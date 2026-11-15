package com.exteragram.messenger.utils;

/**
 * Compatibility facade at exteraGram's literal class name — M0
 * placeholder, M3 implements {@code log} from the DEX decompile.
 *
 * Some .plugin files and DEX payloads resolve
 * {@code com.exteragram.messenger.utils.AppUtils} by name. The
 * MaterialSettingsList DEX (org.zwylair.msldex.PluginCore) calls
 * {@code AppUtils.log(String, Throwable)} for every DEX-side log
 * line, so M3 implements exactly that overload (plus a 1-arg
 * convenience overload). It writes to logcat under tag
 * {@code AminGramDEX} — the DEX carries no plugin-id context, so
 * the line cannot be attributed to the hub per-plugin log; the
 * plugin's own Python logger (BasePlugin.logger) is the one that
 * lands in the hub log.
 *
 * No other AppUtils method is added: a method the DEX does not
 * call must not exist to be called by accident. Do not add stub
 * methods that pretend to work.
 *
 * Documented exception to the MODS.md amin.** rule, same as
 * {@code com.exteragram.messenger.plugins.PluginsController}.
 */
public final class AppUtils {

    private AppUtils() {
    }

    /** DEX-side log line (M3): {@code PluginCore.log(String[],
     *  Throwable)} joins its parts and calls this. Logcat only. */
    public static void log(String message, Throwable throwable) {
        String text = message == null ? "" : message;
        if (throwable == null) {
            android.util.Log.i("AminGramDEX", text);
        } else {
            android.util.Log.i("AminGramDEX", text, throwable);
        }
    }

    /** 1-arg form for callers that have no throwable. */
    public static void log(String message) {
        log(message, null);
    }
}
