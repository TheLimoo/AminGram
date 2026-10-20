package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.core.WallpaperLibraryStore;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class VideoBackgroundModule implements ModModule {
    @Override public String id() { return "video_background"; }
    @Override public String title() { return "Video Chat Background"; }
    @Override public String description() {
        return "Plays a video or GIF behind your chats instead of the static wallpaper. Pick a file below; it plays only while a chat is open.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_MEDIA; }
    @Override public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        try {
            WallpaperLibraryStore.Entry active = WallpaperLibraryStore.getActive(context);
            if (active != null) {
                return "Active · " + active.displayName;
            }
            if (WallpaperLibraryStore.count(context) > 0) {
                return "No wallpaper selected";
            }
        } catch (Throwable ignored) {
        }
        return "Needs a video file";
    }
}
