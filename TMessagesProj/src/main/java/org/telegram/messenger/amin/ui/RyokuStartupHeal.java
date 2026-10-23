package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ryoku heal v2 — the earliest-point, dependency-free startup repair.
 *
 * Round 1 (build #32) put the heal in
 * ApplicationLoader.postInitApplication, and it did not rescue the
 * bricked install. Two proven reasons:
 *
 *  1. postInitApplication is not ordered before the consumers. It is
 *     first invoked from LaunchActivity.onCreate, but Theme's entire
 *     persisted state — the built-in theme list, every theme's
 *     override wallpapers (themeconfig "*_owp"), and the current-theme
 *     selection — is consumed inside Theme's static initializer, which
 *     runs on the first reference to Theme from anywhere in the
 *     process (any component entry point), and postInit is also
 *     guarded by ApplicationLoader.applicationInited. A crash at or
 *     before that first Theme touch means the round-1 heal (and its
 *     crash-probe arming) simply never executes.
 *  2. Round 1 scanned the wrong file for the theme selection. Theme
 *     .setCurrentTheme persists the day/night selection into
 *     MessagesController.getGlobalMainSettings() — the "mainconfig"
 *     SharedPreferences, keys "theme"/"nighttheme" — not into
 *     "themeconfig". The "Ryoku" selection therefore survived the
 *     round-1 reset even when the heal did run.
 *
 * This class runs from ApplicationLoader.attachBaseContext (patch
 * 0022), before onCreate and before any component can touch Theme.
 * It deliberately uses ONLY raw SharedPreferences / files APIs
 * addressed by literal file names, plus android.graphics and
 * org.json: no Theme, no RyokuUi, no ApplicationLoader statics —
 * nothing whose class loading could itself walk into the poisoned
 * path. Every block is Throwable-guarded; the heal can never break
 * attachBaseContext.
 *
 * Two tiers, every launch:
 *  - Always-on validation (cheap): Ryoku override-wallpaper entries
 *    whose JSON/file is broken are removed together with the Ryoku
 *    theme selection; a video-background selection pointing at a
 *    missing/empty file is cleared (selection only — the library and
 *    the user's video files are never touched).
 *  - Nuclear reset, only when the crash probe ("amingram_ryoku" /
 *    "crash_probe") is found still armed — i.e. the previous launch
 *    died before RyokuUi.applyToThemePaints() disarmed it: the full
 *    reset of every AminGram-owned persisted display state (below).
 * The probe is then (re)armed for this launch; it is disarmed by
 * RyokuUi once the shared Theme paints exist. The round-1
 * postInitApplication heal remains as a second, converging layer.
 */
public final class RyokuStartupHeal {

    private static final String PREFS_RYOKU = "amingram_ryoku";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PROBE = "crash_probe";

    private static final String PREFS_THEME = "themeconfig";
    private static final String PREFS_MAIN = "mainconfig";
    private static final String KEY_THEME = "theme";
    private static final String KEY_NIGHT_THEME = "nighttheme";
    private static final String KEY_BUBBLE_RADIUS = "bubbleRadius";

    private static final String PREFS_LIBRARY = "amingram_wallpaper_library";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_ACTIVE = "active_id";
    private static final String PREFS_LEGACY_VIDEOBG = "amingram_videobg";
    private static final String KEY_LEGACY_PATH = "path";
    private static final String PREFS_PLUGINS = "amingram_plugins";
    private static final String KEY_VIDEOBG_MODULE = "enabled_video_background";

    private static final String THEME_NAME = "Ryoku";
    private static final String WALLPAPER_FILE = "ryoku_wallpaper.jpg";

    private RyokuStartupHeal() {
    }

    /** Entry point called from ApplicationLoader.attachBaseContext. */
    public static void healOnAttach(Context base) {
        try {
            if (base == null) {
                return;
            }
            SharedPreferences ryoku = prefs(base, PREFS_RYOKU);
            boolean probeArmed = false;
            if (ryoku != null) {
                try {
                    probeArmed = ryoku.getBoolean(KEY_PROBE, false);
                } catch (Throwable ignore) {
                }
            }
            if (probeArmed) {
                nuclearReset(base);
            } else {
                validateRyokuOverrides(base);
                validateVideoBackground(base);
            }
            // (Re)arm the probe for this launch. Disarm happens in
            // RyokuUi.applyToThemePaints() once the shared paints
            // exist, i.e. the UI demonstrably came up.
            if (ryoku != null) {
                try {
                    ryoku.edit().putBoolean(KEY_PROBE, true).commit();
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            // The heal must never break startup itself.
        }
    }

    // ------------------------------------------------------------ tiers

    /** Always-on: broken Ryoku override wallpapers take the Ryoku
     *  theme selection down with them (same intent as the round-1
     *  reset, but the selection is removed from "mainconfig", where
     *  Theme actually persists it). */
    private static void validateRyokuOverrides(Context ctx) {
        try {
            SharedPreferences themeConfig = prefs(ctx, PREFS_THEME);
            if (themeConfig == null) {
                return;
            }
            List<String> overrideKeys = new ArrayList<>();
            boolean bad = false;
            try {
                Map<String, ?> all = themeConfig.getAll();
                for (String key : all.keySet()) {
                    if (key != null && key.startsWith(THEME_NAME) && key.endsWith("_owp")) {
                        overrideKeys.add(key);
                        Object value = all.get(key);
                        if (!(value instanceof String) || !overrideFileOk(ctx, (String) value)) {
                            bad = true;
                        }
                    }
                }
            } catch (Throwable t) {
                bad = true;
            }
            if (!bad) {
                return;
            }
            removeRyokuThemeState(ctx, themeConfig);
        } catch (Throwable ignore) {
        }
    }

    /** Always-on: a video-background selection whose file is gone or
     *  empty is cleared. Only the selection is touched — never the
     *  library items and never the video file itself. */
    private static void validateVideoBackground(Context ctx) {
        try {
            SharedPreferences library = prefs(ctx, PREFS_LIBRARY);
            SharedPreferences legacy = prefs(ctx, PREFS_LEGACY_VIDEOBG);
            String activeId = null;
            String legacyPath = null;
            try {
                if (library != null) {
                    activeId = library.getString(KEY_ACTIVE, null);
                }
            } catch (Throwable ignore) {
            }
            try {
                if (legacy != null) {
                    legacyPath = legacy.getString(KEY_LEGACY_PATH, null);
                }
            } catch (Throwable ignore) {
            }

            if (activeId != null && activeId.length() > 0) {
                if (!activeFileOk(ctx, library, activeId)) {
                    try {
                        if (library != null) {
                            library.edit().remove(KEY_ACTIVE).commit();
                        }
                    } catch (Throwable ignore) {
                    }
                    try {
                        if (legacy != null) {
                            legacy.edit().remove(KEY_LEGACY_PATH).commit();
                        }
                    } catch (Throwable ignore) {
                    }
                }
                return;
            }
            if (legacyPath != null && legacyPath.length() > 0) {
                File file = new File(legacyPath);
                if (!file.isFile() || file.length() <= 0) {
                    try {
                        legacy.edit().remove(KEY_LEGACY_PATH).commit();
                    } catch (Throwable ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /** Probe-armed: reset every AminGram-owned persisted display
     *  state that startup consumes. User data (chats, account, the
     *  wallpaper library items, the video files) is never touched. */
    private static void nuclearReset(Context ctx) {
        // Ryoku theme selection + override wallpapers + files.
        try {
            removeRyokuThemeState(ctx, prefs(ctx, PREFS_THEME));
        } catch (Throwable ignore) {
        }
        // SharedConfig.bubbleRadius persists as a plain int in
        // "mainconfig" (SharedConfig.loadConfig reads it with a
        // default of 17), so removing the key restores the pristine
        // default on the next load.
        try {
            SharedPreferences main = prefs(ctx, PREFS_MAIN);
            if (main != null && main.contains(KEY_BUBBLE_RADIUS)) {
                main.edit().remove(KEY_BUBBLE_RADIUS).commit();
            }
        } catch (Throwable ignore) {
        }
        // Video background: clear the selection and the module flag;
        // the library items and the video files stay on disk.
        try {
            SharedPreferences library = prefs(ctx, PREFS_LIBRARY);
            if (library != null) {
                library.edit().remove(KEY_ACTIVE).commit();
            }
        } catch (Throwable ignore) {
        }
        try {
            SharedPreferences legacy = prefs(ctx, PREFS_LEGACY_VIDEOBG);
            if (legacy != null) {
                legacy.edit().remove(KEY_LEGACY_PATH).commit();
            }
        } catch (Throwable ignore) {
        }
        try {
            SharedPreferences plugins = prefs(ctx, PREFS_PLUGINS);
            if (plugins != null) {
                plugins.edit().putBoolean(KEY_VIDEOBG_MODULE, false).commit();
            }
        } catch (Throwable ignore) {
        }
        // Ryoku master toggle OFF (persisted; the in-process latch in
        // RyokuUi reads this on first use).
        try {
            SharedPreferences ryoku = prefs(ctx, PREFS_RYOKU);
            if (ryoku != null) {
                ryoku.edit().putBoolean(KEY_ENABLED, false).commit();
            }
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------ parts

    /** Removes all Ryoku*_owp entries and every "Ryoku" theme
     *  selection (themeconfig values, plus mainconfig
     *  "theme"/"nighttheme"), and deletes the Ryoku wallpaper files. */
    private static void removeRyokuThemeState(Context ctx, SharedPreferences themeConfig) {
        try {
            if (themeConfig != null) {
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
            }
        } catch (Throwable ignore) {
        }
        try {
            SharedPreferences main = prefs(ctx, PREFS_MAIN);
            if (main != null) {
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
                if (changed) {
                    editor.commit();
                }
            }
        } catch (Throwable ignore) {
        }
        deleteRyokuWallpaperFiles(ctx);
    }

    /** The persisted override JSON must name a real, decodable image
     *  file under filesDir (same rule as the round-1 heal). */
    private static boolean overrideFileOk(Context ctx, String json) {
        try {
            JSONObject object = new JSONObject(json);
            String wall = object.optString("wall", "");
            if (wall == null || wall.length() == 0) {
                return false;
            }
            File file = new File(ctx.getFilesDir(), wall);
            if (!file.isFile() || file.length() <= 0) {
                return false;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            return options.outWidth > 0 && options.outHeight > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The library's active item must resolve to an existing,
     *  non-empty file under filesDir/amingram/wallpapers. */
    private static boolean activeFileOk(Context ctx, SharedPreferences library, String activeId) {
        try {
            if (library == null) {
                return false;
            }
            String raw = library.getString(KEY_ITEMS, null);
            if (raw == null) {
                return false;
            }
            JSONArray items = new JSONArray(raw);
            File dir = new File(new File(ctx.getFilesDir(), "amingram"), "wallpapers");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                if (!activeId.equals(item.optString("id", ""))) {
                    continue;
                }
                String fileName = item.optString("fileName", "");
                if (fileName.length() == 0) {
                    return false;
                }
                File file = new File(dir, fileName);
                return file.isFile() && file.length() > 0;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Delete the fixed wallpaper file + every random-named file the
     *  pre-hotfix applyWallpaper() generated (Ryoku_<accent>_wp<rand>.jpg). */
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
