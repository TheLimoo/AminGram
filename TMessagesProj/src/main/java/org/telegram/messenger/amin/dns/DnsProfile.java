package org.telegram.messenger.amin.dns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One DNS-tunnel server profile. Profiles are the unit of
 * configuration for the DNS Tunnel module: NOTHING server-specific
 * is hardcoded in the engines — the zone/domain, server key or
 * certificate, resolver list and per-engine options all live here,
 * editable in AminDnsTunnelActivity, so Amin can point the tunnel at
 * any Soar / Slipstream server (his own after a domain change, or
 * somebody else's) without an app update.
 *
 * Two flavours share this shape:
 *  - Soar:      zone + serverKey (base64 Ed25519 public key) +
 *               discovery on/off + discovery country.
 *  - Slipstream: domain + cert (PEM text for strict leaf pinning,
 *               empty = legacy no-verify) + congestion control +
 *               optional SOCKS username/password (the tunnel's far
 *               end is a SOCKS5 server that may demand RFC1929 auth).
 * Both carry a resolver mode (auto / system / public / custom) and,
 * for custom mode, an editable resolver list ("host" or "host:port"
 * entries, port defaults to 53).
 *
 * Serialized as JSON inside the "amingram_dns" prefs — see
 * {@link DnsProfiles}.
 */
public final class DnsProfile {

    public static final String ENGINE_SOAR = "soar";
    public static final String ENGINE_SLIPSTREAM = "slipstream";

    public static final String RESOLVERS_AUTO = "auto";
    public static final String RESOLVERS_SYSTEM = "system";
    public static final String RESOLVERS_PUBLIC = "public";
    public static final String RESOLVERS_CUSTOM = "custom";

    public static final String CONGESTION_DCUBIC = "dcubic";
    public static final String CONGESTION_BBR = "bbr";

    public String id;
    public String engine;
    public String name = "";

    // Soar
    public String zone = "";
    public String serverKey = "";
    public boolean discovery = true;
    public String discoveryCountry = "IR";

    // Slipstream
    public String domain = "";
    public String cert = "";
    public String congestion = CONGESTION_DCUBIC;
    // Optional RFC1929 credentials for the SOCKS5 server at the
    // tunnel's far end (the kit's microsocks runs with -u/-P and
    // answers the greeting with 05 02). Telegram's proxy entry and
    // the controller's liveness gate both consume these. Empty =
    // no auth. Soar profiles ignore them — the local Soar shim's
    // SOCKS listener is auth-free.
    public String socksUser = "";
    public String socksPass = "";

    // Both engines
    public String resolverMode = RESOLVERS_AUTO;
    public final List<String> resolvers = new ArrayList<>();

    public boolean isSoar() {
        return ENGINE_SOAR.equals(engine);
    }

    /** The address the tunnel is pointed at (Soar zone / Slipstream domain). */
    public String host() {
        return isSoar() ? zone : domain;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("engine", engine);
            o.put("name", name);
            o.put("zone", zone);
            o.put("serverKey", serverKey);
            o.put("discovery", discovery);
            o.put("discoveryCountry", discoveryCountry);
            o.put("domain", domain);
            o.put("cert", cert);
            o.put("congestion", congestion);
            o.put("socksUser", socksUser);
            o.put("socksPass", socksPass);
            o.put("resolverMode", resolverMode);
            JSONArray arr = new JSONArray();
            for (String r : resolvers) {
                arr.put(r);
            }
            o.put("resolvers", arr);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static DnsProfile fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        DnsProfile p = new DnsProfile();
        p.id = o.optString("id", "");
        p.engine = o.optString("engine", ENGINE_SOAR);
        p.name = o.optString("name", "");
        p.zone = o.optString("zone", "");
        p.serverKey = o.optString("serverKey", "");
        p.discovery = o.optBoolean("discovery", true);
        p.discoveryCountry = o.optString("discoveryCountry", "IR");
        p.domain = o.optString("domain", "");
        p.cert = o.optString("cert", "");
        p.congestion = o.optString("congestion", CONGESTION_DCUBIC);
        p.socksUser = o.optString("socksUser", "");
        p.socksPass = o.optString("socksPass", "");
        p.resolverMode = o.optString("resolverMode", RESOLVERS_AUTO);
        JSONArray arr = o.optJSONArray("resolvers");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String r = arr.optString(i, "").trim();
                if (!r.isEmpty()) {
                    p.resolvers.add(r);
                }
            }
        }
        if (p.id.isEmpty()) {
            return null;
        }
        return p;
    }
}
