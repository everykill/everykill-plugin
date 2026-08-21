# Working agreement

How to report, where findings live, and the reasoning failure modes this project is prone to.

Read alongside `docs/INTEGRITY.md`. That file covers what counts as verified. This one covers how to communicate it and how to avoid a specific class of mistake.

---

## 1. Report format

Reports have been getting long and dense. Density isn't the problem — burying the one line that matters inside six paragraphs is. Lead with the summary, then the detail underneath for anyone who wants it.

**Every report opens with this header, and nothing before it:**

```
CHANGED:     one line per thing modified
VERIFIED:    what was proven, and by what test
OUTSTANDING: what is still unproven or untested
NEXT:        the single next action, or the question blocking it
```

Rules:

- Each line is one sentence. If a line needs a paragraph, it goes below the header, not in it.
- **OUTSTANDING is never empty.** If nothing is outstanding, the step is finished — say that explicitly.
- If nothing changed, say `CHANGED: nothing`. Don't pad it.
- Detail goes *after* the header, under a `---`. Keep it as long as it needs to be. The header is what gets read first; the detail is what gets read when something looks off.

**Don't restate what's already in the docs.** If a finding was written into a spec, reference the file and section rather than reproducing the reasoning in chat. The docs are the record; the report is the pointer.

**One question at a time.** When a decision is needed, ask the single most blocking one rather than presenting three decisions at once. Multiple simultaneous questions produce rushed answers.

---

## 2. The findings log

Create and maintain `docs/FINDINGS.md`. It is the running record of everything empirically established, so project state lives in the repository rather than in a chat window or a person's memory.

**One entry per verified finding.** Format:

```
## YYYY-MM-DD — Short title
**Status:** verified | inconclusive | contradicted-spec | unverified-assumption
**Method:** how it was established, in one line
**Finding:** what is now known
**Consequence:** what changed in code or docs as a result
**Source:** external references, if any
```

Rules:

- Append only. Never edit or delete a past entry — if a finding is later overturned, add a new entry that supersedes it and link back. The history of what we believed and when is itself valuable.
- Log **contradicted specs** as prominently as successes. A spec proven wrong is a more useful record than a spec proven right.
- Log **unverified assumptions** too, with `Status: unverified-assumption`, so they don't quietly harden into fact.
- Entries are short. Two or three lines per field. Depth belongs in the specs.

Anything established in a chat session that isn't written here is lost.

---

## 3. Reasoning failure modes

These are specific to this project and have each already occurred at least once.

### 3a. Correct-sounding logic that doesn't survive tracing the code path

The most dangerous failure here isn't a wrong fact — it's a well-formed argument about behaviour that nobody traced.

Real example: the review queue was described as the safety net that would surface a new transform-death monster. Tracing it shows the opposite — for an unlisted NPC, `ActorDeath` fires early, a kill is emitted, the record closes, and the non-death despawn path is never reached. The review line cannot fire. The reasoning read well and was structurally wrong.

**Before claiming any mechanism provides coverage, trace the actual path to it.** Name the events in order and confirm the code reaches that branch. If the trace wasn't done, say the coverage is assumed rather than established.

### 3b. Plausibility standing in for verification

Real example: the zygomite `_CAP` variants were excluded because they "look like the pre-combat capped mushroom stage." That may well be right. It was correctly flagged unconfirmed — which is the right handling — but the pattern is where errors will come from, because untestable assumptions sit in the docs looking settled.

**Reasoning from naming patterns, wiki summaries, or what seems sensible is a hypothesis, not a finding.** Label it as such, log it in `FINDINGS.md` with `Status: unverified-assumption`, and state plainly what test would settle it.

### 3c. Treating the specs as authoritative

The specs in `docs/` were written before any code ran. They are a starting hypothesis, not a source of truth, and several assumptions in them have already been proven wrong.

**When the client contradicts a spec, the client wins.** Say so directly, propose the correction, and log it. Never adjust a test until it passes. Never work around a contradiction silently.

### 3d. Preferring secondary sources over the client

Real example: a summarised wiki fetch mislabelled `SLAYER_ROCKSLUG_BABY` as the superior monster, which is a different NPC entirely. Grepping the client's own `NpcID` constants found the error and six missing IDs — reported as "seven" in chat at the time and relayed here uncorrected; see `docs/FINDINGS.md` for the recount. Ironic given this section's own subject, and left visible as the example of exactly the failure mode it warns about.

**The client jar is ground truth for anything the client knows** — NPC IDs, item IDs, animation IDs, names. The wiki is ground truth for game mechanics and drop tables. Prefer the client for the former, always, and cross-check when they disagree.

---

## 4. The context asymmetry

This project runs across two workstreams: one here with the repository, and one holding the full design history, external research, and the reasoning behind decisions in these docs. Notes get relayed between them by hand.

That means:

- **Findings that stay in this terminal are invisible to the other half.** `FINDINGS.md` is the shared channel — write there, not just in chat.
- **When a decision seems to contradict the specs, it may be that the reasoning didn't survive the relay** rather than that the spec is wrong. Ask rather than assuming either way.
- **Flag when a decision has consequences beyond the current step.** The other half is tracking the site, schema and integrity model that this client work feeds into, and a client-side choice can quietly constrain those.
