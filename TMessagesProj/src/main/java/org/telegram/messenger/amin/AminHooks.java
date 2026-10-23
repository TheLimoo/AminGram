package org.telegram.messenger.amin;

import android.content.Context;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.amin.core.DeletedStore;
import org.telegram.messenger.amin.core.HiddenChatsStore;
import org.telegram.messenger.amin.core.MessagePipeline;
import org.telegram.messenger.amin.core.SendWhenOnlineStore;
import org.telegram.messenger.amin.modules.GhostModeModule;
import org.telegram.messenger.amin.modules.LocalPremiumModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.messenger.amin.plugins.js.JsPlugin;
import org.telegram.messenger.amin.plugins.js.JsPluginManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single bridge between upstream Telegram code and the AminGram mod layer.
 * Upstream hooks call ONLY these static methods (1-3 lines each, recorded
 * in MODS.md), so rebasing onto new Telegram versions stays cheap.
 * Every entry point is Throwable-isolated: a mod bug must never break
 * upstream behavior.
 *
 * v1.3 additions: JS plugin dispatch (messages, commands, attach-menu
 * actions), Send-When-Online presence watching, Hidden Chats dialog
 * filtering, Ghost-for-Stories gate, and the messagesDidLoad observer
 * that feeds Keyword Radar's scan-on-chat-open and history snapshots
 * for Anti-Delete / Edit History. All of it is event-driven off
 * Telegram's own update flow — no polling, no wakelocks, no timers.
 */
public final class AminHooks {

    private AminHooks() {
    }

    public static boolean moduleEnabled(String moduleId) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        ensureChatLoadObserver();
        return PluginRegistry.isEnabledById(context, moduleId);
    }

    /** Local Premium master funnel (patch 0003: UserConfig.isPremium
     *  and MessagesController.isPremiumUser for the self user). The
     *  "premium flag" sub-toggle narrows the master module switch;
     *  default ON, so existing users see no change. */
    public static boolean localPremium() {
        if (!moduleEnabled("local_premium")) {
            return false;
        }
        Context context = ApplicationLoader.applicationContext;
        return context != null && LocalPremiumModule.premiumFlag(context);
    }

    /** True while Local Premium's flag makes isPremium() true but the
     *  "premium emoji & sticker packs" sub-toggle is OFF — the
     *  EmojiView pack gates (patch 0003) consult this so the picker
     *  sub-toggle narrows the flag exactly like Ghost Mode's
     *  sub-toggles narrow its master switch. */
    public static boolean pickerPremiumBlocked() {
        if (!moduleEnabled("local_premium")) {
            return false;
        }
        Context context = ApplicationLoader.applicationContext;
        return context != null
                && LocalPremiumModule.premiumFlag(context)
                && !LocalPremiumModule.pickerUnlock(context);
    }

    /** Local emoji status for the self user (patch 0003:
     *  UserObject.getEmojiStatusDocumentId). Returns the stored
     *  document id, or null to keep upstream behaviour. The user must
     *  be a logged-in account's own user (multi-account safe: picks
     *  are keyed by that account's user id). */
    public static Long localStatusDocumentId(TLRPC.User user) {
        if (!moduleEnabled("local_premium") || !isLocalSelf(user)) {
            return null;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null || !LocalPremiumModule.emojiStatusEnabled(context)) {
            return null;
        }
        long documentId = LocalPremiumModule.statusDocumentId(context, user.id);
        return documentId != 0 ? documentId : null;
    }

    /** Suffix appended to the self user's locally rendered name
     *  (patch 0003: UserObject.getUserName) — the base emoji of the
     *  picked custom-emoji document, or null for no suffix. */
    public static String localNameEmojiSuffix(TLRPC.User user) {
        if (!moduleEnabled("local_premium") || !isLocalSelf(user)) {
            return null;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null || !LocalPremiumModule.nameEmojiEnabled(context)) {
            return null;
        }
        if (LocalPremiumModule.nameEmojiDocumentId(context, user.id) == 0) {
            return null;
        }
        String alt = LocalPremiumModule.nameEmojiAlt(context, user.id);
        return alt == null || alt.isEmpty() ? null : " " + alt;
    }

    /** Local name color for the self user (patch 0003:
     *  UserObject.getColorId — drives sender-name colors app-wide). */
    public static Integer localNameColorId(TLRPC.User user) {
        return localPeerColorId(user, 0);
    }

    /** Local profile color for the self user (patch 0003:
     *  UserObject.getProfileColorId — the profile header palette). */
    public static Integer localProfileColorId(TLRPC.User user) {
        return localPeerColorId(user, 1);
    }

    /** Color id for the self user's reply lines (patch 0003:
     *  ReplyMessageLine): the local reply color when set, else the
     *  upstream resolution (which itself honours the local name
     *  color above). */
    public static int replyColorId(TLRPC.User user) {
        Integer local = localPeerColorId(user, 2);
        return local != null ? local : UserObject.getColorId(user);
    }

    private static Integer localPeerColorId(TLRPC.User user, int which) {
        if (!moduleEnabled("local_premium") || !isLocalSelf(user)) {
            return null;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null || !LocalPremiumModule.peerColorsEnabled(context)) {
            return null;
        }
        int colorId;
        if (which == 0) {
            colorId = LocalPremiumModule.nameColorId(context, user.id);
        } else if (which == 1) {
            colorId = LocalPremiumModule.profileColorId(context, user.id);
        } else {
            colorId = LocalPremiumModule.replyColorId(context, user.id);
        }
        return colorId != LocalPremiumModule.COLOR_UNSET ? colorId : null;
    }

    /** True when {@code user} is the own user of one of the logged-in
     *  accounts on this device. Contacts never carry the server-set
     *  self flag; the clientUserId scan keeps multi-account rendering
     *  unambiguous. */
    private static boolean isLocalSelf(TLRPC.User user) {
        if (user == null || !user.self) {
            return false;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (config != null && config.isClientActivated()
                    && config.getClientUserId() == user.id) {
                return true;
            }
        }
        return false;
    }

    public static boolean ghostMode() {
        return moduleEnabled("ghost_mode");
    }

    /** Stage 2 granular Ghost gates (patch 0003 call sites). Each
     *  combines the unchanged master module toggle with one of
     *  GhostModeModule's default-ON sub-toggles, so a sub-toggle is
     *  consulted only while Ghost Mode itself is enabled. */
    public static boolean ghostRead() {
        if (!ghostMode()) {
            return false;
        }
        Context context = ApplicationLoader.applicationContext;
        return context != null && GhostModeModule.suppressReadReceipts(context);
    }

    public static boolean ghostTyping() {
        if (!ghostMode()) {
            return false;
        }
        Context context = ApplicationLoader.applicationContext;
        return context != null && GhostModeModule.suppressTyping(context);
    }

    public static boolean ghostOnline() {
        if (!ghostMode()) {
            return false;
        }
        Context context = ApplicationLoader.applicationContext;
        return context != null && GhostModeModule.suppressOnlineStatus(context);
    }

    public static boolean ghostStoriesEnabled() {
        return moduleEnabled("ghost_stories");
    }

    public static boolean antiDelete() {
        return moduleEnabled("anti_delete");
    }

    public static boolean freeTranslate() {
        return moduleEnabled("free_translate");
    }

    public static boolean hideStories() {
        return moduleEnabled("hide_stories");
    }

    public static boolean hideSponsored() {
        return moduleEnabled("hide_sponsored");
    }

    public static boolean screenshotUnlocked() {
        return moduleEnabled("screenshot_unlock");
    }

    // ------------------------------------------------------------------
    // Message Details (patch 0018, ChatActivity.fillMessageMenu /
    // processSelectedOption). Always available — no module gate.
    // OPTION_MESSAGE_DETAILS = 200 is provably unused by ChatActivity's
    // OPTION_* block (upstream uses 0-35 and 100-117 in 13.0.2).
    // ------------------------------------------------------------------

    public static final int OPTION_MESSAGE_DETAILS = 200;

    /** Appends the "Details" row to the message long-press menu.
     *  Pure list append — safe on the UI thread while the menu builds. */
    public static void addMessageDetailsOption(ArrayList<Integer> icons,
                                               ArrayList<CharSequence> items,
                                               ArrayList<Integer> options) {
        try {
            if (icons == null || items == null || options == null) {
                return;
            }
            items.add("Details");
            options.add(OPTION_MESSAGE_DETAILS);
            icons.add(R.drawable.msg_info);
        } catch (Throwable ignored) {
        }
    }

    public static void showMessageDetails(BaseFragment fragment, MessageObject messageObject) {
        try {
            if (fragment == null || fragment.getParentActivity() == null || messageObject == null) {
                return;
            }
            final String text = messageDetails(messageObject);
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
            builder.setTitle("Message Details");
            builder.setMessage(text);
            builder.setPositiveButton("Copy", (dialog, which) -> AndroidUtilities.addToClipboard(text));
            builder.setNegativeButton("Close", null);
            fragment.showDialog(builder.create());
        } catch (Throwable ignored) {
        }
    }

    /** Readable one-fact-per-line dump of a message's server metadata.
     *  Text only — nothing is stored. */
    public static String messageDetails(MessageObject messageObject) {
        StringBuilder sb = new StringBuilder();
        try {
            if (messageObject == null || messageObject.messageOwner == null) {
                return "No message";
            }
            TLRPC.Message message = messageObject.messageOwner;
            int account = messageObject.currentAccount;
            sb.append("Message ID: ").append(messageObject.getId());
            if (message.id != messageObject.getId()) {
                sb.append(" (real ").append(message.id).append(')');
            }
            sb.append('\n');
            sb.append("Dialog ID: ").append(messageObject.getDialogId()).append('\n');
            long senderId = messageObject.getSenderId();
            sb.append("Sender ID: ").append(senderId).append('\n');
            String senderName = peerName(account, senderId);
            if (senderName != null) {
                sb.append("Sender: ").append(senderName).append('\n');
            }
            if (message.post_author != null && !message.post_author.isEmpty()) {
                sb.append("Post author: ").append(message.post_author).append('\n');
            }
            sb.append("Sent: ").append(formatDetailsDate(message.date)).append('\n');
            if (message.edit_date != 0) {
                sb.append("Edited: ").append(formatDetailsDate(message.edit_date)).append('\n');
            }
            if (message.fwd_from != null) {
                String origin = null;
                if (message.fwd_from.from_name != null && !message.fwd_from.from_name.isEmpty()) {
                    origin = message.fwd_from.from_name;
                } else if (message.fwd_from.from_id != null) {
                    long originId = DialogObject.getPeerDialogId(message.fwd_from.from_id);
                    origin = peerName(account, originId);
                    if (origin == null) {
                        origin = String.valueOf(originId);
                    }
                }
                if (origin != null) {
                    sb.append("Forwarded from: ").append(origin).append('\n');
                }
                if (message.fwd_from.channel_post != 0) {
                    sb.append("Forwarded post ID: ").append(message.fwd_from.channel_post).append('\n');
                }
            }
            if (message.reply_to != null && message.reply_to.reply_to_msg_id != 0) {
                sb.append("Reply to ID: ").append(message.reply_to.reply_to_msg_id).append('\n');
            }
            if (message.grouped_id != 0) {
                sb.append("Grouped ID: ").append(message.grouped_id).append('\n');
            }
            appendMediaDetails(sb, messageObject, message);
            if (message.views != 0) {
                sb.append("Views: ").append(message.views).append('\n');
            }
            if (message.forwards != 0) {
                sb.append("Forwards: ").append(message.forwards).append('\n');
            }
        } catch (Throwable ignored) {
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static void appendMediaDetails(StringBuilder sb, MessageObject messageObject, TLRPC.Message message) {
        try {
            TLRPC.MessageMedia media = MessageObject.getMedia(message);
            if (media == null || media instanceof TLRPC.TL_messageMediaEmpty) {
                sb.append("Type: Text\n");
                return;
            }
            TLRPC.Document document = messageObject.getDocument();
            if (document != null) {
                String kind;
                if (messageObject.isRoundVideo()) {
                    kind = "Round video";
                } else if (messageObject.isVideo()) {
                    kind = "Video";
                } else if (messageObject.isVoice()) {
                    kind = "Voice";
                } else if (messageObject.isMusic()) {
                    kind = "Audio";
                } else if (messageObject.isGif()) {
                    kind = "GIF";
                } else if (messageObject.isSticker()) {
                    kind = "Sticker";
                } else {
                    kind = "File";
                }
                sb.append("Media: ").append(kind).append('\n');
                String fileName = messageObject.getFileName();
                if (fileName != null && !fileName.isEmpty()) {
                    sb.append("File: ").append(fileName).append('\n');
                }
                if (document.mime_type != null && !document.mime_type.isEmpty()) {
                    sb.append("MIME: ").append(document.mime_type).append('\n');
                }
                if (document.size != 0) {
                    sb.append("Size: ").append(AndroidUtilities.formatFileSize(document.size))
                            .append(" (").append(document.size).append(" bytes)\n");
                }
                if (document.dc_id != 0) {
                    sb.append("DC ID: ").append(document.dc_id).append('\n');
                }
                return;
            }
            if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
                sb.append("Media: Photo\n");
                long biggest = 0;
                if (media.photo.sizes != null) {
                    for (TLRPC.PhotoSize size : media.photo.sizes) {
                        if (size != null && size.size > biggest) {
                            biggest = size.size;
                        }
                    }
                }
                if (biggest != 0) {
                    sb.append("Size: ").append(AndroidUtilities.formatFileSize(biggest))
                            .append(" (").append(biggest).append(" bytes)\n");
                }
                if (media.photo.dc_id != 0) {
                    sb.append("DC ID: ").append(media.photo.dc_id).append('\n');
                }
                return;
            }
            String kind = media.getClass().getSimpleName()
                    .replace("TL_messageMedia", "").replace("TL_message", "");
            sb.append("Media: ").append(kind.isEmpty() ? "Other" : kind).append('\n');
        } catch (Throwable ignored) {
        }
    }

    private static String peerName(int account, long peerId) {
        try {
            if (peerId == 0) {
                return null;
            }
            MessagesController controller = MessagesController.getInstance(account);
            if (peerId > 0) {
                TLRPC.User user = controller.getUser(peerId);
                return user != null ? UserObject.getUserName(user) : null;
            }
            TLRPC.Chat chat = controller.getChat(-peerId);
            return chat != null ? chat.title : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String formatDetailsDate(int seconds) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(seconds * 1000L));
        } catch (Throwable ignored) {
            return String.valueOf(seconds);
        }
    }

    // ------------------------------------------------------------------
    // Raw update stream (patch 0006, top of processUpdateArray).
    // Drives: Edit History capture, Send-When-Online, JS plugins.
    // ------------------------------------------------------------------

    public static void onRawUpdates(ArrayList<TLRPC.Update> updates, int account) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || updates == null) {
                return;
            }
            if (moduleEnabled("edit_history")) {
                DeletedStore.recordEdits(context, updates);
            }
            boolean swo = moduleEnabled("send_when_online");
            boolean jsAny = JsPluginManager.hasEnabledPlugins(context);
            if (!swo && !jsAny) {
                return;
            }
            for (TLRPC.Update update : updates) {
                try {
                    if (update instanceof org.telegram.tgnet.tl.TL_update.TL_updateUserStatus) {
                        if (swo) {
                            org.telegram.tgnet.tl.TL_update.TL_updateUserStatus statusUpdate = (org.telegram.tgnet.tl.TL_update.TL_updateUserStatus) update;
                            if (statusUpdate.status instanceof TLRPC.TL_userStatusOnline) {
                                onUserOnline(context, account, statusUpdate.user_id);
                            }
                        }
                    } else if (update instanceof org.telegram.tgnet.tl.TL_update.TL_updateNewMessage
                            || update instanceof org.telegram.tgnet.tl.TL_update.TL_updateNewChannelMessage) {
                        if (jsAny) {
                            TLRPC.Message message = update instanceof org.telegram.tgnet.tl.TL_update.TL_updateNewMessage
                                    ? ((org.telegram.tgnet.tl.TL_update.TL_updateNewMessage) update).message
                                    : ((org.telegram.tgnet.tl.TL_update.TL_updateNewChannelMessage) update).message;
                            if (message != null) {
                                if (message.out && message.message != null && message.message.startsWith("/")) {
                                    // Own command sent from another device.
                                    JsPluginManager.dispatchCommand(account, message);
                                } else if (!message.out) {
                                    JsPluginManager.dispatchMessage(account, message);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onUserOnline(Context context, int account, long userId) {
        try {
            // Account isolation (Stage 1, audit D2): only entries
            // queued under THIS account may flush on its status
            // updates — the same peer on another account stays queued.
            List<SendWhenOnlineStore.Entry> pending =
                    SendWhenOnlineStore.pendingFor(context, account, userId);
            if (pending.isEmpty()) {
                return;
            }
            List<SendWhenOnlineStore.Entry> dispatched = new ArrayList<>();
            for (SendWhenOnlineStore.Entry entry : pending) {
                try {
                    SendMessagesHelper.getInstance(account)
                            .sendMessage(SendMessagesHelper.SendMessageParams.of(entry.text, entry.peer));
                    dispatched.add(entry);
                } catch (Throwable ignored) {
                    // Hand-off failed: the entry is NOT marked sent
                    // (audit D6) and retries on the next online event.
                }
            }
            if (!dispatched.isEmpty()) {
                SendWhenOnlineStore.markSent(context, dispatched);
                SendWhenOnlineStore.notifyChanged();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Anti-Delete v2: a server delete update is about to be applied.
     *  Moves matching pipeline snapshots into the persistent Deleted
     *  store so the content survives the delete (and restarts). */
    public static void onMessagesDeleted(MessagesController controller,
                                         LongSparseArray<ArrayList<Integer>> deleted) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                DeletedStore.markDeleted(context, controller, deleted);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Freshly arrived messages feed (patch 0006, pushMessages):
     *  KeywordRadar live scan / snapshots / Auto-Saver.
     *
     *  The pipeline no longer runs inline here (pillar-4 audit S2):
     *  this hook sits inside MessagesController.processUpdateArray
     *  on the update thread, and a slow store write would stall
     *  upstream update application and, transitively, the
     *  notification dispatch for the same batch. The batch is
     *  copied and posted to Utilities.globalQueue — the same queue
     *  the messagesDidLoad history path uses. globalQueue is a
     *  single serial queue, so batches keep their arrival order
     *  (per account included); MessagePipeline.process stays
     *  exception-isolated per message on that thread. */
    public static void onNewMessages(ArrayList<MessageObject> messages, int account) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || messages == null || messages.isEmpty()) {
                return;
            }
            final ArrayList<MessageObject> batch = new ArrayList<>(messages);
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    MessagePipeline.process(context, batch);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Outgoing message hook (patch 0013, SendMessagesHelper.sendMessage
     *  head): JS plugins see the user's own '/'-commands the moment they
     *  are sent from THIS device. Plugins react by sending — the command
     *  message itself is never touched. */
    public static void onOutgoingMessage(SendMessagesHelper.SendMessageParams params) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || params == null || params.message == null) {
                return;
            }
            if (!params.message.startsWith("/")) {
                return;
            }
            if (!JsPluginManager.hasEnabledPlugins(context)) {
                return;
            }
            JsPluginManager.dispatchOutgoingCommand(params);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Hidden Chats (patch 0011, DialogsActivity.getDialogsArray).
    // ------------------------------------------------------------------

    /** Returns the list unchanged unless Hidden Chats is enabled, locked
     *  and non-empty — then returns a filtered COPY (callers may mutate
     *  what they get; the controller's live list is never touched). */
    public static ArrayList<TLRPC.Dialog> filterHiddenDialogs(ArrayList<TLRPC.Dialog> dialogs) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || dialogs == null) {
                return dialogs;
            }
            if (!PluginRegistry.isEnabledById(context, "hidden_chats")
                    || HiddenChatsStore.isUnlocked()) {
                return dialogs;
            }
            Set<Long> hidden = HiddenChatsStore.getHiddenIdSet(context);
            if (hidden.isEmpty()) {
                return dialogs;
            }
            ArrayList<TLRPC.Dialog> out = new ArrayList<>(dialogs.size());
            for (TLRPC.Dialog dialog : dialogs) {
                if (dialog != null && !hidden.contains(dialog.id)) {
                    out.add(dialog);
                }
            }
            return out;
        } catch (Throwable ignored) {
            return dialogs;
        }
    }

    // ------------------------------------------------------------------
    // JS plugin attach-menu buttons (patch 0014, ChatAttachAlert).
    // ------------------------------------------------------------------

    /** Enabled menu plugins as {id, label, iconSpec} rows (v2.0:
     *  iconSpec is a set name or "emoji:<glyph>"; patch 0014 maps
     *  it), in menu-config order. Pure prefs read — safe on the UI
     *  thread while the menu builds. */
    public static List<String[]> attachMenuPlugins() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return new ArrayList<>();
            }
            return JsPluginManager.menuPlugins(context);
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    public static void onPluginMenuAction(String pluginId, long chatId) {
        try {
            JsPluginManager.dispatchMenuAction(pluginId, chatId);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // JS plugin message context-menu items (patch 0020,
    // ChatActivity.fillMessageMenu / processSelectedOption; design
    // §1.8b). OPTION_PLUGIN_CONTEXT_BASE..+3 are provably unused by
    // ChatActivity's OPTION_* block (upstream uses 0-35 and 100-117
    // in 13.0.2; OPTION_MESSAGE_DETAILS above uses 200).
    // ------------------------------------------------------------------

    public static final int OPTION_PLUGIN_CONTEXT_BASE = 300;
    public static final int MAX_PLUGIN_CONTEXT_ITEMS = 4;

    /** One plugin row in the message long-press menu: the plugin
     *  id to dispatch to and the label to show. */
    public static final class ContextItem {
        public final String pluginId;
        public final String label;

        public ContextItem(String pluginId, String label) {
            this.pluginId = pluginId;
            this.label = label;
        }
    }

    /** Enabled plugins that offer a message context-menu action,
     *  in hub order (JsPluginManager.plugins() order), capped at
     *  MAX_PLUGIN_CONTEXT_ITEMS. An item appears only when the
     *  plugin's manifest context-menu label is non-empty AND it
     *  defines the context hook (hasContext). Pure meta read —
     *  exactly the attachMenuPlugins() pattern: no runtime
     *  creation, no Rhino, no I/O beyond the manager's cached
     *  meta — safe on the UI thread while the menu builds.
     *  Selection is manifest-only in v2.2, so chatId / message do
     *  not filter the list; they are part of the signature for
     *  parity with the dispatch side and future per-message
     *  gating. */
    public static List<ContextItem> pluginContextItems(long chatId, TLRPC.Message message) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return new ArrayList<>();
            }
            List<ContextItem> out = new ArrayList<>();
            if (!JsPluginManager.isEngineEnabled(context)) {
                return out;
            }
            for (JsPlugin plugin : JsPluginManager.plugins(context)) {
                if (out.size() >= MAX_PLUGIN_CONTEXT_ITEMS) {
                    break;
                }
                if (plugin == null || !plugin.enabled || !plugin.hasContext) {
                    continue;
                }
                String label = plugin.contextMenu;
                if (label == null || label.trim().isEmpty()) {
                    continue;
                }
                out.add(new ContextItem(plugin.id, label));
            }
            return out;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** A plugin context-menu row was tapped for the selected
     *  message. The account follows the manager's dispatch idiom
     *  for entries that are not handed one (dispatchMenuAction /
     *  dispatchOutgoingCommand): UserConfig.selectedAccount. The
     *  message itself carries its dialog id, so chatId is not
     *  forwarded — it is kept in the signature for parity with
     *  onPluginMenuAction. */
    public static void onPluginContextAction(String pluginId, long chatId, TLRPC.Message message) {
        try {
            if (pluginId == null || message == null) {
                return;
            }
            int account = UserConfig.selectedAccount;
            JsPluginManager.dispatchContextAction(pluginId, account, message);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // messagesDidLoad observer: scan-on-chat-open (Keyword Radar) and
    // history snapshots (Anti-Delete / Edit History). Registered
    // lazily, PER ACCOUNT: an account activated after the first
    // registration pass gets its own subscription on the next
    // ensureChatLoadObserver() call (audit P1-11 — the old one-shot
    // flag left later-activated accounts unobserved forever).
    // Event-driven — it only runs when Telegram itself loads a
    // chat's messages.
    // ------------------------------------------------------------------

    /** Account slots whose NotificationCenter already carries
     *  CHAT_LOAD_OBSERVER. Slot NotificationCenters live for the
     *  whole process, so a covered slot stays covered across
     *  logout/login within the process. */
    private static final java.util.Set<Integer> observerAccounts =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final NotificationCenter.NotificationCenterDelegate CHAT_LOAD_OBSERVER =
            new NotificationCenter.NotificationCenterDelegate() {
                @Override
                public void didReceivedNotification(int id, int account, Object... args) {
                    try {
                        if (id != NotificationCenter.messagesDidLoad || args == null || args.length < 3) {
                            return;
                        }
                        if (!(args[2] instanceof ArrayList)) {
                            return;
                        }
                        @SuppressWarnings("unchecked")
                        ArrayList<MessageObject> objects = (ArrayList<MessageObject>) args[2];
                        if (objects.isEmpty()) {
                            return;
                        }
                        Context context = ApplicationLoader.applicationContext;
                        if (context == null) {
                            return;
                        }
                        boolean radar = PluginRegistry.isEnabledById(context, "keyword_radar");
                        boolean anti = PluginRegistry.isEnabledById(context, "anti_delete");
                        boolean hist = PluginRegistry.isEnabledById(context, "edit_history");
                        if (!radar && !anti && !hist) {
                            return;
                        }
                        final ArrayList<MessageObject> batch = new ArrayList<>(objects);
                        final boolean doRadar = radar;
                        final boolean doSnap = anti || hist;
                        Utilities.globalQueue.postRunnable(() -> {
                            try {
                                if (doSnap) {
                                    DeletedStore.snapshotAll(context, batch);
                                }
                                if (doRadar) {
                                    MessagePipeline.scanRadar(context, batch);
                                }
                            } catch (Throwable ignored) {
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            };

    public static void ensureChatLoadObserver() {
        // Fast path (this runs on every moduleEnabled() call): all
        // currently-activated accounts are already covered.
        if (observerAccounts.size() >= UserConfig.getActivatedAccountsCount()) {
            return;
        }
        synchronized (AminHooks.class) {
            try {
                for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                    if (observerAccounts.contains(account)) {
                        continue;
                    }
                    if (UserConfig.getInstance(account).isClientActivated()) {
                        NotificationCenter.getInstance(account)
                                .addObserver(CHAT_LOAD_OBSERVER, NotificationCenter.messagesDidLoad);
                        observerAccounts.add(account);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
