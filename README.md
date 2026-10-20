# AminGram

A personal Telegram Android client — a fork of the official
[Telegram for Android](https://github.com/DrKLO/Telegram) sources
(DrKLO/Telegram, pinned at 13.0.2, commit
`f32ffeec763f0bdbf4bec25b52d654f77bff733d`) with a modular feature
layer under `org.telegram.messenger.amin.**`, a native modules hub in
Settings, and a JavaScript plugin system (Mozilla Rhino) whose
plugins install instantly from inside the app — no rebuild needed.

**v2.0 — the Grand Update.** Rich card viewers for Deleted / Edit
history, a redesigned collapsible hub, true plugin deletion, a
customizable attach menu, a new `file` bridge capability that posts
downloaded media as real files, HTTP error bodies for plugin API
calls, three new bundled plugins (10 in total), and the removal of
the custom design themes — AminGram now uses upstream Telegram
themes as-is. Details: `MODS.md` (v2.0 section) and `DEVLOG.md`.

GPLv2 — same license as upstream. Built and signed by GitHub
Actions; releases are sideload APKs signed with AminGram's own
keystore. Uses the owner's own Telegram API credentials (repo
secrets `API_ID` / `API_HASH`), never committed.

## Native features (Settings → AminGram → Plugins)

Built-in modules, each with a switch, a description and a live
status line in the hub:

- **Local Premium** — local (client-side) premium state.
- **Ghost Mode** — no read receipts, no online status, no typing
  indicators where the client controls them.
- **Ghost for Stories** — opening a story sends no read receipt.
- **Anti-Delete** — deleted messages stay visible and are kept in a
  persistent Deleted-messages record.
- **Edit History** — the pre-edit text of edited messages, kept as
  a persistent record.
- **Free Translate** — translation via free endpoints, no API key.
- **Video Background** — an MP4/GIF as the animated chat background.
- **Keyword Radar** — keyword alerts across chats, live and on
  chat-open history scan.
- **Auto-Saver** — view-once / self-destruct media saved to a real
  in-app gallery (grid, full-screen viewer, save/share).
- **Hide Stories** — hides the stories row.
- **Hidden Chats** — PIN-locked chats filtered out of the dialog
  list, with a dedicated management page.
- **Send When Online** — queue a message for a user; it sends when
  their presence update arrives (event-driven, no polling).
- Plus: numeric **user/chat ID row** in profiles (tap to copy),
  custom **app icons**, **AminGram Backup** (one-JSON export/import
  of settings, stores and JS plugins), and a **Google Account** page
  (native OAuth sign-in whose token `google`-capable plugins use).

v2.0 hub: sections are **collapsible with count-labelled headers**,
there is a **plugin search**, the JS engine is a **standard switch
row** (the old custom pill is gone), and every plugin card has an
**overflow menu with Move up / Move down**, settings, log and
delete. Deleted / Edit history open as **rich cards** with a count
header and a **filter** (text / chat / sender); tapping a card
opens a detail screen (full text — before/after for edits —
metadata, Copy, Open chat, **Delete this record**).

## Plugin system

- Runtime: sandboxed JavaScript (Rhino), one dedicated plugin
  thread, per-hook error isolation — a throwing plugin is logged
  and skipped, never crashes the app or other plugins. No polling,
  no timers, no wakelocks: everything rides Telegram's update flow.
- Contract: `docs/PLUGIN-MASTER-PROMPT.md` is the single source of
  truth (manifest header, capabilities, hooks, the `amin` bridge).
  Capabilities: `observe`, `commands`, `send`, `settings`, `http`,
  `menu`, `google`, `file`. Settings schemas are `bool` / `text`
  only.
- v2.0 bridge: `amin.sendFile(url, opts)` (`file` capability,
  synchronous boolean; `video`/`photo`/`audio`/`file`, caption,
  optional chatId; 200 MB cap) and `amin.httpPost` returning the
  HTTP **error body** on non-2xx so plugins can surface the server's
  real verdict (`extractError` is the canonical helper). `httpGet`
  is unchanged.
- Attach menu: `menu`-capable plugins appear as buttons in the
  chat attach menu; the hub's **Menu plugins** page controls
  inclusion, order, icon and label per plugin (manifest defaults:
  `@icon` — a set name or `emoji:<glyph>` — and `@menuLabel`).
- Deleting a plugin is a **true delete**: record, code, settings,
  storage and log are all removed — no tombstone. A deleted bundled
  plugin stays deleted across restarts and upgrades; it can be
  reinstalled manually from the hub's **Built-in library**.

### Bundled plugins (10, pre-installed)

| id | what it does |
| --- | --- |
| `shout` | `/shout <text>` posts the text in uppercase. |
| `vip-alert` | Toasts when a chosen person writes in any chat. |
| `media-dl` | Media Downloader v2: TikTok / X / Reddit / Instagram media sent into the chat as real files (`/dl`, `/mp3`, attach menu), link fallback. |
| `temp-mail` | Disposable email addresses + verification codes inside Telegram (`/tm`, attach menu). |
| `aesthetic-text` | `/wide`, `/space`, `/fliptext` text restyling (+ attach menu). |
| `quick-translate` | `/tr <text>` translation via the keyless Google endpoint (default target Persian). |
| `gemini-chat` | `/ai <question>` asks Gemini with your connected Google account — OAuth, no API key. |
| `link-cleaner` | `/clean <url>` strips tracking parameters and resolves short links; attach-menu button reuses the last link in the chat. |
| `qr-maker` | `/qr <text or link>` posts a QR code image via `amin.sendFile` (link fallback). |
| `price-check` | `/price` — live Iran-market prices in tomans (dollar, gold 18k, Emami coin, BTC, ETH). |

### Install a plugin

1. Get a plugin `.js` file written to the contract in
   `docs/PLUGIN-MASTER-PROMPT.md` (the document doubles as a prompt
   for an AI: paste it, describe the plugin, get one file back).
2. In AminGram: **Settings → AminGram → Plugins → Add plugin** —
   pick the file, or paste the code.
3. The app validates the manifest, syntax-checks and probe-runs the
   file (its `onLoad` is *not* called at install), then stores it.
4. Enable it on its hub card; open its page for settings
   (generated from its `SETTINGS` schema) and its log.

## Build from source

CI (`.github/workflows/build.yml`) does exactly this; locally:

1. Clone the pinned upstream:
   `git clone --depth 1 --recurse-submodules --shallow-submodules https://github.com/DrKLO/Telegram.git upstream`,
   then inside it fetch + check out the pinned commit
   `f32ffeec763f0bdbf4bec25b52d654f77bff733d`. Never build against
   moving upstream HEAD.
2. Copy this repo's overlay over it: `cp -r TMessagesProj upstream/`
   (this repo *is* the overlay: `TMessagesProj/` sources + assets,
   `patches/`, `docs/`).
3. Apply the patches in order (`git apply` from inside `upstream/`):
   `0001-settings-plugins-entry`, `0002-rebrand-package`,
   `0003-phase2-hooks`, `0004-videobg-rebrand`,
   `0006-message-pipeline-hook`, `0007-icon-changer`,
   `0008-hide-stories`, `0009-profile-user-id`,
   `0010-js-runtime-gradle`, `0011-hidden-chats-dialogs-filter`,
   `0012-ghost-stories`, `0013-outgoing-command-hook`,
   `0014-attach-menu-plugins`, `0016-google-auth`.
   (0005 and 0015 are intentionally absent — gaps in the series:
   0005's google-services registration is generated by a CI step,
   and 0015, the design-startup hook, was deleted in v2.0 with the
   design modules.)
4. Register the fork package in `TMessagesProj_App/google-services.json`
   (CI step: deep-copy upstream's `org.telegram.messenger` client
   entry for `org.telegram.amingram` / `.beta`).
5. Provide secrets — never commit them: `API_ID` / `API_HASH`
   (your Telegram API credentials, injected into `BuildVars.java`)
   and the keystore secrets `AMINGRAM_KEYSTORE_B64`,
   `AMINGRAM_KEYSTORE_PASSWORD`, `AMINGRAM_KEY_ALIAS`,
   `AMINGRAM_KEY_PASSWORD` (the decoded keystore replaces
   `TMessagesProj/config/release.keystore`).
6. With JDK 17 and NDK 27.2.12479018 installed, build:
   `./gradlew :TMessagesProj_App:assembleAfatRelease` with the
   signing `-PRELEASE_STORE_PASSWORD` / `-PRELEASE_KEY_ALIAS` /
   `-PRELEASE_KEY_PASSWORD` overrides from the keystore secrets.

## Credits

- Upstream: **DrKLO/Telegram** (Telegram for Android) — AminGram is
  a patch + overlay fork; all upstream code remains the work of its
  authors, GPLv2.
- Plugin engine: Mozilla Rhino. Google sign-in: Play Services Auth.
