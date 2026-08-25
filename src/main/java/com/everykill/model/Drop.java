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

	// resolved on the client thread when the drop lands. the panel paints on the swing
	// thread and ItemManager reads through to the client, so looking it up at paint
	// time is the wrong side of that line. null when the composition wasn't loaded.
	public final String name;

	// price per item, read on the client thread with the name. getItemPrice asserts
	// it's on that thread, so a panel that asks at paint time takes down the repaint.
	public final int price;

	public Drop(int itemId, int quantity)
	{
		this(itemId, quantity, null, 0);
	}

	public Drop(int itemId, int quantity, String name)
	{
		this(itemId, quantity, name, 0);
	}

	public Drop(int itemId, int quantity, String name, int price)
	{
		this.itemId = itemId;
		this.quantity = quantity;
		this.name = name;
		this.price = price;
	}
}
