// ==AminGramPlugin==
// @id          shout
// @name        Shout
// @version     1.1
// @author      Amin
// @description /shout <text> posts the text in uppercase. Optional repeat count in the plugin settings.
// @capabilities commands, send, settings
// @minPlatform 2
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "repeat", type: "number", title: "Repeat count", def: 1, min: 1, max: 5, step: 1,
    help: "Post the shouted text this many times (joined with spaces). 1 = once, as before." }
];

function onCommand(ctx) {
  try {
    if (ctx.command !== "shout") return;
    if (!ctx.args) { amin.reply(ctx.msg, "Usage: /shout <text>"); return; }
    var loud = ctx.args.toUpperCase();
    var n = parseInt(amin.storage.get("repeat", 1), 10);
    if (isNaN(n) || n < 1) n = 1;
    if (n > 5) n = 5;
    if (n === 1) {
      amin.reply(ctx.msg, loud);
      return;
    }
    var parts = [];
    for (var i = 0; i < n; i++) parts.push(loud);
    amin.reply(ctx.msg, parts.join(" "));
  } catch (e) {
    amin.log("shout error: " + e);
  }
}
