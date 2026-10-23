package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

/**
 * AminFonts — the standalone font selector (build #34).
 *
 * This is the deliberate replacement for the removed Ryoku UI system:
 * no theme, no wallpapers, no geometry, no startup side effects — just
 * a persisted font choice, applied through two tiny hooks in
 * AndroidUtilities (patch 0023) plus a re-stamp of Theme's shared text
 * paints when the choice changes.
 *
 * Choices: SYSTEM (stock Roboto, zero behaviour change), FRAUNCES,
 * GROTESK (Space Grotesk), MONO (Space Mono). The seven OFL font cuts
 * ship in assets/fonts/ryoku/ (the folder name is kept from the
 * original fonts drop to avoid asset churn; see RYOKU-DESIGN.md for
 * provenance/credits).
 *
 * State discipline (the anti-Ryoku rules):
 *  - the choice is persisted in SharedPreferences "amingram_fonts",
 *    key "family" (a string), written with commit() on change;
 *  - the process-wide state is a single volatile latch, loaded ONCE
 *    from prefs by initLatch() (patch 0023 calls it from
 *    ApplicationLoader.onCreate, where a context is guaranteed);
 *  - the hooks read ONLY the latch — no SharedPreferences I/O, no
 *    context lookups on any hot path — and every hook body is wrapped
 *    in try/catch(Throwable) with a stock (null) fallback, so a font
 *    failure can only ever mean "stock font", never a crash.
 */
public final class AminFonts {

    public static final int FAMILY_SYSTEM = 0;
    public static final int FAMILY_FRAUNCES = 1;
    public static final int FAMILY_GROTESK = 2;
    public static final int FAMILY_MONO = 3;

    /** Display titles, indexed by the FAMILY_* constants. */
    public static final String[] FAMILY_TITLES = {
            "System default", "Fraunces", "Space Grotesk", "Space Mono"
    };

    private static final String PREFS = "amingram_fonts";
    private static final String KEY_FAMILY = "family";

    private static final String NAME_SYSTEM = "SYSTEM";
    private static final String NAME_FRAUNCES = "FRAUNCES";
    private static final String NAME_GROTESK = "GROTESK";
    private static final String NAME_MONO = "MONO";

    /** Bundled font assets (OFL), kept under their original folder. */
    private static final String DIR = "fonts/ryoku/";

    /** The whole runtime state: hooks read this and nothing else. */
    private static volatile int latch = FAMILY_SYSTEM;

    // Lazily loaded cuts for the latched family (guarded by FACE_LOCK).
    private static final Object FACE_LOCK = new Object();
    private static int facesFamily = -1;
    private static Typeface faceRegular;
    private static Typeface faceMedium;
    private static Typeface faceBold;

    private AminFonts() {
    }

    // ---------------------------------------------------------------- latch

    /** Load the latch from prefs. Called once from
     *  ApplicationLoader.onCreate (patch 0023); context is guaranteed
     *  there. Never throws. */
    public static void initLatch() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return;
            }
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            latch = familyFromName(prefs.getString(KEY_FAMILY, NAME_SYSTEM));
        } catch (Throwable t) {
            latch = FAMILY_SYSTEM;
        }
    }

    /** The currently latched family (FAMILY_* constant). */
    public static int family() {
        return latch;
    }

    /** Display title of the currently latched family (hub row value). */
    public static String familyTitle() {
        int family = latch;
        return family >= 0 && family < FAMILY_TITLES.length
                ? FAMILY_TITLES[family] : FAMILY_TITLES[FAMILY_SYSTEM];
    }

    private static int familyFromName(String name) {
        if (NAME_FRAUNCES.equals(name)) {
            return FAMILY_FRAUNCES;
        }
        if (NAME_GROTESK.equals(name)) {
            return FAMILY_GROTESK;
        }
        if (NAME_MONO.equals(name)) {
            return FAMILY_MONO;
        }
        return FAMILY_SYSTEM;
    }

    private static String nameForFamily(int family) {
        switch (family) {
            case FAMILY_FRAUNCES:
                return NAME_FRAUNCES;
            case FAMILY_GROTESK:
                return NAME_GROTESK;
            case FAMILY_MONO:
                return NAME_MONO;
            default:
                return NAME_SYSTEM;
        }
    }

    // ---------------------------------------------------------------- hooks

    /**
     * Hook for AndroidUtilities.getTypeface (patch 0023). Remaps ONLY
     * the three main text paths; every other asset path returns null
     * (stock). Reads only the latch; fully guarded.
     */
    public static Typeface typefaceFor(String assetPath) {
        try {
            if (assetPath == null || latch == FAMILY_SYSTEM) {
                return null;
            }
            switch (assetPath) {
                case "fonts/rregular.ttf":
                    return regular();
                case "fonts/rmedium.ttf":
                    return medium();
                case "fonts/ritalic.ttf":
                    // No bundled italic cuts; the regular cut is used
                    // and Android synthesises the slant.
                    return regular();
                default:
                    return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Hook for AndroidUtilities.bold() (patch 0023): the chosen
     * family's bold cut, or null (stock) for SYSTEM. Fully guarded —
     * bold() sits under every paint creation in the app.
     */
    public static Typeface boldTypeface() {
        try {
            if (latch == FAMILY_SYSTEM) {
                return null;
            }
            return bold();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- faces

    public static Typeface regular() {
        return face(0);
    }

    public static Typeface medium() {
        return face(1);
    }

    public static Typeface bold() {
        return face(2);
    }

    /**
     * Lazy, cached cut loading. The cuts are loaded at most once per
     * family (first hook call after the latch is set or changed) and
     * kept in static fields; a family change invalidates the cache.
     */
    private static Typeface face(int slot) {
        try {
            int family = latch;
            if (family == FAMILY_SYSTEM) {
                return null;
            }
            synchronized (FACE_LOCK) {
                if (facesFamily != family) {
                    faceRegular = null;
                    faceMedium = null;
                    faceBold = null;
                    facesFamily = family;
                }
                Typeface cut = slot == 0 ? faceRegular : slot == 1 ? faceMedium : faceBold;
                if (cut == null) {
                    cut = load(assetFor(family, slot));
                    if (slot == 0) {
                        faceRegular = cut;
                    } else if (slot == 1) {
                        faceMedium = cut;
                    } else {
                        faceBold = cut;
                    }
                }
                return cut;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Cut mapping: slot 0 = regular, 1 = medium, 2 = bold. */
    private static String assetFor(int family, int slot) {
        switch (family) {
            case FAMILY_FRAUNCES:
                // Fraunces ships as SemiBold (600) + Bold (700) only:
                // SemiBold serves as the regular cut, Bold as both
                // the medium and the bold cut.
                return DIR + (slot == 0 ? "fraunces_semibold.ttf" : "fraunces_bold.ttf");
            case FAMILY_GROTESK:
                return DIR + (slot == 0 ? "spacegrotesk_regular.ttf"
                        : slot == 1 ? "spacegrotesk_medium.ttf" : "spacegrotesk_bold.ttf");
            case FAMILY_MONO:
                // Space Mono ships as Regular (400) + Bold (700) only.
                return DIR + (slot == 0 ? "spacemono_regular.ttf" : "spacemono_bold.ttf");
            default:
                return null;
        }
    }

    private static Typeface load(String assetPath) {
        try {
            if (assetPath == null) {
                return null;
            }
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) {
                return null;
            }
            return Typeface.createFromAsset(ctx.getAssets(), assetPath);
        } catch (Throwable t) {
            return null;
        }
    }

    // --------------------------------------------------------------- change

    /**
     * Change the font family (hub -> App font). Latches first so every
     * hook sees the new state at once, persists with commit(), drops
     * the cached stock bold face so AndroidUtilities.bold() re-resolves
     * through the hook, re-stamps the shared paints and posts the same
     * restyle notification a theme change produces.
     */
    public static void setFamily(Context context, int family) {
        latch = family;
        try {
            Context ctx = context != null ? context : ApplicationLoader.applicationContext;
            if (ctx != null) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_FAMILY, nameForFamily(family)).commit();
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        try {
            AndroidUtilities.mediumTypeface = null;
        } catch (Throwable ignore) {
        }
        stampPaints();
        try {
            NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.didSetNewTheme, false, true);
        } catch (Throwable ignore) {
        }
    }

    // --------------------------------------------------------------- paints

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
     * Stamp Theme's shared text paints for the latched family (or
     * restore stock for SYSTEM). This is exactly the paint set the
     * removed Ryoku system's paint stamping touched: the bold-based
     * paints resolve through AndroidUtilities.bold() (which itself
     * goes through the hook), body/time paints take the family's
     * regular cut, time-bold its bold cut, and the code paints the
     * regular cut — for SYSTEM, body/time restore to no-typeface and
     * the code paints to Typeface.MONOSPACE, matching upstream.
     */
    public static void stampPaints() {
        try {
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

            if (latch != FAMILY_SYSTEM) {
                Typeface regular = regular();
                Typeface boldCut = bold();
                setFace(Theme.dialogs_messagePaint, regular);
                setFace(Theme.chat_msgTextPaint, regular);
                setFace(Theme.dialogs_timePaint, regular);
                setFace(Theme.dialogs_timePaintBold, boldCut);
                setFace(Theme.chat_timePaint, regular);
                setFace(Theme.chat_msgTextCodePaint, regular);
                setFace(Theme.chat_msgTextCode2Paint, regular);
                setFace(Theme.chat_msgTextCode3Paint, regular);
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
}
