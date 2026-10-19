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
