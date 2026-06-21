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
package org.l2jmobius.gameserver.network.serverpackets;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.commons.network.WritableBuffer;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerAppearance;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerStoreItem;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.ServerPackets;

/**
 * The BUY store window for a fake-player vendor: lists the items the bot wants that the viewing player
 * actually owns, at the bot's offered price. Mirrors {@link PrivateStoreListBuy}'s wire format.
 * @author Claude
 */
public class FakePlayerStoreListBuy extends ServerPacket
{
	private static class Row
	{
		final int itemObjectId;
		final int itemId;
		final int enchant;
		final int playerCount;
		final int referencePrice;
		final int bodyMask;
		final int type2;
		final int price;
		final int maxTrade;

		Row(int itemObjectId, int itemId, int enchant, int playerCount, int referencePrice, int bodyMask, int type2, int price, int maxTrade)
		{
			this.itemObjectId = itemObjectId;
			this.itemId = itemId;
			this.enchant = enchant;
			this.playerCount = playerCount;
			this.referencePrice = referencePrice;
			this.bodyMask = bodyMask;
			this.type2 = type2;
			this.price = price;
			this.maxTrade = maxTrade;
		}
	}

	private final int _objectId;
	private final int _playerAdena;
	private final List<Row> _rows = new ArrayList<>();

	public FakePlayerStoreListBuy(Player player, Npc npc)
	{
		_objectId = npc.getObjectId();
		_playerAdena = player.getAdena();
		final FakePlayerAppearance look = npc.getFakePlayerAppearance();
		if (look == null)
		{
			return;
		}
		for (FakePlayerStoreItem demand : look.getStoreItems())
		{
			final Item owned = player.getInventory().getItemByItemId(demand.getItemId());
			if ((owned == null) || !owned.isTradeable() || owned.isEquipped())
			{
				continue;
			}
			final ItemTemplate template = demand.getItem();
			if (template == null)
			{
				continue;
			}
			_rows.add(new Row(owned.getObjectId(), template.getId(), owned.getEnchantLevel(), owned.getCount(), template.getReferencePrice(), template.getBodyPart().getMask(), template.getType2(), demand.getPrice(), Math.min(demand.getCount(), owned.getCount())));
		}
	}

	@Override
	public void writeImpl(GameClient client, WritableBuffer buffer)
	{
		ServerPackets.PRIVATE_STORE_BUY_LIST.writeId(this, buffer);
		buffer.writeInt(_objectId);
		buffer.writeInt(_playerAdena);
		buffer.writeInt(_rows.size());
		for (Row row : _rows)
		{
			buffer.writeInt(row.itemObjectId);
			buffer.writeInt(row.itemId);
			buffer.writeShort(row.enchant);
			buffer.writeInt(row.playerCount); // max the player could sell
			buffer.writeInt(row.referencePrice);
			buffer.writeShort(0);
			buffer.writeInt(row.bodyMask);
			buffer.writeShort(row.type2);
			buffer.writeInt(row.price); // buyer's price
			buffer.writeInt(row.maxTrade); // max the bot will take
		}
	}
}
