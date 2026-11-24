package org.telegram.messenger.amin.core;

import java.util.List;
import java.util.Locale;

/**
 * Case-insensitive keyword matching for Keyword Radar and Message Filters.
 *
 * Phase A optimization (A-23): supports matching against pre-trimmed,
 * pre-lowercased keyword needles so batch scans do not re-trim and
 * re-lowercase every keyword on every message.
 */
public final class KeywordMatcher {

    private KeywordMatcher() {
    }

    public static boolean matches(String text, List<String> keywords) {
        return matchedKeyword(text, keywords) != null;
    }

    public static String matchedKeyword(String text, List<String> keywords) {
        if (text == null || keywords == null || keywords.isEmpty()) {
            return null;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword == null) {
                continue;
            }
            String needle = keyword.trim().toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && haystack.contains(needle)) {
                return keyword;
            }
        }
        return null;
    }

    /** Fast path using pre-lowercased needles parallel to rawKeywords (A-23). */
    public static String matchedPreLowered(String text, List<String> rawKeywords, List<String> loweredNeedles) {
        if (text == null || rawKeywords == null || loweredNeedles == null || rawKeywords.isEmpty()) {
            return null;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        int size = Math.min(rawKeywords.size(), loweredNeedles.size());
        for (int i = 0; i < size; i++) {
            String needle = loweredNeedles.get(i);
            if (needle != null && !needle.isEmpty() && haystack.contains(needle)) {
                return rawKeywords.get(i);
            }
        }
        return null;
    }
}
