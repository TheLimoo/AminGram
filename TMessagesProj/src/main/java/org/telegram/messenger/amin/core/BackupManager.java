package org.telegram.messenger.amin.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.amin.dns.DnsProfiles;
import org.telegram.messenger.amin.modules.LocalPremiumModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.messenger.amin.plugins.js.JsPlugin;
import org.telegram.messenger.amin.plugins.js.JsPluginManager;
import org.telegram.messenger.amin.plugins.js.PluginDb;
import org.telegram.messenger.amin.plugins.js.PluginFiles;
import org.telegram.messenger.amin.ui.AminFonts;
import org.telegram.messenger.amin.ui.VideoBackgroundHelper;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AminGram Backup (v1.3): export/import of ALL AminGram-owned state to
 * a single JSON file:
 * - module on/off states (prefs "amingram_plugins")
 * - Keyword Radar keywords + hits
 * - video background choice (the path — the video FILE itself is not
 *   embedded; if it still exists after a restore it just works,
 *   otherwise it must be re-picked once)
 * - Hidden Chats id list + PIN hash (never the PIN)
 * - Send-When-Online queue
 * - Anti-Delete snapshots / deleted / edit-history stores
 * - Auto-Saver v2 entries + legacy log
 * - installed JS plugins: metadata, full source code, enabled state
 *   and their amin.storage values
 * - plugin database state (amin.db / PluginDb rows), plugin file
 *   sandboxes (amin.files, base64 payloads) and the wallpaper
 *   library metadata (Stage 1, audit D1)
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
 * sections absent leave local state untouched — except JS plugins,
 * which are reinstalled through the normal validated install path.
 * Import REJECTS bundles whose version is newer than
 * {@link #BACKUP_VERSION}: silently half-applying a future format
 * would lose exactly the state the user is trying to restore.
 */
public final class BackupManager {

    /** Current bundle format version (see the class doc): v1 was
     *  the original section set; v2 adds dns/fonts/local_premium. */
    private static final int BACKUP_VERSION = 2;

    private BackupManager() {
    }

    public static String exportJson(Context context) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "AminGram");
            root.put("version", BACKUP_VERSION);
            root.put("created", System.currentTimeMillis());

            // Module states.
            JSONObject pluginPrefs = new JSONObject();
            Map<String, ?> all = context.getSharedPreferences("amingram_plugins",
                    Context.MODE_PRIVATE).getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                pluginPrefs.put(entry.getKey(), entry.getValue());
            }
            root.put("plugin_prefs", pluginPrefs);

            // DNS Tunnel profiles + settings, app font choice and
            // Local Premium toggles (bundle v2, pillar-4 audit §4).
            // Whole prefs maps, same shape as plugin_prefs.
            root.put("dns", prefsToJson(context, DnsProfiles.PREFS));
            root.put("fonts", prefsToJson(context, "amingram_fonts"));
            root.put("local_premium", prefsToJson(context, LocalPremiumModule.PREFS));

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

            // JS plugins: meta + code + storage.
            JSONArray jsPlugins = new JSONArray();
            for (JsPlugin plugin : JsPluginManager.plugins(context)) {
                JSONObject o = new JSONObject();
                o.put("meta", plugin.toJson());
                String code = JsPluginManager.readCode(context, plugin.id);
                o.put("code", code == null ? "" : code);
                JSONObject storage = new JSONObject();
                Map<String, ?> storeAll = context.getSharedPreferences(
                        JsPluginManager.storagePrefsName(plugin.id),
                        Context.MODE_PRIVATE).getAll();
                for (Map.Entry<String, ?> entry : storeAll.entrySet()) {
                    storage.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
                o.put("storage", storage);
                jsPlugins.put(o);
            }
            root.put("jsplugins", jsPlugins);

            // Plugin database (amin.db): every PluginDb row, with
            // the stored JSON text carried verbatim in "v" so any
            // value shape round-trips losslessly (Stage 1, D1).
            JSONArray pluginDb = new JSONArray();
            for (String[] row : PluginDb.dumpAll()) {
                JSONObject o = new JSONObject();
                o.put("p", row[0]);
                o.put("ns", row[1]);
                o.put("k", row[2]);
                o.put("v", row[3]);
                pluginDb.put(o);
            }
            root.put("plugindb", pluginDb);

            // Plugin file sandboxes (amin.files): manifest + base64
            // per file. The sandbox holds durable files only
            // (filesDir/jsplugins-data) — picker caches and other
            // cache-dir data are not plugin state and stay out.
            JSONObject pluginFiles = new JSONObject();
            for (String pluginId : PluginFiles.pluginIds(context)) {
                JSONArray files = new JSONArray();
                for (PluginFiles.FileInfo info : PluginFiles.list(context, pluginId)) {
                    byte[] data = PluginFiles.readBytes(context, pluginId, info.name);
                    if (data == null) {
                        continue;
                    }
                    JSONObject fo = new JSONObject();
                    fo.put("name", info.name);
                    fo.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
                    files.put(fo);
                }
                if (files.length() > 0) {
                    pluginFiles.put(pluginId, files);
                }
            }
            root.put("pluginfiles", pluginFiles);

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
            JSONObject pluginPrefs = root.optJSONObject("plugin_prefs");
            if (pluginPrefs != null) {
                SharedPreferences prefs = context.getSharedPreferences("amingram_plugins",
                        Context.MODE_PRIVATE);
                SharedPreferences.Editor edit = prefs.edit();
                JSONArray names = pluginPrefs.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String key = names.optString(i);
                        Object value = pluginPrefs.opt(key);
                        if (value instanceof Boolean) {
                            edit.putBoolean(key, (Boolean) value);
                        }
                    }
                }
                edit.apply();
            }

            // Bundle v2 sections (absent in v1 bundles = no-op):
            // DNS profiles/settings, app font, Local Premium.
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

            JSONArray jsPlugins = root.optJSONArray("jsplugins");
            if (jsPlugins != null) {
                for (int i = 0; i < jsPlugins.length(); i++) {
                    JSONObject o = jsPlugins.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    JSONObject meta = o.optJSONObject("meta");
                    String code = o.optString("code", "");
                    if (meta == null || code.isEmpty()) {
                        continue;
                    }
                    String error = JsPluginManager.installWithSource(context, code, "pack");
                    if (error != null) {
                        continue; // skip broken entries, import the rest
                    }
                    JsPlugin installed = JsPlugin.fromJson(meta);
                    if (installed == null) {
                        continue;
                    }
                    JSONObject storage = o.optJSONObject("storage");
                    if (storage != null) {
                        SharedPreferences.Editor edit = context.getSharedPreferences(
                                JsPluginManager.storagePrefsName(installed.id),
                                Context.MODE_PRIVATE).edit();
                        edit.clear();
                        JSONArray names = storage.names();
                        if (names != null) {
                            for (int j = 0; j < names.length(); j++) {
                                String key = names.optString(j);
                                edit.putString(key, storage.optString(key, ""));
                            }
                        }
                        edit.apply();
                    }
                    JsPlugin current = JsPluginManager.get(context, installed.id);
                    if (current != null && current.enabled != installed.enabled) {
                        JsPluginManager.setEnabled(context, current, installed.enabled);
                    }
                }
            }

            // Plugin database rows (per-plugin replace, Stage 1).
            JSONArray pluginDb = root.optJSONArray("plugindb");
            if (pluginDb != null) {
                List<String[]> rows = new ArrayList<>();
                for (int i = 0; i < pluginDb.length(); i++) {
                    JSONObject o = pluginDb.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    rows.add(new String[]{o.optString("p", ""), o.optString("ns", ""),
                            o.optString("k", ""), o.optString("v", "")});
                }
                PluginDb.restoreAll(rows);
            }

            // Plugin file sandboxes: each file is written through
            // the picker's own durable-copy path (sandbox name
            // validation + per-plugin quota apply, as on device).
            JSONObject pluginFiles = root.optJSONObject("pluginfiles");
            if (pluginFiles != null) {
                JSONArray pluginIds = pluginFiles.names();
                if (pluginIds != null) {
                    for (int i = 0; i < pluginIds.length(); i++) {
                        String pluginId = pluginIds.optString(i);
                        JSONArray files = pluginFiles.optJSONArray(pluginId);
                        if (files == null) {
                            continue;
                        }
                        for (int j = 0; j < files.length(); j++) {
                            JSONObject fo = files.optJSONObject(j);
                            if (fo == null) {
                                continue;
                            }
                            String name = fo.optString("name", "");
                            String data = fo.optString("data", "");
                            if (name.isEmpty() || data.isEmpty()) {
                                continue;
                            }
                            try {
                                byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                                PluginFiles.copyInto(context, pluginId, name,
                                        new ByteArrayInputStream(bytes), bytes.length);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
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

    // ------------------------------------------------------------------
    // Plugin packs (§3.4): a plugin-only export format —
    // {packVersion, plugins: [{meta, code, storage}]} — the same
    // per-plugin shape the full backup uses, generalised. Export
    // covers a chosen subset; import is driven by the hub through
    // the permission review screen, one plugin at a time.
    // ------------------------------------------------------------------

    public static String exportPackJson(Context context, List<String> pluginIds) {
        JSONObject root = new JSONObject();
        try {
            root.put("packVersion", 1);
            JSONArray plugins = new JSONArray();
            for (JsPlugin plugin : JsPluginManager.plugins(context)) {
                if (pluginIds != null && !pluginIds.contains(plugin.id)) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("meta", plugin.toJson());
                String code = JsPluginManager.readCode(context, plugin.id);
                o.put("code", code == null ? "" : code);
                JSONObject storage = new JSONObject();
                Map<String, ?> storeAll = context.getSharedPreferences(
                        JsPluginManager.storagePrefsName(plugin.id),
                        Context.MODE_PRIVATE).getAll();
                for (Map.Entry<String, ?> entry : storeAll.entrySet()) {
                    storage.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
                o.put("storage", storage);
                plugins.put(o);
            }
            root.put("plugins", plugins);
        } catch (Exception ignored) {
        }
        return root.toString();
    }

    /** Parses a pack file into its plugin entries (each a
     *  JSONObject with meta/code/storage), or null when the text
     *  is not a pack. A full AminGram backup is also accepted —
     *  its jsplugins array has the same shape. */
    public static List<JSONObject> parsePack(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray entries = root.optJSONArray("plugins");
            if (entries == null) {
                entries = root.optJSONArray("jsplugins");
            }
            if (entries == null) {
                return null;
            }
            List<JSONObject> out = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject o = entries.optJSONObject(i);
                if (o != null && o.optJSONObject("meta") != null
                        && !o.optString("code", "").isEmpty()) {
                    out.add(o);
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Restores one plugin's amin.storage values from a pack or
     *  backup entry (clear-then-write, like importJson). */
    public static void restorePluginStorage(Context context, String pluginId, JSONObject storage) {
        if (storage == null) {
            return;
        }
        try {
            SharedPreferences.Editor edit = context.getSharedPreferences(
                    JsPluginManager.storagePrefsName(pluginId),
                    Context.MODE_PRIVATE).edit();
            edit.clear();
            JSONArray names = storage.names();
            if (names != null) {
                for (int j = 0; j < names.length(); j++) {
                    String key = names.optString(j);
                    edit.putString(key, storage.optString(key, ""));
                }
            }
            edit.apply();
        } catch (Exception ignored) {
        }
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
