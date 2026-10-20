package org.telegram.messenger.amin.plugins.js;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plugins bundled with AminGram. They are ordinary plugin files
 * (assets/amingram_plugins/*.js) and go through the exact same
 * manifest validation + install path as user-added plugins — no
 * special casing.
 *
 * v1.3.4: bundled plugins come PRE-INSTALLED (Amin's call — no
 * Install buttons). The first time the plugin system initializes
 * in a process, {@link #ensureInstalled} installs every bundled
 * plugin that has never been auto-installed before. Delivery is
 * tracked per id in the "bundled_seen" set (in the JsPluginManager
 * prefs): once a bundled plugin has been delivered — or was
 * already installed by hand — it is never auto-installed again,
 * so a bundled plugin the user DELETES stays deleted. The hub
 * offers those back as "Built-in · deleted — tap to reinstall".
 *
 * - shout:      the command example from PLUGIN-MASTER-PROMPT.md.
 * - vip-alert:  the observer+settings example from the master prompt.
 * - media-dl:   TikTok / X / Reddit / Instagram direct-link extractor
 *               (/dl, /mp3, attach-menu button).
 * - temp-mail:  disposable email + verification-code reader via
 *               1secmail (/tm …, attach-menu button).
 * - aesthetic-text: text restyling commands (/wide, /space,
 *               /fliptext, attach-menu hint).
 * - quick-translate: /tr translation via the keyless Google
 *               endpoint (default target Persian, /tr en …).
 * - gemini-chat: /ai chat with Gemini, authorized by the user's
 *               own connected Google account (the `google`
 *               capability + amin.httpPost; no API key).
 */
public final class BundledPlugins {

    public static final class Bundled {
        public final String id;
        public final String name;
        public final String asset;
        public final String tagline;

        Bundled(String id, String name, String asset, String tagline) {
            this.id = id;
            this.name = name;
            this.asset = asset;
            this.tagline = tagline;
        }
    }

    private static final List<Bundled> LIST = new ArrayList<>();

    static {
        LIST.add(new Bundled("shout", "Shout", "amingram_plugins/shout.js",
                "/shout <text> posts the text in uppercase."));
        LIST.add(new Bundled("vip-alert", "VIP Alert", "amingram_plugins/vip-alert.js",
                "Toasts when a chosen person writes in any chat."));
        LIST.add(new Bundled("media-dl", "Media Downloader", "amingram_plugins/media-dl.js",
                "Direct MP4/MP3 links from TikTok, X, Reddit and Instagram posts. /dl, /mp3 or the attach menu."));
        LIST.add(new Bundled("temp-mail", "Temp Mail", "amingram_plugins/temp-mail.js",
                "Disposable email addresses + verification codes inside Telegram. /tm or the attach menu."));
        LIST.add(new Bundled("aesthetic-text", "Aesthetic Text", "amingram_plugins/aesthetic-text.js",
                "Restyle text: /wide ｆｕｌｌｗｉｄｔｈ, /space s p a c e d, /fliptext upside down."));
        LIST.add(new Bundled("quick-translate", "Quick Translate", "amingram_plugins/quick-translate.js",
                "/tr <text> translates (default: Persian; /tr en <text> for English)."));
        LIST.add(new Bundled("gemini-chat", "Gemini Chat", "amingram_plugins/gemini-chat.js",
                "/ai <question> asks Gemini — powered by your connected Google account, no API key."));
    }

    private BundledPlugins() {
    }

    public static List<Bundled> all() {
        return LIST;
    }

    public static boolean isInstalled(Context context, Bundled bundled) {
        return JsPluginManager.get(context, bundled.id) != null;
    }

    // ------------------------------------------------------------------
    // Pre-install (v1.3.4)
    // ------------------------------------------------------------------

    private static final String KEY_SEEN = "bundled_seen";
    private static boolean ensured;

    /** True once this bundled id has been delivered by auto-install
     *  (or found already installed). Seen ids are never auto-
     *  installed again — a user deletion is permanent. */
    public static boolean wasSeen(Context context, String id) {
        return seenIds(context).contains(id);
    }

    private static Set<String> seenIds(Context context) {
        Set<String> out = new HashSet<>();
        try {
            String raw = prefs(context).getString(KEY_SEEN, null);
            if (raw != null) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    out.add(arr.optString(i));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Installs every bundled plugin that has never been delivered
     *  before, through the normal install path. Runs at most once
     *  per process (called from JsPluginManager.loadCache) and is
     *  fully Throwable-isolated: auto-install must never break the
     *  plugin system's first touch. An id is marked seen only when
     *  the plugin actually ends up installed, so a failed delivery
     *  is retried on the next process start and the hub keeps its
     *  plain "Install" fallback row meanwhile. */
    public static void ensureInstalled(Context context) {
        if (ensured) {
            return;
        }
        ensured = true;
        try {
            Set<String> seen = seenIds(context);
            boolean changed = false;
            for (Bundled bundled : LIST) {
                if (seen.contains(bundled.id)) {
                    continue;
                }
                boolean installed = JsPluginManager.get(context, bundled.id) != null;
                if (!installed) {
                    installed = install(context, bundled) == null;
                }
                if (installed) {
                    seen.add(bundled.id);
                    changed = true;
                }
            }
            if (changed) {
                JSONArray arr = new JSONArray();
                for (String id : seen) {
                    arr.put(id);
                }
                prefs(context).edit().putString(KEY_SEEN, arr.toString()).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(JsPluginManager.PREFS, Context.MODE_PRIVATE);
    }

    /** Installs (or reinstalls/updates) a bundled plugin through the
     *  standard path. Returns null on success, else the error. */
    public static String install(Context context, Bundled bundled) {
        try {
            AssetManager assets = context.getAssets();
            InputStream in = assets.open(bundled.asset);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            in.close();
            String source = out.toString("UTF-8");
            return JsPluginManager.install(context, source);
        } catch (Throwable t) {
            return "Could not read the bundled plugin: " + t.getMessage();
        }
    }
}
