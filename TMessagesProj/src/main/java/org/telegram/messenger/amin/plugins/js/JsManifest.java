package org.telegram.messenger.amin.plugins.js;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parser/validator for the ==AminGramPlugin== manifest header, exactly
 * as documented in docs/PLUGIN-MASTER-PROMPT.md:
 *
 *   // ==AminGramPlugin==
 *   // @id          shout
 *   // @name        Shout
 *   // @version     1.0
 *   // @author      Amin
 *   // @description ...
 *   // @capabilities commands, send
 *   // @icon         music            (optional, v2.0)
 *   // @menuLabel    Play a song      (optional, v2.0)
 *   // ==/AminGramPlugin==
 *
 * All six fields are required. @id must be lowercase letters, digits
 * and '-'. @capabilities entries must come from the known set
 * (observe, commands, send, settings, http, menu, google, file) — an
 * unknown word makes the plugin invalid, per the master prompt.
 * `google` (v1.3.5) is the amin.google surface: the plugin's
 * bridge carries it only when this capability is declared.
 * `file` (v2.0) gates the bridge's file surface, like `google`.
 *
 * Optional fields (v2.0): @icon is an attach-menu icon SPEC —
 * either a set name (download, mail, sparkles, dice, text, link,
 * music, star, bolt, camera, globe, gear) or "emoji:<glyph>" —
 * and @menuLabel overrides the plugin name as the attach-menu
 * button label. Both default to "" (unset) and are per-user
 * overridable via the menu configuration (AminMenuConfig).
 */
public final class JsManifest {

    public String id;
    public String name;
    public String version;
    public String author;
    public String description;
    /** Optional manifest @icon ("" when unset). */
    public String icon = "";
    /** Optional manifest @menuLabel ("" when unset). */
    public String menuLabel = "";
    /** Optional manifest @dataVersion (Platform v2, A6); 1 when unset. */
    public int dataVersion = 1;
    /** Optional manifest @minPlatform (Platform v2, A9); 1 when unset. */
    public int minPlatform = 1;
    public final List<String> capabilities = new ArrayList<>();

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final String[] KNOWN_CAPABILITIES = {
            "observe", "commands", "send", "settings", "http", "menu", "google", "file",
            "act"
    };

    private JsManifest() {
    }

    public boolean hasCapability(String capability) {
        return capabilities.contains(capability);
    }

    /** Returns the parsed manifest, or throws IllegalArgumentException
     *  with a user-readable reason. */
    public static JsManifest parse(String source) {
        if (source == null) {
            throw new IllegalArgumentException("Empty plugin file.");
        }
        int start = source.indexOf("// ==AminGramPlugin==");
        int end = source.indexOf("// ==/AminGramPlugin==");
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalArgumentException(
                    "Missing manifest block (// ==AminGramPlugin== … // ==/AminGramPlugin==).");
        }
        String block = source.substring(start, end);
        JsManifest manifest = new JsManifest();
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
                case "capabilities":
                    for (String cap : value.split(",")) {
                        String c = cap.trim().toLowerCase();
                        if (!c.isEmpty()) {
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
        return manifest;
    }

    /** Lenient int for the optional v2 fields: an unparsable or
     *  non-positive value keeps the default instead of failing the
     *  install (manifest evolution rule — new fields warn, never
     *  brick). */
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
