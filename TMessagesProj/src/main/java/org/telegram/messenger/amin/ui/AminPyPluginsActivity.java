package org.telegram.messenger.amin.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.pyplugins.PyPlugin;
import org.telegram.messenger.amin.pyplugins.PyPluginsBootstrap;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Python (.plugin) plugins list page — M0.
 *
 * Contents: the engine master switch (default OFF until M3),
 * one row per installed Python plugin (tap opens its page), and
 * "Add plugin from file…" (system document picker, sideload
 * only — no catalog until M4).
 *
 * The install review (static-analysis report) is shown as a
 * confirm dialog before the file is installed: the user approves
 * that specific report. Analysis is advisory, not a sandbox —
 * the dialog says so.
 *
 * All flows are Throwable-isolated like the rest of the layer.
 */
public class AminPyPluginsActivity extends BaseFragment {

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

    private static final int REQUEST_PYPLUGIN_FILE = 3921;

    private static final class Row {
        int kind;
        int seg = SEG_NONE;
        String title;
        String value;
        String action;
        PyPlugin plugin;
        boolean checked;
    }

    private RecyclerListView listView;
    private PyAdapter adapter;
    private final List<Row> rows = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Python Plugins");
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
        Row header = new Row();
        header.kind = KIND_HEADER;
        header.title = "Python engine";
        rows.add(header);

        Row engine = new Row();
        engine.kind = KIND_CHECK;
        engine.seg = SEG_ONLY;
        engine.title = "Enable Python plugins";
        engine.action = "engine";
        engine.checked = PyPluginsController.isEngineEnabled(context);
        rows.add(engine);

        Row engineInfo = new Row();
        engineInfo.kind = KIND_INFO;
        engineInfo.seg = SEG_ONLY;
        engineInfo.title = PyPluginsController.isEngineEnabled(context)
                ? "Engine on. Python starts lazily on first need; turning it off stops every Python plugin."
                : "Engine off — no Python code runs. Turn it on to load .plugin files.";
        rows.add(engineInfo);

        Row pluginsHeader = new Row();
        pluginsHeader.kind = KIND_HEADER;
        pluginsHeader.title = "Installed plugins";
        rows.add(pluginsHeader);

        List<PyPlugin> plugins = PyPluginsController.plugins(context);
        if (plugins.isEmpty()) {
            Row empty = new Row();
            empty.kind = KIND_INFO;
            empty.seg = SEG_ONLY;
            empty.title = "No Python plugins installed yet. Use Add plugin below.";
            rows.add(empty);
        } else {
            for (int i = 0; i < plugins.size(); i++) {
                PyPlugin plugin = plugins.get(i);
                Row row = new Row();
                row.kind = KIND_ACTION;
                row.seg = plugins.size() == 1 ? SEG_ONLY
                        : i == 0 ? SEG_FIRST
                        : i == plugins.size() - 1 ? SEG_LAST : SEG_MID;
                row.title = plugin.name;
                if (plugin.errored) {
                    row.value = "Error — tap for details";
                } else if (!plugin.enabled) {
                    row.value = "Disabled";
                } else {
                    row.value = "v" + plugin.version
                            + (plugin.author.isEmpty() ? "" : " · " + plugin.author);
                }
                row.action = "open_plugin";
                row.plugin = plugin;
                rows.add(row);
            }
        }

        Row add = new Row();
        add.kind = KIND_ACTION;
        add.seg = SEG_ONLY;
        add.title = "Add plugin from file…";
        add.value = ".plugin";
        add.action = "add_plugin_file";
        rows.add(add);

        Row footer = new Row();
        footer.kind = KIND_FOOTER;
        footer.title = "Python plugins run with full app access — install only "
                + "files from sources you trust, like you trust the app itself. "
                + "There is no sandbox: a plugin can read chats, send messages "
                + "and load native code. The install review shows what the "
                + "source text contains; hidden or downloaded code would not "
                + "show up there. M0: single-file .plugin only, sideload only, "
                + "no PyPI packages. M3: DEX loads only with per-plugin consent.";
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
                case "engine": {
                    boolean now = !PyPluginsController.isEngineEnabled(context);
                    PyPluginsController.setEngineEnabled(context, now);
                    if (now) {
                        PyPluginsBootstrap.post(new Runnable() {
                            @Override
                            public void run() {
                                PyPluginsBootstrap.ensureStarted(context, true);
                            }
                        });
                    }
                    bulletin(now ? R.raw.contact_check : R.raw.error,
                            now ? "Python engine on" : "Python engine off");
                    rebuild();
                    break;
                }
                case "open_plugin":
                    if (row.plugin != null) {
                        presentFragment(new AminPyPluginActivity(row.plugin.id));
                    }
                    break;
                case "add_plugin_file":
                    pickPluginFile();
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
    }

    private void pickPluginFile() {
        if (getParentActivity() == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PYPLUGIN_FILE);
        } catch (Throwable t) {
            bulletin(R.raw.error, "No file picker available");
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQUEST_PYPLUGIN_FILE) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        final Context context = getContext();
        if (context == null) {
            return;
        }
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        new Thread(() -> {
            byte[] bytes = null;
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) {
                    throw new java.io.IOException("cannot open picked file");
                }
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    if (out.size() > PyPluginsPrefs.MAX_PLUGIN_BYTES) {
                        break;
                    }
                }
                bytes = out.toByteArray();
            } catch (Throwable t) {
                final String msg = "Could not read that file.";
                AndroidUtilities.runOnUIThread(() -> bulletin(R.raw.error, msg));
                return;
            }
            final byte[] fileBytes = bytes;
            AndroidUtilities.runOnUIThread(() -> confirmInstall(fileBytes));
        }).start();
    }

    /** Shows the install-time analysis report and installs on approval. */
    private void confirmInstall(final byte[] bytes) {
        final Context context = getContext();
        if (context == null || getParentActivity() == null) {
            return;
        }
        String source;
        try {
            source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            bulletin(R.raw.error, "Plugin file is not valid UTF-8 text.");
            return;
        }
        org.telegram.messenger.amin.pyplugins.PyPluginMetadata.Parsed parsed =
                org.telegram.messenger.amin.pyplugins.PyPluginMetadata.parse(source);
        if (parsed.error != null) {
            bulletin(R.raw.error, parsed.error);
            return;
        }
        final PyPlugin plugin = parsed.plugin;
        List<PyPluginAnalysis.Finding> findings =
                org.telegram.messenger.amin.pyplugins.PyPluginAnalysis.analyze(source, plugin);
        StringBuilder report = new StringBuilder();
        report.append(plugin.name).append(" v").append(plugin.version);
        if (!plugin.author.isEmpty()) {
            report.append(" by ").append(plugin.author);
        }
        report.append("\n\nThis plugin runs with full app access. The source text shows:\n");
        for (PyPluginAnalysis.Finding f : findings) {
            report.append("• ").append(f.text).append("\n");
        }
        report.append("\nHidden or downloaded code would not show up here. Install only files from sources you trust.");
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Install Python plugin?");
        builder.setMessage(report.toString());
        builder.setPositiveButton("Install", (dialog, which) -> {
            String error = PyPluginsController.installFromBytes(context, bytes, "file");
            if (error == null) {
                bulletin(R.raw.contact_check, "Plugin installed");
                rebuild();
            } else {
                bulletin(R.raw.error, error);
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
