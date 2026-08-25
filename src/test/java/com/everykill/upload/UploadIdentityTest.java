/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Identity storage. The RSN is not involved and that is the point.
 */
public class UploadIdentityTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private UploadIdentity identityAt(Path path)
	{
		return new UploadIdentity(path);
	}

	@Test
	public void aFirstRunMintsThirtyTwoHexCharacters()
	{
		final UploadIdentity id = identityAt(folder.getRoot().toPath().resolve("id.properties"));
		id.load();

		Assert.assertNotNull(id.getClientId());
		Assert.assertEquals(32, id.getClientId().length());
		Assert.assertTrue("must be hex, the contract says 32 hex chars",
			id.getClientId().matches("[0-9a-f]{32}"));
		Assert.assertFalse(id.isRegistered());
	}

	@Test
	public void twoInstallsDoNotShareAnIdentity()
	{
		final UploadIdentity a = identityAt(folder.getRoot().toPath().resolve("a.properties"));
		final UploadIdentity b = identityAt(folder.getRoot().toPath().resolve("b.properties"));
		a.load();
		b.load();

		Assert.assertNotEquals(a.getClientId(), b.getClientId());
	}

	@Test
	public void aFirstRunDoesNotWriteAnythingUntilItHasAToken()
	{
		// a client id burned by a failed registration is a history nobody can reach.
		// nothing hits disk until the server has actually acknowledged us.
		final Path path = folder.getRoot().toPath().resolve("id.properties");
		identityAt(path).load();

		Assert.assertFalse(Files.exists(path));
	}

	@Test
	public void anIdentitySurvivesARestart()
	{
		final Path path = folder.getRoot().toPath().resolve("id.properties");

		final UploadIdentity first = identityAt(path);
		first.load();
		final String clientId = first.getClientId();
		first.save("token-abc");

		final UploadIdentity second = identityAt(path);
		second.load();

		Assert.assertEquals(clientId, second.getClientId());
		Assert.assertEquals("token-abc", second.getToken());
		Assert.assertTrue(second.isRegistered());
	}

	@Test
	public void clearingATokenKeepsTheClientId()
	{
		// this is what makes a dead token recoverable. register is idempotent on the
		// client id, so keeping it means re-registering lands in the SAME history -
		// losing it orphans the player permanently.
		final Path path = folder.getRoot().toPath().resolve("id.properties");

		final UploadIdentity id = identityAt(path);
		id.load();
		final String clientId = id.getClientId();
		id.save("token-abc");

		id.clearToken();

		Assert.assertEquals(clientId, id.getClientId());
		Assert.assertFalse(id.isRegistered());
	}

	@Test
	public void aCorruptFileIsReplacedRatherThanCrashing()
	{
		final Path path = folder.getRoot().toPath().resolve("id.properties");
		try
		{
			Files.write(path, "this is not a properties file\u0000\u0000".getBytes());
		}
		catch (Exception e)
		{
			Assert.fail(e.getMessage());
		}

		final UploadIdentity id = identityAt(path);
		id.load();

		Assert.assertEquals(32, id.getClientId().length());
		Assert.assertFalse(id.isRegistered());
	}

	@Test
	public void aTruncatedClientIdIsNotTrusted()
	{
		// a half-written file must not produce an id the server will reject forever.
		final Path path = folder.getRoot().toPath().resolve("id.properties");
		try
		{
			Files.write(path, "clientId=abc123\n".getBytes());
		}
		catch (Exception e)
		{
			Assert.fail(e.getMessage());
		}

		final UploadIdentity id = identityAt(path);
		id.load();

		Assert.assertEquals(32, id.getClientId().length());
		Assert.assertNotEquals("abc123", id.getClientId());
	}

	@Test
	public void aRecoveryCodeSurvivesARestartUntilAcknowledged()
	{
		// the server mints it ONCE. holding it in memory means a crash, or the user
		// closing the client to write it down somewhere, loses their history forever.
		final Path path = folder.getRoot().toPath().resolve("id.properties");

		final UploadIdentity first = identityAt(path);
		first.load();
		first.save("token-abc", "P309-51P3-0BY7-LQPS");

		final UploadIdentity afterRestart = identityAt(path);
		afterRestart.load();
		Assert.assertEquals("P309-51P3-0BY7-LQPS", afterRestart.getRecoveryCode());

		afterRestart.acknowledgeRecoveryCode();

		final UploadIdentity afterAck = identityAt(path);
		afterAck.load();
		Assert.assertNull("acknowledged, so it stops nagging", afterAck.getRecoveryCode());
		Assert.assertEquals("but the token is untouched", "token-abc", afterAck.getToken());
	}

	@Test
	public void aLaterRegistrationDoesNotWipeAnUnacknowledgedCode()
	{
		// register is idempotent and returns recoveryCode null every time after the
		// first. passing that null through must not erase the one we are still
		// showing - that would be losing the code to a routine token refresh.
		final Path path = folder.getRoot().toPath().resolve("id.properties");

		final UploadIdentity id = identityAt(path);
		id.load();
		id.save("token-1", "P309-51P3-0BY7-LQPS");

		id.save("token-2", null);

		Assert.assertEquals("P309-51P3-0BY7-LQPS", id.getRecoveryCode());
		Assert.assertEquals("token-2", id.getToken());
	}

	@Test
	public void aReinstallRecoversTheClientIdFromSyncedConfig()
	{
		// the whole point. a fresh machine has no identity.properties, but RuneLite
		// has already synced the id back with the rest of the rs profile config - so
		// register lands in the SAME account instead of minting an orphan.
		final java.util.Map<String, String> synced = new java.util.HashMap<>();
		synced.put("uploadClientId", "0123456789abcdef0123456789abcdef");

		final UploadIdentity fresh = new UploadIdentity(
			folder.getRoot().toPath().resolve("gone.properties"), fakeConfig(synced));
		fresh.load();

		Assert.assertEquals("0123456789abcdef0123456789abcdef", fresh.getClientId());
		Assert.assertFalse("no token yet - register issues a fresh one", fresh.isRegistered());
	}

	@Test
	public void aFirstRunMirrorsTheNewIdIntoSyncedConfig()
	{
		final java.util.Map<String, String> synced = new java.util.HashMap<>();

		final UploadIdentity id = new UploadIdentity(
			folder.getRoot().toPath().resolve("id.properties"), fakeConfig(synced));
		id.load();

		Assert.assertEquals(id.getClientId(), synced.get("uploadClientId"));
	}

	@Test
	public void theTokenIsNeverMirrored()
	{
		// the id names an account; the token authenticates as it. syncing a live
		// bearer token buys nothing and puts a credential in someone else's storage.
		final java.util.Map<String, String> synced = new java.util.HashMap<>();

		final UploadIdentity id = new UploadIdentity(
			folder.getRoot().toPath().resolve("id.properties"), fakeConfig(synced));
		id.load();
		id.save("ek_secret_token");

		Assert.assertFalse(synced.containsValue("ek_secret_token"));
		Assert.assertEquals(1, synced.size());
	}

	@Test
	public void forgettingClearsTheSyncedCopyToo()
	{
		// otherwise the next login resurrects a deleted account.
		final java.util.Map<String, String> synced = new java.util.HashMap<>();

		final UploadIdentity id = new UploadIdentity(
			folder.getRoot().toPath().resolve("id.properties"), fakeConfig(synced));
		id.load();
		id.save("tok");
		Assert.assertFalse(synced.isEmpty());

		id.forget();

		Assert.assertTrue("a deleted account must not come back", synced.isEmpty());
	}

	/** A SyncedStore backed by a map. */
	private static SyncedStore fakeConfig(java.util.Map<String, String> store)
	{
		return new SyncedStore()
		{
			@Override
			public String get(String key)
			{
				return store.get(key);
			}

			@Override
			public void put(String key, String value)
			{
				store.put(key, value);
			}

			@Override
			public void remove(String key)
			{
				store.remove(key);
			}
		};
	}
}
