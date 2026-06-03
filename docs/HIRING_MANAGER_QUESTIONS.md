# Clarifying questions — hiring manager

> The brief invites questions; asking precise ones is part of the deliverable. This is the draft sent
> to the hiring manager. **Each question carries the default we build to if we don't hear back**, so
> the build is never blocked — every default is restated in the submission's *Assumptions* section and
> remains overridable. Resolutions (our committed defaults) are tracked in `DECISION_LOG.md` §3.

---

**Subject:** Clarifying questions — take-home (purchase ledger + currency conversion)

Hi [Hiring Manager],

Thanks again for the exercise — I've enjoyed digging into it. I've designed the service and planned the
build, and I have a short list of clarifications. **None are blockers:** for each I've noted the
assumption I'll build to if I don't hear back, so I'll keep moving regardless. I'd just rather confirm
the few that materially shape the design.

**Questions that shape the design**

1. **Target-currency input contract.** Should the conversion endpoint accept **ISO-4217 codes**
   (e.g. `EUR`, `CAD`), with the service owning the mapping to Treasury's country-coupled descriptors —
   or the raw Treasury `country_currency_desc`? And for the Euro, is the **zone-wide** rate
   (`Euro Zone-Euro`) the one you intend?
   *Default:* ISO-4217 in, backed by a curated, version-controlled map; `EUR → Euro Zone-Euro`.

2. **Which rate date governs selection.** Treasury publishes both a `record_date` (quarter-end) and an
   `effective_date`. They usually match — but I found real cases where they don't: Treasury issues
   **intra-quarter amendments** with a new `effective_date` when a currency moves sharply (e.g. the
   Argentine peso across Q1–Q2 2025). Selecting by `record_date` would apply a stale rate to a
   transaction dated after an amendment. Do you intend selection by **`effective_date`** (correct under
   amendments) or `record_date` (simpler)?
   *Default:* `effective_date`, with the 6-month window measured on the same field.

3. **Expected scale / read pattern.** Roughly what conversion volume and latency profile should I size
   for? It's the main input to the rate-fetching strategy.
   *Default:* low/moderate volume, served by on-demand fetch + cache — placed behind a port so switching
   to a local-ingest model is a one-line config change if you expect a hot, high-RPS path.

**Quick confirmations (sensible defaults, easy to override)**

4. **Amount precision.** The brief says amounts are "rounded to the nearest cent." If a client submits
   more than two decimal places, should the API **reject** or **round**? (I never round the stored
   principal — only the derived converted amount.)
   *Default:* reject with a `400` — don't silently mutate the principal.

5. **Future-dated transactions.** Should a purchase with a future date be storable?
   *Default:* reject future dates at store time; accept old dates (they simply fail conversion if no
   rate exists within six months).

6. **`USD` as a target currency.** Is it a valid conversion target? Treasury has no USD-per-USD row.
   *Default:* treat `USD` as an identity conversion (rate `1.00`), with no upstream call.

7. **Auth & tenancy.** Are authentication/authorization and multi-tenancy in scope, or should I assume
   an upstream gateway handles them?
   *Default:* assume an upstream gateway — I'll leave the seam but not implement auth.

I've kept a short decision log capturing the reasoning and trade-offs behind each of these — happy to
share it, or to talk any of them through live. Otherwise I'll proceed on the defaults above.

Thanks,
[Your name]
