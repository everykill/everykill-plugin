/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import com.everykill.EverykillConfig;
import com.everykill.detect.AccountTypes;
import com.everykill.model.KillRecord;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.RuneLite;
import java.util.function.Consumer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
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

	/**
	 * First flush is quick, because it is usually just registration.
	 *
	 * <p>This was 60s and the first live test never reached it — the client was closed
	 * 34 seconds in, so the plugin never registered and it looked like upload was
	 * broken. Registration is one small request that needs no game data, so making it
	 * wait for a batch that may not exist yet buys nothing.
	 */
	private static final long FIRST_FLUSH_SECONDS = 10L;

	private final EverykillConfig config;
	private final UploadClient client;
	private final UploadIdentity identity;
	private final ScheduledExecutorService executor;
	private final Client gameClient;
	private final ClientThread clientThread;

	private final AccountTypes accountTypes;
	private final PendingKills pending = new PendingKills();

	/** One flush at a time. Two in flight would ack each other's records. */
	private final AtomicBoolean inFlight = new AtomicBoolean();

	private ScheduledFuture<?> task;

	/** Earliest millis we may send again, honouring the server's own retryAfter. */
	private volatile long nextAllowedMillis;

	/**
	 * Set when the server reports a client-wide fault. Stops all uploading.
	 *
	 * <p>Not cleared by a later success, because there won't be one — every batch
	 * carries the same broken field until the client is fixed and restarted.
	 */
	private volatile String halted;

	/** What the server currently believes, so we only call on a real change. */
	private volatile boolean publishedState;

	@Getter
	private volatile String status = "Not uploading";

	@Inject
	UploadService(EverykillConfig config, UploadClient client, UploadIdentity identity,
		ScheduledExecutorService executor, Client gameClient, ClientThread clientThread,
		AccountTypes accountTypes)
	{
		this.config = config;
		this.client = client;
		this.identity = identity;
		this.executor = executor;
		this.gameClient = gameClient;
		this.clientThread = clientThread;
		this.accountTypes = accountTypes;
	}

	/**
	 * The address to upload to: the hardcoded domain, unless a loopback dev override
	 * is set.
	 *
	 * <p>Enforced here rather than trusted from config. A dev override that accepted
	 * any host would be the user-supplied URL the Hub rule forbids, just spelled
	 * differently — so anything that is not this machine is discarded.
	 */
	private String uploadUrl()
	{
		final String dev = config.devUploadUrl();
		if (dev != null && !dev.trim().isEmpty() && isLoopback(dev.trim()))
		{
			return dev.trim();
		}
		return EverykillConfig.UPLOAD_URL;
	}

	/** True only for an address on this machine. */
	private static boolean isLoopback(String url)
	{
		try
		{
			final String host = java.net.URI.create(url).getHost();
			return "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host)
				|| "::1".equals(host);
		}
		catch (IllegalArgumentException e)
		{
			// an unparseable override is not a loopback address.
			return false;
		}
	}

	/**
	 * Redeems a recovery code and adopts the account it belongs to.
	 *
	 * <p>Runs off the client thread — it touches the network and then the identity
	 * file. Status goes into the panel, since the player is standing there watching
	 * after pasting a code.
	 */
	public void recover(String code)
	{
		if (code == null || code.trim().isEmpty())
		{
			return;
		}

		executor.execute(() ->
		{
			if (identity.getClientId() == null)
			{
				identity.load();
			}

			status = "Recovering...";

			client.recover(uploadUrl(), code.trim(), identity.getClientId(),
				(token, rebound) ->
				{
					// the recovered account's token replaces ours. no recovery code
					// comes back here - the player already has one, it is the thing
					// they just typed, and it does not rotate.
					identity.save(token, null);

					if (rebound)
					{
						status = "Recovered";
					}
					else
					{
						// this install's id belongs to a different account. the token
						// works, but the id does not map to it, and saying "recovered"
						// would hide that.
						status = "Recovered - this install was already tracking another "
							+ "account, so both were kept separate";
					}
				},
				error ->
				{
					status = error;
				});
		});
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
		if (halted != null)
		{
			// deliberately sticky. it clears on restart or on toggling upload, both
			// of which follow fixing the client - retrying a known-broken payload on
			// a timer just burns the rate limit.
			status = halted;
			return;
		}

		if (!config.uploadEnabled())
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

			syncPublishState();
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

		client.register(uploadUrl(), identity.getClientId(),
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

		client.send(uploadUrl(), identity.getToken(), batch,
			result ->
			{
				if (result.systemicReason != null)
				{
					// every record failed the same way, so the data is fine and we
					// are not. keep the batch, stop trying, and make it visible -
					// a player whose uploads have silently halted deserves to know.
					halted = "Upload stopped: " + result.systemicReason;
					status = halted;
					log.warn("everykill: upload halted, client fault: {}", result.systemicReason);
					inFlight.set(false);
					return;
				}

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

	/**
	 * Sends the publish state when the toggle has moved, and nothing otherwise.
	 *
	 * <p>The name is read from the client HERE and never stored: no field on
	 * {@code KillRecord}, nothing in {@link UploadIdentity}, nothing in the ledger. A
	 * name that is never held cannot ride along by accident.
	 *
	 * <p>{@code getLocalPlayer().getName()} is the display name. {@code getUsername()}
	 * is the LOGIN, which on a Jagex account is an email address — a credential, and
	 * the published policy states we hold none. It must never be sent.
	 */
	private void syncPublishState()
	{
		final boolean wanted = config.publishName();
		if (wanted == publishedState)
		{
			return;
		}

		if (!wanted)
		{
			client.publish(uploadUrl(), identity.getToken(), null, null,
				msg ->
				{
					publishedState = false;
					log.debug("everykill: {}", msg);
				},
				err -> log.debug("everykill: could not withdraw name: {}", err));
			return;
		}

		// read on the client thread, used once, not kept.
		clientThread.invoke(() ->
		{
			final Player me = gameClient.getLocalPlayer();
			final String name = me == null ? null : me.getName();
			if (name == null || name.isEmpty())
			{
				// not logged in yet. try again on the next flush rather than
				// publishing a blank.
				return;
			}

			// read here on the client thread with the name, and only if the player
			// asked for it. an ironman who would rather not advertise the mode gets
			// the field left out of the request entirely.
			final String mode = config.publishAccountType() ? accountTypes.get().name() : null;

			client.publish(uploadUrl(), identity.getToken(), name, mode,
				msg ->
				{
					publishedState = true;
					log.debug("everykill: {}", msg);
				},
				err -> log.debug("everykill: could not publish name: {}", err));
		});
	}

	/**
	 * Writes everything the server holds to a file the user can open.
	 *
	 * <p>Runs on the scheduler: the request is async but the file write is not, and
	 * neither belongs on the client thread.
	 */
	public void exportData(Consumer<String> onDone, Consumer<String> onError)
	{
		if (!identity.isRegistered())
		{
			onError.accept("Nothing uploaded yet");
			return;
		}

		client.export(uploadUrl(), identity.getToken(),
			json -> executor.execute(() ->
			{
				try
				{
					final Path out = RuneLite.RUNELITE_DIR.toPath()
						.resolve("everykill-plugin")
						.resolve("everykill-export.json");
					Files.createDirectories(out.getParent());
					Files.write(out, json.getBytes(StandardCharsets.UTF_8));
					onDone.accept(out.toString());
				}
				catch (IOException e)
				{
					onError.accept("Could not write the file: " + e.getMessage());
				}
			}),
			onError);
	}

	/**
	 * Erases everything server-side, then forgets the local identity.
	 *
	 * <p>The local wipe matters: keeping the client id after the account is gone
	 * would silently re-register into a new empty account on the next flush, which
	 * looks like the deletion failed.
	 */
	public void eraseData(Consumer<String> onDone, Consumer<String> onError)
	{
		if (!identity.isRegistered())
		{
			onError.accept("Nothing uploaded yet");
			return;
		}

		client.erase(uploadUrl(), identity.getToken(),
			json -> executor.execute(() ->
			{
				identity.forget();
				synchronized (pending)
				{
					pending.clear();
				}
				status = "Deleted";
				onDone.accept("Deleted from the server");
			}),
			onError);
	}

	/**
	 * Whether the display name is currently on public leaderboards.
	 *
	 * <p>Reads the config rather than {@code publishedState}, because the toggle is
	 * what the user chose and the server sync may be a flush behind. Answering "no"
	 * while a name is live would be the dangerous direction to be wrong in; this way
	 * round the panel is honest the instant the toggle moves.
	 */
	public boolean isPublishing()
	{
		return config.publishName() && identity.isRegistered();
	}

	/** Whether the account mode rides along with a published name. */
	public boolean isPublishingAccountType()
	{
		return isPublishing() && config.publishAccountType();
	}

	/** Non-null when uploading has stopped because of a client fault. */
	public String getHalted()
	{
		return halted;
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
