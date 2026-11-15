"""locales_controller — locale string tables.

Clean-room re-implementation of the ZwyLib API surface used by
MaterialSettingsList v1.1.1 (M3), written from that plugin's
usage only — no ZwyLib code was used.

``generate_locale_class({lang_code: {key: text, ...}, ...})``
returns a class whose attributes are the strings for the current
Telegram locale (``LocaleController.getCurrentLocaleInfo()
.pluralLangCode``), falling back to ``"en"``, then to the first
table given. A missing key is an AttributeError, exactly as a
missing attribute on any other locale class.
"""


def _current_lang_code():
    try:
        from java import jclass
        code = (jclass("org.telegram.messenger.LocaleController")
                .getInstance().getCurrentLocaleInfo().pluralLangCode)
        if code:
            return str(code)
    except Exception:
        pass
    return "en"


def generate_locale_class(locales):
    """Build the locale class for the current language."""
    if not isinstance(locales, dict) or not locales:
        raise ValueError("generate_locale_class needs a non-empty dict")
    lang = _current_lang_code()
    table = locales.get(lang) or locales.get("en")
    if table is None:
        table = next(iter(locales.values()), {})
    return type("LocaleStrings", (), dict(table))
