package org.telegram.messenger.amin.core;

import android.content.Context;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

/**
 * AminGram incoming-message pipeline (phase 4). Fed by a single hook in
 * MessagesController.processUpdateArray with the freshly arrived messages,
 * BEFORE the notification dispatch. Everything here is best-effort and
 * exception-isolated: a mod bug must never break upstream update handling.
 *
 * - KeywordRadar: matches message text against user keywords (prefs
 *   "amingram_radar"/"keywords", comma or newline separated) and records
 *   hits in VaultStore ("amingram_radar"/"hits").
 * - ConfigVault: extracts proxy configs + subscription links into
 *   VaultStore ("amingram_vault"/"items").
 * - AutoSaver: for self-destructing (secret/TTL) media messages, kicks
 *   off the download immediately so the file lands in the app media
 *   cache before it expires, and logs it in VaultStore
 *   ("amingram_autosaver"/"items"). v1 covered document media; v2 also
 *   pulls the largest size of pure TL_photo secrets via ImageLocation.
 */
public final class MessagePipeline {

    public static final String RADAR_PREFS = "amingram_radar";
    public static final String RADAR_KEYWORDS_KEY = "keywords";
    public static final String RADAR_HITS_KEY = "hits";
    public static final String VAULT_PREFS = "amingram_vault";
    public static final String VAULT_ITEMS_KEY = "items";
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
        boolean vault = PluginRegistry.isEnabledById(context, "config_vault");
        boolean saver = PluginRegistry.isEnabledById(context, "auto_saver");
        boolean anti = PluginRegistry.isEnabledById(context, "anti_delete");
        boolean hist = PluginRegistry.isEnabledById(context, "edit_history");
        if (!radar && !vault && !saver && !anti && !hist) {
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
                processOne(context, obj, radar, keywords, vault, saver, anti);
            } catch (Throwable ignored) {
                // never break the upstream update flow
            }
        }
    }

    private static void processOne(Context context, MessageObject obj,
                                   boolean radar, List<String> keywords,
                                   boolean vault, boolean saver, boolean anti) {
        if (obj == null || obj.messageOwner == null) {
            return;
        }
        if (anti) {
            DeletedStore.snapshot(context, obj);
        }
        String text = obj.messageOwner.message;

        if (text != null && !text.isEmpty()) {
            if (radar) {
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
            if (vault) {
                for (String link : ConfigExtractor.extractConfigs(text)) {
                    VaultStore.addUnique(context, VAULT_PREFS, VAULT_ITEMS_KEY, link, 500);
                }
                for (String link : ConfigExtractor.extractSubscriptionLinks(text)) {
                    VaultStore.addUnique(context, VAULT_PREFS, VAULT_ITEMS_KEY, link, 500);
                }
            }
        }

        if (saver && obj.isSecretMedia()) {
            TLRPC.Document document = obj.getDocument();
            if (document != null) {
                FileLoader.getInstance(obj.currentAccount)
                        .loadFile(document, obj, FileLoader.PRIORITY_HIGH, 0);
                // Include the file name so the saved cache file is findable;
                // a bare dialog|id|date record (v1.0) left saves invisible.
                String name = obj.getFileName();
                String record = obj.getDialogId() + "|" + obj.getId() + "|" + obj.messageOwner.date
                        + (name != null && !name.isEmpty() ? "|" + name : "");
                VaultStore.addUnique(context, AUTOSAVER_PREFS, AUTOSAVER_ITEMS_KEY, record, 300);
            } else {
                // v2: pure TL_photo secrets carry no document — pull the
                // largest photo size into the media cache before it expires.
                TLRPC.Photo photo = obj.getPhoto();
                if (photo != null && photo.sizes != null && !photo.sizes.isEmpty()) {
                    TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
                    if (size != null) {
                        FileLoader.getInstance(obj.currentAccount).loadFile(
                                ImageLocation.getForPhoto(size, photo), obj, "jpg",
                                FileLoader.PRIORITY_HIGH, 0);
                        String record = obj.getDialogId() + "|" + obj.getId() + "|"
                                + obj.messageOwner.date + "|photo";
                        VaultStore.addUnique(context, AUTOSAVER_PREFS, AUTOSAVER_ITEMS_KEY, record, 300);
                    }
                }
            }
        }
    }
}
