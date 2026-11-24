package tw.nekomimi.nekogram.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.telegram.messenger.BuildConfig;
import org.telegram.ui.LaunchActivity;

import java.io.File;

/**
 * Minimal NekoGram {@code ShareUtil} (Fix 3 / v3.0.3), Java translation of
 * the vendored Kotlin file in exteraless
 * ({@code TMessagesProj/src/main/java/tw/nekomimi/nekogram/utils/ShareUtil.kt},
 * NekoGram, GPL-3.0). Only {@link #shareFile} is reproduced: the engine's
 * plugin screens use it to hand a log/backup file to Telegram's own share
 * flow (ACTION_SEND targeted at {@link LaunchActivity}).
 *
 * The FileProvider authority is {@code BuildConfig.APPLICATION_ID +
 * ".provider"} — the same {@code ${applicationId}.provider} the manifest
 * declares. {@code APPLICATION_ID} is defined as a buildConfigField in
 * TMessagesProj/build.gradle (patch 0054); without it this file does not
 * compile.
 */
public final class ShareUtil {

    private ShareUtil() {
    }

    public static void shareFile(Context ctx, File fileToShare) {
        shareFile(ctx, fileToShare, "");
    }

    public static void shareFile(Context ctx, File fileToShare, String caption) {
        Uri uri = FileProvider.getUriForFile(ctx, BuildConfig.APPLICATION_ID + ".provider", fileToShare);

        Intent i = new Intent(Intent.ACTION_SEND);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setType("message/rfc822");
        i.putExtra(Intent.EXTRA_EMAIL, "");
        if (caption != null && !caption.trim().isEmpty()) {
            i.putExtra(Intent.EXTRA_SUBJECT, caption);
        }
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.setClass(ctx, LaunchActivity.class);
        ctx.startActivity(i);
    }
}
