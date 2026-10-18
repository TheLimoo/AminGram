package org.telegram.messenger.amin.modules;

import org.telegram.messenger.amin.plugins.ModModule;

public class FreeTranslateModule implements ModModule {
    @Override public String id() { return "free_translate"; }
    @Override public String title() { return "Free Translate"; }
    @Override public String description() {
        return "In-chat message translation using free endpoints, no API key required.";
    }
    @Override public boolean enabledByDefault() { return true; }
}
