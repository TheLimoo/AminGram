package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * The AminGram hub — v3 "dossier" home.
 *
 * The home is an index, not a settings list: masthead (力 badge,
 * AMINGRAM wordmark, marble strip), a 2×2 grid of live stats read
 * from the same stores the feature rows use, a real search field
 * (index built from the same row builders as the category pages; a
 * result tap navigates and flashes — it never toggles), the three
 * pinned quick-access toggles, and the six category rows that open
 * {@link AminHubPageActivity}. All rows, actions and dialogs live
 * in {@link HubCommon}; the skin is {@link HubSkin} — hub-local
 * constants and hub-loaded typefaces on hub views only.
 *
 * This class keeps its name and its no-arg construction: patch
 * 0001 instantiates it directly, and the developer-mode statics
 * below are read by the plugin pages.
 */
public class AminPluginsActivity extends BaseFragment {

    /** Developer mode (§3.5): gates the plugin pages' Developer
     *  sections (eval console, storage inspector, live log tail).
     *  In-memory ONLY — off by default, and it re-locks every
     *  session because nothing is persisted: a process restart
     *  starts with it off again. */
    private static volatile boolean developerMode;

    public static boolean isDeveloperMode() {
        return developerMode;
    }

    public static void setDeveloperMode(boolean on) {
        developerMode = on;
    }

    /** One-shot request set by a category page's "Search plugins"
     *  row (via HubCommon's host): the page finishes itself, and
     *  home consumes this on resume, opening its search field
     *  pre-filtered to plugins. */
    private static volatile boolean pluginsSearchRequested;

    public static void requestPluginsSearch() {
        pluginsSearchRequested = true;
    }

    private HubCommon hub;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("AminGram Hub");
        actionBar.setBackgroundColor(HubSkin.BG);
        actionBar.setTitleColor(HubSkin.BONE);
        actionBar.setItemsColor(HubSkin.BONE, false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        hub = new HubCommon(HubCommon.hostFor(this), HubCommon.MODE_HOME, null);
        View root = hub.createRootView(context);
        fragmentView = root;
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (hub != null) {
            hub.onResume();
            if (pluginsSearchRequested) {
                pluginsSearchRequested = false;
                hub.enterPluginSearch();
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (hub != null) {
            hub.onPause();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (hub != null) {
            hub.onActivityResultFragment(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (hub != null && hub.isSearching()) {
            if (invoked) {
                hub.exitSearch();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }
}
