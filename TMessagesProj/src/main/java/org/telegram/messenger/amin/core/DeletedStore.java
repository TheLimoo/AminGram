package org.telegram.messenger.amin.core;

import android.content.Context;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Anti-Delete v2 & Edit History persistence.
 *
 * Phase A optimizations (A-03, A-04, A-06, A-11, A-13):
 * - Snapshots are stored in a LinkedHashMap keyed by "dialogId|msgId"
 *   (insertion-ordered, capped at SNAP_CAP = 1000), turning upsert,
 *   findSnapshotText, updateSnapshotText, and takeSnapshot from O(1000)
 *   regex/prefix scans into O(1) map operations.
 * - flushSnapshots copies the snapshot values under the class lock with
 *   a monotonic dirty generation counter and writes to VaultStore outside
 *   the lock so live snapshots and edit lookups never block on JSON I/O.
 * - markDeleted consumes snapshots in O(1) under the lock and offloads
 *   the deleted-list + snapshot-list disk flushes to stageQueue.
 * - recordEdits synchronizes snapshot lookups/updates and coalesces
 *   all edit records in an update batch into a single addUniqueBatch write.
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

    private static final ThreadLocal<SimpleDateFormat> VIEWER_DATE_FORMAT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm \u00b7 dd MMM", Locale.US);
                }
            };

    private DeletedStore() {
    }

    // ---- Indexed snapshot cache (A-03, A-06, A-11, A-13) ------------

    private static final long SNAP_FLUSH_DELAY_MS = 750;
    private static volatile LinkedHashMap<String, String> snapMap;
    private static long snapDirtyGen;
    private static long snapFlushedGen;
    private static boolean snapFlushScheduled;

    private static String snapKey(long dialogId, int msgId) {
        return dialogId + "|" + msgId;
    }

    private static String extractKeyFromRecord(String record) {
        if (record == null) {
            return null;
        }
        int first = record.indexOf('|');
        if (first <= 0) {
            return null;
        }
        int second = record.indexOf('|', first + 1);
        if (second <= first + 1) {
            return null;
        }
        return record.substring(0, second);
    }

    /** Must be called while holding DeletedStore.class lock. */
    private static LinkedHashMap<String, String> ensureSnapMapLocked(Context context) {
        if (snapMap == null) {
            LinkedHashMap<String, String> loaded = new LinkedHashMap<>();
            for (String record : VaultStore.getAll(context, PREFS, SNAP_KEY)) {
                String k = extractKeyFromRecord(record);
                if (k != null) {
                    loaded.remove(k);
                    loaded.put(k, record);
                }
            }
            while (loaded.size() > SNAP_CAP) {
                Iterator<String> it = loaded.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                } else {
                    break;
                }
            }
            snapMap = loaded;
        }
        return snapMap;
    }

    /** Must be called while holding DeletedStore.class lock. */
    private static void markSnapDirtyLocked(Context context) {
        snapDirtyGen++;
        if (!snapFlushScheduled && context != null) {
            snapFlushScheduled = true;
            final Context appContext = context.getApplicationContext();
            Utilities.stageQueue.postRunnable(() -> flushSnapshots(appContext),
                    SNAP_FLUSH_DELAY_MS);
        }
    }

    /** Persists the cached snapshot list outside the class lock (A-06). */
    private static void flushSnapshots(Context context) {
        if (context == null) {
            return;
        }
        final List<String> copy;
        final long gen;
        synchronized (DeletedStore.class) {
            snapFlushScheduled = false;
            if (snapMap == null || snapDirtyGen == snapFlushedGen) {
                return;
            }
            gen = snapDirtyGen;
            copy = new ArrayList<>(snapMap.values());
        }
        VaultStore.putAll(context, PREFS, SNAP_KEY, copy);
        synchronized (DeletedStore.class) {
            if (gen > snapFlushedGen) {
                snapFlushedGen = gen;
            }
        }
    }

    private static void upsertSnapshotLocked(Context context, MessageObject obj) {
        String record = toRecord(obj);
        String key = snapKey(obj.getDialogId(), obj.getId());
        LinkedHashMap<String, String> map = ensureSnapMapLocked(context);
        map.remove(key);
        map.put(key, record);
        while (map.size() > SNAP_CAP) {
            Iterator<String> it = map.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            } else {
                break;
            }
        }
        markSnapDirtyLocked(context);
    }

    public static synchronized void snapshot(Context context, MessageObject obj) {
        if (context == null || obj == null || obj.messageOwner == null) {
            return;
        }
        upsertSnapshotLocked(context, obj);
    }

    /** Batch snapshot (v1.3, optimized in A-11): O(1) per message via
     *  LinkedHashMap index. */
    public static synchronized void snapshotAll(Context context, List<MessageObject> objects) {
        if (context == null || objects == null || objects.isEmpty()) {
            return;
        }
        for (MessageObject obj : objects) {
            if (obj == null || obj.messageOwner == null) {
                continue;
            }
            upsertSnapshotLocked(context, obj);
        }
    }

    /** Moves snapshots of the deleted messages into the deleted list (A-03).
     *  Consumes from the indexed in-memory map in O(1) per deleted id and
     *  offloads the disk write to stageQueue so the update thread never
     *  stalls on JSON serialization. */
    public static void markDeleted(Context context, MessagesController controller,
                                   LongSparseArray<ArrayList<Integer>> deleted) {
        if (context == null || deleted == null || deleted.size() == 0) {
            return;
        }
        final List<String> newlyDeleted = new ArrayList<>();
        final boolean snapsConsumed;
        synchronized (DeletedStore.class) {
            LinkedHashMap<String, String> map = ensureSnapMapLocked(context);
            boolean changed = false;
            for (int a = 0, size = deleted.size(); a < size; a++) {
                long key = deleted.keyAt(a);
                ArrayList<Integer> ids = deleted.valueAt(a);
                if (ids == null) {
                    continue;
                }
                for (int id : ids) {
                    String record = takeSnapshotLocked(map, key, id);
                    if (record != null) {
                        changed = true;
                    } else if (controller != null) {
                        MessageObject obj = findLive(controller, key, id);
                        if (obj != null) {
                            record = toRecord(obj);
                        }
                    }
                    if (record != null) {
                        newlyDeleted.add(record);
                    }
                }
            }
            if (changed) {
                snapDirtyGen++;
            }
            snapsConsumed = changed;
        }
        if (newlyDeleted.isEmpty() && !snapsConsumed) {
            return;
        }
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Utilities.stageQueue.postRunnable(() -> {
            try {
                if (snapsConsumed) {
                    flushSnapshots(appContext);
                }
                if (!newlyDeleted.isEmpty()) {
                    VaultStore.addUniqueBatch(appContext, PREFS, DELETED_KEY, newlyDeleted, DELETED_CAP);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    // ---- Edit History (v1.2, optimized in A-04 & A-13) --------------

    public static void recordEdits(Context context, ArrayList<org.telegram.tgnet.TLRPC.Update> updates) {
        if (context == null || updates == null || updates.isEmpty()) {
            return;
        }
        List<String> editBatch = null;
        synchronized (DeletedStore.class) {
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
                if (editBatch == null) {
                    editBatch = new ArrayList<>();
                }
                editBatch.add(record);
                updateSnapshotText(context, dialogId, msg.id, msg.date, newText);
            }
        }
        if (editBatch != null && !editBatch.isEmpty()) {
            final List<String> toWrite = editBatch;
            final Context appContext = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            Utilities.stageQueue.postRunnable(() -> {
                try {
                    VaultStore.addUniqueBatch(appContext, PREFS, EDIT_KEY, toWrite, EDIT_CAP);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    public static String formatEditRecord(int account, String record) {
        try {
            String[] parts = record.split("\\|", 4);
            if (parts.length < 4) {
                return record;
            }
            long dialogId = Long.parseLong(parts[0]);
            long dateSec = Long.parseLong(parts[2]);
            String title = resolveTitle(account, dialogId);
            String when = VIEWER_DATE_FORMAT.get().format(new Date(dateSec * 1000L));
            String change = parts[3];
            int arrow = change.indexOf(" -> ");
            if (arrow > 0) {
                change = "\"" + change.substring(0, arrow) + "\" \u2192 \""
                        + change.substring(arrow + 4) + "\"";
            }
            return "\u200E\u2066" + title + "\u2069" + " \u00b7 " + when + " \u2014 " + change;
        } catch (Throwable t) {
            return record;
        }
    }

    public static String formatDeletedRecord(int account, String record) {
        try {
            String[] parts = record.split("\\|", 5);
            if (parts.length < 5) {
                return record;
            }
            long dialogId = Long.parseLong(parts[0]);
            long dateSec = Long.parseLong(parts[2]);
            String title = resolveTitle(account, dialogId);
            String when = VIEWER_DATE_FORMAT.get().format(new Date(dateSec * 1000L));
            String body = parts[4];
            if (body == null || body.isEmpty()) {
                body = "[" + parts[3] + "]";
            }
            return "\u200E\u2066" + title + "\u2069" + " \u00b7 " + when + " \u2014 " + body;
        } catch (Throwable t) {
            return record;
        }
    }

    public static final class HistoryEntry {
        public final String raw;
        public final String storeKey;
        public final long dialogId;
        public final int msgId;
        public final long dateSec;
        public final String kind;
        public final String text;
        public final String oldText;
        public final String newText;
        public final boolean edit;

        private HistoryEntry(String raw, String storeKey, long dialogId, int msgId,
                             long dateSec, String kind, String text,
                             String oldText, String newText, boolean edit) {
            this.raw = raw;
            this.storeKey = storeKey;
            this.dialogId = dialogId;
            this.msgId = msgId;
            this.dateSec = dateSec;
            this.kind = kind;
            this.text = text;
            this.oldText = oldText;
            this.newText = newText;
            this.edit = edit;
        }

        public String bodyText() {
            if (edit) {
                return newText != null ? newText : "";
            }
            return text != null ? text : "";
        }

        public String snippetText() {
            if (!edit) {
                return bodyText();
            }
            if (oldText == null || oldText.isEmpty()) {
                return bodyText();
            }
            return oldText + " \u2192 " + bodyText();
        }

        public String copyText() {
            if (!edit) {
                return bodyText();
            }
            return "Before: " + (oldText != null ? oldText : "")
                    + "\nAfter: " + bodyText();
        }
    }

    public static HistoryEntry parseDeletedRecord(String record) {
        if (record == null) {
            record = "";
        }
        long dialogId = 0;
        int msgId = 0;
        long dateSec = 0;
        String kind = "text";
        String text = record;
        String[] parts = record.split("\\|", 5);
        if (parts.length == 5) {
            kind = parts[3];
            text = parts[4];
            try {
                dialogId = Long.parseLong(parts[0]);
                msgId = Integer.parseInt(parts[1]);
                dateSec = Long.parseLong(parts[2]);
            } catch (Throwable ignored) {
            }
        }
        if (kind == null || kind.isEmpty()) {
            kind = "text";
        }
        if (text == null) {
            text = "";
        }
        return new HistoryEntry(record, DELETED_KEY, dialogId, msgId, dateSec,
                kind, text, null, null, false);
    }

    public static HistoryEntry parseEditRecord(String record) {
        if (record == null) {
            record = "";
        }
        long dialogId = 0;
        int msgId = 0;
        long dateSec = 0;
        String oldText = "";
        String newText = record;
        String[] parts = record.split("\\|", 4);
        if (parts.length == 4) {
            String change = parts[3];
            int arrow = change.indexOf(" -> ");
            if (arrow >= 0) {
                oldText = change.substring(0, arrow);
                newText = change.substring(arrow + 4);
            } else {
                newText = change;
            }
            try {
                dialogId = Long.parseLong(parts[0]);
                msgId = Integer.parseInt(parts[1]);
                dateSec = Long.parseLong(parts[2]);
            } catch (Throwable ignored) {
            }
        }
        if (newText == null) {
            newText = "";
        }
        return new HistoryEntry(record, EDIT_KEY, dialogId, msgId, dateSec,
                "edit", null, oldText, newText, true);
    }

    public static List<HistoryEntry> getHistoryEntries(Context context, String key) {
        List<HistoryEntry> out = new ArrayList<>();
        if (context == null
                || (!DELETED_KEY.equals(key) && !EDIT_KEY.equals(key))) {
            return out;
        }
        for (String raw : VaultStore.getAll(context, PREFS, key)) {
            out.add(EDIT_KEY.equals(key)
                    ? parseEditRecord(raw) : parseDeletedRecord(raw));
        }
        return out;
    }

    public static boolean removeRecord(Context context, String key, String rawRecord) {
        if (context == null || rawRecord == null
                || (!DELETED_KEY.equals(key) && !EDIT_KEY.equals(key))) {
            return false;
        }
        List<String> items = VaultStore.getAll(context, PREFS, key);
        if (!items.remove(rawRecord)) {
            return false;
        }
        VaultStore.putAll(context, PREFS, key, items);
        return true;
    }

    public static boolean removeEntry(Context context, HistoryEntry entry) {
        return entry != null && removeRecord(context, entry.storeKey, entry.raw);
    }

    public static String chatTitle(int account, long dialogId) {
        return resolveTitle(account, dialogId);
    }

    public static String senderName(int account, long dialogId) {
        return resolveTitle(account, dialogId);
    }

    private static String resolveTitle(int account, long dialogId) {
        try {
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
            return title;
        } catch (Throwable t) {
            return "Chat " + dialogId;
        }
    }

    public static synchronized String findSnapshotText(Context context, long dialogId, int msgId) {
        LinkedHashMap<String, String> map = ensureSnapMapLocked(context);
        String s = map.get(snapKey(dialogId, msgId));
        if (s != null) {
            String[] parts = s.split("\\|", 5);
            return parts.length == 5 ? parts[4] : null;
        }
        return null;
    }

    private static synchronized void updateSnapshotText(Context context, long dialogId, int msgId, int date, String newText) {
        LinkedHashMap<String, String> map = ensureSnapMapLocked(context);
        String key = snapKey(dialogId, msgId);
        if (map.containsKey(key)) {
            map.put(key, dialogId + "|" + msgId + "|" + date + "|text|" + cap(newText, TEXT_CAP));
            markSnapDirtyLocked(context);
        }
    }

    private static String flatten(String text) {
        return text.replace('\n', ' ').replace('|', '/');
    }

    private static String cap(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String takeSnapshotLocked(LinkedHashMap<String, String> map, long dialogKey, int msgId) {
        if (dialogKey != 0) {
            String r = map.remove(snapKey(dialogKey, msgId));
            if (r != null) {
                return r;
            }
            return map.remove(snapKey(-dialogKey, msgId));
        }
        String suffix = "|" + msgId;
        Iterator<Map.Entry<String, String>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            if (entry.getKey().endsWith(suffix)) {
                String val = entry.getValue();
                it.remove();
                return val;
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
