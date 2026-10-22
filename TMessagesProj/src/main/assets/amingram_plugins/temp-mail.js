// ==AminGramPlugin==
// @id          temp-mail
// @name        Temp Mail
// @version     2.1
// @author      Amin
// @description Disposable email via mail.tm: /tm new creates a real account, /tm inbox lists mail, /tm read <id> opens one — verification codes and links are extracted and posted in chat. (v2.1: async HTTP, plugin page with address/copy/inbox/forget actions, address history kept in amin.db.)
// @capabilities commands, send, http, settings, menu, ui
// @minPlatform 2
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "postInChat", type: "bool", title: "Post emails in chat (instead of toast/log only)", def: true }
];

var PLUGIN_PAGE = [
  { type: "info", text: "Your current mailbox and every address you create are kept by this plugin. Past addresses live in its database — Forget removes the saved account and that history." },
  { type: "action", id: "address", title: "Show current address", subtitle: "Toast + log the active temp email" },
  { type: "action", id: "copy", title: "Copy address", subtitle: "Copy the active temp email to the clipboard" },
  { type: "action", id: "inbox", title: "Check inbox", subtitle: "Fetch the inbox now and toast the result" },
  { type: "action", id: "clear", title: "Forget saved account", subtitle: "Delete the saved mailbox and the address history" }
];

var API = "https://api.mail.tm";

// Settings read: amin.settings.get when the bridge offers it, else
// the storage form (settings live in the same per-plugin store).
function settingGet(key, def) {
  try {
    if (amin.settings && typeof amin.settings.get === "function") {
      var v = amin.settings.get(key, def);
      return v === undefined || v === null ? def : v;
    }
  } catch (e) { /* fall through to storage */ }
  return amin.storage.get(key, def);
}

function postInChat() {
  return !!settingGet("postInChat", true);
}

function dbOk() {
  return !!(amin.db && typeof amin.db.put === "function");
}

function randName(n) {
  var chars = "abcdefghijklmnopqrstuvwxyz0123456789", s = "";
  for (var i = 0; i < n; i++) s += chars.charAt(Math.floor(Math.random() * chars.length));
  return s;
}

function saved() {
  var a = amin.storage.get("tm_address", "");
  if (!a) return null;
  return {
    login: amin.storage.get("tm_login", ""),
    domain: amin.storage.get("tm_domain", ""),
    address: a,
    password: amin.storage.get("tm_password", ""),
    token: amin.storage.get("tm_token", "")
  };
}

function storeAccount(login, domain, password) {
  amin.storage.set("tm_login", login);
  amin.storage.set("tm_domain", domain);
  amin.storage.set("tm_address", login + "@" + domain);
  amin.storage.set("tm_password", password);
  amin.storage.set("tm_token", "");
  recordAddress(login + "@" + domain, login, domain);
  return login + "@" + domain;
}

// --- amin.db: address history + one-way lazy migration ------------
// The active account stays in amin.storage (exactly where v2.0 kept
// it, so an update loses nothing). The db holds the address HISTORY.
// Migration is lazy and one-way: the first time the db history is
// touched, a pre-existing storage account is copied into it once
// (flag in the "meta" namespace); storage is never rewritten by it.
function ensureDbMigrated() {
  if (!dbOk()) return;
  try {
    if (amin.db.get("meta", "migrated")) return;
    amin.db.put("meta", "migrated", true);
    var keys = amin.db.keys("accounts", "");
    if (keys && keys.length) return;
    var mb = saved();
    if (mb) {
      amin.db.put("accounts", mb.address, {
        login: mb.login, domain: mb.domain, createdAt: 0, migrated: true
      });
    }
  } catch (e) { amin.debug("temp-mail db migrate skipped: " + e); }
}

function recordAddress(address, login, domain) {
  if (!dbOk()) return;
  try {
    ensureDbMigrated();
    amin.db.put("accounts", address, {
      login: login, domain: domain, createdAt: new Date().getTime()
    });
  } catch (e) { amin.debug("temp-mail record address failed: " + e); }
}

function historyCount() {
  if (!dbOk()) return 0;
  try {
    ensureDbMigrated();
    var keys = amin.db.keys("accounts", "");
    return keys ? keys.length : 0;
  } catch (e) { return 0; }
}

// --- mail.tm async layer ------------------------------------------
// Everything is continuation style on httpGetAsync/httpPostAsync:
// the sync forms block the shared plugin thread for up to ~30 s.
// Async POST delivers the error body on non-2xx (like sync POST);
// async GET delivers body null on error statuses, so a null GET
// body still means "maybe 401" — re-auth once, retry once.

function apiPost(path, bodyObj, fn) {
  amin.httpPostAsync(API + path, JSON.stringify(bodyObj || {}),
    { "Content-Type": "application/json" },
    function (res) { fn(res && res.body ? res.body : null); });
}

function apiGetRaw(path, headers, fn) {
  amin.httpGetAsync(API + path, headers, function (res) {
    fn(res && res.status >= 200 && res.status < 300 && res.body ? res.body : null);
  });
}

// POST /token for the account; stores and passes the bearer token
// (or null) to fn. mail.tm tokens are JWTs.
function authenticate(mb, fn) {
  apiPost("/token", { address: mb.address, password: mb.password }, function (raw) {
    try {
      if (raw) {
        var parsed = JSON.parse(raw);
        if (parsed && parsed.token) {
          amin.storage.set("tm_token", parsed.token);
          fn(parsed.token);
          return;
        }
        amin.log("temp-mail auth rejected: " + raw.substring(0, 200));
      }
    } catch (e) { amin.log("temp-mail auth failed: " + e); }
    fn(null);
  });
}

// Authenticated GET → fn(rawBodyOrNull): stored token first, one
// re-auth + one retry on a null body, exactly like v2.0.
function apiGetAuth(path, fn) {
  var mb = saved();
  if (!mb) { fn(null); return; }
  var attempt = function (token) {
    apiGetRaw(path, { Authorization: "Bearer " + token, Accept: "application/json" }, fn);
  };
  if (mb.token) {
    apiGetRaw(path, { Authorization: "Bearer " + mb.token, Accept: "application/json" },
      function (raw) {
        if (raw) { fn(raw); return; }
        authenticate(mb, function (token) {
          if (token) attempt(token); else fn(null);
        });
      });
    return;
  }
  authenticate(mb, function (token) {
    if (token) attempt(token); else fn(null);
  });
}

// mail.tm answers with JSON-LD collections ({"hydra:member":[...]}).
function memberList(parsed) {
  if (!parsed) return null;
  if (parsed["hydra:member"]) return parsed["hydra:member"];
  if (parsed instanceof Array) return parsed;
  return null;
}

function activeDomain(fn) {
  apiGetRaw("/domains", null, function (raw) {
    try {
      if (raw) {
        var list = memberList(JSON.parse(raw));
        if (list) {
          for (var i = 0; i < list.length; i++) {
            if (list[i] && list[i].domain && list[i].isActive !== false) {
              fn(list[i].domain);
              return;
            }
          }
        }
      }
    } catch (e) { amin.log("temp-mail domains failed: " + e); }
    fn(null);
  });
}

function createAddress(ctx) {
  activeDomain(function (domain) {
    if (!domain) { amin.toast("❌ mail.tm is unreachable"); return; }
    // One retry with a fresh login if the random one is taken.
    var tryCreate = function (attempt) {
      var login = randName(10);
      var password = randName(18);
      apiPost("/accounts", { address: login + "@" + domain, password: password },
        function (raw) {
          var ok = false;
          try {
            var parsed = raw ? JSON.parse(raw) : null;
            ok = !!(parsed && (parsed.address || parsed.id));
            if (!ok) amin.log("temp-mail create rejected: " + String(raw).substring(0, 200));
          } catch (e) { amin.log("temp-mail create failed: " + e); }
          if (ok) {
            var address = storeAccount(login, domain, password);
            authenticate(saved(), function (token) {
              if (!token) amin.log("temp-mail: account created but first auth failed");
              present(ctx, address);
            });
            return;
          }
          if (attempt < 1) { tryCreate(attempt + 1); return; }
          amin.toast("❌ Couldn't create a mailbox — try again");
        });
    };
    tryCreate(0);
  });
}

function present(ctx, address) {
  if (!address) return;
  amin.toast("📧 " + address);
  amin.log("temp-mail address: " + address);
  if (postInChat() && ctx) {
    amin.reply(ctx.msg || { chatId: ctx.chatId, id: 0 }, "📧 Temp email:\n" + address + "\n\nCheck it with /tm inbox");
  }
}

function stripHtml(html) {
  return String(html || "").replace(/<style[\s\S]*?<\/style>/gi, " ").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
}

function extractOtp(text) {
  var m = String(text || "").match(/\b(\d{4,8})\b/);
  return m ? m[1] : null;
}

function extractLinks(html, text) {
  var links = [], seen = {}, m;
  var re = /href\s*=\s*["'](https?:\/\/[^"']+)["']/gi;
  var src = String(html || "");
  while ((m = re.exec(src)) !== null) {
    var u = m[1].replace(/&amp;/g, "&");
    if (!seen[u] && !/\.(png|jpe?g|gif|css)($|\?)/i.test(u)) { seen[u] = 1; links.push(u); }
    if (links.length >= 5) break;
  }
  if (!links.length && text) {
    var re2 = /https?:\/\/[^\s<>"']+/g;
    while ((m = re2.exec(String(text))) !== null) {
      var v = m[0].replace(/[)\].,]+$/, "");
      if (!seen[v]) { seen[v] = 1; links.push(v); }
      if (links.length >= 5) break;
    }
  }
  return links;
}

function fromText(from) {
  if (!from) return "?";
  if (typeof from === "string") return from;
  return from.name ? (from.name + " <" + (from.address || "") + ">") : (from.address || "?");
}

function showMessage(ctx, id) {
  apiGetAuth("/messages/" + encodeURIComponent(id), function (raw) {
    try {
      if (!raw) { amin.toast("❌ Couldn't fetch that email"); return; }
      var mail = JSON.parse(raw);
      var html = (mail.html && mail.html.length) ? mail.html.join("\n") : "";
      var body = mail.text || stripHtml(html);
      var from = fromText(mail.from);
      var otp = extractOtp(body) || extractOtp(mail.subject || "");
      var links = extractLinks(html, body);
      amin.log("temp-mail message " + id + " from " + from + " subject: " + mail.subject + (links.length ? "\nLinks: " + links.join(" | ") : "") + "\n" + body);
      amin.toast("📩 " + from + " — " + (mail.subject || "") + (otp ? " — code: " + otp : (links.length ? " — 🔗 link inside" : "")));
      if (postInChat() && ctx) {
        var out = "📩 From: " + from + "\nSubject: " + (mail.subject || "") + "\nDate: " + (mail.createdAt || "");
        if (otp) out += "\n🔑 Code: " + otp;
        out += "\n\n" + String(body || "").substring(0, 800);
        if (links.length) out += "\n\n🔗 Links:\n" + links.join("\n");
        amin.reply(ctx.msg || { chatId: ctx.chatId, id: 0 }, out);
      }
    } catch (e) { amin.log("temp-mail show error: " + e); amin.toast("❌ temp-mail failed — see plugin log"); }
  });
}

function doInbox(ctx) {
  var mb = saved();
  if (!mb) { amin.toast("No temp email yet — use /tm new"); return; }
  apiGetAuth("/messages", function (raw) {
    try {
      if (!raw) { amin.toast("❌ Inbox check failed"); return; }
      var list = memberList(JSON.parse(raw));
      if (!list || !list.length) { amin.toast("📭 Inbox is empty for " + mb.address); return; }
      // mail.tm returns newest first.
      var lines = [];
      for (var i = 0; i < list.length && i < 10; i++) {
        lines.push("• " + list[i].id + " — " + fromText(list[i].from) + " — " + (list[i].subject || "(no subject)"));
      }
      amin.log("temp-mail inbox for " + mb.address + ":\n" + lines.join("\n"));
      if (postInChat() && ctx) {
        amin.reply(ctx.msg || { chatId: ctx.chatId, id: 0 },
          "📬 Inbox for " + mb.address + " (" + list.length + "):\n" + lines.join("\n") + "\n\nOpen one with /tm read <id> — opening the latest now:");
      }
      amin.toast("📬 " + list.length + " message(s) — opening latest");
      showMessage(ctx, list[0].id);
    } catch (e) { amin.log("temp-mail inbox error: " + e); amin.toast("❌ temp-mail failed — see plugin log"); }
  });
}

function usageText() {
  return "Temp Mail commands:\n/tm new — new address\n/tm — show current address (creates one if none)\n/tm inbox — check inbox (auto-opens latest)\n/tm read <id> — open a specific email";
}

function onLoad() {
  try { ensureDbMigrated(); } catch (e) { amin.debug("temp-mail onLoad: " + e); }
}

function onCommand(ctx) {
  try {
    if (ctx.command !== "tm") return;
    var args = (ctx.args || "").trim();
    if (args === "new") {
      createAddress(ctx);
    } else if (args === "inbox") {
      doInbox(ctx);
    } else if (args.indexOf("read ") === 0) {
      var mb = saved();
      if (!mb) { amin.toast("No temp email yet — use /tm new"); return; }
      var id = args.substring(5).trim();
      if (id) showMessage(ctx, id); else amin.toast("Usage: /tm read <id>");
    } else if (args === "read") {
      amin.reply(ctx.msg, "Usage: /tm read <id> — get ids from /tm inbox");
    } else if (args === "") {
      var cur = saved();
      if (cur) present(ctx, cur.address);
      else createAddress(ctx);
    } else {
      amin.reply(ctx.msg, usageText());
    }
  } catch (e) { amin.log("temp-mail onCommand error: " + e); amin.toast("❌ temp-mail failed — see plugin log"); }
}

function onMenuAction(ctx) {
  try {
    var cur = saved();
    if (cur) present(ctx, cur.address);
    else createAddress(ctx);
  } catch (e) { amin.log("temp-mail onMenuAction error: " + e); }
}

function onPluginAction(actionId) {
  try {
    if (actionId === "address") {
      var cur = saved();
      if (cur) {
        amin.toast("📧 " + cur.address);
        amin.log("temp-mail address: " + cur.address + " (history: " + historyCount() + " address(es))");
      } else {
        amin.toast("No temp email yet — send /tm new in any chat");
      }
      return;
    }
    if (actionId === "copy") {
      var mb = saved();
      if (!mb) { amin.toast("No temp email yet — send /tm new in any chat"); return; }
      if (amin.ui && amin.ui.clipboardWrite(mb.address)) {
        amin.toast("📋 Copied: " + mb.address);
      } else {
        amin.toast("📧 " + mb.address);
      }
      return;
    }
    if (actionId === "inbox") {
      doInbox(null);
      return;
    }
    if (actionId === "clear") {
      amin.storage.set("tm_login", null);
      amin.storage.set("tm_domain", null);
      amin.storage.set("tm_address", null);
      amin.storage.set("tm_password", null);
      amin.storage.set("tm_token", null);
      if (dbOk()) { try { amin.db.clear("accounts"); } catch (e) { /* best effort */ } }
      amin.toast("🗑 Temp-mail account forgotten");
      amin.log("temp-mail: saved account + address history cleared from the plugin page");
    }
  } catch (e) { amin.log("temp-mail onPluginAction error: " + e); }
}
