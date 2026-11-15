"""Clean-room BasePlugin — AminGram Python plugin SDK, M0.

Written from the public exteraGram plugin SDK docs
(plugins.exteragram.app/docs): a plugin is exactly one class
inheriting from BasePlugin, with literal metadata constants
(__id__, __name__, ...) and the lifecycle below. No exteraGram
or exteraless code was used.

M0 surface: on_plugin_load / on_plugin_unload, create_settings,
get_setting / set_setting, self.logger, AppEvent constants.
M1 adds: create_settings rendering (see the method), real
export/import_settings, set_setting(reload_settings=True).
Hook registration (add_hook), menus and get_last_fragment are
M2; a plugin that calls them gets a clear NotImplementedError
instead of a silent no-op.
"""

import json
import logging

import amin_context

from java import jclass


class AppEvent:
    START = "start"
    STOP = "stop"
    PAUSE = "pause"
    RESUME = "resume"


class HookStrategy:
    """Verdicts a send-message hook may return (M2)."""
    DEFAULT = "default"        # send the message unchanged
    CANCEL = "cancel"          # suppress the message entirely
    MODIFY = "modify"          # rewrite the text, later hooks see it
    MODIFY_FINAL = "modify_final"  # rewrite and stop the chain


class HookResult:
    """Structured return value for a send-message hook.

    A hook may also return a plain string (the strategy) or a
    dict ({"strategy": ..., "text": ...}); all three forms are
    understood by the host. Returning None means DEFAULT.
    """

    def __init__(self, strategy=HookStrategy.DEFAULT, text=None):
        self.strategy = strategy
        self.text = text

    def to_dict(self):
        out = {"strategy": self.strategy}
        if self.text is not None:
            out["text"] = self.text
        return out


def _registry():
    return jclass(
        "org.telegram.messenger.amin.pyplugins.PyHookRegistry")


def _warn_outside_context(what):
    """Log that a registration happened with no plugin context
    (module import time): it is registered under "engine", is
    not unloaded with any plugin, and is almost certainly a
    mistake — registration belongs in on_plugin_load or behind
    a decorator."""
    try:
        host = jclass(
            "org.telegram.messenger.amin.pyplugins.PyPluginHost")
        host.log("engine",
                 what + " outside a plugin context (import time?) — "
                 "registered under 'engine', not unloaded with a plugin")
    except Exception:
        pass


def add_hook(name, callback=None, match_substring=False, priority=0):
    """Register a named hook (M2, observe-only).

    Three forms::

        add_hook("message", self.on_msg)          # direct
        @add_hook("message")                       # decorator
        def on_msg(self, data): ...
        @add_hook                                  # bare decorator
        def message(self, data): ...              # name = method name

    The decorator forms only tag the method (``_amin_hook``); the
    host registers it when the plugin loads. The direct form
    registers immediately. ``match_substring`` fires the hook when
    the event name contains the registered name. Hooks run by
    priority, highest first. Named hooks cannot cancel or modify
    anything — by the time they run, the event has happened; a
    non-default return value is logged and ignored. For
    intercepting outgoing messages use
    :func:`add_on_send_message_hook`.
    """
    if callable(name):
        # Bare @add_hook with no name: the method name is the hook.
        fn = name
        fn._amin_hook = (fn.__name__, False, 0)
        return fn
    if isinstance(callback, bool):
        match_substring = callback
        callback = None
    if callback is None:
        def decorator(fn):
            fn._amin_hook = (name, match_substring, priority)
            return fn
        return decorator
    if amin_context.get_current() == "engine":
        _warn_outside_context("add_hook(%r)" % (name,))
    _registry().registerHook(
        amin_context.get_current(), str(name), bool(match_substring),
        int(priority), callback)
    return callback


def add_on_send_message_hook(callback=None, priority=0):
    """Register a send-message hook (M2).

    Three forms, like :func:`add_hook`: direct
    (``add_on_send_message_hook(self.on_send)``), decorator with
    parens (``@add_on_send_message_hook(priority=1)``), and bare
    decorator (``@add_on_send_message_hook``). The bare form is
    ambiguous with the direct form — it is a decorator when
    called with no plugin context set (class-definition / import
    time) and a direct registration otherwise. The callback
    receives ``{"text", "dialog_id", "account"}`` and returns a
    strategy: a string, a dict, or a :class:`HookResult`. The
    send path waits for the verdict, bounded by 800 ms — a
    timeout or a throwing hook means DEFAULT. See PyHookRegistry
    for the full contract (a hook must not send a message
    synchronously from inside the callback).
    """
    if callback is None:
        def decorator(fn):
            fn._amin_send_hook = (priority,)
            return fn
        return decorator
    if callable(callback) and amin_context.get_current() == "engine":
        # Bare @add_on_send_message_hook at class-definition time:
        # tag it for load-time registration instead of registering
        # under "engine" (caught by the M2 stub tests — without
        # this, the hook would fire under the wrong plugin id and
        # never be unregistered on unload).
        callback._amin_send_hook = (priority,)
        return callback
    if amin_context.get_current() == "engine":
        _warn_outside_context("add_on_send_message_hook")
    _registry().registerSendHook(
        amin_context.get_current(), int(priority), callback)
    return callback


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
        """Return a list of settings rows for the plugin page (M1).

        Each row is a dict or a ``ui.settings`` row object:
        header / switch / selector / input / edittext / text /
        divider / custom. Dict form, e.g.::

            {"type": "switch", "key": "enabled",
             "text": "Enable", "default": True}
            {"type": "selector", "key": "mode", "text": "Mode",
             "options": ["fast", "slow"], "default": "fast"}
            {"type": "input", "key": "name", "text": "Name",
             "default": "", "hint": "..."}

        Rows are read from the plugin's stored settings (see
        get_setting); a row's ``default`` is used only when no
        value is stored yet. Changing a row on the page persists
        it and runs its ``on_change`` (when one was given) plus
        ``on_settings_changed(key)`` when the plugin defines it.
        """
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
            if reload_settings:
                host = jclass(
                    "org.telegram.messenger.amin.pyplugins.PyPluginHost")
                host.requestSettingsReload(self._plugin_id)
        except Exception:
            pass

    def export_settings(self):
        """All stored settings as a plain dict (values JSON-decoded
        when possible, so a bool stays a bool)."""
        try:
            settings = jclass(
                "org.telegram.messenger.amin.pyplugins.PyPluginSettings")
            raw = settings.getAllSettingsJson(self._plugin_id)
            data = json.loads(raw) if raw else {}
            out = {}
            for key, value in data.items():
                try:
                    out[key] = json.loads(value)
                except Exception:
                    out[key] = value
            return out
        except Exception:
            return {}

    def import_settings(self, data):
        """Write every entry of a dict (as from export_settings)
        then reload the settings page once, if it is open."""
        if not isinstance(data, dict):
            return
        try:
            for key, value in data.items():
                self.set_setting(key, value, reload_settings=False)
            if data:
                host = jclass(
                    "org.telegram.messenger.amin.pyplugins.PyPluginHost")
                host.requestSettingsReload(self._plugin_id)
        except Exception:
            pass

    # -- hooks + menus (M2) -----------------------------------------

    def add_hook(self, name, callback=None, match_substring=False,
                 priority=0):
        """See module-level :func:`add_hook`."""
        return add_hook(name, callback, match_substring, priority)

    def add_on_send_message_hook(self, callback=None, priority=0):
        """See module-level :func:`add_on_send_message_hook`."""
        return add_on_send_message_hook(callback, priority)

    def add_menu_item(self, item):
        """Register a :class:`ui.menu.MenuItemData` with the host.
        Returns the item's ordinal within this plugin's items for
        its location. Items for locations with no hook point yet
        (drawer / main / profile menus) are stored but never shown
        until a later milestone adds the hook points."""
        from ui.menu import MenuItemData

        if not isinstance(item, MenuItemData):
            raise TypeError("add_menu_item needs a MenuItemData")
        return _registry().registerMenuItem(
            self._plugin_id, item.location, item.text,
            item.condition, item.icon, item.on_click)

    def remove_menu_item(self, location, text):
        """Remove the first menu item of this plugin with the
        given location and text. No-op when there is none."""
        _registry().removeMenuItem(self._plugin_id, location, text)

    def on_menu_action(self, location, data):
        """Called when a menu item without an ``on_click`` callback
        is tapped. Override in the plugin. ``data`` is a dict with
        ``location``, ``plugin_id`` and (for context-menu items)
        ``chat_id``, ``message_id``, ``text``, ``out``."""
        pass

    # -- M2 surface: present, but loudly unimplemented --------------

    def _not_implemented(self, name):
        raise NotImplementedError(
            "%s is not implemented yet (a later milestone)." % name)
