# Everykill ingest — everything the plugin needs

**From:** Gage (website/backend lane)
**To:** Tyler (plugin lane)
**Date:** 2026-08-25

One document, replacing four notes. Every response below is **pasted from a real
request against the running server**, not written from memory.

Earlier notes, kept for the reasoning rather than the facts:
`from-gage-ingest-live.md`, `from-gage-systemic-rejects.md`,
`from-gage-leaderboard-names.md`. Where they disagree with this, this wins.

---

## 1. Run it

```bash
cd api
EVERYKILL_SALT=devsalt EVERYKILL_DB=:memory: PORT=8790 node src/server.js
```

No install, no wrangler, no container — `node:sqlite` ships with node 24.
`EVERYKILL_DB=everykill.db` if you want it to survive a restart.

Point `uploadUrl` at `http://127.0.0.1:8790`.

**If it exits instantly with `EADDRINUSE`, something already owns 8790.** This
has bitten me three times and each time I suspected my own code first — kill the
squatter rather than debugging:

```bash
netstat -ano | grep ":8790" | grep LISTENING     # last column is the PID
taskkill /PID <pid> /F
```

## 2. Endpoints

| | | |
|---|---|---|
| `GET` | `/v1/health` | totals + reference-table coverage. No auth. |
| `POST` | `/v1/register` | client id → token. No auth. |
| `POST` | `/v1/kills` | the batch. Bearer token. |
| `GET` | `/v1/me` | export everything (GDPR 15/20). Bearer token. |
| `DELETE` | `/v1/me` | erase everything (GDPR 17). Bearer token. |

### `stored` is on every response, and it never lies

```json
{ "stored": false, "error": "unauthorized", "detail": "Authorization: Bearer *** required. Call POST /v1/register first." }
```

`stored: true` means something was written to the database. **Errors always say
`stored: false`.** They used to say `true` — a bug I found while writing this
doc, in the one field you're most likely to branch on. Fixed and tested.

## 3. Register

```json
{ "stored": true,
  "token": "ek_b03868b32fe94460c68c6efedda050fdbb7cc4c6c3f92675",
  "recoveryCode": "3SGP-D3M1-4DZ4-GC2S",
  "returning": false,
  "note": "Store the recovery code. It is shown once and it is the only way back to this account." }
```

Send the same `clientId` again and it's idempotent — you get a fresh working
token, and **`recoveryCode` is `null`**:

```json
{ "stored": true,
  "token": "ek_588d9977a0c329445ba6f460e0acf2c4ed1ed181cce6c00a",
  "recoveryCode": null,
  "returning": true,
  "note": "Known client id. New token issued; the original recovery code still applies." }
```

That's deliberate. Minting a new recovery code on every register would make it
worthless as a recovery secret. Old tokens keep working.

Your first-run behaviour is right and I'd keep it: nothing on disk until a token
comes back, so a failed registration can't burn a client id.

## 4. Kills

`POST /v1/kills`, JSON array, bearer token. Cap **200 records per request**
(`413` over that, nothing processed) and **2MB body**. Your 50 on a 2–5 minute
interval is comfortably inside both.

Real response, three kills, all `lootConfidence: none`:

```json
{ "stored": true, "accepted": 3, "duplicate": 0, "rejected": 0,
  "results": [
    { "eventId": "d1", "status": "accepted",
      "dryness": { "countsAsDry": false, "inDenominator": false, "reason": "missed_event" } },
    { "eventId": "d2", "status": "accepted",
      "dryness": { "countsAsDry": true, "inDenominator": true, "reason": "monster_is_lootless" } },
    { "eventId": "d3", "status": "accepted",
      "dryness": { "countsAsDry": false, "inDenominator": false, "reason": "npc_not_in_reference_table" } }
  ] }
```

Same input, three different verdicts: `1265` Ram (always drops bones, so no loot
means we missed the event), `9021` Crystalline Hunllef (genuinely lootless — a
**real** dry kill), `2192` Cyclops (no wiki row, excluded from the denominator).

That's the whole reason the table is server-side. It resolves 2,663 of 4,124 npc
ids; the other 1,326 have no wiki row and are excluded rather than guessed at.

### Every record gets a terminal verdict

`accepted`, `duplicate` or `rejected`. **Ack all of them**, including
rejections — that's what keeps your sequential `PendingKills.acknowledge()`
from wedging on a poison record.

Resending an already-stored `eventId`:

```json
{ "stored": true, "accepted": 0, "duplicate": 1, "rejected": 0,
  "results": [ { "eventId": "d1", "status": "duplicate", "dryness": { … } } ] }
```

Dedupe is a `UNIQUE (account_id, event_id)` constraint, not a lookup, so two
retries racing each other can't both write.

## 5. `systemic` — the one exception to "drop rejections"

Your `UNCONTESTED` bug found a hole in my contract. Terminal rejections are right
for one malformed kill and catastrophic for a client-wide fault: every record
rejected, every record dropped, the whole history gone, inside a `200`.

So when **every** record in a batch of 2+ fails for the **identical** reason:

```json
{ "stored": true, "accepted": 0, "duplicate": 0, "rejected": 2,
  "results": [
    { "eventId": "s1", "status": "rejected", "reason": "grade 'UNCONTESTED' is not a known grade" },
    { "eventId": "s2", "status": "rejected", "reason": "grade 'UNCONTESTED' is not a known grade" }
  ],
  "systemic": {
    "reason": "grade 'UNCONTESTED' is not a known grade",
    "count": 2,
    "detail": "Every record in this batch was rejected for the same reason. That is a client fault, not bad data. STOP UPLOADING and keep these records queued — do not drop them as ordinary rejections, or the entire history drains into nothing. Fix the fault and resend." } }
```

**When `systemic` is present, invert the rule: keep the records queued, stop
uploading, and surface it in the panel.** A player whose uploads have silently
halted deserves to know.

Narrow on purpose — one good record suppresses it, two different reasons suppress
it, a single-record batch never trips it.

**The server is deliberately strict about case.** `grade` and `lootConfidence`
lowercase, `signal` uppercase. Lenient parsing would have hidden your enum bug
instead of surfacing it in one request.

## 6. Rate limit

5 requests per rolling 60 seconds, per account.

```json
{ "stored": false, "error": "rate_limited", "detail": "Over the limit. Retry in 60s.", "retryAfter": 60 }
```

`429` with `retryAfter` in seconds. Retry only on `429`, `5xx` and transport
errors — everything else is a per-record verdict inside a `200`.

## 7. What I need from you

### a. Export and delete buttons

Both are built and tested; the plugin needs UI for them.

- `GET /v1/me` — every kill, every drop, account timestamps, as JSON. Deliberately
  excludes all credential hashes: a subject access request must not double as a
  way to lift someone's login off a stolen token.
- `DELETE /v1/me` — account, kills, drops and tokens in one transaction.
  Irreversible, no tombstone kept.

The published policy at **everykill.com/privacy** already promises these work
from inside the plugin. Right now that's the only sentence on the page describing
something that doesn't exist yet.

### b. Consent text matching the policy

Two separate consents, never bundled:

| | what it does | default |
|---|---|---|
| **Upload** | sends kills, pseudonymously. No name, ever. | **off** |
| **Publish** | attaches display name to public entries | **off**, requires upload |

The policy says publish is a *"second, separate opt-in"* and that upload alone
never sends the RSN. Whatever the config UI says has to match that, or the policy
becomes false the day it ships.

### c. `getUsername()` must never be sent — and now I know why

Your `for-gage-display-names.md` is the single most useful thing anyone's sent me
on this. **On a Jagex account `client.getUsername()` returns the email address.**

The published policy states, in writing, that we hold **no email address**. If
that value ever reached the server, the policy would be false the moment it
arrived — not eventually, immediately.

`getLocalPlayer().getName()`, read at publish time, never stored. Agreed on all
three of your rules.

I've added `email` to the schema guard: `rights.test.js` fails the build if a
column with that name appears. Your side is the half I can't enforce.

## 8. Answers to your two questions

**Unpublished accounts hold a visible numbered rank — agreed, and your reasoning
beat mine.** I was weighing tidiness; you pointed out that hiding them makes every
published rank a lie, and that "#3 of 12" reading as top-quartile when it's 150th
is the same sin as counting a missed loot event as a dry kill. That's the
product's whole pitch. `#4 — unpublished` it is.

**Display name = `getLocalPlayer().getName()`.** Confirmed, and your note that
`ConfigManager` already splits it exactly this way — name for humans, hash for the
database — settles it.

On renames: agreed and already designed for. Names are a mutable label on a stable
account id, never a key. Worth stating the sharper version of *why*, because it's
worse than a rename: **Jagex releases inactive players' names to other people.**
So the same string points at different humans over time. Key a row on the name and
someone eventually inherits a stranger's 10,000 gargoyle kills — a data-integrity
bug and a privacy breach in one move, in public.

Also: **don't send `getAccountHash()` either.** It's stable and it's tempting, but
it's Jagex-issued and readable by every other plugin, which makes it a
cross-plugin correlation key. Our random client id does the same job without that
property. `rights.test.js` fails the build if `account_hash` appears in the schema.

## 9. Status

**Mine, done:** validation, dryness resolution, storage, dedupe, tokens, rate
limiting, GDPR export/erasure, three-year retention enforced by a scheduled
pruner. 139 tests. Site and privacy policy live.

**Mine, not done:** not deployed to `api.everykill.com`. Blocked on hosting, not
code — an endpoint that answers `accepted` and drops the data is worse than no
endpoint, because the client stops queueing.

**Yours:** the three items in section 7.

— Gage
