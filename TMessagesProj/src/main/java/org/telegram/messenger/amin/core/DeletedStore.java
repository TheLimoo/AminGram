package org.telegram.messenger.amin.core;

import android.content.Context;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;

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
 *
 * Persistence model (Stage 1, audit D4): the snapshot list lives in
 * a synchronized in-memory cache, loaded from prefs on first use.
 * Live snapshot() calls mutate the cache ONLY — no per-message JSON
 * read/rewrite on the update thread. A debounced flush on
 * Utilities.stageQueue persists the whole list at most once per
 * quiet window. All other snapshot readers (delete marking, edit
 * capture) go through the same cache, so unflushed snapshots are
 * fully visible. Trade-off, accepted: a process death inside the
 * debounce window loses the newest snapshots; Anti-Delete's
 * live-controller fallback still covers deletes for messages the
 * controller holds in memory.
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

    // ---- Snapshot cache (Stage 1, audit D4) -------------------------

    private static final long SNAP_FLUSH_DELAY_MS = 750;
    private static List<String> snapCache;
    private static boolean snapDirty;
    private static boolean snapFlushScheduled;

    /** The live snapshot list, loaded from prefs on first use.
     *  Callers must hold the class lock (all entry points here are
     *  synchronized) and must NOT retain the list across calls. */
    private static List<String> snapshotCache(Context context) {
        if (snapCache == null) {
            snapCache = VaultStore.getAll(context, PREFS, SNAP_KEY);
        }
        return snapCache;
    }

    private static void markSnapDirty(Context context) {
        snapDirty = true;
        if (!snapFlushScheduled) {
            snapFlushScheduled = true;
            final Context appContext = context.getApplicationContext();
            Utilities.stageQueue.postRunnable(() -> flushSnapshots(appContext),
                    SNAP_FLUSH_DELAY_MS);
        }
    }

    /** Persists the cached snapshot list if it changed. Runs on
     *  stageQueue (debounce) or inline from markDeleted, where the
     *  snapshots are being consumed and must be durable first. */
    private static synchronized void flushSnapshots(Context context) {
        snapFlushScheduled = false;
        if (!snapDirty || snapCache == null || context == null) {
            return;
        }
        VaultStore.putAll(context, PREFS, SNAP_KEY, new ArrayList<>(snapCache));
        snapDirty = false;
    }

    private static void upsertSnapshot(Context context, MessageObject obj) {
        String record = toRecord(obj);
        String prefix = obj.getDialogId() + "|" + obj.getId() + "|";
        List<String> items = snapshotCache(context);
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).startsWith(prefix)) {
                items.remove(i);
            }
        }
        items.add(record);
        while (items.size() > SNAP_CAP) {
            items.remove(0);
        }
        markSnapDirty(context);
    }

    public static synchronized void snapshot(Context context, MessageObject obj) {
        if (context == null || obj == null || obj.messageOwner == null) {
            return;
        }
        upsertSnapshot(context, obj);
    }

    /** Batch snapshot (v1.3): one prefs read + one write for a whole
     *  list. Used by the messagesDidLoad observer so messages loaded
     *  from history when a chat is opened are snapshotted too — this is
     *  what gives Edit History its "before" text for messages that did
     *  not arrive live, and Anti-Delete coverage for scrolled history. */
    public static synchronized void snapshotAll(Context context, List<MessageObject> objects) {
        if (context == null || objects == null || objects.isEmpty()) {
            return;
        }
        for (MessageObject obj : objects) {
            if (obj == null || obj.messageOwner == null) {
                continue;
            }
            upsertSnapshot(context, obj);
        }
    }

    /** Moves snapshots of the deleted messages into the deleted list.
     *  Called from the MessagesController delete hook (FQ) before the
     *  upstream application block is skipped by the v1 guard. */
    public static synchronized void markDeleted(Context context, MessagesController controller,
                                   LongSparseArray<ArrayList<Integer>> deleted) {
        if (context == null || deleted == null) {
            return;
        }
        // Consume from the in-memory cache so snapshots taken inside
        // the current debounce window are found too; the consumed
        // list is flushed inline below, before the deleted list is
        // persisted, so the pair stays consistent.
        List<String> snaps = snapshotCache(context);
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
        if (snapsChanged) {
            snapDirty = true;
            flushSnapshots(context);
        }
        VaultStore.putAll(context, PREFS, DELETED_KEY, recovered);
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
            String title = resolveTitle(account, dialogId);
            String when = new java.text.SimpleDateFormat("HH:mm · dd MMM",
                    java.util.Locale.US).format(new java.util.Date(dateSec * 1000L));
            String change = parts[3];
            int arrow = change.indexOf(" -> ");
            if (arrow > 0) {
                change = "\"" + change.substring(0, arrow) + "\" → \""
                        + change.substring(arrow + 4) + "\"";
            }
            // v1.3.4 BiDi: an RTL (e.g. Persian) title otherwise
            // flips the whole line's visual order. LRM prefix +
            // the title wrapped in LTR isolates keeps the layout
            // "Title · time — text" in every language mix. Same
            // treatment in formatDeletedRecord / formatRadarHit.
            return "\u200E\u2066" + title + "\u2069" + " · " + when + " — " + change;
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
            String title = resolveTitle(account, dialogId);
            String when = new java.text.SimpleDateFormat("HH:mm \u00b7 dd MMM",
                    java.util.Locale.US).format(new java.util.Date(dateSec * 1000L));
            String body = parts[4];
            if (body == null || body.isEmpty()) {
                body = "[" + parts[3] + "]";
            }
            // v1.3.4 BiDi: LRM + LTR-isolated title (see
            // formatEditRecord for the rationale).
            return "\u200E\u2066" + title + "\u2069" + " \u00b7 " + when + " \u2014 " + body;
        } catch (Throwable t) {
            return record;
        }
    }

    // ---- Parsed history API (v2.0): the rich Deleted / Edit History
    // viewers work with structured entries instead of re-splitting the
    // raw pipe records in the UI. The storage format is UNCHANGED —
    // these are read/parse/delete helpers over the same VaultStore
    // lists the formatters above render.

    /** One parsed Deleted or Edit record. For deleted records
     *  {@link #text} / {@link #kind} are set; for edits
     *  {@link #oldText} / {@link #newText} are set (split on the
     *  first " -> " of the stored payload, exactly like
     *  formatEditRecord). {@link #dateSec} is the MESSAGE's own
     *  timestamp in both cases: the archival format stores no
     *  separate delete/edit moment. Malformed records still parse
     *  to an entry whose body is the raw record (dialogId 0), so
     *  the UI never silently drops a row. */
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

        /** Primary body: the deleted text, or the AFTER text for edits. */
        public String bodyText() {
            if (edit) {
                return newText != null ? newText : "";
            }
            return text != null ? text : "";
        }

        /** One-string snippet for cards and filter matching: the
         *  deleted body, or "old → new" for edits. */
        public String snippetText() {
            if (!edit) {
                return bodyText();
            }
            if (oldText == null || oldText.isEmpty()) {
                return bodyText();
            }
            return oldText + " → " + bodyText();
        }

        /** Text handed to the clipboard by the detail screen's
         *  Copy action: the full body, or labelled Before/After
         *  for edits. Never carries BiDi control characters. */
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

    /** All stored entries for {@code key} (DELETED_KEY or EDIT_KEY)
     *  in storage order (oldest first — the UI reverses). Any other
     *  key yields an empty list; snapshots are not history entries. */
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

    /** Removes ONE stored record (exact raw-string match) from the
     *  DELETED or EDIT list. Returns true when a row was removed.
     *  The snapshot list is never touched. */
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

    /** Display title for a dialog: the user's name for private
     *  dialogs, the chat title for groups/channels, "Chat <id>"
     *  when the peer is unknown to this account. This is the exact
     *  resolution formatEditRecord / formatDeletedRecord have
     *  always used (extracted in v2.0, output unchanged). */
    public static String chatTitle(int account, long dialogId) {
        return resolveTitle(account, dialogId);
    }

    /** Best-effort sender name for a record. The archival format
     *  stores no per-message from_id, so the sender can only be
     *  resolved at dialog level: for a private dialog the other
     *  user IS the sender side; for a group/channel the record is
     *  attributed to the chat itself. Same value as chatTitle —
     *  kept as a separate API so the UI's intent stays explicit. */
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
        List<String> snaps = snapshotCache(context);
        String prefix = dialogId + "|" + msgId + "|";
        for (String s : snaps) {
            if (s.startsWith(prefix)) {
                String[] parts = s.split("\\|", 5);
                return parts.length == 5 ? parts[4] : null;
            }
        }
        return null;
    }

    private static synchronized void updateSnapshotText(Context context, long dialogId, int msgId, int date, String newText) {
        List<String> snaps = snapshotCache(context);
        String prefix = dialogId + "|" + msgId + "|";
        for (int i = 0; i < snaps.size(); i++) {
            if (snaps.get(i).startsWith(prefix)) {
                snaps.set(i, dialogId + "|" + msgId + "|" + date + "|text|" + cap(newText, TEXT_CAP));
                markSnapDirty(context);
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
