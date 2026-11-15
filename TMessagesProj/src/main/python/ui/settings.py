"""Clean-room ui.settings — AminGram Python plugin SDK, M1.

Row classes a plugin returns from ``BasePlugin.create_settings()``
so the host can render them on the plugin's page. This is
AminGram's own clean-room API, written from the public SDK docs'
row-type list (Header, Switch, Selector, Input, EditText, Text,
Divider, Custom) — no exteraGram code was used.

A row may also be a plain dict with the same keys
(``{"type": "switch", "key": ..., "text": ..., "default": ...}``);
the host accepts both. Constructor argument order is AminGram's:
``(key, text, ...)`` for interactive rows, ``(text)`` for
display rows. A plugin written against exteraGram's exact
``ui.settings`` signatures may need its calls adjusted — the
row *types* match, the constructors are ours.

``on_change`` (Switch / Selector / Input / EditText) is called
with the new value whenever the user changes the row on the
plugin page. It runs on the Python thread, never the UI thread.

``options`` for Selector may be a list of strings
(``["fast", "slow"]``) or a list of ``{"value": ..., "label": ...}``
dicts, or a list of ``(value, label)`` pairs.
"""

__all__ = [
    "Header", "Switch", "Selector", "Input", "EditText", "Text",
    "Divider", "Custom", "SimpleSettingFactory",
]


class Header:
    def __init__(self, text):
        self.type = "header"
        self.text = str(text)


class Switch:
    def __init__(self, key, text, default=False, on_change=None,
                 description=None):
        self.type = "switch"
        self.key = str(key)
        self.text = str(text)
        self.default = bool(default)
        self.on_change = on_change
        self.description = description


class Selector:
    def __init__(self, key, text, options, default=None,
                 on_change=None, description=None):
        self.type = "selector"
        self.key = str(key)
        self.text = str(text)
        self.options = list(options)
        self.default = default
        self.on_change = on_change
        self.description = description


class Input:
    def __init__(self, key, text, default="", hint="", on_change=None,
                 input_type="text", description=None):
        self.type = "input"
        self.key = str(key)
        self.text = str(text)
        self.default = default
        self.hint = hint
        self.on_change = on_change
        self.input_type = input_type
        self.description = description


class EditText(Input):
    def __init__(self, key, text, default="", hint="", on_change=None,
                 input_type="text", description=None):
        super().__init__(key, text, default, hint, on_change,
                         input_type, description)
        self.type = "edittext"


class Text:
    def __init__(self, text, value=None):
        self.type = "text"
        self.text = str(text)
        self.value = value


class Divider:
    def __init__(self):
        self.type = "divider"


class Custom:
    """A display-only row. M1 renders its text; a view factory
    (exteraGram's Custom) is not supported — the host owns the UI."""

    def __init__(self, text):
        self.type = "custom"
        self.text = str(text)


class SimpleSettingFactory:
    """Convenience constructors returning the row classes above."""

    @staticmethod
    def header(text):
        return Header(text)

    @staticmethod
    def switch(key, text, default=False, on_change=None):
        return Switch(key, text, default, on_change)

    @staticmethod
    def selector(key, text, options, default=None, on_change=None):
        return Selector(key, text, options, default, on_change)

    @staticmethod
    def input(key, text, default="", hint="", on_change=None):
        return Input(key, text, default, hint, on_change)

    @staticmethod
    def text(text, value=None):
        return Text(text, value)

    @staticmethod
    def divider():
        return Divider()
