// ==AminGramPlugin==
// @id          gemini-chat
// @name        Gemini Chat
// @version     1.0
// @author      Amin
// @description /ai <question> asks Gemini, using your connected Google account (no API key).
// @capabilities commands, send, http, google, settings, menu
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "model", type: "text", title: "Gemini model (AI Studio mode)", def: "gemini-3-flash-preview" },
  { key: "systemPrompt", type: "text", title: "System prompt (prepended, optional)", def: "" },
  { key: "vertexProject", type: "text", title: "Vertex AI: Google Cloud project", def: "gen-lang-client-0433595679" },
  { key: "vertexLocation", type: "text", title: "Vertex AI: location", def: "us-central1" },
  { key: "vertexModel", type: "text", title: "Vertex AI: model", def: "gemini-2.5-flash" },
  { key: "vertexApiKey", type: "text", title: "Vertex AI: API key (optional; skips Google sign-in)", def: "" }
];

function onCommand(ctx) {
  if (String(ctx.command || "").toLowerCase() !== "ai") {
    return;
  }
  try {
    var prompt = (ctx.args || "").trim();
    if (!prompt) {
      amin.reply(ctx.msg, "Usage: /ai <your question>");
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

// Stored settings are trimmed (v1.3.7): a trailing space from a
// mobile keyboard turns a model name into a 404. An emptied field
// falls back to its declared default.
function readSetting(key, def) {
  var value = String(amin.storage.get(key, def) || "").trim();
  return value ? value : def;
}

function apiMode() {
  return (amin.google.getMode && amin.google.getMode()) || "aistudio";
}

// If the account is not connected yet, amin.google.signIn opens the
// native Google picker; the pending question continues in the
// callback once the user finishes. Exception (v2.1): Vertex mode
// with an API key set needs no account at all — the key IS the
// credential, so no sign-in surface is touched.
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
  var system = String(amin.storage.get("systemPrompt", "") || "").trim();
  return system ? (system + "\n\n" + prompt) : prompt;
}

function requestBody(prompt) {
  return JSON.stringify({ contents: [{ parts: [{ text: requestText(prompt) }] }] });
}

// AI Studio mode: the original generativelanguage request,
// unchanged — model setting, Bearer token, same body.
function aistudioRequest(ctx, prompt, token) {
  var model = readSetting("model", "gemini-3-flash-preview");
  var url = "https://generativelanguage.googleapis.com/v1beta/models/"
    + encodeURIComponent(model) + ":generateContent";
  var response = amin.httpPost(url, requestBody(prompt), {
    "Authorization": "Bearer " + token,
    "Content-Type": "application/json"
  });
  handleResponse(ctx, response, false);
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
  var response = amin.httpPost(url, requestBody(prompt), headers);
  handleResponse(ctx, response, true);
}

function handleResponse(ctx, response, isVertex) {
  if (!response) {
    amin.reply(ctx.msg, "⚠️ Gemini did not answer — check the connection, and "
      + "that the model name in the plugin settings is right.");
    return;
  }
  var apiError = extractError(response);
  if (apiError) {
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
    amin.reply(ctx.msg, "⚠️ Gemini answered, but the reply could not be read "
      + "(it may have been blocked by a safety filter).");
    return;
  }
  amin.reply(ctx.msg, answer);
}

// Gemini error shape: { error: { code, message, status } } — surface
// the message so a rejected token/scope is self-diagnosing (v1.3.7:
// the bridge now returns error bodies instead of null).
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
