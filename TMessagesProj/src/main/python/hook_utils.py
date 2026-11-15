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
- :func:`hook_method`: M3 — before/after interception, but ONLY
  for the three SettingsActivity methods the DEX bridge hooks
  (see below). Any other target raises a clear
  NotImplementedError instead of pretending to work: the shim
  is source-level notification, not native hooking, and there is
  no source-level point for arbitrary methods.
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


def hook_method(class_name, method_name, before=None, after=None):
    """Hook a method with before/after callables — M3, LIMITED
    to the three SettingsActivity methods the DEX bridge hooks:

    - ``("org.telegram.ui.SettingsActivity", "fillItems")``
    - ``("org.telegram.ui.SettingsActivity$SettingCell$Factory",
        "bindView")``
    - ``("org.telegram.ui.SettingsActivity$SettingCell",
        "updateColors")``

    ``before`` / ``after`` (either may be None) receive the
    ``MethodHookParam`` for the call (``.thisObject`` / ``.args``;
    for ``fillItems`` ``args[0]`` is the ``ArrayList<UItem>`` being
    filled, for ``bindView`` ``args[0]`` is the SettingCell view
    and ``args[1]`` the UItem, for ``updateColors`` ``thisObject``
    is the cell). Returns the Java Unhook (call ``.unhook()`` to
    remove the hook); the host also removes it when the plugin
    unloads. Any other class/method raises NotImplementedError:
    there is no source-level hook point for it, and AminGram does
    not bundle native hooking. A throwing callback is logged by
    the host and the settings call continues.

    Chaquopy interop (``PyObject.call(param)`` on the Java side)
    is unproven on device until M3's phone test; the CPython
    stub tests cover the routing and the error paths only.
    """
    points = {
        ("org.telegram.ui.SettingsActivity", "fillItems"): "fillItems",
        ("org.telegram.ui.SettingsActivity$SettingCell$Factory",
         "bindView"): "bindView",
        ("org.telegram.ui.SettingsActivity$SettingCell",
         "updateColors"): "updateColors",
    }
    point = points.get((class_name, method_name))
    if point is None:
        raise NotImplementedError(
            "hook_method: no hook point for %s#%s — AminGram only "
            "hooks fillItems, bindView and updateColors in "
            "SettingsActivity (M3 DEX bridge). Arbitrary method "
            "hooking would need native code, which is not bundled."
            % (class_name, method_name))
    registry = jclass(
        "org.telegram.messenger.amin.pyplugins.PySettingsHookRegistry")
    return registry.hookByName(amin_context.get_current(), point,
                               before, after)
