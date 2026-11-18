package org.telegram.messenger.amin.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resource Usage settings page (opened from the "Resource Usage" row
 * under the AminGram section of Settings, added by patch 0059).
 *
 * Live values for this app process — RAM (PSS, Java heap, native
 * heap), device RAM, CPU %, thread count, app and device uptime —
 * in Telegram's own settings cells (HeaderCell, TextDetailSettingsCell,
 * TextSettingsCell). The list refreshes on a timer while the screen
 * is visible; the interval is adjustable (1s / 5s / 10s / 30s /
 * Paused) and stored in SharedPreferences. The timer is started in
 * onResume and stopped in onPause/onFragmentDestroy, so nothing
 * runs — and no battery is spent — once the screen is closed.
 *
 * CPU % definition: (process CPU time / wall-clock time) * 100,
 * i.e. 100% = one CPU core fully used. Multi-threaded work can
 * exceed 100%. Sampled from /proc/self/stat utime+stime deltas.
 */
public class AminResourcesActivity extends BaseFragment {

    private static final String PREFS = "amingram_resources";
    private static final String KEY_INTERVAL = "resources_refresh_interval";

    /** Seconds between refreshes; 0 = paused. */
    public static final int INTERVAL_OFF = 0;
    public static final int DEFAULT_INTERVAL_SECONDS = 5;
    private static final int[] INTERVALS_SECONDS = {1, 5, 10, 30, 0};
    private static final String[] INTERVAL_LABELS = {
            "1 second", "5 seconds", "10 seconds", "30 seconds", "Paused"
    };

    private static final int KIND_SHADOW = 0;
    private static final int KIND_HEADER = 1;
    private static final int KIND_DETAIL = 2;
    private static final int KIND_SETTING = 3;
    private static final int KIND_INFO = 4;

    private static final class Row {
        int kind;
        String title;
        String value;
        String action;
    }

    private RecyclerListView listView;
    private ListAdapter adapter;
    private final List<Row> rows = new ArrayList<>();

    /** Last CPU sample, for the delta computation. -1 = no sample yet. */
    private long lastCpuTicks = -1;
    private long lastCpuWallMs = -1;
    private double lastCpuPercent = Double.NaN;

    private final Runnable tickRunnable = this::tick;

    // ---------------------------------------------------------------
    // Preferences
    // ---------------------------------------------------------------

    public static int refreshIntervalSeconds(Context context) {
        if (context == null) {
            return DEFAULT_INTERVAL_SECONDS;
        }
        int seconds = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_INTERVAL, DEFAULT_INTERVAL_SECONDS);
        for (int interval : INTERVALS_SECONDS) {
            if (interval == seconds) {
                return seconds;
            }
        }
        return DEFAULT_INTERVAL_SECONDS;
    }

    private static void setRefreshIntervalSeconds(Context context, int seconds) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_INTERVAL, seconds)
                .apply();
    }

    private static String intervalLabel(int seconds) {
        for (int i = 0; i < INTERVALS_SECONDS.length; i++) {
            if (INTERVALS_SECONDS[i] == seconds) {
                return INTERVAL_LABELS[i];
            }
        }
        return INTERVAL_LABELS[1];
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        rebuild();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(tickRunnable);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Resource Usage");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout layout = new FrameLayout(context);
        layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = layout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(position));
        layout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return layout;
    }

    @Override
    public void onResume() {
        super.onResume();
        lastCpuTicks = -1;
        lastCpuWallMs = -1;
        lastCpuPercent = Double.NaN;
        rebuild();
        scheduleNextTick();
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(tickRunnable);
    }

    // ---------------------------------------------------------------
    // Timer
    // ---------------------------------------------------------------

    private void scheduleNextTick() {
        AndroidUtilities.cancelRunOnUIThread(tickRunnable);
        int seconds = refreshIntervalSeconds(getContext());
        if (seconds > 0) {
            AndroidUtilities.runOnUIThread(tickRunnable, seconds * 1000L);
        }
    }

    private void tick() {
        if (isPaused() || getContext() == null) {
            return;
        }
        rebuild();
        scheduleNextTick();
    }

    // ---------------------------------------------------------------
    // Row model
    // ---------------------------------------------------------------

    private void rebuild() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        rows.clear();

        Stats stats = collect(context);
        String cpuValue = Double.isNaN(lastCpuPercent)
                ? "Measuring…"
                : String.format(Locale.US, "%.1f%%", lastCpuPercent);

        header("RAM");
        detail("App RAM (PSS)", formatBytes(stats.appPssBytes));
        detail("Java heap", formatBytes(stats.javaHeapUsedBytes) + " of " + formatBytes(stats.javaHeapMaxBytes));
        detail("Native heap", formatBytes(stats.nativeHeapBytes));
        detail("Device RAM", formatBytes(stats.deviceAvailBytes) + " free of " + formatBytes(stats.deviceTotalBytes));
        shadow();

        header("CPU");
        detail("CPU (app)", cpuValue);
        detail("Threads", stats.threads < 0 ? "—" : String.valueOf(stats.threads));
        info("CPU % is the app's share of one core: 100% = one CPU core fully used. "
                + "Multi-threaded work can exceed 100%.");
        shadow();

        header("Uptime");
        detail("App uptime", formatDuration(stats.appUptimeMs));
        detail("Device uptime", formatDuration(stats.deviceUptimeMs));
        shadow();

        header("Refresh");
        setting("Refresh interval", intervalLabel(refreshIntervalSeconds(context)), "interval");
        info("Values refresh automatically while this screen is open. "
                + "The timer stops when you leave the screen.");
        shadow();

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void header(String title) {
        Row row = new Row();
        row.kind = KIND_HEADER;
        row.title = title;
        rows.add(row);
    }

    private void detail(String title, String value) {
        Row row = new Row();
        row.kind = KIND_DETAIL;
        row.title = title;
        row.value = value;
        rows.add(row);
    }

    private void setting(String title, String value, String action) {
        Row row = new Row();
        row.kind = KIND_SETTING;
        row.title = title;
        row.value = value;
        row.action = action;
        rows.add(row);
    }

    private void info(String text) {
        Row row = new Row();
        row.kind = KIND_INFO;
        row.title = text;
        rows.add(row);
    }

    private void shadow() {
        Row row = new Row();
        row.kind = KIND_SHADOW;
        rows.add(row);
    }

    private void onRowClick(int position) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        if ("interval".equals(row.action)) {
            showIntervalChooser();
        }
    }

    private void showIntervalChooser() {
        if (getParentActivity() == null) {
            return;
        }
        int current = refreshIntervalSeconds(getContext());
        String[] labels = new String[INTERVAL_LABELS.length];
        for (int i = 0; i < INTERVAL_LABELS.length; i++) {
            labels[i] = INTERVALS_SECONDS[i] == current ? "✓ " + INTERVAL_LABELS[i] : INTERVAL_LABELS[i];
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Refresh interval");
        // Telegram's AlertDialog.Builder has no setSingleChoiceItems;
        // setItems is its list-picker API (tap = choose + dismiss).
        builder.setItems(labels, (dialog, which) -> {
            setRefreshIntervalSeconds(getContext(), INTERVALS_SECONDS[which]);
            lastCpuTicks = -1;
            lastCpuWallMs = -1;
            lastCpuPercent = Double.NaN;
            rebuild();
            scheduleNextTick();
            dialog.dismiss();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    // ---------------------------------------------------------------
    // Stats collection (stock Android APIs only)
    // ---------------------------------------------------------------

    private static final class Stats {
        long appPssBytes;
        long javaHeapUsedBytes;
        long javaHeapMaxBytes;
        long nativeHeapBytes;
        long deviceTotalBytes;
        long deviceAvailBytes;
        int threads = -1;
        long appUptimeMs;
        long deviceUptimeMs;
    }

    private Stats collect(Context context) {
        Stats stats = new Stats();

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            android.os.Debug.MemoryInfo[] infos = am.getProcessMemoryInfo(new int[]{Process.myPid()});
            if (infos != null && infos.length > 0) {
                stats.appPssBytes = (long) infos[0].getTotalPss() * 1024L;
            }
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            stats.deviceTotalBytes = mi.totalMem;
            stats.deviceAvailBytes = mi.availMem;
        }

        Runtime runtime = Runtime.getRuntime();
        stats.javaHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory();
        stats.javaHeapMaxBytes = runtime.maxMemory();
        stats.nativeHeapBytes = Debug.getNativeHeapAllocatedSize();

        long ticks = readProcessCpuTicks();
        long nowMs = SystemClock.elapsedRealtime();
        if (ticks >= 0 && lastCpuTicks >= 0 && nowMs > lastCpuWallMs) {
            double seconds = (nowMs - lastCpuWallMs) / 1000.0;
            lastCpuPercent = (ticks - lastCpuTicks) / (double) clockTicksPerSecond() / seconds * 100.0;
        }
        if (ticks >= 0) {
            lastCpuTicks = ticks;
            lastCpuWallMs = nowMs;
        }

        stats.threads = readThreadCount();
        stats.deviceUptimeMs = SystemClock.elapsedRealtime();
        stats.appUptimeMs = stats.deviceUptimeMs - Process.getStartElapsedRealtime();
        if (stats.appUptimeMs < 0) {
            stats.appUptimeMs = 0;
        }
        return stats;
    }

    /** utime + stime from /proc/self/stat, in clock ticks. -1 on failure. */
    private static long readProcessCpuTicks() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String line = reader.readLine();
            if (line == null) {
                return -1;
            }
            int closeParen = line.lastIndexOf(')');
            if (closeParen < 0) {
                return -1;
            }
            // Fields after comm(): state(3) ppid(4) pgrp(5) session(6)
            // tty_nr(7) tpgid(8) flags(9) minflt(10) cminflt(11)
            // majflt(12) cmajflt(13) utime(14) stime(15).
            String[] fields = line.substring(closeParen + 1).trim().split("\\s+");
            if (fields.length < 13) {
                return -1;
            }
            return Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
        } catch (IOException | NumberFormatException ignored) {
            return -1;
        }
    }

    private static int clockTicksPerSecond() {
        try {
            long ticks = Os.sysconf(OsConstants._SC_CLK_TCK);
            if (ticks > 0) {
                return (int) ticks;
            }
        } catch (ErrnoException | RuntimeException ignored) {
            // fall through to the Linux/Android default
        }
        return 100;
    }

    /** Thread count from /proc/self/status; Thread.activeCount() fallback. */
    private static int readThreadCount() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Threads:")) {
                    return Integer.parseInt(line.substring("Threads:".length()).trim());
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // fall through
        }
        return Thread.activeCount();
    }

    // ---------------------------------------------------------------
    // Formatting
    // ---------------------------------------------------------------

    private static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return String.format(Locale.US, "%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format(Locale.US, "%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format(Locale.US, "%dm %ds", minutes, secs);
        } else {
            return String.format(Locale.US, "%ds", secs);
        }
    }

    // ---------------------------------------------------------------
    // Adapter
    // ---------------------------------------------------------------

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position < 0 || position >= rows.size()) {
                return false;
            }
            return rows.get(position).kind == KIND_SETTING;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case KIND_HEADER:
                    view = new HeaderCell(mContext);
                    break;
                case KIND_DETAIL:
                    view = new TextDetailSettingsCell(mContext);
                    break;
                case KIND_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case KIND_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                case KIND_SETTING:
                default:
                    view = new TextSettingsCell(mContext);
                    break;
            }
            if (viewType == KIND_HEADER || viewType == KIND_DETAIL || viewType == KIND_SETTING) {
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            boolean divider = position + 1 < rows.size() && rows.get(position + 1).kind != KIND_SHADOW
                    && rows.get(position + 1).kind != KIND_HEADER;
            switch (holder.getItemViewType()) {
                case KIND_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.title);
                    break;
                case KIND_DETAIL:
                    ((TextDetailSettingsCell) holder.itemView).setTextAndValue(row.title, row.value, divider);
                    break;
                case KIND_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                    break;
                case KIND_SETTING: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (row.value == null || row.value.isEmpty()) {
                        cell.setText(row.title, divider);
                    } else {
                        cell.setTextAndValue(row.title, row.value, divider);
                    }
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).kind;
        }
    }
}
