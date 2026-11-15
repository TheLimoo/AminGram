package de.robv.android.xposed;

/**
 * Clean-room re-implementation of the Xposed {@code XC_MethodHook}
 * API surface — M3.
 *
 * Written from the public Xposed API (method-hook callbacks with a
 * {@code MethodHookParam} carrying {@code thisObject} / {@code args}
 * / {@code getResult} / {@code setResult}) so Kotlin DEX payloads
 * compiled against the real Xposed API resolve and run. No Xposed
 * or exteraGram code was copied; this class contains no hooking
 * machinery of its own — it is only the callback type the DEX
 * subclasses. The dispatch behind it is
 * {@link org.telegram.messenger.amin.pyplugins.PySettingsHookRegistry},
 * which calls these callbacks from source-level hook points in
 * SettingsActivity (patch 0028).
 *
 * Deviations from real Xposed, stated plainly:
 * <ul>
 *   <li>There is no true method interception: {@code beforeHookedMethod}
 *       runs at the start of the hooked method's body (from the hook
 *       point inserted there), not before the call, so a throwing
 *       before-hook cannot skip the method, and {@code setResult}
 *       is never honoured. The MaterialSettingsList DEX only
 *       observes arguments and mutates {@code args[0]} in place,
 *       which the shim supports.</li>
 *   <li>Only the three SettingsActivity methods the registry knows
 *       can be hooked; anything else degrades to a no-op Unhook
 *       (see {@link XposedBridge}).</li>
 * </ul>
 *
 * Documented exception to the amin.** rule: the DEX resolves this
 * literal class name, so the shim must live at Xposed's package.
 * It holds no plugin logic — only the API surface.
 */
public abstract class XC_MethodHook {

    /** Called before the hooked method's body runs (from the hook
     *  point at the top of that body). Default: no-op. */
    public void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** Called after the hooked method's body runs (from the hook
     *  point at the bottom of that body). Default: no-op. */
    public void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** The parameters of one hooked call. Field names and shapes
     *  match the real Xposed API — DEX code reads
     *  {@code param.args[i]} and {@code param.thisObject}
     *  directly. */
    public static class MethodHookParam {
        /** The object the hooked method was called on. For
         *  {@code SettingCell.updateColors} this is the cell; for
         *  {@code Factory.bindView} it is the Factory (the view is
         *  {@code args[0]}); for {@code fillItems} it is the
         *  SettingsActivity. */
        public Object thisObject;
        /** The hooked method's arguments, in declaration order.
         *  Mutating an element (or, for {@code fillItems}, the
         *  list in {@code args[0]}) affects the running call. */
        public Object[] args;

        private Object result;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }
    }

    /** Returned by {@link XposedBridge#hookMethod}; calling it
     *  removes the hook. */
    public interface Unhook {
        void unhook();
    }
}
