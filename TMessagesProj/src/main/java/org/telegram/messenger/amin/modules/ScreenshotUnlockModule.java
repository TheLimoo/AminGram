package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class ScreenshotUnlockModule implements ModModule {
    @Override public String id() { return "screenshot_unlock"; }
    @Override public String title() { return "Screenshot Unlock"; }
    @Override public String description() {
        return "Allows screenshots and screen capture in secret chats and protected (no-forward) media.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_PRIVACY; }
    @Override public String status(Context context) {
        return PluginRegistry.isEnabled(context, this)
                ? "Secret chats & protected media can be captured" : "";
    }
}
