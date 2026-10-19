package org.telegram.messenger.amin.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hidden Chats (v1.3): the set of dialog ids hidden from the main chat
 * list, plus the PIN that guards them.
 *
 * Storage: prefs "amingram_hidden" — "ids" (JSONArray of dialog ids as
 * strings), "pin_hash" (SHA-256 hex of the PIN with a static salt; the
 * PIN itself is never stored). The unlocked state is memory-only: it
 * resets on every app restart, which is the privacy point.
 *
 * Filtering itself lives in AminHooks.filterHiddenDialogs (fed by the
 * DialogsActivity.getDialogsArray patch): while locked and the module
 * is enabled, hidden dialogs are removed from the lists the main UI
 * shows. Telegram's global search uses a different path and is NOT
 * filtered (documented in MODS.md).
 */
public final class HiddenChatsStore {

    public static final String PREFS = "amingram_hidden";
    private static final String KEY_IDS = "ids";
    private static final String KEY_PIN = "pin_hash";
    private static final String SALT = "amingram-hidden-v1";

    private static volatile boolean unlocked;

    private HiddenChatsStore() {
    }

    public static List<Long> getHiddenIds(Context context) {
        List<Long> out = new ArrayList<>();
        String raw = prefs(context).getString(KEY_IDS, null);
        if (raw == null) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Long.parseLong(arr.optString(i)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static Set<Long> getHiddenIdSet(Context context) {
        return new HashSet<>(getHiddenIds(context));
    }

    public static boolean isHidden(Context context, long dialogId) {
        return getHiddenIdSet(context).contains(dialogId);
    }

    public static void setHidden(Context context, long dialogId, boolean hidden) {
        List<Long> ids = getHiddenIds(context);
        if (hidden) {
            if (!ids.contains(dialogId)) {
                ids.add(dialogId);
            }
        } else {
            ids.remove(Long.valueOf(dialogId));
        }
        JSONArray arr = new JSONArray();
        for (Long id : ids) {
            arr.put(String.valueOf(id));
        }
        prefs(context).edit().putString(KEY_IDS, arr.toString()).apply();
    }

    public static void replaceAll(Context context, List<Long> ids, String pinHash) {
        JSONArray arr = new JSONArray();
        for (Long id : ids) {
            arr.put(String.valueOf(id));
        }
        SharedPreferences.Editor edit = prefs(context).edit();
        edit.putString(KEY_IDS, arr.toString());
        if (pinHash != null) {
            edit.putString(KEY_PIN, pinHash);
        }
        edit.apply();
    }

    public static boolean hasPin(Context context) {
        String hash = prefs(context).getString(KEY_PIN, null);
        return hash != null && !hash.isEmpty();
    }

    public static String getPinHash(Context context) {
        return prefs(context).getString(KEY_PIN, null);
    }

    public static void setPin(Context context, String pin) {
        prefs(context).edit().putString(KEY_PIN, hashPin(pin)).apply();
    }

    public static boolean checkPin(Context context, String pin) {
        String stored = getPinHash(context);
        return stored != null && stored.equals(hashPin(pin));
    }

    public static String hashPin(String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest((SALT + ":" + pin).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    public static void setUnlocked(boolean value) {
        unlocked = value;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
