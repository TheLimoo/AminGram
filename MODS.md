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
- LocalPremium v2 (AyuGram-style, still patch 0003) — the module
  (`amin.modules.LocalPremiumModule`, prefs `amingram_local_premium`)
  grew sub-toggles (premium flag / picker unlock / name emoji /
  emoji status / peer colors, all default ON, narrowing the master
  switch only) and per-account picks keyed by user id:
  `MessagesController.isPremiumUser()` returns true for the self
  user while the flag is on (premium badges for own account);
  `UserObject.getUserName()` appends the picked name emoji's base
  glyph for the self user; `UserObject.getEmojiStatusDocumentId(User)`
  returns the locally stored status document for the self user;
  `UserObject.getColorId()` / `getProfileColorId()` return the local
  name/profile color ids for the self user; `ReplyMessageLine`
  resolves its color through `AminHooks.replyColorId()` (local reply
  color, falling back to `getColorId`); `EmojiView`'s eight premium
  pack gates (emoji cell, premium sticker cell, pack install button,
  premium recents, search + three adapter flags) additionally
  consult `AminHooks.pickerPremiumBlocked()` so the picker
  sub-toggle can re-lock the packs while the flag stays on.
  Everything renders locally only — the server still sees a free
  account. Hub rows live under the module in `AminPluginsActivity`
  (upstream `SelectAnimatedEmojiDialog` popup for the emoji picks,
  `MessagesController.peerColors`/`profilePeerColors` palettes for
  the color picks).
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
- 0015 design-startup-hook (v1.3.3, LaunchActivity.onCreate): one
  FQ line right after `ApplicationLoader.postInitApplication()` —
  `org.telegram.messenger.amin.AminHooks.applyDesignOnStartup();` —
  so an enabled design module (amin_design / ios_theme) owns the
  day theme from the very first frame, not just after the hub is
  opened. No-op unless a design is on AND the current theme is not
  already that design (guard inside DesignThemes), so it cannot
  loop on activity recreation. Added to both CI files' apply
  lists; the full series (0001–0015) re-verified on pristine
  f32ffee: applies cleanly, all 17 patch-touched files reproduce
  the working clone byte-for-byte.

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

## v1.3.2 audit pass (2026-10-20, overnight — full line-by-line review)

- **Plugin dispatch moved OFF Telegram's shared queue**: JsPluginManager
  now runs everything on one dedicated HandlerThread ("AminGramPlugins",
  PluginQueue holder). A plugin's blocking amin.httpGet (up to ~30 s) used
  to stall Utilities.globalQueue, which Telegram itself uses. Rhino's
  single-thread confinement is unchanged (all runtime state still lives
  on the one plugin thread).
- **Crash-class guard completed**: enterContext() now sits INSIDE the
  Throwable guard at every dispatch entry (warmUp, message, command,
  outgoing command, menu action), with Context.exit() only when a
  context was actually entered — the same defect class as the v1.3
  install crash, in the remaining places it could occur.
- **Reinstall revives broken plugins**: install() now clears the
  session 'broken' flag; previously a plugin that failed evaluation
  once stayed dead until toggled or the app restarted, even after
  reinstalling fixed code. The 'broken' set is also synchronized now.
- **Readable viewers everywhere**: the Deleted-messages viewer and the
  Radar-hits viewer now render title + time + content like Edit
  History does (DeletedStore.formatDeletedRecord,
  MessagePipeline.formatRadarHit), instead of raw pipe records.
- **VaultStore writes synchronized** (addUnique/putAll/clear) — cheap
  insurance against read-modify-write races between the update thread
  and the UI.
- Audited with no change needed: AminHooks, AminBridge, JsManifest
  (byte-verified), BackupManager, HiddenChatsStore,
  SendWhenOnlineStore, AutoSaverStore, VideoBackgroundHelper, the
  dialog picker, the saved-media viewer, PluginRegistry and all
  module classes. Accepted as-is (noted, not fixed): DeletedStore's
  per-message snapshot rewrite (works; a power nit only), SWO bulk
  mark-sent, observer registration covering accounts activated at
  first check.

## v1.3.3 (2026-10-20, on top of 432f05c) — extera hub, engine switch, design themes

Directed by Amin after seeing exteraGram's UI: the hub speaks
extera's design language, a design becomes THE app design (no
preset switcher UI — his simplification), iOS stays an optional
module, and two more bundled JS plugins land. LOCAL ONLY (no push,
no build).

- Hub redesign (AminPluginsActivity, presentation; every row id,
  action id, data flow and the durable video picker unchanged):
  * ONE rounded group card (radius 22dp) per module SECTION —
    assignSegments now joins all of a section's module/info/action
    rows into a single card; hairlines inset from the text column
    (InfoStatusView text + closing divider at 68dp; action rows
    keep TextCell's own 20dp-inset divider, shown only mid-card).
  * Section labels: ACCENT color (key_windowBackgroundWhite
    BlueText), rmedium, natural case — no more gray uppercase.
  * Status scheme unchanged and uniform: subtitle = description
    (one line, ellipsized); status = colored dot + tiny line only
    when on / needs attention (Off / Active / Needs…).
  * Hub header block: centered shield (R.drawable.
    amingram_foreground on its navy #01030F, 20dp radius) +
    "AminGram" + package versionName.
  * "Enable Plugins Engine" — full-width ACCENT PILL (28dp
    radius, white label, display-only Switch; row click toggles),
    backed by a REAL master pref (see engine switch below).
  * JS plugins are RICH CARDS (new KIND_PLUGIN_CARD +
    PluginCardView; the KIND_PLUGIN_TOGGLE / KIND_PLUGIN_INFO row
    pair is gone): chip + working switch (transparent hit area —
    the upstream Switch stays display-only), 19sp name,
    "Version X · @author" (author accent-spanned), description
    ≤2 lines, capability chips (accent on chipFill), bottom action
    row — settings (msg_settings → plugin page), log (msg_log →
    the page's own log viewer), delete (msg_delete, key_color_red
    → the page's exact confirm dialog + JsPluginManager.delete +
    "Plugin deleted" bulletin). Card tap opens the plugin page.
    Cards dim to 55% content alpha while the engine is off.
  * Bundled installs stay slim action rows (Installed — tap to
    update preserved) in mini-cards with flat (16dp) taglines;
    add-plugin rows keep their accent card, now after the bundled
    cards (pill → plugin cards → bundled → add → note).
  * moduleIcon additions (verified 13.0.2 glyphs): amin_design →
    msg_palette, ios_theme → msg_colors; card action glyphs
    msg_settings / msg_log / msg_delete verified in-tree.

- Engine kill-switch (JsPluginManager): new pref
  `engine_enabled` in amingram_jsplugins (default true) with
  isEngineEnabled/setEngineEnabled. Gates: hasEnabledPlugins,
  menuPlugins (attach menu shows nothing), dispatchMessage,
  dispatchOutgoingCommand, dispatchCommandInternal,
  dispatchMenuAction and warmUp (enabling a plugin while the
  engine is off does not start its runtime). Engine off = no JS
  plugin runs, anywhere.

- Design themes (amin.core.DesignThemes + modules AminDesign
  Module `amin_design` and IosThemeModule `ios_theme`, both
  SECTION_TOOLS, registered FIRST so they lead the Tools section;
  amin_design enabledByDefault = TRUE, ios_theme default off).
  Mechanism — only 13.0.2 APIs verified in the tree (Theme.java):
  the design's .attheme file is built from the COMPLETE color set
  of a builtin base (Theme.getThemeFileValues(null, "night.
  attheme" / "day.attheme", null) — partial files would fall back
  to compiled Blue defaults for missing keys), a curated palette
  subset overridden (keys resolved via ThemeColors.
  stringKeyToInt, serialized via ThemeColors.getStringName,
  key_wallpaperFileOffset skipped), then applied with
  Theme.applyThemeFile(file, name, null, false) — the upstream
  user-theme path: registers + persists the theme, full refresh
  via the standard applyTheme/NotificationCenter flow. Before
  applying, the current theme's key (Theme.getCurrentTheme().
  getKey()) is snapshotted into prefs amingram_designs/
  prev_theme_key — unless the current theme is already one of
  ours. Disable → Theme.applyTheme(snapshot) restores the user's
  own theme (builtin "Blue" fallback if the snapshot theme was
  deleted). The two designs are mutually exclusive (hub toggle
  handles it; the snapshot is never overwritten by our own
  themes). Startup: patch 0015 (above) — DesignThemes.
  applyEnabledDesigns reconciles drift (design on but a foreign
  theme active → re-apply, snapshotting the foreign theme as the
  restore target; no design on but ours stuck active → restore).
  Design file names: amingram-design.attheme /
  amingram-ios.attheme under filesDir/amingram/.
  Palettes (all key names verified present in ThemeColors):
  AminGram Design (32 keys on the Night base) — page
  windowBackgroundGray #0B0B0E, surface windowBackgroundWhite
  #1C1C21, accent #6E9BFF (blue-text/value keys, switch-checked,
  chats action), text #FFFFFF / #8E8E93 / hint #636368, divider
  #2A2A2E, bubbles in #26262B / out #3A5BA9 (deep periwinkle,
  white text). iOS Theme (32 keys on the Day base) — white
  surfaces, grouped windowBackgroundGray #F2F2F7, iOS blue
  #007AFF (blue-text/value/action-bar icon/action), label
  #000000 / secondary #8A8A8E / hint #AEAEB2, divider #C6C6C8,
  in-bubble #E9E9EB, out-bubble #007AFF with white text, switch
  green #34C759 on #E9E9EB track, service pill #D3E9E9EB.
  Bounds: a design occupies the DAY theme slot like any file
  theme (a separately configured auto-night theme is untouched);
  wallpaper keys are deliberately not overridden (never fight
  Video Background); theme accents derive from the palette the
  upstream way.
  Amin's scope call (2026-10-20): NO design-picker / preset
  switcher UI was built — AminGram Design IS the design.

- Bundled JS plugins 5 + 6 (assets amingram_plugins/, registered
  in BundledPlugins; canonical copies also in
  ~/workspace/amingram/plugins/):
  * aesthetic-text (commands, send, menu): /wide (fullwidth:
    ASCII 33–126 → U+FF01 block, space → U+3000), /space (letters
    spread), /fliptext (upside-down map, all outputs BMP so the
    char reversal can't split surrogate pairs); attach-menu
    button posts the command hint in the chat.
  * quick-translate (commands, send, http, settings): /tr <text>
    (+ optional leading language code, /tr en …) via the keyless
    translate.googleapis.com gtx endpoint, defensive array parse,
    translation sent as a reply; default target setting "target"
    (text, def "fa"); httpGet null / parse failure → one clean
    error reply.
  Both validated by evaluating them in a real Rhino 1.7.15 with a
  stubbed amin bridge (wide/space/flip outputs codepoint-checked;
  translate fa default + en override + usage + ignore-others).

- Verified this round: JDK17 syntax parse clean on all touched
  Java; full patch series incl. 0015 applies on pristine f32ffee
  and reproduces the clone byte-for-byte (17 files); amin trees
  byte-identical repo == overlay == clone (the clone had drifted
  behind the v1.3.2 audit commit — re-mirrored from the repo);
  patches dir identical repo == overlay; both CI files carry the
  0015 apply line.
- NOT verified (needs a build + device): visual result of the
  hub redesign and of both designs on-device; theme apply at
  first launch after upgrade (amin_design defaults ON — intended:
  upgrading users get the design immediately, stock theme kept as
  the restore snapshot); Rhino-on-Android behavior of the two new
  plugins beyond the desktop-Rhino contract checks.

## v1.3.4 (2026-10-20, on top of 4c97e7a) — Release 7 field fixes

- Patch 0010 (edited): R8 keeps for the JS bridge. Rhino reaches
  AminBridge reflectively by JS name (log/toast/send/reply/
  httpGet, the storage field, Storage.get/set) and R8 had
  renamed all of it (plugins printed the bridge as fe.b and
  every call failed). Patch 0010's proguard additions now end
  with `-keep class org.telegram.messenger.amin.plugins.js.AminBridge { *; }`
  and the `AminBridge$Storage` line. Rule going forward: ANY
  class JS (or other reflective code) reaches by name needs a
  name-preserving keep — the ClassShutter whitelist in
  JsPluginManager is the checklist (it contains exactly these
  two of ours; the java.lang entries are never renamed). Hunk
  header updated +57,9 → +57,12; patch re-verified byte-exact.

- Bundled plugins are PRE-INSTALLED (no Install buttons):
  BundledPlugins.ensureInstalled runs once per process from
  JsPluginManager.loadCache and installs every never-delivered
  bundled plugin through the normal path. Delivery set:
  "bundled_seen" (JSONArray of ids) in the amingram_jsplugins
  prefs — written only for ids that actually end up installed.
  A user-deleted bundled plugin is never auto-restored; the hub
  shows it as "Built-in · deleted — tap to reinstall". Installed
  bundled plugins render as normal rich cards and get no hub
  rows at all.

- Hub fixes: (a) the engine pill's height is pinned at exactly
  56dp (EnginePillView.onMeasure + the adapter's LayoutParams)
  — v1.3.3's wrap-content pill measured to the full slot
  height on device and rendered as a giant empty accent block.
  (b) PluginCardView's header and actions containers are
  clickable: RecyclerListView suppresses its item click only
  for a clickable DIRECT child of the item view, and the
  card's buttons are nested two levels deep — without this,
  action taps fired both the button and the card's page-open.

- Hidden Chats has a dedicated page (ui/
  AminHiddenChatsActivity, extera hub language): set-PIN card
  when no PIN exists, an Unlock row when locked, and when
  unlocked the hidden-chats card (name + kind + Unhide chip
  per row), "Hide a chat…" (AminDialogPickerActivity), and a
  PIN card (Change PIN with current-PIN check, Lock now). The
  hub's hidden_chats actions collapse to ONE row, "Manage
  hidden chats" (value: Set a PIN / Locked / N hidden).
  HiddenChatsStore semantics unchanged.

- Stories row: patch 0008's gates verified passthrough when
  Hide Stories is off (and dialogStoriesCellVisible is not
  gated at all); no other patch touches stories. As a
  self-heal, AminHooks.applyDesignOnStartup schedules a
  one-shot +4s NotificationCenter.storiesUpdated post when
  Hide Stories is off (upstream posts the same from
  LaunchActivity; DialogsActivity's handler is a pure
  recompute). Root cause of the Release 7 disappearance not
  proven from code — see DEVLOG item 5 for the full audit and
  the honest uncertainty.

- Viewer BiDi: DeletedStore.formatEditRecord /
  formatDeletedRecord and MessagePipeline.formatRadarHit
  prefix lines with U+200E and wrap titles in U+2066…U+2069
  LTR isolates, so RTL titles no longer flip the line order.

- Verified: JDK17 JavacTask parse clean (39 amin files,
  UTF-8); patch series 0001–0015 on pristine f32ffee
  reproduces the clone byte-for-byte (17 files); amin trees
  identical repo == overlay == clone.
- NOT verified (needs the v1.3.4 build + device): bridge calls
  actually executing in plugins, bundled pre-install on first
  launch + delete-sticks, pill rendering, card tap isolation,
  stories row recovery, the hidden-chats page flows.

## v1.3.5 (2026-10-20, on top of f87af55) — Google account auth for plugins

Amin's ask: plugins should call Gemini with HIS OWN Google
account's free quota — native OAuth, no API key typed anywhere.

- **Dependency (patch 0016-google-auth, NEW)**: play-services-auth
  20.4.0 → **21.6.0**. Evidence for the bump: the 20.4.0 AAR
  contains `Identity` but NO AuthorizationClient (checked by
  downloading the real AARs from Google Maven and listing /
  javap-ing them); 21.6.0 has the full Authorization API —
  `Identity.getAuthorizationClient(Context)`,
  `AuthorizationClient.authorize/getAuthorizationResultFromIntent/
  revokeAccess/clearToken`, `AuthorizationRequest.builder()
  .setRequestedScopes(List<Scope>).setAccount(Account)`,
  `AuthorizationResult.hasResolution/getPendingIntent/
  getAccessToken/toGoogleSignInAccount`. 21.6.0 is the newest
  21.x; its transitive play-services-base 18.5.0 carries
  GoogleSignInAccount. Gradle resolves the shared GMS modules to
  the newest requested versions, as the existing mixed GMS pins
  already rely on. NOT compile-verified locally (no full build
  on this VM) — first CI run is the proof.
- **Manifest (same patch 0016)**: one `<activity>` block in
  TMessagesProj/src/main/AndroidManifest.xml (after ShareActivity):
  `org.telegram.messenger.amin.google.GoogleAuthActivity`,
  exported=false, transparent theme, excludeFromRecents.
- **Native manager (amin.google.GoogleAuthManager)**: scopes
  `https://www.googleapis.com/auth/generative-language` + `email`
  (as built in v1.3.5; superseded in v1.3.6 — see the v1.3.6 section).
  Token cache {token, expiry, email} as ONE blob encrypted with
  an AndroidKeyStore AES-256/GCM key (alias amingram_google_auth)
  in prefs "amingram_google" — hand-rolled because
  EncryptedSharedPreferences is deprecated. Tokens cached for a
  conservative 50 min; getValidToken serves the cache while
  >60 s remain, else silently re-authorizes; a result with
  hasResolution (or no grant) fails with a readable error —
  interactive consent is never attempted silently. Disconnect:
  local cache cleared + best-effort clearToken/revokeAccess.
  One interactive flow at a time (static pending-callback
  registry in the manager).
- **GoogleAuthActivity (amin.google)**: transparent trampoline
  Activity. authorize() → direct token, or hasResolution →
  startIntentSenderForResult(PendingIntent) →
  getAuthorizationResultFromIntent → token. Saves via the
  manager, delivers to the pending callback, finishes; destroy
  without a result completes the callback as cancelled. Serves
  the settings page and plugin sign-in alike.
- **Settings UI**: hub Tools card gains a "Google Account" row
  (value: connected email / "Connected" / "Not connected") →
  ui/AminGoogleActivity (extera language like the Hidden Chats
  page): status card, Connect / Disconnect (confirm dialog),
  and the honest footer (capability-gated tokens, on-device
  storage, Play Services required, periodic re-consent for
  unverified apps is Google's behavior — roughly weekly in
  testing mode).
- **Bridge (AminBridge)**: new nested `GoogleAccess`
  (isSignedIn / getAccessToken(fn) / signIn(fn)) carried ONLY
  by the new `AminBridge.WithGoogle` variant, which
  JsPluginManager.newScope builds when the plugin's manifest
  declares the NEW `google` capability (JsManifest's known set
  extended; install probe + runtimes both pass capabilities).
  For other plugins amin.google is genuinely undefined — the
  gate is which bridge class gets instantiated, not a runtime
  check. Callbacks are async: JsPluginManager.
  invokePluginCallback posts to the plugin thread, enters a
  fresh Rhino context inside the Throwable guard, and calls the
  function in its captured parent scope; deleted plugins'
  pending callbacks are dropped. ClassShutter whitelist +=
  WithGoogle + GoogleAccess; patch 0010's proguard block +=
  the two matching `-keep` lines (hunk header +57,12 → +57,15)
  — same R8 lesson as v1.3.4.
- **amin.httpPost(url, body, headersObj)** added to the bridge
  (httpGet's style: 15 s timeouts, 1 MB cap, body or null —
  the null-on-HTTP-error part is superseded by v1.3.7, below;
  JSON Content-Type default; headers from a JS object) — the
  Gemini call needs POST + an Authorization header.
- **Bundled plugin #7 — gemini-chat** (capabilities commands,
  send, http, google, settings, menu): /ai <prompt>; SETTINGS
  model (text, def "gemini-2.5-flash") + systemPrompt (text,
  def ""). Not signed in → amin.google.signIn and the pending
  prompt continues in its callback; token → httpPost to
  generativelanguage.googleapis.com/v1beta/models/<model>:
  generateContent with the Bearer header; defensive
  candidates[0].content.parts[*].text parse; one clean error
  reply per failure mode; menu action posts a hint. Registered
  in BundledPlugins → pre-installs like the others.
- Honest bounds: Play Services required (no GMS = no sign-in);
  online access only (this API issues no refresh token to the
  app — silent re-authorize IS the refresh); ~1 h token life;
  unverified-app re-consent is Google's policy; process death
  during the consent screen loses that callback (the grant
  survives; the next silent fetch succeeds).
- Verified locally: JDK17 JavacTask parse clean (42 amin files);
  FULL patch series 0001–0016 applies on pristine f32ffee and
  reproduces the clone byte-for-byte for every patch-touched
  file; amin trees byte-identical repo == overlay == clone;
  JsManifest (compiled standalone) accepts all 7 bundled
  manifests incl. the new `google` capability and still rejects
  unknown ones; gemini-chat validated in a real Rhino 1.7.15
  harness with a stubbed bridge (google + httpPost): 20/20
  checks — signed-in happy path (URL, Bearer header, body),
  sign-in-then-continue, sign-in declined, API error, token
  failure, usage, settings (model + system prompt), safety-block
  shape, menu hint, foreign-command ignore.
- NOT verified (needs the build + device): the real consent
  flow on a phone (account picker, resolution intent, email
  actually present in AuthorizationResult), Gradle's GMS
  version resolution with the bump, R8 keeps on the packaged
  build, and Gemini answering with a real account token.

## v1.3.6 (2026-10-20, on top of 09b65eb) — cloud-platform scope

The v1.3.5 Google sign-in could never complete: Google's console
refuses to register `https://www.googleapis.com/auth/generative-language`
for the project — rejected as an invalid scope in BOTH the Data Access
picker and the manual-entry field — and the on-device flow therefore
dies at consent with "Error 400: invalid_scope" (verified on device
against the fully registered project). Fix: GoogleAuthManager now
requests `https://www.googleapis.com/auth/cloud-platform` + `email`.
cloud-platform IS registerable in the console, and
generativelanguage.googleapis.com accepts cloud-platform-scoped
tokens. Everything else (cache, TTL, resolution flow, bridge) is
unchanged. The Google Account page footer now discloses the breadth
honestly (Cloud Platform access is broad; it is what lets
google-capable plugins call Gemini with the user's account).
Bundled gemini-chat's default model moves gemini-2.5-flash →
gemini-3-flash-preview (2.5-flash is retired for new users — 404
verified against a real key; 3-flash-preview verified answering).
GOOGLE-CLOUD-SETUP.md step 3 now names cloud-platform.

## v1.3.7 (2026-10-21, TEST build, on top of c314301) — httpPost error bodies

- **amin.httpPost returns the HTTP error body.** Since v1.3.5 the
  bridge returned null for every non-2xx without ever reading
  getErrorStream(), so a caller's error parsing (written for
  exactly those bodies) could never run, and every API rejection
  masqueraded as "did not answer". Now: 2xx unchanged (same read
  loop, same 1 MB cap); a non-2xx WITH a body returns the error
  body; bodyless errors and transport failures still return
  null. httpGet is deliberately NOT changed — older plugins
  depend on its null-on-error. Purpose of this test build: the
  account-route (OAuth) Gemini call fails on device while the
  key route answers; the account call's real Google verdict has
  never been visible — now it will be.
- **Audit of the v1.3.5 httpPost** (prompted by the same
  failure): plain URL.openConnection() — the platform
  HttpURLConnection, NOT Telegram's network stack, so no in-app
  proxy/MTProto interception; setRequestMethod("POST") +
  setDoOutput(true) before anything else; ALL headers from the
  JS object are set via setRequestProperty BEFORE
  getOutputStream() is opened (correct order — nothing is set
  after connect); body = UTF-8 bytes of the JS string with
  setFixedLengthStreamingMode(body.length) set before connect,
  written, then the stream closed; it runs on the PluginQueue
  HandlerThread ("AminGramPlugins"), not the UI thread. No
  header-ordering, header-dropping, downgrade or proxy defect
  found. Corroboration from the device: the failing calls left
  NO [error] line in the plugin log — an exception (e.g. an
  IllegalStateException from a late header) would have logged;
  a completed request with an error status returns silently,
  which is exactly the pre-fix behavior.
- **gemini-chat trims its stored settings** (model,
  systemPrompt) before use — a trailing space from a mobile
  keyboard turns the model name into a 404. Default model
  unchanged (gemini-3-flash-preview).
- Shipped AI stays keyless: the bundled set's only AI plugin is
  gemini-chat (OAuth). The key-based plugins (gemini-key,
  gemini-fix) are standalone diagnostics kept in
  ~/workspace/amingram/plugins/ — they are NOT bundled.

## v2.0 — the Grand Update (2026-10-21)

One coordinated batch across the history viewers, the hub, plugin
deletion, the attach menu and the JS bridge. No new upstream files
touched beyond the patch changes noted below (0014 extended, 0015
deleted); everything else is overlay code under
`org.telegram.messenger.amin.**`.

- **Deleted / Edit history — rich card viewers** (`ui/
  AminStoreListActivity` history mode + NEW `ui/
  AminHistoryDetailActivity`): the two DeletedStore lists (Deleted
  messages, Edit history) now render as rounded cards — initial
  chip, chat title, sender, kind, message time and the text
  (edits show BEFORE → AFTER) — under a count header with a
  **filter** field matching message text, chat title or sender.
  Tapping a card opens the detail screen: full text, a Details
  card (chat, sender, chat type, record type, kind, message id,
  message time) and an Actions card — Copy text (raw, no BiDi
  controls), Open chat, and **Delete this record** (confirm dialog;
  removes just that one record via `DeletedStore.removeRecord` and
  returns to the refreshed list). Other store lists (Radar hits,
  Auto-Saver log) keep the previous plain list presentation.

- **Hub v2** (`ui/AminPluginsActivity`): module sections are
  **collapsible** (state persisted per section) with
  **count-labelled headers**, and a **Search plugins** action
  filters the plugin cards. The JS **engine is now a standard
  module-style switch row** — the v1.3.3 custom accent pill
  (EnginePillView) is REMOVED; the backing pref is unchanged
  (`engine_enabled` in amingram_jsplugins, default true, same
  gates: hasEnabledPlugins, menuPlugins, every dispatch entry,
  warmUp). Each plugin card gains an **overflow menu — Settings,
  Log, Move up, Move down, Delete** — alongside its existing
  settings/log/delete actions; Move up/down persist through the
  normal plugin-order store.

- **TRUE DELETE for JS plugins** (`JsPluginManager.delete`):
  deleting a plugin now removes **every** trace — the cache entry
  and its slot in the persisted plugin order, the meta record
  (`meta_<id>`), the code file (`filesDir/jsplugins/<id>.js`), the
  ENTIRE per-plugin storage prefs file (`amingram_jsstore_<id>`,
  which holds both `amin.storage` values and the hub-written
  SETTINGS values), the log (`log_<id>`, removed directly and via
  VaultStore), the plugin's attach-menu config entry
  (AminMenuConfig), the live runtime and the session "broken"
  mark. **No tombstone**: nothing about the plugin survives to
  resurface on a reinstall of the same id (a reinstall starts
  genuinely clean). Delete works by id even for plugins no longer
  in the cache, so orphaned ghosts are purged too. For a bundled
  id, the ONE deliberate survivor is `BundledPlugins`' explicit
  user-deleted marker (`bundled_user_deleted`) — it exists only
  to stop auto-install from resurrecting the plugin; any
  successful install of the id clears it.

- **Bundled delivery rule v2** (`BundledPlugins.ensureInstalled`):
  a bundled id is auto-installed ONLY when it is genuinely new —
  (a) not installed, (b) not in the `bundled_seen` delivery set
  and (c) not in the user-deleted set. Seen-but-absent ids from
  the pre-v2 tombstone scheme are migrated once into the explicit
  user-deleted set (`seen ∧ ¬installed → user-deleted`; seen ∧
  installed stays installed; unseen stays deliverable), so a
  bundled plugin deleted under ANY version stays deleted. The
  hub's **Built-in library** page (NEW `ui/
  AminBundledLibraryActivity`) lists every bundled plugin that is
  not currently installed — new, deleted or never delivered — and
  reinstalls on tap through the normal install path (which clears
  the user-deleted marker). Deleted bundled plugins leave **no
  hub tombstone row** anymore.

- **Bundled inventory = 10**: `shout`, `vip-alert`, `media-dl`,
  `temp-mail`, `aesthetic-text`, `quick-translate`, `gemini-chat`,
  `link-cleaner` (NEW), `qr-maker` (NEW), `price-check` (NEW) —
  assets under `TMessagesProj/src/main/assets/amingram_plugins/`,
  canonical copies in `~/workspace/amingram/plugins/`.

- **Menu plugins page** (NEW `ui/AminMenuPluginsActivity`, hub →
  Menu plugins; config in NEW `plugins/js/AminMenuConfig`, prefs
  key `menu_config_v1`): per menu-capable plugin — **include /
  exclude** from the chat attach menu, **order**, **icon**
  override and **label** override. Overrides only: an empty
  label/icon means "use the manifest default", so manifest
  updates still flow through for entries never customized. With
  NO stored config the fallback is unchanged (every enabled menu
  plugin, in plugin order, manifest defaults); once a config
  exists it is authoritative for order/inclusion, except plugins
  installed later are appended with defaults rather than hidden.
  Deleting a plugin drops its menu entry. Manifest additions
  (v2.0, `JsManifest`): optional **`@icon`** — an icon SPEC, either
  a set name (`download`, `mail`, `sparkles`, `dice`, `text`,
  `link`, `music`, `star`, `bolt`, `camera`, `globe`, `gear`) or
  `emoji:<glyph>` — and optional **`@menuLabel`** (attach-menu
  label; default = plugin name).

- **Bridge: `file` capability + `amin.sendFile`** (v2.0,
  `AminBridge.WithFile` / `WithGoogleFile`): a NEW known
  capability `file` (JsManifest's set is now observe, commands,
  send, settings, http, menu, google, file). Only file-capable
  plugins get a bridge variant exposing `sendFile` — for every
  other plugin `amin.sendFile` does not exist at all, the same
  structural gate as `google` (the implementation is protected on
  the base bridge; only the file variants publish a public
  wrapper; the ClassShutter whitelist and patch 0010's R8 keeps
  cover the new variant classes). Contract:
  `amin.sendFile(url, opts)` is **SYNCHRONOUS** and returns a
  boolean. `opts = { type: "video"|"photo"|"audio"|"file",
  caption, chatId? }` — chatId defaults to the chat of the hook
  currently being dispatched. The URL is downloaded on the plugin
  thread (**15 s connect / 60 s read**, redirects followed,
  **200 MB hard cap** — declared-size and streamed overruns both
  fail) into the app cache, then handed to Telegram's own
  SendMessagesHelper prepare pipeline as proper media with the
  caption. **Any** failure (bad URL, HTTP error, over the cap, no
  target chat, hand-off failure) returns `false` — never an
  exception across the bridge. `true` means downloaded + handed
  to the send pipeline (Telegram uploads asynchronously).
  **media-dl v2** (bundled, version 2.0, capabilities += `file`,
  `@icon download`, `@menuLabel Download media`) resolves
  TikTok / X / Reddit / Instagram media and now sends the real
  media with `sendFile` first, falling back to posting the direct
  links when sending is unavailable or fails; `qr-maker` uses the
  same send-then-link-fallback pattern for QR images.

- **httpPost error bodies — canonical in v2.0**: the v1.3.7 bridge
  change (non-2xx returns the error body via the error stream,
  same 1 MB cap; bodyless errors and transport failures still
  return null; 2xx unchanged) is now the documented contract,
  and **`extractError(response)` is the canonical helper** —
  plugins parse the JSON error shape (`error.message`, truncated
  to 300 chars) before treating a non-empty httpPost result as an
  answer; bundled gemini-chat carries the reference
  implementation, copied verbatim into the master prompt.
  **`httpGet` is deliberately unchanged** — null on ANY failure
  including HTTP error statuses; older plugins depend on it.

- **Google capability — recap (unchanged in v2.0)**: the `google`
  capability still gates `amin.google` structurally (only
  google-capable plugins get the WithGoogle bridge variant —
  `amin.google` is undefined for everyone else); `isSignedIn()` is
  synchronous, `getAccessToken(fn)` / `signIn(fn)` are async
  callbacks on the plugin thread; scopes remain cloud-platform +
  email (v1.3.6), tokens are fetched fresh (~1 h life, silent
  re-authorize), never stored by plugins. `file` and `google`
  compose: a plugin declaring both gets the combined
  WithGoogleFile bridge.

- **Plugin contract confirmations (v2.0)**: SETTINGS schemas are
  **`bool` / `text` only** (no number/select/slider types — numeric
  input is text, parsed by the plugin); plugins use **no polling
  and no timers** — purely hook/event-driven on the dedicated
  plugin thread; dispatch keeps **per-hook isolation** (every hook
  invocation is Throwable-guarded, errors go to that plugin's log,
  and a failing plugin never affects the app or other plugins).

- **Design modules REMOVED; patch 0015 DELETED**: the v1.3.3
  design system is gone — modules `amin_design` and `ios_theme`,
  `amin.core.DesignThemes`, their PluginRegistry entries, hub
  rows and design prefs are all removed, and **patch 0015
  (design-startup-hook, LaunchActivity) is deleted from the
  series** (the hook's other tenant — the one-shot stories
  self-heal post added in v1.3.4 — goes with it; nothing calls
  a design/startup apply anymore). The app now uses **upstream
  Telegram themes** as-is. The patch series is now 0001–0004, 0006–0014, 0016
  with TWO intentional gaps: **0005** (google-services clients —
  generated by a CI step since v1.1) and **0015**.

- **Patch 0014 EXTENDED (attach-menu-plugins v2)**: the patch's
  row wire format grows from `{id, name}` to
  **`{id, label, iconSpec}`** (index 2 added; the reader tolerates
  the old length-2 arrays). In ChatAttachAlert's bind branch the
  iconSpec resolves: `emoji:<glyph>` prepends the glyph to the
  label and keeps the default ARTICLE animation (GlassTabView has
  no glyph hook); a set name maps to a TabAnimation — download →
  FILES, mail → REPLIES, sparkles/star → GIFT, dice → SYMBOLS,
  text → ARTICLE (default), link → LINK, music → MUSIC, bolt →
  BOOSTS, camera → GALLERY, globe → LOCATION, gear → SETTINGS;
  unknown names keep ARTICLE. Rows still come from
  `AminHooks.attachMenuPlugins()` (now menu-config aware:
  inclusion, order and resolved label/icon per the Menu plugins
  page), tags remain `-1000-index`, and the click branch is
  unchanged (dispatch `onPluginMenuAction(id, dialogId)`,
  dismiss). All other patches are unchanged in v2.0; patch 0010's
  proguard/ClassShutter surface grew only by the new bridge
  variant classes named above.

- **Live wallpaper library (v2.0 WS8)** (`core/
  WallpaperLibraryStore` + NEW `ui/
  AminWallpaperLibraryActivity`): the Video Chat Background
  module's single file becomes a COLLECTION. Durable copies live
  in `filesDir/amingram/wallpapers/<id>.<ext>`; the index (prefs
  `amingram_wallpaper_library`: items [{id, displayName,
  fileName, addedAt}] + `active_id`) is the source of truth, and
  `VideoBackgroundHelper.get/setVideoPath` delegate to it (the
  legacy `amingram_videobg`/`path` pref is kept synced to the
  active item; the playback path itself is unchanged — an Apply
  swaps the file a chat picks up on resume). **Migration**: an
  existing `videobg.<ext>` is COPIED (never moved or deleted)
  in as item #1 "Current" + active on first run; a failed copy
  changes nothing and retries on the next launch. Hub rows:
  **Live wallpapers** (→ the library page) plus **Clear / None**
  (deactivate, keep items); the module status line shows
  `Active · <name>`. The library page is a 2-column grid with
  cached thumbnails (MediaMetadataRetriever frame / GIF first
  frame), name + duration/size, and an ACTIVE badge; tap =
  apply + enable the module; per-item Delete (deleting the
  active item deactivates cleanly); long-press renames;
  **＋ Add from phone** is the old picker, adding a durable item
  AND applying it (the old pick-sets-it behaviour). Backup
  export/import ride the helper delegates (import adopts +
  applies the path; a missing file changes nothing instead of
  storing a dangling path).

- **Post-batch fix — temp-mail v2 + httpGet headers (2026-10-21)**:
  1secmail is dead (its hosts serve an unrelated domain's
  certificate; every call fails TLS verification), so the bundled
  **temp-mail is rewritten on mail.tm** (verified end-to-end:
  GET /domains, POST /accounts, POST /token, GET /messages):
  `/tm new` creates a REAL account (random login at the active
  domain + generated password, both kept in plugin storage) and
  fetches a bearer token; `/tm inbox` and `/tm read <id>` use
  authenticated GETs; on a null (the bridge's error-status
  signal) it re-authenticates ONCE and retries ONCE. v1's
  `extractOtp` / `extractLinks` helpers and the post-in-chat
  presentation are kept verbatim. Supporting bridge change
  (additive): **`amin.httpGet(url, headers)`** — an optional
  second argument, a {name: value} object applied after the
  defaults (the mail.tm inbox needs `Authorization: Bearer`);
  same timeouts/caps and the same null-on-any-failure semantics;
  the 1-arg form is untouched and `AminBridge`'s whole-class R8
  name-keep covers the overload. Documented in
  PLUGIN-MASTER-PROMPT v2.

## v2.1 — Plugin Platform Phase A + the fork-tonight pack (2026-10-21)

Two batches in one version. **Plugin Platform Phase A** (items A1–A9
below) is the PLATFORM-V2-DESIGN Phase A, built overlay-only with
**zero new upstream patches** — the one patch it touches is 0010
(proguard keeps for the new bridge classes). The **fork-tonight pack**
is the exception Amin ordered on top of it: three NEW upstream
patches (0017/0018/0019) that take the series beyond the Phase-A
zero-patch constraint, plus two native modules and a dual-mode
Gemini rework. Patch series is now 0001–0004, 0006–0014, 0016–0019
(intentional gaps: 0005 google-services — CI-generated; 0015 design
hook — deleted in v2.0). Plugin contract documented in
docs/PLUGIN-MASTER-PROMPT.md v2.1 (all three copies byte-identical).

### Plugin Platform Phase A — engine summary (A1–A9)

All in `org.telegram.messenger.amin.plugins.js` unless noted.
`JsPluginManager.PLATFORM_API_VERSION` = 2.

- **A1 — rich msg + lazy lookups + reply context.** `buildMsg` now
  also emits `replyToId`, `editDate`, `fwdFrom` (forward origin's
  display NAME only — header `from_name`, else the resolved peer
  name, else the post author; no peer object leaks), `groupedId`,
  `chatTitle`, `chatType` ("private"/"group"/"supergroup"/
  "channel"), `senderUsername`, `senderIsBot`, and `media` — null
  when the message has none, else `{kind, fileName, mimeType,
  sizeBytes, durationSec, width, height}` built purely by
  instanceof checks on the already-loaded media (kind ∈ photo /
  document / video / audio / voice / sticker / gif / webpage /
  poll / other; nothing is downloaded). New BASE-bridge lookups
  (every plugin, no capability needed): `amin.getChatInfo(chatId)`
  → `{id, type, title, username, memberCount}` (memberCount 0 for
  private chats), `amin.getUserInfo(userId)` → `{id, name,
  username, isBot, isPremium}`, `amin.getMessage(chatId, id)` → a
  buildMsg-shaped object — each null when unknown, and getMessage
  ONLY searches already-loaded messages (per-dialog list, then the
  global by-id map with dialog verification): unloaded messages
  are never fetched — that bound is contractual. Command dispatch
  also gains `ctx.replyTo` `{text, caption, senderName, chatId,
  messageId}` when the command message replies to another
  (caption = the target's text when it carries media, else "";
  an unresolved target still yields the object, with empty
  text/senderName).
- **A2 — `act` capability.** See the dedicated entry below.
- **A3 — async HTTP.** `amin.httpGetAsync(url, headersOrNull, fn)`
  / `amin.httpPostAsync(url, body, headers, fn)` on the BASE
  bridge. Sync and async forms now share `httpGetCore` /
  `httpPostCore` returning `{status, body}`, so the sync semantics
  are byte-compatible by construction (GET sync: body on 2xx/3xx,
  null otherwise; POST sync: body on 2xx, ERROR body on an HTTP
  error status, null on transport failure). The async callback
  receives exactly one `{status, body}` object — status 0 =
  transport failure (body null); async GET delivers body null on
  error statuses (status still reported), async POST delivers the
  error body, matching sync. The request runs on a lazily-created
  daemon cached pool ("AminGramHttp"); headers are snapshotted to
  a plain map on the calling thread so no Rhino object is touched
  off-thread; delivery goes through
  `JsPluginManager.invokePluginCallbackWithArg`, so the callback
  runs on the plugin thread inside the budget + error funnel.
- **A4 — storage v2** (`AminBridge.Storage`). Legacy string
  get/set unchanged (incl. set(key, null) removes the key). New:
  `getJSON(key, def)` / `setJSON(key, value)` — any JSON-shaped
  value via the pure `jsToJson` / `jsonToJs` converters (org.json
  + Rhino only, unit-testable off-device); getJSON returns the
  default when the key is absent OR the stored value doesn't
  parse (e.g. a legacy string); setJSON ignores undefined/function
  values with a [warn] log; values over 64 KB are stored but
  logged [warn] (64 KB is the documented author limit). Plus
  `remove(key)`, `keys(prefix)` (this view's keys with the
  prefix, sorted, as a JS array; null/"" = all) and `ns(name)` —
  a view of the same prefs file whose keys are physically
  `"<name>:<key>"`; views nest and expose the full surface.
- **A5 — settings schema v2** (NEW `SettingsSchema` validator +
  `JsPlugin.SettingDef` v2 fields + hub renderer in
  `ui/AminJsPluginActivity`). Types are now **bool, text, number,
  slider, select, header**. SettingDef gains help, maxLength,
  multiline, min/max/step (Doubles, null = unset), option
  value/label lists, and dependsOnKey/dependsOnEquals. The
  JS-side schema form: text rows take `help` / `maxLength` /
  `multiline`; number/slider take `min` / `max` / `step` and a
  numeric `def` (0 when omitted); select takes `options` —
  `{value, label}` objects or plain strings (string = value and
  label) — and stores the chosen value; a `header` row has NO
  key (title only); any row may carry
  `dependsOn: {key, equals}` (a bare string is also accepted as
  key-only) — the hub shows the row only while the OTHER
  setting's raw stored value string-equals `equals` (a bool
  stores "true"/"false"). Renderer: bool = check cell, text =
  input (maxLength as an InputFilter, multiline honored),
  number = numeric dialog, slider = seek row (min/max default
  0/100, tap opens the same numeric entry), select = single-
  choice dialog, header = section row; every write goes through
  the one writeSetting path (persist raw string → notify manager
  → rebuild rows, so dependents refresh immediately).
  **Validation law:** NEW plugin ids validate STRICT — invalid
  schema = install fails with "Invalid SETTINGS: …" naming the
  keys (unknown type, option-less select, min > max, non-numeric
  number/slider default, duplicate key). Reinstalls of an
  existing id validate LOOSE: warnings logged + coercion
  (unknown type → text; option-less select → text; min > max →
  min/max dropped, never swapped; bad numeric default → 0;
  duplicate key → first wins — in BOTH modes). Header-with-key
  and dependsOn-to-unknown-key are warnings only in both modes.
- **A6 — lifecycle hooks** (probe-detected: hasEnable /
  hasDisable / hasSettingsChanged / hasMigrate on JsPlugin).
  Warm-up sequence inside ensureRuntime: evaluate → `onLoad` →
  `onMigrate` → pending-settings delivery; `onEnable` fires
  right after, on the install / toggle-on paths only (NOT on
  Resume). `onDisable` runs on the plugin thread BEFORE the
  runtime is dropped, inside the same queued runnable, so no
  dispatch can slip between hook and drop; it goes through
  callHook, so a throw is funnelled and never blocks the
  disable. `onSettingsChanged(key, newValue, oldValue)` carries
  the RAW stored strings: live runtime → delivered immediately
  via `JsPluginManager.onSettingWritten`; no live runtime → the
  key is remembered (one pending key per plugin) and delivered
  ONCE at next warm-up with oldValue == newValue == the current
  stored value. **Migration:** manifest `@dataVersion` (A9)
  vs the persisted `dataVersionStored` — a fresh id (or a legacy
  meta with none recorded) adopts the declared version silently;
  when declared > stored, `onMigrate(stored, declared)` runs
  right after onLoad, before onEnable / pending settings / any
  dispatched hook (dispatch entries skip the plugin via the
  `migrating` gate while it runs). The stored version advances
  ONLY on a clean return (persisted immediately); a throwing
  migration is funnelled as an error, the stored version stays,
  and the plugin still runs — the migration retries at the next
  start. A plugin without onMigrate adopts the declared version.
- **A7 — watchdog counters + badge.** `noteHookError` is now the
  SINGLE funnel for every hook / load / probe / callback
  failure: it logs the "[error] <hook>: <throwable>" line,
  mirrors to FileLog, and increments the plugin's PERSISTED
  per-day counter (`errDay` / `errCount` on the meta record —
  counters survive reinstall; suspension does not). The hub
  plugin card appends "· ⚠ N" while today's count > 0, and the
  plugin page gains a Status section (suspension + Resume row,
  and a "⚠ N error(s) today" row linking into the log viewer
  filtered to errors). Accepted behavior change vs the v1.3 note
  ("no auto-throttle / auto-disable"): the counters themselves
  change nothing at runtime — the teeth are A8's, and they bite
  only on storms.
- **A8 — storm suspension + per-hook budget.** NEW
  `ErrorStormTracker` (pure java.util): ≥ **20 hook errors in a
  trailing 60 s** window session-suspends the plugin — dispatch
  entries skip it, warm-ups refuse it, the user's `enabled`
  toggle is NEVER touched, and the reason ("error storm: 20+
  hook errors in 60s") is shown on the plugin page with a
  **Resume** action (hub overflow menu too). Resume clears the
  suspension, the tracker AND today's counter, then re-warms
  (without re-firing onEnable); a successful reinstall also
  clears suspension + tracker. NEW `AminContextFactory` (Rhino
  ContextFactory): every context carries an instruction
  observer; `callHook` (the one hook funnel) arms a **5000 ms
  per-hook budget** — expiry throws `HookBudgetError` (an Error,
  so plugin try/catch can't swallow it) which kills THAT hook
  invocation only and logs "hook budget exceeded". The observer
  fires on instruction ticks, so wall-clock inside a blocking
  native call trips at the first tick after the deadline —
  ≈5 s of JS execution, as documented. Steady, non-throwing
  plugins are never throttled: no other rate limit exists.
- **A9 — platform introspection + manifest v2 fields.**
  `amin.platform` (`AminBridge.PlatformInfo`, present on EVERY
  bridge variant): `apiVersion` = 2 and `has(name)` over exactly
  twelve feature names — `rich-msg`, `lazy-lookup`, `act`,
  `http-async`, `storage-json`, `settings-v2`, `lifecycle`,
  `watchdog`, `platform`, `sendFile`, `http-headers`,
  `reply-context`. Manifest gains optional `@minPlatform` and
  `@dataVersion` (ints, default 1; parsed leniently — an
  unparsable / non-positive value falls back to 1 rather than
  failing the install). `@minPlatform` above the running
  platform NEVER blocks: the install succeeds and a [warn] line
  lands in the plugin's log ("wants platform vN; this build is
  v2 — installing anyway"). Patch 0010's proguard block += keeps
  for the four act variants + PlatformInfo (hunk header +57,24
  region), and the ClassShutter whitelist grows by exactly those
  five class names.

### `act` capability (A2)

- NEW known capability `act` (JsManifest's set is now observe,
  commands, send, settings, http, menu, google, file, **act**).
  The bridge family doubles again: `WithAct`, `WithGoogleAct`,
  `WithFileAct`, `WithGoogleFileAct` — `newScope` picks among
  all eight variants from the declared google/file/act triple,
  so `amin.editMessage` / `amin.react` exist ONLY for
  act-capable plugins (structural gate, same pattern as google /
  file; implementations `doEditMessage` / `doReact` are
  protected on the base bridge).
- `amin.editMessage(ref, text)` → boolean. ref = a msg object
  or any `{chatId, id}` object. The target is resolved ONLY
  among loaded messages (DeletedStore.findLive's method:
  dialogMessage list, then dialogMessagesByIds with dialog
  verification). The own-message rule is enforced NATIVELY:
  the resolved MessageObject must be outgoing AND
  `isOutOwner()` — anything else is refused (false + log);
  ephemeral messages are refused too. **Accepted deviation:**
  `SendMessagesHelper.editMessage(...)` returns 0 immediately
  when its BaseFragment is null / has no parent activity — a
  plugin thread has neither — so the bridge builds the exact
  `TL_messages_editMessage` that method builds (peer via
  getInputPeer, flags |= 2048, quick-reply shortcut preserved)
  and sends it via ConnectionsManager.sendRequest with the
  same processUpdates completion, mirroring the helper's own
  body (SendMessagesHelper.java:3580-3615 in 13.0.2).
- `amin.react(ref, emoji)` → boolean. Any author's message (no
  own-message check — reacting to others is the point). Same
  loaded-message resolution and the same deviation:
  `SendMessagesHelper.sendReaction` is fragment-gated too, so
  the bridge mirrors its body (SendMessagesHelper.java:
  3971-4027) — TL_messages_sendReaction with one
  TL_reactionEmoji (flags |= 1), including its thread-message
  saved-from special case — via ConnectionsManager.
- Both return true once the request is dispatched, false on
  any failure, and never throw across the bridge.
- **Deliberately absent:** there is NO delete and NO pin
  surface in v2.1 — reserved for a later phase (Phase B), and
  the master prompt says so explicitly so plugin authors don't
  invent them.

### Fork-tonight pack — NEW patches 0017 / 0018 / 0019

These three patches are NEW upstream patches beyond the Phase-A
zero-patch constraint, added at Amin's order for the pack.
Both CI apply lists carry all three.

- **0017-hide-sponsored** (2 files, guard at the choke points):
  `MessagesController.getSponsoredMessages` returns null when
  the module is on (one 3-line FQ guard at the method head —
  every sponsored-post surface reads through it), and
  `DialogsSearchAdapter`'s sponsored-peers gate gains
  `|| org.telegram.messenger.amin.AminHooks.hideSponsored()`
  so sponsored peers are never requested in search.
  **Placement decision:** the guards sit at these two choke
  points rather than in DialogsActivity (the original pack
  sketch) — DialogsActivity only renders what the controller
  hands it, so gating the controller + the search request
  covers channels and search with 2 touch points instead of
  chasing render sites.
- **0018-message-details** (1 file, ChatActivity): two touch
  points — `fillMessageMenu` appends via
  `AminHooks.addMessageDetailsOption(icons, items, options)`,
  and `processSelectedOption` gains a case for
  `AminHooks.OPTION_MESSAGE_DETAILS` (= 200, provably unused by
  upstream's OPTION_* block in 13.0.2: 0–35 and 100–117) →
  `AminHooks.showMessageDetails(ChatActivity.this,
  selectedObject)`. See the Message Details entry below.
- **0019-screenshot-unlock** (8 files): every FLAG_SECURE
  decision for secret / protected content is routed through
  `!org.telegram.messenger.amin.AminHooks.screenshotUnlocked()`
  — the FlagSecureReason lambdas in ChatActivity,
  ProfileActivity and ChatMessageCell, the FLAG_SECURE sets in
  PhotoViewer and SecretMediaViewer, and the secure gates in
  SecretVoicePlayer, Stories/StoryViewer (allowScreenshots)
  and Components/TranslateAlert2. With the module off every
  expression reduces to the upstream one.

### Native modules (fork-tonight pack)

- **Hide Sponsored** — module `hide_sponsored` (Privacy,
  enabledByDefault = TRUE): "Removes sponsored posts from
  channels and sponsored peers from search." Pure manifest —
  all behavior is patch 0017 reading
  `AminHooks.hideSponsored()` → PluginRegistry. Status line:
  "Active" while on.
- **Screenshot Unlock** — module `screenshot_unlock` (Privacy,
  default OFF): "Allows screenshots and screen capture in
  secret chats and protected (no-forward) media." Pure
  manifest; behavior is patch 0019 reading
  `AminHooks.screenshotUnlocked()`. Status line: "Secret chats
  & protected media can be captured" while on.
- **Message Details** (patch 0018; NO module — always
  available): a "Details" row (msg_info icon) in every
  message's long-press menu opens an AlertDialog with a
  one-fact-per-line dump of the message's server metadata,
  built by `AminHooks.messageDetails`: message id (with the
  real id when the grouped id differs), dialog id, sender id +
  resolved sender name, post author, sent / edited dates,
  forward origin (+ forwarded post id), reply-to id, grouped
  id, media facts, views — text only, nothing stored. The
  dialog's positive button copies the whole dump to the
  clipboard. Throwable-isolated like every AminHooks entry.

### Dual-mode Gemini (v2.1)

The v1.3.6 cloud-platform direction is superseded by an
explicit per-mode scope split in `amin.google.GoogleAuthManager`:

- NEW pref `api_mode` ("amingram_google" prefs): **"aistudio"**
  (default) or **"vertex"**. `getApiMode` returns vertex only
  when the pref says so. `setApiMode` persists the mode AND
  clears the token cache (a token minted for one mode's scopes
  is meaningless under the other). The hub's Google Account
  page (`ui/AminGoogleActivity`) gains an **API mode** row +
  single-choice picker; switching tells the user they
  reconnect under the new mode.
- Scopes are pinned per mode in requestedScopes: aistudio →
  `generative-language`, vertex → `cloud-platform`; both also
  request `userinfo.email`. **The scope verdict (device +
  console, 2026-10-21):** generativelanguage.googleapis.com
  REJECTS cloud-platform tokens ("Request had insufficient
  authentication scopes"), while the generative-language scope
  itself is UNREGISTRABLE — Google's console rejects it as
  invalid in the picker and by manual entry, and the classic
  consent screen is retired (all classic URLs redirect to the
  new Auth Platform). That is a **Google-side defect**: in
  aistudio mode, authorize dies with `invalid_scope` and no
  client-side change can fix it. Vertex mode VERIFIED working
  with BOTH the account token (cloud-platform authorizes fine)
  and an existing AI Studio API key on the project endpoint —
  the only remaining gate is **project billing** (403 "requires
  billing"); Amin's decision (2026-10-21): WAIT — billing stays OFF; Vertex mode ships dormant until he flips it.
- NEW bridge surface on `GoogleAccess` (google-capable plugins
  only, as always): `amin.google.getMode()` → "aistudio" /
  "vertex" ("" when unreadable) and
  `amin.google.getLastAuthError()` → the most recent authorize
  failure's detail ("" when none / last attempt succeeded) —
  the manager keeps it in a volatile `lastAuthError`, cleared
  on success, so a plugin can tell an `invalid_scope` verdict
  apart from a plain "please reconnect".
- **Bundled gemini-chat goes dual-mode** (same /ai command +
  menu hint): reads `amin.google.getMode()`; aistudio keeps the
  ORIGINAL generativelanguage request untouched; vertex builds
  `https://<location>-aiplatform.googleapis.com/v1/projects/
  <project>/locations/<location>/publishers/google/models/
  <model>:generateContent`. SETTINGS gain `vertexProject`
  (def "gen-lang-client-0433595679"), `vertexLocation`
  (def "us-central1"), `vertexModel` (def "gemini-2.5-flash")
  and `vertexApiKey` (def ""). **Key mode needs NO Google
  sign-in:** with vertexApiKey set, the plugin sends
  `x-goog-api-key` and never calls signIn / getAccessToken —
  the key is the credential. Failure mapping is plain-words,
  never raw dumps: a null token whose lastAuthError contains
  `invalid_scope` (aistudio) → "AI Studio mode needs a
  Google-side scope fix — switch to Vertex AI in Plugins →
  Google Account, or wait for the fix."; a vertex error body
  mentioning billing → "Vertex needs billing enabled on the
  project (one console step) — or switch back to AI Studio
  mode…". Amin's shipped direction remains account-only; the
  key mode is plumbing that ships dormant unless he sets it.

### Bundled plugins 11 + 12 (inventory now 12)

Assets under `TMessagesProj/src/main/assets/amingram_plugins/`,
registered in BundledPlugins (pre-install / library rules
unchanged from v2.0):

- **ai-actions** (capabilities commands, send, http, google,
  menu, observe, settings; `@icon sparkles`, `@menuLabel "AI:
  summarize"`): reply to a message with **/sum** (summarize),
  **/explain** (explain simply) or **/draft** (draft a reply) —
  the target text comes from `ctx.replyTo` — or pass text
  directly (`/sum <text>`). The attach-menu action summarizes
  the last text message its `observe` hook saw in that chat
  (hence observe + the menu hint's honest "watch this chat a
  while"). Same dual-mode Gemini plumbing as gemini-chat,
  including the vertex settings and both plain-words failure
  lines. **Accepted deviation:** it carries the `settings`
  capability (for the model / vertex settings) — noted here
  because the fork pack's sketch had it settings-free.
- **quick-replies** (capabilities commands, send, settings):
  text templates — `/tpl set <name> <text>` saves, `/tpl
  <name>` posts, `/tpl list` lists, `/tpl del <name>` removes
  (names `[a-z0-9_-]{1,24}`, list/set/del/help reserved). All
  templates live as ONE JSON object under the storage key
  "templates", written via storage v2 `getJSON` / `setJSON`
  when `amin.platform.has("storage-json")` says yes, else the
  legacy string get/set with JSON encode/decode — the
  canonical feature-detect + fallback pattern. **Honest wart,
  stated in its own @description:** the /tpl command message
  itself also appears in the chat — the platform cannot
  swallow outgoing commands yet.

- **Bundled inventory = 12**: `shout`, `vip-alert`, `media-dl`,
  `temp-mail`, `aesthetic-text`, `quick-translate`,
  `gemini-chat`, `link-cleaner`, `qr-maker`, `price-check`,
  `ai-actions`, `quick-replies`.

## Stage 1 defect fixes (2026-10-22, on top of 9c6ed80) — audit D1–D6

Approved by Amin 2026-10-22 from AUDIT-2026-10-22.md; five commits
(fdf3cf6…12770b9), overlay-only + CI config, NO new patches, no
upstream files touched. (Numbering below is the audit's.)

- **D3 — GitLab CI parity (fdf3cf6)**: `.gitlab-ci.yml` applied only
  13/18 patches; it now applies 0016–0020 too, byte-identical patch
  sequence to `.github/workflows/build.yml`. Pin (f32ffee), NDK
  27.2.12479018, temurin 17 and the google-services generation step
  were verified identical; remaining differences are the documented
  platform translations (SDK bootstrap, cache, release mechanics).
- **D2 + D6 — SendWhenOnline (8f667f0)**: store schema gains
  `account` (the UserConfig slot that queued the entry; entries
  written before the field existed default to
  `UserConfig.selectedAccount` at load). The hub queues under
  `getCurrentAccount()`; `AminHooks.onUserOnline` flushes only the
  (account, peer) queue of the account that received the status
  update, via that account. `markSent` was replaced: it flips
  exactly the entries whose `sendMessage` hand-off returned without
  throwing — a failed enqueue stays pending and retries on the next
  online event. The send→persist window is at-least-once by design
  (a crash there may duplicate once; the old blanket mark could
  silently lose a queued message).
- **D1 — Backup coverage (e4dd5b1)**: `BackupManager` now exports
  and restores (a) PluginDb — all kv rows, stored JSON text carried
  verbatim, restore = per-plugin replace in one transaction
  (`PluginDb.dumpAll`/`restoreAll`); (b) plugin file sandboxes
  (`amin.files`) as manifest + base64, restored through
  `PluginFiles.copyInto` so name validation + quota apply
  (cache-dir data is not plugin state and stays out); (c)
  WallpaperLibraryStore metadata (items + active_id) via new
  `exportBackup`/`importBackup` — media files not embedded, same
  rule as videobg. SWO backup entries round-trip `account`. The
  Google auth token cache remains EXCLUDED by design.
- **D4 — DeletedStore batching (79204d8)**: the snapshot list now
  lives in a synchronized in-memory cache (loaded once from prefs).
  Live `snapshot()`/`snapshotAll()` mutate memory only — the
  per-message full-list JSON read+rewrite on the update thread is
  gone. Persistence is a debounced flush (750 ms quiet window) on
  `Utilities.stageQueue`; `markDeleted` consumes from the cache and
  flushes inline before persisting the deleted list;
  `findSnapshotText`/`updateSnapshotText` read the cache, so edit
  capture sees unflushed snapshots. Accepted trade-off: a process
  death inside the debounce window loses the newest snapshots
  (Anti-Delete's live-controller fallback still covers deletes for
  messages held in memory). Caps (1000/500/300) and the pipe-record
  format are unchanged — existing data loads identically.
- **D5 — AutoSaver durable copies (12770b9)**: `record()` keeps its
  existing behaviour, then schedules an async copy (stageQueue,
  never the update thread) of the recorded file into
  `filesDir/amin_autosaver/` (temp file + length check + rename)
  and re-points the stored record at the durable copy, so Telegram
  cache purges can no longer dangle saved view-once/TTL entries.
  Copy failure leaves the original record untouched. Record format
  unchanged.

## Stage 2 enhancements (2026-10-22, on top of d2de0e5)

Approved by Amin 2026-10-22 (after Stage 1): Keyword Radar
deep-link jump + granular Ghost Mode sub-toggles. Two commits,
overlay Java + one one-line-per-site patch edit (0003); no new
patches.

- **Keyword Radar deep-link jump**: `AminStoreListActivity`'s
  generic mode now keeps the raw Radar records in a parallel
  `rawItems` list (Radar prefs/key only), position-aligned with
  the display-formatted rows through the newest-first reversal.
  Tapping a Radar row parses `dialogId|msgId|keyword|date|snippet`
  and, when dialogId != 0 and 0 < msgId <= Integer.MAX_VALUE,
  opens the chat at that message with upstream's own construction
  — `ChatActivity.of(dialogId, messageId)`, which builds a Bundle
  with `user_id` (dialogId >= 0) or `chat_id` (-dialogId) plus
  `message_id`, the same form upstream's search/shared-media
  jumps use; encrypted dialogs mirror DialogsActivity's
  `enc_id` + `message_id` Bundle instead. The fragment is pinned
  via `setCurrentAccount(currentAccount)` — the account this list
  resolved Radar titles against. Malformed/invalid hits and all
  non-Radar lists keep the previous copy-to-clipboard behaviour.
- **Granular Ghost Mode toggles**: the master `ghost_mode` module
  switch is unchanged. `GhostModeModule` now owns three
  default-ON sub-toggles in PluginRegistry's existing module
  prefs store (`amingram_plugins`): `ghost_mode_suppress_read_receipts`,
  `ghost_mode_suppress_typing`, `ghost_mode_suppress_online`.
  `AminHooks` gained `ghostRead()` / `ghostTyping()` /
  `ghostOnline()` — each is master AND its sub-toggle — and patch
  0003's three MessagesController call sites were switched
  one-for-one to the matching gate (completeReadTask → ghostRead,
  sendTyping → ghostTyping, updateStatus → ghostOnline); the
  preferred no-patch-edit shape was impossible because all three
  sites called the single `ghostMode()` with no distinguishing
  context. Hunk counts unchanged (same-line replacements);
  re-verified by full patch-series replay from pristine. The hub
  (`AminPluginsActivity`) shows the three toggles as On/Off action
  rows under Ghost Mode, following the existing action-row
  pattern; `GhostModeModule.status()` now lists which
  suppressions are active.
- Verified: JDK17 JavacTask parse clean (4 touched Java files,
  UTF-8); patch series replay on a fresh git-archive extraction
  of pinned upstream f32ffee + overlay → 18/18 clean (repeated
  from repo sources → 18/18); overlay == repo byte-identical for
  every touched file. No JS files touched.

## plugins-v4 promotion (2026-10-22, on top of 1e19829)

The plugins-v4 rewrite set (delivered to Amin as an importable
.aminpack on 2026-10-22) is promoted into the bundled set: all 12
bundled plugins' assets are now byte-identical to their
plugins-v4 sources (media-dl 3.0.1, temp-mail 2.1, gemini-chat
1.1, ai-actions 1.1, link-cleaner 1.1, price-check 1.1, qr-maker
1.1, quick-translate 1.1, quick-replies 1.1, aesthetic-text 1.1,
vip-alert 1.1, shout 1.1). fun-tools and theme-manager exist in
plugins-v4 but are NOT bundled (unchanged: fun-tools ships only
in the pack, theme-manager only as a standalone plugin file);
whether they join the bundled set is Amin's call.

- Check 1 — in-place upgrade behavior (BundledPlugins): BEFORE,
  ensureInstalled only marked an already-installed bundled id as
  seen and never re-delivered it, so an APK update left existing
  users on the old bundled code indefinitely. The install path
  itself (JsPluginManager.installInternal) was verified
  data-preserving: it replaces only the code file (old code kept
  as .prev.js) and the meta, carrying over enabled/installedAt/
  dataVersionStored; the per-plugin data stores (amin.storage +
  settings values in the per-plugin prefs file, PluginDb rows,
  the amin.files sandbox) are removed ONLY by delete() and the
  Developer-tools clearPluginData action. FIX: ensureInstalled
  now runs updateIfBundledNewer for installed bundled ids — it
  parses the bundled asset's @version and, when strictly higher
  than the installed version (JsPluginManager.compareVersions),
  updates in place through the standard install path. Same-or-
  newer installs (e.g. a .aminpack import) are never downgraded
  or clobbered; onMigrate still runs at warm-up when a plugin's
  @dataVersion moved (media-dl 1 → 2).
- Check 2 — three-way metadata sync: BundledPlugins.java
  descriptors (name + tagline) and catalog/index.json (name,
  version, author, description, capabilities, minPlatform,
  sha256, size, updatedAt) were regenerated from the promoted
  assets' manifests and final asset bytes — media-dl's display
  name is now its manifest name "Downloader Pro" in all three
  sources, and every plugin's minPlatform is 2. Verified by
  ~/workspace/amingram/verify-promotion-sync.py: SYNC OK (12
  plugins identical on every shared field; catalog sha256+size
  match asset bytes; assets byte-identical to plugins-v4).
- Check 3 — deep-link extension: NO code change. Deleted and
  Edit History records both carry dialogId + msgId (DeletedStore
  record formats + HistoryEntry parser), and
  AminHistoryDetailActivity already ships an "Open chat" action
  (openChat): it validates the peer on the current account and
  jumps via ChatActivity.of(dialogId, msgId) for edits — the
  message still exists — and ChatActivity.of(dialogId) for
  deleted records, whose message is gone by definition. The
  affordance Stage 2 added for Radar already exists here.
- Verified: Rhino harness full suite 676/676 against the
  promoted assets (test-bundled strict-loads all 12; its two
  media-dl SETTINGS expectations were updated from the old
  bundled 2.1 shape — 2 defs, first bool — to 3.0.1's actual 8
  defs, first select); JDK17 JavacTask parse clean for
  BundledPlugins.java (the only touched Java file); no patch
  files touched, so the 18/18 patch replay is unaffected;
  overlay == repo byte-identical for every touched file.

## Optimization pass (2026-10-22, on top of 9df1be7)

Performance-only pass (Amin's phone heats up; suspects were the
plugin system and the video wallpaper). No behavior change when
a feature is ON, no plugin API changes, no new timers / polling /
wakelocks. Every fix cites the hot spot it removes.

- Rhino instruction observer (AminContextFactory.java):
  OBSERVER_THRESHOLD 1000 -> 10000. The observer callback
  (ThreadLocal read + wall-clock read) fired every 1000 executed
  instructions on ALL plugin JS; at 10k ticks the callback runs
  10x less often while the 5 s per-hook wall-clock deadline —
  checked at every tick — stays fully authoritative (10k
  interpreted instructions execute in well under a millisecond,
  so deadline overshoot is unchanged in practice).
- Module gates (PluginRegistry.java): isEnabledById/isEnabled
  back every AminHooks gate, which sit on hot upstream paths
  (MessagesController.sendTyping, updateStatus, the read-receipt
  task, story/sponsored list binds). Before: a linear 14-module
  scan with a list-wrapper allocation, getSharedPreferences, a
  string-concat key and a prefs read on EVERY call. After: an
  id->module map (built in registerAll) + a ConcurrentHashMap
  enabled-state cache — a gate is two map reads. Invalidation is
  event-driven: an OnSharedPreferenceChangeListener (held in a
  static field; SharedPreferences keeps only weak refs) drops the
  entry for any in-process "enabled_*" write, incl. backup
  restores; setEnabled also updates the cache directly.
  GhostModeModule's sub-toggle reads are only reached after the
  master gate passes (AminHooks short-circuit), so with Ghost
  Mode off the gates cost just the cached master read.
- JS dispatch (JsPluginManager.java):
  * hasEnabledPlugins() — runs on Telegram's update thread for
    every update batch — no longer allocates a full copy of the
    plugin list via plugins(); it scans the meta cache in place
    under the class lock.
  * New enabledPlugins(context, capability) builds dispatch
    target lists straight from the cache under the lock;
    dispatchMessage ("observe") and enabledCommandPlugins
    ("commands") use it instead of copy-then-filter.
  * New MsgFields/computeMsgFields: the immutable per-message
    values (sender name — a string-building getUserName — chat /
    sender controller lookups, chat title/type, forward-origin
    name) are computed ONCE per dispatch event (lazily, on first
    target that defines the hook) instead of inside buildMsg for
    every target plugin. Each plugin still gets its own
    scope-bound Scriptable + media descriptor; only the lookups
    and strings are shared.
- Video wallpaper (VideoBackgroundHelper.java): new
  capDecodeSize() sets the ExoPlayer track-selection max video
  size to the TextureView's pixel size (applied on resume and on
  video-size change, idempotent per size), so a multi-rendition
  source is never selected/decoded above what the full-screen
  layer can show. Single-rendition files are unaffected (media3
  exposes no decode-downscale for a lone track). Audited and
  already correct, left alone: lifecycle (pause on fragment
  onPause, release on destroy — patch 0004), hardware decode
  preferred (upstream DefaultRenderersFactory
  EXTENSION_RENDERER_MODE_ON), audio track type disabled and no
  second audio player (VideoPlayer(false, true) + preparePlayer,
  not the loop API), one player per ChatActivity with stacked
  instances paused.
- Audited, already fine (evidence in the pass report): the
  onRawUpdates / MessagePipeline gate fan-out (all features
  early-out per batch; Radar loads keywords once per batch and
  disables itself when the list is empty), DeletedStore's live
  snapshot path (in-memory upserts + 750 ms debounced stageQueue
  flush, no per-message prefs write — Stage 1 D4), the error
  storm tracker (error path only), ensureRuntime (cached).
- Verified: JDK 17 JavacTask parse clean (4 touched files);
  Rhino full suite 676/676 (baseline held); no patch files
  touched, so the 18/18 patch replay is unaffected;
  overlay == repo byte-identical for every touched file.


## DNS Tunnel — Soar + Slipstream (2026-10-22, on top of 9be7e46)

Telegram-over-DNS-tunnel as a built-in module (DNS-BUILD-PLAN.md):
two lifeline engines behind the local-SOCKS recipe — the engine
exposes a SOCKS5 endpoint on 127.0.0.1, Telegram's OWN proxy
setting is pointed at it, and a specialUse foreground service
keeps the engine alive. NO VpnService (the VPN slot stays free
for Happ/other VPN apps), NO wakelock (power rule: the module
never holds anything of its own; screen-off stalliness is
disclosed in the UI copy).

- NOTHING server-specific is hardcoded (Amin's requirement):
  everything lives in PROFILES (prefs `amingram_dns`, JSON per
  engine + an active id per engine). Multiple named profiles per
  engine, one-tap switching; the two staged limoo profiles ship
  as editable defaults only (Soar zone +
  public key in DnsProfiles/DefaultProfiles; Slipstream domain +
  congestion + the server cert as the bundled asset
  `assets/amingram_dns/slipstream-default-cert.pem` — client
  material by design, safe to bundle). Soar profiles:
  name/zone/server public key/resolvers/discovery on-off +
  country, plus import of a pasted `soar://` link (the payload is
  base64url JSON; unknown contents are rejected with a plain
  "couldn't read" instead of a bad profile). Slipstream profiles:
  name/domain/resolvers/server cert (paste PEM or pick a file
  through the existing SAF picker, copy=false staging under the
  `dns_tunnel` picker id)/congestion (dcubic default, bbr).
  Resolver lists are fully editable for both engines; presets
  Auto (system DNS first, then 8.8.8.8/1.1.1.1/9.9.9.9) /
  System only / Public only / Custom.
- Soar (PRIMARY, the default engine): client pinned to
  `getlantern/soar v0.2.2` — the exact version in the go.mod of
  the WhiteDNS Soar-Server v0.1.3 the server runs (the upstream
  project is pre-release with no compatibility promises; never
  track main). A ~200-line pure-Go gomobile shim
  (`dns-native/soarbridge`, repo-only like `catalog/`) wraps the
  client: Start(zone, serverKeyB64, resolversCSV, discoveryIR) /
  Stop / Status / LocalPort; listener on an ephemeral loopback
  port; SOCKS5 no-auth CONNECT-only with half-close relay. IR
  discovery ON by default (multipath resolver discovery, prunes
  dead paths; forged resolver answers fail the server-key
  signature check by protocol design). TCP stays "auto": a
  resolver whose UDP path never answers gains a TCP/53 path.
- Slipstream (speed option, never the default): the stock
  `Mygod/slipstream-rust` client, pinned commit
  bc772dd07d9a136dbd7553b0da575526de207847, shipped by Option B —
  prebuilt ONCE by the manual `dns-artifacts.yml` workflow into
  `jniLibs/<abi>/libslipstream_client.so` (the one executable
  location for bundled code) and run as a subprocess of the FGS
  (the dnstt-xyz embedding pattern). Server certificate STRICTLY
  pinned via `--cert` (the embedded-app reference omits it; ours
  does not). No `--gso` (unimplemented upstream), no
  `--authoritative`. It works as a SOCKS endpoint because the
  server's fixed target is a microsocks backend. Until the
  prebuilt binaries are committed, the engine reports "not
  installed" cleanly in the UI and starting fails with a plain
  message before anything is touched. Upstream is archived —
  acceptable because the wire is frozen by our own server; Soar
  is the default and the maintained path.
- Proxy apply/restore (AminDnsController): before applying, the
  user's current proxy state is snapshotted symmetrically
  (ProxySettings.from/toSharedPreferences against a dedicated
  prefs file `amingram_dns_snapshot`, plus the previous
  proxy_enabled flag) and the tunnel flags commit; the tunnel
  proxy is applied with upstream's own recipe (SharedConfig
  addProxy dedupe -> currentProxy -> toSharedPreferences +
  proxy_enabled -> ConnectionsManager.setProxySettings ->
  proxySettingsChanged). LIVENESS GATE: the proxy is applied
  only after a full SOCKS CONNECT to a Telegram DC (443) through
  the fresh listener succeeds (Soar budget 25 s, Slipstream
  15 s) — a dead tunnel never blackholes the app; failure tears
  the engine down and restores. Stop restores the snapshot
  exactly (re-apply the previous ProxyInfo, or the toggle-off
  branch when there was none); a DIFFERENT proxy the user picked
  mid-tunnel is respected (bookkeeping only). Restore also runs
  on service death and — via a stale-flag check — at app start.
- Patch 0021-dns-tunnel (series 18 -> 19; both CI files' apply
  lists updated identically): build.gradle .aar line
  (`implementation files('libs/soarbridge.aar')`),
  AndroidManifest (FOREGROUND_SERVICE_SPECIAL_USE permission +
  the specialUse FGS declaration), ProxyListActivity (an
  "AminGram" section: header + "DNS Tunnel" row with live status
  opening the module page) — plus ONE deviation from the plan's
  three-file list: a single call line in
  ApplicationLoader.postInitApplication
  (AminDnsController.recoverIfStale). The plan's own §4.5 mandates
  stale-flag recovery at app start and no earlier amin hook
  exists; the line is Throwable-isolated and once-per-process.
- UI: AminDnsTunnelActivity (Proxy settings section + AminGram
  hub quick-toggle on the active profile + a profiles row) —
  status card (engine/profile/state/resolver), big toggle,
  engine chooser, profile lists with one-tap activation,
  add/edit/delete, resolver editor, honest About copy (no VPN,
  lifeline speeds, calls may not work, screen-off behavior).
- CI wiring (files only): build.yml + .gitlab-ci.yml get the
  0021 apply line, Go 1.25 (setup-go / official tarball into the
  cached project dir), and the gomobile step producing
  `upstream/TMessagesProj/libs/soarbridge.aar` before Gradle.
  `.github/workflows/dns-artifacts.yml` (new, workflow_dispatch)
  builds the 4-ABI Slipstream binaries and runs both live
  interop smokes (Soar shim -> default zone -> Telegram DC +
  HTTPS 200; linux slipstream-client -> default zone with the
  bundled cert pinned -> CONNECT through to the microsocks
  target). First-run checks still owed there: the gomobile
  generated API shape (org.telegram.amingram.soarbridge.
  Soarbridge static natives) and the cargo-ndk pin — both are
  exercised for the first time by that workflow / the next main
  build; this VM has no Go toolchain to pre-verify them.
- Verified locally: patch replay 19/19 on pristine pinned
  upstream f32ffee with both CI apply lists byte-identical;
  JDK 17 parse clean for all new/touched Java (incl. the patched
  ProxyListActivity/ApplicationLoader from the replay tree);
  Rhino suite 676/676 (no JS touched); overlay == repo
  byte-identical for everything touched.

## Ryoku UI (2026-10-23, on top of 9ec5fff) — dossier identity layer, app-wide

Amin approved the ryoku.dev-inspired redesign ("Tier 1+2", then
expanded app-wide the same evening): bone-on-black dossier look —
Fraunces display serif, Space Grotesk sans, Space Mono mono, accent
#CE5E32, sharp corners, hairline dividers, numbered mono section
labels, dithered marble art. Hard boundary kept: NO re-layout or
flow changes anywhere; identity-level hooks only. Design record:
~/workspace/amingram/RYOKU-DESIGN.md; asset credits:
~/workspace/amingram/design/ryoku/CREDITS.md.

- NEW overlay class `amin.ui.RyokuUi`: the master toggle
  (prefs `amingram_ryoku`, default ON, first hub open bootstraps
  the ON side effects) + lazy cached font loaders + the typeface
  remap table + `applyToThemePaints()` (29 Theme paints re-stamped:
  bold-family via `AndroidUtilities.bold()`, body paints → Grotesk,
  time/code paints → Space Mono; stock restore on OFF) +
  `clampRadius()`/`dialogRadiusDp()` geometry gates + theme
  find/apply/restore (records `prev_theme_key`) + bubble radius
  3-while-ON (records `prev_bubble_radius`, SharedConfig.saveConfig)
  + `applyWallpaper()` (standard OverrideWallpaperInfo path on the
  Ryoku theme, intensity 0.5).
- Patch 0022-ryoku-ui (series 19 -> 20; build.yml apply list
  updated — NOTE: overlay has no .github; the GitHub workflow is
  repo-only): AndroidUtilities (getTypeface top → RyokuUi
  .typefaceFor; bold() top → RyokuUi.boldTypeface; gram.ttf left
  stock), Theme (built-in "Ryoku" ThemeInfo registration after
  Night — assetName ryoku.attheme, sortIndex 6, Day-shaped accent
  options in the Ryoku palette; clampRadius in all five
  createRoundRect*Drawable factories; applyToThemePaints() line at
  the end of createDialogsResources + createCommonChatResources),
  AlertDialog (background radius 18/20dp → dialogRadiusDp).
  Upstream files touched total grows by these three (Theme +
  AndroidUtilities were previously untouched by the series).
- NEW assets: `assets/ryoku.attheme` (generated from night.attheme's
  key set, ~186 palette overrides incl. avatar/name colors re-toned
  to a muted warm monochrome family), `assets/fonts/ryoku/` (7 OFL
  static cuts, ~607 KB — Fraunces 600/700 instanced from the VF at
  opsz 100, Space Grotesk 400/500/700 instanced, Space Mono
  400/700), `assets/ryoku/wallpaper_{1,2,3}.webp` (1080x2400
  Floyd-Steinberg-dithered marble: Marcus Aurelius CC0, Laocoon
  CC0, Socrates PD — Wikimedia Commons; masters + sources in
  ~/workspace/amingram/design/ryoku/).
- Hub (AminPluginsActivity): NEW "System" section (06 // SYSTEM)
  between Add Plugin and Developer — KIND_RYOKU CardHeaderView
  master-toggle card + status info row + "Ryoku theme" action
  (applies on tap regardless of toggle) + "Ryoku wallpapers"
  action (AlertDialog setItems picker over the 3 bundled).
  HubHeaderView gains the dossier masthead (力 mark, AMINGRAM in
  Fraunces, mono SYSTEM DOSSIER label, 2x2 live stat grid:
  Plugins / Active / DNS Tunnel / Theme) shown while ON; stock
  shield header while OFF. SectionLabelView renders numbered mono
  labels while ON; segmentDrawable goes 2dp + hairline stroke;
  chip radii via chipRadius(); CardHeaderView + PluginCardView
  names take Fraunces while ON. DNS page untouched (inherits via
  HeaderCell/theme/fonts).
- Known limits (in RYOKU-DESIGN.md): BottomSheet nine-patch
  corners stay rounded (asset, no central drawable); raw
  Typeface.DEFAULT call sites outside the hooks keep system sans;
  already-laid-out messages keep metrics until reopen; light
  themes untouched; input bar restyle is color-level only.
- Verified locally: patch replay 20/20 vs a fresh git-archive
  extraction of pinned f32ffee (0022 last in CI order); JDK 17
  parse clean for RyokuUi, AminPluginsActivity and the three
  patched upstream files (byte-equal to the replay tree);
  repo == overlay byte-identical (TMessagesProj + patches);
  Builder audit: only new Builder use is the hub wallpaper picker
  (setTitle/setItems/setNegativeButton — mirrors the in-file
  showPluginOverflow pattern). Full compile + on-device pass
  (fonts on chat list/chat, stat grid, x3 toggle cycle restore)
  remain CI/device-side.
