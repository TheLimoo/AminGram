# Upstream pin

- Source: https://github.com/DrKLO/Telegram
- Pinned at: version 13.2.2, commit 21f7d10 ("update to 13.2.2 (7303)")
- The CI clones upstream fresh, copies this overlay's `TMessagesProj/` files
  in, applies `patches/`, injects API credentials from repo secrets
  (API_ID / API_HASH) into BuildVars.java, and builds
  `:TMessagesProj_App:assembleAfatRelease`.
- Upgrade = re-clone a newer upstream, re-run CI. Patch conflicts surface
  in the "Apply AminGram overlay" step; fix the patch, note it in MODS.md.
