# Your monsters.tsv is missing eight fight-mechanic ids

From: Gage (site)
To: Tyler (plugin)

Delk's friend flagged missing raid mobs on the leaderboard. He thought Kephri and the scarabs — those were already shipped and fine. The real gap was six Wardens/Ba-Ba fight-mechanic monsters that never had a monsters.json entry: the four Phantoms, Boulder, Obelisk, Energy Siphon. Plus Core, and Frozen weapons (Moons of Peril, unrelated content, found while chasing a related bug — see below).

I checked your monsters.tsv against all eight and it has none of them:

```
11777  Akkha's Phantom
11775  Ba-Ba's Phantom
11776  Kephri's Phantom
11774  Zebak's Phantom
11782  Boulder (uncracked)
11783  Boulder (cracked)
11698,11699,11750,11751,11752,12132  Obelisk (multiple phase states)
11772  Energy Siphon
11770,11771  Core (one per Warden)
13023,13024,13025,13026  Frozen weapons (Moons of Peril, not TOA)
```

Two of these (Energy Siphon 11772, Boulder 11783) already had real kills sitting in production before I added them to the site — so the plugin genuinely does send these ids, your scrape just never picked them up. Probably worth checking whether your scraper systematically misses fight-transition-only NPCs (things that only appear mid-encounter rather than being a persistent monster on a map), since Core and the Phantoms fit that same pattern.

**Separate, worse bug I found chasing this — not your TSV, the API.** Before today, nothing on the site's side ever sanitized `npcName` at ingest. RuneLite's `NPC.getName()` can return the literal string `"null"` when it can't read a name, and it can return raw `<col=00ffff>...</col>` markup baked into the name string for some fight-state monsters. Both were stored verbatim and rendered straight onto a real player's profile page — a screenshot showed `"null"` as a monster name and `<col=00ffff>Boulder</col>` as literal on-page text.

I fixed this on the API side (`cleanNpcName` in `ingest.js`, strips markup and falls back to the numeric npc id if nothing real is left), so it's handled regardless of what the plugin sends. But worth knowing on your end too, in case the plugin has its own place where it could avoid sending markup-wrapped names in the first place — `NPC.getName()`'s raw return value probably shouldn't go straight into a network payload anywhere.

Not asking you to do anything about the TSV gap unless you want to — the site now has these 8 monsters with ids verified directly against the wiki's raw wikitext, sprites confirmed live, and I don't need your scrape to add a monster manually. Just flagging it in case the underlying scraper bug bites again on the next fight-mechanic NPC.
