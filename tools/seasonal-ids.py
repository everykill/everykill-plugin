"""Match seasonal wiki pages to npc ids in our TSV.

Most of those 1,211 pages are items, relics and mechanics. What matters is which of
OUR npc ids belong to a seasonal page - the plugin sends ids, and the kill contract
says to key on npcId and never on the name.

Uses the wiki's own npc-id lookup per candidate title, so a match is the wiki's
statement rather than my guess from an id range.
"""
import io
import json
import re
import time
import urllib.parse
import urllib.request

API = "https://oldschool.runescape.wiki/api.php"

titles = json.load(io.open("seasonal_titles.json", encoding="utf-8"))

rows = []
with io.open(r"C:\Users\mabbo\IdeaProjects\zelnork-tracker\data\monsters.tsv",
             encoding="utf-8") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) >= 2 and p[0].isdigit():
            rows.append((int(p[0]), p[1]))

tsv_names = {}
for i, n in rows:
    tsv_names.setdefault(n, []).append(i)

# a seasonal page whose base name (suffix stripped) exists in our tsv is a candidate:
# either it IS one of our rows, or one of our rows is its unlabelled twin.
BASE = re.compile(r"\s*\((Echo|Deadman[^)]*|Leagues[^)]*|Tournament[^)]*|beta)\)\s*$", re.I)

candidates = {}
for t in titles:
    base = BASE.sub("", t).strip()
    if base in tsv_names:
        candidates[t] = base

print(f"{len(candidates)} seasonal pages whose name matches a monster in our tsv\n")

# ask the wiki which npc ids each of those pages actually owns
seasonal_ids = {}
for i, (title, base) in enumerate(sorted(candidates.items()), 1):
    url = (API + "?" + urllib.parse.urlencode({
        "action": "parse", "page": title, "prop": "wikitext",
        "format": "json", "section": "0"}))
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "everykill/seasonal-audit"})
        with urllib.request.urlopen(req, timeout=30) as r:
            d = json.loads(r.read())
        text = d["parse"]["wikitext"]["*"]
    except Exception:
        continue

    for m in re.finditer(r"\|\s*id\d*\s*=\s*([0-9,\s]+)", text):
        for raw in m.group(1).split(","):
            raw = raw.strip()
            if raw.isdigit():
                seasonal_ids[int(raw)] = title
    if i % 25 == 0:
        print(f"  checked {i}/{len(candidates)}")
    time.sleep(0.25)

ours = {i for i, _ in rows}
hits = sorted((i, t) for i, t in seasonal_ids.items() if i in ours)

print(f"\n{len(hits)} npc ids in OUR tsv belong to a seasonal page:\n")
for i, t in hits:
    ours_name = next(n for j, n in rows if j == i)
    print(f"  {i:6}  tsv='{ours_name}'  wiki='{t}'")

io.open("seasonal_ids.json", "w", encoding="utf-8").write(
    json.dumps({str(i): t for i, t in hits}, indent=1))
print("\nsaved to seasonal_ids.json")
