package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.plugins.js.JsPlugin;
import org.telegram.messenger.amin.plugins.js.JsPluginManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * One JS plugin's page in the hub: about info, the enable toggle, the
 * settings page generated from the plugin's SETTINGS schema (values
 * land in the same per-plugin storage amin.storage reads), its log,
 * and delete. Reached from the "Your Plugins" section of the hub.
 */
public class AminJsPluginActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_INFO = 1;
    private static final int TYPE_TOGGLE = 2;
    private static final int TYPE_SETTING_BOOL = 3;
    private static final int TYPE_SETTING_TEXT = 4;
    private static final int TYPE_ACTION = 5;

    private final String pluginId;
    private JsPlugin plugin;
    private RecyclerListView listView;
    private PluginPageAdapter adapter;

    // Row indices, rebuilt by buildRows().
    private final List<Integer> rowTypes = new ArrayList<>();
    private final List<Object> rowData = new ArrayList<>();
    private int toggleRow = -1;
    private int logRow = -1;
    private int deleteRow = -1;

    public AminJsPluginActivity(String pluginId) {
        super();
        this.pluginId = pluginId;
    }

    @Override
    public View createView(Context context) {
        plugin = JsPluginManager.get(context, pluginId);
        buildRows();

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(plugin != null ? plugin.name : "Plugin");
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
        adapter = new PluginPageAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(context, view, position));
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fragmentView = layout;
        return layout;
    }

    private void buildRows() {
        rowTypes.clear();
        rowData.clear();
        toggleRow = -1;
        logRow = -1;
        deleteRow = -1;
        if (plugin == null) {
            return;
        }
        addRow(TYPE_HEADER, "About");
        StringBuilder about = new StringBuilder();
        about.append(plugin.description);
        about.append("\n\nVersion ").append(plugin.version)
                .append(" · by ").append(plugin.author);
        about.append("\nCapabilities: ").append(plugin.capabilitiesText());
        StringBuilder hooks = new StringBuilder();
        if (plugin.hasMessage) {
            hooks.append("onMessage ");
        }
        if (plugin.hasCommand) {
            hooks.append("onCommand ");
        }
        if (plugin.hasMenu) {
            hooks.append("onMenuAction ");
        }
        if (hooks.length() > 0) {
            about.append("\nHooks: ").append(hooks.toString().trim());
        }
        addRow(TYPE_INFO, about.toString());
        toggleRow = addRow(TYPE_TOGGLE, plugin);
        if (!plugin.settings.isEmpty()) {
            addRow(TYPE_HEADER, "Settings");
            for (JsPlugin.SettingDef def : plugin.settings) {
                addRow("bool".equals(def.type) ? TYPE_SETTING_BOOL : TYPE_SETTING_TEXT, def);
            }
        }
        addRow(TYPE_HEADER, "Plugin");
        logRow = addRow(TYPE_ACTION, "View log");
        deleteRow = addRow(TYPE_ACTION, "Delete plugin");
    }

    private int addRow(int type, Object data) {
        rowTypes.add(type);
        rowData.add(data);
        return rowTypes.size() - 1;
    }

    private void onRowClick(Context context, View view, int position) {
        if (plugin == null || position < 0 || position >= rowTypes.size()) {
            return;
        }
        int type = rowTypes.get(position);
        if (position == toggleRow && type == TYPE_TOGGLE) {
            boolean now = !plugin.enabled;
            JsPluginManager.setEnabled(context, plugin, now);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setTextAndCheck(plugin.name, now, true);
            }
        } else if (type == TYPE_SETTING_BOOL) {
            JsPlugin.SettingDef def = (JsPlugin.SettingDef) rowData.get(position);
            boolean now = !boolValue(context, def);
            JsPluginManager.storageSetRaw(context, plugin.id, def.key, now ? "true" : "false");
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setTextAndCheck(def.title, now, true);
            }
        } else if (type == TYPE_SETTING_TEXT) {
            editTextSetting(context, (JsPlugin.SettingDef) rowData.get(position));
        } else if (position == logRow) {
            presentFragment(new AminStoreListActivity(plugin.name + " — log",
                    JsPluginManager.PREFS, "log_" + plugin.id));
        } else if (position == deleteRow) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Delete " + plugin.name + "?");
            builder.setMessage("The plugin file, its settings and its log are removed.");
            builder.setPositiveButton("Delete", (dialog, which) -> {
                JsPluginManager.delete(context, plugin);
                BulletinFactory.of(AminJsPluginActivity.this)
                        .createSimpleBulletin(R.raw.ic_delete, "Plugin deleted").show();
                finishFragment();
            });
            builder.setNegativeButton("Cancel", null);
            showDialog(builder.create());
        }
    }

    private boolean boolValue(Context context, JsPlugin.SettingDef def) {
        String raw = JsPluginManager.storageGetRaw(context, plugin.id, def.key);
        if (raw != null) {
            return "true".equals(raw);
        }
        return def.def instanceof Boolean && (Boolean) def.def;
    }

    private String textValue(Context context, JsPlugin.SettingDef def) {
        String raw = JsPluginManager.storageGetRaw(context, plugin.id, def.key);
        if (raw != null) {
            return raw;
        }
        return def.def == null ? "" : String.valueOf(def.def);
    }

    private void editTextSetting(Context context, JsPlugin.SettingDef def) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setText(textValue(context, def));
        editText.setHint(def.title);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));

        FrameLayout container = new FrameLayout(context);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8),
                AndroidUtilities.dp(24), 0);
        container.addView(editText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(def.title);
        builder.setView(container);
        builder.setPositiveButton("Save", (dialog, which) -> {
            JsPluginManager.storageSetRaw(context, plugin.id, def.key,
                    editText.getText().toString());
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private class PluginPageAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return rowTypes.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rowTypes.get(position);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = rowTypes.get(holder.getAdapterPosition());
            return type != TYPE_HEADER && type != TYPE_INFO;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(context);
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case TYPE_TOGGLE:
                case TYPE_SETTING_BOOL:
                    view = new TextCheckCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                default:
                    view = new TextCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Context context = holder.itemView.getContext();
            int type = rowTypes.get(position);
            boolean last = position == rowTypes.size() - 1;
            switch (type) {
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText((String) rowData.get(position));
                    break;
                case TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText((String) rowData.get(position));
                    break;
                case TYPE_TOGGLE:
                    ((TextCheckCell) holder.itemView).setTextAndCheck(
                            plugin.name, plugin.enabled, true);
                    break;
                case TYPE_SETTING_BOOL: {
                    JsPlugin.SettingDef def = (JsPlugin.SettingDef) rowData.get(position);
                    ((TextCheckCell) holder.itemView).setTextAndCheck(
                            def.title, boolValue(context, def), true);
                    break;
                }
                case TYPE_SETTING_TEXT: {
                    JsPlugin.SettingDef def = (JsPlugin.SettingDef) rowData.get(position);
                    String value = textValue(context, def);
                    ((TextCell) holder.itemView).setTextAndValue(
                            def.title, value.isEmpty() ? "Not set" : value, !last);
                    break;
                }
                default:
                    ((TextCell) holder.itemView).setText((String) rowData.get(position), !last);
                    break;
            }
        }
    }
}
