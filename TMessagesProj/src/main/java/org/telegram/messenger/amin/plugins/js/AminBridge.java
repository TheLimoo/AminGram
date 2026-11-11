package org.telegram.messenger.amin.plugins.js;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.amin.google.GoogleAuthManager;
import org.telegram.messenger.amin.ui.PluginFilePickerActivity;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The `amin` object exposed to JS plugins — the ONLY bridge between a
 * plugin and the app, as documented in docs/PLUGIN-MASTER-PROMPT.md.
 *
 * Phase A & C hardening/additions:
 * - A-01: sCurrentDispatchChatId and sCurrentDispatchReplyToMsg are set
 *   and cleared around every chat-scoped hook invocation.
 * - A-02: Sync httpGet/httpPost run with a hard 30 s wall-clock deadline
 *   and manual redirect limit (<= 5 hops), disconnecting the socket on
 *   timeout so a slow/trickling server cannot stall the plugin thread.
 * - A-05: doSendFile enforces a 120 s wall-clock deadline, a 200 MB
 *   per-plugin staged-bytes quota, and a 1 h cache sweep; also accepts
 *   "sandbox:<name>" paths and adds sendMessageMedia / saveMessageMedia
 *   on the file variants for sticker/media downloading (Phase C.1).
 * - A-14: Bounded ThreadPoolExecutor + per-plugin concurrent async-HTTP cap.
 * - A-15: Cached SharedPreferences in Storage + Storage.setMulti & DbAccess.putMulti.
 * - A-27: 2 MB request body cap on httpPostCore.
 * - A-34: Runtime capability enforcement for `send` and `http` capabilities.
 * - A-35: amin.getMessageRaw(chatId, id) + msg.raw exposure.
 */
public class AminBridge {

    private final String pluginId;
    public final Storage storage;

    public final PlatformInfo platform = new PlatformInfo();
    public final DbAccess db;
    public UiAccess ui;
    public RelayAccess relay;

    private boolean hasSend = true;
    private boolean hasHttp = true;

    private static volatile long sCurrentDispatchChatId;
    private static volatile MessageObject sCurrentDispatchReplyToMsg;

    public AminBridge(String pluginId) {
        this.pluginId = pluginId;
        this.storage = new Storage(pluginId);
        this.db = new DbAccess(pluginId);
    }

    void attachCapabilities(java.util.Collection<String> capabilities) {
        if (capabilities == null) {
            this.hasSend = false;
            this.hasHttp = false;
            return;
        }
        this.hasSend = capabilities.contains("send");
        this.hasHttp = capabilities.contains("http");
        if (capabilities.contains("ui")) {
            this.ui = new UiAccess(pluginId);
        }
        if (capabilities.contains("relay")) {
            this.relay = new RelayAccess(pluginId);
        }
    }

    public static void setCurrentDispatchChatId(long chatId) {
        sCurrentDispatchChatId = chatId;
    }

    public static void setCurrentDispatchReplyToMsg(MessageObject replyToMsg) {
        sCurrentDispatchReplyToMsg = replyToMsg;
    }

    public void log(Object text) {
        JsPluginManager.appendLog(pluginId, text == null ? "null" : String.valueOf(text));
    }

    public void debug(Object text) {
        JsPluginManager.appendLog(pluginId, "[debug] " + (text == null ? "null" : String.valueOf(text)));
    }

    public void info(Object text) {
        JsPluginManager.appendLog(pluginId, "[info] " + (text == null ? "null" : String.valueOf(text)));
    }

    public void warn(Object text) {
        JsPluginManager.appendLog(pluginId, "[warn] " + (text == null ? "null" : String.valueOf(text)));
    }

    public void error(Object text) {
        JsPluginManager.appendLog(pluginId, "[error] " + (text == null ? "null" : String.valueOf(text)));
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
        sendInternal(chatId, text, null);
    }

    private void sendInternal(Object chatId, Object text, MessageObject explicitReplyTo) {
        if (!hasSend) {
            JsPluginManager.appendLog(pluginId,
                    "[warn] amin.send refused: plugin does not declare 'send' in @capabilities");
            return;
        }
        if (chatId == null || text == null) {
            return;
        }
        long peer = toLong(chatId);
        if (peer == 0) {
            return;
        }
        try {
            int account = JsPluginManager.dispatchAccount();
            SendMessagesHelper.SendMessageParams params =
                    SendMessagesHelper.SendMessageParams.of(String.valueOf(text), peer);
            if (explicitReplyTo != null && explicitReplyTo.getDialogId() == peer) {
                params.replyToMsg = explicitReplyTo;
            }
            SendMessagesHelper.getInstance(account).sendMessage(params);
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.send failed: " + t);
        }
    }

    public void reply(Object msg, Object text) {
        if (!hasSend) {
            JsPluginManager.appendLog(pluginId,
                    "[warn] amin.reply refused: plugin does not declare 'send' in @capabilities");
            return;
        }
        if (msg == null || text == null) {
            return;
        }
        long chatId = 0;
        long msgId = 0;
        long replyToId = 0;
        if (msg instanceof Scriptable) {
            Scriptable s = (Scriptable) msg;
            Object value = s.get("chatId", s);
            if (value != null && value != Scriptable.NOT_FOUND) {
                chatId = toLong(value);
            }
            Object idVal = s.get("id", s);
            if (idVal != null && idVal != Scriptable.NOT_FOUND) {
                msgId = toLong(idVal);
            }
            Object repVal = s.get("replyToId", s);
            if (repVal != null && repVal != Scriptable.NOT_FOUND) {
                replyToId = toLong(repVal);
            }
        } else if (msg instanceof Number) {
            chatId = ((Number) msg).longValue();
        }
        if (chatId != 0) {
            MessageObject replyTarget = null;
            int account = JsPluginManager.dispatchAccount();
            if (msgId == 0 && sCurrentDispatchReplyToMsg != null
                    && sCurrentDispatchReplyToMsg.getDialogId() == chatId) {
                replyTarget = sCurrentDispatchReplyToMsg;
            } else if (msgId == 0 && replyToId > 0) {
                replyTarget = resolveMessageObject(account, chatId, replyToId);
            }
            sendInternal(chatId, text, replyTarget);
        }
    }

    // ------------------------------------------------------------------
    // Lazy lookups (v2.1 + Stage 2 raw message lookup)
    // ------------------------------------------------------------------

    public Object getChatInfo(Object chatId) {
        if (chatId == null) {
            return null;
        }
        return JsPluginManager.lookupChatInfo(pluginId, toLong(chatId));
    }

    public Object getUserInfo(Object userId) {
        if (userId == null) {
            return null;
        }
        return JsPluginManager.lookupUserInfo(pluginId, toLong(userId));
    }

    public Object getMessage(Object chatId, Object id) {
        if (chatId == null || id == null) {
            return null;
        }
        return JsPluginManager.lookupMessage(pluginId, toLong(chatId), toLong(id));
    }

    /** Returns the raw server metadata object (msg.raw shape) for a
     *  loaded message, or null when not loaded (Phase A.4). */
    public Object getMessageRaw(Object chatId, Object id) {
        if (chatId == null || id == null) {
            return null;
        }
        return JsPluginManager.lookupMessageRaw(pluginId, toLong(chatId), toLong(id));
    }

    // ------------------------------------------------------------------
    // amin.editMessage / amin.react / amin.deleteMessage / amin.pinMessage
    // ------------------------------------------------------------------

    private static long[] resolveRef(Object ref) {
        if (!(ref instanceof Scriptable)) {
            return null;
        }
        Scriptable obj = (Scriptable) ref;
        Object chatValue = obj.get("chatId", obj);
        Object idValue = obj.get("id", obj);
        if (chatValue == null || chatValue == Scriptable.NOT_FOUND
                || chatValue instanceof Undefined
                || idValue == null || idValue == Scriptable.NOT_FOUND
                || idValue instanceof Undefined) {
            return null;
        }
        long chatId = toLong(chatValue);
        long id = toLong(idValue);
        if (chatId == 0 || id == 0) {
            return null;
        }
        return new long[]{chatId, id};
    }

    static MessageObject resolveMessageObject(int account, long chatId, long id) {
        try {
            if (id <= 0 || id > Integer.MAX_VALUE) {
                return null;
            }
            MessagesController controller = MessagesController.getInstance(account);
            if (controller == null) {
                return null;
            }
            if (chatId != 0) {
                ArrayList<MessageObject> objs = controller.dialogMessage.get(chatId);
                if (objs == null) {
                    objs = controller.dialogMessage.get(-chatId);
                }
                if (objs != null) {
                    for (MessageObject obj : objs) {
                        if (obj != null && obj.getId() == (int) id) {
                            return obj;
                        }
                    }
                }
            }
            MessageObject byId = controller.dialogMessagesByIds.get((int) id);
            if (byId != null && (chatId == 0 || byId.getDialogId() == chatId)) {
                return byId;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    protected final boolean doEditMessage(Object ref, String text) {
        try {
            if (text == null) {
                return false;
            }
            long[] target = resolveRef(ref);
            if (target == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.editMessage: ref has no usable {chatId, id}");
                return false;
            }
            final int account = JsPluginManager.dispatchAccount();
            MessageObject obj = resolveMessageObject(account, target[0], target[1]);
            if (obj == null || obj.messageOwner == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.editMessage: message not loaded (chatId=" + target[0] + ", id=" + target[1] + ")");
                return false;
            }
            TLRPC.Message owner = obj.messageOwner;
            if (!owner.out || !obj.isOutOwner()) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.editMessage refused: message " + target[1] + " in chat " + target[0] + " is not your own");
                return false;
            }
            if (obj.isEphemeral()) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.editMessage refused: ephemeral messages cannot be edited");
                return false;
            }
            final MessagesController controller = MessagesController.getInstance(account);
            TLRPC.InputPeer peer = controller.getInputPeer(obj.getDialogId());
            if (peer == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.editMessage: no input peer for chat " + target[0]);
                return false;
            }
            TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
            req.id = obj.getId();
            req.peer = peer;
            req.message = text;
            req.flags |= 2048;
            req.no_webpage = false;
            if ((owner.flags & 1073741824) != 0) {
                req.quick_reply_shortcut_id = owner.quick_reply_shortcut_id;
                req.flags |= 131072;
            }
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (error == null && response instanceof TLRPC.Updates) {
                    controller.processUpdates((TLRPC.Updates) response, false);
                } else if (error != null) {
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.editMessage failed: " + error.text);
                }
            });
            return true;
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.editMessage failed: " + t);
            return false;
        }
    }

    protected final boolean doReact(Object ref, String emoji) {
        try {
            if (emoji == null || emoji.isEmpty()) {
                return false;
            }
            long[] target = resolveRef(ref);
            if (target == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.react: ref has no usable {chatId, id}");
                return false;
            }
            final int account = JsPluginManager.dispatchAccount();
            MessageObject obj = resolveMessageObject(account, target[0], target[1]);
            if (obj == null || obj.messageOwner == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.react: message not loaded (chatId=" + target[0] + ", id=" + target[1] + ")");
                return false;
            }
            final MessagesController controller = MessagesController.getInstance(account);
            TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
            if (obj.messageOwner.isThreadMessage && obj.messageOwner.fwd_from != null) {
                req.peer = controller.getInputPeer(obj.getFromChatId());
                req.msg_id = obj.messageOwner.fwd_from.saved_from_msg_id;
            } else {
                req.peer = controller.getInputPeer(obj.getDialogId());
                req.msg_id = obj.getId();
            }
            if (req.peer == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.react: no input peer for chat " + target[0]);
                return false;
            }
            TLRPC.TL_reactionEmoji reaction = new TLRPC.TL_reactionEmoji();
            reaction.emoticon = emoji;
            req.reaction.add(reaction);
            req.flags |= 1;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (response != null && response instanceof TLRPC.Updates) {
                    controller.processUpdates((TLRPC.Updates) response, false);
                } else if (error != null) {
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.react failed: " + error.text);
                }
            });
            return true;
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.react failed: " + t);
            return false;
        }
    }

    protected final boolean doDeleteMessage(Object ref, Object optsObject) {
        try {
            long[] target = resolveRef(ref);
            if (target == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.deleteMessage: ref has no usable {chatId, id}");
                return false;
            }
            boolean forEveryone = false;
            if (optsObject instanceof Scriptable) {
                Object value = ((Scriptable) optsObject).get("forEveryone", (Scriptable) optsObject);
                if (value instanceof Boolean) {
                    forEveryone = (Boolean) value;
                } else if (value instanceof Number) {
                    forEveryone = ((Number) value).doubleValue() != 0;
                }
            }
            final int account = JsPluginManager.dispatchAccount();
            MessageObject obj = resolveMessageObject(account, target[0], target[1]);
            if (obj == null || obj.messageOwner == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.deleteMessage: message not loaded (chatId=" + target[0] + ", id=" + target[1] + ")");
                return false;
            }
            if (forEveryone && !obj.isOutOwner()) {
                JsPluginManager.appendLog(pluginId,
                        "[warn] amin.deleteMessage: forEveryone refused for message " + target[1]
                                + " in chat " + target[0] + " (not your own) — deleting locally only");
                forEveryone = false;
            }
            JsPluginManager.appendLog(pluginId,
                    "[act] deleteMessage chatId=" + target[0] + " id=" + target[1]
                            + " forEveryone=" + forEveryone);
            ArrayList<Integer> ids = new ArrayList<>();
            ids.add(obj.getId());
            MessagesController.getInstance(account).deleteMessages(
                    ids, null, null, obj.getDialogId(), 0, forEveryone, 0, false);
            return true;
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.deleteMessage failed: " + t);
            return false;
        }
    }

    protected final boolean doPinMessage(Object ref, Object optsObject) {
        try {
            long[] target = resolveRef(ref);
            if (target == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.pinMessage: ref has no usable {chatId, id}");
                return false;
            }
            boolean notify = false;
            boolean pmOneSide = false;
            if (optsObject instanceof Scriptable) {
                Scriptable opts = (Scriptable) optsObject;
                Object notifyValue = opts.get("notify", opts);
                if (notifyValue instanceof Boolean) {
                    notify = (Boolean) notifyValue;
                }
                Object sideValue = opts.get("pmOneSide", opts);
                if (sideValue instanceof Boolean) {
                    pmOneSide = (Boolean) sideValue;
                }
            }
            final int account = JsPluginManager.dispatchAccount();
            MessageObject obj = resolveMessageObject(account, target[0], target[1]);
            if (obj == null || obj.messageOwner == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.pinMessage: message not loaded (chatId=" + target[0] + ", id=" + target[1] + ")");
                return false;
            }
            final MessagesController controller = MessagesController.getInstance(account);
            TLRPC.InputPeer peer = controller.getInputPeer(obj.getDialogId());
            if (peer == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.pinMessage: no input peer for chat " + target[0]);
                return false;
            }
            JsPluginManager.appendLog(pluginId,
                    "[act] pinMessage chatId=" + target[0] + " id=" + target[1]
                            + " notify=" + notify + " pmOneSide=" + pmOneSide);
            TLRPC.TL_messages_updatePinnedMessage req = new TLRPC.TL_messages_updatePinnedMessage();
            req.peer = peer;
            req.id = obj.getId();
            req.silent = !notify;
            req.pm_oneside = pmOneSide;
            req.unpin = false;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (response != null && response instanceof TLRPC.Updates) {
                    controller.processUpdates((TLRPC.Updates) response, false);
                } else if (error != null) {
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.pinMessage failed: " + error.text);
                }
            });
            return true;
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.pinMessage failed: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // amin.sendFile + amin.sendMessageMedia (file capability only)
    // ------------------------------------------------------------------

    private static final long MAX_SEND_FILE_BYTES = 200L * 1024 * 1024;
    private static final long MAX_SEND_FILE_STAGED_PER_PLUGIN = 200L * 1024 * 1024;
    private static final long MAX_SEND_FILE_WALL_MS = 120_000L;
    private static final long SEND_FILE_SWEEP_AGE_MS = 60L * 60 * 1000L;

    protected final boolean doSendFile(Object urlObject, Object optsObject) {
        File file = null;
        boolean createdTemp = false;
        try {
            if (urlObject == null) {
                return false;
            }
            String urlString = String.valueOf(urlObject).trim();
            if (urlString.isEmpty()) {
                return false;
            }
            String type = "file";
            String caption = "";
            long chatId = 0;
            Map<String, String> headers = null;
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
                Object headersValue = opts.get("headers", opts);
                if (headersValue != null && headersValue != Scriptable.NOT_FOUND
                        && !(headersValue instanceof Undefined)) {
                    headers = snapshotHeaders(headersValue);
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

            File dir = sendFileCacheDir(context);
            if (dir == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.sendFile: no usable cache directory");
                return false;
            }
            sweepOldSendFiles(dir);
            if (stagedBytesForPlugin(dir, pluginId) >= MAX_SEND_FILE_STAGED_PER_PLUGIN) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.sendFile: per-plugin staged cache quota (200 MB) exceeded");
                return false;
            }

            String contentType = null;
            if (urlString.startsWith("sandbox:")) {
                String sandboxName = urlString.substring("sandbox:".length()).trim();
                File sandboxFile = PluginFiles.file(context, pluginId, sandboxName);
                if (sandboxFile == null || !sandboxFile.isFile() || sandboxFile.length() == 0) {
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.sendFile: sandbox file not found: " + sandboxName);
                    return false;
                }
                String ext = sanitizeExtension(sandboxName.contains(".")
                        ? sandboxName.substring(sandboxName.lastIndexOf('.') + 1) : "bin");
                if (ext == null) {
                    ext = "bin";
                }
                file = new File(dir, "amin_" + safePluginPrefix(pluginId)
                        + "_" + System.currentTimeMillis() + "." + ext);
                createdTemp = true;
                if (!copyLocalFile(sandboxFile, file)) {
                    file.delete();
                    return false;
                }
            } else {
                final URL url = new URL(urlString);
                String protocol = url.getProtocol();
                if (!"http".equals(protocol) && !"https".equals(protocol)) {
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.sendFile: unsupported protocol " + protocol);
                    return false;
                }
                long deadline = System.currentTimeMillis() + MAX_SEND_FILE_WALL_MS;
                HttpURLConnection connection = null;
                try {
                    URL currentUrl = url;
                    int redirectCount = 0;
                    while (true) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) {
                            JsPluginManager.appendLog(pluginId,
                                    "[error] amin.sendFile: exceeded 120 s wall-clock deadline");
                            return false;
                        }
                        connection = (HttpURLConnection) currentUrl.openConnection();
                        connection.setRequestMethod("GET");
                        connection.setConnectTimeout((int) Math.min(15000L, remaining));
                        connection.setReadTimeout((int) Math.min(45000L, remaining));
                        connection.setInstanceFollowRedirects(false);
                        connection.setRequestProperty("User-Agent",
                                "Mozilla/5.0 (Linux; Android 13) AminGram");
                        connection.setRequestProperty("Accept", "*/*");
                        if (headers != null) {
                            applyHeaders(connection, headers);
                        }
                        int code = connection.getResponseCode();
                        String location = connection.getHeaderField("Location");
                        if ((code == HttpURLConnection.HTTP_MOVED_PERM
                                || code == HttpURLConnection.HTTP_MOVED_TEMP
                                || code == HttpURLConnection.HTTP_SEE_OTHER
                                || code == 307 || code == 308)
                                && location != null && redirectCount < 5) {
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
                        long declared = connection.getContentLengthLong();
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
                        file = new File(dir, "amin_" + safePluginPrefix(pluginId)
                                + "_" + System.currentTimeMillis() + "." + ext);
                        createdTemp = true;
                        InputStream in = connection.getInputStream();
                        FileOutputStream out = null;
                        long total = 0;
                        boolean overCap = false;
                        boolean timedOut = false;
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
                                if (System.currentTimeMillis() > deadline) {
                                    timedOut = true;
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
                        if (overCap || timedOut) {
                            file.delete();
                            JsPluginManager.appendLog(pluginId, overCap
                                    ? "[error] amin.sendFile: download exceeded the 200 MB cap"
                                    : "[error] amin.sendFile: download exceeded the 120 s deadline");
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
            }

            return dispatchPreparedMediaFile(file, type, caption, chatId, contentType);
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.sendFile failed: " + t);
            if (file != null && createdTemp) {
                try {
                    file.delete();
                } catch (Throwable ignored) {
                }
            }
            return false;
        }
    }

    /** Exports/sends the media of an already-loaded message (e.g. a
     *  sticker, custom emoji, document, or photo) into the target chat
     *  as a downloadable file/photo (Phase C.1 Sticker Downloader).
     *  opts = { chatId, caption, format: "png"|"webp"|"raw", asPhoto: bool, saveToSandbox: "filename" } */
    protected final boolean doSendMessageMedia(Object ref, Object optsObject) {
        try {
            long[] target = resolveRef(ref);
            if (target == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.sendMessageMedia: ref has no usable {chatId, id}");
                return false;
            }
            final int account = JsPluginManager.dispatchAccount();
            MessageObject obj = resolveMessageObject(account, target[0], target[1]);
            if (obj == null || obj.messageOwner == null || obj.messageOwner.media == null) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.sendMessageMedia: message or media not loaded");
                return false;
            }
            long destChatId = target[0];
            String caption = "";
            String format = "auto";
            boolean asPhoto = false;
            String sandboxName = null;
            if (optsObject instanceof Scriptable) {
                Scriptable opts = (Scriptable) optsObject;
                Object cVal = opts.get("chatId", opts);
                if (cVal != null && cVal != Scriptable.NOT_FOUND && !(cVal instanceof Undefined)) {
                    long parsed = toLong(cVal);
                    if (parsed != 0) {
                        destChatId = parsed;
                    }
                }
                Object capVal = opts.get("caption", opts);
                if (capVal != null && capVal != Scriptable.NOT_FOUND && !(capVal instanceof Undefined)) {
                    caption = String.valueOf(capVal);
                }
                Object fmtVal = opts.get("format", opts);
                if (fmtVal != null && fmtVal != Scriptable.NOT_FOUND && !(fmtVal instanceof Undefined)) {
                    format = String.valueOf(fmtVal).toLowerCase(Locale.US).trim();
                }
                Object photoVal = opts.get("asPhoto", opts);
                if (photoVal instanceof Boolean) {
                    asPhoto = (Boolean) photoVal;
                }
                Object sbVal = opts.get("saveToSandbox", opts);
                if (sbVal instanceof String && !((String) sbVal).isEmpty()) {
                    sandboxName = (String) sbVal;
                }
            }
            if (destChatId == 0) {
                destChatId = sCurrentDispatchChatId;
            }
            Context context = ApplicationLoader.applicationContext;
            if (context == null || destChatId == 0) {
                return false;
            }
            File sourceFile = ensureMessageMediaDownloaded(account, obj, 12000L);
            if (sourceFile == null || !sourceFile.isFile() || sourceFile.length() == 0) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.sendMessageMedia: media file is not cached on disk yet — tap/view it once first");
                return false;
            }
            File dir = sendFileCacheDir(context);
            if (dir == null) {
                return false;
            }
            sweepOldSendFiles(dir);
            String srcName = sourceFile.getName().toLowerCase(Locale.US);
            String baseName = "sticker_" + obj.getId();
            TLRPC.Document doc = obj.getDocument();
            String mime = doc != null && doc.mime_type != null ? doc.mime_type.toLowerCase(Locale.US) : "";
            boolean isTgs = "application/x-tgsticker".equals(mime) || srcName.endsWith(".tgs");
            boolean isWebm = "video/webm".equals(mime) || srcName.endsWith(".webm");
            boolean wantPng = ("png".equals(format) || "auto".equals(format)) && !isTgs && !isWebm;

            File staged;
            String contentType;
            if (wantPng) {
                Bitmap bmp = BitmapFactory.decodeFile(sourceFile.getAbsolutePath());
                if (bmp != null) {
                    staged = new File(dir, "amin_" + safePluginPrefix(pluginId) + "_"
                            + baseName + "_" + System.currentTimeMillis() + ".png");
                    FileOutputStream fos = new FileOutputStream(staged);
                    try {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    } finally {
                        fos.close();
                    }
                    contentType = "image/png";
                } else {
                    String ext = isTgs ? "tgs" : (isWebm ? "webm" : "webp");
                    staged = new File(dir, "amin_" + safePluginPrefix(pluginId) + "_"
                            + baseName + "_" + System.currentTimeMillis() + "." + ext);
                    if (!copyLocalFile(sourceFile, staged)) {
                        return false;
                    }
                    contentType = mime.isEmpty() ? "image/webp" : mime;
                }
            } else {
                String ext = isTgs ? "tgs" : (isWebm ? "webm" : "webp");
                if (doc != null) {
                    String fn = obj.getFileName();
                    if (fn != null && fn.contains(".")) {
                        String cand = sanitizeExtension(fn.substring(fn.lastIndexOf('.') + 1));
                        if (cand != null) {
                            ext = cand;
                        }
                    }
                } else if (obj.isPhoto()) {
                    ext = "jpg";
                }
                staged = new File(dir, "amin_" + safePluginPrefix(pluginId) + "_"
                        + baseName + "_" + System.currentTimeMillis() + "." + ext);
                if (!copyLocalFile(sourceFile, staged)) {
                    return false;
                }
                contentType = !mime.isEmpty() ? mime : mimeForExtension(ext, "application/octet-stream");
            }

            if (sandboxName != null && PluginFiles.validName(sandboxName)) {
                FileInputStream sin = null;
                try {
                    sin = new FileInputStream(staged);
                    PluginFiles.copyInto(context, pluginId, sandboxName, sin, staged.length());
                } catch (Throwable ignored) {
                } finally {
                    if (sin != null) {
                        try {
                            sin.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            return dispatchPreparedMediaFile(staged, asPhoto ? "photo" : "file",
                    caption, destChatId, contentType);
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.sendMessageMedia failed: " + t);
            return false;
        }
    }

    private static File ensureMessageMediaDownloaded(int account, MessageObject obj, long maxWaitMs) {
        try {
            FileLoader loader = FileLoader.getInstance(account);
            File path = loader.getPathToMessage(obj.messageOwner);
            if (path != null && path.isFile() && path.length() > 0) {
                return path;
            }
            TLRPC.Document doc = obj.getDocument();
            if (doc != null) {
                File attachPath = loader.getPathToAttach(doc, true);
                if (attachPath != null && attachPath.isFile() && attachPath.length() > 0) {
                    return attachPath;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        loader.loadFile(doc, obj, FileLoader.PRIORITY_HIGH, 1);
                    } catch (Throwable ignored) {
                    }
                });
            } else if (obj.getPhoto() != null && obj.getPhoto().sizes != null) {
                final TLRPC.Photo photo = obj.getPhoto();
                final TLRPC.PhotoSize best = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
                if (best != null) {
                    File attachPath = loader.getPathToAttach(best, true);
                    if (attachPath != null && attachPath.isFile() && attachPath.length() > 0) {
                        return attachPath;
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        try {
                            loader.loadFile(ImageLocation.getForPhoto(best, photo), obj, "jpg",
                                    FileLoader.PRIORITY_HIGH, 1);
                        } catch (Throwable ignored) {
                        }
                    });
                }
            }
            long deadline = System.currentTimeMillis() + maxWaitMs;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(150);
                if (path != null && path.isFile() && path.length() > 0) {
                    return path;
                }
                if (doc != null) {
                    File attachPath = loader.getPathToAttach(doc, true);
                    if (attachPath != null && attachPath.isFile() && attachPath.length() > 0) {
                        return attachPath;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean dispatchPreparedMediaFile(File file, String type, String caption,
                                              long chatId, String contentType) {
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
    }

    private static boolean copyLocalFile(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return dst.isFile() && dst.length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String safePluginPrefix(String pluginId) {
        return pluginId == null ? "plugin" : pluginId.replaceAll("[^a-z0-9-]", "_");
    }

    private static long stagedBytesForPlugin(File dir, String pluginId) {
        long total = 0;
        try {
            String prefix = "amin_" + safePluginPrefix(pluginId) + "_";
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().startsWith(prefix)) {
                        total += f.length();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return total;
    }

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

    /** Reclaims staged sendFile files older than 1 h (A-05). */
    private static void sweepOldSendFiles(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            long cutoff = System.currentTimeMillis() - SEND_FILE_SWEEP_AGE_MS;
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

    // ------------------------------------------------------------------
    // HTTP — sync + async (A-02, A-14, A-27, A-34)
    // ------------------------------------------------------------------

    private static final long SYNC_HTTP_DEADLINE_MS = 30_000L;
    private static final int MAX_HTTP_REDIRECTS = 5;
    private static final int MAX_HTTP_POST_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_INFLIGHT_HTTP_PER_PLUGIN = 8;
    private static final Map<String, AtomicInteger> INFLIGHT_HTTP = new ConcurrentHashMap<>();

    private static final class HttpResult {
        final int status;
        final String body;

        HttpResult(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** Bounded pools for async HTTP and sync-deadline HTTP (A-02, A-14). */
    private static final class HttpExecutorHolder {
        static final ExecutorService INSTANCE = createPool("AminGramHttp", 2, 8, 64);
        static final ExecutorService SYNC_INSTANCE = createPool("AminGramSyncHttp", 1, 4, 16);

        private static ExecutorService createPool(String name, int core, int max, int queueCap) {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(
                    core, max, 60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(queueCap),
                    runnable -> {
                        Thread thread = new Thread(runnable, name);
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            pool.allowCoreThreadTimeOut(true);
            return pool;
        }
    }

    private static boolean applyHeaders(HttpURLConnection connection, Object headersObject) {
        boolean hasContentType = false;
        if (headersObject instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) headersObject).entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String name = String.valueOf(entry.getKey());
                connection.setRequestProperty(name, String.valueOf(entry.getValue()));
                if ("content-type".equalsIgnoreCase(name)) {
                    hasContentType = true;
                }
            }
            return hasContentType;
        }
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
        return hasContentType;
    }

    private static Map<String, String> snapshotHeaders(Object headersObject) {
        if (!(headersObject instanceof Scriptable)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
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
            out.put(name, String.valueOf(value));
        }
        return out;
    }

    private static HttpResult httpGetCore(String pluginId, String urlString, Object headersObject,
                                          AtomicReference<HttpURLConnection> activeConnRef) {
        HttpURLConnection connection = null;
        long deadline = System.currentTimeMillis() + SYNC_HTTP_DEADLINE_MS;
        try {
            URL currentUrl = new URL(urlString);
            int redirects = 0;
            while (true) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) {
                    JsPluginManager.appendLog(pluginId, "[error] amin.httpGet timed out (30s deadline)");
                    return new HttpResult(0, null);
                }
                connection = (HttpURLConnection) currentUrl.openConnection();
                if (activeConnRef != null) {
                    activeConnRef.set(connection);
                }
                connection.setRequestMethod("GET");
                connection.setConnectTimeout((int) Math.min(15000L, rem));
                connection.setReadTimeout((int) Math.min(15000L, rem));
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AminGram");
                connection.setRequestProperty("Accept", "*/*");
                applyHeaders(connection, headersObject);
                int code = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                if ((code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308)
                        && location != null && redirects < MAX_HTTP_REDIRECTS) {
                    URL nextUrl = new URL(currentUrl, location);
                    connection.disconnect();
                    connection = null;
                    currentUrl = nextUrl;
                    redirects++;
                    continue;
                }
                if (code < 200 || code >= 400) {
                    return new HttpResult(code, null);
                }
                return new HttpResult(code, readCapped(connection.getInputStream(), deadline));
            }
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.httpGet failed: " + t);
            return new HttpResult(0, null);
        } finally {
            if (activeConnRef != null) {
                activeConnRef.set(null);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpResult httpPostCore(String pluginId, String urlString, String bodyString,
                                           Object headersObject,
                                           AtomicReference<HttpURLConnection> activeConnRef) {
        HttpURLConnection connection = null;
        long deadline = System.currentTimeMillis() + SYNC_HTTP_DEADLINE_MS;
        try {
            byte[] body = bodyString == null ? new byte[0]
                    : bodyString.getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_HTTP_POST_BODY_BYTES) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.httpPost refused: request body exceeds 2 MB cap (" + body.length + " bytes)");
                return new HttpResult(0, null);
            }
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            if (activeConnRef != null) {
                activeConnRef.set(connection);
            }
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AminGram");
            boolean hasContentType = applyHeaders(connection, headersObject);
            if (!hasContentType) {
                connection.setRequestProperty("Content-Type",
                        "application/json; charset=utf-8");
            }
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            out.write(body);
            out.close();
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                return new HttpResult(code, readCapped(connection.getInputStream(), deadline));
            }
            InputStream err = connection.getErrorStream();
            if (err == null) {
                return new HttpResult(code, null);
            }
            return new HttpResult(code, readCapped(err, deadline));
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.httpPost failed: " + t);
            return new HttpResult(0, null);
        } finally {
            if (activeConnRef != null) {
                activeConnRef.set(null);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public String httpGet(Object urlObject) {
        return httpGet(urlObject, null);
    }

    public String httpGet(Object urlObject, Object headersObject) {
        if (!hasHttp) {
            JsPluginManager.appendLog(pluginId,
                    "[warn] amin.httpGet refused: plugin does not declare 'http' in @capabilities");
            return null;
        }
        if (urlObject == null) {
            return null;
        }
        final String urlString = String.valueOf(urlObject);
        final Map<String, String> headers = snapshotHeaders(headersObject);
        final AtomicReference<HttpURLConnection> connRef = new AtomicReference<>();
        Future<HttpResult> future = null;
        try {
            future = HttpExecutorHolder.SYNC_INSTANCE.submit(
                    () -> httpGetCore(pluginId, urlString, headers, connRef));
            HttpResult result = future.get(SYNC_HTTP_DEADLINE_MS, TimeUnit.MILLISECONDS);
            return result != null && result.status >= 200 && result.status < 400 ? result.body : null;
        } catch (TimeoutException te) {
            HttpURLConnection c = connRef.get();
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
            if (future != null) {
                future.cancel(true);
            }
            JsPluginManager.appendLog(pluginId, "[error] amin.httpGet timed out after 30 s");
            return null;
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.httpGet failed: " + t);
            return null;
        }
    }

    public String httpPost(Object urlObject, Object bodyObject, Object headersObject) {
        if (!hasHttp) {
            JsPluginManager.appendLog(pluginId,
                    "[warn] amin.httpPost refused: plugin does not declare 'http' in @capabilities");
            return null;
        }
        if (urlObject == null) {
            return null;
        }
        final String urlString = String.valueOf(urlObject);
        final String bodyString = bodyObject == null ? null : String.valueOf(bodyObject);
        final Map<String, String> headers = snapshotHeaders(headersObject);
        final AtomicReference<HttpURLConnection> connRef = new AtomicReference<>();
        Future<HttpResult> future = null;
        try {
            future = HttpExecutorHolder.SYNC_INSTANCE.submit(
                    () -> httpPostCore(pluginId, urlString, bodyString, headers, connRef));
            HttpResult result = future.get(SYNC_HTTP_DEADLINE_MS, TimeUnit.MILLISECONDS);
            return result == null || result.status == 0 ? null : result.body;
        } catch (TimeoutException te) {
            HttpURLConnection c = connRef.get();
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
            if (future != null) {
                future.cancel(true);
            }
            JsPluginManager.appendLog(pluginId, "[error] amin.httpPost timed out after 30 s");
            return null;
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.httpPost failed: " + t);
            return null;
        }
    }

    private boolean acquireAsyncHttpSlot() {
        AtomicInteger counter = INFLIGHT_HTTP.get(pluginId);
        if (counter == null) {
            AtomicInteger created = new AtomicInteger(0);
            AtomicInteger existing = INFLIGHT_HTTP.putIfAbsent(pluginId, created);
            counter = existing != null ? existing : created;
        }
        while (true) {
            int cur = counter.get();
            if (cur >= MAX_INFLIGHT_HTTP_PER_PLUGIN) {
                return false;
            }
            if (counter.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    private void releaseAsyncHttpSlot() {
        AtomicInteger counter = INFLIGHT_HTTP.get(pluginId);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    public void httpGetAsync(Object urlObject, Object headersObject, Object callback) {
        if (!(callback instanceof Function)) {
            JsPluginManager.appendLog(pluginId,
                    "[error] amin.httpGetAsync needs a callback function");
            return;
        }
        final Function fn = (Function) callback;
        final int account = JsPluginManager.dispatchAccount();
        if (!hasHttp) {
            JsPluginManager.appendLog(pluginId,
                    "[warn] amin.httpGetAsync refused: plugin does not declare 'http' in @capabilities");
            deliverHttpResult(fn, new HttpResult(0, null), account);
            return;
        }
        if (!acquireAsyncHttpSlot()) {
            JsPluginManager.appendLog(pluginId,
                    "[warn] amin.httpGetAsync refused: over concurrent HTTP cap (" + MAX_INFLIGHT_HTTP_PER_PLUGIN + ")");
            deliverHttpResult(fn, new HttpResult(0, null), account);
            return;
        }
        final String urlString = urlObject == null ? null : String.valueOf(urlObject);
        final Map<String, String> headers = snapshotHeaders(headersObject);
        try {
            HttpExecutorHolder.INSTANCE.execute(() -> {
                try {
                    HttpResult result = urlString == null
                            ? new HttpResult(0, null)
                            : httpGetCore(pluginId, urlString, headers, null);
                    deliverHttpResult(fn, result, account);
                } finally {
                    releaseAsyncHttpSlot();
                }
            });
        } catch (Throwable t) {
            releaseAsyncHttpSlot();
            JsPluginManager.appendLog(pluginId, "[error] amin.httpGetAsync queue full: " + t);
            deliverHttpResult(fn, new HttpResult(0, null), account);
        }
    }

    public void httpPostAsync(Object urlObject, Object bodyObject, Object headersObject, Object callback) {
        if (!(callback instanceof Function)) {
            JsPluginManager.appendLog(pluginId,
                    "[error] amin.httpPostAsync needs a callback function");
            return;
        }
        final Function fn = (Function) callback;
        final int account = JsPluginManager.dispatchAccount();
        if (!hasHttp) {
            JsPluginManager.appendLog(pluginId,
                    "[warn] amin.httpPostAsync refused: plugin does not declare 'http' in @capabilities");
            deliverHttpResult(fn, new HttpResult(0, null), account);
            return;
        }
        if (!acquireAsyncHttpSlot()) {
            JsPluginManager.appendLog(pluginId,
                    "[warn] amin.httpPostAsync refused: over concurrent HTTP cap (" + MAX_INFLIGHT_HTTP_PER_PLUGIN + ")");
            deliverHttpResult(fn, new HttpResult(0, null), account);
            return;
        }
        final String urlString = urlObject == null ? null : String.valueOf(urlObject);
        final String bodyString = bodyObject == null ? null : String.valueOf(bodyObject);
        final Map<String, String> headers = snapshotHeaders(headersObject);
        try {
            HttpExecutorHolder.INSTANCE.execute(() -> {
                try {
                    HttpResult result = urlString == null
                            ? new HttpResult(0, null)
                            : httpPostCore(pluginId, urlString, bodyString, headers, null);
                    deliverHttpResult(fn, result, account);
                } finally {
                    releaseAsyncHttpSlot();
                }
            });
        } catch (Throwable t) {
            releaseAsyncHttpSlot();
            JsPluginManager.appendLog(pluginId, "[error] amin.httpPostAsync queue full: " + t);
            deliverHttpResult(fn, new HttpResult(0, null), account);
        }
    }

    private void deliverHttpResult(final Function fn, final HttpResult result, final int account) {
        try {
            JsPluginManager.invokePluginCallbackWithArg(pluginId, fn, (cx, scope) -> {
                Scriptable value = cx.newObject(scope);
                value.put("status", value, Double.valueOf(result.status));
                value.put("body", value, result.body);
                return value;
            }, account);
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin http async delivery failed: " + t);
        }
    }

    private static String readCapped(InputStream in, long deadlineMs) throws java.io.IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = in.read(buffer)) > 0) {
            if (Thread.currentThread().isInterrupted()
                    || (deadlineMs > 0 && System.currentTimeMillis() > deadlineMs)) {
                in.close();
                throw new java.io.IOException("HTTP stream read deadline exceeded");
            }
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

    // ------------------------------------------------------------------
    // JSON conversion helpers
    // ------------------------------------------------------------------

    public static String jsToJson(Object value) {
        if (value == null || value instanceof Undefined || value instanceof Function) {
            return null;
        }
        try {
            Object converted = toJsonValue(value, 0);
            if (converted == null) {
                return null;
            }
            if (converted instanceof String) {
                return JSONObject.quote((String) converted);
            }
            return converted.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object toJsonValue(Object value, int depth) throws Exception {
        if (value == null || value instanceof Undefined) {
            return JSONObject.NULL;
        }
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return JSONObject.NULL;
            }
            if (d == Math.floor(d) && Math.abs(d) < 9.0e15) {
                return Long.valueOf((long) d);
            }
            return Double.valueOf(d);
        }
        if (depth > 32) {
            return JSONObject.NULL;
        }
        if (value instanceof NativeArray) {
            NativeArray array = (NativeArray) value;
            JSONArray out = new JSONArray();
            long length = array.getLength();
            for (long i = 0; i < length; i++) {
                Object element = array.get((int) i, array);
                if (element == null || element == Scriptable.NOT_FOUND
                        || element instanceof Function) {
                    out.put(JSONObject.NULL);
                } else {
                    out.put(toJsonValue(element, depth + 1));
                }
            }
            return out;
        }
        if (value instanceof Scriptable) {
            Scriptable obj = (Scriptable) value;
            JSONObject out = new JSONObject();
            for (Object id : obj.getIds()) {
                Object v;
                String key;
                if (id instanceof Number) {
                    v = obj.get(((Number) id).intValue(), obj);
                    key = String.valueOf(((Number) id).intValue());
                } else {
                    key = String.valueOf(id);
                    v = obj.get(key, obj);
                }
                if (v == null || v == Scriptable.NOT_FOUND
                        || v instanceof Undefined || v instanceof Function) {
                    continue;
                }
                out.put(key, toJsonValue(v, depth + 1));
            }
            return out;
        }
        return String.valueOf(value);
    }

    public static Object jsonToJs(String json, org.mozilla.javascript.Context cx, org.mozilla.javascript.Scriptable scope) {
        if (json == null || cx == null || scope == null) {
            return null;
        }
        try {
            String trimmed = json.trim();
            if (trimmed.startsWith("{")) {
                return fromJsonValue(new JSONObject(json), cx, scope, 0);
            }
            if (trimmed.startsWith("[")) {
                return fromJsonValue(new JSONArray(json), cx, scope, 0);
            }
            if ("null".equals(trimmed)) {
                return null;
            }
            if ("true".equals(trimmed)) {
                return Boolean.TRUE;
            }
            if ("false".equals(trimmed)) {
                return Boolean.FALSE;
            }
            if (trimmed.startsWith("\"")) {
                Object parsedString = new org.json.JSONTokener(json).nextValue();
                return parsedString instanceof String ? parsedString : null;
            }
            try {
                return Double.valueOf(trimmed);
            } catch (Exception e) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object fromJsonValue(Object value, org.mozilla.javascript.Context cx,
                                        org.mozilla.javascript.Scriptable scope, int depth) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number) {
            return Double.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            Scriptable out = newJsObject(cx, scope);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                out.put(key, out, fromJsonValue(obj.opt(key), cx, scope, depth + 1));
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            Scriptable out = newJsArray(cx, scope, array.length());
            for (int i = 0; i < array.length(); i++) {
                out.put(i, out, fromJsonValue(array.opt(i), cx, scope, depth + 1));
            }
            return out;
        }
        return String.valueOf(value);
    }

    private static Scriptable newJsObject(org.mozilla.javascript.Context cx,
                                          org.mozilla.javascript.Scriptable scope) {
        try {
            Scriptable obj = cx.newObject(scope);
            if (obj != null) {
                return obj;
            }
        } catch (Throwable ignored) {
        }
        return new NativeObject();
    }

    private static Scriptable newJsArray(org.mozilla.javascript.Context cx,
                                         org.mozilla.javascript.Scriptable scope, int length) {
        try {
            Scriptable array = cx.newArray(scope, length);
            if (array != null) {
                return array;
            }
        } catch (Throwable ignored) {
        }
        return new NativeArray(length);
    }

    // ------------------------------------------------------------------
    // Storage (A-15)
    // ------------------------------------------------------------------

    public static class Storage {

        private final String prefsName;
        private final String pluginId;
        private final String keyPrefix;
        private volatile SharedPreferences cachedPrefs;

        public Storage(String pluginId) {
            this(JsPluginManager.storagePrefsName(pluginId), pluginId, "");
        }

        private Storage(String prefsName, String pluginId, String keyPrefix) {
            this.prefsName = prefsName;
            this.pluginId = pluginId;
            this.keyPrefix = keyPrefix;
        }

        public Object get(Object key, Object defaultValue) {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || key == null) {
                return defaultValue;
            }
            String stored = prefs(context).getString(keyPrefix + String.valueOf(key), null);
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
            putEditorValue(edit, keyPrefix + String.valueOf(key), value);
            edit.apply();
        }

        /** Batched write of multiple keys in a single apply() (A-15). */
        public void setMulti(Object entriesObject) {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || !(entriesObject instanceof Scriptable)) {
                return;
            }
            Scriptable map = (Scriptable) entriesObject;
            SharedPreferences.Editor edit = prefs(context).edit();
            for (Object id : map.getIds()) {
                if (id == null) {
                    continue;
                }
                String k = String.valueOf(id);
                Object v = id instanceof Number
                        ? map.get(((Number) id).intValue(), map)
                        : map.get(k, map);
                if (v == Scriptable.NOT_FOUND || v instanceof Undefined || v instanceof Function) {
                    continue;
                }
                putEditorValue(edit, keyPrefix + k, v);
            }
            edit.apply();
        }

        private static void putEditorValue(SharedPreferences.Editor edit, String storedKey, Object value) {
            if (value == null) {
                edit.remove(storedKey);
            } else if (value instanceof Boolean) {
                edit.putString(storedKey, ((Boolean) value) ? "true" : "false");
            } else if (value instanceof Number) {
                double d = ((Number) value).doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                    edit.putString(storedKey, String.valueOf((long) d));
                } else {
                    edit.putString(storedKey, String.valueOf(d));
                }
            } else {
                edit.putString(storedKey, String.valueOf(value));
            }
        }

        public Object getJSON(Object key, Object defaultValue) {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || key == null) {
                return defaultValue;
            }
            String stored = prefs(context).getString(keyPrefix + String.valueOf(key), null);
            if (stored == null) {
                return defaultValue;
            }
            org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.getCurrentContext();
            if (cx == null) {
                return defaultValue;
            }
            Scriptable scope = defaultValue instanceof Scriptable
                    ? ScriptableObject.getTopLevelScope((Scriptable) defaultValue)
                    : new NativeObject();
            Object parsed = jsonToJs(stored, cx, scope);
            return parsed == null ? defaultValue : parsed;
        }

        public void setJSON(Object key, Object value) {
            if (key == null) {
                return;
            }
            if (value == null || value instanceof Undefined || value instanceof Function) {
                JsPluginManager.appendLog(pluginId,
                        "[warn] amin.storage.setJSON: undefined/function value for key " + key + " ignored");
                return;
            }
            String json = jsToJson(value);
            if (json == null) {
                JsPluginManager.appendLog(pluginId,
                        "[warn] amin.storage.setJSON: value for key " + key + " is not JSON-serialisable; ignored");
                return;
            }
            if (json.length() > 64 * 1024) {
                JsPluginManager.appendLog(pluginId,
                        "[warn] amin.storage.setJSON: value for key " + key + " is "
                                + json.length() + " chars (> 64 KB); storing anyway");
            }
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            prefs(context).edit()
                    .putString(keyPrefix + String.valueOf(key), json)
                    .apply();
        }

        public void remove(Object key) {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || key == null) {
                return;
            }
            prefs(context).edit().remove(keyPrefix + String.valueOf(key)).apply();
        }

        public Object keys(Object prefix) {
            java.util.List<String> out = new java.util.ArrayList<>();
            Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                String p = prefix == null || prefix instanceof Undefined
                        ? "" : String.valueOf(prefix);
                String full = keyPrefix + p;
                try {
                    for (String storedKey : prefs(context).getAll().keySet()) {
                        if (storedKey != null && storedKey.startsWith(full)) {
                            out.add(storedKey.substring(keyPrefix.length()));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            Collections.sort(out);
            return new NativeArray(out.toArray());
        }

        public Storage ns(Object name) {
            String namespace = name == null || name instanceof Undefined
                    ? "" : String.valueOf(name);
            if (namespace.isEmpty()) {
                JsPluginManager.appendLog(pluginId,
                        "[warn] amin.storage.ns: empty namespace name; returning the same view");
                return this;
            }
            return new Storage(prefsName, pluginId, keyPrefix + namespace + ":");
        }

        private SharedPreferences prefs(Context context) {
            SharedPreferences p = cachedPrefs;
            if (p == null) {
                p = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
                cachedPrefs = p;
            }
            return p;
        }
    }

    public static class PlatformInfo {

        public int apiVersion = 2;

        private static final Set<String> FEATURES = new HashSet<>(Arrays.asList(
                "rich-msg", "lazy-lookup", "act", "http-async", "storage-json",
                "settings-v2", "lifecycle", "watchdog", "platform",
                "sendFile", "http-headers", "reply-context",
                "db", "files", "pickFile", "ui", "relay",
                "act-delete-pin", "plugin-page", "context-menu",
                "settings-color-file",
                "raw-msg", "sticker-media", "command-suppress", "db-batch"));

        public boolean has(String name) {
            return name != null && FEATURES.contains(name);
        }
    }

    public static class GoogleAccess {

        private final String pluginId;

        public GoogleAccess(String pluginId) {
            this.pluginId = pluginId;
        }

        public boolean isSignedIn() {
            try {
                Context context = ApplicationLoader.applicationContext;
                return context != null && GoogleAuthManager.isSignedIn(context);
            } catch (Throwable t) {
                return false;
            }
        }

        public void getAccessToken(Object callback) {
            if (!(callback instanceof Function)) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.google.getAccessToken needs a callback function");
                return;
            }
            final Function fn = (Function) callback;
            final int account = JsPluginManager.dispatchAccount();
            try {
                Context context = ApplicationLoader.applicationContext;
                if (context == null) {
                    JsPluginManager.invokePluginCallback(pluginId, fn, null, account);
                    return;
                }
                GoogleAuthManager.getValidToken(context, (token, error) -> {
                    if (token == null && error != null) {
                        JsPluginManager.appendLog(pluginId, "[google] " + error);
                    }
                    JsPluginManager.invokePluginCallback(pluginId, fn, token, account);
                });
            } catch (Throwable t) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.google.getAccessToken failed: " + t);
                JsPluginManager.invokePluginCallback(pluginId, fn, null, account);
            }
        }

        public void signIn(Object callback) {
            if (!(callback instanceof Function)) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.google.signIn needs a callback function");
                return;
            }
            final Function fn = (Function) callback;
            final int account = JsPluginManager.dispatchAccount();
            try {
                Context context = ApplicationLoader.applicationContext;
                if (context == null) {
                    JsPluginManager.invokePluginCallback(pluginId, fn, Boolean.FALSE, account);
                    return;
                }
                GoogleAuthManager.startInteractiveAuth(context, (success, error) -> {
                    if (!success && error != null) {
                        JsPluginManager.appendLog(pluginId, "[google] sign-in: " + error);
                    }
                    JsPluginManager.invokePluginCallback(pluginId, fn,
                            success ? Boolean.TRUE : Boolean.FALSE, account);
                });
            } catch (Throwable t) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.google.signIn failed: " + t);
                JsPluginManager.invokePluginCallback(pluginId, fn, Boolean.FALSE, account);
            }
        }

        public String getMode() {
            try {
                Context context = ApplicationLoader.applicationContext;
                if (context == null) {
                    return "";
                }
                String mode = GoogleAuthManager.getApiMode(context);
                return mode == null ? "" : mode;
            } catch (Throwable t) {
                return "";
            }
        }

        public String getLastAuthError() {
            try {
                String error = GoogleAuthManager.getLastAuthError();
                return error == null ? "" : error;
            } catch (Throwable t) {
                return "";
            }
        }
    }

    protected final void doPickFile(Object optsObject, Object callback) {
        if (!(callback instanceof Function)) {
            JsPluginManager.appendLog(pluginId,
                    "[error] amin.pickFile needs a callback function");
            return;
        }
        final Function fn = (Function) callback;
        String accept = "*/*";
        boolean copy = true;
        if (optsObject instanceof Scriptable) {
            Scriptable opts = (Scriptable) optsObject;
            Object acceptValue = opts.get("accept", opts);
            if (acceptValue instanceof String && !((String) acceptValue).isEmpty()) {
                accept = (String) acceptValue;
            }
            Object copyValue = opts.get("copy", opts);
            if (copyValue instanceof Boolean) {
                copy = (Boolean) copyValue;
            } else if (copyValue instanceof Number) {
                copy = ((Number) copyValue).doubleValue() != 0;
            }
        }
        android.content.Context context = ApplicationLoader.applicationContext;
        final int account = JsPluginManager.dispatchAccount();
        if (context == null) {
            JsPluginManager.invokePluginCallback(pluginId, fn, null, account);
            return;
        }
        PluginFilePickerActivity.request(context, accept, copy, pluginId,
                path -> JsPluginManager.invokePluginCallback(pluginId, fn, path, account));
    }

    public static class DbAccess {

        private final String pluginId;

        public DbAccess(String pluginId) {
            this.pluginId = pluginId;
        }

        public Object get(Object ns, Object key) {
            if (ns == null || key == null) {
                return null;
            }
            String json = PluginDb.get(pluginId, String.valueOf(ns), String.valueOf(key));
            if (json == null) {
                return null;
            }
            org.mozilla.javascript.Context cx =
                    org.mozilla.javascript.Context.getCurrentContext();
            if (cx == null) {
                return null;
            }
            return jsonToJs(json, cx, new NativeObject());
        }

        public boolean put(Object ns, Object key, Object value) {
            if (ns == null || key == null || value == null
                    || value instanceof Undefined || value instanceof Function) {
                JsPluginManager.appendLog(pluginId,
                        "[warn] amin.db.put: missing ns/key or unserialisable value; refused");
                return false;
            }
            String json = jsToJson(value);
            if (json == null) {
                JsPluginManager.appendLog(pluginId,
                        "[warn] amin.db.put: value is not JSON-serialisable; refused");
                return false;
            }
            boolean ok = PluginDb.put(pluginId, String.valueOf(ns), String.valueOf(key), json);
            if (!ok) {
                JsPluginManager.appendLog(pluginId,
                        "[warn] amin.db.put refused (ns/key > 128 chars, value > 256 KB, or 8 MB plugin DB quota)");
            }
            return ok;
        }

        /** Batched transactional write of {key: value} into one namespace (A-15). */
        public boolean putMulti(Object ns, Object entriesObject) {
            if (ns == null || !(entriesObject instanceof Scriptable)) {
                return false;
            }
            Scriptable map = (Scriptable) entriesObject;
            Map<String, String> batch = new LinkedHashMap<>();
            for (Object id : map.getIds()) {
                if (id == null) {
                    continue;
                }
                String k = String.valueOf(id);
                Object v = id instanceof Number
                        ? map.get(((Number) id).intValue(), map)
                        : map.get(k, map);
                if (v == null || v == Scriptable.NOT_FOUND || v instanceof Undefined || v instanceof Function) {
                    return false;
                }
                String json = jsToJson(v);
                if (json == null) {
                    return false;
                }
                batch.put(k, json);
            }
            return PluginDb.putMulti(pluginId, String.valueOf(ns), batch);
        }

        public boolean del(Object ns, Object key) {
            if (ns == null || key == null) {
                return false;
            }
            return PluginDb.del(pluginId, String.valueOf(ns), String.valueOf(key));
        }

        public Object keys(Object ns, Object prefix) {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (ns != null) {
                String p = prefix == null || prefix instanceof Undefined
                        ? "" : String.valueOf(prefix);
                out = PluginDb.keys(pluginId, String.valueOf(ns), p);
            }
            return new NativeArray(out.toArray());
        }

        public void clear(Object ns) {
            if (ns != null) {
                PluginDb.clear(pluginId, String.valueOf(ns));
            }
        }
    }

    public static class FileAccess {

        private final String pluginId;

        public FileAccess(String pluginId) {
            this.pluginId = pluginId;
        }

        private android.content.Context context() {
            return ApplicationLoader.applicationContext;
        }

        public String readText(Object name) {
            android.content.Context context = context();
            if (context == null || name == null) {
                return null;
            }
            return PluginFiles.readText(context, pluginId, String.valueOf(name));
        }

        public boolean writeText(Object name, Object text) {
            android.content.Context context = context();
            if (context == null || name == null || text == null) {
                return false;
            }
            boolean ok = PluginFiles.writeText(context, pluginId,
                    String.valueOf(name), String.valueOf(text));
            if (!ok) {
                JsPluginManager.appendLog(pluginId,
                        "[warn] amin.files.writeText refused (bad name or 50 MB quota)");
            }
            return ok;
        }

        public Object list() {
            java.util.List<Object> out = new java.util.ArrayList<>();
            android.content.Context context = context();
            if (context != null) {
                for (PluginFiles.FileInfo info : PluginFiles.list(context, pluginId)) {
                    NativeObject row = new NativeObject();
                    row.put("name", row, info.name);
                    row.put("sizeBytes", row, Double.valueOf(info.sizeBytes));
                    row.put("modifiedAt", row, Double.valueOf(info.modifiedAt));
                    out.add(row);
                }
            }
            return new NativeArray(out.toArray());
        }

        public boolean remove(Object name) {
            android.content.Context context = context();
            return context != null && name != null
                    && PluginFiles.remove(context, pluginId, String.valueOf(name));
        }

        public boolean exists(Object name) {
            android.content.Context context = context();
            return context != null && name != null
                    && PluginFiles.exists(context, pluginId, String.valueOf(name));
        }
    }

    public static class UiAccess {

        private static final String CHANNEL_ID = "amingram_plugin_notifications";
        private static final int NOTIFY_CAP_PER_MINUTE = 5;
        private static final Map<String, java.util.ArrayDeque<Long>> NOTIFY_TIMES =
                new HashMap<>();

        private final String pluginId;

        public UiAccess(String pluginId) {
            this.pluginId = pluginId;
        }

        public boolean clipboardWrite(Object text) {
            if (text == null) {
                return false;
            }
            try {
                AndroidUtilities.addToClipboard(String.valueOf(text));
                return true;
            } catch (Throwable t) {
                JsPluginManager.appendLog(pluginId,
                        "[error] amin.ui.clipboardWrite failed: " + t);
                return false;
            }
        }

        public boolean notify(Object title, Object body, Object optsObject) {
            try {
                android.content.Context context = ApplicationLoader.applicationContext;
                if (context == null || title == null) {
                    return false;
                }
                synchronized (NOTIFY_TIMES) {
                    java.util.ArrayDeque<Long> times = NOTIFY_TIMES.get(pluginId);
                    if (times == null) {
                        times = new java.util.ArrayDeque<>();
                        NOTIFY_TIMES.put(pluginId, times);
                    }
                    long now = System.currentTimeMillis();
                    while (!times.isEmpty() && now - times.peekFirst() > 60000L) {
                        times.pollFirst();
                    }
                    if (times.size() >= NOTIFY_CAP_PER_MINUTE) {
                        JsPluginManager.appendLog(pluginId,
                                "[warn] amin.ui.notify: over the sanity cap ("
                                        + NOTIFY_CAP_PER_MINUTE + "/min) — notification dropped");
                        return false;
                    }
                    times.addLast(now);
                }
                long chatId = 0;
                if (optsObject instanceof Scriptable) {
                    Object value = ((Scriptable) optsObject).get("chatId", (Scriptable) optsObject);
                    if (value instanceof Number) {
                        chatId = ((Number) value).longValue();
                    }
                }
                android.app.NotificationManager manager = (android.app.NotificationManager)
                        context.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                if (manager == null) {
                    return false;
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    android.app.NotificationChannel channel = new android.app.NotificationChannel(
                            CHANNEL_ID, "Plugin notifications",
                            android.app.NotificationManager.IMPORTANCE_DEFAULT);
                    manager.createNotificationChannel(channel);
                }
                android.content.Intent intent = new android.content.Intent(
                        context, org.telegram.ui.LaunchActivity.class);
                intent.setAction("org.telegram.amingram.PLUGIN_NOTIFY_" + pluginId);
                intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                if (chatId != 0) {
                    intent.putExtra("dialogId", chatId);
                }
                android.app.PendingIntent pending = android.app.PendingIntent.getActivity(
                        context, pluginId.hashCode(), intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | android.app.PendingIntent.FLAG_IMMUTABLE);
                android.app.Notification notification =
                        new android.app.Notification.Builder(context, CHANNEL_ID)
                                .setContentTitle(String.valueOf(title))
                                .setContentText(body == null ? "" : String.valueOf(body))
                                .setSmallIcon(org.telegram.messenger.R.drawable.notification)
                                .setAutoCancel(true)
                                .setContentIntent(pending)
                                .build();
                manager.notify(pluginId.hashCode(), notification);
                return true;
            } catch (Throwable t) {
                JsPluginManager.appendLog(pluginId, "[error] amin.ui.notify failed: " + t);
                return false;
            }
        }

        public boolean openUrl(Object urlObject) {
            if (urlObject == null) {
                return false;
            }
            final String url = String.valueOf(urlObject);
            String scheme = url.contains(":") ? url.substring(0, url.indexOf(':')).toLowerCase(Locale.US) : "";
            if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("tg")) {
                JsPluginManager.appendLog(pluginId,
                        "[warn] amin.ui.openUrl refused scheme '" + scheme + "' (http/https/tg only)");
                return false;
            }
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    android.content.Context context = ApplicationLoader.applicationContext;
                    android.content.Intent intent = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Throwable t) {
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.ui.openUrl failed: " + t);
                }
            });
            return true;
        }

        public boolean openChat(Object chatIdObject) {
            if (chatIdObject == null) {
                return false;
            }
            return openDialog(toLong(chatIdObject));
        }

        public boolean openUser(Object userIdObject) {
            if (userIdObject == null) {
                return false;
            }
            return openDialog(toLong(userIdObject));
        }

        private boolean openDialog(final long dialogId) {
            if (dialogId == 0) {
                return false;
            }
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    android.content.Context context = ApplicationLoader.applicationContext;
                    android.content.Intent intent = new android.content.Intent(
                            context, org.telegram.ui.LaunchActivity.class);
                    intent.setAction("org.telegram.amingram.OPEN_DIALOG_" + dialogId);
                    intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    intent.putExtra("dialogId", dialogId);
                    context.startActivity(intent);
                } catch (Throwable t) {
                    JsPluginManager.appendLog(pluginId,
                            "[error] amin.ui.openChat/openUser failed: " + t);
                }
            });
            return true;
        }
    }

    public static class RelayAccess {

        private final String pluginId;

        public RelayAccess(String pluginId) {
            this.pluginId = pluginId;
        }

        public void call(Object targetId, Object method, Object args, Object callback) {
            if (targetId == null || method == null) {
                JsPluginManager.relayCallError(pluginId, callback,
                        "relay.call needs a target plugin id and a method name");
                return;
            }
            String argsJson;
            if (args == null || args instanceof Undefined) {
                argsJson = "[]";
            } else {
                argsJson = jsToJson(args);
                if (argsJson == null) {
                    JsPluginManager.relayCallError(pluginId, callback,
                            "relay.call arguments are not JSON-serialisable");
                    return;
                }
                if (!argsJson.startsWith("[")) {
                    argsJson = "[" + argsJson + "]";
                }
            }
            JsPluginManager.relayCall(pluginId, String.valueOf(targetId),
                    String.valueOf(method), argsJson, callback);
        }

        public void emit(Object topic, Object data) {
            if (topic == null) {
                return;
            }
            String json = data == null || data instanceof Undefined ? "null" : jsToJson(data);
            if (json == null) {
                JsPluginManager.appendLog(pluginId,
                        "[warn] amin.relay.emit: data is not JSON-serialisable; event dropped");
                return;
            }
            JsPluginManager.relayEmit(pluginId, String.valueOf(topic), json);
        }
    }

    public static class WithGoogle extends AminBridge {

        public final GoogleAccess google;

        public WithGoogle(String pluginId) {
            super(pluginId);
            this.google = new GoogleAccess(pluginId);
        }
    }

    public static class WithFile extends AminBridge {

        public final FileAccess files;

        public WithFile(String pluginId) {
            super(pluginId);
            this.files = new FileAccess(pluginId);
        }

        public boolean sendFile(Object url, Object opts) {
            return doSendFile(url, opts);
        }

        public boolean sendMessageMedia(Object ref, Object opts) {
            return doSendMessageMedia(ref, opts);
        }

        public void pickFile(Object opts, Object callback) {
            doPickFile(opts, callback);
        }
    }

    public static class WithGoogleFile extends WithGoogle {

        public final FileAccess files;

        public WithGoogleFile(String pluginId) {
            super(pluginId);
            this.files = new FileAccess(pluginId);
        }

        public boolean sendFile(Object url, Object opts) {
            return doSendFile(url, opts);
        }

        public boolean sendMessageMedia(Object ref, Object opts) {
            return doSendMessageMedia(ref, opts);
        }

        public void pickFile(Object opts, Object callback) {
            doPickFile(opts, callback);
        }
    }

    public static class WithAct extends AminBridge {

        public WithAct(String pluginId) {
            super(pluginId);
        }

        public boolean editMessage(Object ref, Object text) {
            return doEditMessage(ref, text == null ? null : String.valueOf(text));
        }

        public boolean react(Object ref, Object emoji) {
            return doReact(ref, emoji == null ? null : String.valueOf(emoji));
        }

        public boolean deleteMessage(Object ref, Object opts) {
            return doDeleteMessage(ref, opts);
        }

        public boolean pinMessage(Object ref, Object opts) {
            return doPinMessage(ref, opts);
        }
    }

    public static class WithGoogleAct extends WithGoogle {

        public WithGoogleAct(String pluginId) {
            super(pluginId);
        }

        public boolean editMessage(Object ref, Object text) {
            return doEditMessage(ref, text == null ? null : String.valueOf(text));
        }

        public boolean react(Object ref, Object emoji) {
            return doReact(ref, emoji == null ? null : String.valueOf(emoji));
        }

        public boolean deleteMessage(Object ref, Object opts) {
            return doDeleteMessage(ref, opts);
        }

        public boolean pinMessage(Object ref, Object opts) {
            return doPinMessage(ref, opts);
        }
    }

    public static class WithFileAct extends WithFile {

        public WithFileAct(String pluginId) {
            super(pluginId);
        }

        public boolean editMessage(Object ref, Object text) {
            return doEditMessage(ref, text == null ? null : String.valueOf(text));
        }

        public boolean react(Object ref, Object emoji) {
            return doReact(ref, emoji == null ? null : String.valueOf(emoji));
        }

        public boolean deleteMessage(Object ref, Object opts) {
            return doDeleteMessage(ref, opts);
        }

        public boolean pinMessage(Object ref, Object opts) {
            return doPinMessage(ref, opts);
        }
    }

    public static class WithGoogleFileAct extends WithGoogleFile {

        public WithGoogleFileAct(String pluginId) {
            super(pluginId);
        }

        public boolean editMessage(Object ref, Object text) {
            return doEditMessage(ref, text == null ? null : String.valueOf(text));
        }

        public boolean react(Object ref, Object emoji) {
            return doReact(ref, emoji == null ? null : String.valueOf(emoji));
        }

        public boolean deleteMessage(Object ref, Object opts) {
            return doDeleteMessage(ref, opts);
        }

        public boolean pinMessage(Object ref, Object opts) {
            return doPinMessage(ref, opts);
        }
    }
}
