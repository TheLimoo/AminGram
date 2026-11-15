"""Menu items for Python plugins — AminGram clean-room SDK, M2.

Written from the public exteraGram SDK docs (MenuItemData with
locations MESSAGE_CONTEXT_MENU, DRAWER_MENU, MAIN_MENU,
CHAT_ACTION_MENU, PROFILE_ACTION_MENU and MVEL ``condition``
expressions). No exteraGram or exteraless code was used.

A menu item is data: the plugin creates a MenuItemData and
passes it to ``BasePlugin.add_menu_item``. The host renders it
at the matching call site and dispatches the tap to ``on_click``
(or to the plugin's ``on_menu_action`` when no callback was
given). ``condition`` is evaluated by the host in Java
(PyMenuCondition, an MVEL subset — see its docstring) at
menu-build time; an empty condition always shows the item.

Locations actually rendered in M2: ``message_context_menu``
(ChatActivity long-press menu, patch 0020) and
``chat_action_menu`` (attach menu, patch 0014). The other three
locations have no upstream hook point in AminGram yet, so items
registered for them are accepted and stored but never shown
(the host logs the registration; M3 decides whether to add the
hook points). They are NOT silently dropped from the registry,
so a future milestone can light them up without plugins
re-registering.
"""

# Location constants (exteraGram MenuItemData locations).
MESSAGE_CONTEXT_MENU = "message_context_menu"
DRAWER_MENU = "drawer_menu"
MAIN_MENU = "main_menu"
CHAT_ACTION_MENU = "chat_action_menu"
PROFILE_ACTION_MENU = "profile_action_menu"

LOCATIONS = frozenset([
    MESSAGE_CONTEXT_MENU,
    DRAWER_MENU,
    MAIN_MENU,
    CHAT_ACTION_MENU,
    PROFILE_ACTION_MENU,
])


class MenuItemData:
    """One menu entry contributed by a plugin.

    :param text: label shown in the menu (required, non-empty).
    :param location: one of the LOCATIONS constants.
    :param condition: MVEL-subset expression over ``message`` /
        ``chat`` (context menu) or ``account`` (attach menu);
        empty means always show. See PyMenuCondition for the
        supported syntax.
    :param icon: icon hint (a name such as "download"); M2 renders
        the default icon for every Python item — the hint is
        stored for a future milestone.
    :param on_click: callable(data) run on tap; ``data`` is a dict
        with ``location``, ``plugin_id`` and (for context-menu
        items) ``chat_id``, ``message_id``, ``text``, ``out``.
        When None, the plugin's ``on_menu_action(location, data)``
        method is called instead.
    """

    def __init__(self, text, location, condition=None, icon=None,
                 on_click=None):
        if not text or not str(text).strip():
            raise ValueError("MenuItemData needs a non-empty text")
        if location not in LOCATIONS:
            raise ValueError(
                "unknown menu location %r (one of %s)"
                % (location, sorted(LOCATIONS)))
        if on_click is not None and not callable(on_click):
            raise ValueError("MenuItemData on_click must be callable")
        self.text = str(text)
        self.location = location
        self.condition = condition or ""
        self.icon = icon or ""
        self.on_click = on_click
