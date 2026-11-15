package org.telegram.messenger.amin.pyplugins;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hook + menu registry for the Python (.plugin) engine — M2.
 *
 * Clean-room reimplementation of the exteraGram SDK's event
 * interception surface ({@code add_hook},
 * {@code add_on_send_message_hook}, {@code MenuItemData}), backed
 * by AminGram's own {@code AminHooks} call sites — NOT by native
 * method hooking. No exteraGram or exteraless code was used.
 *
 * <h2>Hook kinds</h2>
 * <ul>
 *   <li><b>Named hooks</b> ({@code add_hook(name, ...)}): observe-only.
 *       Fired from {@code AminHooks} points — currently
 *       {@code "message"} (incoming messages, post-delivery),
 *       {@code "app_start"/"app_stop"/"app_pause"/"app_resume"}
 *       (LaunchActivity lifecycle, patch 0027) — and dispatched
 *       asynchronously on the Python thread. A named hook's return
 *       value is logged when it is not the default; it cannot
 *       cancel or modify anything, because by the time it runs the
 *       event has already happened. With {@code match_substring}
 *       the registration fires when the event name contains the
 *       registered name.</li>
 *   <li><b>Send-message hooks</b> ({@code add_on_send_message_hook}):
 *       the one synchronous Python call in the engine. Fired from
 *       {@code AminHooks.onOutgoingMessage} for EVERY outgoing
 *       message (not just "/" commands). Each registered hook runs
 *       on the Python thread and the send path waits for the
 *       verdict, bounded by {@link #SEND_HOOK_TIMEOUT_MS} — a
 *       timeout or a throwing hook means DEFAULT (the message
 *       sends normally). Hooks run by priority, highest first:
 *       {@code modify} rewrites the text and later hooks see the
 *       new text; {@code modify_final} rewrites and stops the
 *       chain; {@code cancel} suppresses the message entirely.
 *       A send hook must not send a message synchronously from
 *       inside the callback: the nested send would wait on the
 *       same Python thread and time out (bounded, but the outer
 *       hook then sees DEFAULT).</li>
 *   <li><b>Menu items</b> ({@code MenuItemData}): registered per
 *       plugin and location; shown by {@code AminHooks} at the
 *       existing attach-menu (patch 0014) and message context-menu
 *       (patch 0020) call sites. Conditions are evaluated in Java
 *       ({@link PyMenuCondition}, MVEL subset) at menu-build time —
 *       no Python round-trip on the UI thread. Tapping an item
 *       dispatches asynchronously to the item's {@code on_click}
 *       callback, or to the plugin's {@code on_menu_action}
 *       method when no callback was given.</li>
 * </ul>
 *
 * Registrations live only while the plugin is loaded:
 * {@link PyPluginsController} clears a plugin's entries on unload
 * and on (re)load, so a stale callback can never fire. All
 * callbacks run with the current-plugin context set
 * ({@code amin_context}), so SDK helpers inside them log under
 * the right plugin.
 *
 * What this is NOT: native method hooking. {@code hook_method} /
 * Xposed-style before/after interception of arbitrary Telegram
 * methods is M3 (DEX bridge), not this registry.
 */
public final class PyHookRegistry {

    private PyHookRegistry() {
    }

    /** Bound on the synchronous send-hook wait. */
    public static final long SEND_HOOK_TIMEOUT_MS = 800;

    /** Menu locations (exteraGram {@code MenuItemData} values). */
    public static final String LOC_MESSAGE_CONTEXT_MENU = "message_context_menu";
    public static final String LOC_CHAT_ACTION_MENU = "chat_action_menu";
    public static final String LOC_DRAWER_MENU = "drawer_menu";
    public static final String LOC_MAIN_MENU = "main_menu";
    public static final String LOC_PROFILE_ACTION_MENU = "profile_action_menu";

    /** Hook strategies (exteraGram {@code HookStrategy}). */
    public static final String STRATEGY_DEFAULT = "default";
    public static final String STRATEGY_CANCEL = "cancel";
    public static final String STRATEGY_MODIFY = "modify";
    public static final String STRATEGY_MODIFY_FINAL = "modify_final";

    /** Outcome of the synchronous send-hook chain. */
    public static final class SendOutcome {
        /** True when a hook cancelled the message. */
        public final boolean cancel;
        /** The (possibly rewritten) text to send. */
        public final String text;

        public SendOutcome(boolean cancel, String text) {
            this.cancel = cancel;
            this.text = text;
        }
    }

    /** One registered menu item. */
    public static final class MenuEntry {
        public final String pluginId;
        public final String location;
        public final String text;
        public final String condition;
        public final String icon;
        /** Index within this plugin's items for this location
         *  (stable key used by the menu dispatch id). */
        public final int ordinal;
        /** Null when the plugin's on_menu_action handles the tap. */
        public final PyObject callback;

        MenuEntry(String pluginId, String location, String text, String condition,
                  String icon, int ordinal, PyObject callback) {
            this.pluginId = pluginId;
            this.location = location;
            this.text = text;
            this.condition = condition;
            this.icon = icon;
            this.ordinal = ordinal;
            this.callback = callback;
        }
    }

    private static final class HookReg {
        final String pluginId;
        final String name;
        final boolean matchSubstring;
        final int priority;
        final PyObject callback;

        HookReg(String pluginId, String name, boolean matchSubstring,
                int priority, PyObject callback) {
            this.pluginId = pluginId;
            this.name = name;
            this.matchSubstring = matchSubstring;
            this.priority = priority;
            this.callback = callback;
        }
    }

    private static final class SendHookReg {
        final String pluginId;
        final int priority;
        final PyObject callback;

        SendHookReg(String pluginId, int priority, PyObject callback) {
            this.pluginId = pluginId;
            this.priority = priority;
            this.callback = callback;
        }
    }

    private static final List<HookReg> hooks = new ArrayList<>();
    private static final List<SendHookReg> sendHooks = new ArrayList<>();
    private static final List<MenuEntry> menuEntries = new ArrayList<>();

    private static final Comparator<HookReg> BY_PRIORITY_DESC =
            new Comparator<HookReg>() {
                @Override
                public int compare(HookReg a, HookReg b) {
                    return Integer.compare(b.priority, a.priority);
                }
            };

    private static final Comparator<SendHookReg> SEND_BY_PRIORITY_DESC =
            new Comparator<SendHookReg>() {
                @Override
                public int compare(SendHookReg a, SendHookReg b) {
                    return Integer.compare(b.priority, a.priority);
                }
            };

    // ------------------------------------------------------------------
    // Registration (called from Python via jclass)
    // ------------------------------------------------------------------

    /** Registers a named-hook callback. Called from
     *  {@code base_plugin.add_hook} / {@code hook_utils}. */
    public static synchronized void registerHook(String pluginId, String name,
                                                 boolean matchSubstring, int priority,
                                                 PyObject callback) {
        if (pluginId == null || name == null || callback == null) {
            return;
        }
        hooks.add(new HookReg(pluginId, name, matchSubstring, priority, callback));
    }

    /** Registers a send-message hook callback. */
    public static synchronized void registerSendHook(String pluginId, int priority,
                                                     PyObject callback) {
        if (pluginId == null || callback == null) {
            return;
        }
        sendHooks.add(new SendHookReg(pluginId, priority, callback));
    }

    /** Registers a menu item; returns its ordinal within this
     *  plugin's items for the location, or -1 on bad input. */
    public static synchronized int registerMenuItem(String pluginId, String location,
                                                    String text, String condition,
                                                    String icon, PyObject callback) {
        if (pluginId == null || location == null || text == null
                || text.trim().isEmpty()) {
            return -1;
        }
        int ordinal = 0;
        for (MenuEntry e : menuEntries) {
            if (e.pluginId.equals(pluginId) && e.location.equals(location)) {
                ordinal++;
            }
        }
        menuEntries.add(new MenuEntry(pluginId, location, text, condition,
                icon, ordinal, callback));
        if (LOC_DRAWER_MENU.equals(location) || LOC_MAIN_MENU.equals(location)
                || LOC_PROFILE_ACTION_MENU.equals(location)) {
            PyPluginHost.log(pluginId,
                    "menu item '" + text + "' registered for " + location
                    + " — no hook point yet, it will not be shown (M3).");
        }
        return ordinal;
    }

    /** Removes the first menu item of a plugin with the given
     *  location and text. No-op when there is none. */
    public static synchronized void removeMenuItem(String pluginId, String location,
                                                   String text) {
        if (pluginId == null || location == null || text == null) {
            return;
        }
        for (int i = 0; i < menuEntries.size(); i++) {
            MenuEntry e = menuEntries.get(i);
            if (e.pluginId.equals(pluginId) && e.location.equals(location)
                    && e.text.equals(text)) {
                menuEntries.remove(i);
                return;
            }
        }
    }

    /** Drops every registration belonging to a plugin (unload /
     *  reload). Also called from the loader before re-registering
     *  a plugin's decorated hooks. */
    public static synchronized void unregisterPlugin(String pluginId) {
        if (pluginId == null) {
            return;
        }
        for (int i = hooks.size() - 1; i >= 0; i--) {
            if (hooks.get(i).pluginId.equals(pluginId)) {
                hooks.remove(i);
            }
        }
        for (int i = sendHooks.size() - 1; i >= 0; i--) {
            if (sendHooks.get(i).pluginId.equals(pluginId)) {
                sendHooks.remove(i);
            }
        }
        for (int i = menuEntries.size() - 1; i >= 0; i--) {
            if (menuEntries.get(i).pluginId.equals(pluginId)) {
                menuEntries.remove(i);
            }
        }
    }

    /** True when at least one send hook is registered (fast path
     *  for the send path: no Python call at all otherwise). */
    public static synchronized boolean hasSendHooks() {
        return !sendHooks.isEmpty();
    }

    // ------------------------------------------------------------------
    // Named hooks (async, observe-only)
    // ------------------------------------------------------------------

    /** Dispatches a named hook to every matching registration,
     *  highest priority first, on the Python thread. */
    public static void dispatchHookAsync(final String name,
                                         final Map<String, Object> data) {
        if (name == null) {
            return;
        }
        final List<HookReg> targets = matchingHooks(name);
        if (targets.isEmpty()) {
            return;
        }
        final Map<String, Object> payload = data == null
                ? new HashMap<String, Object>() : new HashMap<>(data);
        payload.put("hook", name);
        PyPluginsBootstrap.post(new Runnable() {
            @Override
            public void run() {
                for (HookReg reg : targets) {
                    try {
                        PyObject result = callWithContext(
                                reg.pluginId, reg.callback, payload);
                        if (result != null) {
                            String strategy = extractStrategy(result);
                            if (strategy != null && !STRATEGY_DEFAULT.equals(strategy)) {
                                PyPluginHost.log(reg.pluginId,
                                        "hook '" + name + "' returned '"
                                        + strategy + "' (ignored: named hooks "
                                        + "are observe-only)");
                            }
                        }
                    } catch (Throwable t) {
                        PyPluginsController.recordDispatchFailure(
                                ApplicationLoader.applicationContext,
                                reg.pluginId, t);
                    }
                }
            }
        });
    }

    private static synchronized List<HookReg> matchingHooks(String name) {
        List<HookReg> out = new ArrayList<>();
        for (HookReg reg : hooks) {
            if (reg.name.equals(name)
                    || (reg.matchSubstring && name.contains(reg.name))) {
                out.add(reg);
            }
        }
        Collections.sort(out, BY_PRIORITY_DESC);
        return out;
    }

    // ------------------------------------------------------------------
    // Send-message hooks (synchronous, bounded)
    // ------------------------------------------------------------------

    /** Runs the send-hook chain for one outgoing message and
     *  returns the verdict. Never throws; any failure degrades to
     *  DEFAULT (send the original text). */
    public static SendOutcome dispatchSendHook(final String text, final long dialogId,
                                               final int account) {
        final String original = text == null ? "" : text;
        List<SendHookReg> chain;
        synchronized (PyHookRegistry.class) {
            if (sendHooks.isEmpty()) {
                return new SendOutcome(false, original);
            }
            chain = new ArrayList<>(sendHooks);
        }
        Collections.sort(chain, SEND_BY_PRIORITY_DESC);
        if (!Python.isStarted()) {
            return new SendOutcome(false, original);
        }
        if (PyPluginsBootstrap.isPyThread()) {
            // Already on the Python thread (a hook sending a message
            // from inside a callback): run inline, no latch.
            return runSendChain(chain, original, dialogId, account);
        }
        final AtomicReference<SendOutcome> outcome =
                new AtomicReference<>(new SendOutcome(false, original));
        final CountDownLatch latch = new CountDownLatch(1);
        PyPluginsBootstrap.post(new Runnable() {
            @Override
            public void run() {
                try {
                    outcome.set(runSendChain(chain, original, dialogId, account));
                } catch (Throwable t) {
                    PyPluginHost.log("engine",
                            "send-hook chain failed: " + PyPluginHost.shortError(t));
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(SEND_HOOK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                PyPluginHost.log("engine",
                        "send-hook chain timed out after "
                        + SEND_HOOK_TIMEOUT_MS + " ms — sending original text");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return outcome.get();
    }

    private static SendOutcome runSendChain(List<SendHookReg> chain, String text,
                                            long dialogId, int account) {
        String current = text;
        for (SendHookReg reg : chain) {
            Map<String, Object> data = new HashMap<>();
            data.put("text", current);
            data.put("dialog_id", dialogId);
            data.put("account", account);
            PyObject result;
            try {
                result = callWithContext(reg.pluginId, reg.callback, data);
            } catch (Throwable t) {
                PyPluginsController.recordDispatchFailure(
                        ApplicationLoader.applicationContext, reg.pluginId, t);
                continue;
            }
            if (result == null) {
                continue;
            }
            String strategy = extractStrategy(result);
            if (strategy == null || STRATEGY_DEFAULT.equals(strategy)) {
                continue;
            }
            if (STRATEGY_CANCEL.equals(strategy)) {
                return new SendOutcome(true, current);
            }
            if (STRATEGY_MODIFY.equals(strategy)
                    || STRATEGY_MODIFY_FINAL.equals(strategy)) {
                String rewritten = extractText(result);
                if (rewritten != null) {
                    current = rewritten;
                }
                if (STRATEGY_MODIFY_FINAL.equals(strategy)) {
                    return new SendOutcome(false, current);
                }
            }
            // Unknown strategy strings are ignored (DEFAULT).
        }
        return new SendOutcome(false, current);
    }

    // ------------------------------------------------------------------
    // Menu items
    // ------------------------------------------------------------------

    /** Menu entries for a location whose condition holds in this
     *  context, in registration order. */
    public static synchronized List<MenuEntry> visibleMenuItems(
            String location, Map<String, Object> context) {
        List<MenuEntry> out = new ArrayList<>();
        if (location == null) {
            return out;
        }
        for (MenuEntry e : menuEntries) {
            if (!e.location.equals(location)) {
                continue;
            }
            if (e.condition == null || e.condition.trim().isEmpty()
                    || PyMenuCondition.evaluate(e.condition, context)) {
                out.add(e);
            }
        }
        return out;
    }

    /** All entries of one plugin for one location (build-time
     *  lookup; conditions are NOT evaluated here). */
    public static synchronized List<MenuEntry> entriesForPlugin(
            String pluginId, String location) {
        List<MenuEntry> out = new ArrayList<>();
        if (pluginId == null || location == null) {
            return out;
        }
        for (MenuEntry e : menuEntries) {
            if (e.pluginId.equals(pluginId) && e.location.equals(location)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Dispatches a menu tap (async, on the Python thread). The
     *  entry is looked up by (plugin, location, ordinal) as
     *  encoded in the menu id; a stale ordinal logs and drops. */
    public static void dispatchMenuAction(final String pluginId, final String location,
                                          final int ordinal,
                                          final Map<String, Object> data) {
        if (pluginId == null || location == null) {
            return;
        }
        final MenuEntry entry;
        synchronized (PyHookRegistry.class) {
            MenuEntry found = null;
            for (MenuEntry e : menuEntries) {
                if (e.pluginId.equals(pluginId) && e.location.equals(location)
                        && e.ordinal == ordinal) {
                    found = e;
                    break;
                }
            }
            entry = found;
        }
        if (entry == null) {
            PyPluginHost.log(pluginId,
                    "menu tap for unknown entry " + location + "#" + ordinal
                    + " (plugin reloaded since the menu was built?)");
            return;
        }
        final Map<String, Object> payload = data == null
                ? new HashMap<String, Object>() : new HashMap<>(data);
        payload.put("location", location);
        payload.put("plugin_id", pluginId);
        PyPluginsBootstrap.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (entry.callback != null) {
                        callWithContext(pluginId, entry.callback, payload);
                    } else {
                        PyObject instance = PyPluginsController.instanceFor(pluginId);
                        if (instance == null) {
                            return;
                        }
                        Python.getInstance().getModule("amin_loader")
                                .callAttr("call_optional", instance,
                                        "on_menu_action", payload);
                    }
                } catch (Throwable t) {
                    PyPluginsController.recordDispatchFailure(
                            ApplicationLoader.applicationContext, pluginId, t);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Calls a Python callback with the current-plugin context
     *  set, restoring the previous context afterwards. */
    static PyObject callWithContext(String pluginId, PyObject callback, Object arg) {
        if (callback == null) {
            return null;
        }
        PyObject previous = null;
        boolean contextSet = false;
        try {
            PyObject contextModule = Python.getInstance().getModule("amin_context");
            previous = contextModule.callAttr("set_current", pluginId);
            contextSet = true;
        } catch (Throwable ignored) {
        }
        try {
            return arg == null ? callback.call() : callback.call(arg);
        } finally {
            if (contextSet) {
                try {
                    Python.getInstance().getModule("amin_context")
                            .callAttr("set_current", previous);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Extracts a hook strategy from a callback's return value:
     *  a plain string ("cancel", "modify", ...), a dict with a
     *  "strategy" key, or a HookResult object with a "strategy"
     *  attribute. Returns null when nothing usable was returned
     *  (treated as DEFAULT). */
    static String extractStrategy(PyObject result) {
        if (result == null) {
            return null;
        }
        try {
            String asString = result.toJava(String.class);
            if (asString != null) {
                String s = asString.trim().toLowerCase();
                if (STRATEGY_CANCEL.equals(s) || STRATEGY_MODIFY.equals(s)
                        || STRATEGY_MODIFY_FINAL.equals(s)
                        || STRATEGY_DEFAULT.equals(s)) {
                    return s;
                }
                // A non-strategy string (e.g. a plugin returning
                // text) is not a verdict.
                return null;
            }
        } catch (Throwable ignored) {
        }
        try {
            PyObject strategy = result.callAttr("get", "strategy");
            if (strategy != null) {
                String s = strategy.toJava(String.class);
                if (s != null) {
                    s = s.trim().toLowerCase();
                    if (STRATEGY_CANCEL.equals(s) || STRATEGY_MODIFY.equals(s)
                            || STRATEGY_MODIFY_FINAL.equals(s)
                            || STRATEGY_DEFAULT.equals(s)) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            PyObject strategy = result.get("strategy");
            if (strategy != null) {
                String s = strategy.toJava(String.class);
                if (s != null) {
                    return s.trim().toLowerCase();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Extracts the rewritten text from a MODIFY / MODIFY_FINAL
     *  return value: a dict / HookResult with a "text" key, or
     *  null when the callback returned a bare strategy string
     *  (the text is then unchanged). */
    static String extractText(PyObject result) {
        if (result == null) {
            return null;
        }
        try {
            PyObject text = result.callAttr("get", "text");
            if (text != null) {
                return text.toJava(String.class);
            }
        } catch (Throwable ignored) {
        }
        try {
            PyObject text = result.get("text");
            if (text != null) {
                return text.toJava(String.class);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
