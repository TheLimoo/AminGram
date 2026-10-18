package org.telegram.messenger.amin.modules;

import org.telegram.messenger.amin.plugins.ModModule;

public class GhostModeModule implements ModModule {
    @Override public String id() { return "ghost_mode"; }
    @Override public String title() { return "Ghost Mode"; }
    @Override public String description() {
        return "Stops sending read receipts and hides online/typing presence where the client controls it.";
    }
    @Override public boolean enabledByDefault() { return false; }
}
