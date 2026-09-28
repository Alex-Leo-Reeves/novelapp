#!/usr/bin/env node
/**
 * Verifies the multi-currency billing core in server/index.js.
 *
 * server/index.js is a single-file HTTP server with no module exports, so this
 * script extracts the self-contained billing block (currency metadata, plan
 * catalogue, pricing helpers) plus `paidAmountCovers` and evaluates them in an
 * isolated function scope. Everything it exercises is pure — no network, no
 * Supabase, no Flutterwave credentials required — so it runs in CI.
 *
 * What it protects:
 *   - Legacy `amount` stays an integer NGN value. Older v4x clients decode it
 *     into a non-nullable Int, so shipping 1.99 there crashes them. This is the
 *     single most dangerous regression this file guards.
 *   - `formatMoney` output for each supported currency.
 *   - Currency detection precedence (explicit -> Accept-Language -> NGN).
 *   - Exactly one AI payment tier is sellable. `ai_novel_4` / `ai_novel_5` were
 *     advertised by the app but absent from BILLING_PLANS, so checkout always
 *     answered "Choose a paid plan." — see the "single AI payment tier" section.
 *   - The regression that motivated this work: a USD/GBP/EUR payment must be
 *     verifiable instead of being rejected for not being NGN.
 *
 * Usage: node scripts/verify-billing-multicurrency.mjs
 */
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const serverPath = resolve(repoRoot, "server/index.js");
const source = readFileSync(serverPath, "utf8");

const BLOCK_START = "// ── Multi-currency billing";
const BLOCK_END = "const SPORTS_API_KEY";

function extractBillingBlock() {
    const start = source.indexOf(BLOCK_START);
    if (start < 0) throw new Error(`Could not find billing block start marker in ${serverPath}`);
    const end = source.indexOf(BLOCK_END, start);
    if (end < 0) throw new Error(`Could not find billing block end marker in ${serverPath}`);
    return source.slice(start, end);
}

function extractFunction(name) {
    const pattern = new RegExp(`function ${name}\\([^)]*\\) \\{[\\s\\S]*?\\n\\}`);
    const match = source.match(pattern);
    if (!match) throw new Error(`Could not extract ${name}() from ${serverPath}`);
    return match[0];
}

// Build an isolated instance of the billing core. Each call re-runs the
// module-level IIFE, so BILLING_PRICE_OVERRIDES is picked up per instance.
function loadBillingCore() {
    const body = [
        extractBillingBlock(),
        extractFunction("paidAmountCovers"),
        `return { BILLING_PLANS, BILLING_CURRENCY_META, BILLING_DEFAULT_CURRENCY, billingCurrencies,`
        + ` normalizeCurrency, currencyMeta, roundMoney, formatMoney, detectRequestCurrency,`
        + ` planPriceFor, resolvePlanPrice, planTierAmount, planForClient, billingCurrencyOptions,`
        + ` paidAmountCovers };`
    ].join("\n");
    // eslint-disable-next-line no-new-func
    return new Function(body)();
}

let failures = 0;
let checks = 0;

function check(label, actual, expected) {
    checks += 1;
    const ok = Object.is(actual, expected);
    if (!ok) {
        failures += 1;
        console.error(`  ✗ ${label}\n      expected: ${JSON.stringify(expected)}\n      actual:   ${JSON.stringify(actual)}`);
    }
}

function checkTrue(label, condition, detail) {
    checks += 1;
    if (!condition) {
        failures += 1;
        console.error(`  ✗ ${label}${detail ? `\n      ${detail}` : ""}`);
    }
}

function section(title) {
    console.log(`\n${title}`);
}

const billing = loadBillingCore();
const {
    BILLING_PLANS, billingCurrencies, normalizeCurrency, currencyMeta, roundMoney,
    formatMoney, detectRequestCurrency, planPriceFor, resolvePlanPrice, planTierAmount,
    planForClient, billingCurrencyOptions, paidAmountCovers
} = billing;

const PLAN = BILLING_PLANS.premium_3_devices;
const UNLIMITED = BILLING_PLANS.premium_unlimited;

section("currency catalogue");
check("default currency is NGN", billing.BILLING_DEFAULT_CURRENCY, "NGN");
check("four currencies offered", billingCurrencies().length, 4);
check("NGN is first so the picker defaults home", billingCurrencies()[0], "NGN");
check("normalizeCurrency lowercases input", normalizeCurrency("usd"), "USD");
check("normalizeCurrency rejects unknown", normalizeCurrency("JPY"), "");
check("NGN settles with country NG", currencyMeta("NGN").country, "NG");
check("USD settles with country NG", currencyMeta("USD").country, "NG");
check("NGN has no minor unit", currencyMeta("NGN").decimals, 0);
check("USD has two minor units", currencyMeta("USD").decimals, 2);

section("money formatting");
check("1000 NGN", formatMoney(1000, "NGN"), "₦1,000");
check("4000 NGN groups thousands", formatMoney(4000, "NGN"), "₦4,000");
check("2000 NGN", formatMoney(2000, "NGN"), "₦2,000");
check("1.99 USD", formatMoney(1.99, "USD"), "$1.99");
check("7.99 USD", formatMoney(7.99, "USD"), "$7.99");
check("1.49 GBP", formatMoney(1.49, "GBP"), "£1.49");
check("1.79 EUR", formatMoney(1.79, "EUR"), "€1.79");
check("whole USD keeps two decimals", formatMoney(5, "USD"), "$5.00");
check("NGN never shows decimals", formatMoney(1000.4, "NGN"), "₦1,000");

section("money rounding (float safety)");
check("1.99 survives rounding", roundMoney(1.99, "USD"), 1.99);
check("NGN rounds to integer", roundMoney(1000.6, "NGN"), 1001);
checkTrue(
    "1.99 is bit-exact enough to equal itself",
    roundMoney(1.99, "USD") === 1.99,
    `got ${String(roundMoney(1.99, "USD"))}`
);

section("plan pricing");
check("NGN price", planPriceFor(PLAN, "NGN").amount, 1000);
check("USD price", planPriceFor(PLAN, "USD").amount, 1.99);
check("GBP price", planPriceFor(PLAN, "GBP").amount, 1.49);
check("EUR price", planPriceFor(PLAN, "EUR").amount, 1.79);
check("lowercase currency resolves", planPriceFor(PLAN, "usd").amount, 1.99);
check("unsupported currency returns null", planPriceFor(PLAN, "JPY"), null);
check("free plan is priced zero", planPriceFor(BILLING_PLANS.free, "NGN").amount, 0);
check("unlimited NGN price", planTierAmount(UNLIMITED), 4000);
check("tier yardstick is always NGN", planTierAmount(PLAN), 1000);
checkTrue(
    "unlimited outranks 3-device on the NGN yardstick",
    planTierAmount(UNLIMITED) > planTierAmount(PLAN)
);
check("unknown currency falls back to NGN", resolvePlanPrice(PLAN, "JPY").currency, "NGN");

section("planForClient backwards compatibility");
const usdClientPlan = planForClient(PLAN, "USD");
const ngnClientPlan = planForClient(PLAN, "NGN");
// ── The critical guard: legacy `amount` must remain an integer NGN value. ──
check("legacy amount is still the NGN integer", usdClientPlan.amount, 1000);
check("legacy amount stays integral for USD", Number.isInteger(usdClientPlan.amount), true);
check("legacy currency is still NGN", usdClientPlan.currency, "NGN");
check("legacy amount matches NGN pricing", ngnClientPlan.amount, 1000);
// ── Currency-aware fields ──
check("selectedCurrency reflects the request", usdClientPlan.selectedCurrency, "USD");
check("priceLabel is the USD label", usdClientPlan.priceLabel, "$1.99");
check("amountMajor is the USD amount", usdClientPlan.amountMajor, 1.99);
check("NGN client plan label", ngnClientPlan.priceLabel, "₦1,000");
check("NGN client plan selectedCurrency", ngnClientPlan.selectedCurrency, "NGN");
checkTrue("prices map covers every currency", Object.keys(usdClientPlan.prices).length === 4);
check("prices.NGN label", usdClientPlan.prices.NGN.label, "₦1,000");
check("prices.USD label", usdClientPlan.prices.USD.label, "$1.99");
check("prices.GBP label", usdClientPlan.prices.GBP.label, "£1.49");
check("premium flag preserved", usdClientPlan.premium, true);
check("maxDevices preserved", usdClientPlan.maxDevices, 3);
check("plan id preserved", usdClientPlan.id, "premium_3_devices");

section("currency detection");
check("en-US header -> USD", detectRequestCurrency({ headers: { "accept-language": "en-US,en;q=0.9" } }, null), "USD");
check("en-GB header -> GBP", detectRequestCurrency({ headers: { "accept-language": "en-GB,en;q=0.9" } }, null), "GBP");
check("de-DE header -> EUR", detectRequestCurrency({ headers: { "accept-language": "de-DE,de;q=0.9" } }, null), "EUR");
check("en-NG header -> NGN", detectRequestCurrency({ headers: { "accept-language": "en-NG,en;q=0.9" } }, null), "NGN");
check("unsupported region -> NGN", detectRequestCurrency({ headers: { "accept-language": "ja-JP" } }, null), "NGN");
check("bare language tag -> NGN", detectRequestCurrency({ headers: { "accept-language": "en" } }, null), "NGN");
check("no header -> NGN", detectRequestCurrency({ headers: {} }, null), "NGN");
check("no request object -> NGN", detectRequestCurrency(null, null), "NGN");
check("explicit currency wins over header", detectRequestCurrency({ headers: { "accept-language": "en-US" } }, "GBP"), "GBP");
check("explicit NGN beats a US header", detectRequestCurrency({ headers: { "accept-language": "en-US" } }, "NGN"), "NGN");
check("unknown explicit currency is ignored", detectRequestCurrency({ headers: { "accept-language": "en-US" } }, "JPY"), "USD");
check("explicit currency is case-insensitive", detectRequestCurrency({ headers: {} }, "gbp"), "GBP");

section("payment amount predicate");
const usdExpected = planPriceFor(PLAN, "USD").amount;
const ngnExpected = planPriceFor(PLAN, "NGN").amount;
const unlimitedUsd = planPriceFor(UNLIMITED, "USD").amount;
// The original bug: verification compared the settled currency against the
// plan's single NGN price, so every international payment was rejected even
// though the card had already been charged.
checkTrue("exact USD payment is accepted", paidAmountCovers(1.99, usdExpected, "USD") === true);
checkTrue("overpaid USD payment is accepted", paidAmountCovers(2.5, usdExpected, "USD") === true);
checkTrue("underpaid USD payment is rejected", paidAmountCovers(1.5, usdExpected, "USD") === false);
checkTrue("USD payment cannot buy the unlimited tier", paidAmountCovers(1.99, unlimitedUsd, "USD") === false);
checkTrue("exact NGN payment is accepted", paidAmountCovers(1000, ngnExpected, "NGN") === true);
checkTrue("one naira short is rejected", paidAmountCovers(999, ngnExpected, "NGN") === false);
checkTrue("NGN overpayment is accepted", paidAmountCovers(5000, ngnExpected, "NGN") === true);
// Float-representation slack: 1.99 stored as 1.9900000000000002 must still pass.
checkTrue("float noise on the expected amount is tolerated", paidAmountCovers(1.99, 1.9900000000000002, "USD") === true);
checkTrue("float noise on the paid amount is tolerated", paidAmountCovers(1.9900000000000002, 1.99, "USD") === true);
checkTrue("zero paid is rejected", paidAmountCovers(0, ngnExpected, "NGN") === false);
checkTrue("missing amount is rejected", paidAmountCovers(undefined, ngnExpected, "NGN") === false);

section("currency picker options");
const options = billingCurrencyOptions();
check("option count", options.length, 4);
check("first option is NGN", options[0].code, "NGN");
check("NGN option symbol", options[0].symbol, "₦");
check("USD option symbol", options[1].symbol, "$");
checkTrue("options carry the Flutterwave country", options.every((o) => o.country === "NG"));

section("operator overrides (env)");
process.env.BILLING_CURRENCIES = "NGN,USD";
check("BILLING_CURRENCIES trims the offer list", billingCurrencies().join(","), "NGN,USD");
check("trimmed list drives detection", detectRequestCurrency({ headers: { "accept-language": "de-DE" } }, null), "NGN");
delete process.env.BILLING_CURRENCIES;
check("offer list restored without the env var", billingCurrencies().join(","), "NGN,USD,GBP,EUR");

process.env.BILLING_CURRENCIES = "ZZZ";
check("invalid BILLING_CURRENCIES falls back to all", billingCurrencies().length, 4);
delete process.env.BILLING_CURRENCIES;

process.env.BILLING_PRICE_OVERRIDES = JSON.stringify({ premium_3_devices: { USD: 1.59 } });
const overridden = loadBillingCore();
check("price override applies", overridden.planPriceFor(overridden.BILLING_PLANS.premium_3_devices, "USD").amount, 1.59);
check("price override leaves NGN alone", overridden.planPriceFor(overridden.BILLING_PLANS.premium_3_devices, "NGN").amount, 1000);
check("overridden label reformats", overridden.planForClient(overridden.BILLING_PLANS.premium_3_devices, "USD").priceLabel, "$1.59");
delete process.env.BILLING_PRICE_OVERRIDES;

process.env.BILLING_PRICE_OVERRIDES = "{not json";
const malformed = loadBillingCore();
check("malformed overrides are ignored", malformed.planPriceFor(malformed.BILLING_PLANS.premium_3_devices, "USD").amount, 1.99);
delete process.env.BILLING_PRICE_OVERRIDES;

// ── Plan catalogue: exactly ONE AI payment tier ────────────────────────────
// Regression guard. The app used to sell "ai_novel_4" and "ai_novel_5", which
// were never keys in BILLING_PLANS. billingPlanFor() silently fell back to
// `free`, whose premium === false, so checkout answered 400 "Choose a paid
// plan." — the AI upgrade buttons could never start a payment for anyone.
section("single AI payment tier");
checkTrue("ai_creator_20 is the AI tier", BILLING_PLANS.ai_creator_20?.premium === true);
checkTrue("ai_creator_20 is sellable", BILLING_PLANS.ai_creator_20?.sellable !== false);
checkTrue("dead ai_novel_4 plan is gone", !Object.prototype.hasOwnProperty.call(BILLING_PLANS, "ai_novel_4"));
checkTrue("dead ai_novel_5 plan is gone", !Object.prototype.hasOwnProperty.call(BILLING_PLANS, "ai_novel_5"));
checkTrue("retired ai_creator_unlimited is not sellable", BILLING_PLANS.ai_creator_unlimited?.sellable === false);
checkTrue("retired plan keeps premium entitlement", BILLING_PLANS.ai_creator_unlimited?.premium === true);
checkTrue("non-AI plans stay sellable by default", BILLING_PLANS.premium_3_devices?.sellable === undefined);
check("AI tier NGN price", planPriceFor(BILLING_PLANS.ai_creator_20, "NGN").amount, 2000);
check("AI tier USD price", planPriceFor(BILLING_PLANS.ai_creator_20, "USD").amount, 3.99);

console.log("");
if (failures > 0) {
    console.error(`FAIL — ${failures} of ${checks} checks failed.`);
    process.exit(1);
}
console.log(`PASS — all ${checks} billing checks passed.`);


