package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.amin.dns.AminDnsController;
import org.telegram.messenger.amin.dns.DnsProfile;
import org.telegram.messenger.amin.dns.DnsProfiles;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DNS Tunnel settings page (opened from the Proxy settings section
 * added by patch 0021, and from the AminGram hub).
 *
 * Everything server-specific is a PROFILE (see DnsProfiles):
 * multiple named profiles per engine, one-tap switching, full
 * editing — zone/domain, server key, certificate, resolver list —
 * plus soar:// link import. The staged Limoo profiles are just
 * the seeded defaults. The hub quick-toggle and this page's master
 * switch both drive AminDnsController against the ACTIVE profile
 * of the selected engine.
 */
public class AminDnsTunnelActivity extends BaseFragment implements AminDnsController.Listener {

    private static final int KIND_SHADOW = 0;
    private static final int KIND_HEADER = 1;
    private static final int KIND_CHECK = 2;
    private static final int KIND_SETTING = 3;
    private static final int KIND_INFO = 4;

    private static final class Row {
        int kind;
        String title;
        String value;
        String action;
        boolean checked;
        boolean red;
        DnsProfile profile;
    }

    private RecyclerListView listView;
    private ListAdapter adapter;
    private final List<Row> rows = new ArrayList<>();
    private AminDnsController controller;

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        controller = AminDnsController.get(getContext());
        controller.addListener(this);
        rebuild();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (controller != null) {
            controller.removeListener(this);
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("DNS Tunnel");
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
        fragmentView = layout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(position));
        listView.setOnItemLongClickListener((view, position) -> onRowLongClick(position));
        layout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return layout;
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
    }

    @Override
    public void onDnsStateChanged() {
        rebuild();
    }

    // ---------------------------------------------------------------
    // Row model
    // ---------------------------------------------------------------

    private void rebuild() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        rows.clear();
        String engine = DnsProfiles.engine(context);
        DnsProfile active = DnsProfiles.activeProfile(context);
        AminDnsController.State state = controller.state();
        boolean on = state == AminDnsController.State.CONNECTED || state == AminDnsController.State.STARTING;

        header("Status");
        info(statusText(context, state, active));
        check("Use DNS Tunnel", on, "toggle");
        setting("Engine", engineLabel(engine), "engine");
        if (DnsProfile.ENGINE_SLIPSTREAM.equals(engine) && !AminDnsController.isSlipstreamAvailable(context)) {
            info("The Slipstream engine is not installed in this build yet — "
                    + "its binaries are added by a separate prebuild step. Soar works.");
        }
        shadow();

        header("Profiles");
        addProfileRows(context, DnsProfile.ENGINE_SOAR, "Soar");
        addProfileRows(context, DnsProfile.ENGINE_SLIPSTREAM, "Slipstream");
        setting("Add Soar profile", "", "add_soar");
        setting("Add Slipstream profile", "", "add_slipstream");
        setting("Import soar:// link", "", "import_soar");
        shadow();

        if (active != null) {
            header("Active profile — " + active.name);
            if (active.isSoar()) {
                setting("Zone", active.zone, "edit_zone");
                setting("Server key", active.serverKey.isEmpty() ? "Not set" : "Set", "edit_key");
                setting("Discovery (IR resolvers)", active.discovery ? "On" : "Off", "edit_discovery");
                setting("Discovery country", active.discoveryCountry, "edit_country");
            } else {
                setting("Domain", active.domain, "edit_domain");
                setting("Certificate (pinning)", active.cert == null || active.cert.isEmpty()
                        ? "Not set — no verification" : "Set — pinned", "edit_cert");
                setting("Congestion control", active.congestion, "edit_congestion");
                setting("SOCKS username", active.socksUser == null || active.socksUser.isEmpty()
                        ? "Not set" : active.socksUser, "edit_socks_user");
                setting("SOCKS password", active.socksPass == null || active.socksPass.isEmpty()
                        ? "Not set" : "Set", "edit_socks_pass");
                info("Kit tunnels end at a password-protected SOCKS server — enter the tunnel "
                        + "username/password from the operator's client pack, or the tunnel "
                        + "will be rejected.");
            }
            setting("Resolver mode", resolverModeLabel(active.resolverMode), "edit_resolver_mode");
            setting("Custom resolvers", active.resolvers.isEmpty()
                    ? "None" : active.resolvers.size() + " set", "edit_resolvers");
            setting("Rename profile", "", "rename_profile");
            Row del = setting("Delete profile", "", "delete_profile");
            del.red = true;
            shadow();
        }

        header("About");
        info("Routes Telegram through a DNS tunnel when normal connections are blocked. "
                + "No VPN is used, so other apps and VPN apps are unaffected. Speeds are "
                + "lifeline-grade — text and light media, not bulk downloads. Voice and "
                + "video calls may not work over the tunnel.\n\n"
                + "AminGram does not hold a wakelock: with the screen off for long stretches "
                + "Android may batch DNS traffic and the tunnel can feel slow until you open "
                + "the app again. Telegram reconnects by itself when you return.");
        shadow();

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private String statusText(Context context, AminDnsController.State state, DnsProfile active) {
        StringBuilder sb = new StringBuilder();
        switch (state) {
            case CONNECTED:
                sb.append("Connected");
                break;
            case STARTING:
                sb.append("Connecting…");
                break;
            case ERROR:
                sb.append("Error: ").append(controller.error());
                break;
            case OFF:
            default:
                sb.append("Off");
                break;
        }
        if (active != null) {
            sb.append("\nEngine: ").append(engineLabel(active.engine));
            sb.append("\nProfile: ").append(active.name).append(" (").append(active.host()).append(")");
        }
        if (state == AminDnsController.State.CONNECTED) {
            sb.append("\nLocal SOCKS: 127.0.0.1:").append(controller.localPort());
            if (!controller.activeResolver().isEmpty()) {
                sb.append("\nResolver: ").append(controller.activeResolver());
            }
            long up = controller.uptimeSeconds();
            if (up > 0) {
                sb.append("\nUptime: ").append(up / 60).append("m ").append(up % 60).append("s");
            }
        }
        return sb.toString();
    }

    private static String engineLabel(String engine) {
        return DnsProfile.ENGINE_SLIPSTREAM.equals(engine) ? "Slipstream (fast)" : "Soar (recommended)";
    }

    private static String resolverModeLabel(String mode) {
        if (DnsProfile.RESOLVERS_SYSTEM.equals(mode)) {
            return "System only";
        }
        if (DnsProfile.RESOLVERS_PUBLIC.equals(mode)) {
            return "Public only";
        }
        if (DnsProfile.RESOLVERS_CUSTOM.equals(mode)) {
            return "Custom list";
        }
        return "Auto (system + public)";
    }

    private void addProfileRows(Context context, String engine, String label) {
        List<DnsProfile> list = DnsProfiles.profiles(context, engine);
        DnsProfile active = DnsProfiles.activeProfile(context, engine);
        header(label);
        for (DnsProfile p : list) {
            Row row = new Row();
            row.kind = KIND_SETTING;
            row.title = p.name;
            boolean isActive = active != null && active.id.equals(p.id);
            boolean isSelectedEngine = engine.equals(DnsProfiles.engine(context));
            row.value = (isActive ? "Active · " : "") + p.host();
            row.action = "profile";
            row.profile = p;
            row.checked = isActive && isSelectedEngine;
            rows.add(row);
        }
    }

    private Row header(String title) {
        Row row = new Row();
        row.kind = KIND_HEADER;
        row.title = title;
        rows.add(row);
        return row;
    }

    private Row info(String text) {
        Row row = new Row();
        row.kind = KIND_INFO;
        row.title = text;
        rows.add(row);
        return row;
    }

    private Row check(String title, boolean checked, String action) {
        Row row = new Row();
        row.kind = KIND_CHECK;
        row.title = title;
        row.checked = checked;
        row.action = action;
        rows.add(row);
        return row;
    }

    private Row setting(String title, String value, String action) {
        Row row = new Row();
        row.kind = KIND_SETTING;
        row.title = title;
        row.value = value;
        row.action = action;
        rows.add(row);
        return row;
    }

    private void shadow() {
        Row row = new Row();
        row.kind = KIND_SHADOW;
        rows.add(row);
    }

    // ---------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------

    private void onRowClick(int position) {
        Context context = getContext();
        if (context == null || position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        if (row.action == null) {
            return;
        }
        DnsProfile active = DnsProfiles.activeProfile(context);
        switch (row.action) {
            case "toggle":
                controller.toggle(context);
                break;
            case "engine":
                chooseEngine(context);
                break;
            case "profile":
                activateProfile(context, row.profile);
                break;
            case "add_soar":
                createProfile(context, DnsProfile.ENGINE_SOAR);
                break;
            case "add_slipstream":
                createProfile(context, DnsProfile.ENGINE_SLIPSTREAM);
                break;
            case "import_soar":
                importSoarLink(context);
                break;
            case "edit_zone":
                if (active != null) {
                    promptText("Zone", "t.example.com", active.zone, false, text -> {
                        active.zone = text.trim();
                        saveAndMaybeRestart(context, active);
                    });
                }
                break;
            case "edit_domain":
                if (active != null) {
                    promptText("Domain", "t.example.com", active.domain, false, text -> {
                        active.domain = text.trim();
                        saveAndMaybeRestart(context, active);
                    });
                }
                break;
            case "edit_key":
                if (active != null) {
                    promptText("Server public key", "base64 Ed25519 public key", active.serverKey, true, text -> {
                        active.serverKey = text.trim();
                        saveAndMaybeRestart(context, active);
                    });
                }
                break;
            case "edit_country":
                if (active != null) {
                    promptText("Discovery country", "IR", active.discoveryCountry, false, text -> {
                        active.discoveryCountry = text.trim().isEmpty() ? "IR" : text.trim();
                        saveAndMaybeRestart(context, active);
                    });
                }
                break;
            case "edit_discovery":
                if (active != null) {
                    active.discovery = !active.discovery;
                    saveAndMaybeRestart(context, active);
                }
                break;
            case "edit_cert":
                if (active != null) {
                    certDialog(context, active);
                }
                break;
            case "edit_congestion": {
                if (active == null) {
                    break;
                }
                String[] options = {DnsProfile.CONGESTION_DCUBIC, DnsProfile.CONGESTION_BBR};
                String[] labels = {"dcubic (recommended for recursive paths)", "bbr (fast on clean networks)"};
                showChooser("Congestion control", labels,
                        DnsProfile.CONGESTION_BBR.equals(active.congestion) ? 1 : 0, which -> {
                            active.congestion = options[which];
                            saveAndMaybeRestart(context, active);
                        });
                break;
            }
            case "edit_socks_user":
                if (active != null) {
                    promptText("SOCKS username", "Tunnel username", active.socksUser, false, text -> {
                        active.socksUser = text.trim();
                        saveAndMaybeRestart(context, active);
                    });
                }
                break;
            case "edit_socks_pass":
                if (active != null) {
                    promptSecret("SOCKS password", "Tunnel password", active.socksPass, text -> {
                        active.socksPass = text;
                        saveAndMaybeRestart(context, active);
                    });
                }
                break;
            case "edit_resolver_mode": {
                if (active == null) {
                    break;
                }
                String[] modes = {DnsProfile.RESOLVERS_AUTO, DnsProfile.RESOLVERS_SYSTEM,
                        DnsProfile.RESOLVERS_PUBLIC, DnsProfile.RESOLVERS_CUSTOM};
                String[] labels = {"Auto (system + public)", "System only", "Public only", "Custom list"};
                int current = 0;
                for (int i = 0; i < modes.length; i++) {
                    if (modes[i].equals(active.resolverMode)) {
                        current = i;
                    }
                }
                showChooser("Resolver mode", labels, current, which -> {
                    active.resolverMode = modes[which];
                    saveAndMaybeRestart(context, active);
                });
                break;
            }
            case "edit_resolvers":
                if (active != null) {
                    StringBuilder initial = new StringBuilder();
                    for (String r : active.resolvers) {
                        if (initial.length() > 0) {
                            initial.append('\n');
                        }
                        initial.append(r);
                    }
                    promptText("Custom resolvers", "One per line — host or host:port",
                            initial.toString(), true, text -> {
                                active.resolvers.clear();
                                for (String line : text.split("\n")) {
                                    String v = line.trim();
                                    if (!v.isEmpty()) {
                                        active.resolvers.add(v);
                                    }
                                }
                                saveAndMaybeRestart(context, active);
                            });
                }
                break;
            case "rename_profile":
                if (active != null) {
                    promptText("Profile name", "Name", active.name, false, text -> {
                        if (!text.trim().isEmpty()) {
                            active.name = text.trim();
                        }
                        saveAndMaybeRestart(context, active);
                    });
                }
                break;
            case "delete_profile":
                if (active != null) {
                    confirmDeleteProfile(context, active);
                }
                break;
            default:
                break;
        }
    }

    private boolean onRowLongClick(int position) {
        Context context = getContext();
        if (context == null || position < 0 || position >= rows.size()) {
            return false;
        }
        Row row = rows.get(position);
        if (row.profile == null || getParentActivity() == null) {
            return false;
        }
        DnsProfile profile = row.profile;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(profile.name);
        builder.setItems(new CharSequence[]{"Edit", "Delete"}, (dialog, which) -> {
            if (which == 0) {
                editProfileDialog(context, profile);
            } else if (which == 1) {
                confirmDeleteProfile(context, profile);
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
        return true;
    }

    private void activateProfile(Context context, DnsProfile profile) {
        if (profile == null) {
            return;
        }
        String currentEngine = DnsProfiles.engine(context);
        DnsProfile currentActive = DnsProfiles.activeProfile(context);
        if (profile.engine.equals(currentEngine) && currentActive != null
                && profile.id.equals(currentActive.id)) {
            bulletin("Already the active profile");
            return;
        }
        DnsProfiles.setActive(context, profile.engine, profile.id);
        DnsProfiles.setEngine(context, profile.engine);
        AminDnsController.State state = controller.state();
        if (state == AminDnsController.State.CONNECTED || state == AminDnsController.State.STARTING) {
            controller.restart();
            bulletin("Switching tunnel to " + profile.name);
        }
        rebuild();
    }

    private void createProfile(Context context, String engine) {
        DnsProfile profile = DnsProfiles.newProfile(engine);
        profile.name = DnsProfile.ENGINE_SOAR.equals(engine) ? "New Soar profile" : "New Slipstream profile";
        DnsProfiles.save(context, profile);
        DnsProfiles.setActive(context, engine, profile.id);
        rebuild();
        editProfileDialog(context, profile);
    }

    private void importSoarLink(Context context) {
        promptText("Import soar:// link", "Paste the full soar:// link", "", true, text -> {
            DnsProfile profile = DnsProfiles.parseSoarLink(text);
            if (profile == null) {
                bulletin("Could not parse that link");
                return;
            }
            DnsProfiles.save(context, profile);
            DnsProfiles.setActive(context, DnsProfile.ENGINE_SOAR, profile.id);
            bulletin("Imported " + profile.name);
            rebuild();
        });
    }

    private void confirmDeleteProfile(Context context, DnsProfile profile) {
        if (getParentActivity() == null) {
            return;
        }
        if (DnsProfiles.profiles(context, profile.engine).size() <= 1) {
            bulletin("Keep at least one profile per engine");
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Delete " + profile.name + "?");
        builder.setMessage("The profile and its server settings are removed.");
        builder.setPositiveButton("Delete", (dialog, which) -> {
            boolean wasActive = false;
            DnsProfile active = DnsProfiles.activeProfile(context, profile.engine);
            if (active != null && active.id.equals(profile.id)) {
                wasActive = true;
            }
            DnsProfiles.delete(context, profile);
            if (wasActive && profile.engine.equals(DnsProfiles.engine(context))) {
                AminDnsController.State state = controller.state();
                if (state == AminDnsController.State.CONNECTED || state == AminDnsController.State.STARTING) {
                    controller.restart();
                }
            }
            bulletin("Profile deleted");
            rebuild();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void chooseEngine(Context context) {
        String[] engines = {DnsProfile.ENGINE_SOAR, DnsProfile.ENGINE_SLIPSTREAM};
        String[] labels = {"Soar (recommended)", "Slipstream (fast)"};
        int current = DnsProfile.ENGINE_SLIPSTREAM.equals(DnsProfiles.engine(context)) ? 1 : 0;
        showChooser("Engine", labels, current, which -> {
            String engine = engines[which];
            if (engine.equals(DnsProfiles.engine(context))) {
                return;
            }
            DnsProfiles.setEngine(context, engine);
            AminDnsController.State state = controller.state();
            if (state == AminDnsController.State.CONNECTED || state == AminDnsController.State.STARTING) {
                controller.restart();
            }
            rebuild();
        });
    }

    private void certDialog(Context context, DnsProfile profile) {
        if (getParentActivity() == null) {
            return;
        }
        List<String> items = new ArrayList<>();
        items.add("Paste PEM text");
        items.add("Pick a file…");
        if (profile.cert != null && !profile.cert.isEmpty()) {
            items.add("Clear certificate");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Server certificate");
        builder.setItems(items.toArray(new CharSequence[0]), (dialog, which) -> {
            String choice = items.get(which);
            if ("Paste PEM text".equals(choice)) {
                promptText("Certificate (PEM)", "-----BEGIN CERTIFICATE-----", profile.cert, true, text -> {
                    profile.cert = text.trim();
                    saveAndMaybeRestart(context, profile);
                });
            } else if ("Pick a file…".equals(choice)) {
                pickCertFile(context, profile);
            } else {
                profile.cert = "";
                saveAndMaybeRestart(context, profile);
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void pickCertFile(Context context, DnsProfile profile) {
        Context activityContext = getParentActivity() != null ? getParentActivity() : context;
        PluginFilePickerActivity.request(activityContext, "*/*", false, "dns_tunnel", path -> {
            if (path == null) {
                return;
            }
            String text = readFileText(path);
            AndroidUtilities.runOnUIThread(() -> {
                if (text == null || !text.contains("BEGIN CERTIFICATE")) {
                    bulletin("That file does not look like a PEM certificate");
                    return;
                }
                profile.cert = text.trim();
                saveAndMaybeRestart(context, profile);
                bulletin("Certificate saved");
            });
        });
    }

    private static String readFileText(String path) {
        FileInputStream in = null;
        try {
            File file = new File(path);
            if (file.length() > 256 * 1024) {
                return null;
            }
            in = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(buf, 0, read, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void saveAndMaybeRestart(Context context, DnsProfile profile) {
        DnsProfiles.save(context, profile);
        DnsProfile active = DnsProfiles.activeProfile(context);
        AminDnsController.State state = controller.state();
        if (active != null && active.id.equals(profile.id)
                && (state == AminDnsController.State.CONNECTED || state == AminDnsController.State.STARTING)) {
            controller.restart();
            bulletin("Profile updated — tunnel restarting");
        }
        rebuild();
    }

    // ---------------------------------------------------------------
    // Dialogs
    // ---------------------------------------------------------------

    private interface TextCallback {
        void onText(String text);
    }

    private interface ChoiceCallback {
        void onChoice(int which);
    }

    private void promptText(String title, String hint, String initial, boolean multiline, TextCallback callback) {
        promptText(title, hint, initial, multiline, false, callback);
    }

    /** Masked-input variant for secrets (the profile's SOCKS password). */
    private void promptSecret(String title, String hint, String initial, TextCallback callback) {
        promptText(title, hint, initial, false, true, callback);
    }

    private void promptText(String title, String hint, String initial, boolean multiline,
                            boolean masked, TextCallback callback) {
        Context context = getContext();
        if (context == null || getParentActivity() == null) {
            return;
        }
        EditTextBoldCursor field = textField(context, hint, multiline);
        if (masked) {
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        field.setText(initial == null ? "" : initial);
        field.setSelection(field.getText() == null ? 0 : field.getText().length());
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setView(fieldContainer(context, field));
        builder.setPositiveButton("Save", (dialog, which) ->
                callback.onText(field.getText() == null ? "" : field.getText().toString()));
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    /** Full profile edit: name + host (+ server key for Soar) in one dialog. */
    private void editProfileDialog(Context context, DnsProfile profile) {
        if (getParentActivity() == null) {
            return;
        }
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        EditTextBoldCursor nameField = textField(context, "Name", false);
        nameField.setText(profile.name);
        box.addView(fieldContainer(context, nameField));
        EditTextBoldCursor hostField = textField(context, profile.isSoar() ? "Zone" : "Domain", false);
        hostField.setText(profile.host());
        box.addView(fieldContainer(context, hostField));
        EditTextBoldCursor keyField = null;
        if (profile.isSoar()) {
            keyField = textField(context, "Server public key (base64)", true);
            keyField.setText(profile.serverKey);
            box.addView(fieldContainer(context, keyField));
        }
        final EditTextBoldCursor finalKeyField = keyField;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Edit profile");
        builder.setView(box);
        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = nameField.getText() == null ? "" : nameField.getText().toString().trim();
            if (!name.isEmpty()) {
                profile.name = name;
            }
            String host = hostField.getText() == null ? "" : hostField.getText().toString().trim();
            if (profile.isSoar()) {
                profile.zone = host;
            } else {
                profile.domain = host;
            }
            if (finalKeyField != null && finalKeyField.getText() != null) {
                profile.serverKey = finalKeyField.getText().toString().trim();
            }
            saveAndMaybeRestart(context, profile);
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showChooser(String title, String[] labels, int current, ChoiceCallback callback) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setSingleChoiceItems(labels, current, (dialog, which) -> {
            callback.onChoice(which);
            dialog.dismiss();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private EditTextBoldCursor textField(Context context, String hint, boolean multiline) {
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setBackground(null);
        field.setHint(hint);
        field.setSingleLine(!multiline);
        if (multiline) {
            field.setMaxLines(6);
            field.setGravity(Gravity.TOP | Gravity.LEFT);
        }
        return field;
    }

    private FrameLayout fieldContainer(Context context, View field) {
        FrameLayout container = new FrameLayout(context);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8),
                AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        container.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return container;
    }

    private void bulletin(String text) {
        try {
            BulletinFactory.of(this).createSimpleBulletin(
                    org.telegram.messenger.R.raw.contact_check, text).show();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------
    // Adapter
    // ---------------------------------------------------------------

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position < 0 || position >= rows.size()) {
                return false;
            }
            int kind = rows.get(position).kind;
            return kind == KIND_CHECK || kind == KIND_SETTING;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case KIND_HEADER:
                    view = new HeaderCell(mContext);
                    break;
                case KIND_CHECK:
                    view = new TextCheckCell(mContext);
                    break;
                case KIND_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case KIND_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                case KIND_SETTING:
                default:
                    view = new TextSettingsCell(mContext);
                    break;
            }
            if (viewType == KIND_HEADER || viewType == KIND_CHECK || viewType == KIND_SETTING) {
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            boolean divider = position + 1 < rows.size() && rows.get(position + 1).kind != KIND_SHADOW
                    && rows.get(position + 1).kind != KIND_HEADER;
            switch (holder.getItemViewType()) {
                case KIND_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.title);
                    break;
                case KIND_CHECK:
                    ((TextCheckCell) holder.itemView).setTextAndCheck(row.title, row.checked, divider);
                    break;
                case KIND_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                    break;
                case KIND_SETTING: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(Theme.getColor(row.red
                            ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlackText));
                    if (row.value == null || row.value.isEmpty()) {
                        cell.setText(row.title, divider);
                    } else {
                        cell.setTextAndValue(row.title, row.value, divider);
                    }
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).kind;
        }
    }
}
