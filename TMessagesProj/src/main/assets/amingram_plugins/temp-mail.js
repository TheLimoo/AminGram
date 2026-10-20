// ==AminGramPlugin==
// @id          temp-mail
// @name        Temp Mail
// @version     2.0
// @author      Amin
// @description Disposable email via mail.tm: /tm new creates a real account, /tm inbox lists mail, /tm read <id> opens one — verification codes and links are extracted and posted in chat. (v2: mail.tm replaced the dead 1secmail.)
// @capabilities commands, send, http, settings, menu
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "postInChat", type: "bool", title: "Post emails in chat (instead of toast/log only)", def: true }
];

var API = "https://api.mail.tm";

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
  return login + "@" + domain;
}

// POST /token for the saved account; stores and returns the bearer
// token, or null. mail.tm tokens are JWTs; on any 401 from an
// authenticated call we re-auth ONCE and retry (see apiGetAuth).
function authenticate(mb) {
  try {
    var raw = amin.httpPost(API + "/token",
      JSON.stringify({ address: mb.address, password: mb.password }),
      { "Content-Type": "application/json" });
    if (!raw) return null;
    var parsed = JSON.parse(raw);
    if (parsed && parsed.token) {
      amin.storage.set("tm_token", parsed.token);
      return parsed.token;
    }
    amin.log("temp-mail auth rejected: " + raw.substring(0, 200));
  } catch (e) { amin.log("temp-mail auth failed: " + e); }
  return null;
}

// Authenticated GET with the v2 header-capable bridge overload.
// The bridge returns null on ANY error status, so a null here
// means "maybe 401": re-authenticate once and retry once; a
// second null is a real failure.
function apiGetAuth(path) {
  var mb = saved();
  if (!mb) return null;
  var token = mb.token || authenticate(mb);
  if (!token) return null;
  var raw = amin.httpGet(API + path, { Authorization: "Bearer " + token, Accept: "application/json" });
  if (raw) return raw;
  token = authenticate(mb);
  if (!token) return null;
  return amin.httpGet(API + path, { Authorization: "Bearer " + token, Accept: "application/json" });
}

// mail.tm answers with JSON-LD collections ({"hydra:member":[...]}).
function memberList(parsed) {
  if (!parsed) return null;
  if (parsed["hydra:member"]) return parsed["hydra:member"];
  if (parsed instanceof Array) return parsed;
  return null;
}

function activeDomain() {
  var raw = amin.httpGet(API + "/domains");
  if (!raw) return null;
  var list = memberList(JSON.parse(raw));
  if (!list) return null;
  for (var i = 0; i < list.length; i++) {
    if (list[i] && list[i].domain && list[i].isActive !== false) return list[i].domain;
  }
  return null;
}

function createAddress() {
  var domain = activeDomain();
  if (!domain) { amin.toast("❌ mail.tm is unreachable"); return null; }
  // One retry with a fresh login if the random one is taken.
  for (var attempt = 0; attempt < 2; attempt++) {
    var login = randName(10);
    var password = randName(18);
    try {
      var raw = amin.httpPost(API + "/accounts",
        JSON.stringify({ address: login + "@" + domain, password: password }),
        { "Content-Type": "application/json" });
      var parsed = raw ? JSON.parse(raw) : null;
      if (parsed && (parsed.address || parsed.id)) {
        storeAccount(login, domain, password);
        if (authenticate(saved())) return login + "@" + domain;
        amin.log("temp-mail: account created but first auth failed");
        return login + "@" + domain;
      }
      amin.log("temp-mail create rejected: " + String(raw).substring(0, 200));
    } catch (e) { amin.log("temp-mail create failed: " + e); }
  }
  amin.toast("❌ Couldn't create a mailbox — try again");
  return null;
}

function present(ctx, address, viaMenu) {
  if (!address) return;
  amin.toast("📧 " + address);
  amin.log("temp-mail address: " + address);
  if (amin.storage.get("postInChat", true) && ctx) {
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
  var raw = apiGetAuth("/messages/" + encodeURIComponent(id));
  if (!raw) { amin.toast("❌ Couldn't fetch that email"); return; }
  var mail = JSON.parse(raw);
  var html = (mail.html && mail.html.length) ? mail.html.join("\n") : "";
  var body = mail.text || stripHtml(html);
  var from = fromText(mail.from);
  var otp = extractOtp(body) || extractOtp(mail.subject || "");
  var links = extractLinks(html, body);
  amin.log("temp-mail message " + id + " from " + from + " subject: " + mail.subject + (links.length ? "\nLinks: " + links.join(" | ") : "") + "\n" + body);
  amin.toast("📩 " + from + " — " + (mail.subject || "") + (otp ? " — code: " + otp : (links.length ? " — 🔗 link inside" : "")));
  if (amin.storage.get("postInChat", true) && ctx) {
    var out = "📩 From: " + from + "\nSubject: " + (mail.subject || "") + "\nDate: " + (mail.createdAt || "");
    if (otp) out += "\n🔑 Code: " + otp;
    out += "\n\n" + String(body || "").substring(0, 800);
    if (links.length) out += "\n\n🔗 Links:\n" + links.join("\n");
    amin.reply(ctx.msg || { chatId: ctx.chatId, id: 0 }, out);
  }
}

function doInbox(ctx) {
  var mb = saved();
  if (!mb) { amin.toast("No temp email yet — use /tm new"); return; }
  var raw = apiGetAuth("/messages");
  if (!raw) { amin.toast("❌ Inbox check failed"); return; }
  var list = memberList(JSON.parse(raw));
  if (!list || !list.length) { amin.toast("📭 Inbox is empty for " + mb.address); return; }
  // mail.tm returns newest first.
  var lines = [];
  for (var i = 0; i < list.length && i < 10; i++) {
    lines.push("• " + list[i].id + " — " + fromText(list[i].from) + " — " + (list[i].subject || "(no subject)"));
  }
  amin.log("temp-mail inbox for " + mb.address + ":\n" + lines.join("\n"));
  if (amin.storage.get("postInChat", true) && ctx) {
    amin.reply(ctx.msg || { chatId: ctx.chatId, id: 0 },
      "📬 Inbox for " + mb.address + " (" + list.length + "):\n" + lines.join("\n") + "\n\nOpen one with /tm read <id> — opening the latest now:");
  }
  amin.toast("📬 " + list.length + " message(s) — opening latest");
  showMessage(ctx, list[0].id);
}

function onCommand(ctx) {
  try {
    if (ctx.command !== "tm") return;
    var args = (ctx.args || "").trim();
    if (args === "new") {
      present(ctx, createAddress(), false);
    } else if (args === "inbox") {
      doInbox(ctx);
    } else if (args.indexOf("read ") === 0) {
      var mb = saved();
      if (!mb) { amin.toast("No temp email yet — use /tm new"); return; }
      var id = args.substring(5).trim();
      if (id) showMessage(ctx, id); else amin.toast("Usage: /tm read <id>");
    } else if (args === "") {
      var cur = saved();
      if (cur) present(ctx, cur.address, false);
      else present(ctx, createAddress(), false);
    } else {
      amin.reply(ctx.msg, "Temp Mail commands:\n/tm new — new address\n/tm — show current address\n/tm inbox — check inbox (auto-opens latest)\n/tm read <id> — open a specific email");
    }
  } catch (e) { amin.log("temp-mail onCommand error: " + e); amin.toast("❌ temp-mail failed — see plugin log"); }
}

function onMenuAction(ctx) {
  var cur = saved();
  if (cur) present(ctx, cur.address, true);
  else present(ctx, createAddress(), true);
}
