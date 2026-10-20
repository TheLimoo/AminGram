// ==AminGramPlugin==
// @id          media-dl
// @name        Media Downloader
// @version     2.0
// @author      Amin
// @description Sends TikTok, X/Twitter and Reddit media directly into the chat, extracts TikTok MP3s, and falls back to links when a file cannot be sent. Commands: /dl, /mp3 — or tap Download media in the chat attach menu.
// @icon        download
// @menuLabel   Download media
// @capabilities observe, commands, send, http, settings, menu, file
// ==/AminGramPlugin==

var SETTINGS = [
  { key: "autoToastLinks", type: "bool", title: "Toast & log direct link when someone sends a video URL", def: false },
  { key: "includeAudio", type: "bool", title: "Include MP3 audio link when available", def: true }
];

var lastByChat = {};
var FILE_CAP_BYTES = 200 * 1024 * 1024;

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
    videoHd: d.hdplay || null,
    videoHdSize: d.hd_size || d.hdSize || 0,
    videoSd: d.play || null,
    videoSdSize: d.size || d.sd_size || d.sdSize || 0,
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

// Instagram resolver configuration: set this one host value to enable
// mirror-based direct-media extraction. It is intentionally empty for
// now; with no mirror configured, /dl posts the original Instagram URL
// instead of trying to send Instagram's HTML post page as a file.
var IG_MIRROR_HOST = "";

function instagramMirrorUrl(cleanUrl) {
  var host = String(IG_MIRROR_HOST || "").replace(/^\s+|\s+$/g, "")
    .replace(/^https?:\/\//i, "").replace(/\/.*$/, "");
  if (!host) return null;
  return cleanUrl.replace(/^(https?:\/\/)(?:www\.)?instagram\.com/i, "$1" + host);
}

function decodeExtractedMediaUrl(value) {
  if (!value) return null;
  var out = String(value)
    .replace(/&amp;/gi, "&")
    .replace(/&#38;/g, "&")
    .replace(/\\u0026/gi, "&")
    .replace(/\\u003d/gi, "=")
    .replace(/\\\//g, "/");
  return /^https?:\/\//i.test(out) ? out : null;
}

function firstMediaMatch(html, patterns) {
  if (!html) return null;
  for (var i = 0; i < patterns.length; i++) {
    var m = String(html).match(patterns[i]);
    if (m && m[1]) {
      var decoded = decodeExtractedMediaUrl(m[1]);
      if (decoded) return decoded;
    }
  }
  return null;
}

function extractInstagramMirrorMedia(html) {
  if (!html) return null;
  var video = firstMediaMatch(html, [
    /<meta[^>]*property\s*=\s*["']og:video(?::url|:secure_url)?["'][^>]*content\s*=\s*["']([^"']+)["']/i,
    /<meta[^>]*content\s*=\s*["']([^"']+)["'][^>]*property\s*=\s*["']og:video(?::url|:secure_url)?["']/i,
    /["']video_url["']\s*:\s*["']([^"']+)["']/i,
    /["']playback_url["']\s*:\s*["']([^"']+)["']/i,
    /(https?:\/\/[^"'<>\s]+\.mp4(?:\?[^"'<>\s]*)?)/i
  ]);
  if (video) return { url: video, type: "video" };
  var photo = firstMediaMatch(html, [
    /<meta[^>]*property\s*=\s*["']og:image(?::url|:secure_url)?["'][^>]*content\s*=\s*["']([^"']+)["']/i,
    /<meta[^>]*content\s*=\s*["']([^"']+)["'][^>]*property\s*=\s*["']og:image(?::url|:secure_url)?["']/i,
    /["']display_url["']\s*:\s*["']([^"']+)["']/i
  ]);
  if (photo) return { url: photo, type: "photo" };
  return null;
}

function resolveInstagram(url) {
  var clean = url.split("?")[0];
  var result = {
    title: "Instagram post",
    video: null,
    photo: null,
    images: [],
    audio: null,
    instagram: true,
    mirrorUrl: null
  };
  var mirrorUrl = instagramMirrorUrl(clean);
  if (!mirrorUrl) return result;
  result.mirrorUrl = mirrorUrl;

  var extracted = null;
  try {
    extracted = extractInstagramMirrorMedia(amin.httpGet(mirrorUrl));
  } catch (e) {
    amin.log("media-dl Instagram mirror extraction error: " + e);
  }
  if (extracted && extracted.type === "photo") {
    result.photo = extracted.url;
    result.images = [extracted.url];
  } else if (extracted) {
    result.video = extracted.url;
  } else {
    // Some mirrors serve or redirect to the media itself at the
    // swapped URL; let sendFile make that one attempt. If it fails,
    // doResolve discards every mirror-derived link and falls back
    // to the original Instagram URL.
    result.video = mirrorUrl;
  }
  return result;
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
  if (r.originalOnly) out += "\n\n🔗 Original post:\n" + url;
  if (r.video) out += "\n\n📹 Video (MP4):\n" + r.video;
  if (r.videoSd && r.video && r.videoSd !== r.video) out += "\n📹 Video (SD):\n" + r.videoSd;
  if (r.allVideos && r.allVideos.length > 1) {
    for (var i = 1; i < r.allVideos.length; i++) out += "\n📹 Video " + (i + 1) + ":\n" + r.allVideos[i];
  }
  if (r.videoBackup) out += "\nBackup: " + r.videoBackup;
  if (r.images && r.images.length) out += "\n🖼 Images: " + r.images.length + " (first):\n" + r.images[0];
  if (amin.storage.get("includeAudio", true) && r.audio) out += "\n\n🎵 Audio (MP3):\n" + r.audio;
  if (r.note) out += "\n\nℹ️ " + r.note;
  if (!r.video && !r.audio && !r.originalOnly) out += "\n\n(no direct media found in that post)";
  return out;
}

function canSendFile() {
  return typeof amin.sendFile === "function";
}

function trySendFile(mediaUrl, type, caption, chatId) {
  if (!mediaUrl || !canSendFile()) return false;
  try {
    return amin.sendFile(mediaUrl, { type: type, caption: caption, chatId: chatId }) === true;
  } catch (e) {
    amin.log("media-dl sendFile error: " + e);
    return false;
  }
}

function positiveSize(value) {
  var n = Number(value);
  return isFinite(n) && n > 0 ? n : 0;
}

function pushMediaCandidate(list, mediaUrl, type) {
  if (!mediaUrl) return;
  for (var i = 0; i < list.length; i++) {
    if (list[i].url === mediaUrl && list[i].type === type) return;
  }
  list.push({ url: mediaUrl, type: type });
}

function mediaCandidates(r) {
  var list = [];
  if (!r) return list;
  if (r.instagram) {
    pushMediaCandidate(list, r.video, "video");
    pushMediaCandidate(list, r.photo, "photo");
    return list;
  }
  if (r.videoHd || r.videoSd) {
    var hdSize = positiveSize(r.videoHdSize);
    var sdSize = positiveSize(r.videoSdSize);
    if (hdSize > FILE_CAP_BYTES) {
      // The bridge would reject the HD file before/at the cap; use
      // the SD copy when it is known—or still unknown and therefore
      // worth the bridge's own streaming-cap check.
      if (!sdSize || sdSize <= FILE_CAP_BYTES) pushMediaCandidate(list, r.videoSd, "video");
    } else {
      pushMediaCandidate(list, r.videoHd || r.video, "video");
      pushMediaCandidate(list, r.videoSd, "video");
    }
  } else {
    pushMediaCandidate(list, r.video, "video");
    pushMediaCandidate(list, r.videoSd, "video");
  }
  if (r.allVideos) {
    for (var i = 0; i < r.allVideos.length; i++) pushMediaCandidate(list, r.allVideos[i], "video");
  }
  return list;
}

function mediaCaption(r, sourceUrl, type) {
  var icon = type === "audio" ? "🎵" : (type === "photo" ? "🖼" : "🎬");
  return icon + " " + (r && r.title ? r.title : "Media") + "\nSource: " + sourceUrl;
}

function prepareLinkFallback(r) {
  if (r && r.instagram) {
    // Instagram fallback is the original post, never a mirror URL:
    // remove every mirror-derived candidate/link before formatting.
    r.video = null;
    r.videoSd = null;
    r.allVideos = [];
    r.photo = null;
    r.images = [];
    r.videoBackup = null;
    r.originalOnly = true;
  }
  return r;
}

function fallbackNote(r, candidates) {
  if (r && r.instagram) return "Direct Instagram media couldn't be sent, so the original Instagram link is above.";
  if (candidates && candidates.length) return "The media file couldn't be sent directly (it may exceed 200 MB), so here are the links instead.";
  return "No directly sendable media was found, so here are the links instead.";
}

function doResolve(chatId, msg, url) {
  try {
    var r = null;
    try {
      r = resolveAny(url);
    } catch (resolveError) {
      amin.log("media-dl resolve error: " + resolveError);
      r = null;
    }
    if (!r) {
      amin.reply(msg, formatResult(null, url) + "\n\nℹ️ Direct media couldn't be sent for that link.");
      amin.toast("❌ Couldn't resolve that link");
      return;
    }

    var candidates = mediaCandidates(r);
    amin.log("media-dl resolved " + url + " -> " + (candidates.length ? candidates[0].url : "no sendable media"));
    for (var i = 0; i < candidates.length; i++) {
      var candidate = candidates[i];
      if (trySendFile(candidate.url, candidate.type, mediaCaption(r, url, candidate.type), chatId)) {
        amin.log("media-dl sent " + candidate.type + " from " + candidate.url);
        amin.toast(candidate.type === "photo" ? "✅ Photo sent" : "✅ Video sent");
        return;
      }
    }

    var fallback = prepareLinkFallback(r);
    amin.reply(msg, formatResult(fallback, url) + "\n\nℹ️ " + fallbackNote(fallback, candidates));
    amin.toast(candidates.length ? "⚠️ Couldn't send the file — links posted" : "✅ Media link resolved");
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
      if (r && r.audio) {
        if (trySendFile(r.audio, "audio", mediaCaption(r, u2, "audio"), ctx.chatId)) {
          amin.log("media-dl sent audio from " + r.audio);
          amin.toast("✅ MP3 sent");
        } else {
          amin.reply(ctx.msg, "🎵 Audio (MP3):\n" + r.audio + "\n\nℹ️ The audio file couldn't be sent directly (it may exceed 200 MB), so here is the link instead.");
          amin.toast("⚠️ Couldn't send the MP3 — link posted");
        }
      }
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
