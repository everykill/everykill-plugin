/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A stable, non-reversible per-account tag.
 *
 * <p>The server needs to prove two game accounts on one machine are genuinely
 * different accounts, which a client id alone cannot do: a client id is minted
 * locally, so a bug in how we mint it is invisible from the other side. That is
 * exactly what happened - a claim check misfired before login and minted a fresh
 * identity every launch, and nothing in the upload said "this is the same player".
 *
 * <p>What it is NOT is a RuneScape name. The privacy policy says a name is never sent
 * unless name publishing is separately turned on, and that stays true.
 *
 * <p><b>Why the profile key is not sent raw.</b> RuneLite builds it as
 * {@code "rsprofile." + Base64(six bytes of the Jagex account hash)} - see
 * {@code ConfigManager.findRSProfile}. It is an encoding, not a digest, so anyone
 * holding it decodes the account hash straight back out. Sending it raw would leak a
 * cross-plugin, cross-site account identifier to a server that has no business
 * holding one.
 *
 * <p>So it is salted with a value generated once on this install and never uploaded,
 * then SHA-256'd. The server gets something stable per account, comparable across
 * uploads from this install, and worthless anywhere else - it cannot be reversed to an
 * account hash, and the same account on someone else's machine produces a different
 * tag.
 */
public final class AccountTag
{
	private AccountTag()
	{
	}

	/**
	 * Hashes a profile key with an install-local salt.
	 *
	 * @return 16 hex characters, or null when either input is missing - which is the
	 *         logged-out case, and the caller must send no tag rather than a made-up
	 *         one.
	 */
	public static String of(String profileKey, String salt)
	{
		if (profileKey == null || profileKey.isEmpty() || salt == null || salt.isEmpty())
		{
			return null;
		}

		try
		{
			final MessageDigest sha = MessageDigest.getInstance("SHA-256");
			sha.update(salt.getBytes(StandardCharsets.UTF_8));
			sha.update((byte) 0);
			final byte[] digest = sha.digest(profileKey.getBytes(StandardCharsets.UTF_8));

			// 16 hex chars is 64 bits. collisions need about 4 billion accounts on one
			// install before they are worth thinking about, and a shorter tag is a
			// smaller thing to leak if the database ever walks.
			final StringBuilder out = new StringBuilder(16);
			for (int i = 0; i < 8; i++)
			{
				out.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
				out.append(Character.forDigit(digest[i] & 0xF, 16));
			}
			return out.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			// SHA-256 is required of every JVM. if it is genuinely missing, sending
			// nothing is correct - a tag we cannot compute is not a tag we invent.
			return null;
		}
	}
}
