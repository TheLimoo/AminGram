package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * One hub category page (hub v3): the rows the pre-v3 hub showed
 * all on one screen, grouped under mono dossier labels — merged
 * module cards (title + description + status tail + switch),
 * their action rows inside the same block, toggle/pick children
 * indented on the hairline guide. All builders, clicks and
 * dialogs live in {@link HubCommon}; this fragment is the shell
 * (house style: constructor-arg fragment, like
 * AminStoreListActivity).
 *
 * Opened plain from the home index, or with a highlight key from
 * home search — the page then scrolls to the matching row and
 * flashes it once. Navigation only: arriving never toggles.
 */
public class AminHubPageActivity extends BaseFragment {

    private final String categoryId;
    private final String highlightKey;
    private HubCommon hub;

    public AminHubPageActivity(String categoryId) {
        this(categoryId, null);
    }

    public AminHubPageActivity(String categoryId, String highlightKey) {
        this.categoryId = categoryId;
        this.highlightKey = highlightKey;
    }

    @Override
    public View createView(Context context) {
        HubCommon.Category category = HubCommon.categoryById(categoryId);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(category != null ? category.name : "Hub");
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

        hub = new HubCommon(HubCommon.hostFor(this), HubCommon.MODE_PAGE, categoryId);
        if (highlightKey != null) {
            hub.setHighlight(highlightKey);
        }
        View root = hub.createRootView(context);
        fragmentView = root;
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (hub != null) {
            hub.onResume();
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
}
