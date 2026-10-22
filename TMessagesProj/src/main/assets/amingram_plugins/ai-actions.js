// ==AminGramPlugin==
// @id          ai-actions
// @name        AI Actions
// @version     1.1
// @author      Amin
// @description AI on any message: reply to a message with /sum to summarize it, /explain to explain it simply, or /draft to draft a reply to it — or pass text directly (/sum <text>). Uses Gemini via your connected Google account (AI Studio or Vertex AI mode, see Plugins → Google Account). (v1.1: async requests, usage stats in amin.db on the plugin page.)
// @icon        sparkles
// @menuLabel   AI: summarize
// @capabilities commands, send, http, google, menu, observe, settings
// @minPlatform 2
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "model", type: "text", title: "Gemini model (AI Studio mode)", def: "gemini-3-flash-preview" },
  { key: "vertexProject", type: "text", title: "Vertex AI project", def: "gen-lang-client-0433595679" },
  { key: "vertexLocation", type: "text", title: "Vertex AI location", def: "us-central1" },
  { key: "vertexModel", type: "text", title: "Vertex AI model", def: "gemini-2.5-flash" },
  { key: "vertexApiKey", type: "text", title: "Vertex AI API key (optional — skips Google sign-in in Vertex mode)", def: "" }
];

var PLUGIN_PAGE = [
  { type: "info", text: "AI Actions counts how often /sum, /explain, /draft and the menu summary run. Counts live in the plugin database — Clear stats zeroes them." },
  { type: "action", id: "stats", title: "Usage stats", subtitle: "Toast the totals and log the per-action breakdown" },
  { type: "action", id: "clear", title: "Clear stats", subtitle: "Zero every counter" }
];

var PROMPTS = {
  sum: "Summarize the following message concisely, in the same language as the message. Keep the key facts.",
  explain: "Explain the following message in simple, clear terms, in the same language as the message.",
  draft: "Draft a natural reply to the following message, in the same language as the message. Output only the reply text."
};

var BILLING_LINE = "Vertex needs billing enabled on the project (one console step) — or switch back to AI Studio mode.";
var SCOPE_LINE = "AI Studio mode needs a Google-side scope fix — switch to Vertex AI in Plugins → Google Account, or wait for the fix.";
var MENU_HINT = "/sum as a reply to a message — or watch this chat a while";

// Last text message observed per chat (for the attach-menu action).
// Runtime-only, capped at ~50 chats, oldest evicted first.
var lastByChat = {};
var lastOrder = [];
var LAST_CAP = 50;

// Settings read: amin.settings.get when the bridge offers it, else
// the storage form (settings live in the same per-plugin store).
function settingGet(key, def) {
  try {
    if (amin.settings && typeof amin.settings.get === "function") {
      var v = amin.settings.get(key, def);
      if (v !== undefined && v !== null) return v;
    }
  } catch (e) { /* fall through to storage */ }
  return amin.storage.get(key, def);
}

function setting(key, def) {
  var v = "";
  try {
    v = String(settingGet(key, def) || "").trim();
  } catch (e) {
    v = "";
  }
  return v ? v : def;
}

function dbOk() {
  return !!(amin.db && typeof amin.db.put === "function");
}

// --- amin.db: usage counters --------------------------------------
// v1.0 kept its only state (last-message-per-chat) in memory, so
// there are no stored counters to rescue; the totals still follow
// the lazy one-way pattern — the db record wins, and a legacy
// storage record (if an older build ever wrote one) is adopted
// once, on first read.
function emptyStats() {
  return {
    sum: { n: 0, ok: 0 }, explain: { n: 0, ok: 0 },
    draft: { n: 0, ok: 0 }, menu: { n: 0, ok: 0 }
  };
}

function loadStats() {
  if (dbOk()) {
    try {
      var s = amin.db.get("stats", "totals");
      if (s) return s;
      var legacy = amin.storage.get("aa_totals", "");
      if (legacy) {
        try {
          var parsed = JSON.parse(legacy);
          if (parsed && parsed.sum) {
            amin.db.put("stats", "totals", parsed);
            amin.storage.set("aa_totals", null);
            return parsed;
          }
        } catch (e) { /* not JSON — start fresh */ }
      }
    } catch (e) { amin.debug("ai-actions stats read failed: " + e); }
  }
  return emptyStats();
}

function recordUse(kind, ok) {
  if (!dbOk()) return;
  try {
    var s = loadStats();
    if (!s[kind]) s[kind] = { n: 0, ok: 0 };
    s[kind].n = (s[kind].n || 0) + 1;
    if (ok) s[kind].ok = (s[kind].ok || 0) + 1;
    amin.db.put("stats", "totals", s);
  } catch (e) { amin.debug("ai-actions stats write failed: " + e); }
}

// --- observation + commands (unchanged behaviour) -------------------

function onMessage(msg) {
  try {
    if (!msg || msg.outgoing) {
      return;
    }
    var text = String(msg.text || "");
    if (!text.trim()) {
      return;
    }
    if (text.trim().charAt(0) === "/") {
      return; // commands are not summarizable content
    }
    if (msg.chatId === undefined || msg.chatId === null) {
      return;
    }
    if (!lastByChat[msg.chatId]) {
      lastOrder.push(msg.chatId);
      while (lastOrder.length > LAST_CAP) {
        var old = lastOrder.shift();
        delete lastByChat[old];
      }
    }
    lastByChat[msg.chatId] = {
      text: text,
      senderName: String(msg.senderName || "")
    };
  } catch (e) {
    amin.log("ai-actions observe error: " + e);
  }
}

// Source resolution: (1) the message this command replies to
// (ctx.replyTo — text, else caption), (2) the command's own args,
// (3) nothing → the caller replies with usage.
function resolveSource(ctx) {
  if (ctx.replyTo) {
    var body = String(ctx.replyTo.text || "").trim()
      || String(ctx.replyTo.caption || "").trim();
    if (body) {
      return { text: body, senderName: String(ctx.replyTo.senderName || "") };
    }
  }
  var args = String(ctx.args || "").trim();
  if (args) {
    return { text: args, senderName: "" };
  }
  return null;
}

function onCommand(ctx) {
  var cmd = String(ctx.command || "").toLowerCase();
  if (cmd !== "sum" && cmd !== "explain" && cmd !== "draft") {
    return;
  }
  try {
    var src = resolveSource(ctx);
    if (!src) {
      amin.reply(ctx.msg, "Usage: reply to a message with /" + cmd
        + ", or /" + cmd + " <text>\n\n"
        + "• /sum — summarize\n• /explain — explain simply\n• /draft — draft a reply");
      return;
    }
    var post = function (t) { amin.reply(ctx.msg, t); };
    runAi(cmd, src, post);
  } catch (e) {
    amin.log("ai-actions error: " + e);
    try {
      amin.reply(ctx.msg, "⚠️ AI action failed — see the plugin log.");
    } catch (e2) {
      // Nothing sensible left to do; the error is in the log.
    }
  }
}

function onMenuAction(ctx) {
  try {
    var known = lastByChat[ctx.chatId];
    if (!known || !String(known.text || "").trim()) {
      amin.send(ctx.chatId, MENU_HINT);
      return;
    }
    var post = function (t) { amin.send(ctx.chatId, t); };
    runAi("menu", { text: known.text, senderName: known.senderName || "" }, post);
  } catch (e) {
    amin.log("ai-actions menu error: " + e);
  }
}

function buildPrompt(kind, src) {
  var block = src.senderName ? ("From " + src.senderName + ":\n" + src.text) : src.text;
  return PROMPTS[kind === "menu" ? "sum" : kind] + "\n\n" + block;
}

function googleMode() {
  try {
    if (amin.google && typeof amin.google.getMode === "function") {
      var m = String(amin.google.getMode() || "").toLowerCase();
      if (m === "vertex" || m === "aistudio") {
        return m;
      }
    }
  } catch (e) {
    // Fall through to the default mode.
  }
  return "aistudio";
}

function lastAuthError() {
  try {
    if (amin.google && typeof amin.google.getLastAuthError === "function") {
      return String(amin.google.getLastAuthError() || "");
    }
  } catch (e) {
    // No error surface on this bridge — treated as "no detail".
  }
  return "";
}

// Same auth pattern as gemini-chat: in Vertex mode with a
// vertexApiKey set, the key authenticates on its own — sign-in and
// token fetch are never touched. Otherwise, when not signed in,
// signIn opens the native picker and the pending request continues
// in its callback; when signed in, the token is fetched and the
// request runs inside that callback.
function runAi(kind, src, post) {
  var prompt = buildPrompt(kind, src);
  var mode = googleMode();
  var vertexKey = (mode === "vertex") ? setting("vertexApiKey", "") : "";
  if (mode === "vertex" && vertexKey) {
    doRequest(kind, mode, prompt, null, vertexKey, post);
    return;
  }
  if (!amin.google) {
    post("⚠️ The Google bridge is not available in this build.");
    return;
  }
  if (!amin.google.isSignedIn()) {
    amin.google.signIn(function (ok) {
      if (ok) {
        fetchToken(kind, mode, prompt, post);
      } else {
        post("⚠️ Google sign-in was cancelled or failed — AI actions need "
          + "your Google account. You can also connect it in Plugins → Google Account.");
      }
    });
    return;
  }
  fetchToken(kind, mode, prompt, post);
}

function fetchToken(kind, mode, prompt, post) {
  amin.google.getAccessToken(function (token) {
    try {
      if (!token) {
        if (mode === "aistudio" && lastAuthError().indexOf("invalid_scope") !== -1) {
          post(SCOPE_LINE);
        } else {
          post("⚠️ Could not get a Google token. Reconnect your account "
            + "in Plugins → Google Account, then try again.");
        }
        return;
      }
      doRequest(kind, mode, prompt, token, "", post);
    } catch (e) {
      amin.log("ai-actions token error: " + e);
      try {
        post("⚠️ AI action failed — see the plugin log.");
      } catch (e2) {
        // Logged above.
      }
    }
  });
}

// The request itself is async (v1.1): the callback parses the
// response, records the outcome, and posts — posting is the last
// substantive action of the callback. (Async POST, like the sync
// form, delivers the error body on a non-2xx status and body null
// on transport failure.)
function doRequest(kind, mode, prompt, token, vertexKey, post) {
  var url;
  var headers;
  if (mode === "vertex") {
    var loc = setting("vertexLocation", "us-central1");
    var proj = setting("vertexProject", "gen-lang-client-0433595679");
    var vmodel = setting("vertexModel", "gemini-2.5-flash");
    url = "https://" + loc + "-aiplatform.googleapis.com/v1/projects/" + proj
      + "/locations/" + loc + "/publishers/google/models/"
      + encodeURIComponent(vmodel) + ":generateContent";
    if (vertexKey) {
      headers = { "x-goog-api-key": vertexKey, "Content-Type": "application/json" };
    } else {
      headers = { "Authorization": "Bearer " + token, "Content-Type": "application/json" };
    }
  } else {
    var model = setting("model", "gemini-3-flash-preview");
    url = "https://generativelanguage.googleapis.com/v1beta/models/"
      + encodeURIComponent(model) + ":generateContent";
    headers = { "Authorization": "Bearer " + token, "Content-Type": "application/json" };
  }
  var body = JSON.stringify({ contents: [{ parts: [{ text: prompt }] }] });
  amin.httpPostAsync(url, body, headers, function (res) {
    try {
      var response = res && res.body ? res.body : null;
      if (!response) {
        recordUse(kind, false);
        post("⚠️ Gemini did not answer — check the connection, and "
          + "that the model name in the plugin settings is right.");
        return;
      }
      var apiError = extractError(response);
      if (apiError) {
        recordUse(kind, false);
        if (mode === "vertex" && isBillingError(response)) {
          post(BILLING_LINE);
        } else {
          post("⚠️ Gemini error: " + apiError);
        }
        return;
      }
      var answer = extractText(response);
      if (!answer) {
        recordUse(kind, false);
        post("⚠️ Gemini answered, but the reply could not be read "
          + "(it may have been blocked by a safety filter).");
        return;
      }
      recordUse(kind, true);
      post(answer);
    } catch (e) {
      amin.log("ai-actions request error: " + e);
      try {
        post("⚠️ AI action failed — see the plugin log.");
      } catch (e2) {
        // Logged above.
      }
    }
  });
}

// A Vertex 403 whose error mentions billing gets the plain-words
// line instead of the raw API message. (httpPost returns the error
// body, not the status — the body's error.code carries the 403.)
function isBillingError(response) {
  try {
    var data = JSON.parse(response);
    if (data && data.error) {
      var msg = String(data.error.message || "").toLowerCase();
      if (msg.indexOf("billing") === -1) {
        return false;
      }
      var code = data.error.code;
      return code === undefined || code === null || Number(code) === 403;
    }
    return false;
  } catch (e) {
    return false;
  }
}

// Gemini error shape: { error: { code, message, status } } — surface
// the message so a rejected token/scope is self-diagnosing.
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

// candidates[0].content.parts[*].text, defensively: any missing
// level (safety block, empty answer) yields "".
function extractText(response) {
  try {
    var data = JSON.parse(response);
    if (!data || !data.candidates || !data.candidates.length) {
      return "";
    }
    var content = data.candidates[0].content;
    if (!content || !content.parts) {
      return "";
    }
    var out = "";
    for (var i = 0; i < content.parts.length; i++) {
      if (content.parts[i] && content.parts[i].text) {
        out += content.parts[i].text;
      }
    }
    return out.trim();
  } catch (e) {
    return "";
  }
}

// --- plugin page ----------------------------------------------------

function onPluginAction(actionId) {
  try {
    if (actionId === "stats") {
      var s = loadStats();
      var total = 0, okTotal = 0, lines = [], kinds = ["sum", "explain", "draft", "menu"];
      for (var i = 0; i < kinds.length; i++) {
        var k = kinds[i], rec = s[k] || { n: 0, ok: 0 };
        total += rec.n || 0;
        okTotal += rec.ok || 0;
        lines.push(k + ": " + (rec.ok || 0) + "/" + (rec.n || 0) + " ok");
      }
      amin.log("ai-actions usage — " + lines.join(" · "));
      amin.toast("🤖 AI Actions: " + okTotal + "/" + total + " runs succeeded — details in the log");
      return;
    }
    if (actionId === "clear") {
      if (dbOk()) {
        try { amin.db.del("stats", "totals"); } catch (e) { /* best effort */ }
      }
      amin.toast("🗑 AI Actions stats cleared");
      amin.log("ai-actions: usage counters cleared from the plugin page");
    }
  } catch (e) { amin.log("ai-actions onPluginAction error: " + e); }
}
