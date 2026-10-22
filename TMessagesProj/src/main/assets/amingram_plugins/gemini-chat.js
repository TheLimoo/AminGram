// ==AminGramPlugin==
// @id          gemini-chat
// @name        Gemini Chat
// @version     1.1
// @author      Amin
// @description /ai <question> asks Gemini, using your connected Google account (no API key). Dual mode: AI Studio or Vertex AI (Plugins → Google Account). (v1.1: async requests, plugin page with connection status, question history in amin.db.)
// @capabilities commands, send, http, google, settings, menu
// @minPlatform 2
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "model", type: "text", title: "Gemini model (AI Studio mode)", def: "gemini-3-flash-preview" },
  { key: "systemPrompt", type: "text", title: "System prompt (prepended, optional)", def: "" },
  { key: "vertexProject", type: "text", title: "Vertex AI: Google Cloud project", def: "gen-lang-client-0433595679" },
  { key: "vertexLocation", type: "text", title: "Vertex AI: location", def: "us-central1" },
  { key: "vertexModel", type: "text", title: "Vertex AI: model", def: "gemini-2.5-flash" },
  { key: "vertexApiKey", type: "text", title: "Vertex AI: API key (optional; skips Google sign-in)", def: "" }
];

var PLUGIN_PAGE = [
  { type: "info", text: "Gemini Chat answers /ai with your connected Google account (or a Vertex API key). Questions are counted and the recent ones kept in the plugin database — Clear wipes both." },
  { type: "action", id: "status", title: "Connection status", subtitle: "Mode, sign-in state and active model" },
  { type: "action", id: "history", title: "Recent questions", subtitle: "Toast the totals and log the last few questions" },
  { type: "action", id: "clear", title: "Clear history", subtitle: "Delete the question history and counters" }
];

var HISTORY_CAP = 50;
var historySeq = 0;

// Settings read: amin.settings.get when the bridge offers it, else
// the storage form (settings live in the same per-plugin store).
function settingGet(key, def) {
  try {
    if (amin.settings && typeof amin.settings.get === "function") {
      var v = amin.settings.get(key, def);
      return v === undefined || v === null ? def : v;
    }
  } catch (e) { /* fall through to storage */ }
  return amin.storage.get(key, def);
}

// Stored settings are trimmed: a trailing space from a mobile
// keyboard turns a model name into a 404. An emptied field falls
// back to its declared default.
function readSetting(key, def) {
  var value = String(settingGet(key, def) || "").trim();
  return value ? value : def;
}

function dbOk() {
  return !!(amin.db && typeof amin.db.put === "function");
}

// --- amin.db: question history + counters -------------------------
// v1.0 kept nothing but settings, so there is no legacy history to
// rescue; the counters still follow the lazy one-way pattern —
// the db record wins, and a legacy storage counter (if an older
// build ever wrote one) is adopted once, on first read.
function loadStats() {
  if (dbOk()) {
    try {
      var s = amin.db.get("stats", "totals");
      if (s) return s;
      var legacy = amin.storage.get("gc_totals", "");
      if (legacy) {
        try {
          var parsed = JSON.parse(legacy);
          if (parsed && typeof parsed.asked === "number") {
            amin.db.put("stats", "totals", parsed);
            amin.storage.set("gc_totals", null);
            return parsed;
          }
        } catch (e) { /* not JSON — start fresh */ }
      }
    } catch (e) { amin.debug("gemini-chat stats read failed: " + e); }
  }
  return { asked: 0, answered: 0 };
}

function saveStats(s) {
  if (!dbOk()) return;
  try { amin.db.put("stats", "totals", s); } catch (e) { /* best effort */ }
}

function recordQuestion(prompt, mode, ok) {
  var s = loadStats();
  s.asked = (s.asked || 0) + 1;
  if (ok) s.answered = (s.answered || 0) + 1;
  saveStats(s);
  if (!dbOk()) return;
  try {
    historySeq++;
    var at = new Date().getTime();
    var key = "q:" + at + "-" + historySeq;
    amin.db.put("history", key, {
      q: String(prompt || "").substring(0, 200),
      mode: mode, ok: !!ok, at: at
    });
    var keys = amin.db.keys("history", "q:");
    if (keys && keys.length > HISTORY_CAP) {
      for (var i = 0; i < keys.length - HISTORY_CAP; i++) {
        amin.db.del("history", keys[i]);
      }
    }
  } catch (e) { amin.debug("gemini-chat history write failed: " + e); }
}

function recentQuestions(n) {
  var out = [];
  if (!dbOk()) return out;
  try {
    var keys = amin.db.keys("history", "q:");
    if (!keys) return out;
    for (var i = keys.length - 1; i >= 0 && out.length < n; i--) {
      var rec = amin.db.get("history", keys[i]);
      if (rec) out.push(rec);
    }
  } catch (e) { /* best effort */ }
  return out;
}

// --- command flow (unchanged behaviour, async transport) ----------

function onCommand(ctx) {
  if (String(ctx.command || "").toLowerCase() !== "ai") {
    return;
  }
  try {
    var prompt = (ctx.args || "").trim();
    if (!prompt) {
      amin.reply(ctx.msg, "Usage: /ai <your question>\n\n"
        + "Ask Gemini anything — it answers with your connected Google "
        + "account (Plugins → Google Account), in AI Studio or Vertex AI "
        + "mode. Example: /ai explain black holes simply");
      return;
    }
    ask(ctx, prompt);
  } catch (e) {
    amin.log("gemini-chat error: " + e);
    try {
      amin.reply(ctx.msg, "⚠️ Gemini failed — see the plugin log.");
    } catch (e2) {
      // Nothing sensible left to do; the error is in the log.
    }
  }
}

function onMenuAction(ctx) {
  try {
    amin.send(ctx.chatId, "🤖 Gemini is here: send /ai <your question> in any chat — "
      + "it answers with your own Google account, no API key.");
  } catch (e) {
    amin.log("gemini-chat menu error: " + e);
  }
}

function apiMode() {
  return (amin.google.getMode && amin.google.getMode()) || "aistudio";
}

// If the account is not connected yet, amin.google.signIn opens the
// native Google picker; the pending question continues in the
// callback once the user finishes. Exception: Vertex mode with an
// API key set needs no account at all — the key IS the credential,
// so no sign-in surface is touched.
function ask(ctx, prompt) {
  if (!amin.google) {
    amin.reply(ctx.msg, "⚠️ The Google bridge is not available in this build.");
    return;
  }
  if (apiMode() === "vertex" && readSetting("vertexApiKey", "")) {
    fetchAndAnswer(ctx, prompt);
    return;
  }
  if (!amin.google.isSignedIn()) {
    amin.google.signIn(function (ok) {
      if (ok) {
        fetchAndAnswer(ctx, prompt);
      } else {
        amin.reply(ctx.msg, "⚠️ Google sign-in was cancelled or failed — /ai needs "
          + "your Google account. You can also connect it in Plugins → Google Account.");
      }
    });
    return;
  }
  fetchAndAnswer(ctx, prompt);
}

function fetchAndAnswer(ctx, prompt) {
  var mode = apiMode();
  if (mode === "vertex") {
    var vertexKey = readSetting("vertexApiKey", "");
    if (vertexKey) {
      // Key auth: getAccessToken / signIn are never called.
      try {
        vertexRequest(ctx, prompt, null, vertexKey);
      } catch (e) {
        answerError(ctx, e);
      }
      return;
    }
  }
  amin.google.getAccessToken(function (token) {
    try {
      if (!token) {
        if (mode !== "vertex") {
          var authError = (amin.google.getLastAuthError
            && amin.google.getLastAuthError()) || "";
          if (authError.indexOf("invalid_scope") >= 0) {
            amin.log("gemini-chat auth error: " + authError);
            amin.reply(ctx.msg, "⚠️ AI Studio mode needs a Google-side scope fix — "
              + "switch to Vertex AI in Plugins → Google Account, or wait for the fix.");
            return;
          }
        }
        amin.reply(ctx.msg, "⚠️ Could not get a Google token. Reconnect your account "
          + "in Plugins → Google Account, then try again.");
        return;
      }
      if (mode === "vertex") {
        vertexRequest(ctx, prompt, token, "");
      } else {
        aistudioRequest(ctx, prompt, token);
      }
    } catch (e) {
      answerError(ctx, e);
    }
  });
}

function answerError(ctx, e) {
  amin.log("gemini-chat answer error: " + e);
  try {
    amin.reply(ctx.msg, "⚠️ Gemini failed — see the plugin log.");
  } catch (e2) {
    // Logged above.
  }
}

function requestText(prompt) {
  var system = String(settingGet("systemPrompt", "") || "").trim();
  return system ? (system + "\n\n" + prompt) : prompt;
}

function requestBody(prompt) {
  return JSON.stringify({ contents: [{ parts: [{ text: requestText(prompt) }] }] });
}

// AI Studio mode: the original generativelanguage request —
// model setting, Bearer token, same body — over async POST.
// (Async POST, like the sync form, delivers the error body on a
// non-2xx status and body null on transport failure.)
function aistudioRequest(ctx, prompt, token) {
  var model = readSetting("model", "gemini-3-flash-preview");
  var url = "https://generativelanguage.googleapis.com/v1beta/models/"
    + encodeURIComponent(model) + ":generateContent";
  amin.httpPostAsync(url, requestBody(prompt), {
    "Authorization": "Bearer " + token,
    "Content-Type": "application/json"
  }, function (res) {
    try {
      handleResponse(ctx, prompt, res && res.body ? res.body : null, false);
    } catch (e) { answerError(ctx, e); }
  });
}

// Vertex AI mode: same models via the Cloud project endpoint.
// Auth is the account token (Bearer) — or, when vertexApiKey is
// set, the key header and no account involvement at all.
function vertexRequest(ctx, prompt, token, apiKey) {
  var project = readSetting("vertexProject", "gen-lang-client-0433595679");
  var location = readSetting("vertexLocation", "us-central1");
  var model = readSetting("vertexModel", "gemini-2.5-flash");
  var url = "https://" + location + "-aiplatform.googleapis.com/v1/projects/"
    + project + "/locations/" + location + "/publishers/google/models/"
    + model + ":generateContent";
  var headers;
  if (apiKey) {
    headers = { "x-goog-api-key": apiKey, "Content-Type": "application/json" };
  } else {
    headers = { "Authorization": "Bearer " + token, "Content-Type": "application/json" };
  }
  amin.httpPostAsync(url, requestBody(prompt), headers, function (res) {
    try {
      handleResponse(ctx, prompt, res && res.body ? res.body : null, true);
    } catch (e) { answerError(ctx, e); }
  });
}

function handleResponse(ctx, prompt, response, isVertex) {
  var mode = isVertex ? "vertex" : "aistudio";
  if (!response) {
    recordQuestion(prompt, mode, false);
    amin.reply(ctx.msg, "⚠️ Gemini did not answer — check the connection, and "
      + "that the model name in the plugin settings is right.");
    return;
  }
  var apiError = extractError(response);
  if (apiError) {
    recordQuestion(prompt, mode, false);
    if (isVertex && apiError.toLowerCase().indexOf("billing") >= 0) {
      amin.log("gemini-chat vertex error body: " + response);
      amin.reply(ctx.msg, "⚠️ Vertex needs billing enabled on the project "
        + "(one console step) — or switch back to AI Studio mode in "
        + "Plugins → Google Account.");
      return;
    }
    amin.reply(ctx.msg, "⚠️ Gemini error: " + apiError);
    return;
  }
  var answer = extractText(response);
  if (!answer) {
    recordQuestion(prompt, mode, false);
    amin.reply(ctx.msg, "⚠️ Gemini answered, but the reply could not be read "
      + "(it may have been blocked by a safety filter).");
    return;
  }
  recordQuestion(prompt, mode, true);
  amin.reply(ctx.msg, answer);
}

// Gemini error shape: { error: { code, message, status } } — surface
// the message so a rejected token/scope is self-diagnosing (the
// bridge returns error bodies instead of null).
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

function statusLine() {
  var mode = "unknown";
  try { mode = amin.google ? apiMode() : "no-bridge"; } catch (e) { /* keep */ }
  var model = mode === "vertex"
    ? readSetting("vertexModel", "gemini-2.5-flash")
    : readSetting("model", "gemini-3-flash-preview");
  var auth;
  if (mode === "vertex" && readSetting("vertexApiKey", "")) {
    auth = "Vertex API key (no sign-in needed)";
  } else if (!amin.google) {
    auth = "Google bridge unavailable in this build";
  } else if (amin.google.isSignedIn()) {
    auth = "Google account connected";
  } else {
    auth = "Google account NOT connected — use /ai once or Plugins → Google Account";
  }
  return "Mode: " + mode + " · Model: " + model + " · " + auth;
}

function onPluginAction(actionId) {
  try {
    if (actionId === "status") {
      var line = statusLine();
      amin.log("gemini-chat status: " + line);
      amin.toast(line.length > 120 ? line.substring(0, 117) + "…" : line);
      return;
    }
    if (actionId === "history") {
      var s = loadStats();
      var rec = recentQuestions(5);
      var lines = ["gemini-chat: " + (s.asked || 0) + " asked, " + (s.answered || 0) + " answered"];
      for (var i = 0; i < rec.length; i++) {
        lines.push((rec[i].ok ? "✅ " : "❌ ") + "[" + rec[i].mode + "] " + rec[i].q);
      }
      amin.log(lines.join("\n"));
      amin.toast("💬 " + (s.asked || 0) + " asked · " + (s.answered || 0) + " answered — details in the log");
      return;
    }
    if (actionId === "clear") {
      if (dbOk()) {
        try { amin.db.clear("history"); } catch (e) { /* best effort */ }
        try { amin.db.del("stats", "totals"); } catch (e2) { /* best effort */ }
      }
      amin.toast("🗑 Gemini Chat history cleared");
      amin.log("gemini-chat: history + counters cleared from the plugin page");
    }
  } catch (e) { amin.log("gemini-chat onPluginAction error: " + e); }
}
