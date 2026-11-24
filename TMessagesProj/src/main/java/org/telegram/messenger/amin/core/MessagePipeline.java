package org.telegram.messenger.amin.core;

import android.content.Context;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Single entry point for incoming message processing:
 * - Anti-Delete / Edit History text snapshots
 * - Keyword Radar matching (live + chat-open history scan)
 * - Auto-Saver for self-destruct / view-once media
 *
 * Optimization pass (Stage 2, A-23 & A-33):
 * - Keyword list and its pre-lowercased counterpart are cached in memory and
 *   recomputed only when {@link #setKeywords} writes a new raw string.
 * - Dead {@code scanOne} (which performed a full rewrite per hit and had zero
 *   callers) has been removed.
 * - Date formatting in {@link #formatRadarHit} reuses a {@code ThreadLocal}.
 */
public final class MessagePipeline {

    public static final String RADAR_PREFS = "amingram_radar";
    public static final String RADAR_KEYWORDS_KEY = "keywords";
    public static final String RADAR_HITS_KEY = "hits";

    public static final String AUTOSAVER_PREFS = "amingram_autosaver";
    public static final String AUTOSAVER_ITEMS_KEY = "items";

    private static final int CAP = 300;

    private static volatile String cachedRawKeywords;
    private static volatile List<String> cachedKeywords = Collections.emptyList();
    private static volatile List<String> cachedLowerKeywords = Collections.emptyList();

    private static final ThreadLocal<SimpleDateFormat> HIT_DATE_FMT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("MMM d, HH:mm", Locale.US);
                }
            };

    private MessagePipeline() {
    }

    public static String getKeywordsRaw(Context context) {
        return context.getSharedPreferences(RADAR_PREFS, Context.MODE_PRIVATE)
                .getString(RADAR_KEYWORDS_KEY, "");
    }

    public static void setKeywords(Context context, String raw) {
        String clean = raw == null ? "" : raw;
        context.getSharedPreferences(RADAR_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(RADAR_KEYWORDS_KEY, clean)
                .apply();
        rebuildKeywordCache(clean);
    }

    public static List<String> loadKeywords(Context context) {
        String raw = getKeywordsRaw(context);
        if (!raw.equals(cachedRawKeywords)) {
            rebuildKeywordCache(raw);
        }
        return cachedKeywords;
    }

    public static List<String> loadLowerKeywords(Context context) {
        String raw = getKeywordsRaw(context);
        if (!raw.equals(cachedRawKeywords)) {
            rebuildKeywordCache(raw);
        }
        return cachedLowerKeywords;
    }

    private static synchronized void rebuildKeywordCache(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            cachedRawKeywords = raw == null ? "" : raw;
            cachedKeywords = Collections.emptyList();
            cachedLowerKeywords = Collections.emptyList();
            return;
        }
        List<String> out = new ArrayList<>();
        List<String> lower = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
                lower.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        cachedRawKeywords = raw;
        cachedKeywords = Collections.unmodifiableList(out);
        cachedLowerKeywords = Collections.unmodifiableList(lower);
    }

    public static void process(Context context, ArrayList<MessageObject> messages) {
        if (context == null || messages == null || messages.isEmpty()) {
            return;
        }
        boolean radar = PluginRegistry.isEnabledById(context, "keyword_radar");
        boolean autosave = PluginRegistry.isEnabledById(context, "auto_saver");
        boolean snap = PluginRegistry.isEnabledById(context, "anti_delete")
                || PluginRegistry.isEnabledById(context, "edit_history");
        if (!radar && !autosave && !snap) {
            return;
        }
        List<String> keywords = radar ? loadKeywords(context) : null;
        List<String> lowerKeywords = radar ? loadLowerKeywords(context) : null;
        if (radar && (keywords == null || keywords.isEmpty())) {
            radar = false;
        }
        List<String> radarBatch = radar ? new ArrayList<>() : null;
        List<String> saverBatch = autosave ? new ArrayList<>() : null;
        for (MessageObject msg : messages) {
            try {
                if (snap) {
                    DeletedStore.snapshot(context, msg);
                }
                processOne(context, msg, radar, keywords, lowerKeywords, radarBatch,
                        autosave, saverBatch);
            } catch (Throwable ignored) {
            }
        }
        if (radarBatch != null && !radarBatch.isEmpty()) {
            VaultStore.addUniqueBatch(context, RADAR_PREFS, RADAR_HITS_KEY, radarBatch, CAP);
        }
        if (saverBatch != null && !saverBatch.isEmpty()) {
            VaultStore.addUniqueBatch(context, AUTOSAVER_PREFS, AUTOSAVER_ITEMS_KEY,
                    saverBatch, CAP);
        }
    }

    private static void processOne(Context context, MessageObject msg,
                                   boolean radar, List<String> keywords, List<String> lowerKeywords,
                                   List<String> radarBatch,
                                   boolean autosave, List<String> saverBatch) {
        if (msg == null || msg.messageOwner == null) {
            return;
        }
        String text = extractText(msg);

        if (radar && text != null && !text.isEmpty()) {
            String hit = KeywordMatcher.matchedPreLowered(text, keywords, lowerKeywords);
            if (hit != null) {
                String snippet = text.replace('\n', ' ').replace('|', ' ');
                if (snippet.length() > 140) {
                    snippet = snippet.substring(0, 140) + "…";
                }
                int date = msg.messageOwner.date;
                String record = msg.getDialogId() + "|" + msg.getId()
                        + "|" + hit + "|" + date + "|" + snippet;
                if (radarBatch != null) {
                    radarBatch.add(record);
                }
            }
        }

        if (autosave && msg.isSecretMedia()) {
            saveSecretMedia(context, msg, saverBatch);
        }
    }

    /** Scan a batch of history-loaded messages (from messagesDidLoad) for
     *  Keyword Radar matches. Deduped via VaultStore.addUniqueBatch. */
    public static void scanRadar(Context context, List<MessageObject> messages) {
        if (context == null || messages == null || messages.isEmpty()) {
            return;
        }
        if (!PluginRegistry.isEnabledById(context, "keyword_radar")) {
            return;
        }
        List<String> keywords = loadKeywords(context);
        List<String> lowerKeywords = loadLowerKeywords(context);
        if (keywords.isEmpty()) {
            return;
        }
        List<String> batch = new ArrayList<>();
        for (MessageObject msg : messages) {
            try {
                if (msg == null || msg.messageOwner == null) {
                    continue;
                }
                String text = extractText(msg);
                if (text == null || text.isEmpty()) {
                    continue;
                }
                String hit = KeywordMatcher.matchedPreLowered(text, keywords, lowerKeywords);
                if (hit != null) {
                    String snippet = text.replace('\n', ' ').replace('|', ' ');
                    if (snippet.length() > 140) {
                        snippet = snippet.substring(0, 140) + "…";
                    }
                    int date = msg.messageOwner.date;
                    String record = msg.getDialogId() + "|" + msg.getId()
                            + "|" + hit + "|" + date + "|" + snippet;
                    batch.add(record);
                }
            } catch (Throwable ignored) {
            }
        }
        if (!batch.isEmpty()) {
            VaultStore.addUniqueBatch(context, RADAR_PREFS, RADAR_HITS_KEY, batch, CAP);
        }
    }

    public static String formatRadarHit(String raw, int account) {
        if (raw == null) {
            return "";
        }
        String[] p = raw.split("\\|", 5);
        if (p.length != 5) {
            return raw;
        }
        String title = resolveDialogTitle(p[0], account);
        String time = formatTime(p[3]);
        return "\u200E\u2066" + title + "\u2069 · “" + p[2] + "”"
                + (time.isEmpty() ? "" : " · " + time)
                + "\n\u200E" + p[4];
    }

    private static String resolveDialogTitle(String dialogIdStr, int account) {
        try {
            long did = Long.parseLong(dialogIdStr);
            MessagesController mc = MessagesController.getInstance(account);
            if (did > 0) {
                TLRPC.User u = mc.getUser(did);
                if (u != null) {
                    return UserObject.getUserName(u);
                }
            } else if (did < 0) {
                TLRPC.Chat c = mc.getChat(-did);
                if (c != null && c.title != null) {
                    return c.title;
                }
            }
        } catch (Throwable ignored) {
        }
        return "Chat " + dialogIdStr;
    }

    private static String formatTime(String epochSecStr) {
        try {
            long sec = Long.parseLong(epochSecStr);
            if (sec <= 0) {
                return "";
            }
            SimpleDateFormat fmt = HIT_DATE_FMT.get();
            return fmt != null ? fmt.format(new Date(sec * 1000L)) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String extractText(MessageObject msg) {
        if (msg.messageOwner.message != null && !msg.messageOwner.message.isEmpty()) {
            return msg.messageOwner.message;
        }
        if (msg.messageText != null) {
            return msg.messageText.toString();
        }
        if (msg.caption != null) {
            return msg.caption.toString();
        }
        return null;
    }

    private static void saveSecretMedia(Context context, MessageObject msg,
                                        List<String> saverBatch) {
        int account = msg.currentAccount;
        FileLoader loader = FileLoader.getInstance(account);
        int date = msg.messageOwner != null ? msg.messageOwner.date : 0;
        TLRPC.Document doc = msg.getDocument();
        if (doc != null) {
            loader.loadFile(doc, msg, FileLoader.PRIORITY_HIGH, 1);
            String name = MessageObject.getFileName(doc);
            if (name == null || name.isEmpty()) {
                name = "doc_" + doc.id;
            }
            File target = loader.getPathToMessage(msg.messageOwner);
            String path = target != null ? target.getAbsolutePath() : "";
            String attach = FileLoader.getAttachFileName(doc);
            String kind = MessageObject.isVideoDocument(doc) ? "video" : "doc";
            AutoSaverStore.Entry entry = new AutoSaverStore.Entry();
            entry.dialogId = msg.getDialogId();
            entry.id = msg.getId();
            entry.date = date;
            entry.kind = kind;
            entry.name = name;
            entry.path = path;
            entry.attach = attach != null ? attach : "";
            AutoSaverStore.record(context, entry);
            String logRecord = msg.getDialogId() + "|" + msg.getId() + "|" + date + "|" + name;
            if (saverBatch != null) {
                saverBatch.add(logRecord);
            } else {
                VaultStore.addUnique(context, AUTOSAVER_PREFS, AUTOSAVER_ITEMS_KEY,
                        logRecord, CAP);
            }
            return;
        }
        if (msg.messageOwner.media != null && msg.messageOwner.media.photo != null) {
            TLRPC.Photo photo = msg.messageOwner.media.photo;
            if (photo.sizes != null && !photo.sizes.isEmpty()) {
                TLRPC.PhotoSize best = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
                if (best != null) {
                    ImageLocation loc = ImageLocation.getForPhoto(best, photo);
                    loader.loadFile(loc, msg, "jpg", FileLoader.PRIORITY_HIGH, 1);
                    File target = loader.getPathToMessage(msg.messageOwner);
                    String path = target != null ? target.getAbsolutePath() : "";
                    String attach = FileLoader.getAttachFileName(best);
                    AutoSaverStore.Entry entry = new AutoSaverStore.Entry();
                    entry.dialogId = msg.getDialogId();
                    entry.id = msg.getId();
                    entry.date = date;
                    entry.kind = "photo";
                    entry.name = "photo_" + photo.id + ".jpg";
                    entry.path = path;
                    entry.attach = attach != null ? attach : "";
                    AutoSaverStore.record(context, entry);
                    String logRecord = msg.getDialogId() + "|" + msg.getId()
                            + "|" + date + "|photo";
                    if (saverBatch != null) {
                        saverBatch.add(logRecord);
                    } else {
                        VaultStore.addUnique(context, AUTOSAVER_PREFS, AUTOSAVER_ITEMS_KEY,
                                logRecord, CAP);
                    }
                }
            }
        }
    }
}
