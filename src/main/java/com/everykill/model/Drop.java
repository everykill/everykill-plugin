/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

/**
 * One item the server said a kill dropped.
 *
 * <p>Quantity is the server's own number, not a count of things on the ground. A stack
 * of 99 coins arrives as one drop with {@code quantity=99}, which is why nothing here
 * has to worry about piles merging.
 */
public class Drop
{
	public final int itemId;
	public final int quantity;

	public Drop(int itemId, int quantity)
	{
		this.itemId = itemId;
		this.quantity = quantity;
	}
}
