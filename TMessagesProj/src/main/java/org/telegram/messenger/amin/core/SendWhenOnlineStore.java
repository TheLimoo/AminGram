package org.telegram.messenger.amin.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Send When Online (v1.3): queued messages that fire the moment their
 * target user comes online.
 *
 * Storage: prefs "amingram_swo", key "items" — a JSONArray of objects:
 * {peer, name, text, date, state ("pending"|"sent"), sentDate}.
 *
 * The trigger is purely event-driven: AminHooks watches the raw update
 * stream for TL_updateUserStatus updates (the same updates Telegram
 * itself processes during normal sync, foreground or background) and,
 * when a pending target reports online, sends via SendMessagesHelper.
 * No polling, no timers, no extra threads. Android bound (documented
 * in MODS.md): if the app process is dead / force-stopped, the send
 * fires on the next launch's sync instead.
 */
public final class SendWhenOnlineStore {

    public static final String PREFS = "amingram_swo";
    private static final String KEY_ITEMS = "items";
    private static final int CAP = 100;

    public static final class Entry {
        public long peer;
        public String name;
        public String text;
        public int date;
        public String state;
        public int sentDate;

        public boolean isPending() {
            return !"sent".equals(state);
        }
    }

    private SendWhenOnlineStore() {
    }

    public static List<Entry> getAll(Context context) {
        List<Entry> out = new ArrayList<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, null);
        if (raw == null) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Entry e = new Entry();
                e.peer = o.optLong("peer");
                e.name = o.optString("name", "");
                e.text = o.optString("text", "");
                e.date = o.optInt("date");
                e.state = o.optString("state", "pending");
                e.sentDate = o.optInt("sentDate");
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void saveAll(Context context, List<Entry> entries) {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            try {
                JSONObject o = new JSONObject();
                o.put("peer", e.peer);
                o.put("name", e.name == null ? "" : e.name);
                o.put("text", e.text == null ? "" : e.text);
                o.put("date", e.date);
                o.put("state", e.state == null ? "pending" : e.state);
                o.put("sentDate", e.sentDate);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    public static void add(Context context, long peer, String name, String text) {
        List<Entry> entries = getAll(context);
        Entry e = new Entry();
        e.peer = peer;
        e.name = name;
        e.text = text;
        e.date = (int) (System.currentTimeMillis() / 1000L);
        e.state = "pending";
        entries.add(e);
        while (entries.size() > CAP) {
            entries.remove(0);
        }
        saveAll(context, entries);
    }

    public static int pendingCount(Context context) {
        int n = 0;
        for (Entry e : getAll(context)) {
            if (e.isPending()) {
                n++;
            }
        }
        return n;
    }

    public static List<Entry> pendingFor(Context context, long peer) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : getAll(context)) {
            if (e.peer == peer && e.isPending()) {
                out.add(e);
            }
        }
        return out;
    }

    /** Marks all pending entries for the peer as sent. Returns how many. */
    public static int markSent(Context context, long peer) {
        List<Entry> entries = getAll(context);
        int now = (int) (System.currentTimeMillis() / 1000L);
        int changed = 0;
        for (Entry e : entries) {
            if (e.peer == peer && e.isPending()) {
                e.state = "sent";
                e.sentDate = now;
                changed++;
            }
        }
        if (changed > 0) {
            saveAll(context, entries);
        }
        return changed;
    }

    // Lightweight change listeners so an open hub can refresh itself
    // (e.g. right after a queued message fired). Listeners run inline.
    private static final java.util.List<Runnable> LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    public static void notifyChanged() {
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void remove(Context context, Entry target) {
        List<Entry> entries = getAll(context);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.peer == target.peer && e.date == target.date
                    && e.text != null && e.text.equals(target.text)) {
                entries.remove(i);
                break;
            }
        }
        saveAll(context, entries);
    }
}
