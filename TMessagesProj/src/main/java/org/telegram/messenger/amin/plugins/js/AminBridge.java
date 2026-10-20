package org.telegram.messenger.amin.plugins.js;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.amin.google.GoogleAuthManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The `amin` object exposed to JS plugins — the ONLY bridge between a
 * plugin and the app, exactly as documented in
 * docs/PLUGIN-MASTER-PROMPT.md:
 *
 *   amin.log(text)                       plugin log (hub → plugin → Log)
 *   amin.toast(text)                     small on-screen popup
 *   amin.storage.get(key, defaultValue)  per-plugin saved values
 *   amin.storage.set(key, value)
 *   amin.send(chatId, text)              send a message AS THE USER
 *   amin.reply(msg, text)                send into the msg's chat
 *   amin.httpGet(url)                    response text, or null (blocking)
 *   amin.httpPost(url, body, headers)    response text (the HTTP error body
 *                                        on an error status), or null (blocking)
 *
 * Plugins whose manifest declares the `google` capability get the
 * {@link WithGoogle} variant, which adds:
 *
 *   amin.google.isSignedIn()             a Google account is connected
 *   amin.google.getAccessToken(fn)       ASYNC: fn(tokenOrNull)
 *   amin.google.signIn(fn)               ASYNC: fn(true/false)
 *
 * For every other plugin the `google` property does not exist at
 * all (undefined in JS) — the gate is structural, not a runtime
 * check plugin code could talk its way around.
 *
 * Plugins whose manifest declares the `file` capability get the
 * {@link WithFile} variant (or {@link WithGoogleFile} when they
 * declare BOTH `file` and `google`), which adds (v2.0):
 *
 *   amin.sendFile(url, opts)             true/false, SYNCHRONOUS
 *     opts = { type: "video"|"photo"|"audio"|"file",
 *              caption: string,
 *              chatId: number (optional — defaults to the chat
 *              currently being dispatched, when the manager has
 *              provided one via setCurrentDispatchChatId) }
 *     Downloads the URL on the plugin thread (15 s connect /
 *     60 s read, redirects followed, hard cap 200 MB) into the
 *     app cache, then hands the file to Telegram's own
 *     SendMessagesHelper prepare* pipeline as proper media with
 *     the caption. Returns false on ANY failure (bad URL, HTTP
 *     error, over the cap, no target chat, send hand-off failed);
 *     true means the file was downloaded and handed to the send
 *     pipeline (Telegram uploads it asynchronously from there).
 *
 * For every other plugin `amin.sendFile` does not exist at all
 * (undefined in JS) — same structural gate as `google`: the
 * implementation ({@link #doSendFile}) is protected on the base
 * class, so Rhino never exposes it; only the file variants
 * publish a public sendFile wrapper.
 *
 * The Rhino ClassShutter in JsPluginManager whitelists ONLY this
 * class family (bridge, Storage, WithGoogle, GoogleAccess,
 * WithFile, WithGoogleFile), so plugin code cannot reach any
 * other Java class. Instances are per-plugin; sends act on the
 * account whose update is currently being dispatched.
 */
public class AminBridge {

    private final String pluginId;
    public final Storage storage;

    /** Chat of the update/command/menu action currently being
     *  dispatched, when JsPluginManager provides one (it sets this
     *  before each hook call — see setCurrentDispatchChatId). Used
     *  only as the default target for amin.sendFile when the
     *  plugin omits opts.chatId; 0 = no context, sendFile then
     *  fails cleanly instead of guessing a chat. Static because
     *  bridge instances are created once per plugin runtime,
     *  before any specific chat is known, and all plugin code
     *  runs on the single plugin thread. */
    private static volatile long sCurrentDispatchChatId;

    public AminBridge(String pluginId) {
        this.pluginId = pluginId;
        this.storage = new Storage(pluginId);
    }

    /** Called by JsPluginManager's dispatchers (same package)
     *  immediately before invoking a plugin hook, with that
     *  hook's chat id. Not reachable from JS: Rhino only exposes
     *  instance members of the bridge object, never statics. */
    public static void setCurrentDispatchChatId(long chatId) {
        sCurrentDispatchChatId = chatId;
    }

    public void log(Object text) {
        JsPluginManager.appendLog(pluginId, text == null ? "null" : String.valueOf(text));
    }

    public void toast(final Object text) {
        final String value = text == null ? "" : String.valueOf(text);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Context context = ApplicationLoader.applicationContext;
                if (context != null) {
                    Toast.makeText(context, value, Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    public void send(Object chatId, Object text) {
        if (chatId == null || text == null) {
            return;
        }
        long peer = toLong(chatId);
        if (peer == 0) {
            return;
        }
        try {
            SendMessagesHelper.getInstance(JsPluginManager.dispatchAccount())
                    .sendMessage(SendMessagesHelper.SendMessageParams.of(String.valueOf(text), peer));
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.send failed: " + t);
        }
    }

    public void reply(Object msg, Object text) {
        if (msg == null || text == null) {
            return;
        }
        long chatId = 0;
        if (msg instanceof Scriptable) {
            Object value = ((Scriptable) msg).get("chatId", (Scriptable) msg);
            if (value != null && value != Scriptable.NOT_FOUND) {
                chatId = toLong(value);
            }
        } else if (msg instanceof Number) {
            chatId = ((Number) msg).longValue();
        }
        if (chatId != 0) {
            send(chatId, text);
        }
    }

    // ------------------------------------------------------------------
    // amin.sendFile — implementation (v2.0). PROTECTED on purpose:
    // Rhino exposes only public members, so the plain bridge (and
    // WithGoogle) have no sendFile at all; the public wrappers live
    // exclusively on WithFile / WithGoogleFile below.
    // ------------------------------------------------------------------

    /** Hard download cap for sendFile: 200 MB. Telegram's own
     *  upload limits still apply inside the send pipeline. */
    private static final long MAX_SEND_FILE_BYTES = 200L * 1024 * 1024;

    /** Downloads urlObject and sends it as media into the target
     *  chat. Runs entirely on the calling (plugin dispatch)
     *  thread for the download; the Telegram prepare* hand-off is
     *  posted to the UI thread, as those APIs require. NEVER
     *  throws: any failure is logged and reported as false. */
    protected final boolean doSendFile(Object urlObject, Object optsObject) {
        File file = null;
        try {
            if (urlObject == null) {
                return false;
            }
            String urlString = String.valueOf(urlObject);
            if (urlString.isEmpty()) {
                return false;
            }
            String type = "file";
            String caption = "";
            long chatId = 0;
            if (optsObject instanceof Scriptable) {
                Scriptable opts = (Scriptable) optsObject;
                Object typeValue = opts.get("type", opts);
                if (typeValue != null && typeValue != Scriptable.NOT_FOUND
                        && !(typeValue instanceof Undefined)) {
                    type = String.valueOf(typeValue).toLowerCase(Locale.US).trim();
                }
                Object captionValue = opts.get("caption", opts);
                if (captionValue != null && captionValue != Scriptable.NOT_FOUND
                        && !(captionValue instanceof Undefined)) {
                    caption = String.valueOf(captionValue);
                }
                Object chatValue = opts.get("chatId", opts);
                if (chatValue != null && chatValue != Scriptable.NOT_FOUND
                        && !(chatValue instanceof Undefined)) {
                    chatId = toLong(chatValue);
                }
            }
            if (!"video".equals(type) && !"photo".equals(type)
                    && !"audio".equals(type) && !"file".equals(type)) {
                type = "file";
            }
            if (chatId == 0) {
                chatId = sCurrentDispatchChatId;
            }
            if (chatId == 0) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.sendFile: no chatId (opts.chatId missing and no dispatch chat context)");
                return false;
            }
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return false;
            }
            final URL url = new URL(urlString);
            String protocol = url.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.sendFile: unsupported protocol " + protocol);
                return false;
            }

            String contentType = null;
            HttpURLConnection connection = null;
            try {
                URL currentUrl = url;
                int redirectCount = 0;
                while (true) {
                connection = (HttpURLConnection) currentUrl.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AminGram");
                connection.setRequestProperty("Accept", "*/*");
                int code = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                if ((code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308)
                        && location != null && redirectCount < 5) {
                    // HttpURLConnection's automatic following does
                    // not cross http<->https; handle the remaining
                    // redirects explicitly, still HTTP(S) only.
                    URL nextUrl = new URL(currentUrl, location);
                    String nextProtocol = nextUrl.getProtocol();
                    if (!"http".equals(nextProtocol) && !"https".equals(nextProtocol)) {
                        JsPluginManager.appendLog(pluginId,
                                "[error] amin.sendFile: redirect to unsupported protocol " + nextProtocol);
                        return false;
                    }
                    connection.disconnect();
                    connection = null;
                    currentUrl = nextUrl;
                    redirectCount++;
                    continue;
                }
                if (code < 200 || code >= 300) {
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.sendFile: HTTP " + code + " for " + urlString);
                    return false;
                }
                int declared = connection.getContentLength();
                if (declared > MAX_SEND_FILE_BYTES) {
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.sendFile: declared size " + declared + " over the 200 MB cap");
                    return false;
                }
                String rawType = connection.getContentType();
                if (rawType != null) {
                    int semi = rawType.indexOf(';');
                    contentType = (semi >= 0 ? rawType.substring(0, semi) : rawType)
                            .trim().toLowerCase(Locale.US);
                    if (contentType.isEmpty()) {
                        contentType = null;
                    }
                }
                String ext = inferSendFileExtension(currentUrl, contentType, type);
                File dir = sendFileCacheDir(context);
                if (dir == null) {
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.sendFile: no usable cache directory");
                    return false;
                }
                sweepOldSendFiles(dir);
                file = new File(dir, "amin_" + pluginId.replaceAll("[^a-z0-9-]", "_")
                        + "_" + System.currentTimeMillis() + "." + ext);
                InputStream in = connection.getInputStream();
                FileOutputStream out = null;
                long total = 0;
                boolean overCap = false;
                try {
                    out = new FileOutputStream(file);
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        total += read;
                        if (total > MAX_SEND_FILE_BYTES) {
                            overCap = true;
                            break;
                        }
                        out.write(buffer, 0, read);
                    }
                } finally {
                    if (out != null) {
                        out.close();
                    }
                    in.close();
                }
                if (overCap) {
                    file.delete();
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.sendFile: download exceeded the 200 MB cap");
                    return false;
                }
                if (total == 0 || !file.exists() || file.length() == 0) {
                    file.delete();
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.sendFile: empty download for " + urlString);
                    return false;
                }
                break;
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            final int account = JsPluginManager.dispatchAccount();
            final AccountInstance accountInstance = AccountInstance.getInstance(account);
            if (accountInstance == null) {
                return false;
            }
            final String path = file.getAbsolutePath();
            final String captionFinal = caption;
            final String typeFinal = type;
            final long peerFinal = chatId;
            final String mimeFinal = mimeForExtension(
                    path.substring(path.lastIndexOf('.') + 1), contentType);
            // The file is deliberately NOT deleted here: Telegram's
            // pipeline uploads it asynchronously from this path.
            // sweepOldSendFiles() reclaims staged files after 24 h,
            // and the whole directory lives in purgeable cache.
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    if ("photo".equals(typeFinal)) {
                        SendMessagesHelper.prepareSendingPhoto(accountInstance,
                                path, null, peerFinal, null, null, null,
                                captionFinal, null, null, null, 0, null,
                                true, 0, 0, null);
                    } else if ("video".equals(typeFinal)) {
                        SendMessagesHelper.prepareSendingVideo(accountInstance,
                                path, null, null, null, peerFinal, null, null,
                                null, null, null, 0, null, true, 0, 0,
                                false, false, captionFinal, null, 0, 0);
                    } else {
                        // "file", and "audio": the document pipeline
                        // builds the TL_document itself and adds a
                        // proper audio attribute (duration/title/
                        // performer) when the file is mp3/m4a/ogg/
                        // flac, so audio arrives as playable audio,
                        // not a bare file.
                        SendMessagesHelper.prepareSendingDocument(accountInstance,
                                path, path, null, captionFinal, mimeFinal, peerFinal,
                                null, null, null, null, null,
                                true, 0, null, null, false);
                    }
                } catch (Throwable t) {
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.sendFile send failed: " + t);
                }
            });
            return true;
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.sendFile failed: " + t);
            if (file != null) {
                try {
                    // This catch only runs before the send hand-off
                    // (the hand-off is immediately followed by
                    // `return true`), so the staged file is never
                    // one Telegram is uploading from here.
                    file.delete();
                } catch (Throwable ignored) {
                }
            }
            return false;
        }
    }

    /** Safe file extension for a staged sendFile download, in
     *  priority order: the URL path's extension, the response
     *  Content-Type, then a per-type default. Sanitized to
     *  lowercase [a-z0-9], at most 5 chars, so a hostile or odd
     *  URL can never smuggle path separators or a weird name
     *  into the cache directory. */
    private static String inferSendFileExtension(URL url, String contentType, String type) {
        String ext = null;
        try {
            String path = url.getPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                String last = slash >= 0 ? path.substring(slash + 1) : path;
                int dot = last.lastIndexOf('.');
                if (dot > 0 && dot < last.length() - 1) {
                    ext = last.substring(dot + 1);
                }
            }
        } catch (Throwable ignored) {
        }
        ext = sanitizeExtension(ext);
        if (ext == null && contentType != null) {
            try {
                ext = sanitizeExtension(MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(contentType));
            } catch (Throwable ignored) {
            }
        }
        if (ext == null) {
            if ("video".equals(type)) {
                ext = "mp4";
            } else if ("photo".equals(type)) {
                ext = "jpg";
            } else if ("audio".equals(type)) {
                ext = "mp3";
            } else {
                ext = "bin";
            }
        }
        return ext;
    }

    private static String sanitizeExtension(String ext) {
        if (ext == null) {
            return null;
        }
        String lower = ext.toLowerCase(Locale.US);
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < lower.length() && clean.length() < 5; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                clean.append(c);
            }
        }
        return clean.length() > 0 ? clean.toString() : null;
    }

    private static String mimeForExtension(String ext, String contentType) {
        try {
            String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(ext.toLowerCase(Locale.US));
            if (mime != null) {
                return mime;
            }
        } catch (Throwable ignored) {
        }
        return contentType;
    }

    /** Staging directory for sendFile downloads: Telegram's own
     *  media cache (FileLoader MEDIA_DIR_CACHE — the same cache
     *  tree Telegram stages outgoing media in, and not flagged
     *  as an internal URI by the document pipeline), falling back
     *  to the app external cache dir, then the internal cache
     *  dir. Returns null when no directory is usable. */
    private static File sendFileCacheDir(Context context) {
        File base = null;
        try {
            base = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
        } catch (Throwable ignored) {
        }
        if (base == null) {
            try {
                base = context.getExternalCacheDir();
            } catch (Throwable ignored) {
            }
        }
        if (base == null) {
            try {
                base = context.getCacheDir();
            } catch (Throwable ignored) {
            }
        }
        if (base == null) {
            return null;
        }
        File dir = new File(base, "amingram_sendfile");
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
        } catch (Throwable ignored) {
        }
        return dir.isDirectory() ? dir : null;
    }

    /** Best-effort reclaim of staged sendFile files older than
     *  24 h (uploads are long done by then). Never throws. */
    private static void sweepOldSendFiles(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            for (File f : files) {
                try {
                    if (f.isFile() && f.lastModified() < cutoff) {
                        f.delete();
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Blocking GET on the plugin dispatch thread. Returns the body
     *  text (capped at 1 MB), or null on any failure. */
    public String httpGet(Object urlObject) {
        if (urlObject == null) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(String.valueOf(urlObject));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AminGram");
            connection.setRequestProperty("Accept", "*/*");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 400) {
                return null;
            }
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > 1024 * 1024) {
                    break;
                }
                out.write(buffer, 0, read);
            }
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.httpGet failed: " + t);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Blocking POST on the plugin dispatch thread, in httpGet's
     *  style: 15 s timeouts, bodies capped at 1 MB. On 2xx the
     *  response body text; on an HTTP error status the ERROR body
     *  text (v1.3.7 — so a caller's error parsing can finally see
     *  the server's real verdict instead of a bare null); null on
     *  transport failure or a bodyless error. The third argument
     *  is a JS object of header name → value pairs; a JSON
     *  Content-Type is assumed when the caller doesn't set one.
     *  Needed for API calls like Gemini's generateContent. */
    public String httpPost(Object urlObject, Object bodyObject, Object headersObject) {
        if (urlObject == null) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(String.valueOf(urlObject));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AminGram");
            boolean hasContentType = false;
            if (headersObject instanceof Scriptable) {
                Scriptable headers = (Scriptable) headersObject;
                for (Object id : headers.getIds()) {
                    if (id == null) {
                        continue;
                    }
                    Object value;
                    String name;
                    if (id instanceof Number) {
                        value = headers.get(((Number) id).intValue(), headers);
                        name = String.valueOf(((Number) id).intValue());
                    } else {
                        name = String.valueOf(id);
                        value = headers.get(name, headers);
                    }
                    if (value == null || value == Scriptable.NOT_FOUND
                            || value instanceof org.mozilla.javascript.Undefined) {
                        continue;
                    }
                    connection.setRequestProperty(name, String.valueOf(value));
                    if ("content-type".equalsIgnoreCase(name)) {
                        hasContentType = true;
                    }
                }
            }
            if (!hasContentType) {
                connection.setRequestProperty("Content-Type",
                        "application/json; charset=utf-8");
            }
            byte[] body = bodyObject == null ? new byte[0]
                    : String.valueOf(bodyObject).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            out.write(body);
            out.close();
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                return readCapped(connection.getInputStream());
            }
            // Non-2xx (v1.3.7): return the ERROR body instead of
            // null, so a caller's error parsing finally sees the
            // server's real verdict (e.g. Google's JSON error)
            // instead of a bare "did not answer". A bodyless error
            // still returns null, and any failure while reading
            // lands in the catch below -> null, exactly as before.
            InputStream err = connection.getErrorStream();
            if (err == null) {
                return null;
            }
            return readCapped(err);
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.httpPost failed: " + t);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Reads a response stream to the end, capped at 1 MB, as
     *  UTF-8 — the same read loop httpPost has always used (the
     *  chunk that crosses the cap is not written). */
    private static String readCapped(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > 1024 * 1024) {
                break;
            }
            result.write(buffer, 0, read);
        }
        in.close();
        return new String(result.toByteArray(), StandardCharsets.UTF_8);
    }

    static long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Per-plugin key/value storage, backed by its own
     *  SharedPreferences file ("amingram_jsstore_<id>"). Values are
     *  strings under the hood; get() converts to the type of the
     *  provided default (bool/number/string), as the master prompt
     *  documents string/number/bool values. */
    public static class Storage {

        private final String prefsName;

        public Storage(String pluginId) {
            this.prefsName = JsPluginManager.storagePrefsName(pluginId);
        }

        public Object get(Object key, Object defaultValue) {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || key == null) {
                return defaultValue;
            }
            String stored = prefs(context).getString(String.valueOf(key), null);
            if (stored == null) {
                return defaultValue;
            }
            if (defaultValue instanceof Boolean) {
                return Boolean.valueOf(stored);
            }
            if (defaultValue instanceof Number) {
                try {
                    return Double.valueOf(stored);
                } catch (Exception e) {
                    return defaultValue;
                }
            }
            return stored;
        }

        public void set(Object key, Object value) {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || key == null) {
                return;
            }
            SharedPreferences.Editor edit = prefs(context).edit();
            if (value == null) {
                edit.remove(String.valueOf(key));
            } else if (value instanceof Boolean) {
                edit.putString(String.valueOf(key), ((Boolean) value) ? "true" : "false");
            } else if (value instanceof Number) {
                double d = ((Number) value).doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                    edit.putString(String.valueOf(key), String.valueOf((long) d));
                } else {
                    edit.putString(String.valueOf(key), String.valueOf(d));
                }
            } else {
                edit.putString(String.valueOf(key), String.valueOf(value));
            }
            edit.apply();
        }

        private SharedPreferences prefs(Context context) {
            return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        }
    }

    /** The `amin.google` surface (v1.3.5) — present ONLY on the
     *  {@link WithGoogle} bridge variant, i.e. only for plugins
     *  whose manifest declares the `google` capability. Talks to
     *  GoogleAuthManager (Play Services authorization; the token
     *  cache lives encrypted on-device). Both token operations
     *  are ASYNC: the callback fires later, on the plugin thread,
     *  via JsPluginManager.invokePluginCallback — never inline,
     *  so plugin code can rely on one ordering. */
    public static class GoogleAccess {

        private final String pluginId;

        public GoogleAccess(String pluginId) {
            this.pluginId = pluginId;
        }

        /** True while a Google grant is cached on this device. */
        public boolean isSignedIn() {
            try {
                Context context = ApplicationLoader.applicationContext;
                return context != null && GoogleAuthManager.isSignedIn(context);
            } catch (Throwable t) {
                return false;
            }
        }

        /** fn(tokenOrNull): a valid OAuth access token for the
         *  connected Google account (cache, or silent refresh), or
         *  null when not connected / interactive sign-in needed. */
        public void getAccessToken(Object callback) {
            if (!(callback instanceof Function)) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.google.getAccessToken needs a callback function");
                return;
            }
            final Function fn = (Function) callback;
            try {
                Context context = ApplicationLoader.applicationContext;
                if (context == null) {
                    JsPluginManager.invokePluginCallback(pluginId, fn, null);
                    return;
                }
                GoogleAuthManager.getValidToken(context, (token, error) -> {
                    if (token == null && error != null) {
                        JsPluginManager.appendLog(pluginId, "[google] " + error);
                    }
                    JsPluginManager.invokePluginCallback(pluginId, fn, token);
                });
            } catch (Throwable t) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.google.getAccessToken failed: " + t);
                JsPluginManager.invokePluginCallback(pluginId, fn, null);
            }
        }

        /** fn(true/false): opens the native Google account
         *  picker / consent flow (via GoogleAuthActivity) and
         *  reports whether a grant now exists. */
        public void signIn(Object callback) {
            if (!(callback instanceof Function)) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.google.signIn needs a callback function");
                return;
            }
            final Function fn = (Function) callback;
            try {
                Context context = ApplicationLoader.applicationContext;
                if (context == null) {
                    JsPluginManager.invokePluginCallback(pluginId, fn, Boolean.FALSE);
                    return;
                }
                GoogleAuthManager.startInteractiveAuth(context, (success, error) -> {
                    if (!success && error != null) {
                        JsPluginManager.appendLog(pluginId, "[google] sign-in: " + error);
                    }
                    JsPluginManager.invokePluginCallback(pluginId, fn,
                            success ? Boolean.TRUE : Boolean.FALSE);
                });
            } catch (Throwable t) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.google.signIn failed: " + t);
                JsPluginManager.invokePluginCallback(pluginId, fn, Boolean.FALSE);
            }
        }
    }

    /** The bridge handed to plugins whose manifest declares the
     *  `google` capability: the plain bridge PLUS the amin.google
     *  surface. Every other plugin gets the plain AminBridge, on
     *  which `amin.google` does not exist (undefined in JS). */
    public static class WithGoogle extends AminBridge {

        public final GoogleAccess google;

        public WithGoogle(String pluginId) {
            super(pluginId);
            this.google = new GoogleAccess(pluginId);
        }
    }

    /** The bridge handed to plugins whose manifest declares the
     *  `file` capability (v2.0): the plain bridge PLUS the public
     *  amin.sendFile(url, opts) wrapper. The implementation is
     *  {@link AminBridge#doSendFile} (protected on the base, hence
     *  invisible to Rhino there); this public wrapper is the only
     *  place sendFile becomes reachable from JS. Rhino reaches it
     *  by name via reflection, so patch 0010 keeps this class in
     *  the same way as WithGoogle:
     *    -keep class org.telegram.messenger.amin.plugins.js.AminBridge$WithFile { *; }
     *  and JsPluginManager's ClassShutter whitelists it. */
    public static class WithFile extends AminBridge {

        public WithFile(String pluginId) {
            super(pluginId);
        }

        /** amin.sendFile(url, opts) -> boolean, synchronous.
         *  See the class javadoc for the full contract. */
        public boolean sendFile(Object url, Object opts) {
            return doSendFile(url, opts);
        }
    }

    /** The bridge for plugins declaring BOTH `file` and `google`
     *  (v2.0): amin.google (inherited from WithGoogle) PLUS the
     *  public amin.sendFile wrapper. Like WithFile, it is covered
     *  by an R8 -keep in patch 0010:
     *    -keep class org.telegram.messenger.amin.plugins.js.AminBridge$WithGoogleFile { *; }
     *  and by JsPluginManager's ClassShutter whitelist. */
    public static class WithGoogleFile extends WithGoogle {

        public WithGoogleFile(String pluginId) {
            super(pluginId);
        }

        /** amin.sendFile(url, opts) -> boolean, synchronous.
         *  See the class javadoc for the full contract. */
        public boolean sendFile(Object url, Object opts) {
            return doSendFile(url, opts);
        }
    }
}
