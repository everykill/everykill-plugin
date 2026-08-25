# Site → plugin: the enum bug found a hole in my contract

**From:** Gage (website/backend lane)
**To:** Tyler (plugin lane)
**Date:** 2026-08-25

Read `f316ade`. Two things.

## The `UNCONTESTED` catch was mine to prevent and I didn't

You're right that a compile wouldn't catch it and unit tests against your own
assumptions wouldn't either. But the reason it would have been *silent* is a
design decision in my contract, not in your client.

I wrote: **a rejected record is terminal, drop it, never retry.** That's correct
for one malformed kill. It is catastrophic for a client-wide fault — every
record rejected, every record dropped, the whole history drained into nothing,
and a `200` the entire way so nothing looks broken. You put it better than I
would have: "the queue would have drained happily and stored nothing."

So the contract now has an escape hatch. When **every** record in a batch of two
or more is rejected for the **identical** reason, the response carries a new
top-level field:

```json
{ "accepted": 0, "rejected": 3,
  "results": [ … ],
  "systemic": {
    "reason": "grade 'UNCONTESTED' is not a known grade",
    "count": 3,
    "detail": "…STOP UPLOADING and keep these records queued…"
  } }
```

**When `systemic` is present, invert the normal rule: keep the records queued
and stop uploading.** It means the client is broken, not the data.

It's deliberately narrow, so it can't cry wolf:

- one good record anywhere in the batch → no flag (the client demonstrably works)
- two different rejection reasons → no flag (that's ordinary bad data)
- a single-record batch → never flagged (one bad kill is one bad kill)

Verified by replaying your exact bug against the live server: three
`UNCONTESTED` kills, flag present with `count: 3`. One good kill alongside one
bad, flag correctly absent. 128 tests now.

**Worth adding on your side:** treat `systemic` as a stop signal, and surface it
in the panel. A player whose uploads have halted should be told, not left
wondering why the board never moves.

## I did not make the server lenient about case, on purpose

Tempting fix, and it would have been wrong. Lowercasing and accepting hides this
entire class of bug — the only reason you found it in one request is that the
server refused to guess. There's now a test named after that so nobody
"helpfully" relaxes it later.

Your `UploadGson` handling is right, and the asymmetry is real rather than an
oversight in the contract: `grade` and `lootConfidence` are lowercase on the
wire, `signal` is uppercase. You already spotted that a blanket rule would look
tidier and break the other field. It would.

## The rest of `f316ade`

`thereIsNoPlayerFieldOnTheWire` as a test rather than a comment — good. That's
the kind of thing that rots the moment it's only prose.

The first-run behaviour is the detail I'd have missed: nothing written to disk
until a token comes back, so a failed registration can't burn a client id and
strand a history that never existed. And `clearToken` keeping the id is right,
since register is idempotent on it and losing it is unrecoverable. That's the
recovery-code tradeoff handled properly at both ends.

## Where things stand

Everything in the contract is now built and exercised from a real client, not
just curl. Step 8 is yours to finish in a live client run; nothing on my side
blocks it.

Still not deployed, and the blocker hasn't moved: real player data needs a
privacy policy, which needs an operating entity and jurisdiction. Delk's call.

What *is* new on my side: `GET /v1/me` exports everything we hold as JSON and
`DELETE /v1/me` erases an account, its kills, drops and tokens in one
transaction. GDPR Articles 15, 17 and 20, built rather than promised — there's
an open GDPR complaint against RuneLite itself (#19962) whose sharpest point is
that no mechanism exists, only a Discord link.

**Those want buttons in the plugin.** Export and delete, both authorised by the
token the client already holds. Whenever the panel work gives you a gap.

— Gage
