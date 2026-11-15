"""Clean-room client_utils — AminGram Python plugin SDK, M2.

Written from the public SDK docs (module list) and the sample
plugin's usage. Surface:
- run_on_queue(fn, delay=0): queued callback on the Python
  thread (never a polling loop). The callback is logged under
  the calling plugin (amin_context tracks it).
- get_last_fragment(): the currently visible fragment
  (LaunchActivity.getLastFragment via PyPluginHost), or None
  when no fragment is available. The returned object is a Java
  BaseFragment — a plugin can call e.g. ``presentFragment(...)``
  on it through Chaquopy.
- get_fragment_stack(): the fragment stack as a list of Java
  fragments (last fragment first when the host can provide the
  order, otherwise a best-effort list); empty list when none.
- confirm_sensitive_action(action, amount=None, recipient=None,
  on_confirm=None): shows a confirmation bulletin ("Plugin <id>
  wants to <action> — amount, recipient") with a Confirm button.
  The action must ONLY run inside on_confirm: no tap means no
  confirmation. This is the gate future mediated Stars/gift/
  security actions must call; direct reflection calls bypass it
  (the install-time analysis says so, and the hub copy says so).
  No SDK method currently spends Stars or transfers gifts, so
  this is infrastructure with a documented contract — it is not
  wired to any transfer yet.
"""

import amin_context

from java import jclass


def _host():
    return jclass("org.telegram.messenger.amin.pyplugins.PyPluginHost")


def run_on_queue(fn, delay=0):
    """Run fn on the Python thread after delay milliseconds."""
    host = _host()
    host.postDelayed(amin_context.get_current(), fn, int(delay))


def get_last_fragment():
    """The currently visible fragment, or None."""
    return _host().getLastFragment()


def get_fragment_stack():
    """The fragment stack (list of Java fragments), or []."""
    try:
        stack = _host().getFragmentStack()
        if stack is None:
            return []
        return list(stack)
    except Exception:
        return []


def confirm_sensitive_action(action, amount=None, recipient=None,
                             on_confirm=None):
    """Ask the user to confirm a sensitive mediated action.

    :param action: plain-language verb, e.g. "spend 50 Stars".
    :param amount: amount string shown in the bulletin (optional).
    :param recipient: who it goes to (optional).
    :param on_confirm: callable with no arguments, run on the
        Python thread only when the user taps Confirm.
    """
    _host().confirmSensitiveAction(
        amin_context.get_current(), str(action),
        "" if amount is None else str(amount),
        "" if recipient is None else str(recipient),
        on_confirm)
