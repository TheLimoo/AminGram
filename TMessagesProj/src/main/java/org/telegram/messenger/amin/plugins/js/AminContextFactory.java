package org.telegram.messenger.amin.plugins.js;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;

/**
 * Rhino {@link ContextFactory} for the plugin engine (Platform
 * v2, A8): installs the instruction observer on every context it
 * creates so a runaway JS loop can be preempted once the current
 * hook's budget expires. Pure Rhino dependency — no Android, no
 * Telegram — so it compiles standalone against rhino.jar.
 *
 * The budget is per-hook, not per-context: the manager calls
 * {@link #beginHook(long)} immediately before running plugin code
 * and {@link #endHook()} afterwards (try/finally). The observer
 * only fires while JS instructions execute, so wall-clock time
 * spent inside a blocking native bridge call does not itself trip
 * the budget — it trips at the first instruction tick after the
 * deadline, which matches the blueprint's "≈5 s of JS execution".
 * With no deadline set on the calling thread, observation is a
 * no-op beyond the (cheap) threshold bookkeeping.
 */
public class AminContextFactory extends ContextFactory {

    /** Thrown inside a hook whose JS execution budget expired.
     *  An {@link Error} (not Exception) so plugin try/catch blocks
     *  written against ordinary exceptions do not silently swallow
     *  it, and so the manager's Throwable guard can recognise it. */
    public static class HookBudgetError extends Error {
        private static final long serialVersionUID = 1L;

        public HookBudgetError(String message) {
            super(message);
        }
    }

    /** Instruction ticks between observer callbacks. Small enough
     *  to react promptly past the deadline, large enough that the
     *  observer overhead is negligible for normal hooks. 10k ticks
     *  execute in well under a millisecond of interpreted JS, so
     *  the 5 s wall-clock deadline stays authoritative while the
     *  observer callback (ThreadLocal read + clock read) runs 10x
     *  less often on every plugin code path (2026-10-22 opt pass:
     *  was 1000, which taxed ALL plugin JS continuously). */
    private static final int OBSERVER_THRESHOLD = 10000;

    private static final ThreadLocal<Long> DEADLINE_MS = new ThreadLocal<>();

    /** Arms the budget for the hook about to run on this thread:
     *  JS execution from now on must finish within
     *  {@code budgetMs} milliseconds of wall clock (checked on
     *  instruction ticks). */
    public static void beginHook(long budgetMs) {
        DEADLINE_MS.set(System.currentTimeMillis() + budgetMs);
    }

    /** Disarms the budget (always call in a finally block). */
    public static void endHook() {
        DEADLINE_MS.remove();
    }

    @Override
    protected void onContextCreated(Context cx) {
        super.onContextCreated(cx);
        cx.setInstructionObserverThreshold(OBSERVER_THRESHOLD);
    }

    @Override
    protected void observeInstructionCount(Context cx, int instructionCount) {
        Long deadline = DEADLINE_MS.get();
        if (deadline != null && System.currentTimeMillis() > deadline) {
            throw new HookBudgetError("hook budget exceeded");
        }
    }
}
