package org.telegram.messenger.amin.modules;

import org.telegram.messenger.amin.plugins.ModModule;

public class VideoBackgroundModule implements ModModule {
    @Override public String id() { return "video_background"; }
    @Override public String title() { return "Video Chat Background"; }
    @Override public String description() {
        return "Use an mp4 or gif as an animated chat background (global or per chat).";
    }
    @Override public boolean enabledByDefault() { return false; }
}
