package org.telegram.messenger.amin.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Auto-Saver v3 store.
 *
 * Phase A optimization (A-10, A-20):
 * - Caches parsed entries in memory so getEntries() / count() do not
 *   re-parse both JSON and legacy pipe lists on every call.
 * - Deletes durable files in filesDir/amin_autosaver/ when entries are
 *   evicted by CAP (500) or explicitly removed via remove(), preventing
 *   unbounded disk growth.
 */
public final class AutoSaverStore {

    public static final String PREFS = "amingram_autosaver";
    public static final String ITEMS_V2_KEY = "items_v2";
    private static final int CAP = 500;

    private static final Object LOCK = new Object();
    private static List<Entry> cachedEntries;

    public static final class Entry {
        public long dialogId;
        public int id;
        public int date;
        public String kind;
        public String name;
        public String path;
        public String attach;

        public boolean isVideo() {
            if (name != null) {
                String n = name.toLowerCase();
                return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") || n.endsWith(".webm");
            }
            return false;
        }

        private Entry copy() {
            Entry c = new Entry();
            c.dialogId = dialogId;
            c.id = id;
            c.date = date;
            c.kind = kind;
            c.name = name;
            c.path = path;
            c.attach = attach;
            return c;
        }
    }

    private AutoSaverStore() {
    }

    public static void record(Context context, Entry entry) {
        if (context == null || entry == null) {
            return;
        }
        List<Entry> evicted = null;
        synchronized (LOCK) {
            List<Entry> entries = ensureLoadedLocked(context);
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry old = entries.get(i);
                if (old.dialogId == entry.dialogId && old.id == entry.id) {
                    entries.remove(i);
                }
            }
            entries.add(entry.copy());
            while (entries.size() > CAP) {
                Entry old = entries.remove(0);
                if (evicted == null) {
                    evicted = new ArrayList<>();
                }
                evicted.add(old);
            }
            saveV2Only(context, entries);
        }
        if (evicted != null) {
            for (Entry old : evicted) {
                deleteDurableFileIfOwned(context, old);
            }
        }
        ensureDurableCopy(context, entry);
    }

    private static final String DURABLE_DIR_NAME = "amin_autosaver";

    public static File durableDir(Context context) {
        return new File(context.getFilesDir(), DURABLE_DIR_NAME);
    }

    private static void deleteDurableFileIfOwned(Context context, Entry entry) {
        if (context == null || entry == null) {
            return;
        }
        try {
            File dir = durableDir(context);
            String prefix = entry.dialogId + "_" + entry.id;
            if (entry.path != null && !entry.path.isEmpty()) {
                File f = new File(entry.path);
                File parent = f.getParentFile();
                if (parent != null && dir.getAbsolutePath().equals(parent.getAbsolutePath())
                        && f.getName().startsWith(prefix)) {
                    f.delete();
                }
            }
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    if (n.equals(prefix) || n.startsWith(prefix + ".")) {
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureDurableCopy(Context context, Entry entry) {
        if (context == null || entry == null
                || entry.path == null || entry.path.isEmpty()) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final long dialogId = entry.dialogId;
        final int id = entry.id;
        final String sourcePath = entry.path;
        Utilities.stageQueue.postRunnable(() -> {
            try {
                File source = new File(sourcePath);
                if (!source.isFile() || source.length() == 0) {
                    return;
                }
                File dir = durableDir(appContext);
                File parent = source.getParentFile();
                if (parent != null && dir.getAbsolutePath().equals(parent.getAbsolutePath())) {
                    return;
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    return;
                }
                String ext = "";
                String sourceName = source.getName();
                int dot = sourceName.lastIndexOf('.');
                if (dot > 0) {
                    String candidate = sourceName.substring(dot);
                    if (candidate.length() <= 6 && candidate.matches("\\.[A-Za-z0-9]+")) {
                        ext = candidate;
                    }
                }
                File target = new File(dir, dialogId + "_" + id + ext);
                if (!copyFile(source, target)) {
                    return;
                }
                synchronized (LOCK) {
                    List<Entry> entries = ensureLoadedLocked(appContext);
                    boolean changed = false;
                    for (Entry e : entries) {
                        if (e.dialogId == dialogId && e.id == id
                                && e.path != null && !e.path.isEmpty()) {
                            e.path = target.getAbsolutePath();
                            changed = true;
                        }
                    }
                    if (changed) {
                        saveV2Only(appContext, entries);
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static boolean copyFile(File source, File target) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(tmp);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.close();
            out = null;
            in.close();
            in = null;
            if (tmp.length() != source.length()) {
                tmp.delete();
                return false;
            }
            if (target.exists() && !target.delete()) {
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (Throwable t) {
            try {
                tmp.delete();
            } catch (Throwable ignored) {
            }
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static List<Entry> ensureLoadedLocked(Context context) {
        if (cachedEntries != null) {
            return cachedEntries;
        }
        List<Entry> out = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        if (context != null) {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(ITEMS_V2_KEY, null);
            if (raw != null) {
                try {
                    JSONArray arr = new JSONArray(raw);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) {
                            continue;
                        }
                        Entry e = new Entry();
                        e.dialogId = o.optLong("dialog");
                        e.id = o.optInt("id");
                        e.date = o.optInt("date");
                        e.kind = o.optString("kind", "photo");
                        e.name = o.optString("name", "");
                        e.path = o.optString("path", "");
                        e.attach = o.optString("attach", "");
                        out.add(e);
                        seenKeys.add(e.dialogId + ":" + e.id);
                    }
                } catch (Exception ignored) {
                }
            }
            for (String line : VaultStore.getAll(context, PREFS, MessagePipeline.AUTOSAVER_ITEMS_KEY)) {
                String[] parts = line.split("\\|", 4);
                if (parts.length < 3) {
                    continue;
                }
                try {
                    Entry e = new Entry();
                    e.dialogId = Long.parseLong(parts[0]);
                    e.id = Integer.parseInt(parts[1]);
                    e.date = Integer.parseInt(parts[2]);
                    String rest = parts.length == 4 ? parts[3] : "";
                    e.kind = "photo".equals(rest) ? "photo" : "document";
                    e.name = rest;
                    e.path = "";
                    e.attach = "";
                    if (seenKeys.add(e.dialogId + ":" + e.id)) {
                        out.add(e);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        cachedEntries = out;
        return cachedEntries;
    }

    public static List<Entry> getEntries(Context context) {
        synchronized (LOCK) {
            List<Entry> entries = ensureLoadedLocked(context);
            List<Entry> copy = new ArrayList<>(entries.size());
            for (Entry e : entries) {
                copy.add(e.copy());
            }
            return copy;
        }
    }

    public static int count(Context context) {
        synchronized (LOCK) {
            return ensureLoadedLocked(context).size();
        }
    }

    public static void remove(Context context, Entry target) {
        if (context == null || target == null) {
            return;
        }
        List<Entry> removed = new ArrayList<>();
        synchronized (LOCK) {
            List<Entry> entries = ensureLoadedLocked(context);
            boolean changed = false;
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = entries.get(i);
                if (e.dialogId == target.dialogId && e.id == target.id) {
                    removed.add(entries.remove(i));
                    changed = true;
                }
            }
            if (changed) {
                saveV2Only(context, entries);
                List<String> legacy = VaultStore.getAll(context, PREFS, MessagePipeline.AUTOSAVER_ITEMS_KEY);
                String prefix = target.dialogId + "|" + target.id + "|";
                boolean legacyChanged = false;
                for (int i = legacy.size() - 1; i >= 0; i--) {
                    if (legacy.get(i).startsWith(prefix)) {
                        legacy.remove(i);
                        legacyChanged = true;
                    }
                }
                if (legacyChanged) {
                    VaultStore.putAll(context, PREFS, MessagePipeline.AUTOSAVER_ITEMS_KEY, legacy);
                }
            }
        }
        for (Entry r : removed) {
            deleteDurableFileIfOwned(context, r);
        }
    }

    public static File resolveFile(Context context, int account, Entry entry) {
        if (entry.path != null && !entry.path.isEmpty()) {
            File f = new File(entry.path);
            if (f.exists() && f.length() > 0) {
                return f;
            }
        }
        if (entry.attach != null && !entry.attach.isEmpty()) {
            int[] dirs = {
                    FileLoader.MEDIA_DIR_CACHE,
                    FileLoader.MEDIA_DIR_IMAGE,
                    FileLoader.MEDIA_DIR_VIDEO,
                    FileLoader.MEDIA_DIR_DOCUMENT
            };
            for (int type : dirs) {
                try {
                    File dir = FileLoader.getInstance(account).getDirectory(type);
                    if (dir == null) {
                        continue;
                    }
                    File f = new File(dir, entry.attach);
                    if (f.exists() && f.length() > 0) {
                        return f;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static void saveV2Only(Context context, List<Entry> entries) {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            if (e.path == null) {
                continue;
            }
            try {
                JSONObject o = new JSONObject();
                o.put("dialog", e.dialogId);
                o.put("id", e.id);
                o.put("date", e.date);
                o.put("kind", e.kind == null ? "photo" : e.kind);
                o.put("name", e.name == null ? "" : e.name);
                o.put("path", e.path == null ? "" : e.path);
                o.put("attach", e.attach == null ? "" : e.attach);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(ITEMS_V2_KEY, arr.toString()).apply();
    }
}
