package org.telegram.messenger.amin.dns;

import org.telegram.amingram.soarbridge.Soarbridge;

/**
 * Thin wrapper over the gomobile-bound Soar client
 * (org.telegram.amingram.soarbridge.Soarbridge, built in CI from
 * dns-native/soarbridge — see DNS-BUILD-PLAN.md §2). The shim owns
 * a Soar client (getlantern/soar v0.2.2, wire-pinned to our
 * Soar-Server v0.1.3) plus a local SOCKS5 listener on an ephemeral
 * 127.0.0.1 port; this class is the only Java site that names the
 * generated binding, so the rest of the module stays binding-free.
 *
 * All methods are called from the controller's single worker
 * thread; the shim itself is goroutine-safe.
 */
final class SoarEngine {

    private SoarEngine() {
    }

    /**
     * Starts the client + SOCKS listener. Blocks only for listener
     * setup — the Soar handshake itself is lazy (first dial), so
     * liveness is proven separately by the controller's SOCKS test.
     *
     * @param resolversCsv "host:port" entries, comma-joined, in
     *                     preference order (system DNS first).
     */
    static void start(DnsProfile profile, String resolversCsv) throws Exception {
        Soarbridge.start(profile.zone.trim(), profile.serverKey.trim(),
                resolversCsv, profile.discovery);
    }

    static void stop() {
        try {
            Soarbridge.stop();
        } catch (Throwable ignored) {
        }
    }

    /** "idle" | "starting" | "connected" | "error: <msg>" from the shim. */
    static String status() {
        try {
            String s = Soarbridge.status();
            return s == null ? "idle" : s;
        } catch (Throwable t) {
            return "error: " + t.getMessage();
        }
    }

    static int localPort() {
        try {
            return (int) Soarbridge.localPort();
        } catch (Throwable t) {
            return 0;
        }
    }
}
