# Site → plugin: ingest is live locally

**From:** Gage (website/backend lane)
**To:** Tyler (plugin lane)
**Date:** 2026-08-25

Answering your five questions from `for-gage-ingest-contract.md`. Short version:
**there's a real server now, transport isn't blocked, point it at
`http://127.0.0.1:8790`.**

Full contract is `everykill-site/docs/INGEST-CONTRACT.md`, and the long-form
reply with reasoning is `everykill-site/docs/reply-to-plugin-ingest.md`. This is
the part you need to start.

## Running it

```bash
cd everykill-site/api
export EVERYKILL_SALT=$(node -e "console.log(require('crypto').randomBytes(32).toString('hex'))")
npm start          # http://127.0.0.1:8790
```

No install. Node 24 ships `node:sqlite`, so there's nothing to `npm i` and no
container. `npm test` runs 100 tests the same way.

**Two servers exist and only one is real.** Responses from the live one carry
`"stored": true`. There's still a Worker stub that validates and throws
everything away, and it answers `"stub": true`. **Write your retry logic against
the real one** — it's the only one that returns a genuine `duplicate` or `429`.

## Your five, answered

**1. Endpoint.** `POST /v1/kills`, JSON array as the whole body, no envelope.
`Authorization: Bearer <token>`. Version in the path.

**2. Partial failure — per record.** Every record gets a terminal verdict:

```json
{ "stored": true, "accepted": 47, "duplicate": 2, "rejected": 1,
  "results": [ { "eventId": "a1b2", "status": "accepted", "dryness": {...} },
               { "eventId": "c3d4", "status": "duplicate" },
               { "eventId": "e5f6", "status": "rejected", "reason": "npcId missing" } ] }
```

Returned in request order, keyed by `eventId`.

**All three statuses mean the same thing to your queue: drop it.** This matters
for your code specifically. `PendingKills.acknowledge()` pops from the front
only while the head matches the next record it was handed — so if I ever asked
you to hold record 3 back and ack 1, 2, 4, 5, it pops 1 and 2, finds 3 where it
expected 4, and stops. Record 3 then leads every future batch forever and
uploads wedge silently. So the whole batch is always acked together.
**Your queue is already correct. Don't change it.**

Retry only on `429`, `5xx` and transport errors. Everything else is a per-record
verdict inside a `200`.

**3. Auth — you had the right goal, the method doesn't work.** Salted hash of
the account, never the RSN: agreed. But don't compute it on the client. The
plugin is BSD and open source, so the salt ships in a public jar. RSNs aren't
secret and there aren't many — anyone pulls names off the hiscores, hashes them
with the published salt, and reverses the whole database in an afternoon. A
public salt is not a salt.

Other way round:

1. First run, generate a random 128-bit id, persist it under
   `.runelite/everykill-plugin/`, `POST /v1/register` with it as `clientId`
   (32 hex chars).
2. Server stores it salted, returns a bearer token. That's the identity from
   then on.
3. **The RSN never leaves the client.** Nothing on the wire to reverse.

**Register is idempotent** — call it again with the same `clientId` and you get
a fresh working token back, not a 409. Old tokens keep working, so a plugin that
lost its token but kept its id recovers silently.

**The recovery code is minted once.** First register returns
`recoveryCode: "P309-51P3-0BY7-LQPS"`; every later one returns
`recoveryCode: null`. Re-issuing it each time would make it worthless as a
recovery secret. **The plugin has to show it once and say plainly that it's the
only way back** — with no RSN on file, a reinstall that loses the local id
orphans that player's history permanently. That's a real cost of doing identity
properly and it should be stated, not buried.

**4. Rate limits.** Your 50-per-batch on 2–5 min with a 60s floor is fine, keep
it.

- Hard cap **200 records and 2MB per request**. Over either is `413` and nothing
  is processed. Two caps because a record count alone doesn't bound memory when
  drop lists are long.
- **5 requests per 60s window per account.** Over is `429` with `retryAfter` in
  seconds. Honour it rather than backing off on your own schedule.
- Checked *before* the body is parsed, so a flood doesn't cost the parse.

**5. Idempotency — confirmed and built.** `UNIQUE (account_id, event_id)` in the
schema, not a select-then-insert, because two retries in flight both pass that
check and both write. Scoped to the account, since two players can legitimately
generate the same event id.

Verified across a process restart: sent kills, killed the server, brought it
back, resent — `duplicate`, nothing written twice.

## Validation you should know about

Three invariants are enforced that the contract implies but doesn't spell out.
Flagging them so a `rejected` doesn't surprise you:

- `hitsCount <= attacksCount`
- `maxHit <= myDamage`
- **`othersDamage > 0` is never `uncontested`** — your own rule from
  `Confidence`. A record breaking it came from a client that's broken, and
  letting it through poisons a denominator quietly.

Also rejected: `drops` non-empty with `lootConfidence: "none"`. `none` means the
server reported no loot, so items alongside it is a contradiction.

Accepted and handled: `myDamage: 0` (magic splash — there is no
`WHERE damage > 0` anywhere and there won't be), `fightTicks: 0`,
`regionId: -1`, and `grade: "exact"` from older clients, normalised to
`uncontested` on write with the original kept in `grade_raw`.

## `lootConfidence=none` is resolved server-side

`api/src/dryness.js`. Four identical `none` kills produce four different
verdicts, and the verdict comes back on each accepted record so you can
sanity-check it against something you actually killed:

| npc | verdict | counted? |
|---|---|---|
| Ram (always drops bones) | `missed_event` | no — the gap is ours |
| Crystalline Hunllef | `monster_is_lootless` | **yes, a real dry kill** |
| Aberrant spectre (elite clue only) | `no_countable_always_drop` | no |
| Cyclops (no wiki row) | `npc_not_in_reference_table` | no |

It settles 65.7% of known npc ids. A third have no wiki row at all — excluded
from denominators rather than guessed at, because an absent row isn't evidence.

**Your `Nothing` rows are load-bearing.** The wiki writes a lootless monster's
drop as an item literally called `Nothing`, and `fetch-always-drops.py` flags it
`countable=1`. Taken at face value that files all 47 genuinely-lootless monsters
as *guaranteed*, which silently discards every real dry kill on them — both
Hunllefs, Strangled, the Scarred demons, Bloodworm. The index builder overrides
the flag on the item name; I checked, none of the 47 also carry a real drop.

**Not asking you to change the puller.** `countable` is right for a corpse
counter and wrong for this, and the fix belongs on my side. Just don't drop
those rows — they're the only positive evidence of a lootless monster anywhere
in the dataset.

## Nice work on the icons

`monster icons, by drawing an item instead of the npc` — that was the open
problem nobody owned, and my own notes had it flagged as "research this before
it becomes a build surprise." A Kill Log without icons is a much weaker product
on both surfaces. Good solve.

## What's not done, on me

Nothing is deployed. `api.everykill.com` serves nothing and won't until there's
a privacy policy, which needs an operating entity and jurisdiction — Delk's
call, not mine. Postgres is a three-line schema change whenever hosting is
settled; SQLite is what the tests run against because a schema nobody can
execute is a schema nobody has checked.

None of that blocks you. The local server behaves exactly as this document
describes.

— Gage
