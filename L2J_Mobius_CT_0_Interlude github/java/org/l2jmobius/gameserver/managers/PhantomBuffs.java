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

import org.l2jmobius.gameserver.model.actor.Player;

/**
 * Shared buff plan for support phantoms (personal buddies + recruited buffers): decides which buffs a target
 * actually wants, by the target's archetype (caster vs fighter) and its role in the group, so a buffer stops
 * wasting physical buffs on casters (and vice-versa) and only fully buffs the player it serves.
 * <ul>
 * <li><b>LEADER</b> (the player being served): the full kit minus the wrong-archetype buffs (no Haste/Might/
 * Focus for a caster; no Acumen/Empower/Wild Magic for a fighter).</li>
 * <li><b>MEMBER</b> (other party members): the bare essentials - Wind Walk plus Haste (melee) or Acumen/
 * Berserker (caster).</li>
 * <li><b>SELF</b> (the buffer itself): just movement and casting speed - Wind Walk and Acumen.</li>
 * </ul>
 * Buff ids cover the Interlude Prophet / Elder / Warcryer sets plus their scroll/song/dance equivalents, so the
 * same plan works whatever class is doing the buffing.
 * @author Claude
 */
public final class PhantomBuffs
{
	/** Who a buff target is, relative to the buffer. */
	public enum Tier
	{
		LEADER,
		MEMBER,
		SELF
	}

	// Physical-only buffs: useless on a caster, so skipped for caster targets.
	private static final Set<Integer> MELEE = Set.of( //
		1086, 4357, 4402, // Haste
		1068, 4345, 4393, 5154, 1388, // Might / Greater Might
		1077, 4359, 4404, 5163, // Focus
		1242, 4360, 4405, // Death Whisper
		1240, 4358, 4403, 5162, // Guidance
		1268, 4354, 4399, // Vampiric Rage
		1087, 4406, 5161); // Agility

	// Magic-only buffs: useless on a fighter, so skipped for fighter targets.
	private static final Set<Integer> CASTER = Set.of( //
		1085, 4355, 4400, // Acumen
		1059, 4356, 4401, 5156, // Empower
		1303, 5164, // Wild Magic
		1397, // Clarity
		1078, 4351); // Concentration

	private static final Set<Integer> WIND_WALK = Set.of(1204, 4342, 4391);
	private static final Set<Integer> HASTE = Set.of(1086, 4357, 4402);
	private static final Set<Integer> ACUMEN = Set.of(1085, 4355, 4400);
	private static final Set<Integer> BERSERKER = Set.of(1062, 4352, 4397);

	private PhantomBuffs()
	{
	}

	/** @return {@code true} if a class is a magic user (caster), so it wants caster buffs, not physical ones. */
	public static boolean isCaster(Player player)
	{
		return player.getPlayerClass().isMage();
	}

	/**
	 * @param skillId the buff the buffer knows
	 * @param targetIsCaster whether the target is a magic user
	 * @param tier the target's role relative to the buffer
	 * @return {@code true} if this buff should be maintained on that target
	 */
	public static boolean wanted(int skillId, boolean targetIsCaster, Tier tier)
	{
		switch (tier)
		{
			case SELF:
			{
				return WIND_WALK.contains(skillId) || ACUMEN.contains(skillId);
			}
			case MEMBER:
			{
				if (WIND_WALK.contains(skillId))
				{
					return true;
				}
				return targetIsCaster ? (ACUMEN.contains(skillId) || BERSERKER.contains(skillId)) : HASTE.contains(skillId);
			}
			case LEADER:
			default:
			{
				if (targetIsCaster && MELEE.contains(skillId))
				{
					return false;
				}
				if (!targetIsCaster && CASTER.contains(skillId))
				{
					return false;
				}
				return true;
			}
		}
	}
}
