#!/usr/bin/env bash
#
# Pulls the P0 monster reference table from the OSRS Wiki's Bucket API.
#
# Writes two files into data/ (gitignored - see the licence note below):
#   monsters.raw.json   exactly what the API returned
#   monsters.tsv        npc_id -> name, max hp, combat level, xp bonus
#
# LICENCE. Wiki content is CC BY-NC-SA 3.0: non-commercial, share-alike. That sits
# badly against a BSD plugin, which is why the output is generated on demand and never
# committed. See docs/LICENSING.md. The long-term plan is deriving max hp from our own
# kill logs instead - see docs/spec-reference-data.md section 4.
#
# Needs curl. No jq, because the shape is flat enough not to warrant another dependency.
#
# One awk pass, deliberately. The first version ran grep and sed per row and took over
# five minutes on Windows, where spawning ~19,000 processes is most of the runtime.

set -euo pipefail

UA="everykill-plugin (contact@everykill.com)"
API="https://oldschool.runescape.wiki/api.php"
OUT="$(cd "$(dirname "$0")/.." && pwd)/data"
mkdir -p "$OUT"

echo "fetching infobox_monster..."
curl -sS -A "$UA" --get "$API" \
	--data-urlencode "action=bucket" \
	--data-urlencode "format=json" \
	--data-urlencode "query=bucket('infobox_monster').select('name','id','hitpoints','combat_level','experience_bonus').limit(5000).run()" \
	> "$OUT/monsters.raw.json"

if grep -q '"error"' "$OUT/monsters.raw.json"; then
	echo "api returned an error:" >&2
	head -c 300 "$OUT/monsters.raw.json" >&2
	exit 1
fi

tr '{' '\n' < "$OUT/monsters.raw.json" | awk -F'\t' '
	# Fields are pulled by name, never by position - the API makes no promise about
	# order and it has already varied between queries.
	function field(line, key, prefix,    v) {
		prefix = "\"" key "\":"
		if (match(line, prefix "-?[0-9.]+")) {
			v = substr(line, RSTART + length(prefix), RLENGTH - length(prefix))
			return v
		}
		return ""
	}

	/"name"/ {
		name = ""
		if (match($0, /"name":"[^"]*"/)) {
			name = substr($0, RSTART + 8, RLENGTH - 9)
		}

		# Wiki namespace pages leak into the bucket, eg "RuneScape:Templates".
		if (name == "" || index(name, ":") > 0) {
			next
		}

		hp = field($0, "hitpoints")
		cb = field($0, "combat_level")
		xb = field($0, "experience_bonus")

		# id is a repeated field carrying beta/hist revision refs alongside real npc
		# ids. Only the numeric ones exist in a live client.
		if (match($0, /"id":\[[^]]*\]/)) {
			ids = substr($0, RSTART, RLENGTH)
			n = split(ids, part, "\"")
			for (i = 1; i <= n; i++) {
				if (part[i] ~ /^[0-9]+$/) {
					print part[i] "\t" name "\t" hp "\t" cb "\t" xb
				}
			}
		}
	}
' | awk -F'\t' '
	# Second pass, because one npc_id can arrive on several rows. Most are exact
	# repeats and collapse for free. The rest genuinely disagree - same id, two
	# different monsters - and those we refuse to guess at. See the note below.
	BEGIN { OFS = "\t"; print "npc_id", "name", "hitpoints", "combat_level", "experience_bonus", "ambiguous" }

	{
		id = $1
		sig = $2 SUBSEP $3 SUBSEP $4 SUBSEP $5

		if (!(id in seen)) {
			order[++count] = id
			seen[id] = sig
			nm[id] = $2; hp[id] = $3; cb[id] = $4; xb[id] = $5
			next
		}

		if (seen[id] == sig) {
			next        # exact repeat, nothing to decide
		}

		# Disagreement. Blank whichever fields differ so a reader gets "unknown"
		# rather than a coin flip, and flag the row. GAME-MECHANICS.md: if the
		# source is unclear the code degrades, it does not guess.
		amb[id] = 1
		if (nm[id] != $2) { nm[id] = "" }
		if (hp[id] != $3) { hp[id] = "" }
		if (cb[id] != $4) { cb[id] = "" }
		if (xb[id] != $5) { xb[id] = "" }
	}

	END {
		for (i = 1; i <= count; i++) {
			id = order[i]
			print id, nm[id], hp[id], cb[id], xb[id], (id in amb ? 1 : 0)
		}
	}
' > "$OUT/monsters.tsv"

rows=$(( $(wc -l < "$OUT/monsters.tsv") - 1 ))
echo "wrote $rows npc ids to data/monsters.tsv"
awk -F'\t' 'NR>1 && $3=="" {n++} END {print "  missing hitpoints: " n+0}' "$OUT/monsters.tsv"
awk -F'\t' 'NR>1 && $5=="" {n++} END {print "  missing xp bonus:  " n+0}' "$OUT/monsters.tsv"
awk -F'\t' 'NR>1 && $6==1  {n++} END {print "  ambiguous ids:     " n+0 "  (stats disagree, fields blanked)"}' "$OUT/monsters.tsv"
