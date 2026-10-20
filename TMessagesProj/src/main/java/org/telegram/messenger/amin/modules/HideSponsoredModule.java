package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class HideSponsoredModule implements ModModule {
    @Override public String id() { return "hide_sponsored"; }
    @Override public String title() { return "Hide Sponsored"; }
    @Override public String description() {
        return "Removes sponsored posts from channels and sponsored peers from search.";
    }
    @Override public boolean enabledByDefault() { return true; }
    @Override public int section() { return SECTION_PRIVACY; }
    @Override public String status(Context context) {
        return PluginRegistry.isEnabled(context, this) ? "Active" : "";
    }
}
