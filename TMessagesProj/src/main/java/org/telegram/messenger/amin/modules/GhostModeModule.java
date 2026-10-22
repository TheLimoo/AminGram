package org.telegram.messenger.amin.modules;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Ghost Mode module descriptor and its granular suppression
 * preferences. The master switch remains the PluginRegistry module
 * toggle; these three sub-toggles only narrow what the master switch
 * suppresses. They live in PluginRegistry's existing module prefs
 * store and all default to ON, preserving the pre-Stage-2 behaviour
 * for anyone who already had Ghost Mode enabled.
 */
public class GhostModeModule implements ModModule {

    /** PluginRegistry's module prefs store (kept as a literal here
     *  because PluginRegistry.PREFS is private). */
    private static final String PREFS = "amingram_plugins";

    public static final String KEY_SUPPRESS_READ_RECEIPTS =
            "ghost_mode_suppress_read_receipts";
    public static final String KEY_SUPPRESS_TYPING =
            "ghost_mode_suppress_typing";
    public static final String KEY_SUPPRESS_ONLINE =
            "ghost_mode_suppress_online";

    @Override public String id() { return "ghost_mode"; }
    @Override public String title() { return "Ghost Mode"; }
    @Override public String description() {
        return "Read messages without sending read receipts, and hide your online and typing status from others.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_PRIVACY; }
    @Override public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        List<String> active = new ArrayList<>();
        if (suppressReadReceipts(context)) {
            active.add("receipts");
        }
        if (suppressTyping(context)) {
            active.add("typing");
        }
        if (suppressOnlineStatus(context)) {
            active.add("online");
        }
        if (active.isEmpty()) {
            return "Active — no suppressions selected";
        }
        StringBuilder out = new StringBuilder("Active — hiding ");
        for (int i = 0; i < active.size(); i++) {
            if (i > 0) {
                out.append(i == active.size() - 1 ? " and " : ", ");
            }
            out.append(active.get(i));
        }
        return out.toString();
    }

    public static boolean suppressReadReceipts(Context context) {
        return getPreference(context, KEY_SUPPRESS_READ_RECEIPTS);
    }

    public static boolean suppressTyping(Context context) {
        return getPreference(context, KEY_SUPPRESS_TYPING);
    }

    public static boolean suppressOnlineStatus(Context context) {
        return getPreference(context, KEY_SUPPRESS_ONLINE);
    }

    public static void setSuppressReadReceipts(Context context, boolean suppress) {
        setPreference(context, KEY_SUPPRESS_READ_RECEIPTS, suppress);
    }

    public static void setSuppressTyping(Context context, boolean suppress) {
        setPreference(context, KEY_SUPPRESS_TYPING, suppress);
    }

    public static void setSuppressOnlineStatus(Context context, boolean suppress) {
        setPreference(context, KEY_SUPPRESS_ONLINE, suppress);
    }

    private static boolean getPreference(Context context, String key) {
        if (context == null) {
            return true;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(key, true);
    }

    private static void setPreference(Context context, String key, boolean value) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(key, value)
                .apply();
    }
}
