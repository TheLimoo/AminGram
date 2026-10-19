package org.telegram.messenger.amin.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-Saver v3 store. v1/v2 kept only a text log line per save
 * (dialog|id|date|name in prefs "amingram_autosaver"/"items"), which is
 * why saved photos could not be viewed. v3 records rich entries
 * (key "items_v2", a JSONArray of objects) including the resolved
 * media-cache path of the downloaded file:
 * {dialog, id, date, kind ("photo"|"document"), name, path, attach}.
 *
 * The path is FileLoader.getPathToMessage() at capture time, which for
 * TTL media resolves into the cache directory. resolveFile() also
 * falls back to scanning Telegram's media directories for the attach
 * file name, in case the cache layout moved the file.
 *
 * Legacy v1/v2 log lines are still readable (path == null) so the hub
 * count and the old log viewer keep working.
 */
public final class AutoSaverStore {

    public static final String PREFS = "amingram_autosaver";
    public static final String ITEMS_V2_KEY = "items_v2";
    private static final int CAP = 500;

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
    }

    private AutoSaverStore() {
    }

    public static void record(Context context, Entry entry) {
        List<Entry> entries = getEntries(context);
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry old = entries.get(i);
            if (old.dialogId == entry.dialogId && old.id == entry.id) {
                entries.remove(i);
            }
        }
        entries.add(entry);
        while (entries.size() > CAP) {
            entries.remove(0);
        }
        save(context, entries);
    }

    public static List<Entry> getEntries(Context context) {
        List<Entry> out = new ArrayList<>();
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
                }
            } catch (Exception ignored) {
            }
        }
        // Legacy pipe-format log lines (no file path was recorded).
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
                boolean dup = false;
                for (Entry v2 : out) {
                    if (v2.dialogId == e.dialogId && v2.id == e.id) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    out.add(e);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static int count(Context context) {
        return getEntries(context).size();
    }

    public static void remove(Context context, Entry target) {
        List<Entry> entries = getEntries(context);
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.dialogId == target.dialogId && e.id == target.id) {
                entries.remove(i);
                changed = true;
            }
        }
        if (changed) {
            saveV2Only(context, entries);
            // Also scrub the legacy log line, or the entry would reappear.
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

    /** Resolves the entry's file: recorded path first, then a scan of
     *  Telegram's media directories for the attach file name. */
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

    private static void save(Context context, List<Entry> entries) {
        saveV2Only(context, entries);
    }

    private static void saveV2Only(Context context, List<Entry> entries) {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            // Legacy entries (path-less) stay represented by the old log
            // key; only v2 entries are written to items_v2.
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
