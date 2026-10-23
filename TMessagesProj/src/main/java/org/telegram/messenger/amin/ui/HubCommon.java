package org.telegram.messenger.amin.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
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
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
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
import org.telegram.messenger.amin.modules.LocalPremiumModule;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.messenger.amin.plugins.js.BundledPlugins;
import org.telegram.messenger.amin.plugins.js.JsPlugin;
import org.telegram.messenger.amin.plugins.js.JsPluginManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.SelectAnimatedEmojiDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hub v3 — the shared hub core behind {@link AminPluginsActivity}
 * (the dossier home) and {@link AminHubPageActivity} (one category
 * page). The Row model, the segment machinery, the row builders, the
 * whole click/dialog layer and the plugin card moved here from the
 * pre-v3 single-file hub; regrouping changed WHICH rows are built
 * together, never an action id, a module id or a handler.
 *
 * Presentation is the dossier skin (see {@link HubSkin}): hub-local
 * constants and hub-loaded typefaces on hub views only. Dialogs
 * opened from here stay stock upstream AlertDialogs, untouched.
 */
public final class HubCommon {

    /** Services the hosting fragment lends to the shared layer. */
    public interface Host {
        Context context();

        BaseFragment fragment();

        Activity parentActivity();

        int currentAccount();

        void present(BaseFragment fragment);

        void showDialog(Dialog dialog);

        void startActivityForResult(Intent intent, int requestCode);

        /** Plugins page "Search plugins": leave the page and open
         *  the home search pre-filtered to plugins. */
        void openHomePluginSearch();
    }

    public static Host hostFor(BaseFragment fragment) {
        return new FragmentHost(fragment);
    }

    private static final class FragmentHost implements Host {
        private final BaseFragment fragment;

        FragmentHost(BaseFragment fragment) {
            this.fragment = fragment;
        }

        @Override
        public Context context() {
            return fragment.getContext();
        }

        @Override
        public BaseFragment fragment() {
            return fragment;
        }

        @Override
        public Activity parentActivity() {
            return fragment.getParentActivity();
        }

        @Override
        public int currentAccount() {
            return fragment.getCurrentAccount();
        }

        @Override
        public void present(BaseFragment f) {
            fragment.presentFragment(f);
        }

        @Override
        public void showDialog(Dialog dialog) {
            fragment.showDialog(dialog);
        }

        @Override
        public void startActivityForResult(Intent intent, int requestCode) {
            fragment.startActivityForResult(intent, requestCode);
        }

        @Override
        public void openHomePluginSearch() {
            AminPluginsActivity.requestPluginsSearch();
            fragment.finishFragment();
        }
    }

    private static final int REQUEST_PLUGIN_FILE = 3918;
    private static final int REQUEST_BACKUP_EXPORT = 3919;
    private static final int REQUEST_BACKUP_IMPORT = 3920;
    private static final int REQUEST_PACK_EXPORT = 3921;
    private static final int REQUEST_PACK_IMPORT = 3922;

    public static final int MODE_HOME = 0;
    public static final int MODE_PAGE = 1;

    private static final int KIND_MASTHEAD = 10;
    private static final int KIND_STATS = 11;
    private static final int KIND_SEARCH = 12;
    private static final int KIND_LABEL = 13;
    private static final int KIND_CATEGORY = 14;
    private static final int KIND_QUICK = 15;
    private static final int KIND_MODULE = 1;
    private static final int KIND_INFO = 2; // retired (merged into KIND_MODULE)
    private static final int KIND_ACTION = 3;
    private static final int KIND_FOOTER = 6;
    private static final int KIND_PAGE_HEADER = 17;
    private static final int KIND_ENGINE = 8;
    private static final int KIND_PLUGIN_CARD = 9;
    private static final int KIND_SUBROW = 16;
    private static final int KIND_SEARCH_RESULT = 18;
    private static final int KIND_NOTE = 19;
    private static final int KIND_PAGE_END = 20;

    private static final int SEG_NONE = HubSkin.SEG_NONE;
    private static final int SEG_ONLY = HubSkin.SEG_ONLY;
    private static final int SEG_FIRST = HubSkin.SEG_FIRST;
    private static final int SEG_MID = HubSkin.SEG_MID;
    private static final int SEG_LAST = HubSkin.SEG_LAST;

    // Status-tail tones for module cards.
    private static final int TONE_NONE = 0;
    private static final int TONE_ACTIVE = 1;
    private static final int TONE_ATTENTION = 2;
    private static final int TONE_MUTED = 3;

    // Sub-row flavours: a boolean toggle (switch) or a value pick.
    private static final int SUB_TOGGLE = 0;
    private static final int SUB_PICK = 1;

    static final class Row {
        int kind;
        String title;
        String value;
        String action;
        ModModule module;
        JsPlugin plugin;
        BundledPlugins.Bundled bundled;
        SendWhenOnlineStore.Entry swoEntry;
        // Presentation:
        int seg = SEG_NONE;
        int block = -1;
        String statusText;
        int statusTone = TONE_NONE;
        boolean accent;
        boolean multiline;
        // v3:
        String quickId;
        String categoryId;
        String indexText;
        String subtitle;
        String targetKey;
        int subKind;
        SearchEntry searchEntry;
    }

    // ------------------------------------------------------------------
    // Categories (hub-v2 taxonomy, hub-v3 skin)
    // ------------------------------------------------------------------

    static final class Category {
        final String id;
        final String index;
        final String name;
        final String subtitle;
        final String meta;

        Category(String id, String index, String name, String subtitle, String meta) {
            this.id = id;
            this.index = index;
            this.name = name;
            this.subtitle = subtitle;
            this.meta = meta;
        }
    }

    static final Category[] CATEGORIES = {
            new Category("plugins", "01", "Plugins",
                    "Engine · library · catalog · packs", null),
            new Category("privacy", "02", "Privacy & Ghost",
                    "Ghost · records · hidden chats", "6 MODULES · 3 GROUPS · ALL ON-DEVICE"),
            new Category("appearance", "03", "Appearance & Media",
                    "Wallpaper · icon · font · saver", "4 MODULES · 3 GROUPS · ALL ON-DEVICE"),
            new Category("tools", "04", "Tools & Automation",
                    "Premium · translate · radar · queue", "4 MODULES · 1 GROUP · ALL ON-DEVICE"),
            new Category("network", "05", "Network & Accounts",
                    "DNS profiles · Google account", "2 SERVICES · 1 GROUP · ALL ON-DEVICE"),
            new Category("system", "06", "System & Data",
                    "Backup · restore · developer", "3 ACTIONS · 1 GROUP · ALL ON-DEVICE"),
    };

    static Category categoryById(String id) {
        for (Category category : CATEGORIES) {
            if (category.id.equals(id)) {
                return category;
            }
        }
        return null;
    }

    /** Old ModModule sections are gone from the UI; membership is by
     *  module id into the v2 taxonomy. Returns {categoryId, group} —
     *  group: privacy 0=Ghost 1=Records 2=Locked; appearance
     *  0=Appearance 1=Chat list 2=Media; tools/network n/a (-1). */
    private static String moduleCategory(String moduleId) {
        switch (moduleId) {
            case "ghost_mode":
            case "ghost_stories":
            case "anti_delete":
            case "edit_history":
            case "screenshot_unlock":
            case "hidden_chats":
                return "privacy";
            case "video_background":
            case "hide_stories":
            case "hide_sponsored":
            case "auto_saver":
                return "appearance";
            default:
                return "tools";
        }
    }

    private static int moduleGroup(String moduleId) {
        switch (moduleId) {
            case "ghost_mode":
            case "ghost_stories":
                return 0;
            case "anti_delete":
            case "edit_history":
                return 1;
            case "screenshot_unlock":
            case "hidden_chats":
                return 2;
            case "video_background":
                return 0;
            case "hide_stories":
            case "hide_sponsored":
                return 1;
            case "auto_saver":
                return 2;
            default:
                return -1;
        }
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private final Host host;
    private final int mode;
    private String categoryId;

    private RecyclerListView listView;
    private HubAdapter adapter;
    private final List<Row> rows = new ArrayList<>();
    private int blockCounter;
    private View lastRowView;
    private List<String> pendingPackExportIds;
    private final Runnable swoListener = () -> AndroidUtilities.runOnUIThread(this::rebuild);

    // Home search state.
    private boolean searching;
    private boolean searchPluginsOnly;
    private String searchQuery = "";
    private List<SearchEntry> searchIndex;

    // Search-arrival highlight (page mode).
    private String highlightKey;
    private String flashingKey;

    private static final class SearchEntry {
        String title;
        String breadcrumb;
        String categoryId;
        String targetKey;
        String haystack;
    }

    public HubCommon(Host host, int mode, String categoryId) {
        this.host = host;
        this.mode = mode;
        this.categoryId = categoryId;
    }

    public void setHighlight(String highlightKey) {
        this.highlightKey = highlightKey;
    }

    // ------------------------------------------------------------------
    // View + lifecycle
    // ------------------------------------------------------------------

    public View createRootView(Context context) {
        FrameLayout layout = new FrameLayout(context);
        layout.setBackgroundColor(HubSkin.BG);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new HubAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(view, position));
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rebuild();
        return layout;
    }

    public void onResume() {
        if (mode == MODE_PAGE && "tools".equals(categoryId)) {
            SendWhenOnlineStore.addListener(swoListener);
        }
        rebuild();
    }

    public void onPause() {
        if (mode == MODE_PAGE && "tools".equals(categoryId)) {
            SendWhenOnlineStore.removeListener(swoListener);
        }
    }

    public void rebuild() {
        Context context = host.context();
        if (context == null) {
            return;
        }
        rows.clear();
        blockCounter = 0;
        if (mode == MODE_HOME) {
            buildHomeRows(context);
        } else {
            buildPageRows(context);
        }
        assignSegments();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        maybeFlashHighlight();
    }

    /** Card segments, recomputed per page from the actual row list:
     *  builders tag every card row with a block id; each maximal run
     *  of one block id becomes FIRST/MID/LAST (or ONLY). Everything
     *  else stays uncarded. (The pre-v3 machinery inferred groups
     *  from row kinds and section boundaries; the runs it produced
     *  are the same shape, now explicit.) */
    private void assignSegments() {
        for (Row row : rows) {
            row.seg = SEG_NONE;
        }
        int i = 0;
        while (i < rows.size()) {
            int block = rows.get(i).block;
            if (block < 0) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < rows.size() && rows.get(j + 1).block == block) {
                j++;
            }
            closeGroup(i, j + 1);
            i = j + 1;
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

    private int nextBlock() {
        return ++blockCounter;
    }

    private Row addRow(int kind) {
        Row row = new Row();
        row.kind = kind;
        rows.add(row);
        return row;
    }

    private Row actionRow(int block, String actionId, String title, String value) {
        Row row = addRow(KIND_ACTION);
        row.block = block;
        row.action = actionId;
        row.title = title;
        row.value = value;
        return row;
    }

    /** Child rows are NOT carded (hub-v3 mockup: they hang on the
     *  hairline guide under their module's card), so no block id. */
    private Row subRow(String actionId, String title, String value, int subKind) {
        Row row = addRow(KIND_SUBROW);
        row.action = actionId;
        row.title = title;
        row.value = value;
        row.subKind = subKind;
        return row;
    }

    private void labelRow(String text) {
        Row row = addRow(KIND_LABEL);
        row.title = text;
    }

    // ------------------------------------------------------------------
    // Home builder
    // ------------------------------------------------------------------

    private void buildHomeRows(Context context) {
        if (searching) {
            addRow(KIND_SEARCH);
            buildSearchResults(context);
            return;
        }
        addRow(KIND_MASTHEAD);
        addRow(KIND_STATS);
        addRow(KIND_SEARCH);

        labelRow("PINNED // QUICK ACCESS");
        int quickBlock = nextBlock();
        Row engine = addRow(KIND_QUICK);
        engine.block = quickBlock;
        engine.quickId = "engine";
        engine.title = "Plugins Engine";
        Row ghost = addRow(KIND_QUICK);
        ghost.block = quickBlock;
        ghost.quickId = "ghost";
        ghost.title = "Ghost Mode";
        Row dns = addRow(KIND_QUICK);
        dns.block = quickBlock;
        dns.quickId = "dns";
        dns.title = "DNS Tunnel";

        labelRow("INDEX // CATEGORIES");
        int categoryBlock = nextBlock();
        for (Category category : CATEGORIES) {
            Row row = addRow(KIND_CATEGORY);
            row.block = categoryBlock;
            row.categoryId = category.id;
            row.indexText = category.index;
            row.title = category.name;
            row.subtitle = category.subtitle;
            row.value = categoryHomeValue(context, category);
        }

        Row footer = addRow(KIND_FOOTER);
        // The mockup's footer wording (same disclaimer as the
        // pre-v3 hub, compressed): rendered uppercase mono.
        footer.title = "AminGram runs on Telegram's own sync · no polling, no extra "
                + "background work. Queued actions fire on next launch.";
    }

    private String categoryHomeValue(Context context, Category category) {
        switch (category.id) {
            case "plugins":
                return String.valueOf(JsPluginManager.plugins(context).size());
            case "privacy":
                return "06";
            case "appearance":
                return "06";
            case "tools":
                return "04";
            case "network": {
                String state = "OFF";
                try {
                    switch (AminDnsController.get(context).state()) {
                        case CONNECTED:
                            state = "ON";
                            break;
                        case STARTING:
                            state = "…";
                            break;
                        case ERROR:
                            state = "ERROR";
                            break;
                        default:
                            state = "OFF";
                            break;
                    }
                } catch (Throwable ignored) {
                }
                return "DNS " + state;
            }
            case "system":
                return "03";
            default:
                return "";
        }
    }

    // ------------------------------------------------------------------
    // Category page builders
    // ------------------------------------------------------------------

    private void buildPageRows(Context context) {
        Category category = categoryById(categoryId);
        Row header = addRow(KIND_PAGE_HEADER);
        header.title = category != null ? category.name : "";
        header.indexText = category != null ? category.index : "";
        header.value = category != null ? pageMeta(context, category) : "";
        switch (categoryId) {
            case "plugins":
                buildPluginsPage(context);
                break;
            case "privacy":
                buildGroupedModulesPage(context, "privacy",
                        new String[]{"01 // GHOST", "02 // RECORDS", "03 // LOCKED & HIDDEN"});
                break;
            case "appearance":
                buildGroupedModulesPage(context, "appearance",
                        new String[]{"01 // APPEARANCE", "02 // CHAT LIST", "03 // MEDIA"});
                break;
            case "tools":
                buildGroupedModulesPage(context, "tools", null);
                break;
            case "network":
                buildNetworkPage(context);
                break;
            case "system":
                buildSystemPage(context);
                break;
            default:
                break;
        }
        if (category != null) {
            Row end = addRow(KIND_PAGE_END);
            end.indexText = category.index;
            end.title = category.name;
        }
    }

    private String pageMeta(Context context, Category category) {
        if ("plugins".equals(category.id)) {
            int count = JsPluginManager.plugins(context).size();
            return count + " PLUGINS · 2 GROUPS · ALL ON-DEVICE";
        }
        return category.meta != null ? category.meta : "";
    }

    private void buildGroupedModulesPage(Context context, String category, String[] groupLabels) {
        int currentGroup = -2;
        for (ModModule module : PluginRegistry.modules()) {
            if (!category.equals(moduleCategory(module.id()))) {
                continue;
            }
            int group = moduleGroup(module.id());
            if (groupLabels != null && group != currentGroup) {
                currentGroup = group;
                if (group >= 0 && group < groupLabels.length) {
                    labelRow(groupLabels[group]);
                }
            }
            addModuleBlock(context, module);
            if ("appearance".equals(category) && "video_background".equals(module.id())) {
                // App Icon / App font sit in the Appearance group,
                // right after the wallpaper module's own rows.
                int block = nextBlock();
                actionRow(block, "app_icon", "App Icon", currentIconTitle());
                actionRow(block, "app_font", "App font", AminFonts.familyTitle());
            }
        }
    }

    private void buildNetworkPage(Context context) {
        int block = nextBlock();
        String googleValue = "Not connected";
        try {
            if (GoogleAuthManager.isSignedIn(context)) {
                String email = GoogleAuthManager.connectedEmail(context);
                googleValue = email != null ? email : "Connected";
            }
        } catch (Throwable ignored) {
        }
        actionRow(block, "google_account", "Google Account", googleValue);
        // DNS Tunnel: quick toggle on the ACTIVE profile (the full
        // profiles page lives behind the second row and in Telegram's
        // Proxy settings).
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
        actionRow(block, "dns_toggle", "DNS Tunnel", dnsValue);
        actionRow(block, "dns_open", "DNS Tunnel profiles", dnsProfileName);
    }

    private void buildSystemPage(Context context) {
        int block = nextBlock();
        actionRow(block, "backup_export", "Export AminGram backup", "JSON file");
        actionRow(block, "backup_import", "Import backup", null);
        actionRow(block, "dev_mode", "Developer mode",
                AminPluginsActivity.isDeveloperMode() ? "On for this session" : "Off");
    }

    private void buildPluginsPage(Context context) {
        List<JsPlugin> allPlugins = JsPluginManager.plugins(context);
        labelRow("01 // ENGINE & INSTALLED");
        Row engine = addRow(KIND_ENGINE);
        engine.block = nextBlock();
        actionRow(nextBlock(), "search_plugins", "Search plugins", "All plugins");
        for (JsPlugin plugin : allPlugins) {
            Row card = addRow(KIND_PLUGIN_CARD);
            card.plugin = plugin;
            card.block = nextBlock();
        }
        if (allPlugins.isEmpty()) {
            Row note = addRow(KIND_NOTE);
            note.multiline = true;
            note.title = "No plugins installed yet. Use Add Plugin below or open the Built-in library.";
        }

        labelRow("02 // ADD & MANAGE");
        int block = nextBlock();
        int libraryCount = 0;
        for (BundledPlugins.Bundled bundled : BundledPlugins.all()) {
            if (!BundledPlugins.isInstalled(context, bundled)) libraryCount++;
        }
        actionRow(block, "built_in_library", "Built-in library",
                libraryCount == 0 ? "All installed" : libraryCount + " available");
        actionRow(block, "menu_plugins", "Menu plugins", "Customize attach menu");
        actionRow(block, "add_plugin_file", "Add plugin from file…", ".js");
        actionRow(block, "add_plugin_paste", "Paste plugin code…", null);
        // Catalog (§3.3): fetched only when its page opens; the
        // value hint below comes only from a catalog fetched this
        // session (never fetched from here).
        int catalogUpdates = AminCatalogActivity.sessionUpdateCount(context);
        actionRow(block, "plugin_catalog", "Plugin catalog",
                catalogUpdates > 0 ? catalogUpdates + " update" + (catalogUpdates == 1 ? "" : "s") + " available"
                        : catalogUpdates == 0 ? "Up to date" : "Browse + install");
        actionRow(block, "pack_export", "Export plugin pack…", "Share plugins");
        actionRow(block, "pack_import", "Import plugin pack…", ".json");
    }

    /** One merged module card: the module row itself (title +
     *  description + status tail + switch, computed exactly like the
     *  pre-v3 header + INFO pair) followed by the module's own rows —
     *  action rows inside the same block, toggle/pick children in a
     *  sub-block of their own. */
    private void addModuleBlock(Context context, ModModule module) {
        int block = nextBlock();
        Row row = addRow(KIND_MODULE);
        row.module = module;
        row.block = block;
        String status = "";
        try {
            status = module.status(context);
        } catch (Throwable ignored) {
        }
        if (status == null) {
            status = "";
        }
        boolean enabled = PluginRegistry.isEnabled(context, module);
        if (!enabled) {
            row.statusText = "OFF";
            row.statusTone = TONE_MUTED;
        } else if (status.startsWith("Needs")) {
            row.statusText = status;
            row.statusTone = TONE_ATTENTION;
        } else if (status.isEmpty() || status.equals("Active")) {
            row.statusText = "ACTIVE";
            row.statusTone = TONE_ACTIVE;
        } else {
            row.statusText = status;
            row.statusTone = TONE_ACTIVE;
        }
        addModuleActions(context, module, block);
    }

    private void addModuleActions(Context context, ModModule module, int moduleBlock) {
        switch (module.id()) {
            case "ghost_mode": {
                // Stage 2: granular suppressions under the master
                // Ghost Mode switch. Each is a boolean sub-row in
                // the hub's own switch-row idiom (the module block
                // continues — the guide line carries the indent);
                // all default to On.
                subRow("ghost_suppress_read", "Suppress Read Receipts",
                        GhostModeModule.suppressReadReceipts(context) ? "On" : "Off", SUB_TOGGLE);
                subRow("ghost_suppress_typing", "Suppress Typing & Recording Action",
                        GhostModeModule.suppressTyping(context) ? "On" : "Off", SUB_TOGGLE);
                subRow("ghost_suppress_online", "Suppress Online Status",
                        GhostModeModule.suppressOnlineStatus(context) ? "On" : "Off", SUB_TOGGLE);
                break;
            }
            case "anti_delete": {
                int count = VaultStore.getAll(context, DeletedStore.PREFS, DeletedStore.DELETED_KEY).size();
                actionRow(moduleBlock, "view_deleted", "Deleted messages", String.valueOf(count));
                break;
            }
            case "edit_history": {
                int count = VaultStore.getAll(context, DeletedStore.PREFS, DeletedStore.EDIT_KEY).size();
                actionRow(moduleBlock, "view_edits", "Edit history", String.valueOf(count));
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
                actionRow(moduleBlock, "manage_hidden_chats", "Manage hidden chats", value);
                break;
            }
            case "video_background": {
                // v2.0: the module's files live in the live
                // wallpaper library (its own page); the hub only
                // links there and offers deactivate-without-deleting.
                int count = WallpaperLibraryStore.count(context);
                actionRow(moduleBlock, "live_wallpapers", "Live wallpapers",
                        count == 0 ? "Empty" : count + " wallpapers");
                if (WallpaperLibraryStore.getActive(context) != null) {
                    actionRow(moduleBlock, "clear_wallpaper", "Clear / None", "Keep library");
                }
                break;
            }
            case "auto_saver": {
                int count = AutoSaverStore.count(context);
                actionRow(moduleBlock, "view_saved", "Saved media", String.valueOf(count));
                break;
            }
            case "keyword_radar": {
                int keywords = MessagePipeline.loadKeywords(context).size();
                actionRow(moduleBlock, "edit_keywords", "Radar keywords", keywords == 0 ? "None" : keywords + " set");
                int hits = VaultStore.getAll(context, MessagePipeline.RADAR_PREFS,
                        MessagePipeline.RADAR_HITS_KEY).size();
                actionRow(moduleBlock, "view_hits", "Radar hits", String.valueOf(hits));
                break;
            }
            case "send_when_online": {
                actionRow(moduleBlock, "swo_new", "New message…", "pick a person");
                for (SendWhenOnlineStore.Entry entry : SendWhenOnlineStore.getAll(context)) {
                    Row row = addRow(KIND_ACTION);
                    row.block = moduleBlock;
                    row.action = "swo_entry";
                    row.swoEntry = entry;
                    row.title = entry.name == null || entry.name.isEmpty()
                            ? String.valueOf(entry.peer) : entry.name;
                    row.value = entry.isPending() ? "Waiting for them to come online…"
                            : "Sent ✓ — tap to remove";
                }
                break;
            }
            case "local_premium": {
                // AyuGram-style local premium: sub-toggles that only
                // narrow the master switch (Ghost Mode idiom) plus
                // the per-account picks. Everything is local to this
                // device — see LocalPremiumModule.
                long selfId = UserConfig.getInstance(host.currentAccount()).getClientUserId();
                subRow("lp_flag", "Premium flag",
                        LocalPremiumModule.premiumFlag(context) ? "On" : "Off", SUB_TOGGLE);
                subRow("lp_picker", "Premium emoji & sticker packs",
                        LocalPremiumModule.pickerUnlock(context) ? "On" : "Off", SUB_TOGGLE);
                subRow("lp_status_toggle", "Emoji status",
                        LocalPremiumModule.emojiStatusEnabled(context) ? "On" : "Off", SUB_TOGGLE);
                long statusDoc = LocalPremiumModule.statusDocumentId(context, selfId);
                subRow("lp_status_pick", "Status emoji",
                        statusDoc == 0 ? "None" : "Set", SUB_PICK);
                subRow("lp_name_toggle", "Name emoji",
                        LocalPremiumModule.nameEmojiEnabled(context) ? "On" : "Off", SUB_TOGGLE);
                long nameDoc = LocalPremiumModule.nameEmojiDocumentId(context, selfId);
                String nameAlt = LocalPremiumModule.nameEmojiAlt(context, selfId);
                subRow("lp_name_pick", "Name emoji — choose",
                        nameDoc == 0 ? "None"
                                : nameAlt == null || nameAlt.isEmpty() ? "Set" : nameAlt, SUB_PICK);
                subRow("lp_colors_toggle", "My colors",
                        LocalPremiumModule.peerColorsEnabled(context) ? "On" : "Off", SUB_TOGGLE);
                subRow("lp_name_color", "Name color",
                        peerColorValueText(LocalPremiumModule.nameColorId(context, selfId)), SUB_PICK);
                subRow("lp_profile_color", "Profile color",
                        peerColorValueText(LocalPremiumModule.profileColorId(context, selfId)), SUB_PICK);
                subRow("lp_reply_color", "Reply color",
                        peerColorValueText(LocalPremiumModule.replyColorId(context, selfId)), SUB_PICK);
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Home search — index from the same builders; a result tap
    // navigates (category page + scroll + flash), never toggles.
    // ------------------------------------------------------------------

    public boolean isSearching() {
        return mode == MODE_HOME && searching;
    }

    public boolean exitSearch() {
        if (!searching) {
            return false;
        }
        searching = false;
        searchPluginsOnly = false;
        searchQuery = "";
        searchIndex = null;
        rebuild();
        return true;
    }

    /** Plugins page → "Search plugins": the page asks the host to
     *  finish it; home consumes the request and lands here. */
    public void enterPluginSearch() {
        searching = true;
        searchPluginsOnly = true;
        searchQuery = "";
        searchIndex = null;
        rebuild();
        focusSearchField();
    }

    private final TextWatcher searchWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            searchQuery = s == null ? "" : s.toString();
            searching = true;
            rebuild();
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    };

    private boolean searchHadFocus;

    private void focusSearchField() {
        if (listView == null) {
            return;
        }
        listView.scrollToPosition(0);
        AndroidUtilities.runOnUIThreadDelayed(() -> {
            if (listView == null) {
                return;
            }
            RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(0);
            if (holder != null && holder.itemView instanceof SearchRowView) {
                EditTextBoldCursor field = ((SearchRowView) holder.itemView).editText;
                field.requestFocus();
                AndroidUtilities.showKeyboard(field);
            }
        }, 80);
    }

    private static String rowTargetKey(Row row) {
        switch (row.kind) {
            case KIND_MODULE:
                return row.module != null ? "module:" + row.module.id() : null;
            case KIND_ENGINE:
                return "engine";
            case KIND_PLUGIN_CARD:
                return row.plugin != null ? "plugin:" + row.plugin.id : null;
            case KIND_ACTION:
            case KIND_SUBROW:
                // swo_entry rows are per-entry data, not navigable.
                if (row.action == null || "swo_entry".equals(row.action)) {
                    return null;
                }
                return row.action;
            default:
                return null;
        }
    }

    private void maybeFlashHighlight() {
        if (mode != MODE_PAGE || highlightKey == null || listView == null) {
            return;
        }
        String key = highlightKey;
        highlightKey = null;
        for (int i = 0; i < rows.size(); i++) {
            if (key.equals(rowTargetKey(rows.get(i)))) {
                flashingKey = key;
                listView.scrollToPosition(i);
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                AndroidUtilities.runOnUIThreadDelayed(() -> {
                    if (key.equals(flashingKey)) {
                        flashingKey = null;
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    }
                }, 1400);
                return;
            }
        }
    }

    private boolean isFlashing(Row row) {
        return flashingKey != null && flashingKey.equals(rowTargetKey(row));
    }

    private List<SearchEntry> buildSearchIndex(Context context) {
        List<SearchEntry> index = new ArrayList<>();
        List<Row> saved = new ArrayList<>(rows);
        String savedCategory = categoryId;
        for (Category category : CATEGORIES) {
            SearchEntry self = new SearchEntry();
            self.title = category.name;
            self.breadcrumb = "CATEGORY";
            self.categoryId = category.id;
            self.targetKey = null;
            self.haystack = (category.name + " " + category.subtitle)
                    .toLowerCase(Locale.US);
            index.add(self);

            categoryId = category.id;
            rows.clear();
            buildPageRows(context);
            for (Row row : new ArrayList<>(rows)) {
                String key = rowTargetKey(row);
                if (key == null) {
                    continue;
                }
                SearchEntry entry = new SearchEntry();
                entry.categoryId = category.id;
                entry.targetKey = key;
                entry.breadcrumb = category.name.toUpperCase(Locale.US);
                if (row.kind == KIND_MODULE && row.module != null) {
                    entry.title = row.module.title();
                    entry.haystack = (row.module.title() + " " + row.module.id() + " "
                            + row.module.description()).toLowerCase(Locale.US);
                } else if (row.kind == KIND_PLUGIN_CARD && row.plugin != null) {
                    entry.title = row.plugin.name;
                    // The pre-v3 plugin search's haystack, kept:
                    // name, id, author, description, capabilities.
                    StringBuilder hay = new StringBuilder();
                    hay.append(row.plugin.name).append(' ')
                            .append(row.plugin.id).append(' ')
                            .append(row.plugin.author == null ? "" : row.plugin.author).append(' ')
                            .append(row.plugin.description == null ? "" : row.plugin.description)
                            .append(' ');
                    if (row.plugin.capabilities != null) {
                        for (String cap : row.plugin.capabilities) {
                            hay.append(cap).append(' ');
                        }
                    }
                    entry.haystack = hay.toString().toLowerCase(Locale.US);
                } else {
                    entry.title = row.title;
                    entry.haystack = row.title == null ? ""
                            : row.title.toLowerCase(Locale.US);
                }
                index.add(entry);
            }
        }
        categoryId = savedCategory;
        rows.clear();
        rows.addAll(saved);
        return index;
    }

    private void buildSearchResults(Context context) {
        if (searchIndex == null) {
            searchIndex = buildSearchIndex(context);
        }
        String q = searchQuery.trim().toLowerCase(Locale.US);
        int shown = 0;
        for (SearchEntry entry : searchIndex) {
            if (searchPluginsOnly && !"plugins".equals(entry.categoryId)) {
                continue;
            }
            if (!q.isEmpty() && (entry.haystack == null || !entry.haystack.contains(q))) {
                continue;
            }
            Row row = addRow(KIND_SEARCH_RESULT);
            row.title = entry.title;
            row.subtitle = entry.breadcrumb;
            row.searchEntry = entry;
            shown++;
        }
        if (shown == 0) {
            Row note = addRow(KIND_NOTE);
            note.multiline = true;
            note.title = "No results";
        }
    }

    // ------------------------------------------------------------------
    // Clicks
    // ------------------------------------------------------------------

    private void onRowClick(View view, int position) {
        Context context = host.context();
        if (context == null || position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        lastRowView = view;
        switch (row.kind) {
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
            case KIND_PLUGIN_CARD:
                host.present(new AminJsPluginActivity(row.plugin.id));
                break;
            case KIND_ACTION:
            case KIND_SUBROW:
                onAction(context, row);
                break;
            case KIND_QUICK:
                onQuickClick(context, row);
                break;
            case KIND_CATEGORY:
                host.present(new AminHubPageActivity(row.categoryId));
                break;
            case KIND_SEARCH_RESULT:
                if (row.searchEntry != null) {
                    host.present(new AminHubPageActivity(
                            row.searchEntry.categoryId, row.searchEntry.targetKey));
                }
                break;
            default:
                break;
        }
    }

    /** Quick-access pins run the very same operations as their
     *  in-page rows (engine toggle, Ghost Mode module toggle, DNS
     *  toggle) — no parallel implementations. */
    private void onQuickClick(Context context, Row row) {
        switch (row.quickId) {
            case "engine":
                JsPluginManager.setEngineEnabled(context,
                        !JsPluginManager.isEngineEnabled(context));
                rebuild();
                break;
            case "ghost": {
                for (ModModule module : PluginRegistry.modules()) {
                    if ("ghost_mode".equals(module.id())) {
                        PluginRegistry.setEnabled(context, module,
                                !PluginRegistry.isEnabled(context, module));
                        break;
                    }
                }
                rebuild();
                break;
            }
            case "dns":
                AminDnsController.get(context).toggle(context);
                rebuild();
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
            Context context = host.context();
            if (context != null) {
                JsPluginManager.setEnabled(context, plugin, !plugin.enabled);
                rebuild();
            }
        }

        @Override
        public void onOpenPlugin(JsPlugin plugin) {
            host.present(new AminJsPluginActivity(plugin.id));
        }

        @Override
        public void onPluginLog(JsPlugin plugin) {
            host.present(new AminStoreListActivity(plugin.name + " — log",
                    JsPluginManager.PREFS, "log_" + plugin.id));
        }

        @Override
        public void onPluginMore(JsPlugin plugin) {
            showPluginOverflow(plugin);
        }

        @Override
        public void onDeletePlugin(JsPlugin plugin) {
            Context context = host.context();
            if (context == null || host.parentActivity() == null) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
            builder.setTitle("Delete " + plugin.name + "?");
            builder.setMessage("The plugin file, its settings and its log are removed.");
            builder.setPositiveButton("Delete", (dialog, which) -> {
                JsPluginManager.delete(context, plugin);
                bulletin(R.raw.ic_delete, "Plugin deleted");
                rebuild();
            });
            builder.setNegativeButton("Cancel", null);
            host.showDialog(builder.create());
        }
    };

    private void showPluginOverflow(JsPlugin plugin) {
        Context context = host.context();
        if (context == null || host.parentActivity() == null) return;
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
        AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
        builder.setTitle(plugin.name);
        builder.setItems(items, (dialog, which) -> {
            if (which < 0 || which >= actions.size()) {
                return;
            }
            switch (actions.get(which)) {
                case 0: host.present(new AminJsPluginActivity(plugin.id)); break;
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
        host.showDialog(builder.create());
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
            case "lp_flag":
                LocalPremiumModule.setPremiumFlag(context,
                        !LocalPremiumModule.premiumFlag(context));
                rebuild();
                break;
            case "lp_picker":
                LocalPremiumModule.setPickerUnlock(context,
                        !LocalPremiumModule.pickerUnlock(context));
                rebuild();
                break;
            case "lp_status_toggle":
                LocalPremiumModule.setEmojiStatusEnabled(context,
                        !LocalPremiumModule.emojiStatusEnabled(context));
                rebuild();
                break;
            case "lp_status_pick":
                pickLocalPremiumEmoji(true);
                break;
            case "lp_name_toggle":
                LocalPremiumModule.setNameEmojiEnabled(context,
                        !LocalPremiumModule.nameEmojiEnabled(context));
                rebuild();
                break;
            case "lp_name_pick":
                pickLocalPremiumEmoji(false);
                break;
            case "lp_colors_toggle":
                LocalPremiumModule.setPeerColorsEnabled(context,
                        !LocalPremiumModule.peerColorsEnabled(context));
                rebuild();
                break;
            case "lp_name_color":
                pickLocalPremiumColor(0);
                break;
            case "lp_profile_color":
                pickLocalPremiumColor(1);
                break;
            case "lp_reply_color":
                pickLocalPremiumColor(2);
                break;
            case "view_deleted":
                host.present(new AminStoreListActivity("Deleted messages",
                        DeletedStore.PREFS, DeletedStore.DELETED_KEY));
                break;
            case "view_edits":
                host.present(new AminStoreListActivity("Edit history",
                        DeletedStore.PREFS, DeletedStore.EDIT_KEY));
                break;
            case "view_hits":
                host.present(new AminStoreListActivity("Keyword Radar hits",
                        MessagePipeline.RADAR_PREFS, MessagePipeline.RADAR_HITS_KEY));
                break;
            case "view_saved":
                host.present(new AminSavedMediaActivity());
                break;
            case "live_wallpapers":
                host.present(new AminWallpaperLibraryActivity());
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
            case "app_font":
                pickAppFont();
                break;
            case "manage_hidden_chats":
                host.present(new AminHiddenChatsActivity());
                break;
            case "google_account":
                host.present(new AminGoogleActivity());
                break;
            case "dns_toggle":
                AminDnsController.get(context).toggle(context);
                rebuild();
                break;
            case "dns_open":
                host.present(new AminDnsTunnelActivity());
                break;
            case "search_plugins":
                // v3: the pre-v3 in-page filter dialog is gone —
                // search lives on the home as a real field; this row
                // routes there, pre-filtered to plugins.
                host.openHomePluginSearch();
                break;
            case "built_in_library":
                host.present(new AminBundledLibraryActivity());
                break;
            case "menu_plugins":
                host.present(new AminMenuPluginsActivity());
                break;
            case "swo_new":
                host.present(new AminDialogPickerActivity("Send when online — pick a person", true,
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
            case "dev_mode": {
                boolean devNow = !AminPluginsActivity.isDeveloperMode();
                AminPluginsActivity.setDeveloperMode(devNow);
                bulletin(R.raw.contact_check, devNow
                        ? "Developer mode on — plugin pages show the Developer section"
                        : "Developer mode off");
                rebuild();
                break;
            }
            case "plugin_catalog":
                host.present(new AminCatalogActivity());
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
        BulletinFactory.of(host.fragment()).createSimpleBulletin(icon, text).show();
    }

    private void showError(String error) {
        AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
        builder.setTitle("Plugin not installed");
        builder.setMessage(error);
        builder.setPositiveButton("OK", null);
        host.showDialog(builder.create());
    }

    /** SAF results for the flows this layer starts (the hosting
     *  fragment forwards its callback here). */
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        Context context = host.context();
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
                    AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
                    builder.setTitle("Import failed");
                    builder.setMessage(error);
                    builder.setPositiveButton("OK", null);
                    host.showDialog(builder.create());
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
    // Dialogs and flows (moved from the pre-v3 hub; bodies unchanged
    // apart from host plumbing)
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
        AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
        builder.setTitle("Keyword Radar keywords");
        builder.setView(fieldContainer(context, editText));
        builder.setPositiveButton("Save", (dialog, which) -> {
            MessagePipeline.setKeywords(context, editText.getText().toString());
            rebuild();
        });
        builder.setNegativeButton("Cancel", null);
        host.showDialog(builder.create());
    }

    private void pickAppIcon() {
        Context context = host.context();
        org.telegram.ui.LauncherIconController.LauncherIcon[] icons =
                org.telegram.ui.LauncherIconController.LauncherIcon.values();

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));

        AlertDialog[] dialogHolder = new AlertDialog[1];
        for (org.telegram.ui.LauncherIconController.LauncherIcon icon : icons) {
            boolean active = org.telegram.ui.LauncherIconController.isEnabled(icon);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(4),
                    AndroidUtilities.dp(20), AndroidUtilities.dp(4));
            row.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                    AndroidUtilities.dp(10), android.graphics.Color.TRANSPARENT,
                    Theme.getColor(Theme.key_listSelector), android.graphics.Color.BLACK));

            // preview composed exactly like upstream's own icon picker
            // (AppIconsSelectorCell): background drawable as the image,
            // foreground drawable drawn over it, circular clip
            org.telegram.ui.Cells.AppIconsSelectorCell.AdaptiveIconImageView preview =
                    new org.telegram.ui.Cells.AppIconsSelectorCell.AdaptiveIconImageView(context);
            preview.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8),
                    AndroidUtilities.dp(8), AndroidUtilities.dp(8));
            preview.setImageResource(icon.background);
            preview.setForeground(icon.foreground);
            row.addView(preview, new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(52), AndroidUtilities.dp(52)));

            TextView name = new TextView(context);
            name.setText(org.telegram.messenger.LocaleController.getString(icon.title));
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nameParams.leftMargin = AndroidUtilities.dp(14);
            row.addView(name, nameParams);

            if (active) {
                TextView check = new TextView(context);
                check.setText("✓");
                check.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18);
                check.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
                row.addView(check);
            }

            row.setOnClickListener(v -> {
                org.telegram.ui.LauncherIconController.setIcon(icon);
                bulletin(R.raw.contact_check, "App icon changed");
                rebuild();
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(list);

        AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
        builder.setTitle("App Icon");
        builder.setView(scroll);
        builder.setNegativeButton("Cancel", null);
        dialogHolder[0] = builder.create();
        host.showDialog(dialogHolder[0]);
    }

    private void pickAppFont() {
        CharSequence[] items = new CharSequence[AminFonts.FAMILY_TITLES.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = AminFonts.family() == i
                    ? AminFonts.FAMILY_TITLES[i] + "  \u2713" : AminFonts.FAMILY_TITLES[i];
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
        builder.setTitle("App font");
        builder.setItems(items, (dialog, which) -> {
            AminFonts.setFamily(host.context(), which);
            bulletin(R.raw.contact_check, "Font changed");
            rebuild();
        });
        builder.setNegativeButton("Cancel", null);
        host.showDialog(builder.create());
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

    // ------------------------------------------------------------------
    // Local Premium pickers (all state is this-device-only — see
    // LocalPremiumModule)
    // ------------------------------------------------------------------

    /** The classic names of the seven default peer colors (ids 0–6,
     *  in Theme.keys_avatar_nameInMessage order). */
    private static final String[] PEER_COLOR_NAMES = {
            "Red", "Orange", "Violet", "Green", "Cyan", "Blue", "Pink"
    };

    private long localPremiumSelfId() {
        return UserConfig.getInstance(host.currentAccount()).getClientUserId();
    }

    private static String peerColorName(int colorId) {
        if (colorId >= 0 && colorId < PEER_COLOR_NAMES.length) {
            return PEER_COLOR_NAMES[colorId];
        }
        return "Color " + colorId;
    }

    private String peerColorValueText(int colorId) {
        return colorId == LocalPremiumModule.COLOR_UNSET
                ? "Default" : peerColorName(colorId);
    }

    /** Upstream's custom-emoji picker — the same popup window the
     *  Peer Color / Channel Color pages use — anchored to the tapped
     *  hub row. Picking the empty slot clears the stored document. */
    private void pickLocalPremiumEmoji(boolean forStatus) {
        Context context = host.context();
        View anchor = lastRowView;
        if (context == null || anchor == null || host.parentActivity() == null) {
            return;
        }
        final long selfId = localPremiumSelfId();
        final long current = forStatus
                ? LocalPremiumModule.statusDocumentId(context, selfId)
                : LocalPremiumModule.nameEmojiDocumentId(context, selfId);
        final SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[] popup =
                new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[1];
        int[] location = new int[2];
        anchor.getLocationInWindow(location);
        boolean anchorLow = location[1] > AndroidUtilities.displaySize.y / 2;
        int type = anchorLow
                ? SelectAnimatedEmojiDialog.TYPE_EMOJI_STATUS_TOP
                : SelectAnimatedEmojiDialog.TYPE_EMOJI_STATUS;
        final int popupHeight = (int) Math.min(AndroidUtilities.dp(330),
                AndroidUtilities.displaySize.y * .75f);
        SelectAnimatedEmojiDialog layout = new SelectAnimatedEmojiDialog(host.fragment(), context, true,
                null, type, true, host.fragment().getResourceProvider(), anchorLow ? 24 : 16,
                host.fragment().getThemedColor(Theme.key_windowBackgroundWhiteBlackText)) {
            @Override
            protected void onEmojiSelected(View emojiView, Long documentId,
                    TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                long chosen = documentId == null ? 0 : documentId;
                if (forStatus) {
                    LocalPremiumModule.setStatusDocumentId(context, selfId, chosen);
                } else {
                    String alt = chosen == 0 || document == null ? ""
                            : MessageObject.findAnimatedEmojiEmoticon(document, "");
                    LocalPremiumModule.setNameEmoji(context, selfId, chosen, alt);
                }
                bulletin(R.raw.contact_check,
                        chosen == 0 ? "Cleared" : "Saved — this device only");
                rebuild();
                if (popup[0] != null) {
                    popup[0].dismiss();
                }
            }
        };
        layout.setSelected(current == 0 ? null : current);
        popup[0] = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(layout,
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        int yoff = anchorLow ? -(anchor.getHeight() + popupHeight) : 0;
        popup[0].showAsDropDown(anchor, 0, yoff, Gravity.TOP | Gravity.RIGHT);
        popup[0].dimBehind();
    }

    /** Color picker over upstream's own peer-color palettes
     *  (MessagesController.peerColors / profilePeerColors — the same
     *  lists PeerColorActivity renders). which: 0 = name, 1 =
     *  profile, 2 = reply. */
    private void pickLocalPremiumColor(int which) {
        Context context = host.context();
        if (context == null || host.parentActivity() == null) {
            return;
        }
        MessagesController controller = MessagesController.getInstance(host.currentAccount());
        MessagesController.PeerColors palette = which == 1
                ? controller.profilePeerColors : controller.peerColors;
        if (palette == null || palette.colors.isEmpty()) {
            AlertDialog.Builder notice = new AlertDialog.Builder(host.parentActivity());
            notice.setTitle("Colors");
            notice.setMessage("The color list is still loading — connect once and try again.");
            notice.setPositiveButton("OK", null);
            host.showDialog(notice.create());
            return;
        }
        final long selfId = localPremiumSelfId();
        final int current = which == 0
                ? LocalPremiumModule.nameColorId(context, selfId)
                : which == 1 ? LocalPremiumModule.profileColorId(context, selfId)
                : LocalPremiumModule.replyColorId(context, selfId);
        List<CharSequence> items = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        items.add("Default" + (current == LocalPremiumModule.COLOR_UNSET ? "  ✓" : ""));
        ids.add(LocalPremiumModule.COLOR_UNSET);
        for (MessagesController.PeerColor peerColor : palette.colors) {
            if (peerColor.hidden) {
                continue;
            }
            int swatch = peerColor.isDefaultName && peerColor.id >= 0
                    && peerColor.id < Theme.keys_avatar_nameInMessage.length
                    ? Theme.getColor(Theme.keys_avatar_nameInMessage[peerColor.id])
                    : peerColor.getColor1();
            SpannableString label = new SpannableString("●  " + peerColorName(peerColor.id)
                    + (current == peerColor.id ? "  ✓" : ""));
            label.setSpan(new ForegroundColorSpan(swatch), 0, 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items.add(label);
            ids.add(peerColor.id);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
        builder.setTitle(which == 0 ? "Name color" : which == 1 ? "Profile color" : "Reply color");
        builder.setItems(items.toArray(new CharSequence[0]), (dialog, index) -> {
            if (index < 0 || index >= ids.size()) {
                return;
            }
            int chosen = ids.get(index);
            if (which == 0) {
                LocalPremiumModule.setNameColorId(context, selfId, chosen);
            } else if (which == 1) {
                LocalPremiumModule.setProfileColorId(context, selfId, chosen);
            } else {
                LocalPremiumModule.setReplyColorId(context, selfId, chosen);
            }
            bulletin(R.raw.contact_check, "Saved — this device only");
            rebuild();
        });
        builder.setNegativeButton("Cancel", null);
        host.showDialog(builder.create());
    }

    private void swoComposeText(Context context, long peer, String name) {
        EditTextBoldCursor field = textField(context, "Message to send when they are online", true);
        AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
        builder.setTitle("Send to " + name + " when online");
        builder.setView(fieldContainer(context, field));
        builder.setPositiveButton("Queue", (dialog, which) -> {
            String text = field.getText().toString().trim();
            if (!text.isEmpty()) {
                SendWhenOnlineStore.add(context, host.currentAccount(), peer, name, text);
                bulletin(R.raw.contact_check, "Queued — will send when " + name + " is online");
                rebuild();
            }
        });
        builder.setNegativeButton("Cancel", null);
        host.showDialog(builder.create());
    }

    private void confirmRemoveSwo(Context context, SendWhenOnlineStore.Entry entry) {
        if (entry == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
        builder.setTitle(entry.isPending() ? "Cancel this message?" : "Remove from list?");
        builder.setMessage(entry.text);
        builder.setPositiveButton(entry.isPending() ? "Cancel send" : "Remove", (dialog, which) -> {
            SendWhenOnlineStore.remove(context, entry);
            rebuild();
        });
        builder.setNegativeButton("Keep", null);
        host.showDialog(builder.create());
    }

    private void pastePlugin(Context context) {
        EditTextBoldCursor field = textField(context, "Paste the whole plugin file here", true);
        AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
        builder.setTitle("Add plugin — paste code");
        builder.setView(fieldContainer(context, field));
        builder.setPositiveButton("Review", (dialog, which) ->
                installPluginSource(field.getText().toString(), "paste"));
        builder.setNegativeButton("Cancel", null);
        host.showDialog(builder.create());
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
        host.present(PluginReviewActivity.forInstall(source, sourceTag, null,
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
        AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
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
                host.startActivityForResult(intent, REQUEST_PACK_EXPORT);
            } catch (Exception e) {
                FileLog.e(e);
                bulletin(R.raw.error, "No file picker available");
            }
        });
        builder.setNegativeButton("Cancel", null);
        host.showDialog(builder.create());
    }

    private void pickPackImport() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            host.startActivityForResult(intent, REQUEST_PACK_IMPORT);
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
            AlertDialog.Builder builder = new AlertDialog.Builder(host.parentActivity());
            builder.setTitle("Pack import finished");
            builder.setMessage(results.isEmpty()
                    ? "Nothing was installed."
                    : String.join("\n", results));
            builder.setPositiveButton("OK", null);
            host.showDialog(builder.create());
            rebuild();
            return;
        }
        org.json.JSONObject entry = entries.get(index);
        String code = entry.optString("code", "");
        org.json.JSONObject storage = entry.optJSONObject("storage");
        final String storageJson = storage != null ? storage.toString() : null;
        host.present(PluginReviewActivity.forInstall(code, "pack", storageJson,
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
            host.startActivityForResult(intent, REQUEST_PLUGIN_FILE);
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
            host.startActivityForResult(intent, REQUEST_BACKUP_EXPORT);
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
            host.startActivityForResult(intent, REQUEST_BACKUP_IMPORT);
        } catch (Exception e) {
            FileLog.e(e);
            bulletin(R.raw.error, "No file picker available");
        }
    }

    // ------------------------------------------------------------------
    // Dossier-skin views (hub-local; HubSkin constants + typefaces)
    // ------------------------------------------------------------------

    private static TextView hubText(Context context, float sp, int color,
                                    android.graphics.Typeface typeface) {
        TextView view = new TextView(context);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (typeface != null) {
            view.setTypeface(typeface);
        }
        return view;
    }

    private static FrameLayout switchBox(Context context, Switch switchView) {
        FrameLayout box = new FrameLayout(context);
        box.addView(switchView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        return box;
    }

    /** 力 badge + AMINGRAM wordmark + dossier kicker + marble strip. */
    private static class MastheadView extends LinearLayout {
        MastheadView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(20),
                    AndroidUtilities.dp(24), 0);

            LinearLayout top = new LinearLayout(context);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // The mockup's masthead: the 力 glyph large and unboxed
            // beside the wordmark (Fraunces has no CJK cut — the
            // platform serif fallback draws it, as in the render).
            TextView kanji = hubText(context, 40, HubSkin.BONE, HubSkin.display(context));
            kanji.setText("力");
            top.addView(kanji, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            SpannableString word = new SpannableString("AMINGRAM.");
            word.setSpan(new ForegroundColorSpan(HubSkin.ACCENT),
                    word.length() - 1, word.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView wordView = hubText(context, 29, HubSkin.BONE, HubSkin.display(context));
            wordView.setText(word);
            wordView.setLetterSpacing(0.03f);
            LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wordParams.leftMargin = AndroidUtilities.dp(13);
            top.addView(wordView, wordParams);

            TextView kickerView = hubText(context, 9, HubSkin.GRAY, HubSkin.mono(context));
            kickerView.setText("PLUGINS & MODULES · THE FULL INDEX");
            kickerView.setLetterSpacing(0.14f);
            LinearLayout.LayoutParams kickerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            kickerParams.topMargin = AndroidUtilities.dp(11);
            addView(kickerView, kickerParams);

            ImageView marble = new ImageView(context);
            android.graphics.Bitmap bitmap = HubSkin.marble(context);
            if (bitmap != null) {
                marble.setImageBitmap(bitmap);
            } else {
                marble.setBackgroundColor(HubSkin.SURFACE2);
            }
            marble.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams marbleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(28));
            marbleParams.topMargin = AndroidUtilities.dp(14);
            addView(marble, marbleParams);
        }
    }

    /** 2×2 live stat grid — read at bind time from the same stores
     *  the rows use; no polling, no timers. */
    private static class StatsView extends LinearLayout {
        private final TextView[] values = new TextView[4];
        private final TextView[] labels = new TextView[4];
        private final TextView[] subs = new TextView[4];

        StatsView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(14),
                    AndroidUtilities.dp(24), 0);
            for (int r = 0; r < 2; r++) {
                LinearLayout rowLayout = new LinearLayout(context);
                rowLayout.setOrientation(HORIZONTAL);
                addView(rowLayout, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                for (int c = 0; c < 2; c++) {
                    int i = r * 2 + c;
                    LinearLayout cell = new LinearLayout(context);
                    cell.setOrientation(VERTICAL);
                    cell.setBackground(HubSkin.blockDrawable(SEG_ONLY, HubSkin.SURFACE));
                    cell.setPadding(AndroidUtilities.dp(13), AndroidUtilities.dp(11),
                            AndroidUtilities.dp(13), AndroidUtilities.dp(11));
                    rowLayout.addView(cell, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    // Mockup cell: mono label on top, then the
                    // Fraunces value with the mono sub on its
                    // baseline.
                    labels[i] = hubText(context, 8.5f, HubSkin.GRAY, HubSkin.mono(context));
                    labels[i].setLetterSpacing(0.12f);
                    cell.addView(labels[i]);
                    LinearLayout valueRow = new LinearLayout(context);
                    valueRow.setOrientation(HORIZONTAL);
                    valueRow.setGravity(Gravity.BOTTOM);
                    LinearLayout.LayoutParams valueRowParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    valueRowParams.topMargin = AndroidUtilities.dp(5);
                    cell.addView(valueRow, valueRowParams);
                    values[i] = hubText(context, 20, HubSkin.BONE, HubSkin.display(context));
                    values[i].setSingleLine(true);
                    values[i].setEllipsize(TextUtils.TruncateAt.END);
                    valueRow.addView(values[i], new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    subs[i] = hubText(context, 8.5f, HubSkin.FAINT, HubSkin.mono(context));
                    subs[i].setSingleLine(true);
                    subs[i].setEllipsize(TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    subParams.leftMargin = AndroidUtilities.dp(8);
                    subParams.bottomMargin = AndroidUtilities.dp(2);
                    valueRow.addView(subs[i], subParams);
                }
            }
        }

        private void setCell(int i, String value, String label, String sub) {
            values[i].setText(value);
            values[i].setTextSize(value.length() <= 4 ? 20 : value.length() <= 8 ? 17 : 14.5f);
            labels[i].setText(label);
            subs[i].setText(sub);
        }

        private static String pad2(int n) {
            return n < 10 ? "0" + n : String.valueOf(n);
        }

        void bind(Context context) {
            List<JsPlugin> plugins = JsPluginManager.plugins(context);
            int active = 0;
            for (JsPlugin plugin : plugins) {
                if (plugin.enabled) {
                    active++;
                }
            }
            boolean engineOn = JsPluginManager.isEngineEnabled(context);
            setCell(0, pad2(plugins.size()), "PLUGINS",
                    active + " ACTIVE" + (engineOn ? "" : " · ENGINE OFF"));

            String dnsValue = "OFF";
            boolean dnsOn = false;
            try {
                switch (AminDnsController.get(context).state()) {
                    case CONNECTED:
                        dnsValue = "ON";
                        dnsOn = true;
                        break;
                    case STARTING:
                        dnsValue = "STARTING";
                        break;
                    case ERROR:
                        dnsValue = "ERROR";
                        break;
                    default:
                        break;
                }
            } catch (Throwable ignored) {
            }
            String profile = "NONE";
            try {
                DnsProfile activeProfile = DnsProfiles.activeProfile(context);
                if (activeProfile != null && activeProfile.name != null
                        && !activeProfile.name.isEmpty()) {
                    profile = activeProfile.name.toUpperCase(Locale.US);
                }
            } catch (Throwable ignored) {
            }
            setCell(1, dnsValue, "DNS TUNNEL", "PROFILE · " + profile);
            subs[1].setTextColor(dnsOn ? HubSkin.ACCENT_LIGHT : HubSkin.FAINT);

            List<ModModule> modules = PluginRegistry.modules();
            int enabled = 0;
            for (ModModule module : modules) {
                if (PluginRegistry.isEnabled(context, module)) {
                    enabled++;
                }
            }
            setCell(2, enabled + "/" + modules.size(), "MODULES", "ENABLED");

            String fontSub = "CUSTOM FACE";
            try {
                if (AminFonts.family() == 0) {
                    fontSub = "STOCK FACE";
                }
            } catch (Throwable ignored) {
            }
            setCell(3, AminFonts.familyTitle().toUpperCase(Locale.US), "APP FONT", fontSub);
        }
    }

    private static class MagnifierView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        MagnifierView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dp(1.6f));
            paint.setColor(HubSkin.GRAY);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = AndroidUtilities.dp(5.5f);
            float cx = getWidth() / 2f - AndroidUtilities.dp(1.5f);
            float cy = getHeight() / 2f - AndroidUtilities.dp(1.5f);
            canvas.drawCircle(cx, cy, r, paint);
            canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f,
                    cx + r * 1.6f, cy + r * 1.6f, paint);
        }
    }

    private static class SearchRowView extends FrameLayout {
        final EditTextBoldCursor editText;
        private boolean watcherAttached;

        SearchRowView(Context context) {
            super(context);
            LinearLayout inner = new LinearLayout(context);
            inner.setOrientation(LinearLayout.HORIZONTAL);
            inner.setGravity(Gravity.CENTER_VERTICAL);
            inner.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(2),
                    AndroidUtilities.dp(14), AndroidUtilities.dp(2));
            addView(inner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            MagnifierView magnifier = new MagnifierView(context);
            inner.addView(magnifier, new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(18), AndroidUtilities.dp(18)));

            editText = new EditTextBoldCursor(context);
            editText.setBackground(null);
            editText.setSingleLine(true);
            editText.setTextSize(13);
            editText.setTypeface(HubSkin.mono(context));
            editText.setTextColor(HubSkin.BONE);
            editText.setHintTextColor(HubSkin.FAINT);
            editText.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(11),
                    0, AndroidUtilities.dp(11));
            LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            fieldParams.leftMargin = AndroidUtilities.dp(2);
            inner.addView(editText, fieldParams);
        }

        void bind(HubCommon hub) {
            if (!watcherAttached) {
                watcherAttached = true;
                editText.addTextChangedListener(hub.searchWatcher);
                editText.setOnFocusChangeListener((v, hasFocus) -> hub.searchHadFocus = hasFocus);
            }
            String current = editText.getText() == null ? ""
                    : editText.getText().toString();
            if (!current.equals(hub.searchQuery)) {
                editText.setText(hub.searchQuery);
                editText.setSelection(editText.getText().length());
            }
            editText.setHint(hub.searchPluginsOnly ? "SEARCH PLUGINS" : "SEARCH MODULES · PLUGINS · SETTINGS");
            if (hub.searchHadFocus && !editText.hasFocus()) {
                editText.post(editText::requestFocus);
            }
        }
    }

    private static class LabelView extends TextView {
        LabelView(Context context) {
            super(context);
            setTypeface(HubSkin.mono(context));
            setTextSize(10);
            setLetterSpacing(0.1f);
            setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(20),
                    AndroidUtilities.dp(24), AndroidUtilities.dp(9));
        }

        void setLabel(String text) {
            int idx = text.indexOf("//");
            if (idx > 0 && idx + 2 < text.length()) {
                SpannableString spanned = new SpannableString(text);
                spanned.setSpan(new ForegroundColorSpan(HubSkin.ACCENT), 0, idx + 2,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spanned.setSpan(new ForegroundColorSpan(HubSkin.GRAY), idx + 2,
                        text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                setText(spanned);
            } else {
                setTextColor(HubSkin.GRAY);
                setText(text);
            }
        }
    }

    private static class PageHeaderView extends LinearLayout {
        private final TextView kickerView;
        private final TextView titleView;
        private final TextView metaView;

        PageHeaderView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(18),
                    AndroidUtilities.dp(24), 0);
            kickerView = hubText(context, 9.5f, HubSkin.ACCENT, HubSkin.mono(context));
            kickerView.setLetterSpacing(0.12f);
            addView(kickerView);
            titleView = hubText(context, 28, HubSkin.BONE, HubSkin.display(context));
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.topMargin = AndroidUtilities.dp(6);
            addView(titleView, titleParams);
            metaView = hubText(context, 9, HubSkin.GRAY, HubSkin.mono(context));
            metaView.setLetterSpacing(0.08f);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaParams.topMargin = AndroidUtilities.dp(7);
            addView(metaView, metaParams);
            View hairline = new View(context);
            hairline.setBackgroundColor(HubSkin.HAIRLINE);
            LinearLayout.LayoutParams hairlineParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            hairlineParams.topMargin = AndroidUtilities.dp(15);
            addView(hairline, hairlineParams);
        }

        void bind(Row row) {
            String kicker = "CATEGORY // " + row.indexText;
            SpannableString spanned = new SpannableString(kicker + " · DOSSIER");
            spanned.setSpan(new ForegroundColorSpan(HubSkin.ACCENT), 0, kicker.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spanned.setSpan(new ForegroundColorSpan(HubSkin.GRAY), kicker.length(),
                    spanned.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            kickerView.setText(spanned);
            titleView.setText(row.title);
            metaView.setText(row.value);
        }
    }

    private static class CategoryRowView extends LinearLayout {
        private final TextView indexView;
        private final TextView nameView;
        private final TextView subView;
        private final TextView valueView;
        private final View dividerView;

        CategoryRowView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13),
                    AndroidUtilities.dp(14), AndroidUtilities.dp(13));
            addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            indexView = hubText(context, 11, HubSkin.ACCENT, HubSkin.mono(context));
            content.addView(indexView, new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout middle = new LinearLayout(context);
            middle.setOrientation(VERTICAL);
            content.addView(middle, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            nameView = hubText(context, 17, HubSkin.BONE, HubSkin.displaySemiBold(context));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            middle.addView(nameView);
            subView = hubText(context, 12.5f, HubSkin.SECONDARY, HubSkin.sans(context));
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subParams.topMargin = AndroidUtilities.dp(3);
            middle.addView(subView, subParams);
            valueView = hubText(context, 11, HubSkin.SECONDARY, HubSkin.mono(context));
            content.addView(valueView);
            TextView chevron = hubText(context, 19, HubSkin.FAINT, HubSkin.sans(context));
            chevron.setText("›");
            LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chevronParams.leftMargin = AndroidUtilities.dp(7);
            content.addView(chevron, chevronParams);
            dividerView = new View(context);
            dividerView.setBackgroundColor(HubSkin.HAIRLINE);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dividerParams.leftMargin = AndroidUtilities.dp(16);
            addView(dividerView, dividerParams);
        }

        void bind(Row row) {
            indexView.setText(row.indexText);
            nameView.setText(row.title);
            subView.setText(row.subtitle);
            valueView.setText(row.value);
            dividerView.setVisibility(row.seg == SEG_FIRST || row.seg == SEG_MID
                    ? VISIBLE : GONE);
        }
    }

    /** One switch row in the dossier idiom: Grotesk title, mono
     *  ON/OFF cue, display-only recolored switch (the row toggles). */
    private static class ToggleRowView extends LinearLayout {
        private final TextView titleView;
        private final TextView stateView;
        private final Switch switchView;
        private final View dividerView;

        ToggleRowView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(7),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(7));
            addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            titleView = hubText(context, 15, HubSkin.BONE, HubSkin.sansMedium(context));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(titleView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            stateView = hubText(context, 9.5f, HubSkin.FAINT, HubSkin.mono(context));
            LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stateParams.rightMargin = AndroidUtilities.dp(10);
            content.addView(stateView, stateParams);
            switchView = HubSkin.newSwitch(context);
            switchView.setFocusable(false);
            switchView.setClickable(false);
            content.addView(switchBox(context, switchView), new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(56), AndroidUtilities.dp(40)));
            dividerView = new View(context);
            dividerView.setBackgroundColor(HubSkin.HAIRLINE);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dividerParams.leftMargin = AndroidUtilities.dp(16);
            addView(dividerView, dividerParams);
        }

        void bind(String title, boolean on, String stateText, int seg) {
            titleView.setText(title);
            switchView.setChecked(on, false);
            stateView.setText(stateText);
            stateView.setTextColor(on ? HubSkin.ACCENT_LIGHT : HubSkin.FAINT);
            dividerView.setVisibility(seg == SEG_FIRST || seg == SEG_MID ? VISIBLE : GONE);
        }
    }

    /** The merged module card: title + state + switch on line 1,
     *  and the description under it with the status folded in as a
     *  mono tail ("…presence. · ACTIVE") — the pre-v3 header +
     *  INFO pair as one row, exactly as the v3 mockup draws it. */
    private static class ModuleCardView extends LinearLayout {
        private final TextView titleView;
        private final TextView stateView;
        private final Switch switchView;
        private final TextView descView;
        private final View dividerView;

        ModuleCardView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(12));
            LinearLayout line1 = new LinearLayout(context);
            line1.setOrientation(HORIZONTAL);
            line1.setGravity(Gravity.CENTER_VERTICAL);
            addView(line1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            titleView = hubText(context, 15.5f, HubSkin.BONE, HubSkin.sansMedium(context));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            line1.addView(titleView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            stateView = hubText(context, 9.5f, HubSkin.FAINT, HubSkin.mono(context));
            LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stateParams.rightMargin = AndroidUtilities.dp(10);
            line1.addView(stateView, stateParams);
            switchView = HubSkin.newSwitch(context);
            switchView.setFocusable(false);
            switchView.setClickable(false);
            line1.addView(switchBox(context, switchView), new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(56), AndroidUtilities.dp(40)));

            descView = hubText(context, 13, HubSkin.SECONDARY, HubSkin.sans(context));
            descView.setMaxLines(3);
            descView.setEllipsize(TextUtils.TruncateAt.END);
            descView.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            descParams.topMargin = AndroidUtilities.dp(4);
            addView(descView, descParams);

            dividerView = new View(context);
            dividerView.setBackgroundColor(HubSkin.HAIRLINE);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dividerParams.topMargin = AndroidUtilities.dp(12);
            dividerParams.bottomMargin = -AndroidUtilities.dp(12);
            addView(dividerView, dividerParams);
        }

        void bind(Context context, Row row) {
            ModModule module = row.module;
            titleView.setText(module.title());
            boolean enabled = PluginRegistry.isEnabled(context, module);
            switchView.setChecked(enabled, false);
            stateView.setText(enabled ? "ON" : "OFF");
            stateView.setTextColor(enabled ? HubSkin.ACCENT_LIGHT : HubSkin.FAINT);
            String description = module.description() == null ? "" : module.description();
            String tail = row.statusText == null ? "" : row.statusText;
            if (tail.isEmpty()) {
                descView.setText(description);
                descView.setVisibility(description.isEmpty() ? GONE : VISIBLE);
            } else {
                String full = description.isEmpty() ? tail : description + "  ·  " + tail;
                SpannableString spanned = new SpannableString(full);
                int tailStart = full.length() - tail.length();
                spanned.setSpan(new MonoSpan(HubSkin.mono(context)), tailStart,
                        full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spanned.setSpan(new RelativeSizeSpan(0.8f), tailStart,
                        full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spanned.setSpan(new ForegroundColorSpan(
                        row.statusTone == TONE_ACTIVE ? HubSkin.ACCENT_LIGHT
                                : row.statusTone == TONE_ATTENTION ? HubSkin.ACCENT
                                : row.statusTone == TONE_MUTED ? HubSkin.FAINT
                                : HubSkin.GRAY), tailStart, full.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                descView.setVisibility(VISIBLE);
                descView.setText(spanned);
            }
            dividerView.setVisibility(row.seg == SEG_FIRST || row.seg == SEG_MID
                    ? VISIBLE : GONE);
        }
    }

    /** Sets a mono typeface over a span of a Grotesk paragraph
     *  (the module card's status tail). */
    private static class MonoSpan extends MetricAffectingSpan {
        private final android.graphics.Typeface typeface;

        MonoSpan(android.graphics.Typeface typeface) {
            this.typeface = typeface;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setTypeface(typeface);
        }

        @Override
        public void updateMeasureState(TextPaint paint) {
            paint.setTypeface(typeface);
        }
    }

    private static class ActionRowView extends LinearLayout {
        private final TextView titleView;
        private final TextView valueView;
        private final View dividerView;

        ActionRowView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13),
                    AndroidUtilities.dp(12), AndroidUtilities.dp(13));
            addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            titleView = hubText(context, 14.5f, HubSkin.BONE, HubSkin.sans(context));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(titleView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            valueView = hubText(context, 10.5f, HubSkin.GRAY, HubSkin.mono(context));
            valueView.setSingleLine(true);
            valueView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            valueParams.leftMargin = AndroidUtilities.dp(10);
            content.addView(valueView, valueParams);
            TextView chevron = hubText(context, 18, HubSkin.FAINT, HubSkin.sans(context));
            chevron.setText("›");
            LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chevronParams.leftMargin = AndroidUtilities.dp(6);
            content.addView(chevron, chevronParams);
            dividerView = new View(context);
            dividerView.setBackgroundColor(HubSkin.HAIRLINE);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dividerParams.leftMargin = AndroidUtilities.dp(16);
            addView(dividerView, dividerParams);
        }

        void bind(Row row) {
            titleView.setText(row.title);
            if (row.value == null) {
                valueView.setVisibility(GONE);
            } else {
                valueView.setVisibility(VISIBLE);
                valueView.setText(row.value);
            }
            dividerView.setVisibility(row.seg == SEG_FIRST || row.seg == SEG_MID
                    ? VISIBLE : GONE);
        }
    }

    /** Indented child row — NOT carded: in the v3 mockup the
     *  children hang on a hairline guide under their module's card,
     *  each marked with a small orange tick. The guide tiles across
     *  consecutive child rows into one continuous line. Right side
     *  is either a switch (toggle child) or a mono value + chevron
     *  (pick child). */
    private static class SubRowView extends FrameLayout {
        private final TextView titleView;
        private final LinearLayout toggleGroup;
        private final TextView stateView;
        private final Switch switchView;
        private final LinearLayout pickGroup;
        private final TextView valueView;

        SubRowView(Context context) {
            super(context);
            View guide = new View(context);
            guide.setBackgroundColor(HubSkin.HAIRLINE);
            FrameLayout.LayoutParams guideParams = new FrameLayout.LayoutParams(
                    1, ViewGroup.LayoutParams.MATCH_PARENT);
            guideParams.leftMargin = AndroidUtilities.dp(39);
            addView(guide, guideParams);

            View tick = new View(context);
            tick.setBackgroundColor(HubSkin.ACCENT);
            FrameLayout.LayoutParams tickParams = new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(5), AndroidUtilities.dp(5));
            tickParams.leftMargin = AndroidUtilities.dp(37);
            tickParams.gravity = Gravity.CENTER_VERTICAL;
            addView(tick, tickParams);

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(0, AndroidUtilities.dp(4), AndroidUtilities.dp(40),
                    AndroidUtilities.dp(4));
            FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            contentParams.leftMargin = AndroidUtilities.dp(58);
            addView(content, contentParams);

            titleView = hubText(context, 14, HubSkin.SECONDARY, HubSkin.sans(context));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(titleView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            toggleGroup = new LinearLayout(context);
            toggleGroup.setOrientation(HORIZONTAL);
            toggleGroup.setGravity(Gravity.CENTER_VERTICAL);
            content.addView(toggleGroup);
            stateView = hubText(context, 9.5f, HubSkin.FAINT, HubSkin.mono(context));
            LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stateParams.rightMargin = AndroidUtilities.dp(10);
            toggleGroup.addView(stateView, stateParams);
            switchView = HubSkin.newSwitch(context);
            switchView.setFocusable(false);
            switchView.setClickable(false);
            toggleGroup.addView(switchBox(context, switchView), new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(56), AndroidUtilities.dp(40)));

            pickGroup = new LinearLayout(context);
            pickGroup.setOrientation(HORIZONTAL);
            pickGroup.setGravity(Gravity.CENTER_VERTICAL);
            content.addView(pickGroup);
            valueView = hubText(context, 10.5f, HubSkin.SECONDARY, HubSkin.mono(context));
            valueView.setSingleLine(true);
            valueView.setEllipsize(TextUtils.TruncateAt.END);
            pickGroup.addView(valueView);
            TextView chevron = hubText(context, 18, HubSkin.FAINT, HubSkin.sans(context));
            chevron.setText("›");
            LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chevronParams.leftMargin = AndroidUtilities.dp(6);
            pickGroup.addView(chevron, chevronParams);
        }

        void bind(Row row) {
            titleView.setText(row.title);
            if (row.subKind == SUB_TOGGLE) {
                pickGroup.setVisibility(GONE);
                toggleGroup.setVisibility(VISIBLE);
                boolean on = "On".equals(row.value);
                switchView.setChecked(on, false);
                stateView.setText(on ? "ON" : "OFF");
                stateView.setTextColor(on ? HubSkin.ACCENT_LIGHT : HubSkin.FAINT);
            } else {
                toggleGroup.setVisibility(GONE);
                pickGroup.setVisibility(VISIBLE);
                valueView.setText(row.value);
            }
        }
    }

    private static class SearchResultView extends LinearLayout {
        private final TextView titleView;
        private final TextView crumbView;

        SearchResultView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(11),
                    AndroidUtilities.dp(24), 0);
            titleView = hubText(context, 14.5f, HubSkin.BONE, HubSkin.sansMedium(context));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            addView(titleView);
            crumbView = hubText(context, 8.5f, HubSkin.FAINT, HubSkin.mono(context));
            crumbView.setLetterSpacing(0.1f);
            LinearLayout.LayoutParams crumbParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            crumbParams.topMargin = AndroidUtilities.dp(3);
            addView(crumbView, crumbParams);
            View divider = new View(context);
            divider.setBackgroundColor(HubSkin.HAIRLINE);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dividerParams.topMargin = AndroidUtilities.dp(11);
            addView(divider, dividerParams);
        }

        void bind(Row row) {
            titleView.setText(row.title);
            crumbView.setText(row.subtitle);
        }
    }

    private static class NoteView extends TextView {
        NoteView(Context context) {
            super(context);
            setTypeface(HubSkin.sans(context));
            setTextSize(13);
            setTextColor(HubSkin.GRAY);
            setLineSpacing(0, 1.25f);
            setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(10),
                    AndroidUtilities.dp(24), AndroidUtilities.dp(10));
        }
    }

    private static class FooterView extends LinearLayout {
        private final TextView disclaimerView;
        private final TextView versionView;

        FooterView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24),
                    AndroidUtilities.dp(24), AndroidUtilities.dp(30));
            disclaimerView = hubText(context, 8.5f, HubSkin.FAINT, HubSkin.mono(context));
            disclaimerView.setGravity(Gravity.CENTER);
            disclaimerView.setLineSpacing(0, 1.35f);
            disclaimerView.setLetterSpacing(0.04f);
            addView(disclaimerView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            versionView = hubText(context, 9, HubSkin.GRAY, HubSkin.mono(context));
            versionView.setGravity(Gravity.CENTER);
            versionView.setLetterSpacing(0.08f);
            LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            versionParams.topMargin = AndroidUtilities.dp(12);
            addView(versionView, versionParams);
            View tick = new View(context);
            tick.setBackgroundColor(HubSkin.ACCENT);
            LinearLayout.LayoutParams tickParams = new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(26), AndroidUtilities.dp(3));
            tickParams.topMargin = AndroidUtilities.dp(14);
            tickParams.gravity = Gravity.CENTER_HORIZONTAL;
            addView(tick, tickParams);
        }

        void bind(Row row) {
            disclaimerView.setText(row.title == null ? ""
                    : row.title.toUpperCase(Locale.US));
            String version = null;
            try {
                Context context = getContext();
                version = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Throwable ignored) {
            }
            versionView.setText(version == null || version.isEmpty()
                    ? "AMINGRAM · HUB DOSSIER V3"
                    : "AMINGRAM · VERSION " + version + " · HUB DOSSIER V3");
        }
    }

    /** "END OF CATEGORY 02 — PRIVACY & GHOST" + the orange end-tick. */
    private static class PageEndView extends LinearLayout {
        private final TextView textView;

        PageEndView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(26),
                    AndroidUtilities.dp(24), AndroidUtilities.dp(30));
            textView = hubText(context, 8.5f, HubSkin.FAINT, HubSkin.mono(context));
            textView.setLetterSpacing(0.1f);
            addView(textView);
            View tick = new View(context);
            tick.setBackgroundColor(HubSkin.ACCENT);
            LinearLayout.LayoutParams tickParams = new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(26), AndroidUtilities.dp(3));
            tickParams.topMargin = AndroidUtilities.dp(12);
            tickParams.gravity = Gravity.CENTER_HORIZONTAL;
            addView(tick, tickParams);
        }

        void bind(Row row) {
            textView.setText("END OF CATEGORY " + row.indexText + " — "
                    + (row.title == null ? "" : row.title.toUpperCase(Locale.US)));
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
     *  area over it performs the toggle.
     *  v3: presentation moved to the dossier skin (surface fill,
     *  hairline stroke, Fraunces name, mono meta/chips); structure,
     *  listeners and the v1.3.4 suppression fix are unchanged. */
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
            chipLetter.setTextSize(16);
            chipLetter.setTypeface(HubSkin.displaySemiBold(context));
            chipLetter.setGravity(Gravity.CENTER);
            chip.addView(chipLetter, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View spacer = new View(context);
            header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

            FrameLayout switchBox = new FrameLayout(context);
            header.addView(switchBox, new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(56), AndroidUtilities.dp(40)));
            switchView = HubSkin.newSwitch(context);
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
            nameView.setTextSize(18);
            nameView.setTypeface(HubSkin.displaySemiBold(context));
            nameView.setTextColor(HubSkin.BONE);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameParams.topMargin = AndroidUtilities.dp(10);
            addView(nameView, nameParams);

            metaView = new TextView(context);
            metaView.setTextSize(11.5f);
            metaView.setTypeface(HubSkin.mono(context));
            metaView.setTextColor(HubSkin.GRAY);
            metaView.setSingleLine(true);
            metaView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaParams.topMargin = AndroidUtilities.dp(2);
            addView(metaView, metaParams);

            descView = new TextView(context);
            descView.setTextSize(13);
            descView.setTypeface(HubSkin.sans(context));
            descView.setTextColor(HubSkin.SECONDARY);
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
            hairline.setBackgroundColor(HubSkin.HAIRLINE);
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
            moreBtn.setTextColor(HubSkin.GRAY);
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
            setBackground(HubSkin.blockDrawable(SEG_ONLY));

            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setShape(GradientDrawable.RECTANGLE);
            chipBg.setColor(HubSkin.SURFACE2);
            chipBg.setStroke(1, HubSkin.HAIRLINE);
            chipBg.setCornerRadius(AndroidUtilities.dp(2));
            chip.setBackground(chipBg);
            chipLetter.setText(plugin.name == null || plugin.name.isEmpty() ? "?"
                    : plugin.name.substring(0, 1).toUpperCase(Locale.US));
            chipLetter.setTextColor(HubSkin.ACCENT);

            switchView.setChecked(plugin.enabled, false);
            nameView.setText(plugin.name);

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
                spanned.setSpan(new ForegroundColorSpan(HubSkin.ACCENT),
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
                    chipView.setTextSize(10);
                    chipView.setTypeface(HubSkin.mono(getContext()));
                    chipView.setTextColor(HubSkin.SECONDARY);
                    chipView.setSingleLine(true);
                    chipView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(3),
                            AndroidUtilities.dp(8), AndroidUtilities.dp(3));
                    GradientDrawable chipDrawable = new GradientDrawable();
                    chipDrawable.setShape(GradientDrawable.RECTANGLE);
                    chipDrawable.setColor(HubSkin.SURFACE2);
                    chipDrawable.setStroke(1, HubSkin.HAIRLINE);
                    chipDrawable.setCornerRadius(AndroidUtilities.dp(2));
                    chipView.setBackground(chipDrawable);
                    LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    chipParams.rightMargin = AndroidUtilities.dp(6);
                    chipsRow.addView(chipView, chipParams);
                }
            }

            settingsBtn.setColorFilter(new PorterDuffColorFilter(HubSkin.GRAY, PorterDuff.Mode.SRC_IN));
            logBtn.setColorFilter(new PorterDuffColorFilter(HubSkin.GRAY, PorterDuff.Mode.SRC_IN));
            moreBtn.setTextColor(HubSkin.GRAY);
            deleteBtn.setColorFilter(new PorterDuffColorFilter(
                    HubSkin.ACCENT, PorterDuff.Mode.SRC_IN));

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
            return kind != KIND_MASTHEAD && kind != KIND_STATS
                    && kind != KIND_SEARCH && kind != KIND_LABEL
                    && kind != KIND_PAGE_HEADER && kind != KIND_FOOTER
                    && kind != KIND_NOTE && kind != KIND_PAGE_END;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            switch (viewType) {
                case KIND_MASTHEAD:
                    view = new MastheadView(context);
                    break;
                case KIND_STATS:
                    view = new StatsView(context);
                    break;
                case KIND_SEARCH:
                    view = new SearchRowView(context);
                    break;
                case KIND_LABEL:
                    view = new LabelView(context);
                    break;
                case KIND_PAGE_HEADER:
                    view = new PageHeaderView(context);
                    break;
                case KIND_CATEGORY:
                    view = new CategoryRowView(context);
                    break;
                case KIND_QUICK:
                case KIND_ENGINE:
                    view = new ToggleRowView(context);
                    break;
                case KIND_MODULE:
                    view = new ModuleCardView(context);
                    break;
                case KIND_PLUGIN_CARD: {
                    PluginCardView card = new PluginCardView(context);
                    card.setCallbacks(pluginCardCallbacks);
                    view = card;
                    break;
                }
                case KIND_ACTION:
                    view = new ActionRowView(context);
                    break;
                case KIND_SUBROW:
                    view = new SubRowView(context);
                    break;
                case KIND_SEARCH_RESULT:
                    view = new SearchResultView(context);
                    break;
                case KIND_FOOTER:
                    view = new FooterView(context);
                    break;
                case KIND_PAGE_END:
                    view = new PageEndView(context);
                    break;
                case KIND_NOTE:
                default:
                    view = new NoteView(context);
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
            // Block rows float on the page background: 24dp side
            // margins and a 12dp gap above the first segment of
            // each block. (Pre-v3: 12dp margins / 10dp gap — the
            // dossier page margin is wider.)
            RecyclerView.LayoutParams lp =
                    (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
            boolean carded = row.seg != SEG_NONE;
            lp.leftMargin = carded ? AndroidUtilities.dp(24) : 0;
            lp.rightMargin = carded ? AndroidUtilities.dp(24) : 0;
            lp.topMargin = row.seg == SEG_FIRST || row.seg == SEG_ONLY
                    ? AndroidUtilities.dp(12) : 0;
            holder.itemView.setLayoutParams(lp);
            if (carded && row.kind != KIND_PLUGIN_CARD) {
                holder.itemView.setBackground(isFlashing(row)
                        ? HubSkin.highlightDrawable(row.seg)
                        : HubSkin.blockDrawable(row.seg));
            }
            switch (row.kind) {
                case KIND_MASTHEAD:
                    break;
                case KIND_STATS:
                    ((StatsView) holder.itemView).bind(context);
                    break;
                case KIND_SEARCH:
                    lp.leftMargin = AndroidUtilities.dp(24);
                    lp.rightMargin = AndroidUtilities.dp(24);
                    lp.topMargin = AndroidUtilities.dp(14);
                    holder.itemView.setLayoutParams(lp);
                    holder.itemView.setBackground(
                            HubSkin.blockDrawable(SEG_ONLY, HubSkin.SURFACE));
                    ((SearchRowView) holder.itemView).bind(HubCommon.this);
                    break;
                case KIND_LABEL:
                    ((LabelView) holder.itemView).setLabel(row.title);
                    break;
                case KIND_PAGE_HEADER:
                    ((PageHeaderView) holder.itemView).bind(row);
                    break;
                case KIND_CATEGORY:
                    ((CategoryRowView) holder.itemView).bind(row);
                    break;
                case KIND_QUICK: {
                    ToggleRowView toggle = (ToggleRowView) holder.itemView;
                    if ("engine".equals(row.quickId)) {
                        boolean on = JsPluginManager.isEngineEnabled(context);
                        toggle.bind(row.title, on, on ? "ON" : "OFF", row.seg);
                    } else if ("ghost".equals(row.quickId)) {
                        boolean on = PluginRegistry.isEnabledById(context, "ghost_mode");
                        toggle.bind(row.title, on, on ? "ON" : "OFF", row.seg);
                    } else {
                        String stateText = "OFF";
                        boolean on = false;
                        try {
                            switch (AminDnsController.get(context).state()) {
                                case CONNECTED:
                                    stateText = "ON";
                                    on = true;
                                    break;
                                case STARTING:
                                    stateText = "…";
                                    on = true;
                                    break;
                                case ERROR:
                                    stateText = "ERR";
                                    break;
                                default:
                                    break;
                            }
                        } catch (Throwable ignored) {
                        }
                        toggle.bind(row.title, on, stateText, row.seg);
                    }
                    break;
                }
                case KIND_ENGINE: {
                    boolean on = JsPluginManager.isEngineEnabled(context);
                    ((ToggleRowView) holder.itemView).bind("Plugins Engine", on, on ? "ON" : "OFF", row.seg);
                    break;
                }
                case KIND_MODULE:
                    ((ModuleCardView) holder.itemView).bind(context, row);
                    break;
                case KIND_PLUGIN_CARD:
                    ((PluginCardView) holder.itemView).bind(row.plugin,
                            JsPluginManager.isEngineEnabled(context));
                    if (isFlashing(row)) {
                        holder.itemView.setBackground(HubSkin.highlightDrawable(SEG_ONLY));
                    }
                    break;
                case KIND_ACTION:
                    ((ActionRowView) holder.itemView).bind(row);
                    break;
                case KIND_SUBROW:
                    ((SubRowView) holder.itemView).bind(row);
                    break;
                case KIND_SEARCH_RESULT:
                    ((SearchResultView) holder.itemView).bind(row);
                    break;
                case KIND_FOOTER:
                    ((FooterView) holder.itemView).bind(row);
                    break;
                case KIND_PAGE_END:
                    ((PageEndView) holder.itemView).bind(row);
                    break;
                case KIND_NOTE:
                    ((NoteView) holder.itemView).setText(row.title);
                    break;
                default:
                    break;
            }
        }
    }
}
