package org.telegram.messenger.amin.ui;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.amin.core.AutoSaverStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-screen viewer for one Auto-Saver entry (v1.3): shows the saved
 * photo (or plays the saved video), with Save to gallery, Share and
 * Remove actions. The file is resolved through AutoSaverStore — the
 * recorded cache path first, the attach-name fallback second; if
 * Telegram's cache was cleaned in the meantime the viewer says so
 * plainly instead of showing a black screen.
 */
public class AminPhotoViewActivity extends BaseFragment {

    private static final int MENU_SAVE = 1;
    private static final int MENU_SHARE = 2;
    private static final int MENU_DELETE = 3;

    private final long dialogId;
    private final int messageId;
    private AutoSaverStore.Entry entry;
    private File file;
    private FrameLayout content;

    public AminPhotoViewActivity(long dialogId, int messageId) {
        super();
        this.dialogId = dialogId;
        this.messageId = messageId;
    }

    @Override
    public View createView(Context context) {
        for (AutoSaverStore.Entry e : AutoSaverStore.getEntries(context)) {
            if (e.dialogId == dialogId && e.id == messageId) {
                entry = e;
                break;
            }
        }

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        String name = AminDialogPickerActivity.dialogName(currentAccount, dialogId);
        String date = entry != null
                ? new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                        .format(new Date(entry.date * 1000L)) : "";
        actionBar.setTitle(name);
        actionBar.setSubtitle(date);
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_SAVE, R.drawable.msg_download);
        menu.addItem(MENU_SHARE, R.drawable.msg_share);
        menu.addItem(MENU_DELETE, R.drawable.msg_delete);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SAVE) {
                    saveToGallery(context);
                } else if (id == MENU_SHARE) {
                    share(context);
                } else if (id == MENU_DELETE) {
                    confirmDelete(context);
                }
            }
        });

        content = new FrameLayout(context);
        content.setBackgroundColor(0xFF000000);
        TextView loading = new TextView(context);
        loading.setText("Loading…");
        loading.setTextColor(0xFFFFFFFF);
        loading.setGravity(Gravity.CENTER);
        content.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final int account = currentAccount;
        Utilities.globalQueue.postRunnable(() -> {
            File resolved = entry != null
                    ? AutoSaverStore.resolveFile(context, account, entry) : null;
            AndroidUtilities.runOnUIThread(() -> showFile(context, resolved));
        });

        fragmentView = content;
        return content;
    }

    private void showFile(Context context, File resolved) {
        if (content == null) {
            return;
        }
        content.removeAllViews();
        if (resolved == null) {
            file = null;
            TextView gone = new TextView(context);
            gone.setText("This file is no longer in Telegram's cache.\n"
                    + "(Caches are cleaned by Android over time — save important items to the gallery.)");
            gone.setTextColor(0xFFFFFFFF);
            gone.setGravity(Gravity.CENTER);
            gone.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
            content.addView(gone, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        file = resolved;
        if (entry != null && entry.isVideo()) {
            VideoView video = new VideoView(context);
            video.setVideoPath(file.getAbsolutePath());
            MediaController controller = new MediaController(context);
            controller.setAnchorView(video);
            video.setMediaController(controller);
            content.addView(video, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            video.start();
        } else {
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Utilities.globalQueue.postRunnable(() -> {
                Bitmap bitmap = decodeSampled(file);
                AndroidUtilities.runOnUIThread(() -> image.setImageBitmap(bitmap));
            });
        }
    }

    private Bitmap decodeSampled(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            int target = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
            int sample = 1;
            while (options.outWidth / sample > target || options.outHeight / sample > target) {
                sample *= 2;
            }
            options.inSampleSize = sample;
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable t) {
            return null;
        }
    }

    private void bulletin(int icon, String text) {
        BulletinFactory.of(this).createSimpleBulletin(icon, text).show();
    }

    private void saveToGallery(Context context) {
        if (file == null || entry == null) {
            bulletin(R.raw.error, "Nothing to save — the file is gone");
            return;
        }
        final File source = file;
        final boolean video = entry.isVideo();
        final String displayName = "amingram_" + dialogId + "_" + messageId
                + extensionOf(source.getName(), video ? ".mp4" : ".jpg");
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = saveToGallerySync(context, source, displayName, video);
            AndroidUtilities.runOnUIThread(() ->
                    bulletin(ok ? R.raw.contact_check : R.raw.error,
                            ok ? "Saved to gallery" : "Could not save to gallery"));
        });
    }

    private boolean saveToGallerySync(Context context, File source, String displayName, boolean video) {
        try {
            ContentResolver resolver = context.getContentResolver();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "image/jpeg");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES)
                                + "/AminGram");
                Uri collection = video
                        ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri uri = resolver.insert(collection, values);
                if (uri == null) {
                    return false;
                }
                OutputStream out = resolver.openOutputStream(uri);
                if (out == null) {
                    return false;
                }
                copyStream(new FileInputStream(source), out);
                out.close();
                return true;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES),
                    "AminGram");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File target = new File(dir, displayName);
            FileOutputStream out = new FileOutputStream(target);
            copyStream(new FileInputStream(source), out);
            out.close();
            MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()},
                    null, null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void share(Context context) {
        if (file == null || entry == null) {
            bulletin(R.raw.error, "Nothing to share — the file is gone");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(entry.isVideo() ? "video/*" : "image/*");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent, "Share"));
        } catch (Throwable t) {
            bulletin(R.raw.error, "Sharing failed");
        }
    }

    private void confirmDelete(Context context) {
        if (entry == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Remove from saved media?");
        builder.setMessage("The entry is removed from this list. The file in Telegram's cache is not touched.");
        builder.setPositiveButton("Remove", (dialog, which) -> {
            AutoSaverStore.remove(context, entry);
            finishFragment();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private static String extensionOf(String name, String fallback) {
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 5) {
                return name.substring(dot);
            }
        }
        return fallback;
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        in.close();
    }
}
