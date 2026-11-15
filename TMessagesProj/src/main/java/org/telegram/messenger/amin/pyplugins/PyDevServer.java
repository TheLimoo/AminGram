package org.telegram.messenger.amin.pyplugins;

import android.os.Handler;
import android.os.Looper;

import org.telegram.messenger.ApplicationLoader;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * DevServer Android wrapper (M4).
 *
 * Lifecycle rules (the security part lives here, not in the
 * pure HTTP core):
 * <ul>
 *   <li>Default OFF. {@link #start()} only runs after an
 *       explicit tap on the hub's "Dev server" row.</li>
 *   <li>Loopback only: the socket is bound with
 *       {@link PyDevServerHttp#bindLoopback(int)}; a bind
 *       failure stops the start, never retries on another
 *       address.</li>
 *   <li>Auto-stop: the hub calls {@link #stop()} when it is
 *       closed, and a delayed stop fires 60 s after the app
 *       goes to background (the hub posts it; the timer is
 *       cancelled if the app returns). A dev server left
 *       running by accident dies on its own.</li>
 *   <li>One upload at a time, one connection at a time
 *       (the core is single-threaded).</li>
 *   <li>Uploaded bytes go through the normal install gates
 *       via {@link PyPluginsController#installBytes(byte[], String, boolean)} —
 *       the server itself never executes anything.</li>
 * </ul>
 *
 * The token is generated fresh on every start, shown in the
 * hub and nowhere else, and cleared on stop. It is never
 * written to disk, to SharedPreferences, or to the log.
 */
public final class PyDevServer {

    private static volatile PyDevServer INSTANCE;

    private final Object lock = new Object();
    private Thread acceptThread;
    private ServerSocket serverSocket;
    private String token;
    private boolean running;

    private PyDevServer() {
    }

    public static PyDevServer getInstance() {
        if (INSTANCE == null) {
            synchronized (PyDevServer.class) {
                if (INSTANCE == null) {
                    INSTANCE = new PyDevServer();
                }
            }
        }
        return INSTANCE;
    }

    /** True while the server thread is up. */
    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    /** Current token, or null when stopped. Never logged. */
    public String getToken() {
        synchronized (lock) {
            return token;
        }
    }

    /** Port the server listens on (fixed). */
    public int getPort() {
        return PyDevServerHttp.PORT;
    }

    /**
     * Starts the server on 127.0.0.1. Returns null on success,
     * otherwise a readable error. Starting twice is a no-op
     * success.
     */
    public String start() {
        synchronized (lock) {
            if (running) {
                return null;
            }
            final String freshToken = PyDevServerHttp.newToken();
            final ServerSocket socket;
            try {
                socket = PyDevServerHttp.bindLoopback(PyDevServerHttp.PORT);
            } catch (IOException e) {
                return "Could not bind 127.0.0.1:" + PyDevServerHttp.PORT
                        + " (is another dev server running?).";
            }
            token = freshToken;
            serverSocket = socket;
            running = true;
            Thread thread = new Thread(() -> acceptLoop(socket, freshToken),
                    "amingram-devserver");
            thread.setDaemon(true);
            acceptThread = thread;
            thread.start();
            return null;
        }
    }

    /** Stops the server and forgets the token. Safe to call twice. */
    public void stop() {
        ServerSocket socket;
        Thread thread;
        synchronized (lock) {
            if (!running) {
                token = null;
                return;
            }
            running = false;
            token = null;
            socket = serverSocket;
            thread = acceptThread;
            serverSocket = null;
            acceptThread = null;
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void acceptLoop(ServerSocket socket, String token) {
        while (true) {
            synchronized (lock) {
                if (!running) {
                    return;
                }
            }
            final Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                return; // socket closed by stop()
            }
            // Single-threaded by design: one upload at a time.
            // Bytes go through the normal install gates; the
            // server never executes them itself.
            PyDevServerHttp.serveOne(client, token, (bytes, fileName) -> {
                try {
                    String refused = PyPluginsController.installFromBytes(
                            ApplicationLoader.applicationContext, bytes, "devserver");
                    return refused == null ? "Installed " + fileName + "."
                            : "Refused: " + refused;
                } catch (Exception e) {
                    return "Install failed: " + e.getMessage();
                }
            });
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoStopRunnable = this::stop;

    /** Posts a stop 60 s from now (hub calls this on background). */
    public void scheduleAutoStop() {
        mainHandler.removeCallbacks(autoStopRunnable);
        mainHandler.postDelayed(autoStopRunnable, 60_000L);
    }

    /** Cancels a pending auto-stop (hub calls this on foreground). */
    public void cancelAutoStop() {
        mainHandler.removeCallbacks(autoStopRunnable);
    }
}
