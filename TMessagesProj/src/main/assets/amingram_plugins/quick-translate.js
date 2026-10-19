// ==AminGramPlugin==
// @id          quick-translate
// @name        Quick Translate
// @version     1.0
// @author      Amin
// @description /tr <text> translates text (default target: Persian). /tr en <text> picks another target language.
// @capabilities commands, send, http, settings
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "target", type: "text", title: "Default target language code (fa, en, ar, …)", def: "fa" }
];

function onCommand(ctx) {
  if (String(ctx.command || "").toLowerCase() !== "tr") {
    return;
  }
  try {
    var args = (ctx.args || "").trim();
    if (!args) {
      amin.reply(ctx.msg, "Usage: /tr <text> — or /tr en <text> to translate into English");
      return;
    }
    var target = amin.storage.get("target", "fa");
    if (!target) {
      target = "fa";
    }
    // A leading 2-3 letter token (optionally xx-XX) is a target override.
    var m = args.match(/^([a-zA-Z]{2,3}(?:-[a-zA-Z]{2})?)\s+([\s\S]+)$/);
    if (m) {
      target = m[1].toLowerCase();
      args = m[2];
    }
    var url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
      + encodeURIComponent(target) + "&dt=t&q=" + encodeURIComponent(args);
    var body = amin.httpGet(url);
    if (!body) {
      amin.reply(ctx.msg, "⚠️ Translation failed — the translate service did not answer.");
      return;
    }
    var translated = "";
    var data = JSON.parse(body);
    if (data && data[0]) {
      for (var i = 0; i < data[0].length; i++) {
        if (data[0][i] && data[0][i][0]) {
          translated += data[0][i][0];
        }
      }
    }
    if (!translated) {
      amin.reply(ctx.msg, "⚠️ Translation failed — unexpected answer from the service.");
      return;
    }
    amin.reply(ctx.msg, translated);
  } catch (e) {
    amin.log("quick-translate error: " + e);
    try {
      amin.reply(ctx.msg, "⚠️ Translation failed.");
    } catch (e2) {
      // Nothing sensible left to do; the error is in the log.
    }
  }
}
