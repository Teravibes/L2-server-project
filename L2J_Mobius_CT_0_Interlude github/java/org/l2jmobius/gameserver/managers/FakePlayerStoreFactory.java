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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.RecipeData;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerCraftItem;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerStoreItem;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.recipe.RecipeList;
import org.l2jmobius.gameserver.model.item.type.CrystalType;
import org.l2jmobius.gameserver.model.item.type.EtcItemType;
import org.l2jmobius.gameserver.model.item.type.ItemType;

/**
 * Procedurally fills a fake player's private store with believable, obtainable stock.
 * <p>
 * Everything is driven by the datapack item table, so prices and grades are the game's own:
 * <ul>
 * <li><b>Realistic pricing</b> - each line is {@link ItemTemplate#getReferencePrice()} times a small
 * markup (sellers ask above, buyers offer below). No hand-tuned price list to maintain.</li>
 * <li><b>Grade scarcity</b> - equipment is picked by a weighted grade roll (no-grade/D common, S
 * extremely rare) and additionally capped by the vendor's level, so a low-level town never floods with
 * S-grade and high grades stay scarce everywhere.</li>
 * <li><b>Sensible amounts</b> - stackable goods (shots, mats, scrolls) come in bulk sized by their
 * value (cheap shots in the thousands, costly mats in single digits); equipment comes one at a time.</li>
 * <li><b>Matching titles</b> - {@link #title(String, List)} builds the sign ("WTS Soulshot: D 8.4k")
 * straight from the generated stock, so what is advertised is what is actually inside.</li>
 * </ul>
 * @author Claude
 */
public class FakePlayerStoreFactory
{
	// Synthetic, store-local object ids for SELL lines so the client can round-trip a purchase request.
	// They never address a real world object; the buy handler matches them only within one vendor.
	private static final AtomicInteger STORE_ITEM_OID = new AtomicInteger(0x60000000);

	// Relative scarcity per grade, indexed by CrystalType ordinal (NONE, D, C, B, A, S).
	private static final int[] GRADE_WEIGHT =
	{
		26, // NONE
		30, // D
		22, // C
		12, // B
		7, // A
		3 // S
	};

	// Currencies never belong in a store (a vendor "WTB Adena" makes no sense).
	private static final int ADENA_ID = 57;
	private static final int ANCIENT_ADENA_ID = 5575;

	// Filler words to ignore when matching a trade-ad phrase to an item name.
	private static final Set<String> MATCH_STOPWORDS = Set.of("grade", "gr", "the", "a", "an", "of", "for", "pls", "plz", "pm", "cheap", "each", "ea", "some", "any", "my", "g", "lvl");
	// Variant prefixes that should lose to the plain item when the rest matches (e.g. prefer "Soulshot: D-grade" over "Beast Soulshot").
	private static final String[] MATCH_NOISE =
	{
		"beast", "compressed", "package", "greater", "box", "event", "blessed"
	};

	private static volatile boolean _built = false;
	private static final EnumMap<CrystalType, List<ItemTemplate>> EQUIP = new EnumMap<>(CrystalType.class);
	private static final EnumMap<CrystalType, List<ItemTemplate>> BULK = new EnumMap<>(CrystalType.class);
	private static final EnumMap<CrystalType, List<RecipeList>> RECIPES = new EnumMap<>(CrystalType.class);

	private FakePlayerStoreFactory()
	{
	}

	/**
	 * Builds the item catalog once: equipment bucketed by grade, plus a bulk pool of stackable
	 * consumables/materials. Only tradeable, sellable, sanely priced items are kept.
	 */
	private static void build()
	{
		if (_built)
		{
			return;
		}
		synchronized (FakePlayerStoreFactory.class)
		{
			if (_built)
			{
				return;
			}
			for (CrystalType grade : CrystalType.values())
			{
				EQUIP.put(grade, new ArrayList<>());
				BULK.put(grade, new ArrayList<>());
				RECIPES.put(grade, new ArrayList<>());
			}
			for (ItemTemplate item : ItemData.getInstance().getAllItems())
			{
				if (item == null)
				{
					continue;
				}
				if ((item.getId() == ADENA_ID) || (item.getId() == ANCIENT_ADENA_ID))
				{
					continue;
				}
				final String name = item.getName();
				if ((name == null) || name.isEmpty() || name.equalsIgnoreCase("NULL"))
				{
					continue;
				}
				final int price = item.getReferencePrice();
				if ((price <= 0) || (price > 200_000_000))
				{
					continue;
				}
				if (!item.isTradeable() || !item.isSellable() || item.isQuestItem())
				{
					continue;
				}
				if (item.isEquipable())
				{
					EQUIP.get(item.getCrystalType()).add(item);
				}
				else if (item.isStackable() && isBulkType(item))
				{
					// Bucket consumables by their crystal grade too (soulshots/spiritshots carry crystal_type
					// D..S; gradeless mats fall into NONE and stay available to every town).
					BULK.get(item.getCrystalType()).add(item);
				}
			}
			// Recipes are bucketed by the grade of the item they produce, so crafters honour the same
			// level-gating and scarcity as sellers.
			for (RecipeList recipe : RecipeData.getInstance().getAllRecipes())
			{
				if (recipe == null)
				{
					continue;
				}
				final ItemTemplate product = ItemData.getInstance().getTemplate(recipe.getItemId());
				if ((product == null) || (product.getReferencePrice() <= 0) || !product.isTradeable())
				{
					continue;
				}
				final String name = product.getName();
				if ((name == null) || name.isEmpty() || name.equalsIgnoreCase("NULL"))
				{
					continue;
				}
				RECIPES.get(product.getCrystalType()).add(recipe);
			}
			_built = true;
		}
	}

	/** Keeps the bulk pool to consumables/materials players actually stock up on. */
	private static boolean isBulkType(ItemTemplate item)
	{
		final ItemType type = item.getItemType();
		if (!(type instanceof EtcItemType))
		{
			return false;
		}
		switch ((EtcItemType) type)
		{
			case NONE: // soulshots / spiritshots and misc tradeable goods
			case ARROW:
			case POTION:
			case ELIXIR:
			case SCROLL:
			case SCRL_ENCHANT_WP:
			case SCRL_ENCHANT_AM:
			case BLESS_SCRL_ENCHANT_WP:
			case BLESS_SCRL_ENCHANT_AM:
			case MATERIAL:
			{
				return true;
			}
			default:
			{
				return false;
			}
		}
	}

	/** Highest grade a vendor of this level is allowed to surface. */
	private static CrystalType maxGrade(int level)
	{
		if (level < 20)
		{
			return CrystalType.D;
		}
		if (level < 40)
		{
			return CrystalType.C;
		}
		if (level < 52)
		{
			return CrystalType.B;
		}
		if (level < 61)
		{
			return CrystalType.A;
		}
		return CrystalType.S;
	}

	/**
	 * Picks one equipment item: a weighted grade roll (capped by level) then a random item of that
	 * grade, stepping down to a lower grade if the rolled bucket happens to be empty.
	 */
	/**
	 * Picks one item from grade-bucketed pools: a weighted grade roll capped at {@code maxOrdinal}, then a
	 * random item of that grade, stepping down to a lower grade if the rolled bucket happens to be empty.
	 * @param buckets the grade-keyed pool (equipment or consumables)
	 * @param maxOrdinal the highest {@link CrystalType} ordinal allowed (the vendor's grade cap)
	 */
	private static ItemTemplate pickGraded(EnumMap<CrystalType, List<ItemTemplate>> buckets, int maxOrdinal)
	{
		int total = 0;
		for (int i = 0; i <= maxOrdinal; i++)
		{
			total += GRADE_WEIGHT[i];
		}
		if (total <= 0)
		{
			return null;
		}
		for (int attempt = 0; attempt < 6; attempt++)
		{
			int roll = Rnd.get(total);
			int chosen = 0;
			for (int i = 0; i <= maxOrdinal; i++)
			{
				roll -= GRADE_WEIGHT[i];
				if (roll < 0)
				{
					chosen = i;
					break;
				}
			}
			for (int i = chosen; i >= 0; i--)
			{
				final List<ItemTemplate> bucket = buckets.get(CrystalType.values()[i]);
				if (!bucket.isEmpty())
				{
					return bucket.get(Rnd.get(bucket.size()));
				}
			}
		}
		return null;
	}

	private static ItemTemplate pickEquip(int maxOrdinal)
	{
		return pickGraded(EQUIP, maxOrdinal);
	}

	/** Consumables are now grade-gated like equipment, so a low-level town surfaces its own tier of shots. */
	private static ItemTemplate pickBulk(int maxOrdinal)
	{
		return pickGraded(BULK, maxOrdinal);
	}

	/** The grade cap a vendor surfaces: full range for a market hub, else gated by its level. */
	private static int gradeCap(int level, boolean fullStock)
	{
		return fullStock ? (CrystalType.values().length - 1) : maxGrade(level).ordinal();
	}

	/**
	 * Best-effort resolve of a free-text item phrase from a trade-chat ad (e.g. "soulshots d grade",
	 * "iron ore") to a real tradeable item. Token-based: punctuation is ignored, plurals are folded and
	 * filler words ("grade", "cheap"…) dropped, so messy phrasing still finds "Soulshot: D-grade".
	 * @param phrase the words after WTS/WTB
	 * @return the closest matching item, or {@code null} if nothing reasonable matched
	 */
	public static ItemTemplate findItemByName(String phrase)
	{
		final List<String> wanted = matchTokens(phrase);
		if (wanted.isEmpty())
		{
			return null;
		}
		ItemTemplate best = null;
		int bestScore = Integer.MAX_VALUE;
		for (ItemTemplate item : ItemData.getInstance().getAllItems())
		{
			if ((item == null) || (item.getId() == ADENA_ID) || (item.getId() == ANCIENT_ADENA_ID))
			{
				continue;
			}
			final int price = item.getReferencePrice();
			if ((price <= 0) || (price > 200_000_000) || !item.isTradeable() || !item.isSellable() || item.isQuestItem())
			{
				continue;
			}
			final String name = item.getName();
			if ((name == null) || name.isEmpty())
			{
				continue;
			}
			final List<String> have = matchTokens(name);
			if (!have.containsAll(wanted))
			{
				continue; // every meaningful word the player typed must be in the item name
			}
			int score = have.size() - wanted.size(); // fewer extra words = closer match
			for (String noise : MATCH_NOISE)
			{
				if (have.contains(noise))
				{
					score += 5; // a plain item beats a Beast/Compressed/… variant
				}
			}
			if (score < bestScore)
			{
				bestScore = score;
				best = item;
			}
		}
		return best;
	}

	/** Normalises a name/phrase to lowercase word tokens: strips punctuation, drops filler, folds plurals. */
	private static List<String> matchTokens(String text)
	{
		final List<String> tokens = new ArrayList<>();
		if (text == null)
		{
			return tokens;
		}
		for (String word : text.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim().split(" "))
		{
			if (word.isEmpty() || MATCH_STOPWORDS.contains(word))
			{
				continue;
			}
			if ((word.length() > 3) && word.endsWith("s"))
			{
				word = word.substring(0, word.length() - 1); // crude singularize: soulshots -> soulshot
			}
			tokens.add(word);
		}
		return tokens;
	}

	/**
	 * One-line stock for a bot that is SELLING a specific item to the player (a WTB responder).
	 * @param itemId the item to sell
	 * @return a one-entry sell stock, or empty if the item is unknown
	 */
	public static List<FakePlayerStoreItem> dealSellStock(int itemId)
	{
		final List<FakePlayerStoreItem> stock = new ArrayList<>();
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		if (item != null)
		{
			final int count = item.isStackable() ? bulkAmount(item.getReferencePrice()) : 1;
			stock.add(line(item, 0, count, priced(item.getReferencePrice(), 1.0, 1.6)));
		}
		return stock;
	}

	/**
	 * Sell stock at an explicit agreed price per unit (from a whisper-negotiated deal).
	 * @param itemId the item to sell
	 * @param unitPrice the agreed adena per unit
	 * @return a one-entry sell stock
	 */
	public static List<FakePlayerStoreItem> dealSellStock(int itemId, int unitPrice)
	{
		final List<FakePlayerStoreItem> stock = new ArrayList<>();
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		if (item != null)
		{
			final int count = item.isStackable() ? bulkAmount(item.getReferencePrice()) : 1;
			stock.add(line(item, 0, count, Math.max(1, unitPrice)));
		}
		return stock;
	}

	/**
	 * One-line stock for a bot that is BUYING a specific item from the player (a WTS responder).
	 * @param itemId the item to buy
	 * @return a one-entry buy stock, or empty if the item is unknown
	 */
	public static List<FakePlayerStoreItem> dealBuyStock(int itemId)
	{
		final List<FakePlayerStoreItem> stock = new ArrayList<>();
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		if (item != null)
		{
			final int count = item.isStackable() ? bulkAmount(item.getReferencePrice()) : Rnd.get(1, 3);
			stock.add(line(item, 0, count, priced(item.getReferencePrice(), 0.5, 0.85)));
		}
		return stock;
	}

	/**
	 * Buy stock at an explicit agreed price per unit (from a whisper-negotiated deal).
	 * @param itemId the item to buy
	 * @param unitPrice the agreed adena per unit
	 * @return a one-entry buy stock
	 */
	public static List<FakePlayerStoreItem> dealBuyStock(int itemId, int unitPrice)
	{
		final List<FakePlayerStoreItem> stock = new ArrayList<>();
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		if (item != null)
		{
			final int count = item.isStackable() ? bulkAmount(item.getReferencePrice()) : Rnd.get(1, 3);
			stock.add(line(item, 0, count, Math.max(1, unitPrice)));
		}
		return stock;
	}

	/** A believable stack size for a stackable good, scaled inversely to its unit value. */
	private static int bulkAmount(int referencePrice)
	{
		if (referencePrice <= 10)
		{
			return Rnd.get(2000, 15000); // shots, arrows, cheap mats
		}
		if (referencePrice <= 60)
		{
			return Rnd.get(300, 3000);
		}
		if (referencePrice <= 600)
		{
			return Rnd.get(30, 400);
		}
		if (referencePrice <= 6000)
		{
			return Rnd.get(3, 40);
		}
		return Rnd.get(1, 8); // pricey mats / scrolls
	}

	/** referencePrice * random factor in [lo, hi], clamped to a valid positive int. */
	private static int priced(int referencePrice, double lo, double hi)
	{
		final double factor = lo + (Rnd.nextDouble() * (hi - lo));
		final long value = Math.round(referencePrice * factor);
		return (int) Math.max(1L, Math.min(value, Integer.MAX_VALUE));
	}

	private static FakePlayerStoreItem line(ItemTemplate item, int enchant, int count, int price)
	{
		return new FakePlayerStoreItem(STORE_ITEM_OID.getAndIncrement(), item.getId(), enchant, count, price);
	}

	/**
	 * Builds a SELL store: a few distinct lines, mostly bulk consumables with some equipment, priced a
	 * little above reference.
	 * @param level the vendor's level (gates equipment grade)
	 * @return the generated stock (may be empty if the catalog is unavailable)
	 */
	public static List<FakePlayerStoreItem> generateSell(int level, boolean fullStock)
	{
		build();
		final int cap = gradeCap(level, fullStock);
		final List<FakePlayerStoreItem> stock = new ArrayList<>();
		final Set<Integer> seen = new HashSet<>();
		final int lines = Rnd.get(2, 5);
		for (int i = 0; i < lines; i++)
		{
			final boolean bulk = Rnd.get(100) < 55;
			final ItemTemplate item = bulk ? pickBulk(cap) : pickEquip(cap);
			if ((item == null) || !seen.add(item.getId()))
			{
				continue;
			}
			final int count = bulk ? bulkAmount(item.getReferencePrice()) : 1;
			final int enchant = (!bulk && (item.getCrystalType().ordinal() >= 1) && (Rnd.get(100) < 15)) ? Rnd.get(1, 4) : 0;
			final int price = priced(item.getReferencePrice(), bulk ? 1.0 : 1.0, bulk ? 1.4 : 1.7);
			stock.add(line(item, enchant, count, price));
		}
		return stock;
	}

	/**
	 * Builds a BUY store: a few wanted items the bot will pay below reference for.
	 * @param level the vendor's level (gates equipment grade)
	 * @return the generated demand list
	 */
	public static List<FakePlayerStoreItem> generateBuy(int level, boolean fullStock)
	{
		build();
		final int cap = gradeCap(level, fullStock);
		final List<FakePlayerStoreItem> stock = new ArrayList<>();
		final Set<Integer> seen = new HashSet<>();
		final int lines = Rnd.get(1, 3);
		for (int i = 0; i < lines; i++)
		{
			// Buy stores lean toward bulk goods (mats/shots) with a minority wanting a piece of gear.
			final boolean bulk = Rnd.get(100) < 70;
			final ItemTemplate item = bulk ? pickBulk(cap) : pickEquip(cap);
			if ((item == null) || !seen.add(item.getId()))
			{
				continue;
			}
			final int count = bulk ? bulkAmount(item.getReferencePrice()) : Rnd.get(1, 3);
			final int price = priced(item.getReferencePrice(), 0.45, 0.8);
			stock.add(line(item, 0, count, price));
		}
		return stock;
	}

	/** Picks one recipe by the same weighted grade roll (capped at {@code maxOrdinal}) used for equipment. */
	private static RecipeList pickRecipe(int maxOrdinal)
	{
		int total = 0;
		for (int i = 0; i <= maxOrdinal; i++)
		{
			total += GRADE_WEIGHT[i];
		}
		if (total <= 0)
		{
			return null;
		}
		for (int attempt = 0; attempt < 6; attempt++)
		{
			int roll = Rnd.get(total);
			int chosen = 0;
			for (int i = 0; i <= maxOrdinal; i++)
			{
				roll -= GRADE_WEIGHT[i];
				if (roll < 0)
				{
					chosen = i;
					break;
				}
			}
			for (int i = chosen; i >= 0; i--)
			{
				final List<RecipeList> bucket = RECIPES.get(CrystalType.values()[i]);
				if (!bucket.isEmpty())
				{
					return bucket.get(Rnd.get(bucket.size()));
				}
			}
		}
		return null;
	}

	/**
	 * Builds a crafter's recipe board: a few recipes the bot will make, each with an adena fee. The
	 * customer supplies the materials.
	 * @param level the crafter's level (gates the grade of what it can make)
	 * @return the offered recipes (may be empty if no recipe data is available)
	 */
	public static List<FakePlayerCraftItem> generateCraftRecipes(int level, boolean fullStock)
	{
		build();
		final int cap = gradeCap(level, fullStock);
		final List<FakePlayerCraftItem> recipes = new ArrayList<>();
		final Set<Integer> seen = new HashSet<>();
		final int lines = Rnd.get(2, 5);
		for (int i = 0; i < lines; i++)
		{
			final RecipeList recipe = pickRecipe(cap);
			if ((recipe == null) || !seen.add(recipe.getId()))
			{
				continue;
			}
			final ItemTemplate product = ItemData.getInstance().getTemplate(recipe.getItemId());
			final int productValue = product == null ? 1000 : product.getReferencePrice();
			final int fee = (int) Math.max(100L, Math.min((long) (productValue * (0.08 + (Rnd.nextDouble() * 0.17))), Integer.MAX_VALUE));
			recipes.add(new FakePlayerCraftItem(recipe.getId(), fee));
		}
		return recipes;
	}

	/**
	 * @param recipes a crafter's offered recipes
	 * @return a sign such as "crafting Mithril Gaiters"
	 */
	public static String craftTitle(List<FakePlayerCraftItem> recipes)
	{
		if ((recipes == null) || recipes.isEmpty())
		{
			return FakePlayerAppearanceFactory.storeTitle("CRAFT");
		}
		ItemTemplate head = null;
		for (FakePlayerCraftItem entry : recipes)
		{
			final RecipeList recipe = RecipeData.getInstance().getRecipeList(entry.getRecipeListId());
			final ItemTemplate product = recipe == null ? null : ItemData.getInstance().getTemplate(recipe.getItemId());
			if ((product != null) && ((head == null) || (product.getReferencePrice() > head.getReferencePrice())))
			{
				head = product;
			}
		}
		String name = head == null ? "items" : head.getName();
		if (name.length() > 18)
		{
			name = name.substring(0, 18);
		}
		String result = "crafting " + name;
		if (result.length() > 28)
		{
			result = result.substring(0, 28);
		}
		return result;
	}

	/**
	 * Derives the store sign from the actual stock so the advertised headline matches the contents.
	 * @param kind SELL / BUY / CRAFT (or MANUFACTURE)
	 * @param stock the generated lines
	 * @return a short title such as "WTS Soulshot: D 8.4k" or "WTB Adena"
	 */
	public static String title(String kind, List<FakePlayerStoreItem> stock)
	{
		if ((stock == null) || stock.isEmpty())
		{
			return FakePlayerAppearanceFactory.storeTitle(kind);
		}
		FakePlayerStoreItem head = stock.get(0);
		for (FakePlayerStoreItem entry : stock)
		{
			if (entry.getPrice() > head.getPrice())
			{
				head = entry;
			}
		}
		final ItemTemplate item = head.getItem();
		String name = item == null ? "items" : item.getName();
		if (name.length() > 20)
		{
			name = name.substring(0, 20);
		}
		final String prefix = "BUY".equalsIgnoreCase(kind) ? "WTB " : ("CRAFT".equalsIgnoreCase(kind) || "MANUFACTURE".equalsIgnoreCase(kind)) ? "crafting " : "WTS ";
		final StringBuilder sb = new StringBuilder(prefix).append(name);
		if (head.getEnchant() > 0)
		{
			sb.append(" +").append(head.getEnchant());
		}
		if (head.getCount() > 1)
		{
			sb.append(' ').append(amount(head.getCount()));
		}
		String result = sb.toString();
		if (result.length() > 28)
		{
			result = result.substring(0, 28);
		}
		return result;
	}

	/** 15000 -> "15k", 1500 -> "1.5k", 800 -> "800". */
	private static String amount(int count)
	{
		if (count >= 1000)
		{
			final double thousands = count / 1000.0;
			return (thousands == Math.floor(thousands) ? String.valueOf((int) thousands) : String.format(Locale.US, "%.1f", thousands)) + "k";
		}
		return String.valueOf(count);
	}
}
