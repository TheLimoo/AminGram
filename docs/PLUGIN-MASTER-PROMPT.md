# AminGram Plugin — Master Prompt

Give this whole document to an AI (or paste it at the start of a chat) and then
describe the plugin you want in one or two sentences. The AI must answer with
ONE JavaScript file and nothing else. Install it in AminGram:
**Settings → AminGram → Plugins → ➕ Add plugin** (pick the file, or paste the code).

---

You are writing a plugin for **AminGram**, a modified Telegram Android client.
Output exactly ONE JavaScript file. No explanations, no markdown fences around
it, no extra files.

## File format

The file starts with a manifest comment block. Every field is required:

```js
// ==AminGramPlugin==
// @id          shout
// @name        Shout
// @version     1.0
// @author      Amin
// @description Turns /shout <text> into a loud uppercase message.
// @capabilities commands, send
// ==/AminGramPlugin==
```

- `@id`: lowercase letters, digits and `-` only. Unique. Never changes between versions.
- `@capabilities`: comma list of what the plugin uses, from:
  `observe` (watch messages), `commands` (react to /commands), `send` (post messages),
  `settings` (has its own settings), `http` (uses amin.httpGet / amin.httpPost),
  `menu` (appears as a button in the chat attach menu — see onMenuAction),
  `google` (uses amin.google — the user's connected Google account).

## Runtime

Plain JavaScript (ES5 + most ES6: `let/const`, arrow functions, template literals,
`Array.map/filter`, `String.includes`). No modules, no `require`, no DOM, no
`fetch`, no timers shorter than 5 seconds. The plugin runs inside the app:
code that throws is caught and logged — it can never crash AminGram.

## The `amin` bridge (the ONLY way to touch the app)

```js
amin.log(text)                      // write to the plugin's log (hub → plugin → Log)
amin.toast(text)                    // small popup on screen
amin.storage.get(key, defaultValue) // per-plugin saved values (string/number/bool)
amin.storage.set(key, value)
amin.send(chatId, text)             // send a message AS THE USER to a chat
amin.reply(msg, text)               // send a message into the same chat the msg came from
amin.httpGet(url)                   // returns response text, or null on failure (blocking: call sparingly)
amin.httpPost(url, body, headers)   // POST: body is a string, headers a {name: value} object;
                                    // returns response text, or null on failure (blocking)
```

## Google account (`google` capability only)

If the manifest does NOT declare the `google` capability, `amin.google`
does not exist at all (`typeof amin.google === "undefined"`). With the
capability, the plugin can use the Google account the user connected in
**Plugins → Google Account** — an OAuth access token, never an API key:

```js
amin.google.isSignedIn()            // true while an account is connected
amin.google.getAccessToken(fn)      // ASYNC: fn(token) — token is a string, or null
amin.google.signIn(fn)              // ASYNC: fn(true/false) — opens the native
                                    // Google account picker / consent screen
```

- Both async calls take a **callback** — they never return the value
  directly, and the callback fires later (on the plugin's own thread),
  possibly after the user finishes a consent screen. Structure code as
  continuations: everything that needs the token goes INSIDE the
  getAccessToken callback.
- Typical pattern: if `!amin.google.isSignedIn()`, call
  `amin.google.signIn(function (ok) { … })` and continue inside it when
  `ok` is true; otherwise tell the user once, cleanly.
- The token authorizes Google APIs for the scopes the app requests
  (Generative Language / Gemini + email). Send it as
  `Authorization: "Bearer " + token` with `amin.httpPost`.
- Tokens live about an hour; `getAccessToken` silently refreshes them.
  Never store a token in `amin.storage` — always fetch a fresh one.
- Google may occasionally demand an interactive re-consent
  (unverified app): then `getAccessToken` returns null and `signIn`
  is the way back.
```

## Hooks — define the ones you need

```js
function onLoad() { /* runs once when the plugin is enabled */ }

function onMessage(msg) { /* runs for every message the app processes */ }
// msg = { id, chatId, senderId, senderName, text, outgoing (bool),
//         channel (bool), date (unix seconds) }
// Return true to mark the message "handled" (stops later plugins), or nothing.

function onCommand(ctx) { /* runs when the USER sends a message starting with / */ }
// ctx = { chatId, senderId, command ("shout"), args ("hello world"), msg }
// A plugin CANNOT edit or unsend the command message itself — it reacts,
// usually by sending a new message with amin.reply/amin.send.

function onMenuAction(ctx) { /* runs when the user taps this plugin's button in
  the chat attach menu (next to Gallery / File / Poll …). Requires the `menu`
  capability. ctx = { chatId }. The user is IN that chat — react immediately:
  use the chat's context (e.g. the last link your onMessage remembered) or
  post your result with amin.send(ctx.chatId, …) / amin.reply({chatId: ctx.chatId, id: 0}, …). */ }
```

## Settings (optional)

```js
var SETTINGS = [
  { key: "loud", type: "bool", title: "Extra loud", def: false },
  { key: "suffix", type: "text", title: "Suffix to add", def: "!" }
];
```

The hub builds the settings page from this automatically.
Read values with `amin.storage.get("loud", false)` etc.

## Rules

1. One file, everything inside it. Wrap risky code in try/catch.
2. Never loop forever, never busy-wait. Keep `onMessage` fast — it runs for
   every message; do heavy work only when the message actually matches.
3. Sending messages posts AS THE USER. Only send in direct reaction to a
   command or a message the user clearly configured the plugin to answer.
4. Never invent bridge functions. If a capability is not listed above, the
   plugin cannot do it — say so in a comment at the top instead of guessing.

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

---

## Amin's plugin request

(Describe here, in your own words, what the plugin should do — then the AI
returns the complete file, ready to paste into AminGram.)
