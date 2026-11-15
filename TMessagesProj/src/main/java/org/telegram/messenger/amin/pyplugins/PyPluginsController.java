package org.telegram.messenger.amin.pyplugins;

import android.content.Context;
import android.content.SharedPreferences;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.amin.ui.AminPyPluginActivity;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry + lifecycle for Python (.plugin) plugins — M0.
 *
 * Mirrors the JS engine's {@code JsPluginManager} in shape
 * (prefs cache, order list, per-plugin meta JSON, install /
 * enable / delete, per-hook error isolation) so the hub and the
 * backup tooling can treat both engines alike. Differences are
 * deliberate and documented where they occur:
 *
 * <ul>
 *   <li>Metadata is parsed in Java ({@link PyPluginMetadata}),
 *       not in Python — install must work before Python starts.</li>
 *   <li>Plugin code is loaded through the clean-room Python helper
 *       {@code amin_loader} (in {@code src/main/python/}), which
 *       imports the {@code .plugin} file as a module and finds the
 *       {@code BasePlugin} subclass. No exteraGram code is involved.</li>
 *   <li>Dispatch is M0-provisional: a plugin that defines
 *       {@code on_message(data)} receives incoming messages as a
 *       plain dict; one that defines {@code on_command(text)}
 *       receives the user's own "/" commands and may return a
 *       truthy value to consume them (suppressing the literal
 *       message, like the JS engine). The full SDK hook registry
 *       ({@code add_hook} etc.) is M2.</li>
 *   <li>All Python calls run on the dedicated
 *       {@code "AminGramPyPlugins"} thread, never the UI thread.
 *       A throwing plugin is caught, logged and (after
 *       {@link PyPluginsPrefs#ERROR_STORM_THRESHOLD} consecutive
 *       failures) auto-disabled — it never crashes the app or
 *       other plugins. Native-hook crashes cannot be caught this
 *       way; M0 loads no DEX and installs no native hooks.</li>
 * </ul>
 */
public final class PyPluginsController {

    private PyPluginsController() {
    }

    private static final List<PyPlugin> cache = new ArrayList<>();
    private static volatile boolean cacheLoaded;
    private static volatile Boolean cachedEngineEnabled;
    private static final Map<String, PyObject> instances = new HashMap<>();
    private static final Map<String, Integer> consecutiveFailures = new HashMap<>();
    private static final Set<String> broken = new HashSet<>();
    /** Settings-reload listeners (M1): the open plugin page
     *  registers a WeakReference so a dead fragment is never
     *  kept alive by the engine. */
    private static final Map<String, java.lang.ref.WeakReference<Runnable>> settingsListeners =
            new HashMap<>();

    // ------------------------------------------------------------------
    // Prefs / cache
    // ------------------------------------------------------------------

    public static boolean isEngineEnabled(Context context) {
        if (cachedEngineEnabled != null) {
            return cachedEngineEnabled;
        }
        if (context == null) {
            return false;
        }
        loadCache(context);
        return cachedEngineEnabled != null && cachedEngineEnabled;
    }

    public static void setEngineEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        cachedEngineEnabled = enabled;
        context.getSharedPreferences(PyPluginsPrefs.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PyPluginsPrefs.KEY_ENGINE, enabled).apply();
        if (!enabled) {
            unloadAll();
        } else {
            final Context appContext = context.getApplicationContext();
            PyPluginsBootstrap.post(new Runnable() {
                @Override
                public void run() {
                    if (PyPluginsBootstrap.ensureStarted(appContext, true)) {
                        loadEnabledPlugins(appContext);
                    }
                }
            });
        }
    }

    public static synchronized List<PyPlugin> plugins(Context context) {
        loadCache(context);
        return new ArrayList<>(cache);
    }

    public static synchronized PyPlugin get(Context context, String pluginId) {
        loadCache(context);
        for (PyPlugin p : cache) {
            if (p.id.equals(pluginId)) {
                return p;
            }
        }
        return null;
    }

    public static boolean hasEnabledPlugins(Context context) {
        loadCache(context);
        for (PyPlugin p : cache) {
            if (p.enabled && !p.errored) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void loadCache(Context context) {
        if (cacheLoaded || context == null) {
            return;
        }
        cacheLoaded = true;
        cache.clear();
        SharedPreferences prefs = context.getSharedPreferences(
                PyPluginsPrefs.PREFS, Context.MODE_PRIVATE);
        if (cachedEngineEnabled == null) {
            // Default OFF (M0–M2): the engine must be switched on.
            cachedEngineEnabled = prefs.getBoolean(PyPluginsPrefs.KEY_ENGINE, false);
        }
        String raw = prefs.getString(PyPluginsPrefs.KEY_ORDER, null);
        if (raw == null) {
            return;
        }
        try {
            JSONArray order = new JSONArray(raw);
            for (int i = 0; i < order.length(); i++) {
                String id = order.optString(i);
                String meta = prefs.getString(PyPluginsPrefs.KEY_META_PREFIX + id, null);
                if (meta == null) {
                    continue;
                }
                PyPlugin plugin = PyPlugin.fromJson(new JSONObject(meta));
                if (plugin != null) {
                    cache.add(plugin);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void persistMeta(Context context, PyPlugin plugin) {
        if (context == null || plugin == null) {
            return;
        }
        context.getSharedPreferences(PyPluginsPrefs.PREFS, Context.MODE_PRIVATE)
                .edit().putString(PyPluginsPrefs.KEY_META_PREFIX + plugin.id,
                        plugin.toJson().toString()).apply();
    }

    private static void persistOrder(Context context) {
        if (context == null) {
            return;
        }
        JSONArray order = new JSONArray();
        synchronized (PyPluginsController.class) {
            for (PyPlugin p : cache) {
                order.put(p.id);
            }
        }
        context.getSharedPreferences(PyPluginsPrefs.PREFS, Context.MODE_PRIVATE)
                .edit().putString(PyPluginsPrefs.KEY_ORDER, order.toString()).apply();
    }

    /** On-disk file holding an installed plugin's source. */
    public static File pluginFile(Context context, String pluginId) {
        if (context == null || pluginId == null) {
            return null;
        }
        File dir = new File(context.getFilesDir(), PyPluginsPrefs.PLUGIN_DIR);
        return new File(dir, pluginId + ".plugin");
    }

    public static File pluginDir(Context context) {
        if (context == null) {
            return null;
        }
        File dir = new File(context.getFilesDir(), PyPluginsPrefs.PLUGIN_DIR);
        dir.mkdirs();
        return dir;
    }

    // ------------------------------------------------------------------
    // Install (sideload only, M0)
    // ------------------------------------------------------------------

    /** Installs from raw bytes. Returns null on success, or a
     *  readable refusal. Never starts Python. */
    public static String installFromBytes(Context context, byte[] bytes, String sourceTag) {
        if (context == null) {
            return "No context.";
        }
        if (bytes == null || bytes.length == 0) {
            return "Empty plugin file.";
        }
        if (bytes.length > PyPluginsPrefs.MAX_PLUGIN_BYTES) {
            return "Plugin file is too large (max 2 MB).";
        }
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
            return "This is a .elyx multi-file archive — M0 accepts "
                    + "single-file .plugin only.";
        }
        String source;
        try {
            source = new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "Plugin file is not valid UTF-8 text.";
        }
        return installFromSource(context, source, sourceTag);
    }

    /** Installs from source text. Returns null on success. */
    public static synchronized String installFromSource(Context context, String source,
                                                        String sourceTag) {
        if (context == null) {
            return "No context.";
        }
        PyPluginMetadata.Parsed parsed = PyPluginMetadata.parse(source);
        if (parsed.error != null) {
            return parsed.error;
        }
        PyPlugin plugin = parsed.plugin;
        if (!plugin.requirements.isEmpty()) {
            return "This plugin needs PyPI packages ("
                    + join(plugin.requirements)
                    + ") — AminGram cannot install PyPI packages yet "
                    + "(pip support is planned for M4).";
        }
        List<PyPluginAnalysis.Finding> findings = PyPluginAnalysis.analyze(source, plugin);
        plugin.analysis.clear();
        plugin.analysis.addAll(PyPluginAnalysis.toLines(findings));
        plugin.fingerprint = sha256Hex(source);
        plugin.installSource = sourceTag == null ? "file" : sourceTag;
        plugin.installedAt = System.currentTimeMillis();

        File dir = pluginDir(context);
        if (dir == null) {
            return "Could not create the plugin directory.";
        }
        File dest = new File(dir, plugin.id + ".plugin");
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(source.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return "Could not save the plugin file: " + PyPluginHost.shortError(t);
        }

        PyPlugin existing = get(context, plugin.id);
        if (existing != null) {
            // Reinstall in place: keep the user's enabled state and
            // settings; clear the error/broken flags (JS parity).
            plugin.enabled = existing.enabled;
            plugin.dexConsent = existing.dexConsent;
            broken.remove(plugin.id);
            consecutiveFailures.remove(plugin.id);
            int at = cache.indexOf(existing);
            cache.set(at, plugin);
            PyPluginLog.append(context, plugin.id, "Reinstalled ("
                    + plugin.version + ") — settings kept.");
        } else {
            plugin.enabled = true;
            cache.add(plugin);
            PyPluginLog.append(context, plugin.id, "Installed ("
                    + plugin.version + ").");
        }
        persistMeta(context, plugin);
        persistOrder(context);
        // If the engine is on and Python is up, load it now.
        if (isEngineEnabled(context) && plugin.enabled) {
            final Context appContext = context.getApplicationContext();
            final String pluginId = plugin.id;
            PyPluginsBootstrap.post(new Runnable() {
                @Override
                public void run() {
                    if (PyPluginsBootstrap.ensureStarted(appContext, true)) {
                        loadPlugin(appContext, pluginId);
                    }
                }
            });
        }
        return null;
    }

    public static String sha256Hex(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Enable / disable / error / delete
    // ------------------------------------------------------------------

    public static synchronized void setEnabled(Context context, PyPlugin plugin, boolean enabled) {
        if (context == null || plugin == null) {
            return;
        }
        plugin.enabled = enabled;
        if (enabled) {
            plugin.errored = false;
            plugin.errorText = "";
            broken.remove(plugin.id);
            consecutiveFailures.remove(plugin.id);
        }
        persistMeta(context, plugin);
        final Context appContext = context.getApplicationContext();
        final String pluginId = plugin.id;
        final boolean enableNow = enabled;
        PyPluginsBootstrap.post(new Runnable() {
            @Override
            public void run() {
                if (enableNow) {
                    if (PyPluginsBootstrap.ensureStarted(appContext, true)) {
                        loadPlugin(appContext, pluginId);
                    }
                } else {
                    unloadPlugin(appContext, pluginId);
                }
            }
        });
    }

    /** Marks a plugin errored (exteraGram {@code setError} semantics:
     *  the plugin is disabled until the user re-enables it). Called
     *  by the {@code PluginsController} facade and by load failures. */
    public static synchronized void setError(Context context, String pluginId, Throwable error) {
        if (context == null || pluginId == null) {
            return;
        }
        PyPlugin plugin = get(context, pluginId);
        if (plugin == null) {
            return;
        }
        plugin.errored = true;
        plugin.enabled = false;
        plugin.errorText = error == null ? "Unknown error"
                : PyPluginHost.shortError(error);
        persistMeta(context, plugin);
        PyPluginLog.append(context, pluginId, "ERROR: " + plugin.errorText);
        unloadPlugin(context, pluginId);
    }

    public static synchronized void delete(Context context, String pluginId) {
        if (context == null || pluginId == null) {
            return;
        }
        unloadPlugin(context, pluginId);
        PyPlugin plugin = get(context, pluginId);
        if (plugin != null) {
            cache.remove(plugin);
            persistOrder(context);
            context.getSharedPreferences(PyPluginsPrefs.PREFS, Context.MODE_PRIVATE)
                    .edit().remove(PyPluginsPrefs.KEY_META_PREFIX + pluginId).apply();
        }
        File file = pluginFile(context, pluginId);
        if (file != null) {
            file.delete();
        }
        PyPluginLog.clear(context, pluginId);
        PyPluginSettings.clear(context, pluginId);
    }

    // ------------------------------------------------------------------
    // Loading (Python side, via amin_loader)
    // ------------------------------------------------------------------

    /** Loads every enabled plugin that is not already loaded. Runs
     *  on the Python thread; no-op when Python is not started. */
    public static void loadEnabledPlugins(Context context) {
        if (context == null || !Python.isStarted()) {
            return;
        }
        List<PyPlugin> all = plugins(context);
        for (PyPlugin plugin : all) {
            if (plugin.enabled && !plugin.errored) {
                loadPlugin(context, plugin.id);
            }
        }
    }

    /** Loads (or reloads) one plugin: imports its module, finds the
     *  {@code BasePlugin} subclass, instantiates it and calls
     *  {@code on_plugin_load}. Failures mark the plugin errored. */
    public static synchronized void loadPlugin(Context context, String pluginId) {
        if (context == null || pluginId == null || !Python.isStarted()) {
            return;
        }
        PyPlugin plugin = get(context, pluginId);
        if (plugin == null || !plugin.enabled || plugin.errored) {
            return;
        }
        if (instances.containsKey(pluginId)) {
            return;
        }
        if (broken.contains(pluginId)) {
            return;
        }
        File file = pluginFile(context, pluginId);
        if (file == null || !file.exists()) {
            setError(context, pluginId,
                    new IllegalStateException("plugin file is missing"));
            return;
        }
        try {
            Python py = Python.getInstance();
            // The plugins dir is on sys.path so plugins can import
            // each other (a library-plugin such as ZwyLib, M3).
            PyObject sys = py.getModule("sys");
            PyObject path = sys.get("path");
            String dirPath = file.getParent();
            Boolean already = false;
            try {
                already = path.callAttr("__contains__", dirPath).toBoolean();
            } catch (Throwable ignored) {
            }
            if (!Boolean.TRUE.equals(already)) {
                path.callAttr("append", dirPath);
            }
            PyObject loader = py.getModule("amin_loader");
            PyObject instance = loader.callAttr("load_plugin",
                    file.getAbsolutePath(), moduleName(pluginId));
            // The authoritative id (metadata __id__) — the Python
            // side cannot derive it from the module name ('-' is
            // not a valid module-name character).
            try {
                instance.put("_plugin_id", pluginId);
            } catch (Throwable ignored) {
            }
            instances.put(pluginId, instance);
            try {
                // call_lifecycle (M1) sets the current-plugin
                // context so SDK helpers called from on_plugin_load
                // log under this plugin, not "engine".
                loader.callAttr("call_lifecycle", instance, "on_plugin_load");
            } catch (Throwable t) {
                instances.remove(pluginId);
                throw t;
            }
            consecutiveFailures.remove(pluginId);
            PyPluginLog.append(context, pluginId, "Loaded.");
        } catch (Throwable t) {
            broken.add(pluginId);
            PyPluginLog.append(context, pluginId,
                    "Load failed: " + PyPluginHost.shortError(t));
            setError(context, pluginId, t);
        }
    }

    /** Unloads one plugin: {@code on_plugin_unload}, then drops the
     *  module from {@code sys.modules} (via amin_loader). */
    public static synchronized void unloadPlugin(Context context, String pluginId) {
        if (pluginId == null) {
            return;
        }
        PyObject instance = instances.remove(pluginId);
        if (instance == null) {
            return;
        }
        try {
            if (Python.isStarted()) {
                try {
                    Python.getInstance().getModule("amin_loader")
                            .callAttr("call_lifecycle", instance, "on_plugin_unload");
                } catch (Throwable ignored) {
                }
                try {
                    Python.getInstance().getModule("amin_loader")
                            .callAttr("unload_plugin", moduleName(pluginId));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        if (context != null) {
            PyPluginLog.append(context, pluginId, "Unloaded.");
        }
    }

    private static void unloadAll() {
        List<String> ids = new ArrayList<>(instances.keySet());
        for (String id : ids) {
            unloadPlugin(ApplicationLoader.applicationContext, id);
        }
    }

    /** Python module name for a plugin id: ids may contain '-',
     *  which is not a valid module name. */
    static String moduleName(String pluginId) {
        return "pyplugin_" + pluginId.replace('-', '_').replace('.', '_');
    }

    // ------------------------------------------------------------------
    // Settings (M1)
    // ------------------------------------------------------------------

    /** Callback for {@link #fetchSettingsRows}: always invoked on
     *  the UI thread. {@code rows} is null when {@code error} is set. */
    public interface SettingsRowsCallback {
        void onResult(List<PyPluginSettingsRow> rows, String error);
    }

    /** Fetches a plugin's create_settings() rows on the Python
     *  thread and returns them (with current stored values filled
     *  in) on the UI thread. A create_settings() failure is
     *  reported as an error, never as fake rows. */
    public static void fetchSettingsRows(final Context context, final String pluginId,
                                         final SettingsRowsCallback callback) {
        if (callback == null) {
            return;
        }
        final Context appContext = context == null ? null : context.getApplicationContext();
        PyPluginsBootstrap.post(new Runnable() {
            @Override
            public void run() {
                List<PyPluginSettingsRow> rows = null;
                String error = null;
                List<String> warnings = new ArrayList<>();
                try {
                    if (!Python.isStarted()
                            && !PyPluginsBootstrap.ensureStarted(appContext, true)) {
                        error = "Python engine is not running.";
                    } else {
                        PyObject instance = instances.get(pluginId);
                        if (instance == null) {
                            error = "Plugin is not loaded. Enable it and try again.";
                        } else {
                            PyObject result = Python.getInstance().getModule("amin_loader")
                                    .callAttr("serialize_settings", instance);
                            String json = result == null ? null : result.toString();
                            PyPluginSettingsRow.Parsed parsed =
                                    PyPluginSettingsRow.fromJson(json);
                            warnings.addAll(parsed.warnings);
                            rows = parsed.rows;
                            fillCurrentValues(pluginId, rows);
                        }
                    }
                } catch (Throwable t) {
                    rows = null;
                    error = "create_settings() failed: " + PyPluginHost.shortError(t);
                    if (appContext != null) {
                        PyPluginLog.append(appContext, pluginId,
                                "Settings failed: " + PyPluginHost.shortError(t));
                    }
                }
                if (!warnings.isEmpty() && appContext != null) {
                    for (String w : warnings) {
                        PyPluginLog.append(appContext, pluginId, "Settings: " + w);
                    }
                }
                final List<PyPluginSettingsRow> finalRows = rows;
                final String finalError = error;
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            callback.onResult(finalRows, finalError);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        });
    }

    /** Fills current stored values into parsed rows (switch state
     *  from the boolean getter, everything else as text). */
    private static void fillCurrentValues(String pluginId, List<PyPluginSettingsRow> rows) {
        if (rows == null) {
            return;
        }
        for (PyPluginSettingsRow row : rows) {
            if (!row.isInteractive()) {
                continue;
            }
            try {
                if ("switch".equals(row.type)) {
                    row.currentBool = PyPluginSettings.getBooleanValue(
                            pluginId, row.key, row.defaultAsBool());
                    row.currentValue = row.currentBool ? "true" : "false";
                } else {
                    row.currentValue = PyPluginSettings.getTextValue(
                            pluginId, row.key, null);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Registers the open plugin page's reload callback (M1).
     *  Held weakly — a dead fragment is never kept alive. */
    public static synchronized void registerSettingsReloadListener(String pluginId, Runnable r) {
        if (pluginId == null) {
            return;
        }
        if (r == null) {
            settingsListeners.remove(pluginId);
        } else {
            settingsListeners.put(pluginId,
                    new java.lang.ref.WeakReference<Runnable>(r));
        }
    }

    public static synchronized void unregisterSettingsReloadListener(String pluginId) {
        if (pluginId != null) {
            settingsListeners.remove(pluginId);
        }
    }

    /** A plugin stored a setting with reload_settings=True (or
     *  imported settings): re-read create_settings() on the open
     *  page, if any. No-op when the page is not open. */
    public static void notifySettingsChanged(String pluginId) {
        if (pluginId == null) {
            return;
        }
        java.lang.ref.WeakReference<Runnable> ref;
        synchronized (PyPluginsController.class) {
            ref = settingsListeners.get(pluginId);
        }
        if (ref == null) {
            return;
        }
        final Runnable listener = ref.get();
        if (listener == null) {
            synchronized (PyPluginsController.class) {
                settingsListeners.remove(pluginId);
            }
            return;
        }
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.run();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** Runs after a user changes a setting row on the plugin page:
     *  the row's registered on_change callable (when one was given
     *  in create_settings()) plus the plugin's optional
     *  on_settings_changed(key). Failures are logged and count
     *  towards the error-storm rule, like any dispatch failure. */
    public static void dispatchSettingChanged(final Context context, final String pluginId,
                                              final String key, final String jsonValue) {
        if (pluginId == null || key == null) {
            return;
        }
        final Context appContext = context == null ? null : context.getApplicationContext();
        PyPluginsBootstrap.post(new Runnable() {
            @Override
            public void run() {
                PyObject instance = instances.get(pluginId);
                if (instance == null || !Python.isStarted()) {
                    return;
                }
                try {
                    Python.getInstance().getModule("amin_loader")
                            .callAttr("invoke_setting_callback", pluginId, key, jsonValue);
                    Python.getInstance().getModule("amin_loader")
                            .callAttr("call_optional", instance, "on_settings_changed", key);
                    consecutiveFailures.remove(pluginId);
                } catch (Throwable t) {
                    recordDispatchFailure(appContext, pluginId, t);
                }
            }
        });
    }

    /** Opens a plugin's settings page (M1 wiring for the
     *  PluginsController facade's openPluginSettings). No-op when
     *  no fragment is available to present from. */
    public static void openPluginSettings(final Context context, final String pluginId) {
        if (context == null || pluginId == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                try {
                    BaseFragment fragment = LaunchActivity.getLastFragment();
                    if (fragment != null) {
                        fragment.presentFragment(new AminPyPluginActivity(pluginId));
                    } else {
                        PyPluginLog.append(context, pluginId,
                                "openPluginSettings: no fragment available.");
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Dispatch (M0 provisional hooks)
    // ------------------------------------------------------------------

    /** Incoming message fan-out (from {@code AminHooks.onNewMessages}).
     *  Only plugins defining {@code on_message(data)} are called. */
    public static void dispatchMessage(final int account, final TLRPC.Message message) {
        if (message == null || !Python.isStarted()) {
            return;
        }
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || !isEngineEnabled(context)) {
            return;
        }
        PyPluginsBootstrap.post(new Runnable() {
            @Override
            public void run() {
                Map<String, Object> data = new HashMap<>();
                data.put("account", account);
                data.put("dialog_id", message.dialog_id);
                data.put("message_id", message.id);
                data.put("text", message.message == null ? "" : message.message);
                data.put("out", message.out);
                for (PyPlugin plugin : plugins(context)) {
                    if (!plugin.enabled || plugin.errored) {
                        continue;
                    }
                    PyObject instance = instances.get(plugin.id);
                    if (instance == null) {
                        continue;
                    }
                    try {
                        Python.getInstance().getModule("amin_loader")
                                .callAttr("call_optional", instance, "on_message", data);
                        consecutiveFailures.remove(plugin.id);
                    } catch (Throwable t) {
                        recordDispatchFailure(context, plugin.id, t);
                    }
                }
            }
        });
    }

    /** Outgoing "/" command fan-out (from {@code AminHooks.onOutgoingMessage}).
     *  M0 limitation, stated plainly: this is fire-and-forget and
     *  NEVER suppresses the literal message. Suppression would need
     *  to run plugin code synchronously on the send path (the JS
     *  engine avoids that by deciding from a declared command list
     *  in prefs); Python plugins declare no commands in metadata,
     *  so the M2 hook registry must add a per-command declaration
     *  before suppression can exist. A plugin that defines
     *  {@code on_command(text)} is called asynchronously and may
     *  react (e.g. send a reply); its return value is ignored. */
    public static void dispatchOutgoingCommand(final String text) {
        if (text == null || !text.startsWith("/") || !Python.isStarted()) {
            return;
        }
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || !isEngineEnabled(context)) {
            return;
        }
        PyPluginsBootstrap.post(new Runnable() {
            @Override
            public void run() {
                for (PyPlugin plugin : plugins(context)) {
                    if (!plugin.enabled || plugin.errored) {
                        continue;
                    }
                    PyObject instance = instances.get(plugin.id);
                    if (instance == null) {
                        continue;
                    }
                    try {
                        Python.getInstance().getModule("amin_loader")
                                .callAttr("call_optional", instance, "on_command", text);
                        consecutiveFailures.remove(plugin.id);
                    } catch (Throwable t) {
                        recordDispatchFailure(context, plugin.id, t);
                    }
                }
            }
        });
    }

    /** Records a dispatch failure; after the threshold the plugin
     *  is auto-disabled and the failure is logged (error-storm rule). */
    public static synchronized void recordDispatchFailure(Context context, String pluginId,
                                                          Throwable t) {
        if (pluginId == null) {
            return;
        }
        int count = consecutiveFailures.containsKey(pluginId)
                ? consecutiveFailures.get(pluginId) : 0;
        count += 1;
        consecutiveFailures.put(pluginId, count);
        if (context != null) {
            PyPluginLog.append(context, pluginId,
                    "Dispatch failed (" + count + "): " + PyPluginHost.shortError(t));
        }
        if (count >= PyPluginsPrefs.ERROR_STORM_THRESHOLD) {
            if (context != null) {
                PyPlugin plugin = get(context, pluginId);
                if (plugin != null) {
                    plugin.errored = true;
                    plugin.enabled = false;
                    plugin.errorText = "Disabled after " + count
                            + " consecutive failures: " + PyPluginHost.shortError(t);
                    persistMeta(context, plugin);
                    PyPluginLog.append(context, pluginId,
                            "Auto-disabled after " + count + " failures.");
                    unloadPlugin(context, pluginId);
                }
            }
        }
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item);
        }
        return sb.toString();
    }
}
