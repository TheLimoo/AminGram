package com.exteragram.messenger;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Minimal ExteraConfig shim (Fix 10, v3.0.10).
 *
 * Upstream exteraless has a ~2200-line ExteraConfig that fans out to
 * NekoConfig / NaConfig / ChatsConfig / GeneralConfig / UtilsConfig /
 * MainMenuLayout / BaseIconPacks / OpenExteraConfig / PreferencesUtils —
 * none of which we ported, deliberately. ZwyLib and MSL use exactly ONE
 * member of this class ({@code pluginsSafeMode}, read by ZwyLib's
 * {@code src/utils/hooks.py}), so this shim implements the full
 * get/set API surface the Python SDK's {@code _FIELD_SHAPED} table
 * expects, with honest backing:
 *
 * - The five plugin-controller fields (pluginsDevMode, pluginsSafeMode,
 *   pluginsCompactView, pluginsDisableArtOpts, pluginsEngine) delegate
 *   to {@code app.exteraless.plugins.PluginsController}, exactly like
 *   upstream — these DO affect the engine.
 * - {@code pinnedPlugins} mirrors upstream's read/write helpers over
 *   the controller; {@code sectionsSeparatedHeaders} mirrors upstream's
 *   derivation from the divider style; {@code logging} reads/writes
 *   {@code BuildVars.LOGS_ENABLED}.
 * - Everything else is backed by the private SharedPreferences file
 *   {@code exteraconfig} (upstream's own prefs name): the plugin sees
 *   the full API and values persist across restarts, but they do not
 *   affect the app. Upstream prefs-direct defaults are kept where the
 *   upstream getter reads prefs directly; config-delegating fields use
 *   type defaults (false / 0 / 0.0f / "" / 0L / empty set / first enum
 *   constant).
 *
 * The seven enum types this class returns are ported verbatim from
 * upstream (same package, same constants) as separate files.
 */
public final class ExteraConfig {

    private static final String PREFS_NAME = "exteraconfig";

    private static volatile SharedPreferences preferences;

    private ExteraConfig() {
    }

    public static SharedPreferences getPreferences() {
        SharedPreferences local = preferences;
        if (local == null) {
            synchronized (ExteraConfig.class) {
                local = preferences;
                if (local == null) {
                    local = ApplicationLoader.applicationContext
                            .getSharedPreferences(PREFS_NAME, 0);
                    preferences = local;
                }
            }
        }
        return local;
    }

    private static app.exteraless.plugins.PluginsController plugins() {
        return app.exteraless.plugins.PluginsController.getInstance();
    }

    private static <E extends Enum<E>> E enumAt(E[] values, int ordinal, E fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    private static java.util.Set<String> readPinnedPlugins() {
        LinkedHashSet<String> pinned = new LinkedHashSet<>();
        try {
            for (Map.Entry<String, app.exteraless.plugins.Plugin> entry : plugins().getPlugins().entrySet()) {
                if (plugins().isPluginPinned(entry.getKey())) {
                    pinned.add(entry.getKey());
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return pinned;
    }

    private static void writePinnedPlugins(java.util.Set<String> value) {
        Set<String> wanted = value == null ? Collections.<String>emptySet() : value;
        try {
            for (String id : plugins().getPlugins().keySet()) {
                plugins().setPluginPinned(id, wanted.contains(id));
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Direct accessor, as in upstream (ZwyLib reads it as a field via the SDK wrapper). */
    public static boolean pluginsSafeMode() {
        return getPluginsSafeMode();
    }

    public static int getTranslationProvider() {
        return getPreferences().getInt("translationProvider", 0);
    }

    public static void setTranslationProvider(int value) {
        getPreferences().edit().putInt("translationProvider", value).apply();
    }

    public static TranslationFormality getTranslationFormality() {
        return enumAt(TranslationFormality.values(), getPreferences().getInt("translationFormality", TranslationFormality.NONE.ordinal()), TranslationFormality.NONE);
    }

    public static void setTranslationFormality(TranslationFormality value) {
        getPreferences().edit().putInt("translationFormality", value == null ? TranslationFormality.NONE.ordinal() : value.ordinal()).apply();
    }

    public static boolean getDisableNumberRounding() {
        return getPreferences().getBoolean("disableNumberRounding", false);
    }

    public static void setDisableNumberRounding(boolean value) {
        getPreferences().edit().putBoolean("disableNumberRounding", value).apply();
    }

    public static boolean getFormatTimeWithSeconds() {
        return getPreferences().getBoolean("formatTimeWithSeconds", false);
    }

    public static void setFormatTimeWithSeconds(boolean value) {
        getPreferences().edit().putBoolean("formatTimeWithSeconds", value).apply();
    }

    public static boolean getRelativeLastSeen() {
        return getPreferences().getBoolean("relativeLastSeen", false);
    }

    public static void setRelativeLastSeen(boolean value) {
        getPreferences().edit().putBoolean("relativeLastSeen", value).apply();
    }

    public static boolean getInAppVibration() {
        return getPreferences().getBoolean("inAppVibration", false);
    }

    public static void setInAppVibration(boolean value) {
        getPreferences().edit().putBoolean("inAppVibration", value).apply();
    }

    public static boolean getFilterZalgo() {
        return getPreferences().getBoolean("filterZalgo", false);
    }

    public static void setFilterZalgo(boolean value) {
        getPreferences().edit().putBoolean("filterZalgo", value).apply();
    }

    public static boolean getUseYandexMaps() {
        return getPreferences().getBoolean("useYandexMaps", false);
    }

    public static void setUseYandexMaps(boolean value) {
        getPreferences().edit().putBoolean("useYandexMaps", value).apply();
    }

    public static int getDownloadSpeedBoost() {
        return getPreferences().getInt("downloadSpeedBoost", 0);
    }

    public static void setDownloadSpeedBoost(int value) {
        getPreferences().edit().putInt("downloadSpeedBoost", value).apply();
    }

    public static boolean getUploadSpeedBoost() {
        return getPreferences().getBoolean("uploadSpeedBoost", false);
    }

    public static void setUploadSpeedBoost(boolean value) {
        getPreferences().edit().putBoolean("uploadSpeedBoost", value).apply();
    }

    public static boolean getHidePhoneNumber() {
        return getPreferences().getBoolean("hidePhoneNumber", false);
    }

    public static void setHidePhoneNumber(boolean value) {
        getPreferences().edit().putBoolean("hidePhoneNumber", value).apply();
    }

    public static int getShowIdAndDc() {
        return getPreferences().getInt("showIdAndDc", 0);
    }

    public static void setShowIdAndDc(int value) {
        getPreferences().edit().putInt("showIdAndDc", value).apply();
    }

    public static boolean getHideArchiveFolder() {
        return getPreferences().getBoolean("hideArchiveFolder", false);
    }

    public static void setHideArchiveFolder(boolean value) {
        getPreferences().edit().putBoolean("hideArchiveFolder", value).apply();
    }

    public static boolean getArchiveOnPull() {
        return getPreferences().getBoolean("archiveOnPull", false);
    }

    public static void setArchiveOnPull(boolean value) {
        getPreferences().edit().putBoolean("archiveOnPull", value).apply();
    }

    public static boolean getDisableUnarchiveSwipe() {
        return getPreferences().getBoolean("disableUnarchiveSwipe", false);
    }

    public static void setDisableUnarchiveSwipe(boolean value) {
        getPreferences().edit().putBoolean("disableUnarchiveSwipe", value).apply();
    }

    public static int getDoNotUseProxy() {
        return getPreferences().getInt("doNotUseProxy", 0);
    }

    public static void setDoNotUseProxy(int value) {
        getPreferences().edit().putInt("doNotUseProxy", value).apply();
    }

    public static String getCustomSavePath() {
        return getPreferences().getString("customSavePath", "");
    }

    public static void setCustomSavePath(String value) {
        getPreferences().edit().putString("customSavePath", value).apply();
    }

    public static IconPackType getIconPack() {
        return enumAt(IconPackType.values(), getPreferences().getInt("iconPack", IconPackType.DEFAULT.ordinal()), IconPackType.DEFAULT);
    }

    public static void setIconPack(IconPackType value) {
        getPreferences().edit().putInt("iconPack", value == null ? IconPackType.DEFAULT.ordinal() : value.ordinal()).apply();
    }

    public static String getEditingIconPackId() {
        return getPreferences().getString("editingIconPackId", "");
    }

    public static void setEditingIconPackId(String value) {
        getPreferences().edit().putString("editingIconPackId", value).apply();
    }

    public static float getAvatarCorners() {
        return getPreferences().getFloat("avatarCorners", 0.0f);
    }

    public static void setAvatarCorners(float value) {
        getPreferences().edit().putFloat("avatarCorners", value).apply();
    }

    public static boolean getSingleCornerRadius() {
        return getPreferences().getBoolean("singleCornerRadius", false);
    }

    public static void setSingleCornerRadius(boolean value) {
        getPreferences().edit().putBoolean("singleCornerRadius", value).apply();
    }

    public static DividerStyle getDividerStyle() {
        return enumAt(DividerStyle.values(), getPreferences().getInt("dividerStyle", DividerStyle.HIDDEN.ordinal()), DividerStyle.HIDDEN);
    }

    public static void setDividerStyle(DividerStyle value) {
        getPreferences().edit().putInt("dividerStyle", value == null ? DividerStyle.HIDDEN.ordinal() : value.ordinal()).apply();
    }

    public static boolean getForceSnow() {
        return getPreferences().getBoolean("forceSnow", false);
    }

    public static void setForceSnow(boolean value) {
        getPreferences().edit().putBoolean("forceSnow", value).apply();
    }

    public static boolean getHideActionBarStatus() {
        return getPreferences().getBoolean("hideActionBarStatus", false);
    }

    public static void setHideActionBarStatus(boolean value) {
        getPreferences().edit().putBoolean("hideActionBarStatus", value).apply();
    }

    public static boolean getCenterTitle() {
        return getPreferences().getBoolean("centerTitle", false);
    }

    public static void setCenterTitle(boolean value) {
        getPreferences().edit().putBoolean("centerTitle", value).apply();
    }

    public static boolean getHideStories() {
        return getPreferences().getBoolean("hideStories", false);
    }

    public static void setHideStories(boolean value) {
        getPreferences().edit().putBoolean("hideStories", value).apply();
    }

    public static boolean getHideFloatingButton() {
        return getPreferences().getBoolean("hideFloatingButton", false);
    }

    public static void setHideFloatingButton(boolean value) {
        getPreferences().edit().putBoolean("hideFloatingButton", value).apply();
    }

    public static boolean getHideDialogsSearchBar() {
        return getPreferences().getBoolean("hideDialogsSearchBar", false);
    }

    public static void setHideDialogsSearchBar(boolean value) {
        getPreferences().edit().putBoolean("hideDialogsSearchBar", value).apply();
    }

    public static boolean getSenderMiniAvatars() {
        return getPreferences().getBoolean("senderMiniAvatars", false);
    }

    public static void setSenderMiniAvatars(boolean value) {
        getPreferences().edit().putBoolean("senderMiniAvatars", value).apply();
    }

    public static int getTitleText() {
        return getPreferences().getInt("titleText", 0);
    }

    public static void setTitleText(int value) {
        getPreferences().edit().putInt("titleText", value).apply();
    }

    public static TabIconsMode getTabIcons() {
        return enumAt(TabIconsMode.values(), getPreferences().getInt("tabIcons", TabIconsMode.TITLES_ONLY.ordinal()), TabIconsMode.TITLES_ONLY);
    }

    public static void setTabIcons(TabIconsMode value) {
        getPreferences().edit().putInt("tabIcons", value == null ? TabIconsMode.TITLES_ONLY.ordinal() : value.ordinal()).apply();
    }

    public static boolean getTabCounter() {
        return getPreferences().getBoolean("tabCounter", true);
    }

    public static void setTabCounter(boolean value) {
        getPreferences().edit().putBoolean("tabCounter", value).apply();
    }

    public static boolean getHideAllChats() {
        return getPreferences().getBoolean("hideAllChats", false);
    }

    public static void setHideAllChats(boolean value) {
        getPreferences().edit().putBoolean("hideAllChats", value).apply();
    }

    public static boolean getSquareFab() {
        return getPreferences().getBoolean("squareFab", false);
    }

    public static void setSquareFab(boolean value) {
        getPreferences().edit().putBoolean("squareFab", value).apply();
    }

    public static float getSectionRadius() {
        return getPreferences().getFloat("sectionRadius", 0.0f);
    }

    public static void setSectionRadius(float value) {
        getPreferences().edit().putFloat("sectionRadius", value).apply();
    }

    public static boolean getSectionsSeparatedHeadersPreference() {
        return getPreferences().getBoolean("sectionsSeparatedHeadersPreference", false);
    }

    public static void setSectionsSeparatedHeadersPreference(boolean value) {
        getPreferences().edit().putBoolean("sectionsSeparatedHeadersPreference", value).apply();
    }

    public static boolean getNewLoadingStyle() {
        return getPreferences().getBoolean("newLoadingStyle", false);
    }

    public static void setNewLoadingStyle(boolean value) {
        getPreferences().edit().putBoolean("newLoadingStyle", value).apply();
    }

    public static boolean getNewSliderStyle() {
        return getPreferences().getBoolean("newSliderStyle", true);
    }

    public static void setNewSliderStyle(boolean value) {
        getPreferences().edit().putBoolean("newSliderStyle", value).apply();
    }

    public static boolean getNewSwitchStyle() {
        return getPreferences().getBoolean("newSwitchStyle", true);
    }

    public static void setNewSwitchStyle(boolean value) {
        getPreferences().edit().putBoolean("newSwitchStyle", value).apply();
    }

    public static boolean getNewChatHeaderStyle() {
        return getPreferences().getBoolean("newChatHeaderStyle", false);
    }

    public static void setNewChatHeaderStyle(boolean value) {
        getPreferences().edit().putBoolean("newChatHeaderStyle", value).apply();
    }

    public static boolean getNewNavigationBarStyle() {
        return getPreferences().getBoolean("newNavigationBarStyle", false);
    }

    public static void setNewNavigationBarStyle(boolean value) {
        getPreferences().edit().putBoolean("newNavigationBarStyle", value).apply();
    }

    public static int getTabletMode() {
        return getPreferences().getInt("tabletMode", 0);
    }

    public static void setTabletMode(int value) {
        getPreferences().edit().putInt("tabletMode", value).apply();
    }

    public static boolean getUseSystemFonts() {
        return getPreferences().getBoolean("useSystemFonts", false);
    }

    public static void setUseSystemFonts(boolean value) {
        getPreferences().edit().putBoolean("useSystemFonts", value).apply();
    }

    public static boolean getGooeyAvatarAnimation() {
        return getPreferences().getBoolean("gooeyAvatarAnimation", false);
    }

    public static void setGooeyAvatarAnimation(boolean value) {
        getPreferences().edit().putBoolean("gooeyAvatarAnimation", value).apply();
    }

    public static boolean getCustomThemes() {
        return getPreferences().getBoolean("customThemes", false);
    }

    public static void setCustomThemes(boolean value) {
        getPreferences().edit().putBoolean("customThemes", value).apply();
    }

    public static float getPredictiveBackIntensity() {
        return getPreferences().getFloat("predictiveBackIntensity", 0.0f);
    }

    public static void setPredictiveBackIntensity(float value) {
        getPreferences().edit().putFloat("predictiveBackIntensity", value).apply();
    }

    public static boolean getSpringAnimations() {
        return getPreferences().getBoolean("springAnimations", true);
    }

    public static void setSpringAnimations(boolean value) {
        getPreferences().edit().putBoolean("springAnimations", value).apply();
    }

    public static GlassOutlineStyle getGlassOutlineStyle() {
        return enumAt(GlassOutlineStyle.values(), getPreferences().getInt("glassOutlineStyle", GlassOutlineStyle.GLARE.ordinal()), GlassOutlineStyle.GLARE);
    }

    public static void setGlassOutlineStyle(GlassOutlineStyle value) {
        getPreferences().edit().putInt("glassOutlineStyle", value == null ? GlassOutlineStyle.GLARE.ordinal() : value.ordinal()).apply();
    }

    public static boolean getGlassMessageMenu() {
        return getPreferences().getBoolean("glassMessageMenu", false);
    }

    public static void setGlassMessageMenu(boolean value) {
        getPreferences().edit().putBoolean("glassMessageMenu", value).apply();
    }

    public static boolean getForceBlur() {
        return getPreferences().getBoolean("forceBlur", false);
    }

    public static void setForceBlur(boolean value) {
        getPreferences().edit().putBoolean("forceBlur", value).apply();
    }

    public static int getEventType() {
        return getPreferences().getInt("eventType", 0);
    }

    public static void setEventType(int value) {
        getPreferences().edit().putInt("eventType", value).apply();
    }

    public static boolean getNavigationDrawer() {
        return getPreferences().getBoolean("navigationDrawer", false);
    }

    public static void setNavigationDrawer(boolean value) {
        getPreferences().edit().putBoolean("navigationDrawer", value).apply();
    }

    public static boolean getImmersiveDrawerAnimation() {
        return getPreferences().getBoolean("immersiveDrawerAnimation", false);
    }

    public static void setImmersiveDrawerAnimation(boolean value) {
        getPreferences().edit().putBoolean("immersiveDrawerAnimation", value).apply();
    }

    public static boolean getShowFeedTab() {
        return getPreferences().getBoolean("showFeedTab", false);
    }

    public static void setShowFeedTab(boolean value) {
        getPreferences().edit().putBoolean("showFeedTab", value).apply();
    }

    public static boolean getShowFeedUnreadCounter() {
        return getPreferences().getBoolean("showFeedUnreadCounter", false);
    }

    public static void setShowFeedUnreadCounter(boolean value) {
        getPreferences().edit().putBoolean("showFeedUnreadCounter", value).apply();
    }

    public static float getStickerSize() {
        return getPreferences().getFloat("stickerSize", 0.0f);
    }

    public static void setStickerSize(float value) {
        getPreferences().edit().putFloat("stickerSize", value).apply();
    }

    public static boolean getHideStickerTime() {
        return getPreferences().getBoolean("hideStickerTime", false);
    }

    public static void setHideStickerTime(boolean value) {
        getPreferences().edit().putBoolean("hideStickerTime", value).apply();
    }

    public static boolean getReplyColors() {
        return getPreferences().getBoolean("replyColors", false);
    }

    public static void setReplyColors(boolean value) {
        getPreferences().edit().putBoolean("replyColors", value).apply();
    }

    public static boolean getReplyEmoji() {
        return getPreferences().getBoolean("replyEmoji", false);
    }

    public static void setReplyEmoji(boolean value) {
        getPreferences().edit().putBoolean("replyEmoji", value).apply();
    }

    public static boolean getReplyBackground() {
        return getPreferences().getBoolean("replyBackground", false);
    }

    public static void setReplyBackground(boolean value) {
        getPreferences().edit().putBoolean("replyBackground", value).apply();
    }

    public static int getStickerShape() {
        return getPreferences().getInt("stickerShape", 0);
    }

    public static void setStickerShape(int value) {
        getPreferences().edit().putInt("stickerShape", value).apply();
    }

    public static boolean getUnlimitedRecentStickers() {
        return getPreferences().getBoolean("unlimitedRecentStickers", false);
    }

    public static void setUnlimitedRecentStickers(boolean value) {
        getPreferences().edit().putBoolean("unlimitedRecentStickers", value).apply();
    }

    public static boolean getHideReactionsInPrivateChats() {
        return getPreferences().getBoolean("hideReactionsInPrivateChats", false);
    }

    public static void setHideReactionsInPrivateChats(boolean value) {
        getPreferences().edit().putBoolean("hideReactionsInPrivateChats", value).apply();
    }

    public static boolean getHideReactionsInChannels() {
        return getPreferences().getBoolean("hideReactionsInChannels", false);
    }

    public static void setHideReactionsInChannels(boolean value) {
        getPreferences().edit().putBoolean("hideReactionsInChannels", value).apply();
    }

    public static boolean getHideReactionsInGroups() {
        return getPreferences().getBoolean("hideReactionsInGroups", false);
    }

    public static void setHideReactionsInGroups(boolean value) {
        getPreferences().edit().putBoolean("hideReactionsInGroups", value).apply();
    }

    public static int getDoubleTapAction() {
        return getPreferences().getInt("doubleTapAction", 0);
    }

    public static void setDoubleTapAction(int value) {
        getPreferences().edit().putInt("doubleTapAction", value).apply();
    }

    public static int getDoubleTapActionOutOwner() {
        return getPreferences().getInt("doubleTapActionOutOwner", 0);
    }

    public static void setDoubleTapActionOutOwner(int value) {
        getPreferences().edit().putInt("doubleTapActionOutOwner", value).apply();
    }

    public static int getBottomButton() {
        return getPreferences().getInt("bottomButton", 0);
    }

    public static void setBottomButton(int value) {
        getPreferences().edit().putInt("bottomButton", value).apply();
    }

    public static boolean getWidePostsInFeed() {
        return getPreferences().getBoolean("widePostsInFeed", false);
    }

    public static void setWidePostsInFeed(boolean value) {
        getPreferences().edit().putBoolean("widePostsInFeed", value).apply();
    }

    public static boolean getWidePostsInChannels() {
        return getPreferences().getBoolean("widePostsInChannels", false);
    }

    public static void setWidePostsInChannels(boolean value) {
        getPreferences().edit().putBoolean("widePostsInChannels", value).apply();
    }

    public static boolean getTelegramAiEditor() {
        return getPreferences().getBoolean("telegramAiEditor", false);
    }

    public static void setTelegramAiEditor(boolean value) {
        getPreferences().edit().putBoolean("telegramAiEditor", value).apply();
    }

    public static boolean getTelegramAiSummaries() {
        return getPreferences().getBoolean("telegramAiSummaries", false);
    }

    public static void setTelegramAiSummaries(boolean value) {
        getPreferences().edit().putBoolean("telegramAiSummaries", value).apply();
    }

    public static boolean getQuickAdminShortcuts() {
        return getPreferences().getBoolean("quickAdminShortcuts", false);
    }

    public static void setQuickAdminShortcuts(boolean value) {
        getPreferences().edit().putBoolean("quickAdminShortcuts", value).apply();
    }

    public static boolean getQuickTransitionForChannels() {
        return getPreferences().getBoolean("quickTransitionForChannels", true);
    }

    public static void setQuickTransitionForChannels(boolean value) {
        getPreferences().edit().putBoolean("quickTransitionForChannels", value).apply();
    }

    public static boolean getQuickTransitionForTopics() {
        return getPreferences().getBoolean("quickTransitionForTopics", true);
    }

    public static void setQuickTransitionForTopics(boolean value) {
        getPreferences().edit().putBoolean("quickTransitionForTopics", value).apply();
    }

    public static boolean getDisableGreetingSticker() {
        return getPreferences().getBoolean("disableGreetingSticker", false);
    }

    public static void setDisableGreetingSticker(boolean value) {
        getPreferences().edit().putBoolean("disableGreetingSticker", value).apply();
    }

    public static boolean getHideKeyboardOnScroll() {
        return getPreferences().getBoolean("hideKeyboardOnScroll", false);
    }

    public static void setHideKeyboardOnScroll(boolean value) {
        getPreferences().edit().putBoolean("hideKeyboardOnScroll", value).apply();
    }

    public static boolean getAddCommaAfterMention() {
        return getPreferences().getBoolean("addCommaAfterMention", false);
    }

    public static void setAddCommaAfterMention(boolean value) {
        getPreferences().edit().putBoolean("addCommaAfterMention", value).apply();
    }

    public static boolean getDisableMarkdown() {
        return getPreferences().getBoolean("disableMarkdown", false);
    }

    public static void setDisableMarkdown(boolean value) {
        getPreferences().edit().putBoolean("disableMarkdown", value).apply();
    }

    public static boolean getHideSendAsPeer() {
        return getPreferences().getBoolean("hideSendAsPeer", false);
    }

    public static void setHideSendAsPeer(boolean value) {
        getPreferences().edit().putBoolean("hideSendAsPeer", value).apply();
    }

    public static boolean getRemoveMessageTail() {
        return getPreferences().getBoolean("removeMessageTail", false);
    }

    public static void setRemoveMessageTail(boolean value) {
        getPreferences().edit().putBoolean("removeMessageTail", value).apply();
    }

    public static boolean getReplaceEditedWithIcon() {
        return getPreferences().getBoolean("replaceEditedWithIcon", false);
    }

    public static void setReplaceEditedWithIcon(boolean value) {
        getPreferences().edit().putBoolean("replaceEditedWithIcon", value).apply();
    }

    public static boolean getShowOnlineStatus() {
        return getPreferences().getBoolean("showOnlineStatus", false);
    }

    public static void setShowOnlineStatus(boolean value) {
        getPreferences().edit().putBoolean("showOnlineStatus", value).apply();
    }

    public static boolean getHideShareButton() {
        return getPreferences().getBoolean("hideShareButton", false);
    }

    public static void setHideShareButton(boolean value) {
        getPreferences().edit().putBoolean("hideShareButton", value).apply();
    }

    public static boolean getShowResultsBeforeVoting() {
        return getPreferences().getBoolean("showResultsBeforeVoting", false);
    }

    public static void setShowResultsBeforeVoting(boolean value) {
        getPreferences().edit().putBoolean("showResultsBeforeVoting", value).apply();
    }

    public static boolean getShowCopyPhotoButton() {
        return getPreferences().getBoolean("showCopyPhotoButton", false);
    }

    public static void setShowCopyPhotoButton(boolean value) {
        getPreferences().edit().putBoolean("showCopyPhotoButton", value).apply();
    }

    public static boolean getShowSaveMessageButton() {
        return getPreferences().getBoolean("showSaveMessageButton", false);
    }

    public static void setShowSaveMessageButton(boolean value) {
        getPreferences().edit().putBoolean("showSaveMessageButton", value).apply();
    }

    public static boolean getShowRepeatMessageButton() {
        return getPreferences().getBoolean("showRepeatMessageButton", false);
    }

    public static void setShowRepeatMessageButton(boolean value) {
        getPreferences().edit().putBoolean("showRepeatMessageButton", value).apply();
    }

    public static boolean getShowClearButton() {
        return getPreferences().getBoolean("showClearButton", true);
    }

    public static void setShowClearButton(boolean value) {
        getPreferences().edit().putBoolean("showClearButton", value).apply();
    }

    public static boolean getShowHistoryButton() {
        return getPreferences().getBoolean("showHistoryButton", false);
    }

    public static void setShowHistoryButton(boolean value) {
        getPreferences().edit().putBoolean("showHistoryButton", value).apply();
    }

    public static boolean getShowReportButton() {
        return getPreferences().getBoolean("showReportButton", false);
    }

    public static void setShowReportButton(boolean value) {
        getPreferences().edit().putBoolean("showReportButton", value).apply();
    }

    public static boolean getShowGenerateButton() {
        return getPreferences().getBoolean("showGenerateButton", true);
    }

    public static void setShowGenerateButton(boolean value) {
        getPreferences().edit().putBoolean("showGenerateButton", value).apply();
    }

    public static boolean getShowDetailsButton() {
        return getPreferences().getBoolean("showDetailsButton", false);
    }

    public static void setShowDetailsButton(boolean value) {
        getPreferences().edit().putBoolean("showDetailsButton", value).apply();
    }

    public static boolean getGroupMessageMenu() {
        return getPreferences().getBoolean("groupMessageMenu", false);
    }

    public static void setGroupMessageMenu(boolean value) {
        getPreferences().edit().putBoolean("groupMessageMenu", value).apply();
    }

    public static String getRecognitionLanguage() {
        return getPreferences().getString("recognitionLanguage", "none");
    }

    public static void setRecognitionLanguage(String value) {
        getPreferences().edit().putString("recognitionLanguage", value).apply();
    }

    public static boolean getPostprocessingWithAi() {
        return getPreferences().getBoolean("postprocessingWithAi", false);
    }

    public static void setPostprocessingWithAi(boolean value) {
        getPreferences().edit().putBoolean("postprocessingWithAi", value).apply();
    }

    public static CameraType getCameraType() {
        return enumAt(CameraType.values(), getPreferences().getInt("cameraType", CameraType.CAMERA_1.ordinal()), CameraType.CAMERA_1);
    }

    public static void setCameraType(CameraType value) {
        getPreferences().edit().putInt("cameraType", value == null ? CameraType.CAMERA_1.ordinal() : value.ordinal()).apply();
    }

    public static boolean getExtendedFramesPerSecond() {
        return getPreferences().getBoolean("extendedFramesPerSecond", false);
    }

    public static void setExtendedFramesPerSecond(boolean value) {
        getPreferences().edit().putBoolean("extendedFramesPerSecond", value).apply();
    }

    public static boolean getCameraStabilization() {
        return getPreferences().getBoolean("cameraStabilization", false);
    }

    public static void setCameraStabilization(boolean value) {
        getPreferences().edit().putBoolean("cameraStabilization", value).apply();
    }

    public static boolean getCameraMirrorMode() {
        return getPreferences().getBoolean("cameraMirrorMode", false);
    }

    public static void setCameraMirrorMode(boolean value) {
        getPreferences().edit().putBoolean("cameraMirrorMode", value).apply();
    }

    public static VideoMessagesCamera getVideoMessagesCamera() {
        return enumAt(VideoMessagesCamera.values(), getPreferences().getInt("videoMessagesCamera", VideoMessagesCamera.FRONT.ordinal()), VideoMessagesCamera.FRONT);
    }

    public static void setVideoMessagesCamera(VideoMessagesCamera value) {
        getPreferences().edit().putInt("videoMessagesCamera", value == null ? VideoMessagesCamera.FRONT.ordinal() : value.ordinal()).apply();
    }

    public static boolean getRememberLastUsedCamera() {
        return getPreferences().getBoolean("rememberLastUsedCamera", false);
    }

    public static void setRememberLastUsedCamera(boolean value) {
        getPreferences().edit().putBoolean("rememberLastUsedCamera", value).apply();
    }

    public static boolean getStartWithWideAngleCamera() {
        return getPreferences().getBoolean("startWithWideAngleCamera", false);
    }

    public static void setStartWithWideAngleCamera(boolean value) {
        getPreferences().edit().putBoolean("startWithWideAngleCamera", value).apply();
    }

    public static boolean getZoomSlider() {
        return getPreferences().getBoolean("zoomSlider", false);
    }

    public static void setZoomSlider(boolean value) {
        getPreferences().edit().putBoolean("zoomSlider", value).apply();
    }

    public static boolean getStaticZoom() {
        return getPreferences().getBoolean("staticZoom", false);
    }

    public static void setStaticZoom(boolean value) {
        getPreferences().edit().putBoolean("staticZoom", value).apply();
    }

    public static boolean getAlwaysSendInHD() {
        return getPreferences().getBoolean("alwaysSendInHD", false);
    }

    public static void setAlwaysSendInHD(boolean value) {
        getPreferences().edit().putBoolean("alwaysSendInHD", value).apply();
    }

    public static boolean getHideCameraTile() {
        return getPreferences().getBoolean("hideCameraTile", false);
    }

    public static void setHideCameraTile(boolean value) {
        getPreferences().edit().putBoolean("hideCameraTile", value).apply();
    }

    public static int getDoubleTapSeekDuration() {
        return getPreferences().getInt("doubleTapSeekDuration", 0);
    }

    public static void setDoubleTapSeekDuration(int value) {
        getPreferences().edit().putInt("doubleTapSeekDuration", value).apply();
    }

    public static boolean getPreferOriginalQuality() {
        return getPreferences().getBoolean("preferOriginalQuality", false);
    }

    public static void setPreferOriginalQuality(boolean value) {
        getPreferences().edit().putBoolean("preferOriginalQuality", value).apply();
    }

    public static boolean getSwipeToPip() {
        return getPreferences().getBoolean("swipeToPip", false);
    }

    public static void setSwipeToPip(boolean value) {
        getPreferences().edit().putBoolean("swipeToPip", value).apply();
    }

    public static boolean getUnmuteWithVolumeButtons() {
        return getPreferences().getBoolean("unmuteWithVolumeButtons", false);
    }

    public static void setUnmuteWithVolumeButtons(boolean value) {
        getPreferences().edit().putBoolean("unmuteWithVolumeButtons", value).apply();
    }

    public static boolean getPauseOnMinimizeVideo() {
        return getPreferences().getBoolean("pauseOnMinimizeVideo", false);
    }

    public static void setPauseOnMinimizeVideo(boolean value) {
        getPreferences().edit().putBoolean("pauseOnMinimizeVideo", value).apply();
    }

    public static boolean getPauseOnMinimizeVoice() {
        return getPreferences().getBoolean("pauseOnMinimizeVoice", false);
    }

    public static void setPauseOnMinimizeVoice(boolean value) {
        getPreferences().edit().putBoolean("pauseOnMinimizeVoice", value).apply();
    }

    public static boolean getPauseOnMinimizeRound() {
        return getPreferences().getBoolean("pauseOnMinimizeRound", false);
    }

    public static void setPauseOnMinimizeRound(boolean value) {
        getPreferences().edit().putBoolean("pauseOnMinimizeRound", value).apply();
    }

    public static boolean getUseGoogleCrashlytics() {
        return getPreferences().getBoolean("useGoogleCrashlytics", false);
    }

    public static void setUseGoogleCrashlytics(boolean value) {
        getPreferences().edit().putBoolean("useGoogleCrashlytics", value).apply();
    }

    public static boolean getUseGoogleAnalytics() {
        return getPreferences().getBoolean("useGoogleAnalytics", false);
    }

    public static void setUseGoogleAnalytics(boolean value) {
        getPreferences().edit().putBoolean("useGoogleAnalytics", value).apply();
    }

    public static boolean getEnableAdBlock() {
        return getPreferences().getBoolean("enableAdBlock", true);
    }

    public static void setEnableAdBlock(boolean value) {
        getPreferences().edit().putBoolean("enableAdBlock", value).apply();
    }

    public static long getUpdateScheduleTimestamp() {
        return getPreferences().getLong("updateScheduleTimestamp", 0L);
    }

    public static void setUpdateScheduleTimestamp(long value) {
        getPreferences().edit().putLong("updateScheduleTimestamp", value).apply();
    }

    public static long getSdkUpdateScheduleTimestamp() {
        return getPreferences().getLong("sdkUpdateScheduleTimestamp", 0L);
    }

    public static void setSdkUpdateScheduleTimestamp(long value) {
        getPreferences().edit().putLong("sdkUpdateScheduleTimestamp", value).apply();
    }

    public static String getTargetLang() {
        return getPreferences().getString("targetLang", "");
    }

    public static void setTargetLang(String value) {
        getPreferences().edit().putString("targetLang", value).apply();
    }

    public static float getFlashWarmth() {
        return getPreferences().getFloat("flashWarmth", 0.0f);
    }

    public static void setFlashWarmth(float value) {
        getPreferences().edit().putFloat("flashWarmth", value).apply();
    }

    public static float getFlashIntensity() {
        return getPreferences().getFloat("flashIntensity", 0.0f);
    }

    public static void setFlashIntensity(float value) {
        getPreferences().edit().putFloat("flashIntensity", value).apply();
    }

    public static boolean getPluginsDevMode() {
        return plugins().isDeveloperMode();
    }

    public static void setPluginsDevMode(boolean value) {
        plugins().setDeveloperMode(value);
    }

    public static boolean getPluginsSafeMode() {
        return plugins().isSafeMode();
    }

    public static void setPluginsSafeMode(boolean value) {
        plugins().setSafeMode(value);
    }

    public static boolean getPluginsCompactView() {
        return plugins().isCompactView();
    }

    public static void setPluginsCompactView(boolean value) {
        plugins().setCompactView(value);
    }

    public static boolean getPluginsPySdkAutoUpdate() {
        return getPreferences().getBoolean("pluginsPySdkAutoUpdate", true);
    }

    public static void setPluginsPySdkAutoUpdate(boolean value) {
        getPreferences().edit().putBoolean("pluginsPySdkAutoUpdate", value).apply();
    }

    public static boolean getPluginsPySdkBetaVersions() {
        return getPreferences().getBoolean("pluginsPySdkBetaVersions", false);
    }

    public static void setPluginsPySdkBetaVersions(boolean value) {
        getPreferences().edit().putBoolean("pluginsPySdkBetaVersions", value).apply();
    }

    public static boolean getPluginsDisableArtOpts() {
        return plugins().isCompatibilityMode();
    }

    public static void setPluginsDisableArtOpts(boolean value) {
        plugins().setCompatibilityMode(value);
    }

    public static java.util.Set<String> getPinnedPlugins() {
        return readPinnedPlugins();
    }

    public static void setPinnedPlugins(java.util.Set<String> value) {
        writePinnedPlugins(value);
    }

    public static boolean getUseSystemIconShape() {
        return getPreferences().getBoolean("useSystemIconShape", false);
    }

    public static void setUseSystemIconShape(boolean value) {
        getPreferences().edit().putBoolean("useSystemIconShape", value).apply();
    }

    public static boolean getPluginsEngine() {
        return plugins().isEngineEnabled();
    }

    public static void setPluginsEngine(boolean value) {
        plugins().setEngineEnabled(value);
    }

    public static boolean getSectionsSeparatedHeaders() {
        return getDividerStyle() == DividerStyle.SEGMENTS || getSectionsSeparatedHeadersPreference();
    }

    public static void setSectionsSeparatedHeaders(boolean value) {
        setSectionsSeparatedHeadersPreference(getDividerStyle() == DividerStyle.SEGMENTS || value);
    }

    public static boolean getLogging() {
        return BuildVars.LOGS_ENABLED;
    }

    public static void setLogging(boolean enabled) {
        BuildVars.LOGS_ENABLED = enabled;
    }
}
