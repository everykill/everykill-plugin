/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import com.everykill.EverykillConfig;
import com.everykill.model.KillRecord;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the upload loop: hold kills, register once, flush on a timer.
 *
 * <p>Nothing here blocks the client thread. Disk reads and HTTP both run on the
 * scheduler or OkHttp's pool, and {@link #shutDown()} cancels rather than waits.
 */
@Slf4j
@Singleton
public class UploadService
{
	/** How often we look at the queue. The contract allows 5 requests per 60s. */
	private static final long FLUSH_SECONDS = 180L;

	/** First flush waits, so a login burst doesn't upload before the panel is up. */
	private static final long FIRST_FLUSH_SECONDS = 60L;

	private final EverykillConfig config;
	private final UploadClient client;
	private final UploadIdentity identity;
	private final ScheduledExecutorService executor;
	private final PendingKills pending = new PendingKills();

	/** One flush at a time. Two in flight would ack each other's records. */
	private final AtomicBoolean inFlight = new AtomicBoolean();

	private ScheduledFuture<?> task;

	/** Earliest millis we may send again, honouring the server's own retryAfter. */
	private volatile long nextAllowedMillis;

	@Getter
	private volatile String status = "Not uploading";

	@Inject
	UploadService(EverykillConfig config, UploadClient client, UploadIdentity identity,
		ScheduledExecutorService executor)
	{
		this.config = config;
		this.client = client;
		this.identity = identity;
		this.executor = executor;
	}

	/** Queues a kill. Cheap and non-blocking — safe from the client thread. */
	public void offer(KillRecord kill)
	{
		if (!config.uploadEnabled())
		{
			// dropped on purpose. queueing while disabled would upload a backlog the
			// moment someone toggles it on, which is not what toggling it on means.
			return;
		}

		synchronized (pending)
		{
			pending.add(kill);
		}
	}

	public void startUp()
	{
		// identity.load() touches disk, so it goes on the scheduler rather than here -
		// startUp() runs on the client thread.
		task = executor.scheduleWithFixedDelay(this::flush,
			FIRST_FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
	}

	/** Cancels the timer without blocking. The executor belongs to RuneLite. */
	public void shutDown()
	{
		if (task != null)
		{
			task.cancel(false);
			task = null;
		}
	}

	private void flush()
	{
		if (!config.uploadEnabled() || config.uploadUrl().trim().isEmpty())
		{
			status = "Uploading is off";
			return;
		}

		if (System.currentTimeMillis() < nextAllowedMillis)
		{
			return;
		}

		if (!inFlight.compareAndSet(false, true))
		{
			return;
		}

		try
		{
			if (identity.getClientId() == null)
			{
				identity.load();
			}

			if (!identity.isRegistered())
			{
				registerThenIdle();
				return;
			}

			sendNextBatch();
		}
		catch (RuntimeException e)
		{
			log.warn("everykill: upload flush failed", e);
			status = "Upload error";
			inFlight.set(false);
		}
	}

	private void registerThenIdle()
	{
		status = "Registering";

		client.register(config.uploadUrl(), identity.getClientId(),
			reg ->
			{
				try
				{
					// straight to disk, including the recovery code. it is minted
					// exactly once, so holding it in memory means a restart before
					// the user copies it loses their history permanently.
					identity.save(reg.token, reg.recoveryCode);
					status = "Registered";
				}
				catch (RuntimeException e)
				{
					status = "Could not save identity";
				}
				finally
				{
					// the next tick sends. registering and sending in one pass would
					// spend two of the five requests we get per minute.
					inFlight.set(false);
				}
			},
			error ->
			{
				status = error;
				inFlight.set(false);
			});
	}

	private void sendNextBatch()
	{
		final List<KillRecord> batch;
		synchronized (pending)
		{
			if (pending.isEmpty())
			{
				status = "Up to date";
				inFlight.set(false);
				return;
			}
			batch = pending.peekBatch();
		}

		status = "Sending " + batch.size();

		client.send(config.uploadUrl(), identity.getToken(), batch,
			result ->
			{
				if (result.unauthorised)
				{
					// token is dead, the batch stays. clearing only the token keeps
					// the client id, and register is idempotent on it - so the next
					// tick re-registers into the SAME history.
					identity.clearToken();
					status = "Reconnecting";
					inFlight.set(false);
					return;
				}

				synchronized (pending)
				{
					// accepted, duplicate and rejected are all terminal. holding a
					// rejected record back would park it at the head of the queue
					// forever and wedge every future batch behind it.
					pending.acknowledge(batch);
				}

				status = pending.isEmpty()
					? "Up to date"
					: pending.size() + " waiting";
				inFlight.set(false);
			},
			retryAfterSeconds ->
			{
				if (retryAfterSeconds > 0)
				{
					nextAllowedMillis = System.currentTimeMillis()
						+ TimeUnit.SECONDS.toMillis(retryAfterSeconds);
					status = "Rate limited";
				}
				else
				{
					status = pending.size() + " waiting";
				}
				inFlight.set(false);
			});
	}

	/**
	 * A recovery code the user has not confirmed yet, or null.
	 *
	 * <p>Read from disk, so it survives restarts until acknowledged.
	 */
	public String getRecoveryCode()
	{
		return identity.getRecoveryCode();
	}

	/** The user says they have written it down. Only now does it leave disk. */
	public void acknowledgeRecoveryCode()
	{
		identity.acknowledgeRecoveryCode();
	}

	/** Kills queued but not yet sent. */
	public int queued()
	{
		synchronized (pending)
		{
			return pending.size();
		}
	}

	/** Kills lost to queue overflow this session. */
	public int dropped()
	{
		synchronized (pending)
		{
			return pending.getDropped();
		}
	}
}
