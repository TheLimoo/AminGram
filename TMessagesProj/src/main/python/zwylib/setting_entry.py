"""SettingEntry — one plugin setting backed by the host store.

Clean-room re-implementation of the ZwyLib API surface used by
MaterialSettingsList v1.1.1 (M3). Written from that plugin's
usage only — no ZwyLib code, repo or licence was available, so
this is a compatible re-implementation, not a copy. The real
ZwyLib is NOT bundled, re-hosted or redistributed: this package
ships in AminGram itself, and installing a real ``zwylib.plugin``
is unnecessary (the host package wins on ``sys.path`` — see the
package docstring).

An entry is created at module import time and read later, so
``get()`` resolves the plugin id at call time from
:mod:`amin_context` (set by the host around every plugin call),
never at construction time.
"""

import json

import amin_context

from java import jclass


def _settings():
    return jclass(
        "org.telegram.messenger.amin.pyplugins.PyPluginSettings")


def _wrap_on_change(entry, on_change):
    """Wrap a row's on_change so the entry is stored BEFORE the
    plugin's callback runs: the callback (e.g. the reorder
    lambda in MaterialSettingsList) reads ``entry.get()``, so it
    must already see the new value. Idempotent with the Java
    side, which also writes the value before dispatching the
    callback — storing twice stores the same value."""
    def _wrapped(value):
        try:
            entry.set(value)
        except Exception:
            pass
        if callable(on_change):
            return on_change(value)
        return None
    return _wrapped


class SettingEntry:
    """A named setting with a default, persisted per plugin.

    Values are JSON-encoded in the host store (the same store
    :class:`base_plugin.BasePlugin` uses), so a bool stays a bool
    and survives restarts.
    """

    def __init__(self, key, default=False):
        self.key = str(key)
        self.default = default

    def get(self):
        """The stored value, or the default when nothing is stored
        (or the host is unreachable — CPython stub tests exercise
        the default path)."""
        try:
            raw = _settings().getSetting(
                amin_context.get_current(), self.key, None)
            if raw is None:
                return self.default
            try:
                return json.loads(raw)
            except Exception:
                return raw
        except Exception:
            return self.default

    def set(self, value, reload_settings=False):
        """Store a value (same shapes as BasePlugin.set_setting)."""
        try:
            raw = value if isinstance(value, str) else json.dumps(value)
            _settings().setSetting(
                amin_context.get_current(), self.key, raw)
            if reload_settings:
                host = jclass(
                    "org.telegram.messenger.amin.pyplugins.PyPluginHost")
                host.requestSettingsReload(amin_context.get_current())
        except Exception:
            pass

    def as_switch(self, text, on_change=None, link_alias=None):
        """A settings-page switch row for this entry, in the dict
        shape the M1 serializer accepts (``type`` / ``key`` /
        ``text`` / ``default`` / ``on_change``).

        ``link_alias`` is exteraGram's deep-link alias for the
        row; AminGram has no settings deep-links, so it is
        accepted and ignored."""
        return {
            "type": "switch",
            "key": self.key,
            "text": str(text),
            "default": self.default,
            "on_change": _wrap_on_change(self, on_change),
        }
