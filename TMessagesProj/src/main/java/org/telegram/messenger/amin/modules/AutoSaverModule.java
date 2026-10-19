package org.telegram.messenger.amin.modules;

import org.telegram.messenger.amin.plugins.ModModule;

public class AutoSaverModule implements ModModule {
    @Override public String id() { return "auto_saver"; }
    @Override public String title() { return "Auto-Saver"; }
    @Override public String description() {
        return "Downloads disappearing (self-destruct) photos and videos into the app media cache before they expire, and lists them in the Auto-Saver log. Files stay in the app cache, not the system gallery.";
    }
    @Override public boolean enabledByDefault() { return false; }
}
