package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * Hub v3 "dossier" skin — 100% hub-local (design/hub-v3/DESIGN.md §1).
 *
 * Every color below is a hub constant and every typeface is loaded by
 * hub code from the bundled assets (assets/fonts/ryoku/ — the same
 * OFL cuts the App Font selector ships) and set ONLY on hub views.
 * Zero patches, zero Theme hooks, no .attheme, no paint re-stamps,
 * nothing persisted into themeconfig/mainconfig: leaving the hub
 * leaves the skin.
 *
 * Switch coloring: upstream Switch resolves its four colors through a
 * Theme.ResourcesProvider (Theme.getColor(key, provider) delegates to
 * the provider unconditionally), so SWITCH_PROVIDER hands back the
 * dossier colors for exactly the keys HubCommon sets via setColors()
 * and the ripple-selector keys; any other key delegates to the
 * current theme, which no hub switch queries.
 */
public final class HubSkin {

    // Palette (hub-v3/DESIGN.md §2).
    public static final int BG = 0xFF0A0A0A;
    public static final int SURFACE = 0xFF14120F;
    public static final int SURFACE2 = 0xFF1A1712;
    public static final int BONE = 0xFFCDC4BA;
    public static final int SECONDARY = 0xFFB0A9A0;
    public static final int GRAY = 0xFF958F87;
    public static final int FAINT = 0xFF7A756E;
    public static final int ACCENT = 0xFFCE5E32;
    public static final int ACCENT_DEEP = 0xFFA8481F;
    public static final int ACCENT_LIGHT = 0xFFE07B4F;
    public static final int HAIRLINE = 0xFF2A2723;

    // Switch track/thumb (provider-mapped below).
    private static final int TRACK_OFF = 0xFF3B372F;
    private static final int THUMB_OFF = 0xFF8F8A82;
    private static final int THUMB_ON = 0xFFF0EBE4;

    // Card-segment markers (same values the hub has always used).
    public static final int SEG_NONE = 0;
    public static final int SEG_ONLY = 1;
    public static final int SEG_FIRST = 2;
    public static final int SEG_MID = 3;
    public static final int SEG_LAST = 4;

    private HubSkin() {
    }

    // ------------------------------------------------------------------
    // Typefaces — loaded once, lazily, from the bundled cuts.
    // ------------------------------------------------------------------

    private static volatile boolean typefacesLoaded;
    private static Typeface frauncesBold = Typeface.DEFAULT;
    private static Typeface frauncesSemiBold = Typeface.DEFAULT;
    private static Typeface groteskRegular = Typeface.DEFAULT;
    private static Typeface groteskMedium = Typeface.DEFAULT;
    private static Typeface groteskBold = Typeface.DEFAULT;
    private static Typeface monoRegular = Typeface.DEFAULT;
    private static Typeface monoBold = Typeface.DEFAULT;

    private static void ensureTypefaces(Context context) {
        if (typefacesLoaded || context == null) {
            return;
        }
        synchronized (HubSkin.class) {
            if (typefacesLoaded) {
                return;
            }
            try {
                android.content.res.AssetManager assets =
                        context.getApplicationContext().getAssets();
                frauncesBold = Typeface.createFromAsset(assets, "fonts/ryoku/fraunces_bold.ttf");
                frauncesSemiBold = Typeface.createFromAsset(assets, "fonts/ryoku/fraunces_semibold.ttf");
                groteskRegular = Typeface.createFromAsset(assets, "fonts/ryoku/spacegrotesk_regular.ttf");
                groteskMedium = Typeface.createFromAsset(assets, "fonts/ryoku/spacegrotesk_medium.ttf");
                groteskBold = Typeface.createFromAsset(assets, "fonts/ryoku/spacegrotesk_bold.ttf");
                monoRegular = Typeface.createFromAsset(assets, "fonts/ryoku/spacemono_regular.ttf");
                monoBold = Typeface.createFromAsset(assets, "fonts/ryoku/spacemono_bold.ttf");
            } catch (Throwable ignored) {
                // Stock faces are the fallback — the hub stays usable.
            }
            typefacesLoaded = true;
        }
    }

    public static Typeface display(Context context) {
        ensureTypefaces(context);
        return frauncesBold;
    }

    public static Typeface displaySemiBold(Context context) {
        ensureTypefaces(context);
        return frauncesSemiBold;
    }

    public static Typeface sans(Context context) {
        ensureTypefaces(context);
        return groteskRegular;
    }

    public static Typeface sansMedium(Context context) {
        ensureTypefaces(context);
        return groteskMedium;
    }

    public static Typeface sansBold(Context context) {
        ensureTypefaces(context);
        return groteskBold;
    }

    public static Typeface mono(Context context) {
        ensureTypefaces(context);
        return monoRegular;
    }

    public static Typeface monoBold(Context context) {
        ensureTypefaces(context);
        return monoBold;
    }

    // ------------------------------------------------------------------
    // Drawables
    // ------------------------------------------------------------------

    /** Bordered block background: surface fill + 1px hairline stroke,
     *  2dp corners only where the segment touches the block's edge. */
    public static GradientDrawable blockDrawable(int seg) {
        return blockDrawable(seg, SURFACE);
    }

    public static GradientDrawable blockDrawable(int seg, int fillColor) {
        float r = AndroidUtilities.dp(2);
        float[] radii;
        switch (seg) {
            case SEG_ONLY:
                radii = new float[]{r, r, r, r, r, r, r, r};
                break;
            case SEG_FIRST:
                radii = new float[]{r, r, r, r, 0, 0, 0, 0};
                break;
            case SEG_LAST:
                radii = new float[]{0, 0, 0, 0, r, r, r, r};
                break;
            default:
                radii = new float[]{0, 0, 0, 0, 0, 0, 0, 0};
                break;
        }
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadii(radii);
        drawable.setStroke(1, HAIRLINE);
        return drawable;
    }

    /** Search-arrival flash: the block background warmed with accent. */
    public static GradientDrawable highlightDrawable(int seg) {
        int fill = androidx.core.graphics.ColorUtils.blendARGB(SURFACE, ACCENT, 0.22f);
        return blockDrawable(seg, fill);
    }

    // ------------------------------------------------------------------
    // Switches — upstream silhouette, dossier colors, per instance.
    // ------------------------------------------------------------------

    static final Theme.ResourcesProvider SWITCH_PROVIDER = key -> {
        if (key == Theme.key_switch2TrackChecked) {
            return ACCENT;
        }
        if (key == Theme.key_fill_RedNormal) {
            return TRACK_OFF;
        }
        if (key == Theme.key_windowBackgroundWhiteGrayText) {
            return THUMB_OFF;
        }
        if (key == Theme.key_windowBackgroundWhite) {
            return THUMB_ON;
        }
        if (key == Theme.key_switchTrackBlueSelector) {
            return 0x33958F87;
        }
        if (key == Theme.key_switchTrackBlueSelectorChecked) {
            return 0x55CE5E32;
        }
        return Theme.getColor(key);
    };

    /** Creates an upstream Switch wired to the dossier palette. The
     *  switch is display-only everywhere in the hub (the row click
     *  toggles), exactly like the pre-v3 hub. */
    public static org.telegram.ui.Components.Switch newSwitch(Context context) {
        org.telegram.ui.Components.Switch switchView =
                new org.telegram.ui.Components.Switch(context, SWITCH_PROVIDER);
        switchView.setColors(Theme.key_fill_RedNormal, Theme.key_switch2TrackChecked,
                Theme.key_windowBackgroundWhiteGrayText, Theme.key_windowBackgroundWhite);
        switchView.setFocusable(false);
        switchView.setClickable(false);
        return switchView;
    }

    /** Shared marble bitmap for the home masthead (hub-only asset,
     *  generated from design/ryoku sources like the mockup's band). */
    private static android.graphics.Bitmap marbleBitmap;

    public static android.graphics.Bitmap marble(Context context) {
        if (marbleBitmap != null) {
            return marbleBitmap;
        }
        try {
            java.io.InputStream in = context.getApplicationContext().getAssets()
                    .open("amingram/hub_marble.webp");
            marbleBitmap = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
        } catch (Throwable ignored) {
        }
        return marbleBitmap;
    }
}
