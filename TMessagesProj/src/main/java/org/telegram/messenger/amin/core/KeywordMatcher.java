package org.telegram.messenger.amin.core;

import java.util.List;
import java.util.Locale;

/** Case-insensitive keyword matching for Keyword Radar. */
public final class KeywordMatcher {

    private KeywordMatcher() {
    }

    public static boolean matches(String text, List<String> keywords) {
        return matchedKeyword(text, keywords) != null;
    }

    public static String matchedKeyword(String text, List<String> keywords) {
        if (text == null || keywords == null) {
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
}
