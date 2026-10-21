package org.telegram.messenger.amin.plugins.js;

import android.content.Context;
import android.content.SharedPreferences;
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
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.amin.google.GoogleAuthManager;
import org.telegram.messenger.amin.ui.PluginFilePickerActivity;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 *   amin.httpGet(url[, headers])        response text, or null (blocking);
 *                                        headers is an optional JS object
 *                                        of header name → value pairs
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
 * v2.1 (Platform Phase A) adds, on the BASE bridge (every plugin):
 *
 *   amin.platform.apiVersion / amin.platform.has(name)
 *                                        platform introspection
 *   amin.debug/info/warn/error(text)     leveled plugin log lines
 *   amin.getChatInfo(chatId)             lazy lookups, delegated to
 *   amin.getUserInfo(userId)             JsPluginManager.lookup*
 *   amin.getMessage(chatId, id)          statics (null when unknown)
 *   amin.httpGetAsync(url, headers, fn)  async HTTP: fn({status, body})
 *   amin.httpPostAsync(url, body, headers, fn)
 *                                        status 0 = transport failure
 *   amin.storage.getJSON/setJSON/remove/keys/ns
 *                                        storage v2 (see Storage)
 *
 * and, ONLY on the four `act` variants (WithAct, WithGoogleAct,
 * WithFileAct, WithGoogleFileAct — plugins whose manifest declares
 * the `act` capability):
 *
 *   amin.editMessage(ref, text)          edit YOUR OWN message;
 *                                        ref = a msg object or
 *                                        {chatId, id}; true/false
 *   amin.react(ref, emoji)               react to a message
 *
 * The ClassShutter in JsPluginManager whitelists ONLY this class
 * family (bridge, Storage, PlatformInfo, WithGoogle, GoogleAccess,
 * WithFile, WithGoogleFile, and the four act variants), so plugin
 * code cannot reach any other Java class. Instances are per-plugin;
 * sends act on the account whose update is currently being
 * dispatched.
 */
public class AminBridge {

    private final String pluginId;
    public final Storage storage;

    /** Platform introspection surface (v2.1): amin.platform —
     *  present on EVERY bridge variant, like storage. */
    public final PlatformInfo platform = new PlatformInfo();

    /** Per-plugin KV database (Phase B, §1.4): amin.db — a base
     *  surface on EVERY variant, like storage (no new capability;
     *  same trust tier as amin.storage). */
    public final DbAccess db;

    /** UI surface (Phase B, §1.9): amin.ui — assigned by
     *  attachCapabilities ONLY when the manifest declares the
     *  `ui` capability; null otherwise (§2.6's access-object
     *  pattern on the existing variants). Detect with
     *  amin.platform.has("ui"). */
    public UiAccess ui;

    /** Inter-plugin surface (Phase B, §1.10): amin.relay —
     *  assigned only with the `relay` capability, like ui. */
    public RelayAccess relay;

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
        this.db = new DbAccess(pluginId);
    }

    /** Assigns the Phase B capability-gated access objects (§2.6
     *  pattern). Called by JsPluginManager.newScope right after
     *  construction, with the plugin's declared capabilities.
     *  Package-private: not reachable from JS (Rhino exposes only
     *  public members, and this is neither public nor on a
     *  whitelisted class path from JS's view). */
    void attachCapabilities(java.util.Collection<String> capabilities) {
        if (capabilities == null) {
            return;
        }
        if (capabilities.contains("ui")) {
            this.ui = new UiAccess(pluginId);
        }
        if (capabilities.contains("relay")) {
            this.relay = new RelayAccess(pluginId);
        }
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

    // Leveled log lines (v2.1). log() above stays exactly as it
    // has always been (unprefixed); these add a level prefix the
    // hub log viewer can filter on. info() is log()'s level.

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
    // Lazy lookups (v2.1, Platform Phase A1) — public on the BASE
    // bridge, so every plugin can resolve ids on demand instead of
    // the bridge pre-resolving everything. The real work lives in
    // JsPluginManager's lookup statics (they own the message/chat
    // caches and build the JS data objects); these wrappers only
    // convert the JS arguments and pass this plugin's id. Each
    // returns the manager's data object, or null when unknown.
    // ------------------------------------------------------------------

    /** amin.getChatInfo(chatId) -> {id, title, type, ...} | null */
    public Object getChatInfo(Object chatId) {
        if (chatId == null) {
            return null;
        }
        return JsPluginManager.lookupChatInfo(pluginId, toLong(chatId));
    }

    /** amin.getUserInfo(userId) -> {id, name, username, ...} | null */
    public Object getUserInfo(Object userId) {
        if (userId == null) {
            return null;
        }
        return JsPluginManager.lookupUserInfo(pluginId, toLong(userId));
    }

    /** amin.getMessage(chatId, id) -> msg object | null */
    public Object getMessage(Object chatId, Object id) {
        if (chatId == null || id == null) {
            return null;
        }
        return JsPluginManager.lookupMessage(pluginId, toLong(chatId), toLong(id));
    }

    // ------------------------------------------------------------------
    // amin.editMessage / amin.react — implementations (v2.1,
    // Platform Phase A2). PROTECTED on purpose, exactly like
    // doSendFile: Rhino exposes only public members, so the plain
    // bridge (and the google/file variants) have no editMessage or
    // react at all; the public wrappers live exclusively on the
    // four act variants below (WithAct, WithGoogleAct, WithFileAct,
    // WithGoogleFileAct), handed only to plugins whose manifest
    // declares the `act` capability. Both return boolean and NEVER
    // throw across the bridge; failures are logged, like doSendFile.
    //
    // ref is a msg object (the shape JsPluginManager.buildMsg
    // produces) or any JS object with numeric {chatId, id} props,
    // read defensively.
    // ------------------------------------------------------------------

    /** Extracts {chatId, id} from a msg-like ref; null when the
     *  ref carries neither or a zero value. */
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

    /** Resolves a live MessageObject for (chatId, id) the same way
     *  AminGram's own DeletedStore.findLive does (amin/core/
     *  DeletedStore.java in the 13.0.2 tree): scan the dialog's
     *  loaded message list in MessagesController.dialogMessage,
     *  then fall back to the global dialogMessagesByIds map,
     *  verifying the dialog when one was requested. Null when the
     *  message is not currently loaded. */
    private static MessageObject resolveMessageObject(int account, long chatId, long id) {
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

    /** Edits the text of one of the USER'S OWN messages. The
     *  own-message check is native, not JS-side: the resolved
     *  MessageObject must be outgoing AND pass MessageObject.
     *  isOutOwner() (message.out / from_id == self, including the
     *  Saved Messages and channel-post cases) — anything else is
     *  refused with false + a log line.
     *
     *  Routing: SendMessagesHelper.editMessage(MessageObject,
     *  String, boolean, BaseFragment, ArrayList, int, int)
     *  (SendMessagesHelper.java:3567 in the 13.0.2 clone) is the
     *  text-edit entry point, but it returns 0 immediately when
     *  its BaseFragment is null or has no parent activity — a
     *  plugin thread has neither — so this builds the exact
     *  TL_messages_editMessage that method builds (peer via
     *  MessagesController.getInputPeer(dialogId), flags |= 2048
     *  for the message field, quick-reply shortcut preservation)
     *  and sends it through ConnectionsManager.sendRequest with
     *  the same processUpdates completion, mirroring the method's
     *  own body (SendMessagesHelper.java:3580-3615). Returns true
     *  once the request is dispatched. */
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

    /** Sends an emoji reaction to a message (any author's — reacting
     *  to others' messages is the normal case, so unlike
     *  editMessage there is no own-message check).
     *
     *  Routing mirrors SendMessagesHelper.sendReaction (13.0.2
     *  clone, SendMessagesHelper.java:3971-4027 — the method
     *  ChatActivity.java:33041 calls): it too refuses to run
     *  without a parent fragment, so this builds the same
     *  TL_messages_sendReaction it builds — peer/msg_id (including
     *  its thread-message saved-from special case), one
     *  TL_reactionEmoji with flags |= 1 — and sends it via
     *  ConnectionsManager.sendRequest, processing the Updates
     *  response the same way. Returns true once dispatched. */
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

    /** Deletes one message (Phase B, §1.2). Routes through
     *  MessagesController.deleteMessages (the 8-arg overload,
     *  pristine MessagesController.java:9295) — the same entry
     *  ChatActivity's own delete flow uses, so local storage,
     *  dialogs and the server request all stay consistent.
     *  opts.forEveryone is honoured ONLY for the user's own
     *  messages (verified natively via the resolved
     *  MessageObject's isOutOwner, exactly like editMessage);
     *  asking forEveryone on someone else's message degrades to
     *  a local delete with a log line, never an error the plugin
     *  could mistake for success. Every call is logged with its
     *  target ids (§1.2 mitigation). */
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

    /** Pins one message in its chat (Phase B, §1.2). No
     *  fragment-bound helper exists for pinning either, so this
     *  builds TL_messages_updatePinnedMessage directly (pristine
     *  TLRPC.java:54269): silent = !opts.notify, pm_oneside =
     *  opts.pmOneSide; the response deserialises to Updates and
     *  is processed like the other act calls. Every call is
     *  logged with its target ids. */
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
     *  throws: any failure is logged and reported as false.
     *  opts: type / caption / chatId, plus optional headers (a JS
     *  object of name → value) applied to the download request
     *  and every redirect hop (v2.2). */
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
                if (headers != null) {
                    // Caller headers (v2.2, opts.headers): applied
                    // after the defaults so a plugin can e.g.
                    // present a crawler UA to a mirror host. This
                    // loop rebuilds the connection on every manual
                    // redirect hop, so the headers ride every hop.
                    applyHeaders(connection, headers);
                }
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

    // ------------------------------------------------------------------
    // HTTP — sync (v1.x/v2.0) + async (v2.1, Platform Phase A3).
    // Both forms share the private static cores below, which return
    // an HttpResult {status, body}: status is the HTTP status code,
    // or 0 on transport failure (bad URL, connect/read failure —
    // the failure is logged exactly as the sync forms always did).
    // The sync wrappers preserve the historical semantics BYTE-
    // COMPATIBLY on top of the cores:
    //   GET  sync: body on 2xx/3xx, null on any other status and on
    //        transport failure.
    //   POST sync: body on 2xx, the ERROR body on an HTTP error
    //        status, null on transport failure / bodyless error.
    // The async forms deliver {status, body} to the callback via
    // JsPluginManager.invokePluginCallbackWithArg, off the plugin
    // dispatch thread, so a slow server can no longer stall every
    // other plugin.
    // ------------------------------------------------------------------

    /** One HTTP exchange outcome, shared by the sync/async forms. */
    private static final class HttpResult {
        final int status;
        final String body;

        HttpResult(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** Lazily-created executor for the async HTTP forms (holder
     *  idiom: the pool exists only once an async call is made).
     *  Cached pool of daemon threads named "AminGramHttp" — daemon
     *  so a stuck request can never keep the process alive. */
    private static final class HttpExecutorHolder {
        static final ExecutorService INSTANCE = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "AminGramHttp");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Applies caller headers to a connection. Accepts either a JS
     *  object (Scriptable of name → value, the sync call form) or a
     *  snapshotted Map<String,String> (the async form, snapshotted
     *  on the calling thread so no Rhino object is touched from a
     *  worker thread). Returns true when a Content-Type was set. */
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

    /** Copies a JS headers object into a plain map, on the calling
     *  (plugin) thread, for the async forms. Null when the argument
     *  is not a JS object. */
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

    /** GET core: 15 s connect/read timeouts, body capped at 1 MB.
     *  On 2xx/3xx the body; on any other status body is null (the
     *  status still reports what the server said); on transport
     *  failure status 0 / body null, logged as the sync form logs. */
    private static HttpResult httpGetCore(String pluginId, String urlString, Object headersObject) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AminGram");
            connection.setRequestProperty("Accept", "*/*");
            applyHeaders(connection, headersObject);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 400) {
                return new HttpResult(code, null);
            }
            return new HttpResult(code, readCapped(connection.getInputStream()));
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.httpGet failed: " + t);
            return new HttpResult(0, null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** POST core: same timeouts/cap as GET. On 2xx the body; on an
     *  HTTP error status the ERROR body (v1.3.7 semantics); on
     *  transport failure status 0 / body null, logged as the sync
     *  form logs. A JSON Content-Type is assumed unless the caller
     *  set one. bodyString null sends an empty body. */
    private static HttpResult httpPostCore(String pluginId, String urlString, String bodyString, Object headersObject) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AminGram");
            boolean hasContentType = applyHeaders(connection, headersObject);
            if (!hasContentType) {
                connection.setRequestProperty("Content-Type",
                        "application/json; charset=utf-8");
            }
            byte[] body = bodyString == null ? new byte[0]
                    : bodyString.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            out.write(body);
            out.close();
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                return new HttpResult(code, readCapped(connection.getInputStream()));
            }
            InputStream err = connection.getErrorStream();
            if (err == null) {
                return new HttpResult(code, null);
            }
            return new HttpResult(code, readCapped(err));
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.httpPost failed: " + t);
            return new HttpResult(0, null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Blocking GET on the plugin dispatch thread. Returns the body
     *  text (capped at 1 MB), or null on any failure. */
    public String httpGet(Object urlObject) {
        return httpGet(urlObject, null);
    }

    /** httpGet with caller headers (v2.0): the second argument is a
     *  JS object of header name → value pairs, applied after the
     *  defaults so callers can override them — e.g.
     *  amin.httpGet(url, {Authorization: "Bearer " + token}) for
     *  APIs like mail.tm whose inbox requires a bearer token.
     *  Semantics are otherwise EXACTLY the 1-arg contract: same
     *  timeouts, same 1 MB cap, null on any failure including HTTP
     *  error statuses. Additive only — the 1-arg form is unchanged
     *  and every existing plugin keeps working. */
    public String httpGet(Object urlObject, Object headersObject) {
        if (urlObject == null) {
            return null;
        }
        HttpResult result = httpGetCore(pluginId, String.valueOf(urlObject), headersObject);
        return result.status >= 200 && result.status < 400 ? result.body : null;
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
        HttpResult result = httpPostCore(pluginId, String.valueOf(urlObject),
                bodyObject == null ? null : String.valueOf(bodyObject), headersObject);
        return result.status == 0 ? null : result.body;
    }

    /** Async GET (v2.1): amin.httpGetAsync(url, headersOrNull, fn).
     *  Returns immediately; fn later receives {status, body} on the
     *  plugin thread — status is the HTTP status (error statuses
     *  included, body null for them, as in the sync GET), or 0 with
     *  body null on transport failure. The headers object is
     *  snapshotted on this thread; the request itself runs on the
     *  AminGramHttp pool. */
    public void httpGetAsync(Object urlObject, Object headersObject, Object callback) {
        if (!(callback instanceof Function)) {
            JsPluginManager.appendLog(pluginId,
                    "[error] amin.httpGetAsync needs a callback function");
            return;
        }
        final Function fn = (Function) callback;
        final String urlString = urlObject == null ? null : String.valueOf(urlObject);
        final Map<String, String> headers = snapshotHeaders(headersObject);
        HttpExecutorHolder.INSTANCE.execute(() -> {
            HttpResult result = urlString == null
                    ? new HttpResult(0, null)
                    : httpGetCore(pluginId, urlString, headers);
            deliverHttpResult(fn, result);
        });
    }

    /** Async POST (v2.1): amin.httpPostAsync(url, body, headers, fn).
     *  Same delivery contract as httpGetAsync; unlike the async
     *  GET, an HTTP error status delivers the ERROR body, matching
     *  the sync POST. */
    public void httpPostAsync(Object urlObject, Object bodyObject, Object headersObject, Object callback) {
        if (!(callback instanceof Function)) {
            JsPluginManager.appendLog(pluginId,
                    "[error] amin.httpPostAsync needs a callback function");
            return;
        }
        final Function fn = (Function) callback;
        final String urlString = urlObject == null ? null : String.valueOf(urlObject);
        final String bodyString = bodyObject == null ? null : String.valueOf(bodyObject);
        final Map<String, String> headers = snapshotHeaders(headersObject);
        HttpExecutorHolder.INSTANCE.execute(() -> {
            HttpResult result = urlString == null
                    ? new HttpResult(0, null)
                    : httpPostCore(pluginId, urlString, bodyString, headers);
            deliverHttpResult(fn, result);
        });
    }

    /** Hands an async HTTP outcome to the plugin callback as a
     *  fresh {status: Number, body: String|null} object, built
     *  inside the callback's own Rhino context/scope by the
     *  manager's factory mechanism. */
    private void deliverHttpResult(final Function fn, final HttpResult result) {
        try {
            JsPluginManager.invokePluginCallbackWithArg(pluginId, fn, (cx, scope) -> {
                Scriptable value = cx.newObject(scope);
                value.put("status", value, Double.valueOf(result.status));
                value.put("body", value, result.body);
                return value;
            });
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin http async delivery failed: " + t);
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

    // ------------------------------------------------------------------
    // JSON conversion helpers (v2.1, storage v2) — public statics,
    // pure: org.json + Rhino types only, no Android/framework state,
    // so they are unit-testable off-device. jsToJson serialises any
    // JSON-shaped JS value (objects, arrays, strings, numbers,
    // booleans, null); undefined and function values are dropped
    // from objects, become null inside arrays, and a top-level
    // undefined/function serialises to a null return (the caller —
    // Storage.setJSON — treats that as "not serialisable").
    // Numbers follow JSON: integral values stay integral, NaN and
    // infinities become null. jsonToJs parses back into live Rhino
    // values inside the caller's context/scope; it returns null on
    // any parse failure (and for the JSON literal null).
    // ------------------------------------------------------------------

    public static String jsToJson(Object value) {
        // A top-level null/undefined/function has no JSON document
        // form: report it as unserialisable (Java null). Nested
        // occurrences are handled inside toJsonValue (dropped from
        // objects, null inside arrays, JSON null for real nulls).
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
                // A quoted JSON string literal.
                Object parsedString = new org.json.JSONTokener(json).nextValue();
                return parsedString instanceof String ? parsedString : null;
            }
            // Strictly numeric scalars only: a bare unquoted word
            // is NOT valid JSON, so it takes the parse-failure path
            // (null) — this is what makes Storage.getJSON return
            // its default for a plain string stored via set().
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

    /** Per-plugin key/value storage, backed by its own
     *  SharedPreferences file ("amingram_jsstore_<id>"). Values are
     *  strings under the hood; get() converts to the type of the
     *  provided default (bool/number/string), as the master prompt
     *  documents string/number/bool values.
     *
     *  v2.1 (storage v2) adds, without changing get/set at all:
     *    getJSON(key, def)   the stored string parsed as JSON into a
     *                        live JS value; def when the key is
     *                        absent OR the stored text is not valid
     *                        JSON (corruption path — note this means
     *                        a plain string saved via set() reads
     *                        back as def here, while get() still
     *                        returns it untouched; the two views
     *                        coexist on the same physical keys).
     *                        A stored JSON null also reads as def.
     *    setJSON(key, value) any JSON-serialisable JS value, stored
     *                        via jsToJson; undefined/function values
     *                        are logged and ignored (no-op). Values
     *                        over 64 KB are stored but logged [warn].
     *    remove(key)         deletes the key.
     *    keys(prefix)        JS array of this view's key names
     *                        starting with prefix (null/"" = all),
     *                        sorted.
     *    ns(name)            a Storage VIEW whose keys are physically
     *                        "<name>:<key>" in the same prefs file —
     *                        cheap namespacing inside one plugin.
     *                        Views nest ("a:b:key"). */
    public static class Storage {

        private final String prefsName;
        private final String pluginId;
        private final String keyPrefix;

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
            String storedKey = keyPrefix + String.valueOf(key);
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
            edit.apply();
        }

        /** Stored JSON → live JS value; defaultValue when absent
         *  or unparseable (see the class javadoc). */
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

        /** Any JSON-serialisable JS value → stored JSON text.
         *  undefined/function values are logged and ignored. */
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

        /** This view's keys starting with prefix (null/"" = all),
         *  as a JS array of strings, sorted. Namespace prefixes of
         *  sub-views are part of the key names seen from here. */
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

        /** A namespaced view of this same store: its keys live
         *  physically as "<name>:<key>" in the same prefs file. */
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
            return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        }
    }

    /** Platform introspection (v2.1, Platform Phase A9):
     *  amin.platform — present on EVERY bridge variant. Plugins
     *  feature-detect instead of version-sniffing:
     *    if (amin.platform.has("act")) { ... }
     *  has() answers from the fixed feature set this build ships. */
    public static class PlatformInfo {

        public int apiVersion = 2;

        private static final Set<String> FEATURES = new HashSet<>(Arrays.asList(
                "rich-msg", "lazy-lookup", "act", "http-async", "storage-json",
                "settings-v2", "lifecycle", "watchdog", "platform",
                "sendFile", "http-headers", "reply-context",
                // Platform Phase B (apiVersion stays 2 — Phase B
                // completes platform v2).
                "db", "files", "pickFile", "ui", "relay",
                "act-delete-pin", "plugin-page", "context-menu",
                "settings-color-file"));

        public boolean has(String name) {
            return name != null && FEATURES.contains(name);
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

        /** The active Google API mode (v2.1): "aistudio" or
         *  "vertex" — which endpoint/scope family GoogleAuthManager
         *  is currently configured for, so dual-mode plugins (the
         *  Gemini chat plugin) can build the right request without
         *  guessing. "" if the mode cannot be read. */
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

        /** The most recent Google authorize failure's detail
         *  (v2.1), or "" when none — lets a plugin show WHY a token
         *  request failed (e.g. an invalid_scope verdict) instead
         *  of a bare "did not answer". */
        public String getLastAuthError() {
            try {
                String error = GoogleAuthManager.getLastAuthError();
                return error == null ? "" : error;
            } catch (Throwable t) {
                return "";
            }
        }
    }

    /** amin.pickFile implementation (Phase B, §1.6). PROTECTED,
     *  like doSendFile: the public wrappers live only on the
     *  file-capability variants. ASYNC: fn(pathOrNull) fires later
     *  (via JsPluginManager.invokePluginCallback, on the plugin
     *  thread) once the user has picked — or cancelled — in the
     *  system document picker hosted by PluginFilePickerActivity.
     *  opts = {accept: "video/*", copy: true}; copy defaults to
     *  true (durable sandbox copy); copy:false stages in cache. */
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
        if (context == null) {
            JsPluginManager.invokePluginCallback(pluginId, fn, null);
            return;
        }
        PluginFilePickerActivity.request(context, accept, copy, pluginId,
                path -> JsPluginManager.invokePluginCallback(pluginId, fn, path));
    }

    /** The `amin.db` surface (Phase B, §1.4): per-plugin KV in
     *  the shared SQLite store (PluginDb). Present on EVERY
     *  variant — same trust tier as amin.storage. Values cross
     *  the boundary as JSON only: get returns the parsed value
     *  (null when absent), put refuses unserialisable values and
     *  contract violations (> 256 KB, ns/key > 128 chars) with
     *  false + a log line; nothing throws across the bridge. */
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
                        "[warn] amin.db.put refused (ns/key > 128 chars or value > 256 KB)");
            }
            return ok;
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

    /** The `amin.files` surface (Phase B, §1.6): the plugin's
     *  sandboxed file store (PluginFiles). Present ONLY on the
     *  file-capability variants, next to sendFile/pickFile.
     *  Bare names only — separators and ".." are rejected
     *  natively; failures return false/null, never throw. */
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

    /** The `amin.ui` surface (Phase B, §1.9): clipboard write,
     *  plugin notifications, and open intents. Assigned ONLY with
     *  the `ui` capability (see attachCapabilities). notify is
     *  rate-capped natively at 5/minute/plugin — a sanity cap
     *  against accidents, excess is logged and returns false. */
    public static class UiAccess {

        private static final String CHANNEL_ID = "amingram_plugin_notifications";
        private static final int NOTIFY_CAP_PER_MINUTE = 5;
        private static final Map<String, java.util.ArrayDeque<Long>> NOTIFY_TIMES =
                new java.util.HashMap<>();

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
                    // The extra LaunchActivity itself consumes when a
                    // notification is tapped (upstream behaviour).
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
            // A user id IS the dialog id of the private chat.
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

    /** The `amin.relay` surface (Phase B, §1.10): plugin-to-
     *  plugin calls and event broadcast. Assigned ONLY with the
     *  `relay` capability. Values cross as JSON (deep copies);
     *  call() is async via its callback fn(err, result) and never
     *  throws — every failure arrives as the err string. */
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

        /** amin.files (Phase B, §1.6): the sandboxed file store. */
        public final FileAccess files;

        public WithFile(String pluginId) {
            super(pluginId);
            this.files = new FileAccess(pluginId);
        }

        /** amin.sendFile(url, opts) -> boolean, synchronous.
         *  See the class javadoc for the full contract. */
        public boolean sendFile(Object url, Object opts) {
            return doSendFile(url, opts);
        }

        /** amin.pickFile(opts, fn): async file picker (§1.6). */
        public void pickFile(Object opts, Object callback) {
            doPickFile(opts, callback);
        }
    }

    /** The bridge for plugins declaring BOTH `file` and `google`
     *  (v2.0): amin.google (inherited from WithGoogle) PLUS the
     *  public amin.sendFile wrapper. Like WithFile, it is covered
     *  by an R8 -keep in patch 0010:
     *    -keep class org.telegram.messenger.amin.plugins.js.AminBridge$WithGoogleFile { *; }
     *  and by JsPluginManager's ClassShutter whitelist. */
    public static class WithGoogleFile extends WithGoogle {

        /** amin.files (Phase B, §1.6): the sandboxed file store. */
        public final FileAccess files;

        public WithGoogleFile(String pluginId) {
            super(pluginId);
            this.files = new FileAccess(pluginId);
        }

        /** amin.sendFile(url, opts) -> boolean, synchronous.
         *  See the class javadoc for the full contract. */
        public boolean sendFile(Object url, Object opts) {
            return doSendFile(url, opts);
        }

        /** amin.pickFile(opts, fn): async file picker (§1.6). */
        public void pickFile(Object opts, Object callback) {
            doPickFile(opts, callback);
        }
    }

    /** The bridge handed to plugins whose manifest declares the
     *  `act` capability (v2.1): the plain bridge PLUS the public
     *  amin.editMessage / amin.react wrappers. The implementations
     *  are {@link AminBridge#doEditMessage} / {@link AminBridge#doReact}
     *  (protected on the base, hence invisible to Rhino there);
     *  these public wrappers are the only places they become
     *  reachable from JS. Kept by patch 0010 and whitelisted by
     *  JsPluginManager's ClassShutter, like the other variants. */
    public static class WithAct extends AminBridge {

        public WithAct(String pluginId) {
            super(pluginId);
        }

        /** amin.editMessage(ref, text) -> boolean. Edits one of
         *  the user's OWN messages; see doEditMessage. */
        public boolean editMessage(Object ref, Object text) {
            return doEditMessage(ref, text == null ? null : String.valueOf(text));
        }

        /** amin.react(ref, emoji) -> boolean. See doReact. */
        public boolean react(Object ref, Object emoji) {
            return doReact(ref, emoji == null ? null : String.valueOf(emoji));
        }

        /** amin.deleteMessage(ref, opts) -> boolean (Phase B).
         *  See doDeleteMessage. */
        public boolean deleteMessage(Object ref, Object opts) {
            return doDeleteMessage(ref, opts);
        }

        /** amin.pinMessage(ref, opts) -> boolean (Phase B).
         *  See doPinMessage. */
        public boolean pinMessage(Object ref, Object opts) {
            return doPinMessage(ref, opts);
        }
    }

    /** `act` + `google` (v2.1): amin.google (inherited from
     *  WithGoogle) PLUS the editMessage/react wrappers. */
    public static class WithGoogleAct extends WithGoogle {

        public WithGoogleAct(String pluginId) {
            super(pluginId);
        }

        /** amin.editMessage(ref, text) -> boolean. See doEditMessage. */
        public boolean editMessage(Object ref, Object text) {
            return doEditMessage(ref, text == null ? null : String.valueOf(text));
        }

        /** amin.react(ref, emoji) -> boolean. See doReact. */
        public boolean react(Object ref, Object emoji) {
            return doReact(ref, emoji == null ? null : String.valueOf(emoji));
        }

        /** amin.deleteMessage(ref, opts) -> boolean (Phase B).
         *  See doDeleteMessage. */
        public boolean deleteMessage(Object ref, Object opts) {
            return doDeleteMessage(ref, opts);
        }

        /** amin.pinMessage(ref, opts) -> boolean (Phase B).
         *  See doPinMessage. */
        public boolean pinMessage(Object ref, Object opts) {
            return doPinMessage(ref, opts);
        }
    }

    /** `act` + `file` (v2.1): amin.sendFile (inherited from
     *  WithFile) PLUS the editMessage/react wrappers. */
    public static class WithFileAct extends WithFile {

        public WithFileAct(String pluginId) {
            super(pluginId);
        }

        /** amin.editMessage(ref, text) -> boolean. See doEditMessage. */
        public boolean editMessage(Object ref, Object text) {
            return doEditMessage(ref, text == null ? null : String.valueOf(text));
        }

        /** amin.react(ref, emoji) -> boolean. See doReact. */
        public boolean react(Object ref, Object emoji) {
            return doReact(ref, emoji == null ? null : String.valueOf(emoji));
        }

        /** amin.deleteMessage(ref, opts) -> boolean (Phase B).
         *  See doDeleteMessage. */
        public boolean deleteMessage(Object ref, Object opts) {
            return doDeleteMessage(ref, opts);
        }

        /** amin.pinMessage(ref, opts) -> boolean (Phase B).
         *  See doPinMessage. */
        public boolean pinMessage(Object ref, Object opts) {
            return doPinMessage(ref, opts);
        }
    }

    /** `act` + `google` + `file` (v2.1): every surface — amin.google,
     *  amin.sendFile, amin.editMessage, amin.react. */
    public static class WithGoogleFileAct extends WithGoogleFile {

        public WithGoogleFileAct(String pluginId) {
            super(pluginId);
        }

        /** amin.editMessage(ref, text) -> boolean. See doEditMessage. */
        public boolean editMessage(Object ref, Object text) {
            return doEditMessage(ref, text == null ? null : String.valueOf(text));
        }

        /** amin.react(ref, emoji) -> boolean. See doReact. */
        public boolean react(Object ref, Object emoji) {
            return doReact(ref, emoji == null ? null : String.valueOf(emoji));
        }

        /** amin.deleteMessage(ref, opts) -> boolean (Phase B).
         *  See doDeleteMessage. */
        public boolean deleteMessage(Object ref, Object opts) {
            return doDeleteMessage(ref, opts);
        }

        /** amin.pinMessage(ref, opts) -> boolean (Phase B).
         *  See doPinMessage. */
        public boolean pinMessage(Object ref, Object opts) {
            return doPinMessage(ref, opts);
        }
    }
}
