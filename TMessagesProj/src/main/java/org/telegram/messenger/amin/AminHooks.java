package org.telegram.messenger.amin;

import android.content.Context;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.amin.core.DeletedStore;
import org.telegram.messenger.amin.core.DesignThemes;
import org.telegram.messenger.amin.core.HiddenChatsStore;
import org.telegram.messenger.amin.core.MessagePipeline;
import org.telegram.messenger.amin.core.SendWhenOnlineStore;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.messenger.amin.plugins.js.JsPluginManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;
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

    public static boolean localPremium() {
        return moduleEnabled("local_premium");
    }

    public static boolean ghostMode() {
        return moduleEnabled("ghost_mode");
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

    /** Design themes at app startup (patch 0015, LaunchActivity.
     *  onCreate right after ApplicationLoader.postInitApplication):
     *  the enabled design module (amin_design / ios_theme) owns the
     *  day theme — see amin.core.DesignThemes. Designs persist
     *  natively once applied, so this only reconciles drift (theme
     *  changed in Appearance while a design stayed on, or a restore
     *  that could not complete). Throwable-isolated like everything
     *  here: it must never break app startup. */
    public static void applyDesignOnStartup() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                DesignThemes.applyEnabledDesigns(context);
            }
        } catch (Throwable ignored) {
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
            List<SendWhenOnlineStore.Entry> pending = SendWhenOnlineStore.pendingFor(context, userId);
            if (pending.isEmpty()) {
                return;
            }
            for (SendWhenOnlineStore.Entry entry : pending) {
                SendMessagesHelper.getInstance(account)
                        .sendMessage(SendMessagesHelper.SendMessageParams.of(entry.text, entry.peer));
            }
            SendWhenOnlineStore.markSent(context, userId);
            SendWhenOnlineStore.notifyChanged();
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
     *  KeywordRadar live scan / snapshots / Auto-Saver. */
    public static void onNewMessages(ArrayList<MessageObject> messages, int account) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                MessagePipeline.process(context, messages);
            }
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

    /** Enabled menu plugins as {id, name} pairs, in install order.
     *  Pure prefs read — safe on the UI thread while the menu builds. */
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
    // messagesDidLoad observer: scan-on-chat-open (Keyword Radar) and
    // history snapshots (Anti-Delete / Edit History). Registered once,
    // lazily, for every activated account. Event-driven — it only runs
    // when Telegram itself loads a chat's messages.
    // ------------------------------------------------------------------

    private static volatile boolean observerRegistered;
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
        if (observerRegistered) {
            return;
        }
        synchronized (AminHooks.class) {
            if (observerRegistered) {
                return;
            }
            observerRegistered = true;
            try {
                int count = UserConfig.getActivatedAccountsCount();
                for (int account = 0; account < count; account++) {
                    if (UserConfig.getInstance(account).isClientActivated()) {
                        NotificationCenter.getInstance(account)
                                .addObserver(CHAT_LOAD_OBSERVER, NotificationCenter.messagesDidLoad);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
