// ==AminGramPlugin==
// @id          vip-alert
// @name        VIP Alert
// @version     1.1
// @author      Amin
// @description Toasts when a chosen person writes in any chat. Watch several people at once: comma-separated names or numeric user IDs in the setting below.
// @capabilities observe, settings
// @minPlatform 2
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "person", type: "text", title: "Names / user IDs to watch", def: "",
    help: "Separate entries with commas or spaces. A number matches that user ID; anything else matches part of the sender's name (as before)." }
];

var PLUGIN_PAGE = [
  { type: "info", text: "VIP Alert watches the names / user IDs in the setting above. Alert history lives in the plugin database." },
  { type: "action", id: "last", title: "Last alert", subtitle: "Who triggered the most recent alert, and where" },
  { type: "action", id: "stats", title: "Alert count", subtitle: "How many alerts have fired in total" },
  { type: "action", id: "clear", title: "Clear alert history", subtitle: "Forget the last alert and zero the counter" }
];

// --- Watch list parsing: one setting, many entries ---

// The long-standing single value keeps working unchanged: it is
// simply a list with one entry. Numbers are user IDs; other
// entries are name fragments, matched exactly as v1.0 matched.
function watchList() {
  var raw = String(amin.storage.get("person", "") || "");
  var tokens = raw.split(/[\s,]+/);
  var out = [];
  for (var i = 0; i < tokens.length; i++) {
    var t = tokens[i];
    if (!t) continue;
    if (/^\d+$/.test(t)) out.push({ id: parseInt(t, 10), name: null });
    else out.push({ id: null, name: t });
  }
  return out;
}

// --- Alert history: amin.db primary, storage JSON as fallback ---

function dbReady() {
  try {
    return !!(amin.db && typeof amin.db.get === "function" && typeof amin.db.put === "function");
  } catch (e) {
    return false;
  }
}

function legacyHistory() {
  try {
    if (typeof amin.storage.getJSON === "function") {
      var v = amin.storage.getJSON("vipHistory", null);
      if (v && typeof v === "object") return v;
    }
  } catch (e) { /* ignore */ }
  return {};
}

function historyGet(field) {
  try {
    if (dbReady()) {
      var v = amin.db.get("alerts", field);
      if (v !== null && v !== undefined) return v;
      // Lazy one-way migration from any legacy storage history.
      var legacy = legacyHistory();
      if (legacy[field] !== undefined && legacy[field] !== null) {
        amin.db.put("alerts", field, legacy[field]);
        return legacy[field];
      }
      return null;
    }
    var all = legacyHistory();
    return all[field] !== undefined ? all[field] : null;
  } catch (e) {
    return null;
  }
}

function historySet(field, value) {
  try {
    if (dbReady()) {
      amin.db.put("alerts", field, value);
    } else if (typeof amin.storage.setJSON === "function") {
      var all = legacyHistory();
      all[field] = value;
      amin.storage.setJSON("vipHistory", all);
    }
  } catch (e) {
    amin.debug("vip-alert history error: " + e);
  }
}

function recordAlert(msg) {
  try {
    var prev = historyGet("count");
    historySet("count", (typeof prev === "number" ? prev : 0) + 1);
    historySet("last", {
      name: String(msg.senderName || ""),
      senderId: msg.senderId || 0,
      chatId: msg.chatId || 0,
      date: msg.date || 0
    });
  } catch (e) {
    amin.debug("vip-alert record error: " + e);
  }
}

// --- Hooks ---

function onMessage(msg) {
  try {
    if (!msg || msg.outgoing) return;
    var list = watchList();
    if (!list.length) return;
    for (var i = 0; i < list.length; i++) {
      var entry = list[i];
      var hit = false;
      if (entry.id !== null) {
        hit = !!msg.senderId && msg.senderId === entry.id;
      } else if (entry.name && msg.senderName) {
        hit = msg.senderName.indexOf(entry.name) !== -1;
      }
      if (hit) {
        amin.toast("💬 " + msg.senderName + " just wrote");
        amin.log("VIP hit in chat " + msg.chatId);
        recordAlert(msg);
        return;
      }
    }
  } catch (e) {
    amin.log("vip-alert error: " + e);
  }
}

function pad2(n) {
  return (n < 10 ? "0" : "") + n;
}

function fmtWhen(unixSec) {
  if (!unixSec) return "";
  try {
    var d = new Date(unixSec * 1000);
    return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate())
      + " " + pad2(d.getHours()) + ":" + pad2(d.getMinutes());
  } catch (e) {
    return "";
  }
}

function onPluginAction(actionId) {
  try {
    if (actionId === "last") {
      var last = historyGet("last");
      if (!last) {
        amin.toast("🔔 No VIP alerts yet");
        return;
      }
      var when = fmtWhen(last.date);
      amin.toast("🔔 Last alert: " + (last.name || ("user " + last.senderId))
        + " · chat " + last.chatId + (when ? " · " + when : ""));
      amin.log("VIP last alert: " + JSON.stringify(last));
    } else if (actionId === "stats") {
      var count = historyGet("count");
      amin.toast("🔔 VIP alerts so far: " + (typeof count === "number" ? count : 0));
    } else if (actionId === "clear") {
      if (dbReady()) amin.db.clear("alerts");
      if (typeof amin.storage.remove === "function") amin.storage.remove("vipHistory");
      else if (typeof amin.storage.setJSON === "function") amin.storage.setJSON("vipHistory", {});
      amin.toast("🔔 VIP alert history cleared");
    }
  } catch (e) {
    amin.log("vip-alert page error: " + e);
  }
}
