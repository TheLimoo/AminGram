// ==AminGramPlugin==
// @id          temp-mail
// @name        Temp Mail
// @version     1.0
// @author      Amin
// @description Generate disposable emails and read incoming verification codes inside Telegram. Commands: /tm new, /tm, /tm inbox, /tm read <id> — or tap Temp Mail in the chat attach menu.
// @capabilities commands, send, http, settings, menu
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "postInChat", type: "bool", title: "Post emails in chat (instead of toast/log only)", def: true },
  { key: "domain", type: "text", title: "Fallback domain if random mailbox API fails", def: "1secmail.com" }
];

var API = "https://www.1secmail.com/api/v1/";

function randName(n) {
  var chars = "abcdefghijklmnopqrstuvwxyz0123456789", s = "";
  for (var i = 0; i < n; i++) s += chars.charAt(Math.floor(Math.random() * chars.length));
  return s;
}

function saved() {
  var a = amin.storage.get("tm_address", "");
  if (!a) return null;
  return { login: amin.storage.get("tm_login", ""), domain: amin.storage.get("tm_domain", ""), address: a };
}

function store(login, domain) {
  amin.storage.set("tm_login", login);
  amin.storage.set("tm_domain", domain);
  amin.storage.set("tm_address", login + "@" + domain);
  return login + "@" + domain;
}

function createAddress() {
  try {
    var raw = amin.httpGet(API + "?action=genRandomMailbox&count=1");
    if (raw) {
      var arr = JSON.parse(raw);
      if (arr && arr.length && String(arr[0]).indexOf("@") > 0) {
        var parts = String(arr[0]).split("@");
        return store(parts[0], parts[1]);
      }
    }
  } catch (e) { amin.log("temp-mail genRandomMailbox failed: " + e); }
  var domain = amin.storage.get("domain", "1secmail.com") || "1secmail.com";
  return store(randName(10), domain);
}

function present(ctx, address, viaMenu) {
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

function showMessage(ctx, mb, id) {
  var raw = amin.httpGet(API + "?action=readMessage&login=" + encodeURIComponent(mb.login) + "&domain=" + encodeURIComponent(mb.domain) + "&id=" + id);
  if (!raw) { amin.toast("❌ Couldn't fetch that email"); return; }
  var mail = JSON.parse(raw);
  var body = mail.textBody || stripHtml(mail.htmlBody || "");
  var otp = extractOtp(body) || extractOtp(mail.subject || "");
  var links = extractLinks(mail.htmlBody || "", body);
  amin.log("temp-mail message " + id + " from " + mail.from + " subject: " + mail.subject + (links.length ? "\nLinks: " + links.join(" | ") : "") + "\n" + body);
  amin.toast("📩 " + (mail.from || "") + " — " + (mail.subject || "") + (otp ? " — code: " + otp : (links.length ? " — 🔗 link inside" : "")));
  if (amin.storage.get("postInChat", true) && ctx) {
    var out = "📩 From: " + (mail.from || "?") + "\nSubject: " + (mail.subject || "") + "\nDate: " + (mail.date || "");
    if (otp) out += "\n🔑 Code: " + otp;
    out += "\n\n" + String(body || "").substring(0, 800);
    if (links.length) out += "\n\n🔗 Links:\n" + links.join("\n");
    amin.reply(ctx.msg || { chatId: ctx.chatId, id: 0 }, out);
  }
}

function doInbox(ctx) {
  var mb = saved();
  if (!mb) { amin.toast("No temp email yet — use /tm new"); return; }
  var raw = amin.httpGet(API + "?action=getMessages&login=" + encodeURIComponent(mb.login) + "&domain=" + encodeURIComponent(mb.domain));
  if (!raw) { amin.toast("❌ Inbox check failed"); return; }
  var list = JSON.parse(raw);
  if (!list || !list.length) { amin.toast("📭 Inbox is empty for " + mb.address); return; }
  var latest = list[0];
  for (var i = 1; i < list.length; i++) if (list[i].id > latest.id) latest = list[i];
  amin.toast("📬 " + list.length + " message(s) — opening latest");
  showMessage(ctx, mb, latest.id);
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
      var id = parseInt(args.substring(5), 10);
      if (id) showMessage(ctx, mb, id); else amin.toast("Usage: /tm read <id>");
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
