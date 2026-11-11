package org.telegram.messenger.amin;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
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
import org.telegram.messenger.amin.modules.AyuForwardModule;
import org.telegram.messenger.amin.modules.FolderChatQolModule;
import org.telegram.messenger.amin.modules.ForwardAsCopyModule;
import org.telegram.messenger.amin.modules.GestureActionsModule;
import org.telegram.messenger.amin.modules.GhostModeModule;
import org.telegram.messenger.amin.modules.LocalPremiumModule;
import org.telegram.messenger.amin.modules.MessageFiltersModule;
import org.telegram.messenger.amin.modules.SpeedBoostModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.messenger.amin.plugins.js.JsPlugin;
import org.telegram.messenger.amin.plugins.js.JsPluginManager;
import org.telegram.messenger.amin.ui.AminDialogPickerActivity;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Single bridge between upstream Telegram code and the AminGram mod layer.
 * Upstream hooks call ONLY these static methods (1-3 lines each, recorded
 * in MODS.md), so rebasing onto new Telegram versions stays cheap.
 * Every entry point is Throwable-isolated: a mod bug must never break
 * upstream behavior.
 */
public final class AminHooks {

    private static final ThreadLocal<SimpleDateFormat> DETAILS_DATE_FMT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                }
            };

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
     *  document id, or null to keep upstream behaviour. */
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
     *  (patch 0003: UserObject.getUserName). */
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
     *  ReplyMessageLine). */
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
        if (moduleEnabled("screenshot_unlock")) {
            return true;
        }
        Context context = ApplicationLoader.applicationContext;
        return context != null && moduleEnabled("ayu_forward")
                && AyuForwardModule.unlockCopySave(context);
    }

    public static boolean ayuForwardEnabled() {
        return moduleEnabled("ayu_forward");
    }

    public static boolean forwardAsCopyEnabled() {
        return moduleEnabled("forward_as_copy");
    }

    public static boolean messageFiltersEnabled() {
        return moduleEnabled("message_filters");
    }

    public static boolean folderChatQolEnabled() {
        return moduleEnabled("folder_chat_qol");
    }

    public static boolean gestureActionsEnabled() {
        return moduleEnabled("gesture_actions");
    }

    public static boolean speedBoostEnabled() {
        return moduleEnabled("speed_boost");
    }

    // ------------------------------------------------------------------
    // Message Details & Stage 2 Message Menu Options (patches 0018, 0020, 0024)
    // ------------------------------------------------------------------

    public static final int OPTION_MESSAGE_DETAILS = 200;
    public static final int OPTION_REVEAL_FILTERED = 201;
    public static final int OPTION_AYU_FORWARD_COPY = 202;
    public static final int OPTION_AYU_SAVE_SELF = 203;
    public static final int OPTION_FORWARD_AS_COPY = 204;
    public static final int OPTION_JUMP_TO_BEGINNING = 205;
    public static final int OPTION_MARK_FOLDER_READ = 206;
    public static final int OPTION_AYU_COPY_TEXT = 207;
    public static final int OPTION_QUICK_GESTURE_ACTION = 208;

    /** Appends the "Details" row to the message long-press menu. */
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

    /** Appends Stage 2 native feature options to the message tap/long-press menu
     *  in ChatActivity (patch 0024): Reveal Filtered Message, AyuForward (re-send
     *  from restricted channels), Save to Saved Messages, Forward as Copy,
     *  Jump to Beginning of Chat, and Mark Folder as Read. */
    public static void addStage2MessageOptions(BaseFragment fragment,
                                               MessageObject messageObject,
                                               ArrayList<Integer> icons,
                                               ArrayList<CharSequence> items,
                                               ArrayList<Integer> options) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || icons == null || items == null || options == null) {
                return;
            }
            if (messageObject != null && isMessageFiltered(messageObject)) {
                items.add(0, "Reveal filtered message");
                options.add(0, OPTION_REVEAL_FILTERED);
                icons.add(0, R.drawable.msg_viewchats);
            }
            int account = fragment != null ? fragment.getCurrentAccount() : UserConfig.selectedAccount;
            boolean restricted = messageObject != null && isRestrictedMessage(account, messageObject);
            if (ayuForwardEnabled()) {
                if (restricted) {
                    items.add("AyuForward (Re-send copy)");
                    options.add(OPTION_AYU_FORWARD_COPY);
                    icons.add(R.drawable.msg_forward);

                    items.add("Save to Saved Messages");
                    options.add(OPTION_AYU_SAVE_SELF);
                    icons.add(R.drawable.msg_saved);

                    if (AyuForwardModule.unlockCopySave(context) && hasCopyableText(messageObject)) {
                        items.add("Copy text (AyuForward)");
                        options.add(OPTION_AYU_COPY_TEXT);
                        icons.add(R.drawable.msg_copy);
                    }
                } else {
                    items.add("Save to Saved Messages");
                    options.add(OPTION_AYU_SAVE_SELF);
                    icons.add(R.drawable.msg_saved);
                }
            }
            if (forwardAsCopyEnabled() && messageObject != null && !restricted) {
                items.add("Forward as Copy (No Quote)");
                options.add(OPTION_FORWARD_AS_COPY);
                icons.add(R.drawable.msg_forward);
            }
            if (folderChatQolEnabled()) {
                if (FolderChatQolModule.jumpToStart(context)) {
                    items.add("Jump to beginning");
                    options.add(OPTION_JUMP_TO_BEGINNING);
                    icons.add(R.drawable.msg_go_up);
                }
                if (FolderChatQolModule.markFolderRead(context)) {
                    ArrayList<MessagesController.DialogFilter> filters =
                            MessagesController.getInstance(account).dialogFilters;
                    if (filters != null && !filters.isEmpty()) {
                        items.add("Mark folder as read…");
                        options.add(OPTION_MARK_FOLDER_READ);
                        icons.add(R.drawable.msg_markread);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Handles Stage 2 option selections in ChatActivity.processSelectedOption (patch 0024). */
    public static void handleStage2MessageOption(BaseFragment fragment,
                                                 int option,
                                                 MessageObject selectedObject) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || fragment == null) {
                return;
            }
            int account = fragment.getCurrentAccount();
            switch (option) {
                case OPTION_REVEAL_FILTERED:
                    revealFilteredMessage(fragment, selectedObject);
                    break;
                case OPTION_AYU_FORWARD_COPY:
                    if (selectedObject != null) {
                        promptForwardAsCopy(fragment, selectedObject, false);
                    }
                    break;
                case OPTION_AYU_SAVE_SELF:
                    if (selectedObject != null) {
                        long selfId = UserConfig.getInstance(account).getClientUserId();
                        if (selfId != 0) {
                            ArrayList<MessageObject> list = new ArrayList<>(1);
                            list.add(selectedObject);
                            forwardMessagesAsOwnCopy(fragment, account, list, selfId, false);
                        }
                    }
                    break;
                case OPTION_AYU_COPY_TEXT:
                    if (selectedObject != null) {
                        String text = extractPlainMessageText(selectedObject);
                        if (text != null && !text.isEmpty()) {
                            AndroidUtilities.addToClipboard(text);
                            BulletinFactory.of(fragment)
                                    .createSimpleBulletin(R.raw.copy, "Text copied").show();
                        }
                    }
                    break;
                case OPTION_FORWARD_AS_COPY:
                    if (selectedObject != null) {
                        promptForwardAsCopy(fragment, selectedObject,
                                ForwardAsCopyModule.dropCaption(context));
                    }
                    break;
                case OPTION_JUMP_TO_BEGINNING: {
                    long dialogId = 0;
                    if (fragment instanceof ChatActivity) {
                        dialogId = ((ChatActivity) fragment).getDialogId();
                    } else if (selectedObject != null) {
                        dialogId = selectedObject.getDialogId();
                    }
                    jumpToChatBeginning(fragment, dialogId);
                    break;
                }
                case OPTION_MARK_FOLDER_READ:
                    showMarkFolderReadDialog(fragment, account);
                    break;
                case OPTION_QUICK_GESTURE_ACTION:
                    if (selectedObject != null) {
                        executeQuickGestureAction(fragment, selectedObject, false);
                    }
                    break;
                default:
                    break;
            }
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

    /** Readable one-fact-per-line dump of a message's server metadata. */
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
            if (message.noforwards) {
                sb.append("Restricted (noforwards): true\n");
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
            SimpleDateFormat fmt = DETAILS_DATE_FMT.get();
            return fmt != null ? fmt.format(new Date(seconds * 1000L)) : String.valueOf(seconds);
        } catch (Throwable ignored) {
            return String.valueOf(seconds);
        }
    }

    // ------------------------------------------------------------------
    // Raw update stream (patch 0006, top of processUpdateArray).
    // Drives: Edit History capture, Send-When-Online, JS plugins, and
    // Speed Boost experimental-param sync.
    // ------------------------------------------------------------------

    public static void onRawUpdates(ArrayList<TLRPC.Update> updates, int account) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || updates == null) {
                return;
            }
            syncSpeedBoostFlagOnController(context, account);
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
                            org.telegram.tgnet.tl.TL_update.TL_updateUserStatus statusUpdate =
                                    (org.telegram.tgnet.tl.TL_update.TL_updateUserStatus) update;
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
                }
            }
            if (!dispatched.isEmpty()) {
                SendWhenOnlineStore.markSent(context, dispatched);
                SendWhenOnlineStore.notifyChanged();
            }
        } catch (Throwable ignored) {
        }
    }

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
     *  Message Filters live gate + KeywordRadar / snapshots / Auto-Saver. */
    public static void onNewMessages(ArrayList<MessageObject> messages, int account) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || messages == null || messages.isEmpty()) {
                return;
            }
            if (moduleEnabled("message_filters")) {
                applyMessageFilters(context, messages);
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
     *  head):
     *  1. Phase A.4: when an enabled JS plugin handles a '/'-command,
     *     returns {@code true} so SendMessagesHelper suppresses the literal
     *     command message from posting to the chat (fixes the /tpl wart).
     *  2. Phase B.1 (AyuForward): when forwarding messages from a restricted
     *     ("noforwards") source while AyuForward is enabled, intercepts the
     *     forward and re-sends the messages/media as the user's own copy,
     *     returning {@code true} so the server-rejected forward RPC is skipped.
     *  3. Phase B.2 (Forward as Copy): when Forward as Copy is enabled on a
     *     forward batch, strips attribution (drop_author) and optionally
     *     captions (drop_caption). */
    public static boolean onOutgoingMessage(SendMessagesHelper.SendMessageParams params) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || params == null) {
                return false;
            }
            // 1. Outgoing '/' command suppression for JS plugins (Phase A.4)
            if (params.message != null && params.message.startsWith("/")) {
                if (JsPluginManager.hasEnabledPlugins(context)) {
                    if (JsPluginManager.dispatchOutgoingCommand(params)) {
                        return true;
                    }
                }
            }
            // 2. AyuForward & Forward-as-Copy on outgoing forwards (Phase B.1 & B.2)
            if (params.forwardingMessages != null && !params.forwardingMessages.isEmpty()) {
                int account = UserConfig.selectedAccount;
                MessageObject first = params.forwardingMessages.get(0);
                if (first != null) {
                    account = first.currentAccount;
                }
                boolean hasRestricted = false;
                for (MessageObject msg : params.forwardingMessages) {
                    if (isRestrictedMessage(account, msg)) {
                        hasRestricted = true;
                        break;
                    }
                }
                boolean ayuOn = ayuForwardEnabled();
                boolean copyOn = forwardAsCopyEnabled();
                if (hasRestricted && ayuOn) {
                    boolean dropCap = copyOn && ForwardAsCopyModule.dropCaption(context);
                    ArrayList<MessageObject> toSend = new ArrayList<>(params.forwardingMessages);
                    final int accFinal = account;
                    final long peerFinal = params.peer;
                    AndroidUtilities.runOnUIThread(() ->
                            forwardMessagesAsOwnCopy(null, accFinal, toSend, peerFinal, dropCap));
                    return true;
                }
                if (copyOn) {
                    boolean dropAuth = ForwardAsCopyModule.dropAuthor(context);
                    boolean dropCap = ForwardAsCopyModule.dropCaption(context);
                    if (dropAuth || dropCap) {
                        boolean appliedToParams = applyForwardAsCopyFlags(params, dropAuth, dropCap);
                        if (!appliedToParams && dropAuth) {
                            ArrayList<MessageObject> toSend = new ArrayList<>(params.forwardingMessages);
                            final int accFinal = account;
                            final long peerFinal = params.peer;
                            AndroidUtilities.runOnUIThread(() ->
                                    forwardMessagesAsOwnCopy(null, accFinal, toSend, peerFinal, dropCap));
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Phase B.1 & B.2: AyuForward + Forward as Copy implementation
    // ------------------------------------------------------------------

    public static boolean isRestrictedMessage(int account, MessageObject messageObject) {
        try {
            if (messageObject == null) {
                return false;
            }
            if (messageObject.messageOwner != null && messageObject.messageOwner.noforwards) {
                return true;
            }
            long dialogId = messageObject.getDialogId();
            if (dialogId != 0) {
                return MessagesController.getInstance(account).isPeerNoForwards(dialogId);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasCopyableText(MessageObject msg) {
        String text = extractPlainMessageText(msg);
        return text != null && !text.isEmpty();
    }

    private static String extractPlainMessageText(MessageObject msg) {
        if (msg == null) {
            return null;
        }
        if (msg.messageOwner != null && msg.messageOwner.message != null
                && !msg.messageOwner.message.isEmpty()) {
            CharSequence orig = MessageFiltersModule.getOriginalText(msg.getDialogId(), msg.getId());
            if (orig != null) {
                return orig.toString();
            }
            return msg.messageOwner.message;
        }
        if (msg.messageText != null && msg.messageText.length() > 0) {
            return msg.messageText.toString();
        }
        if (msg.caption != null && msg.caption.length() > 0) {
            return msg.caption.toString();
        }
        return null;
    }

    private static boolean applyForwardAsCopyFlags(SendMessagesHelper.SendMessageParams params,
                                                   boolean dropAuthor,
                                                   boolean dropCaption) {
        boolean any = false;
        try {
            if (dropAuthor) {
                Field f = SendMessagesHelper.SendMessageParams.class.getDeclaredField("hideForwardSendersName");
                f.setAccessible(true);
                f.setBoolean(params, true);
                any = true;
            }
            if (dropCaption) {
                Field fCap = SendMessagesHelper.SendMessageParams.class.getDeclaredField("hideCaption");
                fCap.setAccessible(true);
                fCap.setBoolean(params, true);
                any = true;
            }
        } catch (Throwable ignored) {
        }
        return any;
    }

    public static void promptForwardAsCopy(BaseFragment fragment,
                                           MessageObject messageObject,
                                           boolean dropCaption) {
        try {
            if (fragment == null || messageObject == null) {
                return;
            }
            final int account = fragment.getCurrentAccount();
            final ArrayList<MessageObject> list = new ArrayList<>(1);
            list.add(messageObject);
            String title = isRestrictedMessage(account, messageObject)
                    ? "AyuForward — pick target chat"
                    : "Forward as Copy — pick target chat";
            fragment.presentFragment(new AminDialogPickerActivity(title, false,
                    (dialogId, name) -> forwardMessagesAsOwnCopy(fragment, account, list, dialogId, dropCaption)));
        } catch (Throwable ignored) {
        }
    }

    /** Re-sends one or more messages (including restricted noforwards posts)
     *  as fresh user-owned copies to {@code targetPeer}. Media already in
     *  Telegram's local cache is staged and re-uploaded via
     *  {@code SendMessagesHelper.prepareSending*}; uncached media is requested
     *  at high priority from {@code FileLoader} and re-uploaded as soon as
     *  the download finishes. */
    public static void forwardMessagesAsOwnCopy(BaseFragment fragment,
                                                int account,
                                                List<MessageObject> messages,
                                                long targetPeer,
                                                boolean dropCaption) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || messages == null || messages.isEmpty() || targetPeer == 0) {
                return;
            }
            final AccountInstance accountInstance = AccountInstance.getInstance(account);
            final boolean allowMedia = !ayuForwardEnabled() || AyuForwardModule.reuploadMedia(context);
            int sentCount = 0;
            int downloadingCount = 0;

            for (MessageObject msg : messages) {
                if (msg == null || msg.messageOwner == null) {
                    continue;
                }
                String text = dropCaption ? "" : extractPlainMessageText(msg);
                if (text == null) {
                    text = "";
                }
                ArrayList<TLRPC.MessageEntity> entities = (!dropCaption && msg.messageOwner.entities != null
                        && !msg.messageOwner.entities.isEmpty())
                        ? new ArrayList<>(msg.messageOwner.entities) : null;

                TLRPC.MessageMedia media = MessageObject.getMedia(msg.messageOwner);
                boolean hasMedia = media != null
                        && !(media instanceof TLRPC.TL_messageMediaEmpty)
                        && !(media instanceof TLRPC.TL_messageMediaWebPage);

                if (hasMedia && allowMedia) {
                    File cachedFile = findCachedMediaFile(account, msg);
                    if (cachedFile != null && cachedFile.isFile() && cachedFile.length() > 0) {
                        if (stageAndSendMediaCopy(context, accountInstance, msg, cachedFile,
                                targetPeer, text, entities)) {
                            sentCount++;
                            continue;
                        }
                    } else {
                        // Start download and stage once cached on disk
                        triggerMediaDownloadAndResend(context, accountInstance, account, msg,
                                targetPeer, text, entities);
                        downloadingCount++;
                        continue;
                    }
                }

                if (!text.isEmpty()) {
                    SendMessagesHelper.SendMessageParams sendParams =
                            SendMessagesHelper.SendMessageParams.of(
                                    text, targetPeer, null, null, null, true,
                                    entities, null, null, true, 0, 0, null, false);
                    SendMessagesHelper.getInstance(account).sendMessage(sendParams);
                    sentCount++;
                }
            }

            if (fragment != null) {
                if (downloadingCount > 0) {
                    BulletinFactory.of(fragment).createSimpleBulletin(
                            R.raw.ic_download,
                            "Downloading restricted media — will send copy automatically").show();
                } else if (sentCount > 0) {
                    long selfId = UserConfig.getInstance(account).getClientUserId();
                    String notice = targetPeer == selfId
                            ? "Saved to Saved Messages"
                            : "Sent as copy";
                    BulletinFactory.of(fragment).createSimpleBulletin(
                            R.raw.contact_check, notice).show();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static File findCachedMediaFile(int account, MessageObject msg) {
        try {
            FileLoader loader = FileLoader.getInstance(account);
            File path = loader.getPathToMessage(msg.messageOwner);
            if (path != null && path.isFile() && path.length() > 0) {
                return path;
            }
            TLRPC.Document doc = msg.getDocument();
            if (doc != null) {
                File attach = loader.getPathToAttach(doc, true);
                if (attach != null && attach.isFile() && attach.length() > 0) {
                    return attach;
                }
            }
            if (msg.getPhoto() != null && msg.getPhoto().sizes != null) {
                TLRPC.PhotoSize best = FileLoader.getClosestPhotoSizeWithSize(msg.getPhoto().sizes, 1280);
                if (best != null) {
                    File attach = loader.getPathToAttach(best, true);
                    if (attach != null && attach.isFile() && attach.length() > 0) {
                        return attach;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void triggerMediaDownloadAndResend(Context context,
                                                      AccountInstance accountInstance,
                                                      int account,
                                                      MessageObject msg,
                                                      long targetPeer,
                                                      String caption,
                                                      ArrayList<TLRPC.MessageEntity> entities) {
        try {
            FileLoader loader = FileLoader.getInstance(account);
            TLRPC.Document doc = msg.getDocument();
            if (doc != null) {
                loader.loadFile(doc, msg, FileLoader.PRIORITY_HIGH, 1);
            } else if (msg.getPhoto() != null && msg.getPhoto().sizes != null) {
                TLRPC.Photo photo = msg.getPhoto();
                TLRPC.PhotoSize best = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
                if (best != null) {
                    loader.loadFile(ImageLocation.getForPhoto(best, photo), msg, "jpg",
                            FileLoader.PRIORITY_HIGH, 1);
                }
            }
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    long deadline = System.currentTimeMillis() + 25_000L;
                    while (System.currentTimeMillis() < deadline) {
                        File ready = findCachedMediaFile(account, msg);
                        if (ready != null && ready.isFile() && ready.length() > 0) {
                            final File found = ready;
                            AndroidUtilities.runOnUIThread(() ->
                                    stageAndSendMediaCopy(context, accountInstance, msg, found,
                                            targetPeer, caption, entities));
                            return;
                        }
                        Thread.sleep(250);
                    }
                    // Fallback: if media could not download in time, still send caption if any
                    if (caption != null && !caption.isEmpty()) {
                        AndroidUtilities.runOnUIThread(() -> {
                            SendMessagesHelper.SendMessageParams sendParams =
                                    SendMessagesHelper.SendMessageParams.of(
                                            caption, targetPeer, null, null, null, true,
                                            entities, null, null, true, 0, 0, null, false);
                            SendMessagesHelper.getInstance(account).sendMessage(sendParams);
                        });
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean stageAndSendMediaCopy(Context context,
                                                 AccountInstance accountInstance,
                                                 MessageObject msg,
                                                 File sourceFile,
                                                 long targetPeer,
                                                 String caption,
                                                 ArrayList<TLRPC.MessageEntity> entities) {
        try {
            File dir = new File(context.getCacheDir(), "amin_ayuforward");
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            // Sweep files older than 1 hour
            File[] old = dir.listFiles();
            if (old != null) {
                long cutoff = System.currentTimeMillis() - 3600_000L;
                for (File f : old) {
                    if (f.isFile() && f.lastModified() < cutoff) {
                        f.delete();
                    }
                }
            }
            String srcName = sourceFile.getName();
            String ext = srcName.contains(".")
                    ? srcName.substring(srcName.lastIndexOf('.') + 1)
                    : (msg.isPhoto() ? "jpg" : (msg.isVideo() ? "mp4" : "bin"));
            File staged = new File(dir, "copy_" + msg.getId() + "_" + System.currentTimeMillis() + "." + ext);
            try (FileInputStream in = new FileInputStream(sourceFile);
                 FileOutputStream out = new FileOutputStream(staged)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            if (!staged.isFile() || staged.length() == 0) {
                return false;
            }
            final String path = staged.getAbsolutePath();
            TLRPC.Document doc = msg.getDocument();
            final String mime = (doc != null && doc.mime_type != null && !doc.mime_type.isEmpty())
                    ? doc.mime_type : "application/octet-stream";
            if (msg.isPhoto()) {
                SendMessagesHelper.prepareSendingPhoto(accountInstance,
                        path, null, targetPeer, null, null, null,
                        caption, entities, null, null, 0, null,
                        true, 0, 0, null);
            } else if (msg.isVideo() || msg.isGif() || msg.isRoundVideo()) {
                SendMessagesHelper.prepareSendingVideo(accountInstance,
                        path, null, null, null, targetPeer, null, null,
                        null, null, entities, 0, null, true, 0, 0,
                        false, false, caption, null, 0, 0);
            } else {
                SendMessagesHelper.prepareSendingDocument(accountInstance,
                        path, path, null, caption, mime, targetPeer,
                        null, null, null, null, entities,
                        true, 0, null, null, false);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Phase B.3: Message Filters (keyword & sender filter + tap-to-reveal)
    // ------------------------------------------------------------------

    public static void applyMessageFilters(Context context, List<MessageObject> messages) {
        try {
            if (context == null || messages == null || messages.isEmpty()) {
                return;
            }
            if (!PluginRegistry.isEnabledById(context, "message_filters")) {
                return;
            }
            for (MessageObject msg : messages) {
                applySingleMessageFilter(context, msg);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void applySingleMessageFilter(Context context, MessageObject msg) {
        try {
            if (context == null || msg == null || msg.messageOwner == null || msg.isOut()) {
                return;
            }
            long dialogId = msg.getDialogId();
            int msgId = msg.getId();
            if (MessageFiltersModule.isRevealed(dialogId, msgId)) {
                return;
            }
            CharSequence currentText = msg.messageText != null && msg.messageText.length() > 0
                    ? msg.messageText
                    : (msg.caption != null && msg.caption.length() > 0
                    ? msg.caption : msg.messageOwner.message);
            if (currentText != null && currentText.toString().startsWith(MessageFiltersModule.PLACEHOLDER_PREFIX)) {
                return;
            }
            String reason = MessageFiltersModule.matchReason(
                    context, dialogId, msg.getSenderId(), currentText);
            if (reason == null) {
                return;
            }
            if (currentText != null) {
                MessageFiltersModule.rememberOriginalText(dialogId, msgId, currentText);
            }
            String placeholder = MessageFiltersModule.placeholderFor(reason);
            msg.messageText = placeholder;
            if (msg.caption != null) {
                msg.caption = placeholder;
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isMessageFiltered(MessageObject msg) {
        if (msg == null) {
            return false;
        }
        if (msg.messageText != null
                && msg.messageText.toString().startsWith(MessageFiltersModule.PLACEHOLDER_PREFIX)) {
            return true;
        }
        return msg.caption != null
                && msg.caption.toString().startsWith(MessageFiltersModule.PLACEHOLDER_PREFIX);
    }

    public static boolean revealFilteredMessage(BaseFragment fragment, MessageObject msg) {
        try {
            if (msg == null) {
                return false;
            }
            long dialogId = msg.getDialogId();
            int msgId = msg.getId();
            MessageFiltersModule.reveal(dialogId, msgId);
            CharSequence orig = MessageFiltersModule.getOriginalText(dialogId, msgId);
            if (orig == null && msg.messageOwner != null && msg.messageOwner.message != null) {
                orig = msg.messageOwner.message;
            }
            if (orig != null) {
                msg.messageText = orig;
                if (msg.caption != null) {
                    msg.caption = orig;
                }
                try {
                    msg.generateLayout(null);
                } catch (Throwable ignored) {
                }
            }
            NotificationCenter.getInstance(msg.currentAccount).postNotificationName(
                    NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_MESSAGE_TEXT);
            if (fragment != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(
                        R.raw.contact_check, "Filtered message revealed").show();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Phase B.4: Folder & Chat-List QoL Pack
    // ------------------------------------------------------------------

    public static void jumpToChatBeginning(BaseFragment fragment, long dialogId) {
        try {
            if (fragment == null || dialogId == 0) {
                return;
            }
            if (fragment instanceof ChatActivity) {
                ChatActivity chat = (ChatActivity) fragment;
                try {
                    chat.scrollToMessageId(1, 0, true, 0, true, 0);
                    return;
                } catch (Throwable ignored) {
                }
            }
            fragment.presentFragment(ChatActivity.of(dialogId, 1));
        } catch (Throwable ignored) {
        }
    }

    public static void showMarkFolderReadDialog(BaseFragment fragment, int account) {
        try {
            if (fragment == null || fragment.getParentActivity() == null) {
                return;
            }
            MessagesController controller = MessagesController.getInstance(account);
            ArrayList<MessagesController.DialogFilter> filters = controller.dialogFilters;
            if (filters == null || filters.isEmpty()) {
                BulletinFactory.of(fragment).createSimpleBulletin(
                        R.raw.info, "No chat folders configured").show();
                return;
            }
            List<MessagesController.DialogFilter> usable = new ArrayList<>();
            List<CharSequence> labels = new ArrayList<>();
            for (MessagesController.DialogFilter f : filters) {
                if (f == null || f.isDefault()) {
                    continue;
                }
                int unread = countUnreadInFilter(f);
                usable.add(f);
                String name = f.name == null || f.name.isEmpty() ? "Folder" : f.name;
                labels.add(name + (unread > 0 ? " (" + unread + " unread)" : " (all read)"));
            }
            if (usable.isEmpty()) {
                BulletinFactory.of(fragment).createSimpleBulletin(
                        R.raw.info, "No custom folders found").show();
                return;
            }
            labels.add("Mark ALL folders as read");
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
            builder.setTitle("Mark folder as read");
            builder.setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                if (which < 0) {
                    return;
                }
                int marked = 0;
                if (which == usable.size()) {
                    for (MessagesController.DialogFilter f : usable) {
                        marked += markFolderAsRead(account, f);
                    }
                } else if (which < usable.size()) {
                    marked = markFolderAsRead(account, usable.get(which));
                }
                BulletinFactory.of(fragment).createSimpleBulletin(
                        R.raw.contact_check,
                        marked > 0 ? ("Marked " + marked + " chat(s) as read")
                                : "All chats in folder are already read").show();
            });
            builder.setNegativeButton("Cancel", null);
            fragment.showDialog(builder.create());
        } catch (Throwable ignored) {
        }
    }

    private static int countUnreadInFilter(MessagesController.DialogFilter filter) {
        int count = 0;
        if (filter != null && filter.dialogs != null) {
            for (TLRPC.Dialog d : filter.dialogs) {
                if (d != null && (d.unread_count > 0 || d.unread_mark
                        || d.unread_mentions_count > 0 || d.unread_reactions_count > 0)) {
                    count++;
                }
            }
        }
        return count;
    }

    public static int markFolderAsRead(int account, MessagesController.DialogFilter filter) {
        int marked = 0;
        try {
            if (filter == null || filter.dialogs == null) {
                return 0;
            }
            MessagesController controller = MessagesController.getInstance(account);
            ArrayList<TLRPC.Dialog> snapshot = new ArrayList<>(filter.dialogs);
            for (TLRPC.Dialog d : snapshot) {
                if (d == null) {
                    continue;
                }
                boolean hasUnread = d.unread_count > 0 || d.unread_mark
                        || d.unread_mentions_count > 0 || d.unread_reactions_count > 0;
                if (!hasUnread) {
                    continue;
                }
                controller.markDialogAsRead(
                        d.id, d.top_message, d.top_message, d.last_message_date,
                        false, 0, 0, true, 0);
                if (d.unread_mentions_count > 0) {
                    controller.markMentionsAsRead(d.id, 0);
                }
                if (d.unread_reactions_count > 0) {
                    controller.markReactionsAsRead(d.id, 0);
                }
                marked++;
            }
        } catch (Throwable ignored) {
        }
        return marked;
    }

    // ------------------------------------------------------------------
    // Phase B.5: Configurable Double-Tap & Swipe Actions (patch 0024)
    // ------------------------------------------------------------------

    private static final Set<View> attachedGestureCells =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Called from ChatMessageCell.onAttachedToWindow (patch 0024).
     *  Also applies Message Filters if the bound MessageObject matches a rule
     *  and attaches a non-intrusive double-tap detector that delegates all
     *  other touches to ChatMessageCell's stock handler. */
    public static void onCellAttached(final ChatMessageCell cell) {
        try {
            if (cell == null) {
                return;
            }
            Context context = ApplicationLoader.applicationContext;
            if (context != null && moduleEnabled("message_filters")) {
                MessageObject msg = cell.getMessageObject();
                if (msg != null) {
                    applySingleMessageFilter(context, msg);
                }
            }
            if (!attachedGestureCells.add(cell)) {
                return;
            }
            final GestureDetector detector = new GestureDetector(cell.getContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            try {
                                MessageObject msg = cell.getMessageObject();
                                if (msg == null) {
                                    return false;
                                }
                                BaseFragment fragment = LaunchActivity.getLastFragment();
                                if (isMessageFiltered(msg)) {
                                    return revealFilteredMessage(fragment, msg);
                                }
                                if (gestureActionsEnabled()) {
                                    return onMessageDoubleTap(fragment, msg);
                                }
                            } catch (Throwable ignored) {
                            }
                            return false;
                        }
                    });
            cell.setOnTouchListener((v, event) -> {
                try {
                    if (gestureActionsEnabled() || messageFiltersEnabled()) {
                        detector.onTouchEvent(event);
                    }
                } catch (Throwable ignored) {
                }
                return false;
            });
        } catch (Throwable ignored) {
        }
    }

    public static boolean onMessageDoubleTap(BaseFragment fragment, MessageObject msg) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || msg == null || msg.messageOwner == null) {
                return false;
            }
            if (msg.isOutOwner() && GestureActionsModule.doubleTapOwnEdit(context)) {
                if (triggerChatActivityOption(fragment, msg, 12 /* OPTION_EDIT */)) {
                    return true;
                }
            }
            return executeQuickGestureAction(fragment, msg, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean executeQuickGestureAction(BaseFragment fragment,
                                                    MessageObject msg,
                                                    boolean fromSwipe) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || msg == null || msg.messageOwner == null) {
                return false;
            }
            int account = msg.currentAccount;
            if (fromSwipe) {
                int swipe = GestureActionsModule.swipeAction(context);
                switch (swipe) {
                    case GestureActionsModule.SWIPE_SAVE:
                        return saveMessageToSavedMessages(fragment, account, msg);
                    case GestureActionsModule.SWIPE_TRANSLATE:
                        return translateMessageInline(fragment, msg);
                    case GestureActionsModule.SWIPE_FORWARD_COPY:
                        promptForwardAsCopy(fragment, msg, ForwardAsCopyModule.dropCaption(context));
                        return true;
                    case GestureActionsModule.SWIPE_REPLY:
                    default:
                        return triggerChatActivityOption(fragment, msg, 8 /* OPTION_REPLY */);
                }
            } else {
                int action = GestureActionsModule.doubleTapAction(context);
                switch (action) {
                    case GestureActionsModule.ACTION_REACT:
                        return sendQuickReaction(fragment, account, msg, "👍");
                    case GestureActionsModule.ACTION_REPLY:
                        return triggerChatActivityOption(fragment, msg, 8 /* OPTION_REPLY */);
                    case GestureActionsModule.ACTION_SAVE:
                        return saveMessageToSavedMessages(fragment, account, msg);
                    case GestureActionsModule.ACTION_TRANSLATE:
                        return translateMessageInline(fragment, msg);
                    case GestureActionsModule.ACTION_FORWARD_COPY:
                        promptForwardAsCopy(fragment, msg, ForwardAsCopyModule.dropCaption(context));
                        return true;
                    case GestureActionsModule.ACTION_NONE:
                    default:
                        return false;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean sendQuickReaction(BaseFragment fragment,
                                             int account,
                                             MessageObject msg,
                                             String emoji) {
        try {
            MessagesController controller = MessagesController.getInstance(account);
            TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
            if (msg.messageOwner.isThreadMessage && msg.messageOwner.fwd_from != null) {
                req.peer = controller.getInputPeer(msg.getFromChatId());
                req.msg_id = msg.messageOwner.fwd_from.saved_from_msg_id;
            } else {
                req.peer = controller.getInputPeer(msg.getDialogId());
                req.msg_id = msg.getId();
            }
            if (req.peer == null) {
                return false;
            }
            TLRPC.TL_reactionEmoji reaction = new TLRPC.TL_reactionEmoji();
            reaction.emoticon = emoji;
            req.reaction.add(reaction);
            req.flags |= 1;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (error == null && response instanceof TLRPC.Updates) {
                    controller.processUpdates((TLRPC.Updates) response, false);
                }
            });
            if (fragment != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(
                        R.raw.contact_check, "Reacted " + emoji).show();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean saveMessageToSavedMessages(BaseFragment fragment,
                                                      int account,
                                                      MessageObject msg) {
        try {
            long selfId = UserConfig.getInstance(account).getClientUserId();
            if (selfId == 0) {
                return false;
            }
            ArrayList<MessageObject> list = new ArrayList<>(1);
            list.add(msg);
            forwardMessagesAsOwnCopy(fragment, account, list, selfId, false);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean translateMessageInline(BaseFragment fragment, MessageObject msg) {
        try {
            if (fragment == null || fragment.getContext() == null) {
                return false;
            }
            String text = extractPlainMessageText(msg);
            if (text == null || text.isEmpty()) {
                return false;
            }
            TranslateAlert2.showAlert(
                    fragment.getContext(),
                    fragment,
                    fragment.getCurrentAccount(),
                    msg.getDialogId(),
                    msg.getId(),
                    null,
                    "en",
                    text,
                    null,
                    false,
                    null,
                    null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean triggerChatActivityOption(BaseFragment fragment,
                                                     MessageObject msg,
                                                     int optionCode) {
        try {
            if (!(fragment instanceof ChatActivity)) {
                return false;
            }
            ChatActivity chat = (ChatActivity) fragment;
            Field selectedField = ChatActivity.class.getDeclaredField("selectedObject");
            selectedField.setAccessible(true);
            selectedField.set(chat, msg);
            Method processMethod = ChatActivity.class.getDeclaredMethod("processSelectedOption", int.class);
            processMethod.setAccessible(true);
            processMethod.invoke(chat, optionCode);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Phase B.6: Download & Upload Speed Boost
    // ------------------------------------------------------------------

    public static int downloadSpeedBoostLevel() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null || !moduleEnabled("speed_boost")) {
            return SpeedBoostModule.LEVEL_OFF;
        }
        return SpeedBoostModule.downloadLevel(context);
    }

    public static boolean uploadSpeedBoostEnabled() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null || !moduleEnabled("speed_boost")) {
            return false;
        }
        return SpeedBoostModule.uploadBoost(context);
    }

    /** Keeps {@code MessagesController.getfileExperimentalParams} in sync
     *  with the user's Download Speed Boost setting so FileLoadOperation.updateParams()
     *  takes the fast 512 KB × 8 branch when Balanced or Extreme is enabled. */
    public static void syncSpeedBoostFlagOnController(Context context, int account) {
        try {
            int level = (context != null && PluginRegistry.isEnabledById(context, "speed_boost"))
                    ? SpeedBoostModule.downloadLevel(context)
                    : SpeedBoostModule.LEVEL_OFF;
            if (level >= SpeedBoostModule.LEVEL_BALANCED) {
                MessagesController.getInstance(account).getfileExperimentalParams = true;
            }
        } catch (Throwable ignored) {
        }
    }

    /** Called by FileLoadOperation.updateParams() when speed boost is active:
     *  returns the overridden big-chunk size in bytes (512 KB for Balanced,
     *  1024 KB for Extreme, or 0 for Off / forceSmallChunk). */
    public static int downloadBoostBigChunkBytes(boolean forceSmallChunk) {
        if (forceSmallChunk) {
            return 0;
        }
        int level = downloadSpeedBoostLevel();
        if (level == SpeedBoostModule.LEVEL_EXTREME) {
            return 1024 * 1024;
        }
        if (level == SpeedBoostModule.LEVEL_BALANCED) {
            return 1024 * 512;
        }
        return 0;
    }

    /** Returns the overridden maxDownloadRequestsBig count (8 for Balanced,
     *  12 for Extreme, or 0 for Off / forceSmallChunk). */
    public static int downloadBoostMaxRequests(boolean forceSmallChunk) {
        if (forceSmallChunk) {
            return 0;
        }
        int level = downloadSpeedBoostLevel();
        if (level == SpeedBoostModule.LEVEL_EXTREME) {
            return 12;
        }
        if (level == SpeedBoostModule.LEVEL_BALANCED) {
            return 8;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Hidden Chats (patch 0011, DialogsActivity.getDialogsArray).
    // ------------------------------------------------------------------

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
    // JS plugin message context-menu items (patch 0020)
    // ------------------------------------------------------------------

    public static final int OPTION_PLUGIN_CONTEXT_BASE = 300;
    public static final int MAX_PLUGIN_CONTEXT_ITEMS = 4;

    public static final class ContextItem {
        public final String pluginId;
        public final String label;

        public ContextItem(String pluginId, String label) {
            this.pluginId = pluginId;
            this.label = label;
        }
    }

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
    // messagesDidLoad observer: scan-on-chat-open (Keyword Radar),
    // Message Filters, and history snapshots (Anti-Delete / Edit History).
    // ------------------------------------------------------------------

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
                        if (PluginRegistry.isEnabledById(context, "message_filters")) {
                            applyMessageFilters(context, objects);
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
