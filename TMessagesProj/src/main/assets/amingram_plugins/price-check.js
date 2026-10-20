// ==AminGramPlugin==
// @id          price-check
// @name        Price Check
// @version     1.0
// @author      Amin
// @description /price shows live Iran-market prices in tomans (dollar, gold 18k, Emami coin, BTC, ETH). /price btc|eth|usd|gold|coin shows just one. Sources: tgju.org + CoinGecko — the same endpoints as the toolbox bot.
// @icon        bolt
// @capabilities commands, send, http
// ==/AminGramPlugin==

// Endpoints reused verbatim from the proven toolbox bot
// (~/workspace/awminai tgcloud/lib/tools/prices.js):
// - tgju summary table: https://api.tgju.org/v1/market/indicator/summary-table-data/<slug>
//   slugs price_dollar_rl (USD), geram18 (gold 18k gram), sekee (Emami coin);
//   the price is data[0][0], a comma-grouped string in RIALS (÷10 = tomans).
// - CoinGecko simple price for BTC/ETH in USD; tomans are derived
//   with the tgju dollar rate, exactly like the bot.
var TGJU = "https://api.tgju.org/v1/market/indicator/summary-table-data/";
var COINGECKO = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum&vs_currencies=usd";

// Thousands-grouped integer, same formatting as the bot's fmtInt.
function fmt(n) {
  var v = Math.round(Number(n));
  if (!isFinite(v)) return "—";
  return String(v).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

// One tgju slug → price in tomans, or null on ANY failure
// (network, bad JSON, unexpected shape) — callers degrade per item.
function tgjuToman(slug) {
  try {
    var raw = amin.httpGet(TGJU + slug);
    if (!raw) return null;
    var j = JSON.parse(raw);
    var first = j && j.data && j.data[0] && j.data[0][0];
    var rial = parseFloat(String(first == null ? "" : first).replace(/,/g, ""));
    if (!isFinite(rial)) return null;
    return rial / 10;
  } catch (e) {
    amin.log("price-check tgju " + slug + " error: " + e);
    return null;
  }
}

// CoinGecko → { btc: usd|null, eth: usd|null }, nulls on failure.
function cryptoUsd() {
  var out = { btc: null, eth: null };
  try {
    var raw = amin.httpGet(COINGECKO);
    if (!raw) return out;
    var j = JSON.parse(raw);
    if (j && j.bitcoin && isFinite(Number(j.bitcoin.usd))) out.btc = Number(j.bitcoin.usd);
    if (j && j.ethereum && isFinite(Number(j.ethereum.usd))) out.eth = Number(j.ethereum.usd);
  } catch (e) {
    amin.log("price-check coingecko error: " + e);
  }
  return out;
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

function onCommand(ctx) {
  try {
    if (String(ctx.command || "").toLowerCase() !== "price") return;
    var what = (ctx.args || "").trim().toLowerCase();

    if (what === "usd" || what === "dollar") {
      amin.reply(ctx.msg, "💱 " + tomanLine("🇺🇸 Dollar", tgjuToman("price_dollar_rl")));
      return;
    }
    if (what === "gold") {
      amin.reply(ctx.msg, "💱 " + tomanLine("🥇 Gold 18k (per gram)", tgjuToman("geram18")));
      return;
    }
    if (what === "coin") {
      amin.reply(ctx.msg, "💱 " + tomanLine("🪙 Emami coin", tgjuToman("sekee")));
      return;
    }
    if (what === "btc" || what === "bitcoin" || what === "eth" || what === "ethereum") {
      var rate = tgjuToman("price_dollar_rl");
      var crypto = cryptoUsd();
      if (what === "btc" || what === "bitcoin") {
        amin.reply(ctx.msg, "💱 " + usdLine("₿ Bitcoin", crypto.btc, rate));
      } else {
        amin.reply(ctx.msg, "💱 " + usdLine("Ξ Ethereum", crypto.eth, rate));
      }
      return;
    }
    if (what !== "" && what !== "all") {
      amin.reply(ctx.msg, "Usage: /price — or /price btc|eth|usd|gold|coin");
      return;
    }

    // Full board: every item is fetched independently, so one dead
    // source only blanks its own line.
    var usdT = tgjuToman("price_dollar_rl");
    var goldT = tgjuToman("geram18");
    var coinT = tgjuToman("sekee");
    var prices = cryptoUsd();
    var lines = ["💱 Live prices — Iran market, in tomans", ""];
    lines.push(tomanLine("🇺🇸 Dollar", usdT));
    lines.push(tomanLine("🥇 Gold 18k (per gram)", goldT));
    lines.push(tomanLine("🪙 Emami coin", coinT));
    lines.push(usdLine("₿ Bitcoin", prices.btc, usdT));
    lines.push(usdLine("Ξ Ethereum", prices.eth, usdT));
    lines.push("");
    lines.push("Sources: tgju.org, CoinGecko");
    amin.reply(ctx.msg, lines.join("\n"));
  } catch (e) {
    amin.log("price-check onCommand error: " + e);
    try { amin.reply(ctx.msg, "⚠️ Price check failed — see the plugin log."); } catch (e2) {}
  }
}
