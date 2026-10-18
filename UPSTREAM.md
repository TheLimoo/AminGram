# Upstream pin

- Source: https://github.com/DrKLO/Telegram
- Pinned at: version 13.0.2, commit f32ffee ("update to 13.0.2 (7175)")
- The CI clones upstream fresh, copies this overlay TMessagesProj files
  in, applies patches, injects API credentials from repo secrets
  (API_ID / API_HASH) into BuildVars.java, and builds
  :TMessagesProj_App:assembleAfatRelease.
- Upgrade = re-clone a newer upstream, re-run CI. Patch conflicts surface
  in the Apply AminGram overlay step; fix the patch, note it in MODS.md.
