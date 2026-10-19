package org.telegram.messenger.amin.plugins;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.amin.modules.AminDesignModule;
import org.telegram.messenger.amin.modules.AntiDeleteModule;
import org.telegram.messenger.amin.modules.AutoSaverModule;
import org.telegram.messenger.amin.modules.EditHistoryModule;
import org.telegram.messenger.amin.modules.GhostStoriesModule;
import org.telegram.messenger.amin.modules.HideStoriesModule;
import org.telegram.messenger.amin.modules.FreeTranslateModule;
import org.telegram.messenger.amin.modules.GhostModeModule;
import org.telegram.messenger.amin.modules.HiddenChatsModule;
import org.telegram.messenger.amin.modules.IosThemeModule;
import org.telegram.messenger.amin.modules.KeywordRadarModule;
import org.telegram.messenger.amin.modules.LocalPremiumModule;
import org.telegram.messenger.amin.modules.SendWhenOnlineModule;
import org.telegram.messenger.amin.modules.VideoBackgroundModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Central registry of all mod modules. The Plugins hub UI is generated
 * from this list; adding a module = adding one line in {@link #registerAll()}.
 */
public final class PluginRegistry {

    private static final String PREFS = "amingram_plugins";
    private static final List<ModModule> MODULES = new ArrayList<>();
    private static boolean registered;

    private PluginRegistry() {
    }

    public static synchronized void registerAll() {
        if (registered) {
            return;
        }
        registered = true;
        // The two design modules register first so they lead the
        // Tools section in the hub (v1.3.3 placement decision).
        MODULES.add(new AminDesignModule());
        MODULES.add(new IosThemeModule());
        MODULES.add(new LocalPremiumModule());
        MODULES.add(new GhostModeModule());
        MODULES.add(new GhostStoriesModule());
        MODULES.add(new AntiDeleteModule());
        MODULES.add(new FreeTranslateModule());
        MODULES.add(new VideoBackgroundModule());
        MODULES.add(new KeywordRadarModule());
        MODULES.add(new AutoSaverModule());
        MODULES.add(new EditHistoryModule());
        MODULES.add(new HideStoriesModule());
        MODULES.add(new HiddenChatsModule());
        MODULES.add(new SendWhenOnlineModule());
    }

    public static List<ModModule> modules() {
        registerAll();
        return Collections.unmodifiableList(MODULES);
    }

    public static boolean isEnabledById(Context context, String moduleId) {
        for (ModModule module : modules()) {
            if (module.id().equals(moduleId)) {
                return isEnabled(context, module);
            }
        }
        return false;
    }

    public static boolean isEnabled(Context context, ModModule module) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean("enabled_" + module.id(), module.enabledByDefault());
    }

    public static void setEnabled(Context context, ModModule module, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("enabled_" + module.id(), enabled)
                .apply();
    }
}
