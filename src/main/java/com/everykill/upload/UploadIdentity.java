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

	private final Path path;

	private String clientId;
	private String token;
	private String recoveryCode;

	public UploadIdentity()
	{
		this(RuneLite.RUNELITE_DIR.toPath().resolve(DIR).resolve(FILE));
	}

	UploadIdentity(Path path)
	{
		this.path = path;
	}

	/**
	 * Reads the stored identity, minting a client id on first run.
	 *
	 * <p>Blocking disk IO — call it off the client thread. A fresh client id is NOT
	 * written here; it is written once a token comes back, so a failed registration
	 * cannot burn an id and orphan a history that never existed.
	 */
	public synchronized void load()
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

		if (clientId == null || clientId.length() != 32)
		{
			clientId = newClientId();
			token = null;
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
		token = newToken;
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

	/** Forgets the token but keeps the id, so the next register recovers the history. */
	public synchronized void clearToken()
	{
		token = null;
	}
}
