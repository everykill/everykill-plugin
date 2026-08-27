# Recovery codes: there's now a route that accepts them

Gage → Tyler. Site side, `api.everykill.com`. Nothing here is urgent, but the
plugin currently has no way to use a feature we both tell players about.

## What was broken

`POST /v1/register` has always returned a `recoveryCode` on first registration,
along with this note in its own response body:

> Store the recovery code. It is shown once and it is the only way back to this
> account.

That was not true. The code was generated, salted, hashed and written to
`account.recovery_hash` — and **no route on the server accepted one**. There was
nothing to redeem it against. The first player to reinstall RuneLite, move
machines, or wipe a profile would have found out the hard way that the thing we
told them to write down did nothing.

I found it writing the install page's answer to "where do I enter my account
code?" and went looking for what actually consumes it.

## What exists now

```
POST /v1/recover
Content-Type: application/json

{ "code": "<the recovery code>", "clientId": "<32 hex chars>" }
```

**200**
```json
{
  "stored": true,
  "token": "ek_…",
  "rebound": true,
  "note": "Recovered. This install is now the account's client id."
}
```

**404** — wrong code, unknown code, no difference between them:
```json
{ "stored": false, "error": "no_such_code",
  "detail": "No account matches that recovery code." }
```

**400** `bad_client_id` if `clientId` isn't 32 hex characters, `bad_code` if the
code is missing or an absurd length. **405** on anything but POST.

## Behaviour worth knowing before you wire it

**The account is re-pointed at the new client id.** After a successful recover,
the plugin's ordinary `register()` with that same client id finds the account
again — so recovery is a one-time step, not something the player repeats every
launch. Verified by test.

**The code does not rotate.** A player who reinstalls twice needs it to work
twice. Don't build UI that implies it's spent.

**The old token keeps working.** Recovering on a laptop doesn't lock out the
desktop that's still playing. Nothing about the code means "revoke everything
else".

**`rebound: false` is the case to handle.** It means the client id you sent
already belongs to a *different* account. Rather than silently merge two
histories, the server issues a token for the recovered account and leaves both
client ids alone. The returned token is correct and usable; the plugin should
just not assume the local id now maps to it. In practice this happens when
someone types their code into an install that's already been tracking.

## What I'd suggest on your side

A field in the plugin's settings — "recovery code" — that posts the code plus
the client id already in config, then replaces the stored token with the one
that comes back. Same place the code was shown when it was first minted.

The site deliberately has **no input for this**. There's no login on
everykill.com and nowhere to paste a code; a website that asks for your account
secret is a website that can lose it. The install page now says that in as many
words, and the FAQ answers "where do I enter my account code?" with "you don't,
it goes in the plugin".

## Known gap, so you hear it from me

`/v1/recover` is **not rate limited**. The limiter in `server.js` keys on a
bearer token and there isn't one on this route. The code is 128 bits of random
so brute force isn't the realistic attack, but a per-IP bucket belongs there
before the plugin goes public. It's commented as a gap in the source rather
than left to be discovered.

## Tests

`api/test/recover.test.js`, 9 of them. The ones that matter: history comes back
with the token, the old token survives, the code works a second time, and
recovering onto an occupied client id does **not** merge the two accounts —
that last one I proved by removing the guard and watching it fail.
