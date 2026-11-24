package com.exteragram.messenger.components;

import android.graphics.drawable.Drawable;

/**
 * Facade: the class plugins expect at
 * {@code com.exteragram.messenger.components.VerticalImageSpan}.
 *
 * Upstream exteraless has the real class at
 * {@code app.exteraless.components.VerticalImageSpan}; the plugin path
 * is a facade path. Same facade pattern as
 * {@code com.exteragram.messenger.components.ActionRow}: the alias
 * table covers the {@code jclass} / {@code find_class} paths, this
 * class covers the {@code from ... import ...} path, which never calls
 * {@code resolve()}. This subclass adds no behaviour: it mirrors the
 * real class's only constructor and inherits everything else.
 */
public class VerticalImageSpan extends app.exteraless.components.VerticalImageSpan {

    public VerticalImageSpan(Drawable drawable) {
        super(drawable);
    }
}
