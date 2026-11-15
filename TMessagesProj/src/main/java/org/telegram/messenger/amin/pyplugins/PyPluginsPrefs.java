package org.telegram.messenger.amin.pyplugins;

/**
 * Constants for the Python (.plugin) engine — M0.
 *
 * The Python engine is a SECOND plugin engine next to the JS/Rhino
 * one (platform v2, prefs {@code amingram_jsplugins}). It is
 * default OFF until M3, has its own master switch, its own prefs
 * file, its own on-disk dir and its own per-plugin logs, so the two
 * engines can never confuse each other's state.
 *
 * Presented versions (honest compat claim): the app version is the
 * real Telegram base (13.2.2); the SDK version is the exteraGram
 * SDK version whose documented surface this host implements
 * (1.4.4.3). A plugin that requires a newer SDK is refused at
 * install — the host must never claim a version it does not
 * implement.
 */
public final class PyPluginsPrefs {

    private PyPluginsPrefs() {
    }

    /** SharedPreferences file for the Python engine. */
    public static final String PREFS = "amingram_pyplugins";

    /** Master kill-switch. Default false (M0–M2); engine off = no
     *  Python plugin loads or dispatches anywhere. */
    public static final String KEY_ENGINE = "pyplugins_enabled";

    /** Install order (JSONArray of plugin ids), mirroring the JS engine. */
    public static final String KEY_ORDER = "order";

    /** Per-plugin metadata JSON, keyed {@code meta_<id>}. */
    public static final String KEY_META_PREFIX = "meta_";

    /** Per-plugin log (one JSON line per entry), keyed {@code log_<id>}. */
    public static final String KEY_LOG_PREFIX = "log_";

    /** Per-plugin settings JSON, keyed {@code settings_<id>}. */
    public static final String KEY_SETTINGS_PREFIX = "settings_";

    /** Per-plugin error state: last load/dispatch error, keyed {@code err_<id>}. */
    public static final String KEY_ERROR_PREFIX = "err_";

    /** On-disk dir under filesDir holding installed {@code .plugin} files. */
    public static final String PLUGIN_DIR = "pyplugins";

    /** Max installed plugin size, 2 MB (exteraGram's catalog cap,
     *  adopted for sideload too). */
    public static final int MAX_PLUGIN_BYTES = 2 * 1024 * 1024;

    /** Max per-plugin log entries kept. */
    public static final int LOG_CAP = 500;

    /** Consecutive load/dispatch failures before a plugin is
     *  auto-disabled (error-storm rule, mirroring the JS engine's
     *  ErrorStormTracker in spirit; counted per plugin, reset on
     *  any successful dispatch). */
    public static final int ERROR_STORM_THRESHOLD = 3;

    /** App version this host presents to plugins (real base). */
    public static final String APP_VERSION = "13.2.2";

    /** SDK version this host claims to implement (docs surface). */
    public static final String SDK_VERSION = "1.4.4.3";

    /** Python version pinned for the Chaquopy build (3.11 = last
     *  version with 32-bit ABI support; 3.12+ is 64-bit only). */
    public static final String PYTHON_VERSION = "3.11";

    // --------------------------------------------------------------
    // M4 — catalog / unknown sources / DevServer
    // --------------------------------------------------------------

    /** M4: allow catalogs from hosts the user has not explicitly
     *  added. Default false: every remote catalog is an unknown
     *  source until the user turns this on in the hub. */
    public static final String KEY_UNKNOWN_SOURCES = "unknown_sources";

    /** M4: catalog index URLs the user added, one per line
     *  (extera-plugins.json documents). Empty by default — there
     *  is no bundled catalog and no bundled plugin. */
    public static final String KEY_REPO_URLS = "repo_urls";

    /** M4: DevServer (localhost only) enabled flag. Default
     *  false; the hub turns it on explicitly and it auto-stops. */
    public static final String KEY_DEVSERVER = "devserver_enabled";

    /** M4: max Elyx archive size, 10 MB. */
    public static final int MAX_ARCHIVE_BYTES = 10 * 1024 * 1024;
}
