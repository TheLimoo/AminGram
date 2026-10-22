// ==AminGramPlugin==
// @id          aesthetic-text
// @name        Aesthetic Text
// @version     1.1
// @author      Amin
// @description Restyle text: /wide makes ｆｕｌｌｗｉｄｔｈ text, /space spreads l e t t e r s out, /fliptext turns text upside down, /mono makes 𝚖𝚘𝚗𝚘𝚜𝚙𝚊𝚌𝚎 text, /bubble makes ⓑⓤⓑⓑⓛⓔ text, /bold makes 𝐛𝐨𝐥𝐝 text.
// @capabilities commands, send, menu, settings
// @minPlatform 2
// ==/AminGramPlugin==

var PLUGIN_PAGE = [
  { type: "info", text: "Styles: /wide /space /fliptext /mono /bubble /bold. Usage counts live in the plugin database (page actions below)." },
  { type: "action", id: "stats", title: "Style usage", subtitle: "Show how often each style was used" },
  { type: "action", id: "reset-stats", title: "Reset usage counts", subtitle: "Zero the style counters" }
];

var STYLE_NAMES = ["wide", "space", "fliptext", "mono", "bubble", "bold"];

// --- Usage stats: amin.db primary, legacy storage JSON as fallback ---

function dbReady() {
  try {
    return !!(amin.db && typeof amin.db.get === "function" && typeof amin.db.put === "function");
  } catch (e) {
    return false;
  }
}

function legacyStats() {
  try {
    if (typeof amin.storage.getJSON === "function") {
      var v = amin.storage.getJSON("styleStats", null);
      if (v && typeof v === "object") return v;
    }
  } catch (e) { /* ignore */ }
  return {};
}

function styleCount(style) {
  try {
    if (dbReady()) {
      var v = amin.db.get("stats", style);
      if (typeof v === "number") return v;
      // Lazy one-way migration: seed the db from any legacy
      // storage counters, once, without ever losing them.
      var legacy = legacyStats();
      if (typeof legacy[style] === "number") {
        amin.db.put("stats", style, legacy[style]);
        return legacy[style];
      }
      return 0;
    }
    var all = legacyStats();
    return typeof all[style] === "number" ? all[style] : 0;
  } catch (e) {
    return 0;
  }
}

function bumpStyle(style) {
  try {
    var next = styleCount(style) + 1;
    if (dbReady()) {
      amin.db.put("stats", style, next);
    } else if (typeof amin.storage.setJSON === "function") {
      var all = legacyStats();
      all[style] = next;
      amin.storage.setJSON("styleStats", all);
    }
  } catch (e) {
    // Stats must never break the styling itself.
    amin.debug("aesthetic-text stats error: " + e);
  }
}

function resetStats() {
  try {
    if (dbReady()) amin.db.clear("stats");
    if (typeof amin.storage.remove === "function") amin.storage.remove("styleStats");
    else if (typeof amin.storage.setJSON === "function") amin.storage.setJSON("styleStats", {});
  } catch (e) {
    amin.debug("aesthetic-text reset error: " + e);
  }
}

// --- Styles ---

function toWide(text) {
  var out = "";
  for (var i = 0; i < text.length; i++) {
    var code = text.charCodeAt(i);
    if (code === 32) {
      out += "　";
    } else if (code >= 33 && code <= 126) {
      // (space above is U+3000, the fullwidth space)
      out += String.fromCharCode(0xFF01 + (code - 33));
    } else {
      out += text.charAt(i);
    }
  }
  return out;
}

function toSpaced(text) {
  var out = "";
  for (var i = 0; i < text.length; i++) {
    var ch = text.charAt(i);
    if (ch === " ") {
      out += "  ";
    } else {
      if (out.length > 0 && out.charAt(out.length - 1) !== " ") {
        out += " ";
      }
      out += ch;
    }
  }
  return out;
}

var FLIP_MAP = {
  "a": "ɐ", "b": "q", "c": "ɔ", "d": "p", "e": "ǝ", "f": "ɟ", "g": "ƃ",
  "h": "ɥ", "i": "ᴉ", "j": "ɾ", "k": "ʞ", "l": "l", "m": "ɯ", "n": "u",
  "o": "o", "p": "d", "q": "b", "r": "ɹ", "s": "s", "t": "ʇ", "u": "n",
  "v": "ʌ", "w": "ʍ", "x": "x", "y": "ʎ", "z": "z",
  "A": "∀", "B": "ꓭ", "C": "Ɔ", "D": "ᗡ", "E": "Ǝ", "F": "Ⅎ", "G": "⅁",
  "H": "H", "I": "I", "J": "ſ", "K": "ʞ", "L": "˥", "M": "W", "N": "N",
  "O": "O", "P": "Ԁ", "Q": "Ό", "R": "ᴚ", "S": "S", "T": "⊥", "U": "∩",
  "V": "Λ", "W": "M", "X": "X", "Y": "⅄", "Z": "Z",
  "0": "0", "1": "Ɩ", "2": "ᄅ", "3": "Ɛ", "4": "ㄣ", "5": "ϛ", "6": "9",
  "7": "ㄥ", "8": "8", "9": "6",
  ".": "˙", ",": "'", "'": ",", "\"": ",,", "?": "¿", "!": "¡",
  "(": ")", ")": "(", "[": "]", "]": "[", "{": "}", "}": "{",
  "<": ">", ">": "<", "&": "⅋", "_": "‾"
};

function flipText(text) {
  var flipped = "";
  for (var i = 0; i < text.length; i++) {
    var ch = text.charAt(i);
    flipped += (FLIP_MAP[ch] !== undefined) ? FLIP_MAP[ch] : ch;
  }
  var out = "";
  for (var j = flipped.length - 1; j >= 0; j--) {
    out += flipped.charAt(j);
  }
  return out;
}

// Astral-plane code point → UTF-16 surrogate pair string (ES5-safe).
function astral(cp) {
  var v = cp - 0x10000;
  return String.fromCharCode(0xD800 + (v >> 10), 0xDC00 + (v & 0x3FF));
}

// Maps ASCII letters/digits onto a Unicode math alphabet whose
// lowercase block starts at lowerBase, capitals at upperBase and
// digits at digitBase.
function mathMap(text, lowerBase, upperBase, digitBase) {
  var out = "";
  for (var i = 0; i < text.length; i++) {
    var code = text.charCodeAt(i);
    if (code >= 97 && code <= 122) {
      out += astral(lowerBase + (code - 97));
    } else if (code >= 65 && code <= 90) {
      out += astral(upperBase + (code - 65));
    } else if (code >= 48 && code <= 57) {
      out += astral(digitBase + (code - 48));
    } else {
      out += text.charAt(i);
    }
  }
  return out;
}

function toMono(text) {
  // 𝚊 U+1D68A · 𝙰 U+1D670 · 𝟶 U+1D7F6
  return mathMap(text, 0x1D68A, 0x1D670, 0x1D7F6);
}

function toBold(text) {
  // 𝐚 U+1D41A · 𝐀 U+1D400 · 𝟎 U+1D7CE
  return mathMap(text, 0x1D41A, 0x1D400, 0x1D7CE);
}

function toBubble(text) {
  var out = "";
  for (var i = 0; i < text.length; i++) {
    var code = text.charCodeAt(i);
    if (code >= 97 && code <= 122) {
      out += String.fromCharCode(0x24D0 + (code - 97));       // ⓐ-ⓩ
    } else if (code >= 65 && code <= 90) {
      out += String.fromCharCode(0x24B6 + (code - 65));       // Ⓐ-Ⓩ
    } else if (code >= 49 && code <= 57) {
      out += String.fromCharCode(0x2460 + (code - 49));       // ①-⑨
    } else if (code === 48) {
      out += String.fromCharCode(0x24EA);                     // ⓪
    } else {
      out += text.charAt(i);
    }
  }
  return out;
}

function styleText(cmd, text) {
  if (cmd === "wide") return toWide(text);
  if (cmd === "space") return toSpaced(text);
  if (cmd === "fliptext") return flipText(text);
  if (cmd === "mono") return toMono(text);
  if (cmd === "bubble") return toBubble(text);
  if (cmd === "bold") return toBold(text);
  return null;
}

// --- Hooks ---

function onCommand(ctx) {
  try {
    var cmd = String(ctx.command || "").toLowerCase();
    if (STYLE_NAMES.indexOf(cmd) === -1) {
      return;
    }
    if (!ctx.args || !ctx.args.trim()) {
      amin.reply(ctx.msg, "Usage: /" + cmd + " <text>");
      return;
    }
    amin.reply(ctx.msg, styleText(cmd, ctx.args));
    bumpStyle(cmd);
  } catch (e) {
    amin.log("aesthetic-text error: " + e);
  }
}

function onMenuAction(ctx) {
  try {
    amin.send(ctx.chatId, "✨ Aesthetic Text — /wide <text> → ｆｕｌｌｗｉｄｔｈ · "
      + "/space <text> → s p a c e d · /fliptext <text> → upside down · "
      + "/mono <text> → 𝚖𝚘𝚗𝚘 · /bubble <text> → ⓑⓤⓑⓑⓛⓔ · /bold <text> → 𝐛𝐨𝐥𝐝");
  } catch (e) {
    amin.log("aesthetic-text menu error: " + e);
  }
}

function onPluginAction(actionId) {
  try {
    if (actionId === "stats") {
      var parts = [];
      var total = 0;
      for (var i = 0; i < STYLE_NAMES.length; i++) {
        var c = styleCount(STYLE_NAMES[i]);
        total += c;
        parts.push(STYLE_NAMES[i] + ": " + c);
      }
      amin.toast("✨ Style usage (" + total + " total) — " + parts.join(" · "));
    } else if (actionId === "reset-stats") {
      resetStats();
      amin.toast("✨ Style counters reset");
    }
  } catch (e) {
    amin.log("aesthetic-text page error: " + e);
  }
}
