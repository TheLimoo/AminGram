package org.telegram.messenger.amin.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.webkit.MimeTypeMap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.core.AutoSaverStore;
import org.telegram.messenger.amin.core.BackupManager;
import org.telegram.messenger.amin.core.DeletedStore;
import org.telegram.messenger.amin.core.DesignThemes;
import org.telegram.messenger.amin.core.HiddenChatsStore;
import org.telegram.messenger.amin.core.MessagePipeline;
import org.telegram.messenger.amin.core.SendWhenOnlineStore;
import org.telegram.messenger.amin.core.VaultStore;
import org.telegram.messenger.amin.google.GoogleAuthManager;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.messenger.amin.plugins.js.BundledPlugins;
import org.telegram.messenger.amin.plugins.js.JsPlugin;
import org.telegram.messenger.amin.plugins.js.JsPluginManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AminGram Plugins hub. Sectioned, settings-style:
 *
 * - Privacy / Media / Tools: the built-in modules from PluginRegistry,
 *   each with a switch, a one-line description and a live STATUS line
 *   (Active / Needs a video file / N items saved / …), plus the
 *   module's own actions directly underneath (viewers, pickers,
 *   editors, Hidden Chats management, the Send-When-Online queue,
 *   backup export/import, app icon).
 * - Your Plugins: the JS plugin system — add from file or by pasting
 *   code, one-tap bundled examples, and every installed plugin with
 *   its toggle; tapping a plugin's description opens its page
 *   (settings generated from its SETTINGS schema, log, delete).
 *
 * v1.3.2 presentation: the flat v1.3 list (upstream cells wall to
 * wall, multi-line gray descriptions with the status glued on) was
 * replaced by a card design — modules/plugins render as rounded
 * white cards on the gray page: icon-chip header + switch, ONE
 * truncated subtitle line, a colored status dot line, and the
 * module's actions as hairline-separated rows inside the card.
 *
 * v1.3.3 presentation (exteraGram design language, Amin's pick):
 * ONE rounded group card per module SECTION (all of Privacy / Media
 * / Tools in a single card, extera "General page" style) with
 * accent-colored medium section labels and inset hairlines; a hub
 * header block (shield + name + version); a full-width accent
 * "Enable Plugins Engine" pill driving JsPluginManager's real
 * engine_enabled kill-switch; and each JS plugin as a RICH CARD
 * (icon + working switch, big name, Version · @author line,
 * capability chips, settings/log/delete action row) replacing the
 * old toggle+info row pair — the card's buttons run the exact
 * operations the plugin page runs, and tapping the card still
 * opens that page. The two design modules (amin_design /
 * ios_theme) toggle through amin.core.DesignThemes with mutual
 * exclusion and snapshot restore. Row ids, action ids, data and
 * flows are otherwise exactly the v1.3 ones.
 */
public class AminPluginsActivity extends BaseFragment {

    private static final int REQUEST_VIDEO_BG = 3917;
    private static final int REQUEST_PLUGIN_FILE = 3918;
    private static final int REQUEST_BACKUP_EXPORT = 3919;
    private static final int REQUEST_BACKUP_IMPORT = 3920;

    private static final int KIND_HEADER = 0;
    private static final int KIND_MODULE = 1;
    private static final int KIND_INFO = 2;
    private static final int KIND_ACTION = 3;
    private static final int KIND_FOOTER = 6;
    private static final int KIND_HUB_HEADER = 7;
    private static final int KIND_ENGINE = 8;
    private static final int KIND_PLUGIN_CARD = 9;

    // v1.3.2 card presentation: consecutive rows that belong together
    // (a module header + its subtitle/status + its action rows, a
    // plugin header + its info, a bundled row + its tagline, the two
    // add-plugin rows) are rendered as segments of ONE rounded card
    // floating on the gray page background. Segments are assigned in
    // assignSegments() after the row list is built; all ids, actions
    // and click behaviour are unchanged from the flat v1.3 list.
    private static final int SEG_NONE = 0;
    private static final int SEG_ONLY = 1;
    private static final int SEG_FIRST = 2;
    private static final int SEG_MID = 3;
    private static final int SEG_LAST = 4;

    // Status-dot tones for InfoStatusView.
    private static final int TONE_NONE = 0;
    private static final int TONE_ACTIVE = 1;
    private static final int TONE_ATTENTION = 2;
    private static final int TONE_MUTED = 3;

    private static final class Row {
        int kind;
        String title;
        String value;
        String action;
        ModModule module;
        JsPlugin plugin;
        BundledPlugins.Bundled bundled;
        SendWhenOnlineStore.Entry swoEntry;
        // Presentation-only fields:
        int seg = SEG_NONE;
        String statusText;
        int statusTone = TONE_NONE;
        boolean accent;
        boolean multiline;
        /** Info rows that align at the plain 16dp text inset instead
         *  of the module text column (bundled taglines, the note). */
        boolean flat;
    }

    private RecyclerListView listView;
    private HubAdapter adapter;
    private final List<Row> rows = new ArrayList<>();
    private final Runnable swoListener = () -> AndroidUtilities.runOnUIThread(this::rebuild);

    @Override
    public View createView(Context context) {
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
        adapter = new HubAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(view, position));
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rebuild();
        fragmentView = layout;
        return layout;
    }

    @Override
    public void onResume() {
        super.onResume();
        SendWhenOnlineStore.addListener(swoListener);
        rebuild();
    }

    @Override
    public void onPause() {
        super.onPause();
        SendWhenOnlineStore.removeListener(swoListener);
    }

    private void rebuild() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        rows.clear();
        Row brand = new Row();
        brand.kind = KIND_HUB_HEADER;
        rows.add(brand);
        addSection(context, "Privacy", ModModule.SECTION_PRIVACY);
        addSection(context, "Media", ModModule.SECTION_MEDIA);
        addSection(context, "Tools", ModModule.SECTION_TOOLS);
        // v1.3.5: Google Account closes the Tools card (an action
        // row added here still joins the Tools group card in
        // assignSegments — the group only closes at the next
        // section header).
        String googleValue = "Not connected";
        try {
            if (GoogleAuthManager.isSignedIn(context)) {
                String email = GoogleAuthManager.connectedEmail(context);
                googleValue = email != null ? email : "Connected";
            }
        } catch (Throwable ignored) {
        }
        action("google_account", "Google Account", googleValue);
        addPluginsSection(context);
        Row footer = new Row();
        footer.kind = KIND_FOOTER;
        footer.title = "AminGram features run on Telegram's own sync — no extra background "
                + "work, no polling. If Android force-stops the app, queued actions "
                + "(like Send When Online) fire on the next launch.";
        rows.add(footer);
        assignSegments();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** Groups rows into card segments (v1.3.3): ONE group card per
     *  module section — every module header + info + action row
     *  between two section labels joins a single card (sections are
     *  separated by their labels). The engine pill and each plugin
     *  rich card are standalone (SEG_ONLY). In Your Plugins, each
     *  bundled row + its tagline and the two add-plugin rows form
     *  their own small cards. Labels, the hub header, the footer
     *  and the standalone "no plugins yet" note stay uncarded. */
    private void assignSegments() {
        for (Row row : rows) {
            row.seg = SEG_NONE;
        }
        int start = -1;
        for (int i = 0; i <= rows.size(); i++) {
            // 0 = join the open group, 1 = close the group (row
            // itself uncarded), 2 = close + standalone card,
            // 3 = close + start a fresh group at this row.
            int mode;
            if (i == rows.size()) {
                mode = 1;
            } else {
                Row row = rows.get(i);
                switch (row.kind) {
                    case KIND_ENGINE:
                    case KIND_PLUGIN_CARD:
                        mode = 2;
                        break;
                    case KIND_ACTION:
                        mode = "bundled".equals(row.action)
                                || "add_plugin_file".equals(row.action) ? 3 : 0;
                        break;
                    case KIND_INFO:
                        mode = "note".equals(row.action) ? 1 : 0;
                        break;
                    case KIND_MODULE:
                        mode = 0;
                        break;
                    default: // KIND_HEADER, KIND_HUB_HEADER, KIND_FOOTER
                        mode = 1;
                        break;
                }
            }
            if (mode != 0 && start >= 0) {
                closeGroup(start, i);
                start = -1;
            }
            if (mode == 2) {
                rows.get(i).seg = SEG_ONLY;
            } else if (mode == 3 || (mode == 0 && start < 0)) {
                start = i;
            }
        }
    }

    private void closeGroup(int start, int endExclusive) {
        if (endExclusive - start == 1) {
            rows.get(start).seg = SEG_ONLY;
        } else {
            rows.get(start).seg = SEG_FIRST;
            rows.get(endExclusive - 1).seg = SEG_LAST;
            for (int j = start + 1; j < endExclusive - 1; j++) {
                rows.get(j).seg = SEG_MID;
            }
        }
    }

    private void addSection(Context context, String title, int section) {
        Row header = new Row();
        header.kind = KIND_HEADER;
        header.title = title;
        rows.add(header);
        for (ModModule module : PluginRegistry.modules()) {
            if (module.section() != section) {
                continue;
            }
            Row row = new Row();
            row.kind = KIND_MODULE;
            row.module = module;
            rows.add(row);
            String status = "";
            try {
                status = module.status(context);
            } catch (Throwable ignored) {
            }
            if (status == null) {
                status = "";
            }
            Row info = new Row();
            info.kind = KIND_INFO;
            info.title = module.description();
            boolean enabled = PluginRegistry.isEnabled(context, module);
            if (!enabled) {
                info.statusText = "Off";
                info.statusTone = TONE_MUTED;
            } else if (status.startsWith("Needs")) {
                info.statusText = status;
                info.statusTone = TONE_ATTENTION;
            } else {
                info.statusText = status.isEmpty() ? "Active" : status;
                info.statusTone = TONE_ACTIVE;
            }
            rows.add(info);
            addModuleActions(context, module);
        }
    }

    private Row action(String actionId, String title, String value) {
        Row row = new Row();
        row.kind = KIND_ACTION;
        row.action = actionId;
        row.title = title;
        row.value = value;
        rows.add(row);
        return row;
    }

    private void addModuleActions(Context context, ModModule module) {
        switch (module.id()) {
            case "anti_delete": {
                int count = VaultStore.getAll(context, DeletedStore.PREFS, DeletedStore.DELETED_KEY).size();
                action("view_deleted", "Deleted messages", String.valueOf(count));
                break;
            }
            case "edit_history": {
                int count = VaultStore.getAll(context, DeletedStore.PREFS, DeletedStore.EDIT_KEY).size();
                action("view_edits", "Edit history", String.valueOf(count));
                break;
            }
            case "hidden_chats": {
                // v1.3.4: ONE row into the dedicated management
                // page (AminHiddenChatsActivity) — the scattered
                // set-PIN / unlock / per-chat / lock rows that
                // used to be generated here are gone.
                String value;
                if (!HiddenChatsStore.hasPin(context)) {
                    value = "Set a PIN";
                } else if (!HiddenChatsStore.isUnlocked()) {
                    value = "Locked";
                } else {
                    value = HiddenChatsStore.getHiddenIds(context).size() + " hidden";
                }
                action("manage_hidden_chats", "Manage hidden chats", value);
                break;
            }
            case "video_background": {
                String path = VideoBackgroundHelper.getVideoPath(context);
                action("pick_video", "Video background file",
                        path == null ? "Not set" : new File(path).getName());
                if (path != null) {
                    action("clear_video", "Clear video background", null);
                }
                break;
            }
            case "auto_saver": {
                int count = AutoSaverStore.count(context);
                action("view_saved", "Saved media", String.valueOf(count));
                break;
            }
            case "keyword_radar": {
                int keywords = MessagePipeline.loadKeywords(context).size();
                action("edit_keywords", "Radar keywords", keywords == 0 ? "None" : keywords + " set");
                int hits = VaultStore.getAll(context, MessagePipeline.RADAR_PREFS,
                        MessagePipeline.RADAR_HITS_KEY).size();
                action("view_hits", "Radar hits", String.valueOf(hits));
                break;
            }
            case "send_when_online": {
                action("swo_new", "New message…", "pick a person");
                for (SendWhenOnlineStore.Entry entry : SendWhenOnlineStore.getAll(context)) {
                    Row row = new Row();
                    row.kind = KIND_ACTION;
                    row.action = "swo_entry";
                    row.swoEntry = entry;
                    row.title = entry.name == null || entry.name.isEmpty()
                            ? String.valueOf(entry.peer) : entry.name;
                    row.value = entry.isPending() ? "Waiting for them to come online…"
                            : "Sent ✓ — tap to remove";
                    rows.add(row);
                }
                break;
            }
            default:
                break;
        }
        if (module.section() == ModModule.SECTION_TOOLS
                && module.id().equals("send_when_online")) {
            // Backup + app icon live at the end of the Tools section.
            action("backup_export", "Export AminGram backup", "JSON file");
            action("backup_import", "Import backup", null);
            action("app_icon", "App Icon", currentIconTitle());
        }
    }

    private void addPluginsSection(Context context) {
        Row header = new Row();
        header.kind = KIND_HEADER;
        header.title = "Your Plugins";
        rows.add(header);
        // v1.3.3: the engine kill-switch pill sits at the top.
        Row engine = new Row();
        engine.kind = KIND_ENGINE;
        rows.add(engine);
        // v1.3.3: each installed plugin is ONE rich card (switch,
        // name, version/author, description, capability chips and
        // an action row), replacing the toggle + info row pair.
        for (JsPlugin plugin : JsPluginManager.plugins(context)) {
            Row card = new Row();
            card.kind = KIND_PLUGIN_CARD;
            card.plugin = plugin;
            rows.add(card);
        }
        // v1.3.4: bundled plugins are pre-installed (see
        // BundledPlugins.ensureInstalled) and live above as normal
        // rich cards — no Install rows for them. A bundled row
        // remains only for a bundled plugin that is NOT installed:
        // either it was delivered once and the user deleted it
        // ("Built-in · deleted — tap to reinstall"; auto-install
        // never resurrects it) or delivery has not happened yet
        // (plain "Install" fallback).
        for (BundledPlugins.Bundled bundled : BundledPlugins.all()) {
            if (BundledPlugins.isInstalled(context, bundled)) {
                continue;
            }
            Row row = new Row();
            row.kind = KIND_ACTION;
            row.action = "bundled";
            row.bundled = bundled;
            row.title = bundled.name;
            row.value = BundledPlugins.wasSeen(context, bundled.id)
                    ? "Built-in · deleted — tap to reinstall" : "Install";
            rows.add(row);
            Row info = new Row();
            info.kind = KIND_INFO;
            info.flat = true;
            info.title = bundled.tagline;
            rows.add(info);
        }
        Row addFile = action("add_plugin_file", "Add plugin from file…", ".js");
        addFile.accent = true;
        Row addPaste = action("add_plugin_paste", "Paste plugin code…", null);
        addPaste.accent = true;
        if (JsPluginManager.plugins(context).isEmpty()) {
            Row info = new Row();
            info.kind = KIND_INFO;
            info.multiline = true;
            info.flat = true;
            info.action = "note";
            info.title = "No plugins installed yet. Plugins are single JavaScript files — "
                    + "the full guide is PLUGIN-MASTER-PROMPT.md in the AminGram repo (docs/).";
            rows.add(info);
        }
    }

    // ------------------------------------------------------------------
    // Clicks
    // ------------------------------------------------------------------

    private void onRowClick(View view, int position) {
        Context context = getContext();
        if (context == null || position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        switch (row.kind) {
            case KIND_MODULE: {
                if ("amin_design".equals(row.module.id())
                        || "ios_theme".equals(row.module.id())) {
                    toggleDesignModule(context, row.module);
                } else {
                    boolean now = !PluginRegistry.isEnabled(context, row.module);
                    PluginRegistry.setEnabled(context, row.module, now);
                    rebuild();
                }
                break;
            }
            case KIND_ENGINE:
                JsPluginManager.setEngineEnabled(context,
                        !JsPluginManager.isEngineEnabled(context));
                rebuild();
                break;
            case KIND_PLUGIN_CARD:
                presentFragment(new AminJsPluginActivity(row.plugin.id));
                break;
            case KIND_ACTION:
                onAction(context, row);
                break;
            default:
                break;
        }
    }

    /** The two design modules apply / restore REAL themes via
     *  DesignThemes, and are mutually exclusive: turning one on
     *  switches the other off (keeping the user's original theme
     *  snapshot); turning the active one off restores the snapshot. */
    private void toggleDesignModule(Context context, ModModule module) {
        boolean aminDesign = "amin_design".equals(module.id());
        String thisDesign = aminDesign ? DesignThemes.DESIGN_AMIN : DesignThemes.DESIGN_IOS;
        String otherDesign = aminDesign ? DesignThemes.DESIGN_IOS : DesignThemes.DESIGN_AMIN;
        String otherModuleId = aminDesign ? "ios_theme" : "amin_design";
        if (!PluginRegistry.isEnabled(context, module)) {
            PluginRegistry.setEnabled(context, module, true);
            for (ModModule other : PluginRegistry.modules()) {
                if (otherModuleId.equals(other.id())) {
                    PluginRegistry.setEnabled(context, other, false);
                }
            }
            DesignThemes.applyDesign(context, thisDesign);
        } else {
            PluginRegistry.setEnabled(context, module, false);
            if (PluginRegistry.isEnabledById(context, otherModuleId)) {
                DesignThemes.applyDesign(context, otherDesign);
            } else {
                DesignThemes.restorePrevious(context);
            }
        }
        rebuild();
    }

    /** Rich-card buttons run the exact operations the plugin page
     *  runs (same JsPluginManager calls, same strings). */
    private final PluginCardView.Callbacks pluginCardCallbacks = new PluginCardView.Callbacks() {
        @Override
        public void onTogglePlugin(JsPlugin plugin) {
            Context context = getContext();
            if (context != null) {
                JsPluginManager.setEnabled(context, plugin, !plugin.enabled);
                rebuild();
            }
        }

        @Override
        public void onOpenPlugin(JsPlugin plugin) {
            presentFragment(new AminJsPluginActivity(plugin.id));
        }

        @Override
        public void onPluginLog(JsPlugin plugin) {
            presentFragment(new AminStoreListActivity(plugin.name + " — log",
                    JsPluginManager.PREFS, "log_" + plugin.id));
        }

        @Override
        public void onDeletePlugin(JsPlugin plugin) {
            Context context = getContext();
            if (context == null || getParentActivity() == null) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Delete " + plugin.name + "?");
            builder.setMessage("The plugin file, its settings and its log are removed.");
            builder.setPositiveButton("Delete", (dialog, which) -> {
                JsPluginManager.delete(context, plugin);
                bulletin(R.raw.ic_delete, "Plugin deleted");
                rebuild();
            });
            builder.setNegativeButton("Cancel", null);
            showDialog(builder.create());
        }
    };

    private void onAction(Context context, Row row) {
        switch (row.action) {
            case "view_deleted":
                presentFragment(new AminStoreListActivity("Deleted messages",
                        DeletedStore.PREFS, DeletedStore.DELETED_KEY));
                break;
            case "view_edits":
                presentFragment(new AminStoreListActivity("Edit history",
                        DeletedStore.PREFS, DeletedStore.EDIT_KEY));
                break;
            case "view_hits":
                presentFragment(new AminStoreListActivity("Keyword Radar hits",
                        MessagePipeline.RADAR_PREFS, MessagePipeline.RADAR_HITS_KEY));
                break;
            case "view_saved":
                presentFragment(new AminSavedMediaActivity());
                break;
            case "pick_video":
                pickVideo();
                break;
            case "clear_video":
                VideoBackgroundHelper.setVideoPath(context, null);
                bulletin(R.raw.ic_delete, "Video background cleared");
                rebuild();
                break;
            case "edit_keywords":
                editKeywords(context);
                break;
            case "app_icon":
                pickAppIcon();
                break;
            case "manage_hidden_chats":
                presentFragment(new AminHiddenChatsActivity());
                break;
            case "google_account":
                presentFragment(new AminGoogleActivity());
                break;
            case "swo_new":
                presentFragment(new AminDialogPickerActivity("Send when online — pick a person", true,
                        (dialogId, name) -> swoComposeText(context, dialogId, name)));
                break;
            case "swo_entry":
                confirmRemoveSwo(context, row.swoEntry);
                break;
            case "backup_export":
                exportBackup();
                break;
            case "backup_import":
                importBackup();
                break;
            case "add_plugin_file":
                pickPluginFile();
                break;
            case "add_plugin_paste":
                pastePlugin(context);
                break;
            case "bundled": {
                String error = BundledPlugins.install(context, row.bundled);
                if (error == null) {
                    bulletin(R.raw.contact_check, row.bundled.name + " installed");
                } else {
                    showError(error);
                }
                rebuild();
                break;
            }
            default:
                break;
        }
    }

    private void bulletin(int icon, String text) {
        BulletinFactory.of(this).createSimpleBulletin(icon, text).show();
    }

    private void showError(String error) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Plugin not installed");
        builder.setMessage(error);
        builder.setPositiveButton("OK", null);
        showDialog(builder.create());
    }

    // ------------------------------------------------------------------
    // Dialogs and flows
    // ------------------------------------------------------------------

    private EditTextBoldCursor textField(Context context, String hint, boolean multiline) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setHint(hint);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        if (multiline) {
            editText.setSingleLine(false);
            editText.setGravity(Gravity.TOP | Gravity.LEFT);
            editText.setMinLines(6);
        }
        return editText;
    }

    private FrameLayout fieldContainer(Context context, View field) {
        FrameLayout container = new FrameLayout(context);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8),
                AndroidUtilities.dp(24), 0);
        container.addView(field, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    private void editKeywords(Context context) {
        EditTextBoldCursor editText = textField(context, "keyword1, keyword2, …", false);
        editText.setText(MessagePipeline.getKeywordsRaw(context));
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Keyword Radar keywords");
        builder.setView(fieldContainer(context, editText));
        builder.setPositiveButton("Save", (dialog, which) -> {
            MessagePipeline.setKeywords(context, editText.getText().toString());
            rebuild();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void pickAppIcon() {
        org.telegram.ui.LauncherIconController.LauncherIcon[] icons =
                org.telegram.ui.LauncherIconController.LauncherIcon.values();
        CharSequence[] items = new CharSequence[icons.length];
        for (int i = 0; i < icons.length; i++) {
            String title = org.telegram.messenger.LocaleController.getString(icons[i].title);
            boolean active = org.telegram.ui.LauncherIconController.isEnabled(icons[i]);
            items[i] = active ? title + "  ✓" : title;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("App Icon");
        builder.setItems(items, (dialog, which) -> {
            org.telegram.ui.LauncherIconController.setIcon(icons[which]);
            bulletin(R.raw.contact_check, "App icon changed");
            rebuild();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private static String currentIconTitle() {
        for (org.telegram.ui.LauncherIconController.LauncherIcon icon
                : org.telegram.ui.LauncherIconController.LauncherIcon.values()) {
            if (org.telegram.ui.LauncherIconController.isEnabled(icon)) {
                return org.telegram.messenger.LocaleController.getString(icon.title);
            }
        }
        return "Default";
    }

    private void swoComposeText(Context context, long peer, String name) {
        EditTextBoldCursor field = textField(context, "Message to send when they are online", true);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Send to " + name + " when online");
        builder.setView(fieldContainer(context, field));
        builder.setPositiveButton("Queue", (dialog, which) -> {
            String text = field.getText().toString().trim();
            if (!text.isEmpty()) {
                SendWhenOnlineStore.add(context, peer, name, text);
                bulletin(R.raw.contact_check, "Queued — will send when " + name + " is online");
                rebuild();
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void confirmRemoveSwo(Context context, SendWhenOnlineStore.Entry entry) {
        if (entry == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(entry.isPending() ? "Cancel this message?" : "Remove from list?");
        builder.setMessage(entry.text);
        builder.setPositiveButton(entry.isPending() ? "Cancel send" : "Remove", (dialog, which) -> {
            SendWhenOnlineStore.remove(context, entry);
            rebuild();
        });
        builder.setNegativeButton("Keep", null);
        showDialog(builder.create());
    }

    private void pastePlugin(Context context) {
        EditTextBoldCursor field = textField(context, "Paste the whole plugin file here", true);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Add plugin — paste code");
        builder.setView(fieldContainer(context, field));
        builder.setPositiveButton("Install", (dialog, which) -> installPluginSource(field.getText().toString()));
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void installPluginSource(String source) {
        if (source == null || source.trim().isEmpty()) {
            showError("Nothing to install — the plugin code is empty.");
            return;
        }
        Context context = getContext();
        String error = JsPluginManager.install(context, source);
        if (error == null) {
            bulletin(R.raw.contact_check, "Plugin installed");
        } else {
            showError(error);
        }
        rebuild();
    }

    // ------------------------------------------------------------------
    // File pickers
    // ------------------------------------------------------------------

    private void pickVideo() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "image/gif"});
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_VIDEO_BG);
        } catch (Exception e) {
            FileLog.e(e);
            bulletin(R.raw.error, "No file picker available");
        }
    }

    private void pickPluginFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_PLUGIN_FILE);
        } catch (Exception e) {
            FileLog.e(e);
            bulletin(R.raw.error, "No file picker available");
        }
    }

    private void exportBackup() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType("application/json");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_TITLE, "amingram-backup.json");
            startActivityForResult(intent, REQUEST_BACKUP_EXPORT);
        } catch (Exception e) {
            FileLog.e(e);
            bulletin(R.raw.error, "No file picker available");
        }
    }

    private void importBackup() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_BACKUP_IMPORT);
        } catch (Exception e) {
            FileLog.e(e);
            bulletin(R.raw.error, "No file picker available");
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        Context context = getContext();
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || context == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            if (requestCode == REQUEST_VIDEO_BG) {
                String path = copyVideoToPrivateStorage(context, uri);
                if (path != null && new File(path).length() > 0) {
                    VideoBackgroundHelper.setVideoPath(context, path);
                    bulletin(R.raw.contact_check, "Video background set — reopen a chat");
                } else {
                    bulletin(R.raw.error, "Could not read that file");
                }
                rebuild();
            } else if (requestCode == REQUEST_PLUGIN_FILE) {
                String path = AndroidUtilities.getPath(uri);
                if (path == null || path.startsWith("content://")) {
                    path = MediaController.copyFileToCache(uri, "js");
                }
                String source = path != null ? readTextFile(new File(path)) : null;
                if (source != null) {
                    installPluginSource(source);
                } else {
                    showError("Could not read that file.");
                }
            } else if (requestCode == REQUEST_BACKUP_EXPORT) {
                OutputStream out = context.getContentResolver().openOutputStream(uri);
                if (out != null) {
                    out.write(BackupManager.exportJson(context).getBytes(StandardCharsets.UTF_8));
                    out.close();
                    bulletin(R.raw.contact_check, "Backup saved");
                } else {
                    bulletin(R.raw.error, "Could not write the backup file");
                }
            } else if (requestCode == REQUEST_BACKUP_IMPORT) {
                InputStream in = context.getContentResolver().openInputStream(uri);
                String json = in != null ? readStream(in) : null;
                String error = json != null ? BackupManager.importJson(context, json)
                        : "Could not read that file.";
                if (error == null) {
                    bulletin(R.raw.contact_check, "Backup imported");
                } else {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                    builder.setTitle("Import failed");
                    builder.setMessage(error);
                    builder.setPositiveButton("OK", null);
                    showDialog(builder.create());
                }
                rebuild();
            }
        } catch (Exception e) {
            FileLog.e(e);
            bulletin(R.raw.error, "Something went wrong");
        }
    }

    /**
     * Copies a picked video/GIF into durable app-private storage
     * (filesDir/amingram/videobg.&lt;ext&gt;) and returns its absolute
     * path, or null on failure. v1.3.2 fix: the previous flow stored
     * the picker's own path (AndroidUtilities.getPath) or a copy in
     * Telegram's CACHE directory (MediaController.copyFileToCache) —
     * cache files get purged, so after an app restart the stored path
     * dangled, VideoBackgroundHelper validation failed and chats
     * silently fell back to the static wallpaper. filesDir is never
     * purged, so the pick now survives restarts. The copy goes
     * through ContentResolver.openInputStream so content:// sources
     * work without getPath; a previous pick with a different
     * extension is removed so stale files don't accumulate.
     */
    private static String copyVideoToPrivateStorage(Context context, Uri uri) {
        File out = null;
        try {
            String ext = videoExtension(context, uri);
            File dir = new File(context.getFilesDir(), "amingram");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            out = new File(dir, "videobg." + ext);
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            OutputStream os = new FileOutputStream(out);
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) > 0) {
                os.write(buffer, 0, read);
                total += read;
            }
            os.close();
            in.close();
            if (total == 0) {
                out.delete();
                return null;
            }
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith("videobg.") && !f.equals(out)) {
                        f.delete();
                    }
                }
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            FileLog.e(e);
            if (out != null) {
                out.delete();
            }
            return null;
        }
    }

    /** Real extension of the picked file: from the resolved filesystem
     *  path's suffix when there is one, else from the content type via
     *  MimeTypeMap, else "mp4". (The old code hardcoded "mp4" for the
     *  cache copy, so GIF picks never matched the helper's isGif() and
     *  were fed to the video player.) */
    private static String videoExtension(Context context, Uri uri) {
        String path = AndroidUtilities.getPath(uri);
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot > 0 && dot > path.lastIndexOf('/')) {
                String ext = path.substring(dot + 1).toLowerCase(Locale.US);
                if (ext.length() <= 5 && ext.matches("[a-z0-9]+")) {
                    return ext;
                }
            }
        }
        try {
            String type = context.getContentResolver().getType(uri);
            if (type != null) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
                if (ext != null && !ext.isEmpty()) {
                    return ext.toLowerCase(Locale.US);
                }
            }
        } catch (Exception ignored) {
        }
        return "mp4";
    }

    private static String readTextFile(File file) {
        try {
            return readStream(new FileInputStream(file));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readStream(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > 2 * 1024 * 1024) {
                break;
            }
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toString("UTF-8");
    }

    // ------------------------------------------------------------------
    // Card presentation helpers (v1.3.2)
    // ------------------------------------------------------------------

    /** Background for one card segment: white (or the accent-tinted
     *  fill for the add-plugin card), corners rounded only where the
     *  segment touches the card's outer edge. */
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

    /** Soft accent-tinted card fill (chips, the add-plugin card). */
    private static int accentFill() {
        return ColorUtils.blendARGB(cardFill(), accentColor(), 0.10f);
    }

    /** Chip glyph background: a slightly stronger tint than the card fill. */
    private static int chipFill() {
        return ColorUtils.blendARGB(cardFill(), accentColor(), 0.16f);
    }

    private static int toneColor(int tone) {
        switch (tone) {
            case TONE_ACTIVE:
                return Theme.getColor(Theme.key_windowBackgroundWhiteGreenText);
            case TONE_ATTENTION:
                return Theme.getColor(Theme.key_text_RedRegular);
            case TONE_MUTED:
                return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            default:
                return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        }
    }

    /** Verified 13.0.2 R.drawable glyphs for the module icon chips. */
    private static int moduleIcon(String id) {
        switch (id) {
            case "amin_design":
                return R.drawable.msg_palette;
            case "ios_theme":
                return R.drawable.msg_colors;
            case "ghost_mode":
                return R.drawable.msg_mask;
            case "ghost_stories":
                return R.drawable.msg_filled_stories;
            case "anti_delete":
                return R.drawable.msg_delete;
            case "edit_history":
                return R.drawable.msg_edit;
            case "hidden_chats":
                return R.drawable.msg_mini_lock2;
            case "hide_stories":
                return R.drawable.msg_archive_stories;
            case "local_premium":
                return R.drawable.msg_gift_premium;
            case "video_background":
                return R.drawable.msg_background;
            case "auto_saver":
                return R.drawable.msg_download;
            case "keyword_radar":
                return R.drawable.msg_search;
            case "free_translate":
                return R.drawable.msg_translate;
            case "send_when_online":
                return R.drawable.msg_online;
            default:
                return 0;
        }
    }

    /** Section label in the extera language (v1.3.3): theme ACCENT
     *  color (key_windowBackgroundWhiteBlueText — the same key as
     *  upstream's blue section headers), medium weight, natural
     *  case; NOT the gray uppercase of the v1.3.2 hub. */
    private static class SectionLabelView extends TextView {
        SectionLabelView(Context context) {
            super(context);
            setTextSize(14);
            setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            setTextColor(accentColor());
            setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(22),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(2));
        }

        void setLabel(String title) {
            setText(title == null ? "" : title);
        }
    }

    /** Card header: icon chip (tinted rounded square with a glyph, or
     *  the module's initial letter when no glyph fits) + title + the
     *  module/plugin switch. The switch is display-only — the row
     *  click toggles, exactly like the old TextCheckCell rows. */
    private static class CardHeaderView extends FrameLayout {
        private final FrameLayout chip;
        private final ImageView glyph;
        private final TextView letter;
        private final TextView titleView;
        private final Switch switchView;

        CardHeaderView(Context context) {
            super(context);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(12));

            chip = new FrameLayout(context);
            addView(chip, new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(40), AndroidUtilities.dp(40),
                    Gravity.LEFT | Gravity.CENTER_VERTICAL));

            glyph = new ImageView(context);
            glyph.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            chip.addView(glyph, new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(22), AndroidUtilities.dp(22), Gravity.CENTER));

            letter = new TextView(context);
            letter.setTextSize(17);
            letter.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            letter.setGravity(Gravity.CENTER);
            chip.addView(letter, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            titleView = new TextView(context);
            titleView.setTextSize(16);
            titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.CENTER_VERTICAL);
            titleParams.leftMargin = AndroidUtilities.dp(40 + 12);
            titleParams.rightMargin = AndroidUtilities.dp(52);
            addView(titleView, titleParams);

            switchView = new Switch(context);
            switchView.setFocusable(false);
            switchView.setClickable(false);
            FrameLayout.LayoutParams switchParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            addView(switchView, switchParams);
        }

        void bind(String title, int iconRes, boolean checked, int seg) {
            titleView.setText(title);
            switchView.setChecked(checked, false);
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setShape(GradientDrawable.RECTANGLE);
            chipBg.setColor(chipFill());
            chipBg.setCornerRadius(AndroidUtilities.dp(12));
            chip.setBackground(chipBg);
            if (iconRes != 0) {
                glyph.setImageResource(iconRes);
                glyph.setColorFilter(new PorterDuffColorFilter(accentColor(), PorterDuff.Mode.SRC_IN));
                glyph.setVisibility(VISIBLE);
                letter.setVisibility(GONE);
            } else {
                glyph.setVisibility(GONE);
                letter.setText(title == null || title.isEmpty() ? "?"
                        : title.substring(0, 1).toUpperCase(Locale.US));
                letter.setTextColor(accentColor());
                letter.setVisibility(VISIBLE);
            }
            setBackground(segmentDrawable(seg, cardFill()));
        }
    }

    /** The block under a module header inside the section group
     *  card: ONE truncated subtitle line (the description) and a
     *  status line rendered as a colored dot + short text — the one
     *  status scheme used across the hub. v1.3.3: the text column
     *  indents to align under the module title (68dp) and the
     *  hairline closing the block is inset by the same amount
     *  (extera style); bundled taglines and the standalone note use
     *  the flat 16dp inset instead. */
    private static class InfoStatusView extends LinearLayout {
        private final TextView subtitle;
        private final LinearLayout statusRow;
        private final View dot;
        private final TextView statusText;
        private final View divider;

        InfoStatusView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);

            subtitle = new TextView(context);
            subtitle.setTextSize(13);
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            subtitle.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
            addView(subtitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            statusRow = new LinearLayout(context);
            statusRow.setOrientation(HORIZONTAL);
            statusRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            statusParams.topMargin = AndroidUtilities.dp(5);
            addView(statusRow, statusParams);

            dot = new View(context);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(7), AndroidUtilities.dp(7));
            dotParams.rightMargin = AndroidUtilities.dp(6);
            statusRow.addView(dot, dotParams);

            statusText = new TextView(context);
            statusText.setTextSize(13);
            statusText.setSingleLine(true);
            statusText.setEllipsize(TextUtils.TruncateAt.END);
            statusRow.addView(statusText, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            divider = new View(context);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dividerParams.topMargin = AndroidUtilities.dp(10);
            addView(divider, dividerParams);
        }

        void bind(String subtitleText, boolean multiline, String status, int tone,
                  int seg, boolean carded, boolean flat) {
            subtitle.setText(subtitleText == null ? "" : subtitleText);
            subtitle.setSingleLine(!multiline);
            subtitle.setMaxLines(multiline ? Integer.MAX_VALUE : 1);
            subtitle.setEllipsize(multiline ? null : TextUtils.TruncateAt.END);
            if (status == null || status.isEmpty()) {
                statusRow.setVisibility(GONE);
            } else {
                statusRow.setVisibility(VISIBLE);
                int color = toneColor(tone);
                statusText.setText(status);
                statusText.setTextColor(color);
                if (tone == TONE_NONE) {
                    dot.setVisibility(GONE);
                } else {
                    GradientDrawable dotBg = new GradientDrawable();
                    dotBg.setShape(GradientDrawable.OVAL);
                    dotBg.setColor(color);
                    dot.setBackground(dotBg);
                    dot.setVisibility(VISIBLE);
                }
            }
            divider.setVisibility(seg == SEG_MID ? VISIBLE : GONE);
            int left = flat || !carded ? AndroidUtilities.dp(16) : AndroidUtilities.dp(68);
            setPadding(left, 0, AndroidUtilities.dp(16),
                    seg == SEG_LAST || seg == SEG_ONLY ? AndroidUtilities.dp(12) : AndroidUtilities.dp(2));
            setBackground(carded ? segmentDrawable(seg, cardFill()) : null);
        }
    }

    /** Hub header block (v1.3.3): the AminGram shield (the launcher
     *  icon's own foreground vector on its navy), the app name and
     *  the package version, centered at the very top of the hub. */
    private static class HubHeaderView extends LinearLayout {
        private final TextView versionView;

        HubHeaderView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(24),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(2));

            FrameLayout badge = new FrameLayout(context);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.RECTANGLE);
            badgeBg.setColor(0xFF01030F);
            badgeBg.setCornerRadius(AndroidUtilities.dp(20));
            badge.setBackground(badgeBg);
            ImageView shield = new ImageView(context);
            shield.setImageResource(R.drawable.amingram_foreground);
            shield.setScaleType(ImageView.ScaleType.FIT_CENTER);
            badge.addView(shield, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            addView(badge, new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(72), AndroidUtilities.dp(72)));

            TextView nameView = new TextView(context);
            nameView.setText("AminGram");
            nameView.setTextSize(21);
            nameView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameParams.topMargin = AndroidUtilities.dp(12);
            addView(nameView, nameParams);

            versionView = new TextView(context);
            versionView.setTextSize(13.5f);
            versionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            versionParams.topMargin = AndroidUtilities.dp(3);
            addView(versionView, versionParams);
            bind();
        }

        void bind() {
            String version = null;
            try {
                Context context = getContext();
                version = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Throwable ignored) {
            }
            versionView.setText(version == null || version.isEmpty()
                    ? "" : "Version " + version);
        }
    }

    /** Full-width accent pill (v1.3.3): the JS engine master
     *  switch, extera style — accent fill, white medium label, and
     *  a display-only switch (the row click toggles, like every
     *  other hub row). Backed by JsPluginManager's engine_enabled
     *  pref: off means no JS plugin runs anywhere. */
    private static class EnginePillView extends LinearLayout {
        private final Switch switchView;

        EnginePillView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(AndroidUtilities.dp(56));
            setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(6),
                    AndroidUtilities.dp(12), AndroidUtilities.dp(6));
            TextView label = new TextView(context);
            label.setText("Enable Plugins Engine");
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setTextSize(16);
            label.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            label.setTextColor(0xFFFFFFFF);
            addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            switchView = new Switch(context);
            switchView.setFocusable(false);
            switchView.setClickable(false);
            addView(switchView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(boolean on) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(accentColor());
            bg.setCornerRadius(AndroidUtilities.dp(28));
            setBackground(bg);
            switchView.setChecked(on, false);
        }

        /** v1.3.4: the pill's height is pinned here, in the view
         *  itself. v1.3.3 left it entirely to wrap-content
         *  measurement of a horizontal LinearLayout inside
         *  RecyclerListView (whose adapter also rewrites the
         *  item's LayoutParams margins at bind time); on device
         *  the item measured to the full offered slot height and
         *  only the accent fill painted — a giant empty blue
         *  block with the label and switch lost inside it
         *  (Amin's field screenshot). An exact height spec (plus
         *  an exact height in the adapter's LayoutParams) makes
         *  that failure structurally impossible. */
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                    AndroidUtilities.dp(56), MeasureSpec.EXACTLY));
        }
    }

    /** extera-style rich plugin card (v1.3.3), one per installed JS
     *  plugin: icon chip + working enable switch, big name, a
     *  "Version X · @author" line (author in accent), description
     *  up to two lines, capability chips, and a bottom action row
     *  (settings page, view log, delete in red). The buttons run
     *  the plugin page's own operations via Callbacks; tapping the
     *  card itself still opens the plugin page. The switch is
     *  display-only like the module headers — a transparent hit
     *  area over it performs the toggle. */
    private static class PluginCardView extends LinearLayout {
        interface Callbacks {
            void onTogglePlugin(JsPlugin plugin);
            void onOpenPlugin(JsPlugin plugin);
            void onPluginLog(JsPlugin plugin);
            void onDeletePlugin(JsPlugin plugin);
        }

        private final FrameLayout chip;
        private final TextView chipLetter;
        private final Switch switchView;
        private final TextView nameView;
        private final TextView metaView;
        private final TextView descView;
        private final LinearLayout chipsRow;
        private final ImageView settingsBtn;
        private final ImageView logBtn;
        private final ImageView deleteBtn;
        private JsPlugin bound;
        private Callbacks callbacks;

        PluginCardView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(6));

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            // v1.3.4: header and the actions row are CLICKABLE
            // containers. RecyclerListView's item-click listener
            // suppresses the item click only when a DIRECT child
            // of the item view under the touch is clickable (its
            // one-level check — see RecyclerListViewItemClick-
            // Listener.onInterceptTouchEvent); the card's real
            // buttons (switch hit area, settings/log/delete) are
            // nested two levels deep, so without this the item
            // click fired ALONGSIDE the button click — tapping
            // delete opened the confirm AND navigated into the
            // plugin page (Amin's field report). The containers
            // have no listener of their own: deepest children get
            // the touch first, and taps on the containers' empty
            // areas are simply consumed.
            header.setClickable(true);
            addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            chip = new FrameLayout(context);
            header.addView(chip, new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(40), AndroidUtilities.dp(40)));
            chipLetter = new TextView(context);
            chipLetter.setTextSize(17);
            chipLetter.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            chipLetter.setGravity(Gravity.CENTER);
            chip.addView(chipLetter, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View spacer = new View(context);
            header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

            FrameLayout switchBox = new FrameLayout(context);
            header.addView(switchBox, new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(56), AndroidUtilities.dp(40)));
            switchView = new Switch(context);
            switchView.setFocusable(false);
            switchView.setClickable(false);
            switchBox.addView(switchView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.RIGHT | Gravity.CENTER_VERTICAL));
            View switchHit = new View(context);
            switchHit.setOnClickListener(v -> {
                if (callbacks != null && bound != null) {
                    callbacks.onTogglePlugin(bound);
                }
            });
            switchBox.addView(switchHit, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            nameView = new TextView(context);
            nameView.setTextSize(19);
            nameView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameParams.topMargin = AndroidUtilities.dp(10);
            addView(nameView, nameParams);

            metaView = new TextView(context);
            metaView.setTextSize(13);
            metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            metaView.setSingleLine(true);
            metaView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaParams.topMargin = AndroidUtilities.dp(2);
            addView(metaView, metaParams);

            descView = new TextView(context);
            descView.setTextSize(14);
            descView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            descView.setMaxLines(2);
            descView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            descParams.topMargin = AndroidUtilities.dp(6);
            addView(descView, descParams);

            chipsRow = new LinearLayout(context);
            chipsRow.setOrientation(HORIZONTAL);
            LinearLayout.LayoutParams chipsParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipsParams.topMargin = AndroidUtilities.dp(10);
            addView(chipsRow, chipsParams);

            View hairline = new View(context);
            hairline.setBackgroundColor(Theme.getColor(Theme.key_divider));
            LinearLayout.LayoutParams hairlineParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            hairlineParams.topMargin = AndroidUtilities.dp(12);
            addView(hairline, hairlineParams);

            LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            actions.setClickable(true); // see header note above
            addView(actions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(44)));

            settingsBtn = actionButton(context, R.drawable.msg_settings);
            settingsBtn.setOnClickListener(v -> {
                if (callbacks != null && bound != null) {
                    callbacks.onOpenPlugin(bound);
                }
            });
            actions.addView(settingsBtn);
            logBtn = actionButton(context, R.drawable.msg_log);
            logBtn.setOnClickListener(v -> {
                if (callbacks != null && bound != null) {
                    callbacks.onPluginLog(bound);
                }
            });
            actions.addView(logBtn);
            View actionSpacer = new View(context);
            actions.addView(actionSpacer, new LinearLayout.LayoutParams(0, 1, 1f));
            deleteBtn = actionButton(context, R.drawable.msg_delete);
            deleteBtn.setOnClickListener(v -> {
                if (callbacks != null && bound != null) {
                    callbacks.onDeletePlugin(bound);
                }
            });
            actions.addView(deleteBtn);
        }

        private static ImageView actionButton(Context context, int iconRes) {
            ImageView button = new ImageView(context);
            button.setImageResource(iconRes);
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            button.setPadding(AndroidUtilities.dp(9), AndroidUtilities.dp(9),
                    AndroidUtilities.dp(9), AndroidUtilities.dp(9));
            button.setLayoutParams(new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(42), AndroidUtilities.dp(42)));
            return button;
        }

        void setCallbacks(Callbacks callbacks) {
            this.callbacks = callbacks;
        }

        void bind(JsPlugin plugin, boolean engineOn) {
            bound = plugin;
            setBackground(segmentDrawable(SEG_ONLY, cardFill()));

            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setShape(GradientDrawable.RECTANGLE);
            chipBg.setColor(chipFill());
            chipBg.setCornerRadius(AndroidUtilities.dp(12));
            chip.setBackground(chipBg);
            chipLetter.setText(plugin.name == null || plugin.name.isEmpty() ? "?"
                    : plugin.name.substring(0, 1).toUpperCase(Locale.US));
            chipLetter.setTextColor(accentColor());

            switchView.setChecked(plugin.enabled, false);
            nameView.setText(plugin.name);

            String meta = "Version " + (plugin.version == null ? "?" : plugin.version);
            if (plugin.author != null && !plugin.author.isEmpty()) {
                String author = plugin.author.startsWith("@")
                        ? plugin.author : "@" + plugin.author;
                String full = meta + " · " + author;
                SpannableString spanned = new SpannableString(full);
                spanned.setSpan(new ForegroundColorSpan(accentColor()),
                        full.length() - author.length(), full.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                metaView.setText(spanned);
            } else {
                metaView.setText(meta);
            }

            if (plugin.description == null || plugin.description.isEmpty()) {
                descView.setVisibility(GONE);
            } else {
                descView.setVisibility(VISIBLE);
                descView.setText(plugin.description);
            }

            chipsRow.removeAllViews();
            if (plugin.capabilities == null || plugin.capabilities.isEmpty()) {
                chipsRow.setVisibility(GONE);
            } else {
                chipsRow.setVisibility(VISIBLE);
                for (String capability : plugin.capabilities) {
                    TextView chipView = new TextView(getContext());
                    chipView.setText(capability);
                    chipView.setTextSize(11);
                    chipView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
                    chipView.setTextColor(accentColor());
                    chipView.setSingleLine(true);
                    chipView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(3),
                            AndroidUtilities.dp(8), AndroidUtilities.dp(3));
                    GradientDrawable chipDrawable = new GradientDrawable();
                    chipDrawable.setShape(GradientDrawable.RECTANGLE);
                    chipDrawable.setColor(chipFill());
                    chipDrawable.setCornerRadius(AndroidUtilities.dp(8));
                    chipView.setBackground(chipDrawable);
                    LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    chipParams.rightMargin = AndroidUtilities.dp(6);
                    chipsRow.addView(chipView, chipParams);
                }
            }

            int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            settingsBtn.setColorFilter(new PorterDuffColorFilter(gray, PorterDuff.Mode.SRC_IN));
            logBtn.setColorFilter(new PorterDuffColorFilter(gray, PorterDuff.Mode.SRC_IN));
            deleteBtn.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_color_red), PorterDuff.Mode.SRC_IN));

            float contentAlpha = engineOn ? 1f : 0.55f;
            nameView.setAlpha(contentAlpha);
            metaView.setAlpha(contentAlpha);
            descView.setAlpha(contentAlpha);
            chipsRow.setAlpha(contentAlpha);
        }
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private class HubAdapter extends RecyclerListView.SelectionAdapter {

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
            return kind != KIND_HEADER && kind != KIND_INFO && kind != KIND_FOOTER
                    && kind != KIND_HUB_HEADER;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            switch (viewType) {
                case KIND_HEADER:
                    view = new SectionLabelView(context);
                    break;
                case KIND_HUB_HEADER:
                    view = new HubHeaderView(context);
                    break;
                case KIND_ENGINE:
                    view = new EnginePillView(context);
                    break;
                case KIND_MODULE:
                    view = new CardHeaderView(context);
                    break;
                case KIND_PLUGIN_CARD: {
                    PluginCardView card = new PluginCardView(context);
                    card.setCallbacks(pluginCardCallbacks);
                    view = card;
                    break;
                }
                case KIND_INFO:
                    view = new InfoStatusView(context);
                    break;
                case KIND_FOOTER:
                    view = new TextInfoPrivacyCell(context);
                    break;
                default:
                    view = new TextCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    viewType == KIND_ENGINE ? AndroidUtilities.dp(56)
                            : ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Context context = holder.itemView.getContext();
            Row row = rows.get(position);
            // Card rows float on the gray page: 12dp side margins and
            // a 10dp gap above the first segment of each card.
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
                    ((SectionLabelView) holder.itemView).setLabel(row.title);
                    break;
                case KIND_HUB_HEADER:
                    ((HubHeaderView) holder.itemView).bind();
                    break;
                case KIND_ENGINE:
                    ((EnginePillView) holder.itemView)
                            .bind(JsPluginManager.isEngineEnabled(context));
                    break;
                case KIND_FOOTER:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                    break;
                case KIND_MODULE:
                    ((CardHeaderView) holder.itemView).bind(row.module.title(),
                            moduleIcon(row.module.id()),
                            PluginRegistry.isEnabled(context, row.module), row.seg);
                    break;
                case KIND_PLUGIN_CARD:
                    ((PluginCardView) holder.itemView).bind(row.plugin,
                            JsPluginManager.isEngineEnabled(context));
                    break;
                case KIND_INFO:
                    ((InfoStatusView) holder.itemView).bind(row.title, row.multiline,
                            row.statusText, row.statusTone, row.seg, carded, row.flat);
                    break;
                default:
                    TextCell cell = (TextCell) holder.itemView;
                    boolean divider = row.seg == SEG_MID;
                    String title = row.title;
                    if (row.accent && title != null && !title.startsWith("+")) {
                        title = "+ " + title;
                    }
                    if (row.value != null) {
                        cell.setTextAndValue(title, row.value, divider);
                    } else {
                        cell.setText(title, divider);
                    }
                    cell.setBackground(segmentDrawable(row.seg,
                            row.accent ? accentFill() : cardFill()));
                    break;
            }
        }
    }
}
