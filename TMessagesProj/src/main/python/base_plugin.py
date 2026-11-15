"""Clean-room BasePlugin — AminGram Python plugin SDK, M0.

Written from the public exteraGram plugin SDK docs
(plugins.exteragram.app/docs): a plugin is exactly one class
inheriting from BasePlugin, with literal metadata constants
(__id__, __name__, ...) and the lifecycle below. No exteraGram
or exteraless code was used.

M0 surface: on_plugin_load / on_plugin_unload, create_settings,
get_setting / set_setting, self.logger, AppEvent constants.
Hook registration (add_hook), menus and create_settings rendering
are M1/M2; a plugin that calls them in M0 gets a clear
NotImplementedError instead of a silent no-op.
"""

import json
import logging

from java import jclass


class AppEvent:
    START = "start"
    STOP = "stop"
    PAUSE = "pause"
    RESUME = "resume"


class BasePlugin:
    """Base class for every .plugin file.

    The host (amin_loader) instantiates the single subclass found
    in the module with no arguments, then calls on_plugin_load.
    """

    # Metadata defaults (the host's Java parser reads the literals;
    # these attributes are the runtime-visible copies).
    __id__ = None
    __name__ = None
    __description__ = ""
    __author__ = ""
    __version__ = "1.0"
    __icon__ = ""
    __app_version__ = ""
    __sdk_version__ = ""
    __category__ = []

    def __init__(self):
        # Set by amin_loader right after instantiation.
        self._plugin_id = None
        self._logger = None

    @property
    def logger(self):
        """Writes to logcat under plugin.<id> and to the hub log."""
        if self._logger is None:
            name = "plugin." + (self._plugin_id or "unknown")
            self._logger = logging.getLogger(name)
        return self._logger

    def _log(self, message):
        try:
            host = jclass(
                "org.telegram.messenger.amin.pyplugins.PyPluginHost")
            host.log(self._plugin_id, str(message))
        except Exception:
            pass

    # -- lifecycle (override in the plugin) -------------------------

    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    def create_settings(self):
        """M1: return a list of settings rows for the plugin page.
        M0: the host ignores the return value."""
        return []

    # -- settings persistence ---------------------------------------

    def get_setting(self, key, default=None):
        try:
            settings = jclass(
                "org.telegram.messenger.amin.pyplugins.PyPluginSettings")
            raw = settings.getSetting(self._plugin_id, str(key), None)
            if raw is None:
                return default
            try:
                return json.loads(raw)
            except Exception:
                return raw
        except Exception:
            return default

    def set_setting(self, key, value, reload_settings=False):
        try:
            settings = jclass(
                "org.telegram.messenger.amin.pyplugins.PyPluginSettings")
            if isinstance(value, str):
                raw = value
            else:
                raw = json.dumps(value)
            settings.setSetting(self._plugin_id, str(key), raw)
        except Exception:
            pass

    def export_settings(self):
        return {}

    def import_settings(self, data):
        pass

    # -- M1/M2 surface: present, but loudly unimplemented in M0 -----

    def add_hook(self, *args, **kwargs):
        raise NotImplementedError(
            "add_hook is M2 (hook registry). This plugin needs a newer "
            "AminGram Python engine.")

    def add_on_send_message_hook(self, *args, **kwargs):
        raise NotImplementedError(
            "add_on_send_message_hook is M2 (hook registry).")
