package org.telegram.messenger.amin.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Send When Online (v1.3): queued messages that fire the moment their
 * target user comes online.
 *
 * Storage: prefs "amingram_swo", key "items" — a JSONArray of objects:
 * {peer, name, text, date, state ("pending"|"sent"), sentDate,
 * account}. The account field (Stage 1, audit D2) records which
 * account queued the message; the flush in AminHooks filters by
 * (account, peer), so a status update arriving on account B can never
 * send a message queued under account A. Entries written before the
 * field existed have no "account" key and default AT LOAD to
 * UserConfig.selectedAccount (the store is re-read from prefs on
 * every call, so the default is evaluated fresh until any save
 * persists it alongside the entry).
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
        /** Account index (UserConfig slot) that queued this entry;
         *  -1 only transiently — getAll resolves legacy entries to
         *  UserConfig.selectedAccount. */
        public int account = -1;

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
                // Legacy entries (no "account" key) belong to the
                // account selected at load time.
                e.account = o.optInt("account", UserConfig.selectedAccount);
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
                o.put("account", e.account);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    public static void add(Context context, long peer, String name, String text) {
        add(context, UserConfig.selectedAccount, peer, name, text);
    }

    public static void add(Context context, int account, long peer, String name, String text) {
        List<Entry> entries = getAll(context);
        Entry e = new Entry();
        e.peer = peer;
        e.name = name;
        e.text = text;
        e.date = (int) (System.currentTimeMillis() / 1000L);
        e.state = "pending";
        e.account = account;
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

    /** Pending entries for one (account, peer) pair — the only list
     *  the flush may send from. Entries queued under a different
     *  account are invisible here even for the same peer. */
    public static List<Entry> pendingFor(Context context, int account, long peer) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : getAll(context)) {
            if (e.peer == peer && e.account == account && e.isPending()) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * Marks exactly the given entries as sent (Stage 1, audit D6).
     * The flush calls this ONLY with entries whose sendMessage()
     * hand-off already returned without throwing, so a silently
     * failed enqueue is never recorded as sent — it stays pending
     * and retries on the next online event. Matching is by
     * (peer, account, date, text) identity, the same identity
     * remove() uses. Crash-window semantics: the state flip is a
     * single SharedPreferences apply() of the whole list; if the
     * process dies after dispatch but before the apply lands, the
     * entries stay pending and may send once more on the next
     * online event — at-least-once by design, because the previous
     * ordering (mark first / blanket-mark) could silently LOSE a
     * queued message, which is worse than a rare duplicate.
     * Returns how many entries were flipped.
     */
    public static int markSent(Context context, List<Entry> dispatched) {
        if (dispatched == null || dispatched.isEmpty()) {
            return 0;
        }
        List<Entry> entries = getAll(context);
        int now = (int) (System.currentTimeMillis() / 1000L);
        int changed = 0;
        for (Entry e : entries) {
            if (!e.isPending()) {
                continue;
            }
            for (Entry d : dispatched) {
                if (e.peer == d.peer && e.account == d.account && e.date == d.date
                        && e.text != null && e.text.equals(d.text)) {
                    e.state = "sent";
                    e.sentDate = now;
                    changed++;
                    break;
                }
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
            if (e.peer == target.peer && e.account == target.account && e.date == target.date
                    && e.text != null && e.text.equals(target.text)) {
                entries.remove(i);
                break;
            }
        }
        saveAll(context, entries);
    }
}
