package org.telegram.messenger.amin.pyplugins;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Per-plugin log for the Python engine, mirroring the JS engine's
 * {@code log_<id>} prefs entries (capped at {@link PyPluginsPrefs#LOG_CAP}
 * lines, newest last). The hub's log viewer reads the same key via
 * {@code AminStoreListActivity}, so no new viewer is needed in M0.
 * Every entry is timestamped; install / enable / disable / DEX-load
 * / error events are recorded here as well as in logcat.
 */
public final class PyPluginLog {

    private PyPluginLog() {
    }

    private static final ThreadLocal<SimpleDateFormat> DATE_FMT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss", Locale.US);
                }
            };

    public static void append(Context context, String pluginId, String line) {
        if (context == null || pluginId == null || line == null) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    PyPluginsPrefs.PREFS, Context.MODE_PRIVATE);
            String key = PyPluginsPrefs.KEY_LOG_PREFIX + pluginId;
            JSONArray arr = new JSONArray();
            String raw = prefs.getString(key, null);
            if (raw != null) {
                try {
                    arr = new JSONArray(raw);
                } catch (Exception ignored) {
                }
            }
            String stamped = DATE_FMT.get().format(new Date()) + " " + line;
            arr.put(stamped);
            while (arr.length() > PyPluginsPrefs.LOG_CAP) {
                arr.remove(0);
            }
            prefs.edit().putString(key, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static void clear(Context context, String pluginId) {
        if (context == null || pluginId == null) {
            return;
        }
        try {
            context.getSharedPreferences(PyPluginsPrefs.PREFS, Context.MODE_PRIVATE)
                    .edit().remove(PyPluginsPrefs.KEY_LOG_PREFIX + pluginId).apply();
        } catch (Throwable ignored) {
        }
    }
}
