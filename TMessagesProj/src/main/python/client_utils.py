"""Clean-room client_utils — AminGram Python plugin SDK, M1.

Written from the public SDK docs (module list) and the sample
plugin's usage. M1 surface:
- run_on_queue(fn, delay=0): queued callback on the Python
  thread (never a polling loop). The callback is logged under
  the calling plugin (M1: amin_context tracks it; M0 logged
  everything under "engine").
- get_last_fragment(): M2 — needs the LaunchActivity hook point
  and fragment-stack helpers; raises NotImplementedError
  instead of returning a wrong fragment.
"""

import amin_context

from java import jclass


def run_on_queue(fn, delay=0):
    """Run fn on the Python thread after delay milliseconds."""
    host = jclass("org.telegram.messenger.amin.pyplugins.PyPluginHost")
    host.postDelayed(amin_context.get_current(), fn, int(delay))


def get_last_fragment():
    raise NotImplementedError(
        "get_last_fragment is M2 (needs the LaunchActivity hook "
        "point and fragment-stack helpers).")
