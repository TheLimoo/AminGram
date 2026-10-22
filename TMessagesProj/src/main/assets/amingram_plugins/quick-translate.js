// ==AminGramPlugin==
// @id          quick-translate
// @name        Quick Translate
// @version     1.1
// @author      Amin
// @description /tr <text> translates text (default target: Persian). /tr en <text> picks another target language. Long-press a message → Translate does the same for that message.
// @capabilities commands, send, http, settings, ui
// @contextMenu Translate
// @minPlatform 2
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "target", type: "text", title: "Default target language code (fa, en, ar, …)", def: "fa",
    help: "Used by /tr without a language prefix and by the Translate long-press item." }
];

var PLUGIN_PAGE = [
  { type: "info", text: "Translation is done by Google's keyless gtx endpoint. The long-press Translate item uses the default target language below." },
  { type: "action", id: "swap-language", title: "Swap default language (fa ⇄ en)", subtitle: "Toggles the default target between Persian and English" },
  { type: "action", id: "stats", title: "Show statistics", subtitle: "Translations, by source, and failures" },
  { type: "action", id: "clear-stats", title: "Clear statistics", subtitle: "Zero the counters" }
];

var statsCache = null;

function dbAvailable() {
  try {
    return !!(amin.db && typeof amin.db.get === "function"
      && typeof amin.db.put === "function");
  } catch (e) { return false; }
}

function loadJsonStorage(key) {
  try {
    if (amin.storage && typeof amin.storage.getJSON === "function") {
      var v = amin.storage.getJSON(key, null);
      if (v && typeof v === "object") return v;
    }
  } catch (e) { /* fall through to raw parse */ }
  try {
    var raw = amin.storage.get(key, "");
    if (raw) {
      var parsed = JSON.parse(String(raw));
      if (parsed && typeof parsed === "object") return parsed;
    }
  } catch (e) { /* no legacy value */ }
  return null;
}

function saveJsonStorage(key, value) {
  try {
    if (amin.storage && typeof amin.storage.setJSON === "function") {
      amin.storage.setJSON(key, value);
      return;
    }
  } catch (e) { /* fall through */ }
  try { amin.storage.set(key, JSON.stringify(value)); } catch (e) {}
}

function nonNeg(n) {
  n = Number(n);
  return isFinite(n) && n > 0 ? Math.floor(n) : 0;
}

function normalizeStats(v) {
  v = (v && typeof v === "object") ? v : {};
  return {
    total: nonNeg(v.total), commands: nonNeg(v.commands),
    contexts: nonNeg(v.contexts), failures: nonNeg(v.failures),
    lastTarget: typeof v.lastTarget === "string" ? v.lastTarget : "",
    lastAt: nonNeg(v.lastAt)
  };
}

// Stats live in amin.db ("state"/"stats"); a legacy amin.storage
// copy is read once and migrated when the db has nothing yet.
function loadStats() {
  if (statsCache) return statsCache;
  if (dbAvailable()) {
    try {
      var v = amin.db.get("state", "stats");
      if (v && typeof v === "object") { statsCache = normalizeStats(v); return statsCache; }
    } catch (e) { amin.log("quick-translate stats read error: " + e); }
  }
  var legacy = loadJsonStorage("stats");
  if (legacy) {
    statsCache = normalizeStats(legacy);
    if (dbAvailable()) { try { amin.db.put("state", "stats", statsCache); } catch (e) {} }
    return statsCache;
  }
  statsCache = normalizeStats(null);
  return statsCache;
}

function saveStats() {
  if (!statsCache) return;
  var ok = false;
  if (dbAvailable()) { try { ok = amin.db.put("state", "stats", statsCache) === true; } catch (e) {} }
  if (!ok) saveJsonStorage("stats", statsCache);
}

function recordResult(success, target, sourceKind) {
  var stats = loadStats();
  if (success) {
    stats.total++;
    if (sourceKind === "context") stats.contexts++; else stats.commands++;
    stats.lastTarget = target;
    stats.lastAt = new Date().getTime();
  } else {
    stats.failures++;
  }
  saveStats();
}

// Settings are read through amin.settings when the bridge offers
// it, with amin.storage as the underlying store either way.
function targetSetting() {
  var v = null;
  try {
    if (amin.settings && typeof amin.settings.get === "function") {
      v = amin.settings.get("target", "fa");
    }
  } catch (e) { /* fall through to storage */ }
  if (v === null || v === undefined) {
    try { v = amin.storage.get("target", "fa"); } catch (e) { v = "fa"; }
  }
  v = String(v || "fa").trim().toLowerCase();
  return v ? v : "fa";
}

function setTargetSetting(value) {
  try {
    if (amin.settings && typeof amin.settings.set === "function") {
      amin.settings.set("target", value);
      return;
    }
  } catch (e) { /* fall through to storage */ }
  try { amin.storage.set("target", value); } catch (e) {}
}

function parseTranslated(body) {
  var translated = "";
  var data = JSON.parse(String(body));
  if (data && data[0]) {
    for (var i = 0; i < data[0].length; i++) {
      if (data[0][i] && data[0][i][0]) translated += data[0][i][0];
    }
  }
  return translated;
}

// The same keyless gtx endpoint as v1.0, now async only — the
// sync form would stall every plugin behind a slow response.
// sourceKind is "command" or "context" (for the statistics).
function translateAndReply(msg, text, target, sourceKind) {
  var url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
    + encodeURIComponent(target) + "&dt=t&q=" + encodeURIComponent(text);
  if (typeof amin.httpGetAsync !== "function") {
    recordResult(false, target, sourceKind);
    amin.reply(msg, "⚠️ Translation failed — the translate service did not answer.");
    return;
  }
  amin.httpGetAsync(url, null, function (res) {
    try {
      if (!res || !res.body) {
        recordResult(false, target, sourceKind);
        amin.reply(msg, "⚠️ Translation failed — the translate service did not answer.");
        return;
      }
      var translated = parseTranslated(res.body);
      if (!translated) {
        recordResult(false, target, sourceKind);
        amin.reply(msg, "⚠️ Translation failed — unexpected answer from the service.");
        return;
      }
      recordResult(true, target, sourceKind);
      amin.reply(msg, translated);
    } catch (e) {
      amin.log("quick-translate error: " + e);
      try {
        recordResult(false, target, sourceKind);
        amin.reply(msg, "⚠️ Translation failed.");
      } catch (e2) { /* the error is in the log */ }
    }
  });
}

function onCommand(ctx) {
  try {
    if (String(ctx.command || "").toLowerCase() !== "tr") return;
    var args = (ctx.args || "").trim();
    if (!args) {
      amin.reply(ctx.msg, "Usage: /tr <text> — or /tr en <text> to translate into English. You can also long-press any message → Translate.");
      return;
    }
    var target = targetSetting();
    // A leading 2-3 letter token (optionally xx-XX) is a target override.
    var m = args.match(/^([a-zA-Z]{2,3}(?:-[a-zA-Z]{2})?)\s+([\s\S]+)$/);
    if (m) {
      target = m[1].toLowerCase();
      args = m[2];
    }
    translateAndReply(ctx.msg, args, target, "command");
  } catch (e) {
    amin.log("quick-translate onCommand error: " + e);
    try { amin.reply(ctx.msg, "⚠️ Translation failed."); } catch (e2) {}
  }
}

function onContextAction(ctx) {
  try {
    var text = "";
    if (ctx && ctx.msg) text = String(ctx.msg.text || ctx.msg.caption || "").trim();
    if (!text) {
      amin.toast("Nothing to translate in that message");
      return;
    }
    translateAndReply(ctx.msg, text, targetSetting(), "context");
  } catch (e) {
    amin.log("quick-translate onContextAction error: " + e);
    try { amin.toast("⚠️ Translation failed"); } catch (e2) {}
  }
}

function onPluginAction(actionId) {
  try {
    if (actionId === "swap-language") {
      var current = targetSetting();
      var next = current === "fa" ? "en" : "fa";
      setTargetSetting(next);
      amin.toast("🌐 Default target: " + next + " (was " + current + ")");
      return;
    }
    if (actionId === "stats") {
      var stats = loadStats();
      amin.toast("🌐 " + stats.total + " translation(s) · ⌨️ " + stats.commands
        + " · 👆 " + stats.contexts + " · ❌ " + stats.failures);
      return;
    }
    if (actionId === "clear-stats") {
      statsCache = normalizeStats(null);
      saveStats();
      amin.toast("🧹 Translate statistics cleared");
    }
  } catch (e) { amin.log("quick-translate onPluginAction error: " + e); }
}
