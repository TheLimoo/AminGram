// ==AminGramPlugin==
// @id          ai-actions
// @name        AI Actions
// @version     1.0
// @author      Amin
// @description AI on any message: reply to a message with /sum to summarize it, /explain to explain it simply, or /draft to draft a reply to it — or pass text directly (/sum <text>). Uses Gemini via your connected Google account (AI Studio or Vertex AI mode, see Plugins → Google Account).
// @icon        sparkles
// @menuLabel   AI: summarize
// @capabilities commands, send, http, google, menu, observe, settings
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "model", type: "text", title: "Gemini model (AI Studio mode)", def: "gemini-3-flash-preview" },
  { key: "vertexProject", type: "text", title: "Vertex AI project", def: "gen-lang-client-0433595679" },
  { key: "vertexLocation", type: "text", title: "Vertex AI location", def: "us-central1" },
  { key: "vertexModel", type: "text", title: "Vertex AI model", def: "gemini-2.5-flash" },
  { key: "vertexApiKey", type: "text", title: "Vertex AI API key (optional — skips Google sign-in in Vertex mode)", def: "" }
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

function setting(key, def) {
  var v = "";
  try {
    v = String(amin.storage.get(key, def) || "").trim();
  } catch (e) {
    v = "";
  }
  return v ? v : def;
}

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
      amin.reply(ctx.msg, "Usage: /" + cmd + " — reply to a message with /" + cmd
        + ", or /" + cmd + " <text>");
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
    runAi("sum", { text: known.text, senderName: known.senderName || "" }, post);
  } catch (e) {
    amin.log("ai-actions menu error: " + e);
  }
}

function buildPrompt(kind, src) {
  var block = src.senderName ? ("From " + src.senderName + ":\n" + src.text) : src.text;
  return PROMPTS[kind] + "\n\n" + block;
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
    doRequest(mode, prompt, null, vertexKey, post);
    return;
  }
  if (!amin.google) {
    post("⚠️ The Google bridge is not available in this build.");
    return;
  }
  if (!amin.google.isSignedIn()) {
    amin.google.signIn(function (ok) {
      if (ok) {
        fetchToken(mode, prompt, post);
      } else {
        post("⚠️ Google sign-in was cancelled or failed — AI actions need "
          + "your Google account. You can also connect it in Plugins → Google Account.");
      }
    });
    return;
  }
  fetchToken(mode, prompt, post);
}

function fetchToken(mode, prompt, post) {
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
      doRequest(mode, prompt, token, "", post);
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

function doRequest(mode, prompt, token, vertexKey, post) {
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
  var response = amin.httpPost(url, body, headers);
  if (!response) {
    post("⚠️ Gemini did not answer — check the connection, and "
      + "that the model name in the plugin settings is right.");
    return;
  }
  var apiError = extractError(response);
  if (apiError) {
    if (mode === "vertex" && isBillingError(response)) {
      post(BILLING_LINE);
    } else {
      post("⚠️ Gemini error: " + apiError);
    }
    return;
  }
  var answer = extractText(response);
  if (!answer) {
    post("⚠️ Gemini answered, but the reply could not be read "
      + "(it may have been blocked by a safety filter).");
    return;
  }
  post(answer);
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
