package org.telegram.messenger.amin.core;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * AminGram incoming-message pipeline. Fed by a single hook in
 * MessagesController.processUpdateArray with the freshly arrived
 * messages, BEFORE the notification dispatch; since the pillar-4
 * audit (S2) the live feed is dispatched onto Utilities.globalQueue
 * by AminHooks.onNewMessages, so none of this runs on the update
 * thread. Everything here is best-effort and exception-isolated: a
 * mod bug must never break upstream update handling.
 *
 * - KeywordRadar: matches message text against user keywords (prefs
 *   "amingram_radar"/"keywords", comma or newline separated) and
 *   records hits in VaultStore ("amingram_radar"/"hits"). The same
 *   scanner is fed with loaded history when a chat is opened
 *   (AminHooks' messagesDidLoad observer), so keywords also match
 *   messages that arrived before the radar was configured.
 * - Snapshots (Anti-Delete / Edit History): every incoming message is
 *   snapshotted into DeletedStore while either module is on.
 * - AutoSaver v3: for self-destructing media — secret-chat TTL media
 *   AND "view once" media in normal chats (MessageObject.isSecretMedia
 *   covers both: for TL_message it checks media.ttl_seconds != 0) —
 *   the download is kicked off immediately so the file lands in the
 *   media cache before it expires, and a rich entry (with the resolved
 *   cache path) is recorded in AutoSaverStore for the gallery.
 *
 * ConfigVault was removed in v1.3 at the user's request; the pipeline
 * no longer extracts configs or links.
 */
public final class MessagePipeline {

    public static final String RADAR_PREFS = "amingram_radar";
    public static final String RADAR_KEYWORDS_KEY = "keywords";
    public static final String RADAR_HITS_KEY = "hits";
    public static final String AUTOSAVER_PREFS = "amingram_autosaver";
    public static final String AUTOSAVER_ITEMS_KEY = "items";

    private MessagePipeline() {
    }

    public static List<String> loadKeywords(Context context) {
        List<String> out = new ArrayList<>();
        String raw = context.getSharedPreferences(RADAR_PREFS, Context.MODE_PRIVATE)
                .getString(RADAR_KEYWORDS_KEY, null);
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[,\n]")) {
            String kw = part.trim();
            if (!kw.isEmpty()) {
                out.add(kw);
            }
        }
        return out;
    }

    public static String getKeywordsRaw(Context context) {
        return context.getSharedPreferences(RADAR_PREFS, Context.MODE_PRIVATE)
                .getString(RADAR_KEYWORDS_KEY, "");
    }

    public static void setKeywords(Context context, String raw) {
        context.getSharedPreferences(RADAR_PREFS, Context.MODE_PRIVATE)
                .edit().putString(RADAR_KEYWORDS_KEY, raw == null ? "" : raw).apply();
    }

    public static void process(Context context, List<MessageObject> messages) {
        if (context == null || messages == null || messages.isEmpty()) {
            return;
        }
        boolean radar = PluginRegistry.isEnabledById(context, "keyword_radar");
        boolean saver = PluginRegistry.isEnabledById(context, "auto_saver");
        boolean anti = PluginRegistry.isEnabledById(context, "anti_delete");
        boolean hist = PluginRegistry.isEnabledById(context, "edit_history");
        if (!radar && !saver && !anti && !hist) {
            return;
        }
        // Snapshots feed both Anti-Delete and Edit History (pre-edit text).
        if (hist) {
            anti = true;
        }
        List<String> keywords = radar ? loadKeywords(context) : null;
        if (radar && (keywords == null || keywords.isEmpty())) {
            radar = false;
        }
        // Write coalescing (pillar-4 audit S2): radar hits and
        // Auto-Saver legacy lines are collected and flushed ONCE per
        // batch (one read-modify-write per store key) instead of one
        // full-array rewrite per matched message.
        List<String> radarBatch = new ArrayList<>();
        List<String> saverBatch = new ArrayList<>();
        for (MessageObject obj : messages) {
            try {
                processOne(context, obj, radar, keywords, saver, anti, radarBatch, saverBatch);
            } catch (Throwable ignored) {
                // never break the upstream update flow
            }
        }
        try {
            VaultStore.addUniqueBatch(context, RADAR_PREFS, RADAR_HITS_KEY, radarBatch, 300);
            VaultStore.addUniqueBatch(context, AUTOSAVER_PREFS, AUTOSAVER_ITEMS_KEY, saverBatch, 300);
        } catch (Throwable ignored) {
        }
    }

    private static void processOne(Context context, MessageObject obj,
                                   boolean radar, List<String> keywords,
                                   boolean saver, boolean anti,
                                   List<String> radarBatch, List<String> saverBatch) {
        if (obj == null || obj.messageOwner == null) {
            return;
        }
        if (anti) {
            DeletedStore.snapshot(context, obj);
        }
        String text = obj.messageOwner.message;
        if (radar && text != null && !text.isEmpty()) {
            String record = radarRecord(obj, keywords);
            if (record != null) {
                radarBatch.add(record);
            }
        }
        if (saver && obj.isSecretMedia()) {
            saveSecretMedia(context, obj, saverBatch);
        }
    }

    /** The radar HIT record for one message, or null when it does
     *  not match — the record computation {@link #scanOne} writes,
     *  factored out so batch scans can collect records and flush
     *  them in a single store write. */
    private static String radarRecord(MessageObject obj, List<String> keywords) {
        if (obj == null || obj.messageOwner == null || keywords == null || keywords.isEmpty()) {
            return null;
        }
        String text = obj.messageOwner.message;
        if (text == null || text.isEmpty()) {
            return null;
        }
        String hit = KeywordMatcher.matchedKeyword(text, keywords);
        if (hit == null) {
            return null;
        }
        String snippet = text.replace('\n', ' ');
        if (snippet.length() > 200) {
            snippet = snippet.substring(0, 200);
        }
        return obj.getDialogId() + "|" + obj.getId() + "|" + hit
                + "|" + obj.messageOwner.date + "|" + snippet;
    }

    /** Keyword Radar scan of one message (live feed). */
    public static void scanOne(Context context, MessageObject obj, List<String> keywords) {
        String record = radarRecord(obj, keywords);
        if (record != null) {
            VaultStore.addUnique(context, RADAR_PREFS, RADAR_HITS_KEY, record, 300);
        }
    }

    /** Keyword Radar scan of a batch of messages — used for the
     *  scan-on-chat-open feed (loaded history from messagesDidLoad).
     *  Dedupes against existing hits via VaultStore.addUniqueBatch,
     *  so rescanning the same history is harmless. Records are
     *  collected across the whole batch and flushed in ONE store
     *  write (pillar-4 audit S2). */
    public static void scanRadar(Context context, List<MessageObject> messages) {
        if (context == null || messages == null || messages.isEmpty()) {
            return;
        }
        if (!PluginRegistry.isEnabledById(context, "keyword_radar")) {
            return;
        }
        List<String> keywords = loadKeywords(context);
        if (keywords.isEmpty()) {
            return;
        }
        List<String> batch = new ArrayList<>();
        for (MessageObject obj : messages) {
            try {
                String record = radarRecord(obj, keywords);
                if (record != null) {
                    batch.add(record);
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            VaultStore.addUniqueBatch(context, RADAR_PREFS, RADAR_HITS_KEY, batch, 300);
        } catch (Throwable ignored) {
        }
    }

    /** Viewer rendering for a radar HIT record (dialogId|msgId|
     *  keyword|date|snippet): "Title \u00b7 HH:mm \u00b7 dd MMM \u2014
     *  \u201ckeyword\u201d: snippet" instead of the raw pipe line (v1.3.2
     *  audit fix). Raw record back on any problem. */
    public static String formatRadarHit(int account, String record) {
        try {
            String[] parts = record.split("\\|", 5);
            if (parts.length < 5) {
                return record;
            }
            long dialogId = Long.parseLong(parts[0]);
            long dateSec = Long.parseLong(parts[3]);
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
            // v1.3.4 BiDi: LRM + LTR-isolated title (see
            // DeletedStore.formatEditRecord for the rationale).
            return "\u200E\u2066" + title + "\u2069" + " \u00b7 " + when + " \u2014 \u201c" + parts[2] + "\u201d: " + parts[4];
        } catch (Throwable t) {
            return record;
        }
    }

    /** Auto-Saver v3 capture: start the download NOW (the media may be
     *  destroyed at any moment) and record a rich gallery entry with
     *  the resolved cache path. Covers secret-chat TTL media and
     *  view-once media in normal chats (see class doc). Legacy log
     *  lines are appended to {@code legacySink} for the caller's
     *  single batched store write. */
    private static void saveSecretMedia(Context context, MessageObject obj, List<String> legacySink) {
        FileLoader loader = FileLoader.getInstance(obj.currentAccount);
        File path = loader.getPathToMessage(obj.messageOwner);
        TLRPC.Document document = obj.getDocument();
        if (document != null) {
            loader.loadFile(document, obj, FileLoader.PRIORITY_HIGH, 0);
            String name = obj.getFileName();
            AutoSaverStore.Entry e = new AutoSaverStore.Entry();
            e.dialogId = obj.getDialogId();
            e.id = obj.getId();
            e.date = obj.messageOwner.date;
            e.kind = "document";
            e.name = name != null ? name : "";
            e.path = path != null ? path.getAbsolutePath() : "";
            e.attach = FileLoader.getAttachFileName(document);
            AutoSaverStore.record(context, e);
            // Keep the legacy log line too (older viewers / counts).
            String record = obj.getDialogId() + "|" + obj.getId() + "|" + obj.messageOwner.date
                    + (name != null && !name.isEmpty() ? "|" + name : "");
            legacySink.add(record);
        } else {
            TLRPC.Photo photo = obj.getPhoto();
            if (photo != null && photo.sizes != null && !photo.sizes.isEmpty()) {
                // Use the same size getPathToMessage() resolves to, so the
                // recorded path is the file this download actually writes.
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(
                        photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
                if (size == null) {
                    size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
                }
                if (size != null) {
                    loader.loadFile(ImageLocation.getForPhoto(size, photo), obj, "jpg",
                            FileLoader.PRIORITY_HIGH, 0);
                    AutoSaverStore.Entry e = new AutoSaverStore.Entry();
                    e.dialogId = obj.getDialogId();
                    e.id = obj.getId();
                    e.date = obj.messageOwner.date;
                    e.kind = "photo";
                    e.name = "photo";
                    e.path = path != null ? path.getAbsolutePath() : "";
                    e.attach = FileLoader.getAttachFileName(size);
                    AutoSaverStore.record(context, e);
                    String record = obj.getDialogId() + "|" + obj.getId() + "|"
                            + obj.messageOwner.date + "|photo";
                    legacySink.add(record);
                }
            }
        }
    }
}
