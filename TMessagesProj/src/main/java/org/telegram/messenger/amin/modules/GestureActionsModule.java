package org.telegram.messenger.amin.modules;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

/**
 * Configurable Double-Tap & Swipe Actions — maps message double-tap to
 * React / Reply / Save to Saved Messages / Translate / Forward as Copy,
 * optionally maps double-tap on the user's own sent messages to Edit, and
 * allows customizing the message swipe gesture action.
 */
public class GestureActionsModule implements ModModule {

    private static final String PREFS = "amingram_plugins";
    public static final String KEY_DOUBLE_TAP = "gesture_double_tap_action";
    public static final String KEY_DOUBLE_TAP_OWN_EDIT = "gesture_double_tap_own_edit";
    public static final String KEY_SWIPE_ACTION = "gesture_swipe_action";

    public static final int ACTION_NONE = 0;
    public static final int ACTION_REACT = 1;
    public static final int ACTION_REPLY = 2;
    public static final int ACTION_SAVE = 3;
    public static final int ACTION_TRANSLATE = 4;
    public static final int ACTION_FORWARD_COPY = 5;

    public static final String[] DOUBLE_TAP_LABELS = {
            "Off (Upstream default)",
            "Quick React",
            "Reply",
            "Save to Saved Messages",
            "Translate",
            "Forward as Copy"
    };

    public static final int SWIPE_REPLY = 0;
    public static final int SWIPE_SAVE = 1;
    public static final int SWIPE_TRANSLATE = 2;
    public static final int SWIPE_FORWARD_COPY = 3;

    public static final String[] SWIPE_LABELS = {
            "Reply (Default)",
            "Save to Saved Messages",
            "Translate",
            "Forward as Copy"
    };

    @Override
    public String id() {
        return "gesture_actions";
    }

    @Override
    public String title() {
        return "Double-Tap & Swipe Actions";
    }

    @Override
    public String description() {
        return "Customizes what happens when you double-tap or swipe a message (React, Reply, Save to Saved Messages, Translate, or Forward as Copy), plus double-tap your own message to Edit.";
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
        int dt = doubleTapAction(context);
        boolean ownEdit = doubleTapOwnEdit(context);
        int sw = swipeAction(context);
        return "Double-tap: " + doubleTapLabel(dt)
                + (ownEdit ? " (own = Edit)" : "")
                + " · Swipe: " + swipeLabel(sw);
    }

    public static int doubleTapAction(Context context) {
        if (context == null) {
            return ACTION_REACT;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int v = prefs.getInt(KEY_DOUBLE_TAP, ACTION_REACT);
        return (v >= 0 && v < DOUBLE_TAP_LABELS.length) ? v : ACTION_REACT;
    }

    public static void setDoubleTapAction(Context context, int action) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_DOUBLE_TAP, action)
                .apply();
    }

    public static String doubleTapLabel(int action) {
        if (action >= 0 && action < DOUBLE_TAP_LABELS.length) {
            return DOUBLE_TAP_LABELS[action];
        }
        return DOUBLE_TAP_LABELS[ACTION_REACT];
    }

    public static boolean doubleTapOwnEdit(Context context) {
        if (context == null) {
            return true;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_DOUBLE_TAP_OWN_EDIT, true);
    }

    public static void setDoubleTapOwnEdit(Context context, boolean value) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DOUBLE_TAP_OWN_EDIT, value)
                .apply();
    }

    public static int swipeAction(Context context) {
        if (context == null) {
            return SWIPE_REPLY;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int v = prefs.getInt(KEY_SWIPE_ACTION, SWIPE_REPLY);
        return (v >= 0 && v < SWIPE_LABELS.length) ? v : SWIPE_REPLY;
    }

    public static void setSwipeAction(Context context, int action) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SWIPE_ACTION, action)
                .apply();
    }

    public static String swipeLabel(int action) {
        if (action >= 0 && action < SWIPE_LABELS.length) {
            return SWIPE_LABELS[action];
        }
        return SWIPE_LABELS[SWIPE_REPLY];
    }
}
