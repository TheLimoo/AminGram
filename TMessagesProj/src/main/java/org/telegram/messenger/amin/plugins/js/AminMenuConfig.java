package org.telegram.messenger.amin.plugins.js;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-user configuration for the chat attach menu (v2.0): which
 * menu-capable JS plugins appear there, in which order, and with
 * which icon / label overrides.
 *
 * Storage: prefs {@link JsPluginManager#PREFS}, key
 * {@value #KEY_MENU_CONFIG} — a JSONArray of
 * {"id","label","icon","included"} objects in menu order.
 * label/icon are OVERRIDES ONLY: "" means "use the plugin's
 * manifest default" (@menuLabel/@icon, else the plugin name /
 * the default attach icon). That way a manifest update still
 * flows through for entries the user never customized.
 *
 * Empty-config fallback (contract with JsPluginManager.menuPlugins):
 * when nothing is stored — key absent, unparseable, or an empty
 * array — there is NO config and the menu falls back to "every
 * enabled menu plugin, in plugin order, with manifest defaults".
 * The moment any entry is saved the config becomes authoritative
 * for ordering/inclusion, except that menu plugins missing from
 * the config (installed after the config was saved) are appended
 * at the end with defaults rather than hidden.
 *
 * This class is pure prefs + JSON: it knows nothing about which
 * plugins exist. JsPluginManager materializes the effective
 * editing list ({@code menuConfigEntries}) by merging this stored
 * config with the live plugin list.
 */
public final class AminMenuConfig {

    public static final String KEY_MENU_CONFIG = "menu_config_v1";

    /**
     * One menu entry — the small serializable representation shared
     * by the hub UI, JsPluginManager and the patch-0014 attach code.
     *
     * The array form is the wire format for the attach menu:
     * {pluginId, label, icon} — indices 0/1 are exactly what
     * patch 0014 has always consumed ({id, name}); index 2 (icon)
     * was added in v2.0 and {@link #fromArray} tolerates the old
     * length-2 arrays. In the attach-menu (resolved) form, label is
     * never empty and icon "" means "default icon". In the stored
     * (override) form, label/icon are overrides and "" means
     * "manifest default".
     */
    public static final class MenuEntry implements Serializable {
        private static final long serialVersionUID = 1L;

        public String pluginId;
        /** Label override (stored form) / resolved label (menu form). */
        public String label;
        /** Icon override (stored form) / resolved icon (menu form):
         *  an icon spec — a set name (download, mail, sparkles,
         *  dice, text, link, music, star, bolt, camera, globe,
         *  gear) or "emoji:<glyph>"; "" = default. */
        public String icon;
        public boolean included = true;

        public MenuEntry() {
            this("", "", "", true);
        }

        public MenuEntry(String pluginId, String label, String icon, boolean included) {
            this.pluginId = pluginId == null ? "" : pluginId;
            this.label = label == null ? "" : label;
            this.icon = icon == null ? "" : icon;
            this.included = included;
        }

        /** {pluginId, label, icon} — the patch-0014 wire format. */
        public String[] toArray() {
            return new String[]{pluginId, label, icon};
        }

        /** Tolerant reader: length 1–3 arrays (v1 arrays had no icon). */
        public static MenuEntry fromArray(String[] array) {
            MenuEntry entry = new MenuEntry();
            if (array == null) {
                return entry;
            }
            if (array.length > 0 && array[0] != null) {
                entry.pluginId = array[0];
            }
            if (array.length > 1 && array[1] != null) {
                entry.label = array[1];
            }
            if (array.length > 2 && array[2] != null) {
                entry.icon = array[2];
            }
            return entry;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", pluginId);
                o.put("label", label);
                o.put("icon", icon);
                o.put("included", included);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static MenuEntry fromJson(JSONObject o) {
            if (o == null) {
                return null;
            }
            MenuEntry entry = new MenuEntry(
                    o.optString("id", ""),
                    o.optString("label", ""),
                    o.optString("icon", ""),
                    o.optBoolean("included", true));
            return entry.pluginId.isEmpty() ? null : entry;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof MenuEntry)) {
                return false;
            }
            MenuEntry e = (MenuEntry) other;
            return pluginId.equals(e.pluginId) && label.equals(e.label)
                    && icon.equals(e.icon) && included == e.included;
        }

        @Override
        public int hashCode() {
            int h = pluginId.hashCode();
            h = 31 * h + label.hashCode();
            h = 31 * h + icon.hashCode();
            h = 31 * h + (included ? 1 : 0);
            return h;
        }
    }

    private AminMenuConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(JsPluginManager.PREFS, Context.MODE_PRIVATE);
    }

    /** The stored override entries, in menu order. Empty when there
     *  is no config (see the class javadoc for the fallback rule). */
    public static List<MenuEntry> load(Context context) {
        List<MenuEntry> out = new ArrayList<>();
        if (context == null) {
            return out;
        }
        try {
            String raw = prefs(context).getString(KEY_MENU_CONFIG, null);
            if (raw == null) {
                return out;
            }
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                MenuEntry entry = MenuEntry.fromJson(arr.optJSONObject(i));
                if (entry != null && find(out, entry.pluginId) == null) {
                    out.add(entry);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** True when a non-empty config is stored (i.e. the fallback
     *  "all enabled, manifest defaults" does NOT apply). */
    public static boolean hasConfig(Context context) {
        return context != null && !load(context).isEmpty();
    }

    /** Replaces the stored config. Saving an empty/null list clears
     *  the key, restoring the all-enabled fallback. */
    public static void save(Context context, List<MenuEntry> entries) {
        if (context == null) {
            return;
        }
        if (entries == null || entries.isEmpty()) {
            clear(context);
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (MenuEntry entry : entries) {
                if (entry != null && !entry.pluginId.isEmpty()) {
                    arr.put(entry.toJson());
                }
            }
            if (arr.length() == 0) {
                clear(context);
                return;
            }
            prefs(context).edit().putString(KEY_MENU_CONFIG, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static void clear(Context context) {
        if (context == null) {
            return;
        }
        prefs(context).edit().remove(KEY_MENU_CONFIG).apply();
    }

    /** Drops one plugin's entry (used by JsPluginManager.delete —
     *  a deleted plugin must leave no ghost config behind). If the
     *  config becomes empty it is cleared entirely, which restores
     *  the all-enabled fallback for the remaining plugins. */
    public static void remove(Context context, String pluginId) {
        if (context == null || pluginId == null) {
            return;
        }
        List<MenuEntry> entries = load(context);
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).pluginId.equals(pluginId)) {
                entries.remove(i);
                changed = true;
            }
        }
        if (changed) {
            save(context, entries);
        }
    }

    public static MenuEntry find(List<MenuEntry> entries, String pluginId) {
        if (entries == null || pluginId == null) {
            return null;
        }
        for (MenuEntry entry : entries) {
            if (entry.pluginId.equals(pluginId)) {
                return entry;
            }
        }
        return null;
    }
}
