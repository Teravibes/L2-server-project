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

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.w3c.dom.Document;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.AutoPlayConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.player.AutoPlaySettingsHolder;
import org.l2jmobius.gameserver.model.actor.holders.player.AutoUseSettingsHolder;
import org.l2jmobius.gameserver.model.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.type.ArmorType;
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
 * pathfinding), attacking, skills, buffs, soulshots and potions. This manager owns the <b>macro</b>
 * layer: data-driven deployment from {@code data/PhantomPopulations.xml}, gearing, supervision and
 * death-respawn.
 * @author Claude
 */
public class PhantomManager implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(PhantomManager.class.getName());

	private static final String ACCOUNT_NAME = "phantom";
	// Supervisor cadence: cleanup gone phantoms and revive/respawn dead ones. Combat runs on AutoPlay's
	// own 700ms loop, so this can be lazy.
	private static final long SUPERVISE_INTERVAL = 5000;
	// How long a dead phantom stays down before it is replaced (population) or revived (ad-hoc).
	private static final long RESPAWN_DELAY = 15000;
	// Delay before the boot deployment runs, to let the world and geodata finish loading.
	private static final long DEPLOY_DELAY = 20000;
	// AutoPlay "next target" modes (mirrors the client toggle): 1 = monsters only.
	private static final int TARGET_MODE_MONSTER = 1;
	// AutoPlay auto-action id for the basic melee attack. Without it, AutoPlay treats the character as a
	// mage caster that never auto-hits (see AutoPlayTaskManager.isMageCaster).
	private static final int AUTO_ATTACK_ACTION = 2;
	// How many soulshots to hand a freshly geared phantom (no runtime restock yet).
	private static final int SHOT_COUNT = 5000;

	// Body slots a phantom is geared in. R_HAND = sword; the rest are LIGHT/HEAVY armor pieces.
	private static final BodyPart[] GEAR_SLOTS =
	{
		BodyPart.R_HAND,
		BodyPart.CHEST,
		BodyPart.LEGS,
		BodyPart.GLOVES,
		BodyPart.FEET,
		BodyPart.HEAD
	};
	// Cheapest (i.e. most basic) tradeable item per grade for each slot, resolved once from the datapack
	// so phantoms equip a coherent, level-appropriate set (data-driven - no hard-coded item ids).
	private static final Map<BodyPart, EnumMap<CrystalType, ItemTemplate>> GEAR_BY_SLOT = new EnumMap<>(BodyPart.class);
	private static volatile boolean _gearBuilt = false;

	/** A deployment group: where/how many phantoms spawn, their level range, and whether to respawn. */
	private static class Population
	{
		String name = "unnamed";
		Location center;
		int radius = 800;
		int count;
		int minLevel = 1;
		int maxLevel = 1;
		boolean respawn = true;
		final List<Location> polygon = new ArrayList<>(); // optional area; spawn inside it instead of a circle
	}

	private static class PhantomData
	{
		final Player player;
		final Location home;
		final Population population; // null for ad-hoc (admin) spawns
		long deadSince; // 0 while alive

		PhantomData(Player player, Location home, Population population)
		{
			this.player = player;
			this.home = home;
			this.population = population;
		}
	}

	private final ConcurrentHashMap<Integer, PhantomData> _phantoms = new ConcurrentHashMap<>();
	private final List<Population> _populations = new ArrayList<>();
	private boolean _supervising = false;

	protected PhantomManager()
	{
		load();
	}

	@Override
	public void load()
	{
		_populations.clear();
		parseDatapackFile("data/PhantomPopulations.xml");
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _populations.size() + " phantom population(s).");
		if (!_populations.isEmpty())
		{
			ThreadPool.schedule(this::deploy, DEPLOY_DELAY);
			startSupervising();
		}
	}

	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode -> forEach(listNode, "population", populationNode ->
		{
			final StatSet set = new StatSet(parseAttributes(populationNode));
			final Population population = new Population();
			population.name = set.getString("name", "unnamed");
			population.center = new Location(set.getInt("x"), set.getInt("y"), set.getInt("z"));
			population.radius = set.getInt("radius", 800);
			population.count = set.getInt("count", 0);
			population.minLevel = set.getInt("minLevel", 1);
			population.maxLevel = set.getInt("maxLevel", population.minLevel);
			population.respawn = set.getBoolean("respawn", true);
			forEach(populationNode, "point", pointNode ->
			{
				final StatSet p = new StatSet(parseAttributes(pointNode));
				population.polygon.add(new Location(p.getInt("x"), p.getInt("y"), p.getInt("z", population.center.getZ())));
			});
			_populations.add(population);
		}));
	}

	/**
	 * Deploys every configured population (called once, a short while after boot). Each phantom is
	 * scattered within its group's circle/polygon and Z-snapped to the ground via geodata, so they spread
	 * out instead of stacking on one tile.
	 */
	private void deploy()
	{
		if (!AutoPlayConfig.ENABLE_AUTO_PLAY)
		{
			LOGGER.warning(getClass().getSimpleName() + ": EnableAutoPlay is false in config/Custom/AutoPlay.ini - deployed phantoms will not hunt.");
		}
		int deployed = 0;
		for (Population population : _populations)
		{
			int groupDeployed = 0;
			for (int i = 0; i < population.count; i++)
			{
				if (deployOne(population))
				{
					groupDeployed++;
				}
			}
			deployed += groupDeployed;
			if (groupDeployed < population.count)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Population '" + population.name + "' deployed " + groupDeployed + "/" + population.count + " (bad anchor/geodata?).");
			}
		}
		LOGGER.info("===== " + deployed + " PHANTOMS DEPLOYED =====");
	}

	/** Spawns one phantom into a population: rolls a scattered location and a level in the group's range. */
	private boolean deployOne(Population population)
	{
		final Location location = rollLocation(population);
		if (location == null)
		{
			return false;
		}
		// Rnd.get(origin, bound) is inclusive on both ends.
		final int level = Rnd.get(population.minLevel, population.maxLevel);
		return createAndSpawn(location, level, population) != null;
	}

	/** Picks a geodata-valid, ground-snapped spawn point inside a population's circle or polygon. */
	private Location rollLocation(Population population)
	{
		final int x;
		final int y;
		if (population.polygon.size() >= 3)
		{
			final Location point = randomPointInPolygon(population);
			x = point.getX();
			y = point.getY();
		}
		else
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int distance = Rnd.get(population.radius + 1);
			x = population.center.getX() + (int) (Math.cos(angle) * distance);
			y = population.center.getY() + (int) (Math.sin(angle) * distance);
		}
		final Location valid = GeoEngine.getInstance().getValidLocation(population.center, new Location(x, y, population.center.getZ()));
		final int groundZ = GeoEngine.getInstance().getHeight(valid.getX(), valid.getY(), valid.getZ());
		return new Location(valid.getX(), valid.getY(), groundZ);
	}

	private static Location randomPointInPolygon(Population population)
	{
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		for (Location v : population.polygon)
		{
			minX = Math.min(minX, v.getX());
			maxX = Math.max(maxX, v.getX());
			minY = Math.min(minY, v.getY());
			maxY = Math.max(maxY, v.getY());
		}
		for (int i = 0; i < 30; i++)
		{
			final int rx = Rnd.get(minX, maxX);
			final int ry = Rnd.get(minY, maxY);
			if (isInPolygon(rx, ry, population.polygon))
			{
				return new Location(rx, ry, population.center.getZ());
			}
		}
		return population.center;
	}

	private static boolean isInPolygon(int px, int py, List<Location> poly)
	{
		boolean inside = false;
		for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++)
		{
			final int xi = poly.get(i).getX(), yi = poly.get(i).getY();
			final int xj = poly.get(j).getX(), yj = poly.get(j).getY();
			if (((yi > py) != (yj > py)) && (px < ((double) (xj - xi) * (py - yi) / (yj - yi)) + xi))
			{
				inside = !inside;
			}
		}
		return inside;
	}

	/**
	 * Creates a brand-new clientless fighter phantom, levels/skills/gears it, spawns it, and hands it to
	 * the native auto-hunt so it seeks and fights monsters on its own.
	 * @param location where to spawn (also its home)
	 * @param level the level to bring the phantom to
	 * @param population the owning group (null for ad-hoc admin spawns)
	 * @return the spawned phantom, or {@code null} on failure
	 */
	private Player createAndSpawn(Location location, int level, Population population)
	{
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
			// nearby players receive already shows its full set. (A post-spawn equip update can be throttled
			// or coalesced by broadcastCharInfo, leaving gear invisible even though it was equipped.)
			phantom.setOnlineStatus(true, false);
			outfit(phantom, level);
			enterWorld(phantom, location);
			enableAutoHunt(phantom);

			_phantoms.put(phantom.getObjectId(), new PhantomData(phantom, location, population));
			startSupervising();
			LOGGER.info(getClass().getSimpleName() + ": Spawned phantom '" + phantom.getName() + "' (objId=" + phantom.getObjectId() + ", level " + level + ").");
			return phantom;
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to spawn phantom: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Spawns a single ad-hoc phantom (admin testing path). Population-driven deployment uses
	 * {@link #deployOne(Population)} instead.
	 * @param location where to spawn
	 * @param level the level to bring it to
	 * @return the spawned phantom, or {@code null} on failure
	 */
	public Player spawnPhantom(Location location, int level)
	{
		if (!AutoPlayConfig.ENABLE_AUTO_PLAY)
		{
			LOGGER.warning(getClass().getSimpleName() + ": EnableAutoPlay is false in config/Custom/AutoPlay.ini - phantoms will spawn but not hunt.");
		}
		return createAndSpawn(location, level, null);
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
	 * Equips a grade-appropriate set (sword + light/heavy armor) and hands over matching soulshots
	 * (auto-enabled). The weapon is what lets the base-fighter attack skills (Power Strike / Mortal Blow)
	 * actually fire; the armor keeps the phantom alive long enough to hunt.
	 * @param phantom the phantom to gear
	 * @param level its level (decides the grade tier)
	 */
	private void gear(Player phantom, int level)
	{
		buildGear();
		final CrystalType desired = gradeForLevel(level);

		// Weapon first, so we know the grade actually equipped (soulshots must match it exactly).
		final ItemTemplate sword = pickForSlot(BodyPart.R_HAND, desired);
		if (sword == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": No sword found in the datapack - phantom stays unarmed.");
		}
		else
		{
			equip(phantom, sword);
			final int shotId = soulshotIdFor(sword.getCrystalType());
			phantom.getInventory().addItem(ItemProcessType.REWARD, shotId, SHOT_COUNT, phantom, null);
			phantom.addAutoSoulShot(shotId);
		}

		// Armor pieces: best available at or below the level's grade for each slot.
		for (BodyPart slot : GEAR_SLOTS)
		{
			if (slot == BodyPart.R_HAND)
			{
				continue;
			}
			final ItemTemplate piece = pickForSlot(slot, desired);
			if (piece != null)
			{
				equip(phantom, piece);
			}
		}
		// No broadcast here: gear() runs before the phantom enters the world, so the whole set is in place
		// for the spawn CharInfo (enterWorld broadcasts afterwards).
	}

	/** Adds a single template to the phantom's inventory and equips it. */
	private void equip(Player phantom, ItemTemplate template)
	{
		final Item item = phantom.getInventory().addItem(ItemProcessType.REWARD, template.getId(), 1, phantom, null);
		if (item != null)
		{
			phantom.getInventory().equipItem(item);
		}
	}

	/** Best item for a slot at the desired grade, stepping down if a grade has no candidate. */
	private static ItemTemplate pickForSlot(BodyPart slot, CrystalType desired)
	{
		final EnumMap<CrystalType, ItemTemplate> byGrade = GEAR_BY_SLOT.get(slot);
		if (byGrade == null)
		{
			return null;
		}
		for (int ordinal = desired.ordinal(); ordinal >= 0; ordinal--)
		{
			final ItemTemplate item = byGrade.get(CrystalType.values()[ordinal]);
			if (item != null)
			{
				return item;
			}
		}
		return null;
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

	/**
	 * Resolves the cheapest (most basic) tradeable item per grade for each gear slot from the datapack,
	 * once. Weapons are limited to swords; armor to LIGHT/HEAVY pieces in the standard slots (FULL_ARMOR
	 * is skipped so it never conflicts with the separate legs piece).
	 */
	private static void buildGear()
	{
		if (_gearBuilt)
		{
			return;
		}
		synchronized (GEAR_BY_SLOT)
		{
			if (_gearBuilt)
			{
				return;
			}
			for (BodyPart slot : GEAR_SLOTS)
			{
				GEAR_BY_SLOT.put(slot, new EnumMap<>(CrystalType.class));
			}
			for (ItemTemplate item : ItemData.getInstance().getAllItems())
			{
				if ((item == null) || !item.isEquipable() || !item.isTradeable())
				{
					continue;
				}
				final int price = item.getReferencePrice();
				if (price <= 0)
				{
					continue;
				}

				final BodyPart slot;
				if ((item instanceof Weapon) && (((Weapon) item).getItemType() == WeaponType.SWORD))
				{
					slot = BodyPart.R_HAND;
				}
				else if (item instanceof Armor)
				{
					final ArmorType type = ((Armor) item).getItemType();
					if ((type != ArmorType.LIGHT) && (type != ArmorType.HEAVY))
					{
						continue; // robes / shields / sigils - phantoms are fighters
					}
					final BodyPart part = item.getBodyPart();
					if ((part != BodyPart.CHEST) && (part != BodyPart.LEGS) && (part != BodyPart.GLOVES) && (part != BodyPart.FEET) && (part != BodyPart.HEAD))
					{
						continue; // skip FULL_ARMOR (conflicts with separate legs) and non-armor slots
					}
					slot = part;
				}
				else
				{
					continue;
				}

				final EnumMap<CrystalType, ItemTemplate> byGrade = GEAR_BY_SLOT.get(slot);
				final CrystalType grade = item.getCrystalType();
				final ItemTemplate current = byGrade.get(grade);
				if ((current == null) || (price < current.getReferencePrice()))
				{
					byGrade.put(grade, item);
				}
			}
			_gearBuilt = true;
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
			despawn(data);
			removed++;
		}
		_phantoms.clear();
		return removed;
	}

	/** Stops the auto-hunt, removes the phantom from the world, and forgets it. */
	private void despawn(PhantomData data)
	{
		try
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(data.player);
			AutoUseTaskManager.getInstance().stopAutoUseTask(data.player);
			data.player.deleteMe();
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to despawn phantom " + data.player.getObjectId() + ": " + e.getMessage());
		}
		_phantoms.remove(data.player.getObjectId());
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
						// Population phantoms: despawn the corpse now and replace it with a fresh identity
						// shortly after, so the zone stays populated and gear/name rotate on each death.
						if ((data.population != null) && data.population.respawn)
						{
							final Population population = data.population;
							despawn(data);
							ThreadPool.schedule(() -> deployOne(population), RESPAWN_DELAY);
						}
					}
					else if ((now - data.deadSince) >= RESPAWN_DELAY)
					{
						// Ad-hoc (admin test) phantom: revive it in place so the test count stays stable.
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
