package org.telegram.messenger.amin.plugins.js;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.amin.core.VaultStore;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JS plugin system (v1.3–v2.3): installs, stores and runs single-file
 * JavaScript plugins as specified in docs/PLUGIN-MASTER-PROMPT.md.
 *
 * Phase A & C updates:
 * - A-01: Sets and resets AminBridge.setCurrentDispatchChatId and
 *   setCurrentDispatchReplyToMsg around every chat-scoped hook.
 * - A-12: Caches engineEnabled and hasAnyEnabledPlugin in memory so
 *   hot-path update-thread checks are zero-allocation volatile reads.
 * - A-14 / A-18: Caps pending dispatchMessage backlog and shares a
 *   single Rhino Context across relayEmit targets.
 * - A-16 / A-22: Reuses ThreadLocal SimpleDateFormat, caches todayString()
 *   per calendar day, and debounces error-counter persistMeta writes.
 * - A-17: Enforces 512 KB source cap in installInternal and readCodeFile.
 * - A-27: Reuses a shared fallback scope in lookupScope.
 * - A-33: Removed dead suspendReason/suspendReasons and unused params.
 * - A-35 / Phase A.4 / Phase C.1:
 *   - Outgoing command suppression (dispatchOutgoingCommand returns boolean).
 *   - Raw message object exposure (msg.raw and lookupMessageRaw).
 *   - Rich sticker/document fields on msg.media + ctx.replyTo.msg.
 *   - Relay payload caps and runtime @depends verification.
 */
public final class JsPluginManager {

    public static final String PREFS = "amingram_jsplugins";
    public static final int PLATFORM_API_VERSION = 2;
    private static final String KEY_ORDER = "order";
    private static final String KEY_META_PREFIX = "meta_";
    private static final String KEY_LOG_PREFIX = "log_";
    private static final String KEY_ENGINE = "engine_enabled";
    private static final int LOG_CAP = 500;
    private static final int MAX_PENDING_MESSAGE_DISPATCHES = 64;
    private static final int MAX_RELAY_JSON_CHARS = 256 * 1024;

    private static final List<JsPlugin> cache = new ArrayList<>();
    private static volatile boolean cacheLoaded;
    private static volatile Boolean cachedEngineEnabled;
    private static volatile boolean cachedHasAnyEnabled;

    private static final Map<String, Runtime> runtimes =
            Collections.synchronizedMap(new HashMap<>());
    private static final Set<String> broken = Collections.synchronizedSet(new HashSet<>());

    private static final AminContextFactory CONTEXT_FACTORY = new AminContextFactory();
    private static final long HOOK_BUDGET_MS = 5000L;

    private static final Set<String> suspended = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, ErrorStormTracker> stormTrackers = new HashMap<>();
    private static final Map<String, Long> lastErrMetaPersistMs = new ConcurrentHashMap<>();

    private static final Set<String> migrating = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, String> pendingSettingKeys =
            Collections.synchronizedMap(new HashMap<>());

    private static volatile int dispatchAccount = -1;
    private static final AtomicInteger pendingMessageDispatches = new AtomicInteger(0);
    private static volatile ScriptableObject sharedFallbackScope;

    private static final ThreadLocal<SimpleDateFormat> LOG_DATE_FORMAT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("MMM d HH:mm:ss", Locale.US);
                }
            };

    private static volatile long cachedTodayEpochDay = -1;
    private static volatile String cachedTodayStr = "";

    private static final ClassShutter CLASS_SHUTTER = new ClassShutter() {
        @Override
        public boolean visibleToScripts(String fullClassName) {
            return fullClassName.equals(AminBridge.class.getName())
                    || fullClassName.equals(AminBridge.Storage.class.getName())
                    || fullClassName.equals(AminBridge.WithGoogle.class.getName())
                    || fullClassName.equals(AminBridge.GoogleAccess.class.getName())
                    || fullClassName.equals(AminBridge.WithFile.class.getName())
                    || fullClassName.equals(AminBridge.WithGoogleFile.class.getName())
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$WithAct")
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$WithGoogleAct")
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$WithFileAct")
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$WithGoogleFileAct")
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$PlatformInfo")
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$DbAccess")
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$FileAccess")
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$UiAccess")
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$RelayAccess")
                    || fullClassName.equals("java.lang.Object")
                    || fullClassName.equals("java.lang.String")
                    || fullClassName.equals("java.lang.Boolean")
                    || fullClassName.equals("java.lang.Number")
                    || fullClassName.equals("java.lang.Double")
                    || fullClassName.equals("java.lang.Integer")
                    || fullClassName.equals("java.lang.Long");
        }
    };

    private static final class PluginQueue {
        static final Handler INSTANCE;
        static {
            HandlerThread thread = new HandlerThread("AminGramPlugins");
            thread.start();
            INSTANCE = new Handler(thread.getLooper());
        }
    }

    private static void postToPluginThread(Runnable runnable) {
        PluginQueue.INSTANCE.post(runnable);
    }

    private static final class Runtime {
        JsPlugin plugin;
        ScriptableObject scope;
        boolean onLoadCalled;
    }

    private JsPluginManager() {
    }

    public static int dispatchAccount() {
        int account = dispatchAccount;
        return account >= 0 ? account : UserConfig.selectedAccount;
    }

    public static String storagePrefsName(String pluginId) {
        return "amingram_jsstore_" + pluginId;
    }

    // ------------------------------------------------------------------
    // Metadata store (A-12 cached flags)
    // ------------------------------------------------------------------

    private static void refreshHasAnyEnabledLocked() {
        boolean any = false;
        for (JsPlugin p : cache) {
            if (p.enabled) {
                any = true;
                break;
            }
        }
        cachedHasAnyEnabled = any;
    }

    public static synchronized List<JsPlugin> plugins(Context context) {
        loadCache(context);
        return new ArrayList<>(cache);
    }

    public static synchronized JsPlugin get(Context context, String pluginId) {
        loadCache(context);
        for (JsPlugin plugin : cache) {
            if (plugin.id.equals(pluginId)) {
                return plugin;
            }
        }
        return null;
    }

    public static boolean isEngineEnabled(Context context) {
        if (context == null) {
            return false;
        }
        Boolean cached = cachedEngineEnabled;
        if (cached != null) {
            return cached;
        }
        boolean enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENGINE, true);
        cachedEngineEnabled = enabled;
        return enabled;
    }

    public static void setEngineEnabled(Context context, boolean enabled) {
        cachedEngineEnabled = enabled;
        if (context != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENGINE, enabled).apply();
        }
    }

    public static boolean hasEnabledPlugins(Context context) {
        if (!isEngineEnabled(context)) {
            return false;
        }
        if (cacheLoaded) {
            return cachedHasAnyEnabled;
        }
        synchronized (JsPluginManager.class) {
            loadCache(context);
            return cachedHasAnyEnabled;
        }
    }

    // ------------------------------------------------------------------
    // Attach menu (patch 0014) + menu configuration (v2.0)
    // ------------------------------------------------------------------

    public static final class MenuEntry {
        public JsPlugin plugin;
        public boolean included = true;
        public String icon;
        public String label;

        public MenuEntry() {
        }

        public MenuEntry(JsPlugin plugin, boolean included, String icon, String label) {
            this.plugin = plugin;
            this.included = included;
            this.icon = icon;
            this.label = label;
        }

        public String resolvedLabel() {
            if (label != null && !label.isEmpty()) {
                return label;
            }
            return plugin != null ? plugin.defaultMenuLabel() : "";
        }

        public String resolvedIcon() {
            if (icon != null && !icon.isEmpty()) {
                return icon;
            }
            return plugin != null ? plugin.defaultMenuIcon() : "";
        }

        public String[] toArray() {
            return new String[]{
                    plugin != null ? plugin.id : "", resolvedLabel(), resolvedIcon()};
        }
    }

    private static boolean isMenuCapable(JsPlugin plugin) {
        return plugin.hasMenu && plugin.hasCapability("menu");
    }

    public static List<String[]> menuPlugins(Context context) {
        List<String[]> out = new ArrayList<>();
        for (MenuEntry entry : resolvedMenuEntries(context)) {
            out.add(entry.toArray());
        }
        return out;
    }

    public static List<MenuEntry> menuPluginEntries(Context context) {
        return resolvedMenuEntries(context);
    }

    private static List<MenuEntry> resolvedMenuEntries(Context context) {
        List<MenuEntry> out = new ArrayList<>();
        if (context == null || !isEngineEnabled(context)) {
            return out;
        }
        List<AminMenuConfig.MenuEntry> config = AminMenuConfig.load(context);
        if (config.isEmpty()) {
            for (JsPlugin plugin : plugins(context)) {
                if (plugin.enabled && isMenuCapable(plugin)) {
                    out.add(new MenuEntry(plugin, true,
                            plugin.defaultMenuIcon(), plugin.defaultMenuLabel()));
                }
            }
            return out;
        }
        Set<String> mentioned = new HashSet<>();
        for (AminMenuConfig.MenuEntry stored : config) {
            mentioned.add(stored.pluginId);
            if (!stored.included) {
                continue;
            }
            JsPlugin plugin = get(context, stored.pluginId);
            if (plugin == null || !plugin.enabled || !isMenuCapable(plugin)) {
                continue;
            }
            MenuEntry entry = new MenuEntry(plugin, true, stored.icon, stored.label);
            out.add(new MenuEntry(plugin, true,
                    entry.resolvedIcon(), entry.resolvedLabel()));
        }
        for (JsPlugin plugin : plugins(context)) {
            if (!mentioned.contains(plugin.id)
                    && plugin.enabled && isMenuCapable(plugin)) {
                out.add(new MenuEntry(plugin, true,
                        plugin.defaultMenuIcon(), plugin.defaultMenuLabel()));
            }
        }
        return out;
    }

    public static List<MenuEntry> menuConfigEntries(Context context) {
        List<MenuEntry> out = new ArrayList<>();
        if (context == null) {
            return out;
        }
        List<AminMenuConfig.MenuEntry> config = AminMenuConfig.load(context);
        Set<String> mentioned = new HashSet<>();
        for (AminMenuConfig.MenuEntry stored : config) {
            JsPlugin plugin = get(context, stored.pluginId);
            if (plugin == null || !isMenuCapable(plugin)) {
                continue;
            }
            mentioned.add(stored.pluginId);
            out.add(new MenuEntry(plugin, stored.included, stored.icon, stored.label));
        }
        for (JsPlugin plugin : plugins(context)) {
            if (!mentioned.contains(plugin.id) && isMenuCapable(plugin)) {
                out.add(new MenuEntry(plugin, true, "", ""));
            }
        }
        return out;
    }

    private static void saveMenuEntries(Context context, List<MenuEntry> entries) {
        List<AminMenuConfig.MenuEntry> stored = new ArrayList<>();
        for (MenuEntry entry : entries) {
            if (entry.plugin == null) {
                continue;
            }
            stored.add(new AminMenuConfig.MenuEntry(entry.plugin.id,
                    entry.label == null ? "" : entry.label,
                    entry.icon == null ? "" : entry.icon,
                    entry.included));
        }
        AminMenuConfig.save(context, stored);
    }

    public static void setMenuIncluded(Context context, String pluginId, boolean included) {
        List<MenuEntry> entries = menuConfigEntries(context);
        for (MenuEntry entry : entries) {
            if (entry.plugin.id.equals(pluginId)) {
                entry.included = included;
                saveMenuEntries(context, entries);
                return;
            }
        }
    }

    public static void setMenuIcon(Context context, String pluginId, String iconSpecOrNull) {
        List<MenuEntry> entries = menuConfigEntries(context);
        for (MenuEntry entry : entries) {
            if (entry.plugin.id.equals(pluginId)) {
                entry.icon = iconSpecOrNull == null ? "" : iconSpecOrNull;
                saveMenuEntries(context, entries);
                return;
            }
        }
    }

    public static void setMenuLabel(Context context, String pluginId, String labelOrNull) {
        List<MenuEntry> entries = menuConfigEntries(context);
        for (MenuEntry entry : entries) {
            if (entry.plugin.id.equals(pluginId)) {
                entry.label = labelOrNull == null ? "" : labelOrNull;
                saveMenuEntries(context, entries);
                return;
            }
        }
    }

    public static boolean moveMenuPlugin(Context context, String pluginId, int delta) {
        List<MenuEntry> entries = menuConfigEntries(context);
        int from = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).plugin.id.equals(pluginId)) {
                from = i;
                break;
            }
        }
        if (from < 0 || delta == 0) {
            return false;
        }
        int to = Math.max(0, Math.min(entries.size() - 1, from + delta));
        if (to == from) {
            return false;
        }
        MenuEntry entry = entries.remove(from);
        entries.add(to, entry);
        saveMenuEntries(context, entries);
        return true;
    }

    public static void resetMenuConfig(Context context) {
        AminMenuConfig.clear(context);
    }

    private static void loadCache(Context context) {
        if (cacheLoaded) {
            return;
        }
        cacheLoaded = true;
        cache.clear();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (cachedEngineEnabled == null) {
            cachedEngineEnabled = prefs.getBoolean(KEY_ENGINE, true);
        }
        String raw = prefs.getString(KEY_ORDER, null);
        if (raw != null) {
            try {
                JSONArray order = new JSONArray(raw);
                for (int i = 0; i < order.length(); i++) {
                    String id = order.optString(i);
                    String meta = prefs.getString(KEY_META_PREFIX + id, null);
                    if (meta == null) {
                        continue;
                    }
                    JsPlugin plugin = JsPlugin.fromJson(new JSONObject(meta));
                    if (plugin != null) {
                        if (plugin.hasCapability("commands") && plugin.commands.isEmpty()) {
                            String src = readCode(context, plugin.id);
                            plugin.commands.addAll(
                                    JsManifest.extractCommandsFromSource(src, plugin.description));
                        }
                        cache.add(plugin);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        refreshHasAnyEnabledLocked();
        try {
            BundledPlugins.ensureInstalled(context);
        } catch (Throwable ignored) {
        }
        refreshHasAnyEnabledLocked();
    }

    private static void persistMeta(Context context, JsPlugin plugin) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray order = new JSONArray();
        synchronized (JsPluginManager.class) {
            loadCache(context);
            for (JsPlugin p : cache) {
                order.put(p.id);
            }
            refreshHasAnyEnabledLocked();
        }
        prefs.edit()
                .putString(KEY_ORDER, order.toString())
                .putString(KEY_META_PREFIX + plugin.id, plugin.toJson().toString())
                .apply();
    }

    /** Lightweight single-plugin meta update used by error counters (A-16). */
    private static void persistMetaOnly(Context context, JsPlugin plugin) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_META_PREFIX + plugin.id, plugin.toJson().toString())
                .apply();
    }

    private static void persistOrder(Context context) {
        JSONArray order = new JSONArray();
        for (JsPlugin p : cache) {
            order.put(p.id);
        }
        refreshHasAnyEnabledLocked();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ORDER, order.toString()).apply();
    }

    // ------------------------------------------------------------------
    // Ordering
    // ------------------------------------------------------------------

    public static synchronized boolean movePlugin(Context context, String pluginId, int delta) {
        if (context == null || pluginId == null || delta == 0) {
            return false;
        }
        loadCache(context);
        int from = -1;
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(pluginId)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return false;
        }
        int to = Math.max(0, Math.min(cache.size() - 1, from + delta));
        if (to == from) {
            return false;
        }
        JsPlugin plugin = cache.remove(from);
        cache.add(to, plugin);
        persistOrder(context);
        return true;
    }

    public static synchronized boolean movePluginTo(Context context, String pluginId, int index) {
        if (context == null || pluginId == null) {
            return false;
        }
        loadCache(context);
        int from = -1;
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(pluginId)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return false;
        }
        int to = Math.max(0, Math.min(cache.size() - 1, index));
        if (to == from) {
            return false;
        }
        JsPlugin plugin = cache.remove(from);
        cache.add(to, plugin);
        persistOrder(context);
        return true;
    }

    public static synchronized void reorder(Context context, List<String> orderedIds) {
        if (context == null || orderedIds == null) {
            return;
        }
        loadCache(context);
        Map<String, JsPlugin> byId = new HashMap<>();
        for (JsPlugin plugin : cache) {
            byId.put(plugin.id, plugin);
        }
        List<JsPlugin> next = new ArrayList<>(cache.size());
        Set<String> placed = new HashSet<>();
        for (String id : orderedIds) {
            JsPlugin plugin = byId.get(id);
            if (plugin != null && placed.add(id)) {
                next.add(plugin);
            }
        }
        for (JsPlugin plugin : cache) {
            if (placed.add(plugin.id)) {
                next.add(plugin);
            }
        }
        cache.clear();
        cache.addAll(next);
        persistOrder(context);
    }

    // ------------------------------------------------------------------
    // Install / enable / delete
    // ------------------------------------------------------------------

    public static String install(Context context, String source) {
        return installWithSource(context, source, "paste");
    }

    public static String installWithSource(Context context, String source, String sourceTag) {
        try {
            return installInternal(context, source,
                    sourceTag == null || sourceTag.isEmpty() ? "paste" : sourceTag);
        } catch (Throwable t) {
            return "Plugin install failed: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
        }
    }

    public static void installAsync(final Context context, final String source,
                                    final String sourceTag,
                                    final java.util.function.Consumer<String> done) {
        final Context appContext = context != null
                ? context.getApplicationContext() : null;
        postToPluginThread(() -> {
            String error;
            try {
                error = installWithSource(appContext != null ? appContext : context,
                        source, sourceTag);
            } catch (Throwable t) {
                error = "Plugin install failed: " + t.getClass().getSimpleName();
            }
            if (done != null) {
                final String result = error;
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    try {
                        done.accept(result);
                    } catch (Throwable ignored) {
                    }
                });
            }
        });
    }

    public static boolean isSemver(String version) {
        if (version == null) {
            return false;
        }
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    public static int compareVersions(String a, String b) {
        long[] pa = versionParts(a);
        long[] pb = versionParts(b);
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) {
                return pa[i] < pb[i] ? -1 : 1;
            }
        }
        return 0;
    }

    private static long[] versionParts(String version) {
        long[] out = new long[3];
        if (version == null) {
            return out;
        }
        String[] parts = version.trim().split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                out[i] = Long.parseLong(parts[i].trim());
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }

    public static String sha256Hex(String source) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static String fingerprintOf(String source) {
        String hex = sha256Hex(source);
        return hex.length() > 12 ? hex.substring(0, 12) : hex;
    }

    private static String installInternal(Context context, String source, String sourceTag) {
        JsManifest manifest;
        try {
            manifest = JsManifest.parse(source);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        for (JsManifest.Depends dep : manifest.depends) {
            if (dep.id.equals(manifest.id)) {
                continue;
            }
            JsPlugin installed = get(context, dep.id);
            if (installed == null) {
                return "Missing dependency: this plugin needs '" + dep.id
                        + "' — install it first.";
            }
            if (dep.minVersion != null
                    && compareVersions(installed.version, dep.minVersion) < 0) {
                return "Dependency too old: '" + dep.id + "' v" + installed.version
                        + " is installed, but this plugin needs >= " + dep.minVersion + ".";
            }
        }
        org.mozilla.javascript.Context cx = enterContext();
        try {
            try {
                cx.compileString(source, manifest.id + ".js", 1, null);
            } catch (Throwable t) {
                noteHookError(manifest.id, "compile", t);
                return "JavaScript error: " + t.getMessage();
            }
            ScriptableObject probe = newScope(cx, manifest.id, manifest.capabilities);
            try {
                AminContextFactory.beginHook(HOOK_BUDGET_MS);
                try {
                    cx.evaluateString(probe, source, manifest.id + ".js", 1, null);
                } finally {
                    AminContextFactory.endHook();
                }
            } catch (Throwable t) {
                noteHookError(manifest.id, "probe", t);
                return "Plugin failed to load: " + t.getMessage();
            }
            JsPlugin existing = get(context, manifest.id);
            JsPlugin plugin = new JsPlugin();
            plugin.id = manifest.id;
            plugin.name = manifest.name;
            plugin.version = manifest.version;
            plugin.author = manifest.author;
            plugin.description = manifest.description;
            plugin.icon = manifest.icon;
            plugin.menuLabel = manifest.menuLabel;
            plugin.capabilities.addAll(manifest.capabilities);
            plugin.enabled = existing == null || existing.enabled;
            plugin.installedAt = existing != null ? existing.installedAt
                    : System.currentTimeMillis();
            plugin.hasMessage = ScriptableObject.getProperty(probe, "onMessage")
                    instanceof Function;
            plugin.hasCommand = ScriptableObject.getProperty(probe, "onCommand")
                    instanceof Function;
            plugin.hasMenu = ScriptableObject.getProperty(probe, "onMenuAction")
                    instanceof Function;
            plugin.hasEnable = ScriptableObject.getProperty(probe, "onEnable")
                    instanceof Function;
            plugin.hasDisable = ScriptableObject.getProperty(probe, "onDisable")
                    instanceof Function;
            plugin.hasSettingsChanged = ScriptableObject.getProperty(probe, "onSettingsChanged")
                    instanceof Function;
            plugin.hasMigrate = ScriptableObject.getProperty(probe, "onMigrate")
                    instanceof Function;
            plugin.hasContext = ScriptableObject.getProperty(probe, "onContextAction")
                    instanceof Function;
            plugin.hasEvent = ScriptableObject.getProperty(probe, "onEvent")
                    instanceof Function;
            plugin.hasPluginAction = ScriptableObject.getProperty(probe, "onPluginAction")
                    instanceof Function;
            plugin.contextMenu = manifest.contextMenu;
            plugin.exports.addAll(manifest.exports);
            plugin.topics.addAll(manifest.topics);
            plugin.commands.addAll(manifest.commands);
            for (JsManifest.Depends dep : manifest.depends) {
                plugin.depends.add(dep.minVersion != null
                        ? dep.id + "@>=" + dep.minVersion : dep.id);
            }
            readPluginPage(probe, plugin);
            plugin.dataVersion = manifest.dataVersion;
            plugin.minPlatform = manifest.minPlatform;
            plugin.installSource = sourceTag;
            plugin.fingerprint = fingerprintOf(source);
            if (!isSemver(manifest.version)) {
                appendLog(plugin.id, "[warn] @version '" + manifest.version
                        + "' is not semver (X.Y or X.Y.Z) — update checks may misbehave");
            }
            plugin.dataVersionStored = (existing == null || existing.dataVersionStored <= 0)
                    ? plugin.dataVersion : existing.dataVersionStored;
            plugin.errDay = existing != null && existing.errDay != null ? existing.errDay : "";
            plugin.errCount = existing != null ? existing.errCount : 0;
            boolean isReinstall = existing != null;
            List<String> settingsWarnings = new ArrayList<>();
            List<String> settingsErrors = readSettings(probe, plugin, !isReinstall, settingsWarnings);
            if (!settingsWarnings.isEmpty()) {
                appendLog(plugin.id, "[warn] settings: " + joinStrings(settingsWarnings, "; "));
            }
            if (!settingsErrors.isEmpty()) {
                appendLog(plugin.id, "[error] settings: " + joinStrings(settingsErrors, "; "));
                return "Invalid SETTINGS: " + joinStrings(settingsErrors, "; ");
            }
            if (plugin.hasCapability("settings") && plugin.settings.isEmpty()) {
                appendLog(plugin.id, "[info] declares 'settings' but defines no SETTINGS array");
            }
            File file = codeFile(context, plugin.id);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (existing != null && file.exists()) {
                try {
                    FileInputStream pin = new FileInputStream(file);
                    FileOutputStream pout = new FileOutputStream(
                            prevCodeFile(context, plugin.id), false);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = pin.read(buf)) > 0) {
                        pout.write(buf, 0, n);
                    }
                    pout.close();
                    pin.close();
                } catch (Exception ignored) {
                }
            }
            try {
                FileOutputStream out = new FileOutputStream(file, false);
                out.write(source.getBytes("UTF-8"));
                out.close();
            } catch (Exception e) {
                return "Could not save the plugin file: " + e.getMessage();
            }
            synchronized (JsPluginManager.class) {
                loadCache(context);
                for (int i = cache.size() - 1; i >= 0; i--) {
                    if (cache.get(i).id.equals(plugin.id)) {
                        cache.remove(i);
                    }
                }
                cache.add(plugin);
                refreshHasAnyEnabledLocked();
            }
            persistMeta(context, plugin);
            BundledPlugins.onInstalled(context, plugin.id);
            appendLog(plugin.id, "[info] installed v" + plugin.version + " by " + plugin.author);
            if (plugin.minPlatform > PLATFORM_API_VERSION) {
                appendLog(plugin.id, "[warn] " + plugin.name + " wants platform v"
                        + plugin.minPlatform + "; this build is v" + PLATFORM_API_VERSION
                        + " — installing anyway");
            }
            broken.remove(plugin.id);
            suspended.remove(plugin.id);
            synchronized (stormTrackers) {
                ErrorStormTracker tracker = stormTrackers.get(plugin.id);
                if (tracker != null) {
                    tracker.reset();
                }
            }
            dropRuntime(plugin.id);
            if (plugin.enabled) {
                warmUp(plugin.id, true);
            }
            return null;
        } finally {
            org.mozilla.javascript.Context.exit();
        }
    }

    public static void setEnabled(Context context, JsPlugin plugin, boolean enabled) {
        plugin.enabled = enabled;
        persistMeta(context, plugin);
        if (enabled) {
            broken.remove(plugin.id);
            warmUp(plugin.id, true);
        } else {
            notifyDependentsOfProviderChange(context, plugin.id, "disabled");
            disableRuntime(plugin.id);
        }
    }

    private static void notifyDependentsOfProviderChange(Context context, String providerId, String action) {
        if (context == null || providerId == null) {
            return;
        }
        for (JsPlugin p : plugins(context)) {
            if (p.id.equals(providerId) || !p.enabled || p.depends == null) {
                continue;
            }
            for (String depSpec : p.depends) {
                int at = depSpec.indexOf("@>=");
                String depId = at > 0 ? depSpec.substring(0, at).trim() : depSpec.trim();
                if (providerId.equals(depId)) {
                    appendLog(p.id, "[warn] dependency '" + providerId + "' was " + action);
                }
            }
        }
    }

    private static void disableRuntime(final String pluginId) {
        postToPluginThread(() -> {
            Runtime runtime = runtimes.get(pluginId);
            if (runtime != null && runtime.plugin != null && runtime.plugin.hasDisable) {
                org.mozilla.javascript.Context cx = null;
                try {
                    cx = enterContext();
                    callHook(cx, runtime, "onDisable", new Object[0]);
                } catch (Throwable ignored) {
                } finally {
                    if (cx != null) {
                        org.mozilla.javascript.Context.exit();
                    }
                }
            }
            runtimes.remove(pluginId);
        });
    }

    public static void delete(Context context, JsPlugin plugin) {
        if (plugin != null) {
            delete(context, plugin.id);
        }
    }

    public static void delete(Context context, String pluginId) {
        if (context == null || pluginId == null || pluginId.isEmpty()) {
            return;
        }
        notifyDependentsOfProviderChange(context, pluginId, "deleted");
        synchronized (JsPluginManager.class) {
            loadCache(context);
            for (int i = cache.size() - 1; i >= 0; i--) {
                if (cache.get(i).id.equals(pluginId)) {
                    cache.remove(i);
                }
            }
            persistOrder(context);
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .remove(KEY_META_PREFIX + pluginId)
                .remove(KEY_LOG_PREFIX + pluginId)
                .apply();
        VaultStore.clear(context, PREFS, KEY_LOG_PREFIX + pluginId);
        File file = codeFile(context, pluginId);
        if (file.exists()) {
            file.delete();
        }
        File prevFile = prevCodeFile(context, pluginId);
        if (prevFile.exists()) {
            prevFile.delete();
        }
        PluginDb.deleteAllForPlugin(pluginId);
        PluginFiles.deleteAllForPlugin(context, pluginId);
        deletePluginSettingsDir(context, pluginId);
        deleteStoragePrefs(context, pluginId);
        AminMenuConfig.remove(context, pluginId);
        BundledPlugins.markUserDeleted(context, pluginId);
        dropRuntime(pluginId);
        broken.remove(pluginId);
        suspended.remove(pluginId);
        migrating.remove(pluginId);
        pendingSettingKeys.remove(pluginId);
        lastErrMetaPersistMs.remove(pluginId);
        synchronized (stormTrackers) {
            stormTrackers.remove(pluginId);
        }
    }

    public static File pluginSettingsDir(Context context, String pluginId) {
        File dir = new File(new File(context.getFilesDir(), "amingram/plugin-settings"), pluginId);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static void deletePluginSettingsDir(Context context, String pluginId) {
        try {
            File dir = new File(new File(context.getFilesDir(), "amingram/plugin-settings"), pluginId);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        } catch (Throwable ignored) {
        }
    }

    public static void reloadPlugin(Context context, final String pluginId) {
        postToPluginThread(() -> runtimes.remove(pluginId));
        warmUp(pluginId, false);
        appendLog(pluginId, "[info] reloaded from the hub");
    }

    public static String runtimeState(String pluginId) {
        if (suspended.contains(pluginId)) {
            return "suspended";
        }
        if (broken.contains(pluginId)) {
            return "broken";
        }
        return runtimes.containsKey(pluginId) ? "loaded" : "not loaded";
    }

    public static boolean isRuntimeLoaded(String pluginId) {
        return "loaded".equals(runtimeState(pluginId));
    }

    private static void deleteStoragePrefs(Context context, String pluginId) {
        String name = storagePrefsName(pluginId);
        try {
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit().clear().apply();
        } catch (Throwable ignored) {
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                context.deleteSharedPreferences(name);
            }
        } catch (Throwable ignored) {
        }
        try {
            String dataDir = context.getApplicationInfo().dataDir;
            if (dataDir != null) {
                File xml = new File(new File(dataDir, "shared_prefs"), name + ".xml");
                if (xml.exists()) {
                    xml.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static File codeFile(Context context, String pluginId) {
        return new File(new File(context.getFilesDir(), "jsplugins"), pluginId + ".js");
    }

    private static File prevCodeFile(Context context, String pluginId) {
        return new File(new File(context.getFilesDir(), "jsplugins"), pluginId + ".prev.js");
    }

    public static String readCode(Context context, String pluginId) {
        return readCodeFile(codeFile(context, pluginId));
    }

    public static String readPrevCode(Context context, String pluginId) {
        return readCodeFile(prevCodeFile(context, pluginId));
    }

    private static String readCodeFile(File file) {
        try {
            if (file == null || !file.isFile() || file.length() > JsManifest.MAX_SOURCE_CHARS) {
                return null;
            }
            FileInputStream in = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            in.close();
            return new String(data, 0, read, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Logs (A-16)
    // ------------------------------------------------------------------

    public interface LogListener {
        void onPluginLog(String pluginId);
    }

    private static final List<LogListener> logListeners =
            Collections.synchronizedList(new ArrayList<>());

    public static void addLogListener(LogListener listener) {
        if (listener != null && !logListeners.contains(listener)) {
            logListeners.add(listener);
        }
    }

    public static void removeLogListener(LogListener listener) {
        logListeners.remove(listener);
    }

    private static void notifyLogListeners(final String pluginId) {
        if (logListeners.isEmpty()) {
            return;
        }
        final List<LogListener> snapshot;
        synchronized (logListeners) {
            snapshot = new ArrayList<>(logListeners);
        }
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            for (LogListener listener : snapshot) {
                try {
                    listener.onPluginLog(pluginId);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public static void appendLog(String pluginId, String text) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || pluginId == null) {
                return;
            }
            String flat = (text == null ? "" : text).replace('\n', ' ');
            if (flat.length() > 1000) {
                flat = flat.substring(0, 1000);
            }
            String stamp = LOG_DATE_FORMAT.get().format(new Date());
            List<String> lines = VaultStore.getAll(context, PREFS, KEY_LOG_PREFIX + pluginId);
            lines.add(stamp + " \u2014 " + flat);
            while (lines.size() > LOG_CAP) {
                lines.remove(0);
            }
            VaultStore.putAll(context, PREFS, KEY_LOG_PREFIX + pluginId, lines);
            notifyLogListeners(pluginId);
        } catch (Throwable ignored) {
        }
    }

    public static List<String> getLog(Context context, String pluginId) {
        return VaultStore.getAll(context, PREFS, KEY_LOG_PREFIX + pluginId);
    }

    public static void clearLog(Context context, String pluginId) {
        VaultStore.clear(context, PREFS, KEY_LOG_PREFIX + pluginId);
    }

    public static String storageGetRaw(Context context, String pluginId, String key) {
        return context.getSharedPreferences(storagePrefsName(pluginId), Context.MODE_PRIVATE)
                .getString(key, null);
    }

    public static void storageSetRaw(Context context, String pluginId, String key, String value) {
        context.getSharedPreferences(storagePrefsName(pluginId), Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
    }

    // ------------------------------------------------------------------
    // Watchdog (A-16, A-22) + settings-change notification
    // ------------------------------------------------------------------

    private static String todayString() {
        long epochDay = System.currentTimeMillis() / (24L * 60 * 60 * 1000L);
        if (epochDay == cachedTodayEpochDay && !cachedTodayStr.isEmpty()) {
            return cachedTodayStr;
        }
        String computed = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        cachedTodayStr = computed;
        cachedTodayEpochDay = epochDay;
        return computed;
    }

    private static String joinStrings(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static ErrorStormTracker trackerFor(String pluginId) {
        synchronized (stormTrackers) {
            ErrorStormTracker tracker = stormTrackers.get(pluginId);
            if (tracker == null) {
                tracker = new ErrorStormTracker();
                stormTrackers.put(pluginId, tracker);
            }
            return tracker;
        }
    }

    private static boolean dispatchBlocked(String pluginId) {
        return suspended.contains(pluginId) || migrating.contains(pluginId);
    }

    public static void noteHookError(String pluginId, String hook, Throwable t) {
        if (pluginId == null) {
            return;
        }
        if (t instanceof AminContextFactory.HookBudgetError) {
            appendLog(pluginId, "[error] hook budget exceeded (" + hook + ")");
        } else {
            appendLog(pluginId, "[error] " + hook + ": " + t);
        }
        try {
            FileLog.e("AminGram plugin " + pluginId + " " + hook + " failed", t);
        } catch (Throwable ignored) {
        }
        long now = System.currentTimeMillis();
        boolean stormed = trackerFor(pluginId).record(now);
        boolean newlySuspended = false;
        if (stormed && !suspended.contains(pluginId)) {
            suspended.add(pluginId);
            newlySuspended = true;
            appendLog(pluginId, "[error] suspended — error storm ("
                    + ErrorStormTracker.THRESHOLD
                    + "+ errors in 60s); resume from the plugin page");
        }
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                JsPlugin plugin = get(context, pluginId);
                if (plugin != null) {
                    String today = todayString();
                    if (!today.equals(plugin.errDay)) {
                        plugin.errDay = today;
                        plugin.errCount = 0;
                    }
                    plugin.errCount++;
                    Long lastPersist = lastErrMetaPersistMs.get(pluginId);
                    if (newlySuspended || lastPersist == null || now - lastPersist >= 1000L) {
                        lastErrMetaPersistMs.put(pluginId, now);
                        persistMetaOnly(context, plugin);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static int errorsToday(String pluginId) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || pluginId == null) {
                return 0;
            }
            JsPlugin plugin = get(context, pluginId);
            if (plugin == null) {
                return 0;
            }
            return todayString().equals(plugin.errDay) ? plugin.errCount : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static boolean isSuspended(String pluginId) {
        return pluginId != null && suspended.contains(pluginId);
    }

    public static void resumePlugin(Context context, String pluginId) {
        if (pluginId == null) {
            return;
        }
        suspended.remove(pluginId);
        synchronized (stormTrackers) {
            ErrorStormTracker tracker = stormTrackers.get(pluginId);
            if (tracker != null) {
                tracker.reset();
            }
        }
        broken.remove(pluginId);
        if (context != null) {
            JsPlugin plugin = get(context, pluginId);
            if (plugin != null) {
                plugin.errDay = todayString();
                plugin.errCount = 0;
                persistMeta(context, plugin);
                if (plugin.enabled) {
                    warmUp(pluginId, false);
                }
            }
        }
    }

    public static void onSettingWritten(final Context context, final String pluginId,
                                        final String key, final String newValue,
                                        final String oldValue) {
        if (pluginId == null || key == null) {
            return;
        }
        postToPluginThread(() -> {
            Context ctx = context != null ? context : ApplicationLoader.applicationContext;
            Runtime runtime = runtimes.get(pluginId);
            JsPlugin plugin = ctx != null ? get(ctx, pluginId) : null;
            if (runtime != null && plugin != null && plugin.hasSettingsChanged) {
                org.mozilla.javascript.Context cx = null;
                try {
                    cx = enterContext();
                    callHook(cx, runtime, "onSettingsChanged", new Object[]{
                            key, newValue == null ? "" : newValue,
                            oldValue == null ? "" : oldValue});
                } catch (Throwable ignored) {
                } finally {
                    if (cx != null) {
                        org.mozilla.javascript.Context.exit();
                    }
                }
            } else {
                pendingSettingKeys.put(pluginId, key);
                appendLog(pluginId, "[info] setting '" + key
                        + "' changed while the plugin was not running;"
                        + " onSettingsChanged will fire once on next start");
            }
        });
    }

    // ------------------------------------------------------------------
    // Rhino plumbing
    // ------------------------------------------------------------------

    private static org.mozilla.javascript.Context enterContext() {
        org.mozilla.javascript.Context cx = CONTEXT_FACTORY.enterContext();
        cx.setOptimizationLevel(-1);
        cx.setLanguageVersion(org.mozilla.javascript.Context.VERSION_ES6);
        cx.setClassShutter(CLASS_SHUTTER);
        return cx;
    }

    private static ScriptableObject newScope(org.mozilla.javascript.Context cx, String pluginId,
                                             java.util.Collection<String> capabilities) {
        ScriptableObject scope = cx.initStandardObjects();
        String[] banned = {"Packages", "java", "javax", "JavaAdapter", "importClass", "getClass"};
        for (String name : banned) {
            ScriptableObject.deleteProperty(scope, name);
        }
        boolean hasGoogle = capabilities != null && capabilities.contains("google");
        boolean hasFile = capabilities != null && capabilities.contains("file");
        boolean hasAct = capabilities != null && capabilities.contains("act");
        AminBridge bridge;
        if (hasGoogle && hasFile && hasAct) {
            bridge = new AminBridge.WithGoogleFileAct(pluginId);
        } else if (hasGoogle && hasFile) {
            bridge = new AminBridge.WithGoogleFile(pluginId);
        } else if (hasGoogle && hasAct) {
            bridge = new AminBridge.WithGoogleAct(pluginId);
        } else if (hasFile && hasAct) {
            bridge = new AminBridge.WithFileAct(pluginId);
        } else if (hasGoogle) {
            bridge = new AminBridge.WithGoogle(pluginId);
        } else if (hasFile) {
            bridge = new AminBridge.WithFile(pluginId);
        } else if (hasAct) {
            bridge = new AminBridge.WithAct(pluginId);
        } else {
            bridge = new AminBridge(pluginId);
        }
        bridge.attachCapabilities(capabilities);
        scope.put("amin", scope, org.mozilla.javascript.Context.javaToJS(bridge, scope));
        return scope;
    }

    public static void invokePluginCallback(final String pluginId, final Object fnObject,
                                            final Object value) {
        invokePluginCallback(pluginId, fnObject, value, -1);
    }

    public static void invokePluginCallback(final String pluginId, final Object fnObject,
                                            final Object value, final int account) {
        if (!(fnObject instanceof Function)) {
            return;
        }
        final Function fn = (Function) fnObject;
        postToPluginThread(() -> {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || get(context, pluginId) == null) {
                return;
            }
            if (suspended.contains(pluginId)) {
                return;
            }
            dispatchAccount = account >= 0 ? account : UserConfig.selectedAccount;
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                Scriptable scope = fn.getParentScope();
                if (scope == null) {
                    return;
                }
                AminContextFactory.beginHook(HOOK_BUDGET_MS);
                try {
                    fn.call(cx, scope, scope, new Object[]{value});
                } finally {
                    AminContextFactory.endHook();
                }
            } catch (Throwable t) {
                noteHookError(pluginId, "async callback", t);
            } finally {
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    public interface CallbackArgFactory {
        Object build(org.mozilla.javascript.Context cx, Scriptable scope);
    }

    public static void invokePluginCallbackWithArg(final String pluginId, final Object fnObject,
                                                   final CallbackArgFactory factory) {
        invokePluginCallbackWithArg(pluginId, fnObject, factory, -1);
    }

    public static void invokePluginCallbackWithArg(final String pluginId, final Object fnObject,
                                                   final CallbackArgFactory factory,
                                                   final int account) {
        if (!(fnObject instanceof Function)) {
            return;
        }
        final Function fn = (Function) fnObject;
        postToPluginThread(() -> {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || get(context, pluginId) == null) {
                return;
            }
            if (suspended.contains(pluginId)) {
                return;
            }
            dispatchAccount = account >= 0 ? account : UserConfig.selectedAccount;
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                Scriptable scope = fn.getParentScope();
                if (scope == null) {
                    return;
                }
                AminContextFactory.beginHook(HOOK_BUDGET_MS);
                try {
                    Object arg = factory != null ? factory.build(cx, scope) : null;
                    fn.call(cx, scope, scope, new Object[]{arg});
                } finally {
                    AminContextFactory.endHook();
                }
            } catch (Throwable t) {
                noteHookError(pluginId, "async callback", t);
            } finally {
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    private static List<String> readSettings(ScriptableObject scope, JsPlugin plugin,
                                             boolean strict, List<String> warnings) {
        Object raw = ScriptableObject.getProperty(scope, "SETTINGS");
        return SettingsSchema.parseAndValidate(raw, plugin.settings, strict, warnings);
    }

    private static void readPluginPage(ScriptableObject scope, JsPlugin plugin) {
        plugin.pageRows.clear();
        Object raw = ScriptableObject.getProperty(scope, "PLUGIN_PAGE");
        if (!(raw instanceof Scriptable)) {
            return;
        }
        Scriptable array = (Scriptable) raw;
        Object lengthObj = array.get("length", array);
        if (!(lengthObj instanceof Number)) {
            return;
        }
        int length = Math.min(24, ((Number) lengthObj).intValue());
        for (int i = 0; i < length; i++) {
            Object item = array.get(i, array);
            if (!(item instanceof Scriptable)) {
                continue;
            }
            Scriptable row = (Scriptable) item;
            String type = pageProp(row, "type");
            JsPlugin.PageRow out = new JsPlugin.PageRow();
            if ("action".equals(type)) {
                out.type = "action";
                out.id = pageProp(row, "id");
                out.title = pageProp(row, "title");
                out.subtitle = pageProp(row, "subtitle");
                if (out.id.isEmpty() || out.title.isEmpty()) {
                    continue;
                }
            } else if ("info".equals(type)) {
                out.type = "info";
                out.text = pageProp(row, "text");
                if (out.text.isEmpty()) {
                    continue;
                }
            } else {
                continue;
            }
            plugin.pageRows.add(out);
        }
    }

    private static String pageProp(Scriptable obj, String key) {
        Object value = obj.get(key, obj);
        if (value == null || value == Scriptable.NOT_FOUND
                || value instanceof org.mozilla.javascript.Undefined) {
            return "";
        }
        return String.valueOf(value);
    }

    // ------------------------------------------------------------------
    // Runtime lifecycle (plugin thread only)
    // ------------------------------------------------------------------

    private static void dropRuntime(final String pluginId) {
        postToPluginThread(() -> runtimes.remove(pluginId));
    }

    private static void warmUp(final String pluginId, final boolean fireOnEnable) {
        postToPluginThread(() -> {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            JsPlugin plugin = get(context, pluginId);
            if (plugin == null || !plugin.enabled || !isEngineEnabled(context)) {
                return;
            }
            if (suspended.contains(pluginId)) {
                return;
            }
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                Runtime runtime = ensureRuntime(cx, context, plugin);
                if (runtime != null && fireOnEnable && plugin.hasEnable) {
                    callHook(cx, runtime, "onEnable", new Object[0]);
                }
            } catch (Throwable ignored) {
            } finally {
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    private static Runtime ensureRuntime(org.mozilla.javascript.Context cx,
                                         Context context, JsPlugin plugin) {
        synchronized (runtimes) {
            Runtime existing = runtimes.get(plugin.id);
            if (existing != null) {
                return existing;
            }
        }
        if (broken.contains(plugin.id)) {
            return null;
        }
        String source = readCode(context, plugin.id);
        if (source == null) {
            appendLog(plugin.id, "[error] plugin file missing or over 512 KB cap");
            broken.add(plugin.id);
            return null;
        }
        Runtime runtime = new Runtime();
        runtime.plugin = plugin;
        try {
            runtime.scope = newScope(cx, plugin.id, plugin.capabilities);
            AminContextFactory.beginHook(HOOK_BUDGET_MS);
            try {
                cx.evaluateString(runtime.scope, source, plugin.id + ".js", 1, null);
            } finally {
                AminContextFactory.endHook();
            }
        } catch (Throwable t) {
            noteHookError(plugin.id, "load failed", t);
            broken.add(plugin.id);
            return null;
        }
        synchronized (runtimes) {
            Runtime raced = runtimes.get(plugin.id);
            if (raced != null) {
                return raced;
            }
            runtimes.put(plugin.id, runtime);
        }
        if (!runtime.onLoadCalled) {
            runtime.onLoadCalled = true;
            callHook(cx, runtime, "onLoad", new Object[0]);
            runMigrationIfNeeded(cx, context, runtime);
            deliverPendingSettingsChanged(cx, context, runtime);
        }
        return runtime;
    }

    private static void runMigrationIfNeeded(org.mozilla.javascript.Context cx,
                                             Context context, Runtime runtime) {
        JsPlugin plugin = runtime.plugin;
        int stored = plugin.dataVersionStored;
        int declared = plugin.dataVersion;
        if (stored <= 0 || declared <= stored) {
            return;
        }
        migrating.add(plugin.id);
        try {
            Object fn = ScriptableObject.getProperty(runtime.scope, "onMigrate");
            if (!plugin.hasMigrate || !(fn instanceof Function)) {
                plugin.dataVersionStored = declared;
                persistMeta(context, plugin);
                return;
            }
            AminContextFactory.beginHook(HOOK_BUDGET_MS);
            try {
                ((Function) fn).call(cx, runtime.scope, runtime.scope,
                        new Object[]{Double.valueOf(stored), Double.valueOf(declared)});
            } finally {
                AminContextFactory.endHook();
            }
            plugin.dataVersionStored = declared;
            persistMeta(context, plugin);
            appendLog(plugin.id, "[info] migrated data v" + stored + " -> v" + declared);
        } catch (Throwable t) {
            noteHookError(plugin.id, "onMigrate", t);
        } finally {
            migrating.remove(plugin.id);
        }
    }

    private static void deliverPendingSettingsChanged(org.mozilla.javascript.Context cx,
                                                      Context context, Runtime runtime) {
        String key = pendingSettingKeys.remove(runtime.plugin.id);
        if (key == null || !runtime.plugin.hasSettingsChanged) {
            return;
        }
        String current = storageGetRaw(context, runtime.plugin.id, key);
        if (current == null) {
            current = "";
        }
        callHook(cx, runtime, "onSettingsChanged", new Object[]{key, current, current});
    }

    private static Object callHook(org.mozilla.javascript.Context cx, Runtime runtime,
                                   String name, Object[] args) {
        try {
            Object fn = ScriptableObject.getProperty(runtime.scope, name);
            if (fn instanceof Function) {
                AminContextFactory.beginHook(HOOK_BUDGET_MS);
                try {
                    return ((Function) fn).call(cx, runtime.scope, runtime.scope, args);
                } finally {
                    AminContextFactory.endHook();
                }
            }
        } catch (Throwable t) {
            noteHookError(runtime.plugin.id, name, t);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Dispatch (A-01, A-14, A-18, A-35)
    // ------------------------------------------------------------------

    public static void dispatchMessage(final int account, final TLRPC.Message message) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || message == null || !isEngineEnabled(context)) {
            return;
        }
        final List<JsPlugin> targets = enabledPlugins(context, "observe");
        if (targets.isEmpty()) {
            return;
        }
        if (pendingMessageDispatches.incrementAndGet() > MAX_PENDING_MESSAGE_DISPATCHES) {
            pendingMessageDispatches.decrementAndGet();
            return;
        }
        postToPluginThread(() -> {
            pendingMessageDispatches.decrementAndGet();
            dispatchAccount = account;
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                MsgFields fields = null;
                for (JsPlugin plugin : targets) {
                    if (dispatchBlocked(plugin.id)) {
                        continue;
                    }
                    Runtime runtime = ensureRuntime(cx, context, plugin);
                    if (runtime == null) {
                        continue;
                    }
                    if (!(ScriptableObject.getProperty(runtime.scope, "onMessage") instanceof Function)) {
                        continue;
                    }
                    if (fields == null) {
                        fields = computeMsgFields(account, message);
                    }
                    AminBridge.setCurrentDispatchChatId(fields.chatId);
                    Scriptable msg = buildMsg(cx, runtime.scope, account, message, fields);
                    Object result = callHook(cx, runtime, "onMessage", new Object[]{msg});
                    if (result != null && ScriptRuntime.toBoolean(result)) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                AminBridge.setCurrentDispatchChatId(0);
                AminBridge.setCurrentDispatchReplyToMsg(null);
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    /** Own command arriving as an update (sent from another device). */
    public static void dispatchCommand(final int account, final TLRPC.Message message) {
        if (message == null || message.message == null) {
            return;
        }
        String[] parsed = parseCommand(message.message);
        if (parsed == null) {
            return;
        }
        dispatchCommandInternal(account, message, parsed[0], parsed[1]);
    }

    /** True when at least one enabled, non-blocked command plugin owns
     *  the given lowercase command name (Phase A.4 command suppression). */
    public static boolean willHandleCommand(Context context, String command) {
        if (context == null || command == null || command.isEmpty() || !isEngineEnabled(context)) {
            return false;
        }
        String lowerCmd = command.toLowerCase(Locale.US);
        for (JsPlugin plugin : enabledCommandPlugins(context)) {
            if (!plugin.hasCommand) {
                continue;
            }
            if (plugin.commands != null && plugin.commands.contains(lowerCmd)) {
                return true;
            }
        }
        return false;
    }

    /** Own command sent from THIS device (SendMessagesHelper hook).
     *  Returns true when an enabled command plugin claims the command,
     *  so SendMessagesHelper suppresses posting the raw "/cmd" text
     *  into the chat (Phase A.4). */
    public static boolean dispatchOutgoingCommand(final SendMessagesHelper.SendMessageParams params) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || params == null || params.message == null
                || !isEngineEnabled(context)) {
            return false;
        }
        String[] parsed = parseCommand(params.message);
        if (parsed == null) {
            return false;
        }
        final int account = UserConfig.selectedAccount;
        final long peer = params.peer;
        final String text = params.message;
        final String command = parsed[0];
        final String args = parsed[1];
        final MessageObject replyToMsg = params.replyToMsg;
        final boolean suppressOutgoing = willHandleCommand(context, command);

        postToPluginThread(() -> {
            dispatchAccount = account;
            AminBridge.setCurrentDispatchChatId(peer);
            AminBridge.setCurrentDispatchReplyToMsg(replyToMsg);
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                long selfId = UserConfig.getInstance(account).getClientUserId();
                for (JsPlugin plugin : enabledCommandPlugins(context)) {
                    if (dispatchBlocked(plugin.id)) {
                        continue;
                    }
                    Runtime runtime = ensureRuntime(cx, context, plugin);
                    if (runtime == null) {
                        continue;
                    }
                    if (!(ScriptableObject.getProperty(runtime.scope, "onCommand") instanceof Function)) {
                        continue;
                    }
                    Scriptable msg = cx.newObject(runtime.scope);
                    putNum(msg, "id", 0);
                    putNum(msg, "chatId", peer);
                    putNum(msg, "senderId", selfId);
                    putStr(msg, "senderName", "");
                    putStr(msg, "text", text);
                    msg.put("outgoing", msg, Boolean.TRUE);
                    msg.put("channel", msg, Boolean.FALSE);
                    putNum(msg, "date", (int) (System.currentTimeMillis() / 1000L));
                    putNum(msg, "replyToId", replyToMsg != null ? replyToMsg.getId() : 0);
                    Scriptable ctx = buildCtx(cx, runtime.scope, msg, peer, selfId, command, args,
                            account, null, replyToMsg);
                    Object result = callHook(cx, runtime, "onCommand", new Object[]{ctx});
                    if (result != null && ScriptRuntime.toBoolean(result)) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                AminBridge.setCurrentDispatchChatId(0);
                AminBridge.setCurrentDispatchReplyToMsg(null);
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
        return suppressOutgoing;
    }

    private static void dispatchCommandInternal(final int account, final TLRPC.Message message,
                                                final String command, final String args) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || !isEngineEnabled(context)) {
            return;
        }
        postToPluginThread(() -> {
            dispatchAccount = account;
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                MsgFields fields = null;
                long chatId = MessageObject.getDialogId(message);
                AminBridge.setCurrentDispatchChatId(chatId);
                for (JsPlugin plugin : enabledCommandPlugins(context)) {
                    if (dispatchBlocked(plugin.id)) {
                        continue;
                    }
                    Runtime runtime = ensureRuntime(cx, context, plugin);
                    if (runtime == null) {
                        continue;
                    }
                    if (!(ScriptableObject.getProperty(runtime.scope, "onCommand") instanceof Function)) {
                        continue;
                    }
                    if (fields == null) {
                        fields = computeMsgFields(account, message);
                    }
                    Scriptable msg = buildMsg(cx, runtime.scope, account, message, fields);
                    long senderId = UserConfig.getInstance(account).getClientUserId();
                    Scriptable ctx = buildCtx(cx, runtime.scope, msg, chatId, senderId, command, args,
                            account, message, null);
                    Object result = callHook(cx, runtime, "onCommand", new Object[]{ctx});
                    if (result != null && ScriptRuntime.toBoolean(result)) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                AminBridge.setCurrentDispatchChatId(0);
                AminBridge.setCurrentDispatchReplyToMsg(null);
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    public static void dispatchMenuAction(final String pluginId, final long chatId) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || !isEngineEnabled(context)) {
            return;
        }
        postToPluginThread(() -> {
            JsPlugin plugin = get(context, pluginId);
            if (plugin == null || !plugin.enabled) {
                return;
            }
            if (dispatchBlocked(pluginId)) {
                return;
            }
            dispatchAccount = UserConfig.selectedAccount;
            AminBridge.setCurrentDispatchChatId(chatId);
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                Runtime runtime = ensureRuntime(cx, context, plugin);
                if (runtime == null) {
                    return;
                }
                Scriptable ctx = cx.newObject(runtime.scope);
                putNum(ctx, "chatId", chatId);
                callHook(cx, runtime, "onMenuAction", new Object[]{ctx});
            } catch (Throwable ignored) {
            } finally {
                AminBridge.setCurrentDispatchChatId(0);
                AminBridge.setCurrentDispatchReplyToMsg(null);
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    public static void dispatchContextAction(final String pluginId, final int account,
                                             final TLRPC.Message message) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || message == null || !isEngineEnabled(context)) {
            return;
        }
        postToPluginThread(() -> {
            JsPlugin plugin = get(context, pluginId);
            if (plugin == null || !plugin.enabled || !plugin.hasContext) {
                return;
            }
            if (dispatchBlocked(pluginId)) {
                return;
            }
            dispatchAccount = account;
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                Runtime runtime = ensureRuntime(cx, context, plugin);
                if (runtime == null) {
                    return;
                }
                long chatId = MessageObject.getDialogId(message);
                AminBridge.setCurrentDispatchChatId(chatId);
                Scriptable msg = buildMsg(cx, runtime.scope, account, message);
                Scriptable ctx = cx.newObject(runtime.scope);
                putNum(ctx, "chatId", chatId);
                ctx.put("msg", ctx, msg);
                callHook(cx, runtime, "onContextAction", new Object[]{ctx});
            } catch (Throwable ignored) {
            } finally {
                AminBridge.setCurrentDispatchChatId(0);
                AminBridge.setCurrentDispatchReplyToMsg(null);
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    public static void dispatchPluginAction(final String pluginId, final String actionId) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || !isEngineEnabled(context)) {
            return;
        }
        postToPluginThread(() -> {
            JsPlugin plugin = get(context, pluginId);
            if (plugin == null || !plugin.enabled || !plugin.hasPluginAction) {
                return;
            }
            if (dispatchBlocked(pluginId)) {
                return;
            }
            dispatchAccount = UserConfig.selectedAccount;
            AminBridge.setCurrentDispatchChatId(0);
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                Runtime runtime = ensureRuntime(cx, context, plugin);
                if (runtime == null) {
                    return;
                }
                callHook(cx, runtime, "onPluginAction", new Object[]{actionId});
            } catch (Throwable ignored) {
            } finally {
                AminBridge.setCurrentDispatchChatId(0);
                AminBridge.setCurrentDispatchReplyToMsg(null);
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Relay & @depends hardening (A-18, Phase A.4)
    // ------------------------------------------------------------------

    public static final int RELAY_MAX_DEPTH = 4;
    private static int relayDepth = 0;

    public static void relayCallError(String callerId, Object callback, String message) {
        relayCallback(callerId, callback,
                org.json.JSONObject.quote(message == null ? "relay error" : message), null);
    }

    public static class InstallPreview {
        public String error;
        public String id = "";
        public String name = "";
        public String author = "";
        public String version = "";
        public String description = "";
        public java.util.List<String> capabilities = new java.util.ArrayList<>();
        public String contextMenu = "";
        public java.util.List<String> exports = new java.util.ArrayList<>();
        public java.util.List<String> topics = new java.util.ArrayList<>();
        public java.util.List<String> depends = new java.util.ArrayList<>();
        public int minPlatform;
        public String fingerprint = "";
        public java.util.List<String> problems = new java.util.ArrayList<>();
        public java.util.List<String> addedCapabilities = new java.util.ArrayList<>();
        public boolean update;
        public String installedVersion = "";
    }

    public static InstallPreview previewInstall(Context context, String source) {
        InstallPreview preview = new InstallPreview();
        preview.fingerprint = fingerprintOf(source == null ? "" : source);
        JsManifest manifest;
        try {
            manifest = JsManifest.parse(source);
        } catch (Throwable t) {
            preview.error = "Manifest error: " + t.getMessage();
            return preview;
        }
        preview.id = manifest.id;
        preview.name = manifest.name;
        preview.author = manifest.author;
        preview.version = manifest.version;
        preview.description = manifest.description;
        preview.capabilities.addAll(manifest.capabilities);
        preview.contextMenu = manifest.contextMenu;
        preview.exports.addAll(manifest.exports);
        preview.topics.addAll(manifest.topics);
        preview.minPlatform = manifest.minPlatform;
        for (JsManifest.Depends dep : manifest.depends) {
            preview.depends.add(dep.minVersion != null
                    ? dep.id + "@>=" + dep.minVersion : dep.id);
        }
        if (!isSemver(manifest.version)) {
            preview.problems.add("@version '" + manifest.version
                    + "' is not semver (X.Y or X.Y.Z) — update checks may misbehave.");
        }
        if (manifest.minPlatform > PLATFORM_API_VERSION) {
            preview.problems.add("Needs platform v" + manifest.minPlatform
                    + " — this AminGram provides v" + PLATFORM_API_VERSION + ".");
        }
        for (JsManifest.Depends dep : manifest.depends) {
            if (dep.id.equals(manifest.id)) {
                continue;
            }
            JsPlugin installedDep = get(context, dep.id);
            if (installedDep == null) {
                preview.problems.add("Missing dependency: '" + dep.id
                        + "' is not installed.");
            } else if (dep.minVersion != null
                    && compareVersions(installedDep.version, dep.minVersion) < 0) {
                preview.problems.add("Dependency too old: '" + dep.id + "' v"
                        + installedDep.version + " is installed, needs >= "
                        + dep.minVersion + ".");
            }
        }
        JsPlugin existing = get(context, manifest.id);
        if (existing != null) {
            preview.update = true;
            preview.installedVersion = existing.version;
            for (String cap : manifest.capabilities) {
                if (!existing.capabilities.contains(cap)) {
                    preview.addedCapabilities.add(cap);
                }
            }
        }
        return preview;
    }

    /** Verifies at runtime that all @depends of a plugin are still
     *  installed, enabled, and meet their minimum version. Returns null
     *  when satisfied, or an error reason string. */
    private static String checkRuntimeDependencies(Context context, JsPlugin plugin) {
        if (plugin == null || plugin.depends == null || plugin.depends.isEmpty()) {
            return null;
        }
        for (String spec : plugin.depends) {
            if (spec == null || spec.isEmpty()) {
                continue;
            }
            String depId = spec;
            String minVer = null;
            int at = spec.indexOf("@>=");
            if (at > 0) {
                depId = spec.substring(0, at).trim();
                minVer = spec.substring(at + 3).trim();
            }
            if (depId.equals(plugin.id)) {
                continue;
            }
            JsPlugin dep = get(context, depId);
            if (dep == null) {
                return "missing dependency '" + depId + "'";
            }
            if (!dep.enabled) {
                return "dependency '" + depId + "' is disabled";
            }
            if (dispatchBlocked(depId)) {
                return "dependency '" + depId + "' is suspended";
            }
            if (minVer != null && !minVer.isEmpty() && compareVersions(dep.version, minVer) < 0) {
                return "dependency '" + depId + "' v" + dep.version + " < required " + minVer;
            }
        }
        return null;
    }

    public static void relayCall(final String callerId, final String targetId,
                                 final String method, final String argsJson,
                                 final Object callback) {
        final int depth = relayDepth;
        if (depth >= RELAY_MAX_DEPTH) {
            relayCallback(callerId, callback,
                    "\"relay depth exceeded (max " + RELAY_MAX_DEPTH + ")\"", null);
            return;
        }
        if (argsJson != null && argsJson.length() > MAX_RELAY_JSON_CHARS) {
            relayCallback(callerId, callback,
                    "\"relay arguments exceed 256 KB limit\"", null);
            return;
        }
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            relayCallback(callerId, callback, "\"relay unavailable\"", null);
            return;
        }
        postToPluginThread(() -> {
            JsPlugin target = get(context, targetId);
            String err = null;
            String resultJson = null;
            if (target == null) {
                err = "\"no such plugin: " + targetId + "\"";
            } else if (!target.enabled) {
                err = "\"plugin is disabled: " + targetId + "\"";
            } else if (!target.exports.contains(method)) {
                err = "\"plugin " + targetId + " does not export '" + method + "'\"";
            } else if (dispatchBlocked(targetId)) {
                err = "\"plugin is suspended: " + targetId + "\"";
            } else {
                String depErr = checkRuntimeDependencies(context, target);
                if (depErr != null) {
                    err = "\"plugin " + targetId + " has " + depErr + "\"";
                }
            }
            org.mozilla.javascript.Context cx = null;
            if (err == null) {
                try {
                    cx = enterContext();
                    Runtime runtime = ensureRuntime(cx, context, target);
                    if (runtime == null) {
                        err = "\"plugin failed to load: " + targetId + "\"";
                    } else {
                        Object api = ScriptableObject.getProperty(runtime.scope, "PLUGIN_API");
                        Object fn = api instanceof Scriptable
                                ? ((Scriptable) api).get(method, (Scriptable) api) : null;
                        if (!(fn instanceof Function)) {
                            err = "\"plugin " + targetId + " has no PLUGIN_API method '"
                                    + method + "'\"";
                        } else {
                            Object argsValue = AminBridge.jsonToJs(
                                    argsJson == null ? "[]" : argsJson, cx, runtime.scope);
                            Object[] callArgs;
                            if (argsValue instanceof Scriptable) {
                                Scriptable arr = (Scriptable) argsValue;
                                Object len = arr.get("length", arr);
                                int n = len instanceof Number ? ((Number) len).intValue() : 0;
                                callArgs = new Object[n];
                                for (int i = 0; i < n; i++) {
                                    callArgs[i] = arr.get(i, arr);
                                }
                            } else {
                                callArgs = new Object[0];
                            }
                            appendLog(targetId, "[relay] call <- " + callerId + "." + method);
                            appendLog(callerId, "[relay] call -> " + targetId + "." + method);
                            relayDepth = depth + 1;
                            AminContextFactory.beginHook(HOOK_BUDGET_MS);
                            Object result;
                            try {
                                result = ((Function) fn).call(cx, runtime.scope,
                                        (Scriptable) api, callArgs);
                            } finally {
                                AminContextFactory.endHook();
                                relayDepth = depth;
                            }
                            resultJson = AminBridge.jsToJson(result);
                            if (resultJson == null && result != null
                                    && !(result instanceof org.mozilla.javascript.Undefined)) {
                                err = "\"relay result is not JSON-serialisable\"";
                                resultJson = null;
                            } else if (resultJson != null && resultJson.length() > MAX_RELAY_JSON_CHARS) {
                                err = "\"relay result exceeds 256 KB limit\"";
                                resultJson = null;
                            }
                        }
                    }
                } catch (Throwable t) {
                    noteHookError(targetId, "relay." + method, t);
                    err = "\"relay call failed: "
                            + String.valueOf(t.getMessage()).replace("\"", "'") + "\"";
                    resultJson = null;
                } finally {
                    if (cx != null) {
                        org.mozilla.javascript.Context.exit();
                    }
                }
            }
            relayCallback(callerId, callback, err, resultJson);
        });
    }

    private static void relayCallback(final String callerId, final Object callback,
                                      final String errJson, final String resultJson) {
        if (!(callback instanceof Function)) {
            return;
        }
        final Function fn = (Function) callback;
        postToPluginThread(() -> {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || get(context, callerId) == null
                    || suspended.contains(callerId)) {
                return;
            }
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                Scriptable scope = fn.getParentScope();
                if (scope == null) {
                    return;
                }
                Object err = AminBridge.jsonToJs(errJson, cx, scope);
                Object result = AminBridge.jsonToJs(resultJson, cx, scope);
                AminContextFactory.beginHook(HOOK_BUDGET_MS);
                try {
                    fn.call(cx, scope, scope, new Object[]{err, result});
                } finally {
                    AminContextFactory.endHook();
                }
            } catch (Throwable t) {
                noteHookError(callerId, "relay callback", t);
            } finally {
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    /** Shares a single Rhino Context across all target plugins (A-18). */
    public static void relayEmit(final String callerId, final String topic,
                                 final String dataJson) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || topic == null || topic.isEmpty()) {
            return;
        }
        if (dataJson != null && dataJson.length() > MAX_RELAY_JSON_CHARS) {
            appendLog(callerId, "[warn] amin.relay.emit: event payload exceeds 256 KB; dropped");
            return;
        }
        postToPluginThread(() -> {
            org.mozilla.javascript.Context cx = null;
            try {
                for (JsPlugin plugin : plugins(context)) {
                    if (plugin.id.equals(callerId) || !plugin.enabled || !plugin.hasEvent) {
                        continue;
                    }
                    if (!plugin.topics.contains(topic) && !plugin.topics.contains("*")) {
                        continue;
                    }
                    if (dispatchBlocked(plugin.id)) {
                        continue;
                    }
                    if (cx == null) {
                        cx = enterContext();
                    }
                    try {
                        Runtime runtime = ensureRuntime(cx, context, plugin);
                        if (runtime == null) {
                            continue;
                        }
                        Object data = AminBridge.jsonToJs(
                                dataJson == null ? "null" : dataJson, cx, runtime.scope);
                        appendLog(plugin.id, "[relay] event '" + topic + "' <- " + callerId);
                        callHook(cx, runtime, "onEvent", new Object[]{topic, data});
                    } catch (Throwable ignored) {
                    }
                }
            } finally {
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Developer tools support (§3.5)
    // ------------------------------------------------------------------

    public interface EvalResult {
        void onResult(String text);
    }

    public static void evaluateInPlugin(final String pluginId, final String snippet,
                                        final EvalResult callback) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            callback.onResult("Error: no context");
            return;
        }
        String flat = snippet == null ? "" : snippet.replace('\n', ' ');
        if (flat.length() > 200) {
            flat = flat.substring(0, 200) + "\u2026";
        }
        appendLog(pluginId, "[eval] " + flat);
        postToPluginThread(() -> {
            String out;
            JsPlugin plugin = get(context, pluginId);
            if (plugin == null) {
                out = "Error: plugin not installed";
            } else if (suspended.contains(pluginId)) {
                out = "Error: plugin is suspended — resume it first";
            } else {
                org.mozilla.javascript.Context cx = null;
                try {
                    cx = enterContext();
                    Runtime runtime = ensureRuntime(cx, context, plugin);
                    if (runtime == null) {
                        out = "Error: plugin failed to load";
                    } else {
                        AminContextFactory.beginHook(HOOK_BUDGET_MS);
                        try {
                            Object result = cx.evaluateString(runtime.scope, snippet,
                                    "<eval>", 1, null);
                            if (result == null
                                    || result instanceof org.mozilla.javascript.Undefined) {
                                out = "undefined";
                            } else {
                                String json = AminBridge.jsToJson(result);
                                out = json != null ? json : String.valueOf(result);
                            }
                        } finally {
                            AminContextFactory.endHook();
                        }
                    }
                } catch (Throwable t) {
                    noteHookError(pluginId, "eval", t);
                    out = "Error: " + t.getMessage();
                } finally {
                    if (cx != null) {
                        org.mozilla.javascript.Context.exit();
                    }
                }
            }
            final String text = out;
            appendLog(pluginId, "[eval] -> " + (text.length() > 200
                    ? text.substring(0, 200) + "\u2026" : text));
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> callback.onResult(text));
        });
    }

    public static java.util.Map<String, String> storageGetAll(Context context, String pluginId) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        try {
            java.util.Map<String, ?> all = context
                    .getSharedPreferences(storagePrefsName(pluginId), Context.MODE_PRIVATE)
                    .getAll();
            for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                out.put(entry.getKey(),
                        entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void storageRemoveRaw(Context context, String pluginId, String key) {
        try {
            context.getSharedPreferences(storagePrefsName(pluginId), Context.MODE_PRIVATE)
                    .edit().remove(key).apply();
        } catch (Throwable ignored) {
        }
    }

    public static void clearPluginData(Context context, String pluginId) {
        try {
            context.getSharedPreferences(storagePrefsName(pluginId), Context.MODE_PRIVATE)
                    .edit().clear().apply();
        } catch (Throwable ignored) {
        }
        PluginDb.deleteAllForPlugin(pluginId);
        appendLog(pluginId, "[info] data cleared from the Developer tools");
    }

    private static List<JsPlugin> enabledPlugins(Context context, String capability) {
        List<JsPlugin> out = new ArrayList<>();
        synchronized (JsPluginManager.class) {
            loadCache(context);
            for (JsPlugin plugin : cache) {
                if (plugin.enabled && plugin.hasCapability(capability)
                        && !dispatchBlocked(plugin.id)) {
                    out.add(plugin);
                }
            }
        }
        return out;
    }

    private static List<JsPlugin> enabledCommandPlugins(Context context) {
        return enabledPlugins(context, "commands");
    }

    // ------------------------------------------------------------------
    // JS value builders & lazy lookups (A-27, A-35, Phase A.4, Phase C.1)
    // ------------------------------------------------------------------

    private static int resolveLookupAccount() {
        int account = dispatchAccount;
        if (account < 0) {
            account = UserConfig.selectedAccount;
        }
        return account;
    }

    private static Scriptable lookupScope(org.mozilla.javascript.Context cx, String pluginId) {
        Runtime runtime = pluginId != null ? runtimes.get(pluginId) : null;
        if (runtime != null && runtime.scope != null) {
            return runtime.scope;
        }
        ScriptableObject fb = sharedFallbackScope;
        if (fb == null) {
            fb = cx.initStandardObjects(null, true);
            sharedFallbackScope = fb;
        }
        return fb;
    }

    public static Scriptable lookupChatInfo(String pluginId, long chatId) {
        org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.getCurrentContext();
        if (cx == null || chatId == 0) {
            return null;
        }
        try {
            int account = resolveLookupAccount();
            MessagesController controller = MessagesController.getInstance(account);
            Scriptable scope = lookupScope(cx, pluginId);
            Scriptable obj = cx.newObject(scope);
            if (chatId > 0) {
                TLRPC.User user = controller.getUser(chatId);
                if (user == null) {
                    return null;
                }
                putNum(obj, "id", chatId);
                putStr(obj, "type", "private");
                putStr(obj, "title", UserObject.getUserName(user));
                putStr(obj, "username", user.username == null ? "" : user.username);
                putNum(obj, "memberCount", 0);
                return obj;
            }
            TLRPC.Chat chat = controller.getChat(-chatId);
            if (chat == null) {
                return null;
            }
            putNum(obj, "id", chatId);
            putStr(obj, "type", chat.broadcast ? "channel"
                    : (chat.megagroup ? "supergroup" : "group"));
            putStr(obj, "title", chat.title == null ? "" : chat.title);
            putStr(obj, "username", chat.username == null ? "" : chat.username);
            putNum(obj, "memberCount", chat.participants_count);
            return obj;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Scriptable lookupUserInfo(String pluginId, long userId) {
        org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.getCurrentContext();
        if (cx == null || userId <= 0) {
            return null;
        }
        try {
            int account = resolveLookupAccount();
            TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
            if (user == null) {
                return null;
            }
            Scriptable scope = lookupScope(cx, pluginId);
            Scriptable obj = cx.newObject(scope);
            putNum(obj, "id", userId);
            putStr(obj, "name", UserObject.getUserName(user));
            putStr(obj, "username", user.username == null ? "" : user.username);
            obj.put("isBot", obj, user.bot ? Boolean.TRUE : Boolean.FALSE);
            obj.put("isPremium", obj, user.premium ? Boolean.TRUE : Boolean.FALSE);
            return obj;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Scriptable lookupMessage(String pluginId, long chatId, long id) {
        org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.getCurrentContext();
        if (cx == null) {
            return null;
        }
        try {
            int account = resolveLookupAccount();
            TLRPC.Message message = findLoadedMessage(account, chatId, id);
            if (message == null) {
                return null;
            }
            return buildMsg(cx, lookupScope(cx, pluginId), account, message);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Returns the raw message metadata object for (chatId, id), or null (Phase A.4). */
    public static Scriptable lookupMessageRaw(String pluginId, long chatId, long id) {
        org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.getCurrentContext();
        if (cx == null) {
            return null;
        }
        try {
            int account = resolveLookupAccount();
            TLRPC.Message message = findLoadedMessage(account, chatId, id);
            if (message == null) {
                return null;
            }
            MsgFields fields = computeMsgFields(account, message);
            return buildRawMessageObject(cx, lookupScope(cx, pluginId), account, message, fields);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static TLRPC.Message findLoadedMessage(int account, long chatId, long messageId) {
        try {
            MessagesController controller = MessagesController.getInstance(account);
            java.util.ArrayList<MessageObject> list = controller.dialogMessage.get(chatId);
            if (list != null) {
                for (MessageObject obj : list) {
                    if (obj != null && obj.getId() == messageId && obj.messageOwner != null) {
                        return obj.messageOwner;
                    }
                }
            }
            if (messageId >= Integer.MIN_VALUE && messageId <= Integer.MAX_VALUE) {
                MessageObject obj = controller.dialogMessagesByIds.get((int) messageId);
                if (obj != null && obj.getDialogId() == chatId && obj.messageOwner != null) {
                    return obj.messageOwner;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final class MsgFields {
        long chatId;
        long senderId;
        String senderName;
        String chatTitle;
        String chatType;
        String senderUsername;
        String fwdFrom;
        boolean senderIsBot;
        boolean channel;
    }

    private static MsgFields computeMsgFields(int account, TLRPC.Message message) {
        MsgFields fields = new MsgFields();
        fields.chatId = MessageObject.getDialogId(message);
        fields.senderId = message.from_id != null
                ? DialogObject.getPeerDialogId(message.from_id) : fields.chatId;
        fields.senderName = resolveSenderName(account, fields.senderId, fields.chatId);
        MessagesController controller = null;
        TLRPC.Chat chat = null;
        TLRPC.User senderUser = null;
        try {
            controller = MessagesController.getInstance(account);
            if (fields.chatId < 0) {
                chat = controller.getChat(-fields.chatId);
            }
            if (fields.senderId > 0) {
                senderUser = controller.getUser(fields.senderId);
            }
        } catch (Throwable ignored) {
        }
        boolean channel = false;
        if (message.peer_id != null && message.peer_id.channel_id != 0) {
            TLRPC.Chat peerChat = chat;
            if (peerChat == null && controller != null) {
                try {
                    peerChat = controller.getChat(message.peer_id.channel_id);
                } catch (Throwable ignored) {
                }
            }
            channel = peerChat == null || !peerChat.megagroup;
        }
        fields.channel = channel;
        fields.fwdFrom = resolveFwdFromName(account, message, fields.chatId);
        String chatType;
        String chatTitle = "";
        if (fields.chatId > 0) {
            chatType = "private";
            try {
                TLRPC.User chatUser = controller != null ? controller.getUser(fields.chatId) : null;
                if (chatUser != null) {
                    chatTitle = UserObject.getUserName(chatUser);
                }
            } catch (Throwable ignored) {
            }
            if (chatTitle == null || chatTitle.isEmpty()) {
                chatTitle = fields.chatId == fields.senderId && fields.senderName != null
                        ? fields.senderName : "";
            }
        } else if (fields.chatId < 0) {
            if (chat != null) {
                chatTitle = chat.title == null ? "" : chat.title;
                chatType = chat.broadcast ? "channel" : (chat.megagroup ? "supergroup" : "group");
            } else if (message.peer_id != null && message.peer_id.chat_id != 0) {
                chatType = "group";
            } else {
                chatType = "channel";
            }
        } else {
            chatType = "private";
        }
        fields.chatTitle = chatTitle;
        fields.chatType = chatType;
        String senderUsername = "";
        boolean senderIsBot = false;
        if (senderUser != null) {
            senderUsername = senderUser.username == null ? "" : senderUser.username;
            senderIsBot = senderUser.bot;
        } else if (fields.senderId < 0 && controller != null) {
            try {
                TLRPC.Chat senderChat = controller.getChat(-fields.senderId);
                if (senderChat != null && senderChat.username != null) {
                    senderUsername = senderChat.username;
                }
            } catch (Throwable ignored) {
            }
        }
        fields.senderUsername = senderUsername;
        fields.senderIsBot = senderIsBot;
        return fields;
    }

    private static Scriptable buildMsg(org.mozilla.javascript.Context cx, Scriptable scope,
                                       int account, TLRPC.Message message) {
        return buildMsg(cx, scope, account, message, null);
    }

    private static Scriptable buildMsg(org.mozilla.javascript.Context cx, Scriptable scope,
                                       int account, TLRPC.Message message, MsgFields fields) {
        if (fields == null) {
            fields = computeMsgFields(account, message);
        }
        Scriptable msg = cx.newObject(scope);
        putNum(msg, "id", message.id);
        putNum(msg, "chatId", fields.chatId);
        putNum(msg, "senderId", fields.senderId);
        putStr(msg, "senderName", fields.senderName);
        putStr(msg, "text", message.message == null ? "" : message.message);
        msg.put("outgoing", msg, message.out ? Boolean.TRUE : Boolean.FALSE);
        msg.put("channel", msg, fields.channel ? Boolean.TRUE : Boolean.FALSE);
        putNum(msg, "date", message.date);
        putNum(msg, "replyToId", message.reply_to != null ? message.reply_to.reply_to_msg_id : 0);
        putNum(msg, "editDate", message.edit_date);
        putStr(msg, "fwdFrom", fields.fwdFrom);
        putNum(msg, "groupedId", message.grouped_id);
        putStr(msg, "chatTitle", fields.chatTitle);
        putStr(msg, "chatType", fields.chatType);
        putStr(msg, "senderUsername", fields.senderUsername);
        msg.put("senderIsBot", msg, fields.senderIsBot ? Boolean.TRUE : Boolean.FALSE);
        if (message.media != null && !(message.media instanceof TLRPC.TL_messageMediaEmpty)) {
            msg.put("media", msg, buildMediaDescriptor(cx, scope, account, message, message.media));
        } else {
            msg.put("media", msg, null);
        }
        msg.put("raw", msg, buildRawMessageObject(cx, scope, account, message, fields));
        return msg;
    }

    /** Exposes the full structured server metadata of TLRPC.Message on
     *  msg.raw and amin.getMessageRaw (Phase A.4). */
    private static Scriptable buildRawMessageObject(org.mozilla.javascript.Context cx,
                                                    Scriptable scope, int account,
                                                    TLRPC.Message message, MsgFields fields) {
        Scriptable raw = cx.newObject(scope);
        try {
            putNum(raw, "id", message.id);
            putNum(raw, "realId", message.realId);
            putNum(raw, "dialogId", fields.chatId);
            putNum(raw, "fromId", fields.senderId);
            putNum(raw, "date", message.date);
            putNum(raw, "editDate", message.edit_date);
            raw.put("editHide", raw, message.edit_hide ? Boolean.TRUE : Boolean.FALSE);
            putNum(raw, "flags", message.flags);
            raw.put("out", raw, message.out ? Boolean.TRUE : Boolean.FALSE);
            raw.put("mentioned", raw, message.mentioned ? Boolean.TRUE : Boolean.FALSE);
            raw.put("mediaUnread", raw, message.media_unread ? Boolean.TRUE : Boolean.FALSE);
            raw.put("silent", raw, message.silent ? Boolean.TRUE : Boolean.FALSE);
            raw.put("post", raw, message.post ? Boolean.TRUE : Boolean.FALSE);
            raw.put("fromScheduled", raw, message.from_scheduled ? Boolean.TRUE : Boolean.FALSE);
            raw.put("legacy", raw, message.legacy ? Boolean.TRUE : Boolean.FALSE);
            raw.put("pinned", raw, message.pinned ? Boolean.TRUE : Boolean.FALSE);
            raw.put("noforwards", raw, message.noforwards ? Boolean.TRUE : Boolean.FALSE);
            raw.put("invertMedia", raw, message.invert_media ? Boolean.TRUE : Boolean.FALSE);
            putNum(raw, "views", message.views);
            putNum(raw, "forwards", message.forwards);
            putNum(raw, "groupedId", message.grouped_id);
            putNum(raw, "viaBotId", message.via_bot_id);
            putStr(raw, "postAuthor", message.post_author);
            int dcId = 0;
            if (message.media != null) {
                if (message.media.document != null) {
                    dcId = message.media.document.dc_id;
                } else if (message.media.photo != null) {
                    dcId = message.media.photo.dc_id;
                }
            }
            putNum(raw, "dcId", dcId);
            if (message.fwd_from != null) {
                Scriptable fwd = cx.newObject(scope);
                putNum(fwd, "date", message.fwd_from.date);
                putNum(fwd, "fromId", message.fwd_from.from_id != null
                        ? DialogObject.getPeerDialogId(message.fwd_from.from_id) : 0);
                putStr(fwd, "fromName", message.fwd_from.from_name);
                putStr(fwd, "postAuthor", message.fwd_from.post_author);
                putNum(fwd, "channelPost", message.fwd_from.channel_post);
                putNum(fwd, "savedFromPeerId", message.fwd_from.saved_from_peer != null
                        ? DialogObject.getPeerDialogId(message.fwd_from.saved_from_peer) : 0);
                putNum(fwd, "savedFromMsgId", message.fwd_from.saved_from_msg_id);
                raw.put("fwdFrom", raw, fwd);
            } else {
                raw.put("fwdFrom", raw, null);
            }
            if (message.reply_to != null) {
                Scriptable rep = cx.newObject(scope);
                putNum(rep, "replyToMsgId", message.reply_to.reply_to_msg_id);
                putNum(rep, "replyToTopId", message.reply_to.reply_to_top_id);
                putNum(rep, "replyToPeerId", message.reply_to.reply_to_peer_id != null
                        ? DialogObject.getPeerDialogId(message.reply_to.reply_to_peer_id) : 0);
                putStr(rep, "quoteText", message.reply_to.quote_text);
                raw.put("replyTo", raw, rep);
            } else {
                raw.put("replyTo", raw, null);
            }
            List<Object> ents = new ArrayList<>();
            if (message.entities != null) {
                for (TLRPC.MessageEntity entity : message.entities) {
                    if (entity == null) {
                        continue;
                    }
                    Scriptable eObj = cx.newObject(scope);
                    putStr(eObj, "type", entity.getClass().getSimpleName()
                            .replace("TL_messageEntity", ""));
                    putNum(eObj, "offset", entity.offset);
                    putNum(eObj, "length", entity.length);
                    putStr(eObj, "url", entity.url);
                    putStr(eObj, "language", entity.language);
                    if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
                        putStr(eObj, "documentId",
                                String.valueOf(((TLRPC.TL_messageEntityCustomEmoji) entity).document_id));
                    }
                    ents.add(eObj);
                }
            }
            raw.put("entities", raw, cx.newArray(scope, ents.toArray()));
        } catch (Throwable ignored) {
        }
        return raw;
    }

    private static String resolveFwdFromName(int account, TLRPC.Message message, long chatId) {
        try {
            TLRPC.MessageFwdHeader fwd = message.fwd_from;
            if (fwd == null) {
                return "";
            }
            if (fwd.from_name != null && !fwd.from_name.isEmpty()) {
                return fwd.from_name;
            }
            if (fwd.from_id != null) {
                long peerId = DialogObject.getPeerDialogId(fwd.from_id);
                String name = resolveSenderName(account, peerId, chatId);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
            if (fwd.post_author != null && !fwd.post_author.isEmpty()) {
                return fwd.post_author;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** Enriched msg.media descriptor (Phase A.4 & Phase C.1 Sticker Downloader). */
    private static Scriptable buildMediaDescriptor(org.mozilla.javascript.Context cx,
                                                   Scriptable scope,
                                                   int account,
                                                   TLRPC.Message message,
                                                   TLRPC.MessageMedia media) {
        Scriptable desc = cx.newObject(scope);
        String kind = "other";
        String fileName = "";
        String mimeType = "";
        long sizeBytes = 0;
        long durationSec = 0;
        long width = 0;
        long height = 0;
        long dcId = 0;
        String documentId = "";
        String stickerEmoji = "";
        String stickerSetName = "";
        String stickerSetId = "";
        boolean isAnimatedSticker = false;
        boolean isVideoSticker = false;
        boolean isCustomEmoji = false;
        String localPath = "";
        try {
            if (message != null) {
                File f = FileLoader.getInstance(account).getPathToMessage(message);
                if (f != null && f.isFile() && f.length() > 0) {
                    localPath = f.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (media instanceof TLRPC.TL_messageMediaPhoto) {
                kind = "photo";
                TLRPC.Photo photo = media.photo;
                if (photo != null) {
                    dcId = photo.dc_id;
                    documentId = String.valueOf(photo.id);
                    if (photo.sizes != null) {
                        TLRPC.PhotoSize best = null;
                        for (TLRPC.PhotoSize size : photo.sizes) {
                            if (size != null && (best == null || size.size > best.size)) {
                                best = size;
                            }
                        }
                        if (best != null) {
                            sizeBytes = best.size;
                            width = best.w;
                            height = best.h;
                        }
                    }
                }
            } else if (media instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.Document doc = media.document;
                if (doc != null) {
                    dcId = doc.dc_id;
                    documentId = String.valueOf(doc.id);
                    fileName = documentFileName(doc);
                    mimeType = doc.mime_type == null ? "" : doc.mime_type;
                    sizeBytes = doc.size;
                    String docKind = "document";
                    boolean animated = false;
                    boolean sticker = false;
                    if (doc.attributes != null) {
                        for (TLRPC.DocumentAttribute attr : doc.attributes) {
                            if (attr == null) {
                                continue;
                            }
                            if (attr instanceof TLRPC.TL_documentAttributeSticker) {
                                sticker = true;
                                if (attr.alt != null) {
                                    stickerEmoji = attr.alt;
                                }
                                if (attr.stickerset != null) {
                                    if (attr.stickerset.short_name != null) {
                                        stickerSetName = attr.stickerset.short_name;
                                    }
                                    if (attr.stickerset.id != 0) {
                                        stickerSetId = String.valueOf(attr.stickerset.id);
                                    }
                                }
                            } else if (attr instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                                sticker = true;
                                isCustomEmoji = true;
                                if (attr.alt != null && stickerEmoji.isEmpty()) {
                                    stickerEmoji = attr.alt;
                                }
                                if (attr.stickerset != null) {
                                    if (attr.stickerset.short_name != null && stickerSetName.isEmpty()) {
                                        stickerSetName = attr.stickerset.short_name;
                                    }
                                    if (attr.stickerset.id != 0 && stickerSetId.isEmpty()) {
                                        stickerSetId = String.valueOf(attr.stickerset.id);
                                    }
                                }
                            } else if (attr instanceof TLRPC.TL_documentAttributeAudio) {
                                docKind = attr.voice ? "voice" : "audio";
                                durationSec = Math.round(attr.duration);
                            } else if (attr instanceof TLRPC.TL_documentAttributeAnimated) {
                                animated = true;
                            } else if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                                if ("document".equals(docKind)) {
                                    docKind = "video";
                                }
                                durationSec = Math.round(attr.duration);
                                width = attr.w;
                                height = attr.h;
                            } else if (attr instanceof TLRPC.TL_documentAttributeImageSize) {
                                if (width == 0) {
                                    width = attr.w;
                                    height = attr.h;
                                }
                            } else if (attr instanceof TLRPC.TL_documentAttributeFilename) {
                                if (fileName.isEmpty() && attr.file_name != null) {
                                    fileName = attr.file_name;
                                }
                            }
                        }
                    }
                    if (sticker) {
                        docKind = "sticker";
                        isAnimatedSticker = "application/x-tgsticker".equalsIgnoreCase(mimeType);
                        isVideoSticker = "video/webm".equalsIgnoreCase(mimeType);
                    } else if (animated && ("document".equals(docKind) || "video".equals(docKind))) {
                        docKind = "gif";
                    } else if ("document".equals(docKind) && mimeType.startsWith("video/")) {
                        docKind = "video";
                    }
                    kind = docKind;
                }
            } else if (media instanceof TLRPC.TL_messageMediaWebPage) {
                kind = "webpage";
            } else if (media instanceof TLRPC.TL_messageMediaPoll) {
                kind = "poll";
            } else if (media.photo != null) {
                kind = "photo";
                dcId = media.photo.dc_id;
                documentId = String.valueOf(media.photo.id);
                if (media.photo.sizes != null) {
                    TLRPC.PhotoSize best = null;
                    for (TLRPC.PhotoSize size : media.photo.sizes) {
                        if (size != null && (best == null || size.size > best.size)) {
                            best = size;
                        }
                    }
                    if (best != null) {
                        sizeBytes = best.size;
                        width = best.w;
                        height = best.h;
                    }
                }
            } else if (media.document != null) {
                kind = "document";
                dcId = media.document.dc_id;
                documentId = String.valueOf(media.document.id);
                fileName = documentFileName(media.document);
                mimeType = media.document.mime_type == null ? "" : media.document.mime_type;
                sizeBytes = media.document.size;
            }
        } catch (Throwable ignored) {
        }
        putStr(desc, "kind", kind);
        putStr(desc, "fileName", fileName);
        putStr(desc, "mimeType", mimeType);
        putNum(desc, "sizeBytes", sizeBytes);
        putNum(desc, "durationSec", durationSec);
        putNum(desc, "width", width);
        putNum(desc, "height", height);
        putNum(desc, "dcId", dcId);
        putStr(desc, "documentId", documentId);
        putStr(desc, "stickerEmoji", stickerEmoji);
        putStr(desc, "stickerSetName", stickerSetName);
        putStr(desc, "stickerSetId", stickerSetId);
        desc.put("isAnimatedSticker", desc, isAnimatedSticker ? Boolean.TRUE : Boolean.FALSE);
        desc.put("isVideoSticker", desc, isVideoSticker ? Boolean.TRUE : Boolean.FALSE);
        desc.put("isCustomEmoji", desc, isCustomEmoji ? Boolean.TRUE : Boolean.FALSE);
        putStr(desc, "localPath", localPath);
        return desc;
    }

    private static String documentFileName(TLRPC.Document doc) {
        if (doc.file_name_fixed != null && !doc.file_name_fixed.isEmpty()) {
            return doc.file_name_fixed;
        }
        return doc.file_name == null ? "" : doc.file_name;
    }

    private static Scriptable buildCtx(org.mozilla.javascript.Context cx, Scriptable scope,
                                       Scriptable msg, long chatId, long senderId,
                                       String command, String args, int account,
                                       TLRPC.Message sourceMessage, MessageObject replyTarget) {
        Scriptable ctx = cx.newObject(scope);
        putNum(ctx, "chatId", chatId);
        putNum(ctx, "senderId", senderId);
        putStr(ctx, "command", command);
        putStr(ctx, "args", args);
        ctx.put("msg", ctx, msg);
        long replyToId = 0;
        TLRPC.Message target = null;
        if (replyTarget != null) {
            replyToId = replyTarget.getId();
            target = replyTarget.messageOwner;
        } else if (sourceMessage != null && sourceMessage.reply_to != null) {
            replyToId = sourceMessage.reply_to.reply_to_msg_id;
        }
        if (replyToId != 0) {
            if (target == null) {
                target = findLoadedMessage(account, chatId, replyToId);
            }
            Scriptable replyTo = cx.newObject(scope);
            String targetText = target != null && target.message != null ? target.message : "";
            putStr(replyTo, "text", targetText);
            boolean hasMedia = target != null && target.media != null
                    && !(target.media instanceof TLRPC.TL_messageMediaEmpty);
            putStr(replyTo, "caption", hasMedia ? targetText : "");
            String targetSenderName = "";
            if (target != null) {
                long targetSenderId = target.from_id != null
                        ? DialogObject.getPeerDialogId(target.from_id) : chatId;
                targetSenderName = resolveSenderName(account, targetSenderId, chatId);
            }
            putStr(replyTo, "senderName", targetSenderName);
            putNum(replyTo, "chatId", chatId);
            putNum(replyTo, "messageId", replyToId);
            if (target != null) {
                Scriptable targetMsg = buildMsg(cx, scope, account, target);
                replyTo.put("msg", replyTo, targetMsg);
                replyTo.put("media", replyTo, targetMsg.get("media", targetMsg));
            } else {
                replyTo.put("msg", replyTo, null);
                replyTo.put("media", replyTo, null);
            }
            ctx.put("replyTo", ctx, replyTo);
        }
        return ctx;
    }

    private static String resolveSenderName(int account, long senderId, long chatId) {
        try {
            MessagesController controller = MessagesController.getInstance(account);
            if (senderId > 0) {
                TLRPC.User user = controller.getUser(senderId);
                if (user != null) {
                    return UserObject.getUserName(user);
                }
            }
            if (senderId < 0) {
                TLRPC.Chat chat = controller.getChat(-senderId);
                if (chat != null && chat.title != null) {
                    return chat.title;
                }
            }
            if (chatId < 0) {
                TLRPC.Chat chat = controller.getChat(-chatId);
                if (chat != null && chat.title != null) {
                    return chat.title;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static void putNum(Scriptable obj, String key, long value) {
        obj.put(key, obj, Double.valueOf(value));
    }

    private static void putStr(Scriptable obj, String key, String value) {
        obj.put(key, obj, value == null ? "" : value);
    }

    public static String[] parseCommand(String text) {
        if (text == null || !text.startsWith("/")) {
            return null;
        }
        String body = text.substring(1).trim();
        if (body.isEmpty()) {
            return null;
        }
        int space = body.indexOf(' ');
        String command;
        String args;
        if (space < 0) {
            command = body;
            args = "";
        } else {
            command = body.substring(0, space);
            args = body.substring(space + 1).trim();
        }
        int at = command.indexOf('@');
        if (at >= 0) {
            command = command.substring(0, at);
        }
        command = command.toLowerCase(Locale.US);
        if (command.isEmpty()) {
            return null;
        }
        return new String[]{command, args};
    }
}
