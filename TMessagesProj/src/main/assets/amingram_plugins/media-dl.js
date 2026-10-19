// ==AminGramPlugin==
// @id          media-dl
// @name        Media Downloader
// @version     1.0
// @author      Amin
// @description Extracts direct MP4/MP3 download links from TikTok, X/Twitter, Reddit and Instagram URLs. Commands: /dl, /mp3 — or tap Media Downloader in the chat attach menu.
// @capabilities observe, commands, send, http, settings, menu
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "autoToastLinks", type: "bool", title: "Toast & log direct link when someone sends a video URL", def: false },
  { key: "includeAudio", type: "bool", title: "Include MP3 audio link when available", def: true }
];

var lastByChat = {};

var URL_RE = /https?:\/\/[^\s]+/g;
var TIKTOK_RE = /(vm\.|vt\.)?tiktok\.com/i;
var X_RE = /(^|\.)?(x\.com|twitter\.com)\//i;
var REDDIT_RE = /reddit\.com\/r\/[^/]+\/comments\//i;
var INSTA_RE = /instagram\.com\/(reel|p)\//i;

function findSupportedUrl(text) {
  if (!text) return null;
  var m = text.match(URL_RE);
  if (!m) return null;
  for (var i = 0; i < m.length; i++) {
    var u = m[i].replace(/[)\].,]+$/, "");
    if (TIKTOK_RE.test(u) || X_RE.test(u) || REDDIT_RE.test(u) || INSTA_RE.test(u)) return u;
  }
  return null;
}

function resolveTikTok(url) {
  var raw = amin.httpGet("https://www.tikwm.com/api/?url=" + encodeURIComponent(url));
  if (!raw) return null;
  var res = JSON.parse(raw);
  if (!res || !res.data) return null;
  var d = res.data;
  return {
    title: d.title || "TikTok video",
    video: d.hdplay || d.play || null,
    videoSd: d.play || null,
    audio: d.music || null
  };
}

function resolveX(url) {
  var m = url.match(/(?:x\.com|twitter\.com)\/([^/]+)\/status\/(\d+)/i);
  if (!m) return null;
  var raw = amin.httpGet("https://api.vxtwitter.com/" + m[1] + "/status/" + m[2]);
  if (!raw) return null;
  var res = JSON.parse(raw);
  if (!res) return null;
  var videos = [], images = [];
  var list = res.media_extended || [];
  for (var i = 0; i < list.length; i++) {
    var u = list[i].url || "";
    if (/\.mp4($|\?)/i.test(u)) videos.push(u);
    else if (/\.(jpg|jpeg|png|webp)($|\?)/i.test(u)) images.push(u);
  }
  if (!videos.length && res.mediaURLs) {
    for (var j = 0; j < res.mediaURLs.length; j++) {
      if (/\.mp4($|\?)/i.test(res.mediaURLs[j])) videos.push(res.mediaURLs[j]);
    }
  }
  var who = res.user_name ? (" by " + res.user_name) : "";
  return {
    title: (res.text ? res.text.substring(0, 120) : "X post") + who,
    video: videos.length ? videos[0] : null,
    allVideos: videos,
    images: images,
    audio: null
  };
}

function resolveReddit(url) {
  var clean = url.split("?")[0];
  if (clean.charAt(clean.length - 1) !== "/") clean += "/";
  var raw = amin.httpGet(clean + ".json");
  if (!raw) return null;
  var res = JSON.parse(raw);
  var post = res && res[0] && res[0].data && res[0].data.children && res[0].data.children[0] && res[0].data.children[0].data;
  if (!post) return null;
  var video = null;
  if (post.secure_media && post.secure_media.reddit_video) video = post.secure_media.reddit_video.fallback_url || null;
  return { title: post.title || "Reddit post", video: video, audio: null };
}

function resolveInstagram(url) {
  var clean = url.split("?")[0];
  return {
    title: "Instagram post",
    video: clean.replace("instagram.com", "ddinstagram.com"),
    videoBackup: clean.replace("instagram.com", "kkinstagram.com"),
    audio: null,
    note: "Instagram blocks direct files — this embed link opens the post with a download button."
  };
}

function resolveAny(url) {
  if (TIKTOK_RE.test(url)) return resolveTikTok(url);
  if (X_RE.test(url)) return resolveX(url);
  if (REDDIT_RE.test(url)) return resolveReddit(url);
  if (INSTA_RE.test(url)) return resolveInstagram(url);
  return null;
}

function formatResult(r, url) {
  if (!r) return "❌ Couldn't resolve that link.";
  var out = "🎬 " + (r.title || "Media") + "\nSource: " + url;
  if (r.video) out += "\n\n📹 Video (MP4):\n" + r.video;
  if (r.videoSd && r.video && r.videoSd !== r.video) out += "\n📹 Video (SD):\n" + r.videoSd;
  if (r.allVideos && r.allVideos.length > 1) {
    for (var i = 1; i < r.allVideos.length; i++) out += "\n📹 Video " + (i + 1) + ":\n" + r.allVideos[i];
  }
  if (r.videoBackup) out += "\nBackup: " + r.videoBackup;
  if (r.images && r.images.length) out += "\n🖼 Images: " + r.images.length + " (first):\n" + r.images[0];
  if (amin.storage.get("includeAudio", true) && r.audio) out += "\n\n🎵 Audio (MP3):\n" + r.audio;
  if (r.note) out += "\n\nℹ️ " + r.note;
  if (!r.video && !r.audio) out += "\n\n(no direct media found in that post)";
  return out;
}

function doResolve(chatId, msg, url) {
  try {
    var r = resolveAny(url);
    amin.log("media-dl resolved " + url + " -> " + (r && r.video ? r.video : "none"));
    amin.reply(msg, formatResult(r, url));
    amin.toast(r && (r.video || r.audio) ? "✅ Media link resolved" : "❌ Couldn't resolve that link");
  } catch (e) {
    amin.log("media-dl error: " + e);
    amin.toast("❌ media-dl failed — see plugin log");
  }
}

function onMessage(msg) {
  try {
    var url = findSupportedUrl(msg.text || "");
    if (url) lastByChat[String(msg.chatId)] = url;
    if (url && !msg.outgoing && !msg.channel && amin.storage.get("autoToastLinks", false)
        && (TIKTOK_RE.test(url) || X_RE.test(url))) {
      var r = resolveAny(url);
      if (r && r.video) {
        amin.log("media-dl auto: " + url + " -> " + r.video);
        amin.toast("🎬 Direct link found — see Media Downloader log");
      }
    }
  } catch (e) { amin.log("media-dl onMessage error: " + e); }
}

function onCommand(ctx) {
  try {
    if (ctx.command === "dl") {
      var url = (ctx.args || "").trim() || lastByChat[String(ctx.chatId)];
      if (!url) { amin.reply(ctx.msg, "Usage: /dl <url> — or send a TikTok / X / Reddit / Instagram link in this chat first."); return; }
      doResolve(ctx.chatId, ctx.msg, url);
    } else if (ctx.command === "mp3") {
      var u2 = (ctx.args || "").trim() || lastByChat[String(ctx.chatId)];
      if (!u2) { amin.reply(ctx.msg, "Usage: /mp3 <tiktok url>"); return; }
      if (!TIKTOK_RE.test(u2)) { amin.reply(ctx.msg, "🎵 MP3 extraction works for TikTok links."); return; }
      var r = resolveTikTok(u2);
      if (r && r.audio) { amin.reply(ctx.msg, "🎵 Audio (MP3):\n" + r.audio); amin.toast("✅ MP3 link ready"); }
      else amin.reply(ctx.msg, "❌ No audio found for that link.");
    }
  } catch (e) { amin.log("media-dl onCommand error: " + e); }
}

function onMenuAction(ctx) {
  var url = lastByChat[String(ctx.chatId)];
  if (!url) { amin.toast("No video link seen in this chat yet — send one first"); return; }
  var msg = { chatId: ctx.chatId, id: 0 };
  doResolve(ctx.chatId, msg, url);
}
