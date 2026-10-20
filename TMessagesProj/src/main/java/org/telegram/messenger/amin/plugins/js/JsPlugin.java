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
        public String type; // "bool" | "text" | "number" | "slider" | "select" | "header"
        public String title;
        public Object def;   // Boolean, String or Double (number/slider)
        // Settings schema v2 (Platform v2, A5) — all additive;
        // defaults below are exactly what a pre-v2 meta parses to.
        public String help = "";
        public int maxLength = 0;
        public boolean multiline;
        public Double min;   // null = unset
        public Double max;   // null = unset
        public Double step;  // null = unset
        public final List<String> optionValues = new ArrayList<>();
        public final List<String> optionLabels = new ArrayList<>();
        public String dependsOnKey = "";
        public String dependsOnEquals = "";
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
    // Lifecycle hooks detected at probe time (Platform v2, A6).
    public boolean hasEnable;
    public boolean hasDisable;
    public boolean hasSettingsChanged;
    public boolean hasMigrate;
    /** Manifest @dataVersion (declared by the installed code, A6). */
    public int dataVersion = 1;
    /** Data version the plugin's stored data has been migrated
     *  to; 0 = never recorded (pre-v2 metas, fresh installs adopt
     *  the declared version silently). Persisted. */
    public int dataVersionStored = 0;
    /** Manifest @minPlatform (A9); 1 when undeclared. Persisted. */
    public int minPlatform = 1;
    /** Watchdog counters (A7): errDay is "yyyy-MM-dd"; errCount is
     *  the number of hook errors recorded that day. Persisted. */
    public String errDay = "";
    public int errCount = 0;

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
                // Schema v2 additive keys (A5).
                d.put("help", def.help == null ? "" : def.help);
                d.put("maxLength", def.maxLength);
                d.put("multiline", def.multiline);
                if (def.min != null) {
                    d.put("min", def.min.doubleValue());
                }
                if (def.max != null) {
                    d.put("max", def.max.doubleValue());
                }
                if (def.step != null) {
                    d.put("step", def.step.doubleValue());
                }
                JSONArray values = new JSONArray();
                for (String v : def.optionValues) {
                    values.put(v);
                }
                d.put("optionValues", values);
                JSONArray labels = new JSONArray();
                for (String l : def.optionLabels) {
                    labels.put(l);
                }
                d.put("optionLabels", labels);
                d.put("dependsOnKey", def.dependsOnKey == null ? "" : def.dependsOnKey);
                d.put("dependsOnEquals", def.dependsOnEquals == null ? "" : def.dependsOnEquals);
                defs.put(d);
            }
            o.put("settings", defs);
            o.put("hasMessage", hasMessage);
            o.put("hasCommand", hasCommand);
            o.put("hasMenu", hasMenu);
            o.put("installedAt", installedAt);
            // Platform v2 additive keys (A6/A7/A9).
            o.put("hasEnable", hasEnable);
            o.put("hasDisable", hasDisable);
            o.put("hasSettingsChanged", hasSettingsChanged);
            o.put("hasMigrate", hasMigrate);
            o.put("dataVersion", dataVersion);
            o.put("dataVersionStored", dataVersionStored);
            o.put("minPlatform", minPlatform);
            o.put("errDay", errDay == null ? "" : errDay);
            o.put("errCount", errCount);
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
                // Schema v2 additive keys (A5); absent in old metas.
                def.help = d.optString("help", "");
                def.maxLength = d.optInt("maxLength", 0);
                def.multiline = d.optBoolean("multiline", false);
                if (d.has("min") && !d.isNull("min")) {
                    def.min = d.optDouble("min");
                }
                if (d.has("max") && !d.isNull("max")) {
                    def.max = d.optDouble("max");
                }
                if (d.has("step") && !d.isNull("step")) {
                    def.step = d.optDouble("step");
                }
                JSONArray values = d.optJSONArray("optionValues");
                if (values != null) {
                    for (int j = 0; j < values.length(); j++) {
                        def.optionValues.add(values.optString(j));
                    }
                }
                JSONArray labels = d.optJSONArray("optionLabels");
                if (labels != null) {
                    for (int j = 0; j < labels.length(); j++) {
                        def.optionLabels.add(labels.optString(j));
                    }
                }
                def.dependsOnKey = d.optString("dependsOnKey", "");
                def.dependsOnEquals = d.optString("dependsOnEquals", "");
                plugin.settings.add(def);
            }
        }
        plugin.hasMessage = o.optBoolean("hasMessage");
        plugin.hasCommand = o.optBoolean("hasCommand");
        plugin.hasMenu = o.optBoolean("hasMenu");
        plugin.installedAt = o.optLong("installedAt");
        // Platform v2 additive keys; absent in pre-v2 metas.
        plugin.hasEnable = o.optBoolean("hasEnable");
        plugin.hasDisable = o.optBoolean("hasDisable");
        plugin.hasSettingsChanged = o.optBoolean("hasSettingsChanged");
        plugin.hasMigrate = o.optBoolean("hasMigrate");
        plugin.dataVersion = o.optInt("dataVersion", 1);
        plugin.dataVersionStored = o.optInt("dataVersionStored", 0);
        plugin.minPlatform = o.optInt("minPlatform", 1);
        plugin.errDay = o.optString("errDay", "");
        plugin.errCount = o.optInt("errCount", 0);
        return plugin;
    }
}
