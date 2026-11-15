"""Host-side loader for .plugin files — AminGram clean-room, M0.

Called from Java (PyPluginsController) only. Imports a .plugin
file as a module, finds the BasePlugin subclass, instantiates it.

Subclass discovery rule (documented heuristic — the real
exteraGram rule is not public; its engine source is closed):
exactly one subclass -> it; several -> the LAST defined in
module dict order. That makes MaterialSettingsList resolve to
MaterialSettingsIcons (defined after the inert EmptyPlugin
fallback), which is the intended behaviour for that file.
M3 must verify this rule against real exteraGram behaviour.
"""

import importlib.util
import sys
from importlib.machinery import SourceFileLoader


def load_plugin(file_path, module_name):
    """Import the .plugin file and return a BasePlugin instance."""
    from base_plugin import BasePlugin

    # .plugin is not a known Python suffix, so spec_from_file_location
    # would return a spec with loader=None; pass SourceFileLoader
    # explicitly (caught by the CPython stub test 2026-11-15 — without
    # this, no .plugin file loads at all).
    loader = SourceFileLoader(module_name, file_path)
    spec = importlib.util.spec_from_file_location(module_name, file_path, loader=loader)
    if spec is None or spec.loader is None:
        raise ImportError("cannot load plugin file: " + file_path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    try:
        spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(module_name, None)
        raise

    candidates = []
    for value in vars(module).values():
        if (
            isinstance(value, type)
            and issubclass(value, BasePlugin)
            and value is not BasePlugin
            # NOTE: do NOT filter on __module__ == module_name. Every
            # real plugin defines __name__ metadata ("HelloPy", ...),
            # which overwrites the module globals' __name__ before
            # the class statement runs, so in-file classes end up
            # with __module__ == the metadata value, not the module
            # name. Only the SDK's own BasePlugin is excluded (it
            # lives in base_plugin). A subclass imported from a
            # helper module would also match; M0 accepts that.
            and getattr(value, "__module__", None) != "base_plugin"
        ):
            candidates.append(value)
    if not candidates:
        sys.modules.pop(module_name, None)
        raise ImportError(
            "no BasePlugin subclass found in " + file_path)
    # Several subclasses: last defined wins (see module docstring).
    plugin_class = candidates[-1]
    instance = plugin_class()
    # The Java side sets instance._plugin_id to the authoritative
    # metadata id right after load (module names cannot carry '-').
    return instance


def unload_plugin(module_name):
    """Drop a loaded plugin module so a reinstall re-imports it."""
    sys.modules.pop(module_name, None)


def has_method(instance, name):
    """True when the instance defines a callable attribute."""
    try:
        return callable(getattr(instance, name, None))
    except Exception:
        return False


def call_optional(instance, name, arg=None):
    """Call instance.<name>(arg) if it exists; return its result,
    or None when the method is absent. Lets Java dispatch M0
    provisional hooks (on_message / on_command) without knowing
    which plugins define them."""
    try:
        method = getattr(instance, name, None)
    except Exception:
        return None
    if not callable(method):
        return None
    if arg is None:
        return method()
    return method(arg)
