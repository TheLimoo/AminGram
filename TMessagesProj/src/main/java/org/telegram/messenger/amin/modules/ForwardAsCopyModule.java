package org.telegram.messenger.amin.modules;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

/**
 * Forward as Copy (No-Quote / without attribution) — sets {@code drop_author}
 * (and optionally {@code drop_media_captions}) on outgoing forward requests so
 * forwarded messages arrive without the "Forwarded from" header.
 */
public class ForwardAsCopyModule implements ModModule {

    private static final String PREFS = "amingram_plugins";
    public static final String KEY_DROP_AUTHOR = "forward_as_copy_drop_author";
    public static final String KEY_DROP_CAPTION = "forward_as_copy_drop_caption";

    @Override
    public String id() {
        return "forward_as_copy";
    }

    @Override
    public String title() {
        return "Forward as Copy";
    }

    @Override
    public String description() {
        return "Re-posts forwarded messages without the “Forwarded from” attribution header (sets drop_author on forward requests), with an optional toggle to strip media captions (drop_caption).";
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
        boolean dropAuth = dropAuthor(context);
        boolean dropCap = dropCaption(context);
        if (dropAuth && dropCap) {
            return "Active — no header · captions stripped";
        } else if (dropAuth) {
            return "Active — no “Forwarded from” header";
        } else if (dropCap) {
            return "Active — captions stripped";
        }
        return "Active — all flags off";
    }

    public static boolean dropAuthor(Context context) {
        if (context == null) {
            return true;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_DROP_AUTHOR, true);
    }

    public static void setDropAuthor(Context context, boolean value) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DROP_AUTHOR, value)
                .apply();
    }

    public static boolean dropCaption(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_DROP_CAPTION, false);
    }

    public static void setDropCaption(Context context, boolean value) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DROP_CAPTION, value)
                .apply();
    }
}
