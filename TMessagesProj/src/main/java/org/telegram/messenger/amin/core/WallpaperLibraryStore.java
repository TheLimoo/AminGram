package org.telegram.messenger.amin.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Live wallpaper library (v2.0): the durable collection behind the
 * Video Chat Background module. Before v2.0 the module knew exactly
 * ONE file — the hub picker copied it to filesDir/amingram/videobg.*
 * and the path lived in prefs "amingram_videobg"/"path"; picking a
 * new file deleted the old one. The library keeps every pick:
 *
 * - files: filesDir/amingram/wallpapers/&lt;id&gt;.&lt;ext&gt; (durable
 *   copies, never the picker's cache).
 * - index: prefs "amingram_wallpaper_library" — "items" (JSONArray of
 *   {id, displayName, fileName, addedAt}, in addedAt order) and
 *   "active_id".
 *
 * Migration (flag-guarded, see {@link #ensureMigrated}): a legacy
 * "amingram_videobg"/"path" pointing to an existing non-empty file
 * becomes library item #1 ("Current", active) by COPYING it in —
 * the original is never moved or deleted, and if the copy fails the
 * legacy pref is left untouched and migration stays undone so the
 * next call retries. After migration this store is the source of
 * truth; the legacy "path" pref is kept SYNCED to the active item's
 * file (removed when nothing is active) so older readers (and
 * backups taken through VideoBackgroundHelper) stay consistent.
 *
 * All entry points are synchronized and Throwable-isolated: worst
 * case is an empty library / no active wallpaper, never a crash.
 */
public final class WallpaperLibraryStore {

    public static final String PREFS = "amingram_wallpaper_library";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_ACTIVE = "active_id";
    private static final String KEY_MIGRATED = "migrated_v1";

    private static final String LEGACY_PREFS = "amingram_videobg";
    private static final String LEGACY_KEY_PATH = "path";

    /** True while the legacy-file copy queued by ensureMigrated()
     *  is still queued/running on stageQueue (audit P1-4). Guarded
     *  by the class lock — every entry point of this store is
     *  synchronized; the queued runnable clears it under the same
     *  lock when it finishes. */
    private static boolean migrationInFlight;

    private WallpaperLibraryStore() {
    }

    // ------------------------------------------------------------------
    // Entry
    // ------------------------------------------------------------------

    public static final class Entry {
        public final String id;
        public final String displayName;
        public final String fileName;
        public final long addedAt;

        Entry(String id, String displayName, String fileName, long addedAt) {
            this.id = id;
            this.displayName = displayName;
            this.fileName = fileName;
            this.addedAt = addedAt;
        }

        public File file(Context context) {
            return new File(wallpapersDir(context), fileName);
        }

        public long size(Context context) {
            try {
                File file = file(context);
                return file.exists() ? file.length() : 0;
            } catch (Throwable t) {
                return 0;
            }
        }

        public boolean isGif() {
            return fileName != null && fileName.toLowerCase(Locale.US).endsWith(".gif");
        }

        /** Playback duration in ms via MediaMetadataRetriever;
         *  0 when unknown / on any failure, and always 0 for GIFs
         *  (no meaningful single duration). */
        public long durationMs(Context context) {
            if (isGif()) {
                return 0;
            }
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                File file = file(context);
                if (!file.exists()) {
                    return 0;
                }
                retriever.setDataSource(file.getAbsolutePath());
                String raw = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);
                return raw == null ? 0 : Math.max(0, Long.parseLong(raw));
            } catch (Throwable t) {
                return 0;
            } finally {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Storage primitives (callers hold the class lock via the
    // synchronized public API)
    // ------------------------------------------------------------------

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File wallpapersDir(Context context) {
        return new File(new File(context.getFilesDir(), "amingram"), "wallpapers");
    }

    /** Cached thumbnail for an item (written by the library UI,
     *  removed here when the item is deleted). */
    public static File thumbFile(Context context, String id) {
        return new File(new File(context.getCacheDir(), "amingram_wallpaper_thumbs"),
                id + ".jpg");
    }

    private static List<Entry> loadItems(Context context) {
        List<Entry> out = new ArrayList<>();
        try {
            String raw = prefs(context).getString(KEY_ITEMS, null);
            if (raw == null) {
                return out;
            }
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String id = o.optString("id", "");
                String fileName = o.optString("fileName", "");
                if (id.isEmpty() || fileName.isEmpty()) {
                    continue;
                }
                out.add(new Entry(id, o.optString("displayName", "Wallpaper"),
                        fileName, o.optLong("addedAt", 0)));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String loadActiveId(Context context) {
        try {
            String id = prefs(context).getString(KEY_ACTIVE, null);
            return id == null || id.isEmpty() ? null : id;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void save(Context context, List<Entry> items, String activeId) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : items) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("displayName", e.displayName);
                o.put("fileName", e.fileName);
                o.put("addedAt", e.addedAt);
                arr.put(o);
            }
            SharedPreferences.Editor edit = prefs(context).edit();
            edit.putString(KEY_ITEMS, arr.toString());
            if (activeId == null) {
                edit.remove(KEY_ACTIVE);
            } else {
                edit.putString(KEY_ACTIVE, activeId);
            }
            edit.apply();
        } catch (Throwable ignored) {
        }
        syncLegacy(context, items, activeId);
    }

    /** Keeps the pre-library "amingram_videobg"/"path" pref pointing
     *  at the active item's file (removed when nothing is active). */
    private static void syncLegacy(Context context, List<Entry> items, String activeId) {
        try {
            String path = null;
            if (activeId != null) {
                for (Entry e : items) {
                    if (e.id.equals(activeId)) {
                        path = e.file(context).getAbsolutePath();
                        break;
                    }
                }
            }
            SharedPreferences.Editor edit = context.getSharedPreferences(
                    LEGACY_PREFS, Context.MODE_PRIVATE).edit();
            if (path == null) {
                edit.remove(LEGACY_KEY_PATH);
            } else {
                edit.putString(LEGACY_KEY_PATH, path);
            }
            edit.apply();
        } catch (Throwable ignored) {
        }
    }

    private static Entry findById(List<Entry> items, String id) {
        if (id == null) {
            return null;
        }
        for (Entry e : items) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        return null;
    }

    private static boolean fileUsable(File file) {
        return file != null && file.exists() && file.length() > 0;
    }

    private static String generateId() {
        return "wp" + Long.toHexString(System.currentTimeMillis())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** Same extension rules the old hub picker used: lowercase
     *  [a-z0-9], at most 5 chars, anything else falls back. */
    public static String sanitizeExt(String ext) {
        if (ext != null) {
            String clean = ext.toLowerCase(Locale.US);
            if (clean.startsWith(".")) {
                clean = clean.substring(1);
            }
            if (!clean.isEmpty() && clean.length() <= 5 && clean.matches("[a-z0-9]+")) {
                return clean;
            }
        }
        return "mp4";
    }

    public static String extOf(String nameOrPath) {
        if (nameOrPath != null) {
            int dot = nameOrPath.lastIndexOf('.');
            if (dot > 0 && dot > nameOrPath.lastIndexOf('/')) {
                return sanitizeExt(nameOrPath.substring(dot + 1));
            }
        }
        return "mp4";
    }

    // ------------------------------------------------------------------
    // Migration
    // ------------------------------------------------------------------

    /**
     * One-time, flag-guarded migration from the single-file era.
     * A legacy path pointing to an existing non-empty file is COPIED
     * in as item #1 ("Current", active); the original file and the
     * legacy pref are left exactly as they were until the copy has
     * succeeded (the pref is then re-synced to the library copy by
     * {@link #save}). On copy failure nothing is marked or changed,
     * so the next call retries. With no usable legacy file the
     * library simply starts empty.
     *
     * The copy itself is queued on Utilities.stageQueue (audit
     * P1-4): it can be tens of MB and this method is reachable from
     * the main thread (getActivePath via ChatActivity attach), so
     * it must never run inline on the caller. While the copy is
     * pending, getActivePath() serves the legacy file directly.
     */
    public static synchronized void ensureMigrated(Context context) {
        if (context == null) {
            return;
        }
        try {
            if (prefs(context).getBoolean(KEY_MIGRATED, false)) {
                return;
            }
            String legacyPath = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                    .getString(LEGACY_KEY_PATH, null);
            File legacyFile = legacyPath == null ? null : new File(legacyPath);
            if (!fileUsable(legacyFile)) {
                // Fresh install, or a dangling / zero-length legacy
                // path: start empty (save() also drops the stale
                // legacy pref via the sync rule).
                save(context, loadItems(context), loadActiveId(context));
                prefs(context).edit().putBoolean(KEY_MIGRATED, true).apply();
                return;
            }
            List<Entry> items = loadItems(context);
            if (!items.isEmpty()) {
                // Something is already indexed (should not happen
                // pre-migration); trust the library, just mark done.
                prefs(context).edit().putBoolean(KEY_MIGRATED, true).apply();
                syncLegacy(context, items, loadActiveId(context));
                return;
            }
            if (migrationInFlight) {
                // A copy is already queued; the legacy file keeps
                // serving via getActivePath() until it lands.
                return;
            }
            migrationInFlight = true;
            final Context appContext = context.getApplicationContext();
            final String path = legacyPath;
            Utilities.stageQueue.postRunnable(() -> {
                try {
                    completeLegacyMigration(appContext, path);
                } catch (Throwable ignored) {
                } finally {
                    synchronized (WallpaperLibraryStore.class) {
                        migrationInFlight = false;
                    }
                }
            });
        } catch (Throwable t) {
            FileLog.e("AminGram wallpapers: migration failed, will retry", t);
        }
    }

    /** Second half of the legacy migration, run on stageQueue (see
     *  {@link #ensureMigrated}). Same semantics as the original
     *  inline copy: success imports the file as item #1 ("Current",
     *  active) and marks the migration done; failure marks nothing,
     *  so the next ensureMigrated() call retries. */
    private static synchronized void completeLegacyMigration(Context context, String legacyPath) {
        try {
            if (prefs(context).getBoolean(KEY_MIGRATED, false)) {
                return;
            }
            List<Entry> items = loadItems(context);
            if (!items.isEmpty()) {
                // The user picked/adopted something while the copy
                // was queued — trust the library, just mark done.
                prefs(context).edit().putBoolean(KEY_MIGRATED, true).apply();
                syncLegacy(context, items, loadActiveId(context));
                return;
            }
            File legacyFile = legacyPath == null ? null : new File(legacyPath);
            if (!fileUsable(legacyFile)) {
                // Vanished between queueing and running: start
                // empty, like the no-legacy-file branch.
                save(context, items, loadActiveId(context));
                prefs(context).edit().putBoolean(KEY_MIGRATED, true).apply();
                return;
            }
            Entry migrated = copyIntoLibrary(context, new FileInputStream(legacyFile),
                    extOf(legacyPath), "Current", items);
            if (migrated == null) {
                // Copy failed: legacy pref + file untouched, flag
                // NOT set — the next call retries the migration.
                FileLog.e("AminGram wallpapers: legacy migration copy failed, will retry");
                return;
            }
            save(context, items, migrated.id);
            prefs(context).edit().putBoolean(KEY_MIGRATED, true).apply();
        } catch (Throwable t) {
            FileLog.e("AminGram wallpapers: migration failed, will retry", t);
        }
    }

    /** Streams {@code in} into a new durable library file and appends
     *  its entry to {@code items} (in memory only — the caller saves).
     *  Takes ownership of {@code in} (always closed). Returns null on
     *  any failure, with the partial file removed and {@code items}
     *  unchanged. */
    private static Entry copyIntoLibrary(Context context, InputStream in, String ext,
                                         String displayName, List<Entry> items) {
        File out = null;
        OutputStream os = null;
        try {
            File dir = wallpapersDir(context);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            String id = generateId();
            String fileName = id + "." + sanitizeExt(ext);
            out = new File(dir, fileName);
            os = new FileOutputStream(out);
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) > 0) {
                os.write(buffer, 0, read);
                total += read;
            }
            os.close();
            os = null;
            if (total == 0) {
                out.delete();
                return null;
            }
            String name = displayName == null || displayName.trim().isEmpty()
                    ? "Wallpaper" : displayName.trim();
            Entry entry = new Entry(id, name, fileName, System.currentTimeMillis());
            items.add(entry);
            return entry;
        } catch (Throwable t) {
            FileLog.e("AminGram wallpapers: copy into library failed", t);
            if (out != null) {
                out.delete();
            }
            return null;
        } finally {
            // The output stream joins the input stream in the
            // finally (audit P1-5): previously it was closed only
            // on the success path and leaked on any read/write
            // failure.
            if (os != null) {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** All items, in addedAt (insertion) order. */
    public static synchronized List<Entry> list(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }
        ensureMigrated(context);
        return loadItems(context);
    }

    public static synchronized int count(Context context) {
        if (context == null) {
            return 0;
        }
        ensureMigrated(context);
        return loadItems(context).size();
    }

    public static synchronized Entry getById(Context context, String id) {
        if (context == null || id == null) {
            return null;
        }
        ensureMigrated(context);
        return findById(loadItems(context), id);
    }

    /**
     * The active entry, or null. Self-healing: an active id that no
     * longer resolves to an item, or whose file went missing/empty
     * (deleted outside the app), is cleared (and the legacy pref
     * re-synced) instead of being handed out as a dangling choice.
     */
    public static synchronized Entry getActive(Context context) {
        if (context == null) {
            return null;
        }
        ensureMigrated(context);
        List<Entry> items = loadItems(context);
        String activeId = loadActiveId(context);
        Entry active = findById(items, activeId);
        if (activeId != null && (active == null || !fileUsable(active.file(context)))) {
            save(context, items, null);
            return null;
        }
        return active;
    }

    /** Absolute path of the active item's file, or null when there
     *  is no (usable) active wallpaper. */
    public static synchronized String getActivePath(Context context) {
        Entry active = getActive(context);
        if (active != null && context != null) {
            try {
                return active.file(context).getAbsolutePath();
            } catch (Throwable t) {
                return null;
            }
        }
        // Migration still pending (audit P1-4): the library copy is
        // being made on stageQueue — serve the legacy file directly
        // until it lands, so the background never blinks out (and
        // the main thread never copies) on the first launch.
        if (context != null) {
            try {
                if (!prefs(context).getBoolean(KEY_MIGRATED, false)) {
                    String legacyPath = context.getSharedPreferences(LEGACY_PREFS,
                            Context.MODE_PRIVATE).getString(LEGACY_KEY_PATH, null);
                    File legacyFile = legacyPath == null ? null : new File(legacyPath);
                    if (fileUsable(legacyFile)) {
                        return legacyFile.getAbsolutePath();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Makes an item the active wallpaper (+ legacy sync). False —
     *  changing nothing — when the item is unknown or its file is
     *  missing/empty. */
    public static synchronized boolean apply(Context context, String id) {
        if (context == null || id == null) {
            return false;
        }
        ensureMigrated(context);
        List<Entry> items = loadItems(context);
        Entry entry = findById(items, id);
        if (entry == null || !fileUsable(entry.file(context))) {
            return false;
        }
        save(context, items, id);
        return true;
    }

    /** Deactivates the current wallpaper; the library items stay. */
    public static synchronized void clearActive(Context context) {
        if (context == null) {
            return;
        }
        ensureMigrated(context);
        save(context, loadItems(context), null);
    }

    /**
     * Copies a stream into the library as a new item. Does NOT
     * activate it (callers decide — the picker flow applies right
     * after). Takes ownership of {@code in} (always closed).
     * Returns the new entry, or null on failure (nothing indexed,
     * no partial file kept).
     */
    public static synchronized Entry addFromStream(Context context, InputStream in,
                                                   String ext, String displayName) {
        if (context == null || in == null) {
            return null;
        }
        ensureMigrated(context);
        List<Entry> items = loadItems(context);
        Entry entry = copyIntoLibrary(context, in, ext, displayName, items);
        if (entry == null) {
            return null;
        }
        save(context, items, loadActiveId(context));
        return entry;
    }

    /** Copies an existing file into the library (the original is
     *  never moved or deleted). Does NOT activate the new item. */
    public static synchronized Entry addFromFile(Context context, File source,
                                                 String displayName) {
        if (context == null || !fileUsable(source)) {
            return null;
        }
        try {
            return addFromStream(context, new FileInputStream(source),
                    extOf(source.getName()), displayName);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Removes an item, its file and its cached thumbnail. Deleting
     *  the ACTIVE item deactivates cleanly (legacy pref synced). */
    public static synchronized boolean delete(Context context, String id) {
        if (context == null || id == null) {
            return false;
        }
        ensureMigrated(context);
        List<Entry> items = loadItems(context);
        Entry entry = findById(items, id);
        if (entry == null) {
            return false;
        }
        items.remove(entry);
        String activeId = loadActiveId(context);
        if (id.equals(activeId)) {
            activeId = null;
        }
        save(context, items, activeId);
        try {
            entry.file(context).delete();
        } catch (Throwable ignored) {
        }
        try {
            thumbFile(context, id).delete();
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** Renames an item (trimmed). Empty names are rejected. */
    public static synchronized boolean rename(Context context, String id, String name) {
        if (context == null || id == null || name == null) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        ensureMigrated(context);
        List<Entry> items = loadItems(context);
        for (int i = 0; i < items.size(); i++) {
            Entry e = items.get(i);
            if (e.id.equals(id)) {
                items.set(i, new Entry(e.id, trimmed, e.fileName, e.addedAt));
                save(context, items, loadActiveId(context));
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Backup (Stage 1): the library METADATA only — the same
    // {items, active_id} shape the prefs hold. The wallpaper files
    // themselves are not embedded (consistent with the backup's
    // long-standing videobg rule: paths/metadata travel, media
    // files do not); restored entries whose files are absent
    // self-heal via getActive()/fileUsable checks.
    // ------------------------------------------------------------------

    public static synchronized JSONObject exportBackup(Context context) {
        JSONObject out = new JSONObject();
        if (context == null) {
            return out;
        }
        ensureMigrated(context);
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : loadItems(context)) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("displayName", e.displayName);
                o.put("fileName", e.fileName);
                o.put("addedAt", e.addedAt);
                arr.put(o);
            }
            out.put("items", arr);
            String activeId = loadActiveId(context);
            if (activeId != null) {
                out.put("active_id", activeId);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Replaces the library index with a backup's metadata (the
     *  normal save path, so the legacy path pref is re-synced too).
     *  Malformed entries are skipped exactly as in loadItems. */
    public static synchronized void importBackup(Context context, JSONObject backup) {
        if (context == null || backup == null) {
            return;
        }
        ensureMigrated(context);
        List<Entry> items = new ArrayList<>();
        JSONArray arr = backup.optJSONArray("items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String id = o.optString("id", "");
                String fileName = o.optString("fileName", "");
                if (id.isEmpty() || fileName.isEmpty()) {
                    continue;
                }
                items.add(new Entry(id, o.optString("displayName", "Wallpaper"),
                        fileName, o.optLong("addedAt", 0)));
            }
        }
        String activeId = backup.optString("active_id", "");
        save(context, items, activeId.isEmpty() ? null : activeId);
    }

    /**
     * Compatibility bridge for path-based callers (the old
     * VideoBackgroundHelper.setVideoPath, backup import): if
     * {@code path} is already an indexed item's file, that item is
     * applied; otherwise the file is copied into the library and the
     * copy applied. On copy failure (or a missing/empty file) it
     * returns false and changes nothing.
     */
    public static synchronized boolean adoptPath(Context context, String path,
                                                 String displayName) {
        if (context == null || path == null || path.isEmpty()) {
            return false;
        }
        ensureMigrated(context);
        List<Entry> items = loadItems(context);
        for (Entry e : items) {
            try {
                if (e.file(context).getAbsolutePath().equals(path)) {
                    return apply(context, e.id);
                }
            } catch (Throwable ignored) {
            }
        }
        File source = new File(path);
        if (!fileUsable(source)) {
            return false;
        }
        Entry added = addFromFile(context, source,
                displayName == null || displayName.trim().isEmpty()
                        ? source.getName() : displayName);
        if (added == null) {
            return false;
        }
        return apply(context, added.id);
    }
}
