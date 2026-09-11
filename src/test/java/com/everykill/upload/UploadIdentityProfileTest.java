/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Two game accounts on one PC must not share an Everykill account.
 *
 * <p>Reported live: a player running a UIM and a main from one computer saw his name
 * flip between them on the site while the kill count climbed across both. The ledger
 * was always per RS profile; the identity file is per machine, so both accounts
 * uploaded as the same client id.
 *
 * <p>The migration cases matter more than the happy path. Everyone already installed
 * has an id in that file and it is their only link to their history.
 */
public class UploadIdentityProfileTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** Stands in for one RS profile's synced config. */
	private static class Profile implements SyncedStore
	{
		private final Map<String, String> values = new HashMap<>();

		@Override
		public String get(String key)
		{
			return values.get(key);
		}

		@Override
		public void put(String key, String value)
		{
			values.put(key, value);
		}

		@Override
		public void remove(String key)
		{
			values.remove(key);
		}
	}

	private Path file() throws IOException
	{
		return folder.newFolder().toPath().resolve("identity.properties");
	}

	@Test
	public void twoProfilesOnOneMachineGetDifferentIds() throws IOException
	{
		final Path path = file();
		final Profile uim = new Profile();
		final Profile main = new Profile();

		final UploadIdentity first = new UploadIdentity(path, uim);
		first.load("api.everykill.com");
		final String uimId = first.getClientId();
		first.save("token-a", "AAAA-BBBB-CCCC-DDDD", "api.everykill.com");

		final UploadIdentity second = new UploadIdentity(path, main);
		second.load("api.everykill.com");
		final String mainId = second.getClientId();

		Assert.assertNotNull(uimId);
		Assert.assertNotNull(mainId);
		Assert.assertNotEquals(
			"a second game account must not inherit the first's identity",
			uimId, mainId);
	}

	@Test
	public void anExistingUserKeepsTheirHistory() throws IOException
	{
		// the update case, and by far the most common: one player, one account, an id
		// already in the file. taking it away would orphan everything they have.
		final Path path = file();
		Files.createDirectories(path.getParent());
		Files.write(path, ("clientId=e918fe648dd5491486c3ccc581cd2d40"
			+ System.lineSeparator()
			+ "token=old-token" + System.lineSeparator()
			+ "host=api.everykill.com" + System.lineSeparator()).getBytes());

		final Profile theirs = new Profile();
		final UploadIdentity identity = new UploadIdentity(path, theirs);
		identity.load("api.everykill.com");

		Assert.assertEquals("e918fe648dd5491486c3ccc581cd2d40", identity.getClientId());
		Assert.assertEquals("old-token", identity.getToken());
	}

	@Test
	public void theSecondProfileDoesNotAdoptAClaimedFile() throws IOException
	{
		// the fix. without the claim marker both profiles adopt the same id and the
		// bug survives the fix.
		final Path path = file();
		Files.createDirectories(path.getParent());
		Files.write(path, ("clientId=e918fe648dd5491486c3ccc581cd2d40"
			+ System.lineSeparator()).getBytes());

		final UploadIdentity mine = new UploadIdentity(path, new Profile());
		mine.load("api.everykill.com");
		Assert.assertEquals("e918fe648dd5491486c3ccc581cd2d40", mine.getClientId());

		final UploadIdentity theirs = new UploadIdentity(path, new Profile());
		theirs.load("api.everykill.com");

		Assert.assertNotEquals("e918fe648dd5491486c3ccc581cd2d40",
			theirs.getClientId());
	}

	@Test
	public void aProfileWithItsOwnIdIgnoresTheFile() throws IOException
	{
		final Path path = file();
		Files.createDirectories(path.getParent());
		Files.write(path, ("clientId=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
			+ System.lineSeparator()
			+ "token=someone-elses" + System.lineSeparator()).getBytes());

		final Profile mine = new Profile();
		mine.put("uploadClientId", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

		final UploadIdentity identity = new UploadIdentity(path, mine);
		identity.load("api.everykill.com");

		Assert.assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", identity.getClientId());
		Assert.assertNull("another account's token must never be used",
			identity.getToken());
	}

	@Test
	public void reloadingTheSameProfileIsStable() throws IOException
	{
		// a new id on every restart would be a fresh account every session.
		final Path path = file();
		final Profile p = new Profile();

		final UploadIdentity a = new UploadIdentity(path, p);
		a.load("api.everykill.com");
		final String id = a.getClientId();
		a.save("t", null, "api.everykill.com");

		final UploadIdentity b = new UploadIdentity(path, p);
		b.load("api.everykill.com");

		Assert.assertEquals(id, b.getClientId());
	}
}
