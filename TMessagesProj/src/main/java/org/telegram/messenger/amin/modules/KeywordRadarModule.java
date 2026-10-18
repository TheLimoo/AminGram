package org.telegram.messenger.amin.modules;

import org.telegram.messenger.amin.plugins.ModModule;

public class KeywordRadarModule implements ModModule {
    @Override public String id() { return "keyword_radar"; }
    @Override public String title() { return "Keyword Radar"; }
    @Override public String description() {
        return "Watches your chats for keywords you choose and collects matching messages in one place.";
    }
    @Override public boolean enabledByDefault() { return false; }
}
