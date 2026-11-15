package com.exteragram.messenger.utils;

/**
 * Compatibility placeholder at exteraGram's literal class name — M0.
 *
 * Some .plugin files and DEX payloads resolve
 * {@code com.exteragram.messenger.utils.AppUtils} by name. This
 * class exists ONLY so the name resolves; it deliberately exposes
 * no methods. Any method call on it fails loudly with
 * {@code NoSuchMethodError} instead of silently doing the wrong
 * thing — M3 decides per method, from the DEX decompile, whether
 * an AminGram equivalent exists. Do not add stub methods that
 * pretend to work.
 *
 * Documented exception to the MODS.md amin.** rule, same as
 * {@code com.exteragram.messenger.plugins.PluginsController}.
 */
public final class AppUtils {

    private AppUtils() {
    }
}
