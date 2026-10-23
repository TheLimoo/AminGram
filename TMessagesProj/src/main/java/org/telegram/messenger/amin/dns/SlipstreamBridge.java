package org.telegram.messenger.amin.dns;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the stock slipstream-client binary as a subprocess — the
 * embedding model proven by the open-source dnstt-xyz app (CC0),
 * adapted to this package. There is deliberately no JNI: the Rust
 * client ships as {@code libslipstream_client.so} in jniLibs (built
 * once by the manual dns-artifacts workflow from the pinned
 * slipstream-rust commit bc772dd…), Android extracts it into
 * nativeLibraryDir — the one location app code may execute from —
 * and this bridge launches it with ProcessBuilder.
 *
 * The client is a TCP forwarder: every accepted local connection is
 * carried over QUIC-over-DNS to the server, whose fixed target is
 * a SOCKS5 (microsocks). So a client of the local port is,
 * transitively, talking to a real SOCKS5 — Telegram needs nothing
 * Slipstream-specific.
 *
 * Differences from dnstt-xyz (plan §3.2): we pass --cert for strict
 * leaf pinning when the profile carries a certificate (theirs omits
 * it), and we hold NO wakelock (AminGram's power rule — official-app
 * level only; the foreground service keeps the process alive).
 */
final class SlipstreamBridge {

    static final String BINARY_NAME = "libslipstream_client.so";
    private static final int FIRST_PORT = 18080;
    private static final int LAST_PORT = 18089;
    private static final int KEEP_ALIVE_MS = 400;

    private Process process;
    private int localPort;
    private volatile String lastError = "";
    private Thread stderrThread;

    private SlipstreamBridge() {
    }

    static File binaryFile(Context context) {
        String dir = context.getApplicationContext().getApplicationInfo().nativeLibraryDir;
        return dir == null ? null : new File(dir, BINARY_NAME);
    }

    /** The engine is usable only once the prebuilt binaries are committed into jniLibs. */
    static boolean isAvailable(Context context) {
        File f = binaryFile(context);
        return f != null && f.exists();
    }

    /**
     * Launches the client on the first workable port in
     * 18080..18089 and returns the bound port. "Workable" here means
     * the process survived its startup window; the real end-to-end
     * proof is the controller's SOCKS round-trip through the port.
     */
    static SlipstreamBridge start(Context context, DnsProfile profile, List<String> resolvers) throws Exception {
        File binary = binaryFile(context);
        if (binary == null || !binary.exists()) {
            throw new IllegalStateException("Slipstream engine is not installed in this build");
        }
        File certFile = null;
        if (profile.cert != null && !profile.cert.trim().isEmpty()) {
            certFile = writeCertFile(context, profile);
        }
        SlipstreamBridge bridge = new SlipstreamBridge();
        Exception lastFailure = null;
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            try {
                bridge.launch(context, binary, certFile, profile, resolvers, port);
                bridge.localPort = port;
                return bridge;
            } catch (Exception e) {
                lastFailure = e;
                bridge.stopInternal();
            }
        }
        throw lastFailure != null ? lastFailure
                : new IllegalStateException("no free local port for Slipstream");
    }

    private void launch(Context context, File binary, File certFile, DnsProfile profile,
                        List<String> resolvers, int port) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.getAbsolutePath());
        cmd.add("--tcp-listen-host");
        cmd.add("127.0.0.1");
        cmd.add("--tcp-listen-port");
        cmd.add(String.valueOf(port));
        cmd.add("--domain");
        cmd.add(profile.domain.trim());
        for (String resolver : resolvers) {
            cmd.add("--resolver");
            cmd.add(resolver);
        }
        if (certFile != null) {
            cmd.add("--cert");
            cmd.add(certFile.getAbsolutePath());
        }
        cmd.add("--congestion-control");
        cmd.add(profile.congestion == null || profile.congestion.isEmpty()
                ? DnsProfile.CONGESTION_DCUBIC : profile.congestion);
        cmd.add("--keep-alive-interval");
        cmd.add(String.valueOf(KEEP_ALIVE_MS));
        // No --gso (unimplemented upstream) and no --authoritative
        // (our path goes through public/ISP recursors) — plan §3.2.

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(context.getApplicationContext().getFilesDir());
        pb.redirectErrorStream(false);
        process = pb.start();
        drainStderr();
        // Startup window: a client that cannot bind its port or
        // parse its flags exits immediately; surviving 600 ms with
        // the process alive is our launch signal.
        Thread.sleep(600);
        if (!isAlive()) {
            throw new IllegalStateException("slipstream-client exited at startup"
                    + (lastError.isEmpty() ? "" : ": " + lastError));
        }
    }

    private void drainStderr() {
        final Process p = process;
        stderrThread = new Thread(() -> {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        lastError = line.trim();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "AminSlipstreamStderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private static File writeCertFile(Context context, DnsProfile profile) throws Exception {
        File dir = new File(context.getApplicationContext().getFilesDir(), "amin_dns");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File out = new File(dir, "slipstream-cert-" + profile.id + ".pem");
        FileOutputStream fos = new FileOutputStream(out, false);
        try {
            fos.write(profile.cert.trim().getBytes("UTF-8"));
            fos.write('\n');
        } finally {
            fos.close();
        }
        return out;
    }

    boolean isAlive() {
        if (process == null) {
            return false;
        }
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    int localPort() {
        return localPort;
    }

    String lastError() {
        return lastError;
    }

    void stop() {
        stopInternal();
    }

    private void stopInternal() {
        if (process != null) {
            try {
                process.destroy();
            } catch (Throwable ignored) {
            }
            // Bounded wait BEFORE the forcible escalation (audit
            // P1-13): the old code waited unboundedly here, which
            // made the destroyForcibly() below unreachable for a
            // SIGTERM-ignoring client — and every stop path runs on
            // the DNS controller's single worker thread, so one
            // stubborn subprocess wedged the whole module forever.
            if (Build.VERSION.SDK_INT >= 26) {
                boolean exited = false;
                try {
                    exited = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Throwable ignored) {
                }
                if (!exited) {
                    try {
                        process.destroyForcibly();
                    } catch (Throwable ignored) {
                    }
                    try {
                        process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                // Pre-26 has no timed waitFor: poll isAlive()
                // against a deadline, then give up waiting (the
                // process reference is dropped either way).
                long deadline = System.currentTimeMillis() + 2000;
                while (isAlive() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(50);
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
            process = null;
        }
        if (stderrThread != null) {
            stderrThread.interrupt();
            stderrThread = null;
        }
    }
}
