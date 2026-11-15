"""Clean-room android_utils — AminGram Python plugin SDK, M1.

Written from the public exteraGram plugin SDK docs (module list
only — no exteraGram code was used).

M1 surface:
- run_on_ui_thread(fn): run a callable on the UI thread.
  The callable is invoked on the UI thread itself (that is the
  point — UI work); keep it short, never block it.
- toast(text): short toast + hub log line.
- copy_to_clipboard(text): write-only, like the JS bridge.
  Reading the clipboard is NOT exposed — a plugin that could
  read it could steal anything the user copies.

Everything is Throwable-isolated in the host: a failure is
logged to the plugin, never raised into the caller.
"""

import amin_context

from java import jclass


def _host():
    return jclass("org.telegram.messenger.amin.pyplugins.PyPluginHost")


def run_on_ui_thread(fn):
    _host().runOnUiThread(amin_context.get_current(), fn)


def toast(text):
    _host().toast(amin_context.get_current(), str(text))


def log(message):
    _host().log(amin_context.get_current(), str(message))


def copy_to_clipboard(text):
    _host().copyToClipboard(amin_context.get_current(), str(text))


def get_clipboard_text():
    raise NotImplementedError(
        "clipboard read is not exposed (write-only, like the JS "
        "bridge) — a plugin that could read the clipboard could "
        "steal anything the user copies.")
