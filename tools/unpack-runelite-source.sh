#!/usr/bin/env bash
#
# Unpacks RuneLite core's source into rlsrc/ so it can be read and grepped.
#
# WHY. The specs keep saying "read LootManager before implementing", and twice now a
# problem was solved by reading core instead of guessing at it - DEATH_CONFIRM_TICKS
# got tuned in both directions and broken both ways while the answer sat in
# LootManager and NpcUtil the whole time. So core's source is a working file here,
# not a nice-to-have.
#
# LICENCE. Core is BSD 2-Clause but this is somebody else's code: read it, reimplement
# it, do NOT paste it. rlsrc/ is gitignored and never committed. docs/LICENSING.md.
#
# The sources jar is already a build dependency, so this pulls it out of the gradle
# cache rather than downloading anything. Version is read from the jar gradle actually
# resolved, so it cannot drift from what the client runs.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/rlsrc"

# newest sources jar gradle has cached. -V sorts 1.12.9 below 1.12.36, which plain
# sort does not.
JAR=$(find "$HOME/.gradle/caches/modules-2/files-2.1/net.runelite/client" \
	-name "client-*-sources.jar" 2>/dev/null | sort -V | tail -1)

if [ -z "$JAR" ]; then
	echo "no sources jar in the gradle cache." >&2
	echo "run ./gradlew build once first - it comes down with the client dependency." >&2
	exit 1
fi

VERSION=$(basename "$JAR" | sed 's/^client-//; s/-sources\.jar$//')

rm -rf "$OUT"
mkdir -p "$OUT"
(cd "$OUT" && unzip -qo "$JAR")

echo "unpacked runelite $VERSION into rlsrc/"
echo "  java files: $(find "$OUT" -name '*.java' | wc -l)"
echo
echo "the ones the specs point at:"
for f in game/LootManager.java game/NpcUtil.java game/ItemStack.java; do
	p=$(find "$OUT" -path "*/$f" | head -1)
	[ -n "$p" ] && echo "  ${p#"$ROOT"/}"
done
