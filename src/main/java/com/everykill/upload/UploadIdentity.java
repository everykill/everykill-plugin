/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import com.everykill.EverykillConfig;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.RuneLite;

/**
 * This install's upload identity: a random client id, and the token it earned.
 *
 * <p><b>The RSN never leaves the client.</b> The original plan was a salted hash of the
 * account computed here, and Gage was right to reject it: the plugin is open source, so
 * the salt ships in a public jar, and a public salt is not a salt. RSNs are not secret
 * and there are not many — anyone could pull names off the hiscores, hash them with the
 * published salt and reverse the whole database.
 *
 * <p>So identity runs the other way. We generate 128 random bits that mean nothing on
 * their own, trade them for a bearer token, and the server never learns who we are.
 *
 * <p><b>The cost, stated plainly:</b> with no RSN on file, losing this file orphans that
 * player's history permanently. The recovery code is the only way back, it is minted
 * exactly once, and the panel has to say so.
 */
@Slf4j
public class UploadIdentity
{
	private static final String DIR = "everykill-plugin";
	private static final String FILE = "identity.properties";

	private static final String KEY_CLIENT_ID = "clientId";
	private static final String KEY_TOKEN = "token";

	// held on disk until the user says they've written it down. it is minted exactly
	// once by the server, so a client restart before they copy it would otherwise lose
	// the only way back into their history - forever, with no rsn on file.
	private static final String KEY_RECOVERY = "recoveryCode";

	/** Which server minted the token and code below. */
	private static final String KEY_HOST = "host";

	/** Synced by RuneLite with the rest of the RS profile config. */
	private static final String CONFIG_KEY = "uploadClientId";

	private final Path path;

	/**
	 * RuneLite's own config, which syncs to the user's RuneLite account.
	 *
	 * <p>The client id is mirrored here so a reinstall recovers silently. Nobody reads
	 * a recovery code they were shown once and did not need yet, and losing the local
	 * file is the common case — new computer, fresh install, wiped .runelite. The
	 * ledger already rides this sync; the identity did not, which meant your kills
	 * came back and your account did not.
	 *
	 * <p>Null when RuneLite has no profile yet (before login), which is why the local
	 * file remains the source of truth and this is a mirror rather than a move.
	 */
	private final SyncedStore synced;

	private String clientId;
	private String token;
	private String recoveryCode;

	private String host;

	@Inject
	public UploadIdentity(ConfigManager configManager)
	{
		this(RuneLite.RUNELITE_DIR.toPath().resolve(DIR).resolve(FILE),
			new SyncedStore()
			{
				@Override
				public String get(String key)
				{
					return configManager.getRSProfileConfiguration(EverykillConfig.GROUP, key);
				}

				@Override
				public void put(String key, String value)
				{
					configManager.setRSProfileConfiguration(EverykillConfig.GROUP, key, value);
				}

				@Override
				public void remove(String key)
				{
					configManager.unsetRSProfileConfiguration(EverykillConfig.GROUP, key);
				}
			});
	}

	UploadIdentity(Path path)
	{
		this(path, null);
	}

	UploadIdentity(Path path, SyncedStore synced)
	{
		this.path = path;
		this.synced = synced;
	}

	/**
	 * Reads the stored identity, minting a client id on first run.
	 *
	 * <p>Blocking disk IO — call it off the client thread. A fresh client id is NOT
	 * written here; it is written once a token comes back, so a failed registration
	 * cannot burn an id and orphan a history that never existed.
	 */
	/** Loads with no host check — for callers that don't know where they'll talk. */
	public synchronized void load()
	{
		load(null);
	}

	/**
	 * Loads the identity, discarding anything a different server issued.
	 *
	 * @param currentHost host we are about to talk to, or null to skip the check
	 */
	public synchronized void load(String currentHost)
	{
		final Properties props = new Properties();

		if (Files.exists(path))
		{
			try
			{
				props.load(Files.newBufferedReader(path, StandardCharsets.UTF_8));
			}
			catch (IOException e)
			{
				log.warn("everykill: could not read upload identity, starting fresh", e);
			}
		}

		clientId = props.getProperty(KEY_CLIENT_ID);
		token = props.getProperty(KEY_TOKEN);
		recoveryCode = props.getProperty(KEY_RECOVERY);
		host = props.getProperty(KEY_HOST);

		// a token and a recovery code belong to the server that minted them. pointing
		// the plugin somewhere else makes both meaningless - production answers
		// "Token not recognised." to a dev server's token, and a recovery code from
		// the wrong server is worse than none, because the panel calls it the only way
		// back to your history.
		//
		// the CLIENT ID stays. it is a random local identifier, not the server's
		// property; each server derives its own account from hash(id + its own salt).
		// keeping it is the whole reason a reinstall finds its history again.
		if (host != null && currentHost != null && !host.equals(currentHost))
		{
			log.debug("everykill: identity was issued by {}, now talking to {} - "
				+ "dropping the token and recovery code, keeping the client id",
				host, currentHost);
			token = null;
			recoveryCode = null;
			host = null;
		}

		if (clientId == null || clientId.length() != 32)
		{
			// nothing local. before minting a new id - which orphans any history the
			// server holds - ask RuneLite's synced config, because a reinstall is the
			// exact case this exists for.
			final String synced = syncedClientId();
			if (synced != null)
			{
				clientId = synced;
				token = null;
				log.debug("everykill: recovered client id from RuneLite config sync");
			}
			else
			{
				clientId = newClientId();
				token = null;
			}
		}

		mirrorToConfig();
	}

	private String syncedClientId()
	{
		if (synced == null)
		{
			return null;
		}

		final String value = synced.get(CONFIG_KEY);
		return value != null && value.length() == 32 ? value : null;
	}

	/**
	 * Writes the id into synced config. The TOKEN is deliberately not mirrored.
	 *
	 * <p>The id identifies an account; the token authenticates as it. Syncing the id
	 * lets a reinstall re-register into the same history — register is idempotent, so
	 * it just issues a fresh token. Syncing the credential as well would put a live
	 * bearer token in someone else's storage for no gain.
	 */
	private void mirrorToConfig()
	{
		if (synced == null || clientId == null)
		{
			return;
		}

		if (!clientId.equals(syncedClientId()))
		{
			synced.put(CONFIG_KEY, clientId);
		}
	}

	/** 32 hex chars, per the contract. */
	private static String newClientId()
	{
		final byte[] bytes = new byte[16];
		new SecureRandom().nextBytes(bytes);

		final StringBuilder sb = new StringBuilder(32);
		for (byte b : bytes)
		{
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	public synchronized String getClientId()
	{
		return clientId;
	}

	public synchronized String getToken()
	{
		return token;
	}

	public synchronized boolean isRegistered()
	{
		return token != null && !token.isEmpty();
	}

	/**
	 * Stores the token a successful registration returned.
	 *
	 * <p>Blocking disk IO — off the client thread. This is the point the client id
	 * becomes permanent, because it is now the only thing tying us to a history.
	 */
	public synchronized void save(String newToken)
	{
		save(newToken, recoveryCode);
	}

	/** Stores the token, and the recovery code when the server just minted one. */
	public synchronized void save(String newToken, String newRecoveryCode)
	{
		save(newToken, newRecoveryCode, host);
	}

	/**
	 * Stores the token and code against the server that issued them.
	 *
	 * <p>The host is written alongside because neither a token nor a recovery code
	 * means anything to a different server. Verified live: production answered
	 * {@code "Token not recognised."} to a token the local dev server had minted, and
	 * the panel was cheerfully displaying that server's recovery code at the time.
	 */
	public synchronized void save(String newToken, String newRecoveryCode, String issuedBy)
	{
		token = newToken;
		host = issuedBy;
		if (newRecoveryCode != null)
		{
			recoveryCode = newRecoveryCode;
		}

		final Properties props = new Properties();
		props.setProperty(KEY_CLIENT_ID, clientId);
		props.setProperty(KEY_TOKEN, newToken);
		if (recoveryCode != null)
		{
			props.setProperty(KEY_RECOVERY, recoveryCode);
		}
		if (host != null)
		{
			props.setProperty(KEY_HOST, host);
		}

		mirrorToConfig();

		try
		{
			Files.createDirectories(path.getParent());
			try (var out = Files.newBufferedWriter(path, StandardCharsets.UTF_8))
			{
				props.store(out, "everykill upload identity - do not share");
			}
		}
		catch (IOException e)
		{
			// an unwritten token means re-registering next launch. register is
			// idempotent on the same client id, so that recovers silently - as long
			// as the id itself survives, which it does not here. say so loudly.
			log.warn("everykill: could not save upload identity to {}", path, e);
			throw new UncheckedIOException(e);
		}
	}

	/** The unacknowledged recovery code, or null once the user has confirmed it. */
	public synchronized String getRecoveryCode()
	{
		return recoveryCode;
	}

	/**
	 * Called when the user says they have written the code down.
	 *
	 * <p>Only then does it leave disk. Clearing it on display would mean a misclick
	 * costs someone their history.
	 */
	public synchronized void acknowledgeRecoveryCode()
	{
		recoveryCode = null;
		if (token != null)
		{
			save(token);
		}
	}

	/**
	 * Deletes the identity entirely, after a server-side erasure.
	 *
	 * <p>Unlike {@link #clearToken()} this drops the client id too, and it must. The
	 * id is what register is idempotent on, so keeping it would silently re-register
	 * into a brand new empty account on the next flush — which looks exactly like the
	 * deletion having failed.
	 */
	public synchronized void forget()
	{
		// the synced copy goes too. leaving it would resurrect a deleted account on
		// the next login, which is not what "delete my data" means.
		if (synced != null)
		{
			synced.remove(CONFIG_KEY);
		}

		clientId = null;
		token = null;
		recoveryCode = null;

		try
		{
			Files.deleteIfExists(path);
		}
		catch (IOException e)
		{
			log.warn("everykill: could not delete {}", path, e);
		}
	}

	/** Forgets the token but keeps the id, so the next register recovers the history. */
	public synchronized void clearToken()
	{
		token = null;
	}
}
