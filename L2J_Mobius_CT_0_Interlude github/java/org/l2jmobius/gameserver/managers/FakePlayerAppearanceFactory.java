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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerAppearance;

/**
 * Procedurally generates {@link FakePlayerAppearance}s: unique pronounceable names plus a random but
 * coherent race / gender / class / look. Gear slots are intentionally left empty for now (wiring in
 * verified Interlude item display ids is a separate, data-driven step) but the appearance object
 * already supports them via {@code setArmor}/{@code setWeapon}.
 * @author Claude
 */
public class FakePlayerAppearanceFactory
{
	// Syllable pools used to build pronounceable names; combined 2-3 at a time.
	private static final String[] NAME_START =
	{
		"Ael", "Bro", "Cad", "Dra", "Eil", "Fen", "Gor", "Hal", "Ith", "Jor",
		"Kel", "Lyr", "Mor", "Nyx", "Orin", "Pyr", "Quor", "Rha", "Syl", "Tor",
		"Ul", "Vae", "Wyn", "Xan", "Yor", "Zeph", "Ash", "Bel", "Cor", "Dûn"
	};
	private static final String[] NAME_MID =
	{
		"a", "e", "i", "o", "u", "ar", "en", "il", "or", "yn",
		"ael", "eth", "ith", "ond", "ura", "iel", "ven", "dor", "rim", "lan"
	};
	private static final String[] NAME_END =
	{
		"or", "an", "el", "is", "us", "yr", "wen", "dil", "rok", "tha",
		"mir", "ras", "nor", "vyn", "zar", "lin", "doth", "wyn", "gar", "eth"
	};

	// First-occupation (root) class ids per race for Interlude.
	private static final int[] HUMAN_CLASSES =
	{
		0, // Human Fighter
		10 // Human Mystic (Mage)
	};
	private static final int[] ELF_CLASSES =
	{
		18, // Elven Fighter
		25 // Elven Mystic
	};
	private static final int[] DARK_ELF_CLASSES =
	{
		31, // Dark Fighter
		38 // Dark Mystic
	};
	private static final int[] ORC_CLASSES =
	{
		44, // Orc Fighter
		49 // Orc Mystic
	};
	private static final int[] DWARF_CLASSES =
	{
		53 // Dwarven Fighter
	};

	// Races eligible for generated bots, weighted by repetition (humans most common, dwarves least).
	private static final Race[] RACE_POOL =
	{
		Race.HUMAN, Race.HUMAN, Race.HUMAN,
		Race.ELF, Race.ELF,
		Race.DARK_ELF, Race.DARK_ELF,
		Race.ORC,
		Race.DWARF
	};

	private static final Set<String> USED_NAMES = ConcurrentHashMap.newKeySet();

	private FakePlayerAppearanceFactory()
	{
	}

	/**
	 * Generates a fresh appearance with a random level in the given (inclusive) range.
	 * @param minLevel lowest level to roll
	 * @param maxLevel highest level to roll
	 * @return a populated {@link FakePlayerAppearance}
	 */
	public static FakePlayerAppearance generate(int minLevel, int maxLevel)
	{
		final Race race = RACE_POOL[Rnd.get(RACE_POOL.length)];
		final boolean female = (race != Race.ORC) && (race != Race.DWARF) ? Rnd.nextBoolean() : Rnd.get(100) < 30; // orcs/dwarves skew male
		final int classId = classForRace(race)[Rnd.get(classForRace(race).length)];
		final PlayerClass playerClass = PlayerClass.getPlayerClass(classId);

		final FakePlayerAppearance look = new FakePlayerAppearance();
		look.setName(generateName());
		look.setRace(race);
		look.setFemale(female);
		if (playerClass != null)
		{
			look.setPlayerClass(playerClass);
		}
		look.setLevel(Rnd.get(minLevel, maxLevel));
		look.setHairStyle(Rnd.get(0, 3));
		look.setHairColor(Rnd.get(0, 3));
		look.setFace(Rnd.get(0, 2));
		return look;
	}

	private static int[] classForRace(Race race)
	{
		switch (race)
		{
			case ELF:
			{
				return ELF_CLASSES;
			}
			case DARK_ELF:
			{
				return DARK_ELF_CLASSES;
			}
			case ORC:
			{
				return ORC_CLASSES;
			}
			case DWARF:
			{
				return DWARF_CLASSES;
			}
			default:
			{
				return HUMAN_CLASSES;
			}
		}
	}

	/**
	 * @return a unique pronounceable name (max 16 chars, as the client expects)
	 */
	public static String generateName()
	{
		for (int attempt = 0; attempt < 50; attempt++)
		{
			final StringBuilder sb = new StringBuilder(NAME_START[Rnd.get(NAME_START.length)]);
			if (Rnd.nextBoolean())
			{
				sb.append(NAME_MID[Rnd.get(NAME_MID.length)]);
			}
			sb.append(NAME_END[Rnd.get(NAME_END.length)]);

			String name = sb.toString();
			if (name.length() > 16)
			{
				name = name.substring(0, 16);
			}
			if (USED_NAMES.add(name.toLowerCase()))
			{
				return name;
			}
		}

		// Fallback: guarantee uniqueness with a numeric suffix.
		String name;
		do
		{
			name = NAME_START[Rnd.get(NAME_START.length)] + Rnd.get(1000);
		}
		while (!USED_NAMES.add(name.toLowerCase()));
		return name;
	}
}
