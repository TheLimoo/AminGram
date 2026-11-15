package org.telegram.messenger.amin.pyplugins;

import android.util.Log;

import com.chaquo.python.PyObject;

import de.robv.android.xposed.XC_MethodHook;

import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Source-level hook points for the SettingsActivity list — M3.
 *
 * The DEX bridge, backing the {@code de.robv.android.xposed}
 * shim: DEX payloads hook three methods, and each of them maps
 * to a call site inserted by patch 0028 into
 * {@code SettingsActivity.java} (already a touched upstream file
 * since 0001; no new upstream file was needed for the call
 * sites). The registry translates a hooked call into an
 * {@link XC_MethodHook} before/after callback with a
 * {@link XC_MethodHook.MethodHookParam} whose {@code thisObject}
 * / {@code args} match what the DEX reads:
 *
 * <ul>
 *   <li>{@code fillItems} — after only. {@code thisObject} is the
 *       SettingsActivity, {@code args[0]} is the
 *       {@code ArrayList<UItem>} being filled (the DEX reorders /
 *       debloats it in place), {@code args[1]} is the adapter.</li>
 *   <li>{@code SettingCell.Factory.bindView} — before + after.
 *       {@code thisObject} is the Factory, {@code args[0]} is the
 *       SettingCell view, {@code args[1]} is the UItem (the DEX
 *       records the cell's palette key from it).</li>
 *   <li>{@code SettingCell.updateColors} — after only.
 *       {@code thisObject} is the SettingCell (the DEX recolours
 *       it from the recorded palette key), {@code args} is empty.</li>
 * </ul>
 *
 * Safety: dispatch never crashes the settings screen. Each hook
 * runs inside its own try/catch (a throwing hook is logged and
 * the call continues), the hook list is copied before iteration
 * (a hook may unhook itself while dispatching), and a hook that
 * throws in {@code beforeHookedMethod} cannot skip the method —
 * the shim is before/after notification, not true interception
 * (see {@link XC_MethodHook}). Registration and dispatch run on
 * different threads (DEX init on the Python thread, dispatch on
 * the UI thread), so the lists are guarded by synchronisation.
 *
 * The Python {@code hook_utils.hook_method} bridge also lands
 * here ({@link #hookByName}): a Python plugin may hook the same
 * three points with Python callables. Hooks registered that way
 * are tracked per plugin id so unloading the plugin removes them
 * ({@link #unregisterPlugin}); DEX hooks are owned by the DEX
 * itself (PluginCore holds the Unhooks and unhooks on unload).
 */
public final class PySettingsHookRegistry {

    private static final String TAG = "AminGramPySettings";

    /** Hook-point ids. Also the {@code point} values
     *  {@code hook_utils.hook_method} accepts. */
    public static final String POINT_FILL_ITEMS = "fillItems";
    public static final String POINT_BIND_VIEW = "bindView";
    public static final String POINT_UPDATE_COLORS = "updateColors";

    private static final List<XC_MethodHook> fillItemsHooks = new ArrayList<>();
    private static final List<XC_MethodHook> bindViewHooks = new ArrayList<>();
    private static final List<XC_MethodHook> updateColorsHooks = new ArrayList<>();
    /** Plugin id -> Unhooks registered via hookByName (M3 Python
     *  bridge), so unload removes them with the plugin. */
    private static final Map<String, List<XC_MethodHook.Unhook>> pluginHooks = new HashMap<>();

    private PySettingsHookRegistry() {
    }

    // ------------------------------------------------------------------
    // Routing (XposedBridge shim)
    // ------------------------------------------------------------------

    /** Maps a reflected method to a hook point, or null when the
     *  method has no source-level hook point in AminGram. */
    public static String pointFor(Method method) {
        if (method == null) {
            return null;
        }
        String cls = method.getDeclaringClass().getName();
        String name = method.getName();
        if ("org.telegram.ui.SettingsActivity".equals(cls)
                && "fillItems".equals(name)) {
            return POINT_FILL_ITEMS;
        }
        if ("org.telegram.ui.SettingsActivity$SettingCell$Factory".equals(cls)
                && "bindView".equals(name)) {
            return POINT_BIND_VIEW;
        }
        if ("org.telegram.ui.SettingsActivity$SettingCell".equals(cls)
                && "updateColors".equals(name)) {
            return POINT_UPDATE_COLORS;
        }
        return null;
    }

    /** Registers an {@link XC_MethodHook} for a reflected method
     *  (the XposedBridge entry point). An unknown method returns a
     *  no-op Unhook and logs a warning instead of throwing — the
     *  DEX manager's setup covers several hook calls in one
     *  try-block, so throwing would kill the hooks after it. */
    public static XC_MethodHook.Unhook registerXposed(Method method, XC_MethodHook hook) {
        String point = pointFor(method);
        if (point == null || hook == null) {
            if (method != null) {
                Log.w(TAG, "Unsupported Xposed hook target: "
                        + method.getDeclaringClass().getName()
                        + "#" + method.getName()
                        + " — hook ignored, plugin continues degraded.");
            }
            return new XC_MethodHook.Unhook() {
                @Override
                public void unhook() {
                }
            };
        }
        return register(point, hook);
    }

    /** Registers a hook for a known point id. Unknown point ->
     *  no-op Unhook (same degrade-don't-throw rule). */
    public static XC_MethodHook.Unhook register(String point, final XC_MethodHook hook) {
        final List<XC_MethodHook> list = listFor(point);
        if (list == null || hook == null) {
            return new XC_MethodHook.Unhook() {
                @Override
                public void unhook() {
                }
            };
        }
        synchronized (list) {
            list.add(hook);
        }
        return new XC_MethodHook.Unhook() {
            @Override
            public void unhook() {
                synchronized (list) {
                    list.remove(hook);
                }
            }
        };
    }

    // ------------------------------------------------------------------
    // Python bridge (hook_utils.hook_method)
    // ------------------------------------------------------------------

    /** Hooks one of the three points with Python callables
     *  (before / after, either may be null). Each callable
     *  receives the {@link XC_MethodHook.MethodHookParam} for the
     *  call. Returns an Unhook; the registration is tracked under
     *  {@code pluginId} so {@link #unregisterPlugin} removes it
     *  when the plugin unloads. Unknown point -> throws
     *  IllegalArgumentException (the Python side turns it into a
     *  NotImplementedError). A throwing callable is logged and the
     *  settings call continues.
     *
     *  <p>Chaquopy interop, unproven on device until M3's phone
     *  test: the callables arrive as {@link PyObject}s and are
     *  invoked with {@code call(param)}; the param is a Java
     *  object the Python side reads as {@code .thisObject} /
     *  {@code .args}. */
    public static XC_MethodHook.Unhook hookByName(final String pluginId, String point,
                                                  final PyObject before, final PyObject after) {
        if (listFor(point) == null) {
            throw new IllegalArgumentException(
                    "No hook point '" + point + "' — AminGram only hooks "
                    + "fillItems, bindView and updateColors in SettingsActivity.");
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            public void beforeHookedMethod(MethodHookParam param) {
                if (before != null) {
                    try {
                        before.call(param);
                    } catch (Throwable t) {
                        Log.w(TAG, "Python before-hook failed: "
                                + PyPluginHost.shortError(t));
                    }
                }
            }

            @Override
            public void afterHookedMethod(MethodHookParam param) {
                if (after != null) {
                    try {
                        after.call(param);
                    } catch (Throwable t) {
                        Log.w(TAG, "Python after-hook failed: "
                                + PyPluginHost.shortError(t));
                    }
                }
            }
        };
        XC_MethodHook.Unhook unhook = register(point, hook);
        if (pluginId != null) {
            synchronized (pluginHooks) {
                List<XC_MethodHook.Unhook> mine = pluginHooks.get(pluginId);
                if (mine == null) {
                    mine = new ArrayList<>();
                    pluginHooks.put(pluginId, mine);
                }
                mine.add(unhook);
            }
        }
        return unhook;
    }

    /** Removes every Python-bridge hook a plugin registered.
     *  Called from {@link PyPluginsController#unloadPlugin}. */
    public static void unregisterPlugin(String pluginId) {
        if (pluginId == null) {
            return;
        }
        List<XC_MethodHook.Unhook> mine;
        synchronized (pluginHooks) {
            mine = pluginHooks.remove(pluginId);
        }
        if (mine == null) {
            return;
        }
        for (XC_MethodHook.Unhook unhook : mine) {
            try {
                unhook.unhook();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Dispatch (called from SettingsActivity, patch 0028)
    // ------------------------------------------------------------------

    /** After {@code fillItems(items, adapter)} built the list. */
    public static void onFillItemsAfter(Object activity, ArrayList<UItem> items,
                                        UniversalAdapter adapter) {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.thisObject = activity;
        param.args = new Object[]{items, adapter};
        dispatchAfter(fillItemsHooks, param);
    }

    /** Before {@code SettingCell.Factory.bindView(...)} binds. */
    public static void onBindViewBefore(Object factory, Object view, UItem item,
                                        boolean divider, UniversalAdapter adapter,
                                        UniversalRecyclerView listView) {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.thisObject = factory;
        param.args = new Object[]{view, item, divider, adapter, listView};
        dispatchBefore(bindViewHooks, param);
    }

    /** After {@code SettingCell.Factory.bindView(...)} bound. */
    public static void onBindViewAfter(Object factory, Object view, UItem item,
                                       boolean divider, UniversalAdapter adapter,
                                       UniversalRecyclerView listView) {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.thisObject = factory;
        param.args = new Object[]{view, item, divider, adapter, listView};
        dispatchAfter(bindViewHooks, param);
    }

    /** After {@code SettingCell.updateColors()} recoloured. */
    public static void onUpdateColorsAfter(Object cell) {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.thisObject = cell;
        param.args = new Object[0];
        dispatchAfter(updateColorsHooks, param);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static List<XC_MethodHook> listFor(String point) {
        if (POINT_FILL_ITEMS.equals(point)) {
            return fillItemsHooks;
        }
        if (POINT_BIND_VIEW.equals(point)) {
            return bindViewHooks;
        }
        if (POINT_UPDATE_COLORS.equals(point)) {
            return updateColorsHooks;
        }
        return null;
    }

    private static void dispatchBefore(List<XC_MethodHook> hooks,
                                       XC_MethodHook.MethodHookParam param) {
        List<XC_MethodHook> copy;
        synchronized (hooks) {
            copy = new ArrayList<>(hooks);
        }
        for (XC_MethodHook hook : copy) {
            try {
                hook.beforeHookedMethod(param);
            } catch (Throwable t) {
                // A throwing before-hook must not take the settings
                // screen down; the method body still runs.
                Log.w(TAG, "before-hook failed: " + PyPluginHost.shortError(t));
            }
        }
    }

    private static void dispatchAfter(List<XC_MethodHook> hooks,
                                      XC_MethodHook.MethodHookParam param) {
        List<XC_MethodHook> copy;
        synchronized (hooks) {
            copy = new ArrayList<>(hooks);
        }
        for (XC_MethodHook hook : copy) {
            try {
                hook.afterHookedMethod(param);
            } catch (Throwable t) {
                Log.w(TAG, "after-hook failed: " + PyPluginHost.shortError(t));
            }
        }
    }
}
