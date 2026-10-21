package org.telegram.messenger.amin.plugins.js;

import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SETTINGS schema v2 parser/validator (Platform v2, A5). Pure:
 * depends only on Rhino, {@link JsPlugin} and java.util, so it
 * compiles standalone. The manager's probe hands it the raw JS
 * value of a plugin's {@code SETTINGS} array; it fills {@code out}
 * with validated {@link JsPlugin.SettingDef}s and returns the list
 * of ERRORS (empty = ok). Non-fatal problems are appended to
 * {@code warnings} (may be null, in which case they are dropped).
 *
 * Strict vs loose ({@code strict}): strict is for NEW plugin ids
 * (first install — including first delivery of a bundled id);
 * loose is for reinstalls of an already-known id, where a schema
 * slip must not brick an update of a working plugin.
 *
 * Rules (see PLATFORM-V2-DESIGN.md §1.5):
 * - valid types: bool, text, number, slider, select, header;
 *   unknown type → strict: error / loose: warning + coerce "text".
 * - select with no options → strict: error / loose: warning +
 *   coerce "text".
 * - min &gt; max → strict: error / loose: warning + drop min/max
 *   (never swapped — the author's intent is unknowable).
 * - number/slider default non-numeric → strict: error / loose:
 *   warning + default 0.
 * - duplicate key → strict: error / loose: warning; the first
 *   definition wins in both modes.
 * - header must have no key: a present key is always only a
 *   warning, and the key is dropped.
 * - dependsOn referencing an unknown key → warning only.
 * Entries without a key that are not headers are skipped silently
 * (the pre-v2 behaviour).
 */
public final class SettingsSchema {

    private SettingsSchema() {
    }

    public static List<String> parseAndValidate(Object settingsArrayRaw,
                                                List<JsPlugin.SettingDef> out,
                                                boolean strict,
                                                List<String> warnings) {
        List<String> errors = new ArrayList<>();
        List<String> warns = warnings != null ? warnings : new ArrayList<String>();
        if (out == null) {
            return errors;
        }
        List<Object> items = arrayItems(settingsArrayRaw);
        if (items.isEmpty()) {
            return errors;
        }
        Set<String> seenKeys = new HashSet<>();
        for (Object itemObj : items) {
            if (!(itemObj instanceof Scriptable)) {
                continue;
            }
            Scriptable item = (Scriptable) itemObj;
            String key = stringProp(item, "key");
            String typeRaw = stringProp(item, "type");
            String type = typeRaw == null ? "text" : typeRaw.trim().toLowerCase();
            String title = stringProp(item, "title");

            if ("header".equals(type)) {
                JsPlugin.SettingDef header = new JsPlugin.SettingDef();
                if (key != null && !key.isEmpty()) {
                    warns.add("header '" + (title == null ? key : title)
                            + "': headers must not have a key; key '" + key + "' dropped");
                }
                header.key = "";
                header.type = "header";
                header.title = title == null ? "" : title;
                header.def = "";
                out.add(header);
                continue;
            }
            if (key == null || key.isEmpty()) {
                continue;
            }
            if (seenKeys.contains(key)) {
                String msg = "duplicate setting key '" + key + "' (first definition wins)";
                if (strict) {
                    errors.add(msg);
                } else {
                    warns.add(msg);
                }
                continue;
            }

            JsPlugin.SettingDef def = new JsPlugin.SettingDef();
            def.key = key;
            def.title = (title == null || title.isEmpty()) ? key : title;
            def.help = stringPropOrEmpty(item, "help");
            def.maxLength = intProp(item, "maxLength", 0);
            if (def.maxLength < 0) {
                def.maxLength = 0;
            }
            def.multiline = boolProp(item, "multiline");
            def.min = doubleProp(item, "min");
            def.max = doubleProp(item, "max");
            def.step = doubleProp(item, "step");
            parseOptions(item, def);
            parseDependsOn(item, def);

            boolean knownType = "bool".equals(type) || "text".equals(type)
                    || "number".equals(type) || "slider".equals(type)
                    || "select".equals(type) || "color".equals(type)
                    || "file".equals(type);
            if (!knownType) {
                String msg = "setting '" + key + "': unknown type '" + typeRaw + "'";
                if (strict) {
                    errors.add(msg);
                } else {
                    warns.add(msg + "; coerced to text");
                }
                type = "text";
            }
            if ("select".equals(type) && def.optionValues.isEmpty()) {
                String msg = "setting '" + key + "': select has no options";
                if (strict) {
                    errors.add(msg);
                } else {
                    warns.add(msg + "; coerced to text");
                }
                type = "text";
            }
            if ("color".equals(type)) {
                // Phase B (§1.5): the stored form is canonical
                // "#RRGGBB"; an invalid declared default is a schema
                // error in strict mode (warning + neutral default
                // in loose mode, like the other coercions).
                Object rawDef = prop(item, "def");
                String color = rawDef == null ? "" : String.valueOf(rawDef).trim();
                if (!color.isEmpty() && !isHexColor(color)) {
                    String msg = "setting '" + key + "': color default '" + color
                            + "' is not #RRGGBB";
                    if (strict) {
                        errors.add(msg);
                    } else {
                        warns.add(msg + "; default reset to #000000");
                    }
                    color = "#000000";
                }
                def.type = "color";
                def.def = color.isEmpty() ? "#000000" : normalizeColor(color);
                def.accept = stringPropOrEmpty(item, "accept");
                seenKeys.add(key);
                out.add(def);
                continue;
            }
            if ("file".equals(type)) {
                // Phase B (§1.5): value is a durable-copied path
                // string ("" = nothing picked yet); accept is the
                // SAF mime pattern, carried through to the picker.
                def.type = "file";
                Object rawDef = prop(item, "def");
                def.def = rawDef == null ? "" : String.valueOf(rawDef);
                def.accept = stringPropOrEmpty(item, "accept");
                seenKeys.add(key);
                out.add(def);
                continue;
            }
            def.type = type;

            Object defValue = prop(item, "def");
            if ("number".equals(type) || "slider".equals(type)) {
                if (defValue == null) {
                    def.def = Double.valueOf(0);
                } else if (defValue instanceof Number) {
                    def.def = Double.valueOf(((Number) defValue).doubleValue());
                } else {
                    Double parsed = tryParseDouble(String.valueOf(defValue));
                    if (parsed != null) {
                        def.def = parsed;
                    } else {
                        String msg = "setting '" + key + "': default is not numeric";
                        if (strict) {
                            errors.add(msg);
                        } else {
                            warns.add(msg + "; default reset to 0");
                        }
                        def.def = Double.valueOf(0);
                    }
                }
                if (def.min != null && def.max != null && def.min > def.max) {
                    String msg = "setting '" + key + "': min (" + def.min
                            + ") is greater than max (" + def.max + ")";
                    if (strict) {
                        errors.add(msg);
                    } else {
                        warns.add(msg + "; min/max dropped");
                        def.min = null;
                        def.max = null;
                    }
                }
            } else {
                def.def = defValue == null ? "" : defValue;
            }
            seenKeys.add(key);
            out.add(def);
        }
        for (JsPlugin.SettingDef def : out) {
            if (def.dependsOnKey != null && !def.dependsOnKey.isEmpty()
                    && !seenKeys.contains(def.dependsOnKey)) {
                warns.add("setting '" + def.key + "': dependsOn references unknown key '"
                        + def.dependsOnKey + "'");
            }
        }
        return errors;
    }

    // ------------------------------------------------------------------
    // Raw JS value helpers (Rhino values: Double / String / Boolean)
    // ------------------------------------------------------------------

    private static List<Object> arrayItems(Object raw) {
        List<Object> items = new ArrayList<>();
        if (raw == null || raw == Scriptable.NOT_FOUND || raw instanceof Undefined) {
            return items;
        }
        if (raw instanceof List) {
            items.addAll((List<?>) raw);
            return items;
        }
        if (raw instanceof NativeArray) {
            NativeArray array = (NativeArray) raw;
            for (int i = 0; i < array.getLength(); i++) {
                items.add(array.get(i, array));
            }
            return items;
        }
        if (raw instanceof Scriptable) {
            Scriptable array = (Scriptable) raw;
            Object length = array.get("length", array);
            if (length instanceof Number) {
                int n = ((Number) length).intValue();
                for (int i = 0; i < n; i++) {
                    items.add(array.get(i, array));
                }
            }
        }
        return items;
    }

    /** Property value, or null when absent / NOT_FOUND / undefined. */
    private static Object prop(Scriptable obj, String key) {
        Object value = obj.get(key, obj);
        if (value == null || value == Scriptable.NOT_FOUND || value instanceof Undefined) {
            return null;
        }
        return value;
    }

    private static String stringProp(Scriptable obj, String key) {
        Object value = prop(obj, key);
        return value == null ? null : String.valueOf(value);
    }

    private static String stringPropOrEmpty(Scriptable obj, String key) {
        String value = stringProp(obj, key);
        return value == null ? "" : value;
    }

    private static boolean boolProp(Scriptable obj, String key) {
        Object value = prop(obj, key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static int intProp(Scriptable obj, String key, int fallback) {
        Object value = prop(obj, key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            Double parsed = tryParseDouble(String.valueOf(value));
            if (parsed != null) {
                return parsed.intValue();
            }
        }
        return fallback;
    }

    private static Double doubleProp(Scriptable obj, String key) {
        Object value = prop(obj, key);
        if (value instanceof Number) {
            return Double.valueOf(((Number) value).doubleValue());
        }
        if (value != null) {
            return tryParseDouble(String.valueOf(value));
        }
        return null;
    }

    /** Canonical settings-color form check: #RRGGBB (case-free). */
    public static boolean isHexColor(String value) {
        if (value == null || value.length() != 7 || value.charAt(0) != '#') {
            return false;
        }
        for (int i = 1; i < 7; i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** Normalises a valid #RRGGBB string to upper-case hex. */
    public static String normalizeColor(String value) {
        return "#" + value.substring(1).toUpperCase(java.util.Locale.US);
    }

    private static Double tryParseDouble(String text) {
        try {
            return Double.valueOf(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static void parseOptions(Scriptable item, JsPlugin.SettingDef def) {
        Object raw = prop(item, "options");
        for (Object option : arrayItems(raw)) {
            if (option == null) {
                continue;
            }
            if (option instanceof Scriptable) {
                Scriptable opt = (Scriptable) option;
                String value = stringProp(opt, "value");
                String label = stringProp(opt, "label");
                if (value == null && label == null) {
                    continue;
                }
                if (value == null) {
                    value = label;
                }
                if (label == null) {
                    label = value;
                }
                def.optionValues.add(value);
                def.optionLabels.add(label);
            } else {
                String value = String.valueOf(option);
                def.optionValues.add(value);
                def.optionLabels.add(value);
            }
        }
    }

    private static void parseDependsOn(Scriptable item, JsPlugin.SettingDef def) {
        Object raw = prop(item, "dependsOn");
        if (raw instanceof Scriptable) {
            Scriptable dep = (Scriptable) raw;
            String key = stringProp(dep, "key");
            def.dependsOnKey = key == null ? "" : key;
            Object equals = prop(dep, "equals");
            def.dependsOnEquals = equals == null ? "" : String.valueOf(equals);
        } else if (raw instanceof String && !((String) raw).isEmpty()) {
            def.dependsOnKey = (String) raw;
            def.dependsOnEquals = "";
        }
    }
}
