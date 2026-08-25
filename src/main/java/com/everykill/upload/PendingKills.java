/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import com.everykill.model.KillRecord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Kills waiting to be uploaded.
 *
 * <p>Deliberately knows nothing about HTTP. It holds records, hands out batches, and
 * takes them back when a send fails — so the batching policy can be tested without a
 * server, which matters because there isn't one yet.
 *
 * <p><b>Bounded on purpose.</b> A player who never enables upload, or plays for a
 * week with the backend down, must not accumulate an unbounded queue in memory. When
 * it is full the OLDEST records are dropped: a two-week-old kill is worth less than
 * the one that just happened, and silently growing until the client dies is worse
 * than either.
 *
 * <p>Not thread-safe by itself. Every method is called under the owner's lock — see
 * {@link UploadQueue} usage in the plugin.
 */
public class PendingKills
{
	/**
	 * Records held before the oldest start falling off.
	 *
	 * <p>10k kills at roughly 300 bytes each is about 3MB — enough for days of heavy
	 * play, small enough that a stalled backend can't eat the heap.
	 */
	public static final int MAX_PENDING = 10_000;

	/**
	 * Kills per upload. Small enough that one failure doesn't cost much, large enough
	 * that a busy session doesn't make a request per kill.
	 */
	public static final int BATCH_SIZE = 50;

	private final Deque<KillRecord> pending = new ArrayDeque<>();

	private int dropped;

	/** Queues a kill. Drops the oldest if that would overflow. */
	public void add(KillRecord kill)
	{
		while (pending.size() >= MAX_PENDING)
		{
			pending.pollFirst();
			dropped++;
		}
		pending.addLast(kill);
	}

	/**
	 * The next batch, oldest first, without removing it.
	 *
	 * <p>Peek rather than take: a batch that fails to send has to go back, and taking
	 * it first means a crash between take and failure loses the kills outright.
	 */
	public List<KillRecord> peekBatch()
	{
		final List<KillRecord> batch = new ArrayList<>(Math.min(BATCH_SIZE, pending.size()));
		for (KillRecord kill : pending)
		{
			if (batch.size() >= BATCH_SIZE)
			{
				break;
			}
			batch.add(kill);
		}
		return batch;
	}

	/**
	 * Removes a batch the server accepted.
	 *
	 * <p>Matches on identity from the front rather than trusting the caller's list to
	 * still line up — kills can have been added while the request was in flight.
	 */
	public void acknowledge(List<KillRecord> sent)
	{
		for (KillRecord kill : sent)
		{
			if (!pending.isEmpty() && pending.peekFirst() == kill)
			{
				pending.pollFirst();
			}
		}
	}

	public int size()
	{
		return pending.size();
	}

	public boolean isEmpty()
	{
		return pending.isEmpty();
	}

	/**
	 * Kills lost to overflow this session.
	 *
	 * <p>Surfaced rather than swallowed. If the queue is shedding records the user is
	 * entitled to know their data isn't all arriving.
	 */
	public int getDropped()
	{
		return dropped;
	}

	/** Everything queued, oldest first — for persisting across a restart. */
	public List<KillRecord> snapshot()
	{
		return new ArrayList<>(pending);
	}

	/** Restores a persisted queue. Replaces whatever is held. */
	public void restore(List<KillRecord> kills)
	{
		pending.clear();
		for (KillRecord kill : kills)
		{
			add(kill);
		}
	}

	public void clear()
	{
		pending.clear();
	}
}
