// ==AminGramPlugin==
// @id          link-cleaner
// @name        Link Cleaner
// @version     1.0
// @author      Amin
// @description /clean <url> strips tracking parameters (utm_*, fbclid, gclid, igshid, ref, si, …) and resolves short links. No argument reuses the last link seen in this chat — or tap Link Cleaner in the chat attach menu.
// @icon        link
// @capabilities commands, send, http, menu
// ==/AminGramPlugin==

// Last link seen per chat — the media-dl pattern: onMessage remembers
// it, /clean with no argument and the attach-menu button reuse it.
var lastByChat = {};

var URL_RE = /https?:\/\/[^\s<>"']+/g;

// Exact parameter names that are always tracking / share junk.
// Matched case-insensitively; anything starting with utm_ goes too.
var TRACKERS = {
  "fbclid": 1, "gclid": 1, "dclid": 1, "gbraid": 1, "wbraid": 1,
  "igshid": 1, "igsh": 1, "fb_source": 1, "fb_ref": 1,
  "ref": 1, "ref_src": 1, "ref_url": 1,
  "si": 1, "feature": 1, "spm": 1, "scm": 1,
  "mc_cid": 1, "mc_eid": 1, "msclkid": 1, "yclid": 1,
  "twclid": 1, "ttclid": 1, "sclid": 1, "rbclid": 1,
  "_ga": 1, "_gl": 1, "_hsenc": 1, "_hsmi": 1, "mkt_tok": 1,
  "vero_id": 1, "vero_conv": 1, "wickedid": 1,
  "oly_enc_id": 1, "oly_anon_id": 1, "__s": 1,
  "guccounter": 1, "guce_referrer": 1, "guce_referrer_sig": 1,
  "cmpid": 1, "icid": 1, "ns_campaign": 1, "ns_mchannel": 1,
  "ns_source": 1, "ns_linkname": 1, "ns_fee": 1,
  "pd_rd_r": 1, "pd_rd_w": 1, "pd_rd_wg": 1, "ascsubtag": 1
};

// Hosts that exist only to redirect somewhere else. Only these get
// an amin.httpGet resolution attempt — a normal link is cleaned
// locally, with no network call at all.
var SHORTENERS = {
  "bit.ly": 1, "t.co": 1, "tinyurl.com": 1, "goo.gl": 1,
  "rb.gy": 1, "cutt.ly": 1, "is.gd": 1, "v.gd": 1, "s.id": 1,
  "ow.ly": 1, "buff.ly": 1, "dlvr.it": 1, "lnkd.in": 1,
  "j.mp": 1, "q.gs": 1, "po.st": 1, "x.co": 1, "tr.im": 1,
  "bit.do": 1, "mcaf.ee": 1, "aka.ms": 1, "youtu.be": 1,
  "amzn.to": 1, "rebrand.ly": 1, "shorturl.at": 1, "bl.ink": 1
};

function extractUrl(text) {
  if (!text) return null;
  var m = String(text).match(URL_RE);
  if (m && m.length) return m[0].replace(/[)\].,;]+$/, "");
  // Bare domain typed without a scheme, e.g. /clean example.com/a?x=1
  var bare = String(text).trim();
  if (/^[^\s]+\.[a-z]{2,}([/?#]\S*)?$/i.test(bare)) return "https://" + bare;
  return null;
}

function hostOf(url) {
  var m = String(url || "").match(/^https?:\/\/([^/?#:]+)/i);
  return m ? m[1].toLowerCase() : "";
}

function isTracker(name) {
  var n = String(name || "").toLowerCase();
  try { n = decodeURIComponent(n); } catch (e) { /* keep the raw name */ }
  if (n.indexOf("utm_") === 0) return true;
  return !!TRACKERS[n];
}

// Removes every tracking parameter, keeping the real query, the
// path and the #fragment untouched. Pure string work — no URL
// parser exists in the plugin runtime, and none is needed.
function cleanUrl(url) {
  var s = String(url || "");
  var frag = "";
  var hash = s.indexOf("#");
  if (hash !== -1) { frag = s.substring(hash); s = s.substring(0, hash); }
  var q = s.indexOf("?");
  if (q === -1) return s + frag;
  var base = s.substring(0, q);
  var pairs = s.substring(q + 1).split("&");
  var kept = [];
  for (var i = 0; i < pairs.length; i++) {
    if (!pairs[i]) continue;
    var eq = pairs[i].indexOf("=");
    var name = eq === -1 ? pairs[i] : pairs[i].substring(0, eq);
    if (!isTracker(name)) kept.push(pairs[i]);
  }
  return base + (kept.length ? "?" + kept.join("&") : "") + frag;
}

// amin.httpGet follows redirects inside the bridge but returns only
// the final page's body — the Location chain is not exposed. The
// final address is still recoverable from most landing pages: a
// canonical link, an og:url, or a meta-refresh target. When none of
// those is present (or the fetch fails) the cleaned original wins.
function resolveRedirect(url) {
  try {
    if (!SHORTENERS[hostOf(url)]) return url;
    var raw = amin.httpGet(url);
    if (!raw) return url;
    var patterns = [
      /<link[^>]+rel=["']canonical["'][^>]+href=["'](https?:\/\/[^"']+)["']/i,
      /<link[^>]+href=["'](https?:\/\/[^"']+)["'][^>]+rel=["']canonical["']/i,
      /<meta[^>]+property=["']og:url["'][^>]+content=["'](https?:\/\/[^"']+)["']/i,
      /<meta[^>]+content=["'](https?:\/\/[^"']+)["'][^>]+property=["']og:url["']/i,
      /<meta[^>]+http-equiv=["']refresh["'][^>]+content=["'][^"']*?url=(https?:\/\/[^"']+)["']/i,
      /(?:window\.)?location(?:\.href\s*=|\.replace\(\s*)["'](https?:\/\/[^"']+)["']/
    ];
    for (var i = 0; i < patterns.length; i++) {
      var m = String(raw).match(patterns[i]);
      if (m && m[1]) {
        var candidate = m[1].replace(/&amp;/g, "&").trim();
        if (candidate !== url && candidate.length <= 2048) return candidate;
      }
    }
  } catch (e) { amin.log("link-cleaner resolve error: " + e); }
  return url;
}

function doClean(msg, rawUrl) {
  var resolved = resolveRedirect(rawUrl);
  var finalUrl = cleanUrl(resolved);
  amin.log("link-cleaner: " + rawUrl + " -> " + finalUrl);
  var out = "🔗 Clean link:\n" + finalUrl;
  if (resolved !== rawUrl) out += "\n↪️ Resolved from: " + rawUrl;
  amin.reply(msg, out);
  amin.toast("✅ Clean link ready");
}

function onMessage(msg) {
  try {
    var url = extractUrl(msg.text || "");
    if (url) lastByChat[String(msg.chatId)] = url;
  } catch (e) { amin.log("link-cleaner onMessage error: " + e); }
}

function onCommand(ctx) {
  try {
    if (String(ctx.command || "").toLowerCase() !== "clean") return;
    var url = extractUrl(ctx.args || "")
      || extractUrl((ctx.msg && ctx.msg.text) || "")
      || lastByChat[String(ctx.chatId)];
    if (!url) {
      amin.reply(ctx.msg, "Usage: /clean <url> — or send a link in this chat first, then /clean");
      return;
    }
    doClean(ctx.msg, url);
  } catch (e) {
    amin.log("link-cleaner onCommand error: " + e);
    try { amin.reply(ctx.msg, "⚠️ Couldn't clean that link — see the plugin log."); } catch (e2) {}
  }
}

function onMenuAction(ctx) {
  try {
    var url = lastByChat[String(ctx.chatId)];
    if (!url) { amin.toast("No link seen in this chat yet — send one first, or use /clean <url>"); return; }
    doClean({ chatId: ctx.chatId, id: 0 }, url);
  } catch (e) { amin.log("link-cleaner onMenuAction error: " + e); }
}
