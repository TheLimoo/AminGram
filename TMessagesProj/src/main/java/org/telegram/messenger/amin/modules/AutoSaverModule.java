package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.core.AutoSaverStore;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class AutoSaverModule implements ModModule {
    @Override public String id() { return "auto_saver"; }
    @Override public String title() { return "Auto-Saver"; }
    @Override public String description() {
        return "The moment a self-destruct or “view once” photo/video arrives, AminGram saves a copy before it can disappear. View saved media below — even after the original is gone.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_MEDIA; }
    @Override public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        int n = AutoSaverStore.count(context);
        return n > 0 ? n + " items saved" : "Active · nothing saved yet";
    }
}
