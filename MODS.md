# AminGram — Mod Ledger

Base: official Telegram for Android (DrKLO/Telegram), tracked at release tags.
Rule: ALL custom code lives under `org.telegram.messenger.amin.**`.
Upstream files may only be touched with minimal hook calls into that package.
Every upstream file touched is listed here — this list is the rebase contract.
Target: keep this list under 30 files, each with 1–3 line hooks.

## Upstream files touched
- `org/telegram/ui/SettingsActivity.java` — 3 micro-hooks (base 13.0.2):
  import of `amin.ui.AminPluginsActivity`; an "AminGram" header + Plugins row
  (SettingCell id 90) in `fillItems`; `case 90` in the `onClick` switch →
  opens the Plugins hub. Rebase check: search for `case 90` and `AminGram`.

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
- VideoBackground — mp4/gif as animated chat background
- KeywordRadar — (phase 2) keyword alerts across chats
- ConfigVault — (phase 2) collects vless/sub/soar links from chats

## Plugin system
- Registry: `amin.plugins.PluginRegistry` — modules self-register with a
  manifest (id, title, description, settings schema, enabled-by-default).
- Settings UI ("Plugins" hub) is generated from manifests.
- Runtime script plugins (JS/Python) are phase 3 — API surface will mirror
  the module events so scripts survive upstream rebases.
