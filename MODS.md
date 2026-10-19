# AminGram — Mod Ledger

## Signing (v1.1)
- Releases are signed with AminGram's OWN keystore (alias `amingram`,
  RSA 2048, generated 2026-10-19) — no longer upstream's in-tree key.
  CI decodes it from the `AMINGRAM_KEYSTORE_B64` repo secret over
  `upstream/TMessagesProj/config/release.keystore` and overrides
  RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
  from repo secrets via gradle -P args. Backup keystore + credentials
  note live in `~/workspace/amingram/keystore/` (chmod 600, values
  never recorded here). v1.0 APKs were signed with upstream's key, so
  v1.1 installs as a NEW app signature (uninstall v1.0 first, or
  Android refuses the upgrade — expected one-time cost of the swap).

Base: official Telegram for Android (DrKLO/Telegram), tracked at release tags.
Rule: ALL custom code lives under `org.telegram.messenger.amin.**`.
Upstream files may only be touched with minimal hook calls into that package.
Every upstream file touched is listed here — this list is the rebase contract.
Target: keep this list under 30 files, each with 1–3 line hooks.

## Overlay file overrides (whole-file copies over upstream, v1.1)
- `TMessagesProj/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` +
  `ic_launcher_round.xml` — adaptive icons now point at the AminGram
  assets below (foreground/monochrome), backgrounds unchanged in name.
- `TMessagesProj/src/main/res/drawable/icon_background.xml` +
  `icon_background_round.xml` — replaced the layer-list chain with a
  solid deep-navy (#01030F) shape.
- New files: `drawable/amingram_foreground.xml` (blue shield #0275F2 +
  navy A cut-out + #03A9FD crossbar, 108×108 vector) and
  `drawable/amingram_mono.xml` (single-color silhouette for themed
  icons). Legacy pre-API-26 mipmap PNGs still show the Telegram plane —
  known cosmetic limit; the alternate in-app icons (icon_2…6) are
  untouched.

## Upstream files touched
- `org/telegram/ui/SettingsActivity.java` — 3 micro-hooks (base 13.0.2):
  import of `amin.ui.AminPluginsActivity`; an "AminGram" header + Plugins row
  (SettingCell id 90) in `fillItems`; `case 90` in the `onClick` switch →
  opens the Plugins hub. Rebase check: search for `case 90` and `AminGram`.

- `org/telegram/messenger/UserConfig.java` — LocalPremium: `isPremium()`
  returns true when the module is enabled (1 guard line, patch 0003).
- `org/telegram/messenger/MessagesController.java` — GhostMode:
  `completeReadTask` early-return (no read receipts sent) and the
  `updateTimerProc` online-status branch skipped while ghost is on;
  v1.1 adds typing suppression: the 5-arg `sendTyping` overload
  returns false immediately while ghost is on (one FQ guard,
  patch 0003) — no typing/record/upload indicators are sent.
  AntiDelete v1: the update-driven `deletedMessagesFinal` application
  block is skipped while enabled (patch 0003).
  AntiDelete v2 (v1.1, still patch 0003): one added call right before
  that block — `AminHooks.onMessagesDeleted(this, deletedMessagesFinal)`
  — moves matching entries from the pipeline's snapshot store
  (`amin.core.DeletedStore`, prefs `amingram_antidelete`) into a
  persistent "deleted" list, so deleted content survives restarts;
  the pipeline snapshots every incoming message while the module is
  enabled, and the hook falls back to the controller's in-memory
  message maps for messages that were never snapshotted. Viewer =
  hub "Deleted messages" row (`AminStoreListActivity`).
  AntiDelete v3 (audit fix, still patch 0003): the STORAGE purge in
  `processUpdateArray` (`if (deletedMessages != null)` →
  `markMessagesAsDeleted` + `updateDialogsWithDeletedMessages`) is
  now gated with `!AminHooks.antiDelete()` too. The v1.0/v1.1 code
  gated only the UI block, so messages were deleted from SQLite
  immediately and vanished on chat re-entry/restart — the module
  could not work as designed. With both gates, update-driven deletes
  are fully suppressed while the module is on (rows stay in the DB
  and in the UI; the DeletedStore snapshot/viewer remains as the
  record). User-initiated deletes (his own delete actions) are a
  different code path and are NOT blocked.
- `org/telegram/messenger/TranslateController.java` — FreeTranslate:
  premium gates in both `isFeatureAvailable` variants bypassed when
  enabled; the translate runnable routes to the built-in keyless
  `TranslateAlert2.alternativeTranslate` (Google public endpoint)
  instead of the server request (patch 0003).

- `org/telegram/messenger/MessagesController.java` — (in addition to the
  patch-0003 hooks above) Phase 4 pipeline: one line in
  `processUpdateArray` feeding `pushMessages` to
  `amin.AminHooks.onNewMessages` before the notification dispatch
  (patch 0006). The pipeline (`amin.core.MessagePipeline` + `VaultStore`)
  powers KeywordRadar hits, ConfigVault link collection and AutoSaver
  (immediate download of self-destruct media documents; v1.1/v2 also
  pulls the largest size of pure TL_photo secrets via ImageLocation,
  logged with a `|photo` suffix).
- `TMessagesProj_App/google-services.json` — added Firebase clients for
  `org.telegram.amingram` + `org.telegram.amingram.beta` (cloned from the
  upstream entries, patch 0005). Without this the google-services Gradle
  task fails the rebranded build ("No matching client found for package
  name 'org.telegram.amingram'", CI run #4). NOTE: this only satisfies the
  build; FCM push under Telegram's Firebase project will not deliver to
  the fork package at runtime — expected for a sideload fork.

## Patch-series note (v1.1)
Patches 0003 and 0006 both touch MessagesController; after the typing
gate was added, the series was REBUILT from the clone by staged
commits (base 0001/0002/0004/0005 → 0003 state = clone minus the one
pipeline line → 0006 = that line alone) and re-verified: the full
series applies cleanly on pristine f32ffee and reproduces the clone's
MessagesController byte-for-byte. When regenerating, never diff the
combined tree for a single patch — stage the split as above.

## Hook points (planned)
- `MessagesController` — message update/delete events → AntiDelete, plugin bus
- `ChatActivity` — chat screen lifecycle → VideoBackground, per-chat plugins
- `LaunchActivity` / settings controllers — Plugins hub entry
- `UserConfig` / premium checks → LocalPremium

## Feature modules (org.telegram.messenger.amin.modules)
- LocalPremium — force local premium state (client-side only)
- GhostMode — suppress read receipts / online status where client-controlled
- AntiDelete — cache messages locally; deleted ones stay visible, marked
- FreeTranslate — translation via free endpoints (no API key)
- EditHistory — (v1.2) pre-edit message text, from the AntiDelete
  snapshot store. Hook: ONE added line at the top of
  `MessagesController.processUpdateArray` (patch 0006) →
  `AminHooks.onRawUpdates(updates)`; `DeletedStore.recordEdits`
  matches TL_updateEditMessage / TL_updateEditChannelMessage
  (`org.telegram.tgnet.tl.TL_update.*`) against snapshots, records
  `dialog|id|date|old -> new` in the store (PREFS/EDIT_KEY) and
  re-snapshots the new text so chained edits diff correctly. The
  pipeline snapshots when anti_delete OR edit_history is enabled.
  Viewer: hub Tools → "Edit history". Limits: only messages received
  while a snapshot module was on; text flattened (newlines/pipes),
  media edits not tracked.
- HideStories — (v1.2) patch 0008: the two `hasStories =
  newVisibility;` assignments in DialogsActivity's stories-visibility
  updater gated with `&& !AminHooks.hideStories()` (2 lines, 1 file).
  Stories still load in the background; only the row/header hides.
- Icon changer — (v1.2) patch 0007 mirrors upstream's own mechanism
  (NOTE: aliases live in **TMessagesProj**'s AndroidManifest.xml,
  not the App module's): two activity-aliases
  `org.telegram.messenger.AminBlueIcon` / `AminMonoIcon` →
  LaunchActivity with overlay adaptive icons
  (@mipmap/amingram_icon_blue_launcher / _mono_launcher, API 26+;
  no legacy per-density PNGs), plus two entries in
  `LauncherIconController.LauncherIcon` (AMIN_BLUE / AMIN_MONO,
  non-premium) — required because ApplicationLoader calls
  tryFixLauncherIconIfNeeded() at startup and would reset any icon
  the enum doesn't know. Titles: strings AppIconAminBlue/AppIconAminMono
  (patch 0007 also touches strings.xml, a different region than
  0004's AppName hunk). Bonus: upstream's Appearance → App Icon UI
  lists the customs too. Hub: Plugins → Tools → "App Icon" row opens
  a picker over ALL LauncherIcon values and switches via
  `LauncherIconController.setIcon` (no extra patch).
- VideoBackground — mp4/gif as animated chat background. v1.1.1:
  `VideoBackgroundHelper` layer is INVISIBLE + TextureView non-opaque
  until `VideoPlayer` delegate `onRenderedFirstFrame`; `onError`
  removes the layer; `.gif` uses an `ImageView` +
  `AnimatedImageDrawable` (`ImageDecoder`, API 28+; static first
  frame below) because ExoPlayer cannot decode GIF; the ChatActivity
  `onResume` hook (patch 0004) calls `helper.shouldRelease(ctx)` and
  releases+sheds the layer when the module was toggled off or the
  file changed — open chats no longer keep a dead black layer.
  Hooks verified inside `ChatActivity.onResume()` (l.29887) /
  `onPause()` (l.30102) themselves.
  Audit fix (overlay-only, no patch change): playback now uses
  `VideoPlayer.setLooping(true)` + `preparePlayer(uri, "file")` —
  NEVER `preparePlayerLoop(uri, type, null, null)`: upstream's loop
  API unconditionally builds a second (audio) media source and
  `mediaSourceFromUri` switches on the null type, throwing an NPE
  inside `ChatActivity.createView` (the v1.0 chat-breaking crash;
  the v1.1.1 rewrite had kept the call). `attachVideo` is now fully
  try/catch-isolated and returns false on any failure, so a bad file
  or player error degrades to "no layer", never a crash.
- KeywordRadar — (phase 2) keyword alerts across chats
- ConfigVault — (phase 2) collects vless/sub/soar links from chats
  (audit fix: scheme match is now case-insensitive)
- AutoSaver — audit fix: module description no longer promises
  gallery saves (files land in the app media cache only); the saver
  log record now includes the document file name so a save is
  findable (v1.0 logged a bare dialog|id|date).

## Plugin system
- Registry: `amin.plugins.PluginRegistry` — modules self-register with a
  manifest (id, title, description, settings schema, enabled-by-default).
- Settings UI ("Plugins" hub) is generated from manifests.
- Runtime script plugins (JS/Python) are phase 3 — API surface will mirror
  the module events so scripts survive upstream rebases.
- Hub tools (overlay-only, no upstream files touched): `AminPluginsActivity`
  Tools section — video-background file picker (ACTION_GET_CONTENT →
  `VideoBackgroundHelper` path prefs; content:// URIs copied via
  `MediaController.copyFileToCache`), KeywordRadar keyword editor, and
  read-only `AminStoreListActivity` viewers for Config Vault / Radar hits /
  Auto-Saver log (all backed by `amin.core.VaultStore`; tap = copy, last
  row = clear). NOTE: `BulletinFactory.createSimpleBulletin` icons are
  `R.raw.*` Lottie ids, never `R.drawable.*`.
- Hook-call rule (learned from CI run #6): patch call sites in upstream
  files must use the fully-qualified `org.telegram.messenger.amin.…`
  name — a bare `amin.AminHooks.…` does not resolve from
  `org.telegram.messenger` and fails javac ("package amin does not exist").

## v1.3 batch (2026-10-19, on top of 851a9a6)

Driven by on-device verdicts on the TheLimoo build: Anti-Delete +
Local Premium confirmed working; Video Background rendered nothing;
Keyword Radar + Edit History caught nothing; Auto-Saver saved but its
photos were unviewable (raw log lines); Config Vault ordered DELETED.

New patches (all hook lines use fully-qualified
`org.telegram.messenger.amin.…` names; both CI files apply the full
list in filename order):
- 0006 REGENERATED — the two MessagesController hook lines now pass
  `currentAccount`: `AminHooks.onRawUpdates(updates, currentAccount)`,
  `AminHooks.onNewMessages(pushMessages, currentAccount)`.
- 0009 profile-user-id (ProfileActivity): new `aminIdRow` — assigned
  in `updateRowsIds()` for self (own id, in Settings → My profile),
  for other users, and for groups/channels (chat id). View type
  VIEW_TYPE_TEXT_DETAIL; binds `String.valueOf(userId|chatId)` with
  label "ID"/"Chat ID"; tap copies via `AndroidUtilities.addToClipboard`
  + `createCopyBulletin(TextCopied)` (the file's own pattern).
- 0010 js-runtime-gradle (TMessagesProj/build.gradle): adds
  `implementation 'org.mozilla:rhino:1.7.15'` (verified on Maven
  Central; root + module build.gradle both declare mavenCentral();
  release is unminified so Rhino reflection works).
- 0011 hidden-chats-dialogs-filter (DialogsActivity.getDialogsArray):
  wraps the three upstream-list returns (DEFAULT, FORWARD, type 7/8
  unfiltered) with `AminHooks.filterHiddenDialogs(…)` — returns the
  list untouched unless Hidden Chats is on AND locked AND the hidden
  set is non-empty; otherwise a filtered copy (upstream lists are
  never mutated). NOTE: global SEARCH uses a different upstream path
  and is NOT filtered (documented bound).
- 0012 ghost-stories (StoriesController.markStoryAsRead 3-arg): after
  the `justUploaded` reset, `if (AminHooks.ghostStoriesEnabled())
  return false;` — placed at the single choke point BEFORE both the
  local `dialogIdToMaxReadIdStore` write and the
  `TL_stories_readStories` RPC send, so neither happens.
- 0013 outgoing-command-hook (SendMessagesHelper.sendMessage): first
  line `AminHooks.onOutgoingMessage(sendMessageParams)` — the only
  universal text-send point in 13.0.2; fires JS plugin onCommand for
  the user's own outgoing "/" commands.
- 0014 attach-menu-plugins (ChatAttachAlert.ButtonsAdapter): four
  touch points — (1) fields aminPluginStartRow/aminPluginEndRow/
  aminMenuPlugins; (2) resets in notifyDataSetChanged(); (3) in the
  main (ChatActivity) branch, after the attachMenuBots block, rows
  are assigned from `AminHooks.attachMenuPlugins()` (pure prefs read:
  enabled plugins with the `menu` capability and an onMenuAction
  hook); (4) bind branch gives them view-type BUTTON with tag/id
  -1000-index (no collision with upstream ids 1..12 / LAYOUT_TYPE_*)
  and a click branch right after the click tag is read dispatches
  `AminHooks.onPluginMenuAction(id, dialogId)` and dismisses.

Module changes (overlay):
- ConfigVault DELETED completely: ConfigVaultModule +
  core/ConfigExtractor + registry entry + pipeline call + hub viewer.
  (Keyword extraction it shared code with was never shared in the
  current tree — Radar has its own matcher in MessagePipeline.)
- VideoBackground REWORK (overlay-only; patch 0004 unchanged): root
  cause of "renders nothing" — the layer was inserted during
  createView, but ChatActivity only adds chatListView to contentView
  at ~l.7027 (after the 0004 hook at ~l.4657), and visibility was
  gated solely on the texture first-frame callback. Now:
  attachIfEnabled() only validates (module on, file exists AND
  length > 0) and stores parent+path; the layer is inserted at
  resume() time at the index of the first RecyclerView child of
  contentView (directly beneath the message list, above the
  wallpaper), and revealed on media3 STATE_READY **or**
  onRenderedFirstFrame; center-crop via TextureView Matrix on
  onVideoSizeChanged; GIF path deferred identically. Evidence:
  ChatActivity.createView builds contentView (FrameLayout) first,
  wallpaper views next, chatListView added later (l.7027);
  VideoPlayer.ensurePlayerCreated() applies a stored textureView,
  so set-before-prepare order was never the problem.
- KeywordRadar FIX: root cause — it only ever saw live pushMessages
  arrivals. Now ALSO scan-on-chat-open: AminHooks registers a
  NotificationCenter observer for messagesDidLoad (the lightest
  verified choke point — posted by MessagesController at
  l.12209/12213 with the loaded ArrayList<MessageObject> at
  args[2]) and scans the loaded history generously (~everything
  loaded, deduped via VaultStore.addUnique). Live scan unchanged;
  matching stays case-insensitive contains. Hub: hit count in the
  module STATUS line + "Radar hits" viewer + last-hit snippet.
- EditHistory FIX: root cause — snapshot-coverage gap: snapshots
  only existed for live arrivals, so edits of history-loaded
  messages found no "before" text. The same messagesDidLoad
  observer now snapshots loaded messages when anti_delete OR
  edit_history is on (DeletedStore.snapshotAll, one prefs
  read/write per batch). Capture itself was already correct:
  onRawUpdates runs at the top of processUpdateArray, before
  TL_updateEditMessage/TL_updateEditChannelMessage are applied.
- AutoSaver v3: (a) structured store (core/AutoSaverStore, JSON
  entries dialog|id|date|kind|name|path|attach; legacy pipe-log
  entries are read-merged, files resolved by recorded path or by
  attach-name scan of FileLoader dirs CACHE/IMAGE/VIDEO/DOCUMENT);
  (b) capture now covers view-once media in normal chats too —
  MessageObject.isSecretMedia() already returns true for
  TL_message media with ttl_seconds != 0 (photo or document), in
  addition to secret chats; documents are saved via
  FileLoader.loadFile(document, …) with the file recorded from
  FileLoader.getPathToMessage, photos via the same photo-size
  selection getPathToMessage uses. (c) Real gallery: hub → Saved
  media → AminSavedMediaActivity (3-column grid, thumbnails,
  chat title + date captions) → tap → AminPhotoViewActivity
  (full-screen image / VideoView, save-to-gallery via MediaStore,
  share via FileProvider, remove entry; honest "file gone" state
  when Telegram's cache no longer holds the file).
- Ghost for Stories — new module ghost_stories (patch 0012 above).
  Opening stories still counts views server-side only via the read
  RPC we now never send; story CONTENT still downloads normally.
- Hidden Chats — new module hidden_chats + core/HiddenChatsStore
  (prefs amingram_hidden; ids as JSON strings; PIN stored ONLY as
  SHA-256 hex with a static salt; unlocked state is memory-only,
  resets on process restart). Hide/unhide is hub-driven (dialog
  picker AminDialogPickerActivity) — chosen deliberately over
  DialogsActivity long-press surgery; list filtering is patch 0011.
- Send When Online — new module send_when_online +
  core/SendWhenOnlineStore (prefs amingram_swo). Pending entries
  are watched inside AminHooks.onRawUpdates: a
  TL_updateUserStatus whose status is TL_userStatusOnline for a
  queued peer triggers SendMessagesHelper.sendMessage(
  SendMessageParams.of(text, peer)) and marks the entry sent.
  Presence-driven ONLY — no polling, no timers. Honest bound:
  fires whenever Telegram's sync processes the presence update
  (including background sync); if Android has force-stopped the
  app, it fires at next launch. Hub: composer (user picker +
  text), pending list, tap-to-cancel/remove.
- AminGram Backup — core/BackupManager + hub rows (Tools):
  export/import ONE JSON file via SAF (create/open document)
  covering plugin on/off states, radar keywords + hits, video-bg
  path (the video FILE is not embedded — path only), hidden ids +
  PIN hash, Send-When-Online entries, anti-delete snapshots /
  deleted / edit stores, auto-saver entries, and JS plugins
  (manifest meta + full code + per-plugin storage; reinstalled
  through the normal validator).
- Hub redesign: sections Privacy / Media / Tools / Your Plugins in
  registry order (ModModule.section()); every module row shows its
  description plus a live STATUS line (ModModule.status():
  "Active", "Needs a video file", "Needs keywords", "N hits · last:
  …", "N saved", "Locked · N hidden", "N waiting"…). Module actions
  sit directly under their module. Upstream cells only
  (HeaderCell/TextCheckCell/TextCell/TextInfoPrivacyCell).

## JS plugin runtime (v1.3, org.telegram.messenger.amin.plugins.js)
- Engine: Mozilla Rhino 1.7.15 (patch 0010). Interpreted mode
  (optimizationLevel -1), ES6 language version.
- Sandbox: initStandardObjects scope with Packages/java/javax/
  JavaAdapter/importClass/getClass deleted; a ClassShutter allows
  ONLY AminBridge, AminBridge$Storage and java.lang scalars — no
  other Java class is reachable from JS.
- Contract = docs/PLUGIN-MASTER-PROMPT.md (shipped in repo docs/;
  the runtime matches it exactly): manifest header
  (==AminGramPlugin== with @id/@name/@version/@author/@description/
  @capabilities — unknown capability = invalid install), hooks
  onLoad/onMessage/onCommand/onMenuAction, optional SETTINGS schema
  (bool/text), bridge `amin` = log/toast/storage(per-plugin prefs)/
  send/reply/httpGet (blocking, 15 s timeouts, 1 MB cap).
- Install path (file picker OR paste in hub): manifest parse →
  Rhino compileString syntax check → probe evaluation in a
  throwaway scope (extracts SETTINGS + which hooks exist; onLoad is
  NOT called at install) → code stored in app-private
  filesDir/jsplugins/<id>.js + meta in prefs. Same path for the
  four bundled one-tap installs (assets amingram_plugins/: shout,
  vip-alert, media-dl, temp-mail — the latter two per Amin's specs;
  both use only documented bridge calls, no adjustments needed).
- Dispatch: runtimes are created lazily on Utilities.globalQueue
  (the single dispatch thread — plugin code never runs on the UI
  thread); onLoad runs once per runtime creation. Incoming messages
  → dispatchMessage (observe; return true stops later plugins);
  own "/" commands → patch 0013 (this device) and the update
  pipeline (other devices); attach-menu buttons → patch 0014.
  Errors are caught per hook, written to the plugin's log (hub →
  plugin page → View log), and never crash the app or other
  plugins; a plugin whose code fails to evaluate is skipped for
  the session and the failure logged. NO auto-throttle /
  auto-disable (per Amin's power recalibration — features stay
  full-strength; isolation only).
- Plugin page (AminJsPluginActivity): About info, enable switch,
  settings generated from SETTINGS (values live in the same store
  amin.storage reads), log viewer, delete.

## v1.3.2 (2026-10-20, on top of 5d34ae4) — overlay-only, no new patches

- Video background durability (hub, AminPluginsActivity): a picked
  video/GIF is now ALWAYS copied into app-private storage
  (filesDir/amingram/videobg.<ext>) via ContentResolver and that
  durable path is stored in the amingram_videobg prefs. Previously
  the hub stored the picker's getPath() result or a
  MediaController.copyFileToCache copy — Telegram's cache dir is
  purged, so the stored path dangled after a restart and the helper
  (validation unchanged) attached nothing. The real extension is
  derived (path suffix → MimeTypeMap from the content type →
  "mp4"), which also fixes GIF picks (the old hardcoded "mp4" copy
  never matched VideoBackgroundHelper.isGif); the picker now
  offers image/gif next to video/*.
- Plugins hub card redesign (AminPluginsActivity, presentation
  only — same row kinds, action ids, flows and data): rows are
  grouped into card segments (assignSegments: SEG_FIRST/MID/
  LAST/ONLY; a card = module/plugin header + its info + its
  action rows, each bundled row + tagline, the two add-plugin
  rows). Custom views in the hub adapter: SectionLabelView
  (uppercase gray section labels), CardHeaderView (40dp
  accent-tinted icon chip with a verified 13.0.2 R.drawable glyph
  per module — letter fallback — 16sp title, display-only
  upstream Switch), InfoStatusView (one-line ellipsized subtitle
  + separate status line: colored dot + text; tones green /
  red / gray from Row.statusTone). Action rows remain TextCells
  rendered as card segments (accent-tinted fill + "+ " prefix for
  the add-plugin card); description and status are separate Row
  fields now instead of one glued "\n" string.

## Power design (v1.3, recalibrated by Amin 2026-10-19)
Design goal: **no noticeable extra drain vs official Telegram** —
features full-strength and unrestricted in feel. Baseline sanity
only, matching what the official app itself does: no wakelocks, no
polling loops, no periodic timers of our own; everything rides
Telegram's existing update flow and queues (NotificationCenter
posts, processUpdateArray, globalQueue). Video background plays
while its chat is open and pauses on leaving, like any media in
the app. Radar scan-on-open covers the loaded history generously.
