/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import com.everykill.model.Confidence;
import com.everykill.model.DeathSignal;
import com.everykill.model.KillRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Batching policy. No server involved, which is the point — the queue has to be
 * right before there is anything to send it to.
 */
public class PendingKillsTest
{
	private PendingKills queue;

	@Before
	public void setUp()
	{
		queue = new PendingKills();
	}

	private static KillRecord kill(String id)
	{
		return new KillRecord(id, 7271, "Cyclops", 56, 6556,
			Confidence.UNCONTESTED, DeathSignal.OBSERVED, 75, 0, 9, 7, 17,
			System.currentTimeMillis());
	}

	@Test
	public void aBatchIsCappedAndComesOutOldestFirst()
	{
		for (int i = 0; i < PendingKills.BATCH_SIZE + 20; i++)
		{
			queue.add(kill("k" + i));
		}

		final List<KillRecord> batch = queue.peekBatch();
		Assert.assertEquals(PendingKills.BATCH_SIZE, batch.size());
		Assert.assertEquals("k0", batch.get(0).eventId);
		Assert.assertEquals("oldest first, so a stalled queue drains in order",
			"k1", batch.get(1).eventId);
	}

	@Test
	public void peekingDoesNotRemoveAnything()
	{
		// the whole reason it's a peek: a failed send has to leave the kills queued,
		// and taking them first means a crash mid-request loses them outright.
		queue.add(kill("a"));
		queue.add(kill("b"));

		queue.peekBatch();
		queue.peekBatch();

		Assert.assertEquals(2, queue.size());
	}

	@Test
	public void acknowledgingRemovesOnlyWhatWasSent()
	{
		queue.add(kill("a"));
		queue.add(kill("b"));
		queue.add(kill("c"));

		final List<KillRecord> batch = new ArrayList<>();
		batch.add(queue.peekBatch().get(0));

		queue.acknowledge(batch);

		Assert.assertEquals(2, queue.size());
		Assert.assertEquals("b", queue.peekBatch().get(0).eventId);
	}

	@Test
	public void aKillAddedMidFlightIsNotAcknowledgedByAccident()
	{
		// the trap this design exists to avoid. send a batch, a kill lands while the
		// request is open, then the ack arrives. only the sent kills may leave.
		queue.add(kill("a"));
		queue.add(kill("b"));

		final List<KillRecord> inFlight = queue.peekBatch();

		queue.add(kill("late"));
		queue.acknowledge(inFlight);

		Assert.assertEquals(1, queue.size());
		Assert.assertEquals("late", queue.peekBatch().get(0).eventId);
	}

	@Test
	public void afullQueueDropsTheOldestNotTheNewest()
	{
		for (int i = 0; i < PendingKills.MAX_PENDING + 5; i++)
		{
			queue.add(kill("k" + i));
		}

		Assert.assertEquals(PendingKills.MAX_PENDING, queue.size());
		Assert.assertEquals("the oldest five are gone", "k5", queue.peekBatch().get(0).eventId);
		Assert.assertEquals(5, queue.getDropped());
	}

	@Test
	public void droppedKillsAreCountedNotSwallowed()
	{
		// if the queue is shedding records the user is entitled to know their data
		// isn't all arriving. a silent drop is the worst version of this.
		Assert.assertEquals(0, queue.getDropped());

		for (int i = 0; i < PendingKills.MAX_PENDING + 3; i++)
		{
			queue.add(kill("k" + i));
		}

		Assert.assertEquals(3, queue.getDropped());
	}

	@Test
	public void aQueueSurvivesARoundTripThroughDisk()
	{
		queue.add(kill("a"));
		queue.add(kill("b"));

		final List<KillRecord> saved = queue.snapshot();

		final PendingKills restored = new PendingKills();
		restored.restore(saved);

		Assert.assertEquals(2, restored.size());
		Assert.assertEquals("a", restored.peekBatch().get(0).eventId);
	}

	@Test
	public void anEmptyQueueYieldsAnEmptyBatchNotNull()
	{
		Assert.assertTrue(queue.isEmpty());
		Assert.assertTrue(queue.peekBatch().isEmpty());
	}
}
