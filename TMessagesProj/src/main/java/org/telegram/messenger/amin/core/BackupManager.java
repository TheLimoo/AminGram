package org.telegram.messenger.amin.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.messenger.amin.plugins.js.JsPlugin;
import org.telegram.messenger.amin.plugins.js.JsPluginManager;
import org.telegram.messenger.amin.ui.VideoBackgroundHelper;

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
 *
 * The hub owns the file picker (SAF); this class is pure
 * build/apply over a JSON string. Import is overwrite-per-section:
 * sections present in the file replace the matching local state,
 * sections absent leave local state untouched — except JS plugins,
 * which are reinstalled through the normal validated install path.
 */
public final class BackupManager {

    private BackupManager() {
    }

    public static String exportJson(Context context) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "AminGram");
            root.put("version", 1);
            root.put("created", System.currentTimeMillis());

            // Module states.
            JSONObject pluginPrefs = new JSONObject();
            Map<String, ?> all = context.getSharedPreferences("amingram_plugins",
                    Context.MODE_PRIVATE).getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                pluginPrefs.put(entry.getKey(), entry.getValue());
            }
            root.put("plugin_prefs", pluginPrefs);

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
                    String error = JsPluginManager.install(context, code);
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
        } catch (Exception e) {
            return "Import failed: " + e.getMessage();
        }
        // Plugin states changed under the registry's feet — nothing to
        // invalidate (it reads prefs on every call), but the hub that
        // calls us refreshes itself.
        PluginRegistry.modules();
        return null;
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
