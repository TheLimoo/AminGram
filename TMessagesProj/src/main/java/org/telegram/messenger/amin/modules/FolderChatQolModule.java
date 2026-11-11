package org.telegram.messenger.amin.modules;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Folder & Chat-List QoL Pack — bundles three everyday navigation helpers:
 * 1. Mark an entire folder as read (from the chat menu / folder actions).
 * 2. Jump to the very beginning of any chat (message ID 1 / oldest history).
 * 3. Keep folder tabs visible inside the forward picker dialog.
 */
public class FolderChatQolModule implements ModModule {

    private static final String PREFS = "amingram_plugins";
    public static final String KEY_MARK_FOLDER_READ = "qol_mark_folder_read";
    public static final String KEY_JUMP_TO_START = "qol_jump_to_start";
    public static final String KEY_FOLDERS_IN_FORWARD = "qol_folders_in_forward";

    @Override
    public String id() {
        return "folder_chat_qol";
    }

    @Override
    public String title() {
        return "Folder & Chat QoL";
    }

    @Override
    public String description() {
        return "Adds “Mark folder as read” for dialog folders, “Jump to beginning” in chat menus, and keeps folder tabs visible in the forward picker.";
    }

    @Override
    public boolean enabledByDefault() {
        return true;
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
        List<String> on = new ArrayList<>();
        if (markFolderRead(context)) {
            on.add("folder read");
        }
        if (jumpToStart(context)) {
            on.add("jump to start");
        }
        if (foldersInForward(context)) {
            on.add("forward folders");
        }
        if (on.isEmpty()) {
            return "Active — all sub-options off";
        }
        return "Active · " + String.join(", ", on);
    }

    public static boolean markFolderRead(Context context) {
        return getPref(context, KEY_MARK_FOLDER_READ, true);
    }

    public static void setMarkFolderRead(Context context, boolean value) {
        setPref(context, KEY_MARK_FOLDER_READ, value);
    }

    public static boolean jumpToStart(Context context) {
        return getPref(context, KEY_JUMP_TO_START, true);
    }

    public static void setJumpToStart(Context context, boolean value) {
        setPref(context, KEY_JUMP_TO_START, value);
    }

    public static boolean foldersInForward(Context context) {
        return getPref(context, KEY_FOLDERS_IN_FORWARD, true);
    }

    public static void setFoldersInForward(Context context, boolean value) {
        setPref(context, KEY_FOLDERS_IN_FORWARD, value);
    }

    private static boolean getPref(Context context, String key, boolean def) {
        if (context == null) {
            return def;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(key, def);
    }

    private static void setPref(Context context, String key, boolean value) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(key, value)
                .apply();
    }
}
