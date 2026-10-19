package org.telegram.messenger.amin.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts proxy configs and subscription links from message text.
 * Used by Config Vault (and later by share/export helpers).
 */
public final class ConfigExtractor {

    private static final Pattern CONFIG_PATTERN = Pattern.compile(
            "(vless|vmess|trojan|ss|ssr|hysteria2?|tuic)://[^\\s]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUB_PATTERN = Pattern.compile(
            "https?://[^\\s]+");

    private ConfigExtractor() {
    }

    public static List<String> extractConfigs(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = CONFIG_PATTERN.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Heuristic: subscription URLs are https links hosted on a path that
     *  looks like a sub endpoint; Config Vault stores them for one-tap copy. */
    public static List<String> extractSubscriptionLinks(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = SUB_PATTERN.matcher(text);
        while (m.find()) {
            String url = m.group();
            String lower = url.toLowerCase();
            if (lower.contains("/sub") || lower.contains("subscribe") || lower.contains("/link/")) {
                out.add(url);
            }
        }
        return out;
    }
}
