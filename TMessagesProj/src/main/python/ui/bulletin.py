"""Clean-room ui.bulletin — AminGram Python plugin SDK, M0.

Written from the public SDK docs and the sample plugin's usage
(BulletinHelper.show_with_button / show_error).

M0 limitation, stated plainly: a real Telegram Bulletin needs a
fragment, which a plugin does not have at load time, so M0 shows
a toast and logs the message (via PyPluginHost). The button's
on_click is NOT run in M0 — the ZwyLib-missing force_stop path
still works end-to-end because its setError / setEnabled calls
go through the PluginsController facade, not through the bulletin.
M1 renders a real bulletin once the last-fragment helper lands.
"""

from java import jclass


class BulletinHelper:

    @staticmethod
    def show_with_button(text, icon=None, button=None, on_click=None):
        host = jclass("org.telegram.messenger.amin.pyplugins.PyPluginHost")
        line = str(text)
        if button:
            line += " [" + str(button) + "]"
        host.toast("engine", line)

    @staticmethod
    def show_error(text):
        host = jclass("org.telegram.messenger.amin.pyplugins.PyPluginHost")
        host.toast("engine", str(text))
