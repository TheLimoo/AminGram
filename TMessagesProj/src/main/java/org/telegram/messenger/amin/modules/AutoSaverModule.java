package org.telegram.messenger.amin.modules;

import org.telegram.messenger.amin.plugins.ModModule;

public class AutoSaverModule implements ModModule {
    @Override public String id() { return "auto_saver"; }
    @Override public String title() { return "Auto-Saver"; }
    @Override public String description() {
        return "Automatically saves disappearing photos and videos to your gallery before they expire.";
    }
    @Override public boolean enabledByDefault() { return false; }
}
