package org.telegram.messenger.amin.pyplugins;

import android.content.Context;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

/**
 * Per-plugin settings store, reached from Python
 * ({@code base_plugin.BasePlugin.get_setting / set_setting}).
 *
 * Settings live in the Python engine's prefs file under one JSON
 * object per plugin ({@code settings_<id>}), namespaced by plugin
 * id — a plugin can only ever touch its own settings, because the
 * id is supplied by the host at load time, never by plugin code.
 * Values are JSON scalars (string / number / boolean). M1 renders
 * these through {@code create_settings()}; M0 only stores them.
 */
public final class PyPluginSettings {

    private PyPluginSettings() {
    }

    private static JSONObject load(String pluginId) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || pluginId == null) {
                return new JSONObject();
            }
            String raw = context.getSharedPreferences(PyPluginsPrefs.PREFS,
                    Context.MODE_PRIVATE).getString(
                    PyPluginsPrefs.KEY_SETTINGS_PREFIX + pluginId, null);
            return raw == null ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static void save(String pluginId, JSONObject settings) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || pluginId == null) {
                return;
            }
            context.getSharedPreferences(PyPluginsPrefs.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(
                            PyPluginsPrefs.KEY_SETTINGS_PREFIX + pluginId,
                            settings.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Called from Python: returns the stored value as a string, or
     *  {@code def} when unset. Numbers/booleans round-trip as text;
     *  {@code base_plugin} re-parses them. */
    public static String getSetting(String pluginId, String key, String def) {
        try {
            JSONObject settings = load(pluginId);
            if (!settings.has(key) || settings.isNull(key)) {
                return def;
            }
            Object value = settings.opt(key);
            return value == null ? def : String.valueOf(value);
        } catch (Throwable t) {
            return def;
        }
    }

    /** Called from Python. */
    public static void setSetting(String pluginId, String key, String value) {
        try {
            JSONObject settings = load(pluginId);
            settings.put(key, value);
            save(pluginId, settings);
        } catch (Throwable ignored) {
        }
    }

    /** All stored settings as a JSON object string (used by
     *  Python {@code export_settings()}). Values are the raw
     *  stored strings; the Python side JSON-decodes each one. */
    public static String getAllSettingsJson(String pluginId) {
        try {
            return load(pluginId).toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    /** Parses one stored value: JSON literals ("true", "42",
     *  "\"text\"") decode to Boolean / Number / String; anything
     *  else (plain text stored by {@code set_setting} with a
     *  string value) is returned as-is. */
    private static Object parseStored(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            org.json.JSONTokener tokener = new org.json.JSONTokener(raw);
            Object value = tokener.nextValue();
            // Reject a partial parse (e.g. "12abc" parses as 12
            // then leaves trailing input): only accept it when the
            // whole string was consumed.
            if (tokener.more()) {
                return raw;
            }
            return value;
        } catch (Throwable t) {
            return raw;
        }
    }

    /** Stored value as text ("" default handling is the caller's).
     *  Used by the settings page to show a row's current value. */
    public static String getTextValue(String pluginId, String key, String def) {
        try {
            JSONObject settings = load(pluginId);
            if (!settings.has(key) || settings.isNull(key)) {
                return def;
            }
            Object value = parseStored(String.valueOf(settings.opt(key)));
            return value == null ? def : String.valueOf(value);
        } catch (Throwable t) {
            return def;
        }
    }

    /** Stored value as a boolean (switch rows). */
    public static boolean getBooleanValue(String pluginId, String key, boolean def) {
        try {
            JSONObject settings = load(pluginId);
            if (!settings.has(key) || settings.isNull(key)) {
                return def;
            }
            Object value = parseStored(String.valueOf(settings.opt(key)));
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            return "true".equalsIgnoreCase(String.valueOf(value));
        } catch (Throwable t) {
            return def;
        }
    }

    /** Host-side: wipe a plugin's settings (used on delete). */
    public static void clear(Context context, String pluginId) {
        if (context == null || pluginId == null) {
            return;
        }
        try {
            context.getSharedPreferences(PyPluginsPrefs.PREFS, Context.MODE_PRIVATE)
                    .edit().remove(PyPluginsPrefs.KEY_SETTINGS_PREFIX + pluginId).apply();
        } catch (Throwable ignored) {
        }
    }
}
