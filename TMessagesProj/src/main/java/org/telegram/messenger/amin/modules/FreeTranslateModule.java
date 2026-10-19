package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class FreeTranslateModule implements ModModule {
    @Override public String id() { return "free_translate"; }
    @Override public String title() { return "Free Translate"; }
    @Override public String description() {
        return "Translates chats and messages without a premium account, using Telegram's translate flow on this device.";
    }
    @Override public boolean enabledByDefault() { return true; }
    @Override public int section() { return SECTION_TOOLS; }
    @Override public String status(Context context) {
        return PluginRegistry.isEnabled(context, this) ? "Active" : "";
    }
}
