// ==AminGramPlugin==
// @id          media-dl
// @name        Downloader Pro
// @version     3.0.2
// @author      Amin
// @description Downloads public media into the chat from TikTok, Instagram, X/Twitter, Reddit, Pinterest, Threads and direct media links. Supports albums, quality choices, TikTok MP3, replied-to links, a long-press download action, download history and link fallback.
// @icon        download
// @menuLabel   Download media
// @capabilities observe, commands, send, http, settings, menu, file, ui
// @contextMenu Download media
// @minPlatform 2
// @dataVersion 2
// ==/AminGramPlugin==

// Downloader Pro v3 — a ground-up remake of media-dl for AminGram
// Platform v2. Resolvers use async HTTP where the bridge provides it,
// media is sent as real Telegram media through amin.sendFile, and a
// clean link fallback is posted whenever a resolver or file send
// cannot complete. No timers, polling or background work are used.

var SETTINGS = [
  { key: "quality", type: "select", title: "Preferred video quality", def: "auto",
    options: [
      { value: "auto", label: "Automatic (HD first, then smaller)" },
      { value: "hd", label: "HD first" },
      { value: "sd", label: "SD / smaller first" }
    ] },
  { key: "maxItems", type: "slider", title: "Maximum files per post", def: 4, min: 1, max: 10, step: 1,
    help: "Albums and multi-photo/video posts are capped at this many files." },
  { key: "sendAllMedia", type: "bool", title: "Send every photo/video in a post", def: true },
  { key: "includeAudio", type: "bool", title: "Include TikTok MP3 link when available", def: true },
  { key: "redditAudioTrack", type: "bool", title: "Also send Reddit's separate audio track", def: false,
    help: "Many Reddit videos are hosted as a silent video file plus a separate audio file." },
  { key: "autoToastLinks", type: "bool", title: "Toast when a supported link is seen", def: false },
  { key: "igMirrorHost", type: "text", title: "Instagram mirror host", def: "kkinstagram.com", maxLength: 80,
    help: "Used only for public Instagram posts and reels. Change it if the current mirror stops working." }
];

var PLUGIN_PAGE = [
  { type: "info", text: "Downloader Pro remembers the last supported link in each chat and keeps a private record of completed download attempts." },
  { type: "action", id: "stats", title: "Download statistics", subtitle: "Show the totals stored by this plugin" },
  { type: "action", id: "clear-history", title: "Clear download history", subtitle: "Remove saved download records" },
  { type: "action", id: "forget-links", title: "Forget remembered chat links", subtitle: "Clear the last-link memory for all chats" }
];

var FILE_CAP_BYTES = 200 * 1024 * 1024;
var MAX_FALLBACK_CHARS = 3500;
var DIRECT_IG_HOSTS = { "kkinstagram.com": true };
var IG_UA = "TelegramBot (like TwitterBot)";
var BROWSER_HEADERS = {
  "User-Agent": "Mozilla/5.0 (Linux; Android 13; SM-A566B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36",
  "Accept": "application/json,text/html;q=0.9,*/*;q=0.8"
};

var lastByChatFallback = {};
var activeJobs = {};

// ---------------------------------------------------------------------
// Hook-budget survival (v3.0.1). The platform gives every hook /
// async callback a ~5 s wall-clock budget (AminContextFactory), and
// amin.sendFile BLOCKS inside that window while it downloads — a
// video can take far longer than 5 s. The budget only trips when JS
// runs past the deadline, so the rule in the send machine below is:
// after a sendFile returns, run almost no JS — either finish, loop
// straight into the next native call, or hand over to a fresh async
// callback (fresh budget) via budgetTick(). All heavy work
// (captions, formatting, history, follow-up replies) is precomputed
// or deferred to the finish step under a fresh window.
// ---------------------------------------------------------------------
var BUDGET_SOFT_LIMIT_MS = 3500;
var BUDGET_TICK_URL = "https://www.gstatic.com/generate_204";
var windowStartMs = 0;

function markWindowStart() {
  windowStartMs = nowMs();
}

function windowElapsedMs() {
  return windowStartMs ? nowMs() - windowStartMs : 0;
}

function budgetTick(fn) {
  if (typeof amin.httpGetAsync === "function") {
    try {
      amin.httpGetAsync(BUDGET_TICK_URL, null, function () {
        markWindowStart();
        fn();
      });
      return;
    } catch (e) { amin.debug("media-dl budget tick fell back to direct call: " + e); }
  }
  markWindowStart();
  fn();
}

function str(value) {
  return value === null || value === undefined ? "" : String(value);
}

function trimText(value) {
  return str(value).replace(/^\s+|\s+$/g, "");
}

function lower(value) {
  return str(value).toLowerCase();
}

function startsWith(value, prefix) {
  return str(value).indexOf(prefix) === 0;
}

function contains(value, part) {
  return str(value).indexOf(part) >= 0;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !isArray(value);
}

function isArray(value) {
  return Object.prototype.toString.call(value) === "[object Array]";
}

function arrayOf(value) {
  if (value === null || value === undefined) return [];
  return isArray(value) ? value : [value];
}

function truncateText(value, max) {
  var out = str(value);
  if (out.length <= max) return out;
  return out.substring(0, Math.max(0, max - 1)) + "…";
}

function padLeft(value, width) {
  var out = str(value);
  while (out.length < width) out = "0" + out;
  return out;
}

function nowMs() {
  try { return new Date().getTime(); } catch (e) { return 0; }
}

function decodeHtml(value) {
  var out = str(value);
  out = out.replace(/&amp;/gi, "&");
  out = out.replace(/&#38;/g, "&");
  out = out.replace(/&quot;/gi, "\"");
  out = out.replace(/&#34;/g, "\"");
  out = out.replace(/&apos;/gi, "'");
  out = out.replace(/&#39;/g, "'");
  out = out.replace(/&lt;/gi, "<");
  out = out.replace(/&gt;/gi, ">");
  out = out.replace(/\\u0026/gi, "&");
  out = out.replace(/\\u003d/gi, "=");
  out = out.replace(/\\u002f/gi, "/");
  out = out.replace(/\\\//g, "/");
  return out;
}

function cleanTitle(value, fallback) {
  var out = decodeHtml(value).replace(/<[^>]+>/g, " ");
  out = out.replace(/\s+/g, " ");
  out = trimText(out);
  if (!out) out = fallback || "Media";
  return truncateText(out, 240);
}

function isHttpUrl(value) {
  return /^https?:\/\/[^\s]+$/i.test(str(value));
}

function extractUrls(text) {
  var found = [];
  var matches = str(text).match(/https?:\/\/[^\s<>"']+/gi) || [];
  for (var i = 0; i < matches.length; i++) {
    var url = matches[i];
    while (/[)\].,;:!?]$/.test(url)) url = url.substring(0, url.length - 1);
    if (url && found.indexOf(url) < 0) found.push(url);
  }
  return found;
}

function urlHost(url) {
  var m = str(url).match(/^https?:\/\/([^\/:?#]+)/i);
  return m ? lower(m[1]) : "";
}

function urlPath(url) {
  var m = str(url).match(/^https?:\/\/[^\/]+(\/[^?#]*)?/i);
  return m && m[1] ? m[1] : "/";
}

function urlExtension(url) {
  var path = urlPath(url);
  var m = path.match(/\.([a-z0-9]{2,5})$/i);
  return m ? lower(m[1]) : "";
}

function mediaTypeForUrl(url) {
  var ext = urlExtension(url);
  if (ext === "mp4" || ext === "mov" || ext === "m4v" || ext === "webm") return "video";
  if (ext === "jpg" || ext === "jpeg" || ext === "png" || ext === "webp") return "photo";
  if (ext === "mp3" || ext === "m4a" || ext === "aac" || ext === "ogg" || ext === "opus" || ext === "wav" || ext === "flac") return "audio";
  return "file";
}

function isDirectMediaUrl(url) {
  return mediaTypeForUrl(url) !== "file" || /\.(zip|rar|7z|pdf|apk|bin)($|[?#])/i.test(str(url));
}

function platformLabel(platform) {
  if (platform === "tiktok") return "TikTok";
  if (platform === "instagram") return "Instagram";
  if (platform === "x") return "X/Twitter";
  if (platform === "reddit") return "Reddit";
  if (platform === "pinterest") return "Pinterest";
  if (platform === "threads") return "Threads";
  if (platform === "facebook") return "Facebook";
  if (platform === "youtube") return "YouTube";
  if (platform === "direct") return "Direct file";
  if (platform === "generic") return "Web page";
  return "Media";
}

function classifyUrl(url) {
  if (!isHttpUrl(url)) return null;
  var host = urlHost(url);
  var path = urlPath(url);
  if (isDirectMediaUrl(url)) return "direct";
  if (contains(host, "tiktok.com")) return "tiktok";
  if (contains(host, "instagram.com")) {
    if (/\/(p|reel|reels|tv)\//i.test(path)) return "instagram";
    return null;
  }
  if (host === "x.com" || host === "www.x.com" || contains(host, "twitter.com")) {
    return /\/status\/\d+/i.test(path) ? "x" : null;
  }
  if (contains(host, "reddit.com") || host === "redd.it") return "reddit";
  if (contains(host, "pinterest.") || host === "pin.it") return "pinterest";
  if (contains(host, "threads.net") || contains(host, "threads.com")) return "threads";
  if (contains(host, "facebook.com") || host === "fb.watch") return "facebook";
  if (contains(host, "youtube.com") || host === "youtu.be") return "youtube";
  if (contains(host, "vimeo.com")) return "generic";
  return null;
}

function extractClassifiedUrl(text) {
  var urls = extractUrls(text);
  for (var i = 0; i < urls.length; i++) {
    if (classifyUrl(urls[i])) return urls[i];
  }
  return null;
}

function settingString(key, fallback) {
  try {
    var value = amin.storage.get(key, fallback);
    return trimText(value === null || value === undefined ? fallback : value);
  } catch (e) { return fallback; }
}

function settingBool(key, fallback) {
  try {
    var value = amin.storage.get(key, fallback);
    if (typeof value === "boolean") return value;
    if (typeof value === "string") return value === "true" || value === "1";
    return !!value;
  } catch (e) { return fallback; }
}

function settingNumber(key, fallback) {
  try {
    var value = Number(amin.storage.get(key, fallback));
    return isFinite(value) ? value : fallback;
  } catch (e) { return fallback; }
}

function preferredQuality(override) {
  var q = lower(override || settingString("quality", "auto"));
  return q === "hd" || q === "sd" ? q : "auto";
}

function maxItemsSetting() {
  var n = Math.floor(settingNumber("maxItems", 4));
  if (n < 1) n = 1;
  if (n > 10) n = 10;
  return n;
}

function dbAvailable() {
  return !!(amin.db && typeof amin.db.get === "function"
    && typeof amin.db.put === "function" && typeof amin.db.keys === "function");
}

function rememberUrl(chatId, url) {
  if (chatId === null || chatId === undefined || !url) return;
  var record = { url: url, at: nowMs() };
  if (dbAvailable()) {
    try {
      if (amin.db.put("last", "chat:" + str(chatId), record)) return;
    } catch (e) { amin.debug("media-dl db remember failed: " + e); }
  }
  lastByChatFallback[str(chatId)] = record;
  try {
    if (amin.storage && typeof amin.storage.setJSON === "function") {
      amin.storage.setJSON("lastByChatFallback", lastByChatFallback);
    }
  } catch (e2) {}
}

function rememberedUrl(chatId) {
  if (chatId === null || chatId === undefined) return null;
  if (dbAvailable()) {
    try {
      var fromDb = amin.db.get("last", "chat:" + str(chatId));
      if (fromDb && fromDb.url) return str(fromDb.url);
    } catch (e) { amin.debug("media-dl db last lookup failed: " + e); }
  }
  var local = lastByChatFallback[str(chatId)];
  if (local && local.url) return str(local.url);
  try {
    if (amin.storage && typeof amin.storage.getJSON === "function") {
      var all = amin.storage.getJSON("lastByChatFallback", null);
      if (all && all[str(chatId)] && all[str(chatId)].url) return str(all[str(chatId)].url);
    }
  } catch (e2) {}
  return null;
}

function recordHistory(chatId, platform, source, ok, sentCount) {
  if (!dbAvailable()) return;
  try {
    var at = nowMs();
    var key = "h:" + padLeft(at, 13) + ":" + str(chatId);
    amin.db.put("history", key, {
      chatId: str(chatId), platform: platform || "generic",
      source: truncateText(source, 1000), ok: !!ok,
      sent: sentCount || 0, at: at
    });
    var keys = amin.db.keys("history", "h:") || [];
    for (var i = 0; i < keys.length - 60; i++) amin.db.del("history", keys[i]);
  } catch (e) { amin.debug("media-dl history write failed: " + e); }
}

function historyForChat(chatId, limit) {
  var out = [];
  if (!dbAvailable()) return out;
  try {
    var keys = amin.db.keys("history", "h:") || [];
    for (var i = keys.length - 1; i >= 0 && out.length < limit; i--) {
      var item = amin.db.get("history", keys[i]);
      if (item && str(item.chatId) === str(chatId)) out.push(item);
    }
  } catch (e) { amin.debug("media-dl history read failed: " + e); }
  return out;
}

function clearDbNamespace(ns) {
  if (!dbAvailable()) return 0;
  var count = 0;
  try {
    var keys = amin.db.keys(ns, "") || [];
    for (var i = 0; i < keys.length; i++) {
      if (amin.db.del(ns, keys[i])) count++;
    }
  } catch (e) { amin.warn("media-dl clear " + ns + " failed: " + e); }
  return count;
}

function httpGetTextAsync(url, headers, done) {
  var finished = false;
  function once(result) {
    if (finished) return;
    finished = true;
    // Every async HTTP completion runs as a fresh budgeted
    // invocation — mark the window so the send machine can measure
    // how much of it the resolver parse consumed.
    markWindowStart();
    done(result || { status: 0, body: null });
  }
  if (typeof amin.httpGetAsync === "function") {
    try {
      amin.httpGetAsync(url, headers || null, once);
      return;
    } catch (e) { amin.debug("media-dl async GET fell back to sync: " + e); }
  }
  try {
    var body = headers ? amin.httpGet(url, headers) : amin.httpGet(url);
    once({ status: body === null || body === undefined ? 0 : 200, body: body });
  } catch (e2) {
    amin.debug("media-dl sync GET failed: " + e2);
    once({ status: 0, body: null });
  }
}

function httpGetJsonAsync(url, headers, done) {
  httpGetTextAsync(url, headers, function (res) {
    var parsed = null;
    if (res && res.body) {
      try { parsed = JSON.parse(res.body); } catch (e) {
        amin.debug("media-dl JSON parse failed for " + url + ": " + e);
      }
    }
    done(parsed, res || { status: 0, body: null });
  });
}

function emptyResult(platform, source) {
  return {
    platform: platform || "generic",
    title: platformLabel(platform) + " media",
    source: source,
    items: [],
    audioUrl: null,
    redditAudioUrl: null,
    notes: [],
    originalOnly: false
  };
}

function addItem(result, label) {
  var item = { label: label || "", candidates: [], hideLinks: false };
  result.items.push(item);
  return item;
}

function addCandidate(item, url, type, headers, label, size) {
  if (!item || !isHttpUrl(url)) return false;
  var cleanUrl = decodeHtml(url);
  var candidateType = type || mediaTypeForUrl(cleanUrl);
  for (var i = 0; i < item.candidates.length; i++) {
    if (item.candidates[i].url === cleanUrl && item.candidates[i].type === candidateType) return false;
  }
  item.candidates.push({
    url: cleanUrl, type: candidateType, headers: headers || null,
    label: label || "", size: Number(size) || 0
  });
  return true;
}

function addSimpleItem(result, url, type, headers, label, size) {
  var item = addItem(result, label || "");
  if (!addCandidate(item, url, type, headers, label, size)) {
    result.items.pop();
    return null;
  }
  return item;
}

function itemPrimaryUrl(item) {
  return item && item.candidates.length ? item.candidates[0].url : null;
}

function resultHasMedia(result) {
  return !!(result && result.items && result.items.length);
}

function note(result, text) {
  if (result && text && result.notes.indexOf(text) < 0) result.notes.push(text);
}

// ---------------------------------------------------------------------
// Platform resolvers. Every resolver calls done(result) exactly once;
// done(null) means no usable result could be built.
// ---------------------------------------------------------------------

function tikTokAudioUrl(data) {
  if (!data) return null;
  if (typeof data.music === "string") return data.music;
  if (isPlainObject(data.music)) return data.music.play || data.music.url || null;
  if (isPlainObject(data.music_info)) return data.music_info.play || data.music_info.url || null;
  return null;
}

function resolveTikTok(source, options, done) {
  var endpoint = "https://www.tikwm.com/api/?url=" + encodeURIComponent(source);
  httpGetJsonAsync(endpoint, BROWSER_HEADERS, function (json) {
    try {
      if (!json || !json.data) { done(null); return; }
      var d = json.data;
      var result = emptyResult("tiktok", source);
      result.title = cleanTitle(d.title || d.content_desc, "TikTok video");
      var images = arrayOf(d.images);
      if (images.length) {
        for (var i = 0; i < images.length; i++) {
          addSimpleItem(result, images[i], "photo", null, "Photo " + (i + 1));
        }
      } else {
        var item = addItem(result, "TikTok video");
        var quality = preferredQuality(options && options.quality);
        var hd = d.hdplay || null;
        var sd = d.play || null;
        var watermarked = d.wmplay || null;
        if (quality === "sd") {
          addCandidate(item, sd, "video", null, "SD", d.size || d.sd_size || 0);
          addCandidate(item, hd, "video", null, "HD", d.hd_size || 0);
        } else {
          addCandidate(item, hd, "video", null, "HD", d.hd_size || 0);
          addCandidate(item, sd, "video", null, "SD", d.size || d.sd_size || 0);
        }
        addCandidate(item, watermarked, "video", null, "Watermarked");
        if (!item.candidates.length) result.items.pop();
      }
      result.audioUrl = tikTokAudioUrl(d);
      if (images.length) note(result, "TikTok photo post: each image is sent separately, up to your per-post limit.");
      done(result);
    } catch (e) {
      amin.error("media-dl TikTok resolver error: " + e);
      done(null);
    }
  });
}

function xVariantCandidates(item, obj, quality) {
  var variants = [];
  var lists = [obj.variants, obj.formats, obj.videoVariants];
  for (var i = 0; i < lists.length; i++) {
    var list = arrayOf(lists[i]);
    for (var j = 0; j < list.length; j++) {
      var v = list[j];
      if (typeof v === "string") variants.push({ url: v, bitrate: 0, type: "" });
      else if (isPlainObject(v) && v.url) {
        variants.push({
          url: v.url,
          bitrate: Number(v.bitrate || v.bit_rate || v.bitRate || 0) || 0,
          type: str(v.content_type || v.contentType || v.type || v.format || "")
        });
      }
    }
  }
  variants.sort(function (a, b) {
    return quality === "sd" ? a.bitrate - b.bitrate : b.bitrate - a.bitrate;
  });
  var added = 0;
  for (var k = 0; k < variants.length; k++) {
    var typeText = lower(variants[k].type);
    if (typeText && !contains(typeText, "mp4") && !contains(typeText, "video")) continue;
    if (contains(variants[k].url, ".m3u8")) continue;
    if (addCandidate(item, variants[k].url, "video", null,
        variants[k].bitrate ? Math.round(variants[k].bitrate / 1000) + " kbps" : "Video")) added++;
  }
  return added;
}

function xAddMediaObject(result, obj, quality, label) {
  if (!isPlainObject(obj)) return false;
  var item = addItem(result, label || "X media");
  xVariantCandidates(item, obj, quality);
  var direct = obj.url || obj.media_url_https || obj.media_url || obj.src || null;
  if (direct) {
    var declared = lower(obj.type || obj.kind || obj.media_type || "");
    var type = mediaTypeForUrl(direct);
    if (type === "file") {
      if (contains(declared, "photo") || contains(declared, "image")) type = "photo";
      else if (contains(declared, "video") || contains(declared, "gif")) type = "video";
    }
    addCandidate(item, direct, type, null, label || "");
  }
  if (!item.candidates.length) {
    result.items.pop();
    return false;
  }
  return true;
}

function xCollectMedia(result, node, quality) {
  if (!node) return;
  var i;
  if (isArray(node)) {
    for (i = 0; i < node.length; i++) xCollectMedia(result, node[i], quality);
    return;
  }
  if (typeof node === "string") {
    var type = mediaTypeForUrl(node);
    if (type !== "file") addSimpleItem(result, node, type, null, "X media");
    return;
  }
  if (!isPlainObject(node)) return;

  var containers = [node.all, node.videos, node.photos, node.images, node.media];
  var usedContainer = false;
  for (i = 0; i < containers.length; i++) {
    if (containers[i]) {
      usedContainer = true;
      xCollectMedia(result, containers[i], quality);
    }
  }
  if (usedContainer) return;
  xAddMediaObject(result, node, quality, "X media");
}

function xParseInto(result, root, quality) {
  if (!root) return false;
  var tweet = root.tweet || root;
  if (!isPlainObject(tweet)) return false;
  var text = tweet.text || (tweet.raw_text && tweet.raw_text.text) || root.text || "";
  var author = "";
  if (isPlainObject(tweet.author)) author = tweet.author.name || tweet.author.screen_name || "";
  if (!author) author = tweet.user_name || root.user_name || "";
  result.title = cleanTitle(text || "X post", "X post") + (author ? " — " + author : "");

  xCollectMedia(result, tweet.media_extended, quality);
  xCollectMedia(result, tweet.media, quality);
  xCollectMedia(result, tweet.mediaURLs, quality);
  if (tweet.entities) xCollectMedia(result, tweet.entities.media, quality);
  if (tweet.extended_entities) xCollectMedia(result, tweet.extended_entities.media, quality);

  if (!resultHasMedia(result) && isPlainObject(tweet.quote)) {
    var before = result.items.length;
    xCollectMedia(result, tweet.quote.media_extended, quality);
    xCollectMedia(result, tweet.quote.media, quality);
    xCollectMedia(result, tweet.quote.mediaURLs, quality);
    if (result.items.length > before) note(result, "The media below comes from the quoted post.");
  }
  return resultHasMedia(result);
}

function resolveX(source, options, done) {
  var m = source.match(/(?:x\.com|twitter\.com)\/([A-Za-z0-9_]{1,15})\/status\/(\d+)/i);
  if (!m) { done(null); return; }
  var quality = preferredQuality(options && options.quality);
  var username = m[1], statusId = m[2];

  function tryVx() {
    var vxUrl = "https://api.vxtwitter.com/" + encodeURIComponent(username)
      + "/status/" + encodeURIComponent(statusId);
    httpGetJsonAsync(vxUrl, BROWSER_HEADERS, function (json) {
      var result = emptyResult("x", source);
      if (xParseInto(result, json, quality)) done(result);
      else done(null);
    });
  }

  var fxUrl = "https://api.fxtwitter.com/" + encodeURIComponent(username)
    + "/status/" + encodeURIComponent(statusId);
  httpGetJsonAsync(fxUrl, BROWSER_HEADERS, function (json) {
    try {
      var result = emptyResult("x", source);
      if (json && Number(json.code || 200) === 200 && xParseInto(result, json, quality)) {
        done(result);
      } else {
        tryVx();
      }
    } catch (e) {
      amin.debug("media-dl FxTwitter parse failed, trying VxTwitter: " + e);
      tryVx();
    }
  });
}

function redditDataFromJson(json) {
  try {
    var listing = arrayOf(json)[0];
    var child = listing && listing.data && arrayOf(listing.data.children)[0];
    var post = child && child.data ? child.data : null;
    if (post && isArray(post.crosspost_parent_list) && post.crosspost_parent_list.length) {
      return post.crosspost_parent_list[0] || post;
    }
    return post;
  } catch (e) { return null; }
}

function redditAudioFor(fallbackUrl) {
  var m = str(fallbackUrl).match(/^(https:\/\/v\.redd\.it\/[^\/]+)\//i);
  return m ? m[1] + "/DASH_audio.mp4" : null;
}

function resolveRedditWithEndpoint(source, endpoint, done, allowFallback) {
  httpGetJsonAsync(endpoint, BROWSER_HEADERS, function (json) {
    try {
      var post = redditDataFromJson(json);
      if (!post) {
        if (allowFallback) {
          var idMatch = source.match(/comments\/([a-z0-9]+)/i) || source.match(/redd\.it\/([a-z0-9]+)/i);
          if (idMatch) {
            resolveRedditWithEndpoint(source,
              "https://api.reddit.com/comments/" + idMatch[1] + "?raw_json=1", done, false);
            return;
          }
        }
        done(null);
        return;
      }

      var result = emptyResult("reddit", source);
      result.title = cleanTitle(post.title, "Reddit post");
      var media = null;
      if (post.secure_media && post.secure_media.reddit_video) media = post.secure_media.reddit_video;
      if (!media && post.media && post.media.reddit_video) media = post.media.reddit_video;

      if (media && media.fallback_url) {
        addSimpleItem(result, media.fallback_url, "video", null, "Reddit video");
        result.redditAudioUrl = redditAudioFor(media.fallback_url);
        if (media.has_audio === false) note(result, "Reddit marks this hosted video as having no audio track.");
      }

      if (post.is_gallery && post.gallery_data && isArray(post.gallery_data.items) && post.media_metadata) {
        for (var i = 0; i < post.gallery_data.items.length; i++) {
          var entry = post.gallery_data.items[i];
          var meta = entry && post.media_metadata[entry.media_id];
          var s = meta && meta.s ? meta.s : null;
          if (!s) continue;
          if (s.mp4) addSimpleItem(result, s.mp4, "video", null, "Gallery video " + (i + 1));
          else if (s.u) addSimpleItem(result, s.u, mediaTypeForUrl(s.u), null, "Gallery image " + (i + 1));
          else if (s.gif) addSimpleItem(result, s.gif, "file", null, "Gallery GIF " + (i + 1));
        }
      }

      if (!resultHasMedia(result) && post.url) {
        var postType = mediaTypeForUrl(post.url);
        if (postType !== "file") addSimpleItem(result, post.url, postType, null, "Reddit media");
      }

      if (!resultHasMedia(result) && post.preview) {
        var previewVideo = post.preview.reddit_video_preview;
        if (previewVideo && previewVideo.fallback_url) {
          addSimpleItem(result, previewVideo.fallback_url, "video", null, "Reddit preview video");
        }
      }

      if (!resultHasMedia(result) && post.preview && isArray(post.preview.images) && post.preview.images.length) {
        var sourceImage = post.preview.images[0].source;
        if (sourceImage && sourceImage.url) {
          addSimpleItem(result, decodeHtml(sourceImage.url), "photo", null, "Reddit image");
        }
      }

      if (!resultHasMedia(result)) {
        result.originalOnly = true;
        note(result, "No direct Reddit media was exposed for this post; it may be an external link, text post, or a media type Reddit does not publish directly.");
      }
      done(result);
    } catch (e) {
      amin.error("media-dl Reddit resolver error: " + e);
      done(null);
    }
  });
}

function resolveReddit(source, options, done) {
  var clean = source.split("#")[0];
  var endpoint;
  var idMatch = clean.match(/comments\/([a-z0-9]+)/i) || clean.match(/redd\.it\/([a-z0-9]+)/i);
  if (idMatch) endpoint = "https://www.reddit.com/comments/" + idMatch[1] + ".json?raw_json=1";
  else endpoint = (clean.charAt(clean.length - 1) === "/" ? clean : clean + "/") + ".json?raw_json=1";
  resolveRedditWithEndpoint(source, endpoint, done, true);
}

function sanitizedInstagramMirror() {
  var host = lower(settingString("igMirrorHost", "kkinstagram.com"));
  host = host.replace(/^https?:\/\//i, "").replace(/\/.*$/, "").replace(/:\d+$/, "");
  if (!/^(?:[a-z0-9-]+\.)+[a-z]{2,}$/i.test(host)) return "";
  return host;
}

function resolveInstagram(source, options, done) {
  var result = emptyResult("instagram", source);
  var clean = source.split("#")[0].split("?")[0];
  var host = sanitizedInstagramMirror();
  if (!host) {
    result.originalOnly = true;
    note(result, "The Instagram mirror host setting is empty or invalid, so only the original post can be shown.");
    done(result);
    return;
  }
  var mirrorUrl = clean.replace(/^(https?:\/\/)(?:www\.)?instagram\.com/i, "$1" + host);
  result.title = "Instagram post";

  if (DIRECT_IG_HOSTS[host]) {
    // This mirror returns the media itself only when the TelegramBot
    // user-agent is used. The same URL is tried as video, then photo;
    // sendFile follows the redirect to Instagram's CDN.
    var item = addItem(result, "Instagram media");
    item.hideLinks = true;
    var headers = { "User-Agent": IG_UA };
    addCandidate(item, mirrorUrl, "video", headers, "Instagram video");
    addCandidate(item, mirrorUrl, "photo", headers, "Instagram photo");
    note(result, "For Instagram carousel posts, this mirror normally exposes the first item only.");
    done(result);
    return;
  }

  // A replacement mirror may be a classic HTML mirror. Extract its
  // public OpenGraph media; if that fails, try the swapped URL as a
  // direct-media address without ever exposing it in a fallback.
  httpGetTextAsync(mirrorUrl, BROWSER_HEADERS, function (res) {
    try {
      var parsed = res && res.body ? parseOpenGraph(res.body, mirrorUrl) : null;
      if (parsed) {
        result.title = cleanTitle(parsed.title, "Instagram post");
        var i;
        for (i = 0; i < parsed.videos.length; i++) addSimpleItem(result, parsed.videos[i], "video", null, "Instagram video");
        for (i = 0; i < parsed.images.length; i++) addSimpleItem(result, parsed.images[i], "photo", null, "Instagram photo");
      }
      if (!resultHasMedia(result)) {
        var fallbackItem = addItem(result, "Instagram media");
        fallbackItem.hideLinks = true;
        addCandidate(fallbackItem, mirrorUrl, "video", null, "Instagram video");
        addCandidate(fallbackItem, mirrorUrl, "photo", null, "Instagram photo");
      }
      done(result);
    } catch (e) {
      amin.error("media-dl Instagram mirror error: " + e);
      var errorResult = emptyResult("instagram", source);
      errorResult.originalOnly = true;
      note(errorResult, "The Instagram mirror could not be read, so only the original post can be shown.");
      done(errorResult);
    }
  });
}

function metaAttr(tag, wanted) {
  var names = ["property", "name", "itemprop"];
  for (var i = 0; i < names.length; i++) {
    var m = tag.match(new RegExp(names[i] + "\\s*=\\s*[\"']([^\"']+)[\"']", "i"));
    if (m && lower(m[1]) === wanted) return true;
  }
  return false;
}

function metaContent(tag) {
  var m = tag.match(/content\s*=\s*["']([^"']+)["']/i);
  return m ? decodeHtml(m[1]) : null;
}

function parseOpenGraph(html, pageUrl) {
  var out = { title: "", videos: [], images: [], audios: [] };
  var text = str(html);
  var tags = text.match(/<meta\b[^>]*>/gi) || [];
  var videoKeys = {
    "og:video": true, "og:video:url": true, "og:video:secure_url": true,
    "twitter:player:stream": true, "twitter:player:stream:url": true
  };
  var imageKeys = {
    "og:image": true, "og:image:url": true, "og:image:secure_url": true,
    "twitter:image": true, "twitter:image:src": true
  };
  var audioKeys = { "og:audio": true, "og:audio:url": true, "og:audio:secure_url": true };
  var titleKeys = { "og:title": true, "twitter:title": true };

  for (var i = 0; i < tags.length; i++) {
    var tag = tags[i];
    var content = metaContent(tag);
    if (!content || !isHttpUrl(content)) {
      if (content && metaAttr(tag, "og:title")) out.title = content;
      continue;
    }
    var keyFound = "";
    var allKeys = ["og:video", "og:video:url", "og:video:secure_url",
      "twitter:player:stream", "twitter:player:stream:url",
      "og:image", "og:image:url", "og:image:secure_url",
      "twitter:image", "twitter:image:src",
      "og:audio", "og:audio:url", "og:audio:secure_url",
      "og:title", "twitter:title"];
    for (var j = 0; j < allKeys.length; j++) {
      if (metaAttr(tag, allKeys[j])) { keyFound = allKeys[j]; break; }
    }
    if (!keyFound) continue;
    if (titleKeys[keyFound]) out.title = content;
    else if (videoKeys[keyFound] && out.videos.indexOf(content) < 0) out.videos.push(content);
    else if (imageKeys[keyFound] && out.images.indexOf(content) < 0) out.images.push(content);
    else if (audioKeys[keyFound] && out.audios.indexOf(content) < 0) out.audios.push(content);
  }

  var titleMatch = text.match(/<title[^>]*>([^<]+)<\/title>/i);
  if (!out.title && titleMatch) out.title = decodeHtml(titleMatch[1]);

  var contentUrlRe = /["']contentUrl["']\s*:\s*["']([^"']+)["']/gi;
  var cm;
  while ((cm = contentUrlRe.exec(text)) !== null) {
    var candidate = decodeHtml(cm[1]);
    var type = mediaTypeForUrl(candidate);
    if (type === "video" && out.videos.indexOf(candidate) < 0) out.videos.push(candidate);
    else if (type === "audio" && out.audios.indexOf(candidate) < 0) out.audios.push(candidate);
  }
  out.title = cleanTitle(out.title, platformLabel(classifyUrl(pageUrl)) + " media");
  return out;
}

function usableGenericMediaUrl(url) {
  if (!isHttpUrl(url)) return false;
  var clean = lower(url);
  if (contains(clean, ".m3u8") || contains(clean, ".mpd")) return false;
  if (contains(clean, "/embed/") || contains(clean, "player.vimeo.com/video/")
      || contains(clean, "youtube.com/embed/")) return false;
  return true;
}

function resolveGeneric(source, options, done) {
  var platform = classifyUrl(source) || "generic";
  if (platform === "direct") {
    var direct = emptyResult("direct", source);
    direct.title = cleanTitle(urlPath(source).split("/").pop() || "Direct file", "Direct file");
    addSimpleItem(direct, source, mediaTypeForUrl(source), null, "Direct file");
    done(direct);
    return;
  }
  if (platform === "youtube") {
    var yt = emptyResult("youtube", source);
    yt.originalOnly = true;
    note(yt, "YouTube does not provide a stable login-free direct MP4 resolver that a Telegram plugin can use reliably, so Downloader Pro does not pretend to download it.");
    done(yt);
    return;
  }

  httpGetTextAsync(source, BROWSER_HEADERS, function (res) {
    try {
      if (!res || !res.body) { done(null); return; }
      var parsed = parseOpenGraph(res.body, source);
      var result = emptyResult(platform, source);
      result.title = parsed.title;
      var i;
      for (i = 0; i < parsed.videos.length; i++) {
        if (usableGenericMediaUrl(parsed.videos[i])) {
          addSimpleItem(result, parsed.videos[i], "video", null, platformLabel(platform) + " video");
        }
      }
      for (i = 0; i < parsed.audios.length; i++) {
        if (usableGenericMediaUrl(parsed.audios[i])) {
          addSimpleItem(result, parsed.audios[i], "audio", null, platformLabel(platform) + " audio");
        }
      }
      if (!resultHasMedia(result)) {
        for (i = 0; i < parsed.images.length; i++) {
          addSimpleItem(result, parsed.images[i], "photo", null, platformLabel(platform) + " image");
        }
      }
      if (!resultHasMedia(result)) {
        result.originalOnly = true;
        note(result, "The page did not expose a direct downloadable media file in its public metadata.");
      }
      done(result);
    } catch (e) {
      amin.error("media-dl generic resolver error: " + e);
      done(null);
    }
  });
}

function resolveUrl(source, options, done) {
  var platform = classifyUrl(source);
  try {
    if (platform === "tiktok") resolveTikTok(source, options, done);
    else if (platform === "x") resolveX(source, options, done);
    else if (platform === "reddit") resolveReddit(source, options, done);
    else if (platform === "instagram") resolveInstagram(source, options, done);
    else resolveGeneric(source, options, done);
  } catch (e) {
    amin.error("media-dl resolver dispatch error: " + e);
    done(null);
  }
}

function canSendFile() {
  return typeof amin.sendFile === "function";
}

function trySendFile(mediaUrl, type, caption, chatId, headers) {
  if (!mediaUrl || !canSendFile()) return false;
  try {
    var opts = { type: type, caption: caption, chatId: chatId };
    if (headers) opts.headers = headers;
    return amin.sendFile(mediaUrl, opts) === true;
  } catch (e) {
    amin.error("media-dl sendFile error: " + e);
    return false;
  }
}

function mediaCaption(result, item, index, total) {
  var title = cleanTitle(result && result.title, "Media");
  var suffix = total > 1 ? " (" + (index + 1) + "/" + total + ")" : "";
  var icon = "🎬";
  if (item && item.candidates.length) {
    if (item.candidates[0].type === "photo") icon = "🖼";
    else if (item.candidates[0].type === "audio") icon = "🎵";
    else if (item.candidates[0].type === "file") icon = "📎";
  }
  return truncateText(icon + " " + title + suffix + "\nSource: " + result.source, 1000);
}

function formatLinks(result, source, heading) {
  var lines = [];
  lines.push(heading || ("🎬 " + cleanTitle(result && result.title, "Media")));
  lines.push("Source: " + source);
  if (!result || result.originalOnly) {
    lines.push("");
    lines.push("🔗 Original post:");
    lines.push(source);
  } else {
    var shown = 0;
    for (var i = 0; i < result.items.length && shown < 10; i++) {
      var item = result.items[i];
      if (item.hideLinks) continue;
      var url = itemPrimaryUrl(item);
      if (!url) continue;
      var type = item.candidates[0].type;
      var icon = type === "photo" ? "🖼" : (type === "audio" ? "🎵" : (type === "file" ? "📎" : "📹"));
      lines.push("");
      lines.push(icon + " " + (item.label || platformLabel(result.platform) + " media") + ":");
      lines.push(url);
      shown++;
    }
    if (!shown) {
      lines.push("");
      lines.push("🔗 Original post:");
      lines.push(source);
    }
    if (result.audioUrl && settingBool("includeAudio", true)) {
      lines.push("");
      lines.push("🎵 Audio (MP3):");
      lines.push(result.audioUrl);
    }
    if (result.redditAudioUrl) {
      lines.push("");
      lines.push("🎵 Reddit audio track:");
      lines.push(result.redditAudioUrl);
    }
  }
  if (result && result.notes && result.notes.length) {
    lines.push("");
    for (var n = 0; n < result.notes.length; n++) lines.push("ℹ️ " + result.notes[n]);
  }
  return truncateText(lines.join("\n"), MAX_FALLBACK_CHARS);
}

function selectedItems(result, options) {
  if (!result || !result.items) return [];
  var limit;
  if (options && options.all) limit = 10;
  else if (options && options.maxItems) limit = options.maxItems;
  else limit = settingBool("sendAllMedia", true) ? maxItemsSetting() : 1;
  limit = Math.max(1, Math.min(10, Math.floor(Number(limit) || 1)));
  return result.items.slice(0, limit);
}

function deliverResultInternal(chatId, msg, source, result, options) {
  options = options || {};
  if (!result) {
    amin.reply(msg, "❌ Couldn't resolve that link.\n\n🔗 Original link:\n" + source
      + "\n\nℹ️ The public resolver may be down, the post may be private/deleted, or the platform may not expose direct media.");
    amin.toast("❌ Couldn't resolve that link");
    recordHistory(chatId, classifyUrl(source) || "generic", source, false, 0);
    return false;
  }

  if (options.linksOnly) {
    amin.reply(msg, formatLinks(result, source, "🔗 Download links"));
    amin.toast("✅ Links posted");
    recordHistory(chatId, result.platform, source, true, 0);
    return false;
  }

  if (options.mp3Only) {
    if (!result.audioUrl) {
      amin.reply(msg, "❌ No MP3/audio track was found for that TikTok link.");
      amin.toast("❌ No audio found");
      recordHistory(chatId, result.platform, source, false, 0);
      return false;
    }
    startSendJob(chatId, msg, source, result, options, [{
      candidates: [{ url: result.audioUrl, type: "audio", headers: null, label: "MP3", size: 0 }],
      hideLinks: false, requiresSuccess: false,
      caption: mediaCaption(result, null, 0, 1)
    }], "mp3");
    return true;
  }

  var items = selectedItems(result, options);
  if (!items.length) {
    amin.reply(msg, formatLinks(result, source) + "\n\nℹ️ No directly sendable media was found.");
    amin.toast(result.originalOnly ? "ℹ️ Original link posted" : "⚠️ No sendable media found");
    recordHistory(chatId, result.platform, source, !!result.originalOnly, 0);
    return false;
  }

  // Precompute every caption BEFORE the first send: after a
  // blocking sendFile returns there is no budget left for string
  // work (see the budget note at the top of this file).
  var groups = [];
  for (var gi = 0; gi < items.length; gi++) {
    groups.push({
      candidates: items[gi].candidates, hideLinks: !!items[gi].hideLinks,
      requiresSuccess: false,
      caption: mediaCaption(result, items[gi], gi, items.length)
    });
  }
  if (result.redditAudioUrl && settingBool("redditAudioTrack", false)) {
    groups.push({
      candidates: [{ url: result.redditAudioUrl, type: "audio", headers: null, label: "Reddit audio", size: 0 }],
      hideLinks: false, requiresSuccess: true,
      caption: "🎵 Reddit audio track\nSource: " + source
    });
  }
  startSendJob(chatId, msg, source, result, options, groups, "media");
  return true;
}

// ---------------------------------------------------------------------
// Send job machine (v3.0.1): sends one candidate at a time and keeps
// the JS after each blocking sendFile call to a single elapsed-time
// check. When the current hook/callback window is nearly spent, the
// job hands over to a fresh budgeted callback via budgetTick() —
// this is what keeps long downloads from dying with
// "hook budget exceeded (onContextAction)".
// ---------------------------------------------------------------------

function startSendJob(chatId, msg, source, result, options, groups, reportMode) {
  advanceSendJob({
    chatId: chatId, msg: msg, source: source, result: result,
    options: options || {}, groups: groups, gi: 0, ci: 0,
    sent: 0, failed: [], reportMode: reportMode, finished: false
  });
}

function advanceSendJob(job) {
  try {
    while (!job.finished) {
      if (job.gi >= job.groups.length) { finishSendJob(job); return; }
      var group = job.groups[job.gi];
      if (group.requiresSuccess && job.sent === 0) { job.gi++; job.ci = 0; continue; }
      if (job.ci >= group.candidates.length) {
        job.failed.push(group); job.gi++; job.ci = 0; continue;
      }
      if (windowElapsedMs() > BUDGET_SOFT_LIMIT_MS) {
        budgetTick(function () { advanceSendJob(job); });
        return;
      }
      var candidate = group.candidates[job.ci];
      job.ci++;
      if (candidate.size && candidate.size > FILE_CAP_BYTES) continue;
      var ok = trySendFile(candidate.url, candidate.type,
        group.caption, job.chatId, candidate.headers);
      if (ok) { job.sent++; job.gi++; job.ci = 0; }
      if (windowElapsedMs() > BUDGET_SOFT_LIMIT_MS) {
        budgetTick(function () { advanceSendJob(job); });
        return;
      }
    }
  } catch (e) {
    amin.error("media-dl send job error: " + e);
    finishSendJob(job);
  }
}

function finishSendJob(job) {
  if (job.finished) return;
  job.finished = true;
  var chatId = job.chatId, msg = job.msg, source = job.source, result = job.result;
  try {
    if (job.reportMode === "mp3") {
      if (job.sent > 0) {
        amin.toast("✅ MP3 sent");
        recordHistory(chatId, result.platform, source, true, 1);
      } else {
        amin.reply(msg, "🎵 Audio (MP3):\n" + result.audioUrl
          + "\n\nℹ️ The audio file couldn't be sent directly, so here is the link instead.");
        amin.toast("⚠️ Couldn't send the MP3 — link posted");
        recordHistory(chatId, result.platform, source, false, 0);
      }
    } else if (job.sent > 0) {
      amin.toast("✅ Sent " + job.sent + (job.sent === 1 ? " file" : " files"));
      if (job.failed.length) {
        var failLines = ["⚠️ " + job.failed.length + " file(s) couldn't be sent directly:"];
        for (var f = 0; f < job.failed.length; f++) {
          var fg = job.failed[f];
          if (!fg.hideLinks && fg.candidates.length) failLines.push(fg.candidates[0].url);
        }
        if (result.platform === "instagram") {
          failLines.push("The original Instagram link is: " + source);
        }
        amin.reply(msg, truncateText(failLines.join("\n"), MAX_FALLBACK_CHARS));
      }
      if (result.audioUrl && settingBool("includeAudio", true)) {
        amin.reply(msg, "🎵 TikTok audio (MP3):\n" + result.audioUrl);
      }
      recordHistory(chatId, result.platform, source, true, job.sent);
    } else {
      amin.reply(msg, formatLinks(result, source)
        + "\n\nℹ️ The media couldn't be sent directly (the file may be too large or the download may have been rejected), so the available links are above.");
      amin.toast("⚠️ Couldn't send the file — links posted");
      recordHistory(chatId, result.platform, source, false, 0);
    }
  } catch (e) {
    amin.error("media-dl finish error: " + e);
  }
  activeJobs[str(chatId)] = false;
}

function deliverResult(chatId, msg, source, result, options) {
  var jobStarted = false;
  try {
    jobStarted = deliverResultInternal(chatId, msg, source, result, options) === true;
  } catch (e) {
    amin.error("media-dl delivery error: " + e);
    try { amin.reply(msg, "❌ Downloader Pro hit an error. The original link is:\n" + source); } catch (e2) {}
  }
  // When a send job started, finishSendJob owns the cleanup —
  // it runs later, under fresh budget windows.
  if (!jobStarted) activeJobs[str(chatId)] = false;
}

function startDownload(chatId, msg, source, options) {
  options = options || {};
  if (!source) return false;
  var key = str(chatId);
  if (activeJobs[key]) {
    if (!options.quiet) amin.toast("⏳ A download is already running in this chat");
    return false;
  }
  activeJobs[key] = true;
  markWindowStart();
  rememberUrl(chatId, source);
  if (!options.quietStart) amin.toast("⏳ Resolving link…");
  try {
    resolveUrl(source, options, function (result) {
      deliverResult(chatId, msg, source, result, options);
    });
  } catch (e) {
    activeJobs[key] = false;
    amin.error("media-dl start error: " + e);
    amin.reply(msg, "❌ Downloader Pro couldn't start that download. The original link is:\n" + source);
  }
  return true;
}


function parseCommandFlags(args) {
  var options = { linksOnly: false, all: false, quality: null };
  var tokens = str(args).split(/\s+/);
  for (var i = 0; i < tokens.length; i++) {
    var token = lower(tokens[i]);
    if (token === "link" || token === "links") options.linksOnly = true;
    else if (token === "all") options.all = true;
    else if (token === "hd") options.quality = "hd";
    else if (token === "sd") options.quality = "sd";
  }
  return options;
}

function repliedText(ctx) {
  if (ctx && ctx.replyTo) return ctx.replyTo.text || ctx.replyTo.caption || "";
  try {
    if (ctx && ctx.msg && ctx.msg.replyToId && typeof amin.getMessage === "function") {
      var target = amin.getMessage(ctx.chatId, ctx.msg.replyToId);
      if (target) return target.text || "";
    }
  } catch (e) { amin.debug("media-dl reply lookup failed: " + e); }
  return "";
}

function commandSource(ctx) {
  var argsUrl = extractUrls(ctx && ctx.args || "")[0] || null;
  if (argsUrl) return argsUrl;
  var replyUrl = extractClassifiedUrl(repliedText(ctx)) || extractUrls(repliedText(ctx))[0] || null;
  if (replyUrl) return replyUrl;
  if (ctx && ctx.msg) {
    var ownUrl = extractClassifiedUrl(ctx.msg.text || "");
    if (ownUrl) return ownUrl;
  }
  return rememberedUrl(ctx && ctx.chatId);
}

function usageText() {
  return "⬇️ Downloader Pro\n\n"
    + "Commands:\n"
    + "• /dl <link> — download media into this chat\n"
    + "• /dl all <link> — send up to 10 files from the post\n"
    + "• /dl hd <link> or /dl sd <link> — choose quality order\n"
    + "• /links <link> — post direct links without sending files\n"
    + "• /mp3 <TikTok link> — send the TikTok audio as MP3\n"
    + "• /last — show the last link remembered in this chat\n"
    + "• /recent — show this chat's recent download attempts\n"
    + "• /dlhelp — this help\n\n"
    + "You can also reply to a message containing a link with /dl, long-press a link and choose “Download media”, or use Download media in the attach menu after a supported link appears.\n\n"
    + "Supported: TikTok, Instagram public posts/reels, X/Twitter, Reddit, Pinterest, Threads/Facebook pages that expose public media, direct media files, and web pages with public OpenGraph media.";
}


function onLoad() {
  amin.debug("Downloader Pro v3 loaded");
}

function onMigrate(fromVersion, toVersion) {
  // v2 kept only settings (which keep the same keys here) plus an
  // in-memory last-link map, so there is no stored data to reshape.
  amin.info("Downloader Pro data migrated from v" + fromVersion + " to v" + toVersion);
}

function onMessage(msg) {
  try {
    if (!msg) return;
    var url = extractClassifiedUrl(msg.text || "");
    if (!url) return;
    rememberUrl(msg.chatId, url);

    if (msg.channel) return;

    if (settingBool("autoToastLinks", false) && !msg.outgoing) {
      resolveUrl(url, {}, function (result) {
        if (result && result.items && result.items.length) {
          amin.log("media-dl auto-resolved " + url + " -> " + result.items.length + " media item(s)");
          amin.toast("🎬 Media found — use /dl or Download media");
        }
      });
    }
  } catch (e) {
    amin.error("media-dl onMessage error: " + e);
  }
}

function onCommand(ctx) {
  try {
    if (!ctx) return;
    var command = lower(ctx.command || "").split("@")[0];

    if (command === "dlhelp" || command === "downloadhelp") {
      amin.reply(ctx.msg, usageText());
      return;
    }

    if (command === "last") {
      var last = rememberedUrl(ctx.chatId);
      amin.reply(ctx.msg, last ? "🔗 Last remembered link in this chat:\n" + last
        : "No supported link has been seen in this chat yet.");
      return;
    }

    if (command === "recent") {
      var entries = historyForChat(ctx.chatId, 5);
      if (!entries.length) {
        amin.reply(ctx.msg, "No downloads have been recorded in this chat yet.");
        return;
      }
      var lines = ["🕘 Recent downloads in this chat:"];
      for (var i = 0; i < entries.length; i++) {
        lines.push((entries[i].ok ? "✅ " : "❌ ") + platformLabel(entries[i].platform)
          + " — " + truncateText(entries[i].source, 160));
      }
      amin.reply(ctx.msg, truncateText(lines.join("\n"), MAX_FALLBACK_CHARS));
      return;
    }

    if (command === "mp3") {
      var mp3Source = commandSource(ctx);
      if (!mp3Source) {
        amin.reply(ctx.msg, "Usage: /mp3 <TikTok link> — or reply to a TikTok link with /mp3.");
        return;
      }
      if (classifyUrl(mp3Source) !== "tiktok") {
        amin.reply(ctx.msg, "🎵 MP3 extraction currently works for TikTok links.");
        return;
      }
      startDownload(ctx.chatId, ctx.msg, mp3Source, { mp3Only: true, via: "command" });
      return;
    }

    if (command === "dl" || command === "download" || command === "links") {
      var flags = parseCommandFlags(ctx.args || "");
      if (command === "links") flags.linksOnly = true;
      var source = commandSource(ctx);
      if (!source) {
        amin.reply(ctx.msg, "Usage: /dl <link>\n\nOr reply to a message containing a TikTok, Instagram, X, Reddit, Pinterest, Threads or direct-media link. Send /dlhelp for all options.");
        return;
      }
      flags.via = "command";
      startDownload(ctx.chatId, ctx.msg, source, flags);
      return;
    }
  } catch (e) {
    amin.error("media-dl onCommand error: " + e);
    try { amin.toast("❌ Downloader Pro failed — see plugin log"); } catch (e2) {}
  }
}

function onMenuAction(ctx) {
  try {
    var url = ctx ? rememberedUrl(ctx.chatId) : null;
    if (!url) {
      amin.toast("No supported link seen in this chat yet — send one first");
      return;
    }
    startDownload(ctx.chatId, { chatId: ctx.chatId, id: 0 }, url, { via: "menu" });
  } catch (e) {
    amin.error("media-dl onMenuAction error: " + e);
  }
}

function onContextAction(ctx) {
  try {
    if (!ctx) return;
    var text = ctx.msg ? (ctx.msg.text || "") : "";
    var url = extractClassifiedUrl(text) || extractUrls(text)[0] || rememberedUrl(ctx.chatId);
    if (!url) {
      amin.toast("No download link found in that message");
      return;
    }
    startDownload(ctx.chatId, ctx.msg, url, { via: "context-menu" });
  } catch (e) {
    amin.error("media-dl onContextAction error: " + e);
  }
}

function onPluginAction(actionId) {
  try {
    if (actionId === "stats") {
      var historyCount = 0, successCount = 0, rememberedCount = 0;
      if (dbAvailable()) {
        var keys = amin.db.keys("history", "h:") || [];
        historyCount = keys.length;
        for (var i = 0; i < keys.length; i++) {
          var entry = amin.db.get("history", keys[i]);
          if (entry && entry.ok) successCount++;
        }
        rememberedCount = (amin.db.keys("last", "chat:") || []).length;
      }
      var text = historyCount + " recorded downloads, " + successCount + " successful, "
        + rememberedCount + " remembered chat link(s).";
      amin.toast("⬇️ " + text);
      if (amin.ui && typeof amin.ui.notify === "function") {
        amin.ui.notify("Downloader Pro", text);
      }
      return;
    }
    if (actionId === "clear-history") {
      var cleared = clearDbNamespace("history");
      amin.toast("🧹 Cleared " + cleared + " download record(s)");
      return;
    }
    if (actionId === "forget-links") {
      var forgotten = clearDbNamespace("last");
      lastByChatFallback = {};
      try {
        if (amin.storage && typeof amin.storage.remove === "function") {
          amin.storage.remove("lastByChatFallback");
        }
      } catch (e) {}
      amin.toast("🧹 Forgot " + forgotten + " remembered link(s)");
    }
  } catch (e) {
    amin.error("media-dl onPluginAction error: " + e);
  }
}
