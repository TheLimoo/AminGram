package org.telegram.messenger.amin.modules;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

/**
 * AyuForward — allows forwarding, copying, and saving messages and media from
 * restricted ("no-forward" / {@code noforwards}) channels and groups by
 * re-sending them as a fresh user-owned copy.
 *
 * When a source chat or message has {@code noforwards} set, Telegram's server
 * rejects {@code TL_messages_forwardMessages}. AyuForward bypasses the client
 * UI gates and routes restricted forwards through a local copy-and-resend flow:
 * text and entities are re-posted directly, and downloaded media files in local
 * cache are re-uploaded as fresh media messages. No server state is faked.
 */
public class AyuForwardModule implements ModModule {

    private static final String PREFS = "amingram_plugins";
    public static final String KEY_REUPLOAD_MEDIA = "ayu_forward_reupload_media";
    public static final String KEY_UNLOCK_COPY_SAVE = "ayu_forward_unlock_copy_save";

    @Override
    public String id() {
        return "ayu_forward";
    }

    @Override
    public String title() {
        return "AyuForward";
    }

    @Override
    public String description() {
        return "Forward, copy, or save messages and media from restricted (“no-forward”) channels and groups. Restricted posts are re-sent as your own copy (downloaded media is re-uploaded, text is re-posted; no server state is faked).";
    }

    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public int section() {
        return SECTION_PRIVACY;
    }

    @Override
    public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        boolean media = reuploadMedia(context);
        boolean copySave = unlockCopySave(context);
        if (media && copySave) {
            return "Active — restricted copy, save & media re-upload";
        } else if (media) {
            return "Active — forward re-upload only";
        } else if (copySave) {
            return "Active — text copy & gallery save";
        }
        return "Active — text forward only";
    }

    public static boolean reuploadMedia(Context context) {
        if (context == null) {
            return true;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_REUPLOAD_MEDIA, true);
    }

    public static void setReuploadMedia(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_REUPLOAD_MEDIA, enabled)
                .apply();
    }

    public static boolean unlockCopySave(Context context) {
        if (context == null) {
            return true;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_UNLOCK_COPY_SAVE, true);
    }

    public static void setUnlockCopySave(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_UNLOCK_COPY_SAVE, enabled)
                .apply();
    }
}
