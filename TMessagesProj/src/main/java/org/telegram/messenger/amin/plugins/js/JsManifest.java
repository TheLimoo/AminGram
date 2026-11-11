package org.telegram.messenger.amin.plugins.js;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser/validator for the ==AminGramPlugin== manifest header.
 *
 * Phase A additions (A-17, A-35):
 * - Enforces MAX_SOURCE_CHARS (512 KB) before parsing/compiling.
 * - Deduplicates @capabilities and @depends entries.
 * - Supports optional @commands header and extracts handled slash-command
 *   names from the plugin source/description so outgoing commands can be
 *   suppressed synchronously when matched.
 */
public final class JsManifest {

    public static final int MAX_SOURCE_CHARS = 512 * 1024;

    public String id;
    public String name;
    public String version;
    public String author;
    public String description;
    public String icon = "";
    public String menuLabel = "";
    public int dataVersion = 1;
    public int minPlatform = 1;
    public String contextMenu = "";
    public final List<String> exports = new ArrayList<>();
    public final List<String> topics = new ArrayList<>();
    public final List<Depends> depends = new ArrayList<>();
    public final List<String> capabilities = new ArrayList<>();
    public final List<String> commands = new ArrayList<>();

    public static final class Depends {
        public final String id;
        public final String minVersion;

        Depends(String id, String minVersion) {
            this.id = id;
            this.minVersion = minVersion;
        }
    }

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final Pattern CMD_NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");
    private static final Pattern CODE_CMD_EQ_PATTERN = Pattern.compile(
            "(?:ctx\\.command|\\bcommand|\\bcmd)\\s*(?:===|!==|==|!=)\\s*[\"']([a-zA-Z0-9_]{1,32})[\"']");
    private static final Pattern DESC_SLASH_CMD_PATTERN = Pattern.compile(
            "(?:^|[\\s(])/([a-z][a-z0-9_]{1,23})(?=[\\s,.)|]|$)");
    private static final String[] KNOWN_CAPABILITIES = {
            "observe", "commands", "send", "settings", "http", "menu", "google", "file",
            "act", "ui", "relay"
    };

    private JsManifest() {
    }

    public boolean hasCapability(String capability) {
        return capabilities.contains(capability);
    }

    public static JsManifest parse(String source) {
        if (source == null) {
            throw new IllegalArgumentException("Empty plugin file.");
        }
        if (source.length() > MAX_SOURCE_CHARS) {
            throw new IllegalArgumentException(
                    "Plugin file is too large (" + (source.length() / 1024) + " KB; max 512 KB).");
        }
        int start = source.indexOf("// ==AminGramPlugin==");
        int end = source.indexOf("// ==/AminGramPlugin==");
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalArgumentException(
                    "Missing manifest block (// ==AminGramPlugin== … // ==/AminGramPlugin==).");
        }
        String block = source.substring(start, end);
        JsManifest manifest = new JsManifest();
        Set<String> seenDepends = new LinkedHashSet<>();
        for (String rawLine : block.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("//")) {
                continue;
            }
            line = line.substring(2).trim();
            if (!line.startsWith("@")) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String key = line.substring(1, space).trim();
            String value = line.substring(space + 1).trim();
            switch (key) {
                case "id":
                    manifest.id = value;
                    break;
                case "name":
                    manifest.name = value;
                    break;
                case "version":
                    manifest.version = value;
                    break;
                case "author":
                    manifest.author = value;
                    break;
                case "description":
                    manifest.description = value;
                    break;
                case "icon":
                    manifest.icon = value;
                    break;
                case "menuLabel":
                    manifest.menuLabel = value;
                    break;
                case "dataVersion":
                    manifest.dataVersion = parsePositiveInt(value, 1);
                    break;
                case "minPlatform":
                    manifest.minPlatform = parsePositiveInt(value, 1);
                    break;
                case "contextMenu":
                    manifest.contextMenu = value;
                    break;
                case "exports":
                    for (String name : value.split(",")) {
                        String n = name.trim();
                        if (!n.isEmpty() && !manifest.exports.contains(n)) {
                            manifest.exports.add(n);
                        }
                    }
                    break;
                case "topics":
                    for (String name : value.split(",")) {
                        String n = name.trim();
                        if (!n.isEmpty() && !manifest.topics.contains(n)) {
                            manifest.topics.add(n);
                        }
                    }
                    break;
                case "commands":
                    for (String cmd : value.split(",")) {
                        String c = cmd.trim().toLowerCase(Locale.US);
                        if (c.startsWith("/")) {
                            c = c.substring(1).trim();
                        }
                        if (!c.isEmpty() && CMD_NAME_PATTERN.matcher(c).matches()
                                && !manifest.commands.contains(c)) {
                            manifest.commands.add(c);
                        }
                    }
                    break;
                case "depends":
                    for (String entry : value.split(",")) {
                        String e = entry.trim();
                        if (e.isEmpty()) {
                            continue;
                        }
                        String depId = e;
                        String depMin = null;
                        int at = e.indexOf("@>=");
                        if (at > 0) {
                            depId = e.substring(0, at).trim();
                            depMin = e.substring(at + 3).trim();
                            if (depMin.isEmpty()) {
                                depMin = null;
                            }
                        }
                        if (ID_PATTERN.matcher(depId).matches() && seenDepends.add(depId)) {
                            manifest.depends.add(new Depends(depId, depMin));
                        }
                    }
                    break;
                case "capabilities":
                    for (String cap : value.split(",")) {
                        String c = cap.trim().toLowerCase(Locale.US);
                        if (!c.isEmpty() && !manifest.capabilities.contains(c)) {
                            manifest.capabilities.add(c);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        if (manifest.id == null || !ID_PATTERN.matcher(manifest.id).matches()) {
            throw new IllegalArgumentException(
                    "@id is required and must be lowercase letters, digits and '-' only.");
        }
        if (isEmpty(manifest.name)) {
            throw new IllegalArgumentException("@name is required.");
        }
        if (isEmpty(manifest.version)) {
            throw new IllegalArgumentException("@version is required.");
        }
        if (isEmpty(manifest.author)) {
            throw new IllegalArgumentException("@author is required.");
        }
        if (isEmpty(manifest.description)) {
            throw new IllegalArgumentException("@description is required.");
        }
        if (manifest.capabilities.isEmpty()) {
            throw new IllegalArgumentException("@capabilities is required.");
        }
        for (String cap : manifest.capabilities) {
            boolean known = false;
            for (String knownCap : KNOWN_CAPABILITIES) {
                if (knownCap.equals(cap)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new IllegalArgumentException("Unknown capability: " + cap);
            }
        }
        if (manifest.capabilities.contains("commands") && manifest.commands.isEmpty()) {
            manifest.commands.addAll(extractCommandsFromSource(source, manifest.description));
        }
        return manifest;
    }

    /** Extracts handled slash-command names from source & description when
     *  @commands is not explicitly declared in the manifest header. */
    public static List<String> extractCommandsFromSource(String source, String description) {
        Set<String> out = new LinkedHashSet<>();
        if (source != null) {
            Matcher m = CODE_CMD_EQ_PATTERN.matcher(source);
            while (m.find()) {
                String c = m.group(1).toLowerCase(Locale.US);
                if (CMD_NAME_PATTERN.matcher(c).matches()) {
                    out.add(c);
                }
            }
        }
        if (description != null) {
            Matcher dm = DESC_SLASH_CMD_PATTERN.matcher(description);
            while (dm.find()) {
                String c = dm.group(1).toLowerCase(Locale.US);
                if (CMD_NAME_PATTERN.matcher(c).matches()) {
                    out.add(c);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
