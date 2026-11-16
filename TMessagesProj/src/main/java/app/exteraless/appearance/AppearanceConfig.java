package app.exteraless.appearance;

/**
 * Minimal stand-in for exteraless's Kotlin {@code AppearanceConfig}
 * (Fix 3 / v3.0.3).
 *
 * Source: exteraless (GPL-3.0),
 * {@code TMessagesProj/src/main/kotlin/app/exteraless/appearance/AppearanceConfig.kt}.
 * The real object is a whole Appearance-settings system backed by
 * {@code NekoConfig} / {@code NaConfig} SharedPreferences — none of that
 * is ported. Only the two getters the plugin engine calls are reproduced,
 * returning the .kt file's own defaults:
 *
 * <ul>
 *     <li>{@link #sectionRadius()} — default 20 dp
 *         ({@code addConfig("OEAppearanceSectionRadius", ..., 20)}).</li>
 *     <li>{@link #titleText()} — default 0, i.e. the app name
 *         ({@code addConfig("OEAppearanceTitleText", ..., 0)}).</li>
 * </ul>
 *
 * {@link #TITLE_TEXT_CUSTOM} is 4, as in the .kt file.
 */
public final class AppearanceConfig {

    private AppearanceConfig() {
    }

    /** Custom title mode, as in the .kt file. */
    public static final int TITLE_TEXT_CUSTOM = 4;

    /** Section-card corner radius, dp. 0 would be sharp corners (stock). */
    public static int sectionRadius() {
        return 20;
    }

    /**
     * Chat-list title mode: 0 app name, 1 username, 2 first name,
     * 3 "Chats". Default 0.
     */
    public static int titleText() {
        return 0;
    }
}
