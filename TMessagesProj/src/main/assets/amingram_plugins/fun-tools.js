// ==AminGramPlugin==
// @id          fun-tools
// @name        Fun Tools
// @version     1.1
// @author      Vesper
// @description Little chat toys: /roll a dice, /flip a coin, /pick or /choose from a list, /8ball answers a question. Menu button posts a dice roll in the chat.
// @capabilities commands, send, menu, settings
// @minPlatform 2
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "diceSides", type: "text", title: "Dice sides for /roll", def: "100" }
];

var PLUGIN_PAGE = [
  { type: "info", text: "Toys: /roll /flip /pick /choose /8ball. Usage counts live in the plugin database (page actions below)." },
  { type: "action", id: "stats", title: "Toy usage", subtitle: "Show how often each toy was used" },
  { type: "action", id: "reset-stats", title: "Reset usage counts", subtitle: "Zero the toy counters" }
];

var TOOL_NAMES = ["roll", "flip", "pick", "eightball"];

var EIGHT_BALL = [
  "It is certain.", "Without a doubt.", "Yes — definitely.",
  "Most likely.", "Signs point to yes.", "As I see it, yes.",
  "Reply hazy, try again.", "Ask again later.",
  "Better not tell you now.", "Cannot predict now.",
  "Don't count on it.", "My reply is no.",
  "Outlook not so good.", "Very doubtful."
];

// --- Usage stats: amin.db primary, legacy storage JSON as fallback ---

function dbReady() {
  try {
    return !!(amin.db && typeof amin.db.get === "function" && typeof amin.db.put === "function");
  } catch (e) {
    return false;
  }
}

function legacyStats() {
  try {
    if (typeof amin.storage.getJSON === "function") {
      var v = amin.storage.getJSON("funStats", null);
      if (v && typeof v === "object") return v;
    }
  } catch (e) { /* ignore */ }
  return {};
}

function toolCount(tool) {
  try {
    if (dbReady()) {
      var v = amin.db.get("stats", tool);
      if (typeof v === "number") return v;
      // Lazy one-way migration from any legacy storage counters.
      var legacy = legacyStats();
      if (typeof legacy[tool] === "number") {
        amin.db.put("stats", tool, legacy[tool]);
        return legacy[tool];
      }
      return 0;
    }
    var all = legacyStats();
    return typeof all[tool] === "number" ? all[tool] : 0;
  } catch (e) {
    return 0;
  }
}

function bumpTool(tool) {
  try {
    var next = toolCount(tool) + 1;
    if (dbReady()) {
      amin.db.put("stats", tool, next);
    } else if (typeof amin.storage.setJSON === "function") {
      var all = legacyStats();
      all[tool] = next;
      amin.storage.setJSON("funStats", all);
    }
  } catch (e) {
    amin.debug("fun-tools stats error: " + e);
  }
}

function resetStats() {
  try {
    if (dbReady()) amin.db.clear("stats");
    if (typeof amin.storage.remove === "function") amin.storage.remove("funStats");
    else if (typeof amin.storage.setJSON === "function") amin.storage.setJSON("funStats", {});
  } catch (e) {
    amin.debug("fun-tools reset error: " + e);
  }
}

// --- Toys ---

function roll(max) {
  return Math.floor(Math.random() * max) + 1;
}

// Split a choice list on commas or the word "or":
// "pizza, sushi" and "pizza or sushi" both work.
function splitChoices(args) {
  var text = String(args || "");
  var raw = text.split(/,/);
  var parts = [];
  for (var i = 0; i < raw.length; i++) {
    var seg = raw[i].split(/\s+or\s+/i);
    for (var j = 0; j < seg.length; j++) {
      var p = seg[j].replace(/^\s+|\s+$/g, "");
      if (p) parts.push(p);
    }
  }
  return parts;
}

function pickOne(args) {
  var clean = splitChoices(args);
  if (clean.length < 2) return null;
  return clean[Math.floor(Math.random() * clean.length)];
}

function onCommand(ctx) {
  try {
    if (ctx.command === "roll") {
      var max = parseInt(amin.storage.get("diceSides", "100"), 10) || 100;
      var arg = parseInt((ctx.args || "").trim(), 10);
      if (arg && arg > 1) max = arg;
      amin.reply(ctx.msg, "🎲 Rolled " + roll(max) + " (of " + max + ")");
      bumpTool("roll");
    } else if (ctx.command === "flip") {
      amin.reply(ctx.msg, Math.random() < 0.5 ? "🪙 Heads" : "🪙 Tails");
      bumpTool("flip");
    } else if (ctx.command === "pick" || ctx.command === "choose") {
      var picked = pickOne(ctx.args);
      if (picked === null) {
        amin.reply(ctx.msg, "Usage: /" + ctx.command + " pizza, sushi, burger");
        return;
      }
      amin.reply(ctx.msg, "🎯 I pick: " + picked);
      bumpTool("pick");
    } else if (ctx.command === "8ball") {
      if (!ctx.args || !ctx.args.trim()) {
        amin.reply(ctx.msg, "Usage: /8ball <question>");
        return;
      }
      amin.reply(ctx.msg, "🎱 " + EIGHT_BALL[Math.floor(Math.random() * EIGHT_BALL.length)]);
      bumpTool("eightball");
    }
  } catch (e) { amin.log("fun-tools error: " + e); }
}

function onMenuAction(ctx) {
  try {
    amin.send(ctx.chatId, "🎲 Rolled " + roll(100) + " (of 100)");
    bumpTool("roll");
  } catch (e) { amin.log("fun-tools menu error: " + e); }
}

function onPluginAction(actionId) {
  try {
    if (actionId === "stats") {
      var parts = [];
      var total = 0;
      for (var i = 0; i < TOOL_NAMES.length; i++) {
        var c = toolCount(TOOL_NAMES[i]);
        total += c;
        parts.push(TOOL_NAMES[i] + ": " + c);
      }
      amin.toast("🎲 Toy usage (" + total + " total) — " + parts.join(" · "));
    } else if (actionId === "reset-stats") {
      resetStats();
      amin.toast("🎲 Toy counters reset");
    }
  } catch (e) { amin.log("fun-tools page error: " + e); }
}
