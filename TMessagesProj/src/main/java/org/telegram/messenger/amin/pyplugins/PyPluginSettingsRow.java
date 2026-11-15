package org.telegram.messenger.amin.pyplugins;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@code create_settings()} row, as rendered on a Python
 * plugin's page — M1.
 *
 * Pure Java + org.json (no Android imports) so the parsing is
 * unit-testable on a plain JDK. Rows arrive from Python as a JSON
 * string produced by {@code amin_loader.serialize_settings}:
 * {@code {"rows": [...], "warnings": [...]}}. The host fills
 * {@link #currentValue} / {@link #currentBool} from
 * {@link PyPluginSettings} after parsing; a row's {@code default}
 * is used only when nothing is stored yet.
 *
 * Row types (see {@code ui/settings.py} for the Python side):
 * header, switch, selector, input, edittext, text, divider,
 * custom, plus slider (rendered as a number input — the M1 row
 * list has no slider, but a plugin that returns one gets a
 * working number field instead of a dead row). Unknown types are
 * kept as {@code "unsupported"} rows so the page shows them
 * instead of silently dropping them.
 */
public final class PyPluginSettingsRow {

    public static final class Option {
        public String value;
        public String label;

        public Option(String value, String label) {
            this.value = value == null ? "" : value;
            this.label = label == null ? this.value : label;
        }
    }

    /** Row type, lower-cased ("switch", "selector", ...). */
    public String type = "unsupported";
    /** Original type string when {@link #type} is "unsupported". */
    public String originalType = "";
    /** Settings key, null for display-only rows. */
    public String key;
    /** Row title / text. */
    public String text = "";
    /** Default value (raw JSON value from Python, may be Boolean /
     *  Number / String / null). */
    public Object defaultValue;
    /** Current stored value as text (filled by the host), or null
     *  when nothing is stored yet. */
    public String currentValue;
    /** Current stored value as a boolean (switch rows only). */
    public boolean currentBool;
    public final List<Option> options = new ArrayList<>();
    public String hint = "";
    public String description = "";
    /** "text" / "number" (input + edittext rows). */
    public String inputType = "text";
    public double min;
    public double max;
    public double step;
    public boolean hasMin;
    public boolean hasMax;
    public boolean hasStep;

    /** True when this row writes a setting (has a key and an
     *  interactive type). */
    public boolean isInteractive() {
        return key != null && !key.isEmpty()
                && ("switch".equals(type) || "selector".equals(type)
                || "input".equals(type) || "edittext".equals(type)
                || "slider".equals(type));
    }

    /** Display value: current stored value, else the default as text. */
    public String displayValue() {
        if (currentValue != null) {
            return currentValue;
        }
        return defaultAsText();
    }

    /** Default value rendered as text ("" when none). */
    public String defaultAsText() {
        if (defaultValue == null) {
            return "";
        }
        return String.valueOf(defaultValue);
    }

    /** Default value as a boolean (switch rows only). */
    public boolean defaultAsBool() {
        if (defaultValue instanceof Boolean) {
            return (Boolean) defaultValue;
        }
        if (defaultValue instanceof String) {
            return "true".equalsIgnoreCase((String) defaultValue);
        }
        return false;
    }

    /** Effective switch state: stored value wins, else the default. */
    public boolean switchValue() {
        if (currentValue != null) {
            return "true".equalsIgnoreCase(currentValue);
        }
        return defaultAsBool();
    }

    /** Parsed rows + warnings from one serialize_settings() JSON
     *  string. Never returns null; a malformed payload yields an
     *  empty list and a warning. */
    public static Parsed fromJson(String json) {
        Parsed out = new Parsed();
        if (json == null || json.isEmpty()) {
            out.warnings.add("empty settings payload from plugin");
            return out;
        }
        try {
            JSONObject root;
            String trimmed = json.trim();
            if (trimmed.startsWith("[")) {
                // Defensive: accept a bare array too.
                root = new JSONObject("{\"rows\":" + trimmed + "}");
            } else {
                root = new JSONObject(trimmed);
            }
            JSONArray warnings = root.optJSONArray("warnings");
            if (warnings != null) {
                for (int i = 0; i < warnings.length(); i++) {
                    out.warnings.add(warnings.optString(i));
                }
            }
            JSONArray rows = root.optJSONArray("rows");
            if (rows == null) {
                out.warnings.add("settings payload has no 'rows' array");
                return out;
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o == null) {
                    out.warnings.add("settings row " + i + " is not an object");
                    continue;
                }
                out.rows.add(parseRow(o, out.warnings));
            }
        } catch (Exception e) {
            out.rows.clear();
            out.warnings.add("settings payload is not valid JSON: " + e.getMessage());
        }
        return out;
    }

    public static final class Parsed {
        public final List<PyPluginSettingsRow> rows = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }

    private static PyPluginSettingsRow parseRow(JSONObject o, List<String> warnings) {
        PyPluginSettingsRow row = new PyPluginSettingsRow();
        String type = o.optString("type", "");
        if (type.isEmpty()) {
            type = o.optString("kind", "");
        }
        type = type.toLowerCase();
        row.originalType = type;
        if ("header".equals(type) || "switch".equals(type)
                || "selector".equals(type) || "input".equals(type)
                || "edittext".equals(type) || "text".equals(type)
                || "divider".equals(type) || "custom".equals(type)
                || "slider".equals(type)) {
            row.type = type;
        } else {
            row.type = "unsupported";
            warnings.add("unsupported settings row type '" + type + "'");
        }
        row.key = o.has("key") && !o.isNull("key") ? o.optString("key", null) : null;
        String text = o.optString("text", null);
        if (text == null) {
            text = o.optString("title", null);
        }
        if (text == null) {
            text = o.optString("label", null);
        }
        row.text = text == null ? "" : text;
        if (o.has("default") && !o.isNull("default")) {
            row.defaultValue = o.opt("default");
        } else if (o.has("value") && !o.isNull("value")) {
            row.defaultValue = o.opt("value");
        }
        row.hint = o.optString("hint", "");
        row.description = o.optString("description", "");
        String inputType = o.optString("input_type", "");
        if (inputType.isEmpty()) {
            inputType = o.optString("inputType", "");
        }
        if (!inputType.isEmpty()) {
            row.inputType = "number".equalsIgnoreCase(inputType) ? "number" : "text";
        } else if ("slider".equals(row.type)) {
            row.inputType = "number";
        }
        JSONArray options = o.optJSONArray("options");
        if (options != null) {
            for (int i = 0; i < options.length(); i++) {
                JSONObject opt = options.optJSONObject(i);
                if (opt != null) {
                    String value = opt.optString("value", opt.optString("text", ""));
                    String label = opt.optString("label", opt.optString("text", value));
                    row.options.add(new Option(value, label));
                } else {
                    Object raw = options.opt(i);
                    String value = raw == null ? "" : String.valueOf(raw);
                    row.options.add(new Option(value, value));
                }
            }
        }
        if (o.has("min") && !o.isNull("min")) {
            row.min = o.optDouble("min", 0);
            row.hasMin = true;
        }
        if (o.has("max") && !o.isNull("max")) {
            row.max = o.optDouble("max", 0);
            row.hasMax = true;
        }
        if (o.has("step") && !o.isNull("step")) {
            row.step = o.optDouble("step", 0);
            row.hasStep = true;
        }
        return row;
    }
}
