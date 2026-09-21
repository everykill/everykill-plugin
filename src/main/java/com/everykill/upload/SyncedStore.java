/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

/**
 * The bit of RuneLite's synced config this package needs.
 *
 * <p>An interface rather than {@code ConfigManager} directly because its constructor
 * signature changes between releases, and a test that news one up is a test that
 * breaks on someone else's refactor.
 */
public interface SyncedStore
{
	/**
	 * Whether a profile exists to read and write yet.
	 *
	 * <p>False before login. This is not the same as the store being absent:
	 * the object is always here, but RuneLite has no RS profile until someone
	 * logs in, so reads return null and writes are dropped. Treating "cannot
	 * read a profile id" as "this is a different account" is what fragmented
	 * real accounts into a new one per launch.
	 */
	boolean available();

	/**
	 * The current profile's key, or null when logged out.
	 *
	 * <p>Never send this as-is. RuneLite builds it as Base64 of the Jagex
	 * account hash, so it decodes straight back - see {@link AccountTag}.
	 */
	String profileKey();

	String get(String key);

	void put(String key, String value);

	void remove(String key);
}
