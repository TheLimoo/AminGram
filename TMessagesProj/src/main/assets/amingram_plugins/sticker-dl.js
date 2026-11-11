// ==AminGramPlugin==
// @id          sticker-dl
// @name        Sticker Downloader
// @version     1.0.0
// @author      Amin
// @description Exports stickers, custom emoji, and media messages into the chat as PNG images or raw .webp/.tgs/.webm files. Reply to a sticker with /sticker, /stpng, /straw, or /stinfo — or long-press a sticker and tap “Save Sticker / PNG”.
// @icon        camera
// @menuLabel   Save last sticker
// @capabilities observe, commands, send, file, settings, menu, ui
// @commands    sticker, stpng, straw, stinfo
// @contextMenu Save Sticker / PNG
// @minPlatform 2
// @dataVersion 1
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "defaultFormat", type: "select", title: "Default export format", def: "png",
    options: [
      { value: "png", label: "PNG file (converts static WebP stickers to PNG)" },
      { value: "raw", label: "Raw file (.webp / .tgs / .webm)" }
    ] },
  { key: "sendAsPhoto", type: "bool", title: "Send static stickers as inline Photo instead of Document", def: false },
  { key: "includeMetadata", type: "bool", title: "Include sticker pack & emoji info in caption", def: true },
  { key: "keepInSandbox", type: "bool", title: "Also save a copy in plugin sandbox (amin.files)", def: false }
];

var PLUGIN_PAGE = [
  { type: "info", text: "Sticker Downloader exports static (.webp -> .png/.webp), video (.webm), and animated Lottie (.tgs) stickers using AminGram's enriched media descriptor and amin.sendMessageMedia bridge." },
  { type: "action", id: "stats", title: "Export statistics", subtitle: "Show total stickers exported and sandbox files" },
  { type: "action", id: "clear-sandbox", title: "Clear saved sandbox copies", subtitle: "Remove exported files stored in amin.files" }
];

var lastStickerByChat = {};

function str(v) {
  return v === null || v === undefined ? "" : String(v);
}

function settingStr(key, defVal) {
  try {
    var v = amin.storage.get(key, defVal);
    return v ? str(v) : defVal;
  } catch (e) { return defVal; }
}

function settingBool(key, defVal) {
  try {
    var v = amin.storage.get(key, defVal);
    if (typeof v === "boolean") return v;
    if (typeof v === "string") return v === "true" || v === "1";
    return !!v;
  } catch (e) { return defVal; }
}

function isStickerOrMedia(msg) {
  if (!msg || !msg.media) return false;
  var k = str(msg.media.kind).toLowerCase();
  return k === "sticker" || k === "photo" || k === "gif" || k === "document" || k === "video";
}

function rememberSticker(msg) {
  if (!msg || !msg.media) return;
  if (str(msg.media.kind).toLowerCase() !== "sticker") return;
  var key = str(msg.chatId);
  lastStickerByChat[key] = { chatId: msg.chatId, id: msg.id, media: msg.media };
  try {
    if (amin.db && typeof amin.db.put === "function") {
      amin.db.put("last_sticker", "chat:" + key, { chatId: msg.chatId, id: msg.id, at: new Date().getTime() });
    }
  } catch (e) {}
}

function resolveTargetMessage(ctx) {
  if (ctx && ctx.replyTo && ctx.replyTo.msg && isStickerOrMedia(ctx.replyTo.msg)) {
    return ctx.replyTo.msg;
  }
  if (ctx && ctx.msg && ctx.msg.replyToId && typeof amin.getMessage === "function") {
    var replied = amin.getMessage(ctx.chatId, ctx.msg.replyToId);
    if (replied && isStickerOrMedia(replied)) return replied;
  }
  if (ctx && ctx.msg && isStickerOrMedia(ctx.msg)) {
    return ctx.msg;
  }
  var key = str(ctx && ctx.chatId);
  var mem = lastStickerByChat[key];
  if (mem) {
    if (typeof amin.getMessage === "function") {
      var loaded = amin.getMessage(mem.chatId, mem.id);
      if (loaded && isStickerOrMedia(loaded)) return loaded;
    }
    if (isStickerOrMedia(mem)) return mem;
  }
  try {
    if (amin.db && typeof amin.db.get === "function") {
      var rec = amin.db.get("last_sticker", "chat:" + key);
      if (rec && rec.id && typeof amin.getMessage === "function") {
        var fromDb = amin.getMessage(ctx.chatId, rec.id);
        if (fromDb && isStickerOrMedia(fromDb)) return fromDb;
      }
    }
  } catch (e) {}
  return null;
}

function buildMetadataCaption(msg, forcedFormat) {
  var m = (msg && msg.media) ? msg.media : {};
  var lines = [];
  var kind = str(m.kind || "media");
  var emoji = str(m.stickerEmoji || "");
  lines.push((emoji ? emoji + " " : "🎨 ") + (kind === "sticker" ? "Sticker Export" : "Media Export"));
  if (m.stickerSetShortName) {
    lines.push("Pack: https://t.me/addstickers/" + m.stickerSetShortName);
  } else if (m.stickerSetId) {
    lines.push("Pack ID: " + m.stickerSetId);
  }
  if (m.width && m.height) {
    lines.push("Size: " + m.width + "×" + m.height + (m.sizeBytes ? " (" + Math.round(m.sizeBytes / 1024) + " KB)" : ""));
  }
  if (m.mimeType) {
    lines.push("MIME: " + m.mimeType + (forcedFormat === "png" ? " → image/png" : ""));
  }
  if (m.id) {
    lines.push("Doc ID: " + m.id + (m.dcId ? " · DC " + m.dcId : ""));
  }
  return lines.join("\n");
}

function bumpExportCount() {
  try {
    if (amin.db && typeof amin.db.get === "function") {
      var c = Number(amin.db.get("stats", "exports") || 0) + 1;
      amin.db.put("stats", "exports", c);
    }
  } catch (e) {}
}

function exportSticker(chatId, targetMsg, mode) {
  if (!targetMsg || !targetMsg.media) {
    amin.send(chatId, "ℹ️ Reply to a sticker (or send a sticker first) and use /sticker, /stpng, /straw, or /stinfo.");
    return;
  }
  var m = targetMsg.media;
  if (mode === "info") {
    var infoText = buildMetadataCaption(targetMsg, "raw");
    if (m.localPath) {
      infoText += "\nCached: " + (m.isCached ? "yes" : "no");
    }
    amin.reply(targetMsg, infoText);
    if (m.stickerSetShortName && amin.ui && typeof amin.ui.clipboardWrite === "function") {
      amin.ui.clipboardWrite("https://t.me/addstickers/" + m.stickerSetShortName);
      amin.toast("📋 Sticker pack link copied");
    }
    return;
  }

  if (typeof amin.sendMessageMedia !== "function") {
    amin.reply(targetMsg, "⚠️ This build does not expose amin.sendMessageMedia yet.\n\n" + buildMetadataCaption(targetMsg, "raw"));
    return;
  }

  var fmt = mode || settingStr("defaultFormat", "png");
  var isAnim = !!(m.isAnimatedSticker || m.isVideoSticker);
  if (isAnim && fmt === "png") {
    fmt = "raw";
  }
  var asPhoto = settingBool("sendAsPhoto", false) && !isAnim && fmt === "png";
  var caption = settingBool("includeMetadata", true) ? buildMetadataCaption(targetMsg, fmt) : "";
  var opts = {
    chatId: chatId,
    caption: caption,
    format: fmt,
    asPhoto: asPhoto
  };
  if (settingBool("keepInSandbox", false)) {
    var ext = (fmt === "png" && !isAnim) ? "png" : (m.isAnimatedSticker ? "tgs" : (m.isVideoSticker ? "webm" : "webp"));
    opts.saveToSandbox = "sticker_" + targetMsg.id + "." + ext;
  }
  var ok = amin.sendMessageMedia(targetMsg, opts);
  if (ok) {
    bumpExportCount();
    amin.toast("✅ Sticker exported (" + fmt.toUpperCase() + ")");
  } else {
    amin.reply(targetMsg, "⚠️ Could not export sticker file (tap the sticker once so Telegram downloads it to cache, then retry).\n\n"
      + buildMetadataCaption(targetMsg, "raw"));
  }
}

function onMessage(msg) {
  try {
    rememberSticker(msg);
  } catch (e) {}
}

function onCommand(ctx) {
  try {
    if (!ctx) return;
    var cmd = str(ctx.command).toLowerCase().split("@")[0];
    if (cmd !== "sticker" && cmd !== "stpng" && cmd !== "straw" && cmd !== "stinfo") return;
    var arg = str(ctx.args).trim().toLowerCase();
    var mode = null;
    if (cmd === "stpng" || arg === "png") mode = "png";
    else if (cmd === "straw" || arg === "raw" || arg === "webp") mode = "raw";
    else if (cmd === "stinfo" || arg === "info") mode = "info";
    var target = resolveTargetMessage(ctx);
    exportSticker(ctx.chatId, target, mode);
  } catch (e) {
    amin.error("sticker-dl onCommand error: " + e);
  }
}

function onContextAction(ctx) {
  try {
    if (!ctx || !ctx.msg) return;
    rememberSticker(ctx.msg);
    exportSticker(ctx.chatId, ctx.msg, null);
  } catch (e) {
    amin.error("sticker-dl onContextAction error: " + e);
  }
}

function onMenuAction(ctx) {
  try {
    if (!ctx) return;
    var target = resolveTargetMessage(ctx);
    if (!target) {
      amin.toast("Send or view a sticker in this chat first, or long-press any sticker → Save Sticker / PNG");
      return;
    }
    exportSticker(ctx.chatId, target, null);
  } catch (e) {
    amin.error("sticker-dl onMenuAction error: " + e);
  }
}

function onPluginAction(actionId) {
  try {
    if (actionId === "stats") {
      var count = 0;
      if (amin.db && typeof amin.db.get === "function") {
        count = Number(amin.db.get("stats", "exports") || 0);
      }
      var files = (amin.files && typeof amin.files.list === "function") ? (amin.files.list() || []) : [];
      amin.toast("🎨 " + count + " sticker(s) exported · " + files.length + " sandbox file(s)");
      return;
    }
    if (actionId === "clear-sandbox") {
      var list = (amin.files && typeof amin.files.list === "function") ? (amin.files.list() || []) : [];
      var removed = 0;
      for (var i = 0; i < list.length; i++) {
        if (list[i] && list[i].name && amin.files.remove(list[i].name)) removed++;
      }
      amin.toast("🧹 Removed " + removed + " sandbox sticker file(s)");
    }
  } catch (e) {
    amin.error("sticker-dl onPluginAction error: " + e);
  }
}
