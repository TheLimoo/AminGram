package org.telegram.messenger.amin.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Send When Online (v1.3): queued messages that fire the moment their
 * target user comes online.
 *
 * Phase A optimization (A-29):
 * - Caches entries and active (account, peer) pending keys in memory so
 *   pendingFor() returns in O(1) on every TL_updateUserStatus event
 *   without reading SharedPreferences or parsing JSON when the peer has
 *   no queued messages.
 */
public final class SendWhenOnlineStore {

    public static final String PREFS = "amingram_swo";
    private static final String KEY_ITEMS = "items";
    private static final int CAP = 100;

    private static final Object LOCK = new Object();
    private static List<Entry> cachedEntries;
    private static final Set<String> pendingPeers = new HashSet<>();

    public static final class Entry {
        public long peer;
        public String name;
        public String text;
        public int date;
        public String state;
        public int sentDate;
        public int account = -1;

        public boolean isPending() {
            return !"sent".equals(state);
        }

        private Entry copy() {
            Entry c = new Entry();
            c.peer = peer;
            c.name = name;
            c.text = text;
            c.date = date;
            c.state = state;
            c.sentDate = sentDate;
            c.account = account;
            return c;
        }
    }

    private SendWhenOnlineStore() {
    }

    private static String peerKey(int account, long peer) {
        return account + ":" + peer;
    }

    private static void rebuildPendingIndexLocked(List<Entry> entries) {
        pendingPeers.clear();
        for (Entry e : entries) {
            if (e.isPending()) {
                pendingPeers.add(peerKey(e.account, e.peer));
            }
        }
    }

    private static List<Entry> ensureLoadedLocked(Context context) {
        if (cachedEntries != null) {
            return cachedEntries;
        }
        List<Entry> out = new ArrayList<>();
        if (context != null) {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_ITEMS, null);
            if (raw != null) {
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
                        e.account = o.optInt("account", UserConfig.selectedAccount);
                        out.add(e);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        cachedEntries = out;
        rebuildPendingIndexLocked(out);
        return cachedEntries;
    }

    public static List<Entry> getAll(Context context) {
        synchronized (LOCK) {
            List<Entry> list = ensureLoadedLocked(context);
            List<Entry> copy = new ArrayList<>(list.size());
            for (Entry e : list) {
                copy.add(e.copy());
            }
            return copy;
        }
    }

    public static void saveAll(Context context, List<Entry> entries) {
        if (context == null) {
            return;
        }
        List<Entry> copy = new ArrayList<>(entries != null ? entries.size() : 0);
        if (entries != null) {
            for (Entry e : entries) {
                copy.add(e.copy());
            }
        }
        synchronized (LOCK) {
            cachedEntries = copy;
            rebuildPendingIndexLocked(copy);
        }
        JSONArray arr = new JSONArray();
        for (Entry e : copy) {
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
        synchronized (LOCK) {
            List<Entry> entries = new ArrayList<>(ensureLoadedLocked(context));
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
    }

    public static int pendingCount(Context context) {
        synchronized (LOCK) {
            int n = 0;
            for (Entry e : ensureLoadedLocked(context)) {
                if (e.isPending()) {
                    n++;
                }
            }
            return n;
        }
    }

    /** Fast O(1) early-out when (account, peer) has no pending messages (A-29). */
    public static List<Entry> pendingFor(Context context, int account, long peer) {
        synchronized (LOCK) {
            List<Entry> all = ensureLoadedLocked(context);
            if (pendingPeers.isEmpty() || !pendingPeers.contains(peerKey(account, peer))) {
                return Collections.emptyList();
            }
            List<Entry> out = new ArrayList<>();
            for (Entry e : all) {
                if (e.peer == peer && e.account == account && e.isPending()) {
                    out.add(e.copy());
                }
            }
            return out;
        }
    }

    public static int markSent(Context context, List<Entry> dispatched) {
        if (dispatched == null || dispatched.isEmpty()) {
            return 0;
        }
        synchronized (LOCK) {
            List<Entry> entries = new ArrayList<>(ensureLoadedLocked(context));
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
    }

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
        if (target == null) {
            return;
        }
        synchronized (LOCK) {
            List<Entry> entries = new ArrayList<>(ensureLoadedLocked(context));
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
}
