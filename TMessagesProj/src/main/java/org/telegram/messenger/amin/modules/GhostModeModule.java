package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class GhostModeModule implements ModModule {
    @Override public String id() { return "ghost_mode"; }
    @Override public String title() { return "Ghost Mode"; }
    @Override public String description() {
        return "Read messages without sending read receipts, and hide your online and typing status from others.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_PRIVACY; }
    @Override public String status(Context context) {
        return PluginRegistry.isEnabled(context, this) ? "Active — receipts, online and typing are hidden" : "";
    }
}
