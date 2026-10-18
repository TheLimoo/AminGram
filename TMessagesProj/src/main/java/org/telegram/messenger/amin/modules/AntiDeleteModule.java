package org.telegram.messenger.amin.modules;

import org.telegram.messenger.amin.plugins.ModModule;

public class AntiDeleteModule implements ModModule {
    @Override public String id() { return "anti_delete"; }
    @Override public String title() { return "Anti-Delete"; }
    @Override public String description() {
        return "Keeps a local cache of messages; messages deleted by their sender stay visible to you, marked as deleted.";
    }
    @Override public boolean enabledByDefault() { return false; }
}
