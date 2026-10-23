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
 * v2.0: deletions are now tracked EXPLICITLY in a persistent
 * "bundled_user_deleted" set (same prefs file), written by
 * JsPluginManager.delete via {@link #markUserDeleted} and cleared
 * by any successful install of that id. ensureInstalled only
 * auto-installs a bundled id that is (a) not installed, (b) not
 * in bundled_seen and (c) not user-deleted — i.e. genuinely new
 * with this app version — so a deleted bundled plugin can never
 * be resurrected by an update, while a genuinely new one still
 * arrives pre-installed. The not-installed set (installed ones
 * excluded) is the user-facing library: {@link #library}.
 *
 * Promotion update (v2.3): an ALREADY-installed bundled plugin
 * whose bundled asset now declares a HIGHER @version is updated
 * in place by ensureInstalled, through the same standard install
 * path ({@link #install}). That path replaces only the code file
 * (keeping a .prev.js rollback copy) and the metadata — enabled
 * state, installed-at and the stored data version carry over —
 * and never touches the per-plugin data stores (amin.storage /
 * settings values, PluginDb rows, the amin.files sandbox; only
 * JsPluginManager.delete and the Developer-tools "clear data"
 * action remove those), so user settings, API keys and plugin
 * data survive an APK update. An installed copy at the same or
 * a higher version (e.g. one imported from a .aminpack) is left
 * completely alone: bundled delivery never downgrades or
 * clobbers a newer install.
 *
 * - shout:      the command example from PLUGIN-MASTER-PROMPT.md.
 * - vip-alert:  the observer+settings example from the master prompt.
 * - media-dl:   Downloader Pro — TikTok / X / Reddit / Instagram /
 *               Threads / Pinterest media into the chat
 *               (/dl, /mp3, attach-menu + long-press actions).
 * - temp-mail:  disposable email + verification-code reader via
 *               mail.tm (/tm …, attach-menu button).
 * - aesthetic-text: text restyling commands (/wide, /space,
 *               /fliptext, attach-menu hint).
 * - quick-translate: /tr translation via the keyless Google
 *               endpoint (default target Persian, /tr en …).
 * - gemini-chat: /ai chat with Gemini, authorized by the user's
 *               own connected Google account (the `google`
 *               capability + amin.httpPost; no API key).
 * - ai-actions: /sum, /explain and /draft acting on a message
 *               (reply target, passed text, or — via the attach
 *               menu — the last message observed in the chat),
 *               same dual-mode Gemini auth as gemini-chat.
 * - quick-replies: /tpl text templates (set/list/del/send),
 *               stored as one JSON object (storage v2 when the
 *               platform offers it, legacy strings otherwise).
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

    // Descriptor name + tagline mirror each plugin's manifest
    // @name / @description verbatim (three-way sync with
    // assets/amingram_plugins/*.js and catalog/index.json —
    // verified by the promotion sync check, MODS.md v2.3).
    static {
        LIST.add(new Bundled("shout", "Shout", "amingram_plugins/shout.js",
                "/shout <text> posts the text in uppercase. Optional repeat count in the plugin settings."));
        LIST.add(new Bundled("vip-alert", "VIP Alert", "amingram_plugins/vip-alert.js",
                "Toasts when a chosen person writes in any chat. Watch several people at once: comma-separated names or numeric user IDs in the setting below."));
        LIST.add(new Bundled("media-dl", "Downloader Pro", "amingram_plugins/media-dl.js",
                "Downloads public media into the chat from TikTok, Instagram, X/Twitter, Reddit, Pinterest, Threads and direct media links. Supports albums, quality choices, TikTok MP3, replied-to links, a long-press download action, download history and link fallback."));
        LIST.add(new Bundled("temp-mail", "Temp Mail", "amingram_plugins/temp-mail.js",
                "Disposable email via mail.tm: /tm new creates a real account, /tm inbox lists mail, /tm read <id> opens one — verification codes and links are extracted and posted in chat. (v2.1: async HTTP, plugin page with address/copy/inbox/forget actions, address history kept in amin.db.)"));
        LIST.add(new Bundled("aesthetic-text", "Aesthetic Text", "amingram_plugins/aesthetic-text.js",
                "Restyle text: /wide makes ｆｕｌｌｗｉｄｔｈ text, /space spreads l e t t e r s out, /fliptext turns text upside down, /mono makes 𝚖𝚘𝚗𝚘𝚜𝚙𝚊𝚌𝚎 text, /bubble makes ⓑⓤⓑⓑⓛⓔ text, /bold makes 𝐛𝐨𝐥𝐝 text."));
        LIST.add(new Bundled("quick-translate", "Quick Translate", "amingram_plugins/quick-translate.js",
                "/tr <text> translates text (default target: Persian). /tr en <text> picks another target language. Long-press a message → Translate does the same for that message."));
        LIST.add(new Bundled("gemini-chat", "Gemini Chat", "amingram_plugins/gemini-chat.js",
                "/ai <question> asks Gemini, using your connected Google account (no API key). Dual mode: AI Studio or Vertex AI (Plugins → Google Account). (v1.1: async requests, plugin page with connection status, question history in amin.db.)"));
        LIST.add(new Bundled("link-cleaner", "Link Cleaner", "amingram_plugins/link-cleaner.js",
                "/clean <url> strips tracking parameters (utm_*, fbclid, gclid, igshid, ref, si, …) and resolves short links. No argument reuses the last link seen in this chat — or tap Link Cleaner in the chat attach menu."));
        LIST.add(new Bundled("qr-maker", "QR Maker", "amingram_plugins/qr-maker.js",
                "/qr <text or link> posts a QR code image for it (qrserver.com). If the image cannot be sent, the QR image link is posted instead."));
        LIST.add(new Bundled("price-check", "Price Check", "amingram_plugins/price-check.js",
                "/price shows live Iran-market prices in tomans (dollar, gold 18k, Emami coin, BTC, ETH). /price btc|eth|usd|gold|coin shows just one. Sources: tgju.org + CoinGecko — the same endpoints as the toolbox bot."));
        LIST.add(new Bundled("ai-actions", "AI Actions", "amingram_plugins/ai-actions.js",
                "AI on any message: reply to a message with /sum to summarize it, /explain to explain it simply, or /draft to draft a reply to it — or pass text directly (/sum <text>). Uses Gemini via your connected Google account (AI Studio or Vertex AI mode, see Plugins → Google Account). (v1.1: async requests, usage stats in amin.db on the plugin page.)"));
        LIST.add(new Bundled("quick-replies", "Quick Replies", "amingram_plugins/quick-replies.js",
                "Text templates: /tpl set <name> <text> saves one, /tpl <name> posts it, /tpl list shows your templates, /tpl del <name> removes one, /tpl export posts them all in one message. Note: the /tpl command message itself also appears in the chat (the platform can't swallow outgoing commands yet)."));
    }

    private BundledPlugins() {
    }

    public static List<Bundled> all() {
        return LIST;
    }

    public static boolean isInstalled(Context context, Bundled bundled) {
        return JsPluginManager.get(context, bundled.id) != null;
    }

    public static boolean isBundledId(String id) {
        return bundledById(id) != null;
    }

    public static Bundled bundledById(String id) {
        if (id == null) {
            return null;
        }
        for (Bundled bundled : LIST) {
            if (bundled.id.equals(id)) {
                return bundled;
            }
        }
        return null;
    }

    /** The library: every bundled plugin that is NOT currently
     *  installed — genuinely new ones, ones the user deleted
     *  ({@link #isUserDeleted}) and ones never delivered — in
     *  catalog order. Installing one (via {@link #install}) clears
     *  its user-deleted marker. */
    public static List<Bundled> library(Context context) {
        List<Bundled> out = new ArrayList<>();
        for (Bundled bundled : LIST) {
            if (!isInstalled(context, bundled)) {
                out.add(bundled);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // User-deleted set (v2.0) + migration from bundled_seen
    // ------------------------------------------------------------------

    private static final String KEY_SEEN = "bundled_seen";
    private static final String KEY_USER_DELETED = "bundled_user_deleted";
    private static boolean ensured;

    /** Ids the user explicitly deleted. Reading this set runs the
     *  one-time migration (see below) when the key is absent. */
    public static Set<String> userDeletedIds(Context context) {
        Set<String> out = new HashSet<>();
        try {
            if (!prefs(context).contains(KEY_USER_DELETED)) {
                migrateUserDeleted(context);
            }
            String raw = prefs(context).getString(KEY_USER_DELETED, null);
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

    public static boolean isUserDeleted(Context context, String id) {
        return id != null && userDeletedIds(context).contains(id);
    }

    /**
     * One-time migration from the v1.3.4 tombstone semantics.
     *
     * Before v2.0 there was no explicit deleted set: a bundled id
     * in "bundled_seen" that was NOT installed could only have got
     * there by having been delivered (auto- or manual install —
     * seen is written only on a successful install) and later
     * removed, and the only removal path is the user deleting the
     * plugin. So, safely:
     * - seen ∧ installed     → stays installed; NOT user-deleted.
     * - seen ∧ not installed → the old tombstone: user-deleted.
     * - not seen ∧ installed → installed by hand before delivery
     *   tracking caught up; NOT user-deleted (and delivery marks
     *   it seen in ensureInstalled).
     * - not seen ∧ not installed → never delivered: genuinely new
     *   (or a past delivery failure — old code only marked seen on
     *   success, so a failure was never tombstoned); NOT
     *   user-deleted, ensureInstalled may still deliver it.
     *
     * The migrated set is written back under KEY_USER_DELETED even
     * when empty; the key's presence is the "migrated" marker, so
     * this runs at most once. bundled_seen itself is left intact
     * (the hub's wasSeen-based "deleted" label keeps working).
     */
    private static synchronized void migrateUserDeleted(Context context) {
        try {
            if (prefs(context).contains(KEY_USER_DELETED)) {
                return;
            }
            Set<String> deleted = new HashSet<>();
            for (String id : seenIds(context)) {
                if (isBundledId(id) && JsPluginManager.get(context, id) == null) {
                    deleted.add(id);
                }
            }
            writeIdSet(context, KEY_USER_DELETED, deleted);
        } catch (Throwable ignored) {
        }
    }

    /** Records an explicit user deletion of a bundled plugin
     *  (called by JsPluginManager.delete). No-op for non-bundled
     *  ids. A marked id is never auto-installed again, but stays
     *  in the library for a manual reinstall. */
    public static void markUserDeleted(Context context, String id) {
        if (context == null || !isBundledId(id)) {
            return;
        }
        try {
            Set<String> deleted = userDeletedIds(context);
            if (deleted.add(id)) {
                writeIdSet(context, KEY_USER_DELETED, deleted);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Clears the user-deleted marker (any successful install of
     *  the id — library reinstall or manual import — calls this
     *  via {@link #onInstalled}). No-op for non-bundled ids. */
    public static void clearUserDeleted(Context context, String id) {
        if (context == null || !isBundledId(id)) {
            return;
        }
        try {
            Set<String> deleted = userDeletedIds(context);
            if (deleted.remove(id)) {
                writeIdSet(context, KEY_USER_DELETED, deleted);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Book-keeping for a successful install of a bundled id, from
     *  any path (auto-delivery, library, manual import): the id is
     *  delivered (seen) and no longer user-deleted. Called by
     *  JsPluginManager's install path and by {@link #install}. */
    public static void onInstalled(Context context, String id) {
        if (context == null || !isBundledId(id)) {
            return;
        }
        clearUserDeleted(context, id);
        try {
            Set<String> seen = seenIds(context);
            if (seen.add(id)) {
                writeIdSet(context, KEY_SEEN, seen);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeIdSet(Context context, String key, Set<String> ids) {
        JSONArray arr = new JSONArray();
        for (String id : ids) {
            arr.put(id);
        }
        prefs(context).edit().putString(key, arr.toString()).apply();
    }

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

    /** Installs every genuinely-new bundled plugin, through the
     *  normal install path. Runs at most once per process (called
     *  from JsPluginManager.loadCache) and is fully
     *  Throwable-isolated: auto-install must never break the
     *  plugin system's first touch.
     *
     *  v2.0 delivery rule — a bundled id is auto-installed ONLY
     *  when it is (a) not installed, (b) not in bundled_seen and
     *  (c) not user-deleted. Seen-but-absent ids are the old
     *  tombstone case (migrated to user-deleted on first read) and
     *  are skipped twice over; user-deleted ids are skipped even
     *  if seen were somehow lost. An id is marked seen only when
     *  the plugin actually ends up installed, so a failed delivery
     *  is retried on the next process start and the library keeps
     *  offering it meanwhile.
     *
     *  Promotion update rule (v2.3) — an INSTALLED bundled id is
     *  checked against its bundled asset's @version: when the
     *  asset is newer, the plugin is updated in place through
     *  {@link #install} (code + metadata only; per-plugin data is
     *  never touched by the install path). Same-or-newer installs
     *  are left exactly as they are. */
    public static void ensureInstalled(Context context) {
        if (ensured) {
            return;
        }
        ensured = true;
        try {
            Set<String> seen = seenIds(context);
            Set<String> userDeleted = userDeletedIds(context);
            boolean changed = false;
            for (Bundled bundled : LIST) {
                if (userDeleted.contains(bundled.id)) {
                    continue; // deleted by the user: never resurrect
                }
                JsPlugin installedPlugin = JsPluginManager.get(context, bundled.id);
                if (installedPlugin != null) {
                    if (seen.add(bundled.id)) {
                        changed = true;
                    }
                    updateIfBundledNewer(context, bundled, installedPlugin);
                    continue;
                }
                if (seen.contains(bundled.id)) {
                    continue; // delivered before, currently absent
                }
                if (install(context, bundled) == null) {
                    seen.add(bundled.id);
                    changed = true;
                }
            }
            if (changed) {
                writeIdSet(context, KEY_SEEN, seen);
            }
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(JsPluginManager.PREFS, Context.MODE_PRIVATE);
    }

    /** Reads a bundled asset's source text. */
    private static String assetSource(Context context, Bundled bundled) throws Exception {
        AssetManager assets = context.getAssets();
        InputStream in = assets.open(bundled.asset);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toString("UTF-8");
    }

    /** Promotion update (v2.3): when the bundled asset declares a
     *  HIGHER @version than the installed copy, update the plugin
     *  in place through {@link #install}. The install path swaps
     *  only the code file (old code kept as .prev.js) and the
     *  metadata — it never touches the per-plugin storage/settings
     *  prefs, PluginDb rows or the files sandbox, and it carries
     *  over the enabled flag and stored data version (onMigrate
     *  still runs at warm-up when the data version moved), so the
     *  user's settings, API keys and data survive. An installed
     *  copy at the same or a higher version — e.g. imported from
     *  a .aminpack — is NOT downgraded or clobbered. Fully
     *  Throwable-isolated like the rest of ensureInstalled: a bad
     *  asset can never break first touch. */
    private static void updateIfBundledNewer(Context context, Bundled bundled,
                                             JsPlugin installedPlugin) {
        try {
            String source = assetSource(context, bundled);
            JsManifest manifest = JsManifest.parse(source);
            if (manifest.version != null && !manifest.version.isEmpty()
                    && JsPluginManager.compareVersions(
                            manifest.version, installedPlugin.version) > 0) {
                install(context, bundled);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Installs (or reinstalls/updates) a bundled plugin through the
     *  standard path. Returns null on success, else the error. A
     *  successful install clears the plugin's user-deleted marker,
     *  so a library reinstall fully restores the plugin (the
     *  manager's install path also calls {@link #onInstalled};
     *  both are idempotent). */
    public static String install(Context context, Bundled bundled) {
        try {
            String source = assetSource(context, bundled);
            String error = JsPluginManager.installWithSource(context, source, "bundled");
            if (error == null) {
                onInstalled(context, bundled.id);
            }
            return error;
        } catch (Throwable t) {
            return "Could not read the bundled plugin: " + t.getMessage();
        }
    }

    /** Async variant for UI callers (audit P1-7): the asset read
     *  stays on the caller (a small text asset, exactly as in
     *  {@link #install}), but the validated install's Rhino compile
     *  + probe runs on the plugin thread via
     *  JsPluginManager.installAsync; the result (null = success)
     *  is delivered on the UI thread. */
    public static void installAsync(Context context, Bundled bundled,
                                    java.util.function.Consumer<String> done) {
        final String source;
        try {
            source = assetSource(context, bundled);
        } catch (Throwable t) {
            final String error = "Could not read the bundled plugin: " + t.getMessage();
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                if (done != null) {
                    done.accept(error);
                }
            });
            return;
        }
        JsPluginManager.installAsync(context, source, "bundled", error -> {
            if (error == null) {
                onInstalled(context, bundled.id);
            }
            if (done != null) {
                done.accept(error);
            }
        });
    }
}
