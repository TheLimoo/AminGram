package org.telegram.messenger.amin.core;

import android.content.Context;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;

import java.util.ArrayList;
import java.util.List;

/**
 * Anti-Delete v2 persistence. While the module is enabled, the message
 * pipeline snapshots every incoming message (dialog, id, date, kind,
 * text) into a bounded local store that survives restarts. When a
 * server delete update arrives, the MessagesController hook hands the
 * deleted-id map here: matching snapshots are moved to the "deleted"
 * list (shown in the Plugins hub viewer), so the content stays
 * recoverable after the message is gone — and after app restarts.
 *
 * Records are pipe-joined: dialogId|msgId|date|kind|text
 * (text capped, newlines flattened). Snapshots upsert by dialog+id.
 * Falls back to the controller's in-memory message maps for messages
 * that were never snapshotted (e.g. received before the module was
 * enabled but still loaded in memory when the delete lands).
 */
public final class DeletedStore {

    public static final String PREFS = "amingram_antidelete";
    public static final String SNAP_KEY = "snapshots";
    public static final String DELETED_KEY = "deleted";
    public static final String EDIT_KEY = "edits";
    private static final int EDIT_CAP = 300;
    private static final int SNAP_CAP = 1000;
    private static final int DELETED_CAP = 500;
    private static final int TEXT_CAP = 500;

    private DeletedStore() {
    }

    public static void snapshot(Context context, MessageObject obj) {
        if (context == null || obj == null || obj.messageOwner == null) {
            return;
        }
        String record = toRecord(obj);
        String prefix = obj.getDialogId() + "|" + obj.getId() + "|";
        List<String> items = VaultStore.getAll(context, PREFS, SNAP_KEY);
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).startsWith(prefix)) {
                items.remove(i);
            }
        }
        items.add(record);
        while (items.size() > SNAP_CAP) {
            items.remove(0);
        }
        VaultStore.putAll(context, PREFS, SNAP_KEY, items);
    }

    /** Batch snapshot (v1.3): one prefs read + one write for a whole
     *  list. Used by the messagesDidLoad observer so messages loaded
     *  from history when a chat is opened are snapshotted too — this is
     *  what gives Edit History its "before" text for messages that did
     *  not arrive live, and Anti-Delete coverage for scrolled history. */
    public static void snapshotAll(Context context, List<MessageObject> objects) {
        if (context == null || objects == null || objects.isEmpty()) {
            return;
        }
        List<String> items = VaultStore.getAll(context, PREFS, SNAP_KEY);
        boolean changed = false;
        for (MessageObject obj : objects) {
            if (obj == null || obj.messageOwner == null) {
                continue;
            }
            String record = toRecord(obj);
            String prefix = obj.getDialogId() + "|" + obj.getId() + "|";
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).startsWith(prefix)) {
                    items.remove(i);
                }
            }
            items.add(record);
            changed = true;
        }
        if (!changed) {
            return;
        }
        while (items.size() > SNAP_CAP) {
            items.remove(0);
        }
        VaultStore.putAll(context, PREFS, SNAP_KEY, items);
    }

    /** Moves snapshots of the deleted messages into the deleted list.
     *  Called from the MessagesController delete hook (FQ) before the
     *  upstream application block is skipped by the v1 guard. */
    public static void markDeleted(Context context, MessagesController controller,
                                   LongSparseArray<ArrayList<Integer>> deleted) {
        if (context == null || deleted == null) {
            return;
        }
        List<String> snaps = VaultStore.getAll(context, PREFS, SNAP_KEY);
        List<String> recovered = VaultStore.getAll(context, PREFS, DELETED_KEY);
        boolean snapsChanged = false;
        for (int a = 0, size = deleted.size(); a < size; a++) {
            long key = deleted.keyAt(a);
            ArrayList<Integer> ids = deleted.valueAt(a);
            if (ids == null) {
                continue;
            }
            for (int id : ids) {
                String record = takeSnapshot(snaps, key, id);
                if (record != null) {
                    snapsChanged = true;
                } else if (controller != null) {
                    MessageObject obj = findLive(controller, key, id);
                    if (obj != null) {
                        record = toRecord(obj);
                    }
                }
                if (record != null && !recovered.contains(record)) {
                    recovered.add(record);
                }
            }
        }
        while (recovered.size() > DELETED_CAP) {
            recovered.remove(0);
        }
        VaultStore.putAll(context, PREFS, DELETED_KEY, recovered);
        if (snapsChanged) {
            VaultStore.putAll(context, PREFS, SNAP_KEY, snaps);
        }
    }

    // ---- Edit History (v1.2): pre-edit snapshots double as the "before"
    // text. Fed by AminHooks.onRawUpdates with the raw update list BEFORE
    // upstream applies the edits; records land in PREFS/EDIT_KEY and are
    // shown by the hub's "Edit history" viewer.

    public static void recordEdits(Context context, ArrayList<org.telegram.tgnet.TLRPC.Update> updates) {
        if (context == null || updates == null) {
            return;
        }
        for (org.telegram.tgnet.TLRPC.Update update : updates) {
            org.telegram.tgnet.TLRPC.Message msg = null;
            if (update instanceof org.telegram.tgnet.tl.TL_update.TL_updateEditMessage) {
                msg = ((org.telegram.tgnet.tl.TL_update.TL_updateEditMessage) update).message;
            } else if (update instanceof org.telegram.tgnet.tl.TL_update.TL_updateEditChannelMessage) {
                msg = ((org.telegram.tgnet.tl.TL_update.TL_updateEditChannelMessage) update).message;
            }
            if (msg == null || msg.message == null || msg.message.isEmpty()) {
                continue;
            }
            long dialogId = 0;
            if (msg.peer_id != null) {
                if (msg.peer_id.channel_id != 0) {
                    dialogId = -msg.peer_id.channel_id;
                } else if (msg.peer_id.chat_id != 0) {
                    dialogId = -msg.peer_id.chat_id;
                } else if (msg.peer_id.user_id != 0) {
                    dialogId = msg.peer_id.user_id;
                }
            }
            if (dialogId == 0) {
                continue;
            }
            String oldText = findSnapshotText(context, dialogId, msg.id);
            String newText = flatten(msg.message);
            if (oldText == null || oldText.equals(newText)) {
                continue;
            }
            String record = dialogId + "|" + msg.id + "|" + msg.date + "|"
                    + cap(oldText, 220) + " -> " + cap(newText, 220);
            VaultStore.addUnique(context, PREFS, EDIT_KEY, record, EDIT_CAP);
            updateSnapshotText(context, dialogId, msg.id, msg.date, newText);
        }
    }

    /** Renders one raw edit record ("dialogId|msgId|date|old -> new")
     *  readably for the hub viewer: chat title + time + quoted change.
     *  Field report (v1.3): the viewer printed the raw record, which
     *  looked like an unreadable log line. Falls back to the raw
     *  record on any problem. */
    public static String formatEditRecord(int account, String record) {
        try {
            String[] parts = record.split("\\|", 4);
            if (parts.length < 4) {
                return record;
            }
            long dialogId = Long.parseLong(parts[0]);
            long dateSec = Long.parseLong(parts[2]);
            String title = null;
            org.telegram.messenger.MessagesController mc =
                    org.telegram.messenger.MessagesController.getInstance(account);
            if (dialogId > 0) {
                org.telegram.tgnet.TLRPC.User user = mc.getUser(dialogId);
                if (user != null) {
                    title = ((user.first_name != null ? user.first_name : "") + " "
                            + (user.last_name != null ? user.last_name : "")).trim();
                }
            } else if (dialogId < 0) {
                org.telegram.tgnet.TLRPC.Chat chat = mc.getChat(-dialogId);
                if (chat != null) {
                    title = chat.title;
                }
            }
            if (title == null || title.isEmpty()) {
                title = "Chat " + dialogId;
            }
            String when = new java.text.SimpleDateFormat("HH:mm · dd MMM",
                    java.util.Locale.US).format(new java.util.Date(dateSec * 1000L));
            String change = parts[3];
            int arrow = change.indexOf(" -> ");
            if (arrow > 0) {
                change = "\"" + change.substring(0, arrow) + "\" → \""
                        + change.substring(arrow + 4) + "\"";
            }
            return title + " · " + when + " — " + change;
        } catch (Throwable t) {
            return record;
        }
    }

    /** Viewer rendering for a DELETED record (dialogId|msgId|date|
     *  kind|text): "Title \u00b7 HH:mm \u00b7 dd MMM \u2014 text" instead of
     *  the raw pipe line (v1.3.2 audit fix \u2014 same readability problem
     *  Amin reported for Edit History). Raw record back on any problem. */
    public static String formatDeletedRecord(int account, String record) {
        try {
            String[] parts = record.split("\\|", 5);
            if (parts.length < 5) {
                return record;
            }
            long dialogId = Long.parseLong(parts[0]);
            long dateSec = Long.parseLong(parts[2]);
            String title = null;
            org.telegram.messenger.MessagesController mc =
                    org.telegram.messenger.MessagesController.getInstance(account);
            if (dialogId > 0) {
                org.telegram.tgnet.TLRPC.User user = mc.getUser(dialogId);
                if (user != null) {
                    title = ((user.first_name != null ? user.first_name : "") + " "
                            + (user.last_name != null ? user.last_name : "")).trim();
                }
            } else if (dialogId < 0) {
                org.telegram.tgnet.TLRPC.Chat chat = mc.getChat(-dialogId);
                if (chat != null) {
                    title = chat.title;
                }
            }
            if (title == null || title.isEmpty()) {
                title = "Chat " + dialogId;
            }
            String when = new java.text.SimpleDateFormat("HH:mm \u00b7 dd MMM",
                    java.util.Locale.US).format(new java.util.Date(dateSec * 1000L));
            String body = parts[4];
            if (body == null || body.isEmpty()) {
                body = "[" + parts[3] + "]";
            }
            return title + " \u00b7 " + when + " \u2014 " + body;
        } catch (Throwable t) {
            return record;
        }
    }

    public static String findSnapshotText(Context context, long dialogId, int msgId) {
        List<String> snaps = VaultStore.getAll(context, PREFS, SNAP_KEY);
        String prefix = dialogId + "|" + msgId + "|";
        for (String s : snaps) {
            if (s.startsWith(prefix)) {
                String[] parts = s.split("\\|", 5);
                return parts.length == 5 ? parts[4] : null;
            }
        }
        return null;
    }

    private static void updateSnapshotText(Context context, long dialogId, int msgId, int date, String newText) {
        List<String> snaps = VaultStore.getAll(context, PREFS, SNAP_KEY);
        String prefix = dialogId + "|" + msgId + "|";
        for (int i = 0; i < snaps.size(); i++) {
            if (snaps.get(i).startsWith(prefix)) {
                snaps.set(i, dialogId + "|" + msgId + "|" + date + "|text|" + cap(newText, TEXT_CAP));
                VaultStore.putAll(context, PREFS, SNAP_KEY, snaps);
                return;
            }
        }
    }

    private static String flatten(String text) {
        return text.replace('\n', ' ').replace('|', '/');
    }

    private static String cap(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String takeSnapshot(List<String> snaps, long dialogKey, int msgId) {
        for (int i = 0; i < snaps.size(); i++) {
            String[] parts = snaps.get(i).split("\\|", 4);
            if (parts.length < 3) {
                continue;
            }
            try {
                long dialogId = Long.parseLong(parts[0]);
                int id = Integer.parseInt(parts[1]);
                if (id == msgId && (dialogKey == 0 || dialogId == dialogKey || dialogId == -dialogKey)) {
                    return snaps.remove(i);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static MessageObject findLive(MessagesController controller, long dialogKey, int msgId) {
        if (dialogKey == 0) {
            return controller.dialogMessagesByIds.get(msgId);
        }
        ArrayList<MessageObject> objs = controller.dialogMessage.get(dialogKey);
        if (objs == null) {
            objs = controller.dialogMessage.get(-dialogKey);
        }
        if (objs != null) {
            for (MessageObject obj : objs) {
                if (obj != null && obj.getId() == msgId) {
                    return obj;
                }
            }
        }
        return controller.dialogMessagesByIds.get(msgId);
    }

    private static String toRecord(MessageObject obj) {
        String text = obj.messageOwner.message;
        String kind;
        if (text != null && !text.isEmpty()) {
            kind = "text";
        } else if (obj.isPhoto()) {
            kind = "photo";
            text = "[photo]";
        } else if (obj.getDocument() != null) {
            kind = "document";
            text = "[document]";
        } else {
            kind = "media";
            text = "[media]";
        }
        text = text.replace('\n', ' ').replace('|', '/');
        if (text.length() > TEXT_CAP) {
            text = text.substring(0, TEXT_CAP);
        }
        return obj.getDialogId() + "|" + obj.getId() + "|" + obj.messageOwner.date
                + "|" + kind + "|" + text;
    }
}
