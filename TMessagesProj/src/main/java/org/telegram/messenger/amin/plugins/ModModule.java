package org.telegram.messenger.amin.plugins;

import android.content.Context;

/**
 * A feature module of the AminGram mod layer.
 * Modules are self-contained: they declare a manifest (id, title,
 * description) and register themselves in {@link PluginRegistry}.
 * Behavior hooks into upstream code are added per-module and recorded
 * in MODS.md at the repo root.
 *
 * v1.3: modules also declare a hub section and an optional live status
 * line (shown under the switch in the Plugins hub).
 */
public interface ModModule {

    int SECTION_PRIVACY = 0;
    int SECTION_MEDIA = 1;
    int SECTION_TOOLS = 2;

    String id();
    String title();
    String description();
    boolean enabledByDefault();

    /** Hub section: one of SECTION_PRIVACY / SECTION_MEDIA / SECTION_TOOLS. */
    default int section() {
        return SECTION_TOOLS;
    }

    /** Optional one-line live status ("" = no status). Called on the UI
     *  thread while the hub binds rows; must be cheap (prefs reads only). */
    default String status(Context context) {
        return "";
    }
}
