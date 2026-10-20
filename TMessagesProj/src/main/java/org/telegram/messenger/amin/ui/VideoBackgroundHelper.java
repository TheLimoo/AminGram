package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.amin.AminHooks;
import org.telegram.messenger.amin.core.WallpaperLibraryStore;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;

/**
 * AminGram video/GIF chat background: an animated layer directly above
 * the static wallpaper and directly below the message list inside a
 * chat's content view. The file is the ACTIVE item of the live
 * wallpaper library (core/WallpaperLibraryStore, v2.0); the get/set
 * path accessors below are compatibility delegates to that store —
 * the store keeps the legacy "amingram_videobg"/"path" pref synced
 * to the active item. Lifecycle is driven by ChatActivity hooks
 * (patch 0004, unchanged since v1.1).
 *
 * v1.3 rework (the "renders nothing" fix). The v1.1/v1.2 helper
 * inserted the TextureView during ChatActivity.createView — at that
 * moment the message list does not exist yet (ChatActivity adds
 * chatListView to contentView much later, createView line ~7027), so
 * the layer's final z-position depended on insertion order side
 * effects, and the view only became VISIBLE from the player's
 * first-frame callback, which is not guaranteed to fire for a muted
 * looping file surface. The rework:
 *
 * - attachIfEnabled (createView hook) only validates and remembers
 *   the parent + file; nothing is inserted yet.
 * - The layer is inserted lazily from resume() (the onResume hook),
 *   when the chat hierarchy is complete, at the index of the message
 *   list (the RecyclerView child) — i.e. directly UNDER the list and
 *   above the wallpaper, regardless of what upstream added meanwhile.
 * - The view is revealed when the player reaches STATE_READY as well
 *   as on the first rendered frame; a center-crop texture transform
 *   is applied on video-size changes.
 * - A zero-length file (failed picker copy) is rejected up front.
 *
 * Playback stays strictly on-screen: pause() pauses on chat pause,
 * release() tears everything down on fragment destroy. If anything
 * fails, the layer removes itself — worst case is the stock
 * wallpaper, never a crash (all entry points stay isolated).
 */
public class VideoBackgroundHelper {

    private ViewGroup parent;
    private VideoPlayer player;
    private TextureView textureView;
    private ImageView imageView;
    private AnimatedImageDrawable animatedDrawable;
    private String attachedPath;
    private boolean prepared;
    private boolean released;

    /** Compatibility delegate: the active library item's path (the
     *  store self-heals a dangling active id to null). Playback below
     *  keeps reading through here, so applying a different library
     *  item swaps the file a chat picks up on resume via the
     *  shouldRelease() path-equality check. */
    public static String getVideoPath(Context context) {
        if (context == null) {
            return null;
        }
        try {
            return WallpaperLibraryStore.getActivePath(context);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Compatibility delegate: null deactivates (library items are
     *  kept); a path is adopted into the library and applied (an
     *  already-indexed file is applied in place, anything else is
     *  copied in first; a missing/unreadable file changes nothing). */
    public static void setVideoPath(Context context, String path) {
        if (context == null) {
            return;
        }
        try {
            if (path == null) {
                WallpaperLibraryStore.clearActive(context);
            } else {
                WallpaperLibraryStore.adoptPath(context, path, "Current");
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isGif(String path) {
        return path != null && path.toLowerCase().endsWith(".gif");
    }

    /** Validate + remember. Called from the ChatActivity.createView
     *  hook; the actual layer is inserted later, from resume(). */
    public static VideoBackgroundHelper attachIfEnabled(Context context, ViewGroup parent) {
        if (context == null || parent == null) {
            return null;
        }
        if (!AminHooks.moduleEnabled("video_background")) {
            return null;
        }
        String path = getVideoPath(context);
        if (path == null) {
            return null;
        }
        File file = new File(path);
        if (!file.exists() || file.length() == 0) {
            FileLog.e("AminGram videobg: file missing or empty: " + path);
            return null;
        }
        VideoBackgroundHelper helper = new VideoBackgroundHelper();
        helper.parent = parent;
        helper.attachedPath = path;
        return helper;
    }

    /** The index directly under the message list: the first
     *  RecyclerView child of the chat content view. Falls back to
     *  just above the wallpaper slot when no list is found. */
    private static int insertionIndex(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof RecyclerView) {
                return i;
            }
        }
        return Math.min(1, parent.getChildCount());
    }

    private boolean ensureAttached(Context context) {
        if (textureView != null || imageView != null) {
            return true;
        }
        if (parent == null || attachedPath == null) {
            return false;
        }
        if (isGif(attachedPath)) {
            return attachGif(context, parent, attachedPath);
        }
        return attachVideo(context, parent, attachedPath);
    }

    private boolean attachVideo(Context context, ViewGroup parent, String path) {
        try {
            textureView = new TextureView(context);
            textureView.setOpaque(false);
            textureView.setVisibility(View.INVISIBLE);
            parent.addView(textureView, insertionIndex(parent),
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            player = new VideoPlayer(false, true);
            player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                    // STATE_READY is a reliable "frames are flowing"
                    // signal; reveal there too, not only on the
                    // first-frame callback (the v1.3 fix).
                    if (playbackState == androidx.media3.common.Player.STATE_READY) {
                        reveal();
                    }
                }

                @Override
                public void onError(VideoPlayer p, Exception e) {
                    FileLog.e("AminGram videobg: player error, removing layer", e);
                    release();
                }

                @Override
                public void onVideoSizeChanged(int width, int height,
                                               int unappliedRotationDegrees,
                                               float pixelWidthHeightRatio) {
                    applyCenterCrop(width, height, pixelWidthHeightRatio);
                }

                @Override
                public void onRenderedFirstFrame() {
                    reveal();
                }
            });
            player.setLooping(true);
            player.setTextureView(textureView);
            // NOT preparePlayerLoop(uri, "file", null, null): upstream's
            // loop API unconditionally builds a second (audio) media
            // source and mediaSourceFromUri switches on the null type
            // -> NPE inside ChatActivity.createView (the v1.0 crash).
            // preparePlayer + setLooping gives the same muted loop.
            player.preparePlayer(Uri.fromFile(new File(path)), "file");
            prepared = true;
            return true;
        } catch (Throwable t) {
            FileLog.e("AminGram videobg: attach failed, no layer", t);
            release();
            return false;
        }
    }

    private void reveal() {
        if (!released && textureView != null
                && textureView.getVisibility() != View.VISIBLE) {
            textureView.setVisibility(View.VISIBLE);
        }
    }

    /** Center-crop the video into the (full-screen) texture view. */
    private void applyCenterCrop(int videoWidth, int videoHeight, float pixelRatio) {
        if (textureView == null || videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        try {
            float videoAspect = (float) videoWidth * pixelRatio / videoHeight;
            float viewAspect = (float) viewWidth / viewHeight;
            Matrix matrix = new Matrix();
            float scaleX = 1f;
            float scaleY = 1f;
            if (videoAspect > viewAspect) {
                scaleX = videoAspect / viewAspect;
            } else {
                scaleY = viewAspect / videoAspect;
            }
            matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
            textureView.setTransform(matrix);
        } catch (Throwable ignored) {
        }
    }

    private boolean attachGif(Context context, ViewGroup parent, String path) {
        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
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
                }
            } else {
                // Pre-28 fallback: static first frame beats no layer.
                imageView.setImageBitmap(BitmapFactory.decodeFile(path));
                if (imageView.getDrawable() == null) {
                    imageView = null;
                    return false;
                }
            }
        } catch (Throwable t) {
            FileLog.e("AminGram videobg: gif decode failed, not attaching", t);
            imageView = null;
            return false;
        }
        parent.addView(imageView, insertionIndex(parent),
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        return true;
    }

    /** True when the layer must come off now: module toggled off, file
     *  unset/removed, or a different file was picked since attach. The
     *  ChatActivity onResume hook releases the helper when this is
     *  true. */
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
        if (released || parent == null) {
            return;
        }
        try {
            Context context = parent.getContext();
            if (!ensureAttached(context)) {
                return;
            }
            if (player != null) {
                player.play();
            }
            if (animatedDrawable != null) {
                animatedDrawable.start();
            }
        } catch (Throwable t) {
            FileLog.e("AminGram videobg: resume failed, removing layer", t);
            release();
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
        parent = null;
        prepared = false;
    }
}
