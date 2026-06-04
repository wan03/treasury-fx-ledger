/* ============================================================================
   WEX FX Ledger — interactive explorer (static sibling assets, no build step, no dependencies)
   Two contexts: CODEBASE (architecture, decisions, real code, live playgrounds)
   and LIVE APP (connect + API playground against the deployed service).
   ========================================================================== */

/* ----- optional overrides (both work empty) -----
   When this page is SERVED BY THE APP, the Live App tab targets the same origin
   automatically — no setup needed. These are only fallbacks for opening the file
   offline (file://), and a nicety for deep-linking source on the repo host. */
const DEPLOYED_URL = "https://currency-ledger.onrender.com"; // the live service (used as the base when opened offline)
const REPO_URL     = ""; // e.g. "https://github.com/you/wex-fx-ledger" -> makes the .md links resolve

/* ===================== tiny helpers ===================== */
function el(id){return document.getElementById(id);}
function escapeHtml(s){return String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");}
function toast(msg){var t=el("toast");t.textContent=msg;t.classList.add("show");clearTimeout(t._t);t._t=setTimeout(function(){t.classList.remove("show");},1600);}
function copy(text){navigator.clipboard&&navigator.clipboard.writeText(text).then(function(){toast("Copied");},function(){toast("Copy failed");});}

/* ===================== syntax highlighter ===================== */
function tokenize(code, patterns){
  var combined = new RegExp(patterns.map(function(p){return "("+p[1].source+")";}).join("|"), "g");
  var out="", last=0, m;
  while((m = combined.exec(code))){
    if(m.index < last){combined.lastIndex = last; continue;}
    out += escapeHtml(code.slice(last, m.index));
    var cls=null, text=m[0];
    for(var i=0;i<patterns.length;i++){ if(m[i+1]!==undefined){cls=patterns[i][0];break;} }
    out += '<span class="tok-'+cls+'">'+escapeHtml(text)+'</span>';
    last = m.index + text.length;
    if(text.length===0){combined.lastIndex++;}
  }
  out += escapeHtml(code.slice(last));
  return out;
}
var JAVA_PAT = [
  ["comment", /\/\/[^\n]*|\/\*[\s\S]*?\*\//],
  ["string", /"(?:\\.|[^"\\])*"/],
  ["annotation", /@[A-Za-z_][A-Za-z0-9_]*/],
  ["keyword", /\b(?:public|private|protected|final|static|class|record|interface|enum|return|if|else|for|new|throws|throw|void|int|boolean|import|package|null|this|implements|extends|true|false)\b/],
  ["type", /\b(?:BigDecimal|RoundingMode|LocalDate|Optional|List|Comparator|String|Money|ExchangeRate|RateSelector|Objects|Pattern|Integer|Supplier|Retry|CircuitBreaker)\b/],
  ["number", /\b\d+(?:\.\d+)?\b/]
];
var JSON_PAT = [
  ["string-key", /"(?:\\.|[^"\\])*"(?=\s*:)/],
  ["string", /"(?:\\.|[^"\\])*"/],
  ["keyword", /\b(?:true|false|null)\b/],
  ["number", /-?\b\d+(?:\.\d+)?\b/]
];
var BASH_PAT = [
  ["comment", /#[^\n]*/],
  ["string", /'(?:[^'])*'|"(?:\\.|[^"\\])*"/],
  ["keyword", /\b(?:curl)\b/],
  ["flag", /-{1,2}[A-Za-z][A-Za-z-]*/]
];
var CSV_PAT = [
  ["comment", /#[^\n]*/],
  ["string", /[A-Za-z][A-Za-z &]+/],
  ["number", /\b\d+\b/]
];
function hl(code, lang){
  var pat = lang==="json"?JSON_PAT: lang==="bash"?BASH_PAT: lang==="csv"?CSV_PAT: JAVA_PAT;
  return tokenize(code, pat);
}
function codeblock(fname, code, lang, wrap){
  var id = "cb_"+(codeblock._n=(codeblock._n||0)+1);
  var w = wrap?" wrap":"";
  return '<div class="codeblock'+w+'">'
    + '<div class="cb-head"><span class="fname">'+escapeHtml(fname)+'</span><span class="spacer"></span>'
    + '<button class="copybtn" data-copy="'+id+'">Copy</button></div>'
    + '<pre id="'+id+'">'+hl(code, lang)+'</pre></div>';
}

/* ===================== Java-faithful date math ===================== */
function pad2(n){return (n<10?"0":"")+n;}
function pad4(n){n=String(n);while(n.length<4)n="0"+n;return n;}
function isoOf(y,m,d){return pad4(y)+"-"+pad2(m)+"-"+pad2(d);} // m is 1-based
function lastDayOfMonth(y, m1){ return new Date(Date.UTC(y, m1, 0)).getUTCDate(); } // m1 1-based
// Mirrors java.time.LocalDate.minusMonths: shift the month, then clamp the day to the
// last valid day of the resulting month (e.g. Aug 31 - 6mo -> Feb 28/29).
function minusMonths(dateStr, months){
  var p = dateStr.split("-"); var y=+p[0], m=+p[1], d=+p[2];
  var total = (y*12 + (m-1)) - months;
  var ny = Math.floor(total/12);
  var nm0 = total - ny*12;            // 0-based month
  var nm1 = nm0 + 1;                   // 1-based
  var ld = lastDayOfMonth(ny, nm1);
  return isoOf(ny, nm1, Math.min(d, ld));
}
// ISO yyyy-mm-dd compares correctly as strings for non-negative years.
function dcmp(a,b){return a<b?-1:a>b?1:0;}

/* ===================== fixed-point money (mirrors Money.java) ===================== */
// No float anywhere: scaled BigInt arithmetic, HALF_UP, single final rounding to scale 2.
function parseScaled(str){
  str = String(str).trim();
  if(!/^-?\d+(\.\d+)?$/.test(str)) throw new Error("not a plain decimal: "+str);
  var neg = str[0]==="-"; if(neg) str=str.slice(1);
  var parts = str.split("."); var intp=parts[0], frac=parts[1]||"";
  var scale = frac.length;
  var value = BigInt((intp+frac)||"0");
  if(neg) value = -value;
  return {value:value, scale:scale};
}
function rescaleHalfUp(value, scale, target){
  if(target>=scale){ return {value: value*(10n**BigInt(target-scale)), scale:target}; }
  var drop = scale-target, div = 10n**BigInt(drop);
  var neg = value<0n, v = neg?-value:value;
  var q = v/div, r = v%div;
  if(r*2n >= div) q = q+1n;            // HALF_UP
  if(neg) q = -q;
  return {value:q, scale:target};
}
function formatScaled(value, scale){
  var neg = value<0n, v = neg?-value:value, s=v.toString();
  if(scale===0) return (neg?"-":"")+s;
  while(s.length<=scale) s="0"+s;
  return (neg?"-":"")+s.slice(0,s.length-scale)+"."+s.slice(s.length-scale);
}
// convertedAt: principal (scale<=2) x rate (any scale) at full precision, round once to 2.
function convertMoney(amountStr, rateStr){
  var amt = parseScaled(amountStr);
  if(amt.scale>2) throw new Error("amount has more than 2 decimals (the API returns 400 AMOUNT_PRECISION)");
  var principal = rescaleHalfUp(amt.value, amt.scale, 2);  // pad to scale 2
  var rate = parseScaled(rateStr);
  var prodVal = principal.value * rate.value;
  var prodScale = 2 + rate.scale;                          // full precision product
  var rounded = rescaleHalfUp(prodVal, prodScale, 2);      // the ONE rounding
  return {
    result: formatScaled(rounded.value, rounded.scale),
    principal: formatScaled(principal.value, 2),
    rateScale: rate.scale,
    productRaw: formatScaled(prodVal, prodScale),
    productScale: prodScale
  };
}

/* ===================== real code snippets (verbatim from the repo) ===================== */
var SNIP = {};
SNIP.money =
"public Money {\n"+
"    Objects.requireNonNull(amount, \"amount must not be null\");\n"+
"    Objects.requireNonNull(currencyCode, \"currencyCode must not be null\");\n"+
"    if (!ISO_4217.matcher(currencyCode).matches()) {\n"+
"        throw new IllegalArgumentException(\"currencyCode must match [A-Z]{3}, was: \" + currencyCode);\n"+
"    }\n"+
"    // The ONE rounding in the money path (D-04). Pads a cent-precise principal; performs\n"+
"    // the single, final rounding for a conversion product. Never pre-round a rate/intermediate.\n"+
"    amount = amount.setScale(SCALE, ROUNDING);   // SCALE=2, ROUNDING=HALF_UP\n"+
"}\n\n"+
"public Money convertedAt(BigDecimal rate, String targetCurrencyCode) {\n"+
"    Objects.requireNonNull(rate, \"rate must not be null\");\n"+
"    // full-precision multiply; the constructor above rounds exactly once.\n"+
"    return new Money(amount.multiply(rate), targetCurrencyCode);\n"+
"}";
SNIP.rate =
"public Optional<ExchangeRate> select(List<ExchangeRate> candidates, LocalDate purchaseDate) {\n"+
"    Objects.requireNonNull(purchaseDate, \"purchaseDate must not be null\");\n"+
"    if (candidates == null || candidates.isEmpty()) {\n"+
"        return Optional.empty();\n"+
"    }\n"+
"    LocalDate floor = windowFloor(purchaseDate);\n"+
"    return candidates.stream()\n"+
"            .filter(r -> !r.effectiveDate().isAfter(purchaseDate))  // effectiveDate <= purchaseDate\n"+
"            .filter(r -> !r.effectiveDate().isBefore(floor))        // effectiveDate >= floor (inclusive)\n"+
"            .max(Comparator.comparing(ExchangeRate::effectiveDate)  // latest effectiveDate wins\n"+
"                    .thenComparing(ExchangeRate::recordDate));      // deterministic tiebreak (F8)\n"+
"}\n\n"+
"public LocalDate windowFloor(LocalDate purchaseDate) {\n"+
"    return purchaseDate.minusMonths(windowMonths);   // calendar months, not 180 days\n"+
"}";
SNIP.resilience =
"// Retry OVER circuit-breaker: a tripped breaker fails fast without burning retries.\n"+
"private <T> T guarded(Supplier<T> call) {\n"+
"    Supplier<T> decorated = Retry.decorateSupplier(retry,\n"+
"            CircuitBreaker.decorateSupplier(circuitBreaker, call));\n"+
"    try {\n"+
"        return decorated.get();\n"+
"    } catch (CallNotPermittedException e) {           // breaker open -> 503 + Retry-After\n"+
"        throw RateProviderUnavailableException.circuitOpen(e);\n"+
"    } catch (ResourceAccessException e) {              // timeout -> 504, else 502\n"+
"        throw isTimeout(e) ? ...timeout(e) : ...badGateway(e);\n"+
"    } catch (HttpServerErrorException e) {             // upstream 5xx -> 502\n"+
"        throw RateProviderUnavailableException.badGateway(e);\n"+
"    }\n"+
"    // Every failure collapses to ONE domain signal -> 502/503/504. Never a hang, never a 500.\n"+
"}";
SNIP.errors =
"@ExceptionHandler(NoRateAvailableException.class)            // R2's mandated path\n"+
"ProblemDetail noRate(NoRateAvailableException ex) {\n"+
"    return problem(HttpStatus.UNPROCESSABLE_ENTITY,          // 422\n"+
"            \"No exchange rate available\", ex.getMessage(), \"NO_RATE_AVAILABLE\");\n"+
"}\n\n"+
"@ExceptionHandler(MalformedCurrencyException.class)\n"+
"ProblemDetail malformedCurrency(MalformedCurrencyException ex) {\n"+
"    return problem(HttpStatus.BAD_REQUEST,                   // 400\n"+
"            \"Malformed currency code\", ex.getMessage(), \"CURRENCY_CODE_MALFORMED\");\n"+
"}\n\n"+
"// Every ProblemDetail carries a machine `code` + `traceId`; logs record code/traceId/\n"+
"// method/path ONLY -- never an amount, never the description (constitution s9).";
SNIP.csv =
"# iso,country_currency_desc,notes\n"+
"EUR,Euro Zone-Euro,\n"+
"GBP,United Kingdom-Pound,\n"+
"ARS,Argentina-Peso,amendment fixture currency\n"+
"JPY,Japan-Yen,zero-minor-unit currency (still 2dp on the wire)\n"+
"XOF,Senegal-Cfa Franc,BCEAO  -- West African  (XOF != XAF, F6)\n"+
"XAF,Cameroon-Cfa Franc,BEAC   -- Central African (different rate!)\n"+
"# USD intentionally absent: it is an in-app identity (rate 1.00, no upstream call) -- D-07";

/* captured real responses (from a live R1 -> R2 round-trip) */
var EX = {
  create201:
'{\n  "id": "019e93ff-fbad-7d54-aec5-948f732030b0",\n  "description": "Office supplies",\n  "transactionDate": "2025-04-15",\n  "amount": "100.00",\n  "currency": "USD",\n  "createdAt": "2026-06-04T18:58:07.405834Z"\n}',
  eur200:
'{\n  "purchaseId": "019e93ff-fbad-7d54-aec5-948f732030b0",\n  "description": "Office supplies",\n  "transactionDate": "2025-04-15",\n  "originalAmount": "100.00",\n  "originalCurrency": "USD",\n  "targetCurrency": "EUR",\n  "exchangeRate": "0.924",\n  "rateEffectiveDate": "2025-03-31",\n  "convertedAmount": "92.40",\n  "rateSource": "U.S. Treasury Reporting Rates of Exchange"\n}',
  usd200:
'{\n  "purchaseId": "019e93ff-fbad-7d54-aec5-948f732030b0",\n  "originalAmount": "100.00",\n  "originalCurrency": "USD",\n  "targetCurrency": "USD",\n  "exchangeRate": "1.00",\n  "convertedAmount": "100.00",\n  "rateSource": "In-app USD identity (no upstream rate)"\n}',
  err400:
'{\n  "type": "https://api.example.com/problems/currency-code-malformed",\n  "title": "Malformed currency code",\n  "status": 400,\n  "detail": "Target currency code must be three uppercase letters (^[A-Z]{3}$).",\n  "code": "CURRENCY_CODE_MALFORMED",\n  "traceId": "8f1c2e7a9b3d4f60",\n  "instance": "/v1/purchases/019e93ff-fbad-7d54-aec5-948f732030b0/conversions/eur"\n}',
  err422:
'{\n  "type": "https://api.example.com/problems/no-rate-available",\n  "title": "No exchange rate available",\n  "status": 422,\n  "detail": "No Treasury rate for USD->ARS within 6 months on/before 2019-01-01.",\n  "code": "NO_RATE_AVAILABLE",\n  "traceId": "a3d9...",\n  "instance": "/v1/purchases/.../conversions/ARS"\n}'
};

/* ===================== navigation model ===================== */
var SECTIONS = {
  codebase: [
    {id:"overview",     label:"Overview",          ic:"◆"},
    {id:"architecture", label:"Architecture",      ic:"▦"},
    {id:"seam",         label:"Provider seam",     ic:"⮂"},
    {id:"decisions",    label:"Decision log",      ic:"⚖"},
    {id:"tour",         label:"Code tour",         ic:"{}"},
    {id:"playground",   label:"Rate playground",   ic:"▶"},
    {id:"money",        label:"Money calculator",  ic:"¤"},
    {id:"testing",      label:"Testing & gates",   ic:"✓"},
    {id:"trace",        label:"Traceability",      ic:"⤳"}
  ],
  live: [
    {id:"connect",  label:"Live service",    ic:"⚡"},
    {id:"api",      label:"API playground",  ic:"⟐"},
    {id:"errors",   label:"Error catalog",   ic:"⚠"},
    {id:"flow",     label:"Example flow",    ic:"➜"}
  ]
};
var state = {ctx:"codebase", id:"overview"};

/* ===================== section renderers ===================== */
var RENDER = {};
var INIT = {};

/* ---------- CODEBASE: Overview ---------- */
RENDER.overview = function(){
  return crossref("live")
  + '<h2>USD purchase ledger &amp; Treasury currency conversion</h2>'
  + '<p class="lead">A production-grade Java 21 / Spring Boot service with two operations: '
  + '<b>store a USD purchase</b> (R1) and <b>read it back converted</b> into a target currency '
  + 'using official U.S. Treasury <i>Reporting Rates of Exchange</i> (R2). The happy path is trivial; '
  + 'the engineering signal is in money handling, rate-selection correctness, resilience, security and tests.</p>'
  + '<div class="banner start"><b>Reviewer? The 30-second route:</b> '
    + '<a data-goto="playground">1 · Rate playground</a> — drag the purchase date, watch the right Treasury rate '
    + 'get picked (the intra-quarter amendment case). '
    + '<a data-goto="money">2 · Money calculator</a> — the round-once, no-float arithmetic. '
    + '<a data-goto="tour">3 · Code tour</a> — the five files that carry the signal, each linked to its source. '
    + 'Rather run it? <a data-ctx="live">Live App →</a> fires real requests at the service.</div>'
  + '<div class="grid3 mt18">'
    + kpi("blue","2","operations — R1 store, R2 convert")
    + kpi("green","92%","PIT mutation score on the money / rate core")
    + kpi("red","0","floats in the money path — all BigDecimal")
  + '</div>'
  + '<h3>The two operations</h3>'
  + '<table><thead><tr><th>#</th><th>Operation</th><th>What it does</th></tr></thead><tbody>'
    + '<tr><td><span class="pill">R1</span></td><td><code>POST /v1/purchases</code></td>'
      + '<td>Validate &amp; persist a purchase (<code>description</code> ≤50, valid past <code>date</code>, '
      + 'positive USD <code>amount</code> ≤2dp). Assign a server-side <b>UUIDv7</b>. Append-only.</td></tr>'
    + '<tr><td><span class="pill">R2</span></td><td><code>GET …/conversions/{code}</code></td>'
      + '<td>Select the Treasury rate <b>active on/before the purchase date within 6 months</b>, multiply, '
      + 'round once to 2dp. No in-window rate ⇒ <code>422 NO_RATE_AVAILABLE</code>.</td></tr>'
  + '</tbody></table>'
  + '<h3>What to look at first</h3>'
  + '<div class="grid2">'
    + navcard("playground","▶ Rate-selection playground","Drag a purchase date and watch the Argentina amendment fixture pick the right rate in real time — selection on effective_date, not record_date.")
    + navcard("money","¤ Money calculator","BigInt fixed-point that mirrors Money.java — see the exact HALF_UP, round-once arithmetic with no float.")
    + navcard("decisions","⚖ Decision log","13 ADRs + verified Treasury facts. Every rule traces to a written rationale.")
    + navcard("architecture","▦ Architecture","Hexagonal, ArchUnit-enforced: domain & application import zero framework.")
  + '</div>';
};

/* ---------- CODEBASE: Architecture ---------- */
RENDER.architecture = function(){
  var layers = [
    ["domain","domain","com.wex.fx.domain","Pure business model — <b>zero framework imports</b> (ArchUnit-enforced). "
      +"<code>Money</code>, <code>RateSelector</code>, <code>ExchangeRate</code>, the currency model. Deterministic, exhaustively unit-testable."],
    ["application","application","com.wex.fx.application","Use-case orchestration behind <b>ports</b>: "
      +"<code>StorePurchase</code>, <code>ConvertPurchase</code>. Depends on the <code>ExchangeRateProvider</code> "
      +"and <code>PurchaseRepository</code> interfaces — not on Spring, not on Postgres, not on HTTP."],
    ["adapter","adapter","com.wex.fx.adapter.*","The only place frameworks live: <code>web</code> (controllers, RFC 9457 "
      +"errors, security headers), <code>persistence</code> (Spring Data JDBC + Flyway), <code>treasury</code> "
      +"(RestClient + Resilience4j). Adapters implement the ports."],
    ["config","config","com.wex.fx.config","Wiring &amp; profiles. Picks <i>which</i> <code>ExchangeRateProvider</code> "
      +"adapter to bind from a single config flag (<code>fx.rates.provider</code>) — acquisition strategy is config, not a rewrite."]
  ];
  var html = crossref("live")
  + '<h2>Hexagonal architecture</h2>'
  + '<p class="lead">Dependencies point <b>inward</b>. The domain knows nothing about Spring, Postgres or HTTP; '
  + 'the outside world plugs in through ports. <span class="muted">Click a layer for detail.</span></p>'
  + '<div class="arch" id="archDiagram">';
  layers.forEach(function(L,i){
    html += '<div class="layer '+L[0]+'" data-i="'+i+'">'
      + '<div class="lname">'+L[2].split(".").slice(-1)[0].replace(/\*/g,"")
      + ' <span class="ltag">'+L[2]+'</span></div>'
      + '<div class="ldesc">'+L[3]+'</div></div>';
  });
  html += '</div>'
  + '<div class="banner info">↑ outer layers depend on inner; inner layers never depend on outer. '
  + 'The dependency rule is verified by ArchUnit tests, not just convention.</div>'
  + '<p class="provline">The rule, as an executable test: '
    + prov("src/test/java/com/wex/fx/domain/architecture/DomainArchitectureTest.java","DomainArchitectureTest")+'</p>'
  + '<h3>The crux, in one line</h3>'
  + codeblock("domain/rate/RateSelector.java — windowFloor + select",
      "max( effectiveDate ) where  floor <= effectiveDate <= purchaseDate ,  floor = purchaseDate.minusMonths(6)", "java", true)
  + '<p class="muted">Selection is a <b>pure function</b> over candidate rows — no clock, no network — so it is '
  + 'deterministic and exhaustively testable. See it run in the '
  + '<a data-goto="playground">Rate playground →</a></p>';
  return html;
};
INIT.architecture = function(){
  var d = el("archDiagram");
  d.addEventListener("click", function(e){
    var layer = e.target.closest(".layer"); if(!layer) return;
    var was = layer.classList.contains("sel");
    Array.prototype.forEach.call(d.querySelectorAll(".layer"), function(x){x.classList.remove("sel");});
    if(!was) layer.classList.add("sel");
  });
};

/* ---------- CODEBASE: Provider seam ---------- */
RENDER.seam = function(){
  return crossref("live")
  + '<h2>The <code>ExchangeRateProvider</code> seam</h2>'
  + '<p class="lead">Rate acquisition is a <b>strategy behind one port</b>. Four adapters satisfy the same '
  + 'interface; <code>fx.rates.provider</code> selects which one is wired. Swapping strategy is a config flag, not a rewrite.</p>'
  + '<table><thead><tr><th>Flag</th><th>Adapter</th><th>Strategy</th><th>When</th></tr></thead><tbody>'
    + row("ondemand","OnDemandRateProvider","<span class='pill ok'>default</span> Call Treasury per conversion (filtered, cached).","Low volume, freshest data, simplest ops.")
    + row("ingest","IngestRateProvider","Scheduled batch ingest into the local DB; selection reads local rows.","High volume / Treasury-outage tolerance.")
    + row("hybrid","HybridRateProvider","Local-first, fall back to on-demand on a miss.","Best of both; warm cache + fresh tail.")
    + row("passthrough","PassthroughRateProvider","Thin no-cache passthrough (reference / tests).","Baseline &amp; contract tests.")
  + '</tbody></table>'
  + '<div class="banner info">Whatever the adapter, the caller still runs <code>RateSelector.select(...)</code> over '
  + 'the returned rows — a server-side filter push-down (F7) is an optimization; the pure function is the spec.</div>'
  + '<h3>Why a port at all?</h3>'
  + '<p>One of the seven open questions for the hiring manager is the rate-acquisition strategy (on-demand vs. '
  + 'pre-ingested). Rather than guess, the design makes the answer <b>cheap to change</b>: the domain selection '
  + 'logic is identical across all four adapters. See <a href="DECISION_LOG.md" data-doc="docs/DECISION_LOG.md">D-03</a>.</p>';
  function row(flag,cls,strat,when){
    return '<tr><td><code>'+flag+'</code></td><td><b>'+cls+'</b></td><td>'+strat+'</td><td class="muted">'+when+'</td></tr>';
  }
};

/* ---------- CODEBASE: Decision log ---------- */
var DECISIONS = [
  ["D-01","Currency input &amp; mapping","decision","ISO-4217 in, resolved through a curated, version-controlled CSV map to Treasury <code>country_currency_desc</code>. <b>XOF ≠ XAF</b> — both read \"Cfa Franc\" but are different rates."],
  ["D-02","Rate selection by effective_date","decision","Pick <code>max(effectiveDate) ≤ purchaseDate</code> within 6 calendar months. Treasury issues intra-quarter <b>amendments</b> (new effectiveDate, same recordDate), so selecting on recordDate would be wrong."],
  ["D-03","Rate acquisition strategy","decision","A single <code>ExchangeRateProvider</code> port with four adapters (passthrough / ondemand / hybrid / ingest). Default is on-demand; strategy is a config flag."],
  ["D-04","Money &amp; rounding","decision","<code>BigDecimal</code> only — never float/double. Compute at full precision, round <b>once</b> at the end, HALF_UP, scale 2. Compare with <code>compareTo</code>."],
  ["D-05","Amount precision","decision","Reject amounts with >2 decimals (<code>400 AMOUNT_PRECISION</code>) — never silently round the principal. The only rounding is the derived conversion output."],
  ["D-06","Date validation","decision","Parse strictly as ISO local date. Reject <b>future</b> dates (400). Store too-old dates but fail conversion later with 422."],
  ["D-07","USD target","decision","<code>USD→USD</code> is an in-app identity: rate <code>1.00</code>, convertedAmount = original, <b>no upstream Treasury call</b>."],
  ["D-08","Identifier &amp; idempotency","decision","Server-generated <b>UUIDv7</b> (time-ordered) stored as native <code>uuid</code>. <code>Idempotency-Key</code> makes R1 safely retryable."],
  ["D-09","API contract &amp; resource design","decision","RFC 9457 <code>application/problem+json</code> with machine <code>code</code> + <code>traceId</code>. Append-only (no PUT/PATCH/DELETE). 400 = malformed, 422 = well-formed but unfulfillable."],
  ["D-10","Persistence &amp; local dev","decision","Postgres + Flyway (plain SQL), Spring Data JDBC, Testcontainers (no H2). Least-privilege DB roles: <code>migration</code> (DDL) vs <code>app</code> (DML)."],
  ["D-11","Test strategy","decision","Deterministic: injected <code>Clock</code>, <b>zero real network in the gate</b> (WireMock + Testcontainers), PIT mutation testing on the money / rate core."],
  ["D-12","Deployment","decision","Render (Docker) + Neon (managed Postgres). Flyway migrates on boot; <code>/actuator/health</code> gates rollout."],
  ["D-13","Explorer as the front door","decision","<code>GET /</code> forwards to this page so its Live App tab is <b>same-origin (no CORS)</b>. CSP is tailored per surface: strict <code>default-src 'none'</code> on <code>/v1</code>; an <b>all-<code>'self'</code></b> policy on <code>/</code> + <code>/explore.html</code> (<code>script-src/style-src/img-src 'self'</code>, <code>connect-src 'self'</code>) — <b>no <code>'unsafe-inline'</code>, no <code>data:</code></b>; none on Swagger. To earn that, the page was <b>de-inlined</b> into same-origin <code>explore.js</code> / <code>explore.css</code> / <code>favicon.svg</code>, so injected markup can't execute even if escaping is ever missed. The API contract is untouched."],
  ["F2","Rate direction","fact","Treasury rates are <b>foreign units per 1 USD</b>, so conversion <b>multiplies</b>. 100 USD × 0.924 = 92.40 EUR."],
  ["F4","effective_date vs record_date","fact","<code>record_date</code> is the quarter the rate was published; <code>effective_date</code> is when it applies. Amendments share a record_date but carry a later effective_date."],
  ["F6","XOF vs XAF","fact","Both labelled \"Cfa Franc\" in Treasury data (BCEAO West-African vs BEAC Central-African) but carry <b>different rates</b>. A naive description match conflates them."],
  ["F8","Deterministic tiebreak","fact","If two candidates share an effectiveDate, break the tie by recordDate so selection is fully deterministic."]
];
RENDER.decisions = function(){
  var html = crossref("live")
  + '<h2>Decision log</h2>'
  + '<p class="lead">The <i>why</i> behind every rule — 13 ADRs and the verified Treasury facts that drove them. '
  + 'Full text in <a href="DECISION_LOG.md" data-doc="docs/DECISION_LOG.md">DECISION_LOG.md</a>. <span class="muted">Click any card to expand.</span></p>'
  + '<div class="dl-filter" id="dlFilter">'
    + '<button data-f="all" class="active">All</button>'
    + '<button data-f="decision">Decisions</button>'
    + '<button data-f="fact">Treasury facts</button>'
  + '</div><div id="dlList">';
  DECISIONS.forEach(function(D){
    var kind = D[2];
    var pillc = kind==="fact"?"f":"d";
    html += '<div class="dl-card" data-kind="'+kind+'">'
      + '<div class="dl-head"><span class="pill '+pillc+'">'+D[0]+'</span>'
      + '<span class="dl-title">'+D[1]+'</span></div>'
      + '<div class="dl-body">'+D[3]+'</div></div>';
  });
  html += '</div>';
  return html;
};
INIT.decisions = function(){
  el("dlList").addEventListener("click", function(e){
    var c = e.target.closest(".dl-card"); if(c) c.classList.toggle("open");
  });
  el("dlFilter").addEventListener("click", function(e){
    var b = e.target.closest("button"); if(!b) return;
    Array.prototype.forEach.call(el("dlFilter").children, function(x){x.classList.remove("active");});
    b.classList.add("active");
    var f = b.getAttribute("data-f");
    Array.prototype.forEach.call(el("dlList").children, function(card){
      var show = f==="all" || card.getAttribute("data-kind")===f;
      card.classList.toggle("hide", !show);
    });
  });
};

/* ---------- CODEBASE: Code tour ---------- */
var TOUR = [
  ["money","Money.java","money","Money — round once, HALF_UP","The single guardian of the money path. Note the multiply-then-construct: full precision, then exactly one rounding.","src/main/java/com/wex/fx/domain/money/Money.java#L42-L66"],
  ["rate","RateSelector.java","rate","RateSelector — pure rate selection","A pure function: filter to the 6-month window, take the latest effectiveDate, tiebreak by recordDate. No clock, no network.","src/main/java/com/wex/fx/domain/rate/RateSelector.java#L44-L55"],
  ["resilience","ResilientRateFetcher.java","resilience","Resilience — retry over breaker","Every upstream failure collapses to one domain signal mapped to 502 / 503 / 504. Never a hang, never a leaked 500.","src/main/java/com/wex/fx/adapter/treasury/ResilientRateFetcher.java#L20-L34"],
  ["errors","ApiExceptionHandler.java","errors","Errors — RFC 9457","400 for malformed, 422 for well-formed-but-unfulfillable. Logs carry code/traceId only — never an amount or the description.","src/main/java/com/wex/fx/adapter/web/ApiExceptionHandler.java"],
  ["csv","currency-map.csv","csv","Currency map — XOF ≠ XAF","A curated, version-controlled ISO→descriptor map. USD is intentionally absent (in-app identity).","src/main/resources/currency-map.csv#L19-L20"]
];
RENDER.tour = function(){
  var html = crossref("live")
  + '<h2>Code tour</h2>'
  + '<p class="lead">Real source, straight from the repo — the five files that carry the engineering signal.</p>'
  + '<div class="tabs" id="tourTabs">';
  TOUR.forEach(function(T,i){
    html += '<button data-i="'+i+'"'+(i===0?' class="active"':'')+'>'+T[1]+'</button>';
  });
  html += '</div><div id="tourBody"></div>';
  return html;
};
function renderTour(i){
  var T = TOUR[i];
  el("tourBody").innerHTML =
    '<h3 class="mt18">'+T[3]+'</h3>'
    + '<p class="muted">'+T[4]+'</p>'
    + codeblock(T[1], SNIP[T[2]], T[2]==="csv"?"csv":"java")
    + '<p class="provline">This is an excerpt — read the whole file: '+prov(T[5])+'</p>';
  wireCopy();
  wireDocLinks();
}
INIT.tour = function(){
  renderTour(0);
  el("tourTabs").addEventListener("click", function(e){
    var b = e.target.closest("button"); if(!b) return;
    Array.prototype.forEach.call(el("tourTabs").children, function(x){x.classList.remove("active");});
    b.classList.add("active");
    renderTour(+b.getAttribute("data-i"));
  });
};

/* ---------- CODEBASE: Rate playground ---------- */
var ARS_FIXTURE = [
  {effectiveDate:"2025-03-31", recordDate:"2025-03-31", rate:"1093"},
  {effectiveDate:"2025-04-15", recordDate:"2025-04-15", rate:"1230"},
  {effectiveDate:"2025-06-30", recordDate:"2025-06-30", rate:"1205"}
];
RENDER.playground = function(){
  return crossref("live")
  + '<h2>Rate-selection playground</h2>'
  + '<p class="lead">This is a faithful JavaScript port of <code>RateSelector.select(...)</code> running live in your '
  + 'browser. The fixture is the <b>Argentina-Peso amendment</b> case from the test suite. Move the purchase date and '
  + 'watch which rate wins — and why.</p>'
  + '<div class="banner info"><b>Why it matters:</b> Treasury issued the <code>2025-04-15 → 1230</code> row as an '
  + 'intra-quarter <i>amendment</i> with the same record_date as the quarter base. Selecting on record_date (what most '
  + 'candidates do) picks the wrong rate. Selecting on <b>effective_date</b> picks 1230. <span class="muted">(D-02, F4)</span></div>'
  + '<div class="card mt6">'
    + '<div class="ep-row">'
      + '<div class="control"><label>Purchase date</label><input type="date" id="pgDate" value="2025-05-01"></div>'
      + '<div class="control"><label>Window (months)</label><input type="number" id="pgWin" value="6" min="1" max="24" class="w90"></div>'
      + '<div class="control"><label>Amount (USD)</label><input type="text" id="pgAmt" value="100.00" class="w120"></div>'
      + '<button class="btn blue" id="pgRun">Select rate</button>'
    + '</div>'
    + '<div class="faint fs82">Currency pair: <b>USD → ARS</b> · fixture is fixed; everything else is yours to change.</div>'
  + '</div>'
  + '<p class="provline">The Java this mirrors (source of truth): '
    + prov("src/main/java/com/wex/fx/domain/rate/RateSelector.java#L44-L55","RateSelector.select(…)")
    + ' · its tests: '
    + prov("src/test/java/com/wex/fx/domain/rate/RateSelectorTest.java","RateSelectorTest")+'</p>'
  + '<div id="pgOut"></div>';
};
INIT.playground = function(){
  function run(){
    var pd = el("pgDate").value;
    var win = parseInt(el("pgWin").value,10)||6;
    var amt = el("pgAmt").value.trim();
    if(!pd){el("pgOut").innerHTML='<div class="banner warn">Pick a purchase date.</div>';return;}
    var floor = minusMonthsN(pd, win);
    // emulate select()
    var inWindow = ARS_FIXTURE.filter(function(r){
      return dcmp(r.effectiveDate, pd)<=0 && dcmp(r.effectiveDate, floor)>=0;
    });
    var chosen = null;
    inWindow.forEach(function(r){
      if(!chosen) chosen=r;
      else{
        var c = dcmp(r.effectiveDate, chosen.effectiveDate);
        if(c>0 || (c===0 && dcmp(r.recordDate, chosen.recordDate)>0)) chosen=r;
      }
    });
    // rows table
    var rows = ARS_FIXTURE.map(function(r){
      var status, badge;
      if(dcmp(r.effectiveDate, pd)>0){status="not yet effective"; badge='<span class="badge future">excluded — after purchase</span>';}
      else if(dcmp(r.effectiveDate, floor)<0){status="older than window"; badge='<span class="badge out">excluded — before floor</span>';}
      else if(chosen && r===chosen){status="selected"; badge='<span class="badge chosen">✓ SELECTED</span>';}
      else {status="in window, not latest"; badge='<span class="badge out">in window</span>';}
      return '<tr><td><code>'+r.effectiveDate+'</code></td><td><code>'+r.recordDate+'</code></td>'
        +'<td><code>'+r.rate+'</code></td><td>'+badge+'</td></tr>';
    }).join("");
    var out =
      '<div class="stepbox">purchaseDate = <b>'+pd+'</b>\n'
      +'windowFloor  = purchaseDate.minusMonths('+win+') = <b>'+floor+'</b>\n'
      +'keep rows where  floor &lt;= effectiveDate &lt;= purchaseDate ,  then max(effectiveDate, recordDate)</div>'
      +'<table class="mt14"><thead><tr><th>effective_date</th><th>record_date</th><th>rate</th><th>outcome</th></tr></thead><tbody>'
      +rows+'</tbody></table>';
    if(chosen){
      var conv;
      try{ conv = convertMoney(amt, chosen.rate).result; }catch(e){ conv = "(enter a valid ≤2dp amount)"; }
      out += '<div class="result-box good"><h4 class="m0b6">Result — 200 OK</h4>'
        +'<div>Chosen rate (effective '+chosen.effectiveDate+'): <span class="bignum c-ok">'+chosen.rate+'</span></div>'
        +'<div class="muted mt6">'+escapeHtml(amt)+' USD × '+chosen.rate+' = <b class="c-fg">'+conv+' ARS</b> '
        +'<span class="faint">(round once, HALF_UP, scale 2)</span></div></div>';
    } else {
      out += '<div class="result-box bad"><h4 class="m0b6">Result — 422 NO_RATE_AVAILABLE</h4>'
        +'<div class="muted">No Treasury rate for USD→ARS within '+win+' months on/before '+pd+' (floor '+floor+'). '
        +'The purchase is stored, but cannot be converted — R2\'s mandated error path.</div></div>';
    }
    el("pgOut").innerHTML = out;
  }
  function minusMonthsN(dateStr, n){ return minusMonths(dateStr, n); }
  el("pgRun").addEventListener("click", run);
  el("pgDate").addEventListener("change", run);
  el("pgWin").addEventListener("change", run);
  el("pgAmt").addEventListener("input", run);
  run();
};

/* ---------- CODEBASE: Money calculator ---------- */
RENDER.money = function(){
  return crossref("live")
  + '<h2>Money calculator</h2>'
  + '<p class="lead">A teaching aid that mirrors <code>Money.java</code> — <b>the Java is the source of truth</b>; '
  + 'this is a fixed-point JS re-implementation for the browser: scaled <code>BigInt</code> arithmetic '
  + '(<b>no float anywhere</b>), full-precision multiply, then a <b>single</b> HALF_UP rounding to scale 2.</p>'
  + '<div class="card">'
    + '<div class="ep-row">'
      + '<div class="control"><label>Original amount (USD, ≤2dp)</label><input type="text" id="mAmt" value="100.00" class="w150"></div>'
      + '<div class="control"><label>Exchange rate</label><input type="text" id="mRate" value="0.924" class="w150"></div>'
      + '<div class="control"><label>Target</label><input type="text" id="mCur" value="EUR" class="w90"></div>'
      + '<button class="btn blue" id="mRun">Convert</button>'
    + '</div>'
  + '</div>'
  + '<div id="mOut"></div>'
  + '<h3>The Java it mirrors</h3>'
  + codeblock("domain/money/Money.java", SNIP.money, "java")
  + '<p class="provline">Authoritative source: '
    + prov("src/main/java/com/wex/fx/domain/money/Money.java#L42-L66","Money.java")
    + ' · its tests (incl. property-based): '
    + prov("src/test/java/com/wex/fx/domain/money/MoneyTest.java","MoneyTest")
    + ' · '
    + prov("src/test/java/com/wex/fx/domain/money/MoneyPropertiesTest.java","MoneyPropertiesTest")+'</p>';
};
INIT.money = function(){
  function run(){
    var a = el("mAmt").value, r = el("mRate").value, cur = (el("mCur").value||"EUR").toUpperCase();
    var out;
    try{
      var c = convertMoney(a, r);
      out = '<div class="result-box good">'
        + '<div class="bignum c-ok">'+c.result+' '+escapeHtml(cur)+'</div>'
        + '<div class="stepbox mt10">'
        + 'principal  = '+c.principal+'            <span class="faint">(scale 2, padded — never rounded down)</span>\n'
        + 'rate       = '+escapeHtml(r)+'             <span class="faint">(scale '+c.rateScale+', NOT pre-rounded)</span>\n'
        + 'product    = '+c.productRaw+'   <span class="faint">(full precision, scale '+c.productScale+')</span>\n'
        + 'round once = <b>'+c.result+'</b>          <span class="faint">(HALF_UP -> scale 2)</span></div>'
        + '</div>';
    }catch(e){
      out = '<div class="result-box bad"><h4 class="m0b6">Rejected</h4>'
        + '<div class="muted">'+escapeHtml(e.message)+'</div>'
        + '<div class="faint mt6">The real API never silently rounds the principal — '
        + '>2dp is a <code>400 AMOUNT_PRECISION</code> (D-05).</div></div>';
    }
    el("mOut").innerHTML = out;
  }
  el("mRun").addEventListener("click", run);
  el("mAmt").addEventListener("input", run);
  el("mRate").addEventListener("input", run);
  el("mCur").addEventListener("input", run);
  run();
};

/* ---------- CODEBASE: Testing ---------- */
RENDER.testing = function(){
  return crossref("live")
  + '<h2>Testing &amp; quality gates</h2>'
  + '<p class="lead">Tests assert <b>strength</b>, not just line coverage. Deterministic by construction: an injected '
  + '<code>Clock</code> and <b>zero real network in the gating suite</b> (WireMock + Testcontainers, no H2).</p>'
  + '<div class="grid3">'
    + kpi("green","92%","PIT mutation — domain.* (≥85 gate)")
    + kpi("blue","0","real network calls in the gate")
    + kpi("red","0","H2 — prod-parity Postgres everywhere")
  + '</div>'
  + '<h3>The pyramid</h3>'
  + '<table><thead><tr><th>Layer</th><th>What &amp; how</th></tr></thead><tbody>'
    + '<tr><td><b>Unit</b></td><td>Pure-domain: <code>RateSelectorTest</code> (Argentina amendment, leap-day window floor), '
      + '<code>MoneyTest</code>, property-based (jqwik) for the money invariants. No Spring.</td></tr>'
    + '<tr><td><b>Slice</b></td><td><code>@WebMvcTest</code> for controllers / RFC 9457 errors / security headers; '
      + 'persistence slice on Testcontainers Postgres.</td></tr>'
    + '<tr><td><b>Integration / E2E</b></td><td><code>PurchaseConversionE2EIT</code> drives R1→R2 against Testcontainers '
      + 'Postgres + WireMock Treasury — including the intra-quarter amendment end-to-end.</td></tr>'
    + '<tr><td><b>Mutation</b></td><td>PIT on <code>domain.*</code> — kills mutants in the money &amp; rate logic, so the '
      + 'tests provably catch off-by-one / rounding regressions.</td></tr>'
    + '<tr><td><b>Live canary</b></td><td>Opt-in (<code>-Plive</code>), <b>non-gating</b>: hits real Treasury to lock '
      + '<code>XOF ≠ XAF</code>. A Treasury outage never looks like a test failure.</td></tr>'
  + '</tbody></table>'
  + '<div class="banner info">Run them: <code>make test</code> (fast, no network) · <code>make integration</code> '
  + '(Testcontainers + WireMock) · <code>make mutation</code> (PIT).</div>';
};

/* ---------- CODEBASE: Traceability ---------- */
RENDER.trace = function(){
  return crossref("live")
  + '<h2>Requirement traceability</h2>'
  + '<p class="lead">Every acceptance criterion maps to at least one automated test. A sample of the matrix:</p>'
  + '<table><thead><tr><th>AC</th><th>Requirement</th><th>Test</th></tr></thead><tbody>'
    + tr("AC-1.1","UUIDv7 id + exact cent scale persisted","StorePurchaseServiceTest, persistence slice")
    + tr("AC-1.4","Reject >2dp amount (not round); reject ≤0","PurchaseValidationTest")
    + tr("AC-2.2","Latest effectiveDate ≤ date incl. amendments","RateSelectorTest#argentina_amendment_…")
    + tr("AC-2.3","6-month floor, calendar-month, leap-day","RateSelectorTest#window_floor_…leap_day")
    + tr("AC-2.4","No in-window rate ⇒ 422 NO_RATE_AVAILABLE","ConvertPurchaseServiceTest, E2E")
    + tr("AC-2.5","USD identity, no upstream call","ConvertPurchaseServiceTest#usd_target_…no_provider_call")
    + tr("AC-2.6","XOF ≠ XAF; malformed ⇒ 400","CurrencyMapTest, TreasuryLiveCanaryIT")
    + tr("AC-2.8","Resilience ⇒ 502/503/504, no hang","ResilientRateFetcherTest")
  + '</tbody></table>'
  + '<p class="muted">Full matrix in <a href="builder/test-strategy.md" data-doc="docs/builder/test-strategy.md">test-strategy.md</a> · acceptance criteria in '
  + '<a href="builder/spec.md" data-doc="docs/builder/spec.md">spec.md</a>.</p>';
  function tr(a,b,c){return '<tr><td><span class="pill">'+a+'</span></td><td>'+b+'</td><td><code>'+c+'</code></td></tr>';}
};

/* ============================ LIVE APP ============================ */
function defaultBase(){
  // Served by the app over http(s) -> same origin, so the Live App tab is genuinely
  // same-origin (no CORS, and allowed by this page's connect-src 'self' CSP).
  if(location.protocol==="http:"||location.protocol==="https:") return location.origin;
  // Opened offline (file://) -> the configured fallback, else local dev.
  return DEPLOYED_URL || "http://localhost:8080";
}
function baseUrl(){
  return (localStorage.getItem("fx_base_url") || defaultBase()).replace(/\/+$/,"");
}
/* Make the repo links (docs AND source files) resolve in every context. `data-doc` holds a
   repo-root-relative path, optionally with a GitHub line anchor, e.g. "docs/DECISION_LOG.md" or
   "src/main/java/com/wex/fx/domain/rate/RateSelector.java#L44-L55". This file lives at
   src/main/resources/static/, so the repo root is four directories up. Rewrite to the repo host when
   REPO_URL is set (line anchor preserved → deep-links straight to the code); point at the on-disk file
   when opened offline (file://); and when served by the app (where the source isn't a static route)
   degrade to a hint instead of a dead 404. */
var REPO_ROOT_FROM_HERE = "../../../../"; // src/main/resources/static/ -> repo root
function wireDocLinks(){
  var served = location.protocol==="http:"||location.protocol==="https:";
  Array.prototype.forEach.call(document.querySelectorAll('a[data-doc]'), function(a){
    if(a._docWired) return; a._docWired = true;
    var path = a.getAttribute("data-doc");
    var hash = path.indexOf("#");
    var file = hash>=0 ? path.slice(0,hash) : path;   // path without the #Lxx anchor
    var frag = hash>=0 ? path.slice(hash) : "";        // "#L44-L55" or ""
    if(REPO_URL){
      a.setAttribute("href", REPO_URL.replace(/\/+$/,"")+"/blob/main/"+file+frag);
      a.setAttribute("target","_blank"); a.setAttribute("rel","noopener");
    } else if(served){
      a.setAttribute("href","#"); a.setAttribute("title", file+" — in the repository");
      a.addEventListener("click", function(e){ e.preventDefault(); toast("See "+file+" in the repository"); });
    } else {
      a.setAttribute("href", REPO_ROOT_FROM_HERE + file + frag); // file:// -> resolve up to the repo root
    }
  });
}
/* A consistent "provenance" link: from a claim/snippet in the page straight to the file it came from.
   Uses the data-doc plumbing above, so it deep-links on GitHub (REPO_URL) and still resolves offline. */
function prov(path, label){
  var file = path.split("#")[0];
  var name = label || file.split("/").pop();
  return '<a class="prov" data-doc="'+escapeHtml(path)+'" title="View '+escapeHtml(file)+' in the repository">'
    + '<span class="ic">&lt;/&gt;</span> '+escapeHtml(name)+'</a>';
}
RENDER.connect = function(){
  var bu = baseUrl();
  var served = location.protocol==="http:"||location.protocol==="https:";
  var deployedNote = served
    ? 'You\'re on the <b>live app</b> at <code>'+escapeHtml(location.origin)+'</code>. The <b>Live App</b> tab talks to it '
      + '<b>same-origin</b> — nothing to set up, the <b>Send</b> buttons just work.'
    : 'Opened offline (<code>file://</code>) — requests target the live service at <code>'+escapeHtml(defaultBase())+'</code>. '
      + 'For the genuine same-origin experience, just open <code>'+escapeHtml(defaultBase())+'</code> in your browser.';
  return crossref("codebase")
  + '<h2>Live service</h2>'
  + '<p class="lead">Nothing to connect — the explorer targets the running service automatically.</p>'
  + '<div class="banner info">'+deployedNote+'</div>'
  + '<div class="card">'
    + '<button class="btn primary" id="cPing">Check service health</button>'
    + '<div id="cOut" class="mt8"></div>'
  + '</div>'
  + '<p class="muted mt6"><b>Cold start:</b> on the free tier the first request after ~15 min idle takes ~1 min while the '
  + 'instance wakes — a hosting trait, not an app warm-up cost.</p>'
  + '<details class="mt8"><summary class="muted">Advanced — point at a different instance</summary>'
    + '<div class="card mt8"><div class="control"><label>Base URL</label>'
    + '<div class="ep-row"><input type="text" id="cBase" value="'+escapeHtml(bu)+'" class="grow260">'
    + '<button class="btn" id="cSave">Save</button></div></div>'
    + '<p class="muted mt6">Remembered in this browser. Pointing at a <i>different</i> origin is a best-effort '
    + '<code>fetch</code> the browser may block (CORS / <code>connect-src \'self\'</code> CSP) — copy the curl instead.</p>'
  + '</details>'
  + '<p class="muted mt8">Head to the <a data-goto="api">API playground →</a></p>';
};
INIT.connect = function(){
  el("cSave").addEventListener("click", function(){
    var v = el("cBase").value.trim().replace(/\/+$/,"");
    localStorage.setItem("fx_base_url", v); toast("Base URL saved");
  });
  el("cPing").addEventListener("click", function(){
    var v = el("cBase").value.trim().replace(/\/+$/,"");
    localStorage.setItem("fx_base_url", v);
    el("cOut").innerHTML = '<div class="stepbox">GET '+escapeHtml(v)+'/actuator/health …</div>';
    fetch(v+"/actuator/health",{headers:{Accept:"application/json"}}).then(function(r){
      return r.text().then(function(t){
        el("cOut").innerHTML = '<div class="result-box '+(r.ok?"good":"bad")+'">'
          + '<b class="'+(r.ok?"c-ok":"c-bad")+'">HTTP '+r.status+'</b>'
          + codeblock("response", t || "(empty)", "json", true)+'</div>';
        wireCopy();
      });
    }).catch(function(e){
      el("cOut").innerHTML = '<div class="result-box bad"><b class="c-bad">Request blocked / failed</b>'
        + '<div class="muted mt6">'+escapeHtml(String(e))+' — likely CORS or the service is asleep/down. '
        + 'Copy the curl from the API playground instead.</div></div>';
    });
  });
};

/* ---------- LIVE APP: API playground ---------- */
var ENDPOINTS = [
  {
    id:"create", method:"POST", badge:"post", path:"/v1/purchases",
    desc:"R1 — store a purchase",
    fields:[
      ["description","text","Office supplies"],
      ["transactionDate","text","2025-04-15"],
      ["amount","text","100.00"]
    ],
    build:function(v){
      var body = JSON.stringify({description:v.description,transactionDate:v.transactionDate,amount:v.amount});
      return {
        method:"POST", url:baseUrl()+"/v1/purchases",
        headers:{"Content-Type":"application/json","Idempotency-Key":"demo-001"},
        body:body,
        curl:'curl -sS -X POST "'+baseUrl()+'/v1/purchases" '
          +'-H "Content-Type: application/json" -H "Idempotency-Key: demo-001" '
          +"-d '"+body+"'"
      };
    },
    example:EX.create201, exampleStatus:"201 Created"
  },
  {
    id:"get", method:"GET", badge:"get", path:"/v1/purchases/{id}",
    desc:"read a stored purchase",
    fields:[["id","text","019e93ff-fbad-7d54-aec5-948f732030b0"]],
    build:function(v){
      var url = baseUrl()+"/v1/purchases/"+encodeURIComponent(v.id);
      return {method:"GET", url:url, headers:{Accept:"application/json"},
        curl:'curl -sS "'+url+'"'};
    },
    example:EX.create201, exampleStatus:"200 OK"
  },
  {
    id:"convert", method:"GET", badge:"get", path:"/v1/purchases/{id}/conversions/{code}",
    desc:"R2 — convert to a target currency",
    fields:[
      ["id","text","019e93ff-fbad-7d54-aec5-948f732030b0"],
      ["code","text","EUR"]
    ],
    build:function(v){
      var url = baseUrl()+"/v1/purchases/"+encodeURIComponent(v.id)+"/conversions/"+encodeURIComponent(v.code);
      return {method:"GET", url:url, headers:{Accept:"application/json"},
        curl:'curl -sS "'+url+'"'};
    },
    example:EX.eur200, exampleStatus:"200 OK"
  }
];
RENDER.api = function(){
  var html = crossref("codebase")
  + '<h2>API playground</h2>'
  + '<p class="lead">Build a request, copy a working <b>curl</b>, or hit <b>Send</b> for a best-effort live call. '
  + 'Every endpoint also shows a <b>real captured response</b>.</p>'
  + '<div class="faint mb6">Targeting <code id="apiBase">'+escapeHtml(baseUrl())+'</code> '
  + '(automatic) — override under <a data-goto="connect">Live service</a>.</div>';
  ENDPOINTS.forEach(function(ep,i){
    html += '<div class="endpoint'+(i===0?" open":"")+'" id="ep_'+ep.id+'">'
      + '<div class="ep-head" data-toggle="'+ep.id+'">'
        + '<span class="badge method '+ep.badge+'">'+ep.method+'</span>'
        + '<span class="path">'+escapeHtml(ep.path)+'</span>'
        + '<span class="desc">'+ep.desc+'</span></div>'
      + '<div class="ep-body">'
        + '<div class="ep-row">';
    ep.fields.forEach(function(f){
      html += '<div class="control"><label>'+f[0]+'</label>'
        + '<input type="'+f[1]+'" data-field="'+ep.id+":"+f[0]+'" value="'+escapeHtml(f[2])+'" class="mw170"></div>';
    });
    html += '<button class="btn" data-curl="'+ep.id+'">Copy curl</button>'
      + '<button class="btn primary" data-send="'+ep.id+'">Send (live)</button>'
      + '<button class="btn" data-ex="'+ep.id+'">Show example</button>'
      + '</div><div id="epout_'+ep.id+'"></div></div></div>';
  });
  html += '<div class="banner warn">Tip: run <code>POST</code> first, copy the returned <code>id</code> into the '
    + '<code>GET</code> and <code>convert</code> fields. The example <code>id</code> is from a real prior run.</div>';
  return html;
};
INIT.api = function(){
  var root = el("main");
  function vals(epId){
    var ep = ENDPOINTS.filter(function(e){return e.id===epId;})[0];
    var v = {};
    ep.fields.forEach(function(f){
      var inp = root.querySelector('[data-field="'+epId+":"+f[0]+'"]');
      v[f[0]] = inp ? inp.value.trim() : f[2];
    });
    return {ep:ep, v:v};
  }
  root.addEventListener("click", function(e){
    var t = e.target.closest("[data-toggle],[data-curl],[data-send],[data-ex]");
    if(!t) return;
    if(t.hasAttribute("data-toggle")){
      el("ep_"+t.getAttribute("data-toggle")).classList.toggle("open"); return;
    }
    if(t.hasAttribute("data-curl")){
      var b = vals(t.getAttribute("data-curl")); copy(b.ep.build(b.v).curl); return;
    }
    if(t.hasAttribute("data-ex")){
      var x = ENDPOINTS.filter(function(e2){return e2.id===t.getAttribute("data-ex");})[0];
      el("epout_"+x.id).innerHTML = '<div class="result-box good"><h4 class="m0b6">Example — '
        + x.exampleStatus+'</h4>'+codeblock("response.json", x.example, "json")+'</div>';
      wireCopy(); return;
    }
    if(t.hasAttribute("data-send")){
      var id = t.getAttribute("data-send"); var bv = vals(id); var req = bv.ep.build(bv.v);
      var out = el("epout_"+id);
      out.innerHTML = '<div class="stepbox">'+req.method+' '+escapeHtml(req.url)+' …</div>';
      var opt = {method:req.method, headers:req.headers};
      if(req.body) opt.body = req.body;
      fetch(req.url, opt).then(function(r){
        return r.text().then(function(txt){
          var pretty = txt; try{pretty = JSON.stringify(JSON.parse(txt),null,2);}catch(_){}
          out.innerHTML = '<div class="result-box '+(r.ok?"good":"bad")+'"><h4 class="m0b6">'
            + 'HTTP '+r.status+'</h4>'+codeblock("response", pretty||"(empty)", "json", true)+'</div>';
          wireCopy();
        });
      }).catch(function(err){
        out.innerHTML = '<div class="result-box bad"><h4 class="m0b6">Request blocked / failed</h4>'
          + '<div class="muted mt4">'+escapeHtml(String(err))+' — likely CORS, or the service is '
          + 'asleep/unreachable. Copy the curl above; it always works.</div></div>';
      });
    }
  });
};

/* ---------- LIVE APP: Error catalog ---------- */
var ERRORS = [
  ["400","VALIDATION","Field validation failed (errors[] details)."],
  ["400","MALFORMED_REQUEST","Body unparseable / wrong shape."],
  ["400","AMOUNT_PRECISION","Amount has &gt;2 decimals — rejected, never rounded."],
  ["400","AMOUNT_NOT_POSITIVE","Amount is zero or negative."],
  ["400","DATE_IN_FUTURE","transactionDate is after today."],
  ["400","CURRENCY_CODE_MALFORMED","Target code is not ^[A-Z]{3}$."],
  ["404","PURCHASE_NOT_FOUND","No purchase with that id."],
  ["409","IDEMPOTENCY_CONFLICT","Same Idempotency-Key, different body."],
  ["422","CURRENCY_NOT_STORABLE","Non-USD currency on store (only USD is stored)."],
  ["422","CURRENCY_UNSUPPORTED","ISO-valid but not in the curated map."],
  ["422","NO_RATE_AVAILABLE","No Treasury rate in the 6-month window — R2's mandated path."],
  ["502","UPSTREAM_BAD_GATEWAY","Treasury returned 5xx / unusable response."],
  ["503","UPSTREAM_UNAVAILABLE","Circuit open — includes Retry-After: 30."],
  ["504","UPSTREAM_TIMEOUT","Treasury timed out."],
  ["500","INTERNAL","Unexpected — no internals leaked to the client."]
];
RENDER.errors = function(){
  var rows = ERRORS.map(function(e){
    var cls = e[0][0]==="4"?"warn":(e[0][0]==="5"?"bad":"");
    var badge = '<span class="pill '+(e[0]==="422"?"warn":(e[0][0]==="5"?"":""))+'">'+e[0]+'</span>';
    return '<tr><td>'+badge+'</td><td><code>'+e[1]+'</code></td><td>'+e[2]+'</td></tr>';
  }).join("");
  return crossref("codebase")
  + '<h2>Error catalog</h2>'
  + '<p class="lead">Every error is RFC 9457 <code>application/problem+json</code> with a stable machine '
  + '<code>code</code> and a <code>traceId</code>. <b>400</b> = malformed/invalid; <b>422</b> = well-formed but '
  + 'unfulfillable; <b>5xx</b> = mapped upstream failure (never a leaked stack trace).</p>'
  + '<table><thead><tr><th>Status</th><th>code</th><th>Meaning</th></tr></thead><tbody>'+rows+'</tbody></table>'
  + '<h3>Shape of a problem response</h3>'
  + codeblock("422 — no rate in window", EX.err422, "json")
  + codeblock("400 — malformed currency", EX.err400, "json")
  + '<p class="muted">Handler source: see <a data-goto="tour" data-ctx="codebase">ApiExceptionHandler in the Code tour</a>.</p>';
};

/* ---------- LIVE APP: Example flow ---------- */
RENDER.flow = function(){
  return crossref("codebase")
  + '<h2>Example flow — store, then convert</h2>'
  + '<p class="lead">The canonical R1 → R2 round-trip, captured from a real run against live Postgres + live Treasury.</p>'
  + '<h3>1 · Store the purchase <span class="badge method post">POST</span></h3>'
  + codeblock("request", 'curl -sS -X POST "'+baseUrl()+'/v1/purchases" '
      +'-H "Content-Type: application/json" -H "Idempotency-Key: demo-001" '
      +'-d \'{"description":"Office supplies","transactionDate":"2025-04-15","amount":"100.00"}\'', "bash", true)
  + codeblock("201 Created", EX.create201, "json")
  + '<h3>2 · Convert to EUR <span class="badge method get">GET</span></h3>'
  + codeblock("request", 'curl -sS "'+baseUrl()+'/v1/purchases/019e93ff-fbad-7d54-aec5-948f732030b0/conversions/EUR"', "bash", true)
  + codeblock("200 OK", EX.eur200, "json")
  + '<div class="banner info"><b>100.00 USD × 0.924 = 92.40 EUR.</b> The rate <code>0.924</code> is the one with the '
  + 'greatest <code>effective_date ≤ 2025-04-15</code> (here <code>2025-03-31</code>) within 6 months. Try the math '
  + 'yourself in the <a data-goto="money" data-ctx="codebase">Money calculator</a> or the '
  + '<a data-goto="playground" data-ctx="codebase">Rate playground</a>.</div>'
  + '<h3>3 · USD is an identity <span class="badge method get">GET</span></h3>'
  + codeblock("request", 'curl -sS "'+baseUrl()+'/v1/purchases/019e93ff-fbad-7d54-aec5-948f732030b0/conversions/USD"', "bash", true)
  + codeblock("200 OK — no upstream call", EX.usd200, "json");
};

/* ===================== shared UI builders ===================== */
function crossref(otherCtx){
  if(otherCtx==="live"){
    return '<div class="crossref">◉ <span>You\'re exploring the <b>codebase</b>. Want to poke the running service? '
      + 'Switch to <a data-ctx="live">Live App</a> — fire real requests at the running service.</span></div>';
  }
  return '<div class="crossref">&lt;/&gt; <span>You\'re in the <b>Live App</b>. Curious how it works inside? '
    + 'Switch to <a data-ctx="codebase">Codebase</a> — architecture, decisions, real source and live playgrounds.</span></div>';
}
function kpi(color,big,label){
  return '<div class="card"><div class="kpi '+color+'">'+big+'</div><div class="muted fs85">'+label+'</div></div>';
}
function navcard(id,title,desc){
  return '<div class="card hl clickable" data-goto="'+id+'"><h4 class="c-fg">'+title+'</h4>'
    + '<p class="muted m3em">'+desc+'</p></div>';
}

/* ===================== copy wiring ===================== */
function wireCopy(){
  Array.prototype.forEach.call(document.querySelectorAll(".copybtn[data-copy]"), function(b){
    if(b._wired) return; b._wired=true;
    b.addEventListener("click", function(){
      var pre = el(b.getAttribute("data-copy")); if(pre) copy(pre.textContent);
    });
  });
}

/* ===================== router ===================== */
function setContext(ctx){
  if(ctx===state.ctx) return;
  state.ctx = ctx;
  state.id = SECTIONS[ctx][0].id;
  document.body.className = "ctx-"+ctx;
  Array.prototype.forEach.call(document.querySelectorAll(".toggle button"), function(b){
    b.classList.toggle("active", b.getAttribute("data-ctx")===ctx);
  });
  el("sideLabel").textContent = ctx==="codebase" ? "Explore the code" : "Explore the live app";
  renderNav(); renderSection(); syncHash();
}
function go(id, ctx){
  if(ctx && ctx!==state.ctx){ setContext(ctx); }
  // ensure id belongs to current context
  var ok = SECTIONS[state.ctx].some(function(s){return s.id===id;});
  if(!ok) return;
  state.id = id; renderNav(); renderSection(); syncHash();
  window.scrollTo({top:0,behavior:"smooth"});
}
function renderNav(){
  var html = "";
  SECTIONS[state.ctx].forEach(function(s){
    html += '<a data-goto="'+s.id+'"'+(s.id===state.id?' class="active"':'')+'>'
      + '<span class="ic">'+s.ic+'</span> '+s.label+'</a>';
  });
  el("nav").innerHTML = html;
}
function renderSection(){
  var fn = RENDER[state.id];
  el("main").innerHTML = fn ? fn() : '<h2>Not found</h2>';
  if(INIT[state.id]) INIT[state.id]();
  wireCopy();
  wireDocLinks();
}
function syncHash(){ history.replaceState(null,"","#"+state.ctx+"/"+state.id); }

/* delegated navigation: data-goto (+ optional data-ctx) and data-ctx links */
document.addEventListener("click", function(e){
  var ctxBtn = e.target.closest(".toggle button[data-ctx]");
  if(ctxBtn){ setContext(ctxBtn.getAttribute("data-ctx")); return; }
  var goto = e.target.closest("[data-goto]");
  if(goto){ e.preventDefault(); go(goto.getAttribute("data-goto"), goto.getAttribute("data-ctx")); return; }
  var ctxLink = e.target.closest("a[data-ctx]");
  if(ctxLink){ e.preventDefault(); setContext(ctxLink.getAttribute("data-ctx")); return; }
});

/* ===================== boot ===================== */
(function boot(){
  var h = (location.hash||"").replace(/^#/,"").split("/");
  if(h.length===2 && SECTIONS[h[0]] && SECTIONS[h[0]].some(function(s){return s.id===h[1];})){
    state.ctx = h[0]; state.id = h[1];
  }
  document.body.className = "ctx-"+state.ctx;
  Array.prototype.forEach.call(document.querySelectorAll(".toggle button"), function(b){
    b.classList.toggle("active", b.getAttribute("data-ctx")===state.ctx);
  });
  el("sideLabel").textContent = state.ctx==="codebase" ? "Explore the code" : "Explore the live app";
  renderNav(); renderSection();
})();
