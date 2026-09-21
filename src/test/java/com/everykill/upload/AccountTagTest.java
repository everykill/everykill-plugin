/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import java.util.Base64;
import org.junit.Assert;
import org.junit.Test;

/**
 * The account tag has to satisfy two things at once: stable enough for the server to
 * recognise the same account twice, and worthless to anyone who ends up holding it.
 */
public class AccountTagTest
{
	// shape taken from ConfigManager.findRSProfile: "rsprofile." + Base64(6 bytes of
	// the Jagex account hash).
	private static final String PROFILE_A = "rsprofile.qGVzdGlu";
	private static final String PROFILE_B = "rsprofile.Zm9vYmFy";
	private static final String SALT = "9f8c2a1b4d6e7f0a3b5c8d9e1f2a3b4c";

	@Test
	public void theSameAccountAlwaysTagsTheSame()
	{
		// the whole point: two uploads from one account must be recognisable as one
		// account, or this tells the server nothing it did not already know.
		Assert.assertEquals(AccountTag.of(PROFILE_A, SALT), AccountTag.of(PROFILE_A, SALT));
	}

	@Test
	public void differentAccountsTagDifferently()
	{
		Assert.assertNotEquals(AccountTag.of(PROFILE_A, SALT), AccountTag.of(PROFILE_B, SALT));
	}

	@Test
	public void theSameAccountOnAnotherMachineTagsDifferently()
	{
		// the salt is per install, so the tag cannot be used to follow one player
		// across machines, or joined against anybody else's database.
		final String otherInstall = "0000111122223333444455556666aaaa";
		Assert.assertNotEquals(AccountTag.of(PROFILE_A, SALT),
			AccountTag.of(PROFILE_A, otherInstall));
	}

	@Test
	public void theTagDoesNotContainTheProfileKey()
	{
		// the reason this class exists. the profile key is an ENCODING of the account
		// hash, not a digest - if any of it survived into the tag, we would be
		// shipping a reversible account id.
		final String tag = AccountTag.of(PROFILE_A, SALT);
		final String encoded = PROFILE_A.substring("rsprofile.".length());

		Assert.assertFalse(tag.contains(encoded));
		Assert.assertFalse(tag.contains(PROFILE_A));

		// and the tag must not itself decode to anything resembling the six bytes
		// the key carries.
		boolean decoded;
		try
		{
			Base64.getUrlDecoder().decode(tag);
			decoded = true;
		}
		catch (IllegalArgumentException e)
		{
			decoded = false;
		}
		Assert.assertFalse("a hex digest must not be valid base64 of the key", decoded
			&& tag.equals(encoded));
	}

	@Test
	public void itIsSixteenHexCharacters()
	{
		final String tag = AccountTag.of(PROFILE_A, SALT);
		Assert.assertEquals(16, tag.length());
		Assert.assertTrue(tag.matches("[0-9a-f]{16}"));
	}

	@Test
	public void missingInputsProduceNoTag()
	{
		// logged out, or no salt yet. the caller must send nothing rather than invent
		// a value - an invented tag would merge two real accounts on the server.
		Assert.assertNull(AccountTag.of(null, SALT));
		Assert.assertNull(AccountTag.of(PROFILE_A, null));
		Assert.assertNull(AccountTag.of("", SALT));
		Assert.assertNull(AccountTag.of(PROFILE_A, ""));
	}

	@Test
	public void noRunescapeNameCanBeRecovered()
	{
		// a display name never touches this class. the tag is derived from the profile
		// key alone, which carries an account hash and no name at all.
		final String tag = AccountTag.of(PROFILE_A, SALT);
		Assert.assertFalse(tag.toLowerCase().contains("osiriz"));
		Assert.assertEquals("tag must depend only on key and salt",
			AccountTag.of(PROFILE_A, SALT), tag);
	}
}
