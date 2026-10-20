# AminGram Plugin — Master Prompt (v2.0)

Give this whole document to an AI (or paste it at the start of a chat) and then
describe the plugin you want in one or two sentences. The AI must answer with
ONE JavaScript file and nothing else. Install it in AminGram:
**Settings → AminGram → Plugins → ➕ Add plugin** (pick the file, or paste the code).

This is the v2.0 contract. It matches the v2.0 runtime exactly: the new
`file` capability + `amin.sendFile`, HTTP error bodies from `amin.httpPost`
(with the canonical `extractError` helper), the attach-menu customization
fields `@icon` / `@menuLabel`, and the `google` capability recap.

---

You are writing a plugin for **AminGram**, a modified Telegram Android client.
Output exactly ONE JavaScript file. No explanations, no markdown fences around
it, no extra files.

## File format

The file starts with a manifest comment block. `@id`, `@name`, `@version`,
`@author`, `@description` and `@capabilities` are required; `@icon` and
`@menuLabel` are optional (attach menu, v2.0):

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
// ==/AminGramPlugin==
```

- `@id`: lowercase letters, digits and `-` only. Unique. Never changes between versions.
- `@capabilities`: comma list of what the plugin uses, from:
  `observe` (watch messages), `commands` (react to /commands), `send` (post messages),
  `settings` (has its own settings), `http` (uses amin.httpGet / amin.httpPost),
  `menu` (appears as a button in the chat attach menu — see onMenuAction),
  `google` (uses amin.google — the user's connected Google account),
  `file` (uses amin.sendFile — download a URL and post it as real media, v2.0).
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

## Runtime

Plain JavaScript (ES5 + most ES6: `let/const`, arrow functions, template literals,
`Array.map/filter`, `String.includes`). No modules, no `require`, no DOM, no
`fetch`. **No polling and no timers**: plugins are purely event-driven —
react inside the hooks below; never schedule recurring work. The plugin
runs inside the app on its own thread: code that throws is caught
**per hook** and written to the plugin's log — it can never crash AminGram
or break another plugin (per-hook isolation).

## The `amin` bridge (the ONLY way to touch the app)

```js
amin.log(text)                      // write to the plugin's log (hub → plugin → Log)
amin.toast(text)                    // small popup on screen
amin.storage.get(key, defaultValue) // per-plugin saved values (string/number/bool)
amin.storage.set(key, value)
amin.send(chatId, text)             // send a message AS THE USER to a chat
amin.reply(msg, text)               // send a message into the same chat the msg came from
amin.httpGet(url)                   // returns response text, or null on ANY failure
                                    // (including an HTTP error status) — blocking: call sparingly
amin.httpPost(url, body, headers)   // POST: body is a string, headers a {name: value} object;
                                    // returns the response text on 2xx; on a non-2xx status
                                    // returns the ERROR BODY instead (read the server's
                                    // verdict — see extractError below); null only when the
                                    // request itself failed (blocking)
amin.sendFile(url, opts)            // `file` capability ONLY — see next section
```

`amin.httpGet` is unchanged from v1: null on failure, never an error body.
Only `amin.httpPost` surfaces error bodies (v1.3.7+, canonical in v2.0).
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

## Sending files (`file` capability only, v2.0)

If the manifest does NOT declare the `file` capability, `amin.sendFile`
does not exist at all (`typeof amin.sendFile === "undefined"`) — the gate
is structural, exactly like `amin.google`. With the capability:

```js
var ok = amin.sendFile(url, opts)   // SYNCHRONOUS — returns a boolean
// opts = { type: "video" | "photo" | "audio" | "file",
//          caption: "text shown with the media",   // optional
//          chatId: 123456 }                        // optional — defaults
//                                                     // to the chat the
//                                                     // current hook is
//                                                     // running in
```

- `sendFile` downloads the URL itself (you never download anything) and
  posts it into the chat as **real media** through Telegram's own send
  pipeline, with the caption. `true` means the file was downloaded and
  handed to the send pipeline (Telegram uploads it from there).
- Limits: **200 MB hard cap**, 15 s connect timeout / 60 s read timeout,
  redirects followed. Any failure — bad URL, HTTP error, over the cap,
  no target chat, send hand-off failed — returns `false`; it never
  throws across the bridge (still wrap it in try/catch, belt and braces).
- It BLOCKS the plugin thread while downloading. Call it once per user
  action, never from a hot `onMessage` path for every message.
- **Fallback rule (canonical pattern):** if `sendFile` returns `false`
  (or is unavailable), post the plain link with `amin.reply` /
  `amin.send` instead, so the user always gets something. The bundled
  media-dl v2 works exactly this way: real media first, link fallback.

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
  (Google Cloud Platform + email — since v1.3.6 the app requests
  cloud-platform, because Google's console refuses to register the
  narrower generative-language scope; the Gemini API accepts
  cloud-platform tokens). Send it as
  `Authorization: "Bearer " + token` with `amin.httpPost`.
- Tokens live about an hour; `getAccessToken` silently refreshes them.
  Never store a token in `amin.storage` — always fetch a fresh one.
- Google may occasionally demand an interactive re-consent
  (unverified app): then `getAccessToken` returns null and `signIn`
  is the way back.

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
  post your result with amin.send(ctx.chatId, …) / amin.reply({chatId: ctx.chatId, id: 0}, …).
  Button label/icon come from @menuLabel/@icon unless the user overrode
  them on the hub's Menu plugins page. */ }
```

## Settings (optional)

```js
var SETTINGS = [
  { key: "loud", type: "bool", title: "Extra loud", def: false },
  { key: "suffix", type: "text", title: "Suffix to add", def: "!" }
];
```

Only two setting types exist: **`bool`** and **`text`**. There are no
number, select or slider settings — model numeric input as `text` and
parse/validate it yourself. The hub builds the settings page from this
automatically. Read values with `amin.storage.get("loud", false)` etc.
(and trim `text` values before use — mobile keyboards add trailing
spaces).

## Rules

1. One file, everything inside it. Wrap risky code in try/catch.
2. Never loop forever, never busy-wait, never poll, never use timers.
   Keep `onMessage` fast — it runs for every message; do heavy work
   only when the message actually matches.
3. Sending messages posts AS THE USER. Only send in direct reaction to a
   command or a message the user clearly configured the plugin to answer.
4. Never invent bridge functions or capabilities. If a capability is not
   listed above, the plugin cannot do it — say so in a comment at the
   top instead of guessing.
5. Check structural gates defensively where a fallback exists:
   `typeof amin.sendFile === "function"` before relying on it, and
   always provide the link fallback when it is missing or returns false.

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

## Example A (v2.0, full) — an OAuth AI command (`google` + `httpPost` + `extractError`)

A complete `/ai <question>` plugin: it uses the user's connected Google
account (no API key), continues inside the async token callback, posts
with a Bearer header, and surfaces Google's real error via the
canonical `extractError` (possible only because `httpPost` returns
error bodies on non-2xx).

```js
// ==AminGramPlugin==
// @id          ai-ask
// @name        AI Ask
// @version     1.0
// @author      Amin
// @description /ai <question> asks Gemini with your connected Google account (no API key).
// @capabilities commands, send, http, google, settings
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "model", type: "text", title: "Gemini model", def: "gemini-3-flash-preview" }
];

function onCommand(ctx) {
  if (ctx.command !== "ai") return;
  var prompt = (ctx.args || "").trim();
  if (!prompt) { amin.reply(ctx.msg, "Usage: /ai <question>"); return; }
  if (!amin.google.isSignedIn()) {
    amin.google.signIn(function (ok) {
      if (ok) { askGemini(ctx, prompt); }
      else { amin.reply(ctx.msg, "Google sign-in was cancelled — connect an account in Plugins → Google Account."); }
    });
    return;
  }
  askGemini(ctx, prompt);
}

function askGemini(ctx, prompt) {
  amin.google.getAccessToken(function (token) {
    if (!token) {
      amin.reply(ctx.msg, "⚠️ No Google token — reconnect in Plugins → Google Account.");
      return;
    }
    try {
      var model = String(amin.storage.get("model", "gemini-3-flash-preview") || "").trim()
        || "gemini-3-flash-preview";
      var url = "https://generativelanguage.googleapis.com/v1beta/models/"
        + encodeURIComponent(model) + ":generateContent";
      var body = JSON.stringify({ contents: [{ parts: [{ text: prompt }] }] });
      var response = amin.httpPost(url, body, {
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json"
      });
      if (!response) {
        amin.reply(ctx.msg, "⚠️ Gemini did not answer — check the connection and the model name.");
        return;
      }
      var apiError = extractError(response);
      if (apiError) {
        amin.reply(ctx.msg, "⚠️ Gemini error: " + apiError);
        return;
      }
      var answer = extractText(response);
      if (!answer) {
        amin.reply(ctx.msg, "⚠️ Gemini answered, but the reply could not be read (safety filter?).");
        return;
      }
      amin.reply(ctx.msg, answer);
    } catch (e) {
      amin.log("ai-ask error: " + e);
      amin.reply(ctx.msg, "⚠️ AI Ask failed — see the plugin log.");
    }
  });
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
same pattern bundled media-dl v2 uses.

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
