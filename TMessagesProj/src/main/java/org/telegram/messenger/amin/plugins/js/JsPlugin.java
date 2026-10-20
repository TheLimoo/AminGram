package org.telegram.messenger.amin.plugins.js;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * An installed user plugin: manifest data + install state. The source
 * code itself lives in the app-private files dir (jsplugins/<id>.js);
 * this metadata lives in prefs "amingram_jsplugins"/"meta_<id>".
 *
 * The has* flags (which hooks the plugin defines) are detected at
 * install time by evaluating the file in a throwaway Rhino scope;
 * they drive the hub (and the attach-menu listing) without re-parsing.
 */
public final class JsPlugin {

    public static final class SettingDef {
        public String key;
        public String type; // "bool" | "text"
        public String title;
        public Object def;   // Boolean or String
    }

    public String id;
    public String name;
    public String version;
    public String author;
    public String description;
    /** Manifest @icon ("" when unset): an icon spec — a set name
     *  (download, mail, sparkles, dice, text, link, music, star,
     *  bolt, camera, globe, gear) or "emoji:<glyph>". */
    public String icon = "";
    /** Manifest @menuLabel ("" when unset): attach-menu label override. */
    public String menuLabel = "";
    public final List<String> capabilities = new ArrayList<>();
    public boolean enabled = true;
    public final List<SettingDef> settings = new ArrayList<>();
    public boolean hasMessage;
    public boolean hasCommand;
    public boolean hasMenu;
    public long installedAt;

    public boolean hasCapability(String capability) {
        return capabilities.contains(capability);
    }

    /** The attach-menu label before any per-user override:
     *  @menuLabel when set, else the plugin name. */
    public String defaultMenuLabel() {
        return menuLabel != null && !menuLabel.isEmpty() ? menuLabel : name;
    }

    /** The attach-menu icon before any per-user override ("" = unset). */
    public String defaultMenuIcon() {
        return icon == null ? "" : icon;
    }

    public String capabilitiesText() {
        StringBuilder sb = new StringBuilder();
        for (String cap : capabilities) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(cap);
        }
        return sb.toString();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("version", version);
            o.put("author", author);
            o.put("description", description);
            // v2.0 optional fields: always written ("" when unset);
            // older readers ignore unknown keys, older files simply
            // lack them and fromJson defaults them to "".
            o.put("icon", icon == null ? "" : icon);
            o.put("menuLabel", menuLabel == null ? "" : menuLabel);
            JSONArray caps = new JSONArray();
            for (String cap : capabilities) {
                caps.put(cap);
            }
            o.put("capabilities", caps);
            o.put("enabled", enabled);
            JSONArray defs = new JSONArray();
            for (SettingDef def : settings) {
                JSONObject d = new JSONObject();
                d.put("key", def.key);
                d.put("type", def.type);
                d.put("title", def.title);
                d.put("def", def.def == null ? "" : def.def);
                defs.put(d);
            }
            o.put("settings", defs);
            o.put("hasMessage", hasMessage);
            o.put("hasCommand", hasCommand);
            o.put("hasMenu", hasMenu);
            o.put("installedAt", installedAt);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static JsPlugin fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        JsPlugin plugin = new JsPlugin();
        plugin.id = o.optString("id", null);
        if (plugin.id == null || plugin.id.isEmpty()) {
            return null;
        }
        plugin.name = o.optString("name", plugin.id);
        plugin.version = o.optString("version", "");
        plugin.author = o.optString("author", "");
        plugin.description = o.optString("description", "");
        plugin.icon = o.optString("icon", "");
        plugin.menuLabel = o.optString("menuLabel", "");
        JSONArray caps = o.optJSONArray("capabilities");
        if (caps != null) {
            for (int i = 0; i < caps.length(); i++) {
                plugin.capabilities.add(caps.optString(i));
            }
        }
        plugin.enabled = o.optBoolean("enabled", true);
        JSONArray defs = o.optJSONArray("settings");
        if (defs != null) {
            for (int i = 0; i < defs.length(); i++) {
                JSONObject d = defs.optJSONObject(i);
                if (d == null) {
                    continue;
                }
                SettingDef def = new SettingDef();
                def.key = d.optString("key", "");
                def.type = d.optString("type", "text");
                def.title = d.optString("title", def.key);
                Object rawDef = d.opt("def");
                def.def = rawDef;
                plugin.settings.add(def);
            }
        }
        plugin.hasMessage = o.optBoolean("hasMessage");
        plugin.hasCommand = o.optBoolean("hasCommand");
        plugin.hasMenu = o.optBoolean("hasMenu");
        plugin.installedAt = o.optLong("installedAt");
        return plugin;
    }
}
