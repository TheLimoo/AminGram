# AminGram

A personal Telegram Android client, forked from the official
[Telegram for Android](https://github.com/DrKLO/Telegram) sources.

- Modular feature layer under `org.telegram.messenger.amin.**` — see `MODS.md`
  for the hook ledger that keeps upstream upgrades cheap.
- Plugins hub in Settings, generated from module manifests.
- Built and signed by GitHub Actions; releases are sideload APKs.
- Uses the owner's own Telegram API credentials (repo secrets
  `API_ID` / `API_HASH`), never committed.

GPLv2 — same license as upstream.
