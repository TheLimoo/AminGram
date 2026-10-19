package org.telegram.messenger.amin.modules;

import org.telegram.messenger.amin.plugins.ModModule;

public class HideStoriesModule implements ModModule {
    @Override public String id() { return "hide_stories"; }
    @Override public String title() { return "Hide Stories"; }
    @Override public String description() {
        return "Hide the stories row at the top of the chat list.";
    }
    @Override public boolean enabledByDefault() { return false; }
}
