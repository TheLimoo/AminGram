package org.telegram.messenger.amin.core;

import android.content.Context;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny persistent string-list store backed by SharedPreferences + JSONArray.
 * Used by Keyword Radar (hits), the Anti-Delete / Edit History
 * stores and JS plugin logs.
 * Lists are capped; newest entries are kept.
 */
public final class VaultStore {

    private VaultStore() {
    }

    public static List<String> getAll(Context context, String prefsName, String key) {
        List<String> out = new ArrayList<>();
        String raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(key, null);
        if (raw == null) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.optString(i));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Appends value if not already present; trims the list to cap entries. */
    public static synchronized void addUnique(Context context, String prefsName, String key, String value, int cap) {
        if (value == null || value.isEmpty()) {
            return;
        }
        List<String> items = getAll(context, prefsName, key);
        if (items.contains(value)) {
            return;
        }
        items.add(value);
        while (items.size() > cap) {
            items.remove(0);
        }
        save(context, prefsName, key, items);
    }

    /** Batch form of {@link #addUnique} for scan loops (pillar-4
     *  audit S2): ONE synchronized read-modify-write for the whole
     *  batch instead of one per hit — a live scan over a large chat
     *  no longer rewrites the full JSON array once per matched
     *  message (O(B × kept) → O(B + kept)). Same record format, cap
     *  and trim semantics; duplicates inside the batch itself are
     *  skipped. */
    public static synchronized void addUniqueBatch(Context context, String prefsName, String key, List<String> values, int cap) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> items = getAll(context, prefsName, key);
        boolean changed = false;
        for (String value : values) {
            if (value != null && !value.isEmpty() && !items.contains(value)) {
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
        save(context, prefsName, key, items);
    }

    /** Replaces the whole stored list (used by stores with upsert semantics). */
    public static synchronized void putAll(Context context, String prefsName, String key, List<String> items) {
        save(context, prefsName, key, items);
    }

    public static synchronized void clear(Context context, String prefsName, String key) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().remove(key).apply();
    }

    private static void save(Context context, String prefsName, String key, List<String> items) {
        JSONArray arr = new JSONArray();
        for (String item : items) {
            arr.put(item);
        }
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().putString(key, arr.toString()).apply();
    }
}
