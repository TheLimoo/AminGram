package org.telegram.messenger.amin.modules;

import org.telegram.messenger.amin.plugins.ModModule;

public class LocalPremiumModule implements ModModule {
    @Override public String id() { return "local_premium"; }
    @Override public String title() { return "Local Premium"; }
    @Override public String description() {
        return "Forces premium state locally on this device only (client-side unlocks; server-side premium limits unchanged).";
    }
    @Override public boolean enabledByDefault() { return false; }
}
