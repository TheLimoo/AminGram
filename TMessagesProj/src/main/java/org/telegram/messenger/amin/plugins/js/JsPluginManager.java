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
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.amin.core.VaultStore;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JS plugin system (v1.3 flagship): installs, stores and runs
 * single-file JavaScript plugins exactly as specified in
 * docs/PLUGIN-MASTER-PROMPT.md.
 *
 * Engine: Mozilla Rhino (org.mozilla:rhino), interpreted mode
 * (optimization level -1, required on Android), language version ES6.
 * A ClassShutter whitelists ONLY the AminBridge classes — plugin code
 * gets the `amin` bridge object and pure JavaScript, nothing else:
 * no Java classes, no Android APIs, no file or network access except
 * amin.httpGet.
 *
 * Execution model: all plugin code runs on ONE dedicated plugin
 * thread (a private HandlerThread — see PluginQueue below). v1.3.2
 * audit fix: dispatch used to ride Telegram's shared
 * Utilities.globalQueue, where one plugin's blocking amin.httpGet
 * (up to ~30 s) stalled Telegram's own background work. The private
 * thread keeps Rhino's single-thread confinement AND takes plugins
 * off the shared queue. No timers, no polling.
 * Events are dispatched from Telegram's own update flow via AminHooks.
 * A plugin that throws is caught and logged; it can never crash the
 * app, and it is NOT auto-disabled — its hooks simply fail until the
 * plugin is fixed or removed (per the v1.3 power/behavior rules).
 * A plugin whose source fails to EVALUATE is remembered as broken for
 * the session so a syntax-broken plugin is not re-evaluated on every
 * message; reinstalling or toggling clears that.
 *
 * Storage layout:
 * - prefs "amingram_jsplugins": "order" (JSONArray of ids — the
 *   plugin order; movePlugin/movePluginTo/reorder rewrite it),
 *   "meta_<id>" (JsPlugin JSON), logs under "log_<id>" (VaultStore),
 *   "bundled_seen" (BundledPlugins' pre-install delivery set),
 *   "bundled_user_deleted" (BundledPlugins' explicit v2.0 deletion
 *   set), "menu_config_v1" (AminMenuConfig's attach-menu config).
 * - code: app-private files dir, jsplugins/<id>.js.
 * - per-plugin storage: prefs "amingram_jsstore_<id>" — this one
 *   file holds BOTH amin.storage values and the hub-written
 *   SETTINGS values (storageSetRaw). delete() removes the whole
 *   file, so no setting or storage value survives a delete.
 */
public final class JsPluginManager {

    public static final String PREFS = "amingram_jsplugins";
    /** Plugin platform API level of this build (A9). Plugins
     *  negotiate via @minPlatform in their manifest and (via the
     *  bridge) amin.platform; installs never block on it. */
    public static final int PLATFORM_API_VERSION = 2;
    private static final String KEY_ORDER = "order";
    private static final String KEY_META_PREFIX = "meta_";
    private static final String KEY_LOG_PREFIX = "log_";
    private static final String KEY_ENGINE = "engine_enabled";
    private static final int LOG_CAP = 500;

    private static final List<JsPlugin> cache = new ArrayList<>();
    private static boolean cacheLoaded;

    /** Runtimes are created/dropped on the dedicated plugin thread,
     *  but runtimeState() reads the map from the UI thread — a plain
     *  HashMap raced there (audit P1-8). The synchronized map makes
     *  every single operation safe; ensureRuntime additionally
     *  guards its get-then-put so check-and-create stays atomic. */
    private static final Map<String, Runtime> runtimes =
            Collections.synchronizedMap(new HashMap<>());
    private static final Set<String> broken = Collections.synchronizedSet(new HashSet<>());

    /** The single Rhino context factory (A8): installs the
     *  instruction observer that enforces the per-hook budget. */
    private static final AminContextFactory CONTEXT_FACTORY = new AminContextFactory();
    /** Per-hook JS execution budget in wall-clock ms (A8). */
    private static final long HOOK_BUDGET_MS = 5000L;

    /** Session suspension set (A7/A8): plugins paused by the error
     *  storm watchdog. Distinct from `enabled` (the user's toggle
     *  is never touched) and from `broken` (load failure). Cleared
     *  by resumePlugin() or a successful reinstall. */
    private static final Set<String> suspended = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, String> suspendReasons =
            Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, ErrorStormTracker> stormTrackers = new HashMap<>();

    /** Plugins whose onMigrate is currently running (A6). Dispatch
     *  entries skip these so no hook of new code runs mid-migration. */
    private static final Set<String> migrating = Collections.synchronizedSet(new HashSet<>());

    /** Last settings key written while a plugin had no live runtime
     *  (A6): delivered once as onSettingsChanged at next warm-up. */
    private static final Map<String, String> pendingSettingKeys =
            Collections.synchronizedMap(new HashMap<>());

    /** Account the current plugin execution belongs to. -1 until
     *  the first dispatch (audit P1-9): the old default of 0 made
     *  pre-dispatch bridge lookups silently target account slot 0,
     *  which is a valid account and may not be the selected one. */
    private static volatile int dispatchAccount = -1;

    private static final ClassShutter CLASS_SHUTTER = new ClassShutter() {
        @Override
        public boolean visibleToScripts(String fullClassName) {
            return fullClassName.equals(AminBridge.class.getName())
                    || fullClassName.equals(AminBridge.Storage.class.getName())
                    || fullClassName.equals(AminBridge.WithGoogle.class.getName())
                    || fullClassName.equals(AminBridge.GoogleAccess.class.getName())
                    || fullClassName.equals(AminBridge.WithFile.class.getName())
                    || fullClassName.equals(AminBridge.WithGoogleFile.class.getName())
                    // Platform v2 bridge family (A9): act-capability
                    // variants + the amin.platform info object. Named
                    // as literals because these classes are added by
                    // the bridge layer; the shutter must allow them
                    // by exact name once they exist.
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$WithAct")
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$WithGoogleAct")
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$WithFileAct")
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$WithGoogleFileAct")
                    || fullClassName.equals("org.telegram.messenger.amin.plugins.js.AminBridge$PlatformInfo")
                    // Platform Phase B access objects (§1.4/§1.6/
                    // §1.9/§1.10): nested classes on the bridge,
                    // assigned only when the gating capability is
                    // present (db is the base surface, ungated).
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

    /** The one dedicated plugin thread (see the class javadoc's
     *  execution-model note). Lazily started on first use. */
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

    /** The account bridge calls resolve against: the dispatching
     *  account once any dispatch has run, the user's selected
     *  account before that — never a raw -1 and never a hardcoded
     *  slot 0 (audit P1-9). */
    public static int dispatchAccount() {
        int account = dispatchAccount;
        return account >= 0 ? account : UserConfig.selectedAccount;
    }

    public static String storagePrefsName(String pluginId) {
        return "amingram_jsstore_" + pluginId;
    }

    // ------------------------------------------------------------------
    // Metadata store
    // ------------------------------------------------------------------

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

    /** Master engine switch (hub → "Enable Plugins Engine", v1.3.3).
     *  When off, NO JS plugin runs: hasEnabledPlugins() is false,
     *  every dispatch entry returns immediately and the attach menu
     *  shows no plugin buttons. Default on. Pure prefs read. */
    public static boolean isEngineEnabled(Context context) {
        if (context == null) {
            return false;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENGINE, true);
    }

    public static void setEngineEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENGINE, enabled).apply();
    }

    public static boolean hasEnabledPlugins(Context context) {
        if (!isEngineEnabled(context)) {
            return false;
        }
        // Opt pass (2026-10-22): scan the meta cache in place.
        // This gate runs on Telegram's update thread for every
        // update batch (AminHooks.onRawUpdates); the old body went
        // through plugins(), which allocates a full copy of the
        // plugin list on every call.
        synchronized (JsPluginManager.class) {
            loadCache(context);
            for (JsPlugin plugin : cache) {
                if (plugin.enabled) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Attach menu (patch 0014) + menu configuration (v2.0)
    // ------------------------------------------------------------------

    /**
     * One row of the attach-menu configuration, as consumed by the
     * hub's menu-customization UI. {@link #icon} and {@link #label}
     * are OVERRIDES: null/"" means "use the plugin's manifest
     * default" (@icon / @menuLabel, else the plugin name / the
     * default icon). Use {@link #resolvedLabel()} /
     * {@link #resolvedIcon()} for the effective values.
     *
     * Icon values are specs everywhere in the plugin system
     * (manifest @icon, this override, and menuPlugins row[2]):
     * either a set name — download, mail, sparkles, dice, text,
     * link, music, star, bolt, camera, globe, gear — or
     * "emoji:&lt;glyph&gt;". Patch 0014 maps set names to
     * GlassTabView.TabAnimation values and prepends emoji glyphs
     * to the label.
     */
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

        /** Effective label: override, else @menuLabel, else name. */
        public String resolvedLabel() {
            if (label != null && !label.isEmpty()) {
                return label;
            }
            return plugin != null ? plugin.defaultMenuLabel() : "";
        }

        /** Effective icon spec: override, else manifest @icon,
         *  else "" (patch 0014 renders its default, ARTICLE). */
        public String resolvedIcon() {
            if (icon != null && !icon.isEmpty()) {
                return icon;
            }
            return plugin != null ? plugin.defaultMenuIcon() : "";
        }

        /** {id, resolvedLabel, resolvedIcon} — the patch-0014 row. */
        public String[] toArray() {
            return new String[]{
                    plugin != null ? plugin.id : "", resolvedLabel(), resolvedIcon()};
        }
    }

    private static boolean isMenuCapable(JsPlugin plugin) {
        return plugin.hasMenu && plugin.hasCapability("menu");
    }

    /** Enabled plugins that belong in the chat attach menu, as
     *  {id, label, iconSpec} rows (indices 0/1 are what patch 0014
     *  has always consumed; index 2 is the v2.0 icon spec). Honors
     *  the AminMenuConfig configuration (order, inclusion, icon /
     *  label overrides); with no config stored, falls back to all
     *  enabled menu plugins in plugin order with manifest
     *  defaults. Menu plugins installed after the config was saved
     *  are appended with defaults instead of being hidden.
     *  Pure prefs read. */
    public static List<String[]> menuPlugins(Context context) {
        List<String[]> out = new ArrayList<>();
        for (MenuEntry entry : resolvedMenuEntries(context)) {
            out.add(entry.toArray());
        }
        return out;
    }

    /** Typed twin of {@link #menuPlugins}: the resolved, included
     *  entries actually shown in the attach menu. */
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

    /** The effective menu configuration for editing (hub UI): one
     *  MenuEntry per installed menu-capable plugin (enabled or
     *  not), in configured order first, then plugin order for
     *  plugins the config doesn't mention. icon/label are the
     *  stored OVERRIDES ("" = manifest default), included defaults
     *  to true. Mutate via setMenuIncluded / setMenuIcon /
     *  setMenuLabel / moveMenuPlugin, which materialize and persist
     *  this whole list. */
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

    /** Includes/excludes one plugin in the attach menu. Materializes
     *  the effective config first, so excluding one plugin from the
     *  fallback state doesn't disturb the others. No-op for
     *  unknown / non-menu plugins. */
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

    /** Sets a plugin's menu icon override (a set name or
     *  "emoji:&lt;glyph&gt;"); null/"" clears it back to the
     *  manifest @icon / default. */
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

    /** Sets a plugin's menu label override; null/"" clears it back
     *  to the manifest @menuLabel / plugin name. */
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

    /** Moves a plugin within the menu configuration by delta
     *  positions (negative = up). Returns true when it moved. */
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

    /** Clears the whole menu configuration, restoring the
     *  all-enabled fallback. */
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
                        cache.add(plugin);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // v1.3.4: bundled plugins are pre-installed. loadCache is
        // the single funnel every plugin-system entry point passes
        // through exactly once per process, so this is the lightest
        // reliable auto-install point. cacheLoaded is already true
        // here, so the install path's own loadCache calls no-op and
        // cannot recurse; BundledPlugins guards itself too and only
        // ever delivers each bundled id once (deletions stick).
        try {
            BundledPlugins.ensureInstalled(context);
        } catch (Throwable ignored) {
        }
    }

    private static void persistMeta(Context context, JsPlugin plugin) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray order = new JSONArray();
        synchronized (JsPluginManager.class) {
            loadCache(context);
            for (JsPlugin p : cache) {
                order.put(p.id);
            }
        }
        prefs.edit()
                .putString(KEY_ORDER, order.toString())
                .putString(KEY_META_PREFIX + plugin.id, plugin.toJson().toString())
                .apply();
    }

    /** Rewrites the persisted plugin order from the in-memory cache.
     *  Caller must hold the JsPluginManager.class lock (or be in a
     *  synchronized method) and have mutated {@code cache} already. */
    private static void persistOrder(Context context) {
        JSONArray order = new JSONArray();
        for (JsPlugin p : cache) {
            order.put(p.id);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ORDER, order.toString()).apply();
    }

    // ------------------------------------------------------------------
    // Ordering (v2.0): the cache order IS the persisted "order" list.
    // Dispatch, the hub list and the attach-menu fallback all follow
    // it, so these moves are the single ordering control.
    // ------------------------------------------------------------------

    /** Moves a plugin by delta positions in the plugin order
     *  (negative = up). Persists immediately. Returns true when the
     *  position actually changed. (The hub's Move up/down menu
     *  calls this with -1/+1.) */
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

    /** Moves a plugin to an absolute index (clamped). Persists
     *  immediately. Returns true when the position changed. */
    public static synchronized boolean movePluginTo(Context context, String pluginId, int newIndex) {
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
        int to = Math.max(0, Math.min(cache.size() - 1, newIndex));
        if (to == from) {
            return false;
        }
        JsPlugin plugin = cache.remove(from);
        cache.add(to, plugin);
        persistOrder(context);
        return true;
    }

    /** Applies a full ordering: ids in orderedIds come first in
     *  that sequence; unknown ids are ignored and installed plugins
     *  missing from the list keep their relative order at the end.
     *  Persists immediately. */
    public static synchronized void reorder(Context context, List<String> orderedIds) {
        if (context == null || orderedIds == null) {
            return;
        }
        loadCache(context);
        List<JsPlugin> next = new ArrayList<>();
        for (String id : orderedIds) {
            for (JsPlugin plugin : cache) {
                if (plugin.id.equals(id) && !next.contains(plugin)) {
                    next.add(plugin);
                }
            }
        }
        for (JsPlugin plugin : cache) {
            if (!next.contains(plugin)) {
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

    /** Full install path shared by file import, paste-code and the
     *  bundled examples: manifest validation → Rhino compile check →
     *  probe evaluation (SETTINGS schema + hook detection) → store.
     *  Returns null on success, or a user-readable error. */
    public static String install(Context context, String source) {
        return installWithSource(context, source, "paste");
    }

    /** Install with the source recorded in meta (§2.7):
     *  bundled | file | paste | catalog | pack | editor. */
    public static String installWithSource(Context context, String source, String sourceTag) {
        try {
            return installInternal(context, source,
                    sourceTag == null || sourceTag.isEmpty() ? "paste" : sourceTag);
        } catch (Throwable t) {
            // Installing a plugin must NEVER take the app down (field
            // report, v1.3: an engine-level Error escaped install() and
            // closed the app). Any failure becomes a readable error.
            return "Plugin install failed: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
        }
    }

    /** Async install for UI callers (audit P1-7): the manifest
     *  check + Rhino compile + probe evaluation run installInternal's
     *  whole top level, and the only bound on a hostile plugin is
     *  the 5 s hook budget — on the UI thread that is a multi-second
     *  freeze on the very screen where the user decides whether to
     *  trust the plugin. This variant runs the identical validated
     *  path on the plugin thread and delivers the result (null =
     *  success, else the same user-readable error installWithSource
     *  returns) on the UI thread. */
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

    /** Semver check (§2.1): X.Y or X.Y.Z, all-numeric parts. */
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

    /** Numeric semver compare (§2.1): missing parts count as 0;
     *  non-numeric parts count as 0 (a malformed version never
     *  crashes an update check). Returns -1/0/1. */
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

    /** Full SHA-256 hex of a source string (UTF-8). */
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

    /** The short fingerprint recorded in meta and shown to the
     *  user (§2.7): the first 12 hex chars of the SHA-256. */
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
        // Dependency gate (§2.4): declared @depends must be installed
        // (and new enough) BEFORE this plugin lands — the failure
        // names the dependency so the user can act on it.
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
            // Probe-evaluate in a throwaway scope to read SETTINGS and
            // detect which hooks exist. onLoad is NOT called here.
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
            // Data-version bookkeeping (A6): a fresh id (or a legacy
            // meta that never recorded one) adopts the declared
            // version silently; a real reinstall keeps the stored
            // version so warm-up can run onMigrate when the
            // declared version has moved past it.
            plugin.dataVersionStored = (existing == null || existing.dataVersionStored <= 0)
                    ? plugin.dataVersion : existing.dataVersionStored;
            // Watchdog counters are per-day state, not code state:
            // they survive a reinstall (suspension itself does not —
            // see the success path below).
            plugin.errDay = existing != null && existing.errDay != null ? existing.errDay : "";
            plugin.errCount = existing != null ? existing.errCount : 0;
            // Settings schema (A5): strict for ids this install is
            // the first record of (bundled deliveries of NEW ids
            // included), loose for reinstalls of a known id.
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
                // Declared settings but no SETTINGS array — not fatal.
                appendLog(plugin.id, "[info] declares 'settings' but defines no SETTINGS array");
            }
            // Write the code file first, then swap metadata in.
            File file = codeFile(context, plugin.id);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            // Rollback copy (design §2.1): before an EXISTING install's
            // code is overwritten, keep the old file as <id>.prev.js.
            // This is what makes in-app code editing fearless — the
            // editor can restore the previous version in one tap.
            // Best-effort: a failed backup never blocks the install.
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
            }
            persistMeta(context, plugin);
            // A successful install of a bundled id (any path) clears
            // its user-deleted marker and marks it delivered, so a
            // reinstalled bundled plugin stays reinstalled.
            BundledPlugins.onInstalled(context, plugin.id);
            appendLog(plugin.id, "[info] installed v" + plugin.version + " by " + plugin.author);
            if (plugin.minPlatform > PLATFORM_API_VERSION) {
                appendLog(plugin.id, "[warn] " + plugin.name + " wants platform v"
                        + plugin.minPlatform + "; this build is v" + PLATFORM_API_VERSION
                        + " — installing anyway");
            }
            broken.remove(plugin.id);
            // A successful (re)install is the fix path: it also
            // clears any storm suspension for this id.
            suspended.remove(plugin.id);
            suspendReasons.remove(plugin.id);
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
            disableRuntime(plugin.id);
        }
    }

    /** Disable path (A6): onDisable runs on the plugin thread
     *  BEFORE the runtime is dropped, inside the same queued
     *  runnable, so no dispatch can slip between the hook and the
     *  drop. A throwing onDisable is funnelled by callHook and
     *  never blocks the disable. */
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

    /**
     * TRUE delete (v2.0): removes every trace of the plugin, so no
     * ghost data can resurface on a later reinstall of the same id:
     * - the in-memory cache entry and its slot in the persisted
     *   plugin order;
     * - meta_<id> and the log (log_<id>, removed directly and via
     *   VaultStore, its writer);
     * - the code file jsplugins/<id>.js;
     * - the ENTIRE per-plugin storage prefs file
     *   ("amingram_jsstore_<id>") — that file holds both
     *   amin.storage values AND the hub-written SETTINGS values
     *   (storageSetRaw writes there), so deleting the file removes
     *   storage and settings wherever they are stored;
     * - the plugin's attach-menu configuration entry
     *   (AminMenuConfig), so a reinstall doesn't inherit stale
     *   inclusion/order/icon/label state;
     * - the live runtime and the session "broken" mark.
     * For a bundled id it also records the explicit user deletion
     * (BundledPlugins.markUserDeleted) — that marker is the ONE
     * deliberate survivor: it stops auto-install from resurrecting
     * the plugin, and the library keeps offering it for a manual
     * reinstall (which clears the marker).
     * Works by id even when the plugin is not in the cache, so
     * orphaned meta/storage ghosts can be purged too.
     */
    public static void delete(Context context, String pluginId) {
        if (context == null || pluginId == null || pluginId.isEmpty()) {
            return;
        }
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
        // Phase B data tiers die with the plugin too (true delete
        // stays total): amin.db rows, the amin.files sandbox, and
        // any durable-copied settings files.
        PluginDb.deleteAllForPlugin(pluginId);
        PluginFiles.deleteAllForPlugin(context, pluginId);
        deletePluginSettingsDir(context, pluginId);
        deleteStoragePrefs(context, pluginId);
        AminMenuConfig.remove(context, pluginId);
        BundledPlugins.markUserDeleted(context, pluginId);
        dropRuntime(pluginId);
        broken.remove(pluginId);
        suspended.remove(pluginId);
        suspendReasons.remove(pluginId);
        migrating.remove(pluginId);
        pendingSettingKeys.remove(pluginId);
        synchronized (stormTrackers) {
            stormTrackers.remove(pluginId);
        }
    }

    /** The durable-copy directory for `file` settings values
     *  (§1.5): filesDir/amingram/plugin-settings/<id>/. */
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

    /** Hub Reload (§2.2): drop the runtime and re-warm the SAME
     *  code — restarts a wedged runtime and re-runs onLoad. A tap,
     *  never a timer. */
    public static void reloadPlugin(Context context, final String pluginId) {
        postToPluginThread(() -> runtimes.remove(pluginId));
        warmUp(pluginId, false);
        appendLog(pluginId, "[info] reloaded from the hub");
    }

    /** Runtime state for the Developer section (§3.5):
     *  "suspended" / "broken" / "loaded" / "not loaded". */
    public static String runtimeState(String pluginId) {
        if (suspended.contains(pluginId)) {
            return "suspended";
        }
        if (broken.contains(pluginId)) {
            return "broken";
        }
        // runtimes is a synchronized map (P1-8): containsKey is
        // atomic on its own — the class-monitor wrapper this read
        // used to take was a lock the plugin thread never holds.
        return runtimes.containsKey(pluginId) ? "loaded" : "not loaded";
    }

    public static boolean isRuntimeLoaded(String pluginId) {
        return "loaded".equals(runtimeState(pluginId));
    }

    /** Removes a per-plugin storage prefs file completely — values,
     *  in-process cache and the XML file itself. minSdk is 23 and
     *  deleteSharedPreferences is API 24, so: clear() first (works
     *  everywhere and empties any live cached instance), then the
     *  platform delete where available, then a direct file delete
     *  as belt-and-braces. */
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

    /** The rollback copy kept by install() before an update
     *  overwrites the code file; null when no previous version
     *  exists (never updated, or the plugin was deleted since). */
    public static String readPrevCode(Context context, String pluginId) {
        return readCodeFile(prevCodeFile(context, pluginId));
    }

    private static String readCodeFile(File file) {
        try {
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
    // Logs
    // ------------------------------------------------------------------

    /** Live log tail (Phase B, §3.5): registered listeners are
     *  notified (on the UI thread) whenever a line is appended, so
     *  an open log view refreshes event-driven — never by polling.
     *  Listeners must unregister when their page closes. */
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
            if (context == null) {
                return;
            }
            String flat = (text == null ? "" : text).replace('\n', ' ');
            if (flat.length() > 1000) {
                flat = flat.substring(0, 1000);
            }
            String stamp = new java.text.SimpleDateFormat("MMM d HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            List<String> lines = VaultStore.getAll(context, PREFS, KEY_LOG_PREFIX + pluginId);
            lines.add(stamp + " — " + flat);
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

    // ------------------------------------------------------------------
    // Settings storage access for the hub (same backing store the
    // bridge's amin.storage uses).
    // ------------------------------------------------------------------

    public static String storageGetRaw(Context context, String pluginId, String key) {
        return context.getSharedPreferences(storagePrefsName(pluginId), Context.MODE_PRIVATE)
                .getString(key, null);
    }

    public static void storageSetRaw(Context context, String pluginId, String key, String value) {
        context.getSharedPreferences(storagePrefsName(pluginId), Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
    }

    // ------------------------------------------------------------------
    // Watchdog (A7/A8) + settings-change notification (A6)
    // ------------------------------------------------------------------

    private static String todayString() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
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

    /**
     * The single error funnel (A7): every hook / load / probe /
     * callback failure routes through here. Logs in the existing
     * "[error] &lt;hook&gt;: &lt;throwable&gt;" format (a budget
     * expiry gets its own wording), increments the plugin's
     * persisted per-day counter, and feeds its storm tracker —
     * reaching the storm threshold session-suspends the plugin
     * (A8). The user's enabled toggle is never touched.
     */
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
                    persistMeta(context, plugin);
                }
            }
        } catch (Throwable ignored) {
        }
        if (trackerFor(pluginId).record(System.currentTimeMillis())
                && !suspended.contains(pluginId)) {
            suspended.add(pluginId);
            suspendReasons.put(pluginId,
                    "error storm: " + ErrorStormTracker.THRESHOLD + "+ hook errors in 60s");
            appendLog(pluginId, "[error] suspended — error storm ("
                    + ErrorStormTracker.THRESHOLD
                    + "+ errors in 60s); resume from the plugin page");
        }
    }

    /** Hook errors recorded for this plugin today (hub badge, A7). */
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

    /** True while the plugin is storm-suspended for this session. */
    public static boolean isSuspended(String pluginId) {
        return pluginId != null && suspended.contains(pluginId);
    }

    /** Why the plugin is suspended ("" when it is not). */
    public static String suspendReason(String pluginId) {
        String reason = pluginId == null ? null : suspendReasons.get(pluginId);
        return reason == null ? "" : reason;
    }

    /**
     * Hub Resume (A7/A8): clears the suspension, the storm tracker
     * and today's error counter (per the blueprint, "Resume clears
     * counters"), then re-warms the plugin if it is enabled. The
     * onEnable hook is NOT re-fired — the plugin was never disabled.
     */
    public static void resumePlugin(Context context, String pluginId) {
        if (pluginId == null) {
            return;
        }
        suspended.remove(pluginId);
        suspendReasons.remove(pluginId);
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

    /**
     * Hub settings-write notification (A6). When the plugin has a
     * live runtime, onSettingsChanged(key, newValue, oldValue)
     * runs on the plugin thread; otherwise the key is remembered
     * and the hook is delivered once at the next warm-up with
     * oldValue == newValue == the current stored value.
     */
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
        cx.setOptimizationLevel(-1); // interpreted: required on Android
        cx.setLanguageVersion(org.mozilla.javascript.Context.VERSION_ES6);
        cx.setClassShutter(CLASS_SHUTTER);
        return cx;
    }

    private static ScriptableObject newScope(org.mozilla.javascript.Context cx, String pluginId,
                                             java.util.Collection<String> capabilities) {
        ScriptableObject scope = cx.initStandardObjects();
        // Defense in depth on top of the ClassShutter: the standard
        // scope has no Java access, but strip the names if present.
        String[] banned = {"Packages", "java", "javax", "JavaAdapter", "importClass", "getClass"};
        for (String name : banned) {
            ScriptableObject.deleteProperty(scope, name);
        }
        // Capability gating is structural: only plugins whose
        // manifest declares `google` get amin.google, and only
        // plugins declaring `file` get amin.sendFile. The bridge
        // variant is selected by the declared capability pair —
        // on a variant without the wrapper, the method/property
        // does not exist at all.
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

    /** Delivers an async bridge result to a plugin callback
     *  function (v1.3.5, built for amin.google's token / sign-in
     *  callbacks). Runs on the plugin thread with a fresh Rhino
     *  context — the same isolation pattern as every dispatch
     *  entry (v1.3.2 audit): enterContext INSIDE the guard,
     *  Context.exit() only when a context was entered. The
     *  function's own parent scope is the invocation scope, so a
     *  callback closes over the plugin scope it was defined in.
     *  A deleted plugin's pending callbacks are dropped. */
    public static void invokePluginCallback(final String pluginId, final Object fnObject,
                                            final Object value) {
        invokePluginCallback(pluginId, fnObject, value, -1);
    }

    /** Account-carrying variant (audit P1-9): {@code account} is
     *  the account captured when the async bridge call was MADE
     *  (on the plugin thread, where dispatchAccount was that
     *  call's account). The posted runnable adopts it before the
     *  callback runs, so the callback's own bridge calls resolve
     *  against the account the operation belongs to — not
     *  whichever account most recently dispatched a message.
     *  -1 = unknown: resolve to the selected account at delivery. */
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

    /** Builds the single argument for {@link #invokePluginCallbackWithArg}
     *  (A3 support): invoked INSIDE the callback's guarded context
     *  on the plugin thread, so implementations can create Rhino
     *  values (objects/arrays) in the right scope. */
    public interface CallbackArgFactory {
        Object build(org.mozilla.javascript.Context cx, Scriptable scope);
    }

    /**
     * Twin of {@link #invokePluginCallback} whose single callback
     * argument is produced lazily by {@code factory} inside the
     * guard (A3: async bridge results that are structured values,
     * e.g. an HTTP result object, are built with the live context
     * and the callback's own scope instead of being captured from
     * another thread). Same contract otherwise: plugin thread,
     * fresh context inside the guard, dropped when the plugin was
     * deleted or is storm-suspended, failures funnelled through
     * noteHookError, hook budget applied.
     */
    public static void invokePluginCallbackWithArg(final String pluginId, final Object fnObject,
                                                   final CallbackArgFactory factory) {
        invokePluginCallbackWithArg(pluginId, fnObject, factory, -1);
    }

    /** Account-carrying variant (audit P1-9) — same contract as
     *  {@link #invokePluginCallback(String, Object, Object, int)}. */
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

    /** Reads + validates the probe scope's SETTINGS array via
     *  {@link SettingsSchema} (A5). Fills plugin.settings and
     *  returns the validation ERRORS (empty = ok); warnings are
     *  appended to {@code warnings}. {@code strict} is true for a
     *  first install of the id, false for a reinstall. */
    private static List<String> readSettings(ScriptableObject scope, JsPlugin plugin,
                                             boolean strict, List<String> warnings) {
        Object raw = ScriptableObject.getProperty(scope, "SETTINGS");
        return SettingsSchema.parseAndValidate(raw, plugin.settings, strict, warnings);
    }

    /** PLUGIN_PAGE probe parse (§1.8a): fills plugin.pageRows from
     *  the probe scope's PLUGIN_PAGE array. Lenient by design —
     *  malformed rows are skipped, unknown types dropped; the
     *  renderer only ever sees clean rows. Caps at 24 rows. */
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

    private static void warmUp(final String pluginId) {
        warmUp(pluginId, false);
    }

    /** Warms the plugin's runtime (load + onLoad + migration +
     *  pending settings delivery, all inside ensureRuntime). With
     *  {@code fireOnEnable} (the setEnabled / install paths, A6),
     *  onEnable runs right after that sequence completes.
     *  Storm-suspended plugins are not warmed at all. */
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

    /** Loads (once) and returns the plugin's runtime, calling onLoad
     *  on first successful load. Returns null when the plugin cannot
     *  run (missing/broken source — logged once per session). */
    private static Runtime ensureRuntime(org.mozilla.javascript.Context cx,
                                         Context context, JsPlugin plugin) {
        // Check-and-create under the map's own monitor (P1-8): the
        // build itself (source eval + onLoad) stays OUTSIDE the
        // lock so a UI-thread runtimeState() read never waits on a
        // plugin load; the put is re-checked inside the lock, so a
        // runtime built concurrently can never be overwritten or
        // have onLoad run twice.
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
            appendLog(plugin.id, "[error] plugin file missing");
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

    /**
     * Data migration (A6): when the installed code declares a
     * @dataVersion past the stored one (and a version was actually
     * stored before), onMigrate(stored, declared) runs right after
     * onLoad — before onEnable, any pending settings delivery, and
     * any dispatched hook of the new code (dispatch entries check
     * the {@code migrating} gate; this sequence itself is
     * synchronous on the plugin thread). The stored version
     * advances only when the hook returns normally; a throwing
     * migration is funnelled as an error, the stored version stays,
     * and the plugin still runs. A plugin without onMigrate has
     * nothing that could migrate its data, so the stored version
     * simply adopts the declared one.
     */
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

    /** Delivers a settings change that arrived while the plugin
     *  had no live runtime (A6): fired once, right after onLoad
     *  (and after any migration), with oldValue == newValue ==
     *  the currently stored value. */
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

    /** The one hook-invocation funnel: every dispatched and
     *  lifecycle hook (onLoad, onMessage, onCommand, onMenuAction,
     *  onEnable, onDisable, onSettingsChanged) runs through here,
     *  so the A8 budget wrap and the A7 error funnel each live in
     *  exactly one place. (onMigrate is the exception — its
     *  success/failure drives the stored data version, so it is
     *  invoked, with the same wrap + funnel, by
     *  runMigrationIfNeeded.) */
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
    // Dispatch (called from AminHooks; work runs on the plugin thread)
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
        postToPluginThread(() -> {
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
                    Scriptable msg = buildMsg(cx, runtime.scope, account, message, fields);
                    Object result = callHook(cx, runtime, "onMessage", new Object[]{msg});
                    if (result != null && ScriptRuntime.toBoolean(result)) {
                        break; // handled: stops later plugins
                    }
                }
            } catch (Throwable ignored) {
            } finally {
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
        dispatchCommandInternal(account, message, parsed[0], parsed[1], null);
    }

    /** Own command sent from THIS device (SendMessagesHelper hook). */
    public static void dispatchOutgoingCommand(final SendMessagesHelper.SendMessageParams params) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || params == null || params.message == null
                || !isEngineEnabled(context)) {
            return;
        }
        String[] parsed = parseCommand(params.message);
        if (parsed == null) {
            return;
        }
        final int account = UserConfig.selectedAccount;
        final long peer = params.peer;
        final String text = params.message;
        final String command = parsed[0];
        final String args = parsed[1];
        postToPluginThread(() -> {
            dispatchAccount = account;
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
                    Scriptable ctx = buildCtx(cx, runtime.scope, msg, peer, selfId, command, args,
                            account, null, params.replyToMsg);
                    Object result = callHook(cx, runtime, "onCommand", new Object[]{ctx});
                    if (result != null && ScriptRuntime.toBoolean(result)) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    private static void dispatchCommandInternal(final int account, final TLRPC.Message message,
                                                final String command, final String args,
                                                Object unused) {
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
                    long chatId = MessageObject.getDialogId(message);
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
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    /** Context-menu dispatch (§1.8b): the user long-pressed a
     *  message and tapped this plugin's item. ctx = {chatId, msg}
     *  with msg in the standard buildMsg shape. Called from
     *  AminHooks.onPluginContextAction (patch 0020's click path). */
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
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    /** Plugin-page action dispatch (§1.8a): a PLUGIN_PAGE action
     *  row was tapped in the hub; onPluginAction(actionId) runs on
     *  the plugin thread with the standard isolation. */
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
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Relay (§1.10): plugin-to-plugin calls and event broadcast.
    // Values cross the boundary ONLY as JSON (deep-copied through
    // org.json by AminBridge.jsToJson/jsonToJs) — no Rhino object
    // is ever shared between two plugins' scopes.
    // ------------------------------------------------------------------

    /** Maximum relay call nesting (§1.10). */
    public static final int RELAY_MAX_DEPTH = 4;

    /** Depth of the relay dispatch currently executing on the
     *  plugin thread (0 = not inside a relay call). All relay work
     *  is confined to the plugin thread, so a plain static is the
     *  correct scope. */
    private static int relayDepth = 0;

    /** Delivers an immediate argument error to a relay callback
     *  (bridge-side validation failures: bad target/method/args). */
    public static void relayCallError(String callerId, Object callback, String message) {
        relayCallback(callerId, callback,
                org.json.JSONObject.quote(message == null ? "relay error" : message), null);
    }

    /** A manifest-level preview of an install, for the permission
     *  review screen (§3.2): everything the review shows that can
     *  be known WITHOUT running the probe — identity, declared
     *  capabilities/exports/topics/depends, the code fingerprint,
     *  and problems (bad manifest, @minPlatform, unmet @depends,
     *  non-semver @version). The real install still validates
     *  everything again (probe included). */
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
        /** Raw entries: "id" or "id@>=X.Y". */
        public java.util.List<String> depends = new java.util.ArrayList<>();
        public int minPlatform;
        public String fingerprint = "";
        public java.util.List<String> problems = new java.util.ArrayList<>();
        /** Capabilities the installed copy does NOT have yet
         *  (update diff, §3.2); empty for fresh installs. */
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

    /** amin.relay.call, entered from the bridge on the plugin
     *  thread: validates the target + its @exports, dispatches the
     *  PLUGIN_API method on the SAME thread (queued, so ordering
     *  is deterministic), and delivers (err, result) to the
     *  caller's callback — never throws. argsJson is a JSON array
     *  string produced by the bridge; callback may be null. */
    public static void relayCall(final String callerId, final String targetId,
                                 final String method, final String argsJson,
                                 final Object callback) {
        final int depth = relayDepth;
        if (depth >= RELAY_MAX_DEPTH) {
            relayCallback(callerId, callback,
                    "\"relay depth exceeded (max " + RELAY_MAX_DEPTH + ")\"", null);
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

    /** Delivers a relay (err, result) pair to the caller's JS
     *  callback fn(err, result): err is a string or null, result a
     *  JSON value or null. Both arrive as JSON text and are rebuilt
     *  inside the caller's own scope. */
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

    /** amin.relay.emit: fire-and-forget broadcast to every enabled
     *  plugin (other than the emitter) defining onEvent whose
     *  @topics include the topic or "*". */
    public static void relayEmit(final String callerId, final String topic,
                                 final String dataJson) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || topic == null || topic.isEmpty()) {
            return;
        }
        postToPluginThread(() -> {
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
                org.mozilla.javascript.Context cx = null;
                try {
                    cx = enterContext();
                    Runtime runtime = ensureRuntime(cx, context, plugin);
                    if (runtime == null) {
                        continue;
                    }
                    Object data = AminBridge.jsonToJs(
                            dataJson == null ? "null" : dataJson, cx, runtime.scope);
                    appendLog(plugin.id, "[relay] event '" + topic + "' <- " + callerId);
                    callHook(cx, runtime, "onEvent", new Object[]{topic, data});
                } catch (Throwable ignored) {
                } finally {
                    if (cx != null) {
                        org.mozilla.javascript.Context.exit();
                    }
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

    /** Eval console (§3.5): evaluates a snippet in the plugin's
     *  OWN runtime scope and reports the stringified result. The
     *  caller (the hub Developer section) owns the mitigations:
     *  Developer-mode gate, and this method itself writes every
     *  evaluation to the plugin's log before running it. Snippets
     *  are never stored anywhere. */
    public static void evaluateInPlugin(final String pluginId, final String snippet,
                                        final EvalResult callback) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            callback.onResult("Error: no context");
            return;
        }
        String flat = snippet == null ? "" : snippet.replace('\n', ' ');
        if (flat.length() > 200) {
            flat = flat.substring(0, 200) + "…";
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
                    ? text.substring(0, 200) + "…" : text));
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> callback.onResult(text));
        });
    }

    /** All storage key/value pairs of a plugin (storage inspector):
     *  the raw prefs strings amin.storage + settings share. */
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

    /** Clears a plugin's data tiers (storage prefs + db rows) —
     *  the storage inspector's "clear all data". Files and settings
     *  file copies are NOT touched here; true delete handles those. */
    public static void clearPluginData(Context context, String pluginId) {
        try {
            context.getSharedPreferences(storagePrefsName(pluginId), Context.MODE_PRIVATE)
                    .edit().clear().apply();
        } catch (Throwable ignored) {
        }
        PluginDb.deleteAllForPlugin(pluginId);
        appendLog(pluginId, "[info] data cleared from the Developer tools");
    }

    /** Enabled, non-blocked plugins declaring {@code capability},
     *  in hub order. Built straight from the meta cache under the
     *  class lock (opt pass, 2026-10-22): the dispatch entries used
     *  to iterate plugins() — a full defensive copy of the list —
     *  only to filter it down, on the per-message dispatch path. */
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
    // JS value builders
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Lazy rich lookups (A1, blueprint §1.1): the public statics the
    // bridge's amin.getChatInfo / getUserInfo / getMessage delegate
    // to. They run on the calling (plugin) thread during JS
    // execution, so a Rhino context is already current; objects are
    // built in the plugin runtime's scope when it can be resolved,
    // else in a fresh standard scope — they are plain data either
    // way. Account follows the manager's dispatch idiom:
    // dispatchAccount (set by every dispatch entry before hooks
    // run), falling back to UserConfig.selectedAccount.
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
        return cx.initStandardObjects();
    }

    /** {id, type, title, username, memberCount} for a dialog id,
     *  or null when the user/chat is not loaded. memberCount is
     *  the chat's participants_count; 0 for private chats. */
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

    /** {id, name, username, isBot, isPremium} for a user id, or
     *  null when the user is not loaded. */
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

    /** A buildMsg-shaped object for a message that is already
     *  loaded in MessagesController's maps, or null — the bound is
     *  contractual: unloaded messages are never fetched. */
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

    /** Finds a loaded message in MessagesController's maps: the
     *  per-dialog message list first, then the global by-id map
     *  (accepted only when its dialog matches). Null when not
     *  loaded. Shared by lookupMessage and ctx.replyTo. */
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

    /** The per-message values of {@link #buildMsg} that are
     *  immutable for a given (account, message): the peer lookups
     *  and display strings. Opt pass (2026-10-22): dispatch used to
     *  recompute these inside buildMsg for EVERY target plugin —
     *  sender-name resolution (a string-building getUserName),
     *  chat/sender controller lookups, forward-origin resolution —
     *  so an incoming message cost N full constructions for N
     *  observing plugins. Dispatch entries now compute the fields
     *  once per event and each plugin's Scriptable is populated
     *  from them; only the scope-bound object itself (and the
     *  media descriptor) is still built per plugin. */
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
        // Rich context (Platform v2, A1): the chat and sender user
        // are resolved once here and reused for channel / chatType /
        // chatTitle / senderUsername / senderIsBot, keeping the
        // per-message construction allocation-light.
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
        if (message.media != null) {
            msg.put("media", msg, buildMediaDescriptor(cx, scope, message.media));
        } else {
            msg.put("media", msg, null);
        }
        return msg;
    }

    /** Forward origin display name (A1): the header's from_name
     *  when present, else the resolved name of its from peer,
     *  else the post author. Name only — no peer objects leak. */
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

    /** The msg.media descriptor (A1): built purely by instanceof
     *  checks on the already-loaded media object — no downloads,
     *  no FileLoader paths. Unknown media degrades to "other". */
    private static Scriptable buildMediaDescriptor(org.mozilla.javascript.Context cx,
                                                   Scriptable scope,
                                                   TLRPC.MessageMedia media) {
        Scriptable desc = cx.newObject(scope);
        String kind = "other";
        String fileName = "";
        String mimeType = "";
        long sizeBytes = 0;
        long durationSec = 0;
        long width = 0;
        long height = 0;
        try {
            if (media instanceof TLRPC.TL_messageMediaPhoto) {
                kind = "photo";
                TLRPC.Photo photo = media.photo;
                if (photo != null && photo.sizes != null) {
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
            } else if (media instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.Document doc = media.document;
                if (doc != null) {
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
                // Other media shapes that still carry a photo.
                kind = "photo";
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
        return desc;
    }

    private static String documentFileName(TLRPC.Document doc) {
        if (doc.file_name_fixed != null && !doc.file_name_fixed.isEmpty()) {
            return doc.file_name_fixed;
        }
        return doc.file_name == null ? "" : doc.file_name;
    }

    /**
     * Command context. When the source message is a reply
     * (reply_to id != 0 — from {@code sourceMessage} on the update
     * path, or {@code replyTarget} on the outgoing path where the
     * send params already carry the target MessageObject),
     * ctx.replyTo is set to {text, caption, senderName, chatId,
     * messageId} describing the target: resolved through the same
     * loaded-message lookup as lookupMessage; an unresolved target
     * still yields the object, with empty text/senderName. caption
     * is the target's text when the target carries media, else "".
     */
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

    /** "/shout hello world" → {"shout", "hello world"}; also strips a
     *  trailing "@botname" from the command token. Null when the text
     *  is not a command. */
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
        command = command.toLowerCase();
        if (command.isEmpty()) {
            return null;
        }
        return new String[]{command, args};
    }
}
