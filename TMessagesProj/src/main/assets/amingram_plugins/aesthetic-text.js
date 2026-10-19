// ==AminGramPlugin==
// @id          aesthetic-text
// @name        Aesthetic Text
// @version     1.0
// @author      Amin
// @description Restyle text: /wide makes ｆｕｌｌｗｉｄｔｈ text, /space spreads l e t t e r s out, /fliptext turns text upside down.
// @capabilities commands, send, menu
// ==/AminGramPlugin==

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

function onCommand(ctx) {
  try {
    var cmd = String(ctx.command || "").toLowerCase();
    if (cmd !== "wide" && cmd !== "space" && cmd !== "fliptext") {
      return;
    }
    if (!ctx.args || !ctx.args.trim()) {
      amin.reply(ctx.msg, "Usage: /" + cmd + " <text>");
      return;
    }
    var out = cmd === "wide" ? toWide(ctx.args)
      : cmd === "space" ? toSpaced(ctx.args)
      : flipText(ctx.args);
    amin.reply(ctx.msg, out);
  } catch (e) {
    amin.log("aesthetic-text error: " + e);
  }
}

function onMenuAction(ctx) {
  try {
    amin.send(ctx.chatId, "✨ Aesthetic Text — /wide <text> → ｆｕｌｌｗｉｄｔｈ · "
      + "/space <text> → s p a c e d · /fliptext <text> → upside down");
  } catch (e) {
    amin.log("aesthetic-text menu error: " + e);
  }
}
