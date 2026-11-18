package com.exteragram.messenger.components;

import android.content.Context;

import org.telegram.ui.ActionBar.Theme;

import java.util.List;

import app.exteraless.components.ActionRow.ActionItem;

/**
 * Facade: the class plugins expect at
 * {@code com.exteragram.messenger.components.ActionRow}.
 *
 * Upstream exteraless has the real class at
 * {@code app.exteraless.components.ActionRow}; there is no
 * {@code com.exteragram.messenger.components} package anywhere, and the
 * plugin path is a facade path. The SDK alias table
 * ({@code extera_utils.class_aliases}) already rewrites the
 * {@code com.exteragram.messenger.components.} prefix to
 * {@code app.exteraless.components.} for the {@code jclass} /
 * {@code find_class} paths, but a {@code from ... import ActionRow}
 * goes straight to Chaquopy's java import hook, which never calls
 * {@code resolve()} — so the from-import needs a real class at the
 * imported path (same lesson as the Fix 15 CodeHighlighting facade
 * and the Fix 10 CustomSetting facade).
 *
 * This subclass adds no behaviour: it mirrors the real class's only
 * constructor and inherits everything else. The nested
 * {@code ActionItem} is declared on the superclass; {@code Facade.ActionItem}
 * resolves only if the lookup consults inherited nested classes.
 * The reported failure was {@code ios_like_selection.py:24}:
 * {@code from com.exteragram.messenger.components import ActionRow}.
 */
public class ActionRow extends app.exteraless.components.ActionRow {

    public ActionRow(Context context, Theme.ResourcesProvider resourcesProvider, List<ActionItem> items) {
        super(context, resourcesProvider, items);
    }
}
