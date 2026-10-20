package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.plugins.js.JsPluginManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Attach-menu customization (v2.0): choose which menu-capable JS
 * plugins appear in Telegram's attach menu, their order, their
 * label, and their icon (one of the bundled vector set or an emoji).
 * All state is delegated to JsPluginManager's menu-config store;
 * this page is presentation only.
 */
public class AminMenuPluginsActivity extends BaseFragment {

    private static final String[] ICON_NAMES = new String[]{
            "download", "mail", "sparkles", "dice", "text", "link",
            "music", "star", "bolt", "camera", "globe", "gear"
    };

    private static final int ROW_ENTRY = 0;
    private static final int ROW_INCLUDE = 1;
    private static final int ROW_UP = 2;
    private static final int ROW_DOWN = 3;
    private static final int ROW_ICON = 4;
    private static final int ROW_LABEL = 5;

    private static final class Row {
        int type;
        JsPluginManager.MenuEntry entry;
    }

    private final List<Row> rows = new ArrayList<>();
    private List<JsPluginManager.MenuEntry> entries = new ArrayList<>();
    private RecyclerListView listView;
    private MenuAdapter adapter;

    private void reload(Context context) {
        entries = JsPluginManager.menuConfigEntries(context);
        rows.clear();
        for (JsPluginManager.MenuEntry entry : entries) {
            addRow(ROW_ENTRY, entry);
            addRow(ROW_INCLUDE, entry);
            addRow(ROW_UP, entry);
            addRow(ROW_DOWN, entry);
            addRow(ROW_ICON, entry);
            addRow(ROW_LABEL, entry);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void addRow(int type, JsPluginManager.MenuEntry entry) {
        Row row = new Row();
        row.type = type;
        row.entry = entry;
        rows.add(row);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Menu plugins");
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
        adapter = new MenuAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(context, position));
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        reload(context);
        fragmentView = layout;
        return layout;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getContext() != null) {
            reload(getContext());
        }
    }

    private void onRowClick(Context context, int position) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        String id = row.entry.plugin.id;
        switch (row.type) {
            case ROW_INCLUDE:
                JsPluginManager.setMenuIncluded(context, id, !row.entry.included);
                reload(context);
                break;
            case ROW_UP:
                JsPluginManager.moveMenuPlugin(context, id, -1);
                reload(context);
                break;
            case ROW_DOWN:
                JsPluginManager.moveMenuPlugin(context, id, 1);
                reload(context);
                break;
            case ROW_ICON:
                pickIcon(context, row.entry);
                break;
            case ROW_LABEL:
                editLabel(context, row.entry);
                break;
            default:
                break;
        }
    }

    private void pickIcon(Context context, JsPluginManager.MenuEntry entry) {
        List<String> labels = new ArrayList<>();
        labels.add("Default (manifest or plugin initial)");
        for (String name : ICON_NAMES) {
            labels.add(name.substring(0, 1).toUpperCase() + name.substring(1));
        }
        labels.add("Emoji…");
        labels.add("Clear override");
        CharSequence[] items = labels.toArray(new CharSequence[0]);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Icon for " + entry.plugin.name);
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                JsPluginManager.setMenuIcon(context, entry.plugin.id, null);
            } else if (which >= 1 && which <= ICON_NAMES.length) {
                JsPluginManager.setMenuIcon(context, entry.plugin.id, ICON_NAMES[which - 1]);
            } else if (which == ICON_NAMES.length + 1) {
                editEmoji(context, entry);
                return;
            } else {
                JsPluginManager.setMenuIcon(context, entry.plugin.id, null);
            }
            reload(context);
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void editEmoji(Context context, JsPluginManager.MenuEntry entry) {
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setHint("Type or paste one emoji");
        if (entry.icon != null && entry.icon.startsWith("emoji:")) {
            field.setText(entry.icon.substring("emoji:".length()));
        }
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        FrameLayout container = new FrameLayout(context);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        container.addView(field, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Emoji icon");
        builder.setView(container);
        builder.setPositiveButton("Save", (dialog, which) -> {
            String emoji = field.getText() == null ? "" : field.getText().toString().trim();
            JsPluginManager.setMenuIcon(context, entry.plugin.id,
                    emoji.isEmpty() ? null : "emoji:" + emoji);
            reload(context);
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void editLabel(Context context, JsPluginManager.MenuEntry entry) {
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setHint(entry.plugin.name);
        field.setText(entry.label == null ? "" : entry.label);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        FrameLayout container = new FrameLayout(context);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        container.addView(field, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Menu label");
        builder.setView(container);
        builder.setPositiveButton("Save", (dialog, which) -> {
            String label = field.getText() == null ? "" : field.getText().toString().trim();
            JsPluginManager.setMenuLabel(context, entry.plugin.id, label.isEmpty() ? null : label);
            reload(context);
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void bulletin(String text) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, text).show();
    }

    private class MenuAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public int getItemCount() {
            return Math.max(1, rows.size());
        }

        @Override
        public int getItemViewType(int position) {
            if (rows.isEmpty()) {
                return ROW_ENTRY;
            }
            return rows.get(position).type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position >= 0 && position < rows.size() && rows.get(position).type != ROW_ENTRY;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == ROW_INCLUDE) {
                view = new TextCheckCell(parent.getContext());
            } else {
                view = new TextCell(parent.getContext());
            }
            view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (rows.isEmpty()) {
                ((TextCell) holder.itemView).setText(
                        "No menu-capable plugins are installed", false);
                return;
            }
            Row row = rows.get(position);
            String name = row.entry.plugin.name;
            switch (row.type) {
                case ROW_ENTRY: {
                    TextCell cell = (TextCell) holder.itemView;
                    String icon = row.entry.icon == null ? "default" : row.entry.icon;
                    String label = row.entry.label == null ? name : row.entry.label;
                    cell.setTextAndValue(name, "Menu label: " + label + " · Icon: " + icon, true);
                    break;
                }
                case ROW_INCLUDE:
                    ((TextCheckCell) holder.itemView).setTextAndCheck(
                            "Show in attach menu", row.entry.included, true);
                    break;
                case ROW_UP:
                    ((TextCell) holder.itemView).setText("Move up", true);
                    break;
                case ROW_DOWN:
                    ((TextCell) holder.itemView).setText("Move down", true);
                    break;
                case ROW_ICON: {
                    TextCell cell = (TextCell) holder.itemView;
                    String iconRes = row.entry.icon == null ? "default"
                            : row.entry.icon.startsWith("emoji:") ? row.entry.icon
                            : "@drawable/amin_plugin_" + row.entry.icon;
                    cell.setTextAndValue("Icon", iconRes, true);
                    break;
                }
                case ROW_LABEL: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setTextAndValue("Label", row.entry.label == null ? name : row.entry.label, false);
                    break;
                }
            }
        }
    }
}
