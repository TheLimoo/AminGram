package com.exteragram.messenger.plugins;

import org.telegram.messenger.amin.pyplugins.PyPlugin;
import org.telegram.messenger.amin.pyplugins.PyPluginsController;
import org.telegram.messenger.ApplicationLoader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compatibility facade at exteraGram's literal class name — M0.
 *
 * Plugin code does
 * {@code jclass("com.exteragram.messenger.plugins.PluginsController")}
 * and then {@code .getInstance().plugins.get(__id__).setError(...)} /
 * {@code .setEnabled(false)} (the ZwyLib-missing {@code force_stop}
 * path in MaterialSettingsList does exactly this). Those strings
 * must resolve at runtime or the plugin's error path throws.
 *
 * This class contains NO plugin logic of its own: every call
 * delegates 1:1 to {@code amin.pyplugins.PyPluginsController}.
 * It is the one documented exception to the MODS.md rule that all
 * custom code lives under {@code org.telegram.messenger.amin.**};
 * no further code may be added under {@code com.exteragram.*}
 * except facades like this one.
 */
public final class PluginsController {

    private static volatile PluginsController instance;

    /** Live plugin handles, keyed by plugin id. A plugin marks
     *  itself errored / disabled through its own handle. */
    public final Map<String, Plugin> plugins = new ConcurrentHashMap<>();

    private PluginsController() {
    }

    public static PluginsController getInstance() {
        if (instance == null) {
            synchronized (PluginsController.class) {
                if (instance == null) {
                    instance = new PluginsController();
                }
            }
        }
        return instance;
    }

    /** A live plugin, as seen by plugin code. Only the two methods
     *  the sample plugins use are exposed. */
    public static final class Plugin {
        private final String pluginId;

        public Plugin(String pluginId) {
            this.pluginId = pluginId;
        }

        public String getId() {
            return pluginId;
        }

        /** Marks this plugin errored and disables it (exteraGram
         *  {@code setError} semantics). */
        public void setError(Throwable error) {
            try {
                PyPluginsController.setError(
                        ApplicationLoader.applicationContext, pluginId, error);
            } catch (Throwable ignored) {
            }
        }

        /** Enables / disables this plugin. */
        public void setEnabled(boolean enabled) {
            try {
                PyPlugin plugin = PyPluginsController.get(
                        ApplicationLoader.applicationContext, pluginId);
                if (plugin != null) {
                    PyPluginsController.setEnabled(
                            ApplicationLoader.applicationContext, plugin, enabled);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Returns the handle for an installed plugin, creating it on
     *  first use; null when the id is not installed. */
    public Plugin getPlugin(String pluginId) {
        if (pluginId == null) {
            return null;
        }
        try {
            PyPlugin plugin = PyPluginsController.get(
                    ApplicationLoader.applicationContext, pluginId);
            if (plugin == null) {
                return null;
            }
            Plugin handle = plugins.get(pluginId);
            if (handle == null) {
                handle = new Plugin(pluginId);
                plugins.put(pluginId, handle);
            }
            return handle;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Opens a plugin's settings page (M1: wired to
     *  AminPyPluginActivity via PyPluginsController). */
    public void openPluginSettings(String pluginId) {
        try {
            PyPluginsController.openPluginSettings(
                    ApplicationLoader.applicationContext, pluginId);
        } catch (Throwable ignored) {
        }
    }
}
