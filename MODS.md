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
Target: keep this list under 40 files, each with 1–3 line hooks.
(Rebase cap raised 30 → 40 by Amin, 2026-11-15 23:20. Counting
rule: patch files + CI inline edits together, each distinct
upstream file counted once. Current count at M3: 34 files
(32 via patches + 2 CI inline edits), headroom 6. M4 adds
zero new upstream files. **Cap LIFTED by Amin 2026-11-16 for the
real-engine port (v3.0.0): 45 distinct upstream files now — the
engine's hooks cannot fit in 40, and he approved doing whatever
is needed. No headroom math anymore.**)

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
- 0015 design-startup-hook (v1.3.3, LaunchActivity.onCreate — DELETED in v2.0 along with DesignThemes/applyDesignOnStartup; no startup design work runs in current builds): one
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
  historical self-heal (v1.3.4 only — deleted in v2.0 when patch 0015 and applyDesignOnStartup were removed), it scheduled a
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

## v2.4 — Stage 2 BIG Update (2026-11-11, on top of a5a3804)

### v2.4.1 — Build fix (2026-11-12, after run 43089870239 failed javac)
Gemini's Stage 2 code called 13.0.2 APIs that do not exist (12 errors in `compileReleaseJavaWithJavac`):
- Forwards never flow through `sendMessage(SendMessageParams)` — upstream sends them via `sendMessage(ArrayList, ...)` with a direct `TL_messages_forwardMessages` RPC. Moved AyuForward / Forward-as-Copy interception to a new `AminHooks.onForwardMessages` hooked at the head of that 12-arg overload (patch 0013, second hunk); AyuForward re-sends restricted messages as own copy, Forward-as-Copy re-invokes the forward with `drop_author` / `drop_media_captions` (re-entry guarded). The reflection-based `applyForwardAsCopyFlags` (fields that do not exist) is deleted.
- `prepareSendingDocument`: `entities` was passed where `editingMessageObject` belongs — now `null` (entities are derived from the caption internally).
- `TranslateAlert2.showAlert`: now calls the real 13-arg overload with an `InputPeer` from `MessagesController.getInputPeer(dialogId)` (upstream passes `messageOwner.originalLanguage` as `fromLanguage`, which may be null — passing null is safe).
- `MessagePipeline`: `MessageObject.getDocumentFileName` → `MessageObject.getFileName(doc)`; `AutoSaverStore.record` now takes `(Context, Entry)` — both call sites build an `Entry`; `formatRadarHit` takes `(String raw, int account)` — call site arg order fixed.
- Patches 0001–0024 re-verified to apply cleanly in CI order on pristine f32ffee with the new 0013.

### v2.4.2 — Rebase onto Telegram 13.2.2 (2026-11-12, f32ffee → 21f7d10 "update to 13.2.2 (7303)")
Upstream pin moved in both CIs (GitHub build.yml + GitLab UPSTREAM_SHA), release tags `v13.2.2-amingram.*`, UPSTREAM.md, README.md. 15/21 patches applied clean as-is; 6 ported:
- **0002**: version context only (7175/13.0.2 → 7303/13.2.2); rebrand itself unchanged.
- **0016**: re-anchored the play-services-auth 20.4.0 → 21.6.0 bump — upstream replaced the firebase version pins with a firebase BoM 34.19.0 block (and dropped the Rhino dep; 0010 re-adds it, so the JS runtime is unaffected).
- **0019 (PhotoViewer)**: upstream added an `isPageBlocksNoForwards(pageBlocksAdapter)` clause to the FLAG_SECURE condition — the screenshot-unlock wrap now closes after that clause.
- **0021 (ProxyListActivity)**: upstream removed `selectedCountTextView` and refactored the adapter to `ThemeBind.textColor(...)` — the fields hunk drops the dead field line, the row-render hunk re-anchors on the ThemeBind calls. (`setTextAndValue` and `mContext` verified still present.)
- **0023**: upstream added a widget-engine-process early return in `ApplicationLoader.onCreate` (`WidgetEngineService.isEngineProcess()`); `AminFonts.initLatch()` now runs after that guard, still before `AndroidUtilities` init.
- **0024**: no port needed — it only failed while 0019 was unapplied (its ChatMessageCell hunk anchors on 0019's `screenshotUnlocked()` lambda prefix). Upstream did refactor the FlagSecure block into `updateFlagSecure()`; the hook still lands at the same creation site.
- Submodules: upstream commit e37e0d0 replaced twidget + widget-sandbox sources with git submodules (`TMessagesProj/jni/twidget/twidget`, `.../widget-sandbox`); CI's existing `git submodule update --init --recursive --depth 1` already covers them, and the new cmake `twidget` target + `aidl` build feature come from upstream. No build.yml change needed beyond the pin.
- API audit: every upstream symbol the Stage 2 code touches re-verified against 13.2.2 (12-arg forward `sendMessage`, `sendMessage(SendMessageParams)`, `prepareSendingDocument/Photo/Video` signatures, all 3 `TranslateAlert2.showAlert` overloads, `MessageObject.getFileName(Document)`, `MessagesController.getInputPeer(long)` / `isPeerNoForwards`, `ChatMessageCell.getMessageObject`, `FileLoader` helpers, speed-hook targets `maxDownloadRequestsBig = 8;` / `uploadChunkSize = 64 * 1024;` with `forceSmallChunk` in scope; all amin-imported upstream classes exist except generated `R`). Full series 0001–0024 re-verified to apply cleanly in CI order on pristine 21f7d10.

### Phase A — Optimization & Core/Engine Hardening
- **A-01 (`AminBridge` / `JsPluginManager`)**: Set and clear `sCurrentDispatchChatId` (and `sCurrentDispatchReplyToMsg`) in `try/finally` across all dispatchers (`dispatchMessage`, `dispatchCommand`, `dispatchOutgoingCommand`, `dispatchMenuAction`, `dispatchContextAction`, `dispatchPluginAction`, and async callbacks) so `amin.sendFile` without `opts.chatId` never leaks to a stale chat.
- **A-02, A-05, A-14, A-27 (`AminBridge`)**: Bounded `HTTP_POOL` (2–8 threads, 64 queue) and `SYNC_CALL_POOL`; sync `httpGet`/`httpPost` enforced under a hard 30 s wall-clock deadline with manual redirect cap (<= 5 hops); per-plugin async HTTP concurrency cap (8 in-flight); 2 MB POST body cap; `doSendFile` given a 120 s wall-clock deadline, 200 MB per-plugin staged cache quota, and 1 h sweep window.
- **A-03, A-04, A-06, A-11, A-13 (`DeletedStore`)**: Replaced linear list scans (`O(N * 1000)`) with an insertion-ordered `LinkedHashMap<String, String>` keyed by `dialogId|msgId` (`O(1)` `upsertSnapshot`, `findSnapshotText`, `updateSnapshotText`, `takeSnapshotLocked`); moved `flushSnapshots` JSON serialization outside `DeletedStore.class` lock with a generation counter; offloaded `markDeleted` and batched `recordEdits` disk writes to `Utilities.stageQueue`; reused `ThreadLocal<SimpleDateFormat>`.
- **A-07, A-20 (`VaultStore`)**: Added in-memory cache per `(prefsName, key)`, `VaultStore.count(...)`, `HashSet` `O(B + kept)` deduplication in `addUniqueBatch`, and lock-free JSON serialization.
- **A-10 (`AutoSaverStore`)**: Added in-memory `cachedEntries` and automatic deletion of durable files under `filesDir/amin_autosaver/` on `CAP` eviction and `remove()`.
- **A-12, A-16, A-18, A-22 (`JsPluginManager`)**: Cached `isEngineEnabled` and `hasEnabledPlugins` via `SharedPreferences.OnSharedPreferenceChangeListener`; capped `pendingMessageDispatches` at 64; shared a single Rhino `Context` across `relayEmit` targets; reused `ThreadLocal<SimpleDateFormat>` + cached `todayString()`; debounced error-storm `persistMetaOnly` writes; reused `sharedFallbackScope`.
- **A-15 (`PluginDb` / `AminBridge`)**: Pushed `keys()` prefix filtering into SQL (`LIKE ? ESCAPE '\'`); enforced per-plugin total quota (8 MB / 2,000 rows, grandfathering updates to existing keys); added transactional `amin.db.putMulti(ns, obj)` and `amin.storage.setMulti(obj)`.
- **A-17, A-25 (`JsManifest` / `PluginFiles`)**: Added 512 KB source size cap (`MAX_SOURCE_CHARS`), deduplicated `@capabilities`/`@depends`, and capped `PluginFiles.readBytes`/`readText` at 4 MB.
- **A-19, A-20, A-22, A-24, A-32, A-33 (`HubCommon` / `AminCleanup` / `AminPluginEditorActivity`)**: Removed dead `KIND_INFO`, write-only `Row.multiline`, and dead `JsPluginManager.suspendReason` / `MessagePipeline.scanOne`; cached `FooterView` package `versionName` and `StatsView` DNS profile name; recycled chip `TextView`s and `PorterDuffColorFilter`s in `PluginCardView`; added `_ryoku_scrubbed_v1` marker in `AminCleanup`; switched `AminPluginEditorActivity` and `HubCommon` bundled installs to `installAsync`.
- **Phase A.4 Plugin Engine Additions**:
  - Outgoing command text suppression (`patches/0013-outgoing-command-hook.patch` + `AminHooks.onOutgoingMessage` + `JsPluginManager.dispatchOutgoingCommand`): when an enabled command plugin matches an outgoing `/command` (via manifest `@commands` or source scan), `SendMessagesHelper.sendMessage` returns immediately after dispatching to the plugin so literal command text (e.g. `/tpl`) does not post to the chat.
  - Raw message inspection (`msg.raw` on every dispatched `msg` + `amin.getMessageRaw(chatId, id)` on the base bridge) + enriched `msg.media` (`id`, `dcId`, `stickerSetId`, `stickerSetShortName`, `stickerEmoji`, `isAnimatedSticker`, `isVideoSticker`, `isCached`, `localPath`) + `amin.sendMessageMedia(ref, opts)` on `file`-capable bridges.
  - Relay hardening (`@depends` runtime enforcement + 256 KB payload cap).

### Phase B — New Native Features (`patches/0024-stage2-native-features.patch`)
- **AyuForward (`amin.modules.AyuForwardModule`, id `ayu_forward`)**: Forwards, copies, and saves messages/media from restricted (`noforwards`) channels and groups by re-sending them as a fresh user-owned copy (`AminHooks.onOutgoingMessage`, `AminHooks.forwardMessagesAsOwnCopy`, and message menu options `OPTION_AYU_FORWARD_COPY`, `OPTION_AYU_SAVE_SELF`, `OPTION_AYU_COPY_TEXT`). Cached media is staged and re-uploaded via `SendMessagesHelper.prepareSending*`; uncached media is fetched via `FileLoader` and sent once cached.
- **Forward as Copy (`amin.modules.ForwardAsCopyModule`, id `forward_as_copy`)**: Strips the `"Forwarded from"` header (`drop_author`) and optionally media captions (`drop_caption`) on forwards, plus adds `"Forward as Copy (No Quote)"` (`OPTION_FORWARD_AS_COPY`) to the message menu.
- **Message Filters (`amin.modules.MessageFiltersModule`, id `message_filters`, prefs `amingram_filters`)**: Collapses incoming/loaded messages matching stop-word keywords or sender IDs behind `"🚫 Filtered (...) — tap to reveal"`. Rules sit right next to Keyword Radar in the hub. Tapping `"Reveal filtered message"` (`OPTION_REVEAL_FILTERED`) or double-tapping the filtered bubble restores the original text in-place.
- **Folder & Chat QoL Pack (`amin.modules.FolderChatQolModule`, id `folder_chat_qol`)**: Adds `"Mark folder as read…"` (`OPTION_MARK_FOLDER_READ` + hub action), `"Jump to beginning"` (`OPTION_JUMP_TO_BEGINNING`, scrolling/opening message ID 1), and horizontal folder filter tabs inside `AminDialogPickerActivity` + `DialogsActivity` forward-folder hidden-chat filtering.
- **Configurable Double-Tap & Swipe Actions (`amin.modules.GestureActionsModule`, id `gesture_actions`)**: Attaches a non-intrusive double-tap detector on `ChatMessageCell` (`AminHooks.onCellAttached`, patch 0024) supporting Quick React (`👍`), Reply, Save to Saved Messages, Translate, or Forward as Copy, plus double-tap own message = Edit and configurable swipe action.
- **Download & Upload Speed Boost (`amin.modules.SpeedBoostModule`, id `speed_boost`, default OFF)**: Hub setting with Download levels Off / Balanced (`512 KB × 8`) / Extreme (`1024 KB × 12`) and separate Upload Speed Boost (`512 KB` chunks). Syncs `MessagesController.getfileExperimentalParams` and hooks `FileLoadOperation.updateParams()` (preserving `!forceSmallChunk` / `LIMIT_INVALID` fallback) and `FileUploadOperation`.

### Phase C — Bundled & Catalog Plugins
- **Sticker Downloader (`sticker-dl.js`, v1.0.0, bundled #13 + catalog)**: Exports any sticker, custom emoji, or media message as a PNG image or raw `.webp`/`.tgs`/`.webm` file via `/sticker`, `/stpng`, `/straw`, or long-press `"Save Sticker / PNG"` using `amin.sendMessageMedia`.
- **Downloader Pro (`media-dl.js`, v3.0.3)**: Fixed X/Twitter URL matching (`/i/web/status/`, `mobile.twitter.com`, `vxtwitter.com`, `fxtwitter.com`, `fixupx.com`), added TikTok short-link fallback (`vm.tiktok.com` / `vt.tiktok.com`), and aligned timeouts/caps.
- **Quick Replies (`quick-replies.js`, v1.1.1)**: Updated description and notes now that outgoing `/tpl` commands are suppressed by the platform.

## v2.5 — Python (.plugin) engine, M0 skeleton (2026-11-15, pushed 22:43 as part of 4212787)

Licence-neutral clean-room port of the exteraGram Python plugin
engine (Phase 0: exteraGram repo is archived, no plugins/ dir;
current engine closed/private — NOT transplantable. Only public
source is exteraless, GPL-3.0, which AminGram (GPL-2.0) cannot
copy without a relicensing decision. M0 therefore implements
from the public SDK docs (plugins.exteragram.app) + AminGram's
own code ONLY. No exteraless/exteraGram code was copied. Full
Phase 0 report: ~/workspace/amingram/EXTERAGRAM-PORT-PHASE0-2026-11-15.md;
design: ~/workspace/amingram/EXTERAGRAM-PORT-PHASE1-DESIGN-2026-11-15.md.)

New patches (apply in CI order after 0024):
- 0025-pyplugins-gradle — root build.gradle: Chaquopy Gradle
  plugin classpath 17.2.0 (verified latest on the Gradle Plugin
  Portal; AGP 8.13.2 is in Chaquopy 17's 7.3–9.5 range).
  TMessagesProj/build.gradle: `apply plugin: 'com.chaquo.python'`
  (library module — the ONE Chaquopy module; Python source in
  TMessagesProj/src/main/python/ rides the overlay copy),
  minSdkVersion 23 -> 24 (Chaquopy 17 requires minSdk >= 24;
  drops Android 6.0 / API 23 only), chaquopy block pinning
  Python 3.11 + buildPython "python3.11" (3.11 = last version
  with 32-bit ABI support; 3.12+ is 64-bit only, so the afat
  flavor's armeabi-v7a slice still builds). proguard-rules.pro:
  keeps for com.chaquo.python.**, amin.pyplugins.**, the two
  exteraGram facades, and -keepnames for the org.telegram classes
  plugins resolve by string.
  R8 VERIFIED to run on release: TMessagesProj_App release has
  minifyEnabled true + shrinkResources true — the keeps are
  mandatory, not optional (the old "release is unminified" note
  in patch 0010 is wrong for the afatRelease CI build).
  CI: build.yml adds actions/setup-python 3.11 (Chaquopy needs
  the build-machine Python to match the pinned version).
- 0026-pyplugins-init — ApplicationLoader.postInitApplication:
  one FQ line registering PyPluginsBootstrap (next to the 0021
  DNS line). It only records the context; Python starts lazily.

New upstream files touched: root build.gradle (+1). Honest
touched-file count is now 32 (was 31 incl. the two CI inline
edits). (Historical note: at M0 the 30-file cap was exceeded;
the raise to 40 was APPROVED by Amin 2026-11-15 23:20, see the
header rule. M0/M1 add no further upstream files.)

Documented exception to the amin.** rule (facades only, no
logic): com.exteragram.messenger.plugins.PluginsController
(singleton + plugins map + Plugin.setError/setEnabled, all
delegating to PyPluginsController) and
com.exteragram.messenger.utils.AppUtils (empty placeholder so
the name resolves; any method call fails loudly — M3 decides
per method from the DEX decompile). No further code may live
under com.exteragram.* except facades.

New overlay code (org.telegram.messenger.amin.pyplugins):
- PyPluginsPrefs (prefs amingram_pyplugins, master switch
  pyplugins_enabled default OFF, 2 MB cap, presented versions
  app 13.2.2 / SDK 1.4.4.3, Python 3.11).
- PyPlugin (model + JSON) / PyPluginMetadata (literal-only
  metadata parser + __id__ / semver / category / app+SDK
  constraint checks) / PyPluginAnalysis (install-time static
  analysis: reflection / DEX / native / network / messages /
  Stars / files / blobs — advisory, never a sandbox).
- PyPluginLog (capped per-plugin log, same key shape as JS) /
  PyPluginSettings (per-plugin settings store, reached from
  Python) / PyPluginHost (postDelayed for run_on_queue, toast
  fallback for bulletins, logcat plugin.<id>).
- PyPluginsBootstrap (lazy Python.start — ONE-TIME, on the MAIN
  thread via a latch, because Chaquopy's docs start Python from
  Application.onCreate; dispatch runs on the dedicated
  "AminGramPyPlugins" thread; widget-engine process guard).
- PyPluginsController (registry, install sideload-only with
  SHA-256 + reinstall-in-place, enable/disable/setError/delete,
  load via clean-room amin_loader, error-storm auto-disable
  after 3 consecutive failures, on_message / on_command
  provisional dispatch).
- PyHookDispatcher (fan-out from AminHooks.onRawUpdates /
  onNewMessages / onOutgoingMessage; cheap no-op when off).
New Python SDK (TMessagesProj/src/main/python/, clean-room):
base_plugin.py (BasePlugin lifecycle + get/set_setting +
logger), amin_loader.py (module import + BasePlugin subclass
discovery: exactly one -> it; several -> LAST defined —
heuristic for MaterialSettingsList's EmptyPlugin vs
MaterialSettingsIcons; M3 must verify), client_utils.py
(run_on_queue; get_last_fragment is M2), ui/bulletin.py
(BulletinHelper -> toast + log in M0; real bulletin is M1).
Fixture: assets/amingram_pyplugins/hello_py.plugin (NOT
auto-installed; install from the Python page to test).

UI (overlay): AminPyPluginsActivity (list page: engine switch,
per-plugin rows, add-from-file with analysis review dialog),
AminPyPluginActivity (per-plugin page: About, enable switch,
analysis report, error state, View log via AminStoreListActivity,
Delete). HubCommon plugins page gains section "03 // PYTHON
(.plugin)" with one row opening the list page. JS engine
untouched.

Design deviations from Phase 1 (each documented in code):
1. Metadata parsed in Java, not Python AST — install must work
   before Python starts; literal-only contract makes the two
   equivalent for these fields.
2. Python.start on the main thread (latch), not the background
   thread — Chaquopy docs + field evidence favor main-thread start.
3. on_command dispatch is fire-and-forget, NEVER suppresses the
   literal message in M0 — suppression needs the M2 hook registry
   with per-command declaration (JS avoids sync execution by
   deciding from a declared list in prefs).

M0 exit criteria NOT yet proven (need CI build + device):
Chaquopy starts, hello_py loads/unloads, engine-off cold start
unchanged, APK size delta, 32-bit slice still builds, ZwyLib-
missing force_stop path (facade) end-to-end. Local verification
so far: JDK17 syntax parse on touched Java files, patch series
applies on pristine 21f7d10 + byte reproduction (see M0 report
in the parent agent's final message), Python py_compile +
CPython stub tests of amin_loader/base_plugin, full-diff secret
scan zero. NO local Gradle build (no Android SDK on this VM) —
CI is the only build proof, and green CI proves compilation only.

### M0 verification fixes (2026-11-15, same day, before any push)
- **CRITICAL — metadata parser rejected every plugin:** the
  `STRING_ASSIGN` / `LIST_ASSIGN` key group `[a-z_]+` swallowed
  the trailing underscores, so `__id__` captured `"id__"`, the
  switch never matched, and `parse()` always failed with
  "Missing metadata". Fixed by requiring the closing `__`
  outside the key group (`[a-z]+(_[a-z]+)*`). Caught by a
  plain-JDK stub test of `PyPluginMetadata` (25 checks, all
  passing after the fix, incl. hello_py + MaterialSettingsList
  metadata, constraint eval, Elyx + non-literal rejection,
  JSON round-trip, analysis flags).
- **Loader would not load any .plugin file:** `spec_from_file_location`
  returns `loader=None` for the unknown `.plugin` suffix — fixed
  with an explicit `SourceFileLoader` in `amin_loader.py`.
- **Subclass discovery missed in-file classes:** every real plugin
  defines `__name__` metadata, which overwrites the module
  globals' `__name__` before the class statement runs, so
  `__module__` is the metadata value, not the module name — the
  `__module__ == module_name` filter dropped every class. Filter
  removed (only `base_plugin` itself is excluded). CPython stub
  tests: 16/16 passing (hello_py load/unload, last-defined-wins,
  settings round-trip, run_on_queue, bulletin fallback).

## v2.5.1 — Python (.plugin) engine, M1 settings UI (2026-11-15, pushed 22:43 as 4212787)

On top of M0 commit c234f32, one local commit. Licence-neutral
clean-room only (no exteraless/exteraGram code). Zero new upstream
files — patches/ untouched (0001–0026 byte-identical to M0).
JS/Rhino engine untouched; DNS/AI untouched.

### What M1 adds

**Settings rows (`create_settings()` rendering).** New overlay
`pyplugins/PyPluginSettingsRow.java` — pure Java + org.json,
unit-testable, no Android imports. Parses the JSON payload
`{"rows": [...], "warnings": [...]}` (or a bare array) produced by
`amin_loader.serialize_settings()`. Row types: header, switch,
selector, input, edittext, text, divider, custom, plus slider
(rendered as a number-input dialog — no native slider row in M1).
Unknown types are kept as "unsupported" rows, never dropped
silently. `AminPyPluginActivity` gains a Settings section:
async fetch via `PyPluginsController.fetchSettingsRows` (callback
on the UI thread), placeholder / error / no-settings states,
segment-grouped rows, switch toggles, selector + input dialogs
via `EditTextBoldCursor`. Single write path `writePySetting`
persists bool/number as a JSON literal and text as plain text,
then re-fetches rows. A reload listener is registered in
`onResume` and unregistered in `onPause` so a plugin calling
`set_setting(reload_settings=True)` refreshes the page.

**Settings persistence.** `PyPluginSettings.java` gains
`getAllSettingsJson()` plus `getTextValue` / `getBooleanValue`
via `JSONTokener` with a partial-parse guard (a corrupt value
falls back to the declared default instead of crashing the
page). Per-plugin prefs, survives restart; `reload_settings`
semantics: `BasePlugin.set_setting(reload_settings=True)` calls
`requestSettingsReload`, which notifies registered listeners;
`False` (default) writes silently.

**Real Bulletin.** M0's toast+log fallback in `ui/bulletin.py`
is replaced: `show_bulletin` / `show_error` now call
`PyPluginHost.showBulletin` / `showError`, which use
`LaunchActivity.getLastFragment()` + `BulletinFactory` with the
4-arg `createSimpleBulletin(icon, text, buttonText, Runnable)`
overload. Button `on_click` runs on the Python thread. Toast+log
remains only as the fallback when no fragment is available
(callback NOT run in that case — documented). The 4-arg
BulletinFactory overload is the one upstream-API call NOT
locally verifiable (no Android SDK on the VM); CI build is the
first check, device the verdict.

**Python SDK additions.**
- `amin_context.py` — current-plugin tracking for the shared
  runtime (replaces the "engine" fallback id).
- `android_utils.py` — `run_on_ui_thread`, `toast`, `log`,
  copy-to-clipboard write-only. Clipboard READ raises (never
  silently exfiltrates).
- `file_utils.py` — sandboxed to
  `filesDir/pyplugins/<id>/files`; rejects absolute paths and
  `..` traversal.
- `ui/settings.py` — row classes Header / Switch / Selector /
  Input / EditText / Text / Divider / Custom +
  `SimpleSettingFactory`. Constructor order is AminGram's own
  `(key, text, ...)` and is documented as possibly differing
  from exteraGram's real signatures — M3 must verify against a
  real .plugin that calls `create_settings()`.
- `amin_loader.py` — `serialize_settings()` emits the JSON
  payload; `_normalise_row()` accepts dict-style and
  object-style rows and rejects primitives (a plain-string row
  used to normalise into garbage because `str.title` is a
  method — caught by the M1 stub tests, fixed); `_setting_callbacks`
  registry + `invoke_setting_callback()`; `call_lifecycle()`
  sets `amin_context` before invoking plugin code.
- `base_plugin.py` — `set_setting(reload_settings=True)` now
  requests a reload; real `export_settings` /
  `import_settings`; `create_settings` documented.
- `client_utils.py` — `run_on_queue` now uses the current
  plugin id, not "engine" (M0 stub verified, M1 finished).
- `ui/bulletin.py` — real Bulletin (above).

**Host / controller.**
- `PyPluginHost.java` — `showBulletin` / `showError`,
  `runOnUiThread`, `copyToClipboard`, `pluginFilesDir` (id
  validated by regex), `requestSettingsReload`.
- `PyPluginsController.java` — `fetchSettingsRows` +
  `SettingsRowsCallback` (UI thread), `fillCurrentValues`,
  settings-reload listener registry held as `WeakReference`
  (the fragment holds its `Runnable` strongly as a field —
  a listener stored only weakly would be GC'd immediately),
  `dispatchSettingChanged` runs the row `on_change` + optional
  `on_settings_changed(key)`, `openPluginSettings` via the last
  fragment, lifecycle calls now via `call_lifecycle`.
- Facade `com.exteragram.messenger.plugins.PluginsController` —
  `openPluginSettings` wired to `AminPyPluginActivity`.
- `PyPluginAnalysis.java` — new `gift` and `security`
  findings; the `stars` finding no longer claims gifts (bare
  "gift" needle removed); `__requirements__` text now says the
  M4 pip plan. Non-empty `__requirements__` still blocks install
  with a clear message (pip support is M4).

**Fixture.** `assets/amingram_pyplugins/settings_demo.plugin`
— all 8 row types + one dict-style switch; its switch
`on_change` shows a Bulletin.

### Files changed (10 modified + 5 new, +1080/−56)

Modified: `com/exteragram/messenger/plugins/PluginsController.java`,
`amin/pyplugins/{PyPluginAnalysis,PyPluginHost,PyPluginSettings,PyPluginsController}.java`,
`amin/ui/AminPyPluginActivity.java`,
`python/{amin_loader,base_plugin,client_utils}.py`,
`python/ui/bulletin.py`.
New: `amin/pyplugins/PyPluginSettingsRow.java`,
`python/{amin_context,android_utils,file_utils}.py`,
`python/ui/settings.py`,
`assets/amingram_pyplugins/settings_demo.plugin`.

### M1 verification (2026-11-15, all local, before any push)

- **Patch-series replay (full, this time by the CI method):**
  pristine upstream 21f7d10 fetched as the codeload tarball
  (blob hash of `SendMessagesHelper.java` cross-checked against
  the GitHub API blob `55452d3d…`), committed as a scratch repo,
  overlay copied in (`cp -r TMessagesProj`, CI step), all 24
  patches 0001–0026 applied in CI order with `git apply` —
  **24/24 OK**, zero failures. All 160 overlay files present
  and **byte-identical** in the replayed tree (the 4 icon
  collisions are the intended rebrand overwrites).
  NOTE: an earlier GNU-`patch` replay of the same series
  reported 2 failures (0013 hunk #1, 0023 hunk #2). Both were
  GNU patch artifacts, not series defects: `git apply` (what CI
  uses) applies 0013 cleanly on a verified-pristine file, and
  the 11-line hunk context was proven byte-identical at the
  target location. Do not use GNU `patch` for this series.
- **JDK 17 parse:** ParseCheck PARSE OK (7 touched Java files).
- **Python:** `py_compile` OK on all 9 M1 Python files (incl.
  the demo fixture). CPython stub tests: M0 suite 16/16, M1
  suite `/tmp/pytest/test_pyplugins_m1.py` **33/33** (stub
  `/tmp/pytest/java.py` extended with showBulletin/showError/
  runOnUiThread/copyToClipboard/pluginFilesDir/
  requestSettingsReload/getAllSettingsJson). Caught and fixed
  1 real bug (plain-string settings row, above).
- **Java logic tests:** fresh compile from current repo sources
  (UTF-8, test-only org.json stub):
  `/tmp/javatest/TestM1.java` **22/22** (analysis
  gift/security/stars, SettingsRow parsing incl. demo payload,
  options, defaults, unsupported, bare array, malformed/null
  payloads), `/tmp/javatest/TestMeta.java` **25/25**. Required
  extending the test stub (`opt`, `optDouble`, `isNull`,
  `optJSONObject`, recursive value parsing) and fixing 2
  production-code portability issues (`new JSONArray(String)`
  → wrap in JSONObject; `optString(i, "")` → `opt(i)` + null
  check). Real org.json has those APIs; the fix keeps the code
  testable against the stub.
- **Secret scan:** zero — pattern scan over all 15 M1 files
  (modified + new) for keys/tokens/passwords/private keys.
  The only "token" hits in the raw diff are `JSONTokener`
  (org.json class name).
- **No local Gradle build** (no Android SDK on this VM) — CI is
  the only build proof; green CI proves compilation only, never
  device behaviour.

### Gaps / stubs left (M2 needs)

- `createSimpleBulletin` 4-arg overload not locally verifiable
  (CI first, device the verdict).
- `ui.settings` constructor order is AminGram's own; verify
  against a real exteraGram plugin's `create_settings()` in M3.
- Slider rows render as number-input dialogs (no native slider
  in M1).
- `get_last_fragment` / `add_hook` / menus still M2.
- DEX / ZwyLib / MaterialSettingsList still M3.
- `__requirements__` still refused (pip = M4).
- Python engine still default OFF; on-device behaviour unproven
  (needs CI build + Amin's phone test: install
  settings_demo.plugin, toggle rows, restart, verify values
  persist, Bulletin shows on switch change).

## v2.5.2 — Python (.plugin) engine, M2 hooks (2026-11-15, pushed 22:57 as 7344795)

On top of M1 commit 4212787, one local commit. Licence-neutral
clean-room only (no exteraless/exteraGram code copied; from the
public SDK docs + AminGram's own code). JS engine, DNS, AI
untouched.

### What M2 adds

**Hook registry (`amin/pyplugins/PyHookRegistry.java`, new)** —
the exteraGram SDK's event-interception surface, reimplemented
on AminHooks call sites (NOT native method hooking):
- Named hooks `add_hook(name, match_substring, priority)`:
  observe-only, async on the Python thread. Fired for
  `"message"` (incoming, post-delivery) and `"app_start" /
  "app_stop" / "app_pause" / "app_resume"`. A non-default return
  value is logged and ignored — the event has already happened.
- Send-message hooks `add_on_send_message_hook(priority)`:
  the ONE synchronous Python call in the engine. Runs for EVERY
  outgoing message from `AminHooks.onOutgoingMessage` (not just
  "/" commands). Priority order, highest first; MODIFY rewrites
  the text (later hooks see it), MODIFY_FINAL rewrites and stops,
  CANCEL suppresses. Bounded by an 800 ms latch wait — timeout or
  a throwing hook means DEFAULT (message sends unchanged). A hook
  must not send a message synchronously from inside the callback
  (nested send waits on the same Python thread and times out).
- Menu items (`MenuItemData`, `ui/menu.py`): MESSAGE_CONTEXT_MENU
  (patch 0020 call site, shares the 4-slot cap with JS, JS first)
  and CHAT_ACTION_MENU (patch 0014 call site). Conditions are
  evaluated in Java at menu-build time (no Python round-trip on
  the UI thread). Taps dispatch async to `on_click`, or to the
  plugin's `on_menu_action(location, data)` when none was given.
  DRAWER_MENU / MAIN_MENU / PROFILE_ACTION_MENU items are
  accepted and stored but never shown — no upstream hook point
  exists yet (M3 decision, not silently dropped).
- Registrations live only while the plugin is loaded:
  unregistered on unload and before re-registration on load, so
  a stale callback can never fire.

**MVEL-subset conditions (`amin/pyplugins/PyMenuCondition.java`,
new)** — pure Java, no Android/Chaquopy dependency: `and`/`or`/
`not`, `== != < > <= >=`, `contains`, `startswith`, parentheses,
literals (bool/number/quoted string/`null`), dotted identifiers
(`message.out`, `chat.type`, `account`). Unknown identifiers
resolve to null; invalid syntax fails CLOSED (item hidden).
Deliberately NOT supported: method calls, indexing, arithmetic,
assignment, `in`, ternary. Full MVEL was rejected (new
dependency + its own reflection surface).

**LaunchActivity lifecycle (patch 0027 — +1 upstream file,
`org.telegram.ui.LaunchActivity`, total now 33 vs the 40 cap
(raised 30 → 40 by Amin, 2026-11-15 23:20):** onCreate → START
(once per process — activity recreation is not an app start),
onResume → RESUME, onPause → PAUSE, onDestroy → STOP (only when
`isFinishing()`). One FQ line each, via `AminHooks`
(`onPyLaunchCreate/Resume/Pause/Destroy`).

**`client_utils` finished (M2):** `get_last_fragment()` (returns
the Java BaseFragment, or None), `get_fragment_stack()`
(`INavigationLayout.getFragmentStack`, verified in pristine
21f7d10), `confirm_sensitive_action(action, amount, recipient,
on_confirm)` — a confirmation bulletin; the action must ONLY run
inside `on_confirm` (no tap = no confirmation). This is the gate
future mediated Stars/gift/security actions must call; no SDK
method currently spends Stars or transfers gifts, and direct
reflection calls bypass it (install analysis + hub copy say so).

**`hook_utils.py` (new):** `add_hook` /
`add_on_send_message_hook` / `HookResult` / `HookStrategy`
re-exported; reflection helpers `find_class` / `get_field` /
`set_field` / `call_method` (every use logged to the plugin);
`hook_method` raises NotImplementedError — native before/after
hooking is M3 (DEX bridge), not this registry.

**`extera_utils.py` (new):** HTML text helpers (`bold`,
`italic`, `code`, `pre`, `link`, `mention`, `escape_html`) and
`ClassProxy` / `proxy_class` (attribute-forwarding wrapper, no
hooking).

**`base_plugin.py`:** `HookStrategy` / `HookResult` (string,
dict, or object return forms all understood), `add_hook` /
`add_on_send_message_hook` in direct + decorator forms
(decorator tags `_amin_hook` / `_amin_send_hook`; the host
registers them at load via `amin_loader.
register_decorated_hooks`), `add_menu_item` / `remove_menu_item`
/ `on_menu_action`. Bare `@add_on_send_message_hook` (no parens)
is a decorator when called with no plugin context (import time)
and a direct registration otherwise — without this, the bare
form registered under "engine" at import time, fired under the
wrong plugin id, and was never unregistered (caught by the M2
stub tests; same hazard documented for direct registration
outside a plugin context, which now logs a warning).

**`AminHooks` wiring (overlay only, no patch change):**
`onOutgoingMessage` runs the Python send-hook chain FIRST for
every outgoing message, then the existing JS "/" suppression +
async `on_command` fan-out (a rewrite that drops the leading
"/" also drops the fan-out — the hook owns the message).
Context-menu conditions get `{chat: {id, type}, message: {id,
text, out, dialog_id, date}, account}`; chat type is
best-effort (negative id → group unless the chat is a channel;
positive id → private, "bot" for bots). Attach-menu conditions
see only `{account}` (no chat/message at that call site).
Menu ids are namespaced `py:ctx:<pluginId>:<ordinal>` /
`py:attach:<pluginId>:<ordinal>` so JS dispatch is untouched.

**Fixture:** `assets/amingram_pyplugins/hooks_demo.plugin`
(decorator hooks, `/shout` MODIFY rewrite, context menu with
`message.out == true`, attach menu, extera_utils + hook_utils
use).

### Deliberately deferred (M2 decision, recorded)

- **TL request/response/update interception via
  ConnectionsManager: DEFERRED to M3.** The update stream
  already reaches plugins through `processUpdateArray`
  (patch 0006), and the send path is covered by the send-hook
  chain. Intercepting `sendRequest` would need a new hook point
  in a core RPC flow whose exact request/response lifecycle was
  not verified in M2; a wrong hook there risks corrupting
  requests for the whole app. It needs its own spike, not a
  drive-by patch.
- DRAWER_MENU / MAIN_MENU / PROFILE_ACTION_MENU rendering
  (no hook points; M3).
- Python↔JS relay (M4).

### M2 verification (2026-11-15, all local, before any push)

- **Patch-series replay (git apply only — GNU patch is banned
  for this series, see M1):** copy of the pristine 21f7d10 tree
  (`verify-scratch/pristine`, the M1 codeload tarball tree),
  overlay copied in (`cp -r TMessagesProj`, CI step), all 25
  patches 0001–0027 applied in CI order — **25/25 OK**, zero
  failures. All **166** overlay files byte-identical in the
  replayed tree (0 mismatches). The 4 M2 calls land in
  LaunchActivity at lines 411 (onCreate), 6906 (onPause), 7107
  (onDestroy), 7224 (onResume). NOTE: the first replay attempt
  used `verify-scratch/pristine`, which turned out to be an
  older tree (LaunchActivity 9249 lines, all patches failed) —
  the real 21f7d10 tree is the M1 codeload tarball
  (`m1-upstream/upstream.tar.gz`, top dir `Telegram-21f7d10`),
  re-extracted as `verify-scratch/m2/pristine21`. Patch 0027's
  anchors and hunk counts were re-derived from that tree (its
  first draft had wrong hunk counts and failed `git apply` with
  "patch fragment without header" — fixed, not forced).
- **JDK 17 syntax parse:** ParseCheck (JavacTask parse-only,
  `verify-scratch/m2/ParseCheck.java`) PARSE OK on all 6 touched
  Java files (PyHookRegistry, PyMenuCondition, PyPluginHost,
  PyPluginsController, PyHookDispatcher, AminHooks). NOTE:
  `-proc:only` does NOT do a syntax-only check (it still
  resolves symbols); the M0/M1 "parse" claim used the same
  JavacTask approach.
- **Python:** `py_compile` OK on all 12 Python files (incl. the
  hooks_demo fixture). CPython stub tests
  `verify-scratch/m2/test_pyplugins_m2.py` **56/56** (new stub
  `verify-scratch/m2/java.py` — the M0/M1 harness lived in /tmp
  and was wiped by a runtime restart; the M2 harness lives in
  the persistent workspace). Caught and fixed 1 real bug (bare
  `@add_on_send_message_hook` registering under "engine", above).
  M0/M1 regression inside the suite: hello_py loads,
  settings_demo still serialises 9 rows.
- **Java logic tests:** `verify-scratch/m2/TestM2.java`
  **37/37** (PyMenuCondition, compiled + run on plain JDK 17:
  comparisons, and/or/not, parens, contains/startswith, nulls,
  fail-closed invalid syntax/arithmetic/method calls).
- **Secret scan:** zero — pattern scan over the full M2 diff
  (modified + new files) for keys/tokens/passwords/private keys.
- **API cross-check vs pristine 21f7d10 source (not just
  stubs):** `INavigationLayout.getFragmentStack()`,
  `LaunchActivity.getLastFragment()`,
  `MessagesController.getChat(Long)` / `getUser(Long)`,
  `ChatObject.isChannel(Chat)`, `UserConfig.selectedAccount`,
  `SendMessageParams.peer` (already used by JsPluginManager),
  `BulletinFactory.createSimpleBulletin` 4-arg overload (already
  used by M1) — all present with the signatures used.
- **NOT verified locally (needs CI + device):** no local
  Gradle build (no Android SDK on this VM); the synchronous
  send-hook latch has never run under Chaquopy (timeout and
  modify/cancel semantics are code-reviewed, not executed);
  menu rendering, AppEvent dispatch, and the fragment helpers
  are unproven on device. CI green proves compilation only.

### Gaps / stubs left (M3 needs)

- `add_hook("tl_*")` / ConnectionsManager interception: deferred
  (above).
- DEX bridge (XposedBridge shim vs AliuHook): M3 spike first
  (decompile MaterialSettingsList DEX, list exact hook targets).
- ZwyLib as a library-plugin + MaterialSettingsList: M3.
- `__requirements__` still refused (pip = M4).
- Python engine still default OFF; on-device behaviour unproven
  (needs CI build + Amin's phone test: install hooks_demo,
  `/shout hello` rewrites, long-press an outgoing message for
  the Demo menu item, background/foreground for app_resume log,
  attach menu shows the Demo item).

## v2.5.3 — Python (.plugin) engine, M3 DEX bridge + ZwyLib (2026-11-15, pushed 23:19 as 006653e; CI failed, abiFilters — see M4)

M3 scope, in order: (1) decompile the DEX inside
MaterialSettingsList v1.1.1, (2) shim-vs-AliuHook decision from
the spike, (3) DEX bridge + per-plugin DEX consent, (4) ZwyLib
as a clean-room library + MaterialSettingsList unmodified,
(5) SettingsActivity reorder/debloat via the hook points.
Licence-neutral clean-room only — no exteraless/exteraGram code
copied (the GPL-2.0-only vs GPL-3.0 blocker is still open; see
Phase 0). No push by the subagent; the parent pushes after the
report. JS engine, DNS, AI untouched.

### DEX decompile findings (spike, local only — nothing uploaded)

DEX_BASE64 is 23,680 base64 chars → 17,760 bytes LZMA/XZ →
53,384 bytes DEX, magic `dex\n038`, SHA-256
`bbeb7267b8cf5de2ff0b4f0beee305d2c0e2bf7f7ec62961fa51c669cfe01b1e`.
Decompiled with jadx 1.5.2 into `/tmp/m3/jadx-out` (29 classes,
10 files, 1,601 lines; scratch, not in the repo).
`PluginCore.init(Context)`: `paletteManager.bakePalettes()`,
`settingsCellHooks.setupHooks()`, `listItemsManager.setupHooks()`.
`unload()`: unhooks all Unhooks, clears the reflection cache,
refreshes SettingsActivities.

Exact hook targets (all via `XposedUtilsKt.hook()` →
`XposedBridge.hookMethod(Method, XC_MethodHook)`, method resolved
by `findMethodMaxArgs` = max-arg overload of that name):
1. `SettingsActivity$SettingCell$Factory.bindView` — before:
   `onBindViewBefore` (args[0]=SettingCell view, args[1]=UItem;
   `uItem.longValue==0` → drop from paletteKeys; else store
   `uItem.id`, or BOT_KEY when `uItem.object` is
   `TL_attachMenuBot`); after: `onBindViewAfter` (`uItem.id==-1`
   + palette-tracked cell → `getIconView().setImageResource(
   R.drawable.ic_foreground_solid)`).
2. `SettingsActivity$SettingCell.updateColors` — after:
   `applyColors(cell)` (palette by UItem id, or bot palette).
3. `SettingsActivity.fillItems` — after: `onFillItemsAfter`
   (args[0]=`ArrayList<UItem>`, mutates the list in place:
   `applyDebloat` → `applyReorder` → `cleanShadows`).

Reorder groups (exact): `[-1,-2,-3], [1,3,8], [2,7,10], [5,6,9],
[11,12,13,25,15,16], [17,18,23,19], [24,26,20,21,22]`. Bots
(`TL_attachMenuBot` objects) appended after group 5; prefix
before the first SettingCell and the trailing version-shadow
item preserved; leftover ids appended after the groups; an
`asShadow(null)` inserted after each non-empty group.
Debloat IDs (exact): Premium=11, Stars=12, MyTON=13, Business=15,
Send Gift=16, Wallet=25 (plus any attach-menu bot when wallet
debloat is on), Help=[17,18,23,19]. Quirk: `applyDebloat` also
drops ALL header items whenever any debloat flag is on.
`cleanShadows`: collapses consecutive pure shadows, strips
trailing pure shadows, keeps a trailing version shadow.
`SettingsSyncManager`: `syncInitialState(8×boolean)` sets all
flags then bake + refresh; reorder setter bakes + refreshes
immediately; each debloat setter debounces refresh 1000 ms.
Palette: 10 base colours as signed ints (EXTERA -1416863, AYU
-5152278, ZWY -7897647, BLUE -15231257, GREEN -12664771, ORANGE
-1411294, GOLD -1071598, CYAN -14965523, DEFAULT_BASE -7956054,
BOT_BASE -1071598); two 27-entry maps (base / regroup, chosen by
the reorder flag at bake time); `generateM3Pair` HSL with
saturation clamped 0.55–1.0, dark/light × monet/non-monet
variants. The full maps live in the DEX itself (re-derivable any
time: base64+LZMA-decode DEX_BASE64 from the .plugin file and
decompile with jadx) — they were not copied into the repo.

Upstream 13.2.2 gaps the DEX hits (each caught per-call by the
DEX's own try/catch, so they degrade rather than crash):
- `Theme.isCurrentThemeMonet()` — missing upstream (0 hits);
  fixed by patch 0028 (+1 upstream file, see below).
- `SettingCell.getIconView()` — no getter upstream (private
  field only); fixed by patch 0028.
- `Background.setColor(int, int, boolean)` — upstream has only
  the 2-arg form; fixed by a delegating 3-arg overload.
- `R.drawable.ic_foreground_solid` — no such resource upstream;
  the branch using it fires only for `uItem.id == -1` rows,
  which do not exist in upstream fillItems, so it is effectively
  dead — recorded, not fixed.
- Kotlin stdlib is already in the APK transitively (upstream
  `PasskeysController` imports `kotlin.*`), so the
  Kotlin-compiled DEX resolves `Intrinsics` — no dependency
  change was needed.

### Shim vs AliuHook — decision: SHIM, no native hooking

All three hook targets map to call sites inside the
already-counted `SettingsActivity.java`, plus the one
`Theme.java` fix above. No target needs runtime bytecode
rewriting, so AliuHook / LSPlant / Dobby were evaluated and
NOT bundled — bundling native hooking speculatively would add
an unaudited native attack surface for zero benefit. If a
future plugin hooks a method with no source-level point, that
plugin degrades (no-op Unhook + log) instead of silently
failing, and native hooking would be a separate,
explicitly-flagged decision then.

### What M3 implemented, file by file

New patch 0028-pyplugins-settings-hooks (CI: `git apply` after
0027): `SettingsActivity.java` — import + 3 hook call sites
(fillItems end, Factory.bindView before/after,
SettingCell.updateColors after) + `getIconView()` getter +
`Background.setColor(int,int,boolean)` overload;
`ActionBar/Theme.java` — `isCurrentThemeMonet()` returning
false (truthful: upstream has no monet themes);
`proguard-rules.pro` — keeps for `de.robv.android.xposed.**`
and the three new members (R8 must not rename/strip what the
DEX resolves by name). New upstream files touched: Theme.java
(+1). Honest touched-file count is now **34** (32 files via
patches + the 2 CI inline edits to FileLoadOperation /
FileUploadOperation). The 40-file rebase cap (raised 30 → 40
by Amin, 2026-11-15 23:20; patch files + CI inline edits
counted together) leaves headroom 6.

New overlay Java (documented exceptions to the amin.** rule —
the DEX resolves these literal names):
- `de/robv/android/xposed/XC_MethodHook.java` — clean-room
  Xposed callback type (before/after no-ops, MethodHookParam
  with public `thisObject` / `args` fields + get/setResult,
  nested Unhook). Deviation documented in code: the shim is
  before/after notification, not true interception — a throwing
  before-hook cannot skip the method and setResult is never
  honoured (the DEX only observes args and mutates args[0] in
  place, which the shim supports).
- `de/robv/android/xposed/XposedBridge.java` — `hookMethod`
  only, routing to the registry; unknown methods return a
  no-op Unhook + log instead of throwing.
- `org.telegram.messenger.amin.pyplugins.PySettingsHookRegistry`
  — the dispatch: pointFor maps a reflected Method to
  fillItems / bindView / updateColors (class name + method
  name); onFillItemsAfter / onBindViewBefore+After /
  onUpdateColorsAfter build the MethodHookParam with the exact
  thisObject/args the DEX reads; per-hook try/catch (a throwing
  hook never takes the settings screen down), list copied
  before iteration, synchronised (register on the Python
  thread, dispatch on the UI thread). Also `hookByName` for
  the Python `hook_utils.hook_method` bridge (PyObject
  before/after callables, tracked per plugin id,
  `unregisterPlugin` on unload).

Modified overlay Java:
- `com.exteragram.messenger.utils.AppUtils` — M0's empty
  placeholder now implements `log(String, Throwable)` (+ 1-arg
  form), exactly the overload `PluginCore.log` calls; logcat
  tag `AminGramDEX` (the DEX carries no plugin-id context, so
  it cannot land in the hub per-plugin log). No other AppUtils
  method added.
- `PyPlugin` — new `needsDexConsent` field (+ JSON round-trip;
  old installs without the key default false).
- `PyPluginsController` — install sets `needsDexConsent` from
  the analysis and installs DEX plugins **disabled**; a
  reinstall that newly gains a DEX finding also drops to
  disabled; `setEnabled` refuses enabling a DEX plugin without
  consent (backstop — the page shows its own message);
  `loadPlugin` gates on consent (defence in depth); new
  `setDexConsent` (grant loads an enabled plugin right away,
  revoke unloads it); `unloadPlugin` also removes the
  plugin's Python-bridge settings hooks.
- `AminPyPluginActivity` — "Allow DEX / native code" toggle
  (default OFF) in the State section, shown only when
  `needsDexConsent`, with a plain-language warning row; the
  enable toggle refuses with a bulletin when consent is missing;
  footer updated M1 → M3.
- `AminPyPluginsActivity` — hub footer updated ("DEX loads only
  with per-plugin consent").

New Python SDK (`TMessagesProj/src/main/python/zwylib/`,
clean-room re-implementation of the small ZwyLib surface
MaterialSettingsList uses — written from that plugin's usage
only; the real ZwyLib was never available, nothing of it is
bundled/re-hosted/redistributed):
- `__init__.py` — `__version__ = "2.0.6"`,
  `is_zwylib_version_sufficient` (tuple compare, optional
  notify bulletin), `build_log` (stdlib logger),
  `build_bulletin_helper` (delegates to ui.bulletin),
  `add_autoupdater_task` (NO-OP stub, logged — AminGram never
  auto-updates plugins), `HookUtils.hook_method` (delegates to
  hook_utils). Precedence documented: Chaquopy puts
  `src/main/python/` on sys.path ahead of the plugins dir, so
  `import zwylib` always resolves to this package; installing
  a real `zwylib.plugin` is unnecessary and has no effect on
  imports.
- `setting_entry.py` — `SettingEntry(key, default)` backed by
  the host per-plugin store (read at call time via
  amin_context, so import-time construction is safe);
  `as_switch(text, on_change, link_alias)` returns the M1
  dict row; the on_change wrapper stores the value BEFORE the
  plugin callback runs (the plugin's lambdas read
  `entry.get()`). `link_alias` accepted, ignored (no settings
  deep-links in AminGram).
- `expandable_switch.py` — `ExpandableOption(label, entry,
  on_change)` + `ExpandableSwitchManager.generate(title,
  [options])` → header row + switch rows. Rendering deviation:
  the M1 page has no collapsible groups, so groups render flat
  (header + switches); values/keys/wiring identical.
- `locales_controller.py` — `generate_locale_class({lang:
  {key: text}})` picks the current Telegram locale
  (`pluralLangCode`), falls back to "en", then first table.

Modified Python:
- `hook_utils.py` — `hook_method(class_name, method_name,
  before, after)` implemented for the three SettingsActivity
  points only (via `PySettingsHookRegistry.hookByName`,
  returning the Java Unhook); any other target raises
  NotImplementedError. Chaquopy `PyObject.call(param)` interop
  is unproven on device until the phone test.
- `extera_utils.py` — docstring only (hook_method is no longer
  "not implemented").

NOT in the repo: the MaterialSettingsList `.plugin` file itself
(user-supplied, loaded unmodified from
`~/workspace/user/files/` in the tests only), the DEX, and the
jadx output (`/tmp/m3/`, scratch).

### M3 verification (2026-11-15, all local, before any push)

- **Patch-series replay (git apply only):** fresh pristine
  21f7d10 tree from `m1-upstream/upstream.tar.gz`, overlay
  copied in (`cp -r TMessagesProj`, CI step), all 28 patches
  0001–0028 applied in CI order — **28/28 OK**, zero
  failures. All **187** files under repo `TMessagesProj`
  byte-identical in the replayed tree (0 mismatches). NOTE: the
  first patch-0028 draft was generated with `git diff` over a
  tree that also held the uncommitted 0001–0027 changes, so it
  contained all 32 files — caught before use; the tree was
  rebuilt (pristine → 0001–0027 committed → M3 edits → diff)
  and the clean 3-file / +53-line patch regenerated.
- **JDK 17 syntax parse:** ParseCheck PARSE OK on all 8
  touched overlay Java files (shim ×2, registry, PyPlugin,
  PyPluginsController, both plugin activities, AppUtils) + the
  2 patched upstream files (SettingsActivity, Theme). NOTE:
  the VM default charset is US-ASCII, so plain `javac` fails
  on the em-dashes in comments — `-encoding UTF-8` is required
  locally (CI/Gradle uses UTF-8).
- **Python:** `py_compile` OK on the 4 new zwylib files +
  hook_utils. CPython stub tests
  `verify-scratch/m3/test_pyplugins_m3.py` **62/62** (new stub
  `verify-scratch/m3/java.py`: fake DEX whose PluginCore /
  SettingsSyncManager record every reflective call; the real
  base64+LZMA payload is decoded by the plugin itself). The
  suite loads the REAL unmodified MaterialSettingsList file:
  resolves to MaterialSettingsIcons (not EmptyPlugin),
  force_stop NOT triggered, DEX init + syncInitialState with
  all-8-False, 9 settings rows with all 8 keys, all 8 toggles
  reaching the fake DEX setters with the right values (incl.
  wallet True→False), sync-after-toggles reading stored values
  back, unload calling DEX unload, inject refusing on SDK 25.
  Also: zwylib version gate (accept 2.0.6/2.0.5/1.0, refuse
  2.0.7/garbage), locale en/ru/fallback, SettingEntry
  persistence + wrapper-stores-first, ExpandableSwitchManager
  flat rows, autoupdater logged no-op, hook_method routing +
  refusals. M0/M1 regression inside the suite: hello_py loads,
  settings_demo still serialises 9 rows. Two test bugs caught
  during the run (settings_demo has 9 rows, not 8 as the M3
  test first asserted — M2 asserted 9; a direct
  `sync_initial_state()` call needs the plugin context the host
  normally sets) — both were test bugs, not code bugs. NOTE:
  the M2 suite's old `hook_method()` no-arg expectation is now
  obsolete (the call raises TypeError for missing arguments;
  the meaningful check — unknown target →
  NotImplementedError — is covered in the M3 suite).
- **Java logic tests:** `verify-scratch/m3/javatest/TestM3.java`
  **30/30** (real registry + shim, stub Android/Chaquopy/
  upstream types on plain JDK 17): pointFor routing + decoy
  rejection, fillItems in-place list mutation visible to the
  caller, bindView/updateColors param fidelity (thisObject /
  args), unhook removal, throwing hook does not stop later
  hooks, registration order, hookByName + unregisterPlugin,
  unknown-point rejection, PyPlugin JSON round-trip (incl. old
  JSON defaulting needsDexConsent false).
- **Secret scan:** zero — pattern scan over the full M3 diff
  (tracked modifications + all new files: shim, registry,
  zwylib, patch 0028).
- **NOT verified locally (needs CI + device):** no local
  Gradle build (no Android SDK on this VM); Chaquopy varargs
  interop on the DEX path (`Method.invoke(None, ctx)`,
  `method.invoke(None, *args)` with 8 booleans) is code-traced
  only; `PyObject.call(param)` for Python hook_method is
  unproven; the DEX's `refreshSettingsActivities` fragment
  walk (`LaunchActivity.instance` → `actionBarLayout` →
  `getFragmentStack`, `listView` field walk,
  `ViewPagerActivity.fragmentsArr`) is unproven on device; the
  palette recolouring (`getPalette` → `isCurrentThemeMonet`
  false → non-monet palettes) is unproven until the settings
  screen is opened with the plugin on.

### Gaps / stubs left (M4 needs)

- On-device verdict (needs CI build + Amin's phone test):
  install MaterialSettingsList (DEX consent ON), toggle all 8
  settings, restart, confirm reorder/debloat persist, disable
  the plugin and confirm stock Settings returns; also install
  hello_py / settings_demo / hooks_demo as before.
- `R.drawable.ic_foreground_solid` still missing upstream —
  dead in practice (only `uItem.id == -1` rows), but a future
  upstream/settings change could revive that branch; if it
  ever fires, the DEX logs it via AppUtils.log and continues.
- `add_autoupdater_task` stays a no-op stub by design (M4
  decision, needs its own consent + hash verification).
- ExpandableSwitchManager renders flat (no collapsible groups)
  — a later milestone can add collapsing without changing the
  plugin-side API.
- Library-plugin loading: a real `zwylib.plugin` file (no
  BasePlugin subclass) would fail to load as a plugin with the
  standard "no BasePlugin subclass" error — the host zwylib
  makes installing one unnecessary, but a general
  library-plugin load path is still an M4 gap.
- `add_hook("tl_*")` / ConnectionsManager interception: still
  deferred (M2). `__requirements__` still refused (pip = M4).
- Patch cap 30→40 APPROVED by Amin 2026-11-15 23:20; honest
  count is now 34 (32 patch files + 2 CI inline edits), headroom 6.
  Counting rule: patch files + CI inline edits together, each
  distinct upstream file counted once. M4 adds zero new upstream files.

## v2.5.4 — Python (.plugin) engine, M4 catalog + Elyx + DevServer + security (2026-11-15)

M4 closes the plugin engine: catalog/repo loading, `.elyx`/`.eaf`
archives, library plugins, a localhost DevServer, the final
`__requirements__` decision, APK-size/startup work, and the full
security review. Zero new upstream files (still 34 touched, cap
40 approved 2026-11-15 23:20).

### CI fix — Chaquopy abiFilters (the reason M1/M2/M3 all failed)

- CI runs 43751207829 (M1, 4212787), 43752502295 (M2, 7344795)
  and 43754597643 (M3, 006653e) ALL failed at step 15 "Build
  release APK (afat)", Gradle configure, ~1m26s in, with the
  SAME error (verified from the actual job logs, not a guess):
  `Variant 'debug': Chaquopy requires ndk.abiFilters` from
  `PythonPlugin.getAbis` (PythonPlugin.kt:268). Patch apply
  (step 4) succeeded in all three runs — the patch series was
  fine; the build never reached compilation.
- Root cause: patch 0025 added the Chaquopy plugin but never set
  `ndk.abiFilters` in `TMessagesProj/build.gradle`
  `defaultConfig`. Upstream has no abiFilters in that library
  module at all. Fix (patch 0025, regenerated from a staged
  commit, never hand-edited):
  `ndk { abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64' }`
  — the four ABIs match the app module's product flavors, and
  Python 3.11 keeps the 32-bit slices.
- Also in 0025: `pyc { src = true }` — Chaquopy compiles
  `.py`→`.pyc` by default but only best-effort (warns and ships
  sources on failure); `src = true` makes it a required build
  step, so a broken .py fails the build instead of shipping
  source. Stdlib trimming is a no-op: Chaquopy already ships
  stdlib as `stdlib-pyc.zip` with test modules, `ensurepip`
  and `pydoc_data` removed; there is no supported per-module
  exclusion knob.
- The M3 CI failure above is EXPECTED: the fix lands in this
  M4 push, not in the M3 run. The next CI run is the first one
  that can go green.

### What M4 adds

- `PyPluginRepo.java` — catalog loader: `extera-plugins.json`
  schema 1 (`parseIndex`, max 1 MB / 500 entries, bad entries
  dropped with a count), `verifyEntry` (id, semver, http(s)
  URL, 64-hex sha256, size 1 B–10 MB), `sha256Hex`,
  `verifyFile` (size then SHA-256; a mismatch is rejected and
  the bytes deleted, never installed), `hostOf`,
  `scanMessages` (collects `.plugin`/`.elyx`/`.eaf` documents
  from channel messages as candidates — no id/sha256 there;
  install gates still run after download). No networking in
  the class; the hub fetches.
- `PyElyxLoader.java` — `.elyx`/`.eaf` ZIP loader:
  `refmap.yml` (`entry:`/`main:`) → `src/main.py` → lone
  `src/*.py` (else refused as ambiguous); keeps only
  `refmap.yml`, `metainfo.yml`, `src/*.py`, `strings/*.yml`;
  Zip-Slip (absolute/`..`) refuses the whole archive;
  symlinks refused; caps 10 MB archive / 2 MB per file / 200
  entries; flat `key: value` YAML parser (no YAML evaluator).
- `PyDevServerHttp.java` + `PyDevServer.java` — DevServer:
  loopback-only bind (failure never falls back to `0.0.0.0`),
  32-char random token fresh per start, never logged/stored,
  `POST /plugin` (max 10 MB) + `GET /status` only, constant-time
  token compare, auto-stop 60 s after background (cancellable),
  stop on leaving the hub, uploads go through the normal install
  gates. Default OFF, explicit start.
- `PyPluginsController.java` — `installFromBytes` routes ZIP
  magic to `installElyx` (10 MB cap; entry + modules analysed;
  metainfo id/version must match entry source; writes
  `<id>/__main__.py` + `src/` + `strings/`); library fallback
  in `loadPlugin` (on "no BasePlugin subclass", calls
  `load_library`, marks `plugin.library`, logs, no
  lifecycle/hooks); `loadedLibraries` set; `unloadPlugin` /
  `delete()` handle libraries and Elyx dirs; stale-storage
  cleanup when a reinstall changes format plugin↔elyx.
- `PyPlugin.java` — `format` (`"plugin"`/`"elyx"`, old installs
  default `"plugin"`) + `library` boolean, JSON round-trip.
- `PyPluginsPrefs.java` — `KEY_UNKNOWN_SOURCES` (default
  false), `KEY_REPO_URLS`, `KEY_DEVSERVER`, `MAX_ARCHIVE_BYTES`.
- `amin_loader.py` — extracted `_import_module()`; new
  `load_library(file_path, module_name)` (imports, keeps in
  `sys.modules`, no subclass required); `load_plugin` uses the
  shared importer.
- `AminPyPluginsActivity.java` — Catalogs section
  (unknown-sources toggle, saved catalog rows, Add-catalog
  dialog with gate check, `refreshCatalog` plain-HTTPS fetch
  on a background thread → entry list dialog →
  `downloadAndInstallEntry` with SHA-256/size verify before
  `confirmInstall`); DevServer section (toggle + info row
  showing `127.0.0.1:PORT` and the live token);
  `confirmInstallElyx` review (entry + all modules analysed);
  `onPause` schedules auto-stop, `onResume` cancels it,
  `onFragmentDestroy` stops the server; footer updated to M4.
- `.gitignore` — added `__pycache__/`.

### Final decisions (M4)

- `__requirements__` / pip: KEEP BLOCKING (final). A pip
  installer would download and execute arbitrary code from
  PyPI at install time — exactly what the install gates exist
  to stop — and add megabytes of wheels. The refusal message
  now says so in the controller, the analysis finding, and here.
- Python↔JS relay bridge: deferred. No concrete plugin needs
  it; the bridge would be a new cross-engine attack surface
  with no consumer. Revisit if a plugin requires it.
- ABI splits: deferred. Per-app ABI flavors live in
  `TMessagesProj_App/build.gradle`, an upstream file M4 may
  not touch; Chaquopy's own FAQ says splits/app bundles help
  little for its native components (flavor-per-ABI is the
  recommended route). Needs a parent-approved upstream-file
  change in a later milestone.
- Channel-history loading for catalog scanning: `scanMessages`
  extraction is done; the one SearchMessages request to load a
  channel's history into it is NOT wired in the hub (catalog
  fetch covers the main flow). Recorded gap.

### Verification-found bugs fixed by the parent during M4 review (2026-11-15 ~23:40)

These were NOT in the M4 agent's report — the parent's
independent verification caught them before commit:

1. `PyElyxLoader.isSymlink` called
   `ZipEntry.getExternalAttributes()`, which does NOT exist on
   `java.util.zip.ZipEntry` — neither on the JDK nor on Android.
   The M4 code would have failed CI compilation exactly like
   the abiFilters bug did. Fixed: `load(InputStream)` now reads
   the archive (capped 10 MB) and `load(byte[])` parses the ZIP
   central directory by hand (`centralDirectoryAttrs`) for the
   Unix file-type bits; `isSymlinkAttrs` checks the 0120000 bit.
   Test helper `zipRaw` in TestM4 had the same non-existent
   `setExternalAttributes` call — it now patches the central
   directory bytes directly (`patchCentralAttrs`).
2. `PyDevServerHttp.serveOne` closed the socket after a non-200
   response without draining a POST body, so a client that sent
   a body (curl) got TCP RST ("Connection reset") instead of
   the 403/404. Fixed: drain a small POST body (≤ MAX_BODY)
   before answering a non-200 status; never drain an oversized
   body (413 must not read it).
3. `PyPluginMetadata.parse` rejected parenthesised implicit
   string concatenation (`__description__ = ("a" "b")`) — but
   CPython folds that into one literal Constant, so the real AST
   loader accepts it, and MaterialSettingsList itself writes its
   `__description__` exactly that way. The flagship plugin
   could NOT have installed. Fixed: the parser now joins
   parenthesised adjacent string literals (paren depth counted
   outside string literals); `+-concatenation`, variables and
   triple-quoted values are still refused. TestM4 now asserts
   the real MSL file parses (id `material_settings_list`,
   version `1.1.1`, description contains "Redesigns" + "ZwyLib").
4. Test-harness fixes (scratch only, not repo code): the M4
   CPython test had no `java` stub on its path (load_plugin
   imports base_plugin, which imports jclass at module level —
   its docstring wrongly claimed java is never imported); it
   now reuses the M3 stub. The M2 suite's old `hook_method()`
   no-arg expectation is obsolete since M3 gave hook_method
   required args — it now checks the real rule (unknown target
   → NotImplementedError). The serveOne live-loopback test was
   replaced with a fake-socket test: this VM's sandbox
   intercepts localhost TCP (verified with a repro — the client
   received the sandbox's own message instead of the server's
   response), so a live-socket test cannot pass here; the fake
   socket exercises the same serveOne code path, and real
   loopback is CI / on-device territory.

### Verification (parent, 2026-11-15 ~23:40, all local, no Android SDK)

- **JDK 17 parse:** `ParseCheck` OK, 19 files (all new/changed
  Java incl. `PyPluginsController`, `AminPyPluginsActivity`,
  `PyDevServer`, `PyPluginsPrefs`).
- **Patch series replay:** 0001–0028 `git apply` clean on a
  fresh pristine `21f7d10` tree (codeload tarball, verified
  pristine: no `AminGram`/`messenger.amin`/`pyplugins`
  references before applying); patch 0025's abiFilters present
  in the replayed `TMessagesProj/build.gradle`.
- **Java logic tests:** `verify-scratch/m4/javatest/TestM4.java`
  **73/73** (catalog repo incl. tampered/size-mismatch rejects,
  Elyx incl. Zip-Slip/symlink/oversize/not-a-zip, DevServer HTTP
  incl. fake-socket serveOne 403/200/upload, PyPlugin JSON
  round-trip, PK refusal, MSL analysis proof dex+reflection+
  blob+needsDexConsent, synthetic network/files/messages,
  clean-plugin + requirements findings).
- **CPython tests:** M4 `test_pyplugins_m4.py` **8/8**
  (load_library, load_plugin library refusal + sys.modules
  cleanup, hello regression, syntax-error cleanup); M2 suite
  **56/56**; M3 suite **62/62** (regression, unchanged).
- **Secret scan:** zero — pattern scan over the full M4 diff
  (tracked modifications + all new files).
- **NOT verified locally (needs CI + device):** no local Gradle
  build (no Android SDK on this VM); CI is the only build proof
  for the abiFilters fix; cold-start time with the Python engine
  OFF vs ON and APK size delta are unmeasurable locally.

### Security review (M4, hub-copy language)

- Catalog downloads are SHA-256 + size checked against the
  catalog entry; a mismatch is rejected and the bytes deleted,
  never parsed or executed. Unknown sources are OFF by default;
  there is no bundled catalog or plugin.
- Elyx archives: Zip-Slip and symlinks refuse the whole
  archive; only `src/*.py`, `refmap.yml`, `metainfo.yml`,
  `strings/*.yml` are kept; entry resolution is deterministic
  (refmap → `src/main.py` → lone file, else refused); entry +
  every module go through the same install-time analysis as a
  single-file plugin.
- DevServer binds `127.0.0.1` only, token auth on every request,
  only `POST /plugin` and `GET /status` exist, auto-stops 60 s
  after background and on leaving the hub; uploads are as
  untrusted as any file.
- `__requirements__` stays refused (see above).
- **Known limitations (do not over-claim in the hub):**
  analysis is source-text only — behaviour hidden in a DEX, an
  obfuscated string, or a downloaded payload cannot be seen; a
  plugin that passes review still runs with full app access;
  DEX loads need per-plugin consent but a consented DEX is
  unsandboxed; the catalog index itself is only as trustworthy
  as its host + the SHA-256 pins inside it.

### Gaps / stubs left (after M4 — on-device test list for Amin)

- On-device verdict (needs CI green + Amin's phone test): M0–M3
  items as before (hello_py, settings_demo, hooks_demo,
  MaterialSettingsList 8 toggles, DEX consent ON/OFF), PLUS M4:
  add a test catalog URL with unknown sources on → refresh →
  install → verify a tampered download is rejected; sideload a
  `.elyx` (refmap + `src/main.py` + a module + strings) →
  verify it loads and its module is importable; sideload a
  library-only `.plugin` (no BasePlugin subclass) → verify it
  loads as a library and another plugin can import it; start
  DevServer → `curl -X POST --data-binary @x.plugin -H
  "X-Dev-Token: <token>" http://127.0.0.1:42690/plugin` from
  his computer → verify install; confirm it stops on leaving
  the hub. Cold-start time with Python engine OFF (must be
  ~unchanged) vs ON; APK size delta (expected ~95–105 MB vs
  AminGram 42's 85.4 MB).
- `add_autoupdater_task` still a no-op stub (by design).
  Channel-history scan wiring not done (see above).
  `R.drawable.ic_foreground_solid` still missing upstream —
  dead in practice (see M3 gaps).
- ExpandableSwitchManager renders flat (no collapsible groups).
- `add_hook("tl_*")` / ConnectionsManager interception: still
  deferred (M2). GPL-3.0 vs clean-room decision still open —
  M4 stayed clean-room, no exteraless code copied.

### Fix 1 — overnight CI watch (2026-11-15 ~21:00 UTC, run 43757229145 on 85f3919: FAILED)

The M4 run got past configure (the abiFilters fix worked) and
compiled for 35m39s, then failed with 2 failures, both real code
errors that no local check had caught (local JDK checks are
parse-only — they do not resolve symbols — and the M1/M2/M3 runs
never reached compilation, so these bugs were latent since M1):

1. **Manifest merger** (`:TMessagesProj_App:processAfatReleaseMainManifest`):
   `uses-sdk:minSdkVersion 23 cannot be smaller than version 24
   declared in library [:TMessagesProj]`. M0 raised the library's
   minSdk 23→24 for Chaquopy but never raised the app modules'.
   Fix: new patch **0029** — minSdkVersion 23→24 in all four app
   modules (`TMessagesProj_App`, `_AppHockeyApp`, `_AppHuawei`,
   `_AppStandalone`), regenerated from a staged commit in the
   replay tree, never hand-edited. +4 upstream files: honest
   count now **38** touched (cap 40, headroom 2).
2. **Compile errors in the plugin UI** (`:TMessagesProj:compileReleaseJavaWithJavac`,
   3 errors):
   - `AminPyPluginActivity.java:481` (singular, M1 selector
     dialog): `builder.setSingleChoiceItems(...)` — Telegram's
     `AlertDialog.Builder` has no such method (only `setItems`).
     Fixed with `setItems`, the same tap-to-choose pattern
     `AminDnsTunnelActivity.showChooser` already uses; the
     now-unused current-selection tracking was removed.
   - `AminPyPluginsActivity.java:696,704` (plural, M4 hub):
     `package PyPluginAnalysis does not exist` — the file used
     the simple name without importing it (the fully-qualified
     calls next to it compiled fine). Fixed: added the import.
     An audit of both activity files found no other simple
     names used without an import (remaining hits were method
     names, string literals, and the classes' own names).

Verification of Fix 1: patch series 0001–0029 `git apply`
clean on a fresh pristine `21f7d10` tree; JDK parse OK (2
changed files); Java TestM4 73/73; CPython M4 8/8; secret scan
zero. No local Gradle build possible; CI is the only build
proof. Pushed as the fix commit on top of 85f3919; the new CI
run is watched the same way (max 2 fix attempts total).

### Fix 2 — overnight CI watch (2026-11-15 ~21:45 UTC, run 43761381685 on b08991d: FAILED)

The Fix 1 run compiled clean (the 3 Java errors were gone —
only 1 failure left) but failed with the SAME manifest merger
error: `uses-sdk:minSdkVersion 23 cannot be smaller than
version 24 declared in library [:TMessagesProj]`.

Root cause was NOT the patch: patch 0029 exists in the repo and
applies clean on pristine. The **workflow itself** was the bug:
`.github/workflows/build.yml` ("Apply AminGram overlay") had a
HARDCODED list of `git apply` lines ending at 0028 — patch 0029
was never applied in CI. The log proves it: the step's output
lists `git apply ../patches/0001…0028` and no 0029.

Fix: the hardcoded list is replaced by a loop over
`../patches/*.patch` in filename order (with a comment
explaining why). A loop cannot drift from the directory, so no
future patch can be silently dropped the same way. This adds 0
upstream files (build.yml is the repo's own file); honest count
stays 38 (cap 40, headroom 2).

Verification of Fix 2: YAML parses; the exact workflow step
was simulated on a fresh pristine `21f7d10` tree (same layout
as the runner: overlay copy, then the loop) — all 26 patches
apply in order including 0029, app minSdk is 24, library minSdk
is 24, abiFilters present. Secret scan zero. No local Gradle
build possible; CI is the only build proof. Pushed as the fix
commit on top of b08991d; this is fix attempt 2 of 2 — if this
run also fails, no further pushes; the diagnosis goes to the
parent instead.

## v2.5.6 — Fix 4: nameless-library install + ic_foreground_solid + Python log bridge (2026-11-16, uncommitted)

Two remaining errors from Amin's on-device test of v13.2.2-amingram.49
(2026-11-16 ~06:55 Tehran, HEAD 56f4785), both fixed in this round.
Zero new upstream files: one new overlay resource only, no new patch
needed. Honest count stays 38 (cap 40, headroom 2) — reconciled
this round: 36 distinct files across patches/*.patch + 2 CI inline
functional edits (`FileLoadOperation.java`, `FileUploadOperation.java`
in build.yml). `BuildVars.java` is also sed-edited at build time for
the API keys, but that is a secret substitution, not a functional
touch, and is not counted.

### Bug A — adding real ZwyLib still "Missing metadata"
Fix 3 threaded OpenableColumns.DISPLAY_NAME from the picker into the
library fallback, but the toast persisted on Amin's phone — so the
filename is evidently NOT reaching the fallback (DISPLAY_NAME null
from his picker/provider, or the file being a ZIP routed to the
strict Elyx path). The flow is now bulletproof regardless of why
the name is missing:
- **Fallback 1 (new):** if DISPLAY_NAME is null, try
  `uri.getLastPathSegment()` — for file:// Uris and many content
  Uris this IS the filename. Accepted only if it looks like one:
  `PyPluginMetadata.usableFileName()` requires a `.plugin`/`.py`
  suffix (case-insensitive); a bare last segment such as a numeric
  document id (`1234`) or `msf:1234` is rejected, so a document id
  can never become a library id.
- **Fallback 2 (new):** if there is still no usable name, the hub
  no longer refuses with the generic toast — it shows an input
  dialog asking the user for the library id (rules explained in
  the dialog: letters/digits/`_`/`-`, min 2 chars, starts with a
  letter; hint "zwylib"), validates with `deriveLibraryId`, and
  re-enters `confirmInstall(bytes, typed)` with the typed id, so
  it goes through the same derivation + install review.
- **Elyx path (new):** `confirmInstallElyx` and the controller's
  `installElyx` apply the library fallback too: a metadata-less
  entry installs as a library under the metainfo.yml id
  (`PyPluginMetadata.libraryFromMetainfo`, moved there from the
  controller so it is testable without Android; name/version from
  metainfo when usable, else id / "1.0.0"). The metainfo
  id/version match checks now apply only in the non-library
  branch, so a library's default version cannot trip the
  version-mismatch refusal. If neither metadata nor a metainfo id
  exists, the refusal says so.
- **Diagnostic refusals:** when the fallback cannot run, the
  bulletin says WHY (e.g. "the file picker did not provide a
  filename" / "metainfo.yml has no usable id") instead of
  repeating the generic "Missing metadata" toast — the next
  screenshot tells us the cause.
- **DevServer rule kept:** a client-supplied upload name still
  does NOT choose an install id (no filename forwarding there;
  verified unchanged).

### Bug B — enabling MaterialSettingsList: "Failed to load dex module."
That string is raised by MSL itself (`on_plugin_load`, plugin
line ~343) when its `DexLoader.inject()` returns False. Diagnosis
this round, from a REAL jadx 1.5.2 decompile of the embedded DEX
(`~/workspace/amingram/verify-scratch/fix4/msl-decompiled/` —
downloaded into the scratch dir because /tmp is a 512 MB tmpfs
that was 100% full; nothing uploaded):
- `PluginCore.init(Context)`: sets `appContext`, calls
  `paletteManager.bakePalettes()`, `settingsCellHooks.setupHooks()`,
  `listItemsManager.setupHooks()`, logs "DEX v1.1.1 loaded." Both
  `setupHooks()` methods are try/catch-wrapped (log only), so they
  cannot throw out of `init`; `bakePalettes()` is likewise wrapped.
  `AppUtils.log(String, Throwable)` EXISTS in the repo facade, so
  the log path is fine. `<clinit>` constructs PaletteManager,
  SettingsCellHookManager, ListItemsManager, SettingsActivityManager
  — all trivial constructors/field initializers. `SettingsState`
  is trivial booleans; `PaletteSet` a trivial HashMap holder.
  DEX's kotlin-stdlib (`Intrinsics`, `MapsKt`, `TuplesKt`,
  `CollectionsKt`, `RangesKt`, `Result`) and
  `androidx.core.graphics.ColorUtils` references resolve from the
  app's own dependencies.
- **The one unresolvable reference in the entire DEX:**
  `R$drawable.ic_foreground_solid`, read in exactly one place —
  `SettingsCellHookManager.onBindViewAfter` (line 205):
  `settingCell.getIconView().setImageResource(R.drawable.ic_foreground_solid)`
  for id == -1 rows with a palette key. It does not exist anywhere
  in upstream res/ (grep of the pristine tree is empty). All other
  17 types / 8 fields / 12 methods from the Fix 3 enumeration exist
  upstream and are kept — so this was the prime suspect for the
  init-time failure, and it is needed at bind time regardless.
- The direct-buffer theory is WEAK and was NOT acted on: Android's
  docs describe `dexBuffer` only as "buffer containing DEX file
  contents between buffer.position() and buffer.limit()", with no
  direct-buffer requirement, and real-world examples use
  `ByteBuffer.wrap(...)` — the same code works in exteraGram, so a
  heap buffer cannot be the difference. Chaquopy `except Exception`
  vs Java `Error` semantics (whether
  `ExceptionInInitializerError`/`VerifyError` would be caught by
  MSL's `except Exception` and return False vs propagating a
  different message) could not be settled from first principles or
  web search; that uncertainty is why the log bridge below matters.
- **Fix:** new overlay resource
  `TMessagesProj/src/main/res/drawable/ic_foreground_solid.xml` —
  a plain solid white oval `<shape>` written by us (the DEX tints
  it with the palette icon colour via `setColorFilter` SRC_IN in
  `applyColors`, so the base colour only matters before tinting).
  Clean-room shape, no exteraGram asset copied. The overlay res
  dir already holds `amingram_*.xml` files and the CI workflow
  copies `TMessagesProj` over the pristine tree, so the file is
  picked up with no patch. Fix 3's
  `-keep class org.telegram.messenger.R$* { *;}` already preserves
  the new field through R8.

### Diagnostics — Python logging now reaches the hub log (new)
MSL swallows the cause: its `logger.warning(..., exc_info=True)`
goes to Python logging, invisible in the hub. `amin_loader.py`
now installs a `logging.Handler` (`_LogBridgeHandler`) on the root
logger, lazily and idempotently, when a plugin or library loads.
It forwards every record (level + logger + message + traceback,
split into lines, capped at 30 lines per record) via
`PyPluginHost.log` under the current plugin id from
`amin_context` — so the hub's per-plugin log view shows the
plugin's traceback next time a load fails. Records outside a
plugin call ("engine") are skipped, not misattributed. The bridge
never raises, and `PyPluginHost.log` writes to logcat + prefs,
not to Python logging, so it cannot loop on itself. No Java
change was needed for this.

### Verification (all local; no Android SDK)
- JDK 17 parse: 4 changed Java files OK.
- Patch series replay 0001–0030 via `git apply` on a fresh
  pristine 21f7d10 tree: clean, no FAILED lines; no new patch.
- Java TestFix4
  (`~/workspace/amingram/verify-scratch/fix4/javatest/TestFix4.java`):
  **52/52 passed** — `usableFileName` (incl. numeric/`msf:`
  document ids rejected), `deriveLibraryId` (incl. 40-char stem
  refused, 32-char stem accepted), `isValidId`,
  `libraryFromFileName`, `libraryFromMetainfo` (incl. bad id/version
  fallbacks), `isMissingMetadataError`, real-MSL metadata regression,
  drawable shape/self-contained checks, Fix 3 keeps still present
  in the replayed proguard-rules.pro, no blanket `org.telegram.**`
  keep.
- CPython: new Fix 4 log-bridge suite **5/5 passed** (idempotent
  install, warning forwarded, exc_info traceback forwarded, engine
  records skipped, INFO forwarded); M3 suite **62/62**; M4 suite
  **8/8** — all still pass.
- `py_compile` of `amin_loader.py` + `zwylib/__init__.py` OK;
  drawable XML well-formed (minidom parse OK).
- `git diff --check` clean; secret scan zero.
- Files changed: `PyPluginMetadata.java` (usableFileName,
  isValidId, libraryFromMetainfo, libraryIdRefusal), `PyPluginsController.java`
  (Elyx library fallback, diagnostic refusals), `AminPyPluginsActivity.java`
  (last-path-segment fallback, typed-id dialog, Elyx library review),
  `amin_loader.py` (log bridge), new overlay
  `res/drawable/ic_foreground_solid.xml`.
- **Not verifiable locally:** whether the DEX now loads on a real
  phone (the `ic_foreground_solid` theory, the Chaquopy varargs
  path on `Method.invoke`, and the fragment-walk refresh are
  unproven until device); whether Amin's picker supplies a
  DISPLAY_NAME or a usable last path segment (the typed-id dialog
  covers the worst case either way).

### On-device test list (Amin, v13.2.2-amingram.50 when CI is green)
1. Add the real ZwyLib `.plugin` — should install as a library
   (via filename, last path segment, or the typed-id dialog).
2. Enable MaterialSettingsList — DEX should load; check the hub
   log view for the plugin's log lines if it does not.
3. All 8 toggles, restart persistence, disable → stock Settings
   returns; `hello_py`, `settings_demo`, `hooks_demo`.
4. M4 items: catalog, `.elyx`, library-only `.plugin`, DevServer
   curl install, cold-start time engine OFF vs ON, APK size delta.

## v2.5.5 — Fix 3: library install + R8 keeps (2026-11-16, uncommitted)

Two bugs from Amin's on-device test of v13.2.2-amingram.48
(2026-11-16 ~05:22 Tehran, HEAD 6e748d7), both fixed in this
round. Zero new upstream files: patch 0030 touches only
`TMessagesProj/proguard-rules.pro`, already touched by 0025 /
0028. Honest count stays 38 (cap 40, headroom 2) — reconciled
this round: 36 distinct files across patches/*.patch + 2 CI
inline functional edits (`FileLoadOperation.java`,
`FileUploadOperation.java` in build.yml). `BuildVars.java` is
also sed-edited at build time for the API keys, but that is a
secret substitution, not a functional touch, and is not counted
— same convention as the 34-at-M3 count.

### Bug A — adding real ZwyLib .plugin → "Missing metadata"

Evidence: Amin's screenshot — installing the real ZwyLib
`.plugin` from file refused with "Missing metadata: a .plugin
file must define __id__ and __name__ as literal values."

Root cause (re-verified against the code): the real ZwyLib is
a LIBRARY .plugin — no BasePlugin subclass, no __id__/__name__
literals. `installFromSource` refused on ANY parse error,
before M4's load-time library fallback (`loadPlugin`:
`load_library` on "no BasePlugin subclass") could ever run;
`confirmInstall` in the hub parsed first and refused before
the install dialog too. A metadata-less library could never
install. The bundled clean-room zwylib 2.0.6 already satisfies
MSL's version gate and wins on sys.path, so installing real
ZwyLib is unnecessary — but the install must accept library
files instead of refusing.

Fix (shared helper, no duplication):
- `PyPluginMetadata.libraryFromFileName(fileName)` /
  `deriveLibraryId(fileName)` / `fileNameStem(fileName)` /
  `isMissingMetadataError(error)` / `libraryIdRefusal(fileName)`.
  Id derivation: stem minus `.plugin`/`.py`, invalid chars →
  `_`, `lib_` prefix when it does not start with a letter;
  must match `ID_PATTERN` (2–32 chars) — no truncation, so a
  too-long stem is refused rather than silently colliding with
  another id. Derived library: name = stem, version "1.0.0",
  category "libraries", `library=true`, no requirements.
- `PyPluginsController.installFromBytes/installFromSource`
  gained 4-arg forms `(…, fileName)`; the 3-arg forms delegate
  with null. On a "Missing metadata" parse error ONLY, with a
  fileName, the file installs as a library through the SAME
  gates as a normal plugin (requirements gate still refuses
  PyPI, analysis + DEX-consent flag still run, fingerprint,
  `<id>.plugin`, `registerInstalled`). With fileName null
  (DevServer upload) it is refused with a clear message
  ("install it from a file instead, or add __id__/__name__").
  Other parse errors are refused unchanged.
- `AminPyPluginsActivity`: `onActivityResultFragment` queries
  `OpenableColumns.DISPLAY_NAME` via ContentResolver and
  threads it to `confirmInstall(bytes, fileName)`; the dialog
  applies the same fallback before showing (shared helper) and
  says it is a library ("no settings UI, no lifecycle; other
  plugins can import it", title "Install Python library?"),
  and its Install button passes the fileName so the controller
  cannot refuse again. The catalog path passes `entry.name` as
  the fileName. The DevServer handler receives a fileName from
  `serveOne` but deliberately does NOT forward it — a
  client-supplied upload name must not choose an install id.
- Elyx left STRICT (deliberate): a metadata-less Elyx entry is
  still refused; the metainfo-id treatment was judged not
  worth the second dialog path for AminGram's own format.
- `zwylib/__init__.py` docstring: the stale "do not install
  one" paragraph is gone — installing real ZwyLib is now
  accepted as a harmless library, but still unnecessary: the
  bundled 2.0.6 wins on sys.path.

### Bug B — enabling MaterialSettingsList → "cannot import name 'R'"

Evidence: Amin's screenshot — `PyException: ImportError:
cannot import name 'R' from '<java import hook>'`.

Root cause: the release build runs R8/minify. `from
org.telegram.messenger import R` resolves the class BY NAME at
runtime via Chaquopy's Java import hook; R8 renamed
`org.telegram.messenger.R`. Patches 0025/0028 kept a few class
names and 3 members, but not R, not ChatActivity, and not the
members MSL/the DEX resolve by name. This was a whole class of
failure, so this round fixes the entire string-resolution
surface at once.

Fix — new patch `0030-pyplugins-r8-keeps.patch` (generated by
`git diff` from a staged scratch tree, never hand-written),
touching ONLY `TMessagesProj/proguard-rules.pro`. DEX
reference enumeration (pure-Python DEX parser over the real
plugin's embedded DEX, SHA-256
`bbeb7267b8cf5de2ff0b4f0beee305d2c0e2bf7f7ec62961fa51c669cfe01b1e`,
matching M3): 17 org.telegram/de.robv types, 8 fields, 12
methods, plus class-name strings (`bindView`, `fillItems`,
`updateColors`, `org.telegram.ui.LaunchActivity`,
`org.telegram.ui.ViewPagerActivity`). Every keep below was
verified against pristine 21f7d10 source (line numbers are
pristine):
- `R` + `R$*`: `-keep class org.telegram.messenger.R { *; }`
  and `-keep class org.telegram.messenger.R$* { *; }` — covers
  `Res.string.*` (all 7 used names exist in res/values:
  TelegramPremium, TelegramStars, GramEarnings, MyTON,
  WalletAttachMoney, TelegramBusiness, SendAGift) and
  `Res.raw.error` (res/raw/error.json exists).
- `ChatActivity.of(long)` (1367) and `of(long, int)` (1377):
  class name + `of(...)`.
- `LocaleController`: `getInstance()` (561),
  `getCurrentLocaleInfo()` (1455, returns `LocaleInfo`),
  `getString` overloads (1488/1494/1500, wildcard
  `*** getString(...);`).
- `LocaleController$LocaleInfo.pluralLangCode` (448, `public
  String pluralLangCode;`).
- `ApplicationLoader.applicationContext` (57, `public static
  volatile Context applicationContext;`).
- `SettingsActivity.fillItems(ArrayList, UniversalAdapter)`
  (717, private void) — the DEX hook target.
- `SettingCell.updateColors()` (1336, inside SettingCell
  1279–1383) + class name; `SettingCell$Factory.bindView(View,
  UItem, boolean, UniversalAdapter, UniversalRecyclerView)`
  (1443, inside Factory 1434–1482) + class name;
  `SettingCell$Background` class name (its `setColor` was
  already kept by M3 — the M3 keep alone was insufficient,
  because `-keepclassmembers` keeps the member name but R8
  could still rename the class the DEX resolves by name).
- `Theme.isCurrentThemeDark()` (6530, static; `isCurrentThemeMonet`
  was already kept by M3) + class name.
- `UItem.asShadow(CharSequence)` (351), `instanceOf(Class)`
  (730), fields `id` (33), `longValue` (56), `object` (63);
  `viewType` is declared in the superclass
  `AdapterWithDiffUtils$Item` (ListView/AdapterWithDiffUtils.java:24)
  and is kept there.
- `UniversalAdapter.update(boolean)` (297),
  `UniversalRecyclerView.adapter` (38, `public final
  UniversalAdapter adapter;`), `INavigationLayout.getFragmentStack()`
  (35), `AndroidUtilities.runOnUIThread(Runnable)` (3040),
  `BaseFragment` name, `LaunchActivity` / `ViewPagerActivity`
  names (class-name strings in the DEX, resolved via
  Class.forName-style lookup).
- Already covered, NOT re-added: `TLRPC$TL_attachMenuBot`
  (upstream already has `-keep class
  org.telegram.tgnet.** { *; }`); `de.robv.android.xposed.**`
  (full keep from M3); `PluginsController` (M0).
- No blanket `-keep class org.telegram.**` — targeted only.

Open gaps (do NOT present as fixed):
- `R$drawable.ic_foreground_solid`: the DEX references this
  field, but it does NOT exist anywhere in upstream res/
  (verified across TMessagesProj + TMessagesProj_App res).
  No keep can create a missing field; if the DEX code path
  that reads it ever runs, it fails. Dead in practice so far
  (M3), but unproven until device.
- No exteraGram-only API was found in the DEX enumeration —
  every referenced org.telegram member exists upstream.
- R8 behaviour itself is CI/on-device territory: no local
  Gradle build exists on the VM, so the keeps are verified
  present + signatures verified, not proven to satisfy R8.

### Verification (all local; no Android SDK)

- JDK 17 parse (Temurin 17.0.21+9, ParseCheck): OK, 4 files
  (PyPluginsController, PyPluginMetadata, PyPlugin,
  AminPyPluginsActivity).
- Patch series replay: 0001–0030 `git apply` clean on a fresh
  pristine 21f7d10 tree (extracted from the M1 codeload
  tarball; pristine verified by 0 AminGram refs). New keeps
  present in the replayed proguard-rules.pro; minSdk 24 and
  abiFilters still present.
- Java tests `verify-scratch/fix3/javatest/TestFix3.java`:
  63/63 — library id derivation (valid stem, spaces/dots,
  leading digit → lib_ prefix, .py ext, path prefix, bare
  ".plugin" → null, null → null, 1-char → null, 40-char →
  null with no truncation, dash kept, 32-char ok),
  libraryFromFileName fields (id/name/version/categories/
  library flag/no requirements), Missing-metadata trigger
  condition (positive + negative + null), MSL metadata
  regression on the real plugin file, and all 36 required keep
  lines present in the replayed proguard file + no blanket
  org.telegram keep.
- CPython regression: M3 suite 62/62, M4 suite 8/8.
- Secret scan over the full Fix 3 diff (tracked + patch
  0030): zero.
- Scratch: `~/workspace/amingram/verify-scratch/fix3/` —
  `extract_dex.py` + `msl.dex` + `dex_refs.py` (+ output
  `dex_refs.txt`, `dex_strings.txt`), patch-generation tree
  (`Telegram-21f7d10`, pristine + 0001–0029 applied),
  replay tree (`replay/Telegram-21f7d10`, 0001–0030 applied),
  `javatest/TestFix3.java` (+ M4 stubs).

### On-device test list (Amin)

- Install the real ZwyLib `.plugin` from file → installs as
  a LIBRARY (dialog says so); list shows it; MSL still loads
  (bundled 2.0.6 wins — `import zwylib` resolves to the
  bundle, behaviour unchanged).
- Enable MaterialSettingsList → loads with NO ImportError;
  its 8 toggles appear; toggle each, restart the app, confirm
  reorder/debloat persist; disable the plugin and confirm
  stock Settings returns.
- DEX-hook behaviour (reorder groups, debloat, palette) is
  still unverified until device — green CI only proves the
  keeps compile in.

## v3.0.3 — Fix 3: 37 Java compile errors — missing upstream APIs + unported helpers (2026-11-16, uncommitted)

CI run 43819280503 (head 2b7c95f, Fix 2) resolved all deps, then
FAILED at `:TMessagesProj:compileReleaseJavaWithJavac` with **37
Java compile errors in 15 files** (full log
`verify-scratch/v3watch-job3.log`; 111 `error:` lines = 37 errors,
three sites doubled on one line each). Two categories, both traced
to the same root cause: **the v3.0.0 "zero version skew" claim was
wrong** — exteraless's 13.2.2 commit differs from pristine 21f7d10
in the spots below, and the engine also calls helpers from packages
the port never carried over. Corrected in the v3.0.0 section above.

### Error inventory (all 37)

(a) Missing packages/helpers the engine calls (11 errors):
- `tw.nekomimi.nekogram.helpers` does not exist —
  LocaleUtils:29 (TypefaceHelper), ChatUtils:11 (MessageHelper).
- `tw.nekomimi.nekogram.utils` does not exist —
  PluginsActivity:215, :287; PluginActivityLogActivity:280 (ShareUtil).
- `app.exteraless.chats` does not exist — LocaleUtils:274
  (LinkedCustomEmoji).
- `app.exteraless.appearance` does not exist — PluginCell:308
  (AppearanceConfig.sectionRadius).
- `R.string.OpenExtera` — LocaleUtils:199.

(b) Upstream APIs absent in pristine 21f7d10 (26 errors):
- `ActionBar.UnreadImageView` — PyMethodHook:24, :76, :80 (x2),
  :81, :103, :105 (7).
- `UItem` — PluginSettingsActivity:604 (`asCheck(int,String,
  String,boolean)` overload missing), :666 (`copy()`); PluginUiItem
  :55 (x2, `.multiline`); PluginsInfoActivity:113, :124, :131
  (`.setValue(String)`), :134, :139, :142 (`.setIcon(int)`) (11).
- `TextCheckCell.setIcon(int)` — PluginUiItem:89 (1).
- `BuildConfig.APPLICATION_ID` — AppUtils:237 (1);
  `BUILD_COMMIT_ID` — PluginLog:109 (x2) (2); `DEBUG_TOOLS` —
  PluginToggleTrace:62, :162 (2).
- `GapView.setDividerVisible(boolean)` — PluginsMenuWrapper:172 (1).
- `ActionBarMenuItem.lazilyReuseItem(Item)` — MenuInjector:362 (1).
- `Process.myPid()` — ApplicationLoader:770 (1; pristine has NO
  `android.os.Process` import, so `Process` resolved to
  `java.lang.Process`).

### Resolutions

New patches 0051–0055 (patch files 44 → 49; no new upstream
files beyond the 8 already counted — all eight were already in the
46-file count):
- **0051-fix3-uitem.patch** — UItem additions, ported from the
  exteraless↔pristine UItem diff (GPL-3.0): `implements Cloneable`
  + `copy()`; fields `checkBoxIconResId`, `iconColor`,
  `prioritizeTitleOverValue`, `multiline`; `asButtonWithSubtext`,
  `asCheck(id,text,int)`, `asCheck(id,text,CharSequence,boolean)`,
  `asShadow()`; builders `setIcon`, `setValue`, `setMultiline`,
  `setTransparent`, `setCheckBoxIcon`, `showDivider`,
  `setColorfulIcon`, `prioritizeTitleOverValue`. NOT ported:
  `setSearchable`/`setLinkAlias` (no callers in the tree; they need
  the unported `SettingsRegistry`) and patch 0034's additions
  (`settingItem`, `switchClickCallback`, `exteraExpandableSwitch`,
  `asExteraExpandableSwitch`) which already exist.
- **0052-fix3-upstream-apis.patch** — ActionBar: the
  `UnreadImageView` inner class ported verbatim from exteraless
  ActionBar:2839–2900 + `private StaticLayout countLayout;` +
  `backButtonImageView` retyped `ImageView` → `UnreadImageView`
  (pristine line 395) + the two imports pristine lacks
  (`android.text.StaticLayout`,
  `androidx.appcompat.widget.AppCompatImageView`).
  `unreadBadgeSetCount` NOT ported (it gates on NekoConfig) — so
  `setUnread` is never called by the app and the badge never draws
  in normal use; the class exists so PyMethodHook's plugin-badge
  suppression has a type to hook. GapView: `dividerVisible` field
  (default true) + `setDividerVisible` + onDraw condition.
  ActionBarMenuItem: `lazilyReuseItem` verbatim from exteraless
  :3058–3070 (`putLazyItem`/`lazyList` already exist in pristine).
  AndroidUtilities: `TYPEFACE_ROBOTO_REGULAR = "fonts/rregular.ttf"`.
- **0053-fix3-textcheckcell.patch** — `setIcon` ported verbatim
  from exteraless TextCheckCell:608–650 (null-guards `imageView`,
  `resId == 0` hides it and restores padding) + `private int
  defaultPaddingDp;` + ctor init. The resId==0 path is exactly how
  PluginUiItem calls it (`cell.setIcon(0)`).
- **0054-fix3-buildconfig.patch** — `TMessagesProj/build.gradle`
  `defaultConfig`: `APPLICATION_ID = "org.telegram.messenger"`
  (APP_PACKAGE), `BUILD_COMMIT_ID` from CI's `GITHUB_SHA` (first 7
  chars; empty on local builds — PluginLog tolerates empty),
  `DEBUG_TOOLS = false`. exteraless builds TMessagesProj as an
  APPLICATION module, where AGP generates APPLICATION_ID; ours is a
  library, so it is declared here. `BUILD_VERSION_STRING` already
  existed in buildTypes.
- **0055-fix3-process-import.patch** — ApplicationLoader: add
  `import android.os.Process;`.

New overlay files (all GPL-3.0 sources, minimal ports — only what
the engine calls):
- `tw/nekomimi/nekogram/helpers/MessageHelper.java` — minimal:
  `getPathToMessage` x2 + `getMessagePlainText`, verbatim from the
  NekoGram-vendored helpers in exteraless, EXCEPT the
  NaConfig/AyuMessageUtils deleted-message-vault branch in
  `getPathToMessage` is omitted (no vault in AminGram; returns null
  when the file is not found). `FileLoader.getPathToMessage` /
  `getPathToAttach` exist in pristine.
- `tw/nekomimi/nekogram/helpers/TypefaceHelper.java` — minimal:
  `getTitleText(int)` + `createTypeface(int,boolean)` chain
  (asset lookup, Pixel Google Sans detection). NekoConfig flags
  stubbed to false; NaConfig custom title dropped; title defaults
  to the app name. Needs `AndroidUtilities.TYPEFACE_ROBOTO_REGULAR`
  (patch 0052).
- `tw/nekomimi/nekogram/utils/ShareUtil.java` — Java translation
  of the vendored `ShareUtil.kt`: `shareFile(ctx, file)` +
  `(ctx, file, caption)` via `FileProvider` with
  `BuildConfig.APPLICATION_ID + ".provider"`, ACTION_SEND targeted
  at `LaunchActivity`.
- `app/exteraless/chats/LinkedCustomEmoji.java` — full 298-line
  port; only `canSend` adapted: the original reads
  `NekoConfig.localPremium` (not ported); this reads AminGram's own
  `LocalPremiumModule.premiumFlag(ApplicationLoader.applicationContext)`
  instead — same meaning (treat the user as premium locally).
- `app/exteraless/appearance/AppearanceConfig.java` — minimal Java
  STUB (the real one is a Kotlin object backed by NekoConfig /
  NaConfig, not portable): `sectionRadius()` returns 20,
  `titleText()` returns 0, `TITLE_TEXT_CUSTOM` = 4 (defaults from
  the .kt file). PluginCell only calls `sectionRadius()`.
- `res/values/strings_oe_plugins.xml` — added `<string
  name="OpenExtera" translatable="false">exteraless</string>`.

### Verification (local; no Android SDK — CI is the real typecheck)
- Patch series 0001–0055 `git apply` clean on a fresh pristine
  21f7d10 tree (+ overlay, CI model, incl. the workflow's
  FileLoadOperation/FileUploadOperation hook): **49/49 patch files
  OK**. (Process note: the scratch port-tree first generated 0051
  against pristine instead of the 0001–0050 base — 0051 failed on
  the fresh replay. Corrected by committing the 0001–0050 base in
  the port-tree, re-running the port script, and regenerating all
  five patches; the fresh replay then went 49/49. The first
  `replay/` tree was also found already half-applied from an older
  run and was deleted and re-extracted.)
- JDK 17 ParseCheck over the FULL overlaid tree: **2495/2495 files
  parse OK** (vs 2490 for v3.0.0 — the +5 are the new overlay
  files). The 8 fix3-touched upstream files and the 6 new overlay
  files are byte-identical between the patch-source tree and the
  fresh replay tree.
- CPython engine-port suite 20/20 still passing.
- `git diff --check` clean; secret scan zero.
- Script that generates patches 0051–0055 from the applied tree:
  `verify-scratch/fix3/fix3_port.py` (anchor-based `rep()`, each
  anchor asserted to occur exactly once; run from the scratch
  port-tree, NOT from the repo — the repo holds only the overlay +
  patches, the upstream files live in the patches).

### CI watch follow-up (Fix 3 run 43827424276, head 266b6d3)
Run 43827424276 FAILED at `:TMessagesProj:compileReleaseJavaWithJavac`
with exactly **2 errors**, both in the Fix 3 minimal port
`tw.nekomimi.nekogram.helpers.TypefaceHelper` (lines 144–145):
switch expression / switch rules are Java 14+ and the project
compiles with `-source 8`. The 37 Fix 3 errors are gone; this was
the only remaining one. Fixed in the overlay file (no new patch):
the switch expression became a plain if/else chain, and the three
`var` local declarations (lines 74, 121, 144 — warnings only in CI)
became explicit types (`String`, `SpannableStringBuilder`). Local
check: JDK 17 parse OK, `javac -source 8` shows zero
"not supported in -source 8" errors (remaining errors are only
missing Android symbols, unresolvable without the SDK); CPython
suite still 20/20. Commit on top of 266b6d3; CI watch re-armed.

### CI watch follow-up 2 (run 43832972176, head 8aaaa9e)
That run ALSO FAILED at `:TMessagesProj:compileReleaseJavaWithJavac`,
with exactly **3 errors** (the switch errors are gone). Two root
causes, both "zero skew" misses in the port:
(a) `ActionBar.java:2635` — patch 0052's UnreadImageView port uses
`Layout.Alignment.ALIGN_CENTER` but 0052 only imported
`android.text.StaticLayout`, never `android.text.Layout`
("package Layout does not exist").
(b) `LinkedCustomEmoji.java:79, :300` — the engine reads/writes
`TL_messageEntityCustomEmoji.local`, a runtime-only flag
(`public boolean local;`, not serialized) that exteraless's TLRPC
has but pristine 21f7d10 lacks ("cannot find symbol").
Fixed by new patch **0056** (two one-line hunks, generated by
diffing the fully-patched tree): `import android.text.Layout;`
in ActionBar, `public boolean local;` after `document; //custom`
in `TL_messageEntityCustomEmoji` (mirrors exteraless's field
position exactly). Verified: patch series 0001–0056 (50 patches)
applies clean on fresh pristine, both hunks present, JDK 17 parse
OK on both edited files, CPython suite still 20/20, secret scan
zero. Commit on top of 8aaaa9e (fix attempt 2 of 2); CI watch
re-armed. If this run also fails, the watch stops pushing and
reports the diagnosis.

### Still unverifiable until CI / device
- Whether all 37 errors are really gone — only
  `:TMessagesProj:compileReleaseJavaWithJavac` in CI can confirm;
  ParseCheck is syntax-only and cannot resolve symbols.
- The `DEBUG_TOOLS = false` value: PluginToggleTrace only reads it;
  if exteraless ever expected it true in debug, the toggle-trace UI
  stays off — acceptable, it is a debug aid.
- On-device test list from v3.0.0 still stands (zwylib + MSL, 8
  toggles, restart persistence, disable restores stock Settings).

## v3.0.2 — Fix 2: Aliucord repo must be project-level, settings repo ignored (2026-11-16, uncommitted)

CI run 43816668348 (head 661e3ef, Fix 1) FAILED the same way:
`Could not find com.aliucord:Aliuhook:1.1.4`, searched ONLY Maven
Central + Google — even though patch 0049 WAS applied in CI (log
line 402) and adds the repo to settings.gradle. Root cause, in
Gradle's own words from the failure's "* Try:" hint: "The project
declares repositories, effectively ignoring the repositories you
have declared in the settings." `TMessagesProj/build.gradle:30`
and `TMessagesProj_App/build.gradle:4` each declare their own
`repositories { mavenCentral(); google() }`, and those are the
blocks Gradle consults for this resolution — the settings-level
repo never gets searched.

Fix: new patch 0050-engine-aliucord-repo-project.patch adds
`maven { url "https://maven.aliucord.com/releases" }` to BOTH
project-level repositories blocks (the dependency is declared in
:TMessagesProj, the failing configuration is
:TMessagesProj_App:afatReleaseRuntimeClasspath). Patch 0049 stays
— harmless belt-and-braces. No new upstream files (both were
already counted; count stays 46, patch files 43 → 44). Verified:
patch series 0001–0050 `git apply` clean on a fresh pristine
21f7d10 tree (+ overlay); all three repo declarations present in
the replayed tree; CPython engine-port suite 20/20; secret scan
zero. This is fix attempt 2 of 2 — if this run fails too, no
further pushes; report the log error instead.

## v3.0.1 — Fix 1: AliuHook Maven repo missing (2026-11-16, uncommitted)

CI run 43814128194 (head d03198f, v3.0.0) FAILED at step 15 "Build
release APK", task `:TMessagesProj_App:checkAfatReleaseDuplicateClasses`:
`ModuleVersionNotFoundException: Could not find com.aliucord:Aliuhook:1.1.4`
(searched Maven Central + Google only). Root cause: patch 0047 added
the `com.aliucord:Aliuhook:1.1.4` dependency, but the port never carried
over exteraless's `settings.gradle` repository line — AliuHook lives
ONLY on `https://maven.aliucord.com/releases`, not on Maven Central.

Fix: new patch 0049-engine-aliucord-repo.patch adds that repo to the
pristine `settings.gradle` `dependencyResolutionManagement` block (same
line exteraless declares). `settings.gradle` is a new upstream file
(upstream count 45 → 46; patch files 42 → 43). Verified: the artifact
`Aliuhook-1.1.4.pom` returns HTTP 200 at the Aliucord repo, and the
other three new deps (MVEL 2.5.2.Final, DexMaker 2.28.6,
HiddenApiBypass 6.1) all return 200 on Maven Central — no other repo
is missing. Patch series 0001–0049 `git apply` clean on a fresh
pristine 21f7d10 tree (+ overlay), CPython engine-port suite 20/20,
secret scan zero. The workflow already loops over `patches/*.patch`,
so 0049 is picked up with no workflow change.

## v3.0.0 — Real plugin engine port (exteraless), clean-room engine removed (2026-11-16, uncommitted)

Amin tested v50 (Fix 4, HEAD dceb8ed) on his phone and it did NOT work
("کاد نکرد"). His order (2026-11-16 ~09:13 Tehran): use the ORIGINAL
plugin engine from exteraGram or AyuGram instead of the clean-room
reimplementation — "اشکال نداره هرکاری لازمه انحام بده" (no problem, do
whatever is needed). That order confirmed BOTH consequences: the
GPL-3.0 relicense AND the lifted patch-file cap. The parent handles the
LICENSE relicense + commit + push; this port is the technical work only.

### Engine chosen: exteraless/exteraless (GPL-3.0), NOT AyuGram
- `exteraless/exteraless`, default branch main, pushed 2026-11-13.
  **APP_VERSION_CODE=7303, APP_VERSION_NAME=13.2.2 — exactly our base,
  zero version skew.** *(CORRECTED 2026-11-16, Fix 3: that claim was
  WRONG. exteraless's 13.2.2 commit differs from pristine 21f7d10 in
  at least 11 spots the engine calls — ActionBar.UnreadImageView,
  GapView.setDividerVisible, ActionBarMenuItem.lazilyReuseItem,
  UItem.copy/asCheck-overloads/asShadow()/setIcon/setValue/multiline,
  TextCheckCell.setIcon, AndroidUtilities.TYPEFACE_ROBOTO_REGULAR,
  BuildConfig.APPLICATION_ID/BUILD_COMMIT_ID/DEBUG_TOOLS, plus the
  NekoGram helpers it vendors. Same version number, different commit.
  See ## v3.0.3 for the full inventory and fixes.)* Its own
  docstring: a simplified reimplementation
  of exteraGram's PluginsController (2379 lines), WITHOUT chat install
  dialogs, WITHOUT pip, WITHOUT SDK auto-updates — all three already
  match AminGram policy.
- Rejected: `AyuGram/AyuGram4A` ("Just an exteraGram based client",
  GPL-2.0, default branch `rewrite`, APP_VERSION_NAME=9.6.6,
  APP_VERSION_CODE=3362, last pushed 2023-09-11) — ~3.5 years stale,
  its hooks would not apply to 13.2.2.
- Note: the first-guess paths `exteraSquad/exteraless` and
  `AyuGram/AyuGram` are both 404; the real owner is `exteraless`.
- exteraless is a FULL client fork (its 13.2.2 commit differs slightly
  from our pristine 21f7d10: 745 files differ, mostly trivial typo /
  newline fixes; ChatActivity +6508 lines of other features). Only the
  plugin engine was ported — every patch hunk below is plugin-only;
  the fork's other features (backup UI, LinkCleaner, appearance,
  feed, ghost mode, AI review, drawer menu, proxy ping, etc.) were
  deliberately NOT ported.

### Licence (parent confirms the relicense; do NOT edit LICENSE here)
- Distributed AminGram must go out under **GPL-3.0** (exteraless engine
  is GPL-3.0; the Telegram base is GPL-2.0). Sources used, with
  licences: exteraless/exteraless (GPL-3.0, used); AyuGram/AyuGram4A
  (GPL-2.0, consulted only, not used). No code was copied from any
  source whose licence could not be identified.

### What was ported (verbatim exteraless code, adapted only where noted)
- Engine Java, 86 files under `TMessagesProj/src/main/java/`:
  `app/exteraless/plugins/**` (~55 files: PluginsController,
  PluginSinkGate, ClassProxyFactory, PluginSettingsActivity,
  PluginsWatchdog, PluginsActivity, PythonPluginsEngine,
  PluginInstallBottomSheet, MenuInjector, IntentsDispatcher,
  XposedHooks, PythonBridge, PluginRuntime, PluginInstallHelper,
  PluginLog, PluginPermissions, PluginGrantStore, PluginTrustLevel,
  PluginsBackup, PluginsConstants, SignedGrantPreferences,
  PluginViewProxies, PluginMediaServices, PluginServices,
  PluginDenialNotice, HookResult, HookTargetCache, JsonUtils,
  MenuItemRecord, ClassAliases, files/FilesControllerJava, intents/,
  menus/, models/, ui/, utils/, xposed/) +
  `com/exteragram/messenger/plugins/**` (abstract facades: Plugin,
  PluginsConstants, PluginsController, PythonPluginsEngine, ui/,
  xposed/ — plugins resolve these FQ names, so they stay) +
  support: `com/exteragram/messenger/preferences/BasePreferencesActivity`,
  `com/exteragram/messenger/utils/AppUtils`,
  `app/exteraless/utils/AppUtils`, `app/exteraless/utils/text/MarkdownUtils`,
  `com/exteragram/messenger/utils/PluginToggleTrace`,
  `org/telegram/ui/Wallet/WalletPluginFreeze.java` (freeze flag while
  Wallet screens open; XposedHooks checks it).
  Two files OVERWROTE M0 facade stubs of the same FQ name
  (PluginsController, AppUtils) — the stubs are gone, the real ones
  are in.
- Python SDK, 39 files under `TMessagesProj/src/main/python/`:
  the whole `extera_utils/` (metadata_parser, plugin_loader,
  audit_gate, capability_scan), `elyx_runtime/`, `elyxcore/`,
  `base_plugin.py` (38 KB), `client_utils.py`, `hook_utils.py`,
  `file_utils.py`, `intents.py`, `log_utils.py`, `markdown_utils.py`,
  `pip_controller.py` (kept; the engine never calls pip),
  `plugin_settings.py`, `dev_server.py`, `android_utils.py`, `ui/`.
  The Chaquopy fork `exteraless/chaquopy` was NOT used: it is an
  upstream Chaquopy mirror with no exteraGram-specific patches, so
  stock Chaquopy 17.2.0 (patch 0025, CI-proven) stays. The fork is
  the fallback if a Chaquopy-level failure appears on device.
- Resources: `res/values/strings_oe_plugins.xml` (213 lines, ~190
  strings, incl. 4 unused AI strings), `msg_plugins.png` in
  drawable-hdpi/mdpi/xhdpi/xxhdpi, `drawable/baseline_code_24.xml`,
  `drawable/ic_ab_search_solar.xml`. Our `ic_foreground_solid.xml`
  (Fix 4) is kept.
- New Maven deps (patch 0047, the engine's own versions):
  `com.aliucord:Aliuhook:1.1.4` (LSPlant Xposed bridge — hooking is
  AliuHook; `de.robv.android.xposed.XposedBridge` comes from it, no
  native code of ours needed), `org.mvel:mvel2:2.5.2.Final`,
  `com.linkedin.dexmaker:dexmaker:2.28.6`,
  `org.lsposed.hiddenapibypass:hiddenapibypass:6.1`. Gson stays at
  pristine 2.11.0 (the engine's Gson use is API-compatible).
- Hooking model: AliuHook (LSPlant) + XposedHooks (lazy init,
  hidden-API restriction removal, crash-strike gate — 2 strikes
  disable hooks, denied targets Wallet/TON/keystore, per-plugin
  unhook registry). MSL's DEX hooks SettingsActivity bindView /
  updateColors / fillItems through this, not through our old
  PySettingsHookRegistry call sites.

### What was removed (one plugin system only)
- The whole clean-room engine: `org/telegram/messenger/amin/pyplugins/`
  (18 files), the `de/robv/android/xposed/` shim (2 files — it
  conflicted with AliuHook's real XposedBridge), `AminPyPluginsActivity`,
  `AminPyPluginActivity`, `AminCatalogActivity` (M4 catalog dropped —
  the real engine has no catalog/repo loader), all our Python files
  incl. the clean-room `zwylib/` package (the real ZwyLib installs as
  a library; the bundle is gone), `__pycache__` dirs.
- Patches deleted: 0026 (clean-room bootstrap init), 0027 (clean-room
  LaunchActivity hooks), 0028 old (PySettingsHookRegistry call sites).
  0028 was rewritten as `0028-plugin-settings-members.patch`: it keeps
  ONLY the three member additions the MSL DEX resolves by name
  (`Theme.isCurrentThemeMonet()` → false, `SettingCell.getIconView()`,
  `Background.setColor(int,int,boolean)`) + the proguard keeps for
  them. Stock 13.2.2 has none of the three; exteraless's Theme HAS
  isCurrentThemeMonet and its SettingCell HAS getIconView.
- `AminHooks.java`: all pyplugins branches stripped (onRawUpdates +
  onNewMessages fan-out lines, onOutgoingMessage M2 block, M0
  on_command fan-out, whole AppEvent section + 4 onPyLaunch* methods,
  attachMenuPlugins Python block, `py:attach:` / `py:ctx:` branches,
  `buildPyMenuContext`, dead `pyChatType`). JS engine paths untouched.
- `HubCommon.java`: catalog row + `case "plugin_catalog"` removed;
  Python section rewired to the real engine
  (`PluginsController.getInstance().isEngineEnabled()`, null-safe
  before init) and `case "py_plugins"` now presents the real
  `app.exteraless.plugins.ui.PluginsActivity`.
- Fixture `.plugin` files under `assets/amingram_pyplugins` were left
  in place (inert). `hooks_demo.plugin` still references the old
  `amin.pyplugins.PyPluginHost` class name in a string — dead text,
  it will not run under the real engine. The on-device list below
  says which fixtures to try.

### Adaptations (engine code changed for our tree)
- `PluginInstallBottomSheet.java`: AI review stripped (AiController /
  AiSettingsActivity / PlayerColors / PluginAiReview imports, the
  review chip block, `openAiSettings()`); `PlayerColors` replaced
  with Theme colors throughout (no material dep in stock Telegram's
  build, so PlayerColors could not be ported). Verified: zero
  `colors.` references remain.
- `PluginsWatchdog.java`: `AppRestartHelper.triggerRebirth` (neko)
  replaced with a local restart (Intent to LaunchActivity with
  NEW_TASK|CLEAR_TASK + `Process.killProcess(myPid())`).
- `MenuInjector.java`: drawer paths dropped (no DrawerMenuItemView /
  MainMenuHelper in stock Telegram); MAIN_MENU rewritten to stock
  ItemOptions — a "Plugins" row opening a swipe-back submenu,
  long-press opens the hub, empty case opens the hub directly.
- `VibratorUtils.java` (com.exteragram.messenger.utils.system) was
  DELETED, with its identity row removed from ClassAliases: it calls
  `ExteraConfig.inAppVibration()`, and ExteraConfig is a 2257-line
  fork config class whose whole settings tree (OpenExteraConfig,
  AppearanceConfig, ChatsConfig, backup.PreferencesUtils, …) was not
  ported. Nothing in the engine calls VibratorUtils — only
  ClassAliases referenced it, as a string in the alias table. A
  full-repo import-closure check after the fix finds only the three
  expected non-issues: `org.telegram.messenger.BuildConfig` and
  `org.telegram.messenger.R` (both generated at build time, absent
  from pristine source too) and the pre-existing, untouched
  `org.telegram.amingram.soarbridge.Soarbridge` (DNS code).
- `ApplicationLoader` (patch 0031): engine init + a SIMPLIFIED
  `installCrashReportFilter()` (watchdog + saved handler only; no
  CrashLog / Crashlytics reporting). Pristine has no saved
  `systemUncaughtExceptionHandler`, so the field + the
  attachBaseContext assignment were added too.
- `ConnectionsManager` (patch 0038): the pre/post request hooks use
  the (possibly replaced) `pluginRequest`/`pluginResponse`; the
  feed-normalizer step was skipped (other feature). `object` is kept
  for the `instanceof TL_upload_getFile` download-size checks, exactly
  as exteraless does; `dumpResponseAndRequest` logs the request that
  was actually sent.
- `MessagesController.processUpdates` (patch 0037): the internal
  stageQueue re-post calls `processUpdatesInternal` directly, as
  exteraless does — no double-hook.
- `LaunchActivity` (patch 0032): EVENT_APP_STOP fires unconditionally
  in onDestroy (as exteraless does — NOT guarded by isFinishing, unlike
  our old M2 code); `PluginDenialNotice.flush()` added onResume.
  EVENT_APP_START is NOT fired from LaunchActivity — the engine fires
  it from `PluginsController.init()`.
- `ChatActivity` (patch 0044): the MESSAGE_PLUGINS_OPTION cell block
  was placed in pristine's cell loop (after the default click
  listener, before OPTION_TRANSLATE) — pristine has no Reply/Copy/
  Forward swipeback wrappers, so there is no `nkbtn_detail` anchor
  to hang it on. The flags condition gains ONLY the
  MESSAGE_PLUGINS_OPTION term (exteraless also adds OPTION_REPLY /
  COPY / FORWARD terms for its own wrappers — not ported).
- Per-plugin DEX consent default OFF and no auto-update are the
  engine's own behaviour (its docstring: no pip, no SDK auto-updates);
  nothing was changed there. The M4 catalog is gone with the
  clean-room engine (no replacement — the real engine has none).

### Patches (new series 0001–0048; cap LIFTED by Amin 2026-11-16)
- 0028-plugin-settings-members (rewrite, members only, see above)
- 0031-engine-init (ApplicationLoader: init + watchdog filter)
- 0032-engine-launch-hooks (LaunchActivity: app events, intent
  dispatch wrapper, handleViewIntent, settings links)
- 0033-engine-notification-events (NotificationCenter: 5 new ids —
  pluginIsNotResponding, pluginMenuItemsUpdated, pluginsUpdated,
  pluginSettingsRegistered, pluginSettingsUnregistered)
- 0034-engine-uitem (UItem: settingItem field + exteraExpandableSwitch
  + asExteraExpandableSwitch; setSearchable/setLinkAlias NOT ported —
  they call the exteraless SettingsRegistry, another feature)
- 0036-engine-send-hook (SendMessagesHelper: on_send_message_hook)
- 0037-engine-updates-hooks (MessagesController: on_updates_hook
  wrapper + on_update_hook filter in processUpdateArray)
- 0038-engine-request-hooks (ConnectionsManager: pre/post request hooks)
- 0039-engine-db-gate (MessagesStorage.getDatabase: PluginSinkGate)
- 0040-engine-browser-intents (Browser.openUrl wrapper + openUrlInternal)
- 0041-engine-file-open (AndroidUtilities: 2× dispatchOpenFile)
- 0042-engine-rich-file-tap (RichMessageLayout: handleFileTap)
- 0043-engine-message-tap (SearchDownloadsContainer +
  SharedMediaLayout + FilteredSearchView: handleMessageTap)
- 0044-engine-chat-activity (ChatActivity: pluginsMenu field,
  observers, swipe-back submenu, message-menu option, 2×
  expandMessagePluginsOption, 2× handleMessageTap)
- 0045-engine-dialogs-menu (DialogsActivity: appendMainMenuItems)
- 0046-engine-profile-menu (ProfileActivity: PROFILE_ACTION_MENU
  submenu + observer)
- 0047-engine-gradle-deps (AliuHook / MVEL / DexMaker /
  HiddenApiBypass)
- 0048-engine-proguard-keeps (app.exteraless.plugins.** +
  com.exteragram.messenger.**; the stale amin.pyplugins keep and the
  three narrow M0 keeps it subsumes were removed; de.robv keep stays
  from 0028)
- Upstream count: **45 distinct files** (was 38; cap lifted by Amin's
  order, so no headroom math anymore). 19 upstream Java files carry
  the new engine hooks + build.gradle + proguard-rules.pro; the rest
  is the pre-existing series. Rebase contract: the 21 files above are
  the new surface; every hunk in them is plugin-only.

### Verification (local, no Android SDK; 2026-11-16)
- Patch series replay: fresh pristine 21f7d10 tree +
  `git apply` of the whole series 0001–0048 in order — **all 39
  patches apply clean, zero failures**, then the repo overlay copied
  over (CI model) with all hook lines present.
- JDK 17 ParseCheck (Temurin 17.0.21+9, syntax-only, UTF-8): **PARSE
  OK — 2453 Java files**, incl. all 86 engine files and all 19
  patched upstream files.
- CPython tests (`verify-scratch/engine-port/test_engine_port.py`):
  **20/20 pass** — all 39 ported .py files compile; the real MSL
  .plugin's metadata parses with the ported metadata_parser
  (id material_settings_list, name MaterialSettingsList, version
  1.1.1, permissions [], requirements []); MSL's imports resolve
  against the ported SDK names (base_plugin, client_utils, ui) +
  Chaquopy Java bridges, with `zwylib` as the only non-SDK import
  (installed library, guarded in MSL by try/except + RecursiveMock);
  the real ZwyLib .elyx metainfo parses with the engine's own
  elyx_runtime.metadata reader (id zwylib, version 3.1.0, category
  libraries — category lives in the raw YAML, the normalized shape
  drops it); ported BasePlugin has create_settings /
  on_plugin_load / on_plugin_unload.
- `git diff --check` clean. Secret scan zero (no keys/tokens/
  passwords in the ported code or the new patches).
- Repo diff vs HEAD dceb8ed: 34 deleted (23 Java + 8 Python + 3 old
  patches), 12 modified (incl. PluginsController + AppUtils, which
  OVERWROTE M0 facade stubs of the same FQ name), 86 engine Java
  files + 39 Python files + resources untracked. No commits, no
  pushes (parent handles that).

### On-device test list (Amin — nothing below is verifiable until device)
- Install the real `zwylib-3.1.0_3_jazb.elyx` from file → installs as
  a LIBRARY; list shows it (id zwylib v3.1.0). Then install
  `material_settings_list_1_87g4.plugin` → enable MaterialSettingsList.
- Enable MSL → loads with NO ImportError; its 8 toggles appear;
  toggle each, restart the app, confirm reorder/debloat persist;
  disable the plugin and confirm stock Settings returns.
- DEX-hook behaviour (reorder groups, debloat, palette) is still
  unverified until device — green local checks only prove the code
  parses and the metadata/import surface resolves.
- Fixtures, if they still exist after the port:
  `assets/amingram_pyplugins/hello_py.plugin`,
  `settings_demo.plugin`, `hooks_demo.plugin` — try installing
  them; `hooks_demo` references the old host class name and is
  expected NOT to work (dead fixture, delete if it annoys).
- Still open until device: whether stock Chaquopy 17.2.0 suffices
  (the exteraless/chaquopy fork is the fallback); cold-start time
  with the engine on/off; APK size delta (v50 was ~113.9 MB);
  fragment-walk refresh behaviour of the plugin menus.
