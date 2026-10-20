package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.messenger.amin.plugins.js.BundledPlugins;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in plugin library (v2.0): bundled plugins that are not
 * currently installed. Deleting a bundled plugin removes it from
 * the hub completely; this page is the deliberate, one-tap way
 * back. Nothing here is a tombstone in the main hub.
 */
public class AminBundledLibraryActivity extends BaseFragment {

    private final List<BundledPlugins.Bundled> items = new ArrayList<>();
    private RecyclerListView listView;
    private LibraryAdapter adapter;

    private void reload(Context context) {
        items.clear();
        for (BundledPlugins.Bundled bundled : BundledPlugins.all()) {
            if (!BundledPlugins.isInstalled(context, bundled)) {
                items.add(bundled);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Built-in library");
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
        adapter = new LibraryAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) {
                return;
            }
            BundledPlugins.Bundled bundled = items.get(position);
            String error = BundledPlugins.install(context, bundled);
            if (error == null) {
                BulletinFactory.of(this).createSimpleBulletin(
                        R.raw.contact_check, bundled.name + " installed").show();
                reload(context);
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle("Plugin not installed");
                builder.setMessage(error);
                builder.setPositiveButton("OK", null);
                showDialog(builder.create());
            }
        });
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

    private class LibraryAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public int getItemCount() {
            return Math.max(1, items.size());
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getAdapterPosition() < items.size();
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
            if (items.isEmpty()) {
                cell.setText("All built-in plugins are installed", false);
                return;
            }
            BundledPlugins.Bundled bundled = items.get(position);
            cell.setTextAndValue(bundled.name, "Install", position < items.size() - 1);
        }
    }
}
