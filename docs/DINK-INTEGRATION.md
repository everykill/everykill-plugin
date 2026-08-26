# Discord notifications, via Dink

Everykill does not send Discord webhooks. **Dink does**, and it does it properly —
rich embeds, screenshots, retries with backoff, forum threads, rate limits. We hand
it the event and it sends the message.

## Why not build our own

Three reasons, in order of how much they'd cost us:

**It's a duplicate.** The Plugin Hub explicitly prefers contributions to existing
plugins over reimplementations, and "why isn't this just Dink?" is a question we'd
have to answer at review. `docs/LICENSING.md` already recorded the decision: *our
Dink "integration" is simply not building webhooks.*

**It doubles our review surface.** Everykill's upload path is already the
highest-risk thing a reviewer looks at, and it goes to exactly one endpoint the user
typed in. A webhook field POSTs to *any host on the internet*, chosen at runtime.
That is a much harder thing to defend for a feature someone else already ships.

**It's a permanent maintenance tax.** Discord changes embed formats, limits and
retry semantics. Dink's author tracks that. We'd be tracking it too, worse.

## How a user sets it up

1. Install **Dink** from the Plugin Hub.
2. Put a Discord webhook URL in Dink's `Primary Webhook URLs`.
3. In Dink: enable `External Plugin Requests > Enable External Plugin Notifications`.
4. In Everykill: `Notices > Send milestones to Dink`.

That's it. **Everykill never sees the webhook URL** and never contacts Discord.

## What gets sent

Only milestones — never every kill.

| | when |
|---|---|
| Kill-count milestone | 100 / 250 / 500 / 1,000 / 2,500 / 5,000 / 10,000 |
| Fastest kill | a new personal best, in ticks |

A webhook that fires on all 4,000 gargoyles is a webhook the user mutes, and then it
never fires for the one that mattered.

The milestone ladder is **the same constant the chat notice uses** — one source of
truth, so Discord and the chatbox can't disagree about what counts.

## How it works technically

`PluginMessage("dink", "notify", data)` on RuneLite's own event bus. Core's
inter-plugin API, so:

- **No dependency added.** `build.gradle` is untouched — the Hub requires that.
- **Works whether Dink is installed or not.** An event nobody listens for is dropped.
- **No HTTP from us at all.**

Payload keys, read from Dink's source and asserted in `DinkNotifierTest`:

```
sourcePlugin   required — Dink logs and skips without it
text           required — the message body
title          optional embed title
fields         optional [{name, value, inline}]
```

**We never set `urls`.** Omitting it makes Dink use the webhook the *user*
configured. Setting one would mean the plugin choosing where a player's data goes.

**We never set `imageRequested`.** Asking for a screenshot of someone's client isn't
ours to ask for by default. Dink can be configured to add one if they want it.

## If you're extending this

Getting `sourcePlugin` or `text` wrong produces **silence, not an error** — Dink logs
a skip and moves on. That's why both are asserted with the literal strings from
Dink's own test file rather than checked by eye.
