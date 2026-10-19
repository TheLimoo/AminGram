package org.telegram.messenger.amin.core;

import android.content.Context;
import android.util.SparseIntArray;

import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeColors;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * App-wide design themes (v1.3.3), shared by the "amin_design" and
 * "ios_theme" modules. Both are REAL Telegram themes, applied through
 * upstream's own theme engine — no color-table hacking:
 *
 * - The complete color set of an upstream builtin base theme
 *   (values read via the public Theme.getThemeFileValues) is
 *   serialized to an .attheme file (the parser format: lines of
 *   "keyName=#AARRGGBB", keys resolved by ThemeColors), with a
 *   curated subset of keys overridden by the design palette.
 * - The file is applied with Theme.applyThemeFile(...), the exact
 *   entry point upstream uses for user-supplied theme files: it
 *   registers the theme, persists it as the current theme and runs
 *   the full apply/refresh path. The design therefore survives
 *   restarts natively; a startup hook (patch 0015) only re-applies
 *   it if the user drifted away in Appearance while the module
 *   stayed on.
 * - Before applying, the CURRENT theme's key is snapshotted (unless
 *   the current theme is already one of ours). Turning a design off
 *   re-applies that snapshot via Theme.applyTheme — restore is the
 *   user's own previous theme, byte for byte.
 *
 * Bounds: the design occupies the DAY theme slot (same as any file
 * theme applied from Appearance); a separate auto-night theme the
 * user configured is untouched. If the snapshotted theme was
 * deleted meanwhile, restore falls back to the builtin "Blue".
 * Every entry point is Throwable-isolated: a theme failure degrades
 * to "theme unchanged", never a crash.
 */
public final class DesignThemes {

    public static final String DESIGN_AMIN = "amin";
    public static final String DESIGN_IOS = "ios";

    private static final String PREFS = "amingram_designs";
    private static final String KEY_PREV_THEME = "prev_theme_key";

    private static final String FILE_AMIN = "amingram-design.attheme";
    private static final String FILE_IOS = "amingram-ios.attheme";
    private static final String BASE_DARK = "night.attheme";
    private static final String BASE_LIGHT = "day.attheme";

    /** AminGram Design (B1): extera-inspired near-black surfaces and
     *  periwinkle accent, on upstream's Night base. */
    private static final String[][] PALETTE_AMIN = {
            {"windowBackgroundGray", "#FF0B0B0E"},
            {"windowBackgroundWhite", "#FF1C1C21"},
            {"windowBackgroundWhiteBlackText", "#FFFFFFFF"},
            {"windowBackgroundWhiteGrayText", "#FF8E8E93"},
            {"windowBackgroundWhiteHintText", "#FF636368"},
            {"windowBackgroundWhiteBlueText", "#FF6E9BFF"},
            {"windowBackgroundWhiteValueText", "#FF6E9BFF"},
            {"divider", "#FF2A2A2E"},
            {"actionBarDefault", "#FF0B0B0E"},
            {"actionBarDefaultTitle", "#FFFFFFFF"},
            {"actionBarDefaultIcon", "#FFFFFFFF"},
            {"dialogBackground", "#FF1C1C21"},
            {"dialogTextBlack", "#FFFFFFFF"},
            {"dialogTextGray", "#FF8E8E93"},
            {"chats_menuBackground", "#FF1C1C21"},
            {"chat_messagePanelBackground", "#FF1C1C21"},
            {"chat_messagePanelText", "#FFFFFFFF"},
            {"chat_messagePanelHint", "#FF636368"},
            {"chat_inBubble", "#FF26262B"},
            {"chat_inBubbleSelected", "#FF303036"},
            {"chat_messageTextIn", "#FFFFFFFF"},
            {"chat_messageLinkIn", "#FF6E9BFF"},
            {"chat_outBubble", "#FF3A5BA9"},
            {"chat_outBubbleSelected", "#FF2F4C8C"},
            {"chat_messageTextOut", "#FFFFFFFF"},
            {"chat_messageLinkOut", "#FFFFFFFF"},
            {"chat_serviceBackground", "#CC1C1C21"},
            {"chat_serviceText", "#FF8E8E93"},
            {"switchTrack", "#FF2A2A2E"},
            {"switchTrackChecked", "#FF6E9BFF"},
            {"chats_actionBackground", "#FF6E9BFF"},
            {"chats_actionPressedBackground", "#FF4A7BE0"},
    };

    /** iOS Theme: Apple's palette (grouped light gray, white cards,
     *  iOS blue, gray incoming bubbles, green switches) on upstream's
     *  Day base. */
    private static final String[][] PALETTE_IOS = {
            {"windowBackgroundWhite", "#FFFFFFFF"},
            {"windowBackgroundGray", "#FFF2F2F7"},
            {"windowBackgroundWhiteBlackText", "#FF000000"},
            {"windowBackgroundWhiteGrayText", "#FF8A8A8E"},
            {"windowBackgroundWhiteHintText", "#FFAEAEB2"},
            {"windowBackgroundWhiteBlueText", "#FF007AFF"},
            {"windowBackgroundWhiteValueText", "#FF007AFF"},
            {"divider", "#FFC6C6C8"},
            {"actionBarDefault", "#FFFFFFFF"},
            {"actionBarDefaultTitle", "#FF000000"},
            {"actionBarDefaultIcon", "#FF007AFF"},
            {"dialogBackground", "#FFFFFFFF"},
            {"dialogTextBlack", "#FF000000"},
            {"dialogTextGray", "#FF8A8A8E"},
            {"chats_menuBackground", "#FFFFFFFF"},
            {"chat_inBubble", "#FFE9E9EB"},
            {"chat_inBubbleSelected", "#FFD9D9DE"},
            {"chat_messageTextIn", "#FF000000"},
            {"chat_messageLinkIn", "#FF007AFF"},
            {"chat_outBubble", "#FF007AFF"},
            {"chat_outBubbleSelected", "#FF0062CC"},
            {"chat_messageTextOut", "#FFFFFFFF"},
            {"chat_messageLinkOut", "#FFFFFFFF"},
            {"chat_serviceBackground", "#D3E9E9EB"},
            {"chat_serviceText", "#FF8A8A8E"},
            {"chat_messagePanelBackground", "#FFF2F2F7"},
            {"chat_messagePanelText", "#FF000000"},
            {"chat_messagePanelHint", "#FFAEAEB2"},
            {"switchTrack", "#FFE9E9EB"},
            {"switchTrackChecked", "#FF34C759"},
            {"chats_actionBackground", "#FF007AFF"},
            {"chats_actionPressedBackground", "#FF0062CC"},
    };

    private DesignThemes() {
    }

    private static String fileName(String designId) {
        return DESIGN_IOS.equals(designId) ? FILE_IOS : FILE_AMIN;
    }

    /** The design whose theme is currently the app's day theme,
     *  or null when a foreign theme is active. */
    public static String activeDesign() {
        try {
            String key = currentThemeKey();
            if (FILE_AMIN.equals(key)) {
                return DESIGN_AMIN;
            }
            if (FILE_IOS.equals(key)) {
                return DESIGN_IOS;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean isActive(String designId) {
        return designId != null && designId.equals(activeDesign());
    }

    private static String currentThemeKey() {
        Theme.ThemeInfo info = Theme.getCurrentTheme();
        return info != null ? info.getKey() : null;
    }

    /** Builds the design's .attheme file from the builtin base plus
     *  the palette overrides and applies it via upstream's file
     *  theme entry point. Snapshots the previous theme first.
     *  Returns true when the design theme is now applied. */
    public static boolean applyDesign(Context context, String designId) {
        try {
            String base = DESIGN_IOS.equals(designId) ? BASE_LIGHT : BASE_DARK;
            String[][] palette = DESIGN_IOS.equals(designId) ? PALETTE_IOS : PALETTE_AMIN;
            SparseIntArray values = Theme.getThemeFileValues(null, base, null);
            if (values == null || values.size() < 100) {
                FileLog.e("AminGram design: base theme values unavailable (" + base + ")");
                return false;
            }
            for (String[] entry : palette) {
                int key = ThemeColors.stringKeyToInt(entry[0]);
                if (key >= 0) {
                    values.put(key, (int) Long.parseLong(entry[1].substring(1), 16));
                }
            }
            snapshotPrevious(context);
            File dir = new File(context.getFilesDir(), "amingram");
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File source = new File(dir, fileName(designId));
            StringBuilder sb = new StringBuilder(values.size() * 40);
            for (int i = 0; i < values.size(); i++) {
                int key = values.keyAt(i);
                if (key == Theme.key_wallpaperFileOffset) {
                    continue;
                }
                String name = ThemeColors.getStringName(key);
                if (name == null) {
                    continue;
                }
                sb.append(name).append('=')
                        .append(String.format("#%08X", values.valueAt(i))).append('\n');
            }
            FileOutputStream out = new FileOutputStream(source, false);
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
            Theme.ThemeInfo applied = Theme.applyThemeFile(source, fileName(designId), null, false);
            return applied != null;
        } catch (Throwable t) {
            FileLog.e("AminGram design: apply failed", t);
            return false;
        }
    }

    /** Records the current theme as the restore target, unless the
     *  current theme is already one of our designs (in which case
     *  the older snapshot is the real "user theme"). */
    private static void snapshotPrevious(Context context) {
        try {
            String current = currentThemeKey();
            if (current == null || FILE_AMIN.equals(current) || FILE_IOS.equals(current)) {
                return;
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PREV_THEME, current).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Re-applies the snapshotted user theme. Returns false when
     *  there is nothing sensible to restore (no snapshot, and the
     *  current theme is not ours either). */
    public static boolean restorePrevious(Context context) {
        try {
            String prevKey = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PREV_THEME, null);
            String current = currentThemeKey();
            if (prevKey == null) {
                return activeDesign() == null;
            }
            if (prevKey.equals(current)) {
                clearSnapshot(context);
                return true;
            }
            Theme.ThemeInfo prev = Theme.getTheme(prevKey);
            if (prev == null) {
                prev = Theme.getTheme("Blue");
            }
            if (prev == null) {
                return false;
            }
            Theme.applyTheme(prev);
            clearSnapshot(context);
            return true;
        } catch (Throwable t) {
            FileLog.e("AminGram design: restore failed", t);
            return false;
        }
    }

    private static void clearSnapshot(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PREV_THEME).apply();
    }

    /** Startup reconciliation (patch 0015, LaunchActivity.onCreate):
     *  whichever design module is on owns the day theme. AminGram
     *  Design wins if both are somehow on. When neither is on but
     *  one of our themes is somehow still active (a restore that
     *  failed earlier), restore the snapshot now. No-op in the
     *  common cases, so the common launch pays one prefs read. */
    public static void applyEnabledDesigns(Context context) {
        try {
            if (context == null) {
                return;
            }
            boolean designOn = org.telegram.messenger.amin.plugins.PluginRegistry
                    .isEnabledById(context, "amin_design");
            boolean iosOn = org.telegram.messenger.amin.plugins.PluginRegistry
                    .isEnabledById(context, "ios_theme");
            String target = designOn ? DESIGN_AMIN : (iosOn ? DESIGN_IOS : null);
            String active = activeDesign();
            if (target != null) {
                if (!target.equals(active)) {
                    applyDesign(context, target);
                }
            } else if (active != null) {
                restorePrevious(context);
            }
        } catch (Throwable ignored) {
        }
    }
}
