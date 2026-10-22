package org.telegram.messenger.amin.dns;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the DNS Tunnel state machine and every interaction with
 * Telegram's proxy configuration.
 *
 *   OFF → STARTING → CONNECTED / ERROR
 *
 * The rules that keep this safe (plan §4.1/§4.4):
 *  - The user's current proxy is snapshotted BEFORE the tunnel
 *    proxy is applied, and restored exactly on stop — including
 *    the death paths: service onDestroy funnels into
 *    {@link #onServiceDied()}, and if the whole process dies,
 *    {@link #recoverIfStale(Context)} (called from
 *    ApplicationLoader.postInitApplication via patch 0021, and
 *    lazily from every status read) restores from the snapshot.
 *    A tunnel can never leave Telegram pointing at a dead local
 *    port.
 *  - The proxy is applied only AFTER the engine proves liveness
 *    with a real SOCKS round-trip through the tunnel (the
 *    readiness gate): a dead tunnel degrades to an ERROR state
 *    with the user's proxy untouched, never to a blackholed
 *    Telegram.
 *  - All engine + proxy work is serialized on one worker thread.
 *
 * The engines themselves run under AminDnsTunnelService (a
 * specialUse foreground service — no VPN permission, no wakelock);
 * this controller is the only orchestrator.
 */
public final class AminDnsController {

    public enum State {OFF, STARTING, CONNECTED, ERROR}

    public interface Listener {
        void onDnsStateChanged();
    }

    private static final String SNAP_PREFS = "amingram_dns_snapshot";
    private static final String KEY_TUNNEL_ON = "tunnel_on";
    private static final String KEY_TUNNEL_PORT = "tunnel_port";
    private static final String KEY_SNAP_HAS = "snap_has_proxy";
    private static final String KEY_SNAP_ENABLED = "snap_proxy_enabled";

    /** Liveness probe target: a Telegram DC on 443 (plan §4.4). */
    private static final String PROBE_HOST = "91.108.56.130";
    private static final int PROBE_PORT = 443;
    private static final long SOAR_LIVENESS_MS = 25_000;
    private static final long SLIPSTREAM_LIVENESS_MS = 15_000;

    private static AminDnsController instance;
    private static final AtomicBoolean staleChecked = new AtomicBoolean();

    private final Context appContext;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AminDnsWorker");
        t.setDaemon(true);
        return t;
    });
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile State state = State.OFF;
    private volatile String error = "";
    private volatile int localPort;
    private volatile String activeResolver = "";
    private volatile long connectedSinceElapsed;
    private volatile boolean cancelStart;
    private volatile boolean stopInProgress;
    private volatile SlipstreamBridge slipstream;
    private volatile boolean proxyApplied;

    private AminDnsController(Context context) {
        appContext = context.getApplicationContext();
    }

    public static synchronized AminDnsController get(Context context) {
        if (instance == null) {
            instance = new AminDnsController(context);
        }
        return instance;
    }

    // ---------------------------------------------------------------
    // Status surface (Proxy page row, hub rows, DNS activity)
    // ---------------------------------------------------------------

    public static String proxyRowValue(Context context) {
        recoverIfStale(context);
        AminDnsController c = get(context);
        switch (c.state) {
            case STARTING:
                return c.engineLabel() + " · Connecting";
            case CONNECTED:
                return c.engineLabel() + " · Connected";
            case ERROR:
                return "Error";
            case OFF:
            default:
                return "Off";
        }
    }

    /** True once the prebuilt Slipstream binaries are present in jniLibs. */
    public static boolean isSlipstreamAvailable(Context context) {
        return context != null && SlipstreamBridge.isAvailable(context);
    }

    public State state() {
        return state;
    }

    public String error() {
        return error;
    }

    public int localPort() {
        return localPort;
    }

    public String activeResolver() {
        return activeResolver;
    }

    public long uptimeSeconds() {
        if (state != State.CONNECTED || connectedSinceElapsed == 0) {
            return 0;
        }
        return Math.max(0, (SystemClock.elapsedRealtime() - connectedSinceElapsed) / 1000);
    }

    public String engineLabel() {
        return DnsProfile.ENGINE_SLIPSTREAM.equals(DnsProfiles.engine(appContext)) ? "Slipstream" : "Soar";
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : listeners) {
                try {
                    l.onDnsStateChanged();
                } catch (Throwable ignored) {
                }
            }
        });
        // The Proxy settings page refreshes its rows on this
        // notification — our entry row's status text rides along.
        try {
            NotificationCenter.getGlobalInstance()
                    .postNotificationName(NotificationCenter.proxySettingsChanged);
        } catch (Throwable ignored) {
        }
    }

    private void setState(State newState) {
        state = newState;
        notifyListeners();
    }

    // ---------------------------------------------------------------
    // Start / stop
    // ---------------------------------------------------------------

    public void toggle(Context context) {
        if (state == State.CONNECTED || state == State.STARTING) {
            stop();
        } else {
            start();
        }
    }

    public void start() {
        worker.execute(() -> {
            if (state == State.STARTING || state == State.CONNECTED) {
                return;
            }
            doStart();
        });
    }

    public void stop() {
        cancelStart = true;
        worker.execute(this::doStop);
    }

    /** Re-runs the tunnel against the CURRENT active profile (profile switched while on). */
    public void restart() {
        worker.execute(() -> {
            doStop();
            doStart();
        });
    }

    private void doStart() {
        Context context = appContext;
        DnsProfile profile = DnsProfiles.activeProfile(context);
        if (profile == null) {
            fail("No profile configured");
            return;
        }
        boolean slip = !profile.isSoar();
        if (slip && !SlipstreamBridge.isAvailable(context)) {
            fail("Slipstream engine is not installed in this build yet");
            return;
        }
        cancelStart = false;
        error = "";
        setState(State.STARTING);
        startService(context);

        List<String> resolvers = DnsProfiles.effectiveResolvers(context, profile);
        activeResolver = resolvers.isEmpty() ? "" : resolvers.get(0);
        int port;
        try {
            snapshotProxy();
            if (slip) {
                slipstream = SlipstreamBridge.start(context, profile, resolvers);
                port = slipstream.localPort();
            } else {
                SoarEngine.start(profile, DnsProfiles.resolversCsv(resolvers));
                port = SoarEngine.localPort();
                if (port <= 0) {
                    throw new IllegalStateException("Soar engine did not open a local port");
                }
            }
        } catch (Throwable t) {
            stopEngines();
            clearSnapshot();
            stopService(context);
            if (!cancelStart) {
                fail("Engine failed to start: " + message(t));
            } else {
                setState(State.OFF);
            }
            return;
        }
        localPort = port;

        // Readiness gate (plan §4.4): the proxy is applied only
        // after a full SOCKS round-trip THROUGH the tunnel succeeds.
        long budget = slip ? SLIPSTREAM_LIVENESS_MS : SOAR_LIVENESS_MS;
        boolean live = awaitLiveness(port, budget);
        if (!live || cancelStart) {
            String slipError = slip && slipstream != null ? slipstream.lastError() : "";
            stopEngines();
            clearSnapshot();
            stopService(context);
            if (cancelStart) {
                setState(State.OFF);
            } else {
                fail(!slipError.isEmpty()
                        ? "Tunnel did not answer: " + slipError
                        : "Tunnel did not answer in time — check the resolver / network");
            }
            return;
        }

        applyProxy(port);
        proxyApplied = true;
        connectedSinceElapsed = SystemClock.elapsedRealtime();
        setState(State.CONNECTED);
    }

    private void doStop() {
        if (state == State.OFF) {
            return;
        }
        stopInProgress = true;
        try {
            stopEngines();
            if (proxyApplied) {
                restoreProxy();
                proxyApplied = false;
            } else {
                clearSnapshot();
            }
            localPort = 0;
            connectedSinceElapsed = 0;
            error = "";
            setState(State.OFF);
        } finally {
            stopInProgress = false;
            stopService(appContext);
        }
    }

    private void fail(String message) {
        error = message == null ? "Unknown error" : message;
        setState(State.ERROR);
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    // ---------------------------------------------------------------
    // Engines
    // ---------------------------------------------------------------

    private void stopEngines() {
        try {
            SoarEngine.stop();
        } catch (Throwable ignored) {
        }
        if (slipstream != null) {
            try {
                slipstream.stop();
            } catch (Throwable ignored) {
            }
            slipstream = null;
        }
    }

    private void startService(Context context) {
        try {
            Intent intent = new Intent(context, AminDnsTunnelService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable ignored) {
            // FGS start restrictions: the tunnel is only ever
            // started from a user-visible UI action, so this is a
            // last-resort guard, not an expected path.
        }
    }

    private void stopService(Context context) {
        try {
            context.stopService(new Intent(context, AminDnsTunnelService.class));
        } catch (Throwable ignored) {
        }
    }

    /**
     * The foreground service died without a user stop (system kill
     * of the service while the process lived). Restore everything
     * exactly as a normal stop would.
     */
    public void onServiceDied() {
        if (stopInProgress || state == State.OFF) {
            return;
        }
        worker.execute(() -> {
            if (stopInProgress || state == State.OFF) {
                return;
            }
            stopInProgress = true;
            try {
                stopEngines();
                if (proxyApplied) {
                    restoreProxy();
                    proxyApplied = false;
                } else {
                    clearSnapshot();
                }
                localPort = 0;
                connectedSinceElapsed = 0;
                setState(State.OFF);
            } finally {
                stopInProgress = false;
            }
        });
    }

    // ---------------------------------------------------------------
    // Liveness — a real SOCKS5 round-trip through the tunnel
    // ---------------------------------------------------------------

    private boolean awaitLiveness(int port, long budgetMs) {
        long deadline = SystemClock.elapsedRealtime() + budgetMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancelStart) {
                return false;
            }
            if (socksConnect(port, PROBE_HOST, PROBE_PORT, 4_000)) {
                return true;
            }
            if (slipstream != null && !slipstream.isAlive()) {
                return false;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /** Minimal SOCKS5 client: greeting + CONNECT; true only on a success reply. */
    static boolean socksConnect(int localPort, String targetHost, int targetPort, int timeoutMs) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", localPort), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(new byte[]{5, 1, 0});
            out.flush();
            byte[] greeting = readFully(in, 2);
            if (greeting == null || greeting[0] != 5 || greeting[1] != 0) {
                return false;
            }
            byte[] host = targetHost.getBytes("UTF-8");
            byte[] request = new byte[7 + host.length];
            request[0] = 5;
            request[1] = 1; // CONNECT
            request[2] = 0;
            request[3] = 3; // domain
            request[4] = (byte) host.length;
            System.arraycopy(host, 0, request, 5, host.length);
            request[5 + host.length] = (byte) (targetPort >> 8);
            request[6 + host.length] = (byte) targetPort;
            out.write(request);
            out.flush();
            byte[] head = readFully(in, 4);
            if (head == null || head[0] != 5 || head[1] != 0) {
                return false;
            }
            int atyp = head[3];
            int rest = atyp == 1 ? 4 : atyp == 4 ? 16 : atyp == 3 ? -1 : 0;
            if (rest == -1) {
                byte[] len = readFully(in, 1);
                if (len == null) {
                    return false;
                }
                rest = (len[0] & 0xff) + 2;
            } else {
                rest += 2;
            }
            return readFully(in, rest) != null;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] readFully(InputStream in, int count) throws Exception {
        byte[] buf = new byte[count];
        int read = 0;
        while (read < count) {
            int n = in.read(buf, read, count - read);
            if (n < 0) {
                return null;
            }
            read += n;
        }
        return buf;
    }

    // ---------------------------------------------------------------
    // Proxy snapshot / apply / restore (plan §4.1 recipe)
    // ---------------------------------------------------------------

    private SharedPreferences snapPrefs() {
        return appContext.getSharedPreferences(SNAP_PREFS, Context.MODE_PRIVATE);
    }

    private void snapshotProxy() {
        runOnUiSync(() -> {
            try {
                SharedPreferences snap = snapPrefs();
                SharedPreferences.Editor e = snap.edit();
                SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
                boolean enabled = MessagesController.getGlobalMainSettings()
                        .getBoolean("proxy_enabled", false);
                e.putBoolean(KEY_SNAP_HAS, current != null);
                e.putBoolean(KEY_SNAP_ENABLED, enabled);
                if (current != null) {
                    current.settings.toSharedPreferences(e);
                }
                e.commit();
            } catch (Throwable ignored) {
            }
        });
    }

    private void applyProxy(int port) {
        runOnUiSync(() -> {
            try {
                ProxySettings settings = ProxySettings.builder()
                        .setAddress("127.0.0.1")
                        .setPort(port)
                        .setUser("")
                        .setPassword("")
                        .setSecret("")
                        .setType(ProxySettings.Type.SOCKS5)
                        .build();
                SharedConfig.ProxyInfo info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
                SharedConfig.currentProxy = info;
                SharedPreferences.Editor e = MessagesController.getGlobalMainSettings().edit();
                settings.toSharedPreferences(e);
                e.putBoolean("proxy_enabled", true);
                e.commit();
                ConnectionsManager.setProxySettings(true, settings);
                NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.proxySettingsChanged);
                // The stale-flag pair: written with commit() so a
                // process death can never lose them.
                DnsProfiles.prefs(appContext).edit()
                        .putBoolean(KEY_TUNNEL_ON, true)
                        .putInt(KEY_TUNNEL_PORT, port)
                        .commit();
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * Restores the snapshotted proxy exactly. Safe to call when no
     * snapshot exists (no-op beyond bookkeeping) and never overrides
     * a different proxy the user picked manually in the meantime.
     */
    void restoreProxy() {
        runOnUiSync(() -> {
            try {
                SharedPreferences snap = snapPrefs();
                boolean has = snap.getBoolean(KEY_SNAP_HAS, false);
                boolean enabled = snap.getBoolean(KEY_SNAP_ENABLED, false);
                SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
                boolean currentIsTunnel = isTunnelProxy(current);
                boolean userPickedOther = current != null && !currentIsTunnel;
                if (has && !userPickedOther) {
                    ProxySettings prev = ProxySettings.fromSharedPreferences(snap);
                    SharedConfig.ProxyInfo prevInfo = findOrCreate(prev);
                    SharedConfig.currentProxy = prevInfo;
                    SharedPreferences.Editor e = MessagesController.getGlobalMainSettings().edit();
                    prev.toSharedPreferences(e);
                    e.putBoolean("proxy_enabled", enabled);
                    e.commit();
                    ConnectionsManager.setProxySettings(enabled && prev.isValid(), prev);
                } else if (!has && currentIsTunnel) {
                    // No previous proxy: switch proxying off the way
                    // ProxyListActivity's toggle-off branch does.
                    ConnectionsManager.setProxySettings(false, current.settings);
                    SharedConfig.currentProxy = null;
                    SharedPreferences.Editor e = MessagesController.getGlobalMainSettings().edit();
                    e.putString("proxy_ip", "");
                    e.putString("proxy_pass", "");
                    e.putString("proxy_user", "");
                    e.putString("proxy_secret", "");
                    e.putInt("proxy_type", 0);
                    e.putInt("proxy_port", 1080);
                    e.putBoolean("proxy_enabled", false);
                    e.commit();
                }
                if (has || currentIsTunnel) {
                    NotificationCenter.getGlobalInstance()
                            .postNotificationName(NotificationCenter.proxySettingsChanged);
                }
            } catch (Throwable ignored) {
            } finally {
                clearSnapshot();
                DnsProfiles.prefs(appContext).edit()
                        .putBoolean(KEY_TUNNEL_ON, false)
                        .remove(KEY_TUNNEL_PORT)
                        .commit();
            }
        });
    }

    private SharedConfig.ProxyInfo findOrCreate(ProxySettings settings) {
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (Objects.equals(info.settings, settings)) {
                return info;
            }
        }
        return SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
    }

    private boolean isTunnelProxy(SharedConfig.ProxyInfo info) {
        if (info == null || info.settings == null) {
            return false;
        }
        int tunnelPort = DnsProfiles.prefs(appContext).getInt(KEY_TUNNEL_PORT, -1);
        return info.settings.getType() == ProxySettings.Type.SOCKS5
                && "127.0.0.1".equals(info.settings.getAddress())
                && tunnelPort > 0
                && info.settings.getPort() == tunnelPort;
    }

    private void clearSnapshot() {
        try {
            snapPrefs().edit().clear().commit();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------
    // Stale-flag recovery (process death while the tunnel was on)
    // ---------------------------------------------------------------

    /**
     * If the previous process died mid-tunnel, the durable
     * tunnel_on flag is set but no engine exists in this process —
     * restore the pre-tunnel proxy before anything connects through
     * the dead local port. Called from
     * ApplicationLoader.postInitApplication (patch 0021) and lazily
     * from every status read; runs at most once per process.
     */
    public static void recoverIfStale(Context context) {
        if (context == null) {
            return;
        }
        if (!staleChecked.compareAndSet(false, true)) {
            return;
        }
        try {
            SharedPreferences prefs = DnsProfiles.prefs(context);
            if (!prefs.getBoolean(KEY_TUNNEL_ON, false)) {
                return;
            }
            AminDnsController controller = get(context);
            // This process just started: nothing is applied yet from
            // our side, but Telegram reloaded the persisted tunnel
            // entry as its current proxy. Restore from the snapshot.
            controller.restoreProxy();
            controller.state = State.OFF;
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------

    /** Runs r on the UI thread and waits (bounded) — proxy ops mirror upstream's UI-thread calls. */
    private void runOnUiSync(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                r.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }
}
