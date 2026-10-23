package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Typeface;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ryoku UI — the dossier identity layer for AminGram (inspired by ryoku.dev).
 *
 * One master toggle ("Ryoku UI", hub -> System) gates:
 *  - the typeface remap (Fraunces display / Space Grotesk sans / Space Mono mono),
 *    hooked centrally into AndroidUtilities.getTypeface + AndroidUtilities.bold
 *    and into Theme's shared text paints via applyToThemePaints();
 *  - the bundled "Ryoku" theme (bone on near-black, burnt-orange accent) applied
 *    as the current theme while ON; the previous theme is restored on OFF;
 *  - sharp geometry: shared bubble radius is dropped to 3 while ON (restored on
 *    OFF) and centrally-built round-rect drawables are clamped via clampRadius()
 *    from Theme's drawable factories + AlertDialog (patch 0022).
 *
 * Hotfix (build #31 field defects):
 *  - The toggle state is LATCHED in-process (enabledLatch): the hot hooks no
 *    longer re-read SharedPreferences through ApplicationLoader
 *    .applicationContext on every call, so every reader in the process sees
 *    one consistent state, and every hook body is fully guarded — a Ryoku
 *    failure can only ever fall back to stock, never throw into
 *    AndroidUtilities/Theme startup code.
 *  - healAtStartup() runs from ApplicationLoader.postInitApplication
 *    (patch 0022) before Theme consumes any persisted state: it validates
 *    the Ryoku override-wallpaper entries in "themeconfig" (JSON parses,
 *    the referenced file exists, is non-empty and decodes as an image) and
 *    honours a crash probe — if the previous launch died before the shared
 *    paints were created, the Ryoku theme selection + overrides are reset
 *    to stock and the toggle is forced OFF. A bad wallpaper state can
 *    never brick the install again.
 *  - applyWallpaper() writes ONE fixed file (ryoku_wallpaper.jpg), clears
 *    the previous override through upstream bookkeeping first (no random
 *    filename accumulation, no dangling references), and refuses to run
 *    while a video wallpaper is selected.
 *
 * Everything here is defensive: any failure falls back to stock behaviour.
 * Fonts are OFL-licensed (see RYOKU-DESIGN.md); wallpapers are generated from
 * public-domain / CC0 sculpture photographs (see design/ryoku/CREDITS.md).
 */
public class RyokuUi {

    public static final String THEME_NAME = "Ryoku";
    public static final int COLOR_BG = 0xFF0A0A0A;
    public static final int COLOR_BONE = 0xFFCDC4BA;
    public static final int COLOR_ACCENT = 0xFFCE5E32;
    public static final int COLOR_HAIRLINE = 0xFF2A2723;
    public static final int COLOR_GRAY = 0xFF958F87;
    public static final int BUBBLE_RADIUS_ON = 3;

    private static final String PREFS = "amingram_ryoku";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BOOTSTRAPPED = "bootstrapped";
    private static final String KEY_PREV_THEME = "prev_theme_key";
    private static final String KEY_PREV_BUBBLE = "prev_bubble_radius";
    private static final String KEY_PROBE = "crash_probe";

    /** The one wallpaper file applyWallpaper() ever writes. */
    private static final String WALLPAPER_FILE = "ryoku_wallpaper.jpg";

    /**
     * In-process latch of the master toggle: -1 = not read yet, 0 = OFF,
     * 1 = ON. The AndroidUtilities hooks run on hot paths (every paint
     * creation, every drawable factory); they must not depend on a
     * SharedPreferences read through a possibly-not-yet-set static context
     * succeeding on every single call.
     */
    private static volatile int enabledLatch = -1;

    private static Typeface displayTypeface;
    private static Typeface displayBoldTypeface;
    private static Typeface sansTypeface;
    private static Typeface sansMediumTypeface;
    private static Typeface sansBoldTypeface;
    private static Typeface monoTypeface;
    private static Typeface monoBoldTypeface;
    private static Typeface sansItalicTypeface;
    private static Typeface sansMediumItalicTypeface;
    private static Typeface displayBoldItalicTypeface;

    // ------------------------------------------------------------------ prefs

    private static SharedPreferences prefs(Context context) {
        Context ctx = context != null ? context : ApplicationLoader.applicationContext;
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Master toggle state. Default ON. Once read successfully the value is
     * latched for the process; before that (no context yet) it reports OFF
     * (stock) WITHOUT latching, so the first real read still wins.
     */
    public static boolean isEnabled(Context context) {
        int latch = enabledLatch;
        if (latch != -1) {
            return latch == 1;
        }
        SharedPreferences p = prefs(context);
        if (p == null) {
            return false;
        }
        try {
            boolean on = p.getBoolean(KEY_ENABLED, true);
            enabledLatch = on ? 1 : 0;
            return on;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * First-run bootstrap: the toggle defaults ON, so the first time the hub is
     * created we run the same side effects as a manual OFF->ON transition
     * (theme + bubble radius), recording the stock state for a later restore.
     */
    public static void bootstrapIfNeeded(Context context) {
        SharedPreferences p = prefs(context);
        if (p == null) {
            return;
        }
        try {
            if (p.getBoolean(KEY_BOOTSTRAPPED, false)) {
                return;
            }
            p.edit().putBoolean(KEY_BOOTSTRAPPED, true).apply();
            if (isEnabled(context)) {
                applyOnSideEffects(context);
                refreshTypefaces();
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Toggle the whole identity layer. Called from the hub on the UI thread. */
    public static void setEnabled(Context context, boolean on) {
        // Latch first: every hook in the process sees the new state at once,
        // even if the disk write below is interrupted.
        enabledLatch = on ? 1 : 0;
        SharedPreferences p = prefs(context);
        if (p != null) {
            try {
                // commit() (not apply()): the toggle must survive an immediate
                // process death — an async flush can lose it.
                p.edit().putBoolean(KEY_ENABLED, on).putBoolean(KEY_BOOTSTRAPPED, true).commit();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        try {
            if (on) {
                applyOnSideEffects(context);
            } else {
                applyOffSideEffects(context);
            }
            refreshTypefaces();
            // Force the same restyle wave a real theme change produces, even
            // when the side effects did not switch the theme (e.g. it was
            // already stock): without it already-built screens keep the old
            // faces and the toggle looks dead. Same arg shape Theme uses.
            postThemeRefresh();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void postThemeRefresh() {
        try {
            NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.didSetNewTheme, false, true);
        } catch (Throwable ignore) {
        }
    }

    private static void applyOnSideEffects(Context context) {
        SharedPreferences p = prefs(context);
        // Theme: remember where we came from, then switch to Ryoku.
        try {
            Theme.ThemeInfo current = Theme.getCurrentTheme();
            Theme.ThemeInfo ryoku = findTheme(THEME_NAME);
            if (ryoku != null) {
                if (current != null && !THEME_NAME.equals(current.getKey()) && p != null) {
                    p.edit().putString(KEY_PREV_THEME, current.getKey()).apply();
                }
                if (current == null || !THEME_NAME.equals(current.getKey())) {
                    Theme.applyTheme(ryoku);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        // Bubbles: record the user's radius once, then go sharp.
        try {
            if (p != null && !p.contains(KEY_PREV_BUBBLE)) {
                p.edit().putInt(KEY_PREV_BUBBLE, SharedConfig.bubbleRadius).apply();
            }
            if (SharedConfig.bubbleRadius != BUBBLE_RADIUS_ON) {
                SharedConfig.bubbleRadius = BUBBLE_RADIUS_ON;
                SharedConfig.saveConfig();
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void applyOffSideEffects(Context context) {
        SharedPreferences p = prefs(context);
        // Theme: go back to whatever was current before Ryoku was applied.
        try {
            Theme.ThemeInfo current = Theme.getCurrentTheme();
            if (current != null && THEME_NAME.equals(current.getKey()) && p != null) {
                String prev = p.getString(KEY_PREV_THEME, null);
                Theme.ThemeInfo target = prev != null ? findTheme(prev) : null;
                if (target != null && target != current) {
                    Theme.applyTheme(target);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        // Bubbles: restore the recorded radius.
        try {
            if (p != null && p.contains(KEY_PREV_BUBBLE)) {
                int prev = p.getInt(KEY_PREV_BUBBLE, 17);
                if (SharedConfig.bubbleRadius != prev) {
                    SharedConfig.bubbleRadius = prev;
                    SharedConfig.saveConfig();
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Re-stamp shared paints + drop the cached stock bold face after a toggle. */
    private static void refreshTypefaces() {
        try {
            AndroidUtilities.mediumTypeface = null;
        } catch (Throwable ignore) {
        }
        applyToThemePaints();
    }

    // ------------------------------------------------------------- self-heal

    /**
     * Startup self-heal, called from ApplicationLoader.postInitApplication
     * (patch 0022) — the earliest point our code controls, before Theme
     * reads any persisted theme/wallpaper state.
     *
     * Validates every persisted Ryoku state that can break a launch:
     *  - "themeconfig" override-wallpaper entries Ryoku*_owp: the JSON must
     *    parse and its "wall" file must exist under filesDir, be non-empty
     *    and decode as an image;
     *  - the crash probe (KEY_PROBE): armed here on every launch and
     *    disarmed by applyToThemePaints() once the shared paints exist
     *    (i.e. the app demonstrably reached its UI). Finding it still armed
     *    means the previous launch died during startup.
     * On ANY inconsistency: all Ryoku*_owp entries are removed, any
     * themeconfig value pointing at "Ryoku" (incl. the "theme" selection)
     * is removed so Telegram falls back to its default theme, the Ryoku
     * wallpaper files are deleted, and — when the probe fired — the toggle
     * is forced OFF. Never throws.
     */
    public static void healAtStartup() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return;
            }
            SharedPreferences ryoku = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            SharedPreferences themeConfig = ctx.getSharedPreferences("themeconfig", Context.MODE_PRIVATE);

            boolean probeArmed = false;
            try {
                probeArmed = ryoku.getBoolean(KEY_PROBE, false);
            } catch (Throwable ignore) {
            }

            List<String> overrideKeys = new ArrayList<>();
            boolean overrideBad = false;
            try {
                Map<String, ?> all = themeConfig.getAll();
                for (String key : all.keySet()) {
                    if (key != null && key.startsWith(THEME_NAME) && key.endsWith("_owp")) {
                        overrideKeys.add(key);
                        Object value = all.get(key);
                        if (!(value instanceof String) || !overrideFileOk((String) value)) {
                            overrideBad = true;
                        }
                    }
                }
            } catch (Throwable t) {
                overrideBad = true;
            }

            if (probeArmed || overrideBad) {
                try {
                    SharedPreferences.Editor editor = themeConfig.edit();
                    for (String key : overrideKeys) {
                        editor.remove(key);
                    }
                    // Any selection value pointing at the Ryoku theme
                    // ("theme", a night-theme key, ...) goes too: stock
                    // fallback beats a second crash.
                    for (Map.Entry<String, ?> entry : themeConfig.getAll().entrySet()) {
                        if (THEME_NAME.equals(entry.getValue())) {
                            editor.remove(entry.getKey());
                        }
                    }
                    editor.commit();
                } catch (Throwable ignore) {
                }
                deleteRyokuWallpaperFiles();
                if (probeArmed) {
                    enabledLatch = 0;
                    try {
                        ryoku.edit().putBoolean(KEY_ENABLED, false).commit();
                    } catch (Throwable ignore) {
                    }
                }
                FileLog.e(new Exception("RyokuUi: healed persisted state at startup"
                        + " (probe=" + probeArmed + ", overrideBad=" + overrideBad + ")"));
            }

            // Arm the probe for this launch.
            try {
                ryoku.edit().putBoolean(KEY_PROBE, true).apply();
            } catch (Throwable ignore) {
            }
        } catch (Throwable t) {
            // The heal must never break startup itself.
        }
    }

    /** The persisted override JSON must name a real, decodable image file. */
    private static boolean overrideFileOk(String json) {
        try {
            JSONObject object = new JSONObject(json);
            String wall = object.optString("wall", "");
            if (wall == null || wall.length() == 0) {
                return false;
            }
            File file = new File(ApplicationLoader.getFilesDirFixed(), wall);
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

    /** Delete the fixed wallpaper file + every random-named file the
     *  pre-hotfix applyWallpaper() generated (Ryoku_<accent>_wp<rand>.jpg). */
    private static void deleteRyokuWallpaperFiles() {
        try {
            File dir = ApplicationLoader.getFilesDirFixed();
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

    /** Disarm the crash probe: the shared paints exist, the UI was reached. */
    private static void disarmCrashProbe() {
        try {
            SharedPreferences p = prefs(null);
            if (p != null && p.getBoolean(KEY_PROBE, false)) {
                p.edit().putBoolean(KEY_PROBE, false).apply();
            }
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ theme

    public static Theme.ThemeInfo findTheme(String key) {
        try {
            if (Theme.themes != null) {
                for (Theme.ThemeInfo info : Theme.themes) {
                    if (info != null && key.equals(info.getKey())) {
                        return info;
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return null;
    }

    /** Hub row: apply the Ryoku theme on demand (available regardless of toggle). */
    public static boolean applyRyokuTheme(Context context) {
        try {
            Theme.ThemeInfo ryoku = findTheme(THEME_NAME);
            if (ryoku == null) {
                return false;
            }
            Theme.ThemeInfo current = Theme.getCurrentTheme();
            SharedPreferences p = prefs(context);
            if (current != null && !THEME_NAME.equals(current.getKey()) && p != null) {
                p.edit().putString(KEY_PREV_THEME, current.getKey()).apply();
            }
            if (current == null || !THEME_NAME.equals(current.getKey())) {
                Theme.applyTheme(ryoku);
            }
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    public static String currentThemeName() {
        try {
            Theme.ThemeInfo current = Theme.getCurrentTheme();
            return current != null ? current.getName() : "—";
        } catch (Throwable t) {
            return "—";
        }
    }

    // -------------------------------------------------------------- wallpapers

    public static final String[] WALLPAPER_NAMES = {
            "Marcus Aurelius — marble, dithered",
            "Laocoön — marble, dithered",
            "Socrates — marble, dithered"
    };

    /**
     * Apply bundled wallpaper {@code index} (0..2) through Telegram's standard
     * per-theme override path, on the Ryoku theme (switching to it if needed).
     *
     * Hotfix rules: ONE fixed output file (no random-name accumulation);
     * the previous override is cleared through upstream's own bookkeeping
     * BEFORE the new file is written, so its delete step can never remove
     * the file the new override points at; and the call refuses to run
     * while a video wallpaper is selected — the video layer owns the chat
     * background then, and stacking a static override under it is the
     * interplay that preceded the build #31 startup crash.
     */
    public static boolean applyWallpaper(Context context, int index) {
        if (index < 0 || index >= WALLPAPER_NAMES.length) {
            return false;
        }
        try {
            Context ctx = context != null ? context : ApplicationLoader.applicationContext;
            if (ctx == null) {
                return false;
            }
            try {
                if (VideoBackgroundHelper.getVideoPath(ctx) != null) {
                    return false;
                }
            } catch (Throwable ignore) {
            }
            Bitmap bitmap;
            InputStream in = ctx.getAssets().open("ryoku/wallpaper_" + (index + 1) + ".webp", AssetManager.ACCESS_STREAMING);
            try {
                bitmap = BitmapFactory.decodeStream(in);
            } finally {
                in.close();
            }
            if (bitmap == null) {
                return false;
            }
            Theme.ThemeInfo theme = findTheme(THEME_NAME);
            if (theme == null) {
                theme = Theme.getActiveTheme();
            }
            if (theme == null) {
                return false;
            }
            // Clear the old override first (removes its themeconfig entry and
            // its files), then sweep any stale pre-hotfix files.
            try {
                if (theme.overrideWallpaper != null) {
                    theme.setOverrideWallpaper(null);
                }
            } catch (Throwable ignore) {
            }
            deleteRyokuWallpaperFiles();
            File outFile = new File(ApplicationLoader.getFilesDirFixed(), WALLPAPER_FILE);
            FileOutputStream out = new FileOutputStream(outFile);
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 87, out);
            } finally {
                out.close();
            }
            bitmap.recycle();
            Theme.OverrideWallpaperInfo info = new Theme.OverrideWallpaperInfo();
            info.fileName = WALLPAPER_FILE;
            info.originalFileName = WALLPAPER_FILE;
            info.slug = "";
            info.color = 0;
            info.gradientColor1 = 0;
            info.gradientColor2 = 0;
            info.gradientColor3 = 0;
            info.rotation = 0;
            info.isBlurred = false;
            info.isMotion = false;
            info.intensity = 0.5f;
            theme.setOverrideWallpaper(info);
            Theme.ThemeInfo active = Theme.getActiveTheme();
            if (active != theme) {
                applyRyokuTheme(context);
            } else {
                Theme.reloadWallpaper(true);
            }
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    // ------------------------------------------------------------------ fonts

    private static Typeface load(String assetPath) {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return null;
            }
            return Typeface.createFromAsset(ctx.getAssets(), assetPath);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Typeface italicOf(Typeface base, Typeface cached) {
        if (base == null) {
            return null;
        }
        if (cached != null) {
            return cached;
        }
        try {
            return Typeface.create(base, Typeface.ITALIC);
        } catch (Throwable t) {
            return base;
        }
    }

    /** Fraunces SemiBold — display face (titles, names, masthead). */
    public static Typeface display() {
        if (displayTypeface == null) {
            displayTypeface = load("fonts/ryoku/fraunces_semibold.ttf");
        }
        return displayTypeface;
    }

    /** Fraunces Bold. */
    public static Typeface displayBold() {
        if (displayBoldTypeface == null) {
            displayBoldTypeface = load("fonts/ryoku/fraunces_bold.ttf");
        }
        return displayBoldTypeface;
    }

    /** Space Grotesk Regular — body face. */
    public static Typeface sans() {
        if (sansTypeface == null) {
            sansTypeface = load("fonts/ryoku/spacegrotesk_regular.ttf");
        }
        return sansTypeface;
    }

    /** Space Grotesk Medium. */
    public static Typeface sansMedium() {
        if (sansMediumTypeface == null) {
            sansMediumTypeface = load("fonts/ryoku/spacegrotesk_medium.ttf");
        }
        return sansMediumTypeface;
    }

    /** Space Grotesk Bold. */
    public static Typeface sansBold() {
        if (sansBoldTypeface == null) {
            sansBoldTypeface = load("fonts/ryoku/spacegrotesk_bold.ttf");
        }
        return sansBoldTypeface;
    }

    /** Space Mono Regular — timestamps + micro-labels. */
    public static Typeface mono() {
        if (monoTypeface == null) {
            monoTypeface = load("fonts/ryoku/spacemono_regular.ttf");
        }
        return monoTypeface;
    }

    /** Space Mono Bold. */
    public static Typeface monoBold() {
        if (monoBoldTypeface == null) {
            monoBoldTypeface = load("fonts/ryoku/spacemono_bold.ttf");
        }
        return monoBoldTypeface;
    }

    /**
     * Central remap called from AndroidUtilities.getTypeface (patch 0022).
     * Returns the Ryoku face for a stock asset path, or null to keep stock.
     * gram.ttf (Telegram's glyph font) and everything unknown stay stock.
     * Fully guarded: any failure means "keep stock", never a throw into
     * upstream code.
     */
    public static Typeface typefaceFor(String assetPath) {
        try {
            if (assetPath == null || !isEnabled(null)) {
                return null;
            }
            switch (assetPath) {
                case "fonts/rmedium.ttf":
                    return sansMedium();
                case "fonts/rregular.ttf":
                    return sans();
                case "fonts/rbold.ttf":
                    return sansBold();
                case "fonts/ritalic.ttf":
                    if (sansItalicTypeface == null) {
                        sansItalicTypeface = italicOf(sans(), null);
                    }
                    return sansItalicTypeface;
                case "fonts/rmediumitalic.ttf":
                    if (sansMediumItalicTypeface == null) {
                        sansMediumItalicTypeface = italicOf(sansMedium(), null);
                    }
                    return sansMediumItalicTypeface;
                case "fonts/rmono.ttf":
                case "fonts/rmono_var.ttf":
                case "fonts/num.otf":
                    return mono();
                case "fonts/rcondensedbold.ttf":
                    return display();
                case "fonts/rextrabold.ttf":
                case "fonts/mw_bold.ttf":
                    return displayBold();
                case "fonts/mw_bolditalic.ttf":
                    if (displayBoldItalicTypeface == null) {
                        displayBoldItalicTypeface = italicOf(displayBold(), null);
                    }
                    return displayBoldItalicTypeface;
                default:
                    return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called from AndroidUtilities.bold() (patch 0022): Fraunces when ON.
     *  Fully guarded — bold() sits under every paint creation in the app. */
    public static Typeface boldTypeface() {
        try {
            if (!isEnabled(null)) {
                return null;
            }
            return display();
        } catch (Throwable t) {
            return null;
        }
    }

    // ----------------------------------------------------------------- paints

    private static void setFace(Paint paint, Typeface face) {
        if (paint != null && face != null) {
            paint.setTypeface(face);
        }
    }

    private static void setFace(Paint[] paints, Typeface face) {
        if (paints != null && face != null) {
            for (Paint paint : paints) {
                setFace(paint, face);
            }
        }
    }

    /** Restore a paint's stock face: upstream never sets one on these
     *  paints, so stock == no typeface (Paint's built-in default). */
    private static void clearFace(Paint paint) {
        if (paint != null) {
            try {
                paint.setTypeface(null);
            } catch (Throwable ignore) {
            }
        }
    }

    private static void clearFace(Paint[] paints) {
        if (paints != null) {
            for (Paint paint : paints) {
                clearFace(paint);
            }
        }
    }

    /**
     * Stamp Theme's shared text paints with the Ryoku faces (or restore stock).
     * Called from Theme.createDialogsResources / createCommonChatResources /
     * createCommonDialogResources (patch 0022) right after the paints are
     * (re)built, and on every toggle. Bold-based paints are re-stamped with
     * AndroidUtilities.bold() in both states, which itself resolves through
     * the Ryoku hook and the latched toggle state. Also disarms the startup
     * crash probe: reaching this call means the app got its UI up.
     */
    public static void applyToThemePaints() {
        disarmCrashProbe();
        try {
            // Bold/display paints — state-correct via the bold() hook.
            Typeface bold = AndroidUtilities.bold();
            setFace(Theme.dialogs_namePaint, bold);
            setFace(Theme.dialogs_nameEncryptedPaint, bold);
            setFace(Theme.dialogs_searchNamePaint, bold);
            setFace(Theme.dialogs_searchNameEncryptedPaint, bold);
            setFace(Theme.dialogs_messageNamePaint, bold);
            setFace(Theme.dialogs_archiveTextPaint, bold);
            setFace(Theme.dialogs_archiveTextPaintSmall, bold);
            setFace(Theme.dialogs_tagTextPaint, bold);
            setFace(Theme.dialogs_countTextPaint, bold);
            setFace(Theme.dialogs_countTextPaint2, bold);
            setFace(Theme.chat_namePaint, bold);
            setFace(Theme.chat_replyNamePaint, bold);
            setFace(Theme.chat_topicTextPaint, bold);
            setFace(Theme.chat_docNamePaint, bold);
            setFace(Theme.chat_locationTitlePaint, bold);
            setFace(Theme.chat_audioTitlePaint, bold);
            setFace(Theme.chat_gamePaint, bold);
            setFace(Theme.chat_infoBoldPaint, bold);
            setFace(Theme.chat_msgBotButtonPaint, bold);
            setFace(Theme.chat_stickerCommentCountPaint, bold);
            setFace(Theme.chat_contactNamePaint, bold);
            setFace(Theme.chat_actionTextPaint, bold);

            if (isEnabled(null)) {
                Typeface sans = sans();
                Typeface mono = mono();
                Typeface monoBold = monoBold();
                setFace(Theme.dialogs_messagePaint, sans);
                setFace(Theme.chat_msgTextPaint, sans);
                setFace(Theme.dialogs_timePaint, mono);
                setFace(Theme.dialogs_timePaintBold, monoBold);
                setFace(Theme.chat_timePaint, mono);
                setFace(Theme.chat_msgTextCodePaint, mono);
                setFace(Theme.chat_msgTextCode2Paint, mono);
                setFace(Theme.chat_msgTextCode3Paint, mono);
            } else {
                // Stock: body/time paints carry no typeface; code is MONOSPACE.
                clearFace(Theme.dialogs_messagePaint);
                clearFace(Theme.chat_msgTextPaint);
                clearFace(Theme.dialogs_timePaint);
                clearFace(Theme.dialogs_timePaintBold);
                clearFace(Theme.chat_timePaint);
                setFace(Theme.chat_msgTextCodePaint, Typeface.MONOSPACE);
                setFace(Theme.chat_msgTextCode2Paint, Typeface.MONOSPACE);
                setFace(Theme.chat_msgTextCode3Paint, Typeface.MONOSPACE);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    // --------------------------------------------------------------- geometry

    /**
     * Clamp a round-rect radius (px) to the sharp dossier value while ON.
     * Called from Theme's drawable factories (patch 0022), which serve
     * dialogs, menus, popups, sheets and most constructed backgrounds.
     * Fully guarded: any failure returns the stock radius.
     */
    public static int clampRadius(int radiusPx) {
        try {
            if (radiusPx <= 0 || !isEnabled(null)) {
                return radiusPx;
            }
            int sharp = AndroidUtilities.dp(2);
            int threshold = AndroidUtilities.dp(3);
            return radiusPx > threshold ? sharp : radiusPx;
        } catch (Throwable t) {
            return radiusPx;
        }
    }

    /** AlertDialog corner radius in dp: 2 while ON, the stock value otherwise. */
    public static int dialogRadiusDp(int stockDp) {
        try {
            return isEnabled(null) ? 2 : stockDp;
        } catch (Throwable t) {
            return stockDp;
        }
    }
}
