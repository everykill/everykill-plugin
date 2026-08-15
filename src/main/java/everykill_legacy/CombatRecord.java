package everykill_legacy;

import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;

/**
 * Tracks damage dealt to a single NPC, from the first tick we hit it until it
 * dies or the record is dropped. One of these exists per NPC we're currently
 * fighting, keyed on the NPC's runtime reference (see
 * {@link EverykillTrackerPlugin#openRecords}).
 */
class CombatRecord
{
	// Not final: NpcChanged (Step 4, phase transitions) retargets these to the
	// new phase's id/name in place, same record, same map key (the NPC object
	// reference is stable across a phase change - see retarget()).
	int npcId;
	String npcName;
	final int regionId;
	final int openedAtTick;

	int damageByPlayer;

	/**
	 * Sum of every hitsplat landed since this record opened - ours and
	 * foreign alike. Named deliberately, not just "damageTotal": the record
	 * opens on our first hitsplat, so this misses any foreign damage the NPC
	 * took *before* we engaged. Fine for now (nothing reads it as "the NPC's
	 * full lifetime damage"), but Step 8's `exact` grade ("we dealt 100% of
	 * damage") must not compare this against the NPC's max HP as if it were -
	 * it can only ever prove 100% of damage *since we engaged*, not that no
	 * one else touched it earlier. See docs/FINDINGS.md.
	 */
	int damageTotalSinceEngaged;

	/**
	 * Count of our own hitsplats since this record opened, including
	 * zero-damage ones (blocks, magic splashes). Added 2026-08-14 per
	 * spec-performance.md §2 - observed accuracy needs attempt counts, not
	 * just damage totals, and this cannot be backfilled once a kill is gone.
	 */
	int attacksCount;

	/** Count of our own hitsplats with damage &gt; 0. See {@link #attacksCount}. */
	int hitsCount;

	/** Largest single one of our hitsplats this fight. See {@link #attacksCount}. */
	int maxHit;

	/**
	 * Attack speed in ticks of the weapon used. **Not yet populated** - left
	 * at -1 (unknown) because this plugin has no weapon-speed data source at
	 * all yet (no loadout/equipment tracking is wired into the real pipeline,
	 * and RuneLite's {@code ItemComposition} doesn't expose attack speed
	 * directly). Populating this needs its own small data-sourcing decision,
	 * the same shape of problem as the Step 0a NPC stat table but for
	 * weapons - not something to improvise inline. Field kept here (rather
	 * than added later) so the eventual kill_event shape in
	 * spec-data-model.md is already reflected in code, per spec-performance.md
	 * §2's "record it now" urgency, even though the value itself is a
	 * placeholder.
	 */
	int weaponSpeedTicks = -1;

	/**
	 * Set once {@code ActorDeath} has fired for this NPC. Lets the despawn
	 * handler tell an already-emitted kill apart from one it needs to emit
	 * itself via the {@code isDead()} fallback path (see BUILD-ORDER Step 2).
	 */
	boolean actorDeathSeen;

	/**
	 * The tick {@code ActorDeath} fired, -1 if it hasn't. Used by the
	 * post-ActorDeath survival check (spec-kill-detection.md edge case A1) -
	 * a real kill means the actor is gone, so any further hitsplat or a
	 * despawn much later than this is suspicious, regardless of whether the
	 * NPC is on {@link TransformDeathNpcs}.
	 */
	int actorDeathTick = -1;

	CombatRecord(NPC npc, int currentTick)
	{
		retarget(npc);
		WorldPoint location = npc.getWorldLocation();
		this.regionId = location == null ? -1 : location.getRegionID();
		this.openedAtTick = currentTick;
	}

	/**
	 * Point this record at the NPC's current id/name. Called from the
	 * constructor, and again from {@code onNpcChanged} (Step 4) when a phase
	 * transition changes the actor's id without it being a new kill - the
	 * record's npc_id/name need to reflect whichever phase is current, not
	 * whichever phase we first hit.
	 */
	void retarget(NPC npc)
	{
		this.npcId = npc.getId();
		String name = npc.getName();
		this.npcName = name == null ? "Unknown" : name;
	}
}
