package org.telegram.messenger.amin.pyplugins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata for a {@code .plugin} file, parsed in Java.
 *
 * Deviation from the Phase 1 design (parse in Python via
 * {@code ast}): install must work BEFORE Python has started, and
 * exteraGram's own loader rule is that metadata must be LITERAL
 * (it parses them with AST and rejects anything built
 * dynamically). For literal-only assignments, a line parser over
 * top-level {@code __name__ = "..."} / {@code __name__ = [...]}
 * forms is equivalent to the AST for exactly the fields below,
 * and it is testable without Chaquopy. Anything non-literal
 * (variables, function calls, triple-quoted values) is rejected
 * with a readable error, exactly as the AST loader would reject
 * it. One multi-line form IS accepted: parenthesised implicit
 * string concatenation ({@code __description__ = ("a" "b")}) —
 * CPython folds that into one literal Constant, so the AST
 * loader accepts it, and MaterialSettingsList itself uses it.
 *
 * Required: {@code __id__}, {@code __name__}. Everything else has
 * a default. {@code __id__}: 2–32 chars, starts with a letter,
 * only {@code [A-Za-z0-9_-]}. Changing an id makes it a different
 * plugin (settings / enabled state / hooks reset).
 */
public final class PyPluginMetadata {

    private PyPluginMetadata() {
    }

    /** Categories from the public SDK docs (max 3 per plugin). */
    public static final Set<String> KNOWN_CATEGORIES = new HashSet<>(Arrays.asList(
            "appearance", "chats", "media", "privacy", "tools",
            "integrations", "fun", "libraries"));

    private static final Pattern ID_PATTERN =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{1,31}$");

    // NOTE (bug found by the JDK stub test, 2026-11-15): the key
    // group must be [a-z]+(_[a-z]+)* with the closing "__" outside
    // it. The old [a-z_]+ swallowed the trailing underscores, so
    // "__id__" captured "id__", the switch never matched, and EVERY
    // plugin was refused with "Missing metadata". Same fix on the
    // list pattern (category/requirements were silently dropped).
    private static final Pattern STRING_ASSIGN = Pattern.compile(
            "^__([a-z]+(?:_[a-z]+)*)__\\s*=\\s*(\"([^\"]*)\"|'([^']*)')\\s*(#.*)?$");
    private static final Pattern LIST_ASSIGN = Pattern.compile(
            "^__([a-z]+(?:_[a-z]+)*)__\\s*=\\s*\\[([^\\]]*)\\]\\s*(#.*)?$");
    private static final Pattern QUOTED_ITEM = Pattern.compile("\"([^\"]*)\"|'([^']*)'");

    /** Result of {@link #parse(String)}: metadata or a refusal reason. */
    public static final class Parsed {
        public PyPlugin plugin;
        /** Null when parsing succeeded; otherwise a readable refusal. */
        public String error;
    }

    /** Mutable string fields collected while parsing. */
    private static final class StrFields {
        String id;
        String name;
        String description = "";
        String author = "";
        String version = "1.0";
        String icon = "";
        String appVersion = "";
        String sdkVersion = "";
    }

    private static void setStringField(StrFields f, String key, String value) {
        switch (key) {
            case "id": f.id = value; break;
            case "name": f.name = value; break;
            case "description": f.description = value; break;
            case "author": f.author = value; break;
            case "version": f.version = value; break;
            case "icon": f.icon = value; break;
            case "app_version": f.appVersion = value; break;
            case "sdk_version": f.sdkVersion = value; break;
            default: break; // unknown string metadata: ignored
        }
    }

    /** Paren depth of a line, ignoring characters inside string literals. */
    private static int parenDepth(String line) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && (inSingle || inDouble) && i + 1 < line.length()) {
                i++;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
        }
        return depth;
    }

    public static Parsed parse(String source) {
        Parsed out = new Parsed();
        if (source == null || source.trim().isEmpty()) {
            out.error = "Empty plugin file.";
            return out;
        }
        // Multi-file Elyx archives are a ZIP, not Python source.
        // (M4: installFromBytes routes ZIPs to PyElyxLoader before
        // this is ever reached; this stays as a defence in depth.)
        if (source.startsWith("PK\u0003\u0004")) {
            out.error = "This is a .elyx multi-file archive, not a "
                    + "single-file .plugin.";
            return out;
        }
        StrFields f = new StrFields();
        List<String> categories = new ArrayList<>();
        List<String> requirements = new ArrayList<>();
        boolean sawAnyMeta = false;
        String[] rawLines = source.split("\n");
        for (int li = 0; li < rawLines.length; li++) {
            String line = rawLines[li].trim();
            if (!line.startsWith("__")) {
                continue;
            }
            // Parenthesised implicit concatenation — the one multi-line
            // form that IS a literal in Python (CPython folds adjacent
            // string literals into one Constant, so the AST loader
            // accepts it). MaterialSettingsList itself writes its
            // __description__ this way; rejecting it meant the very
            // plugin this port exists for could not install.
            Matcher pm = Pattern.compile("^__([a-z]+(?:_[a-z]+)*)__\\s*=\\s*\\(.*$")
                    .matcher(line);
            if (pm.matches() && parenDepth(line) > 0) {
                StringBuilder block = new StringBuilder(line);
                int depth = parenDepth(line);
                while (depth > 0 && li + 1 < rawLines.length) {
                    li++;
                    block.append("\n").append(rawLines[li]);
                    depth += parenDepth(rawLines[li]);
                }
                if (depth == 0) {
                    Matcher qm = QUOTED_ITEM.matcher(block.toString());
                    StringBuilder value = new StringBuilder();
                    boolean any = false;
                    while (qm.find()) {
                        value.append(qm.group(1) != null ? qm.group(1) : qm.group(2));
                        any = true;
                    }
                    if (any) {
                        sawAnyMeta = true;
                        setStringField(f, pm.group(1), value.toString());
                        continue;
                    }
                }
                out.error = "Metadata must be literal values: the line '"
                        + line + "' is not a plain string or list.";
                return out;
            }
            Matcher sm = STRING_ASSIGN.matcher(line);
            if (sm.matches()) {
                sawAnyMeta = true;
                String key = sm.group(1);
                String value = sm.group(3) != null ? sm.group(3) : sm.group(4);
                setStringField(f, key, value);
                continue;
            }
            Matcher lm = LIST_ASSIGN.matcher(line);
            if (lm.matches()) {
                sawAnyMeta = true;
                String key = lm.group(1);
                String body = lm.group(2);
                List<String> items = new ArrayList<>();
                Matcher im = QUOTED_ITEM.matcher(body);
                while (im.find()) {
                    items.add(im.group(1) != null ? im.group(1) : im.group(2));
                }
                if ("category".equals(key)) {
                    categories = items;
                } else if ("requirements".equals(key)) {
                    requirements = items;
                }
                continue;
            }
            // A __-assignment that is neither a literal string nor a
            // literal list: non-literal metadata — reject, as the AST
            // loader would. (Triple-quoted values and +-concatenation
            // land here; parenthesised implicit concatenation is
            // handled above and is a literal.)
            if (line.matches("^__[a-z_]+\\s*=.*")) {
                out.error = "Metadata must be literal values: the line '"
                        + line + "' is not a plain string or list.";
                return out;
            }
        }
        if (!sawAnyMeta || f.id == null || f.name == null) {
            out.error = "Missing metadata: a .plugin file must define "
                    + "__id__ and __name__ as literal values.";
            return out;
        }
        if (!ID_PATTERN.matcher(f.id).matches()) {
            out.error = "Invalid __id__ '" + f.id + "': 2–32 chars, starts "
                    + "with a letter, only letters, digits, '_' and '-'.";
            return out;
        }
        if (!isSemver(f.version)) {
            out.error = "Invalid __version__ '" + f.version + "': use semver "
                    + "like 1.1.1.";
            return out;
        }
        if (categories.size() > 3) {
            out.error = "Too many __category__ entries (max 3).";
            return out;
        }
        for (String c : categories) {
            if (!KNOWN_CATEGORIES.contains(c)) {
                out.error = "Unknown __category__ '" + c + "'.";
                return out;
            }
        }
        if (!f.appVersion.isEmpty() && !satisfies(f.appVersion, PyPluginsPrefs.APP_VERSION)) {
            out.error = "This plugin needs app version " + f.appVersion
                    + "; AminGram is " + PyPluginsPrefs.APP_VERSION + ".";
            return out;
        }
        if (!f.sdkVersion.isEmpty() && !satisfies(f.sdkVersion, PyPluginsPrefs.SDK_VERSION)) {
            out.error = "This plugin needs SDK version " + f.sdkVersion
                    + "; AminGram implements " + PyPluginsPrefs.SDK_VERSION + ".";
            return out;
        }
        PyPlugin plugin = new PyPlugin();
        plugin.id = f.id;
        plugin.name = f.name;
        plugin.description = f.description;
        plugin.author = f.author;
        plugin.version = f.version;
        plugin.icon = f.icon;
        plugin.appVersion = f.appVersion;
        plugin.sdkVersion = f.sdkVersion;
        plugin.categories.addAll(categories);
        plugin.requirements.addAll(requirements);
        out.plugin = plugin;
        return out;
    }

    /** Evaluates a constraint like {@code ">=12.10.6"} against a
     *  version. Bare versions mean {@code ==}. Unknown operators
     *  fail closed (constraint not satisfied). */
    public static boolean satisfies(String constraint, String version) {
        if (constraint == null || constraint.trim().isEmpty()) {
            return true;
        }
        String c = constraint.trim();
        String op = "==";
        String target = c;
        for (String candidate : new String[] {">=", "<=", "==", ">", "<"}) {
            if (c.startsWith(candidate)) {
                op = candidate;
                target = c.substring(candidate.length()).trim();
                break;
            }
        }
        int cmp = compareVersions(version, target);
        switch (op) {
            case ">=": return cmp >= 0;
            case "<=": return cmp <= 0;
            case ">": return cmp > 0;
            case "<": return cmp < 0;
            case "==": default: return cmp == 0;
        }
    }

    public static boolean isSemver(String version) {
        if (version == null) {
            return false;
        }
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Numeric dotted-version comparison; missing parts count as 0. */
    public static int compareVersions(String a, String b) {
        long[] pa = versionParts(a);
        long[] pb = versionParts(b);
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) {
                return pa[i] < pb[i] ? -1 : 1;
            }
        }
        return 0;
    }

    private static long[] versionParts(String version) {
        long[] out = new long[3];
        if (version == null) {
            return out;
        }
        String[] parts = version.trim().split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                out[i] = Long.parseLong(parts[i].trim());
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
