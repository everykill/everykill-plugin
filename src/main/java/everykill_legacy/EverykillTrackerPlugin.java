package everykill_legacy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Provides;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.RuneLite;

@Slf4j
@PluginDescriptor(
	name = "Everykill",
	description = "Tracks XP rates, location, gear and loot, and writes them to a JSON snapshot",
	tags = {"xp", "rate", "slayer", "loot", "tracker"}
)
public class EverykillTrackerPlugin extends Plugin
{
	// --- LEGACY SCAFFOLDING (pre-rewrite, not part of the damage-first kill-
	// detection pipeline in BUILD-ORDER.md) -----------------------------------
	//
	// Everything below marked with this same "LEGACY" note - OUTPUT_DIR/
	// SNAPSHOT_FILE, the Slayer task regexes, gson/session/gistUploader/
	// killCounts/drops/currentTask/taskRemaining/lastAutoWrite fields,
	// writeSnapshot(), buildSnapshot(), readContainer(), the task-parsing half
	// of onChatMessage(), and onNpcLootReceived() - predates the CombatRecord/
	// openRecords state machine and was never rebuilt against it.
	//
	// BUILD-ORDER.md is explicit: "No storage, no upload, no UI until Step 8."
	// This code does all three already. It is left in place per a live
	// decision (2026-08-14, relayed from the other project half) rather than
	// removed mid-Step-4, but it must not be treated as part of the verified
	// pipeline - see the FINDINGS.md entry on the two parallel kill counters
	// for the concrete reason onNpcLootReceived's killCounts specifically
	// cannot be trusted. Resolve or remove before Step 8 (upload).
	// ---------------------------------------------------------------------

	private static final Path OUTPUT_DIR = RuneLite.RUNELITE_DIR.toPath().resolve("everykill-plugin");
	private static final String SNAPSHOT_FILE = "snapshot.json"; // LEGACY

	// LEGACY - "Your new task is to kill 73 Bloodveld." / "You are assigned to kill 15 Crawling Hands; only 15 more to go."
	private static final Pattern NEW_TASK = Pattern.compile(
		"(?:Your new task is to kill|You are assigned to kill)\\s+(\\d+)\\s+([A-Za-z' ]+?)[.;]");
	private static final Pattern TASK_REMAINING = Pattern.compile(
		"You're assigned to kill\\s+([A-Za-z' ]+?);\\s+only\\s+(\\d+)\\s+more to go");
	private static final Pattern TASK_COMPLETE = Pattern.compile(
		"You've completed (?:your task|[\\d,]+ tasks?)");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EverykillTrackerConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private GistUploader gistUploader; // LEGACY - arbitrary user-supplied upload URL, predates the opt-in/warning-text upload design in docs/PROJECT.md hard constraint 4 and BUILD-ORDER.md Step 8

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create(); // LEGACY
	private final XpSession session = new XpSession(); // LEGACY

	/** LEGACY - keyed by name, not npc_id; driven by NpcLootReceived, not the damage-first state machine. See FINDINGS.md. */
	private final Map<String, Integer> killCounts = new TreeMap<>();

	/** LEGACY - every notable drop received this session, newest last. */
	private final List<Map<String, Object>> drops = new ArrayList<>();

	/**
	 * Open combat records, keyed on the NPC's runtime reference (identity, not
	 * equals/hashCode - two different NPCs must never collide here even if they
	 * momentarily share an id or name). See {@link CombatRecord}.
	 */
	final Map<NPC, CombatRecord> openRecords = new IdentityHashMap<>();

	/**
	 * Tiles an item landed on this tick, for the transform-death coincidence
	 * check (Step 3). Cleared every {@code GameTick}, same as RuneLite's own
	 * {@code LootManager} - items spawned this tick are only relevant to a
	 * despawn seen this same tick.
	 */
	private final Set<WorldPoint> itemSpawnPointsThisTick = new HashSet<>();

	private String currentTask = null; // LEGACY
	private int taskRemaining = 0; // LEGACY
	private boolean seeded = false; // LEGACY (XpSession baseline)
	private long lastAutoWrite = 0L; // LEGACY

	@Provides
	EverykillTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(EverykillTrackerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		Files.createDirectories(OUTPUT_DIR);
		log.info("Everykill started, writing to {}", OUTPUT_DIR);
	}

	@Override
	protected void shutDown()
	{
		writeSnapshot("shutdown");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		// Never carry a combat record through a scene reload - hop, world
		// change, login, disconnect. Guessing whether a fight in progress is
		// still valid is worse than just losing that one kill.
		if (!openRecords.isEmpty())
		{
			log.debug("Dropping {} open combat record(s) on game state change to {}", openRecords.size(), state);
			openRecords.clear();
		}

		if (state == GameState.LOGGING_IN || state == GameState.HOPPING)
		{
			// Skill XP arrives in a burst right after login. Re-seed so that
			// burst isn't counted as a gain.
			seeded = false;
		}
		else if (state == GameState.LOGIN_SCREEN && config.writeOnLogout())
		{
			writeSnapshot("logout");
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		int xp = event.getXp();

		if (!seeded)
		{
			session.seed(skill, xp);
			return;
		}

		session.record(skill, xp, Instant.now());
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) actor;
		Hitsplat hitsplat = event.getHitsplat();

		CombatRecord record = openRecords.get(npc);
		if (record == null)
		{
			// Someone else's hitsplat on an NPC we haven't engaged yet. Not our
			// fight (or not yet) - nothing to open a record for.
			if (!hitsplat.isMine())
			{
				return;
			}
			record = new CombatRecord(npc, client.getTickCount());
			openRecords.put(npc, record);
		}

		record.damageTotalSinceEngaged += hitsplat.getAmount();
		if (hitsplat.isMine())
		{
			int amount = hitsplat.getAmount();
			record.damageByPlayer += amount;

			// spec-performance.md §2: attempt counts, not just damage totals -
			// a zero-damage hitsplat (block, magic splash) still counts as an
			// attack, and is exactly what makes observed accuracy meaningful
			// for Magic. Never skip incrementing attacksCount just because
			// amount is 0.
			record.attacksCount++;
			if (amount > 0)
			{
				record.hitsCount++;
				record.maxHit = Math.max(record.maxHit, amount);
			}
		}

		// Post-ActorDeath survival check (spec-kill-detection.md edge case
		// A1): a real kill means the actor is gone. Any hitsplat landing
		// after ActorDeath already fired means it's still very much alive -
		// logging only, no behaviour change, to see how often this fires and
		// on what before it influences any detection logic.
		if (record.actorDeathSeen)
		{
			log.debug("Suspected post-ActorDeath hitsplat: npc_id={} name={} ticksSinceActorDeath={} hitsplatAmount={} isMine={}",
				record.npcId, record.npcName, client.getTickCount() - record.actorDeathTick,
				hitsplat.getAmount(), hitsplat.isMine());
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) actor;

		// ActorDeath fires for every NPC death in the loaded scene, including
		// kills that aren't ours. No open record means it isn't our kill.
		// Don't remove the record yet - it's left in place (marked) so
		// onNpcDespawned can tell whether isDead() also fires for this same
		// actor, which is the empirical question BUILD-ORDER Step 2 asks us
		// to answer. The record is cleaned up when the NPC actually despawns.
		CombatRecord record = openRecords.get(npc);
		if (record == null)
		{
			return;
		}

		record.actorDeathSeen = true;
		record.actorDeathTick = client.getTickCount();

		if (TransformDeathNpcs.IDS.contains(npc.getId()))
		{
			// Verified 2026-08-14: ActorDeath fires for these NPCs the moment
			// their health ratio hits zero, not when they actually die - a
			// rockslug at 0 HP without salt is still alive and needs the item
			// to be finished off. Never trust this signal for this list; log
			// it for visibility (see onNpcDespawned test 2) but do not count
			// it as a kill.
			log.debug("ActorDeath fired for a transform-death npc (ignored, not a real death): npc_id={} name={} damageByPlayer={}",
				record.npcId, record.npcName, record.damageByPlayer);
			return;
		}

		emitKillIfOurs(npc, record, "ActorDeath", null);
	}

	/**
	 * Step 4 - phase transitions (spec-kill-detection.md edge case B). Same
	 * actor, new npc_id: carry the record forward, never emit a kill here.
	 *
	 * <p>This reuses the post-ActorDeath survival detector built for edge case
	 * A2 rather than a separate phase-specific guard, per FINDINGS.md - both
	 * edge cases produce the identical signature (ActorDeath fires at
	 * health-ratio-zero while the actor is still alive), so one mechanism
	 * covers both. If {@code actorDeathSeen} is set on this record, ActorDeath
	 * already lied to us once this fight (a false death at a phase boundary,
	 * the same bug as the rockslug finding); NpcChanged carrying the actor
	 * forward is direct proof it lied, so the flag is cleared and the fight
	 * continues under the same record rather than having been wrongly emitted
	 * as a kill.
	 *
	 * <p><b>Known gap, not covered by this:</b> the Kalphite Queen / Zalcano
	 * reports (runelite/runelite#15394, #16479) describe a phase invulnerability
	 * window where health can hit zero and regenerate on the *same* npc_id, with
	 * no NpcChanged at all. That case has no id change for this handler to key
	 * off, and cannot be tested on this account. Untested, not assumed safe -
	 * see spec-kill-detection.md edge case B.
	 */
	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		NPC npc = event.getNpc();
		CombatRecord record = openRecords.get(npc);
		if (record == null)
		{
			return;
		}

		if (record.actorDeathSeen)
		{
			log.debug("NpcChanged after a false ActorDeath (phase transition, not a real death): old_npc_id={} old_name={} new_npc_id={} ticksSinceActorDeath={}",
				record.npcId, record.npcName, npc.getId(), client.getTickCount() - record.actorDeathTick);
			record.actorDeathSeen = false;
			record.actorDeathTick = -1;
		}
		else
		{
			log.debug("NpcChanged carrying record forward (phase transition): old_npc_id={} old_name={} new_npc_id={}",
				record.npcId, record.npcName, npc.getId());
		}

		record.retarget(npc);
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		CombatRecord record = openRecords.remove(npc);
		if (record == null)
		{
			return;
		}

		if (TransformDeathNpcs.IDS.contains(npc.getId()))
		{
			// Neither ActorDeath nor isDead() can be trusted for this list -
			// both can fire well before the NPC is actually finished off (see
			// the 2026-08-14 finding above). The only signal that counts is a
			// despawn with an item landing on its tile(s) this same tick,
			// checked unconditionally, regardless of what record.actorDeathSeen
			// says.
			//
			// This means a lootless finish (e.g. no drop table roll) is
			// currently a false negative - undercounted, not miscounted. Log
			// every transform-list despawn unconditionally, counted or not, so
			// that rate can actually be measured against a hand count rather
			// than assumed.
			// getWorldArea() can return null for a despawning NPC (it's leaving
			// the scene) - treat that as "can't confirm coincidence" rather than
			// letting a null area NPE the most important detection path in the
			// plugin. Same outcome as no item found: counted=false, logged, never
			// silently dropped.
			WorldArea area = npc.getWorldArea();
			boolean itemCoincidence = area != null && itemSpawnPointsThisTick.stream().anyMatch(area::contains);
			boolean counted = itemCoincidence && record.damageByPlayer > 0;
			int ticksSinceActorDeath = record.actorDeathTick < 0 ? -1 : client.getTickCount() - record.actorDeathTick;

			log.debug("Transform despawn: npc_id={} name={} counted={} itemCoincidence={} damageByPlayer={} actorDeathSeen={} ticksSinceActorDeath={}",
				record.npcId, record.npcName, counted, itemCoincidence, record.damageByPlayer, record.actorDeathSeen, ticksSinceActorDeath);

			if (counted)
			{
				emitKillIfOurs(npc, record, "transform-death", "inferred");
			}
			return;
		}

		if (record.actorDeathSeen)
		{
			// Both signals fired for the same kill - the kill was already
			// emitted from onActorDeath. Log the confirmation so repeated runs
			// can tell us whether this always happens or only sometimes.
			// ticksSinceActorDeath is the post-ActorDeath survival check
			// (edge case A1) applied to every normal kill too, not just
			// transform-death ones - this builds the baseline for what a
			// "normal" delay looks like, so an unusually large value on an
			// unlisted NPC later becomes recognisable as suspicious.
			log.debug("Despawn also fired for an already-killed npc: npc_id={} name={} despawnIsDead={} ticksSinceActorDeath={}",
				record.npcId, record.npcName, npc.isDead(), client.getTickCount() - record.actorDeathTick);
			return;
		}

		if (npc.isDead())
		{
			// ActorDeath never fired for this actor, but the despawn is
			// flagged dead - the isDead() fallback path from Step 2.
			emitKillIfOurs(npc, record, "despawn-fallback", null);
			return;
		}

		// Despawned without ever being flagged dead, and no ActorDeath either,
		// and not on the transform-death list. We damaged this NPC and it
		// vanished without a death flag - something unusual happened. Could be
		// a transform-death NPC we don't know about yet, could be a walk-off
		// we misjudged. Flag it for manual review; never auto-count it.
		if (record.damageByPlayer > 0)
		{
			boolean possibleTransformDeathGap = TransformDeathNpcs.FAMILY_NAME_HINTS.stream()
				.anyMatch(hint -> record.npcName.toLowerCase().contains(hint));

			log.debug("Review: non-death despawn with our damage on an unlisted npc: npc_id={} name={} damageByPlayer={} possibleTransformDeathGap={}",
				record.npcId, record.npcName, record.damageByPlayer, possibleTransformDeathGap);
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		itemSpawnPointsThisTick.add(event.getTile().getWorldLocation());
	}

	/**
	 * Temporary discovery logging for the transform-death death-animation
	 * project (see spec-kill-detection.md edge case A). Logs every animation
	 * change for any NPC on {@link TransformDeathNpcs}, with health ratio and
	 * tick alongside the animation id, so the real finishing animation can be
	 * told apart from ordinary combat/idle animations by watching for the one
	 * that lands right as the NPC despawns.
	 *
	 * <p><b>Removal trigger (explicit, so this doesn't drift into a shipped
	 * build):</b> delete this subscriber the moment a real death-animation id
	 * is identified and confirmed (per edge case A's negative-control
	 * criterion) and wired into the actual detection logic. Not before -
	 * removing it early loses the only data source for finding those ids.
	 * Currently scoped to {@link TransformDeathNpcs} only, so load is fine as
	 * long as it stays scoped that way.
	 */
	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) actor;
		if (!TransformDeathNpcs.IDS.contains(npc.getId()))
		{
			return;
		}

		log.debug("Transform-death npc animation: npc_id={} name={} animationId={} healthRatio={} tick={}",
			npc.getId(), npc.getName(), npc.getAnimation(), npc.getHealthRatio(), client.getTickCount());
	}

	/**
	 * Log a kill line for a record we know is ours (a record only exists
	 * because of at least one of our own hitsplats - see {@link
	 * #onHitsplatApplied}). A zero-damage-only record (all blocks/splashes)
	 * is still emitted, graded {@code inferred} rather than dropped.
	 */
	private void emitKillIfOurs(NPC npc, CombatRecord record, String source, String confidence)
	{
		// A record only exists because of an isMine() hitsplat, so
		// attacksCount is never 0 here. damageByPlayer <= 0 means every one
		// of our hitsplats was a 0-damage block or magic splash - per
		// spec-performance.md §2, those are now load-bearing attempts, not
		// noise, so the kill is emitted rather than dropped. Grade it
		// inferred rather than trusting the caller's confidence: we can't
		// distinguish "we fought the whole thing and got blocked/splashed
		// throughout" from "someone/something else did the real damage while
		// we barely grazed it" (damageTotalSinceEngaged may include real
		// foreign damage either way). Never guess, never silently drop.
		if (record.damageByPlayer <= 0)
		{
			confidence = "inferred";
		}

		int durationTicks = client.getTickCount() - record.openedAtTick;
		if (confidence == null)
		{
			log.debug("Kill: source={} npc_id={} name={} damageByPlayer={} damageTotalSinceEngaged={} ourAttacks={} ourHits={} ourMaxHit={} weaponSpeedTicks={} durationTicks={}",
				source, record.npcId, record.npcName, record.damageByPlayer, record.damageTotalSinceEngaged,
				record.attacksCount, record.hitsCount, record.maxHit, record.weaponSpeedTicks, durationTicks);
		}
		else
		{
			log.debug("Kill: source={} confidence={} npc_id={} name={} damageByPlayer={} damageTotalSinceEngaged={} ourAttacks={} ourHits={} ourMaxHit={} weaponSpeedTicks={} durationTicks={}",
				source, confidence, record.npcId, record.npcName, record.damageByPlayer, record.damageTotalSinceEngaged,
				record.attacksCount, record.hitsCount, record.maxHit, record.weaponSpeedTicks, durationTicks);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		// Item spawns are only relevant to a despawn seen the same tick -
		// clear before the next tick's spawns start accumulating.
		itemSpawnPointsThisTick.clear();

		if (!seeded && client.getGameState() == GameState.LOGGED_IN)
		{
			// One full tick after login has passed, so the XP burst is done.
			for (Skill skill : Skill.values())
			{
				session.seed(skill, client.getSkillExperience(skill));
			}
			seeded = true;
			log.debug("Seeded XP baseline");
			return;
		}

		Instant now = Instant.now();

		if (session.isActive() && session.isIdle(now, config.sessionIdleMinutes()))
		{
			log.debug("Session idle, resetting. Gained {} xp over {}",
				session.totalGained(), session.elapsed(now));
			writeSnapshot("session-end");
			session.reset();
			killCounts.clear();
			drops.clear();
		}

		int autoMinutes = config.autoWriteMinutes();
		if (autoMinutes > 0 && session.isActive())
		{
			long elapsedMs = System.currentTimeMillis() - lastAutoWrite;
			if (elapsedMs >= autoMinutes * 60_000L)
			{
				writeSnapshot("auto");
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.NPC_EXAMINE
			&& event.getType() != ChatMessageType.DIALOG)
		{
			return;
		}

		String message = event.getMessage().replaceAll("<[^>]*>", "");

		Matcher newTask = NEW_TASK.matcher(message);
		if (newTask.find())
		{
			taskRemaining = Integer.parseInt(newTask.group(1));
			currentTask = newTask.group(2).trim();
			session.setLabel(currentTask);
			log.debug("New task: {} x{}", currentTask, taskRemaining);
			return;
		}

		Matcher remaining = TASK_REMAINING.matcher(message);
		if (remaining.find())
		{
			currentTask = remaining.group(1).trim();
			taskRemaining = Integer.parseInt(remaining.group(2));
			session.setLabel(currentTask);
			return;
		}

		if (TASK_COMPLETE.matcher(message).find())
		{
			log.debug("Task complete: {}", currentTask);
			writeSnapshot("task-complete");
			currentTask = null;
			taskRemaining = 0;
		}
	}

	/**
	 * LEGACY - see the top-of-class note. This is the second, untrusted kill
	 * counter FINDINGS.md flags: {@code NpcLootReceived} comes from RuneLite's
	 * own {@code LootManager}, which uses the same item-coincidence heuristic
	 * Step 3 found unreliable for transform-death monsters (silent on lootless
	 * kills) - and keys counts on {@code npcName} rather than {@code npc_id},
	 * contradicting the "store raw npc_id forever" rule. Do not treat
	 * {@code killCounts} as agreeing with (or correcting) the damage-first
	 * {@code openRecords} pipeline. Must be removed or reconciled before Step 8.
	 */
	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (!config.trackLoot())
		{
			return;
		}

		String npcName = event.getNpc().getName();
		if (npcName == null)
		{
			return;
		}

		killCounts.merge(npcName, 1, Integer::sum);

		if (taskRemaining > 0 && npcName.equalsIgnoreCase(singularise(currentTask)))
		{
			taskRemaining--;
		}

		for (ItemStack stack : event.getItems())
		{
			ItemComposition comp = itemManager.getItemComposition(stack.getId());
			Map<String, Object> drop = new LinkedHashMap<>();
			drop.put("time", Instant.now().toString());
			drop.put("from", npcName);
			drop.put("item", comp.getName());
			drop.put("quantity", stack.getQuantity());
			drop.put("alchValue", comp.getHaPrice());
			drops.add(drop);
		}

		// Keep memory bounded on long sessions.
		while (drops.size() > 500)
		{
			drops.remove(0);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// Nothing to do per-change; containers are read on demand when writing
		// a snapshot. Subscribed so the hook exists if per-change diffing is
		// wanted later.
	}

	/**
	 * LEGACY - see the top-of-class note. Storage and upload before Step 8,
	 * kept only per the 2026-08-14 decision to leave it labelled rather than
	 * remove it mid-Step-4. Safe to call from any thread; container reads are
	 * marshalled onto the client thread.
	 */
	public void writeSnapshot(String reason)
	{
		clientThread.invoke(() ->
		{
			try
			{
				Map<String, Object> snapshot = buildSnapshot(reason);
				String json = gson.toJson(snapshot);

				Path target = OUTPUT_DIR.resolve(SNAPSHOT_FILE);
				Files.write(target, json.getBytes(StandardCharsets.UTF_8));
				lastAutoWrite = System.currentTimeMillis();

				if (config.gistEnabled())
				{
					gistUploader.upload(config.gistId(), config.gistToken(), SNAPSHOT_FILE, json);
				}

				log.debug("Wrote snapshot ({}) to {}", reason, target);
			}
			catch (IOException e)
			{
				log.warn("Could not write snapshot", e);
			}
		});
	}

	private Map<String, Object> buildSnapshot(String reason)
	{
		Instant now = Instant.now();
		Map<String, Object> root = new LinkedHashMap<>();

		root.put("generatedAt", now.toString());
		root.put("reason", reason);

		Player local = client.getLocalPlayer();
		root.put("player", local == null ? "unknown" : local.getName());

		// --- Skills ---
		Map<String, Object> skills = new LinkedHashMap<>();
		int totalLevel = 0;
		long totalXp = 0;
		for (Skill skill : Skill.values())
		{
			int level = client.getRealSkillLevel(skill);
			int xp = client.getSkillExperience(skill);
			totalLevel += level;
			totalXp += xp;

			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("level", level);
			entry.put("xp", xp);
			entry.put("xpToNextLevel", xpToNextLevel(xp));
			skills.put(skill.getName(), entry);
		}
		root.put("skills", skills);
		root.put("totalLevel", totalLevel);
		root.put("totalXp", totalXp);
		root.put("combatLevel", local == null ? 0 : local.getCombatLevel());

		// --- XP session ---
		Map<String, Object> sessionData = new LinkedHashMap<>();
		sessionData.put("label", session.getLabel());
		sessionData.put("active", session.isActive());
		sessionData.put("startedAt", session.getStartedAt() == null ? null : session.getStartedAt().toString());
		sessionData.put("elapsedSeconds", session.elapsed(now).getSeconds());
		sessionData.put("totalXpGained", session.totalGained());
		sessionData.put("totalXpPerHour", session.totalRatePerHour(now));

		Map<String, Object> perSkill = new LinkedHashMap<>();
		for (Map.Entry<Skill, Integer> entry : session.gains().entrySet())
		{
			Map<String, Object> skillRate = new LinkedHashMap<>();
			skillRate.put("gained", entry.getValue());
			skillRate.put("perHour", session.ratePerHour(entry.getKey(), now));
			perSkill.put(entry.getKey().getName(), skillRate);
		}
		sessionData.put("bySkill", perSkill);
		root.put("session", sessionData);

		// --- Slayer task ---
		Map<String, Object> task = new LinkedHashMap<>();
		task.put("name", currentTask);
		task.put("remaining", taskRemaining);
		root.put("task", task);

		// --- Location ---
		if (config.trackLocation() && local != null)
		{
			WorldPoint wp = local.getWorldLocation();
			Map<String, Object> location = new LinkedHashMap<>();
			location.put("x", wp.getX());
			location.put("y", wp.getY());
			location.put("plane", wp.getPlane());
			location.put("regionId", wp.getRegionID());
			location.put("world", client.getWorld());
			root.put("location", location);
		}

		// --- Gear ---
		if (config.trackGear())
		{
			root.put("equipment", readContainer(net.runelite.api.InventoryID.EQUIPMENT));
			root.put("inventory", readContainer(net.runelite.api.InventoryID.INVENTORY));
		}

		// --- Kills and loot ---
		if (config.trackLoot())
		{
			root.put("killCounts", new LinkedHashMap<>(killCounts));
			root.put("drops", new ArrayList<>(drops));
		}

		return root;
	}

	private List<Map<String, Object>> readContainer(net.runelite.api.InventoryID id)
	{
		List<Map<String, Object>> items = new ArrayList<>();
		ItemContainer container = client.getItemContainer(id);
		if (container == null)
		{
			return items;
		}

		for (Item item : container.getItems())
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			ItemComposition comp = itemManager.getItemComposition(item.getId());
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", item.getId());
			entry.put("name", comp.getName());
			entry.put("quantity", item.getQuantity());
			items.add(entry);
		}
		return items;
	}

	/**
	 * XP remaining until the next level. Uses the standard OSRS XP curve.
	 */
	static int xpToNextLevel(int xp)
	{
		int level = 1;
		while (level < 126 && xpForLevel(level + 1) <= xp)
		{
			level++;
		}
		if (level >= 126)
		{
			return 0;
		}
		return xpForLevel(level + 1) - xp;
	}

	static int xpForLevel(int level)
	{
		double points = 0;
		for (int i = 1; i < level; i++)
		{
			points += Math.floor(i + 300 * Math.pow(2, i / 7.0));
		}
		return (int) Math.floor(points / 4);
	}

	/** "Bloodveld" from "Bloodvelds", so task names match NPC names. */
	private static String singularise(String name)
	{
		if (name == null)
		{
			return "";
		}
		String trimmed = name.trim();
		if (trimmed.endsWith("s") && trimmed.length() > 1)
		{
			return trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}
}
