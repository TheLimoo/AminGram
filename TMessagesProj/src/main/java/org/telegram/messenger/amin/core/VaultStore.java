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
