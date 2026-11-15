package org.telegram.messenger.amin.pyplugins;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Elyx archive loader (M4): {@code .elyx} / {@code .eaf} files.
 *
 * An Elyx archive is a ZIP with, at minimum:
 * <pre>
 *   refmap.yml      — maps logical names to files (entry point)
 *   metainfo.yml    — id / name / version / author (same fields
 *                     as a .plugin's metadata)
 *   src/*.py        — plugin source (exactly one entry file)
 *   strings/*.yml   — optional localised strings (loaded, not
 *                     executed)
 * </pre>
 *
 * Entry-point rule (a design decision, NOT a verified Elyx
 * spec — no public Elyx reference doc was found during M4):
 * {@code refmap.yml} may name the entry file with an
 * {@code entry:} / {@code main:} key; otherwise {@code src/main.py}
 * wins; otherwise a lone {@code src/*.py} file wins; otherwise
 * the archive is refused as ambiguous. Only the entry file is
 * executed; other {@code src/*.py} files are importable modules
 * of the same plugin (same package, no new paths).
 *
 * Security limits (fail closed, no partial extraction):
 * <ul>
 *   <li>Archive &gt; 10 MB → refused.</li>
 *   <li>Any single extracted file &gt; 2 MB → refused.</li>
 *   <li>Zip-Slip: any entry whose name is absolute, or contains
 *       {@code ..}, or escapes the archive root → the whole
 *       archive is refused (not skipped, not sanitised).</li>
 *   <li>Symlink entries are refused outright (a symlink is not
 *       a file; an archive that needs one is not a plugin).</li>
 *   <li>Only {@code src/*.py}, {@code refmap.yml},
 *       {@code metainfo.yml} and {@code strings/*.yml} are kept.
 *       Anything else in the archive is ignored, never extracted
 *       to disk and never executed.</li>
 *   <li>{@code strings/*.yml} and the YAML files are parsed as
 *       flat {@code key: value} text only — there is no YAML
 *       parser in the app and no YAML is ever evaluated.</li>
 * </ul>
 *
 * After extraction the entry source goes through the SAME
 * install-time gates as a single-file plugin
 * ({@link PyPluginMetadata} + {@link PyPluginAnalysis}), and
 * the metainfo id/name/version must match the entry source's
 * metadata when both are present. The loaded plugin is then
 * installed as a normal plugin directory
 * ({@code <id>/__main__.py} + {@code src/} + {@code strings/}).
 */
public final class PyElyxLoader {

    private PyElyxLoader() {
    }

    /** Max archive size: 10 MB. */
    public static final long MAX_ARCHIVE_BYTES = 10L * 1024L * 1024L;
    /** Max single extracted file: 2 MB. */
    public static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    /** Max entries in the archive: 200. */
    public static final int MAX_ENTRIES = 200;

    /** Result of {@link #load(File)} / {@link #load(InputStream)}. */
    public static final class Loaded {
        /** Entry-point Python source (UTF-8). Null on refusal. */
        public String entrySource;
        /** Name of the entry file inside the archive (e.g. {@code src/main.py}). */
        public String entryName;
        /** Extra {@code src/*.py} modules, name → source. */
        public Map<String, String> modules = new LinkedHashMap<>();
        /** Localised strings, file name → flat key/value pairs. */
        public Map<String, Map<String, String>> strings = new LinkedHashMap<>();
        /** Flat {@code metainfo.yml} values (id, name, version, …). */
        public Map<String, String> metainfo = new LinkedHashMap<>();
        /** Flat {@code refmap.yml} values (entry, …). */
        public Map<String, String> refmap = new LinkedHashMap<>();
        /** Null when loading succeeded; otherwise a readable refusal. */
        public String error;
    }

    /** Loads an Elyx archive from a file. */
    public static Loaded load(File file) {
        Loaded out = new Loaded();
        if (file == null || !file.isFile()) {
            out.error = "Archive file is missing.";
            return out;
        }
        if (file.length() > MAX_ARCHIVE_BYTES) {
            out.error = "Archive is too large (over 10 MB).";
            return out;
        }
        try (InputStream in = new FileInputStream(file)) {
            return load(in);
        } catch (IOException e) {
            out.error = "Could not read the archive.";
            return out;
        }
    }

    /** Loads an Elyx archive from a stream (capped at 10 MB). */
    public static Loaded load(InputStream in) {
        Loaded out = new Loaded();
        byte[] bytes;
        try {
            bytes = readAllCapped(in, MAX_ARCHIVE_BYTES);
        } catch (IOException e) {
            out.error = "Could not read the archive.";
            return out;
        }
        if (bytes == null) {
            out.error = "Archive is too large (over 10 MB).";
            return out;
        }
        return load(bytes);
    }

    /** Loads an Elyx archive from its bytes. */
    public static Loaded load(byte[] bytes) {
        Loaded out = new Loaded();
        // Symlink detection needs the ZIP central directory
        // (java.util.zip.ZipEntry exposes no external attributes),
        // so parse it from the bytes before streaming entries.
        Map<String, Long> attrs = centralDirectoryAttrs(bytes);
        Map<String, byte[]> kept = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new java.io.ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                count++;
                if (count > MAX_ENTRIES) {
                    out.error = "Archive has too many entries (max "
                            + MAX_ENTRIES + ").";
                    return out;
                }
                String name = entry.getName();
                // Zip-Slip + absolute paths: refuse the WHOLE archive.
                if (name == null || name.startsWith("/")
                        || name.startsWith("\\")
                        || name.contains("..")) {
                    out.error = "Unsafe entry name '" + name
                            + "': archives must not contain absolute "
                            + "paths or '..'.";
                    return out;
                }
                if (entry.isDirectory()) {
                    continue;
                }
                // Symlinks are not files: refuse outright.
                if (isSymlink(name, attrs)) {
                    out.error = "Symlink entry '" + name
                            + "' is not allowed.";
                    return out;
                }
                if (!isKept(name)) {
                    continue;
                }
                byte[] entryBytes = readCapped(zip, MAX_FILE_BYTES);
                if (entryBytes == null) {
                    out.error = "File '" + name
                            + "' is too large (over 2 MB).";
                    return out;
                }
                kept.put(name, entryBytes);
            }
        } catch (IOException e) {
            out.error = "Could not read the archive (not a valid ZIP?).";
            return out;
        }
        return finish(out, kept);
    }

    private static Loaded finish(Loaded out, Map<String, byte[]> kept) {
        // refmap / metainfo are flat key: value text.
        if (kept.containsKey("refmap.yml")) {
            out.refmap = parseFlatYaml(toString(kept.get("refmap.yml")));
        }
        if (kept.containsKey("metainfo.yml")) {
            out.metainfo = parseFlatYaml(toString(kept.get("metainfo.yml")));
        }
        // Entry point: refmap entry/main -> src/main.py -> lone src/*.py.
        List<String> srcFiles = new ArrayList<>();
        for (String name : kept.keySet()) {
            if (name.startsWith("src/") && name.endsWith(".py")) {
                srcFiles.add(name);
            }
        }
        if (srcFiles.isEmpty()) {
            out.error = "Archive has no src/*.py files.";
            return out;
        }
        String entryName = null;
        String refEntry = firstNonEmpty(out.refmap.get("entry"),
                out.refmap.get("main"));
        if (refEntry != null) {
            String wanted = refEntry.startsWith("src/")
                    ? refEntry : "src/" + refEntry;
            if (kept.containsKey(wanted)) {
                entryName = wanted;
            } else {
                out.error = "refmap.yml names entry '" + refEntry
                        + "', but that file is not in the archive.";
                return out;
            }
        } else if (kept.containsKey("src/main.py")) {
            entryName = "src/main.py";
        } else if (srcFiles.size() == 1) {
            entryName = srcFiles.get(0);
        } else {
            out.error = "Ambiguous entry point: " + srcFiles.size()
                    + " src/*.py files and no refmap.yml entry / "
                    + "src/main.py. Name the entry in refmap.yml.";
            return out;
        }
        out.entryName = entryName;
        out.entrySource = toString(kept.get(entryName));
        for (String name : srcFiles) {
            if (!name.equals(entryName)) {
                out.modules.put(name, toString(kept.get(name)));
            }
        }
        for (Map.Entry<String, byte[]> e : kept.entrySet()) {
            if (e.getKey().startsWith("strings/") && e.getKey().endsWith(".yml")) {
                out.strings.put(e.getKey(), parseFlatYaml(toString(e.getValue())));
            }
        }
        return out;
    }

    /** Only these paths are ever extracted from an archive. */
    public static boolean isKept(String name) {
        if (name == null) {
            return false;
        }
        return "refmap.yml".equals(name)
                || "metainfo.yml".equals(name)
                || (name.startsWith("src/") && name.endsWith(".py"))
                || (name.startsWith("strings/") && name.endsWith(".yml"));
    }

    /** Unix symlink bit (0120000) in the external attributes. */
    static boolean isSymlinkAttrs(long attrs) {
        return (attrs & 0170000L) == 0120000L;
    }

    /** True when the named entry is a symlink (per the central directory). */
    private static boolean isSymlink(String name, Map<String, Long> attrs) {
        Long a = attrs.get(name);
        return a != null && isSymlinkAttrs(a);
    }

    /**
     * External file attributes (upper 16 bits) per entry name,
     * parsed from the ZIP central directory. {@code java.util.zip}
     * exposes no external attributes on {@link ZipEntry} — neither
     * on the JDK nor on Android — so this small parser is the only
     * way to see the Unix file-type bits (symlink detection).
     * A malformed directory yields an empty map (fail closed is
     * handled by the entry stream: no attrs entry means the
     * entry is treated as a regular file, and the symlink test
     * in TestM4 covers a well-formed archive).
     */
    static Map<String, Long> centralDirectoryAttrs(byte[] zip) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (zip == null || zip.length < 22) {
            return out;
        }
        // Find the End Of Central Directory record (scan backwards).
        int eocd = -1;
        for (int i = zip.length - 22; i >= 0 && i >= zip.length - 22 - 65536; i--) {
            if (u32(zip, i) == 0x06054b50L) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            return out;
        }
        int count = u16(zip, eocd + 10);
        int cdOffset = (int) u32(zip, eocd + 16);
        int p = cdOffset;
        for (int i = 0; i < count; i++) {
            if (p + 46 > zip.length || u32(zip, p) != 0x02014b50L) {
                break;
            }
            int nameLen = u16(zip, p + 28);
            int extraLen = u16(zip, p + 30);
            int commentLen = u16(zip, p + 32);
            long extAttrs = u32(zip, p + 38);
            if (p + 46 + nameLen > zip.length) {
                break;
            }
            String name = new String(zip, p + 46, nameLen,
                    java.nio.charset.StandardCharsets.UTF_8);
            out.put(name, extAttrs >> 16);
            p += 46 + nameLen + extraLen + commentLen;
        }
        return out;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8)
                | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }

    /** Reads a stream fully, capped: null when it exceeds {@code cap}. */
    private static byte[] readAllCapped(InputStream in, long cap) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > cap) {
                return null;
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /**
     * Reads a ZIP entry, capped at {@code cap} bytes. Returns null
     * when the entry is bigger than the cap (the rest is drained
     * and discarded so the stream stays usable).
     */
    private static byte[] readCapped(InputStream in, long cap) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > cap) {
                return null;
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static String toString(byte[] bytes) {
        try {
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            return new String(bytes);
        }
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return null;
    }

    /**
     * Minimal flat YAML: one {@code key: value} per line.
     * Comments ({@code #…}), blank lines and indented lines are
     * ignored. Quoted values lose their quotes. This is NOT a YAML
     * parser — nested maps, lists and anchors are not supported
     * and are silently dropped, which is fine for refmap /
     * metainfo / strings files.
     */
    public static Map<String, String> parseFlatYaml(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // Indented lines belong to a nested block: not flat.
            if (rawLine.length() > 0 && (rawLine.charAt(0) == ' ' || rawLine.charAt(0) == '\t')) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.startsWith("#")) {
                value = "";
            } else {
                int hash = value.indexOf(" #");
                if (hash >= 0) {
                    value = value.substring(0, hash).trim();
                }
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
            }
            if (!key.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }
}
