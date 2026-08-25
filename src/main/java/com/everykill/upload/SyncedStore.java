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
	String get(String key);

	void put(String key, String value);

	void remove(String key);
}
