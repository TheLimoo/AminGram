package org.telegram.messenger.amin;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.amin.plugins.PluginRegistry;

/**
 * Single bridge between upstream Telegram code and the AminGram mod layer.
 * Upstream hooks call ONLY these static methods (1-3 lines each, recorded
 * in MODS.md), so rebasing onto new Telegram versions stays cheap.
 */
public final class AminHooks {

    private AminHooks() {
    }

    public static boolean moduleEnabled(String moduleId) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        return PluginRegistry.isEnabledById(context, moduleId);
    }

    public static boolean localPremium() {
        return moduleEnabled("local_premium");
    }

    public static boolean ghostMode() {
        return moduleEnabled("ghost_mode");
    }

    public static boolean antiDelete() {
        return moduleEnabled("anti_delete");
    }

    public static boolean freeTranslate() {
        return moduleEnabled("free_translate");
    }
}
