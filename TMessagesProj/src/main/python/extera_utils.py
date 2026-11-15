"""extera_utils — class-proxy DSL + text formatting, M2.

Clean-room subset, written from the public exteraGram SDK docs
(module list only — no exteraGram code was used). Only the
parts that can be built on AminGram's own SDK are here; anything
needing native hooking is out of scope until M3.

Text helpers produce Telegram HTML fragments (parse_mode=HTML):
``bold``, ``italic``, ``code``, ``pre``, ``link``,
``mention`` (``tg://user?id=...``), ``escape_html``. They are
pure Python — no host call — so they work in tests too.

``proxy_class(name)`` returns the Java class (via ``jclass``) so
a plugin can construct or call it; ``ClassProxy`` wraps an
instance and forwards attribute access, logging the wrapping.
Neither helper installs hooks — for method interception see
``hook_utils.hook_method`` (M3, not implemented).
"""

import html

from java import jclass


def escape_html(text):
    return html.escape("" if text is None else str(text))


def bold(text):
    return "<b>%s</b>" % escape_html(text)


def italic(text):
    return "<i>%s</i>" % escape_html(text)


def code(text):
    return "<code>%s</code>" % escape_html(text)


def pre(text, language=None):
    if language:
        return '<pre language="%s">%s</pre>' % (
            escape_html(language), escape_html(text))
    return "<pre>%s</pre>" % escape_html(text)


def link(text, url):
    return '<a href="%s">%s</a>' % (
        escape_html(url), escape_html(text))


def mention(user_id, name):
    return '<a href="tg://user?id=%s">%s</a>' % (
        escape_html(user_id), escape_html(name))


def proxy_class(name):
    """Return the Java class object for ``name`` (jclass)."""
    return jclass(name)


class ClassProxy:
    """Thin wrapper forwarding attribute access to a Java object.

    ``proxy = ClassProxy(obj)``; ``proxy.someMethod(...)`` calls
    through. Use it to pass a wrapped Java object around plugin
    code with an explicit marker that it came from the host.
    """

    def __init__(self, target):
        object.__setattr__(self, "_target", target)

    def __getattr__(self, name):
        return getattr(object.__getattribute__(self, "_target"), name)

    def __setattr__(self, name, value):
        setattr(object.__getattribute__(self, "_target"), name, value)

    def unwrap(self):
        return object.__getattribute__(self, "_target")

    def __repr__(self):
        return "ClassProxy(%r)" % (
            object.__getattribute__(self, "_target"),)
