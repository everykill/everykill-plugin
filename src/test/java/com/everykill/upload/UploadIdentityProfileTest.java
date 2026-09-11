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
	public void fourProfilesOnOneMachineAllGetTheirOwnId() throws IOException
	{
		// two was the reported case. the claim is a single marker, so the question is
		// whether accounts three and four also see a claimed file or whether one of
		// them slips through and adopts an id that is already in use.
		final Path path = file();

		final java.util.Set<String> ids = new java.util.HashSet<>();
		for (int i = 0; i < 4; i++)
		{
			final UploadIdentity identity = new UploadIdentity(path, new Profile());
			identity.load("api.everykill.com");
			final String id = identity.getClientId();

			Assert.assertNotNull("profile " + i + " got no id", id);
			Assert.assertTrue("profile " + i + " reused an id already in use",
				ids.add(id));

			// each one registers, which is when the file gets written
			identity.save("token-" + i, null, "api.everykill.com");
		}

		Assert.assertEquals("four accounts must be four identities", 4, ids.size());
	}

	@Test
	public void interleavedLoginsStillSeparate() throws IOException
	{
		// nobody logs in cleanly one after another. account A, then B, then back to A,
		// then C - the file is rewritten each time, so a claim that only survives one
		// save would let the next account adopt whatever is sitting there.
		final Path path = file();
		final Profile a = new Profile();
		final Profile b = new Profile();
		final Profile c = new Profile();

		final UploadIdentity ia = new UploadIdentity(path, a);
		ia.load("api.everykill.com");
		final String idA = ia.getClientId();
		ia.save("ta", null, "api.everykill.com");

		final UploadIdentity ib = new UploadIdentity(path, b);
		ib.load("api.everykill.com");
		final String idB = ib.getClientId();
		ib.save("tb", null, "api.everykill.com");

		// back to A. it must find its OWN id, not B's, which is the last thing written
		// to the shared file.
		final UploadIdentity ia2 = new UploadIdentity(path, a);
		ia2.load("api.everykill.com");

		final UploadIdentity ic = new UploadIdentity(path, c);
		ic.load("api.everykill.com");

		Assert.assertEquals("account A must come back to its own identity",
			idA, ia2.getClientId());
		Assert.assertNotEquals(idA, idB);
		Assert.assertNotEquals("a third account must not inherit either",
			idA, ic.getClientId());
		Assert.assertNotEquals(idB, ic.getClientId());
	}

	@Test
	public void twoClientsStartingAtOnceDoNotShareAnId() throws IOException
	{
		// two RuneLite clients open at the same time are two JVMs writing one file.
		// every other test here loads sequentially - A finishes before B starts - which
		// is not what happens when someone launches both at once.
		//
		// the dangerous window is the FIRST login after the update, when neither
		// profile has a synced id yet and both are looking at the same unclaimed file.
		final Path path = file();
		Files.createDirectories(path.getParent());
		Files.write(path, ("clientId=e918fe648dd5491486c3ccc581cd2d40"
			+ System.lineSeparator()).getBytes());

		final UploadIdentity clientA = new UploadIdentity(path, new Profile());
		final UploadIdentity clientB = new UploadIdentity(path, new Profile());

		// both read before either writes - the interleaving that a sequential test
		// can never produce.
		clientA.load("api.everykill.com");
		clientB.load("api.everykill.com");

		Assert.assertNotEquals(
			"two clients starting together must not adopt the same identity",
			clientA.getClientId(), clientB.getClientId());
	}

	@Test
	public void concurrentSavesDoNotStealEachOthersIdentity() throws IOException
	{
		// both clients already have their own id. they are now both writing the shared
		// file on every register and flush. whoever writes last owns the file - the
		// question is whether that can drag the other account's identity with it.
		final Path path = file();
		final Profile a = new Profile();
		final Profile b = new Profile();

		final UploadIdentity ca = new UploadIdentity(path, a);
		ca.load("api.everykill.com");
		final String idA = ca.getClientId();

		final UploadIdentity cb = new UploadIdentity(path, b);
		cb.load("api.everykill.com");
		final String idB = cb.getClientId();

		// interleaved writes, the way two live clients actually behave
		ca.save("token-a1", null, "api.everykill.com");
		cb.save("token-b1", null, "api.everykill.com");
		ca.save("token-a2", null, "api.everykill.com");
		cb.save("token-b2", null, "api.everykill.com");

		Assert.assertEquals("A kept its own identity", idA, ca.getClientId());
		Assert.assertEquals("B kept its own identity", idB, cb.getClientId());
		Assert.assertEquals("A kept its own token", "token-a2", ca.getToken());
		Assert.assertEquals("B kept its own token", "token-b2", cb.getToken());

		// and a restart of A must still find A, not whatever B wrote last
		final UploadIdentity restartA = new UploadIdentity(path, a);
		restartA.load("api.everykill.com");
		Assert.assertEquals("A restarts into its own identity",
			idA, restartA.getClientId());
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
