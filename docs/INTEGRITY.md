# Integrity & testing standards

This project's only asset is trust in its numbers. A hiscore site whose data is quietly wrong is worth less than no site at all — it actively misleads people and, once discovered, poisons everything else we publish. Read this before marking any step complete.

---

## 1. The cost asymmetry

A **false fail** costs an hour of rework. A **false pass** propagates silently into every layer built on top of it, and is usually discovered months later by someone comparing our published rates to the wiki's.

These are not symmetric mistakes. When uncertain, fail the step.

---

## 2. Absence of evidence is not evidence of absence

"No smoking gun", "nothing suspicious appeared", "I didn't see any problems" — none of these are findings. They are the absence of a finding, which is also exactly what you get when the test never actually ran.

**A negative test only means something if the condition being tested for was definitely present.**

Concretely: "no foreign kills appeared in the log" proves the filter works *only if other players were demonstrably killing things in the loaded scene at the time*. If nobody else was fighting nearby, a clean log is the expected result of a completely broken filter too. The test has to be *capable* of failing, or it isn't a test.

Before reporting a negative result, state explicitly: **what would have made this test fail, and was that condition actually present?** If you can't answer, the test is inconclusive, not passing.

---

## 3. "Approximately" never satisfies "exactly"

When a criterion says the counts must match, a rough count in the same ballpark is a **fail**, not a partial pass.

This matters more here than in most software, because the errors we're hunting are *invisible in aggregate*. Three foreign kills mixed into a hundred looks identical to a correct log. Small systematic bias is precisely the failure mode that survives casual checking and destroys a published drop rate.

If an exact count wasn't obtained, the correct report is: **"not yet verified — exact count not obtained."**

---

## 4. Vocabulary — use these words precisely

| Term | Meaning |
|---|---|
| **Verified** | The criterion was met exactly, under conditions capable of producing a failure |
| **Partially verified** | Some criteria met exactly; others named explicitly as outstanding |
| **Inconclusive** | The test ran but couldn't have detected the failure it targets |
| **Untested** | Not attempted, or blocked by account/content access |
| **Failed** | Criterion not met |

Never write "satisfies the acceptance criteria" unless **every** listed criterion is **Verified**. If three of four passed, say that, and name the fourth.

---

## 5. Report what wasn't tested, not just what was

Every step report should end with an explicit list of what remains unverified. That list is more valuable than the list of successes, because it's the only thing preventing a false sense of completion from compounding.

A good report looks like:

> **Verified:** chicken count matched exactly (50/50), hand-counted.
> **Inconclusive:** busy-area cross-attribution — the log was clean, but I cannot confirm other players were killing NPCs in the loaded scene during the window, so a clean log doesn't distinguish a working filter from a broken one.
> **Outstanding:** rerun with a confirmed-populated area and an exact hand count.

---

## 6. Core data rules these standards protect

These exist because the same instinct — filling a gap with a reasonable-sounding estimate — destroys all of them.

- **Classify, never correct.** Where attribution is uncertain, record the uncertainty. Never guess, never backfill from expected rates, never rewrite an observed value to make it consistent.
- **Unknown ≠ empty.** A kill whose loot couldn't be attributed is excluded from drop-rate maths entirely. Recording it as "no drop" makes every published rate wrong in a way nobody will notice.
- **A kill requires both a death and our damage.** Neither alone. `ActorDeath` fires for every NPC in the scene including strangers' kills.
- **Skip, don't approximate.** When the gating conditions for a calculation aren't met, skip it. A skipped measurement costs one confidence grade. A wrong one poisons a benchmark permanently.
- **Publish sample sizes; hide thin data.** Showing a number derived from four kills is worse than showing nothing.
- **Descriptive, never prescriptive.** Correlations in this dataset carry confounds we cannot remove — better-geared players are usually better players. State what was observed, never what someone should do.

---

## 7. Designing a test that can actually fail

Before running any acceptance test, answer these three:

1. **What specific wrong behaviour is this test hunting?**
2. **What would I observe if that wrong behaviour were present?**
3. **Are the conditions right now such that I would observe it?**

If the answer to 3 is no, fix the conditions before running. A test run under conditions where failure was impossible produces no information, and reporting it as a pass is worse than not running it.

---

## 8. When the spec is wrong

The specs in this repo are **designed, not verified.** Several assumptions are explicitly flagged as needing empirical confirmation, and some will turn out to be wrong.

When testing contradicts a spec: say so plainly, explain what was actually observed, and propose the correction. Do not work around it silently, and do not adjust the test until it passes. A contradicted spec is a valuable finding — it's the entire reason the console-only phase exists.
