package org.telegram.messenger.amin.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.core.MessagePipeline;
import org.telegram.messenger.amin.core.VaultStore;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.List;

/**
 * AminGram Plugins hub. Generated from {@link PluginRegistry}: one switch
 * row per module, plus a Tools section (video background picker, keyword
 * editor, and viewers for Config Vault / Radar hits / Auto-Saver log).
 */
public class AminPluginsActivity extends BaseFragment {

    private static final int REQUEST_VIDEO_BG = 3917;

    private RecyclerListView listView;
    private PluginsAdapter adapter;
    private List<ModModule> modules;

    // Row layout: 0 header, 1..N modules, N+1 header, then tools.
    private int toolsHeaderPos() { return modules.size() + 1; }
    private int rowVideoSet() { return modules.size() + 2; }
    private int rowVideoClear() { return modules.size() + 3; }
    private int rowKeywords() { return modules.size() + 4; }
    private int rowVault() { return modules.size() + 5; }
    private int rowRadarHits() { return modules.size() + 6; }
    private int rowSaverLog() { return modules.size() + 7; }
    private int rowCount() { return modules.size() + 8; }

    @Override
    public View createView(Context context) {
        modules = PluginRegistry.modules();

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Plugins");
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
        adapter = new PluginsAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(context, view, position));
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fragmentView = layout;
        return layout;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void onRowClick(Context context, View view, int position) {
        if (position >= 1 && position <= modules.size()) {
            ModModule module = modules.get(position - 1);
            boolean now = !PluginRegistry.isEnabled(context, module);
            PluginRegistry.setEnabled(context, module, now);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setTextAndCheck(module.title(), now, true);
            }
            return;
        }
        if (position == rowVideoSet()) {
            pickVideo();
        } else if (position == rowVideoClear()) {
            if (VideoBackgroundHelper.getVideoPath(context) != null) {
                VideoBackgroundHelper.setVideoPath(context, null);
                bulletin(R.raw.ic_delete, "Video background cleared");
            } else {
                bulletin(R.raw.chats_infotip, "No video background set");
            }
            adapter.notifyDataSetChanged();
        } else if (position == rowKeywords()) {
            editKeywords(context);
        } else if (position == rowVault()) {
            presentFragment(new AminStoreListActivity("Config Vault",
                    MessagePipeline.VAULT_PREFS, MessagePipeline.VAULT_ITEMS_KEY));
        } else if (position == rowRadarHits()) {
            presentFragment(new AminStoreListActivity("Keyword Radar hits",
                    MessagePipeline.RADAR_PREFS, MessagePipeline.RADAR_HITS_KEY));
        } else if (position == rowSaverLog()) {
            presentFragment(new AminStoreListActivity("Auto-Saver log",
                    MessagePipeline.AUTOSAVER_PREFS, MessagePipeline.AUTOSAVER_ITEMS_KEY));
        }
    }

    private void bulletin(int icon, String text) {
        BulletinFactory.of(this).createSimpleBulletin(icon, text).show();
    }

    private void pickVideo() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_VIDEO_BG);
        } catch (Exception e) {
            FileLog.e(e);
            bulletin(R.raw.error, "No file picker available");
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_VIDEO_BG) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String path = AndroidUtilities.getPath(uri);
        if (path == null || path.startsWith("content://")) {
            path = MediaController.copyFileToCache(uri, "mp4");
        }
        Context context = getContext();
        if (path != null && context != null) {
            VideoBackgroundHelper.setVideoPath(context, path);
            bulletin(R.raw.contact_check, "Video background set — reopen a chat");
        } else {
            bulletin(R.raw.error, "Could not read that file");
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void editKeywords(Context context) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setText(MessagePipeline.getKeywordsRaw(context));
        editText.setHint("keyword1, keyword2, …");
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));

        FrameLayout container = new FrameLayout(context);
        container.addView(editText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8),
                AndroidUtilities.dp(24), 0);
        editText.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Keyword Radar keywords");
        builder.setView(container);
        builder.setPositiveButton("Save", (dialog, which) ->
                MessagePipeline.setKeywords(context, editText.getText().toString()));
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private class PluginsAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return rowCount();
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0 || position == toolsHeaderPos()) {
                return 0; // header
            }
            if (position >= 1 && position <= modules.size()) {
                return 1; // module check
            }
            return 2; // tool row
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position > 0 && position != toolsHeaderPos();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                view = new HeaderCell(parent.getContext());
            } else if (viewType == 1) {
                view = new TextCheckCell(parent.getContext());
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextCell(parent.getContext());
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Context context = holder.itemView.getContext();
            if (position == 0) {
                ((HeaderCell) holder.itemView).setText("Feature modules");
                return;
            }
            if (position == toolsHeaderPos()) {
                ((HeaderCell) holder.itemView).setText("Tools");
                return;
            }
            if (position >= 1 && position <= modules.size()) {
                ModModule module = modules.get(position - 1);
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndCheck(module.title(),
                        PluginRegistry.isEnabled(context, module), true);
                return;
            }
            TextCell cell = (TextCell) holder.itemView;
            boolean last = position == rowCount() - 1;
            if (position == rowVideoSet()) {
                String path = VideoBackgroundHelper.getVideoPath(context);
                cell.setTextAndValue("Video background file",
                        path == null ? "Not set" : new File(path).getName(), !last);
            } else if (position == rowVideoClear()) {
                cell.setText("Clear video background", !last);
            } else if (position == rowKeywords()) {
                int count = MessagePipeline.loadKeywords(context).size();
                cell.setTextAndValue("Keyword Radar keywords",
                        count == 0 ? "None" : count + " set", !last);
            } else if (position == rowVault()) {
                int count = VaultStore.getAll(context,
                        MessagePipeline.VAULT_PREFS, MessagePipeline.VAULT_ITEMS_KEY).size();
                cell.setTextAndValue("Config Vault", String.valueOf(count), !last);
            } else if (position == rowRadarHits()) {
                int count = VaultStore.getAll(context,
                        MessagePipeline.RADAR_PREFS, MessagePipeline.RADAR_HITS_KEY).size();
                cell.setTextAndValue("Keyword Radar hits", String.valueOf(count), !last);
            } else if (position == rowSaverLog()) {
                int count = VaultStore.getAll(context,
                        MessagePipeline.AUTOSAVER_PREFS, MessagePipeline.AUTOSAVER_ITEMS_KEY).size();
                cell.setTextAndValue("Auto-Saver log", String.valueOf(count), !last);
            }
        }
    }
}
