package org.telegram.messenger.amin.plugins.js;

import android.content.Context;
import android.content.SharedPreferences;

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
 * Execution model: all plugin code runs on Utilities.globalQueue (an
 * existing Telegram queue — no new threads, no timers, no polling).
 * Events are dispatched from Telegram's own update flow via AminHooks.
 * A plugin that throws is caught and logged; it can never crash the
 * app, and it is NOT auto-disabled — its hooks simply fail until the
 * plugin is fixed or removed (per the v1.3 power/behavior rules).
 * A plugin whose source fails to EVALUATE is remembered as broken for
 * the session so a syntax-broken plugin is not re-evaluated on every
 * message; reinstalling or toggling clears that.
 *
 * Storage layout:
 * - prefs "amingram_jsplugins": "order" (JSONArray of ids),
 *   "meta_<id>" (JsPlugin JSON), logs under "log_<id>" (VaultStore).
 * - code: app-private files dir, jsplugins/<id>.js.
 * - per-plugin storage: prefs "amingram_jsstore_<id>".
 */
public final class JsPluginManager {

    public static final String PREFS = "amingram_jsplugins";
    private static final String KEY_ORDER = "order";
    private static final String KEY_META_PREFIX = "meta_";
    private static final String KEY_LOG_PREFIX = "log_";
    private static final int LOG_CAP = 200;

    private static final List<JsPlugin> cache = new ArrayList<>();
    private static boolean cacheLoaded;

    /** Runtimes live only on the globalQueue thread (and are dropped
     *  via runnables posted to it), keeping Rhino single-threaded. */
    private static final Map<String, Runtime> runtimes = new HashMap<>();
    private static final Set<String> broken = new HashSet<>();

    private static volatile int dispatchAccount;

    private static final ClassShutter CLASS_SHUTTER = new ClassShutter() {
        @Override
        public boolean visibleToScripts(String fullClassName) {
            return fullClassName.equals(AminBridge.class.getName())
                    || fullClassName.equals(AminBridge.Storage.class.getName())
                    || fullClassName.equals("java.lang.Object")
                    || fullClassName.equals("java.lang.String")
                    || fullClassName.equals("java.lang.Boolean")
                    || fullClassName.equals("java.lang.Number")
                    || fullClassName.equals("java.lang.Double")
                    || fullClassName.equals("java.lang.Integer")
                    || fullClassName.equals("java.lang.Long");
        }
    };

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

    public static boolean hasEnabledPlugins(Context context) {
        for (JsPlugin plugin : plugins(context)) {
            if (plugin.enabled) {
                return true;
            }
        }
        return false;
    }

    /** Enabled plugins that belong in the chat attach menu:
     *  {id, name} pairs in install order. Pure prefs read. */
    public static List<String[]> menuPlugins(Context context) {
        List<String[]> out = new ArrayList<>();
        for (JsPlugin plugin : plugins(context)) {
            if (plugin.enabled && plugin.hasMenu && plugin.hasCapability("menu")) {
                out.add(new String[]{plugin.id, plugin.name});
            }
        }
        return out;
    }

    private static void loadCache(Context context) {
        if (cacheLoaded) {
            return;
        }
        cacheLoaded = true;
        cache.clear();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_ORDER, null);
        if (raw == null) {
            return;
        }
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

    // ------------------------------------------------------------------
    // Install / enable / delete
    // ------------------------------------------------------------------

    /** Full install path shared by file import, paste-code and the
     *  bundled examples: manifest validation → Rhino compile check →
     *  probe evaluation (SETTINGS schema + hook detection) → store.
     *  Returns null on success, or a user-readable error. */
    public static String install(Context context, String source) {
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
            ScriptableObject probe = newScope(cx, manifest.id);
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
            appendLog(plugin.id, "[info] installed v" + plugin.version + " by " + plugin.author);
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
        synchronized (JsPluginManager.class) {
            loadCache(context);
            for (int i = cache.size() - 1; i >= 0; i--) {
                if (cache.get(i).id.equals(plugin.id)) {
                    cache.remove(i);
                }
            }
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray order = new JSONArray();
        for (JsPlugin p : plugins(context)) {
            order.put(p.id);
        }
        prefs.edit()
                .putString(KEY_ORDER, order.toString())
                .remove(KEY_META_PREFIX + plugin.id)
                .remove(KEY_LOG_PREFIX + plugin.id)
                .apply();
        File file = codeFile(context, plugin.id);
        if (file.exists()) {
            file.delete();
        }
        context.getSharedPreferences(storagePrefsName(plugin.id), Context.MODE_PRIVATE)
                .edit().clear().apply();
        dropRuntime(plugin.id);
        broken.remove(plugin.id);
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

    private static ScriptableObject newScope(org.mozilla.javascript.Context cx, String pluginId) {
        ScriptableObject scope = cx.initStandardObjects();
        // Defense in depth on top of the ClassShutter: the standard
        // scope has no Java access, but strip the names if present.
        String[] banned = {"Packages", "java", "javax", "JavaAdapter", "importClass", "getClass"};
        for (String name : banned) {
            ScriptableObject.deleteProperty(scope, name);
        }
        scope.put("amin", scope, org.mozilla.javascript.Context.javaToJS(new AminBridge(pluginId), scope));
        return scope;
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
    // Runtime lifecycle (globalQueue thread only)
    // ------------------------------------------------------------------

    private static void dropRuntime(final String pluginId) {
        Utilities.globalQueue.postRunnable(() -> runtimes.remove(pluginId));
    }

    private static void warmUp(final String pluginId) {
        Utilities.globalQueue.postRunnable(() -> {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            JsPlugin plugin = get(context, pluginId);
            if (plugin == null || !plugin.enabled) {
                return;
            }
            org.mozilla.javascript.Context cx = enterContext();
            try {
                ensureRuntime(cx, context, plugin);
            } catch (Throwable ignored) {
            } finally {
                org.mozilla.javascript.Context.exit();
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
            runtime.scope = newScope(cx, plugin.id);
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
    // Dispatch (called from AminHooks; work runs on globalQueue)
    // ------------------------------------------------------------------

    public static void dispatchMessage(final int account, final TLRPC.Message message) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || message == null) {
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
        Utilities.globalQueue.postRunnable(() -> {
            dispatchAccount = account;
            org.mozilla.javascript.Context cx = enterContext();
            try {
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
                org.mozilla.javascript.Context.exit();
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
        if (context == null || params == null || params.message == null) {
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
        Utilities.globalQueue.postRunnable(() -> {
            dispatchAccount = account;
            org.mozilla.javascript.Context cx = enterContext();
            try {
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
                org.mozilla.javascript.Context.exit();
            }
        });
    }

    private static void dispatchCommandInternal(final int account, final TLRPC.Message message,
                                                final String command, final String args,
                                                Object unused) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            dispatchAccount = account;
            org.mozilla.javascript.Context cx = enterContext();
            try {
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
                org.mozilla.javascript.Context.exit();
            }
        });
    }

    public static void dispatchMenuAction(final String pluginId, final long chatId) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            JsPlugin plugin = get(context, pluginId);
            if (plugin == null || !plugin.enabled) {
                return;
            }
            dispatchAccount = UserConfig.selectedAccount;
            org.mozilla.javascript.Context cx = enterContext();
            try {
                Runtime runtime = ensureRuntime(cx, context, plugin);
                if (runtime == null) {
                    return;
                }
                Scriptable ctx = cx.newObject(runtime.scope);
                putNum(ctx, "chatId", chatId);
                callHook(cx, runtime, "onMenuAction", new Object[]{ctx});
            } catch (Throwable ignored) {
            } finally {
                org.mozilla.javascript.Context.exit();
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
