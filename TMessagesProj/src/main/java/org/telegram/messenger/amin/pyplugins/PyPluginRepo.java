package org.telegram.messenger.amin.pyplugins;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plugin catalog / repository loader (M4).
 *
 * A catalog is a JSON document ({@code extera-plugins.json},
 * schema version 1) published by a plugin channel or a website:
 *
 * <pre>
 * {
 *   "version": 1,
 *   "name": "My plugin channel",
 *   "plugins": [
 *     {
 *       "id": "material_settings_list",
 *       "name": "Material Settings List",
 *       "version": "1.1.1",
 *       "author": "zwylair",
 *       "url": "https://example.com/material_settings_list_1_87g4.plugin",
 *       "sha256": "bbeb7267b8cf5de2ff0b4f0beee305d2c0e2bf7f7ec62961fa51c669cfe01b1e",
 *       "size": 53123,
 *       "description": "…"
 *     }
 *   ]
 * }
 * </pre>
 *
 * Security model, in order — a download is only ever written to
 * the install queue when every gate passes:
 * <ol>
 *   <li>The catalog itself must be schema 1 with valid entries
 *       (bad id, bad version, non-http(s) URL, malformed sha256,
 *       non-positive size → the entry is dropped, never installed).</li>
 *   <li>Unknown sources are OFF by default. An index URL whose
 *       host is not on the built-in allow-list (currently: none —
 *       every remote catalog is an unknown source until the user
 *       adds its host in the hub) is refused unless the user has
 *       explicitly enabled unknown sources in the hub. There is
 *       no bundled catalog and no bundled plugin.</li>
 *   <li>After download, size and SHA-256 are checked against the
 *       catalog entry. A mismatch — tampered file, truncated
 *       download, wrong file — is rejected and the bytes are
 *       deleted, never parsed, never executed.</li>
 *   <li>Only then does the file go through the normal
 *       install-time gates ({@link PyPluginMetadata} +
 *       {@link PyPluginAnalysis}): a RAT loader, a plugin that
 *       exfiltrates tokens, or one that downloads code at runtime
 *       is flagged there, exactly as for a hand-picked file.</li>
 * </ol>
 *
 * Channel scanning: a channel's messages are scanned for
 * {@code .plugin} / {@code .elyx} documents
 * ({@link #scanMessages(List)}). The scan only COLLECTS
 * candidates (name + URL + size); a candidate has no id and no
 * sha256 until it is downloaded, so it is installed only after
 * the normal gates run on the downloaded bytes. Loading a
 * channel's history into that scan is a hub wiring job (one
 * SearchMessages request) and is NOT in this class.
 *
 * This class does no networking itself: the caller fetches the
 * index JSON / file bytes (FileLoader or the panel's download
 * helper) and hands the bytes here for parsing and verification.
 * That keeps the pure logic testable without Android.
 */
public final class PyPluginRepo {

    private PyPluginRepo() {
    }

    /** Max catalog size: 1 MB. Bigger than any honest index. */
    public static final long MAX_INDEX_BYTES = 1024L * 1024L;
    /** Max single plugin file: 10 MB. */
    public static final long MAX_PLUGIN_BYTES = 10L * 1024L * 1024L;
    /** Max catalog entries: 500. */
    public static final int MAX_ENTRIES = 500;

    /** One catalog entry — the unit the hub shows and installs. */
    public static final class Entry {
        public String id;
        public String name;
        public String version;
        public String author;
        public String description;
        public String url;
        /** Lower-case hex SHA-256 from the catalog (64 chars). */
        public String sha256;
        /** Declared size in bytes (must be &gt; 0). */
        public long size;
        /** True when the entry came from a channel scan, not an index. */
        public boolean fromChannelScan;
        /** Refusal reason when {@link #verifyEntry(Entry)} fails. */
        public String error;
    }

    /** Result of {@link #parseIndex(String)}: entries or a refusal. */
    public static final class IndexResult {
        public List<Entry> entries = new ArrayList<>();
        /** Catalog display name ("" when absent). */
        public String name = "";
        /** Null when parsing succeeded; otherwise a readable refusal. */
        public String error;
        /** Entries dropped for failing {@link #verifyEntry(Entry)}. */
        public int dropped;
    }

    /** Parses an {@code extera-plugins.json} document (schema 1). */
    public static IndexResult parseIndex(String json) {
        IndexResult out = new IndexResult();
        if (json == null || json.trim().isEmpty()) {
            out.error = "Empty catalog.";
            return out;
        }
        if (json.length() > MAX_INDEX_BYTES) {
            out.error = "Catalog is too large (over 1 MB).";
            return out;
        }
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            out.error = "Catalog is not valid JSON.";
            return out;
        }
        if (root.optInt("version", -1) != 1) {
            out.error = "Unsupported catalog schema version "
                    + "(need version 1).";
            return out;
        }
        out.name = root.optString("name", "");
        JSONArray plugins = root.optJSONArray("plugins");
        if (plugins == null) {
            out.error = "Catalog has no \"plugins\" list.";
            return out;
        }
        if (plugins.length() > MAX_ENTRIES) {
            out.error = "Catalog has too many plugins (max "
                    + MAX_ENTRIES + ").";
            return out;
        }
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject o = plugins.optJSONObject(i);
            if (o == null) {
                out.dropped++;
                continue;
            }
            Entry e = new Entry();
            e.id = o.optString("id", "");
            e.name = o.optString("name", "");
            e.version = o.optString("version", "");
            e.author = o.optString("author", "");
            e.description = o.optString("description", "");
            e.url = o.optString("url", "");
            e.sha256 = o.optString("sha256", "").toLowerCase(Locale.US);
            e.size = o.optLong("size", -1);
            if (!verifyEntry(e)) {
                out.dropped++;
                continue;
            }
            out.entries.add(e);
        }
        return out;
    }

    /**
     * Validates one catalog entry. Fails closed: any bad field
     * drops the entry (the refusal reason is in
     * {@link Entry#error}).
     */
    public static boolean verifyEntry(Entry e) {
        if (e == null) {
            return false;
        }
        if (e.id == null || e.id.trim().isEmpty()) {
            e.error = "Missing plugin id.";
            return false;
        }
        if (e.id.length() > 64) {
            e.error = "Plugin id is too long.";
            return false;
        }
        if (e.name == null || e.name.trim().isEmpty()) {
            e.error = "Missing plugin name.";
            return false;
        }
        if (e.version == null || !PyPluginMetadata.isSemver(e.version)) {
            e.error = "Invalid version '" + e.version + "' (use semver).";
            return false;
        }
        if (e.url == null
                || !(e.url.startsWith("https://") || e.url.startsWith("http://"))
                || e.url.length() > 2048) {
            e.error = "Plugin URL must be http(s).";
            return false;
        }
        if (e.sha256 == null || !e.sha256.matches("^[0-9a-f]{64}$")) {
            e.error = "Missing or malformed sha256 (need 64 hex chars).";
            return false;
        }
        if (e.size <= 0 || e.size > MAX_PLUGIN_BYTES) {
            e.error = "Invalid size (must be 1 byte – 10 MB).";
            return false;
        }
        e.error = null;
        return true;
    }

    /** SHA-256 of a file, lower-case hex. Null on any I/O error. */
    public static String sha256Hex(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            return sha256Hex(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** SHA-256 of a stream, lower-case hex. Null on any error. */
    public static String sha256Hex(InputStream in) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verifies a downloaded file against its catalog entry:
     * size first (cheap), then SHA-256. Returns null when the
     * file is good, otherwise a readable refusal. A non-null
     * return means the file must be deleted, never installed.
     */
    public static String verifyFile(Entry entry, File file) {
        if (entry == null || file == null || !file.isFile()) {
            return "Downloaded file is missing.";
        }
        if (file.length() != entry.size) {
            return "Size mismatch: expected " + entry.size + " bytes, got "
                    + file.length() + ". The file may be tampered with.";
        }
        String actual = sha256Hex(file);
        if (actual == null) {
            return "Could not hash the downloaded file.";
        }
        if (!actual.equals(entry.sha256)) {
            return "SHA-256 mismatch: the downloaded file does not "
                    + "match the catalog. It may be tampered with — "
                    + "rejected.";
        }
        return null;
    }

    /** Host of a URL (lower-case), or "" when it cannot be parsed. */
    public static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        int scheme = u.indexOf("://");
        if (scheme < 0) {
            return "";
        }
        String rest = u.substring(scheme + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end).toLowerCase(Locale.US);
    }

    /**
     * Scans channel messages for plugin documents. Returns one
     * candidate per {@code .plugin} / {@code .elyx} attachment.
     * Candidates have no id and no sha256 — the hub must download
     * them and run the normal install gates before anything is
     * installed. Messages without a document, and documents with
     * any other extension, are ignored.
     */
    public static List<Entry> scanMessages(List<MessageObject> messages) {
        List<Entry> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (MessageObject mo : messages) {
            if (mo == null || mo.messageOwner == null) {
                continue;
            }
            TLRPC.Document doc = mo.messageOwner.media instanceof TLRPC.TL_messageMediaDocument
                    ? ((TLRPC.TL_messageMediaDocument) mo.messageOwner.media).document
                    : null;
            if (doc == null) {
                continue;
            }
            String fileName = null;
            long size = 0;
            for (TLRPC.DocumentAttribute attr : doc.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeFilename) {
                    fileName = ((TLRPC.TL_documentAttributeFilename) attr).file_name;
                }
            }
            size = doc.size;
            if (fileName == null) {
                continue;
            }
            String lower = fileName.toLowerCase(Locale.US);
            if (!lower.endsWith(".plugin") && !lower.endsWith(".elyx")
                    && !lower.endsWith(".eaf")) {
                continue;
            }
            if (size <= 0 || size > MAX_PLUGIN_BYTES) {
                continue;
            }
            Entry e = new Entry();
            e.id = "";
            e.name = fileName;
            e.version = "";
            e.url = "";
            e.sha256 = "";
            e.size = size;
            e.fromChannelScan = true;
            out.add(e);
        }
        return out;
    }
}
