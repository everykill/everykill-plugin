package everykill_legacy;

import java.util.Set;
import net.runelite.api.gameval.NpcID;

/**
 * NPCs that die at nonzero HP and despawn without ever being flagged dead -
 * they must be finished with a specific item (bag of salt, ice cooler, rock
 * hammer, fungicide) or they just stop retaliating and wander off instead.
 * RuneLite's own LootManager hardcodes this same list, which is itself
 * evidence the client API offers no general way to detect these deaths.
 *
 * <p>The list is cross-checked directly against {@code NpcID} in this client
 * version, not just the OSRS Wiki - verified 2026-08-14 that an AI-summarised
 * wiki fetch produced wrong location/variant labels when checked against the
 * actual client source (e.g. mislabelled {@code SLAYER_ROCKSLUG_BABY} as
 * "Giant rockslug (superior slayer monster)", which is actually a completely
 * different NPC, {@code SUPERIOR_ROCKSLUG}). Treat {@code NpcID} as ground
 * truth for "does this ID exist and share the naming family" and rely on
 * {@code npc.getName()} in the live kill/despawn logs to confirm the real
 * in-game display name, rather than trusting a summarised wiki description.
 *
 * <p>Excluded on purpose, but genuinely unverified rather than safely assumed:
 * the zygomite "_CAP" variants ({@code FOSSIL_ZYGOMITE_CAP}, {@code
 * SLAYER_MUTATED_ZYGOMITE_ADOLESCENT_CAP*}, {@code
 * SLAYER_MUTATED_ZYGOMITE_ADULT_CAP*}) look like the pre-combat capped
 * mushroom stage you interact with to spawn the real creature. Two different
 * outcomes are possible and only one is harmless: if the capped form is
 * attackable and transforms into the real zygomite via {@code NpcChanged},
 * the combat record carries forward and this exclusion is fine. If instead it
 * despawns and a separate NPC spawns in its place, the record is lost and the
 * kill goes unrecorded entirely - a silent miss, not a graceful fallback.
 * Cannot be tested on this account (needs 57 Slayer). Do not treat this
 * exclusion as verified-safe until someone can.
 *
 * See docs/spec-kill-detection.md, edge case A.
 */
final class TransformDeathNpcs
{
	static final Set<Integer> IDS = Set.of(
		// Gargoyles - rock hammer
		NpcID.SLAYER_GARGOYLE_1,
		NpcID.SLAYER_GARGOYLE_DEAD,
		NpcID.SLAYER_CAVE_GARGOYLE,
		NpcID.SUPERIOR_GARGOYLE,
		NpcID.SUPERIOR_GARGOYLE_DEAD,
		NpcID.LEAGUE_SUPERIOR_GARGOYLE,
		NpcID.LEAGUE_SUPERIOR_GARGOYLE_DEAD,
		NpcID.GARGBOSS_DUSK_PHASE4,
		NpcID.GARGBOSS_DUSK_DEATH,

		// Rockslugs - bag of salt
		NpcID.SLAYER_ROCKSLUG,
		NpcID.SLAYER_ROCKSLUG_BABY,
		NpcID.SLAYER_ROCKSLUG_CRYPT_OF_TONALI,
		NpcID.SUPERIOR_ROCKSLUG,
		NpcID.LEAGUE_SUPERIOR_ROCKSLUG,

		// Lizards - ice cooler
		NpcID.SLAYER_LIZARD_SMALL1_GREEN,
		NpcID.SLAYER_LIZARD_SMALL1_GREEN_LOWRANGE,
		NpcID.SLAYER_LIZARD_SMALL2_SANDY,
		NpcID.SLAYER_LIZARD_LARGE1_GREEN,
		NpcID.SLAYER_LIZARD_LARGE1_GREEN_LOWRANGE,
		NpcID.SLAYER_LIZARD_LARGE2_SANDY,
		NpcID.SLAYER_LIZARD_LARGE3_SANDY,
		NpcID.SLAYER_LIZARD_MASSIVE,

		// Zygomites - fungicide
		NpcID.SLAYER_MUTATED_ZYGOMITE_ADOLESCENT,
		NpcID.SLAYER_MUTATED_ZYGOMITE_ADULT,
		NpcID.FOSSIL_ZYGOMITE
	);

	/**
	 * Case-insensitive substrings of names known to belong to this monster
	 * family. Used only to flag a possible gap in {@link #IDS} in the review
	 * log - a name match here does not mean the NPC is a transform-death
	 * monster, only that it's worth a human looking at rather than assuming
	 * the review queue is noise.
	 */
	static final Set<String> FAMILY_NAME_HINTS = Set.of(
		"gargoyle", "rockslug", "lizard", "zygomite", "dusk"
	);

	private TransformDeathNpcs()
	{
	}
}
