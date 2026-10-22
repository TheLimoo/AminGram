// ==AminGramPlugin==
// @id          price-check
// @name        Price Check
// @version     1.1
// @author      Amin
// @description /price shows live Iran-market prices in tomans (dollar, gold 18k, Emami coin, BTC, ETH). /price btc|eth|usd|gold|coin shows just one. Sources: tgju.org + CoinGecko — the same endpoints as the toolbox bot.
// @icon        bolt
// @capabilities commands, send, http, settings
// @minPlatform 2
// ==/AminGramPlugin==

var PLUGIN_PAGE = [
  { type: "info", text: "Prices are fetched live on every /price — nothing is cached. Statistics count your lookups only." },
  { type: "action", id: "refresh-hint", title: "How to refresh", subtitle: "Prices are always live — here is the shortcut" },
  { type: "action", id: "stats", title: "Show statistics", subtitle: "Lookups, successes and failures" },
  { type: "action", id: "clear-stats", title: "Clear statistics", subtitle: "Zero the lookup counters" }
];

// Endpoints reused verbatim from the proven toolbox bot
// (~/workspace/awminai tgcloud/lib/tools/prices.js):
// - tgju summary table: https://api.tgju.org/v1/market/indicator/summary-table-data/<slug>
//   slugs price_dollar_rl (USD), geram18 (gold 18k gram), sekee (Emami coin);
//   the price is data[0][0], a comma-grouped string in RIALS (÷10 = tomans).
// - CoinGecko simple price for BTC/ETH in USD; tomans are derived
//   with the tgju dollar rate, exactly like the bot.
var TGJU = "https://api.tgju.org/v1/market/indicator/summary-table-data/";
var COINGECKO = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum&vs_currencies=usd";

var statsCache = null;

function dbAvailable() {
  try {
    return !!(amin.db && typeof amin.db.get === "function"
      && typeof amin.db.put === "function");
  } catch (e) { return false; }
}

function loadJsonStorage(key) {
  try {
    if (amin.storage && typeof amin.storage.getJSON === "function") {
      var v = amin.storage.getJSON(key, null);
      if (v && typeof v === "object") return v;
    }
  } catch (e) { /* fall through to raw parse */ }
  try {
    var raw = amin.storage.get(key, "");
    if (raw) {
      var parsed = JSON.parse(String(raw));
      if (parsed && typeof parsed === "object") return parsed;
    }
  } catch (e) { /* no legacy value */ }
  return null;
}

function saveJsonStorage(key, value) {
  try {
    if (amin.storage && typeof amin.storage.setJSON === "function") {
      amin.storage.setJSON(key, value);
      return;
    }
  } catch (e) { /* fall through */ }
  try { amin.storage.set(key, JSON.stringify(value)); } catch (e) {}
}

function nonNeg(n) {
  n = Number(n);
  return isFinite(n) && n > 0 ? Math.floor(n) : 0;
}

function normalizeStats(v) {
  v = (v && typeof v === "object") ? v : {};
  return {
    requests: nonNeg(v.requests), successes: nonNeg(v.successes),
    failures: nonNeg(v.failures), lastAt: nonNeg(v.lastAt)
  };
}

// Stats live in amin.db ("state"/"stats"); a legacy amin.storage
// copy is read once and migrated when the db has nothing yet.
function loadStats() {
  if (statsCache) return statsCache;
  if (dbAvailable()) {
    try {
      var v = amin.db.get("state", "stats");
      if (v && typeof v === "object") { statsCache = normalizeStats(v); return statsCache; }
    } catch (e) { amin.log("price-check stats read error: " + e); }
  }
  var legacy = loadJsonStorage("stats");
  if (legacy) {
    statsCache = normalizeStats(legacy);
    if (dbAvailable()) { try { amin.db.put("state", "stats", statsCache); } catch (e) {} }
    return statsCache;
  }
  statsCache = normalizeStats(null);
  return statsCache;
}

function saveStats() {
  if (!statsCache) return;
  var ok = false;
  if (dbAvailable()) { try { ok = amin.db.put("state", "stats", statsCache) === true; } catch (e) {} }
  if (!ok) saveJsonStorage("stats", statsCache);
}

function recordResult(success) {
  var stats = loadStats();
  stats.requests++;
  if (success) stats.successes++; else stats.failures++;
  stats.lastAt = new Date().getTime();
  saveStats();
}

// Thousands-grouped integer, same formatting as the bot's fmtInt.
function fmt(n) {
  var v = Math.round(Number(n));
  if (!isFinite(v)) return "—";
  return String(v).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

// Async JSON GET: done(parsedOrNull). Async only — the sync form
// would stall every plugin behind a slow price source.
function fetchJsonAsync(url, done) {
  var finished = false;
  function finish(value) {
    if (finished) return;
    finished = true;
    done(value);
  }
  try {
    if (typeof amin.httpGetAsync !== "function") { finish(null); return; }
    amin.httpGetAsync(url, null, function (res) {
      try {
        if (!res || !res.body) { finish(null); return; }
        finish(JSON.parse(String(res.body)));
      } catch (e) {
        amin.log("price-check fetch parse error: " + e);
        finish(null);
      }
    });
  } catch (e) {
    amin.log("price-check fetch error: " + e);
    finish(null);
  }
}

// One tgju slug → price in tomans via done(valueOrNull); null on
// ANY failure (network, bad JSON, unexpected shape).
function tgjuTomanAsync(slug, done) {
  fetchJsonAsync(TGJU + slug, function (j) {
    try {
      var first = j && j.data && j.data[0] && j.data[0][0];
      var rial = parseFloat(String(first == null ? "" : first).replace(/,/g, ""));
      done(isFinite(rial) ? rial / 10 : null);
    } catch (e) {
      amin.log("price-check tgju " + slug + " error: " + e);
      done(null);
    }
  });
}

// CoinGecko → done({ btc: usd|null, eth: usd|null }).
function cryptoUsdAsync(done) {
  fetchJsonAsync(COINGECKO, function (j) {
    var out = { btc: null, eth: null };
    try {
      if (j && j.bitcoin && isFinite(Number(j.bitcoin.usd))) out.btc = Number(j.bitcoin.usd);
      if (j && j.ethereum && isFinite(Number(j.ethereum.usd))) out.eth = Number(j.ethereum.usd);
    } catch (e) {
      amin.log("price-check coingecko error: " + e);
    }
    done(out);
  });
}

function usdLine(label, usdValue, tomanRate) {
  if (usdValue == null) return label + ": unavailable";
  var line = label + ": $" + fmt(usdValue);
  if (tomanRate) line += " (≈ " + fmt(usdValue * tomanRate) + " تومان)";
  return line;
}

function tomanLine(label, tomanValue) {
  return label + ": " + (tomanValue ? fmt(tomanValue) + " تومان" : "unavailable");
}

function replyFiat(ctx, label, slug) {
  tgjuTomanAsync(slug, function (value) {
    try {
      recordResult(value != null);
      amin.reply(ctx.msg, "💱 " + tomanLine(label, value));
    } catch (e) { amin.log("price-check reply error: " + e); }
  });
}

function replyCrypto(ctx, which) {
  var state = { pending: 2, rate: null, crypto: { btc: null, eth: null } };
  function step() {
    state.pending--;
    if (state.pending > 0) return;
    try {
      var value = which === "btc" ? state.crypto.btc : state.crypto.eth;
      recordResult(value != null);
      amin.reply(ctx.msg, "💱 " + usdLine(which === "btc" ? "₿ Bitcoin" : "Ξ Ethereum", value, state.rate));
    } catch (e) { amin.log("price-check reply error: " + e); }
  }
  tgjuTomanAsync("price_dollar_rl", function (rate) { state.rate = rate; step(); });
  cryptoUsdAsync(function (crypto) { state.crypto = crypto; step(); });
}

function replyBoard(ctx) {
  var state = { pending: 4, usdT: null, goldT: null, coinT: null, crypto: { btc: null, eth: null } };
  function step() {
    state.pending--;
    if (state.pending > 0) return;
    try {
      var any = state.usdT != null || state.goldT != null || state.coinT != null
        || state.crypto.btc != null || state.crypto.eth != null;
      recordResult(any);
      var lines = ["💱 Live prices — Iran market, in tomans", ""];
      lines.push(tomanLine("🇺🇸 Dollar", state.usdT));
      lines.push(tomanLine("🥇 Gold 18k (per gram)", state.goldT));
      lines.push(tomanLine("🪙 Emami coin", state.coinT));
      lines.push(usdLine("₿ Bitcoin", state.crypto.btc, state.usdT));
      lines.push(usdLine("Ξ Ethereum", state.crypto.eth, state.usdT));
      lines.push("");
      lines.push("Sources: tgju.org, CoinGecko");
      amin.reply(ctx.msg, lines.join("\n"));
    } catch (e) { amin.log("price-check reply error: " + e); }
  }
  // Every item is fetched independently, in parallel, so one dead
  // source only blanks its own line.
  tgjuTomanAsync("price_dollar_rl", function (v) { state.usdT = v; step(); });
  tgjuTomanAsync("geram18", function (v) { state.goldT = v; step(); });
  tgjuTomanAsync("sekee", function (v) { state.coinT = v; step(); });
  cryptoUsdAsync(function (crypto) { state.crypto = crypto; step(); });
}

function onCommand(ctx) {
  try {
    if (String(ctx.command || "").toLowerCase() !== "price") return;
    var what = (ctx.args || "").trim().toLowerCase();

    if (what === "help") {
      amin.reply(ctx.msg, "Usage: /price — full live board · /price btc|eth|usd|gold|coin — just one price.");
      return;
    }
    if (what === "usd" || what === "dollar") { replyFiat(ctx, "🇺🇸 Dollar", "price_dollar_rl"); return; }
    if (what === "gold") { replyFiat(ctx, "🥇 Gold 18k (per gram)", "geram18"); return; }
    if (what === "coin") { replyFiat(ctx, "🪙 Emami coin", "sekee"); return; }
    if (what === "btc" || what === "bitcoin") { replyCrypto(ctx, "btc"); return; }
    if (what === "eth" || what === "ethereum") { replyCrypto(ctx, "eth"); return; }
    if (what !== "" && what !== "all") {
      amin.reply(ctx.msg, "Usage: /price — or /price btc|eth|usd|gold|coin");
      return;
    }
    replyBoard(ctx);
  } catch (e) {
    amin.log("price-check onCommand error: " + e);
    try { amin.reply(ctx.msg, "⚠️ Price check failed — see the plugin log."); } catch (e2) {}
  }
}

function onPluginAction(actionId) {
  try {
    if (actionId === "refresh-hint") {
      amin.toast("Prices are fetched live every time — run /price in any chat for a fresh board");
      return;
    }
    if (actionId === "stats") {
      var stats = loadStats();
      amin.toast("💱 " + stats.requests + " lookup(s) · ✅ " + stats.successes + " · ❌ " + stats.failures);
      return;
    }
    if (actionId === "clear-stats") {
      statsCache = normalizeStats(null);
      saveStats();
      amin.toast("🧹 Price statistics cleared");
    }
  } catch (e) { amin.log("price-check onPluginAction error: " + e); }
}
