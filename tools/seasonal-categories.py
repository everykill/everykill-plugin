"""Get the authoritative seasonal monster list from the wiki's categories.

Sweeping id blocks and checking each page is slow and only finds what I thought to
look at. The wiki already maintains the answer as categories - ask it directly.

Then match those page titles back to our TSV ids, so Gage gets ids rather than names.
Ids are what the plugin sends and what the contract says to key on.
"""
import io
import json
import time
import urllib.parse
import urllib.request

API = "https://oldschool.runescape.wiki/api.php"

CATEGORIES = [
    "Demonic Pacts League",
    "Raging Echoes League",
    "Trailblazer Reloaded League",
    "Shattered Relics League",
    "Trailblazer League",
    "Twisted League",
    "Deadman Mode",
    "Deadman: Apocalypse",
    "Deadman: Armageddon",
    "Deadman: Reborn",
    "Deadman: Annihilation",
    "Beta content",
]


def fetch(params):
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": "everykill/seasonal-audit"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


titles = {}
for cat in CATEGORIES:
    cont = None
    got = 0
    while True:
        p = {
            "action": "query", "list": "categorymembers",
            "cmtitle": "Category:" + cat, "cmlimit": "500",
            "cmnamespace": "0", "format": "json",
        }
        if cont:
            p["cmcontinue"] = cont
        try:
            d = fetch(p)
        except Exception as e:
            print(f"  {cat}: lookup failed ({e})")
            break
        for m in d.get("query", {}).get("categorymembers", []):
            titles.setdefault(m["title"], set()).add(cat)
            got += 1
        cont = d.get("continue", {}).get("cmcontinue")
        if not cont:
            break
        time.sleep(0.3)
    print(f"  {cat:32} {got:5} pages")

print(f"\n{len(titles)} distinct seasonal page titles\n")
io.open("seasonal_titles.json", "w", encoding="utf-8").write(
    json.dumps(sorted(titles.keys()), indent=1))
print("saved to seasonal_titles.json")
