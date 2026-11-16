package org.telegram.messenger.amin.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.pyplugins.PyDevServer;
import org.telegram.messenger.amin.pyplugins.PyPlugin;
import org.telegram.messenger.amin.pyplugins.PyPluginAnalysis;
import org.telegram.messenger.amin.pyplugins.PyPluginRepo;
import org.telegram.messenger.amin.pyplugins.PyPluginsBootstrap;
import org.telegram.messenger.amin.pyplugins.PyPluginsController;
import org.telegram.messenger.amin.pyplugins.PyPluginsPrefs;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Python (.plugin) plugins list page — M0.
 *
 * Contents: the engine master switch (default OFF until M3),
 * one row per installed Python plugin (tap opens its page), and
 * "Add plugin from file…" (system document picker, sideload
 * only — no catalog until M4).
 *
 * M4 adds: catalogs (extera-plugins.json, SHA-256 verified
 * downloads, unknown-sources gate default OFF) and the DevServer
 * (localhost only, token auth, auto-stops).
 *
 * The install review (static-analysis report) is shown as a
 * confirm dialog before the file is installed: the user approves
 * that specific report. Analysis is advisory, not a sandbox —
 * the dialog says so.
 *
 * All flows are Throwable-isolated like the rest of the layer.
 */
public class AminPyPluginsActivity extends BaseFragment {

    private static final int KIND_HEADER = 0;
    private static final int KIND_INFO = 1;
    private static final int KIND_ACTION = 2;
    private static final int KIND_CHECK = 3;
    private static final int KIND_FOOTER = 4;

    private static final int SEG_NONE = 0;
    private static final int SEG_ONLY = 1;
    private static final int SEG_FIRST = 2;
    private static final int SEG_MID = 3;
    private static final int SEG_LAST = 4;

    private static final int REQUEST_PYPLUGIN_FILE = 3921;

    private static final class Row {
        int kind;
        int seg = SEG_NONE;
        String title;
        String value;
        String action;
        PyPlugin plugin;
        boolean checked;
    }

    private RecyclerListView listView;
    private PyAdapter adapter;
    private final List<Row> rows = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Python Plugins");
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
        adapter = new PyAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(position));
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rebuild();
        fragmentView = layout;
        return layout;
    }

    private void rebuild() {
        Context context = getContext();
        rows.clear();
        if (context == null) {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            return;
        }
        Row header = new Row();
        header.kind = KIND_HEADER;
        header.title = "Python engine";
        rows.add(header);

        Row engine = new Row();
        engine.kind = KIND_CHECK;
        engine.seg = SEG_ONLY;
        engine.title = "Enable Python plugins";
        engine.action = "engine";
        engine.checked = PyPluginsController.isEngineEnabled(context);
        rows.add(engine);

        Row engineInfo = new Row();
        engineInfo.kind = KIND_INFO;
        engineInfo.seg = SEG_ONLY;
        engineInfo.title = PyPluginsController.isEngineEnabled(context)
                ? "Engine on. Python starts lazily on first need; turning it off stops every Python plugin."
                : "Engine off — no Python code runs. Turn it on to load .plugin files.";
        rows.add(engineInfo);

        Row pluginsHeader = new Row();
        pluginsHeader.kind = KIND_HEADER;
        pluginsHeader.title = "Installed plugins";
        rows.add(pluginsHeader);

        List<PyPlugin> plugins = PyPluginsController.plugins(context);
        if (plugins.isEmpty()) {
            Row empty = new Row();
            empty.kind = KIND_INFO;
            empty.seg = SEG_ONLY;
            empty.title = "No Python plugins installed yet. Use Add plugin below.";
            rows.add(empty);
        } else {
            for (int i = 0; i < plugins.size(); i++) {
                PyPlugin plugin = plugins.get(i);
                Row row = new Row();
                row.kind = KIND_ACTION;
                row.seg = plugins.size() == 1 ? SEG_ONLY
                        : i == 0 ? SEG_FIRST
                        : i == plugins.size() - 1 ? SEG_LAST : SEG_MID;
                row.title = plugin.name;
                if (plugin.errored) {
                    row.value = "Error — tap for details";
                } else if (!plugin.enabled) {
                    row.value = "Disabled";
                } else {
                    row.value = "v" + plugin.version
                            + (plugin.author.isEmpty() ? "" : " · " + plugin.author);
                }
                row.action = "open_plugin";
                row.plugin = plugin;
                rows.add(row);
            }
        }

        Row add = new Row();
        add.kind = KIND_ACTION;
        add.seg = SEG_ONLY;
        add.title = "Add plugin from file…";
        add.value = ".plugin · .elyx";
        add.action = "add_plugin_file";
        rows.add(add);

        // M4: catalogs.
        Row catalogHeader = new Row();
        catalogHeader.kind = KIND_HEADER;
        catalogHeader.title = "Catalogs";
        rows.add(catalogHeader);

        Row unknown = new Row();
        unknown.kind = KIND_CHECK;
        unknown.seg = SEG_FIRST;
        unknown.title = "Allow unknown sources";
        unknown.action = "unknown_sources";
        unknown.checked = isUnknownSources(context);
        rows.add(unknown);

        Row unknownInfo = new Row();
        unknownInfo.kind = KIND_INFO;
        unknownInfo.seg = SEG_MID;
        unknownInfo.title = isUnknownSources(context)
                ? "On — any https catalog URL can be added. Downloads are still SHA-256 checked against the catalog."
                : "Off — catalogs are refused unless you turn this on. There is no bundled catalog.";
        rows.add(unknownInfo);

        List<String> repoUrls = repoUrls(context);
        for (int i = 0; i < repoUrls.size(); i++) {
            Row repo = new Row();
            repo.kind = KIND_ACTION;
            repo.seg = i == repoUrls.size() - 1 ? SEG_LAST : SEG_MID;
            repo.title = PyPluginRepo.hostOf(repoUrls.get(i));
            repo.value = repoUrls.get(i);
            repo.action = "refresh_catalog";
            rows.add(repo);
        }

        Row addCatalog = new Row();
        addCatalog.kind = KIND_ACTION;
        addCatalog.seg = SEG_LAST;
        addCatalog.title = "Add catalog…";
        addCatalog.value = "extera-plugins.json";
        addCatalog.action = "add_catalog";
        rows.add(addCatalog);

        // M4: DevServer (localhost only, token auth, auto-stops).
        Row devHeader = new Row();
        devHeader.kind = KIND_HEADER;
        devHeader.title = "Development";
        rows.add(devHeader);

        Row dev = new Row();
        dev.kind = KIND_CHECK;
        dev.seg = SEG_FIRST;
        dev.title = "Dev server (localhost only)";
        dev.action = "devserver";
        dev.checked = PyDevServer.getInstance().isRunning();
        rows.add(dev);

        Row devInfo = new Row();
        devInfo.kind = KIND_INFO;
        devInfo.seg = SEG_LAST;
        if (PyDevServer.getInstance().isRunning()) {
            String token = PyDevServer.getInstance().getToken();
            devInfo.title = "Listening on 127.0.0.1:" + PyDevServer.getInstance().getPort()
                    + " — reachable only from this phone. Token (keep it "
                    + "private, it changes on every start): "
                    + (token == null ? "…" : token)
                    + ". POST a .plugin / .elyx file to /plugin with "
                    + "header X-Dev-Token. Stops 60 s after the app goes "
                    + "to background, or when you leave this page.";
        } else {
            devInfo.title = "Off. When on, your computer can push a plugin "
                    + "file to this phone over 127.0.0.1 only — nothing on "
                    + "the network can reach it. Uploads go through the "
                    + "same install review as a file.";
        }
        rows.add(devInfo);

        Row footer = new Row();
        footer.kind = KIND_FOOTER;
        footer.title = "Python plugins run with full app access — install only "
                + "files from sources you trust, like you trust the app itself. "
                + "There is no sandbox: a plugin can read chats, send messages "
                + "and load native code. The install review shows what the "
                + "source text contains; hidden or downloaded code would not "
                + "show up there. M0: single-file .plugin only, sideload only, "
                + "no PyPI packages. M3: DEX loads only with per-plugin consent. "
                + "M4: .elyx archives and catalogs (SHA-256 checked); PyPI "
                + "packages are still not installed.";
        rows.add(footer);

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void onRowClick(int position) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        Context context = getContext();
        if (context == null || row.action == null) {
            return;
        }
        try {
            switch (row.action) {
                case "engine": {
                    boolean now = !PyPluginsController.isEngineEnabled(context);
                    PyPluginsController.setEngineEnabled(context, now);
                    if (now) {
                        PyPluginsBootstrap.post(new Runnable() {
                            @Override
                            public void run() {
                                PyPluginsBootstrap.ensureStarted(context, true);
                            }
                        });
                    }
                    bulletin(now ? R.raw.contact_check : R.raw.error,
                            now ? "Python engine on" : "Python engine off");
                    rebuild();
                    break;
                }
                case "open_plugin":
                    if (row.plugin != null) {
                        presentFragment(new AminPyPluginActivity(row.plugin.id));
                    }
                    break;
                case "add_plugin_file":
                    pickPluginFile();
                    break;
                case "unknown_sources": {
                    boolean now = !isUnknownSources(context);
                    context.getSharedPreferences(PyPluginsPrefs.PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean(PyPluginsPrefs.KEY_UNKNOWN_SOURCES, now).apply();
                    bulletin(now ? R.raw.contact_check : R.raw.error,
                            now ? "Unknown sources on" : "Unknown sources off");
                    rebuild();
                    break;
                }
                case "add_catalog":
                    addCatalogDialog();
                    break;
                case "refresh_catalog":
                    refreshCatalog(row.value);
                    break;
                case "devserver": {
                    if (PyDevServer.getInstance().isRunning()) {
                        PyDevServer.getInstance().stop();
                        bulletin(R.raw.error, "Dev server stopped");
                    } else {
                        String error = PyDevServer.getInstance().start();
                        if (error != null) {
                            bulletin(R.raw.error, error);
                        } else {
                            bulletin(R.raw.contact_check,
                                    "Dev server on 127.0.0.1:"
                                            + PyDevServer.getInstance().getPort());
                        }
                    }
                    rebuild();
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // M4: a dev server left running dies 60 s after background.
        if (PyDevServer.getInstance().isRunning()) {
            PyDevServer.getInstance().scheduleAutoStop();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        PyDevServer.getInstance().cancelAutoStop();
        rebuild();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        // M4: leaving the hub stops the dev server outright.
        PyDevServer.getInstance().cancelAutoStop();
        PyDevServer.getInstance().stop();
    }

    // --------------------------------------------------------------
    // M4 — catalogs
    // --------------------------------------------------------------

    private static boolean isUnknownSources(Context context) {
        return context.getSharedPreferences(PyPluginsPrefs.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PyPluginsPrefs.KEY_UNKNOWN_SOURCES, false);
    }

    private static List<String> repoUrls(Context context) {
        List<String> out = new ArrayList<>();
        String raw = context.getSharedPreferences(PyPluginsPrefs.PREFS, Context.MODE_PRIVATE)
                .getString(PyPluginsPrefs.KEY_REPO_URLS, "");
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String url = line.trim();
            if (!url.isEmpty() && !out.contains(url)) {
                out.add(url);
            }
        }
        return out;
    }

    private static void saveRepoUrls(Context context, List<String> urls) {
        StringBuilder sb = new StringBuilder();
        for (String url : urls) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(url);
        }
        context.getSharedPreferences(PyPluginsPrefs.PREFS, Context.MODE_PRIVATE)
                .edit().putString(PyPluginsPrefs.KEY_REPO_URLS, sb.toString()).apply();
    }

    /** Asks for a catalog URL, checks the unknown-sources gate,
     *  and stores it. The index is fetched only when the row is
     *  tapped (refreshCatalog). */
    private void addCatalogDialog() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        if (!isUnknownSources(context)) {
            bulletin(R.raw.error,
                    "Unknown sources are off — turn them on first.");
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Add catalog");
        builder.setMessage("URL of an extera-plugins.json index "
                + "(https). Downloads from it are SHA-256 checked "
                + "against the catalog before install.");
        final EditText input = new EditText(context);
        input.setHint("https://example.com/extera-plugins.json");
        builder.setView(input);
        builder.setPositiveButton("Add", (dialog, which) -> {
            String url = input.getText() == null ? "" : input.getText().toString().trim();
            if (!(url.startsWith("https://") || url.startsWith("http://"))
                    || PyPluginRepo.hostOf(url).isEmpty()) {
                bulletin(R.raw.error, "That is not an http(s) URL.");
                return;
            }
            List<String> urls = repoUrls(context);
            if (!urls.contains(url)) {
                urls.add(url);
                saveRepoUrls(context, urls);
            }
            bulletin(R.raw.contact_check, "Catalog added — tap it to refresh.");
            rebuild();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /** Fetches a catalog index on a background thread, then shows
     *  its entries. The fetch is plain HTTPS (a website JSON, not
     *  a Telegram file); parsing + verification are in
     *  PyPluginRepo and run before anything is shown. */
    private void refreshCatalog(final String url) {
        final Context context = getContext();
        if (context == null || url == null) {
            return;
        }
        new Thread(() -> {
            String error = null;
            String json = null;
            try {
                java.net.URL u = new java.net.URL(url);
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "AminGram-pyplugins");
                if (conn.getResponseCode() != 200) {
                    error = "Catalog returned HTTP " + conn.getResponseCode() + ".";
                } else {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    try (InputStream in = conn.getInputStream()) {
                        byte[] chunk = new byte[8192];
                        int n;
                        while ((n = in.read(chunk)) != -1) {
                            buf.write(chunk, 0, n);
                            if (buf.size() > PyPluginRepo.MAX_INDEX_BYTES) {
                                break;
                            }
                        }
                    }
                    json = new String(buf.toByteArray(), "UTF-8");
                }
            } catch (Throwable t) {
                error = "Could not fetch the catalog: " + t.getMessage();
            }
            final String fetchError = error;
            final String fetchedJson = json;
            AndroidUtilities.runOnUIThread(() -> {
                if (fetchError != null) {
                    bulletin(R.raw.error, fetchError);
                    return;
                }
                PyPluginRepo.IndexResult result = PyPluginRepo.parseIndex(fetchedJson);
                if (result.error != null) {
                    bulletin(R.raw.error, result.error);
                    return;
                }
                showCatalogEntries(result, url);
            });
        }).start();
    }

    /** Lists a catalog's entries; tapping one downloads it,
     *  verifies size + SHA-256, and opens the install review. */
    private void showCatalogEntries(final PyPluginRepo.IndexResult result, String url) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        if (result.entries.isEmpty()) {
            bulletin(R.raw.error, "Catalog is empty"
                    + (result.dropped > 0 ? " (" + result.dropped + " bad entries dropped)." : "."));
            return;
        }
        String[] names = new String[result.entries.size()];
        for (int i = 0; i < result.entries.size(); i++) {
            PyPluginRepo.Entry e = result.entries.get(i);
            names[i] = e.name + " v" + e.version
                    + (e.author.isEmpty() ? "" : " · " + e.author);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(result.name.isEmpty() ? "Catalog" : result.name);
        builder.setItems(names, (dialog, which) -> {
            downloadAndInstallEntry(result.entries.get(which));
        });
        builder.setNegativeButton("Close", null);
        builder.show();
    }

    private void downloadAndInstallEntry(final PyPluginRepo.Entry entry) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        new Thread(() -> {
            String error = null;
            byte[] bytes = null;
            try {
                java.net.URL u = new java.net.URL(entry.url);
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "AminGram-pyplugins");
                if (conn.getResponseCode() != 200) {
                    error = "Download returned HTTP " + conn.getResponseCode() + ".";
                } else {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    try (InputStream in = conn.getInputStream()) {
                        byte[] chunk = new byte[8192];
                        int n;
                        while ((n = in.read(chunk)) != -1) {
                            buf.write(chunk, 0, n);
                            if (buf.size() > PyPluginRepo.MAX_PLUGIN_BYTES) {
                                break;
                            }
                        }
                    }
                    bytes = buf.toByteArray();
                }
            } catch (Throwable t) {
                error = "Download failed: " + t.getMessage();
            }
            final String dlError = error;
            final byte[] dlBytes = bytes;
            AndroidUtilities.runOnUIThread(() -> {
                if (dlError != null) {
                    bulletin(R.raw.error, dlError);
                    return;
                }
                // SHA-256 + size check against the catalog entry.
                // A mismatch deletes the bytes — nothing is installed.
                java.io.File tmp = null;
                try {
                    tmp = java.io.File.createTempFile("catalog-", ".plugin");
                    try (java.io.FileOutputStream out =
                                 new java.io.FileOutputStream(tmp)) {
                        out.write(dlBytes);
                    }
                    String mismatch = PyPluginRepo.verifyFile(entry, tmp);
                    if (mismatch != null) {
                        bulletin(R.raw.error, mismatch);
                        return;
                    }
                } catch (Throwable t) {
                    bulletin(R.raw.error, "Could not verify the download.");
                    return;
                } finally {
                    if (tmp != null) {
                        tmp.delete();
                    }
                }
                // A catalog entry's name doubles as the file name
                // for the library fallback (Fix 3 / v2.5.5).
                confirmInstall(dlBytes, entry.name);
            });
        }).start();
    }

    private void pickPluginFile() {
        if (getParentActivity() == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PYPLUGIN_FILE);
        } catch (Throwable t) {
            bulletin(R.raw.error, "No file picker available");
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQUEST_PYPLUGIN_FILE) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        final Context context = getContext();
        if (context == null) {
            return;
        }
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        new Thread(() -> {
            // Fix 3 (v2.5.5): the picked file's display name is
            // the library id when the file has no metadata
            // (__id__/__name__) — e.g. the real ZwyLib.
            String displayName = null;
            try (Cursor c = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    displayName = c.getString(0);
                }
            } catch (Throwable ignored) {
            }
            byte[] bytes = null;
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) {
                    throw new java.io.IOException("cannot open picked file");
                }
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    if (out.size() > PyPluginsPrefs.MAX_PLUGIN_BYTES) {
                        break;
                    }
                }
                bytes = out.toByteArray();
            } catch (Throwable t) {
                final String msg = "Could not read that file.";
                AndroidUtilities.runOnUIThread(() -> bulletin(R.raw.error, msg));
                return;
            }
            final byte[] fileBytes = bytes;
            final String fileName = displayName;
            AndroidUtilities.runOnUIThread(() -> confirmInstall(fileBytes, fileName));
        }).start();
    }

    /** Shows the install-time analysis report and installs on approval. */
    private void confirmInstall(final byte[] bytes) {
        confirmInstall(bytes, null);
    }

    /** As {@link #confirmInstall(byte[])}, plus the picked file's
     *  display name (Fix 3 / v2.5.5): a metadata-less file is
     *  offered as a LIBRARY under an id derived from that name —
     *  the dialog says so, and the Install path uses the same
     *  fallback as the controller, so it cannot refuse again. */
    private void confirmInstall(final byte[] bytes, final String fileName) {
        final Context context = getContext();
        if (context == null || getParentActivity() == null) {
            return;
        }
        // M4: ZIP magic — a .elyx / .eaf archive gets its own review.
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
            confirmInstallElyx(bytes);
            return;
        }
        String source;
        try {
            source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            bulletin(R.raw.error, "Plugin file is not valid UTF-8 text.");
            return;
        }
        org.telegram.messenger.amin.pyplugins.PyPluginMetadata.Parsed parsed =
                org.telegram.messenger.amin.pyplugins.PyPluginMetadata.parse(source);
        final PyPlugin plugin;
        final boolean isLibrary;
        if (parsed.error != null) {
            // Fix 3 (v2.5.5): same fallback as the controller —
            // a metadata-less file with a known name is a library.
            // Without a name there is nothing to derive an id
            // from, so the refusal stands.
            if (!org.telegram.messenger.amin.pyplugins.PyPluginMetadata
                    .isMissingMetadataError(parsed.error)
                    || fileName == null) {
                bulletin(R.raw.error, parsed.error);
                return;
            }
            PyPlugin lib = org.telegram.messenger.amin.pyplugins.PyPluginMetadata
                    .libraryFromFileName(fileName);
            if (lib == null) {
                bulletin(R.raw.error, org.telegram.messenger.amin.pyplugins.PyPluginMetadata
                        .libraryIdRefusal(fileName));
                return;
            }
            plugin = lib;
            isLibrary = true;
        } else {
            plugin = parsed.plugin;
            isLibrary = false;
        }
        List<PyPluginAnalysis.Finding> findings =
                org.telegram.messenger.amin.pyplugins.PyPluginAnalysis.analyze(source, plugin);
        StringBuilder report = new StringBuilder();
        report.append(plugin.name).append(" v").append(plugin.version);
        if (isLibrary) {
            report.append(" (library — no settings UI, no lifecycle; "
                    + "other plugins can import it)");
        } else if (!plugin.author.isEmpty()) {
            report.append(" by ").append(plugin.author);
        }
        report.append("\n\nThis plugin runs with full app access. The source text shows:\n");
        for (PyPluginAnalysis.Finding f : findings) {
            report.append("• ").append(f.text).append("\n");
        }
        report.append("\nHidden or downloaded code would not show up here. Install only files from sources you trust.");
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(isLibrary ? "Install Python library?" : "Install Python plugin?");
        builder.setMessage(report.toString());
        builder.setPositiveButton("Install", (dialog, which) -> {
            String error = PyPluginsController.installFromBytes(context, bytes, "file", fileName);
            if (error == null) {
                bulletin(R.raw.contact_check, isLibrary ? "Library installed" : "Plugin installed");
                rebuild();
            } else {
                bulletin(R.raw.error, error);
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    /** Install review for a .elyx / .eaf archive (M4): the entry
     *  source and every extra module are analysed; the report
     *  names the archive's entry file. */
    private void confirmInstallElyx(final byte[] bytes) {
        final Context context = getContext();
        if (context == null || getParentActivity() == null) {
            return;
        }
        org.telegram.messenger.amin.pyplugins.PyElyxLoader.Loaded loaded;
        try {
            loaded = org.telegram.messenger.amin.pyplugins.PyElyxLoader.load(
                    new java.io.ByteArrayInputStream(bytes));
        } catch (Throwable t) {
            bulletin(R.raw.error, "Could not read the archive.");
            return;
        }
        if (loaded.error != null) {
            bulletin(R.raw.error, loaded.error);
            return;
        }
        org.telegram.messenger.amin.pyplugins.PyPluginMetadata.Parsed parsed =
                org.telegram.messenger.amin.pyplugins.PyPluginMetadata.parse(loaded.entrySource);
        if (parsed.error != null) {
            bulletin(R.raw.error, parsed.error);
            return;
        }
        final PyPlugin plugin = parsed.plugin;
        List<org.telegram.messenger.amin.pyplugins.PyPluginAnalysis.Finding> findings =
                org.telegram.messenger.amin.pyplugins.PyPluginAnalysis.analyze(
                        loaded.entrySource, plugin);
        for (java.util.Map.Entry<String, String> module : loaded.modules.entrySet()) {
            findings.addAll(
                    org.telegram.messenger.amin.pyplugins.PyPluginAnalysis.analyze(
                            module.getValue(), plugin));
        }
        StringBuilder report = new StringBuilder();
        report.append(plugin.name).append(" v").append(plugin.version);
        if (!plugin.author.isEmpty()) {
            report.append(" by ").append(plugin.author);
        }
        report.append(" (.elyx archive, entry ").append(loaded.entryName);
        if (!loaded.modules.isEmpty()) {
            report.append(", +").append(loaded.modules.size()).append(" module(s)");
        }
        report.append(")\n\nThis plugin runs with full app access. The source text shows:\n");
        for (org.telegram.messenger.amin.pyplugins.PyPluginAnalysis.Finding f : findings) {
            report.append("• ").append(f.text).append("\n");
        }
        report.append("\nHidden or downloaded code would not show up here. Install only files from sources you trust.");
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Install Python plugin?");
        builder.setMessage(report.toString());
        builder.setPositiveButton("Install", (dialog, which) -> {
            String error = PyPluginsController.installFromBytes(context, bytes, "file");
            if (error == null) {
                bulletin(R.raw.contact_check, "Plugin installed");
                rebuild();
            } else {
                bulletin(R.raw.error, error);
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void bulletin(int icon, String text) {
        try {
            BulletinFactory.of(this).createSimpleBulletin(icon, text).show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Presentation helpers (same pattern as AminGoogleActivity)
    // ------------------------------------------------------------------

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

    private static class SectionLabelView extends TextView {
        SectionLabelView(Context context) {
            super(context);
            setTextSize(14);
            setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            setTextColor(accentColor());
            setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(22),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(2));
        }
    }

    private static class InfoView extends TextView {
        InfoView(Context context) {
            super(context);
            setTextSize(13.5f);
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            setLineSpacing(AndroidUtilities.dp(2), 1.0f);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        }

        void bind(String text, int seg) {
            setText(text == null ? "" : text);
            setBackground(segmentDrawable(seg, cardFill()));
        }
    }

    private class PyAdapter extends RecyclerListView.SelectionAdapter {

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
            return kind == KIND_ACTION || kind == KIND_CHECK;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            switch (viewType) {
                case KIND_HEADER:
                    view = new SectionLabelView(context);
                    break;
                case KIND_INFO:
                    view = new InfoView(context);
                    break;
                case KIND_FOOTER:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case KIND_CHECK:
                    view = new TextCheckCell(context);
                    break;
                default:
                    view = new TextCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
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
                    ((SectionLabelView) holder.itemView).setText(row.title);
                    break;
                case KIND_INFO:
                    ((InfoView) holder.itemView).bind(row.title, row.seg);
                    break;
                case KIND_FOOTER:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                    break;
                case KIND_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(row.title, row.checked, false);
                    cell.setBackground(segmentDrawable(row.seg, cardFill()));
                    break;
                }
                default:
                    TextCell cell = (TextCell) holder.itemView;
                    boolean divider = row.seg == SEG_MID;
                    if (row.value != null) {
                        cell.setTextAndValue(row.title, row.value, divider);
                    } else {
                        cell.setText(row.title, divider);
                    }
                    cell.setBackground(segmentDrawable(row.seg, cardFill()));
                    break;
            }
        }
    }
}
