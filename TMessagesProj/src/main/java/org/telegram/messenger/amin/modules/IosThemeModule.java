package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.core.DesignThemes;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

/**
 * iOS Theme (v1.3.3, default OFF): Apple's iOS palette applied to
 * the whole app through Telegram's theme engine (see
 * amin.core.DesignThemes) — grouped light-gray backgrounds, white
 * cards, iOS blue, gray incoming bubbles, green switches. A JS
 * plugin cannot do this (the plugin bridge has no UI access), so
 * it ships as a built-in module. Turning it off restores the theme
 * that was active before. Mutually exclusive with AminGram
 * Design: enabling one switches the other off.
 */
public class IosThemeModule implements ModModule {
    @Override public String id() { return "ios_theme"; }
    @Override public String title() { return "iOS Theme"; }
    @Override public String description() {
        return "Re-skins the whole app with Apple's iOS palette — light-gray groups, white cards, iOS blue and green switches — through Telegram's theme engine.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_TOOLS; }
    @Override public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        return DesignThemes.isActive(DesignThemes.DESIGN_IOS)
                ? "Active" : "Applies at next launch";
    }
}
