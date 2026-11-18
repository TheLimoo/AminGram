package com.exteragram.messenger.components;

import org.telegram.ui.ActionBar.BaseFragment;

/**
 * Facade: the class plugins expect at
 * {@code com.exteragram.messenger.components.QRCodeSheet}.
 *
 * Upstream exteraless has the real class at
 * {@code app.exteraless.components.QRCodeSheet}; the plugin path is a
 * facade path. Same facade pattern as
 * {@code com.exteragram.messenger.components.ActionRow}: the alias
 * table covers the {@code jclass} / {@code find_class} paths, this
 * class covers the {@code from ... import ...} path, which never calls
 * {@code resolve()}. This subclass adds no behaviour: it mirrors the
 * real class's only constructor and inherits everything else.
 */
public class QRCodeSheet extends app.exteraless.components.QRCodeSheet {

    public QRCodeSheet(BaseFragment fragment, String content) {
        super(fragment, content);
    }
}
