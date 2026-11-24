package org.telegram.messenger;

/**
 * Facade: the class plugins expect at {@code org.telegram.messenger.CodeHighlighting}.
 *
 * In Telegram Android 13.2.2 the class lives at
 * {@code org.telegram.utils.code.highlight.CodeHighlighting} (it moved to
 * {@code org.telegram.messenger} only in a newer upstream), so a plugin
 * written against the newer base fails its import with
 * {@code ImportError: cannot import name 'CodeHighlighting'} (ui_tweaks.py:44).
 *
 * This subclass adds no behaviour: every static method, constant and nested
 * class is inherited from the real implementation, and a {@code from
 * org.telegram.messenger import CodeHighlighting} lookup resolves to this
 * class by name. The SDK alias table
 * ({@code extera_utils.class_aliases._EXACT}) maps the same name to the real
 * class for the {@code jclass} / {@code find_class} paths; this facade
 * covers the {@code from ... import ...} path, which the alias table cannot
 * reach (the SDK import wrapper never calls {@code resolve()} for a
 * from-import — it goes straight to Chaquopy's java import hook).
 *
 * Caveat: nested classes ({@code CodeHighlighting.Span},
 * {@code CodeHighlighting.ColorSpan}, ...) are declared on the superclass,
 * not on this subclass, so {@code Facade.Span} resolves only if the lookup
 * consults inherited nested classes. No plugin in scope uses them; if one
 * does, it gets its own round.
 */
public class CodeHighlighting extends org.telegram.utils.code.highlight.CodeHighlighting {
}
