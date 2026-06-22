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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.AutoPlayConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.player.AutoPlaySettingsHolder;
import org.l2jmobius.gameserver.model.actor.holders.player.AutoUseSettingsHolder;
import org.l2jmobius.gameserver.model.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.type.CrystalType;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;

/**
 * Real-Player phantom system.
 * <p>
 * A phantom is a genuine {@link Player} database object with no client attached - the same
 * clientless-Player pattern Mobius ships for offline traders / offline-play
 * ({@code OfflineTraderTable}, {@code OfflinePlayTable}). That gives it real skills, inventory, stats,
 * leveling and PvP eligibility an NPC cannot have.
 * <p>
 * <b>Combat is delegated to the engine's native auto-hunt</b> ({@link AutoPlayTaskManager} +
 * {@link AutoUseTaskManager}) - the exact systems {@code OfflinePlayTable.restoreOfflinePlayers}
 * uses to make a clientless player farm. They handle target selection, movement (geodata
 * pathfinding), attacking, and - once a phantom has them - skills, buffs, soulshots and potions. This
 * manager only owns the <b>macro</b> layer: spawning, supervision and reviving. Hunting-zone routing
 * and procedural identities/gear are later increments.
 * @author Claude
 */
public class PhantomManager
{
	private static final Logger LOGGER = Logger.getLogger(PhantomManager.class.getName());

	private static final String ACCOUNT_NAME = "phantom";
	// Supervisor cadence: cleanup gone phantoms and revive dead ones. Combat runs on AutoPlay's own
	// 700ms loop, so this can be lazy.
	private static final long SUPERVISE_INTERVAL = 5000;
	// How long a dead phantom stays down before it is revived at its home spot and resumes hunting.
	private static final long REVIVE_DELAY = 15000;
	// AutoPlay "next target" modes (mirrors the client toggle): 1 = monsters only.
	private static final int TARGET_MODE_MONSTER = 1;
	// AutoPlay auto-action id for the basic melee attack. Without it, AutoPlay treats the character as a
	// mage caster that never auto-hits (see AutoPlayTaskManager.isMageCaster).
	private static final int AUTO_ATTACK_ACTION = 2;
	// How many soulshots to hand a freshly geared phantom (no runtime restock yet).
	private static final int SHOT_COUNT = 5000;

	// Cheapest (i.e. most basic) one-handed-capable SWORD per grade, resolved once from the datapack so
	// phantoms equip a weapon Power Strike / Mortal Blow can actually be used with.
	private static final EnumMap<CrystalType, ItemTemplate> SWORD_BY_GRADE = new EnumMap<>(CrystalType.class);
	private static volatile boolean _swordsBuilt = false;

	private static class PhantomData
	{
		final Player player;
		final Location home;
		long deadSince; // 0 while alive

		PhantomData(Player player, Location home)
		{
			this.player = player;
			this.home = home;
		}
	}

	private final ConcurrentHashMap<Integer, PhantomData> _phantoms = new ConcurrentHashMap<>();
	private boolean _supervising = false;

	protected PhantomManager()
	{
	}

	/**
	 * Creates a brand-new clientless fighter phantom, spawns it, levels/skills it, and hands it to the
	 * native auto-hunt so it seeks and fights monsters on its own.
	 * @param location where to spawn (also its revive home)
	 * @param level the level to bring the phantom to (1 = a raw fighter)
	 * @return the spawned phantom, or {@code null} on failure
	 */
	public Player spawnPhantom(Location location, int level)
	{
		if (!AutoPlayConfig.ENABLE_AUTO_PLAY)
		{
			LOGGER.warning(getClass().getSimpleName() + ": EnableAutoPlay is false in config/Custom/AutoPlay.ini - phantoms will spawn but not hunt.");
		}

		try
		{
			final PlayerTemplate template = PlayerTemplateData.getInstance().getTemplate(PlayerClass.FIGHTER);
			if (template == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": No FIGHTER template available.");
				return null;
			}

			final PlayerAppearance appearance = new PlayerAppearance((byte) 0, (byte) 0, (byte) 0, false);
			final Player phantom = Player.create(template, ACCOUNT_NAME, nextName(), appearance);
			if (phantom == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Player.create returned null (duplicate name / db error?).");
				return null;
			}

			// Level, skill and gear the phantom BEFORE it enters the world, so the very first CharInfo
			// nearby players receive already shows its weapon. (A post-spawn equip update can be throttled
			// or coalesced by broadcastCharInfo, which left the weapon invisible even though it was equipped
			// and working - soulshots fired but no blade rendered.)
			phantom.setOnlineStatus(true, false);
			outfit(phantom, level);
			enterWorld(phantom, location);
			enableAutoHunt(phantom);

			_phantoms.put(phantom.getObjectId(), new PhantomData(phantom, location));
			startSupervising();
			LOGGER.info(getClass().getSimpleName() + ": Spawned phantom '" + phantom.getName() + "' (objId=" + phantom.getObjectId() + ").");
			return phantom;
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to spawn phantom: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Drops an already-leveled, already-geared clientless player into the world exactly the way
	 * offline-play characters are restored - no GameClient is ever attached. {@code setOfflinePlay(true)}
	 * is required so the AutoPlay loop does not treat the missing client as a plain offline-shop and stop
	 * the task.
	 */
	private void enterWorld(Player phantom, Location location)
	{
		phantom.setCurrentHp(phantom.getMaxHp());
		phantom.setCurrentMp(phantom.getMaxMp());
		phantom.setCurrentCp(phantom.getMaxCp());
		phantom.spawnMe(location.getX(), location.getY(), location.getZ());
		phantom.setOfflinePlay(true);
		phantom.setOnlineStatus(true, true);
		phantom.setRunning();
		phantom.broadcastUserInfo();
	}

	/**
	 * Brings a phantom to the requested level (by granting the exact experience for it, the same way the
	 * admin level command does), learns its class skills, tops up its bars, and registers those skills
	 * with the auto-use system so the engine casts them in combat. Gear (weapon/armor) and soulshots are
	 * a later increment.
	 * @param phantom the phantom to outfit
	 * @param level the target level
	 */
	private void outfit(Player phantom, int level)
	{
		if (level > 1)
		{
			final long currentExp = phantom.getExp();
			final long targetExp = ExperienceData.getInstance().getExpForLevel(level);
			if (targetExp > currentExp)
			{
				// Leveling up through addExpAndSp rewards the class skills for each level along the way.
				phantom.addExpAndSp(targetExp - currentExp, 0);
			}
		}
		// Belt and braces: make sure every auto-get class skill for the final level is learned. This must
		// happen before gear() so the matching Expertise passive is known and the weapon has no grade
		// penalty (which would also disable soulshots).
		phantom.rewardSkills();
		gear(phantom, level);
		phantom.setCurrentHpMp(phantom.getMaxHp(), phantom.getMaxMp());
		phantom.setCurrentCp(phantom.getMaxCp());
		registerAutoSkills(phantom);
	}

	/**
	 * Equips a grade-appropriate sword and hands over matching soulshots (auto-enabled). The sword is
	 * what lets the base-fighter attack skills (Power Strike / Mortal Blow) actually fire - without a
	 * weapon those skills cannot be cast, so a naked phantom only throws punches. Armor is a later step.
	 * @param phantom the phantom to gear
	 * @param level its level (decides the grade tier)
	 */
	private void gear(Player phantom, int level)
	{
		buildSwords();

		// Pick the sword for this level's grade, stepping down if a grade has no candidate.
		CrystalType grade = gradeForLevel(level);
		ItemTemplate sword = null;
		for (int ordinal = grade.ordinal(); (ordinal >= 0) && (sword == null); ordinal--)
		{
			final CrystalType candidateGrade = CrystalType.values()[ordinal];
			sword = SWORD_BY_GRADE.get(candidateGrade);
			if (sword != null)
			{
				grade = candidateGrade;
			}
		}
		if (sword == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": No sword found in the datapack - phantom stays unarmed.");
			return;
		}

		final Item weapon = phantom.getInventory().addItem(ItemProcessType.REWARD, sword.getId(), 1, phantom, null);
		if (weapon != null)
		{
			phantom.getInventory().equipItem(weapon);
		}

		// Soulshots must match the equipped weapon's grade exactly.
		final int shotId = soulshotIdFor(grade);
		phantom.getInventory().addItem(ItemProcessType.REWARD, shotId, SHOT_COUNT, phantom, null);
		phantom.addAutoSoulShot(shotId);
		// No broadcast here: gear() runs before the phantom enters the world, so the weapon is already in
		// place for the spawn CharInfo (enterWorld broadcasts afterwards).
	}

	/** Highest weapon grade a phantom of this level may wield (aligned with when Expertise is learned). */
	private static CrystalType gradeForLevel(int level)
	{
		if (level < 20)
		{
			return CrystalType.NONE;
		}
		if (level < 40)
		{
			return CrystalType.D;
		}
		if (level < 52)
		{
			return CrystalType.C;
		}
		if (level < 61)
		{
			return CrystalType.B;
		}
		if (level < 76)
		{
			return CrystalType.A;
		}
		return CrystalType.S;
	}

	/** Soulshot item id matching a weapon grade. */
	private static int soulshotIdFor(CrystalType grade)
	{
		switch (grade)
		{
			case D:
			{
				return 1463;
			}
			case C:
			{
				return 1464;
			}
			case B:
			{
				return 1465;
			}
			case A:
			{
				return 1466;
			}
			case S:
			{
				return 1467;
			}
			default:
			{
				return 1835; // No Grade
			}
		}
	}

	/** Resolves the cheapest (most basic) tradeable SWORD per grade from the datapack, once. */
	private static void buildSwords()
	{
		if (_swordsBuilt)
		{
			return;
		}
		synchronized (SWORD_BY_GRADE)
		{
			if (_swordsBuilt)
			{
				return;
			}
			for (ItemTemplate item : ItemData.getInstance().getAllItems())
			{
				if (!(item instanceof Weapon) || (((Weapon) item).getItemType() != WeaponType.SWORD))
				{
					continue;
				}
				if (!item.isEquipable() || !item.isTradeable())
				{
					continue;
				}
				final int price = item.getReferencePrice();
				if (price <= 0)
				{
					continue;
				}
				final CrystalType grade = item.getCrystalType();
				final ItemTemplate current = SWORD_BY_GRADE.get(grade);
				if ((current == null) || (price < current.getReferencePrice()))
				{
					SWORD_BY_GRADE.put(grade, item);
				}
			}
			_swordsBuilt = true;
		}
	}

	/**
	 * Registers the phantom's own learned skills with the native auto-use system: self-buffs go to the
	 * auto-buff list (recast when they drop), offensive actives go to the auto-skill list (cast on the
	 * current target in combat). Classification is by skill metadata, so it is Interlude-data-driven and
	 * needs no hard-coded skill ids.
	 */
	private void registerAutoSkills(Player phantom)
	{
		final AutoUseSettingsHolder autoUse = phantom.getAutoUseSettings();
		for (Skill skill : phantom.getAllSkills())
		{
			if (skill.isPassive() || skill.isToggle())
			{
				continue;
			}
			// Self-buffs: lasting, beneficial, cast on self.
			if (skill.isContinuous() && !skill.isDebuff() && (skill.getEffectPoint() >= 0) && (skill.getTargetType() == TargetType.SELF))
			{
				autoUse.getAutoBuffs().add(skill.getId());
			}
			// Offensive actives: instant (non-continuous) harmful skills used on the target.
			else if (skill.isActive() && !skill.isContinuous() && (skill.getEffectPoint() < 0))
			{
				autoUse.getAutoSkills().add(skill.getId());
			}
		}
	}

	/**
	 * Configures the auto-hunt preferences and starts the native AutoPlay + AutoUse tasks. Soulshots
	 * would be registered here too (via {@code addAutoSoulShot}) once phantoms are geared with a weapon.
	 */
	private void enableAutoHunt(Player phantom)
	{
		final AutoPlaySettingsHolder settings = phantom.getAutoPlaySettings();
		settings.setNextTargetMode(TARGET_MODE_MONSTER);
		settings.setShortRange(false); // search the long range so it actually goes looking for mobs
		settings.setRespectfulHunting(false); // do not skip mobs already targeting something
		settings.setPickup(true);

		// Melee auto-attack; without this AutoPlay assumes a non-hitting mage caster.
		phantom.getAutoUseSettings().getAutoActions().add(AUTO_ATTACK_ACTION);

		AutoPlayTaskManager.getInstance().startAutoPlay(phantom);
		AutoUseTaskManager.getInstance().startAutoUseTask(phantom);
	}

	/** Despawns and forgets every phantom (does not delete the DB rows). */
	public int clear()
	{
		int removed = 0;
		for (PhantomData data : new ArrayList<>(_phantoms.values()))
		{
			try
			{
				AutoPlayTaskManager.getInstance().stopAutoPlay(data.player);
				AutoUseTaskManager.getInstance().stopAutoUseTask(data.player);
				data.player.deleteMe();
				removed++;
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Failed to despawn phantom " + data.player.getObjectId() + ": " + e.getMessage());
			}
		}
		_phantoms.clear();
		return removed;
	}

	public int getCount()
	{
		return _phantoms.size();
	}

	private synchronized void startSupervising()
	{
		if (_supervising)
		{
			return;
		}
		_supervising = true;
		ThreadPool.scheduleAtFixedRate(this::supervise, SUPERVISE_INTERVAL, SUPERVISE_INTERVAL);
		LOGGER.info(getClass().getSimpleName() + ": Phantom supervisor started.");
	}

	private void supervise()
	{
		final long now = System.currentTimeMillis();
		for (PhantomData data : new ArrayList<>(_phantoms.values()))
		{
			final Player phantom = data.player;
			try
			{
				// Forget phantoms that left the world for good.
				if (World.getInstance().findObject(phantom.getObjectId()) == null)
				{
					_phantoms.remove(phantom.getObjectId());
					continue;
				}

				if (phantom.isDead())
				{
					if (data.deadSince == 0)
					{
						data.deadSince = now;
					}
					else if ((now - data.deadSince) >= REVIVE_DELAY)
					{
						revive(data);
					}
					continue;
				}

				// Alive again / still alive: clear the death stamp.
				data.deadSince = 0;
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Supervise error for " + phantom.getName() + ": " + e.getMessage());
			}
		}
	}

	/** Brings a dead phantom back to full health at its home spot and resumes the auto-hunt. */
	private void revive(PhantomData data)
	{
		final Player phantom = data.player;
		phantom.doRevive();
		phantom.setCurrentHp(phantom.getMaxHp());
		phantom.setCurrentMp(phantom.getMaxMp());
		phantom.setCurrentCp(phantom.getMaxCp());
		phantom.teleToLocation(data.home);
		phantom.setRunning();
		data.deadSince = 0;
		// AutoPlay keeps the player pooled across death; only restart if it somehow dropped out.
		if (!phantom.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().startAutoPlay(phantom);
			AutoUseTaskManager.getInstance().startAutoUseTask(phantom);
		}
	}

	/** Generates a unique character name not already taken in the DB (slice naming; identities come later). */
	private String nextName()
	{
		for (int attempt = 0; attempt < 50; attempt++)
		{
			final String name = "Phantom" + Rnd.get(1000, 9999);
			if (!CharInfoTable.getInstance().doesCharNameExist(name))
			{
				return name;
			}
		}
		// Extremely unlikely fallback.
		return "Phantom" + System.currentTimeMillis();
	}

	public static PhantomManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomManager INSTANCE = new PhantomManager();
	}
}
