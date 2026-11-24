package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Map;

/**
 * AminCleanup — the Ryoku removal migration (build #34).
 *
 * Phase A optimization (A-26):
 * - Keeps synchronous mainconfig/themeconfig scrub intact before Theme
 *   static init, and uses a versioned completion marker in
 *   "amingram_ryoku" so steady-state cold starts skip the expensive
 *   themeconfig.getAll() and filesDir.listFiles() scans once the
 *   one-time scrub has completed.
 */
public final class AminCleanup {

    private static final String PREFS_MAIN = "mainconfig";
    private static final String PREFS_THEME = "themeconfig";
    private static final String PREFS_RYOKU = "amingram_ryoku";
    private static final String KEY_MIGRATED_V1 = "_ryoku_scrubbed_v1";

    private static final String KEY_THEME = "theme";
    private static final String KEY_NIGHT_THEME = "nighttheme";
    private static final String KEY_BUBBLE_RADIUS = "bubbleRadius";

    private static final String THEME_NAME = "Ryoku";
    private static final String WALLPAPER_FILE = "ryoku_wallpaper.jpg";

    private AminCleanup() {
    }

    /** Entry point called from ApplicationLoader.attachBaseContext. */
    public static void migrate(Context base) {
        try {
            if (base == null) {
                return;
            }
            scrubMainConfig(base);
            SharedPreferences ryoku = prefs(base, PREFS_RYOKU);
            if (ryoku != null && ryoku.getBoolean(KEY_MIGRATED_V1, false)) {
                return;
            }
            scrubThemeConfig(base);
            deleteRyokuWallpaperFiles(base);
            if (ryoku != null) {
                ryoku.edit().clear().putBoolean(KEY_MIGRATED_V1, true).commit();
            }
        } catch (Throwable t) {
            // The migration must never break startup itself.
        }
    }

    private static void scrubMainConfig(Context ctx) {
        try {
            SharedPreferences main = prefs(ctx, PREFS_MAIN);
            if (main == null) {
                return;
            }
            SharedPreferences.Editor editor = main.edit();
            boolean changed = false;
            try {
                if (THEME_NAME.equals(main.getString(KEY_THEME, null))) {
                    editor.remove(KEY_THEME);
                    changed = true;
                }
            } catch (Throwable ignore) {
            }
            try {
                if (THEME_NAME.equals(main.getString(KEY_NIGHT_THEME, null))) {
                    editor.remove(KEY_NIGHT_THEME);
                    changed = true;
                }
            } catch (Throwable ignore) {
            }
            try {
                if (main.contains(KEY_BUBBLE_RADIUS)) {
                    editor.remove(KEY_BUBBLE_RADIUS);
                    changed = true;
                }
            } catch (Throwable ignore) {
            }
            if (changed) {
                editor.commit();
            }
        } catch (Throwable ignore) {
        }
    }

    private static void scrubThemeConfig(Context ctx) {
        try {
            SharedPreferences themeConfig = prefs(ctx, PREFS_THEME);
            if (themeConfig == null) {
                return;
            }
            SharedPreferences.Editor editor = themeConfig.edit();
            boolean changed = false;
            for (Map.Entry<String, ?> entry : themeConfig.getAll().entrySet()) {
                String key = entry.getKey();
                if (key != null && key.startsWith(THEME_NAME) && key.endsWith("_owp")) {
                    editor.remove(key);
                    changed = true;
                } else if (THEME_NAME.equals(entry.getValue())) {
                    editor.remove(key);
                    changed = true;
                }
            }
            if (changed) {
                editor.commit();
            }
        } catch (Throwable ignore) {
        }
    }

    private static void deleteRyokuWallpaperFiles(Context ctx) {
        try {
            File dir = ctx.getFilesDir();
            File[] files = dir != null ? dir.listFiles() : null;
            if (files == null) {
                return;
            }
            for (File file : files) {
                String name = file.getName();
                if (WALLPAPER_FILE.equals(name)
                        || (name.startsWith(THEME_NAME + "_") && name.contains("_wp") && name.endsWith(".jpg"))) {
                    try {
                        file.delete();
                    } catch (Throwable ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static SharedPreferences prefs(Context ctx, String name) {
        try {
            return ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }
}
