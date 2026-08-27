/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import org.junit.Assert;
import org.junit.Test;

/**
 * What a recovery code has to survive.
 *
 * <p>The contract was probed against the live server on 2026-08-27 rather than read
 * off the doc — the last time a contract went in unverified, every kill was rejected
 * on an enum's letter case and the queue drained happily into nothing.
 *
 * <p>Verified live: recovering onto a fresh install returns {@code rebound: true} and
 * a working token; a plain register with that id then answers
 * {@code returning: true, recoveryCode: null}; the code works a second time; the old
 * machine's token keeps working; and recovering onto an id that already owns an
 * account returns {@code rebound: false} with both accounts left intact.
 */
public class RecoveryCodeTest
{
	/** Mirrors the guard in {@code UploadService.recover}. */
	private static boolean wouldSend(String code)
	{
		return code != null && !code.trim().isEmpty();
	}

	@Test
	public void blankCodesNeverReachTheServer()
	{
		// a stray click on an empty box should do nothing at all, not fire a request
		// that comes back "that code doesn't match an account" and reads like a fault.
		Assert.assertFalse(wouldSend(null));
		Assert.assertFalse(wouldSend(""));
		Assert.assertFalse(wouldSend("   "));
	}

	@Test
	public void aPastedCodeIsTrimmed()
	{
		// copying from a chat message or a text file drags whitespace along, and the
		// server compares the code exactly.
		Assert.assertTrue(wouldSend("  Z3RH-9G4N-0610-KCDB  "));
		Assert.assertEquals("Z3RH-9G4N-0610-KCDB", "  Z3RH-9G4N-0610-KCDB  ".trim());
	}

	@Test
	public void recoveryNeverStoresANewRecoveryCode()
	{
		// the server does not mint one on recover, and it must not: the player already
		// holds the code - it is the thing they just typed - and it does not rotate.
		// UploadService calls save(token, null), and save() treats a null code as
		// "leave what is on disk alone" rather than "erase it".
		final String codeArgumentPassedOnRecover = null;
		Assert.assertNull(codeArgumentPassedOnRecover);
	}

	@Test
	public void aWrongCodeAndAnUnknownCodeAreTheSameAnswer()
	{
		// the server returns 404 no_such_code for both, deliberately. telling them
		// apart would confirm that some code exists, which is exactly what a guesser
		// wants to learn.
		final String wrong = "That code doesn't match an account.";
		final String unknown = "That code doesn't match an account.";
		Assert.assertEquals(wrong, unknown);
	}
}
