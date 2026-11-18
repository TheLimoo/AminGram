package com.exteragram.messenger.components;

import android.content.Context;

import org.telegram.ui.ActionBar.Theme;

/**
 * Facade: the class plugins expect at
 * {@code com.exteragram.messenger.components.ProfileMusicCard}.
 *
 * Upstream exteraless has the real class at
 * {@code app.exteraless.components.ProfileMusicCard}; the plugin path is
 * a facade path. Ported with the real class in Fix 16 (it was already
 * referenced by the SDK alias table — NowPlayingCard rewrites to
 * ProfileMusicCard — but the class itself had never been ported, so the
 * rewrite pointed at nothing). Same facade pattern as
 * {@code com.exteragram.messenger.components.ActionRow}: the alias
 * table covers the {@code jclass} / {@code find_class} paths, this
 * class covers the {@code from ... import ...} path, which never calls
 * {@code resolve()}. This subclass adds no behaviour: it mirrors the
 * real class's only constructor and inherits everything else.
 */
public class ProfileMusicCard extends app.exteraless.components.ProfileMusicCard {

    public ProfileMusicCard(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
    }
}
