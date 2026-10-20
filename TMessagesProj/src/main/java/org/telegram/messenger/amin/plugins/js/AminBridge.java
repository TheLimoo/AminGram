package org.telegram.messenger.amin.plugins.js;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.amin.google.GoogleAuthManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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
 *   amin.httpPost(url, body, headers)    response text, or null (blocking)
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
 * The Rhino ClassShutter in JsPluginManager whitelists ONLY this
 * class family (bridge, Storage, WithGoogle, GoogleAccess), so
 * plugin code cannot reach any other Java class. Instances are
 * per-plugin; sends act on the account whose update is currently
 * being dispatched.
 */
public class AminBridge {

    private final String pluginId;
    public final Storage storage;

    public AminBridge(String pluginId) {
        this.pluginId = pluginId;
        this.storage = new Storage(pluginId);
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
     *  style: 15 s timeouts, response body capped at 1 MB, body
     *  text or null on any failure (including non-2xx). The third
     *  argument is a JS object of header name → value pairs; a
     *  JSON Content-Type is assumed when the caller doesn't set
     *  one. Needed for API calls like Gemini's generateContent. */
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
            if (code < 200 || code >= 300) {
                return null;
            }
            InputStream in = connection.getInputStream();
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
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.httpPost failed: " + t);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
}
