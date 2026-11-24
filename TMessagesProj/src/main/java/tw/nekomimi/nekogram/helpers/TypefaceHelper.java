package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.SystemFonts;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.LeadingMarginSpan;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.TypefaceSpan;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Minimal NekoGram {@code TypefaceHelper} (Fix 3 / v3.0.3).
 *
 * Source: NekoGram (GPL-3.0), as vendored in exteraless
 * ({@code TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/TypefaceHelper.java}).
 * Only {@link #getTitleText} — the one method the exteraless engine calls —
 * and the {@code createTypeface(int, boolean)} chain behind it are
 * reproduced, verbatim where possible. The rest of the 392-line class
 * (asset typefaces, lyrics typeface, medium/italic probes, ...) is not
 * ported.
 *
 * Two deliberate stubbings, both documented here:
 * <ul>
 *     <li>{@code NekoConfig.typeface} / {@code NekoConfig.forceFontWeightFallback}
 *         (NekoGram config) do not exist in AminGram: both read as false,
 *         so the title uses {@code createTypeface(600, false)} — the
 *         default branch of the original.</li>
 *     <li>{@code NaConfig.getCustomTitle()} (NagramX config) does not exist:
 *         the title defaults to the app name, and the custom-title branch
 *         is dropped. {@link app.exteraless.appearance.AppearanceConfig#titleText()}
 *         (our minimal stand-in) never returns
 *         {@code TITLE_TEXT_CUSTOM}, so that branch is unreachable.</li>
 * </ul>
 */
public class TypefaceHelper {

    private static final String TEST_TEXT;
    private static final int CANVAS_SIZE = dp(20);
    private static final Paint PAINT = new Paint() {{
        setTextSize(CANVAS_SIZE);
        setAntiAlias(true);
        setSubpixelText(false);
        setFakeBoldText(false);
    }};

    private static volatile Boolean usePixelGoogleSans = null;
    private static Typeface systemGoogleSans;
    private static boolean systemGoogleSansLoaded;
    private static Typeface systemGoogleSansMedium;
    private static boolean systemGoogleSansMediumLoaded;

    static {
        String lang = LocaleController.getInstance().getCurrentLocale().getLanguage();
        if (List.of("zh", "ja", "ko").contains(lang)) {
            TEST_TEXT = "你好";
        } else if (List.of("ar", "fa").contains(lang)) {
            TEST_TEXT = "مرحبا";
        } else if ("iw".equals(lang)) {
            TEST_TEXT = "שלום";
        } else if ("th".equals(lang)) {
            TEST_TEXT = "สวัสดี";
        } else if ("hi".equals(lang)) {
            TEST_TEXT = "नमस्ते";
        } else if (List.of("ru", "uk", "ky", "be", "sr").contains(lang)) {
            TEST_TEXT = "Привет";
        } else {
            TEST_TEXT = "R";
        }
    }

    public static SpannableStringBuilder getTitleText(int currentAccount) {
        String title;
        try {
            title = ApplicationLoader.applicationContext.getString(R.string.OpenExtera);
        } catch (Exception e) {
            title = "exteraless";
        }
        // Chat-list title mode (AppearanceConfig.titleText): 0 app name,
        // 1 username, 2 first name, 3 "Chats". Our minimal stand-in
        // always returns 0, so only the 0 branch is live; the others are
        // kept verbatim from the original for when it is ported fully.
        final int oeTitleText = app.exteraless.appearance.AppearanceConfig.titleText();
        if (oeTitleText == 3) {
            title = LocaleController.getString(R.string.FilterChats);
        } else if (oeTitleText == app.exteraless.appearance.AppearanceConfig.TITLE_TEXT_CUSTOM) {
            // Fix 3: NaConfig custom title does not exist in AminGram —
            // unreachable while titleText() returns 0; keep the app name.
        } else if (oeTitleText != 0) {
            TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();
            String username = oeTitleText == 1 ? UserObject.getPublicUsername(self) : null;
            if (!TextUtils.isEmpty(username)) {
                title = username;
            } else {
                String firstName = UserObject.getFirstName(self);
                if (!TextUtils.isEmpty(firstName)) {
                    title = firstName;
                }
            }
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(title);
        builder.setSpan(new LeadingMarginSpan.Standard(dp(2), 0), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // Fix 3: NekoConfig.typeface / forceFontWeightFallback read as
        // false, so the title uses createTypeface(600, false) — the
        // default branch of the original.
        Typeface titleTypeface = createTypeface(600, false);
        builder.setSpan(new TypefaceSpan(titleTypeface, 0, Theme.key_telegram_color_dialogsLogo, null), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    public static Typeface createTypeface(int weight, boolean italic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (weight >= 500 && weight < 700 && shouldUsePixelGoogleSans()) {
                final Typeface medium = getSystemGoogleSansMedium();
                if (medium != null) {
                    return Typeface.create(medium, weight, italic);
                }
            }
            return Typeface.create(baseTypeface(), weight, italic);
        }
        if (weight == 700) {
            return Typeface.create("sans-serif", italic ? Typeface.BOLD_ITALIC : Typeface.BOLD);
        }
        // Fix 3 watch (CI run 43827424276): switch expressions/rules are
        // Java 14+; the project compiles with -source 8, so plain if/else.
        String family;
        if (weight == 800) {
            family = "sans-serif-black";
        } else if (weight == 500) {
            family = "sans-serif-medium";
        } else {
            family = "sans-serif";
        }
        return Typeface.create(family, italic ? Typeface.ITALIC : Typeface.NORMAL);
    }

    public static Typeface createTypefaceFromAsset(String assetPath) {
        Typeface.Builder builder = new Typeface.Builder(ApplicationLoader.applicationContext.getAssets(), assetPath);
        if (assetPath.contains("rextrabold")) {
            builder.setWeight(800);
        }
        if (assetPath.contains("medium") || assetPath.contains("rbold")) {
            builder.setWeight(700);
        }
        if (assetPath.contains("italic")) {
            builder.setItalic(true);
        }
        return builder.build();
    }

    private static Typeface baseTypeface() {
        if (!shouldUsePixelGoogleSans()) {
            return Typeface.create("sans-serif", Typeface.NORMAL);
        }
        if (!systemGoogleSansLoaded) {
            systemGoogleSansLoaded = true;
            for (String family : new String[]{"google-sans-text", "google-sans"}) {
                final Typeface candidate = Typeface.create(family, Typeface.NORMAL);
                if (rendersDifferently(candidate, Typeface.DEFAULT)) {
                    systemGoogleSans = candidate;
                    FileLog.d("system google sans alias = " + family);
                    break;
                }
            }
            if (systemGoogleSans == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                final File file = findGoogleSansFile();
                if (file != null) {
                    try {
                        systemGoogleSans = Typeface.createFromFile(file);
                        FileLog.d("system google sans file = " + file.getAbsolutePath());
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
        }
        return systemGoogleSans != null ? systemGoogleSans : Typeface.create("sans-serif", Typeface.NORMAL);
    }

    private static Typeface getSystemGoogleSansMedium() {
        if (!shouldUsePixelGoogleSans()) {
            return null;
        }
        if (!systemGoogleSansMediumLoaded) {
            systemGoogleSansMediumLoaded = true;
            for (String family : new String[]{"variable-title-medium-emphasized", "variable-title-medium"}) {
                final Typeface candidate = Typeface.create(family, Typeface.NORMAL);
                if (rendersDifferently(candidate, Typeface.DEFAULT)) {
                    systemGoogleSansMedium = candidate;
                    FileLog.d("system google sans medium alias = " + family);
                    break;
                }
            }
        }
        return systemGoogleSansMedium;
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private static File findGoogleSansFile() {
        try {
            for (Font font : SystemFonts.getAvailableFonts()) {
                final File file = font.getFile();
                if (file == null) {
                    continue;
                }
                final String name = file.getName().toLowerCase(Locale.US);
                if ((name.contains("googlesans") || name.contains("google-sans"))
                        && !name.contains("medium") && !name.contains("bold") && !name.contains("italic") && !name.contains("condensed")) {
                    return file;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private static boolean isGooglePixelDevice() {
        return "google".equalsIgnoreCase(Build.MANUFACTURER) && Build.MODEL != null && Build.MODEL.toLowerCase(Locale.US).startsWith("pixel");
    }

    public static boolean shouldUsePixelGoogleSans() {
        if (!isGooglePixelDevice()) {
            return false;
        }
        if (usePixelGoogleSans == null) {
            synchronized (TypefaceHelper.class) {
                if (usePixelGoogleSans == null) {
                    boolean use;
                    try {
                        use = hasSimilarMetrics(Typeface.create("sans-serif", Typeface.NORMAL), createTypefaceFromAsset(AndroidUtilities.TYPEFACE_ROBOTO_REGULAR));
                    } catch (Exception e) {
                        FileLog.e(e);
                        use = false;
                    }
                    usePixelGoogleSans = use;
                    FileLog.d("usePixelGoogleSans = " + use);
                }
            }
        }
        return usePixelGoogleSans;
    }

    private static boolean hasSimilarMetrics(Typeface a, Typeface b) {
        final String sample = "Hamburgefontsiv0123456789";
        final Paint paint = new Paint();
        paint.setTextSize(100);
        final float[] first = new float[sample.length()];
        final float[] second = new float[sample.length()];
        paint.setTypeface(a);
        paint.getTextWidths(sample, first);
        paint.setTypeface(b);
        paint.getTextWidths(sample, second);
        for (int i = 0; i < sample.length(); i++) {
            if (second[i] == 0 || Math.abs(first[i] - second[i]) / second[i] > 0.01f) {
                return false;
            }
        }
        return true;
    }

    private static boolean rendersDifferently(Typeface a, Typeface b) {
        final Canvas canvas = new Canvas();
        final Bitmap first = Bitmap.createBitmap(CANVAS_SIZE * 2, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
        final Bitmap second = Bitmap.createBitmap(CANVAS_SIZE * 2, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
        synchronized (PAINT) {
            canvas.setBitmap(first);
            PAINT.setTypeface(a);
            canvas.drawText(TEST_TEXT, 0, CANVAS_SIZE, PAINT);
            canvas.setBitmap(second);
            PAINT.setTypeface(b);
            canvas.drawText(TEST_TEXT, 0, CANVAS_SIZE, PAINT);
            PAINT.setTypeface(null);
        }
        final boolean different = !first.sameAs(second);
        AndroidUtilities.recycleBitmaps(List.of(first, second));
        return different;
    }
}
