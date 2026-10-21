package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.plugins.js.JsPlugin;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin catalog page (Platform Phase B, §3.1/§3.3): the
 * curated index hosted in the AminGram repo (catalog/index.json)
 * — beside the Built-in library, which stays fully offline.
 *
 * POWER RULE: the index is fetched ONLY when this page opens or
 * the user taps Refresh — never in the background, never on a
 * timer. The hub's "updates available" hint is computed only
 * from a catalog fetched this session (the static snapshot
 * below); nothing is fetched to compute it.
 *
 * Install flow: fetch the plugin file → verify its SHA-256
 * against the index (hard stop on mismatch — the file is never
 * installed) → permission review screen → validated install.
 * A missing @depends that the catalog itself carries is offered
 * as a one-tap chain after the install.
 */
public class AminCatalogActivity extends BaseFragment {

    public static final String CATALOG_URL =
            "https://raw.githubusercontent.com/TheLimoo/AminGram/main/catalog/index.json";

    /** Session snapshot of the last fetched catalog (id →
     *  version), the ONLY source of the hub's update hint. */
    private static final Map<String, String> sessionVersions = new LinkedHashMap<>();

    public static int sessionUpdateCount(Context context) {
        if (sessionVersions.isEmpty()) {
            return -1;
        }
        int count = 0;
        for (Map.Entry<String, String> entry : sessionVersions.entrySet()) {
            JsPlugin installed = JsPluginManager.get(context, entry.getKey());
            if (installed != null
                    && JsPluginManager.compareVersions(installed.version, entry.getValue()) < 0) {
                count++;
            }
        }
        return count;
    }

    static class Entry {
        String id = "";
        String name = "";
        String version = "";
        String author = "";
        String description = "";
        String category = "";
        String url = "";
        String sha256 = "";
        int minPlatform;
        List<String> capabilities = new ArrayList<>();
        List<String> depends = new ArrayList<>();
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_INFO = 1;
    private static final int TYPE_ENTRY = 2;
    private static final int TYPE_ACTION = 3;

    private final List<Entry> entries = new ArrayList<>();
    private final List<Integer> rowTypes = new ArrayList<>();
    private final List<Object> rowData = new ArrayList<>();
    private CatalogAdapter adapter;
    private String status = "Loading…";
    private boolean loading;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Plugin catalog");
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
        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(
                context, LinearLayoutManager.VERTICAL, false));
        adapter = new CatalogAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(context, position));
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fragmentView = layout;
        rebuildRows(context);
        fetchCatalog(context);
        return layout;
    }

    private void addRow(int type, Object data) {
        rowTypes.add(type);
        rowData.add(data);
    }

    private String stateText(Context context, Entry entry) {
        if (entry.minPlatform > JsPluginManager.PLATFORM_API_VERSION) {
            return "Needs platform v" + entry.minPlatform;
        }
        JsPlugin installed = JsPluginManager.get(context, entry.id);
        if (installed == null) {
            return "Install";
        }
        if (JsPluginManager.compareVersions(installed.version, entry.version) < 0) {
            return "Update → v" + entry.version;
        }
        return "Installed v" + installed.version;
    }

    private boolean installable(Context context, Entry entry) {
        if (entry.minPlatform > JsPluginManager.PLATFORM_API_VERSION) {
            return false;
        }
        JsPlugin installed = JsPluginManager.get(context, entry.id);
        return installed == null
                || JsPluginManager.compareVersions(installed.version, entry.version) < 0;
    }

    private void rebuildRows(Context context) {
        rowTypes.clear();
        rowData.clear();
        addRow(TYPE_HEADER, "Catalog");
        addRow(TYPE_INFO, status);
        String lastCategory = null;
        for (Entry entry : entries) {
            if (entry.category != null && !entry.category.isEmpty()
                    && !entry.category.equals(lastCategory)) {
                addRow(TYPE_HEADER, entry.category);
                lastCategory = entry.category;
            }
            addRow(TYPE_ENTRY, entry);
        }
        addRow(TYPE_ACTION, "Refresh");
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void onRowClick(Context context, int position) {
        if (position < 0 || position >= rowTypes.size()) {
            return;
        }
        int type = rowTypes.get(position);
        if (type == TYPE_ACTION) {
            fetchCatalog(context);
        } else if (type == TYPE_ENTRY) {
            Entry entry = (Entry) rowData.get(position);
            if (installable(context, entry)) {
                fetchAndReview(context, entry);
            }
        }
    }

    private void fetchCatalog(final Context context) {
        if (loading) {
            return;
        }
        loading = true;
        status = "Loading…";
        rebuildRows(context);
        new Thread(() -> {
            String error = null;
            List<Entry> fetched = new ArrayList<>();
            try {
                String json = httpGet(CATALOG_URL);
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Entry entry = new Entry();
                    entry.id = o.optString("id");
                    entry.name = o.optString("name");
                    entry.version = o.optString("version");
                    entry.author = o.optString("author");
                    entry.description = o.optString("description");
                    entry.category = o.optString("category");
                    entry.url = o.optString("url");
                    entry.sha256 = o.optString("sha256");
                    entry.minPlatform = o.optInt("minPlatform");
                    JSONArray caps = o.optJSONArray("capabilities");
                    if (caps != null) {
                        for (int j = 0; j < caps.length(); j++) {
                            entry.capabilities.add(caps.optString(j));
                        }
                    }
                    JSONArray deps = o.optJSONArray("depends");
                    if (deps != null) {
                        for (int j = 0; j < deps.length(); j++) {
                            entry.depends.add(deps.optString(j));
                        }
                    }
                    if (!entry.id.isEmpty() && !entry.url.isEmpty()) {
                        fetched.add(entry);
                    }
                }
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final String fetchError = error;
            final List<Entry> result = fetched;
            AndroidUtilities.runOnUIThread(() -> {
                loading = false;
                if (fetchError == null) {
                    entries.clear();
                    entries.addAll(result);
                    synchronized (sessionVersions) {
                        sessionVersions.clear();
                        for (Entry entry : result) {
                            sessionVersions.put(entry.id, entry.version);
                        }
                    }
                    status = result.size() + " plugins · fetched just now. "
                            + "Files are SHA-256-verified before the review screen.";
                } else {
                    status = "Could not load the catalog (" + fetchError + "). "
                            + "Check your connection and tap Refresh.";
                }
                if (getContext() != null) {
                    rebuildRows(getContext());
                }
            });
        }, "AminCatalogFetch").start();
    }

    /** Fetch one plugin file, verify its hash, then hand it to
     *  the permission review screen. A hash mismatch is a hard
     *  stop: nothing is installed and the user is told why. */
    private void fetchAndReview(final Context context, final Entry entry) {
        BulletinFactory.of(AminCatalogActivity.this)
                .createSimpleBulletin(R.raw.contact_check, "Downloading " + entry.name + "…")
                .show();
        new Thread(() -> {
            String code = null;
            String error = null;
            try {
                code = httpGet(entry.url);
                String actual = JsPluginManager.sha256Hex(code);
                if (entry.sha256 == null || entry.sha256.isEmpty()
                        || !entry.sha256.equalsIgnoreCase(actual)) {
                    error = "SHA-256 mismatch — the downloaded file does not match "
                            + "the catalog entry, so it was NOT installed.";
                    code = null;
                }
            } catch (Throwable t) {
                error = "Download failed: " + t.getMessage();
            }
            final String pluginCode = code;
            final String fetchError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (fetchError != null) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                    builder.setTitle(entry.name);
                    builder.setMessage(fetchError);
                    builder.setPositiveButton("OK", null);
                    showDialog(builder.create());
                    return;
                }
                presentFragment(PluginReviewActivity.forInstall(
                        pluginCode, "catalog", null,
                        (installed, pluginId, reviewError) -> {
                            if (getContext() != null) {
                                rebuildRows(getContext());
                            }
                            if (installed) {
                                offerDependsChain(getContext(), pluginId);
                            }
                        }));
            });
        }, "AminCatalogPluginFetch").start();
    }

    /** After a catalog install, offer any missing @depends the
     *  catalog itself carries — one tap per plugin (§3.3). */
    private void offerDependsChain(final Context context, String pluginId) {
        if (context == null || pluginId == null || getParentActivity() == null) {
            return;
        }
        JsPluginManager.InstallPreview preview = null;
        JsPlugin installed = JsPluginManager.get(context, pluginId);
        if (installed == null) {
            return;
        }
        String code = JsPluginManager.readCode(context, pluginId);
        if (code != null) {
            preview = JsPluginManager.previewInstall(context, code);
        }
        if (preview == null) {
            return;
        }
        for (String dep : preview.depends) {
            String depId = dep.contains("@") ? dep.substring(0, dep.indexOf('@')) : dep;
            if (JsPluginManager.get(context, depId) != null) {
                continue;
            }
            for (Entry candidate : entries) {
                if (candidate.id.equals(depId) && installable(context, candidate)) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                    builder.setTitle("Also install " + candidate.name + "?");
                    builder.setMessage(installed.name + " needs " + candidate.name
                            + ", which is in the catalog.");
                    builder.setPositiveButton("Install", (dialog, which) ->
                            fetchAndReview(context, candidate));
                    builder.setNegativeButton("Not now", null);
                    showDialog(builder.create());
                    return; // one offer at a time
                }
            }
        }
    }

    private static String httpGet(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "AminGram");
        try {
            int code = connection.getResponseCode();
            InputStream in = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (in == null) {
                throw new Exception("HTTP " + code);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > 2 * 1024 * 1024) {
                    throw new Exception("response too large");
                }
                out.write(buffer, 0, read);
            }
            in.close();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }

    private class CatalogAdapter extends RecyclerListView.SelectionAdapter {

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
            int type = rowTypes.get(holder.getAdapterPosition());
            return type == TYPE_ENTRY || type == TYPE_ACTION;
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
            Context context = holder.itemView.getContext();
            int type = rowTypes.get(position);
            boolean divider = position < rowTypes.size() - 1;
            if (type == TYPE_HEADER) {
                ((HeaderCell) holder.itemView).setText((String) rowData.get(position));
            } else if (type == TYPE_INFO) {
                ((TextInfoPrivacyCell) holder.itemView).setText((String) rowData.get(position));
            } else if (type == TYPE_ENTRY) {
                Entry entry = (Entry) rowData.get(position);
                ((TextCell) holder.itemView).setTextAndValue(
                        entry.name + "  v" + entry.version,
                        stateText(context, entry), divider);
            } else {
                ((TextCell) holder.itemView).setText((String) rowData.get(position), divider);
            }
        }
    }
}
