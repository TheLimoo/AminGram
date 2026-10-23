package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Map;

/**
 * AminCleanup — the Ryoku removal migration (build #34).
 *
 * The Ryoku UI system (master toggle, bundled "Ryoku" theme, override
 * wallpapers) is deleted, but installs that ran builds #31–#33 may
 * still carry its persisted state, and one piece of it — the current
 * theme selection — is live ammunition: Theme's static initializer
 * reads the selection from "mainconfig" ("theme"/"nighttheme") and
 * the per-theme override wallpapers from "themeconfig" ("*_owp") on
 * the very first Theme reference in the process, and a poisoned
 * combination of those is what crash-looped the app on icon launch.
 *
 * This migration runs unconditionally from
 * ApplicationLoader.attachBaseContext (patch 0023) — before onCreate
 * and before any component can trigger Theme's static init — and
 * scrubs exactly that state:
 *  - "mainconfig": "theme"/"nighttheme" values equal to "Ryoku" are
 *    removed (Telegram falls back to its default theme); the plain
 *    int "bubbleRadius" key is removed too (Ryoku forced it to 3
 *    while on; SharedConfig reads it from mainconfig with a pristine
 *    default of 17, so removal restores stock bubbles);
 *  - "themeconfig": every key starting with "Ryoku" and ending with
 *    "_owp" (including the plain "Ryoku_owp"), and every key whose
 *    value equals "Ryoku", is removed;
 *  - Ryoku's own prefs file "amingram_ryoku" is cleared;
 *  - filesDir: "ryoku_wallpaper.jpg" and the random-named
 *    "Ryoku_*_wp*.jpg" files the old applyWallpaper() generated are
 *    deleted.
 *
 * Nothing else is touched: no chats/account data, no wallpaper
 * library, no video files. The migration is idempotent (each block
 * commits only when it actually changed something), uses ONLY raw
 * SharedPreferences / java.io APIs addressed by literal names — no
 * Theme, no ApplicationLoader statics — and every block is
 * Throwable-guarded, so it can never break startup itself.
 */
public final class AminCleanup {

    private static final String PREFS_MAIN = "mainconfig";
    private static final String PREFS_THEME = "themeconfig";
    private static final String PREFS_RYOKU = "amingram_ryoku";

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
            scrubThemeConfig(base);
            scrubRyokuPrefs(base);
            deleteRyokuWallpaperFiles(base);
        } catch (Throwable t) {
            // The migration must never break startup itself.
        }
    }

    /** "mainconfig": drop a "Ryoku" day/night theme selection and the
     *  bubble radius Ryoku forced — all Ryoku-owned display state in
     *  the file Theme + SharedConfig actually read at startup. */
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

    /** "themeconfig": drop every Ryoku override-wallpaper entry and
     *  every key whose value selects the Ryoku theme. */
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

    /** Ryoku's own prefs file (toggle, bootstrap flag, previous-theme
     *  bookkeeping, crash probe): dead state once the class is gone. */
    private static void scrubRyokuPrefs(Context ctx) {
        try {
            SharedPreferences ryoku = prefs(ctx, PREFS_RYOKU);
            if (ryoku != null && !ryoku.getAll().isEmpty()) {
                ryoku.edit().clear().commit();
            }
        } catch (Throwable ignore) {
        }
    }

    /** Delete the fixed wallpaper file + every random-named file the
     *  old applyWallpaper() generated (Ryoku_<accent>_wp<rand>.jpg). */
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
