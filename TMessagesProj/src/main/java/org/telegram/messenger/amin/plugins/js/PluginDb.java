package org.telegram.messenger.amin.plugins.js;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-plugin KV database (Platform v2 Phase B, blueprint §1.4).
 *
 * Phase A optimization (A-15):
 * - Enforces a per-plugin total quota (MAX_PLUGIN_TOTAL_CHARS = 8 MB,
 *   MAX_PLUGIN_ROWS = 2000) on new inserts while grandfathering existing
 *   keys on update.
 * - Pushes prefix filtering in keys() into SQLite LIKE queries.
 * - Adds transactional putMulti() for batched writes.
 */
public final class PluginDb {

    public static final int MAX_VALUE_CHARS = 256 * 1024;
    public static final int MAX_NAME_CHARS = 128;
    public static final long MAX_PLUGIN_TOTAL_CHARS = 8L * 1024 * 1024;
    public static final int MAX_PLUGIN_ROWS = 2000;

    private static SQLiteDatabase db;

    private PluginDb() {
    }

    private static synchronized SQLiteDatabase open() {
        if (db != null && db.isOpen()) {
            return db;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        try {
            File file = new File(context.getFilesDir(), "amingram_plugin_db.db");
            db = SQLiteDatabase.openOrCreateDatabase(file, null);
            db.execSQL("CREATE TABLE IF NOT EXISTS kv ("
                    + "plugin_id TEXT NOT NULL, ns TEXT NOT NULL, key TEXT NOT NULL, "
                    + "value TEXT NOT NULL, updated_at INTEGER NOT NULL, "
                    + "PRIMARY KEY (plugin_id, ns, key))");
            return db;
        } catch (Throwable t) {
            db = null;
            return null;
        }
    }

    static boolean validName(String name) {
        return name != null && !name.isEmpty() && name.length() <= MAX_NAME_CHARS;
    }

    public static synchronized String get(String pluginId, String ns, String key) {
        if (!validName(ns) || !validName(key)) {
            return null;
        }
        SQLiteDatabase handle = open();
        if (handle == null) {
            return null;
        }
        Cursor cursor = null;
        try {
            cursor = handle.query("kv", new String[]{"value"},
                    "plugin_id = ? AND ns = ? AND key = ?",
                    new String[]{pluginId, ns, key}, null, null, null);
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static boolean checkQuotaLocked(SQLiteDatabase handle, String pluginId,
                                            String ns, String key, int newLen) {
        Cursor c = null;
        try {
            // Grandfather updates to an existing key if within MAX_VALUE_CHARS
            c = handle.query("kv", new String[]{"LENGTH(value)"},
                    "plugin_id = ? AND ns = ? AND key = ?",
                    new String[]{pluginId, ns, key}, null, null, null);
            int existingLen = -1;
            if (c.moveToFirst()) {
                existingLen = c.getInt(0);
            }
            c.close();
            c = null;
            c = handle.rawQuery(
                    "SELECT COUNT(*), COALESCE(SUM(LENGTH(value)), 0) FROM kv WHERE plugin_id = ?",
                    new String[]{pluginId});
            if (c.moveToFirst()) {
                int count = c.getInt(0);
                long totalChars = c.getLong(1);
                if (existingLen < 0 && count >= MAX_PLUGIN_ROWS) {
                    return false;
                }
                long projected = totalChars - (existingLen >= 0 ? existingLen : 0) + newLen;
                if (projected > MAX_PLUGIN_TOTAL_CHARS) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return true;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    public static synchronized boolean put(String pluginId, String ns, String key, String json) {
        if (!validName(ns) || !validName(key) || json == null
                || json.length() > MAX_VALUE_CHARS) {
            return false;
        }
        SQLiteDatabase handle = open();
        if (handle == null) {
            return false;
        }
        try {
            if (!checkQuotaLocked(handle, pluginId, ns, key, json.length())) {
                return false;
            }
            ContentValues values = new ContentValues();
            values.put("plugin_id", pluginId);
            values.put("ns", ns);
            values.put("key", key);
            values.put("value", json);
            values.put("updated_at", System.currentTimeMillis());
            return handle.insertWithOnConflict("kv", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Batched transactional write of multiple keys in one namespace (A-15). */
    public static synchronized boolean putMulti(String pluginId, String ns, Map<String, String> entries) {
        if (!validName(ns) || entries == null || entries.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (!validName(e.getKey()) || e.getValue() == null
                    || e.getValue().length() > MAX_VALUE_CHARS) {
                return false;
            }
        }
        SQLiteDatabase handle = open();
        if (handle == null) {
            return false;
        }
        try {
            handle.beginTransaction();
            try {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, String> e : entries.entrySet()) {
                    if (!checkQuotaLocked(handle, pluginId, ns, e.getKey(), e.getValue().length())) {
                        return false;
                    }
                    ContentValues values = new ContentValues();
                    values.put("plugin_id", pluginId);
                    values.put("ns", ns);
                    values.put("key", e.getKey());
                    values.put("value", e.getValue());
                    values.put("updated_at", now);
                    if (handle.insertWithOnConflict("kv", null, values,
                            SQLiteDatabase.CONFLICT_REPLACE) < 0) {
                        return false;
                    }
                }
                handle.setTransactionSuccessful();
                return true;
            } finally {
                handle.endTransaction();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized boolean del(String pluginId, String ns, String key) {
        if (!validName(ns) || !validName(key)) {
            return false;
        }
        SQLiteDatabase handle = open();
        if (handle == null) {
            return false;
        }
        try {
            return handle.delete("kv", "plugin_id = ? AND ns = ? AND key = ?",
                    new String[]{pluginId, ns, key}) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String escapeLikePrefix(String prefix) {
        StringBuilder sb = new StringBuilder(prefix.length() + 4);
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('%');
        return sb.toString();
    }

    /** Keys in one namespace, prefix-filtered in SQLite and sorted (A-15). */
    public static synchronized List<String> keys(String pluginId, String ns, String prefix) {
        List<String> out = new ArrayList<>();
        if (!validName(ns)) {
            return out;
        }
        SQLiteDatabase handle = open();
        if (handle == null) {
            return out;
        }
        Cursor cursor = null;
        try {
            if (prefix == null || prefix.isEmpty()) {
                cursor = handle.query("kv", new String[]{"key"},
                        "plugin_id = ? AND ns = ?",
                        new String[]{pluginId, ns}, null, null, "key ASC");
            } else {
                cursor = handle.query("kv", new String[]{"key"},
                        "plugin_id = ? AND ns = ? AND key LIKE ? ESCAPE '\\'",
                        new String[]{pluginId, ns, escapeLikePrefix(prefix)},
                        null, null, "key ASC");
            }
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                if (prefix == null || prefix.isEmpty() || key.startsWith(prefix)) {
                    out.add(key);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return out;
    }

    public static synchronized void clear(String pluginId, String ns) {
        if (!validName(ns)) {
            return;
        }
        SQLiteDatabase handle = open();
        if (handle == null) {
            return;
        }
        try {
            handle.delete("kv", "plugin_id = ? AND ns = ?",
                    new String[]{pluginId, ns});
        } catch (Throwable ignored) {
        }
    }

    public static synchronized List<String> namespaces(String pluginId) {
        List<String> out = new ArrayList<>();
        SQLiteDatabase handle = open();
        if (handle == null) {
            return out;
        }
        Cursor cursor = null;
        try {
            cursor = handle.query(true, "kv", new String[]{"ns"},
                    "plugin_id = ?", new String[]{pluginId}, null, null, "ns ASC", null);
            while (cursor.moveToNext()) {
                out.add(cursor.getString(0));
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return out;
    }

    public static synchronized List<String[]> dumpAll() {
        List<String[]> out = new ArrayList<>();
        SQLiteDatabase handle = open();
        if (handle == null) {
            return out;
        }
        Cursor cursor = null;
        try {
            cursor = handle.query("kv",
                    new String[]{"plugin_id", "ns", "key", "value"},
                    null, null, null, null, "plugin_id ASC, ns ASC, key ASC");
            while (cursor.moveToNext()) {
                out.add(new String[]{cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3)});
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return out;
    }

    public static synchronized void restoreAll(List<String[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        SQLiteDatabase handle = open();
        if (handle == null) {
            return;
        }
        try {
            Set<String> pluginIds = new HashSet<>();
            for (String[] row : rows) {
                if (row != null && row.length == 4 && row[0] != null) {
                    pluginIds.add(row[0]);
                }
            }
            handle.beginTransaction();
            try {
                for (String pluginId : pluginIds) {
                    handle.delete("kv", "plugin_id = ?", new String[]{pluginId});
                }
                long now = System.currentTimeMillis();
                for (String[] row : rows) {
                    if (row == null || row.length != 4 || !validName(row[1])
                            || !validName(row[2]) || row[3] == null
                            || row[3].length() > MAX_VALUE_CHARS) {
                        continue;
                    }
                    ContentValues values = new ContentValues();
                    values.put("plugin_id", row[0]);
                    values.put("ns", row[1]);
                    values.put("key", row[2]);
                    values.put("value", row[3]);
                    values.put("updated_at", now);
                    handle.insertWithOnConflict("kv", null, values,
                            SQLiteDatabase.CONFLICT_REPLACE);
                }
                handle.setTransactionSuccessful();
            } finally {
                handle.endTransaction();
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void deleteAllForPlugin(String pluginId) {
        SQLiteDatabase handle = open();
        if (handle == null) {
            return;
        }
        try {
            handle.delete("kv", "plugin_id = ?", new String[]{pluginId});
        } catch (Throwable ignored) {
        }
    }
}
