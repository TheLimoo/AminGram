# AminGram Plugin — Master Prompt (v2.2 + Bot Bridge)

Give this whole document to an AI (or paste it at the start of a chat) and then
describe the plugin you want in one or two sentences. The AI must answer with
ONE JavaScript file and nothing else. Install it in AminGram:
**Settings → AminGram → Plugins → ➕ Add plugin** (pick the file, or paste the code).

This is the v2.2 contract (Plugin Platform v2, complete). It matches the
v2.2 runtime exactly — every signature, return value and failure mode
below is the shipped behaviour, verified against the runtime sources.
New in v2.2 (Phase B): `amin.db` (a real per-plugin database),
`amin.files` + `amin.pickFile` (a sandboxed file store and the system
file picker), the `ui` capability (`amin.ui` — clipboard, notifications,
open chats/links — plus long-press menu items via `@contextMenu` /
`onContextAction`), `amin.deleteMessage` / `amin.pinMessage` under the
existing `act` capability, a plugin's own page (`PLUGIN_PAGE` /
`onPluginAction`), plugin-to-plugin calls (`amin.relay`, `@exports` /
`@topics` / `@depends`), `color` and `file` setting types, the
permission review screen shown before outside installs, the plugin
catalog and shareable plugin packs, `amin.sendFile` download headers,
and the in-app code editor with one-tap rollback. New in v2.1: the rich
message object + lazy lookups (`amin.getChatInfo` / `getUserInfo` /
`getMessage`), the `act` capability (`amin.editMessage` / `amin.react`),
async HTTP (`amin.httpGetAsync` / `amin.httpPostAsync`), storage v2
(JSON + namespaces), settings schema v2 (number / slider / select /
header / dependsOn), lifecycle hooks (`onEnable` / `onDisable` /
`onSettingsChanged` / `onMigrate`), leveled logging, `amin.platform`
feature detection, manifest `@minPlatform` / `@dataVersion`, and
dual-mode Gemini (AI Studio / Vertex AI) for the `google` capability.
Everything from v2.0 still holds: the `file` capability +
`amin.sendFile`, HTTP error bodies from `amin.httpPost` (with the
canonical `extractError` helper), and the attach-menu fields `@icon` /
`@menuLabel`. The platform API version is still **2** — Phase B
completes v2, it does not replace it.

New since v2.2 — the **Bot Bridge** (see the dedicated section
below): a plugin can offload work to the **@awminai** bot — rich
button cards, server-side media resolving, a cloud state store,
translation, bot-side file downloads. It is NOT a new bridge surface
and needs NO new capability: it is a message protocol plus an
embeddable client (`bridge-client.js`) riding on the existing
`observe` + `send` capabilities, and it required no AminGram update.
There is still no native `amin.bot` — the helper + protocol are the
whole interface. The two halves complement each other: the plugin
owns in-chat delivery and account actions; the bot owns heavy,
multi-request, server-side work.

---

## Golden rules

1. **Declare exactly the capabilities you use.** The install review
   screen shows the user your capabilities in plain language (the same
   wording as the capability table below). Declaring a capability you
   don't use is asking for power you don't need; missing one makes the
   surface not exist at runtime.
2. **Gated surfaces do not exist without their capability** — this is
   structural, not a permission check. `amin.sendFile`, `amin.google`,
   `amin.editMessage`, `amin.files` … are `undefined` / absent when
   the capability isn't declared. Feature-detect and degrade.
3. **Nothing ever throws across the bridge.** Every `amin.*` failure
   comes back as `false` / `null` / an error string in a callback.
   Check return values; still wrap risky code in try/catch.
4. **Event-driven only.** No timers, no polling, no busy loops — the
   platform forbids them by design (there is no `setTimeout` contract
   to rely on). React inside hooks and callbacks.
5. **Messages may not be loaded.** Lookups and message actions only
   see messages the app has already loaded. A `null` / `false` from a
   lookup or action is a normal outcome — design for it.
6. **Pick the right store** (see the decision table): small prefs and
   settings → `amin.storage`; structured/queryable/bigger data →
   `amin.db`; actual files → `amin.files`. Putting big blobs in
   storage is the classic mistake.
7. **Async beats blocking** on anything user-facing: async HTTP over
   sync HTTP, callbacks over stalls. All plugins share ONE thread —
   a slow plugin makes every plugin slow.
8. **Send as the user, sparingly.** `amin.send` / `amin.reply` /
   `act` actions post as the account owner. Only act in direct
   reaction to something the user did or explicitly configured.
9. **Offload, don't grind.** If a job needs many sequential web
   requests, a rich button UI, or state that must survive a
   reinstall, hand it to the @awminai bot through the Bot Bridge
   (below) instead of burning your 5 s hook budget — the bot has
   no hook budget and a server-side network.

## Which surface for which job

| Job | Use | Not |
|---|---|---|
| A flag, a counter, a small JSON note | `amin.storage` (`get`/`set`/`getJSON`/`setJSON`) | `amin.db` (overkill) |
| Many records, key prefixes, up to 256 KB per value | `amin.db` (namespaces + keys) | storage (64 KB design limit) |
| Downloaded/generated file content | `amin.files` (sandbox, 50 MB) | db values (text-shaped) |
| Post media into a chat | `amin.sendFile(url, opts)` | downloading it yourself |
| Let the user pick a file | `amin.pickFile(opts, fn)` | asking them to paste a path |
| Quick web call inside a command | sync `amin.httpGet`/`httpPost` | — |
| Web call from `onMessage` or anything user-facing | `amin.httpGetAsync`/`httpPostAsync` | sync HTTP (blocks every plugin) |
| Answer in the same chat | `amin.reply(msg, text)` | `amin.send` with a guessed id |
| Edit/react/delete/pin a message | `act` surface (`amin.editMessage` …) | — (no other way exists) |
| Talk to another plugin | `amin.relay.call` / `emit` | shared storage (there is none) |
| Show the user something | `amin.toast` (momentary) / `amin.ui.notify` (persistent) | spamming `amin.send` |
| A row/button on your plugin page | `PLUGIN_PAGE` + `onPluginAction` | a fake settings row |
| Rich button UI inside a chat | Bot Bridge `card` action | fake buttons drawn as text |
| Multi-request link/media discovery | Bot Bridge `resolve` (one round trip) | chaining sync HTTP inside a hook |
| State that survives reinstall / syncs devices | Bot Bridge `store` | `amin.db` (device-local only) |
| Translation via the bot's fallback chain | Bot Bridge `translate` | hand-rolling three endpoints |
| The bot downloads a file into the DM | Bot Bridge `fetch` | `sendFile` (that's for posting into a chat yourself) |

---

---

You are writing a plugin for **AminGram**, a modified Telegram Android client.
Output exactly ONE JavaScript file. No explanations, no markdown fences around
it, no extra files.

## File format

The file starts with a manifest comment block. `@id`, `@name`, `@version`,
`@author`, `@description` and `@capabilities` are required; `@icon`,
`@menuLabel`, `@minPlatform`, `@dataVersion`, `@contextMenu`,
`@exports`, `@topics` and `@depends` are optional:

```js
// ==AminGramPlugin==
// @id          shout
// @name        Shout
// @version     1.0.0
// @author      Amin
// @description Turns /shout <text> into a loud uppercase message.
// @capabilities commands, send
// @icon        sparkles
// @menuLabel   Shout it
// @minPlatform 2
// @dataVersion 1
// @contextMenu Make it loud
// @exports     shoutText
// @topics      jokes, *
// @depends     text-utils@>=1.2
// ==/AminGramPlugin==
```

- `@id`: lowercase letters, digits and `-` only. Unique. Never changes between versions.
- `@version`: write it as **semver** — `X.Y` or `X.Y.Z`, numbers only
  (`1.0` and `1.2.3` are fine; `v1`, `1.0-beta`, `one` are not). A
  non-semver version still installs, but it draws a warning in the
  plugin's log and version comparisons (catalog updates, `@depends`)
  treat its parts as best-effort numbers. Versions compare
  numerically part by part: `1.10` is newer than `1.9`, and `1.2`
  equals `1.2.0`.
- `@capabilities`: comma list of what the plugin uses, from the table
  below. An unknown capability makes the install invalid — never
  invent one. The right-hand column is the EXACT plain-language line
  the install review screen shows the user for that capability —
  declare a capability only when you'd be comfortable with the user
  reading that line about your plugin:

  | Capability | Surface it unlocks | Review screen says |
  |---|---|---|
  | `observe` | `onMessage` sees messages | "See your incoming messages as they arrive" |
  | `commands` | `onCommand` | "Run commands you type (like /dl)" |
  | `send` | `amin.send` / `amin.reply` | "Send messages as you" |
  | `act` | `amin.editMessage` / `react` / `deleteMessage` / `pinMessage` | "Edit, react to, delete and pin messages as you" |
  | `file` | `amin.sendFile`, `amin.files`, `amin.pickFile` | "Send and download files, pick files, and keep its own files" |
  | `http` | `amin.httpGet`/`httpPost` + async forms | "Make internet requests" |
  | `relay` | `amin.relay` | "Talk to other plugins and hear their events" |
  | `ui` | `amin.ui` (+ `@contextMenu` items) | "Show notifications, use the clipboard, open chats and links" |
  | `settings` | the SETTINGS page + `PLUGIN_PAGE` | "Keep its own settings and page here" |
  | `menu` | attach-menu button (`onMenuAction`) | "Add items to the attach (+) menu" |
  | `google` | `amin.google` | "Use your Google account sign-in (for Gemini)" |

- `@contextMenu` (optional, v2.2; needs `ui`): a label for ONE item in
  the message long-press menu. When the user taps it, your
  `onContextAction(ctx)` runs with `ctx = {chatId, msg}` (msg = the
  rich message object). At most 4 plugins' items are shown, in hub
  order. Without the field (or the capability) there is no item.
- `@exports` (optional, v2.2; pairs with `relay`): comma list of method
  names other plugins may call on you. You implement them on a global
  `PLUGIN_API` object (see Relay). A method that isn't listed here
  cannot be called, even if it exists on `PLUGIN_API`.
- `@topics` (optional, v2.2; pairs with `relay`): comma list of event
  topics your `onEvent(topic, data)` wants. `*` means every topic.
- `@depends` (optional, v2.2): comma list of other plugins you require,
  each `id` or `id@>=X.Y`. Checked at install: a missing or too-old
  dependency fails the install with an error naming it. (The catalog
  offers the missing plugin as a one-tap follow-up install.)
- `@icon` (optional, only meaningful with `menu`): the attach-menu icon.
  Either a set name — `download`, `mail`, `sparkles`, `dice`, `text`,
  `link`, `music`, `star`, `bolt`, `camera`, `globe`, `gear` — or an
  emoji, written `emoji:<glyph>` (e.g. `emoji:🎬`). Unset = default icon.
- `@menuLabel` (optional, only meaningful with `menu`): the attach-menu
  button label. Unset = the plugin `@name`. The user can override both
  the icon and the label per plugin in the hub's **Menu plugins** page,
  and can reorder or hide menu buttons there — a plugin never controls
  its own menu position.
- `@minPlatform` (optional, integer, default 1): the plugin-platform API
  level the plugin was written against. This build is platform **2**.
  If a plugin declares a `@minPlatform` NEWER than the running build,
  the install still succeeds — the user only gets a warning line in the
  plugin's log ("installing anyway"). It never blocks. Declare `2` when
  the plugin uses any v2.1 surface; feature-detect with
  `amin.platform.has(...)` (below) instead of relying on it.
- `@dataVersion` (optional, integer, default 1): the version of the
  plugin's OWN stored-data shape. Bump it when an update changes what
  the plugin keeps in `amin.storage`, and migrate the old data in
  `onMigrate` (see Lifecycle hooks). An unparsable or non-positive
  `@minPlatform` / `@dataVersion` quietly falls back to 1 — it never
  fails the install.

## Runtime

Plain JavaScript (ES5 + most ES6: `let/const`, arrow functions, template literals,
`Array.map/filter`, `String.includes`). No modules, no `require`, no DOM, no
`fetch`. **No polling and no timers**: plugins are purely event-driven —
react inside the hooks below; never schedule recurring work. The plugin
runs inside the app on its own dedicated thread: code that throws is caught
**per hook** and written to the plugin's log — it can never crash AminGram
or break another plugin (per-hook isolation). Two guardrails sit on top of
that isolation — a per-hook execution budget and an error-storm suspension;
see **Reliability** below, and write hooks that finish fast and rarely throw.

## The `amin` bridge (the ONLY way to touch the app)

```js
amin.log(text)                      // write to the plugin's log (hub → plugin → Log)
amin.debug(text)                    // leveled log lines (v2.1): debug / info /
amin.info(text)                     // warn / error — amin.log is the info
amin.warn(text)                     // level (unprefixed, as always); the
amin.error(text)                    // others carry a [level] prefix
amin.toast(text)                    // small popup on screen
amin.storage.get(key, defaultValue) // per-plugin saved values (string/number/bool)
amin.storage.set(key, value)        // set(key, null) removes the key
amin.send(chatId, text)             // send a message AS THE USER to a chat
amin.reply(msg, text)               // send a message into the same chat the msg came from
amin.httpGet(url)                   // returns response text, or null on ANY failure
                                    // (including an HTTP error status) — blocking: call sparingly
amin.httpGet(url, headers)          // headers overload: headers is a {name: value} object
                                    // (e.g. {Authorization: "Bearer " + token}) applied
                                    // after the defaults; same caps/timeouts/null rules
amin.httpPost(url, body, headers)   // POST: body is a string, headers a {name: value} object;
                                    // returns the response text on 2xx; on a non-2xx status
                                    // returns the ERROR BODY instead (read the server's
                                    // verdict — see extractError below); null only when the
                                    // request itself failed (blocking)
amin.httpGetAsync(url, headersOrNull, fn)   // v2.1 async HTTP — see below
amin.httpPostAsync(url, body, headers, fn)  // v2.1 async HTTP — see below
amin.sendFile(url, opts)            // `file` capability ONLY — see next section
amin.platform                       // v2.1 feature detection — see below
amin.getChatInfo(chatId)            // v2.1 lazy lookups — see below
amin.getUserInfo(userId)
amin.getMessage(chatId, id)
amin.editMessage(ref, text)         // `act` capability ONLY — see below
amin.react(ref, emoji)              // `act` capability ONLY — see below
amin.deleteMessage(ref, opts)       // `act` ONLY (v2.2) — see Acting
amin.pinMessage(ref, opts)          // `act` ONLY (v2.2) — see Acting
amin.db.get(ns, key)                // v2.2 — EVERY plugin — see Database
amin.db.put(ns, key, value)         //   (false / null on contract
amin.db.del(ns, key)                //    violations, never a throw)
amin.db.keys(ns, prefix)
amin.db.clear(ns)
amin.files.readText(name)           // `file` ONLY (v2.2) — see File store
amin.files.writeText(name, text)
amin.files.list()                   // [{name, sizeBytes, modifiedAt}]
amin.files.remove(name)
amin.files.exists(name)
amin.pickFile(opts, fn)             // `file` ONLY (v2.2) — async picker
amin.ui                             // `ui` ONLY (v2.2) — see UI surface;
                                    // null when the capability is absent
amin.relay                          // `relay` ONLY (v2.2) — see Relay;
                                    // null when the capability is absent
```

`amin.httpGet` semantics are unchanged from v1: null on failure, never
an error body. The optional second argument is a headers object for
bearer-auth APIs (the bundled temp-mail uses it against mail.tm: on a
null from an authenticated GET, re-auth once and retry once). Only
`amin.httpPost` surfaces error bodies (v1.3.7+, canonical in v2.0).
Always run a non-empty `httpPost` result through `extractError` before
treating it as a successful answer:

```js
// Canonical error extractor — copy this verbatim into any plugin that
// calls amin.httpPost against a JSON API. Returns the server's error
// message (truncated), or "" when the response carries no error.
function extractError(response) {
  try {
    var data = JSON.parse(response);
    if (data && data.error && data.error.message) {
      var msg = String(data.error.message);
      return msg.length > 300 ? msg.substring(0, 300) + "…" : msg;
    }
    return "";
  } catch (e) {
    return "";
  }
}
```

## The message object (rich in v2.1)

Every `msg` the runtime hands you (in `onMessage`, inside `ctx.msg`, and
from `amin.getMessage`) has this shape:

```js
msg = {
  id,             // message id in its chat
  chatId,         // dialog id (positive = private chat, negative = group/channel)
  senderId, senderName,
  text,           // message text or caption ("" when none)
  outgoing,       // bool — sent by the user themselves
  channel,        // bool — posted in a broadcast channel
  date,           // unix seconds
  // v2.1 additions:
  replyToId,      // id of the message this one replies to (0 = not a reply)
  editDate,       // unix seconds of the last edit (0 = never edited)
  fwdFrom,        // forward origin's display NAME ("" when not forwarded; name only, no id)
  groupedId,      // album group id (0 = not part of an album)
  chatTitle,      // title/name of the chat the message is in
  chatType,       // "private" | "group" | "supergroup" | "channel"
  senderUsername, // sender's @username ("" when none / unknown)
  senderIsBot,    // bool
  media           // null when the message carries no media, otherwise:
                  // { kind, fileName, mimeType, sizeBytes,
                  //   durationSec, width, height }
                  // kind: "photo" | "document" | "video" | "audio" |
                  // "voice" | "sticker" | "gif" | "webpage" | "poll" | "other";
                  // fields that don't apply are "" / 0. Metadata only —
                  // the descriptor never downloads anything.
}
```

## Lazy lookups (v2.1, on every plugin)

Resolve ids on demand instead of guessing from the message alone. All
three return **null** when the target isn't known/loaded — always
null-check the result.

```js
amin.getChatInfo(chatId)  // {id, title, type, username, memberCount} | null
                          // type: "private" | "group" | "supergroup" | "channel";
                          // memberCount is 0 for private chats
amin.getUserInfo(userId)  // {id, name, username, isBot, isPremium} | null
amin.getMessage(chatId, id) // a full msg object (same shape as above) | null
```

**Bound:** `amin.getMessage` only finds messages that are **already
loaded** in the app (the open chat's loaded history, messages seen this
session). It never fetches from the server — an older, never-loaded
message returns null. Design for the null case.

## Acting on messages (`act` capability only, v2.1)

If the manifest does NOT declare the `act` capability, `amin.editMessage`
and `amin.react` do not exist at all (`typeof amin.editMessage ===
"undefined"`) — the gate is structural, exactly like `amin.google` and
`amin.sendFile`. With the capability:

```js
var ok = amin.editMessage(ref, "new text")  // → true / false
var ok2 = amin.react(ref, "👍")             // → true / false
// ref = a msg object, or any object with {chatId, id}
```

- `editMessage` edits the text of one of **the user's OWN outgoing
  messages only** — that is enforced natively, not by your code: a
  message that isn't the user's own (or is ephemeral) is refused with
  `false` and a log line. The message must also be currently loaded
  (same bound as `amin.getMessage`); an unloaded target returns `false`.
- `react` sends an emoji reaction to a message (any author's — reacting
  to other people's messages is the normal case). Same loaded-message
  bound.
- Both return `true` once the request has been dispatched, `false` on
  any failure; neither ever throws across the bridge.

New in v2.2, same `act` capability, same ref shape:

```js
var okD = amin.deleteMessage(ref, { forEveryone: true })  // → true / false
var okP = amin.pinMessage(ref, { notify: false, pmOneSide: false }) // → true / false
// opts is optional; both default to the least destructive form
// (local delete / silent pin).
```

- `deleteMessage` deletes through the app's own delete path (local
  storage, dialogs and the server request stay consistent).
  **`forEveryone` is honoured ONLY for the user's own messages** —
  enforced natively: asking for it on someone else's message degrades
  to a local-only delete with a warning in your log (the call still
  returns `true` when the local delete is dispatched). Never promise
  the user a for-everyone delete you didn't verify.
- `pinMessage` pins in the message's chat. `notify: true` un-silences
  the pin (other members get the pin system message); `pmOneSide:
  true` in a private chat pins only on the user's side.
- Same loaded-message bound as edit/react (unloaded → `false`).
  **Every delete/pin call is written to your plugin's log with its
  target ids** — destructive power is auditable by design.

## Async HTTP (v2.1)

The sync `httpGet` / `httpPost` block the plugin thread (up to ~30 s
worst case) — and every plugin shares that one thread, so a slow server
stalls everyone. Prefer the async forms for anything user-facing:

```js
amin.httpGetAsync(url, headersOrNull, function (res) { /* res = {status, body} */ });
amin.httpPostAsync(url, body, headers, function (res) { /* res = {status, body} */ });
```

- The callback receives ONE object `{status, body}`: `status` is the
  HTTP status code, or **0 on transport failure** (bad URL, connect/read
  failure) with `body` null.
- Body rules mirror the sync forms: async GET delivers `body` null on
  HTTP error statuses (the status still tells you what happened); async
  POST delivers the **error body** on a non-2xx status, like sync POST.
- The network work runs OFF the plugin thread; the callback itself runs
  back ON the plugin thread, inside the usual per-hook isolation —
  and with its OWN fresh 5 s budget window (see Reliability), so a
  chain of async steps never shares one window. Headers may be
  `null`. Same 15 s timeouts and 1 MB body cap as the sync forms.

## Storage v2 (v2.1)

The legacy string store is unchanged — and SETTINGS values live in the
same store, read the same way:

```js
amin.storage.get(key, defaultValue)   // string/number/bool, typed by the default you pass
amin.storage.set(key, value)          // set(key, null) removes the key
```

v2.1 adds JSON values and namespaces on top of the same store:

```js
amin.storage.setJSON(key, value)      // any JSON-shaped value: object, array,
                                      // string, number, bool, null
amin.storage.getJSON(key, defaultValue) // the parsed value, or defaultValue when
                                      // the key is absent OR its stored value
                                      // is not valid JSON (e.g. a legacy string)
amin.storage.remove(key)              // delete one key
amin.storage.keys(prefix)             // sorted array of this plugin's key names
                                      // starting with prefix (null / "" = all)
var sub = amin.storage.ns("cache")    // a namespaced VIEW of the same store:
sub.set("a", 1)                       // physically stored as "cache:a"; views
                                      // have the full get/set/getJSON/setJSON/
                                      // remove/keys surface and can nest
```

- `setJSON` with an `undefined` or function value is ignored (logged as
  a warning) — JSON has no such values.
- Keep each stored value **≤ 64 KB** of JSON. (Larger values are stored
  anyway, but logged as a warning — treat 64 KB as the design limit.)
- Storage is per-plugin and private: no plugin can read another's keys.

## Database — `amin.db` (v2.2, EVERY plugin)

When storage's flat keys stop being enough — many records, prefix
scans, values bigger than a preference — use the per-plugin key/value
database. It is a base surface like storage (no capability needed);
your rows live in a shared database file, physically namespaced by
your plugin id — you can never see another plugin's rows.

```js
amin.db.put("notes", "n1", { title: "Shopping", done: false })  // → true
var note = amin.db.get("notes", "n1")        // → the object, or null
var ids  = amin.db.keys("notes", "n")        // → ["n1", …] sorted,
                                             // prefix-filtered
amin.db.del("notes", "n1")                   // → true when a row went away
amin.db.clear("notes")                       // empties ONE namespace
```

- Values are any JSON-shaped value (object/array/string/number/
  bool) — they cross as JSON, so functions and `undefined` are
  refused: `put` returns `false` and logs a warning. An explicit
  `null` value is refused the same way — to remove a key, use
  `del`; to test for one, use `keys`.
- **Contract limits (hard, not warnings):** a value's JSON must be
  **≤ 256 KB**, and namespace + key names **≤ 128 chars** each.
  Violations make `put` return `false`; `get`/`del` of out-of-contract
  names simply find nothing. Design keys accordingly (short ids,
  prefix schemes like `"note:2026-10-21:3"`).
- `get` returns `null` for a missing key — distinguish "absent" from a
  stored JSON `null` via `keys()` if it matters.
- Nothing throws. A `false` from `put` means NOT stored — check it
  when the write matters.

## Sending files (`file` capability only, v2.0)

If the manifest does NOT declare the `file` capability, `amin.sendFile`
does not exist at all (`typeof amin.sendFile === "undefined"`) — the gate
is structural, exactly like `amin.google`. With the capability:

```js
var ok = amin.sendFile(url, opts)   // SYNCHRONOUS — returns a boolean
// opts = { type: "video" | "photo" | "audio" | "file",
//          caption: "text shown with the media",   // optional
//          chatId: 123456,                         // optional — defaults
//                                                     // to the chat the
//                                                     // current hook is
//                                                     // running in
//          headers: { "User-Agent": "…" } }       // optional (v2.2) —
//                                                     // download request
//                                                     // headers, applied
//                                                     // after the defaults
//                                                     // on every redirect hop
```

- `sendFile` downloads the URL itself (you never download anything) and
  posts it into the chat as **real media** through Telegram's own send
  pipeline, with the caption. `true` means the file was downloaded and
  handed to the send pipeline (Telegram uploads it from there).
- Limits: **200 MB hard cap**, 15 s connect timeout / 60 s read timeout,
  redirects followed. Any failure — bad URL, HTTP error, over the cap,
  no target chat, send hand-off failed — returns `false`; it never
  throws across the bridge (still wrap it in try/catch, belt and braces).
- `opts.headers` (v2.2): an object of header name → value for the
  download request — e.g. a crawler `User-Agent` for hosts that only
  serve media to one (media-dl's Instagram mirror works this way).
  Headers override the defaults and are re-applied on every redirect.
- It BLOCKS the plugin thread while downloading — inside your
  current hook's 5 s wall-clock window (see Reliability). Call it
  once per user action, never from a hot `onMessage` path for every
  message. **Budget discipline (Downloader Pro's rule):** compute
  the caption and every follow-up BEFORE calling `sendFile`, and
  never do meaningful JS after it in the same invocation — if the
  download was slow, the deadline kills whatever comes next. For
  multi-file jobs, use a job queue: persist the remaining work
  (`amin.db` / storage), send ONE file per invocation, and chain to
  the next step from a fresh budgeted callback (Downloader Pro
  3.0.1 re-enters through an async-GET completion tick), so the
  reporting/finish step always runs in a fresh window.
- **Fallback rule (canonical pattern):** if `sendFile` returns `false`
  (or is unavailable), post the plain link with `amin.reply` /
  `amin.send` instead, so the user always gets something. The bundled
  media-dl works exactly this way: real media first, link fallback.

## File store + picker (`file` capability only, v2.2)

`amin.files` is your plugin's private sandbox directory — for content
that is a FILE (downloaded text, generated output, a working copy),
not a value. Like `amin.sendFile`, it does not exist without the
`file` capability (`typeof amin.files === "undefined"`).

```js
amin.files.writeText("draft.txt", "hello")   // → true / false
var text = amin.files.readText("draft.txt")  // → string, or null
var list = amin.files.list()  // → [{name, sizeBytes, modifiedAt}, …] sorted by name
amin.files.exists("draft.txt")               // → bool
amin.files.remove("draft.txt")               // → bool
```

- **Bare file names only.** No directories, no paths: a name with
  `/`, `\`, `..` or a NUL, or longer than 128 chars, is rejected
  (`writeText`/`remove` → `false`, `readText` → `null`). There is no
  way out of your sandbox — don't design around subfolders.
- **50 MB total quota** per plugin: a write that would exceed it
  fails with `false` (and the partial file is discarded). Check
  `list()` sizes when you manage big content.
- Text only. For binary, save via a flow that produces a path (below)
  and pass paths to `amin.sendFile`-style sends by URL — the sandbox
  is for text you read back with `readText`.

`amin.pickFile` lets the user hand you a file through the system
document picker — the only way a plugin receives a local file:

```js
amin.pickFile({ accept: "video/*", copy: true }, function (path) {
  if (path === null) { /* user cancelled — say nothing or say so once */ }
  else { /* path = absolute path of the picked file */ }
});
```

- ASYNC: the callback fires when the picker closes — later, on your
  plugin's thread. `copy: true` (the default) copies the file into
  your sandbox first (durable — the original may vanish); the path
  you get is the copy's. `copy: false` stages it in a cache dir
  instead (faster, but treat the path as temporary).
- Cancellation and every failure deliver `null`. There is no error
  object — `null` IS the failure signal.
- `{accept}` is a MIME filter (`"video/*"`, `"application/pdf"`,
  `"*/*"` default). The picker is the system's; you get one file
  per call.

## UI surface (`ui` capability only, v2.2)

`amin.ui` covers the small ways a plugin touches the interface
directly. Without the `ui` capability the field is **null** (not
undefined — check `if (amin.ui)` or feature-detect `"ui"`).

```js
amin.ui.clipboardWrite("text")        // → true / false. WRITE-only:
                                      // there is no clipboard read.
amin.ui.notify("Title", "Body")       // → true / false
amin.ui.notify("Title", "Body", { chatId: 123456 })
                                      // tapping the notification
                                      // opens that chat
amin.ui.openUrl("https://example.com") // → true / false
amin.ui.openChat(chatId)              // → true / false
amin.ui.openUser(userId)              // → true / false (opens the
                                      // private chat with that user)
```

- `notify` posts to the app's single "Plugin notifications" channel.
  A native sanity cap allows **5 notifications per minute per
  plugin**; beyond that `notify` returns `false` and logs a line.
  Notifications are for events the user asked to hear about — never
  a per-message firehose.
- `openUrl` accepts only `http:`, `https:` and `tg:` URLs; anything
  else returns `false` (and logs). The URL opens in the system
  handler / Telegram itself.
- `openChat` / `openUser` route through the same intent the app's
  own notifications use. They return `true` once dispatched — they
  cannot promise the chat exists; a stale id just lands the user on
  the chat list.
- All five return booleans and never throw.

### Context-menu items (`@contextMenu` + `onContextAction`)

Declare `@contextMenu <label>` (and the `ui` capability) and define:

```js
function onContextAction(ctx) {
  // ctx = { chatId, msg } — msg is the rich message object the user
  // long-pressed. Runs on your plugin thread, standard budget.
}
```

Your item appears in the message long-press menu alongside the
built-in actions. At most **4** plugin items show (hub order), so
don't be surprised if yours isn't always visible on a crowded
install. The menu path itself is metadata-only — your code runs
only when the user actually taps your item.

## Relay — plugin-to-plugin (`relay` capability only, v2.2)

Plugins can offer services to each other and broadcast events,
without any shared storage. Without the `relay` capability
`amin.relay` is **null**.

**Offering:** list method names in `@exports` and implement them on a
global `PLUGIN_API` object:

```js
// @exports shoutText
var PLUGIN_API = {
  shoutText: function (text) { return String(text).toUpperCase() + "!"; }
};
```

**Calling** (async — the result comes back through the callback):

```js
amin.relay.call("text-utils", "shoutText", ["hello"], function (err, result) {
  if (err) { /* err is a string: no such plugin / disabled / not
                exported / suspended / depth exceeded / the method
                threw (its message) */ }
  else { /* result = the return value, deep-copied as JSON */ }
});
```

- Arguments and results cross as **JSON deep copies** — no shared
  objects, no functions. A result that isn't JSON-shaped fails the
  call with an error string.
- Recursion is capped: a relay chain may nest **4** calls deep; the
  next one fails with `"relay depth exceeded"`. Design providers to
  be leaves, not routers.
- `call` NEVER throws — argument mistakes (missing target/method,
  unserialisable args) also arrive as the callback's `err`.

**Events** (fire-and-forget broadcast):

```js
amin.relay.emit("download-done", { chatId: 123, name: "clip.mp4" });
// receiver side — declare @topics download-done (or *) and define:
function onEvent(topic, data) { /* data is a JSON deep copy */ }
```

Delivery goes to enabled plugins that define `onEvent` AND list the
topic (or `*`) in `@topics`. The emitter never receives its own
event. Both sides log relay traffic — keep event payloads small and
meaningful.

**Dependencies:** if you call another plugin, declare it:
`@depends text-utils@>=1.0`. Install then fails cleanly when it's
missing or too old, instead of your calls failing at runtime.

## The Bot Bridge — @awminai as your server side

Plugins are fast but constrained: a 5 s wall-clock hook budget,
multi-request discovery that eats it, and state that dies with the
device. The **@awminai** bot is the complement: no hook budget, a
server-side network, durable per-user storage, and the ability to
render real inline-button cards — which no plugin surface can do
inside a chat. The bridge generalises the pattern the Theme
Changer has shipped since v4.0. It is **not** a native surface:
there is no `amin.bot`, no new capability and no feature name for
it. It is a message protocol plus a client you embed.

**Requirements:** declare `observe` (to see the bot's replies in
`onMessage`) and `send` (to post requests). You also need the chat
id of the user's DM with @awminai: learn it from any incoming bot
message (`msg.chatId` in that DM) and remember it in storage, the
way theme-manager does — or let the user paste it into a setting.

### The client — embed `bridge-client.js`

The canonical client ships with the plugin sources
(`bridge-client.js`, protocol v1). Paste it **verbatim** above your
hooks — do not rewrite the codec; both sides must match byte-for-byte.
Wire-up is three lines:

```js
var bridge = AminBridge.create({
  ns: "my-plugin",                 // your @id — it namespaces ALL bot-side state
  getBotChatId: function () { return Number(amin.storage.get("botChat", 0)) || 0; },
  timeout: 30000,                  // optional, ms (default 30000)
  onAction: function (action, arg, env) { /* card button taps land here */ }
});

function onMessage(msg) {
  if (bridge.handleMessage(msg)) return;  // true = a bridge reply/action; consumed
  // …your normal message handling…
}
```

Wrappers (all take a Node-style callback `fn(err, data)`):

```js
bridge.card(cardData, fn)                    // rich button card in the bot DM
bridge.resolve(url, fn)                      // server-side media resolution
bridge.storeSet(value, fn)                   // cloud state: write your blob
bridge.storeGet(fn)                          // cloud state: read it back
bridge.storeDelete(fn)                       // cloud state: delete it
bridge.translate(text, to, fn)               // to optional — the bot's chain
bridge.fetchFile(url, { as, name, caption }, fn) // bot downloads into the DM
bridge.request(action, data, fn)             // the raw form, for anything new
bridge.poll()                                // enforce timeouts from any hook
```

- Callback `err` is **null on success**, otherwise an OBJECT
  `{code, message}` (unlike relay, whose `err` is a string — don't
  confuse the two). No reply within the timeout arrives as
  `{code: "timeout"}`; a failed send as `{code: "send_failed"}`.
- Callbacks fire from your `onMessage` — inside a fresh hook
  window, on your thread. Keep them light: the heavy work already
  happened on the bot.
- **Timeouts are LAZY** (plugins have no timers): a pending request
  only times out when the next message arrives or you call
  `bridge.poll()` from some hook. Design for late answers.
- The bot answers every request it actually receives (a malformed
  one gets a `bad_payload` reply) — so silence means your request
  never arrived. **Always keep a local fallback** for the timeout
  case (bot down / bridge not deployed): a local attempt, a plain
  link, device-local state. A bridge failure must never dead-end
  the user.

### The five actions — when to use which

**`card`** — a rich inline-button card in the bot DM. Use it for
choices, pagers and confirmations (quality pickers, theme grids):
the ONLY way a plugin gets real buttons. Data:
`{title (≤120), text (≤3500, HTML — escape user content yourself),
preview (bool, default off), buttons}` — buttons are ≤8 rows × ≤4
of `{label, action, arg}`. **One tracked card per plugin**: a new
`card` edits the open one in place. Reply data:
`{messageId, edited}`. Button taps come back as action messages
routed to your `onAction(action, arg, env)` — react by doing the
work and/or posting a fresh `card` (the bot never edits the card
on a tap; you own the flow). Telegram caps `callback_data` at 64
bytes: keep `arg` a short token or index, never a URL — one long
button rejects the whole card with `button_too_long`.

**`resolve`** — server-side media resolution in ONE round trip:
send `{url}`, get
`{source, title, video, images[], audio, linkOnly, note}`. The bot
runs its resolver stack (TikTok via tikwm; X via VxTwitter →
FxTwitter; Reddit via its `.json` API; Instagram via the
kkinstagram mirror with the crawler user-agent; direct file URLs
pass through; anything else falls back to an OpenGraph scrape).
Use it instead of chaining discovery requests inside a hook —
then post the returned URL with `amin.sendFile` yourself
(caption precomputed, per the budget rule). Failures:
`bad_url` / `resolve_failed`; a `linkOnly` answer means "no direct
media — here is the mirror link", which is your cue for the plain
link fallback.

**`store`** — ONE JSON blob per (user, plugin) in the bot's
durable store: backup/restore and cross-device sync for state
that should survive a reinstall. Ops: `storeSet(value)` →
`{stored, size}`; `storeGet` → `{exists, value}`;
`storeDelete` → `{deleted}`. **Cap: 32 KB** of serialized JSON
(`too_large` above it) — settings, lists, counters; it is a state
blob, not a file store. `amin.db` stays your local workhorse;
`store` is its cloud mirror, not its replacement (sync at moments
that matter — after a settings change, on enable — not per event).
The user can wipe all bridge cloud data from the bot's Plugin
Bridge card, so treat the blob as a mirror that can disappear —
never the only copy of anything precious.

**`translate`** — `{text, to}` through the bot's exact 3-stage
fallback chain (gtx on translate.googleapis.com → gtx on
translate.google.com → MyMemory); reply `{text, to}`; failure
`translate_failed`. Use it when you don't want to hand-roll the
endpoints and their fallbacks yourself.

**`fetch`** — the bot downloads a URL itself and posts the file
into the DM as a real upload; reply
`{messageId, fileId, kind, size, name}` — reference or forward
that message afterwards. **Cap: 20 MB** (failures: `bad_url` /
`fetch_failed` / `send_failed`). Use it when the file belongs in
the DM; to post media into an arbitrary chat AS THE USER,
`amin.sendFile` is still the tool.

Generic error codes on any action: `bad_payload`, `bad_ns`,
`unknown_action`, `internal`. Errors are data, never exceptions.

### The wire protocol (for debugging / hand-rolling)

Requests are DM texts `🔗BRIDGE <base64url(JSON)>` (🔗 = U+1F517);
replies are `⚡BRIDGE <base64url(JSON)>` (⚡ = U+26A1). Request
envelope: `{v: 1, ns, action, id, data}` — `ns` is your `@id`,
`id` a correlation id the reply echoes verbatim. Response
envelope: `{v: 1, id, ns, ok, data}` or
`{…, ok: false, error: {code, message}}`. Replies longer than one
message arrive **chunked** (~3000-char base64 parts in
`{chunk: {i, n}, part}` envelopes, same `id`) — the embedded
client reassembles them; requests are never chunked, so a
request must fit in ONE message: keep `data` compact (send a
URL, not content). Card buttons carry
`callback_data = "b:<ns>:<action>:<arg>"`; a tap makes the bot
post an action message whose `data` is
`{kind: "action", action, arg}` with the callback-query id as
`id`. Full spec: `PLUGIN-BRIDGE.md` in the AminGram sources.

### Hard limits — design around them, don't discover them

- **Payload messages stay visible** in the bot DM — yours and the
  bot's replies. Bots cannot delete user messages in a DM. It's
  cosmetic, shipped since Theme Changer v4.0 — but it means:
- **Payloads are NOT encrypted.** base64url is an encoding anyone
  can decode. NEVER send secrets through the bridge — no API keys,
  no OAuth tokens, no passwords, no one-time codes. (Same rule as
  for storage, twice as important here: payloads leave the app.)
- **Request/response only.** The bot can never initiate: no
  pushes, no reminders, no self-refreshing cards. Every ⚡ message
  answers a request or a button tap. The bridge does not give a
  plugin background execution — nothing here runs while the user
  isn't interacting.
- **One tracked card per plugin** (keyed by user + `ns`). Need
  several live cards? That's not this tool — each new `card`
  replaces the old by design.
- **Latency is two Telegram messages** plus the bot waking up:
  right for menus, resolving and fetching; wrong for anything
  keystroke-by-keystroke.
- **`ns` is a namespace, not a credential** — any plugin can speak
  for any `ns`. Fine for a personal toolbox; don't build secrecy
  between plugins on top of it.

### Worked shape — resolve via the bot, deliver yourself

```js
function onCommand(ctx) {
  if (ctx.command !== "dlx") return;
  var url = (ctx.args || "").trim();
  if (!url) { amin.reply(ctx.msg, "Usage: /dlx <link>"); return; }
  bridge.resolve(url, function (err, data) {
    if (err || !data || (!data.video && !data.linkOnly)) {
      amin.reply(ctx.msg, "🔗 " + url);   // local fallback: the link
      return;
    }
    if (data.video) {
      // Caption computed BEFORE the blocking send; nothing after it.
      var caption = (data.title || "").substring(0, 200);
      amin.sendFile(data.video, { type: "video", caption: caption, chatId: ctx.chatId });
    } else {
      amin.reply(ctx.msg, "🔗 " + data.linkOnly);
    }
  });
}
```

## Plugin page — `PLUGIN_PAGE` + `onPluginAction` (v2.2)

Your plugin's hub page can carry your own rows (needs the `settings`
capability — the same one that unlocks SETTINGS):

```js
var PLUGIN_PAGE = [
  { type: "info", text: "Counters reset every Monday." },
  { type: "action", id: "reset", title: "Reset counters",
    subtitle: "Zero everything now" }
];

function onPluginAction(actionId) {
  if (actionId === "reset") { /* … */ amin.toast("Counters reset"); }
}
```

- `info` rows are static text; `action` rows are tappable and
  dispatch `onPluginAction(actionId)` on your thread.
- Malformed rows (unknown type, action without `id`/`title`, info
  without `text`) are skipped silently at install; at most **24**
  rows are kept. The rows are read ONCE at install (the probe) —
  changing them needs a reinstall/update, they are not dynamic.
- Pair with `amin.db`/`amin.storage` reads inside `onPluginAction`
  for pages that DO things (export, reset, recompute), and use
  `amin.ui.notify` / `amin.toast` to report the outcome.

## Google account (`google` capability only) — dual mode (v2.1)

If the manifest does NOT declare the `google` capability, `amin.google`
does not exist at all (`typeof amin.google === "undefined"`). With the
capability, the plugin can use the Google account the user connected in
**Plugins → Google Account** — an OAuth access token, never an API key:

```js
amin.google.isSignedIn()            // true while an account is connected
amin.google.getAccessToken(fn)      // ASYNC: fn(token) — token is a string, or null
amin.google.signIn(fn)              // ASYNC: fn(true/false) — opens the native
                                    // Google account picker / consent screen
amin.google.getMode()               // v2.1: "aistudio" or "vertex" ("" if unreadable)
amin.google.getLastAuthError()      // v2.1: the most recent authorize failure's
                                    // detail, or "" when none / last attempt OK
```

- Both async calls take a **callback** — they never return the value
  directly, and the callback fires later (on the plugin's own thread),
  possibly after the user finishes a consent screen. Structure code as
  continuations: everything that needs the token goes INSIDE the
  getAccessToken callback.
- Typical pattern: if `!amin.google.isSignedIn()`, call
  `amin.google.signIn(function (ok) { … })` and continue inside it when
  `ok` is true; otherwise tell the user once, cleanly.
- Tokens live about an hour; `getAccessToken` silently refreshes them.
  Never store a token in `amin.storage` — always fetch a fresh one.
- Google may occasionally demand an interactive re-consent
  (unverified app): then `getAccessToken` returns null and `signIn`
  is the way back.

### The two modes

The user picks the mode in **Plugins → Google Account → API mode**
(`amin.google.getMode()` reports it; switching modes clears the cached
token, since the modes use different scopes — the user reconnects under
the new mode). A dual-mode plugin must branch on the mode:

- **"aistudio" (default)** — the original AI Studio endpoint:
  `https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent`
  with `Authorization: "Bearer " + token`.
  ⚠️ **Currently blocked by a Google-side defect.** This mode needs the
  `generative-language` OAuth scope, which Google's console refuses to
  register for this app — authorization dies with `invalid_scope`, and
  tokens minted under the broader `cloud-platform` scope are rejected
  by the API itself ("insufficient authentication scopes"). There is
  nothing a plugin (or the app) can do about it today. Plugin-facing
  behaviour: `getAccessToken` yields null and
  `amin.google.getLastAuthError()` contains `invalid_scope` — answer
  with a plain-words line, like the bundled gemini-chat does:
  *"AI Studio mode needs a Google-side scope fix — switch to Vertex AI
  in Plugins → Google Account, or wait for the fix."* Do not retry in a
  loop and do not blame the user's connection.
- **"vertex"** — the same Gemini models through the user's Google Cloud
  project (this mode's `cloud-platform` scope registers fine):
  `https://<location>-aiplatform.googleapis.com/v1/projects/<project>/locations/<location>/publishers/google/models/<model>:generateContent`,
  same JSON body as AI Studio. Auth is EITHER the account token
  (`Authorization: "Bearer " + token`) OR — when the plugin's
  `vertexApiKey` setting is set — the header
  `x-goog-api-key: <key>` with **no Google sign-in at all**: in key
  mode, never call `signIn` / `getAccessToken`; the key IS the
  credential. The one gate is **project billing**: a 403 whose error
  mentions billing means billing is off — map it to a plain-words
  line ("Vertex needs billing enabled on the project (one console
  step) — or switch back to AI Studio mode."), never a raw dump.
  The bundled gemini-chat carries `vertexProject` /
  `vertexLocation` / `vertexModel` / `vertexApiKey` settings for
  exactly this.

## Hooks — define the ones you need

```js
function onLoad() { /* runs once when the plugin's runtime is created */ }

function onEnable() { /* v2.1 lifecycle — see below */ }
function onDisable() { /* v2.1 lifecycle — see below */ }
function onSettingsChanged(key, newValue, oldValue) { /* v2.1 — see below */ }
function onMigrate(fromVersion, toVersion) { /* v2.1 — see below */ }

function onMessage(msg) { /* runs for every message the app processes */ }
// msg = the rich message object above.
// Return true to mark the message "handled" (stops later plugins), or nothing.

function onCommand(ctx) { /* runs when the USER sends a message starting with / */ }
// ctx = { chatId, senderId, command ("shout"), args ("hello world"), msg }
// When the command message REPLIES to another message, ctx also carries
// ctx.replyTo = { text, caption, senderName, chatId, messageId } describing
// the replied-to message (text = its text; caption = the same text when the
// target carries media, else ""). If the target isn't loaded, the object is
// still present with empty text/senderName. No reply → no ctx.replyTo.
// A plugin CANNOT edit or unsend the command message itself — it reacts,
// usually by sending a new message with amin.reply/amin.send. (With the
// `act` capability it CAN edit its own earlier messages — see above.)

function onMenuAction(ctx) { /* runs when the user taps this plugin's button in
  the chat attach menu (next to Gallery / File / Poll …). Requires the `menu`
  capability. ctx = { chatId }. The user is IN that chat — react immediately:
  use the chat's context (e.g. the last link your onMessage remembered) or
  post your result with amin.send(ctx.chatId, …) / amin.reply({chatId: ctx.chatId, id: 0}, …).
  Button label/icon come from @menuLabel/@icon unless the user overrode
  them on the hub's Menu plugins page. */ }

function onContextAction(ctx) { /* v2.2 — the user tapped your
  @contextMenu item on a message. Requires the `ui` capability.
  ctx = { chatId, msg }. See UI surface. */ }

function onEvent(topic, data) { /* v2.2 — a relay event arrived.
  Requires the `relay` capability and the topic (or *) in @topics.
  See Relay. */ }

function onPluginAction(actionId) { /* v2.2 — a PLUGIN_PAGE action
  row was tapped on your hub page. Requires the `settings`
  capability. See Plugin page. */ }
```

### Lifecycle hooks (v2.1)

- `onEnable()` — runs when the plugin is enabled (toggle on, or right
  after an install of an enabled plugin), after the load sequence
  (`onLoad` → `onMigrate` → pending settings delivery) completes. It is
  NOT re-fired when a storm suspension is resumed (the plugin was never
  disabled).
- `onDisable()` — runs on the plugin thread when the plugin is disabled,
  BEFORE its runtime is dropped, so it can flush state to
  `amin.storage`. A throw inside `onDisable` is logged like any hook
  error and **never blocks the disable** — the plugin still turns off.
- `onSettingsChanged(key, newValue, oldValue)` — runs when the user
  changes one of the plugin's settings in the hub. Values are the raw
  stored **strings** (parse numbers/bools yourself). If the plugin was
  stopped when the change happened, the hook is delivered **once at the
  next start** (after `onLoad` / `onMigrate`) with
  `oldValue == newValue ==` the current stored value — you learn THAT a
  setting changed while you were away, not its history (only the most
  recent changed key is remembered).
- `onMigrate(fromVersion, toVersion)` — runs when an installed update
  declares a higher `@dataVersion` than the version stored from the
  previous code. It runs right after `onLoad` — before `onEnable`, the
  pending settings delivery, and any dispatched hook of the new code —
  and no message/command dispatch reaches the plugin while it runs.
  Rewrite your stored data here (read old shape, write new shape). The
  stored version advances **only when `onMigrate` returns cleanly**: if
  it throws, the error is logged, the stored version stays put (so the
  migration is retried at the next start), and the plugin still runs.
  A fresh install never triggers `onMigrate` — the declared version is
  adopted silently. A plugin without `onMigrate` simply has its stored
  version adopted; its data is its own problem.

## Settings (optional) — schema v2

```js
var SETTINGS = [
  { key: "loud", type: "bool", title: "Extra loud", def: false },
  { key: "suffix", type: "text", title: "Suffix to add", def: "!", help: "Appended to every shout." },
  { key: "count", type: "number", title: "Repeat count", def: 1, min: 1, max: 10, step: 1 },
  { key: "volume", type: "slider", title: "Volume", def: 50, min: 0, max: 100, step: 5 },
  { key: "lang", type: "select", title: "Language", def: "fa",
    options: [{ value: "fa", label: "Persian" }, { value: "en", label: "English" }] },
  { key: "accent", type: "color", title: "Accent color", def: "#FF8800" },
  { key: "model", type: "file", title: "Model file", def: "", accept: "*/*" },
  { type: "header", title: "Advanced" },
  { key: "apiKey", type: "text", title: "API key", def: "",
    dependsOn: { key: "loud", equals: true } }
];
```

Eight row types: **`bool`**, **`text`**, **`number`**, **`slider`**,
**`select`**, **`header`**, **`color`** and **`file`** (the last two
are new in v2.2). The hub builds the settings page from this
automatically. Read values with `amin.storage.get(key, def)` — pass a
default of the right type (bool def → boolean back, number/slider def →
number back, text/select/color/file → string) and trim `text` values
before use — mobile keyboards add trailing spaces.

- `color`: the hub renders Telegram's own color picker; the stored
  value is a normalised **`#RRGGBB`** string (upper-case). `def` must
  be a valid `#RRGGBB` too (strict mode rejects anything else, naming
  the key).
- `file`: the hub renders a document-picker row; the picked file is
  **copied into the app's private storage** and the stored value is
  the copy's **absolute path** (a string; `""` = nothing picked yet).
  `accept` (optional) is the picker's MIME filter. Read the file from
  the path; never assume the user's original still exists.

- Any row: `key`, `type`, `title`, `def`, `help` (a subtitle shown
  under the row), `dependsOn`.
- `text`: also `maxLength` (0 / absent = unlimited) and `multiline`
  (bool).
- `number` / `slider`: also `min`, `max`, `step` (all optional;
  a slider without min/max runs 0–100). `def` is a number (0 when the
  schema omits it).
- `select`: `options` is required — an array of `{value, label}`
  objects, or plain strings (the string is both value and label).
  The stored value is the chosen option's `value`.
- `header`: a section label row. It has **no `key`** and no value —
  give it only `type` + `title`.
- `dependsOn: {key, equals}`: the row is shown only while the OTHER
  setting's stored value equals `equals` (compared as strings — a
  bool setting stores `"true"` / `"false"`, so `equals: true` in the
  example above works). Use it to hide advanced rows until a master
  switch is on.

**Validation law — keep your schema strict-clean.** When a plugin id is
installed for the FIRST time, the schema is validated **strictly**: an
invalid schema fails the install with an error naming the offending
keys. Strict errors include: unknown `type`, a `select` with no
options, `min` greater than `max`, a non-numeric `def` for
number/slider, a `color` whose `def` isn't `#RRGGBB`, and a duplicate
`key`. (Lenient mode coerces a bad color to `#000000` with a warning;
a `file` row's stored value is just a path string — `accept` is a
hint to the picker, never validated against the file.) Reinstalling an EXISTING id is
lenient instead — the same problems become warnings in the plugin's
log and are coerced (unknown type → `text`; option-less select →
`text`; `min > max` → both dropped; bad numeric default → 0; duplicate
key → first definition wins). A `header` that carries a `key` is only
ever a warning (the key is dropped), and a `dependsOn` pointing at an
unknown key is only a warning — but write schemas that pass strict
mode anyway: your users' first install is the strict one.

## Reliability — errors, watchdog, budget (v2.1)

- Every hook throw is caught, written to the plugin's log as an
  `[error]` line, and counted for that plugin (a per-day counter —
  the hub shows a **⚠ N** badge on the plugin's card and page while
  today's count is above zero). One plugin's errors never affect
  another plugin or the app.
- **Error-storm suspension:** if a plugin's hooks throw **20 or more
  times within 60 seconds**, the watchdog suspends it **for the rest
  of the session**: it stops receiving hooks (its enabled toggle is
  untouched). The user can Resume it from the plugin page (Resume
  clears the counters), and reinstalling the plugin also clears a
  suspension. A steady plugin that does not throw is **never**
  throttled — there is no rate limit on well-behaved hooks.
- **Per-hook budget:** each hook invocation gets a **5-second
  WALL-CLOCK deadline**, counted from the moment the hook starts —
  not 5 s of CPU. The clock keeps running while you sit inside a
  BLOCKING native call: a sync `httpGet` or a `sendFile` download
  eats the same window, and when the call returns past the deadline
  the very next JS instruction kills the hook ("hook budget
  exceeded" in the log; the plugin itself keeps running). Practical
  consequence, learned the hard way by the bundled downloader:
  **code placed after a long blocking call may never run** —
  precompute everything (captions, follow-up text, bookkeeping)
  BEFORE the blocking call, and move post-work into a fresh
  callback. Every new dispatch — a hook, an async HTTP callback, a
  relay callback, a picker callback — starts its own fresh window,
  which is why chaining async steps is the budget-safe shape for
  multi-step work. Keep hooks fast regardless: `onMessage` runs for
  every message.

## Feature detection — `amin.platform` (v2.1)

```js
amin.platform.apiVersion            // 2 in this build
amin.platform.has("act")            // true when the named feature exists
```

Feature names in this build — exactly these twenty-one:
`rich-msg`, `lazy-lookup`, `act`, `http-async`, `storage-json`,
`settings-v2`, `lifecycle`, `watchdog`, `platform`, `sendFile`,
`http-headers`, `reply-context`, and the v2.2 additions `db`,
`files`, `pickFile`, `ui`, `relay`, `act-delete-pin`, `plugin-page`,
`context-menu`, `settings-color-file`. (`amin.platform.has("act")`
covers edit/react; `act-delete-pin` tells you delete/pin exist in
this build — still declare the `act` capability to use them.)
Feature-detect instead of version-sniffing:
`if (amin.platform && amin.platform.has("storage-json")) { … }` — and
keep a fallback for when the answer is no (the bundled quick-replies
does exactly this for its template store).

## Rules

1. One file, everything inside it. Wrap risky code in try/catch.
2. Never loop forever, never busy-wait, never poll, never use timers.
   Keep `onMessage` fast — it runs for every message; do heavy work
   only when the message actually matches.
3. Sending messages posts AS THE USER. Only send in direct reaction to a
   command or a message the user clearly configured the plugin to answer.
4. Never invent bridge functions or capabilities. If a capability is not
   listed above, the plugin cannot do it — say so in a comment at the
   top instead of guessing. (Message delete and pin DO exist — under
   `act`, since v2.2. What does not exist: reading the clipboard,
   scheduling anything, running in the background, touching another
   plugin's storage/db/files. The Bot Bridge is not an exception to
   any of that: it is message-based offload — the bot answers, it
   never pushes, and nothing runs while the user isn't interacting.)
5. Check structural gates defensively where a fallback exists:
   `typeof amin.sendFile === "function"` before relying on it, and
   always provide the link fallback when it is missing or returns false.
   The same applies to `amin.editMessage` / `amin.react` (`act`),
   `amin.google` (`google`), the access objects `amin.files`
   (a field on `file` bridges), `amin.ui` / `amin.relay` (fields that
   are **null** without their capability — test truthiness, not
   `typeof`), and new surfaces via `amin.platform.has(...)`.
6. Prefer the async HTTP forms over the blocking ones, and never store
   OAuth tokens in `amin.storage`.
7. Respect the store contracts: db values ≤ 256 KB JSON, db/file names
   ≤ 128 chars, files are bare names in a 50 MB sandbox, storage
   values ≤ 64 KB by design. A refused write returns `false` — it
   does not throw, and it does not partially store.
8. Mind the review screen: before an outside install (file / paste /
   catalog / pack) the user sees your name, author, version, source,
   code fingerprint and your capabilities in plain language — and, on
   an update, any capabilities the new version ADDS are highlighted
   for explicit confirmation. Write an honest `@description`;
   don't smuggle power into an update.

## Anti-patterns (designs that fail here)

- **Blocking HTTP in `onMessage`.** The sync calls stall the one
  shared plugin thread for up to ~30 s; every plugin waits behind
  you. Match cheaply in `onMessage`, fetch async, answer in the
  callback. A chain of dependent discovery requests is the same
  sin in instalments — offload it to the bot (bridge `resolve`)
  or chain async callbacks, each in its own budget window.
- **Meaningful JS after `amin.sendFile` in the same hook.** If the
  download ate the 5 s wall-clock window, everything after the
  call — toasts, history writes, the next file — is dead code that
  dies with "hook budget exceeded". Precompute before the send;
  hand the rest to a fresh callback (the downloader's job-queue
  pattern, see Sending files).
- **Big values in `amin.storage`.** Storage is a preferences store
  (64 KB design limit). Records belong in `amin.db` (256 KB values,
  namespaced keys), file content in `amin.files`.
- **Polling-shaped code.** No timers exist and none are coming: a
  plugin that "checks every minute" cannot be written on this
  platform. React to messages, commands, taps and relay events —
  if a design needs a clock, it needs a different shape (e.g. react
  when the user next opens the chat).
- **Assuming messages are loaded.** History lookups/actions see only
  what the app has loaded this session. Code that "finds the message
  from last week" will get `null` — handle it as a normal case, not
  an error.
- **Inventing relay partners.** `amin.relay.call` to a plugin that
  isn't installed (or doesn't `@exports` the method) fails through
  the callback's `err`. Declare real partners in `@depends` so the
  install itself guarantees them.
- **Notification firehoses.** `amin.ui.notify` is capped at 5/min and
  meant for configured events. Per-message notifications train the
  user to uninstall you.
- **Storing secrets you were handed at runtime** (OAuth tokens,
  one-time codes) in storage/db/files. Fetch fresh credentials
  through their proper surface each time.

## Developing & sharing (v2.2)

- **Editor:** every plugin page has **Edit code** — fix a bug in
  place; saving re-validates and hot-reloads. The previous code is
  kept as a rollback copy: **Restore previous version** in the
  editor brings it back. The plugin page's **Reload plugin** restarts
  a wedged runtime without reinstalling.
- **Eval console:** with **Developer mode** on (hub → Developer —
  off by default, re-locks every app session), the plugin page gains
  a Developer section: runtime state, a live log tail, a storage
  inspector (storage keys + db namespaces, editable), and an eval
  console that runs a snippet in the plugin's own scope. Evaluations
  are written to the plugin's log; snippets are never stored.
- **Catalog:** the curated catalog (hub → Add Plugin → Plugin
  catalog) installs after SHA-256 verification + the review screen.
  Each entry carries the plugin's id, version, capabilities,
  `sha256` + `size` of the code, and a `minPlatform` — every entry
  in the current catalog declares `minPlatform` **2**. The Built-in
  library stays offline and unchanged.
- **Packs:** hub → Add Plugin → Export/Import plugin pack shares a
  chosen set of installed plugins (code + their storage) as one JSON
  file. Importing walks each plugin through the review screen.
- **Install provenance:** every install records its source
  (bundled / file / paste / catalog / pack / editor) and a short
  SHA-256 fingerprint of the code, shown on the plugin page and the
  review screen — when a user asks "which copy is this?", that's
  the answer.
- **Bundled updates:** when an app update ships a NEWER bundled
  copy of a plugin, the installed plugin updates in place on first
  launch — code and manifest are replaced, while the user's
  settings, storage, db rows, files and enabled state all survive
  (and `@dataVersion` migration runs as usual). An installed copy
  at the same or a newer version (e.g. from a pack import) is
  never downgraded or clobbered, and a plugin the user deleted is
  never resurrected.

## Bundled plugins (reference implementations)

These ship inside AminGram and pre-install; their sources are the best
worked examples of the surfaces above (12 in the Built-in library):

- **shout** — the Example 1 plugin, plus a repeat setting
  (`commands`, `send`, `settings`).
- **vip-alert** — the Example 2 plugin (`observe`, `settings`).
- **media-dl** — **Downloader Pro** 3.0.1: TikTok / Instagram / X /
  Reddit / Pinterest / Threads + direct links; albums, quality
  choices, TikTok MP3, a long-press "Download media" action
  (`@contextMenu`), download history in `amin.db`; real media via
  `sendFile` first (job-queue pattern — never JS after a blocking
  send in the same window), link fallback (`observe`, `commands`,
  `send`, `http`, `settings`, `menu`, `file`, `ui`).
- **temp-mail** — disposable mailboxes on **mail.tm** (rewritten in
  v2.0; the old 1secmail backend is dead): `/tm new` creates a real
  account + bearer token kept in plugin storage, `/tm inbox` /
  `/tm read <id>` use authenticated GETs via the
  `amin.httpGet(url, headers)` overload, re-authenticating once and
  retrying once on failure.
- **aesthetic-text** — /wide, /space, /fliptext, /mono, /bubble,
  /bold (`commands`, `send`, `menu`, `settings`).
- **quick-translate** — /tr via the keyless gtx endpoint, plus a
  long-press **Translate** context action (`@contextMenu`)
  (`commands`, `send`, `http`, `settings`, `ui`).
- **gemini-chat** — /ai, dual-mode Gemini as documented in the Google
  section (`commands`, `send`, `http`, `google`, `settings`, `menu`).
- **link-cleaner** — strips tracking parameters from links.
- **qr-maker** — /qr posts a QR image (`sendFile` + link fallback).
- **price-check** — /price, live currency/gold/crypto prices.
- **ai-actions** (NEW in v2.1) — reply to a message with **/sum**
  (summarize), **/explain** (explain simply) or **/draft** (draft a
  reply), or pass text directly (`/sum <text>`); the attach-menu
  button **"AI: summarize"** summarizes the last text seen in the
  chat. Dual-mode Gemini like gemini-chat, driven by `ctx.replyTo`
  (`commands`, `send`, `http`, `google`, `menu`, `observe`, `settings`).
- **quick-replies** (NEW in v2.1) — text templates: `/tpl set <name>
  <text>` saves one, `/tpl <name>` posts it, `/tpl list` lists them,
  `/tpl del <name>` removes one. Templates live as one JSON object via
  storage v2 (`getJSON`/`setJSON`, with a legacy-string fallback when
  `amin.platform.has("storage-json")` is false). Honest wart, stated
  in its own description: the /tpl command message itself also
  appears in the chat — the platform cannot swallow outgoing commands
  yet.

## Example 1 — a command plugin

```js
// ==AminGramPlugin==
// @id          shout
// @name        Shout
// @version     1.0
// @author      Amin
// @description /shout <text> posts the text in uppercase.
// @capabilities commands, send
// ==/AminGramPlugin==

function onCommand(ctx) {
  if (ctx.command !== "shout") return;
  if (!ctx.args) { amin.reply(ctx.msg, "Usage: /shout <text>"); return; }
  amin.reply(ctx.msg, ctx.args.toUpperCase());
}
```

## Example 2 — an observer plugin with settings

```js
// ==AminGramPlugin==
// @id          vip-alert
// @name        VIP Alert
// @version     1.0
// @author      Amin
// @description Toasts when a chosen person writes in any chat.
// @capabilities observe, settings
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "person", type: "text", title: "Name to watch for", def: "" }
];

function onMessage(msg) {
  if (msg.outgoing) return;
  var who = amin.storage.get("person", "");
  if (who && msg.senderName && msg.senderName.indexOf(who) !== -1) {
    amin.toast("💬 " + msg.senderName + " just wrote");
    amin.log("VIP hit in chat " + msg.chatId);
  }
}
```

## Example A (v2.1, full) — a dual-mode Gemini command (`google` + `httpPost` + `extractError`)

A complete `/ai <question>` plugin in the v2.1 shape: it reads the
user's API mode with `amin.google.getMode()`, supports Vertex key mode
(no sign-in at all), continues inside the async token callback, posts
with the right auth header, and maps the two known dead ends —
AI Studio's Google-side scope defect and Vertex's billing gate — to
plain-words replies via the canonical `extractError` (possible only
because `httpPost` returns error bodies on non-2xx). This mirrors the
bundled gemini-chat.

```js
// ==AminGramPlugin==
// @id          ai-ask
// @name        AI Ask
// @version     1.0
// @author      Amin
// @description /ai <question> asks Gemini — AI Studio or Vertex mode, per Plugins → Google Account.
// @capabilities commands, send, http, google, settings
// @minPlatform 2
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "model", type: "text", title: "Gemini model (AI Studio mode)", def: "gemini-3-flash-preview" },
  { key: "vertexProject", type: "text", title: "Vertex AI: project", def: "" },
  { key: "vertexLocation", type: "text", title: "Vertex AI: location", def: "us-central1" },
  { key: "vertexModel", type: "text", title: "Vertex AI: model", def: "gemini-2.5-flash" },
  { key: "vertexApiKey", type: "text", title: "Vertex AI: API key (optional; skips Google sign-in)", def: "" }
];

function setting(key, def) {
  var v = String(amin.storage.get(key, def) || "").trim();
  return v ? v : def;
}

function mode() {
  return (amin.google.getMode && amin.google.getMode()) || "aistudio";
}

function onCommand(ctx) {
  if (ctx.command !== "ai") return;
  var prompt = (ctx.args || "").trim();
  if (!prompt) { amin.reply(ctx.msg, "Usage: /ai <question>"); return; }
  // Vertex + API key: no Google account involved at all.
  if (mode() === "vertex" && setting("vertexApiKey", "")) {
    vertexRequest(ctx, prompt, null, setting("vertexApiKey", ""));
    return;
  }
  if (!amin.google.isSignedIn()) {
    amin.google.signIn(function (ok) {
      if (ok) { askWithToken(ctx, prompt); }
      else { amin.reply(ctx.msg, "Google sign-in was cancelled — connect an account in Plugins → Google Account."); }
    });
    return;
  }
  askWithToken(ctx, prompt);
}

function askWithToken(ctx, prompt) {
  amin.google.getAccessToken(function (token) {
    if (!token) {
      var authError = (amin.google.getLastAuthError && amin.google.getLastAuthError()) || "";
      if (mode() !== "vertex" && authError.indexOf("invalid_scope") >= 0) {
        amin.reply(ctx.msg, "⚠️ AI Studio mode needs a Google-side scope fix — switch to Vertex AI in Plugins → Google Account, or wait for the fix.");
        return;
      }
      amin.reply(ctx.msg, "⚠️ No Google token — reconnect in Plugins → Google Account.");
      return;
    }
    if (mode() === "vertex") { vertexRequest(ctx, prompt, token, ""); }
    else { aistudioRequest(ctx, prompt, token); }
  });
}

function aistudioRequest(ctx, prompt, token) {
  var model = setting("model", "gemini-3-flash-preview");
  var url = "https://generativelanguage.googleapis.com/v1beta/models/"
    + encodeURIComponent(model) + ":generateContent";
  var response = amin.httpPost(url, body(prompt), {
    "Authorization": "Bearer " + token,
    "Content-Type": "application/json"
  });
  handleResponse(ctx, response, false);
}

function vertexRequest(ctx, prompt, token, apiKey) {
  var project = setting("vertexProject", "");
  var location = setting("vertexLocation", "us-central1");
  var model = setting("vertexModel", "gemini-2.5-flash");
  if (!project) { amin.reply(ctx.msg, "⚠️ Set the Vertex project in the plugin settings first."); return; }
  var url = "https://" + location + "-aiplatform.googleapis.com/v1/projects/"
    + project + "/locations/" + location + "/publishers/google/models/"
    + model + ":generateContent";
  var headers = apiKey
    ? { "x-goog-api-key": apiKey, "Content-Type": "application/json" }
    : { "Authorization": "Bearer " + token, "Content-Type": "application/json" };
  var response = amin.httpPost(url, body(prompt), headers);
  handleResponse(ctx, response, true);
}

function body(prompt) {
  return JSON.stringify({ contents: [{ parts: [{ text: prompt }] }] });
}

function handleResponse(ctx, response, isVertex) {
  if (!response) {
    amin.reply(ctx.msg, "⚠️ Gemini did not answer — check the connection and the model name.");
    return;
  }
  var apiError = extractError(response);
  if (apiError) {
    if (isVertex && apiError.toLowerCase().indexOf("billing") >= 0) {
      amin.reply(ctx.msg, "⚠️ Vertex needs billing enabled on the project (one console step) — or switch back to AI Studio mode in Plugins → Google Account.");
      return;
    }
    amin.reply(ctx.msg, "⚠️ Gemini error: " + apiError);
    return;
  }
  var answer = extractText(response);
  if (!answer) {
    amin.reply(ctx.msg, "⚠️ Gemini answered, but the reply could not be read (safety filter?).");
    return;
  }
  amin.reply(ctx.msg, answer);
}

// Canonical error extractor (see the httpPost section above).
function extractError(response) {
  try {
    var data = JSON.parse(response);
    if (data && data.error && data.error.message) {
      var msg = String(data.error.message);
      return msg.length > 300 ? msg.substring(0, 300) + "…" : msg;
    }
    return "";
  } catch (e) {
    return "";
  }
}

// candidates[0].content.parts[*].text, defensively.
function extractText(response) {
  try {
    var data = JSON.parse(response);
    if (!data || !data.candidates || !data.candidates.length) return "";
    var content = data.candidates[0].content;
    if (!content || !content.parts) return "";
    var out = "";
    for (var i = 0; i < content.parts.length; i++) {
      if (content.parts[i] && content.parts[i].text) out += content.parts[i].text;
    }
    return out.trim();
  } catch (e) {
    return "";
  }
}
```

## Example B (v2.0, full) — a `sendFile` downloader with menu action and link fallback

A complete downloader: `/get <url>` — and the attach-menu button,
which reuses the last link seen in the chat — post the URL as **real
media** via `amin.sendFile`. Any failure (no `file` capability, HTTP
error, over the 200 MB cap) falls back to posting the plain link, the
same pattern bundled media-dl uses.

```js
// ==AminGramPlugin==
// @id          get-file
// @name        Get File
// @version     1.0
// @author      Amin
// @description /get <url> downloads the URL and posts it as real media; the attach-menu button reuses the last link seen in the chat. Falls back to posting the link when the file cannot be sent.
// @capabilities observe, commands, send, menu, file
// @icon        download
// @menuLabel   Send as file
// ==/AminGramPlugin==

var lastByChat = {};

function onMessage(msg) {
  if (!msg.text) return;
  var m = msg.text.match(/https?:\/\/[^\s]+/);
  if (m) lastByChat[msg.chatId] = m[0];
}

function onCommand(ctx) {
  if (ctx.command !== "get") return;
  var url = (ctx.args || "").trim();
  if (!url) { amin.reply(ctx.msg, "Usage: /get <url>"); return; }
  deliver(ctx.chatId, url, ctx.msg);
}

function onMenuAction(ctx) {
  var url = lastByChat[ctx.chatId];
  if (!url) {
    amin.send(ctx.chatId, "No link seen in this chat yet — send one, or use /get <url>.");
    return;
  }
  deliver(ctx.chatId, url, { chatId: ctx.chatId, id: 0 });
}

function deliver(chatId, url, msg) {
  var type = guessType(url);
  var sent = false;
  if (typeof amin.sendFile === "function") {
    try {
      sent = amin.sendFile(url, { type: type, caption: "", chatId: chatId }) === true;
    } catch (e) {
      amin.log("get-file sendFile error: " + e);
      sent = false;
    }
  }
  if (!sent) {
    // Fallback: the user always gets the link, media or not.
    amin.reply(msg, "🔗 " + url);
  }
}

function guessType(url) {
  var u = url.toLowerCase().split("?")[0];
  if (/\.(mp4|mov|mkv|webm)$/.test(u)) return "video";
  if (/\.(jpg|jpeg|png|webp|gif)$/.test(u)) return "photo";
  if (/\.(mp3|ogg|m4a|flac)$/.test(u)) return "audio";
  return "file";
}
```

## Example C (v2.2, full) — a note-keeper with db, a plugin page, relay events and a notification

```js
// ==AminGramPlugin==
// @id          note-keeper
// @name        Note Keeper
// @version     1.0.0
// @author      Amin
// @description Saves notes with /note, lists them from its plugin page, and broadcasts a relay event other plugins can hear.
// @capabilities commands, send, settings, relay, ui
// @topics      notes-cleared
// ==/AminGramPlugin==

// The plugin page (settings capability): one info row + two actions.
var PLUGIN_PAGE = [
  { type: "info", text: "Your notes live in this plugin's own database." },
  { type: "action", id: "count", title: "How many notes?",
    subtitle: "Shows the count as a notification" },
  { type: "action", id: "wipe", title: "Delete all notes" }
];

function noteKeys() { return amin.db.keys("notes", "n:"); }

function onCommand(ctx) {
  if (ctx.command === "note") {
    var id = "n:" + Date.now();
    if (amin.db.put("notes", id, { text: ctx.args, chatId: ctx.chatId })) {
      amin.reply(ctx.msg, "Saved. You have " + noteKeys().length + " note(s).");
    } else {
      amin.reply(ctx.msg, "Could not save that note (too large?).");
    }
  }
}

function onPluginAction(actionId) {
  if (actionId === "count") {
    if (amin.ui) amin.ui.notify("Note Keeper",
      noteKeys().length + " note(s) saved");
  } else if (actionId === "wipe") {
    amin.db.clear("notes");
    if (amin.relay) amin.relay.emit("notes-cleared", { by: "note-keeper" });
    amin.toast("All notes deleted");
  }
}

function onEvent(topic, data) {
  // We also listen for our own topic pattern from other plugins
  // (the emitter never receives its own events).
  amin.log("event " + topic + ": " + JSON.stringify(data));
}
```

Notes on Example C: `amin.db` needs **no capability** (base
surface); `PLUGIN_PAGE` rides on `settings`; `amin.ui` /
`amin.relay` are null-checked because fields (not functions) carry
those gates; a `/note` argument so large that its JSON passes
256 KB makes `put` return `false` — handled inline instead of
throwing.

---

## Amin's plugin request

(Describe here, in your own words, what the plugin should do — then the AI
returns the complete file, ready to paste into AminGram.)
