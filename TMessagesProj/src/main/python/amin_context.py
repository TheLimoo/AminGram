"""amin_context — current-plugin tracking for the AminGram Python SDK, M1.

The Python runtime is SHARED by all plugins (same as exteraGram:
no isolation), so module-level SDK helpers such as
``client_utils.run_on_queue`` or ``ui.bulletin.BulletinHelper``
cannot know which plugin is calling them from the call site alone.
``amin_loader`` sets the current plugin id before invoking any
plugin method (load / unload / create_settings / dispatch /
settings callbacks) and restores the previous value afterwards;
SDK modules read it via :func:`get_current`.

The value is best-effort: code running outside a plugin call
(e.g. at import time) sees ``"engine"``. It is never trusted for
security — the Java side always re-derives the plugin id from
its own registry when persisting anything.
"""

_current = {"plugin_id": "engine"}


def set_current(plugin_id):
    """Set the current plugin id; returns the previous value."""
    previous = _current["plugin_id"]
    _current["plugin_id"] = plugin_id or "engine"
    return previous


def get_current():
    return _current["plugin_id"]
