"""Clean-room client_utils — AminGram Python plugin SDK, M0.

Written from the public SDK docs (module list) and the sample
plugin's usage. M0 surface:
- run_on_queue(fn, delay=0): queued callback on the Python
  thread (never a polling loop).
- get_last_fragment(): M2 — needs the LaunchActivity hook point
  and fragment-stack helpers; raises NotImplementedError in M0
  instead of returning a wrong fragment.
"""

from java import jclass


def run_on_queue(fn, delay=0):
    """Run fn on the Python thread after delay milliseconds."""
    host = jclass("org.telegram.messenger.amin.pyplugins.PyPluginHost")
    # The host needs the owning plugin id for logging; it is
    # derived from the calling module by the Java side in M1.
    # M0: log under the engine id.
    host.postDelayed("engine", fn, int(delay))


def get_last_fragment():
    raise NotImplementedError(
        "get_last_fragment is M2 (needs the LaunchActivity hook "
        "point and fragment-stack helpers).")
