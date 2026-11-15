package de.robv.android.xposed;

import org.telegram.messenger.amin.pyplugins.PySettingsHookRegistry;

import java.lang.reflect.Member;

/**
 * Clean-room re-implementation of the Xposed {@code XposedBridge}
 * entry point — M3.
 *
 * Only {@link #hookMethod(Member, XC_MethodHook)} exists, because
 * that is the one call the decompiled MaterialSettingsList DEX
 * makes (via its {@code XposedUtilsKt.hook()}, which resolves the
 * max-arg overload of a method name and hooks it). The call is
 * routed to {@link PySettingsHookRegistry}, which maps the
 * reflected method to a source-level hook point in
 * SettingsActivity (patch 0028) and invokes the callback from
 * there. No native code, no runtime bytecode rewriting, no
 * LSPlant / Dobby / AliuHook is involved — the spike (Phase 0
 * report + M3 decompile) showed every DEX target maps to a hook
 * point inside the already-counted SettingsActivity.java, so
 * native hooking was not needed and was not bundled.
 *
 * Unknown methods degrade, they do not throw: hooking a method
 * with no hook point returns a no-op {@link XC_MethodHook.Unhook}
 * and logs a warning. Throwing would abort the DEX manager's
 * whole setup (its try-block covers several hook calls), while a
 * no-op lets the rest of the plugin keep working degraded.
 *
 * Documented exception to the amin.** rule: the DEX resolves this
 * literal class name, so the shim must live at Xposed's package.
 * It holds no plugin logic — only routing.
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    /** Hooks a method with a before/after callback. The method
     *  must be one of the three SettingsActivity methods the
     *  registry knows (fillItems, SettingCell.Factory.bindView,
     *  SettingCell.updateColors); anything else returns a no-op
     *  Unhook and logs a warning instead of throwing. */
    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        if (!(method instanceof java.lang.reflect.Method) || callback == null) {
            return new XC_MethodHook.Unhook() {
                @Override
                public void unhook() {
                }
            };
        }
        return PySettingsHookRegistry.registerXposed(
                (java.lang.reflect.Method) method, callback);
    }
}
