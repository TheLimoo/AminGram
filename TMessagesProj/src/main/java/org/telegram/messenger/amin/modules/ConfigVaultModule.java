package org.telegram.messenger.amin.modules;

import org.telegram.messenger.amin.plugins.ModModule;

public class ConfigVaultModule implements ModModule {
    @Override public String id() { return "config_vault"; }
    @Override public String title() { return "Config Vault"; }
    @Override public String description() {
        return "Collects proxy configs and subscription links posted in your chats into one vault, ready to copy or share.";
    }
    @Override public boolean enabledByDefault() { return false; }
}
