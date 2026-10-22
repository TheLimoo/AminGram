package org.telegram.messenger.amin.dns;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Profile store for the DNS Tunnel module, backed by the
 * "amingram_dns" SharedPreferences (the same file the controller
 * keeps its state in — see AminDnsController).
 *
 * Layout:
 *   "profiles_soar" / "profiles_slipstream" — JSONArray of
 *       {@link DnsProfile} JSON objects.
 *   "active_soar" / "active_slipstream" — id of the active profile
 *       per engine.
 *   "engine" — the engine the tunnel currently targets
 *       ("soar" default).
 *
 * First run seeds ONE default profile per engine for Amin's own
 * servers (Stockholm). They are ordinary editable profiles — rename,
 * repoint or delete them like any other; nothing in the engines
 * knows they exist.
 *
 * Resolver resolution: a profile carries a resolver MODE
 * (auto / system / public / custom) plus a custom list. The
 * effective list is computed here, on the Java side, so all Android
 * API access (system DNS via ConnectivityManager) stays out of the
 * native engines. Order matters: in shutdown conditions the ISP
 * resolver is frequently the only working path (plan §5.3), so
 * system DNS always leads in auto mode; Soar additionally runs its
 * IR discovery on top and uses all paths simultaneously.
 */
public final class DnsProfiles {

    public static final String PREFS = "amingram_dns";

    private static final String KEY_PROFILES_SOAR = "profiles_soar";
    private static final String KEY_PROFILES_SLIP = "profiles_slipstream";
    private static final String KEY_ACTIVE_SOAR = "active_soar";
    private static final String KEY_ACTIVE_SLIP = "active_slipstream";
    private static final String KEY_ENGINE = "engine";

    /** Public fallback resolvers (plan §5.3), in preference order. */
    private static final String[] PUBLIC_RESOLVERS = {"8.8.8.8", "1.1.1.1", "9.9.9.9"};

    private DnsProfiles() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------
    // Engine selection
    // ---------------------------------------------------------------

    public static String engine(Context context) {
        String e = prefs(context).getString(KEY_ENGINE, DnsProfile.ENGINE_SOAR);
        return DnsProfile.ENGINE_SLIPSTREAM.equals(e) ? DnsProfile.ENGINE_SLIPSTREAM : DnsProfile.ENGINE_SOAR;
    }

    public static void setEngine(Context context, String engine) {
        prefs(context).edit().putString(KEY_ENGINE, engine).apply();
    }

    // ---------------------------------------------------------------
    // CRUD
    // ---------------------------------------------------------------

    public static List<DnsProfile> profiles(Context context, String engine) {
        ensureSeeded(context);
        return load(context, engine);
    }

    public static DnsProfile activeProfile(Context context, String engine) {
        List<DnsProfile> list = profiles(context, engine);
        if (list.isEmpty()) {
            return null;
        }
        String activeId = prefs(context).getString(activeKey(engine), "");
        for (DnsProfile p : list) {
            if (p.id.equals(activeId)) {
                return p;
            }
        }
        return list.get(0);
    }

    /** The profile the tunnel will use: the active profile of the selected engine. */
    public static DnsProfile activeProfile(Context context) {
        return activeProfile(context, engine(context));
    }

    public static void setActive(Context context, String engine, String profileId) {
        prefs(context).edit().putString(activeKey(engine), profileId).apply();
    }

    public static void save(Context context, DnsProfile profile) {
        List<DnsProfile> list = load(context, profile.engine);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(profile.id)) {
                list.set(i, profile);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(profile);
        }
        store(context, profile.engine, list);
    }

    public static void delete(Context context, DnsProfile profile) {
        List<DnsProfile> list = load(context, profile.engine);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(profile.id)) {
                list.remove(i);
            }
        }
        store(context, profile.engine, list);
        if (profile.id.equals(prefs(context).getString(activeKey(profile.engine), ""))) {
            SharedPreferences.Editor e = prefs(context).edit();
            e.remove(activeKey(profile.engine));
            e.apply();
        }
    }

    public static DnsProfile newProfile(String engine) {
        DnsProfile p = new DnsProfile();
        p.id = UUID.randomUUID().toString();
        p.engine = engine;
        p.name = "New profile";
        return p;
    }

    private static String activeKey(String engine) {
        return DnsProfile.ENGINE_SLIPSTREAM.equals(engine) ? KEY_ACTIVE_SLIP : KEY_ACTIVE_SOAR;
    }

    private static String profilesKey(String engine) {
        return DnsProfile.ENGINE_SLIPSTREAM.equals(engine) ? KEY_PROFILES_SLIP : KEY_PROFILES_SOAR;
    }

    private static List<DnsProfile> load(Context context, String engine) {
        List<DnsProfile> out = new ArrayList<>();
        String raw = prefs(context).getString(profilesKey(engine), null);
        if (raw == null) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                DnsProfile p = DnsProfile.fromJson(arr.optJSONObject(i));
                if (p != null) {
                    p.engine = engine;
                    out.add(p);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void store(Context context, String engine, List<DnsProfile> list) {
        JSONArray arr = new JSONArray();
        for (DnsProfile p : list) {
            arr.put(p.toJson());
        }
        prefs(context).edit().putString(profilesKey(engine), arr.toString()).apply();
    }

    // ---------------------------------------------------------------
    // Seeding (first run only)
    // ---------------------------------------------------------------

    private static void ensureSeeded(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(KEY_PROFILES_SOAR) && prefs.contains(KEY_PROFILES_SLIP)) {
            return;
        }
        synchronized (DnsProfiles.class) {
            if (!prefs.contains(KEY_PROFILES_SOAR)) {
                DnsProfile soar = new DnsProfile();
                soar.id = "default-soar";
                soar.engine = DnsProfile.ENGINE_SOAR;
                soar.name = "Limoo (Stockholm)";
                soar.zone = "s.limoo.indevs.in";
                soar.serverKey = DefaultProfiles.soarServerKey();
                soar.discovery = true;
                soar.discoveryCountry = "IR";
                List<DnsProfile> list = new ArrayList<>();
                list.add(soar);
                store(context, DnsProfile.ENGINE_SOAR, list);
                prefs.edit().putString(KEY_ACTIVE_SOAR, soar.id).apply();
            }
            if (!prefs.contains(KEY_PROFILES_SLIP)) {
                DnsProfile slip = new DnsProfile();
                slip.id = "default-slipstream";
                slip.engine = DnsProfile.ENGINE_SLIPSTREAM;
                slip.name = "Limoo (Stockholm)";
                slip.domain = "b.limoo.indevs.in";
                slip.congestion = DnsProfile.CONGESTION_DCUBIC;
                slip.cert = readAssetText(context, "amingram_dns/slipstream-default-cert.pem");
                List<DnsProfile> list = new ArrayList<>();
                list.add(slip);
                store(context, DnsProfile.ENGINE_SLIPSTREAM, list);
                prefs.edit().putString(KEY_ACTIVE_SLIP, slip.id).apply();
            }
        }
    }

    private static String readAssetText(Context context, String path) {
        InputStream in = null;
        try {
            in = context.getApplicationContext().getAssets().open(path);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // soar:// import
    // ---------------------------------------------------------------

    /**
     * Parses a {@code soar://} share link (base64url-encoded JSON, as
     * produced by Soar-Server) into a new Soar profile. The link
     * schema is not formally versioned, so field lookup is tolerant:
     * zone/domain/host, key/serverKey/pubkey, resolvers/dns. Returns
     * null when the payload does not decode to something usable.
     */
    public static DnsProfile parseSoarLink(String text) {
        if (text == null) {
            return null;
        }
        String payload = text.trim();
        if (payload.startsWith("soar://")) {
            payload = payload.substring("soar://".length());
        }
        int frag = payload.indexOf('#');
        if (frag >= 0) {
            payload = payload.substring(0, frag);
        }
        int q = payload.indexOf('?');
        if (q >= 0) {
            payload = payload.substring(0, q);
        }
        payload = payload.trim();
        if (payload.isEmpty()) {
            return null;
        }
        try {
            byte[] raw = android.util.Base64.decode(payload,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
            JSONObject o = new JSONObject(new String(raw, "UTF-8"));
            DnsProfile p = newProfile(DnsProfile.ENGINE_SOAR);
            p.zone = firstString(o, "zone", "domain", "host", "server");
            p.serverKey = firstString(o, "serverKey", "key", "pubkey", "publicKey", "server_key");
            p.name = firstString(o, "name", "label");
            if (p.name.isEmpty()) {
                p.name = p.zone.isEmpty() ? "Imported Soar profile" : p.zone;
            }
            JSONArray arr = o.optJSONArray("resolvers");
            if (arr == null) {
                arr = o.optJSONArray("dns");
            }
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String r = arr.optString(i, "").trim();
                    if (!r.isEmpty()) {
                        p.resolvers.add(r);
                    }
                }
                if (!p.resolvers.isEmpty()) {
                    p.resolverMode = DnsProfile.RESOLVERS_CUSTOM;
                }
            }
            if (o.has("discovery")) {
                Object d = o.opt("discovery");
                if (d instanceof JSONObject) {
                    p.discovery = ((JSONObject) d).optBoolean("enabled", true);
                    p.discoveryCountry = ((JSONObject) d).optString("country", "IR");
                } else if (d instanceof Boolean) {
                    p.discovery = (Boolean) d;
                }
            }
            if (p.zone.isEmpty() || p.serverKey.isEmpty()) {
                return null;
            }
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstString(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    // ---------------------------------------------------------------
    // Resolver resolution (plan §5.3)
    // ---------------------------------------------------------------

    /** System (ISP/carrier) DNS servers, bare host strings, in OS order. */
    public static List<String> systemDns(Context context) {
        List<String> out = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network network = cm.getActiveNetwork();
                LinkProperties props = network != null ? cm.getLinkProperties(network) : null;
                if (props != null) {
                    for (InetAddress addr : props.getDnsServers()) {
                        String host = addr.getHostAddress();
                        if (host != null && !host.isEmpty()) {
                            out.add(host);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * The resolver list an engine start will use for this profile,
     * as "host:port" entries (port 53 defaulted), order-significant:
     * system DNS first in auto mode — in shutdown conditions the ISP
     * resolver is often the only path that still recurses (plan §5.3).
     */
    public static List<String> effectiveResolvers(Context context, DnsProfile profile) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String mode = profile.resolverMode == null ? DnsProfile.RESOLVERS_AUTO : profile.resolverMode;
        switch (mode) {
            case DnsProfile.RESOLVERS_SYSTEM:
                addAll(out, systemDns(context));
                if (out.isEmpty()) {
                    addAll(out, publics());
                }
                break;
            case DnsProfile.RESOLVERS_PUBLIC:
                addAll(out, publics());
                break;
            case DnsProfile.RESOLVERS_CUSTOM:
                addAll(out, profile.resolvers);
                if (out.isEmpty()) {
                    addAll(out, systemDns(context));
                    addAll(out, publics());
                }
                break;
            case DnsProfile.RESOLVERS_AUTO:
            default:
                addAll(out, systemDns(context));
                addAll(out, publics());
                break;
        }
        List<String> result = new ArrayList<>();
        for (String r : out) {
            result.add(withPort(r));
        }
        return result;
    }

    public static List<String> publics() {
        List<String> out = new ArrayList<>();
        for (String r : PUBLIC_RESOLVERS) {
            out.add(r);
        }
        return out;
    }

    private static void addAll(LinkedHashSet<String> out, List<String> values) {
        if (values == null) {
            return;
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                out.add(v.trim());
            }
        }
    }

    /** "1.1.1.1" -> "1.1.1.1:53"; an explicit ":port" suffix is kept. */
    public static String withPort(String resolver) {
        String r = resolver.trim();
        if (r.matches(".*:\\d+$") && !r.endsWith("]")) {
            // Already has a port — but a bare IPv6 address also matches;
            // those carry several colons, so only trust the suffix when
            // the host part has at most one colon.
            int first = r.indexOf(':');
            int last = r.lastIndexOf(':');
            if (first == last) {
                return r;
            }
        }
        if (r.indexOf(':') >= 0 && r.indexOf(':') != r.lastIndexOf(':')) {
            // Bare IPv6 — wrap so host:port stays unambiguous.
            return "[" + r + "]:53";
        }
        return r + ":53";
    }

    /** Joins resolvers for the Soar shim's CSV parameter. */
    public static String resolversCsv(List<String> resolvers) {
        StringBuilder sb = new StringBuilder();
        for (String r : resolvers) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(r);
        }
        return sb.toString();
    }
}
