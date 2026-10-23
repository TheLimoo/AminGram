package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.messenger.amin.plugins.js.JsPluginManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Permission review screen (Platform Phase B, §3.2): shown
 * BEFORE any plugin install that arrives from outside the app —
 * a pasted plugin, a picked file, a catalog download, or a pack
 * entry. (Built-in library reinstalls of unchanged bundled code
 * and editor saves skip it — that code already ran on this
 * device with these permissions.)
 *
 * The screen shows, in plain language: what the plugin is
 * (name/author/version), where it came from (source) and its
 * SHA-256 short fingerprint, what it can do (capabilities,
 * high-impact first), what it offers other plugins (@exports)
 * and listens to (@topics), and every problem the manifest-level
 * preview found (@minPlatform, unmet @depends, non-semver
 * @version). On an UPDATE, capabilities the installed copy does
 * not have yet are listed separately and highlighted: adding
 * power is the one decision that must be explicit.
 *
 * The real install (JsPluginManager.installWithSource) still
 * runs the full validated path afterwards — probe, settings
 * schema, @depends gate — and its error is shown if it refuses.
 */
public class PluginReviewActivity extends BaseFragment {

    /** Called once when the review ends: installed (the install
     *  attempt succeeded), or not (skipped / refused / failed,
     *  with the reason). pluginId is "" when the code could not
     *  even be previewed. */
    public interface ReviewListener {
        void onReviewed(boolean installed, String pluginId, String error);
    }

    // Reviews are strictly sequential (one fragment at a time),
    // so the pending payload lives in statics handed over by the
    // factory — the same pending-registry pattern the file
    // picker uses.
    private static String pendingCode;
    private static String pendingSource;
    private static String pendingStorageJson;
    private static ReviewListener pendingListener;

    public static PluginReviewActivity forInstall(String code, String sourceTag,
                                                  String storageJson,
                                                  ReviewListener listener) {
        pendingCode = code;
        pendingSource = sourceTag;
        pendingStorageJson = storageJson;
        pendingListener = listener;
        return new PluginReviewActivity();
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_INFO = 1;
    private static final int TYPE_ACTION = 2;

    private final String code;
    private final String sourceTag;
    private final String storageJson;
    private final ReviewListener listener;
    private JsPluginManager.InstallPreview preview;
    private boolean finished;

    private final List<Integer> rowTypes = new ArrayList<>();
    private final List<String> rowTexts = new ArrayList<>();
    private int installRow = -1;
    private int cancelRow = -1;
    /** True while an async install is in flight (P1-7): the
     *  install row shows "Installing…" and further taps are
     *  ignored until the plugin thread reports back. */
    private boolean installing;
    private RecyclerListView listView;

    public PluginReviewActivity() {
        super();
        this.code = pendingCode;
        this.sourceTag = pendingSource != null ? pendingSource : "file";
        this.storageJson = pendingStorageJson;
        this.listener = pendingListener;
        pendingCode = null;
        pendingSource = null;
        pendingStorageJson = null;
        pendingListener = null;
    }

    /** The storage JSON handed to the review, for pack imports:
     *  the caller restores it after a successful install. */
    public String storageJson() {
        return storageJson;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Review plugin");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishReview(false, "cancelled");
                }
            }
        });
        preview = JsPluginManager.previewInstall(context, code != null ? code : "");
        if (preview.name != null && !preview.name.isEmpty()) {
            actionBar.setTitle(preview.name);
        }
        buildRows();

        FrameLayout layout = new FrameLayout(context);
        layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(
                context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(new ReviewAdapter());
        listView.setOnItemClickListener((view, position) -> onRowClick(context, position));
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fragmentView = layout;
        return layout;
    }

    private void addRow(int type, String text) {
        rowTypes.add(type);
        rowTexts.add(text);
    }

    /** §3.2's plain-language capability lines. */
    static String capabilityText(String cap) {
        switch (cap) {
            case "commands":
                return "Run commands you type (like /dl)";
            case "observe":
                return "See your incoming messages as they arrive";
            case "send":
                return "Send messages as you";
            case "act":
                return "Edit, react to, delete and pin messages as you";
            case "file":
                return "Send and download files, pick files, and keep its own files";
            case "http":
                return "Make internet requests";
            case "relay":
                return "Talk to other plugins and hear their events";
            case "ui":
                return "Show notifications, use the clipboard, open chats and links";
            case "settings":
                return "Keep its own settings and page here";
            case "menu":
                return "Add items to the attach (+) menu";
            case "google":
                return "Use your Google account sign-in (for Gemini)";
            default:
                return cap;
        }
    }

    /** High-impact first: account-acting powers, then reach,
     *  then conveniences — the order the user should weigh. */
    private static int capRank(String cap) {
        switch (cap) {
            case "act":
                return 0;
            case "send":
                return 1;
            case "google":
                return 2;
            case "file":
                return 3;
            case "http":
                return 4;
            case "observe":
                return 5;
            case "relay":
                return 6;
            case "ui":
                return 7;
            case "commands":
                return 8;
            case "menu":
                return 9;
            case "settings":
                return 10;
            default:
                return 50;
        }
    }

    private void buildRows() {
        rowTypes.clear();
        rowTexts.clear();
        installRow = -1;
        cancelRow = -1;
        addRow(TYPE_HEADER, preview.update ? "Update plugin" : "Install plugin");
        StringBuilder about = new StringBuilder();
        about.append(preview.name);
        if (preview.author != null && !preview.author.isEmpty()) {
            about.append(" · by ").append(preview.author);
        }
        about.append("\nVersion ").append(preview.version);
        if (preview.update) {
            about.append(" (installed: ").append(preview.installedVersion).append(")");
        }
        about.append("\nSource: ").append(sourceTag);
        about.append("\nFingerprint: ").append(preview.fingerprint);
        if (preview.description != null && !preview.description.isEmpty()) {
            about.append("\n\n").append(preview.description);
        }
        addRow(TYPE_INFO, about.toString());

        if (preview.error != null) {
            addRow(TYPE_HEADER, "Cannot install");
            addRow(TYPE_INFO, preview.error);
            cancelRow = rowTypes.size();
            addRow(TYPE_ACTION, "Close");
            return;
        }

        List<String> caps = new ArrayList<>(preview.capabilities);
        caps.sort((a, b) -> Integer.compare(capRank(a), capRank(b)));
        addRow(TYPE_HEADER, "This plugin can:");
        if (caps.isEmpty()) {
            addRow(TYPE_INFO, "Nothing special — it only runs when you open it.");
        }
        for (String cap : caps) {
            boolean added = preview.update && preview.addedCapabilities.contains(cap);
            addRow(TYPE_INFO, (added ? "➕ NEW: " : "• ") + capabilityText(cap));
        }
        if (preview.update && !preview.addedCapabilities.isEmpty()) {
            addRow(TYPE_INFO, "⚠ This update ADDS the powers marked NEW above. "
                    + "Installing confirms you accept them.");
        }
        if (preview.contextMenu != null && !preview.contextMenu.isEmpty()) {
            addRow(TYPE_INFO, "• Adds \"" + preview.contextMenu
                    + "\" to the message long-press menu");
        }
        if (!preview.exports.isEmpty() || !preview.topics.isEmpty()) {
            addRow(TYPE_HEADER, "Plugin-to-plugin");
            if (!preview.exports.isEmpty()) {
                addRow(TYPE_INFO, "Offers to other plugins: "
                        + String.join(", ", preview.exports));
            }
            if (!preview.topics.isEmpty()) {
                addRow(TYPE_INFO, "Listens for events: "
                        + String.join(", ", preview.topics));
            }
        }
        if (!preview.depends.isEmpty()) {
            addRow(TYPE_INFO, "Needs other plugins: "
                    + String.join(", ", preview.depends));
        }
        if (!preview.problems.isEmpty()) {
            addRow(TYPE_HEADER, "Problems");
            for (String problem : preview.problems) {
                addRow(TYPE_INFO, "⚠ " + problem);
            }
        }
        installRow = rowTypes.size();
        addRow(TYPE_ACTION, preview.update ? "Update" : "Install");
        cancelRow = rowTypes.size();
        addRow(TYPE_ACTION, "Cancel");
    }

    private void onRowClick(Context context, int position) {
        if (position == installRow) {
            if (installing) {
                return;
            }
            // The validated install (compile + probe of untrusted
            // JS) runs on the plugin thread, never here on the UI
            // thread (audit P1-7).
            installing = true;
            rowTexts.set(installRow, "Installing…");
            if (listView != null && listView.getAdapter() != null) {
                listView.getAdapter().notifyItemChanged(installRow);
            }
            JsPluginManager.installAsync(context, code, sourceTag, error -> {
                installing = false;
                if (error == null) {
                    BulletinFactory.of(PluginReviewActivity.this)
                            .createSimpleBulletin(R.raw.contact_check,
                                    (preview.update ? "Updated: " : "Installed: ") + preview.name)
                            .show();
                    finishReview(true, null);
                } else {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                    builder.setTitle("Plugin not installed");
                    builder.setMessage(error);
                    builder.setPositiveButton("OK", null);
                    showDialog(builder.create());
                    finishReview(false, error);
                }
            });
        } else if (position == cancelRow) {
            finishReview(false, "skipped");
        }
    }

    private void finishReview(boolean installed, String error) {
        if (finished) {
            return;
        }
        finished = true;
        ReviewListener l = listener;
        String id = preview != null ? preview.id : "";
        finishFragment();
        if (l != null) {
            l.onReviewed(installed, id, error);
        }
    }

    @Override
    public void onFragmentDestroy() {
        // Back-button path: still report (as a skip) so chained
        // flows (packs) can advance to their summary.
        if (!finished) {
            finished = true;
            if (listener != null) {
                listener.onReviewed(false,
                        preview != null ? preview.id : "", "skipped");
            }
        }
        super.onFragmentDestroy();
    }

    private class ReviewAdapter extends RecyclerListView.SelectionAdapter {

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
            return rowTypes.get(holder.getAdapterPosition()) == TYPE_ACTION;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            if (viewType == TYPE_HEADER) {
                view = new HeaderCell(context);
            } else if (viewType == TYPE_INFO) {
                view = new TextInfoPrivacyCell(context);
            } else {
                view = new TextCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = rowTypes.get(position);
            String text = rowTexts.get(position);
            if (type == TYPE_HEADER) {
                ((HeaderCell) holder.itemView).setText(text);
            } else if (type == TYPE_INFO) {
                ((TextInfoPrivacyCell) holder.itemView).setText(text);
            } else {
                ((TextCell) holder.itemView).setText(text,
                        position < rowTypes.size() - 1);
            }
        }
    }
}
