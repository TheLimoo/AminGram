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
 * messages, BEFORE the notification dispatch. Everything here is
 * best-effort and exception-isolated: a mod bug must never break
 * upstream update handling.
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
        for (MessageObject obj : messages) {
            try {
                processOne(context, obj, radar, keywords, saver, anti);
            } catch (Throwable ignored) {
                // never break the upstream update flow
            }
        }
    }

    private static void processOne(Context context, MessageObject obj,
                                   boolean radar, List<String> keywords,
                                   boolean saver, boolean anti) {
        if (obj == null || obj.messageOwner == null) {
            return;
        }
        if (anti) {
            DeletedStore.snapshot(context, obj);
        }
        String text = obj.messageOwner.message;
        if (radar && text != null && !text.isEmpty()) {
            scanOne(context, obj, keywords);
        }
        if (saver && obj.isSecretMedia()) {
            saveSecretMedia(context, obj);
        }
    }

    /** Keyword Radar scan of one message (live feed). */
    public static void scanOne(Context context, MessageObject obj, List<String> keywords) {
        if (obj == null || obj.messageOwner == null || keywords == null || keywords.isEmpty()) {
            return;
        }
        String text = obj.messageOwner.message;
        if (text == null || text.isEmpty()) {
            return;
        }
        String hit = KeywordMatcher.matchedKeyword(text, keywords);
        if (hit != null) {
            String snippet = text.replace('\n', ' ');
            if (snippet.length() > 200) {
                snippet = snippet.substring(0, 200);
            }
            String record = obj.getDialogId() + "|" + obj.getId() + "|" + hit
                    + "|" + obj.messageOwner.date + "|" + snippet;
            VaultStore.addUnique(context, RADAR_PREFS, RADAR_HITS_KEY, record, 300);
        }
    }

    /** Keyword Radar scan of a batch of messages — used for the
     *  scan-on-chat-open feed (loaded history from messagesDidLoad).
     *  Dedupes against existing hits via VaultStore.addUnique, so
     *  rescanning the same history is harmless. */
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
        for (MessageObject obj : messages) {
            try {
                scanOne(context, obj, keywords);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Auto-Saver v3 capture: start the download NOW (the media may be
     *  destroyed at any moment) and record a rich gallery entry with
     *  the resolved cache path. Covers secret-chat TTL media and
     *  view-once media in normal chats (see class doc). */
    private static void saveSecretMedia(Context context, MessageObject obj) {
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
            VaultStore.addUnique(context, AUTOSAVER_PREFS, AUTOSAVER_ITEMS_KEY, record, 300);
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
                    VaultStore.addUnique(context, AUTOSAVER_PREFS, AUTOSAVER_ITEMS_KEY, record, 300);
                }
            }
        }
    }
}
