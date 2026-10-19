package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class HideStoriesModule implements ModModule {
    @Override public String id() { return "hide_stories"; }
    @Override public String title() { return "Hide Stories"; }
    @Override public String description() {
        return "Removes the stories row from the top of the chat list.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_PRIVACY; }
    @Override public String status(Context context) {
        return PluginRegistry.isEnabled(context, this) ? "Active" : "";
    }
}
