package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.core.DesignThemes;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

/**
 * AminGram Design (v1.3.3, default ON): Vesper's extera-inspired
 * design applied to the WHOLE app through Telegram's theme engine
 * (see amin.core.DesignThemes): near-black page backgrounds, dark
 * card surfaces, periwinkle-blue accent, white primary text.
 * Applies at app launch (patch 0015) and immediately on toggle;
 * turning it off restores the theme that was active before.
 */
public class AminDesignModule implements ModModule {
    @Override public String id() { return "amin_design"; }
    @Override public String title() { return "AminGram Design"; }
    @Override public String description() {
        return "The extera-style design for the whole app: near-black backgrounds, dark cards and a periwinkle accent, applied through Telegram's theme engine.";
    }
    @Override public boolean enabledByDefault() { return true; }
    @Override public int section() { return SECTION_TOOLS; }
    @Override public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        return DesignThemes.isActive(DesignThemes.DESIGN_AMIN)
                ? "Active" : "Applies at next launch";
    }
}
