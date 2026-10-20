// ==AminGramPlugin==
// @id          gemini-chat
// @name        Gemini Chat
// @version     1.0
// @author      Amin
// @description /ai <question> asks Gemini, using your connected Google account (no API key).
// @capabilities commands, send, http, google, settings, menu
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "model", type: "text", title: "Gemini model", def: "gemini-3-flash-preview" },
  { key: "systemPrompt", type: "text", title: "System prompt (prepended, optional)", def: "" }
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

// If the account is not connected yet, amin.google.signIn opens the
// native Google picker; the pending question continues in the
// callback once the user finishes.
function ask(ctx, prompt) {
  if (!amin.google) {
    amin.reply(ctx.msg, "⚠️ The Google bridge is not available in this build.");
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
  amin.google.getAccessToken(function (token) {
    try {
      if (!token) {
        amin.reply(ctx.msg, "⚠️ Could not get a Google token. Reconnect your account "
          + "in Plugins → Google Account, then try again.");
        return;
      }
      // Trim stored settings (v1.3.7): a trailing space from a
      // mobile keyboard turns the model name into a 404.
      var model = String(amin.storage.get("model", "gemini-3-flash-preview") || "").trim();
      if (!model) {
        model = "gemini-3-flash-preview";
      }
      var system = String(amin.storage.get("systemPrompt", "") || "").trim();
      var text = system ? (system + "\n\n" + prompt) : prompt;
      var url = "https://generativelanguage.googleapis.com/v1beta/models/"
        + encodeURIComponent(model) + ":generateContent";
      var body = JSON.stringify({ contents: [{ parts: [{ text: text }] }] });
      var response = amin.httpPost(url, body, {
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json"
      });
      if (!response) {
        amin.reply(ctx.msg, "⚠️ Gemini did not answer — check the connection, and "
          + "that the model name in the plugin settings is right.");
        return;
      }
      var answer = extractText(response);
      if (!answer) {
        amin.reply(ctx.msg, "⚠️ Gemini answered, but the reply could not be read "
          + "(it may have been blocked by a safety filter).");
        return;
      }
      amin.reply(ctx.msg, answer);
    } catch (e) {
      amin.log("gemini-chat answer error: " + e);
      try {
        amin.reply(ctx.msg, "⚠️ Gemini failed — see the plugin log.");
      } catch (e2) {
        // Logged above.
      }
    }
  });
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
