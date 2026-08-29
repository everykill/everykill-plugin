"""Resolve all 53 helmets to item ids using the wiki, not constant-name guessing.

Two earlier attempts failed because gameval constants use game-internal names
(SLAYER_HELM, SLAYER_FACEMASK) rather than wiki names, and some - Dragon full helm -
have no plain constant at all. ItemManager.search() is tradeable-only, so Slayer helm,
Void melee helm and the Barrows pieces would all miss.

The wiki knows every item id. Ask it once, ship the answer as a resource file, and the
picker then draws through itemManager with ZERO new network surface - the same shape as
npc-icons.json which we already ship.
"""
import json
import re
import time
import urllib.parse
import urllib.request

API = "https://oldschool.runescape.wiki/api.php"


def fetch(params):
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": "everykill/helmet-ids"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


req = urllib.request.Request("https://api.everykill.com/v1/helmets",
                             headers={"User-Agent": "everykill/probe"})
with urllib.request.urlopen(req, timeout=20) as r:
    helmets = json.load(r).get("helmets") or []

out, misses = {}, []

for h in helmets:
    # the wiki filename is the page name for almost all of these
    page = h["file"].replace(".png", "").replace("_", " ")
    try:
        d = fetch({"action": "parse", "page": page, "prop": "wikitext",
                   "format": "json", "section": "0"})
        text = d["parse"]["wikitext"]["*"]
    except Exception:
        misses.append((h["id"], h["name"], page))
        continue

    # |id = 1234   or   |id1 = 1234
    m = re.search(r"\|\s*id\d*\s*=\s*(\d+)", text)
    if m:
        out[h["id"]] = {"item": int(m.group(1)), "name": h["name"]}
    else:
        misses.append((h["id"], h["name"], page))
    time.sleep(0.3)

print(f"resolved {len(out)}/{len(helmets)}")
if misses:
    print(f"\n{len(misses)} unresolved:")
    for hid, name, page in misses:
        print(f"  {hid:20} {name:24} tried page '{page}'")

with open("helmet-items.json", "w", encoding="utf-8") as f:
    json.dump({k: v["item"] for k, v in sorted(out.items())}, f, indent=1)
print("\nwrote helmet-items.json")
