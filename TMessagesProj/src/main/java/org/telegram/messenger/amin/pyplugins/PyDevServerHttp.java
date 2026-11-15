package org.telegram.messenger.amin.pyplugins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Local development server (M4) — pure-Java HTTP core.
 *
 * A developer runs a plugin's source on their own computer and
 * tests it on their phone without rebuilding the app: the hub
 * starts this server, the developer POSTs a plugin file to it,
 * and the phone installs it through the normal gates.
 *
 * Security — this is the most dangerous surface in M4, so the
 * rules are deliberately boring:
 * <ul>
 *   <li>Binds {@code 127.0.0.1} ONLY. The socket is created on
 *       the loopback address; it is unreachable from the LAN,
 *       from the internet, and from any other device. A bind
 *       failure (or a non-loopback bind attempt) refuses to
 *       start — there is no fallback to {@code 0.0.0.0}.</li>
 *   <li>Off by default. Starting it needs an explicit tap in
 *       the hub, and it stops when the hub is left or the app
 *       is backgrounded for more than a minute (the Android
 *       wrapper enforces that; see {@link PyDevServer}).</li>
 *   <li>Token auth: a 32-char random token is generated at
 *       start and shown in the hub (and only there). Every
 *       request must carry {@code X-Dev-Token: &lt;token&gt;};
 *       a missing or wrong token gets 403 and nothing else.
 *       The token is never written to disk or the log.</li>
 *   <li>One method, two paths: {@code POST /plugin} (body =
 *       a {@code .plugin} / {@code .elyx} file, max 10 MB) and
 *       {@code GET /status}. Everything else is 404. No file
 *       serving, no directory listing, no eval endpoint.</li>
 *   <li>Uploaded bytes are NOT executed by the server. They are
 *       handed to the caller, which runs the normal install
 *       gates ({@link PyPluginMetadata} +
 *       {@link PyPluginAnalysis}) before anything is installed
 *       — a dev server upload is exactly as untrusted as a
 *       file from the internet.</li>
 *   <li>Single-threaded, one connection at a time, 30 s socket
 *       timeout. This is a dev tool, not a service.</li>
 * </ul>
 */
public final class PyDevServerHttp {

    /** Default port. Fixed, so the developer's script can rely on it. */
    public static final int PORT = 42690;
    /** Max upload body: 10 MB. */
    public static final int MAX_BODY = 10 * 1024 * 1024;
    /** Token length: 32 chars from a 62-char alphabet. */
    public static final int TOKEN_LENGTH = 32;

    /** Called with uploaded plugin bytes (already token-checked). */
    public interface UploadHandler {
        /** Returns a short human-readable result for the HTTP body. */
        String onUpload(byte[] bytes, String fileName);
    }

    private PyDevServerHttp() {
    }

    /** Generates a fresh random token (never logged, never stored). */
    public static String newToken() {
        SecureRandom random = new SecureRandom();
        String alphabet = "abcdefghijklmnopqrstuvwxyz"
                + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** Parses the request line + headers of one HTTP request. */
    public static final class Request {
        public String method;
        public String path;
        public String token;
        public int contentLength;
        public String fileName;
    }

    /** Parses headers from a raw request head (CRLF or LF). Null on garbage. */
    public static Request parseHead(String head) {
        if (head == null || head.isEmpty()) {
            return null;
        }
        String[] lines = head.split("\r\n|\n");
        if (lines.length == 0) {
            return null;
        }
        String[] first = lines[0].split(" ");
        if (first.length < 2) {
            return null;
        }
        Request r = new Request();
        r.method = first[0].toUpperCase(Locale.US);
        r.path = first[1];
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if ("x-dev-token".equals(key)) {
                r.token = value;
            } else if ("content-length".equals(key)) {
                try {
                    r.contentLength = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    r.contentLength = -1;
                }
            } else if ("x-file-name".equals(key)) {
                r.fileName = value;
            }
        }
        return r;
    }

    /** Decides the status code for a request BEFORE the body is read. */
    public static int decideStatus(Request r, String token) {
        if (r == null) {
            return 400;
        }
        if (token == null || r.token == null || !constantTimeEquals(r.token, token)) {
            return 403;
        }
        if ("GET".equals(r.method) && "/status".equals(r.path)) {
            return 200;
        }
        if ("POST".equals(r.method) && "/plugin".equals(r.path)) {
            if (r.contentLength < 0 || r.contentLength > MAX_BODY) {
                return 413;
            }
            return 200;
        }
        return 404;
    }

    /** Constant-time string comparison (no early exit on mismatch). */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** Minimal HTTP response writer (status + plain-text body). */
    public static void respond(OutputStream out, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0]
                : body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 500: default: return "Internal Server Error";
        }
    }

    /**
     * Serves ONE connection: reads the head, checks the token,
     * reads the body for POST /plugin, hands it to the handler.
     * Returns false when the server should stop after this
     * request (never in practice — the loop owns the lifecycle).
     */
    public static boolean serveOne(Socket socket, String token, UploadHandler handler) {
        try {
            socket.setSoTimeout(30000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String head = readHead(in);
            Request r = parseHead(head);
            int status = decideStatus(r, token);
            if (status != 200) {
                // Drain a small POST body before answering: closing a
                // socket with unread bytes in its receive buffer makes
                // the kernel send RST instead of the response, so a
                // client (curl) would see "Connection reset" rather
                // than the 403/404. Never drain an oversized body
                // (413): the whole point of 413 is not to read it.
                if (r != null && "POST".equals(r.method)
                        && r.contentLength > 0 && r.contentLength <= MAX_BODY) {
                    readBody(in, r.contentLength);
                }
                respond(out, status, status == 403
                        ? "Wrong or missing X-Dev-Token."
                        : status == 413
                        ? "Upload is too large (max 10 MB)."
                        : status == 404 ? "Not found." : "Bad request.");
                return true;
            }
            if ("GET".equals(r.method)) {
                respond(out, 200, "AminGram dev server is running.");
                return true;
            }
            byte[] body = readBody(in, r.contentLength);
            if (body == null) {
                respond(out, 400, "Could not read the upload.");
                return true;
            }
            String fileName = r.fileName == null || r.fileName.isEmpty()
                    ? "upload.plugin" : r.fileName;
            String result;
            try {
                result = handler.onUpload(body, fileName);
            } catch (Exception e) {
                respond(out, 500, "Install failed: " + e.getMessage());
                return true;
            }
            respond(out, 200, result == null ? "OK" : result);
            return true;
        } catch (Exception e) {
            return true;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Reads bytes up to the blank line that ends the HTTP head. */
    static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        int total = 0;
        // Break on the blank line: "\r\n\r\n" or "\n\n".
        // (A single "\r\n" only ends one header line.)
        byte[] tail = new byte[4];
        int tailLen = 0;
        while ((b = in.read()) != -1) {
            buf.write(b);
            total++;
            if (total > 8192) {
                break;
            }
            if (tailLen < 4) {
                tail[tailLen++] = (byte) b;
            } else {
                tail[0] = tail[1];
                tail[1] = tail[2];
                tail[2] = tail[3];
                tail[3] = (byte) b;
            }
            if (tailLen >= 2 && tail[tailLen - 2] == '\n' && tail[tailLen - 1] == '\n') {
                break;
            }
            if (tailLen >= 4 && tail[0] == '\r' && tail[1] == '\n'
                    && tail[2] == '\r' && tail[3] == '\n') {
                break;
            }
        }
        return buf.toString("UTF-8");
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int remaining = length;
        while (remaining > 0) {
            int n = in.read(chunk, 0, Math.min(chunk.length, remaining));
            if (n == -1) {
                return null;
            }
            buf.write(chunk, 0, n);
            remaining -= n;
        }
        return buf.toByteArray();
    }

    /**
     * Creates the loopback-only server socket. Throws when the
     * loopback bind fails — the caller must NOT retry on another
     * address.
     */
    public static ServerSocket bindLoopback(int port) throws IOException {
        return new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
    }
}
