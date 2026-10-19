package org.telegram.messenger.amin.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.core.AutoSaverStore;
import org.telegram.messenger.amin.core.BackupManager;
import org.telegram.messenger.amin.core.DeletedStore;
import org.telegram.messenger.amin.core.HiddenChatsStore;
import org.telegram.messenger.amin.core.MessagePipeline;
import org.telegram.messenger.amin.core.SendWhenOnlineStore;
import org.telegram.messenger.amin.core.VaultStore;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.messenger.amin.plugins.js.BundledPlugins;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AminGram Plugins hub (v1.3 redesign). Sectioned, settings-style:
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
 * Everything here is upstream cells and theme colors — no custom
 * design system.
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
    private static final int KIND_PLUGIN_TOGGLE = 4;
    private static final int KIND_PLUGIN_INFO = 5;
    private static final int KIND_FOOTER = 6;

    private static final class Row {
        int kind;
        String title;
        String value;
        String action;
        ModModule module;
        JsPlugin plugin;
        BundledPlugins.Bundled bundled;
        SendWhenOnlineStore.Entry swoEntry;
        long hiddenId;
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
        addSection(context, "Privacy", ModModule.SECTION_PRIVACY);
        addSection(context, "Media", ModModule.SECTION_MEDIA);
        addSection(context, "Tools", ModModule.SECTION_TOOLS);
        addPluginsSection(context);
        Row footer = new Row();
        footer.kind = KIND_FOOTER;
        footer.title = "AminGram features run on Telegram's own sync — no extra background "
                + "work, no polling. If Android force-stops the app, queued actions "
                + "(like Send When Online) fire on the next launch.";
        rows.add(footer);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
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
            Row info = new Row();
            info.kind = KIND_INFO;
            info.title = module.description()
                    + (status == null || status.isEmpty() ? "" : "\n" + status);
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
            case "hidden_chats":
                addHiddenChatsActions(context);
                break;
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

    private void addHiddenChatsActions(Context context) {
        if (!HiddenChatsStore.hasPin(context)) {
            action("pin_set", "Set a PIN to use Hidden Chats", null);
            return;
        }
        if (!HiddenChatsStore.isUnlocked()) {
            action("pin_unlock", "Unlock hidden chats", null);
            return;
        }
        action("hide_chat", "Hide a chat…", null);
        for (Long id : HiddenChatsStore.getHiddenIds(context)) {
            Row row = new Row();
            row.kind = KIND_ACTION;
            row.action = "hidden_entry";
            row.hiddenId = id;
            row.title = AminDialogPickerActivity.dialogName(currentAccount, id);
            row.value = "tap to unhide";
            rows.add(row);
        }
        action("pin_lock", "Lock hidden chats", null);
    }

    private void addPluginsSection(Context context) {
        Row header = new Row();
        header.kind = KIND_HEADER;
        header.title = "Your Plugins";
        rows.add(header);
        action("add_plugin_file", "Add plugin from file…", ".js");
        action("add_plugin_paste", "Paste plugin code…", null);
        for (BundledPlugins.Bundled bundled : BundledPlugins.all()) {
            Row row = new Row();
            row.kind = KIND_ACTION;
            row.action = "bundled";
            row.bundled = bundled;
            row.title = bundled.name;
            row.value = BundledPlugins.isInstalled(context, bundled)
                    ? "Installed — tap to update" : "Install";
            rows.add(row);
            Row info = new Row();
            info.kind = KIND_INFO;
            info.title = bundled.tagline;
            rows.add(info);
        }
        for (JsPlugin plugin : JsPluginManager.plugins(context)) {
            Row toggle = new Row();
            toggle.kind = KIND_PLUGIN_TOGGLE;
            toggle.plugin = plugin;
            rows.add(toggle);
            Row info = new Row();
            info.kind = KIND_PLUGIN_INFO;
            info.plugin = plugin;
            info.title = plugin.description + "\nCapabilities: " + plugin.capabilitiesText();
            rows.add(info);
        }
        if (JsPluginManager.plugins(context).isEmpty()) {
            Row info = new Row();
            info.kind = KIND_INFO;
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
                boolean now = !PluginRegistry.isEnabled(context, row.module);
                PluginRegistry.setEnabled(context, row.module, now);
                rebuild();
                break;
            }
            case KIND_PLUGIN_TOGGLE: {
                boolean now = !row.plugin.enabled;
                JsPluginManager.setEnabled(context, row.plugin, now);
                rebuild();
                break;
            }
            case KIND_PLUGIN_INFO:
                presentFragment(new AminJsPluginActivity(row.plugin.id));
                break;
            case KIND_ACTION:
                onAction(context, row);
                break;
            default:
                break;
        }
    }

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
            case "pin_set":
                pinSetFlow(context);
                break;
            case "pin_unlock":
                pinUnlockFlow(context);
                break;
            case "pin_lock":
                HiddenChatsStore.setUnlocked(false);
                bulletin(R.raw.contact_check, "Hidden chats locked");
                rebuild();
                break;
            case "hide_chat":
                presentFragment(new AminDialogPickerActivity("Hide a chat", false,
                        (dialogId, name) -> {
                            HiddenChatsStore.setHidden(context, dialogId, true);
                            bulletin(R.raw.contact_check, "Chat hidden");
                            rebuild();
                        }));
                break;
            case "hidden_entry":
                HiddenChatsStore.setHidden(context, row.hiddenId, false);
                bulletin(R.raw.contact_check, "Chat unhidden");
                rebuild();
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

    private void pinSetFlow(Context context) {
        EditTextBoldCursor first = textField(context, "New PIN (4+ digits)", false);
        first.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Set a PIN for Hidden Chats");
        builder.setView(fieldContainer(context, first));
        builder.setPositiveButton("Next", (dialog, which) -> {
            String pin = first.getText().toString().trim();
            if (pin.length() < 4) {
                bulletin(R.raw.error, "PIN must be at least 4 digits");
                return;
            }
            EditTextBoldCursor second = textField(context, "Repeat the PIN", false);
            second.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            AlertDialog.Builder confirm = new AlertDialog.Builder(getParentActivity());
            confirm.setTitle("Confirm PIN");
            confirm.setView(fieldContainer(context, second));
            confirm.setPositiveButton("Set PIN", (d2, w2) -> {
                if (pin.equals(second.getText().toString().trim())) {
                    HiddenChatsStore.setPin(context, pin);
                    HiddenChatsStore.setUnlocked(true);
                    bulletin(R.raw.contact_check, "PIN set — hidden chats unlocked");
                } else {
                    bulletin(R.raw.error, "PINs did not match");
                }
                rebuild();
            });
            confirm.setNegativeButton("Cancel", null);
            showDialog(confirm.create());
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void pinUnlockFlow(Context context) {
        EditTextBoldCursor field = textField(context, "PIN", false);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Unlock hidden chats");
        builder.setView(fieldContainer(context, field));
        builder.setPositiveButton("Unlock", (dialog, which) -> {
            if (HiddenChatsStore.checkPin(context, field.getText().toString().trim())) {
                HiddenChatsStore.setUnlocked(true);
                bulletin(R.raw.contact_check, "Unlocked");
            } else {
                bulletin(R.raw.error, "Wrong PIN");
            }
            rebuild();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
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
            intent.setType("video/*");
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
                String path = AndroidUtilities.getPath(uri);
                if (path == null || path.startsWith("content://")) {
                    path = MediaController.copyFileToCache(uri, "mp4");
                }
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
            return kind != KIND_HEADER && kind != KIND_INFO && kind != KIND_FOOTER;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            switch (viewType) {
                case KIND_HEADER:
                    view = new HeaderCell(context);
                    break;
                case KIND_INFO:
                case KIND_FOOTER:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case KIND_MODULE:
                case KIND_PLUGIN_TOGGLE:
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
            Row row = rows.get(position);
            boolean last = position == rows.size() - 1
                    || rows.get(position + 1).kind == KIND_HEADER;
            switch (row.kind) {
                case KIND_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.title);
                    break;
                case KIND_INFO:
                case KIND_FOOTER:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                    break;
                case KIND_MODULE:
                    ((TextCheckCell) holder.itemView).setTextAndCheck(row.module.title(),
                            PluginRegistry.isEnabled(context, row.module), true);
                    break;
                case KIND_PLUGIN_TOGGLE:
                    ((TextCheckCell) holder.itemView).setTextAndCheck(row.plugin.name,
                            row.plugin.enabled, true);
                    break;
                case KIND_PLUGIN_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                    break;
                default:
                    TextCell cell = (TextCell) holder.itemView;
                    if (row.value != null) {
                        cell.setTextAndValue(row.title, row.value, !last);
                    } else {
                        cell.setText(row.title, !last);
                    }
                    break;
            }
        }
    }
}
