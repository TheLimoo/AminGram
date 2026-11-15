package org.telegram.messenger.amin.pyplugins;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * An installed Python (.plugin) plugin: metadata + install state.
 * Mirrors {@code amin.plugins.js.JsPlugin} in shape so the hub,
 * backup and log tooling can treat both engines alike; the Python
 * engine stores code as a {@code .plugin} file on disk, never in
 * prefs (2 MB cap).
 */
public final class PyPlugin {

    public String id;
    public String name;
    public String version = "1.0";
    public String author = "";
    public String description = "";
    public String icon = "";
    public String appVersion = "";
    public String sdkVersion = "";
    public final List<String> categories = new ArrayList<>();
    /** Raw {@code __requirements__} (PyPI packages). Non-empty =
     *  install is refused in M0–M3 (no pip installer in the app). */
    public final List<String> requirements = new ArrayList<>();
    public boolean enabled = true;
    /** True when the last load/dispatch failed; the plugin is
     *  disabled until the user re-enables it. */
    public boolean errored;
    public String errorText = "";
    public long installedAt;
    public String installSource = "";
    public String fingerprint = "";
    /** Install-time static-analysis findings (plain-language lines). */
    public final List<String> analysis = new ArrayList<>();
    /** Set when analysis flagged DEX / native-code loading: the
     *  plugin cannot be enabled until the user turns on the
     *  per-plugin DEX consent (M3). M0: always false on install,
     *  kept here so M3 needs no model change. */
    public boolean dexConsent;
    /** Set at install (M3) when the analysis flagged DEX / native
     *  code: the plugin installs disabled and cannot load until
     *  the user grants {@link #dexConsent} on the plugin page.
     *  False for old installs (the JSON key is absent there) and
     *  for plugins the analysis cleared. */
    public boolean needsDexConsent;

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name == null ? id : name);
            o.put("version", version == null ? "1.0" : version);
            o.put("author", author == null ? "" : author);
            o.put("description", description == null ? "" : description);
            o.put("icon", icon == null ? "" : icon);
            o.put("appVersion", appVersion == null ? "" : appVersion);
            o.put("sdkVersion", sdkVersion == null ? "" : sdkVersion);
            JSONArray cats = new JSONArray();
            for (String c : categories) {
                cats.put(c);
            }
            o.put("categories", cats);
            JSONArray reqs = new JSONArray();
            for (String r : requirements) {
                reqs.put(r);
            }
            o.put("requirements", reqs);
            o.put("enabled", enabled);
            o.put("errored", errored);
            o.put("errorText", errorText == null ? "" : errorText);
            o.put("installedAt", installedAt);
            o.put("installSource", installSource == null ? "" : installSource);
            o.put("fingerprint", fingerprint == null ? "" : fingerprint);
            JSONArray an = new JSONArray();
            for (String a : analysis) {
                an.put(a);
            }
            o.put("analysis", an);
            o.put("dexConsent", dexConsent);
            o.put("needsDexConsent", needsDexConsent);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static PyPlugin fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        PyPlugin plugin = new PyPlugin();
        plugin.id = o.optString("id", null);
        if (plugin.id == null || plugin.id.isEmpty()) {
            return null;
        }
        plugin.name = o.optString("name", plugin.id);
        plugin.version = o.optString("version", "1.0");
        plugin.author = o.optString("author", "");
        plugin.description = o.optString("description", "");
        plugin.icon = o.optString("icon", "");
        plugin.appVersion = o.optString("appVersion", "");
        plugin.sdkVersion = o.optString("sdkVersion", "");
        JSONArray cats = o.optJSONArray("categories");
        if (cats != null) {
            for (int i = 0; i < cats.length(); i++) {
                plugin.categories.add(cats.optString(i));
            }
        }
        JSONArray reqs = o.optJSONArray("requirements");
        if (reqs != null) {
            for (int i = 0; i < reqs.length(); i++) {
                plugin.requirements.add(reqs.optString(i));
            }
        }
        plugin.enabled = o.optBoolean("enabled", true);
        plugin.errored = o.optBoolean("errored", false);
        plugin.errorText = o.optString("errorText", "");
        plugin.installedAt = o.optLong("installedAt", 0);
        plugin.installSource = o.optString("installSource", "");
        plugin.fingerprint = o.optString("fingerprint", "");
        JSONArray an = o.optJSONArray("analysis");
        if (an != null) {
            for (int i = 0; i < an.length(); i++) {
                plugin.analysis.add(an.optString(i));
            }
        }
        plugin.dexConsent = o.optBoolean("dexConsent", false);
        plugin.needsDexConsent = o.optBoolean("needsDexConsent", false);
        return plugin;
    }
}
