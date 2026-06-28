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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.skill.Skill;

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
		1059, 1462, 4356, 4401, 5156, // Empower / Greater Empower
		1303, 5164, // Wild Magic
		1397, // Clarity
		1078, 4351); // Concentration

	private static final Set<Integer> WIND_WALK = Set.of(1204, 4342, 4391);
	private static final Set<Integer> HASTE = Set.of(1086, 4357, 4402);
	private static final Set<Integer> ACUMEN = Set.of(1085, 4355, 4400);
	private static final Set<Integer> BERSERKER = Set.of(1062, 4352, 4397);

	// Pre-buff kits applied to a recruited member the moment it spawns, so it arrives already fully buffed for its
	// level (no need to re-buff a fresh party from scratch). Prophet/Elder primary ids; an unknown id is skipped.
	private static final int[] PREBUFF_COMMON =
	{
		1204 // Wind Walk
	};
	private static final int[] PREBUFF_MELEE =
	{
		1068, // Might
		1086, // Haste
		1077, // Focus
		1242, // Death Whisper
		1240, // Guidance
		1268, // Vampiric Rage
		1087, // Agility
		1040, // Shield
		1035 // Mental Shield
	};
	private static final int[] PREBUFF_CASTER =
	{
		1085, // Acumen
		1059, // Empower (Greater Empower is maintained by the buffer if known; pre-buff uses base Empower)
		1303, // Wild Magic
		1078, // Concentration
		1397, // Clarity
		1036, // Magic Barrier
		1035 // Mental Shield
	};

	// Buff names/aliases a player might ask for by name ("give me might", "ww pls"). Maps to a canonical word
	// matched against the skills the buffer actually knows (so "might" finds "Greater Might" too).
	private static final Map<String, String> BUFF_ALIASES = new HashMap<>();
	static
	{
		BUFF_ALIASES.put("wind walk", "wind walk");
		BUFF_ALIASES.put("ww", "wind walk");
		BUFF_ALIASES.put("haste", "haste");
		BUFF_ALIASES.put("acumen", "acumen");
		BUFF_ALIASES.put("might", "might");
		BUFF_ALIASES.put("shield", "shield");
		BUFF_ALIASES.put("focus", "focus");
		BUFF_ALIASES.put("death whisper", "death whisper");
		BUFF_ALIASES.put("dw", "death whisper");
		BUFF_ALIASES.put("guidance", "guidance");
		BUFF_ALIASES.put("empower", "empower");
		BUFF_ALIASES.put("berserker", "berserker");
		BUFF_ALIASES.put("zerk", "berserker");
		BUFF_ALIASES.put("vampiric rage", "vampiric rage");
		BUFF_ALIASES.put("vamp", "vampiric rage");
		BUFF_ALIASES.put("concentration", "concentration");
		BUFF_ALIASES.put("wild magic", "wild magic");
		BUFF_ALIASES.put("magic barrier", "magic barrier");
		BUFF_ALIASES.put("clarity", "clarity");
		BUFF_ALIASES.put("agility", "agility");
		BUFF_ALIASES.put("regeneration", "regeneration");
		BUFF_ALIASES.put("regen", "regeneration");
		BUFF_ALIASES.put("mental shield", "mental shield");
		BUFF_ALIASES.put("bless the body", "bless the body");
		BUFF_ALIASES.put("bless the soul", "bless the soul");
		BUFF_ALIASES.put("prophecy", "prophecy");
		BUFF_ALIASES.put("chant", "chant");
		BUFF_ALIASES.put("vampiric", "vampiric rage");
	}

	private PhantomBuffs()
	{
	}

	/**
	 * @return the canonical name of a buff the message asks for by name (e.g. "give me might" -> "might"), or
	 *         {@code null} if the line names no known buff. Longest alias wins ("magic barrier" over "shield").
	 */
	public static String requestedBuff(String message)
	{
		final String m = " " + message.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim() + " ";
		String best = null;
		for (String alias : BUFF_ALIASES.keySet())
		{
			if (m.contains(" " + alias + " ") && ((best == null) || (alias.length() > best.length())))
			{
				best = alias;
			}
		}
		return (best == null) ? null : BUFF_ALIASES.get(best);
	}

	/** @return a known buff whose name contains {@code canonicalName} (so "might" finds Greater Might), else null. */
	public static Skill findKnown(List<Skill> known, String canonicalName)
	{
		for (Skill skill : known)
		{
			if (skill.getName().toLowerCase().contains(canonicalName))
			{
				return skill;
			}
		}
		return null;
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
			case LEADER:
			default:
			{
				// Every party member now gets the full archetype-appropriate kit (was: members got bare essentials,
				// which left caster members without Empower/Wild Magic etc.). Casters skip physical buffs and
				// fighters skip caster buffs, but otherwise the buffer maintains the whole kit on everyone.
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

	/**
	 * Applies the full archetype-appropriate buff kit to {@code target} directly (used to pre-buff a recruited
	 * member the moment it spawns, so a fresh party arrives already buffed). Each buff is applied at its max level;
	 * unknown ids are skipped. The buffs are real effects with normal durations - the party's buffer keeps them up
	 * afterwards.
	 */
	public static void applyFullBuffs(Player target)
	{
		applyBuffs(target, PREBUFF_COMMON);
		applyBuffs(target, isCaster(target) ? PREBUFF_CASTER : PREBUFF_MELEE);
	}

	private static void applyBuffs(Player target, int[] ids)
	{
		for (int id : ids)
		{
			final int max = SkillData.getInstance().getMaxLevel(id);
			if (max <= 0)
			{
				continue;
			}
			final Skill skill = SkillData.getInstance().getSkill(id, max);
			if (skill != null)
			{
				skill.applyEffects(target, target);
			}
		}
	}
}
