package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.core.DeletedStore;
import org.telegram.messenger.amin.core.VaultStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;

import java.util.List;

/**
 * Generic read-only viewer for a VaultStore list (Config Vault links,
 * Keyword Radar hits, Auto-Saver log). Tap a row to copy it; the last
 * row clears the list. Newest entries are at the bottom of the store,
 * shown newest-first here.
 */
public class AminStoreListActivity extends BaseFragment {

    private final String title;
    private final String prefsName;
    private final String key;
    private List<String> items;
    private RecyclerListView listView;
    private StoreAdapter adapter;

    public AminStoreListActivity(String title, String prefsName, String key) {
        super();
        this.title = title;
        this.prefsName = prefsName;
        this.key = key;
    }

    private void reload(Context context) {
        items = VaultStore.getAll(context, prefsName, key);
        // reverse: newest first
        for (int i = 0, j = items.size() - 1; i < j; i++, j--) {
            String tmp = items.get(i);
            items.set(i, items.get(j));
            items.set(j, tmp);
        }
        if (DeletedStore.EDIT_KEY.equals(key)) {
            // Edit records are stored raw (dialogId|msgId|date|old -> new);
            // render them readably (title + time + quoted change).
            for (int i = 0; i < items.size(); i++) {
                items.set(i, DeletedStore.formatEditRecord(currentAccount, items.get(i)));
            }
        } else if (DeletedStore.DELETED_KEY.equals(key)) {
            // Deleted records are raw too (dialogId|msgId|date|kind|text).
            for (int i = 0; i < items.size(); i++) {
                items.set(i, DeletedStore.formatDeletedRecord(currentAccount, items.get(i)));
            }
        } else if (org.telegram.messenger.amin.core.MessagePipeline.RADAR_HITS_KEY.equals(key)) {
            // Radar hits are raw too (dialogId|msgId|keyword|date|snippet).
            for (int i = 0; i < items.size(); i++) {
                items.set(i, org.telegram.messenger.amin.core.MessagePipeline.formatRadarHit(
                        currentAccount, items.get(i)));
            }
        }
    }

    @Override
    public View createView(Context context) {
        reload(context);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(title);
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
        adapter = new StoreAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (items == null || items.isEmpty()) {
                return;
            }
            if (position < items.size()) {
                String value = items.get(position);
                AndroidUtilities.addToClipboard(value);
                BulletinFactory.of(AminStoreListActivity.this)
                        .createSimpleBulletin(R.raw.copy, "Copied to clipboard")
                        .show();
            } else if (position == items.size()) {
                VaultStore.clear(context, prefsName, key);
                reload(context);
                adapter.notifyDataSetChanged();
                BulletinFactory.of(AminStoreListActivity.this)
                        .createSimpleBulletin(R.raw.ic_delete, "List cleared")
                        .show();
            }
        });
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fragmentView = layout;
        return layout;
    }

    private class StoreAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            if (items == null || items.isEmpty()) {
                return 1; // "empty" row
            }
            return items.size() + 1; // + clear row
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextCell cell = new TextCell(parent.getContext());
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            cell.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TextCell cell = (TextCell) holder.itemView;
            if (items == null || items.isEmpty()) {
                cell.setText("Nothing here yet", false);
                return;
            }
            if (position < items.size()) {
                String value = items.get(position);
                int cap = (DeletedStore.EDIT_KEY.equals(key) || DeletedStore.DELETED_KEY.equals(key)
                        || org.telegram.messenger.amin.core.MessagePipeline.RADAR_HITS_KEY.equals(key)) ? 600 : 140;
                if (value.length() > cap) {
                    value = value.substring(0, cap) + "…";
                }
                cell.setText(value, position < items.size() - 1);
            } else {
                cell.setText("Clear this list", false);
            }
        }
    }
}
