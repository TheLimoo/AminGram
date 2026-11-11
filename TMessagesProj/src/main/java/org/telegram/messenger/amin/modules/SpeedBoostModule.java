package org.telegram.messenger.amin.modules;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

/**
 * Download & Upload Speed Boost — exposes {@code FileLoadOperation} chunk size
 * and concurrent request count levels (Off / Balanced / Extreme), plus an
 * independent Upload Speed Boost toggle.
 *
 * Verified against upstream 13.0.2 ({@code f32ffee}):
 * - Off (level 0): upstream default ({@code 128 KB} big chunk, {@code 4}
 *   concurrent requests unless server {@code getfileExperimentalParams} is set).
 * - Balanced (level 1): forces Telegram's own experimental fast branch
 *   ({@code 512 KB} big chunk, {@code 8} concurrent requests) when
 *   {@code !forceSmallChunk}.
 * - Extreme (level 2): {@code 1024 KB} (1 MB) big chunk, {@code 12} concurrent
 *   requests when {@code !forceSmallChunk}, while preserving upstream's
 *   {@code LIMIT_INVALID -> forceSmallChunk = true} fallback.
 * - Upload Boost: increases upload chunk size and concurrent upload window.
 *
 * Default is OFF. Real-world throughput gain depends on DC routing and ISP/VPN
 * conditions and must be measured on-device (same file, Wi-Fi + mobile, MB/s).
 */
public class SpeedBoostModule implements ModModule {

    private static final String PREFS = "amingram_plugins";
    public static final String KEY_DOWNLOAD_LEVEL = "speed_boost_download_level";
    public static final String KEY_UPLOAD_BOOST = "speed_boost_upload";

    public static final int LEVEL_OFF = 0;
    public static final int LEVEL_BALANCED = 1;
    public static final int LEVEL_EXTREME = 2;

    public static final String[] LEVEL_LABELS = {
            "Off (Upstream 128 KB × 4)",
            "Balanced (512 KB × 8)",
            "Extreme (1024 KB × 12)"
    };

    @Override
    public String id() {
        return "speed_boost";
    }

    @Override
    public String title() {
        return "Download & Upload Speed Boost";
    }

    @Override
    public String description() {
        return "Tunes download chunk size and concurrent DC requests (Off / Balanced 512 KB × 8 / Extreme 1024 KB × 12) plus an optional upload boost. Default OFF — measure before/after MB/s on your link; higher parallelism can trigger DC FloodWait.";
    }

    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public int section() {
        return SECTION_TOOLS;
    }

    @Override
    public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        int dl = downloadLevel(context);
        boolean ul = uploadBoost(context);
        if (dl == LEVEL_OFF && !ul) {
            return "Active — select a download level or enable upload boost below";
        }
        return "Download: " + levelShortLabel(dl) + " · Upload: " + (ul ? "Boosted" : "Stock");
    }

    public static int downloadLevel(Context context) {
        if (context == null) {
            return LEVEL_OFF;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int level = prefs.getInt(KEY_DOWNLOAD_LEVEL, LEVEL_OFF);
        return (level >= LEVEL_OFF && level <= LEVEL_EXTREME) ? level : LEVEL_OFF;
    }

    public static void setDownloadLevel(Context context, int level) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_DOWNLOAD_LEVEL, level)
                .apply();
    }

    public static String levelLabel(int level) {
        if (level >= 0 && level < LEVEL_LABELS.length) {
            return LEVEL_LABELS[level];
        }
        return LEVEL_LABELS[LEVEL_OFF];
    }

    public static String levelShortLabel(int level) {
        switch (level) {
            case LEVEL_BALANCED:
                return "Balanced (512 KB × 8)";
            case LEVEL_EXTREME:
                return "Extreme (1024 KB × 12)";
            default:
                return "Off";
        }
    }

    public static boolean uploadBoost(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_UPLOAD_BOOST, false);
    }

    public static void setUploadBoost(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_UPLOAD_BOOST, enabled)
                .apply();
    }
}
