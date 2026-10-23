// ==AminGramPlugin==
// @id          theme-manager
// @name        Theme Changer
// @version     4.2
// @author      Amin
// @description Real-button theme manager: the interactive UI (theme grid, prev/next/random, favorite, close) is rendered by our Telegram bot @awminai with native inline buttons; the built-in themes are delivered by the bot as real Android .attheme files (the old addtheme slugs were other platforms' formats and got refused). This plugin syncs your theme list to the bot and keeps the fast local tricks (1-tap cycle, quick numbers, settings picker) on-device.
// @capabilities observe, commands, send, settings, menu, file, act
// @icon        emoji:🎨
// @menuLabel   Switch Theme
// @minPlatform 2
// @dataVersion 3
// ==/AminGramPlugin==

var SETTINGS = [
  { type: "header", title: "Bot Buttons (real inline UI via @awminai)" },
  {
    key: "useBotButtons",
    type: "bool",
    title: "Use the bot for menus & interactive UI",
    def: true,
    help: "When on, /th and the menu action open a real inline-button card in your @awminai chat. The bot must be deployed with the Theme Changer tool; if it can't be reached, the local text card is used instead."
  },
  {
    key: "botChatId",
    type: "text",
    title: "Bot chat ID (learned automatically)",
    def: "8493619765",
    help: "The @awminai chat. Pre-filled with the bot's ID; the plugin also re-learns it whenever the bot messages you."
  },
  { type: "header", title: "Native Quick Theme Selector" },
  {
    key: "presetPicker",
    type: "select",
    title: "Select Active Theme",
    def: "0",
    options: [
      { value: "0", label: "1. 🌑 Night Dark (Official)" },
      { value: "1", label: "2. 🧛 Dracula" },
      { value: "2", label: "3. ❄️ Nord Frost" },
      { value: "3", label: "4. 🌌 Dark Blue (Official)" },
      { value: "4", label: "5. 🎨 Monokai" },
      { value: "5", label: "6. ⚡ Cyberpunk" },
      { value: "6", label: "7. ☀️ Classic Day (Official)" }
    ],
    help: "Pick a theme here to switch immediately, post its Apply card in your last active chat, and sync the bot card."
  },
  { type: "header", title: "Attach Menu (🎨 Switch Theme) Button" },
  {
    key: "menuActionMode",
    type: "select",
    title: "When tapping 🎨 Switch Theme in chat",
    def: "cycle",
    options: [
      { value: "cycle", label: "⚡ 1-Tap Switch: Cycle to next theme & show Apply button" },
      { value: "menu", label: "📋 Open the bot's interactive Theme Menu (real buttons)" },
      { value: "favorites", label: "⭐ Cycle through Favorite themes only" },
      { value: "random", label: "🎲 Pick a random theme" }
    ],
    help: "Default is 1-Tap Switch so tapping the attach menu button immediately switches themes."
  },
  { type: "header", title: "Interactive Chat Features" },
  {
    key: "quickNumberPick",
    type: "bool",
    title: "Quick number switching in chat (1, 2, 3, n, p, r)",
    def: true,
    help: "After opening /th, just type a theme number (like 2) or 'n' in chat without typing /th!"
  },
  {
    key: "editInPlace",
    type: "bool",
    title: "Update local cards in-place (less chat clutter)",
    def: true,
    help: "Uses editMessage to update the existing local theme card instead of sending new messages."
  },
  {
    key: "sniffLinks",
    type: "bool",
    title: "Auto-detect theme links shared in chat",
    def: true,
    help: "Remembers t.me/addtheme/... and .attheme links so you can save them with /th grab."
  }
];

// Plugin page (v4.2 — ADDITIVE ONLY: reads the same local state and
// re-sends the usual bot sync; storage, the sync payload, commands
// and quick flows are all untouched).
var PLUGIN_PAGE = [
  { type: "info", text: "Theme Changer — the interactive button UI lives in your @awminai chat (/th). These rows only read local state or re-send the usual sync." },
  { type: "action", id: "tm-stats", title: "Theme count & active theme", subtitle: "How many themes you have and which one is active" },
  { type: "action", id: "tm-sync", title: "Sync to bot now", subtitle: "Re-send your theme list to @awminai (the same sync /th sends)" },
  { type: "action", id: "tm-open", title: "Open the bot theme menu", subtitle: "Ask @awminai to post a fresh button card" }
];

function onPluginAction(actionId) {
  try {
    if (actionId === "tm-stats") {
      var themes = getThemes();
      var active = themes[getActiveIndex(themes.length)];
      amin.toast("🎨 " + themes.length + " themes · active: " + (active ? active.name : "—"));
    } else if (actionId === "tm-sync") {
      amin.toast(syncToBot(false)
        ? "🎨 Theme list synced to @awminai"
        : "⚠️ Bot buttons are off or the bot chat is unknown");
    } else if (actionId === "tm-open") {
      if (!openBotMenu()) {
        amin.toast("⚠️ Bot menu unavailable — use /th for the local card");
      }
    }
  } catch (e) {
    amin.error("onPluginAction error: " + e);
  }
}

var STORAGE_KEY_THEMES = "saved_themes_v2";
var STORAGE_KEY_INDEX = "active_theme_index";
var STORAGE_KEY_LAST_CHAT = "last_active_chat_id";
var BOT_SYNC_PREFIX = "🎨THEME ";

// Built-in set: delivered by the bot as Android .attheme FILES ("botfile:<key>").
// The v3/v4.0 addtheme slugs were unusable on modern Android — Night was a
// foreign-format upload ("doesn't support your device"), Nord/Day were dead.
var DEFAULT_THEMES = [
  { name: "Night Dark", url: "botfile:night-dark", fav: true, icon: "🌑" },
  { name: "Dracula", url: "botfile:dracula", fav: true, icon: "🧛" },
  { name: "Nord Frost", url: "botfile:nord-frost", fav: true, icon: "❄️" },
  { name: "Dark Blue", url: "botfile:dark-blue", fav: false, icon: "🌌" },
  { name: "Monokai", url: "botfile:monokai", fav: false, icon: "🎨" },
  { name: "Cyberpunk", url: "botfile:cyberpunk", fav: false, icon: "⚡" },
  { name: "Classic Day", url: "botfile:classic-day", fav: false, icon: "☀️" }
];

// The retired v3/v4.0 default slugs — used by the data migration to swap
// ONLY the built-in entries while preserving user-added themes.
var OLD_DEFAULT_URLS = [
  "https://t.me/addtheme/Night",
  "https://t.me/addtheme/Dracula",
  "https://t.me/addtheme/Nord",
  "https://t.me/addtheme/DarkBlue",
  "https://t.me/addtheme/Monokai",
  "https://t.me/addtheme/Cyberpunk",
  "https://t.me/addtheme/Day"
];

// Session state per chat
var lastMenuMsgIdByChat = {};
var quickPickArmedByChat = {};
var lastSeenThemeByChat = {};

// --- Platform & Storage Helpers ---

function hasJsonStorage() {
  try {
    return !!(amin.platform && typeof amin.platform.has === "function" && amin.platform.has("storage-json"));
  } catch (e) {
    return false;
  }
}

function canEdit() {
  try {
    return amin.storage.get("editInPlace", true) &&
      typeof amin.editMessage === "function" &&
      (!amin.platform || typeof amin.platform.has !== "function" || amin.platform.has("act"));
  } catch (e) {
    return false;
  }
}

function canReact() {
  try {
    return typeof amin.react === "function" &&
      (!amin.platform || typeof amin.platform.has !== "function" || amin.platform.has("act"));
  } catch (e) {
    return false;
  }
}

function getThemes() {
  try {
    var list = null;
    if (hasJsonStorage() && typeof amin.storage.getJSON === "function") {
      list = amin.storage.getJSON(STORAGE_KEY_THEMES, null);
    } else {
      var raw = amin.storage.get(STORAGE_KEY_THEMES, "");
      if (raw) list = JSON.parse(raw);
    }
    if (Array.isArray(list) && list.length > 0) return list;
  } catch (e) {
    amin.warn("Failed reading themes: " + e);
  }
  saveThemes(DEFAULT_THEMES);
  return DEFAULT_THEMES.slice();
}

function saveThemes(list) {
  try {
    if (hasJsonStorage() && typeof amin.storage.setJSON === "function") {
      amin.storage.setJSON(STORAGE_KEY_THEMES, list);
    } else {
      amin.storage.set(STORAGE_KEY_THEMES, JSON.stringify(list));
    }
  } catch (e) {
    amin.error("Failed saving themes: " + e);
  }
}

function getActiveIndex(total) {
  var idx = parseInt(amin.storage.get(STORAGE_KEY_INDEX, 0), 10);
  if (isNaN(idx) || idx < 0 || idx >= total) return 0;
  return idx;
}

function setActiveIndex(idx) {
  amin.storage.set(STORAGE_KEY_INDEX, idx);
  amin.storage.set("presetPicker", String(idx));
}

function rememberChat(chatId) {
  if (chatId) amin.storage.set(STORAGE_KEY_LAST_CHAT, String(chatId));
}

// --- Bot bridge: sync state to @awminai, which renders the real buttons ---

function getBotChatId() {
  var v = parseInt(amin.storage.get("botChatId", "8493619765"), 10);
  return isNaN(v) || v <= 0 ? 0 : v;
}

function botButtonsOn() {
  return amin.storage.get("useBotButtons", true) && getBotChatId() > 0 && typeof amin.send === "function";
}

// UTF-8 → base64url, pure JS (Rhino has no btoa for non-Latin1 text).
var B64C = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
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

function buildSyncPayload(openMenu) {
  var themes = getThemes();
  var slim = [];
  for (var i = 0; i < themes.length; i++) {
    slim.push({ name: themes[i].name, url: themes[i].url, fav: !!themes[i].fav, icon: themes[i].icon || "🎨" });
  }
  return BOT_SYNC_PREFIX + b64urlEncode(JSON.stringify({
    v: 1,
    open: !!openMenu,
    active: getActiveIndex(themes.length),
    themes: slim
  }));
}

// Push the current list+active to the bot. openMenu=true asks the bot to
// post a fresh button card; false silently refreshes the live card.
function syncToBot(openMenu) {
  if (!botButtonsOn()) return false;
  try {
    amin.send(getBotChatId(), buildSyncPayload(openMenu));
    return true;
  } catch (e) {
    amin.warn("Bot sync failed: " + e);
    return false;
  }
}

// Open the interactive UI in the bot chat. Returns true when handed off.
function openBotMenu() {
  if (!botButtonsOn()) return false;
  if (syncToBot(true)) {
    amin.toast("🎨 Buttons are in @awminai — open that chat 👆");
    return true;
  }
  return false;
}

// --- Lifecycle Hooks ---

function onLoad() {
  getThemes();
}

function onEnable() {
  amin.toast("🎨 Theme Changer v4.0 ready! /th opens real buttons in @awminai.");
}

function isOldDefault(url) {
  for (var i = 0; i < OLD_DEFAULT_URLS.length; i++) {
    if (url === OLD_DEFAULT_URLS[i]) return true;
  }
  return false;
}

function onMigrate(fromVersion, toVersion) {
  if (fromVersion < 3) {
    // v3 data: swap the built-in slug entries for the bot-file defaults,
    // preserving every theme the user added themselves.
    var existing = [];
    try {
      var raw = null;
      if (hasJsonStorage() && typeof amin.storage.getJSON === "function") {
        raw = amin.storage.getJSON(STORAGE_KEY_THEMES, null);
      } else {
        var s = amin.storage.get(STORAGE_KEY_THEMES, "");
        if (s) raw = JSON.parse(s);
      }
      if (Array.isArray(raw)) existing = raw;
    } catch (e) { /* fall through with an empty list */ }
    var customs = [];
    for (var i = 0; i < existing.length; i++) {
      if (existing[i] && !isOldDefault(existing[i].url) &&
          String(existing[i].url || "").indexOf("botfile:") !== 0) {
        customs.push(existing[i]);
      }
    }
    saveThemes(DEFAULT_THEMES.concat(customs));
    setActiveIndex(0);
    amin.info("Migrated theme list from v" + fromVersion + " to v" + toVersion + " (defaults swapped, " + customs.length + " custom kept)");
  }
}

function onSettingsChanged(key, newValue, oldValue) {
  try {
    if (key === "presetPicker") {
      var themes = getThemes();
      var idx = parseInt(newValue, 10);
      if (!isNaN(idx) && idx >= 0 && idx < themes.length) {
        amin.storage.set(STORAGE_KEY_INDEX, idx);
        var chosen = themes[idx];
        amin.toast("🎨 Selected: " + chosen.name);
        var lastChat = parseInt(amin.storage.get(STORAGE_KEY_LAST_CHAT, "0"), 10);
        if (lastChat && lastChat !== getBotChatId()) {
          deliverApplyCard(lastChat, null, chosen, idx, themes.length, "🎨 Switched from Settings");
        }
        syncToBot(false);
      }
    }
  } catch (e) {
    amin.warn("onSettingsChanged error: " + e);
  }
}

// --- Message Observer (Captures Menu ID + Quick Number Input + Link Sniffer) ---

function extractThemeUrl(text) {
  if (!text) return null;
  var m1 = text.match(/https?:\/\/(?:t\.me|telegram\.me)\/addtheme\/([a-zA-Z0-9_]+)/i);
  if (m1) {
    return { url: "https://t.me/addtheme/" + m1[1], slug: m1[1], isFile: false };
  }
  var m2 = text.match(/https?:\/\/[^\s]+\.(?:attheme|tgtheme)(?:\?[^\s]*)?/i);
  if (m2) {
    var name = m2[0].split("/").pop().split("?")[0].replace(/\.(attheme|tgtheme)$/i, "");
    return { url: m2[0], slug: name || "CustomTheme", isFile: true };
  }
  return null;
}

function onMessage(msg) {
  try {
    if (!msg) return;

    // Learn the bot chat whenever the bot itself messages us.
    if (!msg.outgoing && msg.senderUsername &&
        String(msg.senderUsername).toLowerCase() === "awminai") {
      if (msg.chatId && String(msg.chatId) !== String(amin.storage.get("botChatId", ""))) {
        amin.storage.set("botChatId", String(msg.chatId));
      }
    }
    // Inside the bot chat the BOT owns the UI — never quick-pick there.
    if (msg.chatId && msg.chatId === getBotChatId()) return;

    if (!msg.text) return;
    rememberChat(msg.chatId);

    // 1. Track our own posted Theme Changer cards so we can edit them in-place!
    if (msg.outgoing) {
      if (msg.text.indexOf("🎨 ━━ THEME CHANGER") !== -1 ||
          msg.text.indexOf("👇 Tap \"VIEW THEME\" below") !== -1) {
        lastMenuMsgIdByChat[msg.chatId] = msg.id;
        quickPickArmedByChat[msg.chatId] = true;
        return;
      }

      // 2. Quick-Number / Single-Key Switching in chat (when armed & enabled)
      if (amin.storage.get("quickNumberPick", true) && quickPickArmedByChat[msg.chatId]) {
        var trimmed = msg.text.trim().toLowerCase();
        var themes = getThemes();

        // Check if user typed a number like "1", "2", "3"
        if (/^\d+$/.test(trimmed)) {
          var num = parseInt(trimmed, 10) - 1;
          if (num >= 0 && num < themes.length) {
            setActiveIndex(num);
            if (canReact()) amin.react(msg, "🎨");
            // Edit the user's "2" message directly into the theme card if possible!
            if (canEdit() && amin.editMessage(msg, buildSingleThemeCard(themes[num], num, themes.length, "🎨 Quick-Switched Theme"))) {
              lastMenuMsgIdByChat[msg.chatId] = msg.id;
              amin.toast("🎨 " + themes[num].name);
            } else {
              deliverApplyCard(msg.chatId, msg, themes[num], num, themes.length, "🎨 Quick-Switched Theme");
            }
            syncToBot(false);
            return;
          }
        }

        // Single-letter shortcuts: "n" (next), "p" (prev), "r" (random), "m" (menu)
        if (trimmed === "n" || trimmed === "next") {
          if (canReact()) amin.react(msg, "⚡");
          cycleTheme(msg.chatId, msg, 1, false, true);
          return;
        }
        if (trimmed === "p" || trimmed === "prev") {
          if (canReact()) amin.react(msg, "⚡");
          cycleTheme(msg.chatId, msg, -1, false, true);
          return;
        }
        if (trimmed === "r" || trimmed === "rand") {
          if (canReact()) amin.react(msg, "🎲");
          applyRandomTheme(msg.chatId, msg, true);
          return;
        }
        if (trimmed === "m" || trimmed === "menu") {
          if (canReact()) amin.react(msg, "📋");
          if (!openBotMenu()) postInteractiveMenu(msg.chatId, msg, true);
          return;
        }
      }
    }

    // 3. Sniff theme links shared in chat
    if (amin.storage.get("sniffLinks", true)) {
      if (msg.text.indexOf("addtheme/") !== -1 ||
          msg.text.indexOf(".attheme") !== -1 ||
          msg.text.indexOf(".tgtheme") !== -1) {
        var found = extractThemeUrl(msg.text);
        if (found) {
          lastSeenThemeByChat[msg.chatId] = found;
        }
      }
    }
  } catch (e) {
    amin.error("onMessage error: " + e);
  }
}

// --- Attach Menu Button Action ---

function onMenuAction(ctx) {
  try {
    if (!ctx || !ctx.chatId) return;
    rememberChat(ctx.chatId);
    var mode = String(amin.storage.get("menuActionMode", "cycle") || "cycle").trim();

    if (mode === "menu") {
      if (!openBotMenu()) postInteractiveMenu(ctx.chatId, null, false);
    } else if (mode === "favorites") {
      cycleTheme(ctx.chatId, null, 1, true, false);
    } else if (mode === "random") {
      applyRandomTheme(ctx.chatId, null, false);
    } else {
      // Default: 1-tap cycle and show the native View Theme button card!
      cycleTheme(ctx.chatId, null, 1, false, false);
    }
  } catch (e) {
    amin.error("onMenuAction error: " + e);
  }
}

// --- Commands (/th, /theme) ---

function onCommand(ctx) {
  try {
    if (!ctx || !ctx.command) return;
    var cmd = ctx.command.toLowerCase();
    if (cmd !== "th" && cmd !== "theme") return;

    rememberChat(ctx.chatId);
    var rawArgs = String(ctx.args || "").trim();

    if (!rawArgs) {
      // The menu is a bot surface now — real buttons beat text cards.
      if (!openBotMenu()) postInteractiveMenu(ctx.chatId, ctx.msg, false);
      return;
    }

    var parts = rawArgs.split(/\s+/);
    var sub = parts[0].toLowerCase();
    var rest = rawArgs.substring(parts[0].length).trim();

    if (sub === "menu" || sub === "list" || sub === "m") {
      if (!openBotMenu()) postInteractiveMenu(ctx.chatId, ctx.msg, false);
    } else if (sub === "bot") {
      if (!openBotMenu()) {
        amin.reply(ctx.msg, "⚠️ Bot buttons are off or the bot chat is unknown. Enable them in the plugin settings, open @awminai once, then try /th again.");
      }
    } else if (sub === "local") {
      postInteractiveMenu(ctx.chatId, ctx.msg, false);
    } else if (sub === "next" || sub === "n") {
      cycleTheme(ctx.chatId, ctx.msg, 1, false, false);
    } else if (sub === "prev" || sub === "p") {
      cycleTheme(ctx.chatId, ctx.msg, -1, false, false);
    } else if (sub === "favnext" || sub === "fn") {
      cycleTheme(ctx.chatId, ctx.msg, 1, true, false);
    } else if (sub === "rand" || sub === "random" || sub === "r") {
      applyRandomTheme(ctx.chatId, ctx.msg, false);
    } else if (sub === "add" || sub === "new") {
      handleAddTheme(ctx, rest);
    } else if (sub === "grab" || sub === "save") {
      handleGrabTheme(ctx, rest);
    } else if (sub === "fav" || sub === "star") {
      handleToggleFav(ctx, rest);
    } else if (sub === "del" || sub === "rm") {
      handleDeleteTheme(ctx, rest);
    } else if (sub === "reset") {
      saveThemes(DEFAULT_THEMES);
      setActiveIndex(0);
      amin.toast("🎨 Reset to verified themes!");
      syncToBot(false);
      postInteractiveMenu(ctx.chatId, ctx.msg, false);
    } else {
      handleSelectByQuery(ctx.chatId, ctx.msg, rawArgs);
    }
  } catch (e) {
    amin.error("onCommand error: " + e);
  }
}

// --- Card Builders & Delivery (Designed so Telegram renders the native "VIEW THEME" button!) ---

function isBotFile(theme) {
  return theme && typeof theme.url === "string" && theme.url.indexOf("botfile:") === 0;
}

function buildSingleThemeCard(theme, idx, total, header) {
  var icon = theme.icon || "🎨";
  var star = theme.fav ? " ⭐" : "";
  var lines = [
    "🎨 ━━ THEME CHANGER [" + (idx + 1) + "/" + total + "] ━━ 🎨",
    "",
    icon + " Selected: " + theme.name + star
  ];
  if (isBotFile(theme)) {
    lines.push("📎 This theme is a file — @awminai has it under the button card. Open that chat and tap the file to preview & apply.");
  } else {
    lines.push("👇 Tap \"VIEW THEME\" below to preview & apply:");
    lines.push(theme.url);
  }
  lines.push("");
  lines.push("💡 Tip: Send 1–" + total + " or 'n' (next) / 'm' (menu) right in chat — or /th for real buttons in @awminai!");
  return lines.join("\n");
}

function buildInteractiveMenuCard(themes, activeIdx) {
  var active = themes[activeIdx] || themes[0];
  var lines = [];
  lines.push("🎨 ━━ THEME CHANGER MENU ━━ 🎨");
  lines.push("Just send a number (1–" + themes.length + ") or 'n' in chat to switch!");
  lines.push("");

  // IMPORTANT: We DO NOT print raw URLs on inactive rows!
  // Keeping ONLY ONE valid URL in the message forces Telegram to attach
  // its interactive "VIEW THEME" button preview card at the bottom!
  for (var i = 0; i < themes.length; i++) {
    var t = themes[i];
    var icon = t.icon || "🎨";
    var star = t.fav ? " ⭐" : "";
    if (i === activeIdx) {
      lines.push("👉 [" + (i + 1) + "] " + icon + " " + t.name + star + "  ◀ ACTIVE");
    } else {
      lines.push("     [" + (i + 1) + "] " + icon + " " + t.name + star);
    }
  }

  lines.push("");
  if (isBotFile(active)) {
    lines.push("📎 " + active.name + " is a theme file — delivered by @awminai under its button card.");
  } else {
    lines.push("👇 Tap \"VIEW THEME\" on the preview box below to apply " + active.name + ":");
    lines.push(active.url);
  }
  lines.push("");
  lines.push("⚡ Quick keys: send 1–" + themes.length + " | n (next) | r (random) | /th add <name> <url>");
  return lines.join("\n");
}

function postInteractiveMenu(chatId, triggerMsg, editTriggerMsg) {
  var themes = getThemes();
  var activeIdx = getActiveIndex(themes.length);
  var text = buildInteractiveMenuCard(themes, activeIdx);
  quickPickArmedByChat[chatId] = true;

  // 1. If triggered by a quick-key message from the user, edit that message in-place
  if (editTriggerMsg && triggerMsg && canEdit()) {
    if (amin.editMessage(triggerMsg, text)) {
      lastMenuMsgIdByChat[chatId] = triggerMsg.id;
      return;
    }
  }

  // 2. Or if we already have a recent menu card in this chat, update it in-place
  if (canEdit() && lastMenuMsgIdByChat[chatId]) {
    if (amin.editMessage({ chatId: chatId, id: lastMenuMsgIdByChat[chatId] }, text)) {
      amin.toast("🎨 Menu updated!");
      return;
    }
  }

  // 3. Otherwise post a fresh card
  if (triggerMsg && triggerMsg.id) {
    amin.reply(triggerMsg, text);
  } else {
    amin.send(chatId, text);
  }
}

function deliverApplyCard(chatId, triggerMsg, theme, idx, total, headerLabel, editTriggerMsg) {
  quickPickArmedByChat[chatId] = true;
  amin.toast("🎨 " + theme.name + " (" + (idx + 1) + "/" + total + ")");

  // Bot-file themes are delivered by @awminai as .attheme documents —
  // make sure its card + file are fresh, then post the local pointer card.
  if (isBotFile(theme)) {
    syncToBot(true);
  }

  // If this theme is a direct .attheme / .tgtheme file URL, send it as a real installable file bubble
  if (/\.(attheme|tgtheme)(?:\?.*)?$/i.test(theme.url) && typeof amin.sendFile === "function") {
    try {
      var sent = amin.sendFile(theme.url, {
        type: "file",
        caption: "🎨 " + theme.name + " — Tap this file to preview & apply the theme!",
        chatId: chatId
      });
      if (sent === true) return;
    } catch (e) {
      amin.warn("sendFile error, falling back to card: " + e);
    }
  }

  var cardText = buildSingleThemeCard(theme, idx, total, headerLabel);

  // Edit user's quick-key message or existing menu card in-place if enabled
  if (editTriggerMsg && triggerMsg && canEdit()) {
    if (amin.editMessage(triggerMsg, cardText)) {
      lastMenuMsgIdByChat[chatId] = triggerMsg.id;
      return;
    }
  }

  if (canEdit() && lastMenuMsgIdByChat[chatId]) {
    if (amin.editMessage({ chatId: chatId, id: lastMenuMsgIdByChat[chatId] }, cardText)) {
      return;
    }
  }

  if (triggerMsg && triggerMsg.id) {
    amin.reply(triggerMsg, cardText);
  } else {
    amin.send(chatId, cardText);
  }
}

function cycleTheme(chatId, triggerMsg, step, favOnly, editTriggerMsg) {
  var themes = getThemes();
  if (!themes.length) return;

  var cur = getActiveIndex(themes.length);
  var next = cur;
  for (var i = 0; i < themes.length; i++) {
    next = (next + step + themes.length) % themes.length;
    if (!favOnly || themes[next].fav) break;
  }

  setActiveIndex(next);
  deliverApplyCard(chatId, triggerMsg, themes[next], next, themes.length, "🔄 Switched Theme", editTriggerMsg);
  syncToBot(false);
}

function applyRandomTheme(chatId, triggerMsg, editTriggerMsg) {
  var themes = getThemes();
  if (!themes.length) return;
  var rnd = Math.floor(Math.random() * themes.length);
  setActiveIndex(rnd);
  deliverApplyCard(chatId, triggerMsg, themes[rnd], rnd, themes.length, "🎲 Random Theme", editTriggerMsg);
  syncToBot(false);
}

function findThemeIndex(themes, query) {
  var q = String(query || "").trim();
  if (!q) return -1;
  if (/^\d+$/.test(q)) {
    var n = parseInt(q, 10) - 1;
    if (n >= 0 && n < themes.length) return n;
  }
  var low = q.toLowerCase();
  for (var i = 0; i < themes.length; i++) {
    if (themes[i].name.toLowerCase().indexOf(low) !== -1) return i;
  }
  return -1;
}

function handleSelectByQuery(chatId, triggerMsg, query) {
  var themes = getThemes();
  var idx = findThemeIndex(themes, query);
  if (idx === -1) {
    amin.reply(triggerMsg, "⚠️ Theme \"" + query + "\" not found. Type /th to see the numbered list.");
    return;
  }
  setActiveIndex(idx);
  deliverApplyCard(chatId, triggerMsg, themes[idx], idx, themes.length, "🎨 Theme Selected", false);
  syncToBot(false);
}

function handleAddTheme(ctx, rest) {
  var replyText = (ctx.replyTo && (ctx.replyTo.text || ctx.replyTo.caption)) ? (ctx.replyTo.text || ctx.replyTo.caption) : "";
  var found = extractThemeUrl(rest) || extractThemeUrl(replyText);
  if (!found) {
    amin.reply(ctx.msg, "Usage: /th add <Name> <https://t.me/addtheme/... or .attheme URL>");
    return;
  }
  var name = rest.replace(found.url, "").replace(/https?:\/\/[^\s]+/i, "").trim() || found.slug;
  var themes = getThemes();
  themes.push({ name: name, url: found.url, fav: true, icon: "✨" });
  saveThemes(themes);
  setActiveIndex(themes.length - 1);
  deliverApplyCard(ctx.chatId, ctx.msg, themes[themes.length - 1], themes.length - 1, themes.length, "✅ Added & Selected", false);
  syncToBot(false);
}

function handleGrabTheme(ctx, customName) {
  var replyText = (ctx.replyTo && (ctx.replyTo.text || ctx.replyTo.caption)) ? (ctx.replyTo.text || ctx.replyTo.caption) : "";
  var found = extractThemeUrl(replyText) || lastSeenThemeByChat[ctx.chatId];
  if (!found) {
    amin.reply(ctx.msg, "⚠️ Reply to a message containing a t.me/addtheme link or .attheme URL with /th grab.");
    return;
  }
  var name = String(customName || "").trim() || found.slug;
  var themes = getThemes();
  themes.push({ name: name, url: found.url, fav: true, icon: "📥" });
  saveThemes(themes);
  setActiveIndex(themes.length - 1);
  deliverApplyCard(ctx.chatId, ctx.msg, themes[themes.length - 1], themes.length - 1, themes.length, "✅ Grabbed Theme", false);
  syncToBot(false);
}

function handleToggleFav(ctx, query) {
  var themes = getThemes();
  var idx = query ? findThemeIndex(themes, query) : getActiveIndex(themes.length);
  if (idx === -1 || !themes[idx]) {
    amin.reply(ctx.msg, "Usage: /th fav <number>");
    return;
  }
  themes[idx].fav = !themes[idx].fav;
  saveThemes(themes);
  amin.toast((themes[idx].fav ? "⭐ Favorited " : "☆ Unfavorited ") + themes[idx].name);
  syncToBot(false);
  postInteractiveMenu(ctx.chatId, ctx.msg, false);
}

function handleDeleteTheme(ctx, query) {
  var themes = getThemes();
  var idx = findThemeIndex(themes, query);
  if (idx === -1) {
    amin.reply(ctx.msg, "Usage: /th del <number>");
    return;
  }
  var removed = themes.splice(idx, 1)[0];
  saveThemes(themes);
  setActiveIndex(0);
  amin.toast("🗑️ Deleted " + removed.name);
  syncToBot(false);
  postInteractiveMenu(ctx.chatId, ctx.msg, false);
}
