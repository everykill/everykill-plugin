#!/usr/bin/env python3
"""
Pulls guaranteed drops - rarity=Always - for every monster in data/monsters.tsv.

Writes data/always_drops.tsv:  npc_id, name, item, quantity_min, quantity_max, countable

WHY NOT THE BUCKET API. spec-reference-data.md says "same API, drops bucket, filter
rarity Always". It can't be done. The bucket is called 'dropsline' and it exposes
exactly two fields, page_name and item_name - no rarity, no quantity. ~35 candidate
field names probed 2026-08-22. Rarity is the entire point, so this parses the
wikitext instead. See FINDINGS 2026-08-22.

WHY PYTHON, when fetch-reference-data.sh is bash. That one is a flat field grab and
awk suits it. This needs real parsing - nested braces, HTML comments inside values,
quantity ranges, duplicate version blocks - and awk would be a liability. It is still
ONE process: batched through urllib, not a curl per page. The 2026-08-14 lesson was
about spawning 19,000 subprocesses on Windows, not about awk.

LICENCE. Wiki content is CC BY-NC-SA 3.0. Output is generated on demand and never
committed - data/ is gitignored. docs/LICENSING.md.
"""

import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

UA = "everykill-plugin (contact@everykill.com)"
API = "https://oldschool.runescape.wiki/api.php"
BATCH = 50          # MediaWiki's anonymous limit for titles= in one query
PAUSE = 0.2         # be polite, this is somebody else's server

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "data")

# Items that are guaranteed but useless as corpse counters. Stackables merge into one
# pile so three kills are indistinguishable from one - spec-data-model.md is explicit
# that only non-stackable guaranteed drops qualify. Clue scrolls only ever drop one at
# a time no matter how many corpses, so they lie about the count too.
NOT_COUNTABLE = re.compile(r"coins|clue scroll|reward casket|scales", re.I)


def api(params):
    params = dict(params, format="json")
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def strip_noise(value):
    """Wiki editors leave comments and refs inside parameter values. Zulrah's quantity
    is literally '100-299<!--note: the 500 scale drop is separate-->'."""
    value = re.sub(r"<!--.*?-->", "", value, flags=re.S)
    value = re.sub(r"<ref.*?(/>|</ref>)", "", value, flags=re.S)
    value = re.sub(r"\[\[([^\]|]*\|)?([^\]]*)\]\]", r"\2", value)
    return value.strip()


def split_params(body):
    """Split a template body on | but not inside [[links]] or {{nested}}."""
    parts, depth, cur = [], 0, []
    for ch in body:
        if ch in "[{":
            depth += 1
        elif ch in "]}":
            depth -= 1
        if ch == "|" and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    return parts


def quantity_range(raw):
    """'1' -> (1,1).  '100-299' -> (100,299).  '3; 5' and '' -> (None,None)."""
    raw = strip_noise(raw).replace(",", "")
    m = re.fullmatch(r"(\d+)\s*[-\u2013]\s*(\d+)", raw)
    if m:
        return int(m.group(1)), int(m.group(2))
    m = re.fullmatch(r"(\d+)", raw)
    if m:
        return int(m.group(1)), int(m.group(1))
    return None, None


def always_drops(wikitext):
    """Every rarity=Always DropsLine on a page, deduped.

    Pages carry one drop table per version - standard, catacombs, wilderness - so the
    same guaranteed drop appears several times. Same item AND same quantity is the same
    drop restated; keep it once. Different quantity is a genuinely different table and
    we cannot tell from here which version we killed, so flag it rather than pick."""
    found = {}
    for m in re.finditer(r"\{\{DropsLine\|", wikitext):
        start = m.end()
        # one unclosed brace pair at this point, not two. getting this wrong makes the
        # matcher run off the end of the template and swallow the rest of the page.
        depth, i = 1, start
        while i < len(wikitext) and depth > 0:
            if wikitext.startswith("{{", i):
                depth += 1
                i += 2
                continue
            if wikitext.startswith("}}", i):
                depth -= 1
                i += 2
                continue
            i += 1
        body = wikitext[start:i - 2]

        kv = {}
        for part in split_params(body):
            if "=" in part:
                k, v = part.split("=", 1)
                kv[k.strip().lower()] = v
        if strip_noise(kv.get("rarity", "")).lower() != "always":
            continue

        item = strip_noise(kv.get("name", ""))
        if not item:
            continue
        lo, hi = quantity_range(kv.get("quantity", "1"))
        found.setdefault(item, set()).add((lo, hi))

    out = []
    for item, quantities in found.items():
        countable = not NOT_COUNTABLE.search(item)
        if len(quantities) > 1:
            # conflicting quantities across versions - degrade, don't guess
            out.append((item, None, None, False))
        else:
            lo, hi = next(iter(quantities))
            out.append((item, lo, hi, countable and lo is not None))
    return out


def main():
    src = os.path.join(DATA, "monsters.tsv")
    if not os.path.exists(src):
        sys.exit("no data/monsters.tsv - run tools/fetch-reference-data.sh first")

    # name -> [npc_id, ...]. one wiki page covers every id sharing that name, so we
    # fetch per name and fan the result back out.
    by_name = {}
    with open(src, encoding="utf-8") as f:
        next(f)
        for line in f:
            col = line.rstrip("\n").split("\t")
            if len(col) < 2 or not col[1]:
                continue
            by_name.setdefault(col[1], []).append(col[0])

    names = sorted(by_name)
    print(f"{len(names)} distinct monster names, {sum(len(v) for v in by_name.values())} npc ids")

    rows, no_guaranteed, missing = [], 0, 0
    for i in range(0, len(names), BATCH):
        chunk = names[i:i + BATCH]
        try:
            d = api({
                "action": "query",
                "prop": "revisions",
                "rvprop": "content",
                "rvslots": "main",
                "titles": "|".join(chunk),
            })
        except Exception as e:                      # noqa: BLE001
            print(f"  batch {i // BATCH}: FAILED {e}", file=sys.stderr)
            continue

        for page in d.get("query", {}).get("pages", {}).values():
            title = page.get("title", "")
            try:
                text = page["revisions"][0]["slots"]["main"]["*"]
            except Exception:                       # noqa: BLE001
                missing += 1
                continue
            drops = always_drops(text)
            if not drops:
                no_guaranteed += 1
                continue
            for npc_id in by_name.get(title, []):
                for item, lo, hi, countable in drops:
                    rows.append((npc_id, title, item,
                                 "" if lo is None else lo,
                                 "" if hi is None else hi,
                                 1 if countable else 0))

        done = min(i + BATCH, len(names))
        print(f"  {done}/{len(names)} names, {len(rows)} rows", end="\r", flush=True)
        time.sleep(PAUSE)

    print()
    out = os.path.join(DATA, "always_drops.tsv")
    with open(out, "w", encoding="utf-8", newline="") as f:
        f.write("npc_id\tname\titem\tquantity_min\tquantity_max\tcountable\n")
        for r in sorted(rows, key=lambda x: (int(x[0]), x[2])):
            f.write("\t".join(str(c) for c in r) + "\n")

    ids = {r[0] for r in rows}
    countable_ids = {r[0] for r in rows if r[5] == 1}
    print(f"wrote {len(rows)} rows to data/always_drops.tsv")
    print(f"  npc ids with a guaranteed drop:  {len(ids)}")
    print(f"  npc ids with a COUNTABLE drop:   {len(countable_ids)}  <- corpse counter works on these")
    print(f"  pages with no guaranteed drop:   {no_guaranteed}")
    print(f"  pages the api returned nothing for: {missing}")


if __name__ == "__main__":
    main()
