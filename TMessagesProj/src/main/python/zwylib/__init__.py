"""zwylib — clean-room ZwyLib-compatible library (M3).

Ships IN AminGram (``src/main/python/zwylib/``) so plugins that
depend on ZwyLib — MaterialSettingsList v1.1.1 first among them —
can load without it. This is a re-implementation of the small
API surface those plugins use, written from the plugins'
usage and the public SDK docs only. The real ZwyLib was never
available to this task (no public repo or licence; distributed
only as a ``.plugin`` from a Telegram channel), so nothing of
it is bundled, re-hosted or redistributed, and no ZwyLib code
was copied.

Version: 2.0.6 — the version MaterialSettingsList requires
(``REQUIRED_ZWYLIB_VERSION = "2.0.6"``). The gate
:func:`is_zwylib_version_sufficient` compares against it.

Precedence, stated plainly: Chaquopy puts this package's
directory (``src/main/python/``) on ``sys.path`` ahead of the
plugins directory (which the host appends at load time), so
``import zwylib`` in a plugin always resolves to THIS package,
never to a user-installed ``zwylib.plugin`` file. Installing the
real ZwyLib is therefore unnecessary and has no effect on
imports. A real ``zwylib.plugin`` file installed as a plugin
would additionally fail to load as a plugin itself (it defines
no BasePlugin subclass — library-plugin loading is an M4 gap);
do not install one.

Surface (all clean-room):
- :func:`is_zwylib_version_sufficient` — version gate.
- :class:`SettingEntry` — a persisted per-plugin setting.
- :class:`ExpandableSwitchManager` / :class:`ExpandableOption` —
  switch groups (rendered flat: header + switches; the M1
  settings page has no collapsible groups).
- :class:`HookUtils` — minimal: ``hook_method`` for the three
  SettingsActivity points (see :mod:`hook_utils`).
- :mod:`locales_controller` — ``generate_locale_class``.
- :func:`build_log` — a standard logging logger.
- :func:`build_bulletin_helper` — bulletin helpers.
- :func:`add_autoupdater_task` — NO-OP stub (M3): AminGram
  never auto-updates plugins; the call is logged and ignored.
"""

import logging

import amin_context

from java import jclass

from . import locales_controller
from .expandable_switch import ExpandableOption, ExpandableSwitchManager
from .setting_entry import SettingEntry

__version__ = "2.0.6"
_VERSION_TUPLE = (2, 0, 6)

__all__ = [
    "__version__",
    "is_zwylib_version_sufficient",
    "SettingEntry",
    "ExpandableSwitchManager",
    "ExpandableOption",
    "HookUtils",
    "locales_controller",
    "build_log",
    "build_bulletin_helper",
    "add_autoupdater_task",
]


def is_zwylib_version_sufficient(name, version, notify=False):
    """True when this package's version is >= ``version``.

    ``notify=True`` shows an error bulletin on failure (the
    plugin's own fallback path shows its own bulletin instead,
    so MaterialSettingsList calls with ``notify=False``)."""
    try:
        required = tuple(int(p) for p in str(version).split("."))
    except Exception:
        return False
    ok = _VERSION_TUPLE >= required
    if not ok and notify:
        try:
            from ui.bulletin import BulletinHelper
            BulletinHelper.show_error(
                "%s requires ZwyLib %s or newer." % (name, version))
        except Exception:
            pass
    return ok


def build_log(name, level=logging.INFO):
    """A standard :mod:`logging` logger under ``name``."""
    logger = logging.getLogger(name)
    try:
        logger.setLevel(level)
    except Exception:
        pass
    return logger


class _BulletinHelper:
    """Bulletin helpers (delegates to :mod:`ui.bulletin`)."""

    @staticmethod
    def show_error(text):
        from ui.bulletin import BulletinHelper
        BulletinHelper.show_error(text)

    @staticmethod
    def show_with_button(text, icon=None, button=None, on_click=None):
        from ui.bulletin import BulletinHelper
        BulletinHelper.show_with_button(text, icon, button, on_click)


def build_bulletin_helper():
    """A bulletin helper (see :class:`_BulletinHelper`)."""
    return _BulletinHelper()


def add_autoupdater_task(channel_id, msg_id):
    """NO-OP stub (M3): AminGram never auto-updates a plugin from
    a Telegram channel — the call is logged and ignored, so a
    plugin that registers its updater still loads and works.
    Auto-update, if ever wanted, is an M4 decision with its own
    consent and hash verification; it is not smuggled in here."""
    try:
        host = jclass(
            "org.telegram.messenger.amin.pyplugins.PyPluginHost")
        host.log(amin_context.get_current(),
                 "zwylib: auto-updater is not supported in AminGram — "
                 "task ignored (channel %s, message %s)."
                 % (channel_id, msg_id))
    except Exception:
        pass


class HookUtils:
    """Minimal HookUtils (M3): only ``hook_method`` for the three
    SettingsActivity hook points, via :mod:`hook_utils`. Any
    other target raises NotImplementedError there instead of
    pretending to hook."""

    @staticmethod
    def hook_method(class_name, method_name, before=None, after=None):
        from hook_utils import hook_method as _hook_method
        return _hook_method(class_name, method_name,
                            before=before, after=after)
