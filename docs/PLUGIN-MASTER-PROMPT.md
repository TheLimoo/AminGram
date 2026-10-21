# AminGram Plugin — Master Prompt (v2.1)

Give this whole document to an AI (or paste it at the start of a chat) and then
describe the plugin you want in one or two sentences. The AI must answer with
ONE JavaScript file and nothing else. Install it in AminGram:
**Settings → AminGram → Plugins → ➕ Add plugin** (pick the file, or paste the code).

This is the v2.1 contract (Plugin Platform v2, Phase A). It matches the
v2.1 runtime exactly. New in v2.1: the rich message object + lazy lookups
(`amin.getChatInfo` / `getUserInfo` / `getMessage`), the `act` capability
(`amin.editMessage` / `amin.react`), async HTTP (`amin.httpGetAsync` /
`amin.httpPostAsync`), storage v2 (JSON + namespaces), settings schema v2
(number / slider / select / header / dependsOn), lifecycle hooks
(`onEnable` / `onDisable` / `onSettingsChanged` / `onMigrate`), leveled
logging, `amin.platform` feature detection, manifest `@minPlatform` /
`@dataVersion`, and dual-mode Gemini (AI Studio / Vertex AI) for the
`google` capability. Everything from v2.0 still holds: the `file`
capability + `amin.sendFile`, HTTP error bodies from `amin.httpPost` (with
the canonical `extractError` helper), and the attach-menu fields `@icon` /
`@menuLabel`.

---

You are writing a plugin for **AminGram**, a modified Telegram Android client.
Output exactly ONE JavaScript file. No explanations, no markdown fences around
it, no extra files.

## File format

The file starts with a manifest comment block. `@id`, `@name`, `@version`,
`@author`, `@description` and `@capabilities` are required; `@icon`,
`@menuLabel`, `@minPlatform` and `@dataVersion` are optional:

```js
// ==AminGramPlugin==
// @id          shout
// @name        Shout
// @version     1.0
// @author      Amin
// @description Turns /shout <text> into a loud uppercase message.
// @capabilities commands, send
// @icon        sparkles
// @menuLabel   Shout it
// @minPlatform 2
// @dataVersion 1
// ==/AminGramPlugin==
```

- `@id`: lowercase letters, digits and `-` only. Unique. Never changes between versions.
- `@capabilities`: comma list of what the plugin uses, from:
  `observe` (watch messages), `commands` (react to /commands), `send` (post messages),
  `settings` (has its own settings), `http` (uses amin.httpGet / amin.httpPost and the async forms),
  `menu` (appears as a button in the chat attach menu — see onMenuAction),
  `google` (uses amin.google — the user's connected Google account),
  `file` (uses amin.sendFile — download a URL and post it as real media, v2.0),
  `act` (uses amin.editMessage / amin.react — act on messages, v2.1).
  An unknown capability makes the install invalid — never invent one.
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
- **There is NO delete and NO pin.** Plugins cannot delete or pin
  messages — that surface does not exist in v2.1 (it is reserved for a
  later phase). Do not invent `amin.deleteMessage` / `amin.pinMessage`.

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
  back ON the plugin thread, inside the usual per-hook isolation and
  budget. Headers may be `null`. Same 15 s timeouts and 1 MB body cap
  as the sync forms.

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
- It BLOCKS the plugin thread while downloading. Call it once per user
  action, never from a hot `onMessage` path for every message.
- **Fallback rule (canonical pattern):** if `sendFile` returns `false`
  (or is unavailable), post the plain link with `amin.reply` /
  `amin.send` instead, so the user always gets something. The bundled
  media-dl works exactly this way: real media first, link fallback.

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
  { type: "header", title: "Advanced" },
  { key: "apiKey", type: "text", title: "API key", def: "",
    dependsOn: { key: "loud", equals: true } }
];
```

Six row types: **`bool`**, **`text`**, **`number`**, **`slider`**,
**`select`**, **`header`**. The hub builds the settings page from this
automatically. Read values with `amin.storage.get(key, def)` — pass a
default of the right type (bool def → boolean back, number/slider def →
number back, text/select → string) and trim `text` values before use —
mobile keyboards add trailing spaces.

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
number/slider, and a duplicate `key`. Reinstalling an EXISTING id is
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
- **Per-hook budget:** each hook invocation gets about **5 seconds of
  JS execution**. A runaway loop (or pathological regex) that exceeds
  it is killed mid-hook — that hook invocation dies with a "hook
  budget exceeded" log line; the plugin itself keeps running and the
  next hook gets a fresh budget. Time spent inside a blocking native
  bridge call (e.g. a sync `httpGet`) is not JS execution; the budget
  trips at the next JS instruction after the deadline. Keep hooks
  fast regardless: `onMessage` runs for every message.

## Feature detection — `amin.platform` (v2.1)

```js
amin.platform.apiVersion            // 2 in this build
amin.platform.has("act")            // true when the named feature exists
```

Feature names in this build — exactly these twelve:
`rich-msg`, `lazy-lookup`, `act`, `http-async`, `storage-json`,
`settings-v2`, `lifecycle`, `watchdog`, `platform`, `sendFile`,
`http-headers`, `reply-context`.
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
   top instead of guessing. (In particular: no message delete, no pin.)
5. Check structural gates defensively where a fallback exists:
   `typeof amin.sendFile === "function"` before relying on it, and
   always provide the link fallback when it is missing or returns false.
   The same applies to `amin.editMessage` / `amin.react` (`act`),
   `amin.google` (`google`), and new-in-v2.1 surfaces via
   `amin.platform.has(...)`.
6. Prefer the async HTTP forms over the blocking ones, and never store
   OAuth tokens in `amin.storage`.

## Bundled plugins (reference implementations)

These ship inside AminGram and pre-install; their sources are the best
worked examples of the surfaces above (12 in v2.1):

- **shout** — the Example 1 plugin (`commands`, `send`).
- **vip-alert** — the Example 2 plugin (`observe`, `settings`).
- **media-dl** — TikTok / X / Reddit / Instagram downloader; real media
  via `sendFile` first, link fallback (`observe`, `commands`, `send`,
  `http`, `menu`, `file`).
- **temp-mail** — disposable mailboxes on **mail.tm** (rewritten in
  v2.0; the old 1secmail backend is dead): `/tm new` creates a real
  account + bearer token kept in plugin storage, `/tm inbox` /
  `/tm read <id>` use authenticated GETs via the
  `amin.httpGet(url, headers)` overload, re-authenticating once and
  retrying once on failure.
- **aesthetic-text** — /wide, /space, /fliptext (`commands`, `send`, `menu`).
- **quick-translate** — /tr via the keyless gtx endpoint
  (`commands`, `send`, `http`, `settings`).
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

---

## Amin's plugin request

(Describe here, in your own words, what the plugin should do — then the AI
returns the complete file, ready to paste into AminGram.)
