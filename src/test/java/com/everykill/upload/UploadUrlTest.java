/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import java.net.URI;
import org.junit.Assert;
import org.junit.Test;

/**
 * The dev override must never become a user-supplied URL.
 *
 * <p>The Hub rule is <i>"One hardcoded API domain. No user-supplied URLs, ever."</i>
 * A local override is only acceptable while it is genuinely local — one that accepted
 * any host would be the forbidden thing spelled differently, and would turn the
 * plugin into an arbitrary exfiltration target for anyone who could talk a player
 * into pasting a link.
 *
 * <p>Mirrors {@code UploadService.isLoopback}, which is private.
 */
public class UploadUrlTest
{
	private static boolean isLoopback(String url)
	{
		try
		{
			final String host = URI.create(url).getHost();
			return "127.0.0.1".equals(host) || "localhost".equals(host)
				|| "[::1]".equals(host) || "::1".equals(host);
		}
		catch (IllegalArgumentException e)
		{
			return false;
		}
	}

	@Test
	public void loopbackAddressesAreAccepted()
	{
		Assert.assertTrue(isLoopback("http://127.0.0.1:8790"));
		Assert.assertTrue(isLoopback("http://localhost:8790"));
		Assert.assertTrue(isLoopback("http://127.0.0.1:8790/v1"));
	}

	@Test
	public void anyRemoteHostIsRejected()
	{
		Assert.assertFalse(isLoopback("https://evil.example.com"));
		Assert.assertFalse(isLoopback("http://192.168.1.50:8790"));
		Assert.assertFalse(isLoopback("https://api.everykill.com.evil.example.com"));
	}

	@Test
	public void aHostThatMerelyContainsLocalhostIsRejected()
	{
		// substring matching is how this check usually gets broken. "localhost" must
		// be the WHOLE host, not part of a longer one someone registered.
		Assert.assertFalse(isLoopback("https://localhost.evil.example.com"));
		Assert.assertFalse(isLoopback("https://notlocalhost"));
		Assert.assertFalse(isLoopback("https://127.0.0.1.evil.example.com"));
	}

	@Test
	public void credentialsInTheUrlCannotSmuggleARemoteHost()
	{
		// http://127.0.0.1@evil.example.com/ resolves to evil.example.com - the bit
		// before the @ is userinfo, not the host. reading it as a string would pass.
		Assert.assertFalse(isLoopback("http://127.0.0.1@evil.example.com/"));
		Assert.assertFalse(isLoopback("http://localhost@evil.example.com/"));
	}

	@Test
	public void rubbishIsRejectedRatherThanThrowing()
	{
		Assert.assertFalse(isLoopback("not a url"));
		Assert.assertFalse(isLoopback(""));
		Assert.assertFalse(isLoopback("://"));
	}

	@Test
	public void theProductionDomainIsNotLoopback()
	{
		// sanity: the real endpoint must fall through to the hardcoded constant
		// rather than being treated as an override.
		Assert.assertFalse(isLoopback("https://api.everykill.com"));
	}
}
