package org.telegram.messenger.amin.core;

import android.content.Context;

import org.telegram.messenger.FileLoader;
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
 * - AutoSaver (v1): for self-destructing (secret/TTL) media messages,
 *   kicks off the document download immediately so the file lands in the
 *   app media cache before it expires, and logs it in VaultStore
 *   ("amingram_autosaver"/"items"). Photos arrive as documents in most
 *   clients; pure TL_photo secrets are a v2 item.
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
        if (!radar && !vault && !saver) {
            return;
        }
        List<String> keywords = radar ? loadKeywords(context) : null;
        if (radar && (keywords == null || keywords.isEmpty())) {
            radar = false;
        }
        for (MessageObject obj : messages) {
            try {
                processOne(context, obj, radar, keywords, vault, saver);
            } catch (Throwable ignored) {
                // never break the upstream update flow
            }
        }
    }

    private static void processOne(Context context, MessageObject obj,
                                   boolean radar, List<String> keywords,
                                   boolean vault, boolean saver) {
        if (obj == null || obj.messageOwner == null) {
            return;
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
                String record = obj.getDialogId() + "|" + obj.getId() + "|" + obj.messageOwner.date;
                VaultStore.addUnique(context, AUTOSAVER_PREFS, AUTOSAVER_ITEMS_KEY, record, 300);
            }
        }
    }
}
