package org.telegram.messenger.amin.plugins;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.amin.modules.AntiDeleteModule;
import org.telegram.messenger.amin.modules.AutoSaverModule;
import org.telegram.messenger.amin.modules.EditHistoryModule;
import org.telegram.messenger.amin.modules.GhostStoriesModule;
import org.telegram.messenger.amin.modules.HideSponsoredModule;
import org.telegram.messenger.amin.modules.HideStoriesModule;
import org.telegram.messenger.amin.modules.FreeTranslateModule;
import org.telegram.messenger.amin.modules.GhostModeModule;
import org.telegram.messenger.amin.modules.HiddenChatsModule;
import org.telegram.messenger.amin.modules.KeywordRadarModule;
import org.telegram.messenger.amin.modules.LocalPremiumModule;
import org.telegram.messenger.amin.modules.ScreenshotUnlockModule;
import org.telegram.messenger.amin.modules.SendWhenOnlineModule;
import org.telegram.messenger.amin.modules.VideoBackgroundModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry of all mod modules. The Plugins hub UI is generated
 * from this list; adding a module = adding one line in {@link #registerAll()}.
 */
public final class PluginRegistry {

    private static final String PREFS = "amingram_plugins";
    private static final List<ModModule> MODULES = new ArrayList<>();
    private static final Map<String, ModModule> MODULES_BY_ID = new ConcurrentHashMap<>();
    private static boolean registered;

    private PluginRegistry() {
    }

    public static synchronized void registerAll() {
        if (registered) {
            return;
        }
        registered = true;
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
        MODULES.add(new HideSponsoredModule());
        MODULES.add(new ScreenshotUnlockModule());
        MODULES.add(new HiddenChatsModule());
        MODULES.add(new SendWhenOnlineModule());
        for (ModModule module : MODULES) {
            MODULES_BY_ID.put(module.id(), module);
        }
    }

    public static List<ModModule> modules() {
        registerAll();
        return Collections.unmodifiableList(MODULES);
    }

    public static boolean isEnabledById(Context context, String moduleId) {
        registerAll();
        ModModule module = MODULES_BY_ID.get(moduleId);
        return module != null && isEnabled(context, module);
    }

    // ------------------------------------------------------------------
    // Enabled-state cache (2026-10-22 optimization pass).
    //
    // isEnabled() backs AminHooks' module gates, which sit on hot
    // upstream paths — MessagesController.sendTyping (per typing
    // burst), updateStatus, the read-receipt task, story/sponsored
    // checks in list binds. The uncached read cost a linear registry
    // scan, a getSharedPreferences lookup, a string-concat key and a
    // prefs read on EVERY call. The cache turns a hit into one map
    // read; it is invalidated by a prefs change listener (fires for
    // any in-process write to an "enabled_*" key, including backup
    // restores) and updated directly by setEnabled. The listener is
    // held in a static field because SharedPreferences keeps only a
    // weak reference to listeners. Event-driven — no polling.
    // ------------------------------------------------------------------

    private static final Map<String, Boolean> enabledCache = new ConcurrentHashMap<>();
    private static volatile boolean enabledListenerRegistered;
    private static SharedPreferences.OnSharedPreferenceChangeListener enabledListener;

    private static void ensureEnabledListener(Context context) {
        if (enabledListenerRegistered) {
            return;
        }
        synchronized (PluginRegistry.class) {
            if (enabledListenerRegistered) {
                return;
            }
            enabledListener = (prefs, key) -> {
                if (key != null && key.startsWith("enabled_")) {
                    enabledCache.remove(key.substring("enabled_".length()));
                }
            };
            context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .registerOnSharedPreferenceChangeListener(enabledListener);
            enabledListenerRegistered = true;
        }
    }

    public static boolean isEnabled(Context context, ModModule module) {
        String id = module.id();
        Boolean cached = enabledCache.get(id);
        if (cached != null) {
            return cached;
        }
        ensureEnabledListener(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("enabled_" + id, module.enabledByDefault());
        enabledCache.put(id, enabled);
        return enabled;
    }

    public static void setEnabled(Context context, ModModule module, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("enabled_" + module.id(), enabled)
                .apply();
        enabledCache.put(module.id(), enabled);
    }
}
