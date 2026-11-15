"""Clean-room ui.bulletin — AminGram Python plugin SDK, M1.

Written from the public SDK docs and the sample plugin's usage
(BulletinHelper.show_with_button / show_error).

M1: a real Telegram Bulletin, shown from the last fragment
(via PyPluginHost.showBulletin). The button's on_click runs on
the Python thread when the user taps it. When no fragment is
available (e.g. the app is in the background) the host falls
back to a toast + log line and the on_click is NOT run — the
call is still logged so the plugin's author can see it happened.

``icon`` is an ``R.raw.*`` resource id (an int, e.g.
``jclass("org.telegram.messenger.R").raw.error``) or None for
the default icon.
"""

import amin_context

from java import jclass


def _host():
    return jclass("org.telegram.messenger.amin.pyplugins.PyPluginHost")


class BulletinHelper:

    @staticmethod
    def show_with_button(text, icon=None, button=None, on_click=None):
        _host().showBulletin(
            amin_context.get_current(),
            str(text),
            int(icon) if icon is not None else -1,
            None if button is None else str(button),
            on_click)

    @staticmethod
    def show_error(text):
        _host().showError(amin_context.get_current(), str(text))
