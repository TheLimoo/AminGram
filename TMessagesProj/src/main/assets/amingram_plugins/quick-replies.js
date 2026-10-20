// ==AminGramPlugin==
// @id          quick-replies
// @name        Quick Replies
// @version     1.0
// @author      Amin
// @description Text templates: /tpl set <name> <text> saves one, /tpl <name> posts it, /tpl list shows your templates, /tpl del <name> removes one. Note: the /tpl command message itself also appears in the chat (the platform can't swallow outgoing commands yet).
// @capabilities commands, send, settings
// ==/AminGramPlugin==

var NAME_RE = /^[a-z0-9_-]{1,24}$/i;
var RESERVED = { list: true, set: true, del: true, help: true };

// Templates live as ONE object {name: text} under the storage key
// "templates" — via storage v2 JSON when the platform offers it,
// else the legacy string get/set with JSON encode/decode.
function hasJsonStorage() {
  try {
    return !!(amin.platform && typeof amin.platform.has === "function"
      && amin.platform.has("storage-json")
      && amin.storage && typeof amin.storage.getJSON === "function"
      && typeof amin.storage.setJSON === "function");
  } catch (e) {
    return false;
  }
}

function loadTemplates() {
  try {
    if (hasJsonStorage()) {
      var v = amin.storage.getJSON("templates", {});
      return (v && typeof v === "object") ? v : {};
    }
    var raw = amin.storage.get("templates", "");
    if (!raw) {
      return {};
    }
    var parsed = JSON.parse(String(raw));
    return (parsed && typeof parsed === "object") ? parsed : {};
  } catch (e) {
    return {};
  }
}

function saveTemplates(templates) {
  if (hasJsonStorage()) {
    amin.storage.setJSON("templates", templates);
  } else {
    amin.storage.set("templates", JSON.stringify(templates));
  }
}

function onCommand(ctx) {
  if (String(ctx.command || "").toLowerCase() !== "tpl") {
    return;
  }
  try {
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
      amin.reply(ctx.msg, "Usage: /tpl set <name> <text> · /tpl <name> posts it · /tpl list · /tpl del <name>");
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
    var templates = loadTemplates();
    if (Object.prototype.hasOwnProperty.call(templates, first)) {
      amin.send(ctx.chatId, String(templates[first]));
    } else {
      amin.reply(ctx.msg, "No template named \"" + first + "\" — /tpl list shows what you have.");
    }
  } catch (e) {
    amin.log("quick-replies error: " + e);
    try {
      amin.reply(ctx.msg, "⚠️ Quick Replies failed — see the plugin log.");
    } catch (e2) {
      // Nothing sensible left to do; the error is in the log.
    }
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
  amin.reply(ctx.msg, "🗑 Deleted template \"" + name + "\".");
}

function listTemplates(ctx) {
  var templates = loadTemplates();
  var names = [];
  for (var k in templates) {
    if (Object.prototype.hasOwnProperty.call(templates, k)) {
      names.push(k);
    }
  }
  names.sort();
  if (!names.length) {
    amin.reply(ctx.msg, "📋 Templates: none yet — save one with /tpl set <name> <text>.");
    return;
  }
  amin.reply(ctx.msg, "📋 Templates: " + names.join(", "));
}
