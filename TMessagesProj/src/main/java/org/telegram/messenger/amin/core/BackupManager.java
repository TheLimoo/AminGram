package org.telegram.messenger.amin.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.amin.dns.DnsProfiles;
import org.telegram.messenger.amin.modules.LocalPremiumModule;
import org.telegram.messenger.amin.modules.MessageFiltersModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.messenger.amin.ui.AminFonts;
import org.telegram.messenger.amin.ui.VideoBackgroundHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AminGram Backup (v1.3): export/import of ALL AminGram-owned state to
 * a single JSON file:
 * - module on/off states + sub-options (prefs "amingram_plugins")
 * - Keyword Radar keywords + hits
 * - Message Filters keywords + sender IDs (prefs "amingram_filters")
 * - video background choice (the path — the video FILE itself is not
 *   embedded; if it still exists after a restore it just works,
 *   otherwise it must be re-picked once)
 * - Hidden Chats id list + PIN hash (never the PIN)
 * - Send-When-Online queue
 * - Anti-Delete snapshots / deleted / edit-history stores
 * - Auto-Saver v2 entries + legacy log
 * - the wallpaper library metadata (Stage 1, audit D1)
 * - DNS Tunnel profiles + settings (prefs "amingram_dns"), the app
 *   font choice (prefs "amingram_fonts") and the Local Premium
 *   toggles (prefs "amingram_local_premium") — added at bundle
 *   version 2 (pillar-4 audit §4); version-1 bundles simply lack
 *   these sections and import with them untouched.
 *
 * Deliberately NOT exported: the Google auth token cache
 * (GoogleAuthManager) — credential material never enters a backup
 * file; a restored app simply re-runs the account sign-in flow.
 *
 * The hub owns the file picker (SAF); this class is pure
 * build/apply over a JSON string. Import is overwrite-per-section:
 * sections present in the file replace the matching local state,
 * sections absent leave local state untouched.
 * Import REJECTS bundles whose version is newer than
 * {@link #BACKUP_VERSION}: silently half-applying a future format
 * would lose exactly the state the user is trying to restore.
 */
public final class BackupManager {

    /** Current bundle format version (see the class doc): v1 was
     *  the original section set; v2 adds dns/fonts/local_premium/filters. */
    private static final int BACKUP_VERSION = 2;

    private BackupManager() {
    }

    public static String exportJson(Context context) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "AminGram");
            root.put("version", BACKUP_VERSION);
            root.put("created", System.currentTimeMillis());

            // Module states + sub-settings (Boolean / Integer / String).
            root.put("plugin_prefs", prefsToJson(context, "amingram_plugins"));

            // DNS Tunnel profiles + settings, app font choice,
            // Local Premium toggles, and Message Filters rules.
            root.put("dns", prefsToJson(context, DnsProfiles.PREFS));
            root.put("fonts", prefsToJson(context, "amingram_fonts"));
            root.put("local_premium", prefsToJson(context, LocalPremiumModule.PREFS));
            root.put("filters", prefsToJson(context, MessageFiltersModule.PREFS));

            // Keyword Radar.
            JSONObject radar = new JSONObject();
            radar.put("keywords", MessagePipeline.getKeywordsRaw(context));
            radar.put("hits", new JSONArray(VaultStore.getAll(context,
                    MessagePipeline.RADAR_PREFS, MessagePipeline.RADAR_HITS_KEY)));
            root.put("radar", radar);

            // Video background (path only).
            JSONObject videobg = new JSONObject();
            String path = VideoBackgroundHelper.getVideoPath(context);
            if (path != null) {
                videobg.put("path", path);
            }
            root.put("videobg", videobg);

            // Hidden chats.
            JSONObject hidden = new JSONObject();
            JSONArray ids = new JSONArray();
            for (Long id : HiddenChatsStore.getHiddenIds(context)) {
                ids.put(id);
            }
            hidden.put("ids", ids);
            String pinHash = HiddenChatsStore.getPinHash(context);
            if (pinHash != null) {
                hidden.put("pin_hash", pinHash);
            }
            root.put("hidden", hidden);

            // Send When Online queue (raw entries round-trip).
            JSONArray swo = new JSONArray();
            for (SendWhenOnlineStore.Entry e : SendWhenOnlineStore.getAll(context)) {
                JSONObject o = new JSONObject();
                o.put("peer", e.peer);
                o.put("name", e.name);
                o.put("text", e.text);
                o.put("date", e.date);
                o.put("state", e.state);
                o.put("sentDate", e.sentDate);
                o.put("account", e.account);
                swo.put(o);
            }
            root.put("swo", swo);

            // Anti-Delete / Edit History stores.
            JSONObject anti = new JSONObject();
            anti.put("snapshots", new JSONArray(VaultStore.getAll(context,
                    DeletedStore.PREFS, DeletedStore.SNAP_KEY)));
            anti.put("deleted", new JSONArray(VaultStore.getAll(context,
                    DeletedStore.PREFS, DeletedStore.DELETED_KEY)));
            anti.put("edits", new JSONArray(VaultStore.getAll(context,
                    DeletedStore.PREFS, DeletedStore.EDIT_KEY)));
            root.put("antidelete", anti);

            // Auto-Saver.
            JSONObject saver = new JSONObject();
            String v2 = context.getSharedPreferences(AutoSaverStore.PREFS, Context.MODE_PRIVATE)
                    .getString(AutoSaverStore.ITEMS_V2_KEY, null);
            if (v2 != null) {
                saver.put("items_v2", new JSONArray(v2));
            }
            saver.put("log", new JSONArray(VaultStore.getAll(context,
                    MessagePipeline.AUTOSAVER_PREFS, MessagePipeline.AUTOSAVER_ITEMS_KEY)));
            root.put("autosaver", saver);

            // Wallpaper library metadata (files themselves are not
            // embedded — same rule as the videobg path above).
            root.put("wallpapers", WallpaperLibraryStore.exportBackup(context));
        } catch (Exception ignored) {
        }
        return root.toString();
    }

    /** Applies a backup produced by exportJson. Returns null on
     *  success, or a user-readable error. */
    public static String importJson(Context context, String json) {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            return "That file is not a valid AminGram backup.";
        }
        if (!"AminGram".equals(root.optString("app"))) {
            return "That file is not an AminGram backup.";
        }
        // Format gate (pillar-4 audit §4): a bundle from a NEWER
        // AminGram may carry sections/semantics this build would
        // silently drop — refuse it outright. Older bundles (v1,
        // or a missing field) import normally; sections they lack
        // are no-ops.
        int version = root.optInt("version", 1);
        if (version > BACKUP_VERSION) {
            return "This backup was made by a newer version of AminGram (format v"
                    + version + "); update the app to import it.";
        }
        try {
            applyPrefsJson(context, "amingram_plugins", root.optJSONObject("plugin_prefs"));

            // Bundle v2 sections (absent in v1 bundles = no-op):
            // DNS profiles/settings, app font, Local Premium, Message Filters.
            applyPrefsJson(context, DnsProfiles.PREFS, root.optJSONObject("dns"));
            JSONObject fonts = root.optJSONObject("fonts");
            if (fonts != null) {
                applyPrefsJson(context, "amingram_fonts", fonts);
                // The font family is latched in memory at startup;
                // re-read it so the restored choice applies without
                // a restart.
                AminFonts.initLatch();
            }
            applyPrefsJson(context, LocalPremiumModule.PREFS,
                    root.optJSONObject("local_premium"));
            applyPrefsJson(context, MessageFiltersModule.PREFS,
                    root.optJSONObject("filters"));

            JSONObject radar = root.optJSONObject("radar");
            if (radar != null) {
                MessagePipeline.setKeywords(context, radar.optString("keywords", ""));
                VaultStore.putAll(context, MessagePipeline.RADAR_PREFS,
                        MessagePipeline.RADAR_HITS_KEY, toStringList(radar.optJSONArray("hits")));
            }

            JSONObject videobg = root.optJSONObject("videobg");
            if (videobg != null && videobg.has("path")) {
                VideoBackgroundHelper.setVideoPath(context, videobg.optString("path", null));
            }

            JSONObject hidden = root.optJSONObject("hidden");
            if (hidden != null) {
                List<Long> ids = new ArrayList<>();
                JSONArray arr = hidden.optJSONArray("ids");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        ids.add(arr.optLong(i));
                    }
                }
                HiddenChatsStore.replaceAll(context, ids,
                        hidden.has("pin_hash") ? hidden.optString("pin_hash") : null);
            }

            JSONArray swo = root.optJSONArray("swo");
            if (swo != null) {
                List<SendWhenOnlineStore.Entry> entries = new ArrayList<>();
                for (int i = 0; i < swo.length(); i++) {
                    JSONObject o = swo.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    SendWhenOnlineStore.Entry e = new SendWhenOnlineStore.Entry();
                    e.peer = o.optLong("peer");
                    e.name = o.optString("name", "");
                    e.text = o.optString("text", "");
                    e.date = o.optInt("date");
                    e.state = o.optString("state", "pending");
                    e.sentDate = o.optInt("sentDate");
                    // Backups predating the account field leave -1
                    // here only transiently: the store resolves it
                    // to UserConfig.selectedAccount on next load.
                    e.account = o.optInt("account", -1);
                    entries.add(e);
                }
                SendWhenOnlineStore.saveAll(context, entries);
            }

            JSONObject anti = root.optJSONObject("antidelete");
            if (anti != null) {
                VaultStore.putAll(context, DeletedStore.PREFS, DeletedStore.SNAP_KEY,
                        toStringList(anti.optJSONArray("snapshots")));
                VaultStore.putAll(context, DeletedStore.PREFS, DeletedStore.DELETED_KEY,
                        toStringList(anti.optJSONArray("deleted")));
                VaultStore.putAll(context, DeletedStore.PREFS, DeletedStore.EDIT_KEY,
                        toStringList(anti.optJSONArray("edits")));
            }

            JSONObject saver = root.optJSONObject("autosaver");
            if (saver != null) {
                JSONArray v2 = saver.optJSONArray("items_v2");
                if (v2 != null) {
                    context.getSharedPreferences(AutoSaverStore.PREFS, Context.MODE_PRIVATE)
                            .edit().putString(AutoSaverStore.ITEMS_V2_KEY, v2.toString()).apply();
                }
                VaultStore.putAll(context, MessagePipeline.AUTOSAVER_PREFS,
                        MessagePipeline.AUTOSAVER_ITEMS_KEY, toStringList(saver.optJSONArray("log")));
            }

            // Wallpaper library metadata.
            JSONObject wallpapers = root.optJSONObject("wallpapers");
            if (wallpapers != null) {
                WallpaperLibraryStore.importBackup(context, wallpapers);
            }
        } catch (Exception e) {
            return "Import failed: " + e.getMessage();
        }
        // Plugin states changed under the registry's feet — nothing to
        // invalidate (it reads prefs on every call), but the hub that
        // calls us refreshes itself.
        PluginRegistry.modules();
        return null;
    }

    /** Whole-prefs-map → JSON (export side of the flat bundle
     *  sections). Values are carried with their native types
     *  (Boolean/String/Number) so import can restore them exactly. */
    private static JSONObject prefsToJson(Context context, String prefsName) throws Exception {
        JSONObject out = new JSONObject();
        Map<String, ?> all = context.getSharedPreferences(prefsName,
                Context.MODE_PRIVATE).getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    /** Import side of {@link #prefsToJson}: writes the section's
     *  typed values over the local prefs (merge, no clear — the
     *  same per-section semantics as the plugin_prefs block).
     *  Unknown value shapes are skipped. Null section = no-op. */
    private static void applyPrefsJson(Context context, String prefsName, JSONObject section) {
        if (section == null) {
            return;
        }
        SharedPreferences.Editor edit = context.getSharedPreferences(prefsName,
                Context.MODE_PRIVATE).edit();
        JSONArray names = section.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                Object value = section.opt(key);
                if (value instanceof Boolean) {
                    edit.putBoolean(key, (Boolean) value);
                } else if (value instanceof String) {
                    edit.putString(key, (String) value);
                } else if (value instanceof Integer) {
                    // Width matters: several of these prefs are read
                    // back with getInt (premium colors, DNS ports) —
                    // a Long in their place would throw on read.
                    edit.putInt(key, (Integer) value);
                } else if (value instanceof Long) {
                    edit.putLong(key, (Long) value);
                } else if (value instanceof Number) {
                    edit.putFloat(key, ((Number) value).floatValue());
                }
            }
        }
        edit.apply();
    }

    private static List<String> toStringList(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            out.add(arr.optString(i));
        }
        return out;
    }
}
