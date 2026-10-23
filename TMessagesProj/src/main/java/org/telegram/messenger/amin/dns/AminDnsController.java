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
import java.util.ArrayList;
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
 *  - The module owns exactly ONE entry in Telegram's proxy
 *    list: recorded by identity in the amingram_dns prefs,
 *    updated in place on start (the local port changes), and
 *    REMOVED on stop, when the snapshot selection is restored.
 *    A startup sweep collects module entries left behind by
 *    older builds or crashes. The user's own entries are never
 *    added, edited, or removed.
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

    // Identity of THE one proxy-list entry this module owns,
    // recorded in the amingram_dns prefs at creation (address +
    // port + username as created). The module reuses/updates that
    // entry on start and removes it on stop; the identity is also
    // how the startup sweep recognises a stale module entry after
    // a crash. Never used to match any other entry.
    private static final String KEY_MOD_IP = "mod_entry_ip";
    private static final String KEY_MOD_PORT = "mod_entry_port";
    private static final String KEY_MOD_USER = "mod_entry_user";

    /** Slipstream's fixed local listen range (SlipstreamBridge). */
    private static final int SLIPSTREAM_PORT_MIN = 18080;
    private static final int SLIPSTREAM_PORT_MAX = 18089;

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
    /** Specific, non-retryable liveness failure (e.g. SOCKS auth) for the status line. */
    private volatile String livenessFailure = "";
    private volatile int localPort;
    private volatile String activeResolver = "";
    private volatile long connectedSinceElapsed;
    private volatile boolean cancelStart;
    private volatile boolean stopInProgress;
    /** Set when WE stop the foreground service (stop path or
     *  start-failure cleanup), so the service's onDestroy →
     *  onServiceDied() can tell a controller-initiated stop from a
     *  genuine system kill (audit P1-15). Cleared by onServiceDied
     *  when it consumes it. */
    private volatile boolean serviceStopExpected;
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
        livenessFailure = "";
        setState(State.STARTING);
        if (!startService(context)) {
            // FGS start refused (audit P1-14): without the service
            // the process has no foreground priority and Android
            // may kill it mid-tunnel, leaving Telegram pointed at a
            // dead local port. Fail the attempt BEFORE any engine
            // starts, so there is nothing to clean up.
            fail("Could not start the tunnel service — Android blocked the foreground-service start. Open the app and try again.");
            return;
        }

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
        boolean live = awaitLiveness(profile, port, budget);
        if (!live || cancelStart) {
            String slipError = slip && slipstream != null ? slipstream.lastError() : "";
            stopEngines();
            clearSnapshot();
            stopService(context);
            if (cancelStart) {
                setState(State.OFF);
            } else if (!livenessFailure.isEmpty()) {
                fail(livenessFailure);
            } else {
                fail(!slipError.isEmpty()
                        ? "Tunnel did not answer: " + slipError
                        : "Tunnel did not answer in time — check the resolver / network");
            }
            return;
        }

        try {
            applyProxy(port, profile);
        } catch (Throwable t) {
            // A timed-out apply (P1-16) must not become CONNECTED:
            // Telegram may never have been re-pointed. Tear the
            // engines down and fail with the reason. (The late UI
            // runnable, if it ever runs, applies the same settings
            // this attempt would have — and recoverIfStale/the
            // sweep reconcile any residue at next launch.)
            stopEngines();
            stopService(context);
            fail("Proxy setup failed: " + message(t));
            return;
        }
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
                try {
                    restoreProxy();
                } catch (Throwable ignored) {
                    // Timed out (P1-16): the restore runnable still
                    // runs when the UI thread unblocks, and
                    // recoverIfStale reconciles any residue at the
                    // next launch — the stop completes regardless.
                }
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

    /** Returns false when Android refused the start (audit
     *  P1-14) — the caller must fail the start attempt instead of
     *  proceeding without a foreground service. */
    private boolean startService(Context context) {
        try {
            Intent intent = new Intent(context, AminDnsTunnelService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (Throwable t) {
            // FGS start restrictions (ForegroundServiceStartNotAllowed-
            // Exception on Android 12+ when the start was queued
            // behind other worker tasks and executes after the user
            // backgrounded the app): no longer swallowed — a tunnel
            // without its service is worse than a clear failure.
            return false;
        }
    }

    private void stopService(Context context) {
        // Mark BEFORE stopping (audit P1-15): the service's
        // onDestroy reports into onServiceDied(), which must not
        // mistake this controller-initiated stop for a system kill
        // and overwrite a just-set ERROR state with OFF.
        serviceStopExpected = true;
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
        if (serviceStopExpected) {
            // We stopped the service ourselves (stop path or
            // start-failure cleanup) — its onDestroy is expected,
            // not a system kill. Consume the mark and stand down
            // (audit P1-15).
            serviceStopExpected = false;
            return;
        }
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
                    try {
                        restoreProxy();
                    } catch (Throwable ignored) {
                        // Timed out (P1-16) — same reasoning as
                        // doStop: finish the teardown regardless.
                    }
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

    // Liveness probe outcomes. Only PROBE_OK applies the proxy;
    // PROBE_AUTH_* are non-retryable within a start attempt (the
    // credentials cannot change between retries), so the gate fails
    // fast with a specific status message instead of burning the
    // whole budget.
    private static final int PROBE_OK = 0;
    private static final int PROBE_RETRY = 1;
    private static final int PROBE_AUTH_REQUIRED = 2;
    private static final int PROBE_AUTH_FAILED = 3;

    private boolean awaitLiveness(DnsProfile profile, int port, long budgetMs) {
        String user = profile.socksUser == null ? "" : profile.socksUser;
        String pass = profile.socksPass == null ? "" : profile.socksPass;
        long deadline = SystemClock.elapsedRealtime() + budgetMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancelStart) {
                return false;
            }
            int probe = socksConnect(port, PROBE_HOST, PROBE_PORT, 4_000, user, pass);
            if (probe == PROBE_OK) {
                return true;
            }
            if (probe == PROBE_AUTH_REQUIRED) {
                livenessFailure = "Server requires SOCKS authentication — set the profile's "
                        + "SOCKS username and password (DNS Tunnel settings)";
                return false;
            }
            if (probe == PROBE_AUTH_FAILED) {
                livenessFailure = "Server rejected the profile's SOCKS username/password";
                return false;
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

    /**
     * Minimal SOCKS5 client: greeting (offering no-auth AND
     * username/password), the RFC1929 subnegotiation when the far
     * end demands it (05 02 — the kit's microsocks runs with
     * -u/-P), then CONNECT. Returns a PROBE_* code.
     */
    static int socksConnect(int localPort, String targetHost, int targetPort, int timeoutMs,
                            String user, String pass) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", localPort), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(new byte[]{5, 2, 0, 2});
            out.flush();
            byte[] greeting = readFully(in, 2);
            if (greeting == null || greeting[0] != 5) {
                return PROBE_RETRY;
            }
            if (greeting[1] == 2) {
                // Username/password auth demanded (RFC1929).
                if (user == null || user.isEmpty()) {
                    return PROBE_AUTH_REQUIRED;
                }
                byte[] userBytes = user.getBytes("UTF-8");
                byte[] passBytes = (pass == null ? "" : pass).getBytes("UTF-8");
                if (userBytes.length > 255 || passBytes.length > 255) {
                    return PROBE_AUTH_FAILED;
                }
                byte[] auth = new byte[3 + userBytes.length + passBytes.length];
                auth[0] = 1; // RFC1929 version
                auth[1] = (byte) userBytes.length;
                System.arraycopy(userBytes, 0, auth, 2, userBytes.length);
                auth[2 + userBytes.length] = (byte) passBytes.length;
                System.arraycopy(passBytes, 0, auth, 3 + userBytes.length, passBytes.length);
                out.write(auth);
                out.flush();
                byte[] authReply = readFully(in, 2);
                if (authReply == null || authReply[0] != 1 || authReply[1] != 0) {
                    return PROBE_AUTH_FAILED;
                }
            } else if (greeting[1] != 0) {
                return PROBE_RETRY;
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
                return PROBE_RETRY;
            }
            int atyp = head[3];
            int rest = atyp == 1 ? 4 : atyp == 4 ? 16 : atyp == 3 ? -1 : 0;
            if (rest == -1) {
                byte[] len = readFully(in, 1);
                if (len == null) {
                    return PROBE_RETRY;
                }
                rest = (len[0] & 0xff) + 2;
            } else {
                rest += 2;
            }
            return readFully(in, rest) != null ? PROBE_OK : PROBE_RETRY;
        } catch (Throwable t) {
            return PROBE_RETRY;
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

    private void applyProxy(int port, DnsProfile profile) {
        runOnUiSync(() -> {
            try {
                // The far end of a Slipstream tunnel is a SOCKS5
                // server that may demand RFC1929 auth (the kit's
                // microsocks) — Telegram's proxy entry must carry
                // the profile's credentials or its own connections
                // get 05 02 rejections. Empty for no-auth servers
                // and for Soar (its local shim is auth-free).
                String user = profile.socksUser == null ? "" : profile.socksUser;
                String pass = profile.socksPass == null ? "" : profile.socksPass;
                ProxySettings settings = ProxySettings.builder()
                        .setAddress("127.0.0.1")
                        .setPort(port)
                        .setUser(user)
                        .setPassword(pass)
                        .setSecret("")
                        .setType(ProxySettings.Type.SOCKS5)
                        .build();
                SharedConfig.loadProxyList();
                // Exactly ONE proxy-list entry belongs to this
                // module: the one whose identity was recorded when
                // it was created. Soar's local port is ephemeral,
                // so building a fresh settings object and calling
                // addProxy() on every start (the old behavior)
                // appended a brand-new entry on every connect and
                // the user's proxy list grew without bound. Update
                // the recorded entry's settings in place instead;
                // create the entry only when there is none.
                SharedConfig.ProxyInfo info = findModuleEntry();
                if (info != null) {
                    if (!Objects.equals(info.settings, settings)) {
                        info.settings = settings;
                        SharedConfig.saveProxyList();
                    }
                } else {
                    info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
                }
                recordModuleIdentity(settings);
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
     * Restores the snapshotted proxy exactly and REMOVES the
     * module's own entry from the proxy list, so after any number
     * of on/off cycles the list holds exactly the user's own
     * entries, unchanged. Safe to call when no snapshot exists and
     * never overrides a different proxy the user picked manually
     * in the meantime (that pick stays selected; our entry is
     * still removed, since it is not the selected one then).
     */
    void restoreProxy() {
        runOnUiSync(() -> {
            try {
                SharedConfig.loadProxyList();
                SharedPreferences snap = snapPrefs();
                boolean has = snap.getBoolean(KEY_SNAP_HAS, false);
                boolean enabled = snap.getBoolean(KEY_SNAP_ENABLED, false);
                ProxySettings prev = has ? ProxySettings.fromSharedPreferences(snap) : null;
                SharedConfig.ProxyInfo moduleEntry = findModuleEntry();
                SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
                boolean currentIsModule = moduleEntry != null && current == moduleEntry;
                boolean userPickedOther = current != null && !currentIsModule;
                boolean changed = false;
                if (!userPickedOther) {
                    // Decide and apply the post-restore selection
                    // FIRST, while the module entry is still in the
                    // list: re-select the snapshot entry by its
                    // recorded identity (address + port + username),
                    // re-adding it only if it vanished from the
                    // list while the tunnel was up. If there is no
                    // snapshot, select no proxy at all.
                    boolean restoredToSnapshot = false;
                    if (prev != null) {
                        SharedConfig.ProxyInfo target = findByIdentity(prev);
                        if (target != null && target != moduleEntry) {
                            selectProxy(target, prev, enabled);
                            restoredToSnapshot = true;
                        } else if (target == null) {
                            target = SharedConfig.addProxy(new SharedConfig.ProxyInfo(prev));
                            selectProxy(target, prev, enabled);
                            restoredToSnapshot = true;
                        }
                        // target == moduleEntry: the snapshot IS
                        // the tunnel entry (recovery was skipped
                        // while a stale entry was current) — never
                        // select the entry being removed; fall
                        // through to no-proxy.
                    }
                    if (!restoredToSnapshot) {
                        selectNoProxy(moduleEntry != null ? moduleEntry.settings
                                : current != null ? current.settings : null);
                    }
                    changed = true;
                }
                if (moduleEntry != null) {
                    // The selection has been moved off the module
                    // entry above (or it was never selected): safe
                    // to take ours out of the list.
                    SharedConfig.proxyList.remove(moduleEntry);
                    SharedConfig.saveProxyList();
                    clearModuleIdentity();
                    changed = true;
                }
                if (changed) {
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

    /** Selects an existing list entry and re-applies its flat prefs + connection state. */
    private void selectProxy(SharedConfig.ProxyInfo info, ProxySettings settings, boolean enabled) {
        SharedConfig.currentProxy = info;
        SharedPreferences.Editor e = MessagesController.getGlobalMainSettings().edit();
        settings.toSharedPreferences(e);
        e.putBoolean("proxy_enabled", enabled);
        e.commit();
        ConnectionsManager.setProxySettings(enabled && settings.isValid(), settings);
    }

    /**
     * Selects "no proxy": clears the flat proxy prefs the way
     * ProxyListActivity's toggle-off branch and
     * SharedConfig.deleteProxy do.
     */
    private void selectNoProxy(ProxySettings previousSettings) {
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
        ConnectionsManager.setProxySettings(false, previousSettings);
    }

    /** Finds a list entry by recorded identity: address + port + username. */
    private SharedConfig.ProxyInfo findByIdentity(ProxySettings settings) {
        if (settings == null) {
            return null;
        }
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            ProxySettings s = info.settings;
            if (s != null
                    && Objects.equals(s.getAddress(), settings.getAddress())
                    && s.getPort() == settings.getPort()
                    && Objects.equals(s.getUser(), settings.getUser())) {
                return info;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // The module's own proxy-list entry (identity-tracked)
    // ---------------------------------------------------------------

    /**
     * Finds THE proxy-list entry this module owns, by the identity
     * recorded at creation. Fallback for entries created before
     * identity recording existed (the append-per-connect build):
     * the last tunnel port it wrote, on 127.0.0.1 SOCKS5.
     */
    private SharedConfig.ProxyInfo findModuleEntry() {
        SharedPreferences prefs = DnsProfiles.prefs(appContext);
        String ip = prefs.getString(KEY_MOD_IP, null);
        int port = prefs.getInt(KEY_MOD_PORT, -1);
        String user = prefs.getString(KEY_MOD_USER, "");
        if (ip != null && port > 0) {
            for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                ProxySettings s = info.settings;
                if (s != null && ip.equals(s.getAddress()) && s.getPort() == port
                        && Objects.equals(user, s.getUser())) {
                    return info;
                }
            }
        }
        int legacyPort = prefs.getInt(KEY_TUNNEL_PORT, -1);
        if (legacyPort > 0) {
            for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                ProxySettings s = info.settings;
                if (s != null && s.getType() == ProxySettings.Type.SOCKS5
                        && "127.0.0.1".equals(s.getAddress()) && s.getPort() == legacyPort) {
                    return info;
                }
            }
        }
        return null;
    }

    private void recordModuleIdentity(ProxySettings settings) {
        DnsProfiles.prefs(appContext).edit()
                .putString(KEY_MOD_IP, settings.getAddress())
                .putInt(KEY_MOD_PORT, settings.getPort())
                .putString(KEY_MOD_USER, settings.getUser() == null ? "" : settings.getUser())
                .commit();
    }

    private void clearModuleIdentity() {
        DnsProfiles.prefs(appContext).edit()
                .remove(KEY_MOD_IP)
                .remove(KEY_MOD_PORT)
                .remove(KEY_MOD_USER)
                .commit();
    }

    /**
     * Housekeeping sweep, run from the same once-per-process
     * recovery point as the stale-tunnel restore, and only while
     * the tunnel is off in this process. Collects entries the
     * module owns but no live tunnel is using:
     *   - the entry matching the recorded module identity (covers
     *     anything the fixed build ever created, e.g. after a
     *     crash that skipped the restore), and the legacy
     *     last-tunnel-port fallback inside findModuleEntry();
     *   - any 127.0.0.1 SOCKS5 entry on Slipstream's fixed local
     *     port range (18080–18089) — that range exists only for
     *     this module's tunnels.
     * NOTHING else is touched: a user's own local proxies on any
     * other port are theirs. If a collected entry is the current
     * selection, the selection is first moved to "no proxy" —
     * the selected entry is never deleted out from under
     * Telegram.
     */
    private void sweepStaleModuleProxies() {
        if (state != State.OFF) {
            return;
        }
        runOnUiSync(() -> {
            try {
                if (state != State.OFF
                        || DnsProfiles.prefs(appContext).getBoolean(KEY_TUNNEL_ON, false)) {
                    return;
                }
                SharedConfig.loadProxyList();
                List<SharedConfig.ProxyInfo> victims = new ArrayList<>();
                SharedConfig.ProxyInfo recorded = findModuleEntry();
                if (recorded != null) {
                    victims.add(recorded);
                }
                for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                    if (info.settings == null || victims.contains(info)) {
                        continue;
                    }
                    ProxySettings s = info.settings;
                    if (s.getType() == ProxySettings.Type.SOCKS5
                            && "127.0.0.1".equals(s.getAddress())
                            && s.getPort() >= SLIPSTREAM_PORT_MIN
                            && s.getPort() <= SLIPSTREAM_PORT_MAX) {
                        victims.add(info);
                    }
                }
                if (victims.isEmpty()) {
                    return;
                }
                for (SharedConfig.ProxyInfo victim : victims) {
                    if (SharedConfig.currentProxy == victim) {
                        selectNoProxy(victim.settings);
                    }
                    SharedConfig.proxyList.remove(victim);
                }
                SharedConfig.saveProxyList();
                if (recorded != null && victims.contains(recorded)) {
                    clearModuleIdentity();
                }
                NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.proxySettingsChanged);
            } catch (Throwable ignored) {
            }
        });
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
            AminDnsController controller = get(context);
            if (prefs.getBoolean(KEY_TUNNEL_ON, false)) {
                // This process just started: nothing is applied yet
                // from our side, but Telegram reloaded the persisted
                // tunnel entry as its current proxy. Restore from
                // the snapshot — which also removes the module's
                // entry from the proxy list.
                controller.restoreProxy();
                controller.state = State.OFF;
            }
            // Housekeeping: collect any module-owned entries left
            // behind (older builds appended one per connect) while
            // the tunnel is off in this process.
            controller.sweepStaleModuleProxies();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------

    /** Runs r on the UI thread and waits (bounded) — proxy ops mirror upstream's UI-thread calls.
     *  A timeout is a FAILURE of the operation (audit P1-16): the
     *  old code returned anyway while the runnable still executed
     *  later, so callers set proxyApplied/CONNECTED (or made restore
     *  decisions) against work that had not happened — and could
     *  then race the late execution. Callers that must not proceed
     *  on a maybe-applied proxy let this propagate; stop paths
     *  catch it and continue their teardown regardless. */
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
        boolean done;
        try {
            done = latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            done = false;
        }
        if (!done) {
            throw new IllegalStateException("UI thread busy — proxy operation timed out");
        }
    }
}
