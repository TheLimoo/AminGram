package org.telegram.messenger.amin.core;

import android.content.Context;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tiny persistent string-list store backed by SharedPreferences + JSONArray.
 * Used by Keyword Radar (hits), the Anti-Delete / Edit History
 * stores and JS plugin logs.
 * Lists are capped; newest entries are kept.
 *
 * Phase A optimization (A-07, A-20):
 * - In-memory cache per (prefsName, key) eliminates repeated JSONArray
 *   re-parsing on Hub UI binds/rebuilds and hot-path reads.
 * - addUniqueBatch uses a HashSet for true O(B + kept) deduplication.
 * - JSON serialization happens outside the SharedPreferences write call.
 */
public final class VaultStore {

    private static final Map<String, List<String>> CACHE = new HashMap<>();

    private VaultStore() {
    }

    private static String cacheKey(String prefsName, String key) {
        return prefsName + "#" + key;
    }

    private static List<String> loadLocked(Context context, String prefsName, String key) {
        String ck = cacheKey(prefsName, key);
        List<String> cached = CACHE.get(ck);
        if (cached != null) {
            return cached;
        }
        List<String> loaded = new ArrayList<>();
        if (context != null) {
            String raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .getString(key, null);
            if (raw != null) {
                try {
                    JSONArray arr = new JSONArray(raw);
                    for (int i = 0; i < arr.length(); i++) {
                        loaded.add(arr.optString(i));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        CACHE.put(ck, loaded);
        return loaded;
    }

    public static synchronized List<String> getAll(Context context, String prefsName, String key) {
        return new ArrayList<>(loadLocked(context, prefsName, key));
    }

    /** Fast cached count without copying the list or re-parsing JSON (A-20). */
    public static synchronized int count(Context context, String prefsName, String key) {
        return loadLocked(context, prefsName, key).size();
    }

    /** Appends value if not already present; trims the list to cap entries. */
    public static void addUnique(Context context, String prefsName, String key, String value, int cap) {
        if (context == null || value == null || value.isEmpty()) {
            return;
        }
        List<String> snapshot;
        synchronized (VaultStore.class) {
            List<String> items = loadLocked(context, prefsName, key);
            if (items.contains(value)) {
                return;
            }
            items.add(value);
            while (items.size() > cap) {
                items.remove(0);
            }
            snapshot = new ArrayList<>(items);
        }
        saveSnapshot(context, prefsName, key, snapshot);
    }

    /** Batch form of {@link #addUnique} for scan loops (A-07):
     *  uses a HashSet for true O(B + kept) deduplication and
     *  serializes JSON outside the synchronized block. */
    public static void addUniqueBatch(Context context, String prefsName, String key, List<String> values, int cap) {
        if (context == null || values == null || values.isEmpty()) {
            return;
        }
        List<String> snapshot = null;
        synchronized (VaultStore.class) {
            List<String> items = loadLocked(context, prefsName, key);
            Set<String> seen = new HashSet<>(items);
            boolean changed = false;
            for (String value : values) {
                if (value != null && !value.isEmpty() && seen.add(value)) {
                    items.add(value);
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
            while (items.size() > cap) {
                items.remove(0);
            }
            snapshot = new ArrayList<>(items);
        }
        saveSnapshot(context, prefsName, key, snapshot);
    }

    /** Replaces the whole stored list (used by stores with upsert semantics). */
    public static void putAll(Context context, String prefsName, String key, List<String> items) {
        if (context == null) {
            return;
        }
        List<String> snapshot = items == null ? new ArrayList<>() : new ArrayList<>(items);
        synchronized (VaultStore.class) {
            CACHE.put(cacheKey(prefsName, key), new ArrayList<>(snapshot));
        }
        saveSnapshot(context, prefsName, key, snapshot);
    }

    public static void clear(Context context, String prefsName, String key) {
        synchronized (VaultStore.class) {
            CACHE.remove(cacheKey(prefsName, key));
        }
        if (context != null) {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .edit().remove(key).apply();
        }
    }

    private static void saveSnapshot(Context context, String prefsName, String key, List<String> snapshot) {
        JSONArray arr = new JSONArray();
        for (String item : snapshot) {
            arr.put(item);
        }
        String serialized = arr.toString();
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().putString(key, serialized).apply();
    }
}
