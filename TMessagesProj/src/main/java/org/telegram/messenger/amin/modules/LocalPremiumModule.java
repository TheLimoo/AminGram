package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class LocalPremiumModule implements ModModule {
    @Override public String id() { return "local_premium"; }
    @Override public String title() { return "Local Premium"; }
    @Override public String description() {
        return "Unlocks premium-only client features on this device (translation, larger client limits and more). This device only — the server still sees a free account.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_TOOLS; }
    @Override public String status(Context context) {
        return PluginRegistry.isEnabled(context, this) ? "Active" : "";
    }
}
