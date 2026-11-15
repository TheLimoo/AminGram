"""Hook + reflection helpers — AminGram clean-room SDK, M2.

Written from the public exteraGram SDK docs (module list only —
no exteraGram code was used).

- :func:`add_hook` / :func:`add_on_send_message_hook`,
  :class:`HookResult` / :class:`HookStrategy`: re-exported from
  :mod:`base_plugin` so plugins can import them from here too.
- Reflection helpers (:func:`find_class`, :func:`get_field`,
  :func:`set_field`, :func:`call_method`): thin wrappers over
  Chaquopy's Java interop. Every use is logged to the plugin
  (``hook_utils: ...``) — reflection is the dangerous surface,
  and the install-time analysis already flags it. These helpers
  reach plain Java objects only; they do NOT hook methods.
- :func:`hook_method`: NOT implemented in M2. Native / Xposed-
  style before/after interception of arbitrary Telegram methods
  belongs to the M3 DEX bridge; calling it raises a clear
  NotImplementedError instead of pretending to work.
"""

import amin_context

from base_plugin import (
    HookResult,
    HookStrategy,
    add_hook,
    add_on_send_message_hook,
)
from java import jclass

__all__ = [
    "HookResult",
    "HookStrategy",
    "add_hook",
    "add_on_send_message_hook",
    "find_class",
    "get_field",
    "set_field",
    "call_method",
    "hook_method",
]


def _log(what):
    try:
        host = jclass(
            "org.telegram.messenger.amin.pyplugins.PyPluginHost")
        host.log(amin_context.get_current(), "hook_utils: " + what)
    except Exception:
        pass


def find_class(name):
    """Look up a Java class by name (``jclass``). Logged."""
    _log("find_class(%s)" % (name,))
    return jclass(name)


def get_field(obj, name):
    """Read a field (or attribute) of a Java object. Logged."""
    _log("get_field(%s)" % (name,))
    return getattr(obj, name)


def set_field(obj, name, value):
    """Write a field of a Java object. Logged."""
    _log("set_field(%s)" % (name,))
    setattr(obj, name, value)


def call_method(obj, name, *args):
    """Call a method of a Java object by name. Logged."""
    _log("call_method(%s)" % (name,))
    return getattr(obj, name)(*args)


def hook_method(*args, **kwargs):
    raise NotImplementedError(
        "hook_method (native before/after method hooking) is M3 "
        "(DEX bridge). M2 hooks are the AminHooks registry: "
        "add_hook / add_on_send_message_hook.")
