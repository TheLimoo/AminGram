package org.telegram.messenger.amin.pyplugins;

import android.content.Context;
import android.widget.Toast;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

/**
 * Java helpers called FROM Python (via Chaquopy's {@code java}
 * module, e.g. {@code jclass("org.telegram.messenger.amin.
 * pyplugins.PyPluginHost")}). M0 surface only:
 *
 * <ul>
 *   <li>{@link #postDelayed} — backs {@code client_utils.run_on_queue}:
 *       a queued callback on the Python thread, never a polling loop.</li>
 *   <li>{@link #toast} — backs {@code ui.bulletin.BulletinHelper}
 *       in M0: a real Bulletin needs a fragment, which a plugin
 *       does not have at load time, so M0 shows a toast and logs.
 *       M1 renders a real bulletin once the last-fragment helper
 *       lands.</li>
 *   <li>{@link #log} — plugin log lines into logcat under
 *       {@code plugin.<id>} and into the hub log.</li>
 * </ul>
 *
 * Every entry is Throwable-isolated: a failure here must never
 * crash the plugin thread or the app.
 */
public final class PyPluginHost {

    private PyPluginHost() {
    }

    /** Post a Python callable on the Python thread after {@code delayMs}.
     *  Chaquopy passes a Python function as a {@link PyObject}; it is
     *  invoked with no arguments. Failures are logged to the plugin,
     *  never thrown back into the poster. */
    public static void postDelayed(final String pluginId, final PyObject callable,
                                   final long delayMs) {
        try {
            PyPluginsBootstrap.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (callable != null) {
                            callable.call();
                        }
                    } catch (Throwable t) {
                        log(pluginId, "run_on_queue callback failed: " + shortError(t));
                        PyPluginsController.recordDispatchFailure(
                                ApplicationLoader.applicationContext, pluginId, t);
                    }
                }
            }, Math.max(0L, delayMs));
        } catch (Throwable ignored) {
        }
    }

    /** M0 bulletin fallback: toast + log. */
    public static void toast(final String pluginId, final String text) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || text == null) {
                return;
            }
            log(pluginId, "bulletin: " + text);
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(ApplicationLoader.applicationContext,
                                text, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static void log(String pluginId, String line) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context != null && pluginId != null && line != null) {
                PyPluginLog.append(context, pluginId, line);
            }
            if (pluginId != null && line != null) {
                android.util.Log.i("plugin." + pluginId, line);
            }
        } catch (Throwable ignored) {
        }
    }

    /** True once Python has started in this process. Used by the
     *  hub to report engine state honestly. */
    public static boolean isPythonStarted() {
        try {
            return Python.isStarted();
        } catch (Throwable t) {
            return false;
        }
    }

    static String shortError(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String msg = t.getMessage();
        String name = t.getClass().getSimpleName();
        return msg == null || msg.isEmpty() ? name : name + ": " + msg;
    }
}
