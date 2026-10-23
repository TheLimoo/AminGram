// ==AminGramPlugin==
// @id          article-composer
// @name        Article Composer
// @version     1.0.0
// @author      Amin
// @description Compose a rich article (markdown-lite text + up to 10 image URLs) and have @awminai post it into your DM with full Telegram formatting — then forward it anywhere. Posting happens only when you tap Post.
// @capabilities observe, commands, send, menu, file, settings, ui, http
// @icon        text
// @menuLabel   Article
// @minPlatform 2
// ==/AminGramPlugin==

// =====================================================================
// Article Composer — rich posts via the @awminai Bot Bridge (`post`).
//
// WHY THIS SHAPE (decided from the actual platform v2 surfaces):
//  • Plugins send PLAIN text only (amin.send has no entity support),
//    so a formatted article can only be posted by the bot — bridge
//    action `post` (BRIDGE-PROTOCOL.md §4.6). This plugin composes,
//    the bot posts into your DM, you forward from there.
//  • AUTHORING = markdown-lite in a /article command. The platform
//    has NO rich-text editor surface: PLUGIN_PAGE rows are static
//    info/action rows (read once at install) and there is no text
//    input widget or selection API a plugin could build an editor
//    on. So the draft is written as text with light markup:
//      **bold**  *italic*  __underline__  ~~strike~~  ||spoiler||
//      `code`  ```pre```  [link text](https://…)  > quote lines
//    The parser below converts it to plain text + Bot API entities
//    with UTF-16 offsets (Telegram's own arithmetic).
//  • ENTRY POINTS (all explicit user actions — nothing auto-sends):
//      – attach-menu button "Article" (menu capability): works from
//        ANY chat; sends the draft preview card to your bot DM.
//      – commands (type them in the @awminai DM or Saved Messages,
//        so the command messages themselves stay out of other chats):
//        /article <text> · /aimg <image url> · /apreview · /apost ·
//        /acancel · /ahelp
//      – this plugin's hub page rows (Preview / Post / Discard).
//  • IMAGES are a URL queue (≤10): amin.sendFile is URL-only in this
//    platform — no surface can send a local/picked file path — so
//    each queued URL is uploaded to the bot DM with sendFile at Post
//    time, and the bot's media stash (30 min) feeds the `post`.
//  • SEND is a job machine in the Downloader Pro pattern: ONE
//    blocking sendFile per budget window, the counter advanced
//    BEFORE the call, re-entry through an async-GET budget tick —
//    no meaningful JS ever runs after a blocking send in the same
//    window. The bridge request itself fires from the finish step.
//  • SIZE GUARD: bridge requests are never chunked — one message.
//    The exact encoded envelope is measured before sending; an
//    article that cannot fit is refused locally (draft kept).
//  • `http` is declared ONLY for the budget tick (the gstatic 204
//    ping media-dl uses); the plugin makes no other web requests.
// =====================================================================

// ============================================================================
// bridge-client.js — AminGram Plugin ⇄ Bot Bridge, plugin-side client v1
// ============================================================================
// Embed this file in an AminGram plugin (paste it above your hooks, or
// keep it as the top section of the plugin file) to offload work to the
// @awminai bot: rich button cards, server-side media resolving,
// translation, file fetching, and a per-plugin cloud store. Full wire
// spec: PLUGIN-BRIDGE.md (bot project: BRIDGE-PROTOCOL.md).
//
// ADOPTION NOTES
//   • Capabilities: the host plugin must declare `observe` (to see the
//     bot's replies in onMessage) and `send` (amin.send) in its
//     @capabilities header — the same pair Theme Changer already uses
//     for its bot buttons.
//   • Wire-up (three lines):
//       var bridge = AminBridge.create({ ns: "my-plugin-id",
//                                        botChatId: 8493619765,
//                                        onAction: myCardAction });
//       // inside your onMessage(msg):   bridge.handleMessage(msg);
//       // anywhere:                     bridge.resolve(url, function (err, data) { … });
//   • botChatId: pass the @awminai chat id; plugins usually learn it
//     from any bot message (see theme-manager's botChatId setting).
//     A getBotChatId() function may be passed instead of a number.
//   • Callbacks fire from onMessage context — i.e. inside a hook, with
//     the platform's 5 s budget. Keep callbacks light; the heavy work
//     already happened on the bot.
//   • Timeouts are LAZY (the platform gives plugins no timers): a
//     request times out when the next message arrives or when you
//     call bridge.poll() from any hook. Default timeout 30 s.
//   • If the bot is unreachable or not deployed with the bridge, you
//     simply get a timeout error — always keep a local fallback.
//   • Never put secrets in payloads: base64url is an encoding, not
//     encryption, and payload messages are visible in the bot DM.
// ============================================================================

var AminBridge = (function () {
  var REQ_PREFIX = "🔗BRIDGE ";
  var RES_PREFIX = "⚡BRIDGE ";
  var B64C = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

  // --- UTF-8 → base64url (encoder identical to theme-manager.js) ---
  function b64urlEncode(str) {
    var bytes = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c < 0x80) {
        bytes.push(c);
      } else if (c < 0x800) {
        bytes.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
      } else if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
        var c2 = str.charCodeAt(i + 1);
        if (c2 >= 0xdc00 && c2 <= 0xdfff) {
          var cp = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
          bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
          i++;
        } else {
          bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
        }
      } else {
        bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
      }
    }
    var out = "";
    for (var j = 0; j < bytes.length; j += 3) {
      var b0 = bytes[j], b1 = j + 1 < bytes.length ? bytes[j + 1] : 0, b2 = j + 2 < bytes.length ? bytes[j + 2] : 0;
      out += B64C.charAt(b0 >> 2);
      out += B64C.charAt(((b0 & 3) << 4) | (b1 >> 4));
      if (j + 1 < bytes.length) out += B64C.charAt(((b1 & 15) << 2) | (b2 >> 6));
      if (j + 2 < bytes.length) out += B64C.charAt(b2 & 63);
    }
    return out.replace(/\+/g, "-").replace(/\//g, "_");
  }

  // --- base64url → bytes (decoder identical to the bot's b64url.js) ---
  function b64urlToBytes(s) {
    var clean = String(s || "").replace(/-/g, "+").replace(/_/g, "/").replace(/\s+/g, "");
    var bytes = [];
    var buf = 0, bits = 0;
    for (var i = 0; i < clean.length; i++) {
      var ch = clean.charAt(i);
      if (ch === "=") break;
      var v = B64C.indexOf(ch);
      if (v < 0) continue;
      buf = (buf << 6) | v; bits += 6;
      if (bits >= 8) { bits -= 8; bytes.push((buf >> bits) & 0xff); }
    }
    return bytes;
  }

  function utf8Decode(bytes) {
    var out = "";
    var i = 0;
    while (i < bytes.length) {
      var b = bytes[i];
      if (b < 0x80) { out += String.fromCharCode(b); i += 1; }
      else if (b >= 0xc0 && b < 0xe0) {
        out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f)); i += 2;
      } else if (b >= 0xe0 && b < 0xf0) {
        out += String.fromCharCode(((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f)); i += 3;
      } else {
        var cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f);
        var cc = cp - 0x10000;
        out += String.fromCharCode(0xd800 + (cc >> 10), 0xdc00 + (cc & 0x3ff)); i += 4;
      }
    }
    return out;
  }

  function encodePayload(obj) { return b64urlEncode(JSON.stringify(obj)); }
  function decodePayload(s) { return JSON.parse(utf8Decode(b64urlToBytes(s))); }

  function create(opts) {
    opts = opts || {};
    var ns = String(opts.ns || "");
    if (!ns) throw new Error("AminBridge.create: opts.ns (plugin id) is required");
    var timeoutMs = opts.timeout || 30000;
    var onAction = typeof opts.onAction === "function" ? opts.onAction : null;
    var pending = {};   // id -> { cb, at }
    var chunkBuf = {};  // id -> { parts: {}, n }
    var seq = 0;

    function botChatId() {
      if (typeof opts.getBotChatId === "function") return opts.getBotChatId();
      return opts.botChatId || 0;
    }

    function newId() {
      seq++;
      return ns + "-" + Date.now().toString(36) + "-" + seq + "-"
        + Math.floor(Math.random() * 1679616).toString(36);
    }

    function sweepTimeouts() {
      var now = Date.now();
      for (var id in pending) {
        if (!Object.prototype.hasOwnProperty.call(pending, id)) continue;
        if (now - pending[id].at > timeoutMs) {
          var p = pending[id];
          delete pending[id];
          if (p.cb) p.cb({ code: "timeout", message: "no reply from @awminai within " + timeoutMs + "ms" }, null);
        }
      }
    }

    function request(action, data, cb) {
      var id = newId();
      var env = { v: 1, ns: ns, action: action, id: id, data: data || {} };
      pending[id] = { cb: typeof cb === "function" ? cb : null, at: Date.now() };
      try {
        amin.send(botChatId(), REQ_PREFIX + encodePayload(env));
      } catch (e) {
        delete pending[id];
        if (cb) cb({ code: "send_failed", message: String(e) }, null);
      }
      return id;
    }

    function deliver(env) {
      if (!env || typeof env !== "object") return;
      if (env.ns && env.ns !== ns) return;
      if (env.data && env.data.kind === "action") {
        if (onAction) onAction(env.data.action, env.data.arg, env);
        return;
      }
      var p = pending[env.id];
      if (!p) return;
      delete pending[env.id];
      if (!p.cb) return;
      if (env.ok) p.cb(null, env.data || {});
      else p.cb(env.error || { code: "unknown", message: "bridge error" }, null);
    }

    // Call from the host plugin's onMessage. Returns true when the
    // message was a bridge reply/action (host may stop processing).
    function handleMessage(msg) {
      try {
        if (!msg || msg.outgoing || !msg.text) return false;
        if (msg.text.indexOf(RES_PREFIX) !== 0) return false;
        var bc = botChatId();
        if (bc && msg.chatId && String(msg.chatId) !== String(bc)) return false;
        var head = decodePayload(msg.text.slice(RES_PREFIX.length).trim());
        if (head && head.chunk && typeof head.part === "string") {
          var key = String(head.id);
          var buf = chunkBuf[key] || (chunkBuf[key] = { parts: {}, n: head.chunk.n });
          buf.parts[head.chunk.i] = head.part;
          var got = 0;
          for (var k in buf.parts) { if (Object.prototype.hasOwnProperty.call(buf.parts, k)) got++; }
          if (got >= buf.n) {
            var full = "";
            for (var i = 0; i < buf.n; i++) full += buf.parts[i];
            delete chunkBuf[key];
            deliver(decodePayload(full));
          }
          sweepTimeouts();
          return true;
        }
        deliver(head);
        sweepTimeouts();
        return true;
      } catch (e) {
        return false;
      }
    }

    return {
      ns: ns,
      request: request,
      handleMessage: handleMessage,
      poll: sweepTimeouts,
      pendingCount: function () { var n = 0; for (var k in pending) { if (Object.prototype.hasOwnProperty.call(pending, k)) n++; } return n; },

      // Convenience wrappers — one per bridge action.
      card: function (cardData, cb) { return request("card", cardData, cb); },
      resolve: function (url, cb) { return request("resolve", { url: url }, cb); },
      storeSet: function (value, cb) { return request("store", { op: "set", value: value }, cb); },
      storeGet: function (cb) { return request("store", { op: "get" }, cb); },
      storeDelete: function (cb) { return request("store", { op: "delete" }, cb); },
      translate: function (text, to, cb) {
        if (typeof to === "function") { cb = to; to = null; }
        return request("translate", to ? { text: text, to: to } : { text: text }, cb);
      },
      fetchFile: function (url, opts2, cb) {
        if (typeof opts2 === "function") { cb = opts2; opts2 = {}; }
        var d = { url: url };
        if (opts2) { for (var k in opts2) { if (Object.prototype.hasOwnProperty.call(opts2, k)) d[k] = opts2[k]; } }
        return request("fetch", d, cb);
      }
    };
  }

  return {
    create: create,
    REQ_PREFIX: REQ_PREFIX,
    RES_PREFIX: RES_PREFIX,
    encode: encodePayload,       // exposed for tests / debugging
    decode: decodePayload
  };
})();

// =====================================================================
// markdown-lite → plain text + Telegram entities
// =====================================================================
// Block pass: consecutive "> " lines form one blockquote block;
// everything else is a normal block (lines joined, so inline spans
// may cross line breaks inside a block, never across a quote edge).
// Inline pass: recursive-descent over paired delimiters. Offsets are
// JS string indices = UTF-16 code units = Telegram's entity units,
// so emoji before a span shift it by 2, exactly as Telegram counts.

function acParseArticle(src) {
  src = String(src == null ? "" : src);
  var lines = src.split("\n");
  var blocks = [];
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i];
    var q = /^>\s?/.test(line);
    var content = q ? line.replace(/^>\s?/, "") : line;
    var last = blocks.length ? blocks[blocks.length - 1] : null;
    if (last && last.quote === q) last.lines.push(content);
    else blocks.push({ quote: q, lines: [content] });
  }
  var outText = "", entities = [];
  for (var b = 0; b < blocks.length; b++) {
    if (b > 0) outText += "\n";
    var r = acParseInline(blocks[b].lines.join("\n"));
    var base = outText.length;
    outText += r.text;
    for (var e = 0; e < r.entities.length; e++) {
      r.entities[e].offset += base;
      entities.push(r.entities[e]);
    }
    if (blocks[b].quote && r.text.length > 0) {
      entities.push({ type: "blockquote", offset: base, length: r.text.length });
    }
  }
  return { text: outText, entities: entities };
}

function acParseInline(src) {
  var st = { s: String(src == null ? "" : src), i: 0 };
  var r = acParseSeq(st, null);
  return { text: r.text, entities: r.entities };
}

// Parses until end of input or one of `closers`. Returns
// { text, entities, closed } — entity offsets relative to `text`.
function acParseSeq(st, closers) {
  var text = "", entities = [];
  function shiftInto(inner, type, extra) {
    var off = text.length, k;
    for (k = 0; k < inner.entities.length; k++) {
      inner.entities[k].offset += off;
      entities.push(inner.entities[k]);
    }
    text += inner.text;
    if (type) {
      var ent = { type: type, offset: off, length: inner.text.length };
      if (extra) {
        for (var key in extra) {
          if (Object.prototype.hasOwnProperty.call(extra, key)) ent[key] = extra[key];
        }
      }
      entities.push(ent);
    }
  }
  while (st.i < st.s.length) {
    var rest = st.s.substring(st.i), c, hit;
    if (closers) {
      hit = null;
      for (c = 0; c < closers.length; c++) {
        if (rest.indexOf(closers[c]) === 0) { hit = closers[c]; break; }
      }
      if (hit) { st.i += hit.length; return { text: text, entities: entities, closed: hit }; }
    }
    // ``` pre block (optional one-word language line)
    if (rest.indexOf("```") === 0) {
      var endPre = rest.indexOf("```", 3);
      if (endPre >= 3) {
        var innerPre = rest.substring(3, endPre), lang = "";
        var nl = innerPre.indexOf("\n");
        if (nl > 0 && nl <= 24 && !/\s/.test(innerPre.substring(0, nl))) {
          lang = innerPre.substring(0, nl);
          innerPre = innerPre.substring(nl + 1);
        }
        if (innerPre.length) {
          var pe = { type: "pre", offset: text.length, length: innerPre.length };
          if (lang) pe.language = lang;
          entities.push(pe);
          text += innerPre;
        }
        st.i += endPre + 3;
        continue;
      }
    }
    // `code`
    if (rest.charAt(0) === "`") {
      var endCode = rest.indexOf("`", 1);
      if (endCode > 1) {
        var codeTxt = rest.substring(1, endCode);
        entities.push({ type: "code", offset: text.length, length: codeTxt.length });
        text += codeTxt;
        st.i += endCode + 1;
        continue;
      }
    }
    // [text](url) — the text may itself carry inline markup
    if (rest.charAt(0) === "[") {
      var lm = /^\[([^\]\n]{1,300})\]\((https?:\/\/[^\s)]{1,1500}|tg:\/\/[^\s)]{1,1500})\)/.exec(rest);
      if (lm) {
        shiftInto(acParseInline(lm[1]), "text_link", { url: lm[2] });
        st.i += lm[0].length;
        continue;
      }
    }
    // paired delimiters, longest first; a failed pair stays literal
    var pairs = [["**", "bold"], ["__", "underline"], ["~~", "strikethrough"], ["||", "spoiler"], ["*", "italic"]];
    var consumed = false;
    for (var p = 0; p < pairs.length; p++) {
      var delim = pairs[p][0];
      if (rest.indexOf(delim) === 0) {
        var save = st.i;
        st.i += delim.length;
        var inner = acParseSeq(st, [delim]);
        if (inner.closed === delim && inner.text.length > 0) {
          shiftInto(inner, pairs[p][1], null);
          consumed = true;
        } else {
          st.i = save;
        }
        break; // the longest matching delimiter decides — no fall-through
      }
    }
    if (consumed) continue;
    text += rest.charAt(0);
    st.i += 1;
  }
  return { text: text, entities: entities, closed: null };
}

// =====================================================================
// Draft store (amin.db) + bot chat id
// =====================================================================

var AC_DB_NS = "draft";
var AC_MAX_IMAGES = 10;

function acLoadDraft() {
  var d = null;
  try { d = amin.db.get(AC_DB_NS, "current"); } catch (e) { d = null; }
  if (!d || typeof d !== "object") d = {};
  return {
    src: typeof d.src === "string" ? d.src : "",
    text: typeof d.text === "string" ? d.text : "",
    entities: Array.isArray(d.entities) ? d.entities : [],
    images: Array.isArray(d.images) ? d.images : []
  };
}

function acSaveDraft(d) {
  try { amin.db.put(AC_DB_NS, "current", d); } catch (e) { amin.error("article-composer draft save failed: " + e); }
}

function acClearDraft() {
  try { amin.db.del(AC_DB_NS, "current"); } catch (e) { /* already gone */ }
}

function acHasDraft(d) { return !!(d && (d.text || (d.images && d.images.length))); }

function getBotChatId() {
  var v = parseInt(amin.storage.get("botChatId", "8493619765"), 10);
  return isNaN(v) ? 0 : v;
}

// Learn the bot DM chat id from the bot's own messages (theme-manager
// pattern), so the setting only needs hand-editing if it ever drifts.
function acLearnBotChat(msg) {
  try {
    if (!msg || msg.outgoing || !msg.chatId) return;
    var isBotUser = msg.senderIsBot && msg.senderUsername
      && String(msg.senderUsername).toLowerCase() === "awminai";
    var isBridgeReply = typeof msg.text === "string" && msg.text.indexOf("⚡BRIDGE ") === 0;
    if ((isBotUser || isBridgeReply) && String(msg.chatId) !== String(getBotChatId())) {
      amin.storage.set("botChatId", String(msg.chatId));
    }
  } catch (e) { /* learning is best-effort */ }
}

// =====================================================================
// Bridge
// =====================================================================

var bridge = AminBridge.create({
  ns: "article-composer",
  getBotChatId: getBotChatId,
  timeout: 60000,
  onAction: function (action, arg, env) {
    if (action === "post") acStartPost();
    else if (action === "discard") {
      acClearDraft();
      if (typeof amin.toast === "function") amin.toast("🗑 Article draft discarded");
    }
  }
});

function acEscHtml(s) {
  return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// The preview card in the bot DM — the bot renders it, the tap comes
// back through onAction above. This is also the attach-menu entry's
// payload: composing can start in any chat, the card lands in the DM.
function acSendPreviewCard() {
  var d = acLoadDraft();
  if (!acHasDraft(d)) {
    if (typeof amin.toast === "function") {
      amin.toast("No article draft yet — write one with /article in the @awminai DM");
    }
    return false;
  }
  if (!getBotChatId()) {
    if (amin.ui) amin.ui.notify("Article Composer", "Open your @awminai DM once (send /start there) so I know where to post.");
    return false;
  }
  var preview = d.text.length > 700 ? d.text.substring(0, 700) + "…" : d.text;
  var foot = "\n\n— " + d.text.length + " chars · " + d.entities.length + " format spans · "
    + d.images.length + " image(s) queued";
  bridge.card({
    title: "📰 Article draft",
    text: acEscHtml(preview) + acEscHtml(foot),
    buttons: [[
      { label: "📤 Post to my DM", action: "post", arg: "" },
      { label: "🗑 Discard", action: "discard", arg: "" }
    ]]
  }, function (err) {
    if (err && amin.ui) {
      amin.ui.notify("Article Composer", "⚠️ Could not reach @awminai (" + (err.code || "error") + ") — is the bot DM open?");
    }
  });
  return true;
}

// =====================================================================
// Post job machine — one blocking sendFile per budget window
// (Downloader Pro pattern: counter advanced BEFORE the call, re-entry
// through an async-GET budget tick, finish step in a fresh window
// whenever the current one is nearly spent.)
// =====================================================================

var AC_BUDGET_SOFT_LIMIT_MS = 3500;
var AC_BUDGET_TICK_URL = "https://www.gstatic.com/generate_204";
var acWindowStartMs = 0;
var acPostJob = null;

function acNowMs() { return Date.now(); }
function acMarkWindowStart() { acWindowStartMs = acNowMs(); }
function acWindowElapsedMs() { return acWindowStartMs ? acNowMs() - acWindowStartMs : 0; }

function acBudgetTick(fn) {
  if (typeof amin.httpGetAsync === "function") {
    try {
      amin.httpGetAsync(AC_BUDGET_TICK_URL, null, function () {
        acMarkWindowStart();
        fn();
      });
      return;
    } catch (e) { amin.debug("article-composer budget tick fell back to direct call: " + e); }
  }
  acMarkWindowStart();
  fn();
}

function acGuessImageType(url) {
  var u = String(url).toLowerCase().split("?")[0];
  if (/\.(mp4|mov|webm|mkv)$/.test(u)) return "video";
  if (/\.(jpg|jpeg|png|webp|gif)$/.test(u)) return "photo";
  return "file";
}

function acTrySendImage(url) {
  // sendFile downloads + posts into the bot DM as real media; the bot
  // stashes it for the `post` that follows. BLOCKING — callers keep
  // the JS after this call to an elapsed-time check only.
  if (typeof amin.sendFile !== "function") return false;
  try {
    return amin.sendFile(url, { type: acGuessImageType(url), chatId: getBotChatId() }) === true;
  } catch (e) {
    amin.error("article-composer sendFile error: " + e);
    return false;
  }
}

function acStartPost() {
  var d = acLoadDraft();
  if (!acHasDraft(d)) {
    if (typeof amin.toast === "function") amin.toast("Nothing to post — the draft is empty");
    return;
  }
  if (acPostJob && !acPostJob.finished) {
    if (typeof amin.toast === "function") amin.toast("⏳ A post is already in flight…");
    return;
  }
  if (!getBotChatId()) {
    if (amin.ui) amin.ui.notify("Article Composer", "Open your @awminai DM once (send /start there) so I know where to post.");
    return;
  }
  acPostJob = {
    urls: d.images.slice(), idx: 0, sent: 0, failed: 0,
    text: d.text, entities: d.entities, finished: false
  };
  acMarkWindowStart();
  acAdvancePostJob();
}

function acAdvancePostJob() {
  var job = acPostJob;
  if (!job || job.finished) return;
  try {
    while (job.idx < job.urls.length) {
      if (acWindowElapsedMs() > AC_BUDGET_SOFT_LIMIT_MS) { acBudgetTick(acAdvancePostJob); return; }
      var url = job.urls[job.idx];
      job.idx++; // advance BEFORE the blocking call — nothing meaningful runs after it in this window
      if (acTrySendImage(url)) job.sent++; else job.failed++;
      if (acWindowElapsedMs() > AC_BUDGET_SOFT_LIMIT_MS) { acBudgetTick(acAdvancePostJob); return; }
    }
    // Uploads done — the finish step (bridge request + reporting)
    // gets a fresh window whenever this one is nearly spent.
    if (acWindowElapsedMs() > AC_BUDGET_SOFT_LIMIT_MS) { acBudgetTick(acFinishPostJob); return; }
    acFinishPostJob();
  } catch (e) {
    amin.error("article-composer post job error: " + e);
    job.finished = true;
    if (amin.ui) amin.ui.notify("Article Composer", "⚠️ Posting stopped midway — the draft is kept; check the plugin log.");
  }
}

function acFinishPostJob() {
  var job = acPostJob;
  if (!job || job.finished) return;
  job.finished = true;
  var data = { text: job.text, entities: job.entities, mediaCount: job.sent };
  // Bridge requests are never chunked: measure the exact envelope.
  var probe = AminBridge.encode({ v: 1, ns: "article-composer", action: "post", id: "probe", data: data });
  if (probe.length + "🔗BRIDGE ".length > 3900) {
    if (amin.ui) amin.ui.notify("Article Composer",
      "⚠️ This article is too long for one bridge request (" + probe.length
      + " encoded chars). Shorten it or split it in two — the draft is kept.");
    return;
  }
  if (job.failed && typeof amin.toast === "function") {
    amin.toast("⚠️ " + job.failed + " image(s) could not be uploaded and were skipped");
  }
  bridge.request("post", data, function (err, res) {
    if (err) {
      if (amin.ui) amin.ui.notify("Article Composer",
        "⚠️ Post failed (" + (err.code || "error") + "): " + (err.message || "")
        + " — the draft is kept, fix and /apost again.");
      return;
    }
    acClearDraft();
    if (amin.ui) amin.ui.notify("Article Composer",
      "📰 Article posted to your @awminai DM"
      + (res && res.mediaUsed ? " with " + res.mediaUsed + " image(s)" : "")
      + " — forward it anywhere.");
  });
}

// =====================================================================
// Hooks
// =====================================================================

var SETTINGS = [
  { key: "botChatId", type: "text", title: "@awminai chat id", def: "8493619765",
    help: "Your DM chat id with @awminai. Learned automatically from the bot's messages — edit only if it is wrong." }
];

var PLUGIN_PAGE = [
  { type: "info", text: "Compose in the @awminai DM (or Saved Messages): /article <text> writes the draft in markdown-lite — **bold**, *italic*, __underline__, ~~strike~~, ||spoiler||, `code`, [link](https://…), > quote lines. /aimg <image url> queues up to 10 images. Post sends the article into your @awminai DM — forward it anywhere from there." },
  { type: "action", id: "preview", title: "Preview draft card", subtitle: "Sends the preview + Post button to @awminai" },
  { type: "action", id: "post", title: "Post article to my DM", subtitle: "Uploads the queued images, then the bot posts the article" },
  { type: "action", id: "discard", title: "Discard draft", subtitle: "Clears the text and the image queue" }
];

function onPluginAction(actionId) {
  if (actionId === "preview") acSendPreviewCard();
  else if (actionId === "post") acStartPost();
  else if (actionId === "discard") {
    acClearDraft();
    if (typeof amin.toast === "function") amin.toast("🗑 Article draft discarded");
  }
}

// Attach-menu entry — available while composing in ANY chat. It
// never posts into that chat: it sends the draft card to the bot DM.
function onMenuAction(ctx) {
  if (acSendPreviewCard() && typeof amin.toast === "function") {
    amin.toast("📰 Draft card sent to your @awminai DM");
  }
}

function onMessage(msg) {
  acLearnBotChat(msg);
  if (bridge.handleMessage(msg)) return true;
}

var AC_HELP = "📰 *Article Composer*\n"
  + "Write the draft here (or in Saved Messages):\n"
  + "• /article <text> — set the article text (markdown-lite: **bold**, *italic*, __underline__, ~~strike~~, ||spoiler||, `code`, [link](url), > quote)\n"
  + "• /aimg <image url> — queue an image (up to 10); /aimg clear empties the queue\n"
  + "• /apreview — preview card with a Post button, here in the DM\n"
  + "• /apost — post the article into this DM (as the bot), ready to forward\n"
  + "• /acancel — discard the draft";

function onCommand(ctx) {
  var cmd = ctx.command, args = (ctx.args || "").trim();
  if (cmd === "article") {
    if (!args) {
      var cur = acLoadDraft();
      amin.reply(ctx.msg, acHasDraft(cur)
        ? "Draft on file: " + cur.text.length + " chars, " + cur.images.length + " image(s). /apreview to see it, /apost to post, or /article <text> to replace the text."
        : "No draft yet. Usage: /article <your markdown-lite text>");
      return;
    }
    var parsed = acParseArticle(args);
    var d = acLoadDraft();
    d.src = args;
    d.text = parsed.text;
    d.entities = parsed.entities;
    acSaveDraft(d);
    amin.reply(ctx.msg, "✍️ Draft saved — " + parsed.text.length + " chars, "
      + parsed.entities.length + " format span(s), " + d.images.length + " image(s) queued. /apreview or /apost.");
    return;
  }
  if (cmd === "aimg") {
    var dd = acLoadDraft();
    if (args === "clear") {
      dd.images = [];
      acSaveDraft(dd);
      amin.reply(ctx.msg, "🧹 Image queue cleared.");
      return;
    }
    if (args === "list") {
      amin.reply(ctx.msg, dd.images.length ? dd.images.join("\n") : "The image queue is empty.");
      return;
    }
    if (!/^https?:\/\/\S+$/i.test(args)) {
      amin.reply(ctx.msg, "Usage: /aimg <image url> — a direct http(s) link (plugins can only upload by URL).");
      return;
    }
    if (dd.images.length >= AC_MAX_IMAGES) {
      amin.reply(ctx.msg, "The queue already holds " + AC_MAX_IMAGES + " images — Telegram albums cap at 10.");
      return;
    }
    dd.images.push(args);
    acSaveDraft(dd);
    amin.reply(ctx.msg, "🖼 Image " + dd.images.length + "/" + AC_MAX_IMAGES + " queued.");
    return;
  }
  if (cmd === "apreview") {
    if (!acSendPreviewCard()) return;
    amin.reply(ctx.msg, "📰 Preview card is in this DM — tap 📤 Post on it when it looks right.");
    return;
  }
  if (cmd === "apost") { acStartPost(); return; }
  if (cmd === "acancel") {
    acClearDraft();
    acPostJob = null;
    amin.reply(ctx.msg, "🗑 Draft discarded.");
    return;
  }
  if (cmd === "ahelp") { amin.reply(ctx.msg, AC_HELP); return; }
}
