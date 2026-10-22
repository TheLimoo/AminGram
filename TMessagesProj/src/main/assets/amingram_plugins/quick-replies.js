// ==AminGramPlugin==
// @id          quick-replies
// @name        Quick Replies
// @version     1.1
// @author      Amin
// @description Text templates: /tpl set <name> <text> saves one, /tpl <name> posts it, /tpl list shows your templates, /tpl del <name> removes one, /tpl export posts them all in one message. Note: the /tpl command message itself also appears in the chat (the platform can't swallow outgoing commands yet).
// @capabilities commands, send, settings, ui
// @minPlatform 2
// ==/AminGramPlugin==

var PLUGIN_PAGE = [
  { type: "info", text: "Templates are stored in this plugin's database. Existing templates from earlier versions are migrated automatically on first use — nothing is lost." },
  { type: "action", id: "count", title: "Template count", subtitle: "How many templates you have saved" },
  { type: "action", id: "export-all", title: "Export all templates", subtitle: "Posts every template in one notification (or use /tpl export in a chat)" },
  { type: "action", id: "clear-all", title: "Clear all templates", subtitle: "Asks twice — the second tap confirms" }
];

var NAME_RE = /^[a-z0-9_-]{1,24}$/i;
var RESERVED = { list: true, set: true, del: true, help: true };

var templatesCache = null;
var statsCache = null;
var clearArmed = false;

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
    sets: nonNeg(v.sets), posts: nonNeg(v.posts), dels: nonNeg(v.dels),
    exports: nonNeg(v.exports), clears: nonNeg(v.clears), lastAt: nonNeg(v.lastAt)
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
    } catch (e) { amin.log("quick-replies stats read error: " + e); }
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

function bumpStat(field) {
  var stats = loadStats();
  stats[field] = nonNeg(stats[field]) + 1;
  stats.lastAt = new Date().getTime();
  saveStats();
}

// Templates live as ONE object {name: text} — in amin.db
// ("templates"/"all") since v1.1. The v1.0 store was the same
// object under the amin.storage key "templates" (storage v2 JSON
// when available, else a legacy JSON string): it is read ONCE,
// lazily, when the db has no templates yet, and migrated into
// the db — existing templates are never lost. Every save also
// refreshes the storage copy as a fallback; if a db write is
// refused, the db row is dropped so the storage copy stays
// authoritative instead of shadowing newer data.
function loadTemplates() {
  if (templatesCache) return templatesCache;
  if (dbAvailable()) {
    try {
      var v = amin.db.get("templates", "all");
      if (v && typeof v === "object") { templatesCache = v; return templatesCache; }
    } catch (e) { amin.log("quick-replies db read error: " + e); }
  }
  var legacy = loadJsonStorage("templates");
  if (legacy) {
    templatesCache = legacy;
    if (dbAvailable()) {
      try { amin.db.put("templates", "all", templatesCache); } catch (e) {}
    }
    return templatesCache;
  }
  templatesCache = {};
  return templatesCache;
}

function saveTemplates(templates) {
  templatesCache = templates;
  if (dbAvailable()) {
    var ok = false;
    try { ok = amin.db.put("templates", "all", templates) === true; } catch (e) {}
    if (!ok) {
      try { amin.db.del("templates", "all"); } catch (e) {}
    }
  }
  saveJsonStorage("templates", templates);
}

function templateNames(templates) {
  var names = [];
  for (var k in templates) {
    if (Object.prototype.hasOwnProperty.call(templates, k)) names.push(k);
  }
  names.sort();
  return names;
}

function exportText(templates) {
  var names = templateNames(templates);
  if (!names.length) return "📦 Quick Replies export: no templates saved yet.";
  var lines = ["📦 Quick Replies export — " + names.length + " template(s)"];
  for (var i = 0; i < names.length; i++) {
    lines.push("");
    lines.push("— " + names[i] + " —");
    lines.push(String(templates[names[i]]));
  }
  return lines.join("\n");
}

function onCommand(ctx) {
  try {
    if (String(ctx.command || "").toLowerCase() !== "tpl") return;
    var args = String(ctx.args || "").trim();
    if (!args) {
      listTemplates(ctx);
      return;
    }
    var first = args.split(/\s+/)[0];
    var sub = first.toLowerCase();
    if (sub === "list") {
      listTemplates(ctx);
      return;
    }
    if (sub === "help") {
      amin.reply(ctx.msg, "Usage: /tpl set <name> <text> · /tpl <name> posts it · /tpl list · /tpl del <name> · /tpl export posts them all in one message");
      return;
    }
    if (sub === "set") {
      setTemplate(ctx, args);
      return;
    }
    if (sub === "del") {
      delTemplate(ctx, args);
      return;
    }
    if (sub === "export") {
      // A template literally named "export" (possible in v1.0,
      // where export wasn't a subcommand) keeps its old meaning.
      var existing = loadTemplates();
      if (!Object.prototype.hasOwnProperty.call(existing, first)) {
        bumpStat("exports");
        amin.send(ctx.chatId, exportText(existing));
        return;
      }
    }
    var templates = loadTemplates();
    if (Object.prototype.hasOwnProperty.call(templates, first)) {
      bumpStat("posts");
      amin.send(ctx.chatId, String(templates[first]));
    } else {
      amin.reply(ctx.msg, "No template named \"" + first + "\" — /tpl list shows what you have.");
    }
  } catch (e) {
    amin.log("quick-replies error: " + e);
    try {
      amin.reply(ctx.msg, "⚠️ Quick Replies failed — see the plugin log.");
    } catch (e2) { /* the error is in the log */ }
  }
}

function setTemplate(ctx, args) {
  var m = args.match(/^set\s+(\S+)(?:\s+([\s\S]+))?$/i);
  if (!m || !m[2] || !String(m[2]).trim()) {
    amin.reply(ctx.msg, "Usage: /tpl set <name> <text>");
    return;
  }
  var name = m[1];
  var text = String(m[2]).trim();
  if (RESERVED[name.toLowerCase()]) {
    amin.reply(ctx.msg, "\"" + name + "\" is a built-in /tpl command (list, set, del, help), "
      + "so it can't be a template name — pick another name.");
    return;
  }
  if (!NAME_RE.test(name)) {
    amin.reply(ctx.msg, "Invalid template name \"" + name + "\" — names are 1–24 characters: "
      + "letters, digits, _ or -.");
    return;
  }
  var templates = loadTemplates();
  templates[name] = text;
  saveTemplates(templates);
  bumpStat("sets");
  amin.reply(ctx.msg, "✅ Saved template \"" + name + "\".");
}

function delTemplate(ctx, args) {
  var m = args.match(/^del\s+(\S+)\s*$/i);
  if (!m) {
    amin.reply(ctx.msg, "Usage: /tpl del <name>");
    return;
  }
  var name = m[1];
  var templates = loadTemplates();
  if (!Object.prototype.hasOwnProperty.call(templates, name)) {
    amin.reply(ctx.msg, "No template named \"" + name + "\" — nothing to delete.");
    return;
  }
  delete templates[name];
  saveTemplates(templates);
  bumpStat("dels");
  amin.reply(ctx.msg, "🗑 Deleted template \"" + name + "\".");
}

function listTemplates(ctx) {
  var templates = loadTemplates();
  var names = templateNames(templates);
  if (!names.length) {
    amin.reply(ctx.msg, "📋 Templates: none yet — save one with /tpl set <name> <text>.");
    return;
  }
  amin.reply(ctx.msg, "📋 Templates: " + names.join(", "));
}

function onPluginAction(actionId) {
  try {
    if (actionId === "count") {
      clearArmed = false;
      var names = templateNames(loadTemplates());
      amin.toast("📋 " + names.length + (names.length === 1 ? " template" : " templates") + " saved");
      return;
    }
    if (actionId === "export-all") {
      clearArmed = false;
      var templates = loadTemplates();
      bumpStat("exports");
      if (amin.ui && typeof amin.ui.notify === "function"
          && amin.ui.notify("Quick Replies export", exportText(templates))) {
        amin.toast("📦 Export posted as a notification — or use /tpl export in a chat");
      } else {
        amin.toast("Use /tpl export in a chat to get all templates in one message");
      }
      return;
    }
    if (actionId === "clear-all") {
      var current = loadTemplates();
      var count = templateNames(current).length;
      if (!count) {
        clearArmed = false;
        amin.toast("No templates to clear");
        return;
      }
      if (!clearArmed) {
        clearArmed = true;
        amin.toast("⚠️ Tap “Clear all templates” again to delete all " + count + " template(s)");
        return;
      }
      clearArmed = false;
      saveTemplates({});
      bumpStat("clears");
      amin.toast("🧹 All templates cleared");
    }
  } catch (e) { amin.log("quick-replies onPluginAction error: " + e); }
}
