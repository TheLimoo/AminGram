package org.telegram.messenger.amin.dns;

/**
 * Compile-time constants for the seeded default profiles.
 *
 * The Soar server PUBLIC key below belongs to Amin's own Soar
 * server (zone s.limoo.indevs.in, Stockholm). It is client-profile
 * material by design — Soar clients ship it inside soar:// links,
 * and the server authenticates clients with it; it is not a
 * credential (the matching private key never leaves the server).
 * Source: dns-prep/client-profiles/soar-server-pubkey.txt
 * (`soar-server pubkey` output, fetched 2026-10-22).
 *
 * It is used ONLY to seed the editable default profile on first
 * run — {@link DnsProfiles}. Nothing in the engines reads it.
 */
final class DefaultProfiles {

    private static final String SOAR_SERVER_KEY = "ti9Hb/0dD+mcIp5G7SMCDxiOqJ7wu2H0DY28CWw0Nn4=";

    private DefaultProfiles() {
    }

    static String soarServerKey() {
        return SOAR_SERVER_KEY;
    }
}
