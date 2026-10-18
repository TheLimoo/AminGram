package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.TextureView;
import android.view.ViewGroup;

import org.telegram.messenger.amin.AminHooks;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;

/**
 * AminGram video chat background: attaches a muted, looping video layer
 * directly above the static wallpaper inside a chat's content view.
 * The video file path lives in our own prefs ("amingram_videobg"/"path"),
 * set from the Plugins hub. Lifecycle is driven by ChatActivity hooks.
 */
public class VideoBackgroundHelper {

    private static final String PREFS = "amingram_videobg";
    private static final String KEY_PATH = "path";

    private VideoPlayer player;
    private TextureView textureView;

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

    /** Attach if the module is enabled and a video file is set. Returns the
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
        helper.textureView = new TextureView(context);
        parent.addView(helper.textureView, Math.min(1, parent.getChildCount()),
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        helper.player = new VideoPlayer(false, true);
        helper.player.setTextureView(helper.textureView);
        helper.player.preparePlayerLoop(Uri.fromFile(new File(path)), "file", null, null);
        helper.player.play();
        return helper;
    }

    public void resume() {
        if (player != null) {
            player.play();
        }
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    public void release() {
        if (player != null) {
            player.releasePlayer(false);
            player = null;
        }
        if (textureView != null) {
            ViewGroup parent = (ViewGroup) textureView.getParent();
            if (parent != null) {
                parent.removeView(textureView);
            }
            textureView = null;
        }
    }
}
