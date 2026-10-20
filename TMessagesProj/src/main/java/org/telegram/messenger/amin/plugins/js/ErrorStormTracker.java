package org.telegram.messenger.amin.plugins.js;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Error-storm detector (Platform v2, A8): remembers the timestamps
 * of a plugin's recent hook errors and reports a "storm" once 20
 * errors land inside a trailing 60 s window. Pure java.util — no
 * Android, no Telegram, no Rhino — so it can be unit-checked
 * standalone. All methods are synchronized; the tracker is fed
 * from the single plugin thread but read from hub threads too.
 */
public final class ErrorStormTracker {

    /** Window length: errors older than this stop counting. */
    public static final long WINDOW_MS = 60_000L;
    /** Errors inside the window at/above which a storm is active. */
    public static final int THRESHOLD = 20;

    private final Deque<Long> times = new ArrayDeque<>();

    /**
     * Records one error at {@code nowMs}. Returns true exactly
     * when the count of records within the trailing window has
     * reached {@link #THRESHOLD}, and keeps returning true on
     * further records while the count stays at/above the
     * threshold, until {@link #reset()} (or the window drains).
     */
    public synchronized boolean record(long nowMs) {
        prune(nowMs);
        times.addLast(nowMs);
        return times.size() >= THRESHOLD;
    }

    /** Number of records inside the trailing window at {@code nowMs}. */
    public synchronized int count(long nowMs) {
        prune(nowMs);
        return times.size();
    }

    /** Forgets every recorded error (hub Resume). */
    public synchronized void reset() {
        times.clear();
    }

    private void prune(long nowMs) {
        long cutoff = nowMs - WINDOW_MS;
        while (!times.isEmpty() && times.peekFirst() <= cutoff) {
            times.pollFirst();
        }
    }
}
