package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.pyplugins.PyPlugin;
import org.telegram.messenger.amin.pyplugins.PyPluginsController;
import org.telegram.messenger.amin.pyplugins.PyPluginsPrefs;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-plugin page for one Python (.plugin) plugin — M0.
 *
 * Contents: About (id / name / version / author / description /
 * app + SDK constraints / fingerprint / install source), the
 * enable switch, the install-time analysis report, the error
 * state when one exists, View log (the shared store-list viewer
 * over {@code log_<id>}, same as the JS engine) and Delete
 * (confirm dialog; removes code, meta, log and settings).
 *
 * No settings UI in M0 — {@code create_settings()} rendering is
 * M1. No DEX consent toggle in M0 — DEX loading is refused
 * until M3; the analysis report flags it.
 */
public class AminPyPluginActivity extends BaseFragment {

    private static final int KIND_HEADER = 0;
    private static final int KIND_INFO = 1;
    private static final int KIND_ACTION = 2;
    private static final int KIND_CHECK = 3;
    private static final int KIND_FOOTER = 4;

    private static final int SEG_NONE = 0;
    private static final int SEG_ONLY = 1;
    private static final int SEG_FIRST = 2;
    private static final int SEG_MID = 3;
    private static final int SEG_LAST = 4;

    private static final class Row {
        int kind;
        int seg = SEG_NONE;
        String title;
        String value;
        String action;
        boolean checked;
    }

    private final String pluginId;
    private RecyclerListView listView;
    private PyAdapter adapter;
    private final List<Row> rows = new ArrayList<>();

    public AminPyPluginActivity(String pluginId) {
        this.pluginId = pluginId;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Python Plugin");
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

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new PyAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(position));
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rebuild();
        fragmentView = layout;
        return layout;
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
    }

    private void rebuild() {
        Context context = getContext();
        rows.clear();
        if (context == null) {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            return;
        }
        PyPlugin plugin = PyPluginsController.get(context, pluginId);
        if (plugin == null) {
            Row empty = new Row();
            empty.kind = KIND_INFO;
            empty.seg = SEG_ONLY;
            empty.title = "This plugin is no longer installed.";
            rows.add(empty);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            return;
        }

        Row aboutHeader = new Row();
        aboutHeader.kind = KIND_HEADER;
        aboutHeader.title = "About";
        rows.add(aboutHeader);

        Row name = new Row();
        name.kind = KIND_INFO;
        name.seg = SEG_FIRST;
        name.title = plugin.name + "  ·  v" + plugin.version
                + (plugin.author.isEmpty() ? "" : "\n" + plugin.author)
                + (plugin.description.isEmpty() ? "" : "\n" + plugin.description);
        rows.add(name);

        Row id = new Row();
        id.kind = KIND_INFO;
        id.seg = SEG_MID;
        id.title = "ID: " + plugin.id
                + "\nNeeds app " + (plugin.appVersion.isEmpty() ? "any" : plugin.appVersion)
                + " · SDK " + (plugin.sdkVersion.isEmpty() ? "any" : plugin.sdkVersion)
                + (plugin.categories.isEmpty() ? "" : "\nCategories: " + join(plugin.categories))
                + "\nInstalled from: " + plugin.installSource
                + (plugin.fingerprint.isEmpty() ? ""
                        : "\nSHA-256: " + plugin.fingerprint.substring(0,
                                Math.min(12, plugin.fingerprint.length())) + "…");
        rows.add(id);

        if (plugin.errored) {
            Row err = new Row();
            err.kind = KIND_INFO;
            err.seg = SEG_LAST;
            err.title = "ERROR — plugin disabled:\n" + plugin.errorText
                    + "\nRe-enable it below to try loading it again.";
            rows.add(err);
        }

        Row enableHeader = new Row();
        enableHeader.kind = KIND_HEADER;
        enableHeader.title = "State";
        rows.add(enableHeader);

        Row enable = new Row();
        enable.kind = KIND_CHECK;
        enable.seg = SEG_ONLY;
        enable.title = "Enabled";
        enable.action = "toggle";
        enable.checked = plugin.enabled && !plugin.errored;
        rows.add(enable);

        Row analysisHeader = new Row();
        analysisHeader.kind = KIND_HEADER;
        analysisHeader.title = "Install-time analysis";
        rows.add(analysisHeader);

        if (plugin.analysis.isEmpty()) {
            Row none = new Row();
            none.kind = KIND_INFO;
            none.seg = SEG_ONLY;
            none.title = "No analysis recorded.";
            rows.add(none);
        } else {
            for (int i = 0; i < plugin.analysis.size(); i++) {
                Row a = new Row();
                a.kind = KIND_INFO;
                a.seg = plugin.analysis.size() == 1 ? SEG_ONLY
                        : i == 0 ? SEG_FIRST
                        : i == plugin.analysis.size() - 1 ? SEG_LAST : SEG_MID;
                a.title = "• " + plugin.analysis.get(i);
                rows.add(a);
            }
        }

        Row actionsHeader = new Row();
        actionsHeader.kind = KIND_HEADER;
        actionsHeader.title = "Actions";
        rows.add(actionsHeader);

        Row log = new Row();
        log.kind = KIND_ACTION;
        log.seg = SEG_FIRST;
        log.title = "View log";
        log.action = "log";
        rows.add(log);

        Row del = new Row();
        del.kind = KIND_ACTION;
        del.seg = SEG_LAST;
        del.title = "Delete plugin";
        del.action = "delete";
        rows.add(del);

        Row footer = new Row();
        footer.kind = KIND_FOOTER;
        footer.title = "M0 bounds: settings UI (create_settings) is M1; "
                + "command suppression is M2; DEX loading and ZwyLib are "
                + "M3. A plugin that needs any of those will fail loudly "
                + "instead of pretending to work.";
        rows.add(footer);

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void onRowClick(int position) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        Context context = getContext();
        if (context == null || row.action == null) {
            return;
        }
        try {
            switch (row.action) {
                case "toggle": {
                    PyPlugin plugin = PyPluginsController.get(context, pluginId);
                    if (plugin == null) {
                        return;
                    }
                    boolean now = !(plugin.enabled && !plugin.errored);
                    PyPluginsController.setEnabled(context, plugin, now);
                    bulletin(now ? R.raw.contact_check : R.raw.error,
                            now ? "Plugin enabled" : "Plugin disabled");
                    rebuild();
                    break;
                }
                case "log": {
                    PyPlugin plugin = PyPluginsController.get(context, pluginId);
                    if (plugin == null) {
                        return;
                    }
                    presentFragment(new AminStoreListActivity(plugin.name + " — log",
                            PyPluginsPrefs.PREFS,
                            PyPluginsPrefs.KEY_LOG_PREFIX + plugin.id));
                    break;
                }
                case "delete":
                    confirmDelete();
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
    }

    private void confirmDelete() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Delete plugin?");
        builder.setMessage("The plugin's code, settings and log are removed. This cannot be undone.");
        builder.setPositiveButton("Delete", (dialog, which) -> {
            try {
                if (context != null) {
                    PyPluginsController.delete(context, pluginId);
                }
                finishFragment();
            } catch (Throwable ignored) {
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void bulletin(int icon, String text) {
        try {
            BulletinFactory.of(this).createSimpleBulletin(icon, text).show();
        } catch (Throwable ignored) {
        }
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Presentation helpers (same pattern as AminGoogleActivity)
    // ------------------------------------------------------------------

    private static GradientDrawable segmentDrawable(int seg, int fillColor) {
        float r = AndroidUtilities.dp(22);
        float[] radii;
        switch (seg) {
            case SEG_ONLY:
                radii = new float[]{r, r, r, r, r, r, r, r};
                break;
            case SEG_FIRST:
                radii = new float[]{r, r, r, r, 0, 0, 0, 0};
                break;
            case SEG_LAST:
                radii = new float[]{0, 0, 0, 0, r, r, r, r};
                break;
            default:
                radii = new float[]{0, 0, 0, 0, 0, 0, 0, 0};
                break;
        }
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadii(radii);
        return drawable;
    }

    private static int cardFill() {
        return Theme.getColor(Theme.key_windowBackgroundWhite);
    }

    private static int accentColor() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
    }

    private static class SectionLabelView extends TextView {
        SectionLabelView(Context context) {
            super(context);
            setTextSize(14);
            setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            setTextColor(accentColor());
            setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(22),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(2));
        }
    }

    private static class InfoView extends TextView {
        InfoView(Context context) {
            super(context);
            setTextSize(13.5f);
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            setLineSpacing(AndroidUtilities.dp(2), 1.0f);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        }

        void bind(String text, int seg) {
            setText(text == null ? "" : text);
            setBackground(segmentDrawable(seg, cardFill()));
        }
    }

    private class PyAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).kind;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position < 0 || position >= rows.size()) {
                return false;
            }
            int kind = rows.get(position).kind;
            return kind == KIND_ACTION || kind == KIND_CHECK;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            switch (viewType) {
                case KIND_HEADER:
                    view = new SectionLabelView(context);
                    break;
                case KIND_INFO:
                    view = new InfoView(context);
                    break;
                case KIND_FOOTER:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case KIND_CHECK:
                    view = new TextCheckCell(context);
                    break;
                default:
                    view = new TextCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            RecyclerView.LayoutParams lp =
                    (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
            boolean carded = row.seg != SEG_NONE;
            lp.leftMargin = carded ? AndroidUtilities.dp(12) : 0;
            lp.rightMargin = carded ? AndroidUtilities.dp(12) : 0;
            lp.topMargin = row.seg == SEG_FIRST || row.seg == SEG_ONLY
                    ? AndroidUtilities.dp(10) : 0;
            holder.itemView.setLayoutParams(lp);
            switch (row.kind) {
                case KIND_HEADER:
                    ((SectionLabelView) holder.itemView).setText(row.title);
                    break;
                case KIND_INFO:
                    ((InfoView) holder.itemView).bind(row.title, row.seg);
                    break;
                case KIND_FOOTER:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                    break;
                case KIND_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(row.title, row.checked, false);
                    cell.setBackground(segmentDrawable(row.seg, cardFill()));
                    break;
                }
                default:
                    TextCell cell = (TextCell) holder.itemView;
                    boolean divider = row.seg == SEG_MID;
                    if (row.value != null) {
                        cell.setTextAndValue(row.title, row.value, divider);
                    } else {
                        cell.setText(row.title, divider);
                    }
                    cell.setBackground(segmentDrawable(row.seg, cardFill()));
                    break;
            }
        }
    }
}
