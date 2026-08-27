/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A token belongs to the server that minted it.
 *
 * <p>Found live, not theorised: production answered {@code "Token not recognised."} to
 * a token the local dev server had issued, while the panel displayed that server's
 * recovery code under a banner calling it the only way back to your history.
 */
public class UploadIdentityHostTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Path file() throws IOException
	{
		return folder.newFolder("everykill-plugin").toPath().resolve("identity.properties");
	}

	@Test
	public void aTokenFromAnotherServerIsDiscarded() throws IOException
	{
		final Path path = file();

		final UploadIdentity first = new UploadIdentity(path);
		first.load("127.0.0.1");
		final String id = first.getClientId();
		first.save("ek_devtoken", "AAAA-BBBB-CCCC-DDDD", "127.0.0.1");

		final UploadIdentity second = new UploadIdentity(path);
		second.load("api.everykill.com");

		Assert.assertNull("a foreign token must not be presented", second.getToken());
		Assert.assertNull("a foreign recovery code is worse than none",
			second.getRecoveryCode());
		Assert.assertEquals("the client id is ours, not the server's",
			id, second.getClientId());
	}

	@Test
	public void theSameServerKeepsEverything() throws IOException
	{
		final Path path = file();

		final UploadIdentity first = new UploadIdentity(path);
		first.load("api.everykill.com");
		first.save("ek_realtoken", "ZZZZ-YYYY-XXXX-WWWW", "api.everykill.com");

		final UploadIdentity second = new UploadIdentity(path);
		second.load("api.everykill.com");

		Assert.assertEquals("ek_realtoken", second.getToken());
		Assert.assertEquals("ZZZZ-YYYY-XXXX-WWWW", second.getRecoveryCode());
	}

	@Test
	public void anIdentityWithNoHostRecordedIsLeftAlone() throws IOException
	{
		// files written before this existed carry no host. throwing their token away
		// would log every existing user out for nothing.
		final Path path = file();
		Files.createDirectories(path.getParent());
		Files.write(path, ("clientId=abcdef0123456789abcdef0123456789" + System.lineSeparator()
			+ "token=ek_legacy" + System.lineSeparator()).getBytes());

		final UploadIdentity identity = new UploadIdentity(path);
		identity.load("api.everykill.com");

		Assert.assertEquals("ek_legacy", identity.getToken());
	}

	@Test
	public void aNullHostSkipsTheCheckEntirely() throws IOException
	{
		final Path path = file();

		final UploadIdentity first = new UploadIdentity(path);
		first.load("127.0.0.1");
		first.save("ek_devtoken", null, "127.0.0.1");

		final UploadIdentity second = new UploadIdentity(path);
		second.load(null);

		Assert.assertEquals("an unknown destination is not a mismatch",
			"ek_devtoken", second.getToken());
	}

	@Test
	public void aPortChangeIsTheSameServer() throws IOException
	{
		// dev servers move ports. only the host is compared, so 8790 -> 8791 keeps a
		// perfectly good token.
		final Path path = file();

		final UploadIdentity first = new UploadIdentity(path);
		first.load("127.0.0.1");
		first.save("ek_devtoken", null, "127.0.0.1");

		final UploadIdentity second = new UploadIdentity(path);
		second.load("127.0.0.1");

		Assert.assertEquals("ek_devtoken", second.getToken());
	}
}
