package org.telegram.messenger.amin.plugins.js;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Plugins bundled with AminGram, installable in one tap from the hub.
 * They are ordinary plugin files (assets/amingram_plugins/*.js) and go
 * through the exact same manifest validation + install path as
 * user-added plugins — no special casing.
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
    }

    private BundledPlugins() {
    }

    public static List<Bundled> all() {
        return LIST;
    }

    public static boolean isInstalled(Context context, Bundled bundled) {
        return JsPluginManager.get(context, bundled.id) != null;
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
