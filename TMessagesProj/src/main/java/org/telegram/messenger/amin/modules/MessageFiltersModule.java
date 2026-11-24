package org.telegram.messenger.amin.modules;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.amin.core.KeywordMatcher;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Message Filters — hides or collapses messages matching user-configured
 * stop-word keywords or blocked sender IDs behind a slim
 * {@code "Filtered — tap to reveal"} placeholder.
 *
 * Rules live right next to Keyword Radar in the hub and share the same
 * pre-lowercased {@link KeywordMatcher} engine. Revealed messages are tracked
 * per session in memory so tapping a filtered message (or choosing "Reveal
 * filtered text" from the message menu) restores the original text immediately.
 */
public class MessageFiltersModule implements ModModule {

    public static final String PREFS = "amingram_filters";
    public static final String KEY_KEYWORDS = "filter_keywords";
    public static final String KEY_SENDERS = "filter_senders";
    public static final String KEY_CHANNELS_ONLY = "filter_channels_only";

    public static final String PLACEHOLDER_PREFIX = "\u200E\uD83D\uDEAB Filtered";
    public static final String PLACEHOLDER_SUFFIX = " — tap to reveal";

    private static volatile String cachedRawKeywords;
    private static volatile List<String> cachedKeywords = Collections.emptyList();
    private static volatile List<String> cachedLowerKeywords = Collections.emptyList();

    private static volatile String cachedRawSenders;
    private static volatile Set<Long> cachedSenders = Collections.emptySet();

    /** Session-scoped set of "dialogId|msgId" keys that the user has tapped to reveal. */
    private static final Set<String> revealedMessages = ConcurrentHashMap.newKeySet();
    /** Original text preserved when a message is replaced with the placeholder in-place. */
    private static final Map<String, CharSequence> originalTexts = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "message_filters";
    }

    @Override
    public String title() {
        return "Message Filters";
    }

    @Override
    public String description() {
        return "Collapses messages matching filter keywords or blocked sender IDs behind a slim “Filtered — tap to reveal” placeholder. Rules live right next to Keyword Radar.";
    }

    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public int section() {
        return SECTION_TOOLS;
    }

    @Override
    public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        int kwCount = loadKeywords(context).size();
        int senderCount = loadSenders(context).size();
        if (kwCount == 0 && senderCount == 0) {
            return "Needs filter keywords or sender IDs";
        }
        return "Active · " + kwCount + " keywords · " + senderCount + " senders";
    }

    public static String getKeywordsRaw(Context context) {
        if (context == null) {
            return "";
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_KEYWORDS, "");
    }

    public static void setKeywords(Context context, String raw) {
        if (context == null) {
            return;
        }
        String clean = raw == null ? "" : raw.trim();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_KEYWORDS, clean)
                .apply();
        rebuildKeywordCache(clean);
    }

    public static List<String> loadKeywords(Context context) {
        String raw = getKeywordsRaw(context);
        if (!raw.equals(cachedRawKeywords)) {
            rebuildKeywordCache(raw);
        }
        return cachedKeywords;
    }

    public static List<String> loadLowerKeywords(Context context) {
        String raw = getKeywordsRaw(context);
        if (!raw.equals(cachedRawKeywords)) {
            rebuildKeywordCache(raw);
        }
        return cachedLowerKeywords;
    }

    private static synchronized void rebuildKeywordCache(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            cachedRawKeywords = raw == null ? "" : raw;
            cachedKeywords = Collections.emptyList();
            cachedLowerKeywords = Collections.emptyList();
            return;
        }
        List<String> out = new ArrayList<>();
        List<String> lower = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
                lower.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        cachedRawKeywords = raw;
        cachedKeywords = Collections.unmodifiableList(out);
        cachedLowerKeywords = Collections.unmodifiableList(lower);
    }

    public static String getSendersRaw(Context context) {
        if (context == null) {
            return "";
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SENDERS, "");
    }

    public static void setSenders(Context context, String raw) {
        if (context == null) {
            return;
        }
        String clean = raw == null ? "" : raw.trim();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SENDERS, clean)
                .apply();
        rebuildSenderCache(clean);
    }

    public static Set<Long> loadSenders(Context context) {
        String raw = getSendersRaw(context);
        if (!raw.equals(cachedRawSenders)) {
            rebuildSenderCache(raw);
        }
        return cachedSenders;
    }

    private static synchronized void rebuildSenderCache(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            cachedRawSenders = raw == null ? "" : raw;
            cachedSenders = Collections.emptySet();
            return;
        }
        Set<Long> set = new HashSet<>();
        for (String part : raw.split("[,\\s]+")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(trimmed);
                if (id != 0) {
                    set.add(id);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        cachedRawSenders = raw;
        cachedSenders = Collections.unmodifiableSet(set);
    }

    public static boolean channelsOnly(Context context) {
        if (context == null) {
            return false;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CHANNELS_ONLY, false);
    }

    public static void setChannelsOnly(Context context, boolean value) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CHANNELS_ONLY, value)
                .apply();
    }

    public static String matchReason(Context context, long dialogId, long senderId, CharSequence text) {
        if (context == null) {
            return null;
        }
        if (channelsOnly(context) && dialogId >= 0) {
            return null;
        }
        Set<Long> senders = loadSenders(context);
        if (!senders.isEmpty() && (senders.contains(senderId) || senders.contains(Math.abs(senderId)))) {
            return "sender " + senderId;
        }
        if (text != null && text.length() > 0) {
            String s = text.toString();
            if (s.startsWith(PLACEHOLDER_PREFIX)) {
                return "filtered";
            }
            List<String> kws = loadKeywords(context);
            List<String> lower = loadLowerKeywords(context);
            if (!kws.isEmpty()) {
                String hit = KeywordMatcher.matchedPreLowered(s, kws, lower);
                if (hit != null) {
                    return "“" + hit + "”";
                }
            }
        }
        return null;
    }

    public static String messageKey(long dialogId, int msgId) {
        return dialogId + "|" + msgId;
    }

    public static boolean isRevealed(long dialogId, int msgId) {
        return revealedMessages.contains(messageKey(dialogId, msgId));
    }

    public static void rememberOriginalText(long dialogId, int msgId, CharSequence text) {
        if (text == null || text.toString().startsWith(PLACEHOLDER_PREFIX)) {
            return;
        }
        if (originalTexts.size() > 2000) {
            originalTexts.clear();
        }
        originalTexts.putIfAbsent(messageKey(dialogId, msgId), text);
    }

    public static CharSequence getOriginalText(long dialogId, int msgId) {
        return originalTexts.get(messageKey(dialogId, msgId));
    }

    public static boolean reveal(long dialogId, int msgId) {
        String key = messageKey(dialogId, msgId);
        return revealedMessages.add(key);
    }

    public static String placeholderFor(String reason) {
        if (reason == null || reason.isEmpty() || "filtered".equals(reason)) {
            return PLACEHOLDER_PREFIX + PLACEHOLDER_SUFFIX;
        }
        return PLACEHOLDER_PREFIX + " (" + reason + ")" + PLACEHOLDER_SUFFIX;
    }
}
