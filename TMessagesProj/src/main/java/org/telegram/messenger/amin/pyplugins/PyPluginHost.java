package org.telegram.messenger.amin.pyplugins;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.io.File;

/**
 * Java helpers called FROM Python (via Chaquopy's {@code java}
 * module, e.g. {@code jclass("org.telegram.messenger.amin.
 * pyplugins.PyPluginHost")}). M0 surface only:
 *
 * <ul>
 *   <li>{@link #postDelayed} — backs {@code client_utils.run_on_queue}:
 *       a queued callback on the Python thread, never a polling loop.</li>
 *   <li>{@link #showBulletin} / {@link #showError} — back
 *       {@code ui.bulletin.BulletinHelper} in M1: a real Bulletin
 *       from the last fragment ({@code LaunchActivity.getLastFragment},
 *       already used by {@code AminHooks}); a toast + log when no
 *       fragment is available, and the button callback is then
 *       NOT run.</li>
 *   <li>{@link #runOnUiThread} — backs {@code android_utils}.</li>
 *   <li>{@link #pluginFilesDir} — backs {@code file_utils}: a
 *       plugin's own sandboxed files directory.</li>
 *   <li>{@link #requestSettingsReload} — backs
 *       {@code BasePlugin.set_setting(..., reload_settings=True)}.</li>
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

    /** Real Bulletin (M1). {@code icon} is an {@code R.raw.*} id
     *  (<= 0 = default check icon); {@code buttonText} null/empty =
     *  no button. The button callback runs on the Python thread
     *  when tapped. When no fragment is available the call falls
     *  back to a toast + log and the callback is NOT run. */
    public static void showBulletin(final String pluginId, final String text,
                                    final int icon, final String buttonText,
                                    final PyObject onClick) {
        try {
            if (text == null) {
                return;
            }
            log(pluginId, "bulletin: " + text
                    + (buttonText == null || buttonText.isEmpty()
                            ? "" : " [" + buttonText + "]"));
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BaseFragment fragment = LaunchActivity.getLastFragment();
                        if (fragment == null) {
                            toast(pluginId, text);
                            return;
                        }
                        int iconRes = icon > 0 ? icon : R.raw.contact_check;
                        BulletinFactory factory = BulletinFactory.of(fragment);
                        if (buttonText != null && !buttonText.isEmpty()) {
                            final PyObject callback = onClick;
                            factory.createSimpleBulletin(iconRes, text, buttonText,
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            if (callback == null) {
                                                return;
                                            }
                                            PyPluginsBootstrap.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    try {
                                                        callback.call();
                                                    } catch (Throwable t) {
                                                        log(pluginId, "bulletin button callback failed: "
                                                                + shortError(t));
                                                        PyPluginsController.recordDispatchFailure(
                                                                ApplicationLoader.applicationContext,
                                                                pluginId, t);
                                                    }
                                                }
                                            });
                                        }
                                    }).show();
                        } else {
                            factory.createSimpleBulletin(iconRes, text).show();
                        }
                    } catch (Throwable t) {
                        log(pluginId, "bulletin failed, falling back to toast: "
                                + shortError(t));
                        toast(pluginId, text);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Error bulletin (M1): the error icon, no button. */
    public static void showError(final String pluginId, final String text) {
        showBulletin(pluginId, text, R.raw.error, null, null);
    }

    /** Run a Python callable on the UI thread (android_utils).
     *  Keep the callable short — it runs on the UI thread itself. */
    public static void runOnUiThread(final String pluginId, final PyObject callable) {
        try {
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (callable != null) {
                            callable.call();
                        }
                    } catch (Throwable t) {
                        log(pluginId, "run_on_ui_thread callback failed: " + shortError(t));
                        PyPluginsController.recordDispatchFailure(
                                ApplicationLoader.applicationContext, pluginId, t);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Write-only clipboard (android_utils; JS-parity). */
    public static void copyToClipboard(final String pluginId, final String text) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || text == null) {
                return;
            }
            ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("AminGram plugin", text));
            }
            log(pluginId, "copied text to clipboard");
        } catch (Throwable ignored) {
        }
    }

    /** A plugin's own sandboxed files directory
     *  ({@code filesDir/pyplugins/<id>/files}), created on demand.
     *  Returns null for a malformed plugin id — the Python side
     *  raises instead of touching a wrong directory. */
    public static String pluginFilesDir(String pluginId) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || pluginId == null
                    || !pluginId.matches("[A-Za-z0-9_-]{2,32}")) {
                return null;
            }
            File dir = new File(new File(new File(context.getFilesDir(),
                    PyPluginsPrefs.PLUGIN_DIR), pluginId), "files");
            dir.mkdirs();
            return dir.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** A plugin changed a setting with reload_settings=True (or
     *  imported settings): the open plugin page, if any, re-reads
     *  create_settings(). No-op when the page is not open. */
    public static void requestSettingsReload(String pluginId) {
        try {
            PyPluginsController.notifySettingsChanged(pluginId);
        } catch (Throwable ignored) {
        }
    }

    /** Bulletin fallback: toast + log (used when no fragment is
     *  available, and by the M0 toast path). */
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
