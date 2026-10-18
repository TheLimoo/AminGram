package org.telegram.messenger.amin.plugins;

/**
 * A feature module of the AminGram mod layer.
 * Modules are self-contained: they declare a manifest (id, title,
 * description) and register themselves in {@link PluginRegistry}.
 * Behavior hooks into upstream code are added per-module and recorded
 * in MODS.md at the repo root.
 */
public interface ModModule {
    String id();
    String title();
    String description();
    boolean enabledByDefault();
}
