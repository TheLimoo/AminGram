package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.List;

/**
 * AminGram Plugins hub. The list is generated from {@link PluginRegistry}:
 * one switch row per registered module. Toggling persists via the registry.
 */
public class AminPluginsActivity extends BaseFragment {

    private RecyclerListView listView;
    private List<ModModule> modules;

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
        listView.setAdapter(new PluginsAdapter());
        listView.setOnItemClickListener((view, position) -> {
            if (position <= 0 || position > modules.size()) {
                return;
            }
            ModModule module = modules.get(position - 1);
            boolean now = !PluginRegistry.isEnabled(context, module);
            PluginRegistry.setEnabled(context, module, now);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setTextAndCheck(module.title(), now, position < modules.size());
            }
        });
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fragmentView = layout;
        return layout;
    }

    private class PluginsAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return modules.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? 0 : 1;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getAdapterPosition() > 0;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                view = new HeaderCell(parent.getContext());
            } else {
                view = new TextCheckCell(parent.getContext());
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position == 0) {
                ((HeaderCell) holder.itemView).setText("Feature modules");
                return;
            }
            ModModule module = modules.get(position - 1);
            TextCheckCell cell = (TextCheckCell) holder.itemView;
            cell.setTextAndCheck(module.title(),
                    PluginRegistry.isEnabled(holder.itemView.getContext(), module),
                    position < modules.size());
        }
    }
}
