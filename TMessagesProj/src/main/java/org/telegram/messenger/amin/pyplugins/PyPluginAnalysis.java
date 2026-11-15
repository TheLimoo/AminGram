package org.telegram.messenger.amin.pyplugins;

import java.util.ArrayList;
import java.util.List;

/**
 * Install-time static analysis of a {@code .plugin} file (M0).
 *
 * Advisory only — it reports what the SOURCE text shows, in plain
 * language, on the install review screen. It is NOT a sandbox:
 * behaviour hidden in a DEX, an obfuscated string or a downloaded
 * payload cannot be seen here, and the review screen says so.
 * A plugin is never refused for an analysis finding alone (except
 * {@code __requirements__}, which M0 cannot install); the user
 * approves the specific report to install.
 *
 * Detection is substring-based on purpose: M0 must not depend on
 * Python being started at install time.
 */
public final class PyPluginAnalysis {

    private PyPluginAnalysis() {
    }

    public static final class Finding {
        /** Short code, e.g. {@code "dex"}. */
        public String code;
        /** Plain-language line shown on the review screen. */
        public String text;
        /** True when the finding needs the separate DEX consent (M3). */
        public boolean needsDexConsent;

        public Finding(String code, String text, boolean needsDexConsent) {
            this.code = code;
            this.text = text;
            this.needsDexConsent = needsDexConsent;
        }
    }

    public static List<Finding> analyze(String source, PyPlugin plugin) {
        List<Finding> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        String s = source;
        if (containsAny(s, "jclass", "java.lang.reflect", "Class.forName",
                "getDeclaredMethod", "getDeclaredField", "setAccessible")) {
            out.add(new Finding("reflection",
                    "Uses Java reflection (jclass / reflect) — it can reach "
                    + "Telegram internals directly, past anything AminGram mediates.",
                    false));
        }
        if (containsAny(s, "InMemoryDexClassLoader", "DexClassLoader",
                "PathClassLoader", "defineClass")) {
            out.add(new Finding("dex",
                    "Loads DEX / native code at runtime — the loaded code runs "
                    + "with full app privileges and is not sandboxed.",
                    true));
        }
        if (containsAny(s, "loadLibrary", "System.load", "ctypes", "dlopen")) {
            out.add(new Finding("native",
                    "Loads native libraries — native code runs with full app "
                    + "privileges and is not sandboxed.",
                    true));
        }
        if (containsAny(s, "import requests", "urllib", "http.client",
                "socket", "aiohttp", "httpx")) {
            out.add(new Finding("network",
                    "Makes its own network requests — it can send data "
                    + "anywhere once installed.",
                    false));
        }
        if (containsAny(s, "sendMessage", "editMessage", "send_message",
                "deleteMessage", "forwardMessage")) {
            out.add(new Finding("messages",
                    "Can send, edit, delete or forward messages as your account.",
                    false));
        }
        if (containsAny(s, "TelegramStars", "sendStars", "transfer_gift",
                "gift", "Stars")) {
            out.add(new Finding("stars",
                    "Touches Stars / gifts — spending is confirmed by AminGram "
                    + "only where AminGram mediates the call; direct reflection "
                    + "calls cannot be intercepted.",
                    false));
        }
        if (containsAny(s, "open(", "os.remove", "os.rename", "shutil",
                "pathlib", "/sdcard", "/storage/emulated")) {
            out.add(new Finding("files",
                    "Reads/writes files — including paths outside the plugin's "
                    + "own directory.",
                    false));
        }
        if (s.length() > 100 * 1024 && containsAny(s, "base64", "lzma", "b64decode")) {
            out.add(new Finding("blob",
                    "Contains a large base64 / compressed blob — hidden code "
                    + "or data the analysis cannot see.",
                    false));
        } else if (containsAny(s, "base64.b64decode", "lzma.decompress")) {
            out.add(new Finding("blob",
                    "Decodes base64 / compressed data at runtime — the decoded "
                    + "content is not visible to this analysis.",
                    false));
        }
        if (plugin != null && !plugin.requirements.isEmpty()) {
            out.add(new Finding("requirements",
                    "Needs PyPI packages (" + join(plugin.requirements)
                    + ") — M0 cannot install them; install is refused.",
                    false));
        }
        if (out.isEmpty()) {
            out.add(new Finding("clean",
                    "No reflection, DEX, network or message-sending code found "
                    + "in the source text. (Hidden or downloaded code would "
                    + "still not show up here.)",
                    false));
        }
        return out;
    }

    public static List<String> toLines(List<Finding> findings) {
        List<String> lines = new ArrayList<>();
        for (Finding f : findings) {
            lines.add(f.text);
        }
        return lines;
    }

    public static boolean needsDexConsent(List<Finding> findings) {
        for (Finding f : findings) {
            if (f.needsDexConsent) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) {
            if (s.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item);
        }
        return sb.toString();
    }
}
