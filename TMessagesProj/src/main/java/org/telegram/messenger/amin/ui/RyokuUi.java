package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Typeface;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

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

    /** Master toggle state. Default ON; false whenever state cannot be read. */
    public static boolean isEnabled(Context context) {
        SharedPreferences p = prefs(context);
        if (p == null) {
            return false;
        }
        try {
            return p.getBoolean(KEY_ENABLED, true);
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
        SharedPreferences p = prefs(context);
        if (p == null) {
            return;
        }
        try {
            p.edit().putBoolean(KEY_ENABLED, on).putBoolean(KEY_BOOTSTRAPPED, true).apply();
            if (on) {
                applyOnSideEffects(context);
            } else {
                applyOffSideEffects(context);
            }
            refreshTypefaces();
        } catch (Throwable t) {
            FileLog.e(t);
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
            String fileName = theme.generateWallpaperName(null, false);
            File outFile = new File(ApplicationLoader.getFilesDirFixed(), fileName);
            FileOutputStream out = new FileOutputStream(outFile);
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 87, out);
            } finally {
                out.close();
            }
            bitmap.recycle();
            Theme.OverrideWallpaperInfo info = new Theme.OverrideWallpaperInfo();
            info.fileName = fileName;
            info.originalFileName = fileName;
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
     */
    public static Typeface typefaceFor(String assetPath) {
        if (assetPath == null || !isEnabled(null)) {
            return null;
        }
        try {
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

    /** Called from AndroidUtilities.bold() (patch 0022): Fraunces when ON. */
    public static Typeface boldTypeface() {
        if (!isEnabled(null)) {
            return null;
        }
        return display();
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

    /**
     * Stamp Theme's shared text paints with the Ryoku faces (or restore stock).
     * Called from Theme.createCommonChatResources / createDialogsResources
     * (patch 0022) right after the paints are (re)built, and on every toggle.
     * Bold-based paints are re-stamped with AndroidUtilities.bold() in both
     * states, which itself resolves through the Ryoku hook.
     */
    public static void applyToThemePaints() {
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
                setFace(Theme.dialogs_messagePaint, Typeface.DEFAULT);
                setFace(Theme.chat_msgTextPaint, Typeface.DEFAULT);
                setFace(Theme.dialogs_timePaint, Typeface.DEFAULT);
                setFace(Theme.dialogs_timePaintBold, Typeface.DEFAULT);
                setFace(Theme.chat_timePaint, Typeface.DEFAULT);
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
     */
    public static int clampRadius(int radiusPx) {
        if (radiusPx <= 0 || !isEnabled(null)) {
            return radiusPx;
        }
        try {
            int sharp = AndroidUtilities.dp(2);
            int threshold = AndroidUtilities.dp(3);
            return radiusPx > threshold ? sharp : radiusPx;
        } catch (Throwable t) {
            return radiusPx;
        }
    }

    /** AlertDialog corner radius in dp: 2 while ON, the stock value otherwise. */
    public static int dialogRadiusDp(int stockDp) {
        return isEnabled(null) ? 2 : stockDp;
    }
}
