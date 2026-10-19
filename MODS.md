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
