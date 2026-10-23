package org.telegram.messenger.amin.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
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
import org.telegram.messenger.amin.core.HiddenChatsStore;
import org.telegram.messenger.amin.core.MessagePipeline;
import org.telegram.messenger.amin.core.SendWhenOnlineStore;
import org.telegram.messenger.amin.core.VaultStore;
import org.telegram.messenger.amin.core.WallpaperLibraryStore;
import org.telegram.messenger.amin.dns.AminDnsController;
import org.telegram.messenger.amin.dns.DnsProfile;
import org.telegram.messenger.amin.dns.DnsProfiles;
import org.telegram.messenger.amin.google.GoogleAuthManager;
import org.telegram.messenger.amin.modules.GhostModeModule;
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
 * v2.0 presentation: one rounded group card per module SECTION
 * with collapsible, count-labelled headers; the JS engine is a
 * standard module-style switch row (the former custom pill widget
 * is gone); each JS plugin is a rich card with settings/log/
 * overflow/delete actions; deleted bundled plugins leave no hub
 * tombstone and can be restored from the Built-in library page.
 */
public class AminPluginsActivity extends BaseFragment {

    private static final int REQUEST_PLUGIN_FILE = 3918;
    private static final int REQUEST_BACKUP_EXPORT = 3919;
    private static final int REQUEST_BACKUP_IMPORT = 3920;
    private static final int REQUEST_PACK_EXPORT = 3921;
    private static final int REQUEST_PACK_IMPORT = 3922;

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

    /** Plugin ids chosen in the pack-export picker, waiting for
     *  the SAF destination (transient UI state only). */
    private List<String> pendingPackExportIds;

    private static final int KIND_HEADER = 0;
    private static final int KIND_MODULE = 1;
    private static final int KIND_INFO = 2;
    private static final int KIND_ACTION = 3;
    private static final int KIND_FOOTER = 6;
    private static final int KIND_HUB_HEADER = 7;
    private static final int KIND_ENGINE = 8;
    private static final int KIND_PLUGIN_CARD = 9;
    private static final int KIND_RYOKU = 10;

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
        String sectionId;
        int sectionCount;
        /** Info rows that align at the plain 16dp text inset instead
         *  of the module text column (bundled taglines, the note). */
        boolean flat;
    }

    private static final String HUB_PREFS = "amingram_hub";
    private RecyclerListView listView;
    private HubAdapter adapter;
    private final List<Row> rows = new ArrayList<>();
    private String pluginQuery = "";
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

        RyokuUi.bootstrapIfNeeded(context);
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
        if (!isSectionCollapsed(context, "modules_" + ModModule.SECTION_TOOLS)) {
            String googleValue = "Not connected";
            try {
                if (GoogleAuthManager.isSignedIn(context)) {
                    String email = GoogleAuthManager.connectedEmail(context);
                    googleValue = email != null ? email : "Connected";
                }
            } catch (Throwable ignored) {
            }
            action("google_account", "Google Account", googleValue);
            // DNS Tunnel: quick toggle on the ACTIVE profile (the
            // full profiles page lives behind the second row and in
            // Telegram's Proxy settings).
            String dnsValue = "Off";
            String dnsProfileName = "";
            try {
                dnsValue = AminDnsController.proxyRowValue(context);
                DnsProfile dnsActive = DnsProfiles.activeProfile(context);
                if (dnsActive != null) {
                    dnsProfileName = dnsActive.name;
                }
            } catch (Throwable ignored) {
            }
            action("dns_toggle", "DNS Tunnel", dnsValue);
            action("dns_open", "DNS Tunnel profiles", dnsProfileName);
        }
        addPluginsSection(context);
        // System section: the Ryoku UI identity layer — one master
        // toggle card (fonts + theme + geometry, app-wide) plus the
        // theme and wallpaper actions. The theme and the wallpapers
        // stay available even while the toggle is off.
        sectionHeader(context, "System", "system", 3);
        if (!isSectionCollapsed(context, "system")) {
            Row ryoku = new Row();
            ryoku.kind = KIND_RYOKU;
            rows.add(ryoku);
            Row ryokuInfo = new Row();
            ryokuInfo.kind = KIND_INFO;
            ryokuInfo.title = "Fraunces · Space Grotesk · Space Mono — bone on black, "
                    + "one orange accent, sharp corners. App-wide while on.";
            boolean ryokuOn = RyokuUi.isEnabled(context);
            ryokuInfo.statusText = ryokuOn ? "On" : "Off";
            ryokuInfo.statusTone = ryokuOn ? TONE_ACTIVE : TONE_MUTED;
            rows.add(ryokuInfo);
            action("ryoku_theme", "Ryoku theme", RyokuUi.currentThemeName());
            action("ryoku_wallpapers", "Ryoku wallpapers", "3 bundled");
        }
        // Developer section (§3.5): the session Developer-mode
        // toggle. Off by default; turning it on unlocks the
        // Developer section on every plugin page (eval console,
        // storage inspector, live log tail) until the app process
        // restarts.
        sectionHeader(context, "Developer", "developer", 1);
        if (!isSectionCollapsed(context, "developer")) {
            Row devMode = action("dev_mode", "Developer mode",
                    developerMode ? "On for this session" : "Off");
            devMode.accent = true;
        }
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

    private boolean isSectionCollapsed(Context context, String sectionId) {
        return context.getSharedPreferences(HUB_PREFS, Context.MODE_PRIVATE)
                .getBoolean("collapsed_" + sectionId, false);
    }

    private void setSectionCollapsed(Context context, String sectionId, boolean collapsed) {
        context.getSharedPreferences(HUB_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("collapsed_" + sectionId, collapsed).apply();
    }

    private Row sectionHeader(Context context, String title, String sectionId, int count) {
        Row header = new Row();
        header.kind = KIND_HEADER;
        header.title = title;
        header.sectionId = sectionId;
        header.sectionCount = count;
        header.value = isSectionCollapsed(context, sectionId) ? "▸" : "▾";
        rows.add(header);
        return header;
    }

    private boolean pluginMatches(JsPlugin plugin) {
        if (pluginQuery == null || pluginQuery.trim().isEmpty()) {
            return true;
        }
        String q = pluginQuery.trim().toLowerCase(Locale.US);
        StringBuilder hay = new StringBuilder();
        if (plugin.name != null) hay.append(plugin.name).append(' ');
        if (plugin.id != null) hay.append(plugin.id).append(' ');
        if (plugin.author != null) hay.append(plugin.author).append(' ');
        if (plugin.description != null) hay.append(plugin.description).append(' ');
        if (plugin.capabilities != null) {
            for (String cap : plugin.capabilities) hay.append(cap).append(' ');
        }
        return hay.toString().toLowerCase(Locale.US).contains(q);
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
                    case KIND_RYOKU:
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
        String sectionId = "modules_" + section;
        int count = 0;
        for (ModModule module : PluginRegistry.modules()) {
            if (module.section() == section) count++;
        }
        sectionHeader(context, title, sectionId, count);
        if (isSectionCollapsed(context, sectionId)) {
            return;
        }
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
            case "ghost_mode": {
                // Stage 2: granular suppressions under the master
                // Ghost Mode switch. Each row is an On/Off action in
                // the hub's existing style; all default to On.
                action("ghost_suppress_read", "Suppress Read Receipts",
                        GhostModeModule.suppressReadReceipts(context) ? "On" : "Off");
                action("ghost_suppress_typing", "Suppress Typing & Recording Action",
                        GhostModeModule.suppressTyping(context) ? "On" : "Off");
                action("ghost_suppress_online", "Suppress Online Status",
                        GhostModeModule.suppressOnlineStatus(context) ? "On" : "Off");
                break;
            }
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
                // v2.0: the module's files live in the live
                // wallpaper library (its own page); the hub only
                // links there and offers deactivate-without-deleting.
                int count = WallpaperLibraryStore.count(context);
                action("live_wallpapers", "Live wallpapers",
                        count == 0 ? "Empty" : count + " wallpapers");
                if (WallpaperLibraryStore.getActive(context) != null) {
                    action("clear_wallpaper", "Clear / None", "Keep library");
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
        List<JsPlugin> allPlugins = JsPluginManager.plugins(context);
        List<JsPlugin> visiblePlugins = new ArrayList<>();
        for (JsPlugin plugin : allPlugins) {
            if (pluginMatches(plugin)) visiblePlugins.add(plugin);
        }
        sectionHeader(context, "Your Plugins", "your_plugins", allPlugins.size());
        if (isSectionCollapsed(context, "your_plugins")) {
            return;
        }
        // v2.0: the engine is a standard module-style row/card, not
        // a custom pill. KIND_ENGINE is rendered by CardHeaderView.
        Row engine = new Row();
        engine.kind = KIND_ENGINE;
        rows.add(engine);
        Row search = action("search_plugins", "Search plugins",
                pluginQuery == null || pluginQuery.isEmpty() ? "All plugins" : "“" + pluginQuery + "”");
        search.accent = false;
        for (JsPlugin plugin : visiblePlugins) {
            Row card = new Row();
            card.kind = KIND_PLUGIN_CARD;
            card.plugin = plugin;
            rows.add(card);
        }
        if (visiblePlugins.isEmpty()) {
            Row info = new Row();
            info.kind = KIND_INFO;
            info.multiline = true;
            info.flat = true;
            info.action = "note";
            info.title = allPlugins.isEmpty()
                    ? "No plugins installed yet. Use Add Plugin below or open the Built-in library."
                    : "No plugins match this search.";
            rows.add(info);
        }

        int libraryCount = 0;
        for (BundledPlugins.Bundled bundled : BundledPlugins.all()) {
            if (!BundledPlugins.isInstalled(context, bundled)) libraryCount++;
        }
        sectionHeader(context, "Add Plugin", "add_plugin", libraryCount + 2);
        if (isSectionCollapsed(context, "add_plugin")) {
            return;
        }
        Row library = action("built_in_library", "Built-in library",
                libraryCount == 0 ? "All installed" : libraryCount + " available");
        library.accent = true;
        Row menu = action("menu_plugins", "Menu plugins", "Customize attach menu");
        menu.accent = true;
        Row addFile = action("add_plugin_file", "Add plugin from file…", ".js");
        addFile.accent = true;
        Row addPaste = action("add_plugin_paste", "Paste plugin code…", null);
        addPaste.accent = true;
        // Catalog (§3.3): fetched only when its page opens; the
        // value hint below comes only from a catalog fetched this
        // session (never fetched from here).
        int catalogUpdates = AminCatalogActivity.sessionUpdateCount(context);
        Row catalog = action("plugin_catalog", "Plugin catalog",
                catalogUpdates > 0 ? catalogUpdates + " update" + (catalogUpdates == 1 ? "" : "s") + " available"
                        : catalogUpdates == 0 ? "Up to date" : "Browse + install");
        catalog.accent = true;
        Row packExport = action("pack_export", "Export plugin pack…", "Share plugins");
        packExport.accent = true;
        Row packImport = action("pack_import", "Import plugin pack…", ".json");
        packImport.accent = true;
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
            case KIND_HEADER:
                if (row.sectionId != null) {
                    setSectionCollapsed(context, row.sectionId,
                            !isSectionCollapsed(context, row.sectionId));
                    rebuild();
                }
                break;
            case KIND_MODULE: {
                boolean now = !PluginRegistry.isEnabled(context, row.module);
                PluginRegistry.setEnabled(context, row.module, now);
                rebuild();
                break;
            }
            case KIND_ENGINE:
                JsPluginManager.setEngineEnabled(context,
                        !JsPluginManager.isEngineEnabled(context));
                rebuild();
                break;
            case KIND_RYOKU: {
                boolean now = !RyokuUi.isEnabled(context);
                RyokuUi.setEnabled(context, now);
                bulletin(R.raw.contact_check, now
                        ? "Ryoku UI on" : "Ryoku UI off — stock look restored");
                rebuild();
                break;
            }
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
        public void onPluginMore(JsPlugin plugin) {
            showPluginOverflow(plugin);
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

    private void showPluginOverflow(JsPlugin plugin) {
        Context context = getContext();
        if (context == null || getParentActivity() == null) return;
        // v2.1: the item list is built per plugin state — a suspended
        // plugin (watchdog error storm) gains a Resume item; the
        // parallel action codes keep the dispatch below unchanged
        // for the always-present items.
        List<String> labels = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        labels.add("Settings");
        actions.add(0);
        labels.add("Log");
        actions.add(1);
        if (JsPluginManager.isSuspended(plugin.id)) {
            labels.add("Resume");
            actions.add(5);
        }
        labels.add("Move up");
        actions.add(2);
        labels.add("Move down");
        actions.add(3);
        labels.add("Delete");
        actions.add(4);
        CharSequence[] items = labels.toArray(new CharSequence[0]);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(plugin.name);
        builder.setItems(items, (dialog, which) -> {
            if (which < 0 || which >= actions.size()) {
                return;
            }
            switch (actions.get(which)) {
                case 0: presentFragment(new AminJsPluginActivity(plugin.id)); break;
                case 1: pluginCardCallbacks.onPluginLog(plugin); break;
                case 2: JsPluginManager.movePlugin(context, plugin.id, -1); rebuild(); break;
                case 3: JsPluginManager.movePlugin(context, plugin.id, 1); rebuild(); break;
                case 4: pluginCardCallbacks.onDeletePlugin(plugin); break;
                case 5:
                    JsPluginManager.resumePlugin(context, plugin.id);
                    bulletin(R.raw.contact_check, "Plugin resumed");
                    rebuild();
                    break;
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void searchPlugins(Context context) {
        EditTextBoldCursor field = textField(context, "Name, author, description, capability…", false);
        field.setText(pluginQuery == null ? "" : pluginQuery);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Search plugins");
        builder.setView(fieldContainer(context, field));
        builder.setPositiveButton("Search", (dialog, which) -> {
            pluginQuery = field.getText() == null ? "" : field.getText().toString().trim();
            rebuild();
        });
        builder.setNeutralButton("Clear", (dialog, which) -> {
            pluginQuery = "";
            rebuild();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void onAction(Context context, Row row) {
        switch (row.action) {
            case "ghost_suppress_read":
                GhostModeModule.setSuppressReadReceipts(context,
                        !GhostModeModule.suppressReadReceipts(context));
                rebuild();
                break;
            case "ghost_suppress_typing":
                GhostModeModule.setSuppressTyping(context,
                        !GhostModeModule.suppressTyping(context));
                rebuild();
                break;
            case "ghost_suppress_online":
                GhostModeModule.setSuppressOnlineStatus(context,
                        !GhostModeModule.suppressOnlineStatus(context));
                rebuild();
                break;
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
            case "live_wallpapers":
                presentFragment(new AminWallpaperLibraryActivity());
                break;
            case "clear_wallpaper":
                WallpaperLibraryStore.clearActive(context);
                bulletin(R.raw.ic_delete, "Wallpaper cleared — library kept");
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
            case "dns_toggle":
                AminDnsController.get(context).toggle(context);
                rebuild();
                break;
            case "dns_open":
                presentFragment(new AminDnsTunnelActivity());
                break;
            case "ryoku_theme":
                if (RyokuUi.applyRyokuTheme(context)) {
                    bulletin(R.raw.contact_check, "Ryoku theme applied");
                } else {
                    bulletin(R.raw.error, "Ryoku theme not found");
                }
                rebuild();
                break;
            case "ryoku_wallpapers":
                showRyokuWallpapers(context);
                break;
            case "search_plugins":
                searchPlugins(context);
                break;
            case "built_in_library":
                presentFragment(new AminBundledLibraryActivity());
                break;
            case "menu_plugins":
                presentFragment(new AminMenuPluginsActivity());
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
            case "dev_mode":
                developerMode = !developerMode;
                bulletin(R.raw.contact_check, developerMode
                        ? "Developer mode on — plugin pages show the Developer section"
                        : "Developer mode off");
                rebuild();
                break;
            case "plugin_catalog":
                presentFragment(new AminCatalogActivity());
                break;
            case "pack_export":
                pickPackExport(context);
                break;
            case "pack_import":
                pickPackImport();
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

    /** Ryoku wallpaper picker: three bundled dithered-marble
     *  wallpapers, applied through Telegram's standard per-theme
     *  wallpaper override on the Ryoku theme. */
    private void showRyokuWallpapers(Context context) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Ryoku wallpapers");
        builder.setItems(RyokuUi.WALLPAPER_NAMES, (dialog, which) -> {
            boolean ok = RyokuUi.applyWallpaper(context, which);
            bulletin(ok ? R.raw.contact_check : R.raw.error,
                    ok ? "Wallpaper applied" : "Could not apply wallpaper");
            rebuild();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
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
                SendWhenOnlineStore.add(context, getCurrentAccount(), peer, name, text);
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
        builder.setPositiveButton("Review", (dialog, which) ->
                installPluginSource(field.getText().toString(), "paste"));
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    /** Paste/file installs go through the permission review
     *  screen (§3.2) since v2.2: the review shows what the plugin
     *  can do and installs on confirmation. The hub rebuilds when
     *  the review reports back. */
    private void installPluginSource(String source, String sourceTag) {
        if (source == null || source.trim().isEmpty()) {
            showError("Nothing to install — the plugin code is empty.");
            return;
        }
        presentFragment(PluginReviewActivity.forInstall(source, sourceTag, null,
                (installed, pluginId, error) -> rebuild()));
    }

    // ------------------------------------------------------------------
    // Plugin packs (§3.4)
    // ------------------------------------------------------------------

    /** Export step 1: multi-select over the installed plugins. */
    private void pickPackExport(Context context) {
        List<JsPlugin> installed = JsPluginManager.plugins(context);
        if (installed.isEmpty()) {
            bulletin(R.raw.error, "No plugins installed to pack");
            return;
        }
        CharSequence[] items = new CharSequence[installed.size()];
        boolean[] checked = new boolean[installed.size()];
        for (int i = 0; i < installed.size(); i++) {
            items[i] = installed.get(i).name + "  v" + installed.get(i).version;
            checked[i] = true;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Export plugin pack");
        // Telegram's AlertDialog.Builder has no setMultiChoiceItems
        // (run #15's compile error) — the multi-select is a scroll
        // list of check cells in a custom dialog view instead.
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        LinearLayout checkList = new LinearLayout(context);
        checkList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(checkList);
        for (int i = 0; i < installed.size(); i++) {
            final int idx = i;
            org.telegram.ui.Cells.TextCheckCell cell =
                    new org.telegram.ui.Cells.TextCheckCell(context);
            cell.setTextAndCheck(items[idx], checked[idx], idx < installed.size() - 1);
            cell.setOnClickListener(v -> {
                checked[idx] = !checked[idx];
                cell.setChecked(checked[idx]);
            });
            checkList.addView(cell);
        }
        builder.setView(scroll);
        builder.setPositiveButton("Export…", (dialog, which) -> {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < installed.size(); i++) {
                if (checked[i]) {
                    ids.add(installed.get(i).id);
                }
            }
            if (ids.isEmpty()) {
                bulletin(R.raw.error, "Nothing selected");
                return;
            }
            pendingPackExportIds = ids;
            try {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.setType("application/json");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_TITLE, "amingram-plugin-pack.json");
                startActivityForResult(intent, REQUEST_PACK_EXPORT);
            } catch (Exception e) {
                FileLog.e(e);
                bulletin(R.raw.error, "No file picker available");
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void pickPackImport() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_PACK_IMPORT);
        } catch (Exception e) {
            FileLog.e(e);
            bulletin(R.raw.error, "No file picker available");
        }
    }

    /** Import: parse the pack, then walk its entries through the
     *  permission review screen one at a time (source "pack").
     *  Each confirmed entry installs and gets its storage
     *  restored; the chain ends with a summary. */
    private void importPack(Context context, String json) {
        List<org.json.JSONObject> entries = BackupManager.parsePack(json);
        if (entries == null || entries.isEmpty()) {
            showError("That file is not an AminGram plugin pack.");
            return;
        }
        List<String> results = new ArrayList<>();
        advancePackReview(context, entries, 0, results);
    }

    private void advancePackReview(final Context context,
                                   final List<org.json.JSONObject> entries,
                                   final int index,
                                   final List<String> results) {
        if (index >= entries.size()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Pack import finished");
            builder.setMessage(results.isEmpty()
                    ? "Nothing was installed."
                    : String.join("\n", results));
            builder.setPositiveButton("OK", null);
            showDialog(builder.create());
            rebuild();
            return;
        }
        org.json.JSONObject entry = entries.get(index);
        String code = entry.optString("code", "");
        org.json.JSONObject storage = entry.optJSONObject("storage");
        final String storageJson = storage != null ? storage.toString() : null;
        presentFragment(PluginReviewActivity.forInstall(code, "pack", storageJson,
                (installed, pluginId, error) -> {
                    String name = pluginId == null || pluginId.isEmpty() ? "plugin" : pluginId;
                    if (installed) {
                        if (storageJson != null) {
                            try {
                                BackupManager.restorePluginStorage(context, pluginId,
                                        new org.json.JSONObject(storageJson));
                            } catch (Exception ignored) {
                            }
                        }
                        results.add("✓ " + name + " installed");
                    } else if ("skipped".equals(error) || "cancelled".equals(error)) {
                        results.add("— " + name + " skipped");
                    } else {
                        results.add("✗ " + name + " failed: " + error);
                    }
                    AndroidUtilities.runOnUIThread(() ->
                            advancePackReview(context, entries, index + 1, results));
                }));
    }

    // ------------------------------------------------------------------
    // File pickers
    // ------------------------------------------------------------------

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
            if (requestCode == REQUEST_PLUGIN_FILE) {
                String path = AndroidUtilities.getPath(uri);
                if (path == null || path.startsWith("content://")) {
                    path = MediaController.copyFileToCache(uri, "js");
                }
                String source = path != null ? readTextFile(new File(path)) : null;
                if (source != null) {
                    installPluginSource(source, "file");
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
            } else if (requestCode == REQUEST_PACK_EXPORT) {
                OutputStream out = context.getContentResolver().openOutputStream(uri);
                if (out != null && pendingPackExportIds != null) {
                    out.write(BackupManager.exportPackJson(context, pendingPackExportIds)
                            .getBytes(StandardCharsets.UTF_8));
                    out.close();
                    bulletin(R.raw.contact_check, "Plugin pack saved");
                } else {
                    bulletin(R.raw.error, "Could not write the pack file");
                }
                pendingPackExportIds = null;
            } else if (requestCode == REQUEST_PACK_IMPORT) {
                InputStream in = context.getContentResolver().openInputStream(uri);
                String json = in != null ? readStream(in) : null;
                if (json != null) {
                    importPack(context, json);
                } else {
                    showError("Could not read that file.");
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
    // Card presentation helpers (v1.3.2)
    // ------------------------------------------------------------------

    /** Background for one card segment: white (or the accent-tinted
     *  fill for the add-plugin card), corners rounded only where the
     *  segment touches the card's outer edge. Ryoku mode: sharp 2dp
     *  corners + a 1px hairline stroke in the theme divider color. */
    private static GradientDrawable segmentDrawable(int seg, int fillColor) {
        boolean ryoku = RyokuUi.isEnabled(null);
        float r = AndroidUtilities.dp(ryoku ? 2 : 22);
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
        if (ryoku) {
            drawable.setStroke(1, Theme.getColor(Theme.key_divider));
        }
        return drawable;
    }

    /** Chip corner radius: sharp 2dp in Ryoku mode, stock otherwise. */
    private static float chipRadius(float stockDp) {
        return AndroidUtilities.dp(RyokuUi.isEnabled(null) ? 2 : stockDp);
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
     *  case; NOT the gray uppercase of the v1.3.2 hub. Ryoku mode:
     *  numbered mono micro-label ("01 // PRIVACY") in the gray text
     *  color, letter-spaced — the dossier treatment. Styling is
     *  applied in setLabel so recycled holders restyle on toggle. */
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
            if (RyokuUi.isEnabled(getContext())) {
                Typeface mono = RyokuUi.mono();
                if (mono != null) {
                    setTypeface(mono);
                }
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                setLetterSpacing(0.08f);
                setText(dossierLabel(title));
            } else {
                setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
                setTextColor(accentColor());
                setLetterSpacing(0);
                setText(title == null ? "" : title);
            }
        }

        /** "Privacy  ·  3  ▾" -> "01 // PRIVACY  ·  3  ▾". */
        private static String dossierLabel(String title) {
            if (title == null) {
                return "";
            }
            String head = title;
            String tail = "";
            int split = title.indexOf("  ·  ");
            if (split >= 0) {
                head = title.substring(0, split);
                tail = title.substring(split);
            }
            String mapped;
            switch (head) {
                case "Privacy":
                    mapped = "01 // PRIVACY";
                    break;
                case "Media":
                    mapped = "02 // MEDIA";
                    break;
                case "Tools":
                    mapped = "03 // TOOLS";
                    break;
                case "Your Plugins":
                    mapped = "04 // YOUR PLUGINS";
                    break;
                case "Add Plugin":
                    mapped = "05 // ADD PLUGIN";
                    break;
                case "System":
                    mapped = "06 // SYSTEM";
                    break;
                case "Developer":
                    mapped = "07 // DEVELOPER";
                    break;
                default:
                    mapped = head.toUpperCase(Locale.US);
                    break;
            }
            return mapped + tail;
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
            Typeface titleFace = RyokuUi.isEnabled(getContext()) ? RyokuUi.display() : null;
            titleView.setTypeface(titleFace != null ? titleFace
                    : AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            switchView.setChecked(checked, false);
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setShape(GradientDrawable.RECTANGLE);
            chipBg.setColor(chipFill());
            chipBg.setCornerRadius(chipRadius(12));
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

    /** Hub header block. Stock mode (v1.3.3): the AminGram shield
     *  (the launcher icon's own foreground vector on its navy), the
     *  app name and the package version, centered. Ryoku mode: a
     *  left-aligned dossier masthead — the 力 mark, "AMINGRAM" in
     *  Fraunces, a mono SYSTEM DOSSIER label, and a 2x2 stat grid
     *  in hairline-bordered cells with live values, refreshed on
     *  every bind. Both layouts are built once; bind() flips
     *  visibility so recycled holders follow the toggle. */
    private static class HubHeaderView extends LinearLayout {
        private final LinearLayout stockBox;
        private final TextView versionView;
        private final LinearLayout dossierBox;
        private final TextView dossierVersion;
        private final TextView statPlugins;
        private final TextView statActive;
        private final TextView statDns;
        private final TextView statTheme;

        HubHeaderView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(24),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(2));

            // ---- stock masthead ----------------------------------
            stockBox = new LinearLayout(context);
            stockBox.setOrientation(VERTICAL);
            stockBox.setGravity(Gravity.CENTER_HORIZONTAL);

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
            stockBox.addView(badge, new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(72), AndroidUtilities.dp(72)));

            TextView nameView = new TextView(context);
            nameView.setText("AminGram");
            nameView.setTextSize(21);
            nameView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameParams.topMargin = AndroidUtilities.dp(12);
            stockBox.addView(nameView, nameParams);

            versionView = new TextView(context);
            versionView.setTextSize(13.5f);
            versionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            versionParams.topMargin = AndroidUtilities.dp(3);
            stockBox.addView(versionView, versionParams);
            addView(stockBox, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // ---- dossier masthead --------------------------------
            dossierBox = new LinearLayout(context);
            dossierBox.setOrientation(VERTICAL);
            dossierBox.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(2),
                    AndroidUtilities.dp(12), 0);

            LinearLayout markRow = new LinearLayout(context);
            markRow.setOrientation(HORIZONTAL);
            markRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView mark = new TextView(context);
            mark.setText("力");
            mark.setTextSize(38);
            mark.setTextColor(accentColor());
            markRow.addView(mark, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout titles = new LinearLayout(context);
            titles.setOrientation(VERTICAL);
            LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            titlesParams.leftMargin = AndroidUtilities.dp(14);
            TextView appName = new TextView(context);
            appName.setText("AMINGRAM");
            appName.setTextSize(25);
            Typeface displayFace = RyokuUi.display();
            appName.setTypeface(displayFace != null ? displayFace
                    : AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            appName.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            appName.setLetterSpacing(0.03f);
            titles.addView(appName, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView dossierLabel = new TextView(context);
            dossierLabel.setText("SYSTEM DOSSIER — PLUGINS & MODULES");
            dossierLabel.setTextSize(10.5f);
            Typeface monoFace = RyokuUi.mono();
            dossierLabel.setTypeface(monoFace != null ? monoFace : Typeface.MONOSPACE);
            dossierLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            dossierLabel.setLetterSpacing(0.10f);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = AndroidUtilities.dp(4);
            titles.addView(dossierLabel, labelParams);
            markRow.addView(titles, titlesParams);
            dossierBox.addView(markRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout gridRow1 = new LinearLayout(context);
            gridRow1.setOrientation(HORIZONTAL);
            LinearLayout.LayoutParams grid1Params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            grid1Params.topMargin = AndroidUtilities.dp(16);
            statPlugins = addStatCell(context, gridRow1, "PLUGINS", true);
            statActive = addStatCell(context, gridRow1, "ACTIVE", false);
            dossierBox.addView(gridRow1, grid1Params);
            LinearLayout gridRow2 = new LinearLayout(context);
            gridRow2.setOrientation(HORIZONTAL);
            LinearLayout.LayoutParams grid2Params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            grid2Params.topMargin = AndroidUtilities.dp(8);
            statDns = addStatCell(context, gridRow2, "DNS TUNNEL", true);
            statTheme = addStatCell(context, gridRow2, "THEME", false);
            dossierBox.addView(gridRow2, grid2Params);

            dossierVersion = new TextView(context);
            dossierVersion.setTextSize(10.5f);
            dossierVersion.setTypeface(monoFace != null ? monoFace : Typeface.MONOSPACE);
            dossierVersion.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            dossierVersion.setLetterSpacing(0.10f);
            LinearLayout.LayoutParams dossierVersionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dossierVersionParams.topMargin = AndroidUtilities.dp(12);
            dossierBox.addView(dossierVersion, dossierVersionParams);
            View hairline = new View(context);
            hairline.setBackgroundColor(Theme.getColor(Theme.key_divider));
            LinearLayout.LayoutParams hairlineParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            hairlineParams.topMargin = AndroidUtilities.dp(10);
            dossierBox.addView(hairline, hairlineParams);
            dossierBox.setVisibility(GONE);
            addView(dossierBox, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            bind();
        }

        /** One bordered stat cell: mono micro-label + live value.
         *  Returns the value view so bind() can refresh it. */
        private TextView addStatCell(Context context, LinearLayout row,
                                     String label, boolean leftCell) {
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(VERTICAL);
            cell.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8),
                    AndroidUtilities.dp(10), AndroidUtilities.dp(8));
            GradientDrawable cellBg = new GradientDrawable();
            cellBg.setShape(GradientDrawable.RECTANGLE);
            cellBg.setColor(cardFill());
            cellBg.setStroke(1, Theme.getColor(Theme.key_divider));
            cellBg.setCornerRadius(0);
            cell.setBackground(cellBg);
            TextView labelView = new TextView(context);
            labelView.setText(label);
            labelView.setTextSize(9.5f);
            Typeface monoFace = RyokuUi.mono();
            labelView.setTypeface(monoFace != null ? monoFace : Typeface.MONOSPACE);
            labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            labelView.setLetterSpacing(0.12f);
            cell.addView(labelView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView valueView = new TextView(context);
            valueView.setTextSize(15);
            Typeface valueFace = RyokuUi.sansMedium();
            valueView.setTypeface(valueFace != null ? valueFace
                    : AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            valueView.setSingleLine(true);
            valueView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            valueParams.topMargin = AndroidUtilities.dp(2);
            cell.addView(valueView, valueParams);
            LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (leftCell) {
                cellParams.rightMargin = AndroidUtilities.dp(8);
            }
            row.addView(cell, cellParams);
            return valueView;
        }

        void bind() {
            Context context = getContext();
            String version = null;
            try {
                version = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Throwable ignored) {
            }
            versionView.setText(version == null || version.isEmpty()
                    ? "" : "Version " + version);
            boolean ryoku = RyokuUi.isEnabled(context);
            stockBox.setVisibility(ryoku ? GONE : VISIBLE);
            dossierBox.setVisibility(ryoku ? VISIBLE : GONE);
            if (!ryoku) {
                return;
            }
            dossierVersion.setText("VERSION " + (version == null || version.isEmpty()
                    ? "—" : version.toUpperCase(Locale.US)));
            try {
                List<JsPlugin> plugins = JsPluginManager.plugins(context);
                statPlugins.setText(String.valueOf(plugins.size()));
                int active = 0;
                for (JsPlugin plugin : plugins) {
                    if (plugin.enabled) {
                        active++;
                    }
                }
                for (ModModule module : PluginRegistry.modules()) {
                    if (PluginRegistry.isEnabled(context, module)) {
                        active++;
                    }
                }
                statActive.setText(String.valueOf(active));
            } catch (Throwable ignored) {
                statPlugins.setText("—");
                statActive.setText("—");
            }
            try {
                statDns.setText(AminDnsController.proxyRowValue(context));
            } catch (Throwable ignored) {
                statDns.setText("Off");
            }
            statTheme.setText(RyokuUi.currentThemeName());
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
            void onPluginMore(JsPlugin plugin);
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
        private final TextView moreBtn;
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
            moreBtn = new TextView(context);
            moreBtn.setText("⋯");
            moreBtn.setTextSize(24);
            moreBtn.setGravity(Gravity.CENTER);
            moreBtn.setLayoutParams(new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(42), AndroidUtilities.dp(42)));
            moreBtn.setOnClickListener(v -> {
                if (callbacks != null && bound != null) callbacks.onPluginMore(bound);
            });
            actions.addView(moreBtn);
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
            chipBg.setCornerRadius(chipRadius(12));
            chip.setBackground(chipBg);
            chipLetter.setText(plugin.name == null || plugin.name.isEmpty() ? "?"
                    : plugin.name.substring(0, 1).toUpperCase(Locale.US));
            chipLetter.setTextColor(accentColor());

            switchView.setChecked(plugin.enabled, false);
            nameView.setText(plugin.name);
            Typeface nameFace = RyokuUi.isEnabled(getContext()) ? RyokuUi.display() : null;
            nameView.setTypeface(nameFace != null ? nameFace
                    : AndroidUtilities.getTypeface("fonts/rmedium.ttf"));

            String meta = "Version " + (plugin.version == null ? "?" : plugin.version);
            // v2.1 watchdog badge on the meta (subtitle/status) line:
            // a suspended plugin's line is replaced by the suspended
            // text (takes precedence); otherwise today's error count
            // is appended when there is one.
            boolean suspended = JsPluginManager.isSuspended(plugin.id);
            int errorsToday = !suspended
                    ? JsPluginManager.errorsToday(plugin.id) : 0;
            if (suspended) {
                metaView.setText("⏸ Suspended — error storm");
            } else if (plugin.author != null && !plugin.author.isEmpty()) {
                String author = plugin.author.startsWith("@")
                        ? plugin.author : "@" + plugin.author;
                String full = meta + " · " + author;
                if (errorsToday > 0) {
                    full += " · ⚠ " + errorsToday;
                }
                SpannableString spanned = new SpannableString(full);
                spanned.setSpan(new ForegroundColorSpan(accentColor()),
                        meta.length() + 3, meta.length() + 3 + author.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                metaView.setText(spanned);
            } else {
                metaView.setText(errorsToday > 0
                        ? meta + " · ⚠ " + errorsToday : meta);
            }

            if (plugin.description == null || plugin.description.isEmpty()) {
                descView.setVisibility(GONE);
            } else {
                descView.setVisibility(VISIBLE);
                descView.setText(plugin.description);
            }

            chipsRow.removeAllViews();
            // Chips: the install-source chip first (§3.3 — where
            // this copy came from), then one per capability.
            List<String> chipTexts = new ArrayList<>();
            if (plugin.installSource != null && !plugin.installSource.isEmpty()) {
                chipTexts.add("from " + plugin.installSource);
            }
            if (plugin.capabilities != null) {
                chipTexts.addAll(plugin.capabilities);
            }
            if (chipTexts.isEmpty()) {
                chipsRow.setVisibility(GONE);
            } else {
                chipsRow.setVisibility(VISIBLE);
                for (String capability : chipTexts) {
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
                    chipDrawable.setCornerRadius(chipRadius(8));
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
            moreBtn.setTextColor(gray);
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
            return kind != KIND_INFO && kind != KIND_FOOTER
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
                    view = new CardHeaderView(context);
                    break;
                case KIND_RYOKU:
                    view = new CardHeaderView(context);
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
                    ViewGroup.LayoutParams.WRAP_CONTENT));
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
                    ((SectionLabelView) holder.itemView).setLabel(
                            row.title + (row.sectionId != null
                                    ? "  ·  " + row.sectionCount + "  " + row.value : ""));
                    break;
                case KIND_HUB_HEADER:
                    ((HubHeaderView) holder.itemView).bind();
                    break;
                case KIND_ENGINE:
                    ((CardHeaderView) holder.itemView).bind("Plugins Engine",
                            R.drawable.msg_settings,
                            JsPluginManager.isEngineEnabled(context), row.seg);
                    break;
                case KIND_RYOKU:
                    ((CardHeaderView) holder.itemView).bind("Ryoku UI",
                            R.drawable.msg_background,
                            RyokuUi.isEnabled(context), row.seg);
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
