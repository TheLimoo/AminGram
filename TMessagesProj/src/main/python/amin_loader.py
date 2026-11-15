"""Host-side loader for .plugin files — AminGram clean-room, M1.

Called from Java (PyPluginsController) only. Imports a .plugin
file as a module, finds the BasePlugin subclass, instantiates it.

Subclass discovery rule (documented heuristic — the real
exteraGram rule is not public; its engine source is closed):
exactly one subclass -> it; several -> the LAST defined in
module dict order. That makes MaterialSettingsList resolve to
MaterialSettingsIcons (defined after the inert EmptyPlugin
fallback), which is the intended behaviour for that file.
M3 must verify this rule against real exteraGram behaviour.

M1 additions: settings serialisation (``serialize_settings`` —
the plugin page's ``create_settings()`` rows, normalised to JSON
so the Java side never has to walk PyObjects), the per-row
``on_change`` callback registry, and ``amin_context`` tracking
so module-level SDK helpers know which plugin is calling them.
"""

import importlib.util
import json
import sys
from importlib.machinery import SourceFileLoader

import amin_context

# (plugin_id, setting_key) -> on_change callable, registered when
# a create_settings() row carrying one is serialised. The Java
# side invokes them after a user changes a row on the plugin page.
_setting_callbacks = {}


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


def _plugin_id_of(instance):
    try:
        return getattr(instance, "_plugin_id", None) or "engine"
    except Exception:
        return "engine"


def call_lifecycle(instance, name):
    """Call a lifecycle method (on_plugin_load / on_plugin_unload)
    with the current-plugin context set. Java must use this
    instead of calling the method on the instance directly, or
    module-level SDK helpers called from inside the lifecycle
    method would log under the wrong plugin."""
    previous = amin_context.set_current(_plugin_id_of(instance))
    try:
        method = getattr(instance, name, None)
        if callable(method):
            return method()
        return None
    finally:
        amin_context.set_current(previous)


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
    previous = amin_context.set_current(_plugin_id_of(instance))
    try:
        method = getattr(instance, name, None)
    except Exception:
        return None
    if not callable(method):
        return None
    try:
        if arg is None:
            return method()
        return method(arg)
    finally:
        amin_context.set_current(previous)


# ------------------------------------------------------------------
# Settings (M1)
# ------------------------------------------------------------------

def _normalise_options(options):
    """Selector options -> [{"value": ..., "label": ...}]. Accepts
    strings, {"value","label"} dicts and (value, label) pairs."""
    out = []
    for opt in options or []:
        if isinstance(opt, dict):
            value = opt.get("value", opt.get("text", ""))
            label = opt.get("label", opt.get("text", value))
            out.append({"value": str(value), "label": str(label)})
        elif isinstance(opt, (list, tuple)) and len(opt) >= 2:
            out.append({"value": str(opt[0]), "label": str(opt[1])})
        else:
            out.append({"value": str(opt), "label": str(opt)})
    return out


def _normalise_row(row, plugin_id, warnings):
    """One create_settings() row (dict or row object) -> a plain
    dict the Java side can render. Returns None (with a warning)
    when the row is unusable — one bad row must not kill the page."""
    data = None
    if isinstance(row, dict):
        data = dict(row)
    elif isinstance(row, (str, bytes, int, float, bool, list, tuple, set)):
        # A bare string / number / list is not a row. (Caught by a
        # test: str.title is a method, so without this guard a
        # string row was "normalised" into garbage.)
        warnings.append("unsupported settings row value: %r" % (row,))
        return None
    else:
        # Row object (ui.settings classes): read attributes,
        # skipping callables (e.g. a .title method is not a title).
        attrs = {}
        for name in ("type", "kind", "key", "text", "title", "label",
                     "value", "default", "options", "hint",
                     "description", "input_type", "min", "max", "step"):
            if hasattr(row, name):
                value = getattr(row, name)
                if not callable(value):
                    attrs[name] = value
        if not attrs:
            warnings.append("unrecognised settings row: %r" % (row,))
            return None
        data = attrs
        if "type" not in data:
            cls = type(row).__name__.lower()
            if cls.endswith("row"):
                cls = cls[:-3]
            data["type"] = cls
    row_type = str(data.get("type") or data.get("kind") or "").lower()
    if not row_type:
        warnings.append("settings row without a type: %r" % (row,))
        return None
    out = {"type": row_type}
    if data.get("key") is not None:
        out["key"] = str(data["key"])
    text = data.get("text", data.get("title", data.get("label", "")))
    out["text"] = "" if text is None else str(text)
    if "default" in data:
        out["default"] = data["default"]
    elif "value" in data:
        out["default"] = data["value"]
    if data.get("options") is not None:
        out["options"] = _normalise_options(data["options"])
    for field in ("hint", "description", "input_type"):
        if data.get(field) is not None:
            out[field] = str(data[field])
    for field in ("min", "max", "step"):
        if data.get(field) is not None:
            out[field] = data[field]
    # on_change is a callable: it cannot travel in JSON, so it is
    # registered here, keyed by (plugin, setting key).
    on_change = None
    if isinstance(row, dict):
        on_change = row.get("on_change")
    else:
        on_change = getattr(row, "on_change", None)
    if callable(on_change) and out.get("key"):
        _setting_callbacks[(plugin_id, out["key"])] = on_change
    return out


def serialize_settings(instance):
    """Call instance.create_settings() and return its rows as a
    JSON string: {"rows": [...], "warnings": [...]}. Raises when
    create_settings() itself raises — the Java side logs it and
    shows an error on the page instead of fake rows."""
    plugin_id = _plugin_id_of(instance)
    previous = amin_context.set_current(plugin_id)
    try:
        rows = instance.create_settings()
    finally:
        amin_context.set_current(previous)
    if rows is None:
        return json.dumps({"rows": [], "warnings": []})
    warnings = []
    out = []
    try:
        iterable = list(rows)
    except Exception:
        raise TypeError("create_settings() must return a list of rows")
    for row in iterable:
        try:
            norm = _normalise_row(row, plugin_id, warnings)
        except Exception as exc:
            warnings.append("row failed to serialise: %s" % (exc,))
            norm = None
        if norm is not None:
            out.append(norm)
    return json.dumps({"rows": out, "warnings": warnings})


def invoke_setting_callback(plugin_id, key, json_value):
    """Run a row's registered on_change callable with the new
    value (JSON-decoded when possible). No-op when no callback
    was registered for (plugin, key). Raises on callback failure
    so the Java side can log / record it."""
    previous = amin_context.set_current(plugin_id)
    try:
        callback = _setting_callbacks.get((plugin_id, key))
        if callback is None:
            return None
        try:
            value = json.loads(json_value)
        except Exception:
            value = json_value
        return callback(value)
    finally:
        amin_context.set_current(previous)
