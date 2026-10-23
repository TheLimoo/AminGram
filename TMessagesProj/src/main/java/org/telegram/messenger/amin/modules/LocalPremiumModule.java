package org.telegram.messenger.amin.modules;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Local Premium — an AyuGram-style LOCAL premium. The master switch
 * remains the PluginRegistry module toggle; the sub-toggles and the
 * per-account picks live in this module's own prefs file
 * ({@link #PREFS}) and only ever narrow or decorate what the master
 * switch enables (the GhostModeModule sub-toggle idiom).
 *
 * Everything here is LOCAL to this device:
 *  - the premium flag makes {@code UserConfig.isPremium()} return
 *    true (patch 0003), which opens client-side UI gates — including
 *    the emoji/sticker picker, whose every gate consults that one
 *    funnel;
 *  - the emoji status, the name emoji and the name/profile/reply
 *    colors are stored here and injected at render time for Amin's
 *    OWN user object only (UserObject getters, patch 0003).
 * Other people see none of it, and the server still treats the
 * account as free: server-enforced premium (upload limits, a real
 * emoji status, custom-emoji entities in sent messages) is NOT
 * faked here and does not change.
 */
public class LocalPremiumModule implements ModModule {

    /** This module's own prefs store (sub-toggles + per-user picks). */
    public static final String PREFS = "amingram_local_premium";

    // Sub-toggles (global; all default ON — they only narrow the
    // master switch, exactly like Ghost Mode's suppressions).
    public static final String KEY_PREMIUM_FLAG = "premium_flag";
    public static final String KEY_PICKER_UNLOCK = "picker_unlock";
    public static final String KEY_NAME_EMOJI_ON = "name_emoji_on";
    public static final String KEY_EMOJI_STATUS_ON = "emoji_status_on";
    public static final String KEY_PEER_COLORS_ON = "peer_colors_on";

    // Per-user values: key + "_" + userId (the account's own id, so
    // accounts never see each other's picks).
    private static final String KEY_STATUS_DOC = "status_doc_";
    private static final String KEY_NAME_EMOJI_DOC = "name_emoji_doc_";
    private static final String KEY_NAME_EMOJI_ALT = "name_emoji_alt_";
    private static final String KEY_NAME_COLOR = "name_color_";
    private static final String KEY_PROFILE_COLOR = "profile_color_";
    private static final String KEY_REPLY_COLOR = "reply_color_";

    /** "Not chosen" sentinel for the color ids (real ids are >= 0). */
    public static final int COLOR_UNSET = -1;

    @Override public String id() { return "local_premium"; }
    @Override public String title() { return "Local Premium"; }
    @Override public String description() {
        return "Treats your account as Premium on THIS device only: premium UI gates and emoji/sticker packs unlock, plus a local emoji status, a name emoji and your own name/profile/reply colors. Other people see none of it — the server still sees a free account.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_TOOLS; }
    @Override public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        List<String> active = new ArrayList<>();
        if (premiumFlag(context)) {
            active.add("flag");
        }
        if (pickerUnlock(context)) {
            active.add("packs");
        }
        if (nameEmojiEnabled(context)) {
            active.add("name emoji");
        }
        if (emojiStatusEnabled(context)) {
            active.add("status");
        }
        if (peerColorsEnabled(context)) {
            active.add("colors");
        }
        if (active.isEmpty()) {
            return "Active — all extras off";
        }
        StringBuilder out = new StringBuilder("Local only — ");
        for (int i = 0; i < active.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(active.get(i));
        }
        return out.toString();
    }

    // ---------------------------------------------------------------
    // Sub-toggles
    // ---------------------------------------------------------------

    public static boolean premiumFlag(Context context) {
        return getToggle(context, KEY_PREMIUM_FLAG);
    }

    public static void setPremiumFlag(Context context, boolean value) {
        setToggle(context, KEY_PREMIUM_FLAG, value);
    }

    public static boolean pickerUnlock(Context context) {
        return getToggle(context, KEY_PICKER_UNLOCK);
    }

    public static void setPickerUnlock(Context context, boolean value) {
        setToggle(context, KEY_PICKER_UNLOCK, value);
    }

    public static boolean nameEmojiEnabled(Context context) {
        return getToggle(context, KEY_NAME_EMOJI_ON);
    }

    public static void setNameEmojiEnabled(Context context, boolean value) {
        setToggle(context, KEY_NAME_EMOJI_ON, value);
    }

    public static boolean emojiStatusEnabled(Context context) {
        return getToggle(context, KEY_EMOJI_STATUS_ON);
    }

    public static void setEmojiStatusEnabled(Context context, boolean value) {
        setToggle(context, KEY_EMOJI_STATUS_ON, value);
    }

    public static boolean peerColorsEnabled(Context context) {
        return getToggle(context, KEY_PEER_COLORS_ON);
    }

    public static void setPeerColorsEnabled(Context context, boolean value) {
        setToggle(context, KEY_PEER_COLORS_ON, value);
    }

    // ---------------------------------------------------------------
    // Per-user picks
    // ---------------------------------------------------------------

    /** Local emoji status document id for the user, 0 = none. */
    public static long statusDocumentId(Context context, long userId) {
        return prefs(context).getLong(KEY_STATUS_DOC + userId, 0);
    }

    public static void setStatusDocumentId(Context context, long userId, long documentId) {
        prefs(context).edit().putLong(KEY_STATUS_DOC + userId, documentId).apply();
    }

    /** Local name-emoji document id for the user, 0 = none. */
    public static long nameEmojiDocumentId(Context context, long userId) {
        return prefs(context).getLong(KEY_NAME_EMOJI_DOC + userId, 0);
    }

    /** The base unicode emoji of the picked name-emoji document,
     *  captured at pick time ("" when unknown). This is what is
     *  appended to the locally rendered name. */
    public static String nameEmojiAlt(Context context, long userId) {
        return prefs(context).getString(KEY_NAME_EMOJI_ALT + userId, "");
    }

    public static void setNameEmoji(Context context, long userId, long documentId, String alt) {
        prefs(context).edit()
                .putLong(KEY_NAME_EMOJI_DOC + userId, documentId)
                .putString(KEY_NAME_EMOJI_ALT + userId, alt == null ? "" : alt)
                .apply();
    }

    public static int nameColorId(Context context, long userId) {
        return prefs(context).getInt(KEY_NAME_COLOR + userId, COLOR_UNSET);
    }

    public static void setNameColorId(Context context, long userId, int colorId) {
        prefs(context).edit().putInt(KEY_NAME_COLOR + userId, colorId).apply();
    }

    public static int profileColorId(Context context, long userId) {
        return prefs(context).getInt(KEY_PROFILE_COLOR + userId, COLOR_UNSET);
    }

    public static void setProfileColorId(Context context, long userId, int colorId) {
        prefs(context).edit().putInt(KEY_PROFILE_COLOR + userId, colorId).apply();
    }

    public static int replyColorId(Context context, long userId) {
        return prefs(context).getInt(KEY_REPLY_COLOR + userId, COLOR_UNSET);
    }

    public static void setReplyColorId(Context context, long userId, int colorId) {
        prefs(context).edit().putInt(KEY_REPLY_COLOR + userId, colorId).apply();
    }

    // ---------------------------------------------------------------

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean getToggle(Context context, String key) {
        if (context == null) {
            return true;
        }
        return prefs(context).getBoolean(key, true);
    }

    private static void setToggle(Context context, String key, boolean value) {
        if (context == null) {
            return;
        }
        prefs(context).edit().putBoolean(key, value).apply();
    }
}
