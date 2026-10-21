package org.telegram.messenger.amin.plugins.js;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-plugin KV database (Platform v2 Phase B, blueprint §1.4):
 * the backing store for {@code amin.db}. One SQLite database for
 * all plugins ({@code filesDir/amingram_plugin_db.db}), one table
 * {@code kv(plugin_id, ns, key, value, updated_at)}; every access
 * is scoped by the plugin id taken from the bridge instance, so a
 * plugin can never name another plugin's rows. Values are JSON
 * text (the bridge converts at the boundary); the contract caps a
 * value at 256 KB and ns/key at 128 chars — enforced here as well
 * as at the bridge, and violations are refused (put returns
 * false), never thrown across the bridge.
 *
 * All calls happen on the plugin thread in practice (the bridge
 * is only reachable from plugin code); the single static handle is
 * additionally guarded by synchronizing on the class, which is
 * cheap at plugin call rates and removes any doubt.
 */
public final class PluginDb {

    public static final int MAX_VALUE_CHARS = 256 * 1024;
    public static final int MAX_NAME_CHARS = 128;

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

    /** The stored JSON text, or null when absent/invalid names. */
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

    /** Stores JSON text; false when a contract limit is violated
     *  or the database is unavailable. */
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

    /** Keys in one namespace, optionally prefix-filtered, sorted. */
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
            cursor = handle.query("kv", new String[]{"key"},
                    "plugin_id = ? AND ns = ?",
                    new String[]{pluginId, ns}, null, null, "key ASC");
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

    /** Removes every row of one namespace. */
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

    /** Distinct namespaces a plugin has rows in (storage inspector). */
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

    /** Drops ALL of a plugin's rows (true delete). */
    /** The plugin's namespace names, sorted (storage inspector). */
    public static synchronized List<String> namespaces(String pluginId) {
        List<String> out = new ArrayList<>();
        SQLiteDatabase handle = open();
        if (handle == null || pluginId == null) {
            return out;
        }
        Cursor cursor = null;
        try {
            cursor = handle.rawQuery(
                    "SELECT DISTINCT ns FROM kv WHERE plugin_id=? ORDER BY ns",
                    new String[]{pluginId});
            while (cursor != null && cursor.moveToNext()) {
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
