package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.amin.AminHooks;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;

/**
 * AminGram video/GIF chat background: attaches an animated layer directly
 * above the static wallpaper inside a chat's content view. The file path
 * lives in our own prefs ("amingram_videobg"/"path"), set from the Plugins
 * hub. Lifecycle is driven by ChatActivity hooks.
 *
 * v1.1.1 fixes (black-layer bug): the layer stays INVISIBLE and the
 * TextureView non-opaque until the first frame actually renders; a player
 * error removes the layer entirely; .gif files are played with an
 * AnimatedImageDrawable (ExoPlayer cannot decode GIFs); and
 * {@link #shouldRelease} lets the onResume hook shed the layer the moment
 * the module is toggled off or the file changes — no destroy needed.
 */
public class VideoBackgroundHelper {

    private static final String PREFS = "amingram_videobg";
    private static final String KEY_PATH = "path";

    private VideoPlayer player;
    private TextureView textureView;
    private ImageView imageView;
    private AnimatedImageDrawable animatedDrawable;
    private String attachedPath;
    private boolean released;

    public static String getVideoPath(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PATH, null);
    }

    public static void setVideoPath(Context context, String path) {
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (path == null) {
            edit.remove(KEY_PATH);
        } else {
            edit.putString(KEY_PATH, path);
        }
        edit.apply();
    }

    private static boolean isGif(String path) {
        return path != null && path.toLowerCase().endsWith(".gif");
    }

    /** Attach if the module is enabled and a media file is set. Returns the
     *  helper to keep for lifecycle calls, or null when not applicable. */
    public static VideoBackgroundHelper attachIfEnabled(Context context, ViewGroup parent) {
        if (context == null || parent == null) {
            return null;
        }
        if (!AminHooks.moduleEnabled("video_background")) {
            return null;
        }
        String path = getVideoPath(context);
        if (path == null || !new File(path).exists()) {
            return null;
        }
        VideoBackgroundHelper helper = new VideoBackgroundHelper();
        helper.attachedPath = path;
        if (isGif(path)) {
            if (!helper.attachGif(context, parent, path)) {
                return null;
            }
        } else {
            helper.attachVideo(context, parent, path);
        }
        return helper;
    }

    private void attachVideo(Context context, ViewGroup parent, String path) {
        textureView = new TextureView(context);
        textureView.setOpaque(false);
        textureView.setVisibility(View.INVISIBLE);
        parent.addView(textureView, Math.min(1, parent.getChildCount()),
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        player = new VideoPlayer(false, true);
        player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
            }

            @Override
            public void onError(VideoPlayer p, Exception e) {
                FileLog.e("AminGram videobg: player error, removing layer", e);
                release();
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            }

            @Override
            public void onRenderedFirstFrame() {
                if (!released && textureView != null) {
                    textureView.setVisibility(View.VISIBLE);
                }
            }
        });
        player.setTextureView(textureView);
        player.preparePlayerLoop(Uri.fromFile(new File(path)), "file", null, null);
        player.play();
    }

    private boolean attachGif(Context context, ViewGroup parent, String path) {
        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setVisibility(View.INVISIBLE);
        try {
            Drawable drawable;
            if (Build.VERSION.SDK_INT >= 28) {
                drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(new File(path)));
            } else {
                drawable = null;
            }
            if (drawable != null) {
                imageView.setImageDrawable(drawable);
                if (drawable instanceof AnimatedImageDrawable) {
                    animatedDrawable = (AnimatedImageDrawable) drawable;
                    animatedDrawable.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    animatedDrawable.start();
                }
            } else {
                // Pre-28 fallback: static first frame is better than a black layer.
                imageView.setImageBitmap(BitmapFactory.decodeFile(path));
                if (imageView.getDrawable() == null) {
                    return false;
                }
            }
        } catch (Throwable t) {
            FileLog.e("AminGram videobg: gif decode failed, not attaching", t);
            imageView = null;
            return false;
        }
        parent.addView(imageView, Math.min(1, parent.getChildCount()),
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        imageView.setVisibility(View.VISIBLE);
        return true;
    }

    /** True when the layer must come off now: module toggled off, file
     *  unset/removed, or a different file was picked since attach. The
     *  ChatActivity onResume hook releases the helper when this is true. */
    public boolean shouldRelease(Context context) {
        if (released || context == null) {
            return true;
        }
        if (!AminHooks.moduleEnabled("video_background")) {
            return true;
        }
        String path = getVideoPath(context);
        return path == null || !path.equals(attachedPath) || !new File(path).exists();
    }

    public void resume() {
        if (player != null) {
            player.play();
        }
        if (animatedDrawable != null) {
            animatedDrawable.start();
        }
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
        if (animatedDrawable != null) {
            animatedDrawable.stop();
        }
    }

    public void release() {
        released = true;
        if (player != null) {
            player.releasePlayer(false);
            player = null;
        }
        if (animatedDrawable != null) {
            animatedDrawable.stop();
            animatedDrawable = null;
        }
        if (textureView != null) {
            ViewGroup parent = (ViewGroup) textureView.getParent();
            if (parent != null) {
                parent.removeView(textureView);
            }
            textureView = null;
        }
        if (imageView != null) {
            ViewGroup parent = (ViewGroup) imageView.getParent();
            if (parent != null) {
                parent.removeView(imageView);
            }
            imageView = null;
        }
    }
}
