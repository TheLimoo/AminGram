package org.telegram.messenger.amin.pyplugins;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lazy Chaquopy bootstrap — M0.
 *
 * Deviation from the Phase 1 design (§1.1, "start on a background
 * thread"): Chaquopy's own docs start Python from
 * {@code Application.onCreate()} — the main thread — and field
 * evidence from other Chaquopy integrations is that
 * {@code Python.start()} belongs on the main thread. This class
 * therefore performs the ONE-TIME start on the main thread (via a
 * latch when called from elsewhere), and does ALL dispatch on the
 * dedicated {@code "AminGramPyPlugins"} thread afterwards. The
 * start cost (~200–500 ms, Chaquopy's own estimate) is paid once,
 * only on first need, never on every cold start.
 *
 * First need = the user opens the Python hub, installs a Python
 * plugin, or an enabled Python plugin exists at app start AND the
 * master switch is on. With the engine OFF (the M0–M2 default),
 * Python never starts: cold start is unchanged.
 *
 * The widget engine's isolated process never starts Python:
 * {@code ApplicationLoader.onCreate()} returns before
 * {@code postInitApplication()} there, so this bootstrap is never
 * even registered; a defensive {@code isEngineProcess()} check is
 * kept here as well.
 */
public final class PyPluginsBootstrap {

    private PyPluginsBootstrap() {
    }

    private static final HandlerThread PY_THREAD;

    static {
        HandlerThread thread = new HandlerThread("AminGramPyPlugins");
        thread.start();
        PY_THREAD = thread;
    }

    private static volatile Context appContext;
    private static final AtomicBoolean startAttempted = new AtomicBoolean(false);
    private static volatile boolean startFailed;

    /** Called once from the {@code postInitApplication()} hook
     *  (patch 0026). Records the context only — starting Python
     *  here would tax every cold start. */
    public static void registerOnAppCreate(Context context) {
        try {
            if (context == null) {
                return;
            }
            if (isWidgetEngineProcess()) {
                return;
            }
            appContext = context.getApplicationContext();
            // If the engine is on and plugins are enabled, warm up
            // in the background (still lazy: the start itself runs
            // on the main thread, once, below).
            if (PyPluginsController.isEngineEnabled(context)
                    && PyPluginsController.hasEnabledPlugins(context)) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        ensureStarted(appContext, false);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    /** Ensures Python is started. Returns true when Python is
     *  usable afterwards. When {@code userInitiated} is false the
     *  engine switch must already be on. */
    public static boolean ensureStarted(Context context, boolean userInitiated) {
        try {
            Context ctx = context != null ? context.getApplicationContext() : appContext;
            if (ctx == null) {
                ctx = ApplicationLoader.applicationContext;
            }
            if (ctx == null || isWidgetEngineProcess()) {
                return false;
            }
            if (Python.isStarted()) {
                return true;
            }
            if (startFailed) {
                return false;
            }
            if (!userInitiated && !PyPluginsController.isEngineEnabled(ctx)) {
                return false;
            }
            if (!startAttempted.compareAndSet(false, true)) {
                // Another thread is starting (or started) — wait briefly.
                for (int i = 0; i < 100; i++) {
                    if (Python.isStarted() || startFailed) {
                        break;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return Python.isStarted();
            }
            final Context startCtx = ctx;
            final AtomicBoolean ok = new AtomicBoolean(false);
            Runnable startTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!Python.isStarted()) {
                            Python.start(new AndroidPlatform(startCtx));
                        }
                        ok.set(Python.isStarted());
                    } catch (Throwable t) {
                        startFailed = true;
                        PyPluginLog.append(startCtx, "engine",
                                "Python.start failed: " + PyPluginHost.shortError(t));
                    }
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                startTask.run();
            } else {
                final CountDownLatch latch = new CountDownLatch(1);
                final Runnable[] holder = new Runnable[1];
                holder[0] = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            startTask.run();
                        } finally {
                            latch.countDown();
                        }
                    }
                };
                AndroidUtilities.runOnUIThread(holder[0]);
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!ok.get() && !Python.isStarted()) {
                startFailed = true;
                return false;
            }
            // Python is up: load enabled plugins on the py thread.
            post(new Runnable() {
                @Override
                public void run() {
                    PyPluginsController.loadEnabledPlugins(appContext);
                }
            });
            return true;
        } catch (Throwable t) {
            startFailed = true;
            return false;
        }
    }

    /** Post work to the dedicated Python thread (never the UI thread). */
    public static void post(Runnable runnable) {
        try {
            new Handler(PY_THREAD.getLooper()).post(runnable);
        } catch (Throwable ignored) {
        }
    }

    public static void postDelayed(Runnable runnable, long delayMs) {
        try {
            new Handler(PY_THREAD.getLooper()).postDelayed(runnable, Math.max(0L, delayMs));
        } catch (Throwable ignored) {
        }
    }

    /** True when called on the dedicated Python thread. */
    public static boolean isPyThread() {
        try {
            return Thread.currentThread() == PY_THREAD;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isWidgetEngineProcess() {
        try {
            return org.telegram.utils.twidget.WidgetEngineService.isEngineProcess();
        } catch (Throwable t) {
            return false;
        }
    }
}
