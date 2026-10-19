package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class GhostStoriesModule implements ModModule {
    @Override public String id() { return "ghost_stories"; }
    @Override public String title() { return "Ghost for Stories"; }
    @Override public String description() {
        return "Watch stories without marking them as seen — the poster never gets your view, and the story stays unread here and on your other devices.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_PRIVACY; }
    @Override public String status(Context context) {
        return PluginRegistry.isEnabled(context, this) ? "Active — story views are not reported" : "";
    }
}
