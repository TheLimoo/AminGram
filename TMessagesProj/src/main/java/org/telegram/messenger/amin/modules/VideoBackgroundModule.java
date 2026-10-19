package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.messenger.amin.ui.VideoBackgroundHelper;

import java.io.File;

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
        String path = VideoBackgroundHelper.getVideoPath(context);
        if (path == null || !new File(path).exists()) {
            return "Needs a video file";
        }
        return "Active · " + new File(path).getName();
    }
}
