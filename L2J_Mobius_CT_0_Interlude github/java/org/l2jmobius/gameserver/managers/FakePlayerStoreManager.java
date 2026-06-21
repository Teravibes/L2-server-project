/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.managers;

import static org.l2jmobius.gameserver.model.actor.Npc.INTERACTION_DISTANCE;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerAppearance;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerStoreItem;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.holders.RequestTrade;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.FakePlayerStoreListBuy;
import org.l2jmobius.gameserver.network.serverpackets.FakePlayerStoreListSell;

/**
 * Turns the visual fake-player vendors into working shops. It sends the right store window when a
 * vendor is clicked and carries out real purchases/sales: adena moves, items transfer, the vendor's
 * stock is decremented, and a depleted vendor restocks (with a fresh, matching sign) so the world
 * never runs dry.
 * <p>
 * SELL/PACKAGE and BUY are fully transactional; CRAFT vendors are deployed as finished-goods SELL
 * stores, so they are purchasable too.
 * @author Claude
 */
public class FakePlayerStoreManager
{
	private FakePlayerStoreManager()
	{
	}

	/**
	 * Opens the appropriate store window for a clicked vendor bot.
	 * @return {@code true} if a store window was sent (caller should skip the normal chat/follow)
	 */
	public static boolean openStore(Player player, Npc npc)
	{
		final FakePlayerAppearance look = npc.getFakePlayerAppearance();
		if ((look == null) || (look.getPrivateStoreType() == 0))
		{
			return false;
		}
		if (look.getPrivateStoreType() == PrivateStoreType.BUY.getId())
		{
			player.sendPacket(new FakePlayerStoreListBuy(player, npc));
		}
		else
		{
			player.sendPacket(new FakePlayerStoreListSell(player, npc));
		}
		return true;
	}

	/**
	 * Player buys from a SELL/PACKAGE vendor bot: validates the request, charges adena, hands over the
	 * items and decrements the bot's stock.
	 */
	public static void buy(Player player, Npc npc, Set<RequestTrade> items)
	{
		final FakePlayerAppearance look = npc.getFakePlayerAppearance();
		if ((items == null) || items.isEmpty() || (look == null) || !commonChecks(player, npc))
		{
			return;
		}
		if ((look.getPrivateStoreType() != PrivateStoreType.SELL.getId()) && (look.getPrivateStoreType() != PrivateStoreType.PACKAGE_SELL.getId()))
		{
			return;
		}

		// Validate every line against the live stock, then total it up (anti-tamper on price/amount).
		long total = 0;
		final Map<FakePlayerStoreItem, Integer> plan = new LinkedHashMap<>();
		for (RequestTrade request : items)
		{
			final FakePlayerStoreItem entry = findByObjectId(look, request.getObjectId());
			if (entry == null)
			{
				player.sendPacket(ActionFailed.STATIC_PACKET);
				return;
			}
			final int want = plan.getOrDefault(entry, 0) + request.getCount();
			if ((request.getCount() < 1) || (want > entry.getCount()) || (request.getPrice() != entry.getPrice()))
			{
				player.sendPacket(ActionFailed.STATIC_PACKET);
				return;
			}
			total += (long) request.getCount() * entry.getPrice();
			plan.put(entry, want);
		}

		if ((total <= 0) || (total > player.getAdena()))
		{
			player.sendMessage("You do not have enough adena.");
			player.sendPacket(new FakePlayerStoreListSell(player, npc));
			return;
		}
		if (!player.reduceAdena(ItemProcessType.BUY, (int) total, npc, true))
		{
			return;
		}

		for (Map.Entry<FakePlayerStoreItem, Integer> bought : plan.entrySet())
		{
			final FakePlayerStoreItem entry = bought.getKey();
			player.addItem(ItemProcessType.BUY, entry.getItemId(), bought.getValue(), entry.getEnchant(), npc, true);
			entry.decrease(bought.getValue());
		}

		restock(npc, look, false);
		player.sendPacket(new FakePlayerStoreListSell(player, npc));
	}

	/**
	 * Player sells to a BUY vendor bot: validates ownership, removes the items, pays the player and
	 * decrements the bot's remaining demand.
	 */
	public static void sell(Player player, Npc npc, RequestTrade[] items)
	{
		final FakePlayerAppearance look = npc.getFakePlayerAppearance();
		if ((items == null) || (items.length == 0) || (look == null) || !commonChecks(player, npc))
		{
			return;
		}
		if (look.getPrivateStoreType() != PrivateStoreType.BUY.getId())
		{
			return;
		}

		long total = 0;
		final List<int[]> plan = new ArrayList<>(); // {itemObjectId, count}
		final Map<FakePlayerStoreItem, Integer> taken = new LinkedHashMap<>();
		for (RequestTrade request : items)
		{
			final Item owned = player.getInventory().getItemByObjectId(request.getObjectId());
			if ((owned == null) || (owned.getId() != request.getItemId()) || !owned.isTradeable() || owned.isEquipped())
			{
				player.sendPacket(ActionFailed.STATIC_PACKET);
				return;
			}
			final FakePlayerStoreItem demand = findByItemId(look, request.getItemId());
			if (demand == null)
			{
				player.sendPacket(ActionFailed.STATIC_PACKET);
				return;
			}
			final int alreadyTaken = taken.getOrDefault(demand, 0);
			final int want = Math.min(request.getCount(), Math.min(owned.getCount(), demand.getCount() - alreadyTaken));
			if ((want < 1) || (request.getPrice() != demand.getPrice()))
			{
				player.sendPacket(ActionFailed.STATIC_PACKET);
				return;
			}
			total += (long) want * demand.getPrice();
			plan.add(new int[]
			{
				owned.getObjectId(),
				want
			});
			taken.put(demand, alreadyTaken + want);
		}

		if (total <= 0)
		{
			return;
		}
		if (total > Integer.MAX_VALUE)
		{
			total = Integer.MAX_VALUE;
		}

		for (int[] row : plan)
		{
			player.destroyItem(ItemProcessType.SELL, row[0], row[1], npc, true);
		}
		for (Map.Entry<FakePlayerStoreItem, Integer> sold : taken.entrySet())
		{
			sold.getKey().decrease(sold.getValue());
		}
		player.addAdena(ItemProcessType.SELL, (int) total, npc, true);

		restock(npc, look, true);
		player.sendPacket(new FakePlayerStoreListBuy(player, npc));
	}

	private static boolean commonChecks(Player player, Npc npc)
	{
		if (!player.isInsideRadius3D(npc, INTERACTION_DISTANCE))
		{
			return false;
		}
		if (!player.getAccessLevel().allowTransaction())
		{
			player.sendMessage("Transactions are disabled for your Access Level.");
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return false;
		}
		return true;
	}

	private static FakePlayerStoreItem findByObjectId(FakePlayerAppearance look, int objectId)
	{
		for (FakePlayerStoreItem entry : look.getStoreItems())
		{
			if (entry.getObjectId() == objectId)
			{
				return entry;
			}
		}
		return null;
	}

	private static FakePlayerStoreItem findByItemId(FakePlayerAppearance look, int itemId)
	{
		for (FakePlayerStoreItem entry : look.getStoreItems())
		{
			if ((entry.getItemId() == itemId) && (entry.getCount() > 0))
			{
				return entry;
			}
		}
		return null;
	}

	/**
	 * Drops sold-out lines; when the whole store empties, generates a fresh stock and re-signs the
	 * vendor so it keeps trading.
	 * @param buying {@code true} for a BUY vendor (regenerate demand), {@code false} for SELL
	 */
	private static void restock(Npc npc, FakePlayerAppearance look, boolean buying)
	{
		final List<FakePlayerStoreItem> remaining = new ArrayList<>();
		for (FakePlayerStoreItem entry : look.getStoreItems())
		{
			if (entry.getCount() > 0)
			{
				remaining.add(entry);
			}
		}
		if (!remaining.isEmpty())
		{
			look.setStoreItems(remaining);
			return;
		}
		final int level = look.getLevel();
		final List<FakePlayerStoreItem> fresh = buying ? FakePlayerStoreFactory.generateBuy(level) : FakePlayerStoreFactory.generateSell(level);
		look.setStoreItems(fresh);
		look.setStore(look.getPrivateStoreType(), FakePlayerStoreFactory.title(buying ? "BUY" : "SELL", fresh));
		npc.updateAbnormalEffect(); // refresh the sign above the vendor for everyone nearby
	}
}
