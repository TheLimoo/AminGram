"""ExpandableSwitchManager — collapsible switch groups.

Clean-room re-implementation of the ZwyLib API surface used by
MaterialSettingsList v1.1.1 (M3), written from that plugin's
usage only — no ZwyLib code was used.

Rendering deviation, stated plainly: AminGram's settings page
(M1) has no collapsible groups, so a group renders as a header
row followed by plain switch rows. The values, the keys and the
on_change wiring are identical to the collapsible form; only the
collapsing is missing. If a later milestone adds collapsible
groups, this module gains them without changing the plugin-side
API.
"""

from .setting_entry import _wrap_on_change


class ExpandableOption:
    """One switch inside an expandable group: a label, the
    :class:`zwylib.setting_entry.SettingEntry` holding its value,
    and an on_change callable receiving the new value."""

    def __init__(self, label, entry, on_change=None):
        self.label = str(label)
        self.entry = entry
        self.on_change = on_change

    def as_row(self):
        """The switch row dict (same shape as
        :meth:`SettingEntry.as_switch`)."""
        return {
            "type": "switch",
            "key": self.entry.key,
            "text": self.label,
            "default": self.entry.default,
            "on_change": _wrap_on_change(self.entry, self.on_change),
        }


class ExpandableSwitchManager:
    """Generates a group's rows: a header row with the title,
    then one switch row per option, in order."""

    @staticmethod
    def generate(title, options):
        rows = [{"type": "header", "text": str(title)}]
        for opt in options or []:
            if isinstance(opt, ExpandableOption):
                rows.append(opt.as_row())
            elif isinstance(opt, dict):
                # A plain row dict passes through unchanged.
                rows.append(opt)
        return rows
