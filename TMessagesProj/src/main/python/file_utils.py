"""Clean-room file_utils — AminGram Python plugin SDK, M1.

Sandboxed file access: a plugin may only read/write inside its
OWN directory (``filesDir/pyplugins/<id>/files``), never anywhere
else on the device. Paths are validated here in Python AND the
root is supplied by the Java host, which validates the plugin id.

- Absolute paths, ``..`` components and drive prefixes are
  rejected with ValueError (loud, not silent).
- Files are text (UTF-8) unless a bytes API is used.
- No size cap is enforced here; the directory is app-private
  and the host may prune it. Keep plugin data small.

The raw ``open()`` builtin is NOT removed (the Python engine
has no sandbox), so this module is a convenience + a convention
for well-behaved plugins — the install-time analysis flags
``open(`` / ``os.remove`` / ``pathlib`` use in a plugin's source.
"""

import os

import amin_context

from java import jclass


def _host():
    return jclass("org.telegram.messenger.amin.pyplugins.PyPluginHost")


def files_dir():
    """Absolute path of this plugin's private files directory."""
    root = _host().pluginFilesDir(amin_context.get_current())
    if not root:
        raise RuntimeError("no files directory for this plugin")
    return root


def _safe(name):
    if not name or not isinstance(name, str):
        raise ValueError("file name must be a non-empty string")
    if os.path.isabs(name) or ":" in name.split("/")[0]:
        raise ValueError("absolute paths are not allowed: " + name)
    parts = name.replace("\\", "/").split("/")
    if any(p in ("", ".", "..") for p in parts):
        raise ValueError("path escapes the plugin directory: " + name)
    root = os.path.realpath(files_dir())
    path = os.path.realpath(os.path.join(root, *parts))
    if os.path.commonpath((root, path)) != root:
        raise ValueError("path escapes the plugin directory: " + name)
    return path


def read_text(name, default=None):
    try:
        with open(_safe(name), "r", encoding="utf-8") as f:
            return f.read()
    except FileNotFoundError:
        return default


def write_text(name, text):
    path = _safe(name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def read_bytes(name, default=None):
    try:
        with open(_safe(name), "rb") as f:
            return f.read()
    except FileNotFoundError:
        return default


def write_bytes(name, data):
    path = _safe(name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def exists(name):
    try:
        return os.path.exists(_safe(name))
    except ValueError:
        return False


def delete(name):
    os.remove(_safe(name))


def list_files():
    root = files_dir()
    if not os.path.isdir(root):
        return []
    return sorted(
        p for p in os.listdir(root)
        if os.path.isfile(os.path.join(root, p))
    )
