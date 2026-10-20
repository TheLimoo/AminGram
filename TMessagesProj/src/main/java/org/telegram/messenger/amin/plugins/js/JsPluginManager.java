package org.telegram.messenger.amin.plugins.js;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
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
    private static final String KEY_ORDER = "order";
    private static final String KEY_META_PREFIX = "meta_";
    private static final String KEY_LOG_PREFIX = "log_";
    private static final String KEY_ENGINE = "engine_enabled";
    private static final int LOG_CAP = 200;

    private static final List<JsPlugin> cache = new ArrayList<>();
    private static boolean cacheLoaded;

    /** Runtimes live only on the dedicated plugin thread (and are
     *  dropped via runnables posted to it), keeping Rhino
     *  single-threaded. */
    private static final Map<String, Runtime> runtimes = new HashMap<>();
    private static final Set<String> broken = Collections.synchronizedSet(new HashSet<>());

    private static volatile int dispatchAccount;

    private static final ClassShutter CLASS_SHUTTER = new ClassShutter() {
        @Override
        public boolean visibleToScripts(String fullClassName) {
            return fullClassName.equals(AminBridge.class.getName())
                    || fullClassName.equals(AminBridge.Storage.class.getName())
                    || fullClassName.equals(AminBridge.WithGoogle.class.getName())
                    || fullClassName.equals(AminBridge.GoogleAccess.class.getName())
                    || fullClassName.equals(AminBridge.WithFile.class.getName())
                    || fullClassName.equals(AminBridge.WithGoogleFile.class.getName())
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

    public static int dispatchAccount() {
        return dispatchAccount;
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
        for (JsPlugin plugin : plugins(context)) {
            if (plugin.enabled) {
                return true;
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
        try {
            return installInternal(context, source);
        } catch (Throwable t) {
            // Installing a plugin must NEVER take the app down (field
            // report, v1.3: an engine-level Error escaped install() and
            // closed the app). Any failure becomes a readable error.
            return "Plugin install failed: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
        }
    }

    private static String installInternal(Context context, String source) {
        JsManifest manifest;
        try {
            manifest = JsManifest.parse(source);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        org.mozilla.javascript.Context cx = enterContext();
        try {
            try {
                cx.compileString(source, manifest.id + ".js", 1, null);
            } catch (Throwable t) {
                return "JavaScript error: " + t.getMessage();
            }
            // Probe-evaluate in a throwaway scope to read SETTINGS and
            // detect which hooks exist. onLoad is NOT called here.
            ScriptableObject probe = newScope(cx, manifest.id, manifest.capabilities);
            try {
                cx.evaluateString(probe, source, manifest.id + ".js", 1, null);
            } catch (Throwable t) {
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
            readSettings(probe, plugin);
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
            broken.remove(plugin.id);
            dropRuntime(plugin.id);
            if (plugin.enabled) {
                warmUp(plugin.id);
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
            warmUp(plugin.id);
        } else {
            dropRuntime(plugin.id);
        }
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
        deleteStoragePrefs(context, pluginId);
        AminMenuConfig.remove(context, pluginId);
        BundledPlugins.markUserDeleted(context, pluginId);
        dropRuntime(pluginId);
        broken.remove(pluginId);
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

    public static String readCode(Context context, String pluginId) {
        try {
            File file = codeFile(context, pluginId);
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
    // Rhino plumbing
    // ------------------------------------------------------------------

    private static org.mozilla.javascript.Context enterContext() {
        org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.enter();
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
        AminBridge bridge;
        if (hasGoogle && hasFile) {
            bridge = new AminBridge.WithGoogleFile(pluginId);
        } else if (hasGoogle) {
            bridge = new AminBridge.WithGoogle(pluginId);
        } else if (hasFile) {
            bridge = new AminBridge.WithFile(pluginId);
        } else {
            bridge = new AminBridge(pluginId);
        }
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
        if (!(fnObject instanceof Function)) {
            return;
        }
        final Function fn = (Function) fnObject;
        postToPluginThread(() -> {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || get(context, pluginId) == null) {
                return;
            }
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                Scriptable scope = fn.getParentScope();
                if (scope == null) {
                    return;
                }
                fn.call(cx, scope, scope, new Object[]{value});
            } catch (Throwable t) {
                appendLog(pluginId, "[error] async callback: " + t);
            } finally {
                if (cx != null) {
                    org.mozilla.javascript.Context.exit();
                }
            }
        });
    }

    private static void readSettings(ScriptableObject scope, JsPlugin plugin) {
        Object raw = ScriptableObject.getProperty(scope, "SETTINGS");
        if (!(raw instanceof NativeArray)) {
            return;
        }
        NativeArray array = (NativeArray) raw;
        for (int i = 0; i < array.getLength(); i++) {
            Object item = array.get(i, array);
            if (!(item instanceof Scriptable)) {
                continue;
            }
            Scriptable def = (Scriptable) item;
            JsPlugin.SettingDef setting = new JsPlugin.SettingDef();
            setting.key = stringProp(def, "key");
            setting.type = stringProp(def, "type");
            setting.title = stringProp(def, "title");
            Object defValue = def.get("def", def);
            setting.def = defValue == Scriptable.NOT_FOUND ? "" : defValue;
            if (setting.key == null || setting.key.isEmpty()) {
                continue;
            }
            if (!"bool".equals(setting.type)) {
                setting.type = "text";
            }
            if (setting.title == null || setting.title.isEmpty()) {
                setting.title = setting.key;
            }
            plugin.settings.add(setting);
        }
    }

    private static String stringProp(Scriptable obj, String key) {
        Object value = obj.get(key, obj);
        if (value == null || value == Scriptable.NOT_FOUND) {
            return null;
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
        postToPluginThread(() -> {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            JsPlugin plugin = get(context, pluginId);
            if (plugin == null || !plugin.enabled || !isEngineEnabled(context)) {
                return;
            }
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                ensureRuntime(cx, context, plugin);
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
        Runtime runtime = runtimes.get(plugin.id);
        if (runtime != null) {
            return runtime;
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
        runtime = new Runtime();
        runtime.plugin = plugin;
        try {
            runtime.scope = newScope(cx, plugin.id, plugin.capabilities);
            cx.evaluateString(runtime.scope, source, plugin.id + ".js", 1, null);
        } catch (Throwable t) {
            appendLog(plugin.id, "[error] load failed: " + t);
            broken.add(plugin.id);
            return null;
        }
        runtimes.put(plugin.id, runtime);
        if (!runtime.onLoadCalled) {
            runtime.onLoadCalled = true;
            callHook(cx, runtime, "onLoad", new Object[0]);
        }
        return runtime;
    }

    private static Object callHook(org.mozilla.javascript.Context cx, Runtime runtime,
                                   String name, Object[] args) {
        try {
            Object fn = ScriptableObject.getProperty(runtime.scope, name);
            if (fn instanceof Function) {
                return ((Function) fn).call(cx, runtime.scope, runtime.scope, args);
            }
        } catch (Throwable t) {
            appendLog(runtime.plugin.id, "[error] " + name + ": " + t);
            FileLog.e("AminGram plugin " + runtime.plugin.id + " " + name + " failed", t);
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
        final List<JsPlugin> targets = new ArrayList<>();
        for (JsPlugin plugin : plugins(context)) {
            if (plugin.enabled && plugin.hasCapability("observe")) {
                targets.add(plugin);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        postToPluginThread(() -> {
            dispatchAccount = account;
            org.mozilla.javascript.Context cx = null;
            try {
                cx = enterContext();
                for (JsPlugin plugin : targets) {
                    Runtime runtime = ensureRuntime(cx, context, plugin);
                    if (runtime == null) {
                        continue;
                    }
                    if (!(ScriptableObject.getProperty(runtime.scope, "onMessage") instanceof Function)) {
                        continue;
                    }
                    Scriptable msg = buildMsg(cx, runtime.scope, account, message);
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
                    Scriptable ctx = buildCtx(cx, runtime.scope, msg, peer, selfId, command, args);
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
                for (JsPlugin plugin : enabledCommandPlugins(context)) {
                    Runtime runtime = ensureRuntime(cx, context, plugin);
                    if (runtime == null) {
                        continue;
                    }
                    if (!(ScriptableObject.getProperty(runtime.scope, "onCommand") instanceof Function)) {
                        continue;
                    }
                    Scriptable msg = buildMsg(cx, runtime.scope, account, message);
                    long chatId = MessageObject.getDialogId(message);
                    long senderId = UserConfig.getInstance(account).getClientUserId();
                    Scriptable ctx = buildCtx(cx, runtime.scope, msg, chatId, senderId, command, args);
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

    private static List<JsPlugin> enabledCommandPlugins(Context context) {
        List<JsPlugin> out = new ArrayList<>();
        for (JsPlugin plugin : plugins(context)) {
            if (plugin.enabled && plugin.hasCapability("commands")) {
                out.add(plugin);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // JS value builders
    // ------------------------------------------------------------------

    private static Scriptable buildMsg(org.mozilla.javascript.Context cx, Scriptable scope,
                                       int account, TLRPC.Message message) {
        Scriptable msg = cx.newObject(scope);
        long chatId = MessageObject.getDialogId(message);
        long senderId = message.from_id != null
                ? DialogObject.getPeerDialogId(message.from_id) : chatId;
        putNum(msg, "id", message.id);
        putNum(msg, "chatId", chatId);
        putNum(msg, "senderId", senderId);
        putStr(msg, "senderName", resolveSenderName(account, senderId, chatId));
        putStr(msg, "text", message.message == null ? "" : message.message);
        msg.put("outgoing", msg, message.out ? Boolean.TRUE : Boolean.FALSE);
        boolean channel = false;
        if (message.peer_id != null && message.peer_id.channel_id != 0) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(message.peer_id.channel_id);
            channel = chat == null || !chat.megagroup;
        }
        msg.put("channel", msg, channel ? Boolean.TRUE : Boolean.FALSE);
        putNum(msg, "date", message.date);
        return msg;
    }

    private static Scriptable buildCtx(org.mozilla.javascript.Context cx, Scriptable scope,
                                       Scriptable msg, long chatId, long senderId,
                                       String command, String args) {
        Scriptable ctx = cx.newObject(scope);
        putNum(ctx, "chatId", chatId);
        putNum(ctx, "senderId", senderId);
        putStr(ctx, "command", command);
        putStr(ctx, "args", args);
        ctx.put("msg", ctx, msg);
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
