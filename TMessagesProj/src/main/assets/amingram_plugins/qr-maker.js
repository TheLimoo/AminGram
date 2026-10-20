// ==AminGramPlugin==
// @id          qr-maker
// @name        QR Maker
// @version     1.0
// @author      Amin
// @description /qr <text or link> posts a QR code image for it (qrserver.com). If the image cannot be sent, the QR image link is posted instead.
// @icon        sparkles
// @capabilities commands, send, http, file
// ==/AminGramPlugin==

// QR images come from the keyless goqr.me (qrserver) API — the
// plugin never downloads anything itself: amin.sendFile(url, opts)
// fetches and posts the image. sendFile is SYNCHRONOUS and returns
// a boolean; false (or the bridge missing it, i.e. no `file`
// capability) falls back to posting the image URL as a plain link.
var QR_API = "https://api.qrserver.com/v1/create-qr-code/?size=512x512&margin=10&data=";
var MAX_TEXT = 1000;

function qrUrlFor(text) {
  return QR_API + encodeURIComponent(text);
}

function shortLabel(text) {
  var t = String(text || "").replace(/\s+/g, " ").trim();
  return t.length > 80 ? t.substring(0, 80) + "…" : t;
}

function onCommand(ctx) {
  try {
    if (String(ctx.command || "").toLowerCase() !== "qr") return;
    var text = (ctx.args || "").trim();
    if (!text) {
      amin.reply(ctx.msg, "Usage: /qr <text or link>");
      return;
    }
    if (text.length > MAX_TEXT) {
      amin.reply(ctx.msg, "⚠️ That text is too long for a QR code (max " + MAX_TEXT + " characters).");
      return;
    }
    var url = qrUrlFor(text);
    var sent = false;
    try {
      if (typeof amin.sendFile === "function") {
        sent = amin.sendFile(url, {
          chatId: ctx.chatId,
          type: "photo",
          caption: "QR for: " + shortLabel(text)
        }) === true;
      }
    } catch (e) {
      amin.log("qr-maker sendFile error: " + e);
      sent = false;
    }
    if (sent) {
      amin.log("qr-maker sent QR photo for: " + shortLabel(text));
      return;
    }
    // Fallback: the image link itself — tapping it shows the QR.
    amin.log("qr-maker sendFile unavailable/failed, posting link for: " + shortLabel(text));
    amin.reply(ctx.msg, "🔳 QR code for: " + shortLabel(text) + "\n" + url);
  } catch (e) {
    amin.log("qr-maker onCommand error: " + e);
    try { amin.reply(ctx.msg, "⚠️ QR failed — see the plugin log."); } catch (e2) {}
  }
}
