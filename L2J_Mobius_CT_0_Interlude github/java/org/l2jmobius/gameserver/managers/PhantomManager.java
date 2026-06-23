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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.w3c.dom.Document;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.AutoPlayConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.holders.player.AutoPlaySettingsHolder;
import org.l2jmobius.gameserver.model.actor.holders.player.AutoUseSettingsHolder;
import org.l2jmobius.gameserver.model.actor.holders.player.ClassType;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
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
import org.l2jmobius.gameserver.network.GameClient;
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
	// On-demand activation: a population's phantoms exist only while a real player is near its anchor, and
	// despawn a short while after the last one leaves. Keeps memory/DB free for empty zones and rolls a
	// fresh crowd on every visit. ACTIVATION_MARGIN is added to the group radius to decide "near"; the
	// grace delay avoids spawn/despawn thrash when a player skirts the edge; the stagger spreads the spawn
	// cost (DB insert + level + gear per phantom) over a few ticks so entering a zone does not hitch.
	private static final int ACTIVATION_MARGIN = 2000;
	private static final long DEACTIVATE_DELAY = 30000;
	private static final long SPAWN_STAGGER = 250;
	// AutoPlay "next target" modes (mirrors the client toggle): 1 = monsters only.
	private static final int TARGET_MODE_MONSTER = 1;
	// AutoPlay auto-action id for the basic melee attack. Without it, AutoPlay treats the character as a
	// mage caster that never auto-hits (see AutoPlayTaskManager.isMageCaster).
	private static final int AUTO_ATTACK_ACTION = 2;
	// How many soulshots to hand a freshly geared phantom (no runtime restock yet).
	private static final int SHOT_COUNT = 5000;
	// Healing potions for in-combat HP sustain while farming. Generous stack since phantoms fight a lot;
	// refreshed on every (re)spawn. Native auto-potion drinks one when HP falls below the percent.
	private static final int HP_POTION_ID = 1539; // Greater Healing Potion
	private static final int HP_POTION_COUNT = 20000;
	private static final int HP_POTION_PERCENT = 60;
	// Minimum spacing between phantoms at spawn, so a group does not stack on one tile.
	private static final int MIN_SEPARATION = 250;
	// First-occupation FIGHTER class ids per race (verified in FakePlayerAppearanceFactory), weighted by
	// repetition so phantoms vary in race/body type. All are melee, so the sword + soulshot path is shared.
	private static final int[] FIGHTER_CLASS_POOL =
	{
		0, 0, 0, // Human Fighter
		18, 18, // Elven Fighter
		31, 31, // Dark Fighter
		44, // Orc Fighter
		53 // Dwarven Fighter
	};
	// First-occupation MYSTIC (DD) base classes; orcs/dwarves excluded (no nuker line).
	private static final int[] MAGE_CLASS_POOL =
	{
		10, 10, // Human Mage
		25, // Elven Mystic
		38 // Dark Mystic
	};
	// How many (cheapest) candidate items to keep per slot/grade, so phantoms vary their look without
	// pulling in rare/expensive drops.
	private static final int CANDIDATES_PER_SLOT = 6;
	// Safety ceiling on total live phantom Player objects (each is far heavier than an NPC fake player).
	private static final int MAX_PHANTOMS = 200;
	// Proximity dormancy: a phantom only runs the (costly) auto-hunt while a real, client-connected player
	// is near. Hysteresis (wake closer than sleep) stops it flapping at the boundary. Ideal for solo play:
	// only the handful of phantoms around you actually compute.
	private static final int WAKE_RANGE = 3500;
	private static final int SLEEP_RANGE = 4500;
	// Resting: when safe (no mob near, not in combat) and low on HP/MP, a phantom sits to regen, then
	// stands when recovered or threatened. This is what sustains mages (MP) and wounded fighters (HP).
	private static final int REST_SIT_PERCENT = 35;
	private static final int REST_STAND_PERCENT = 85;
	private static final int REST_DANGER_RANGE = 700;
	// Share of phantoms that roll a (DD) mage instead of a fighter.
	private static final int MAGE_CHANCE = 30;
	// Mage combat: casters don't move under AutoPlay, so a faster tick positions them. They hold at
	// CAST_RANGE to nuke; when MP drops below CAST_MP_PERCENT they melee the target until MP recovers
	// (a pure caster is otherwise passive with no MP). TOLERANCE stops constant micro-repositioning.
	private static final long MAGE_TICK_INTERVAL = 1000;
	private static final int MAGE_CAST_RANGE = 650;
	private static final int MAGE_RANGE_TOLERANCE = 150;
	private static final int MAGE_CAST_MP_PERCENT = 20;
	// Idle roaming: the native AutoPlay never moves a phantom past its search radius, so once no monster is
	// in range it stands still forever. When an awake phantom has nothing to fight, the supervisor walks it
	// to a fresh spot inside its home area so the next AutoPlay scan finds new mobs - this is what keeps a
	// spread-out zone active instead of phantoms freezing wherever the local mobs ran out.
	private static final int ROAM_RADIUS = 1200;
	private static final int ROAM_MIN_DISTANCE = 400;
	private static final int ROAM_ATTEMPTS = 10;
	private static final int IDLE_TARGET_CLEAR_TICKS = 3;

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
	// The cheapest few tradeable items per grade for each slot, resolved once from the datapack, so each
	// phantom can roll a varied but level-appropriate look (data-driven - no hard-coded item ids). Two
	// loadouts: fighters (sword + light/heavy armor) and mages (magic weapon + robe).
	private static final Map<BodyPart, EnumMap<CrystalType, List<ItemTemplate>>> FIGHTER_GEAR = new EnumMap<>(BodyPart.class);
	private static final Map<BodyPart, EnumMap<CrystalType, List<ItemTemplate>>> MAGE_GEAR = new EnumMap<>(BodyPart.class);
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
		boolean active; // phantoms currently spawned (a real player is in range)
		long emptySince; // when the last observer left this area (0 while a player is near)
	}

	private static class PhantomData
	{
		final Player player;
		final Location home;
		final Population population; // null for ad-hoc (admin) spawns
		final boolean mage; // pure caster: needs the mage combat tick to position/kite it
		long deadSince; // 0 while alive
		boolean dormant; // auto-hunt paused because no real player is near
		boolean resting; // sitting to regenerate HP/MP
		int idleTargetTicks; // stuck with a target but no movement/attack/cast

		PhantomData(Player player, Location home, Population population, boolean mage)
		{
			this.player = player;
			this.home = home;
			this.population = population;
			this.mage = mage;
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
			// Populations are spawned on demand (when a real player approaches), not at boot - the supervisor
			// drives activation/deactivation, so nothing is created until someone is near.
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
	 * Activates populations a real player has approached and despawns those everyone has left (after a grace
	 * delay). Called every supervisor tick so phantoms exist only around the player(s).
	 */
	private void updatePopulations(List<Player> observers, long now)
	{
		for (Population population : _populations)
		{
			if (population.count <= 0)
			{
				continue;
			}
			if (isAnyoneNear(population, observers))
			{
				population.emptySince = 0;
				if (!population.active)
				{
					activate(population);
				}
			}
			else if (population.active)
			{
				if (population.emptySince == 0)
				{
					population.emptySince = now;
				}
				else if ((now - population.emptySince) >= DEACTIVATE_DELAY)
				{
					deactivate(population);
				}
			}
		}
	}

	/** @return {@code true} if any real player is within {@link #ACTIVATION_MARGIN} of a population's area. */
	private static boolean isAnyoneNear(Population population, List<Player> observers)
	{
		final long range = (long) population.radius + ACTIVATION_MARGIN;
		final long rangeSq = range * range;
		for (Player observer : observers)
		{
			final long dx = observer.getX() - population.center.getX();
			final long dy = observer.getY() - population.center.getY();
			if (((dx * dx) + (dy * dy)) <= rangeSq)
			{
				return true;
			}
		}
		return false;
	}

	/** Spawns a population's phantoms (staggered to spread the cost) when a player first enters its area. */
	private void activate(Population population)
	{
		population.active = true;
		population.emptySince = 0;
		if (!AutoPlayConfig.ENABLE_AUTO_PLAY)
		{
			LOGGER.warning(getClass().getSimpleName() + ": EnableAutoPlay is false in config/Custom/AutoPlay.ini - phantoms in '" + population.name + "' will not hunt.");
		}
		LOGGER.info(getClass().getSimpleName() + ": Activating population '" + population.name + "' (" + population.count + " phantoms).");
		for (int i = 0; i < population.count; i++)
		{
			ThreadPool.schedule(() ->
			{
				// A quick in-and-out could have deactivated it before this staggered spawn fires.
				if (population.active)
				{
					deployOne(population);
				}
			}, i * SPAWN_STAGGER);
		}
	}

	/** Despawns all of a population's phantoms (deleting their DB rows) once everyone has left its area. */
	private void deactivate(Population population)
	{
		population.active = false;
		population.emptySince = 0;
		int removed = 0;
		for (PhantomData data : new ArrayList<>(_phantoms.values()))
		{
			if (data.population == population)
			{
				despawn(data);
				removed++;
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": Deactivated population '" + population.name + "' (despawned " + removed + ").");
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
		Location fallback = null;
		for (int attempt = 0; attempt < 15; attempt++)
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
				// sqrt() spreads points evenly across the disc area instead of bunching them near the center.
				final double angle = Rnd.nextDouble() * 2 * Math.PI;
				final int distance = (int) (population.radius * Math.sqrt(Rnd.nextDouble()));
				x = population.center.getX() + (int) (Math.cos(angle) * distance);
				y = population.center.getY() + (int) (Math.sin(angle) * distance);
			}
			final Location valid = GeoEngine.getInstance().getValidLocation(population.center, new Location(x, y, population.center.getZ()));
			final int groundZ = GeoEngine.getInstance().getHeight(valid.getX(), valid.getY(), valid.getZ());
			final Location candidate = new Location(valid.getX(), valid.getY(), groundZ);
			fallback = candidate;
			if (isClearOfOtherPhantoms(candidate))
			{
				return candidate;
			}
		}
		return fallback; // gave up finding a clear spot; place anyway
	}

	/** @return {@code true} if no live phantom is within {@link #MIN_SEPARATION} of the spot. */
	private boolean isClearOfOtherPhantoms(Location loc)
	{
		for (PhantomData data : _phantoms.values())
		{
			final Player other = data.player;
			if ((other == null) || other.isDead())
			{
				continue;
			}
			final double dx = other.getX() - loc.getX();
			final double dy = other.getY() - loc.getY();
			if (((dx * dx) + (dy * dy)) < (MIN_SEPARATION * MIN_SEPARATION))
			{
				return false;
			}
		}
		return true;
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
		if (_phantoms.size() >= MAX_PHANTOMS)
		{
			return null; // safety ceiling reached
		}
		// Snap the spawn point to the geodata ground. The population deploy path already snaps via
		// rollLocation, but the ad-hoc/admin path keeps the caller's raw Z (e.g. the admin's feet Z) at an
		// offset (x,y) whose real ground sits higher/lower, so the phantom ends up embedded in or floating
		// over the terrain and its pathfinding raycasts from a bad Z. Snapping here makes every path safe;
		// it is idempotent for the already-snapped deploy locations.
		final int groundZ = GeoEngine.getInstance().getHeight(location.getX(), location.getY(), location.getZ());
		final Location spawnLocation = new Location(location.getX(), location.getY(), groundZ);
		try
		{
			// Random race/body type. Most phantoms are melee fighters; a share are DD mages. Fall back to
			// Human Fighter if a template is missing. Orcs/dwarves skew male, like the NPC appearance factory.
			final boolean mage = Rnd.get(100) < MAGE_CHANCE;
			final int[] pool = mage ? MAGE_CLASS_POOL : FIGHTER_CLASS_POOL;
			final int classId = pool[Rnd.get(pool.length)];
			PlayerClass playerClass = PlayerClass.getPlayerClass(classId);
			PlayerTemplate template = (playerClass == null) ? null : PlayerTemplateData.getInstance().getTemplate(playerClass);
			if (template == null)
			{
				playerClass = PlayerClass.FIGHTER;
				template = PlayerTemplateData.getInstance().getTemplate(playerClass);
			}
			if (template == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": No FIGHTER template available.");
				return null;
			}

			final boolean female = ((classId == 44) || (classId == 53)) ? (Rnd.get(100) < 30) : Rnd.nextBoolean();
			final PlayerAppearance appearance = new PlayerAppearance((byte) Rnd.get(0, 2), (byte) Rnd.get(0, 3), (byte) Rnd.get(0, 2), female);
			final Player phantom = Player.create(template, ACCOUNT_NAME, nextName(), appearance);
			if (phantom == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Player.create returned null (duplicate name / db error?).");
				return null;
			}

			// Ignore the weight penalty. Phantoms carry a large stack of healing potions (+ soulshots), which
			// easily exceeds a non-Dwarf's max load - at >=100% load the engine applies Weight Penalty (skill
			// 4270) level 4, whose runSpd multiplier is 0, so the phantom is fully immobilized. Dwarves have a
			// far higher carry capacity, which is why only they moved before this. Diet mode zeroes the penalty
			// regardless of load (set before items are added so the inventory weight never pins them).
			phantom.setDietMode(true);

			// Level, skill and gear the phantom BEFORE it enters the world, so the very first CharInfo
			// nearby players receive already shows its full set. (A post-spawn equip update can be throttled
			// or coalesced by broadcastCharInfo, leaving gear invisible even though it was equipped.)
			phantom.setOnlineStatus(true, false);
			outfit(phantom, level, mage);
			phantom.refreshOverloaded();
			enterWorld(phantom, spawnLocation);
			enableAutoHunt(phantom, mage);

			_phantoms.put(phantom.getObjectId(), new PhantomData(phantom, spawnLocation, population, mage));
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
	 * Brings a phantom to the requested level (granting the exact experience for it), advances it to the
	 * class its level warrants, learns that class's full skill tree up to its level, gears it, tops up its
	 * bars, and registers its skills with the auto-use system.
	 * @param phantom the phantom to outfit
	 * @param level the target level
	 */
	private void outfit(Player phantom, int level, boolean mage)
	{
		if (level > 1)
		{
			final long currentExp = phantom.getExp();
			final long targetExp = ExperienceData.getInstance().getExpForLevel(level);
			if (targetExp > currentExp)
			{
				phantom.addExpAndSp(targetExp - currentExp, 0);
			}
		}
		// Advance through the class transfers a real character of this level would have done (fighters down
		// a melee branch, mages down a DD/nuker branch), then learn everything that class can learn by now -
		// so a level-40 phantom is a proper 2nd-class character with a real kit. Learning is looped so
		// chained skills resolve.
		transferClass(phantom, level, mage);
		learnAllSkills(phantom);
		gear(phantom, level, mage);
		phantom.setCurrentHpMp(phantom.getMaxHp(), phantom.getMaxMp());
		phantom.setCurrentCp(phantom.getMaxCp());
		registerAutoSkills(phantom);
	}

	/**
	 * Advances a phantom from its base class to the occupation its level warrants by walking the class
	 * tree, choosing a random in-archetype branch at each transfer: 1st at 20+, 2nd at 40+, 3rd at 76+.
	 * @param phantom the phantom (created as a base fighter or mage)
	 * @param level its level
	 * @param mage {@code true} to follow the DD/nuker line, {@code false} for the melee line
	 */
	private void transferClass(Player phantom, int level, boolean mage)
	{
		final int targetTier = (level < 20) ? 0 : (level < 40) ? 1 : (level < 76) ? 2 : 3;
		if (targetTier == 0)
		{
			return; // base class is correct below level 20
		}
		PlayerClass current = phantom.getPlayerClass();
		for (int step = 0; step < targetTier; step++)
		{
			final PlayerClass next = randomChild(current, mage);
			if (next == null)
			{
				break; // no further transfer available
			}
			current = next;
		}
		if (current.getId() != phantom.getPlayerClass().getId())
		{
			phantom.setPlayerClass(current.getId());
			phantom.setBaseClass(current.getId());
		}
	}

	/**
	 * A random class transfer from the given class within the wanted archetype: melee fighters, or DD
	 * mages (mystic but not priest/summoner). Returns {@code null} if there are none.
	 */
	private static PlayerClass randomChild(PlayerClass parent, boolean mage)
	{
		final List<PlayerClass> options = new ArrayList<>();
		for (PlayerClass child : parent.getNextClasses())
		{
			final boolean wanted = mage ? (child.isOfType(ClassType.MYSTIC) && !child.isSummoner()) : !child.isMage();
			if (wanted)
			{
				options.add(child);
			}
		}
		return options.isEmpty() ? null : options.get(Rnd.get(options.size()));
	}

	/** Learns every skill the phantom's class can learn at its level, looped so chained skills resolve. */
	private void learnAllSkills(Player phantom)
	{
		int guard = 0;
		while ((phantom.giveAvailableSkills(false, true, true) > 0) && (guard++ < 10))
		{
			// keep learning until nothing new becomes available
		}
	}

	/**
	 * Equips a grade-appropriate set and hands over matching shots (auto-enabled): fighters get a sword +
	 * light/heavy armor + soulshots; mages get a magic weapon + robe + spiritshots. The weapon is what
	 * makes their attack skills / nukes usable; the armor keeps them alive long enough to hunt.
	 * @param phantom the phantom to gear
	 * @param level its level (decides the grade tier)
	 * @param mage {@code true} for a caster loadout
	 */
	private void gear(Player phantom, int level, boolean mage)
	{
		buildGear();
		final Map<BodyPart, EnumMap<CrystalType, List<ItemTemplate>>> set = mage ? MAGE_GEAR : FIGHTER_GEAR;
		final CrystalType desired = gradeForLevel(level);

		// Weapon first, so we know the grade actually equipped (shots must match it exactly).
		final ItemTemplate weapon = pickForSlot(set, BodyPart.R_HAND, desired);
		if (weapon == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": No " + (mage ? "magic weapon" : "sword") + " found in the datapack - phantom stays unarmed.");
		}
		else
		{
			equip(phantom, weapon);
			final int shotId = mage ? spiritshotIdFor(weapon.getCrystalType()) : soulshotIdFor(weapon.getCrystalType());
			phantom.getInventory().addItem(ItemProcessType.REWARD, shotId, SHOT_COUNT, phantom, null);
			phantom.addAutoSoulShot(shotId); // registers both soulshots and spiritshots for auto-use
		}

		// Healing potions: in-combat HP sustain (sitting can't help mid-fight). Native auto-potion drinks
		// one when HP drops below the threshold; the big stack lasts a long farm session.
		phantom.getInventory().addItem(ItemProcessType.REWARD, HP_POTION_ID, HP_POTION_COUNT, phantom, null);
		phantom.getAutoUseSettings().setAutoPotionItem(HP_POTION_ID);
		phantom.getAutoPlaySettings().setAutoPotionPercent(HP_POTION_PERCENT);

		// Armor pieces: a varied at-or-below-grade piece per slot. Completeness varies (chest almost
		// always, helmet/gloves often skipped) so a group is not a row of identical fully-armored clones.
		for (BodyPart slot : GEAR_SLOTS)
		{
			if (slot == BodyPart.R_HAND)
			{
				continue;
			}
			if ((slot == BodyPart.HEAD) && (Rnd.get(100) >= 55))
			{
				continue;
			}
			if ((slot == BodyPart.GLOVES) && (Rnd.get(100) >= 60))
			{
				continue;
			}
			if ((slot == BodyPart.FEET) && (Rnd.get(100) >= 85))
			{
				continue;
			}
			if ((slot == BodyPart.LEGS) && (Rnd.get(100) >= 92))
			{
				continue;
			}
			final ItemTemplate piece = pickForSlot(set, slot, desired);
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

	/** A random candidate item for a slot at the desired grade, stepping down if a grade has none. */
	private static ItemTemplate pickForSlot(Map<BodyPart, EnumMap<CrystalType, List<ItemTemplate>>> set, BodyPart slot, CrystalType desired)
	{
		final EnumMap<CrystalType, List<ItemTemplate>> byGrade = set.get(slot);
		if (byGrade == null)
		{
			return null;
		}
		for (int ordinal = desired.ordinal(); ordinal >= 0; ordinal--)
		{
			final List<ItemTemplate> list = byGrade.get(CrystalType.values()[ordinal]);
			if ((list != null) && !list.isEmpty())
			{
				return list.get(Rnd.get(list.size()));
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

	/** Spiritshot item id matching a weapon grade. */
	private static int spiritshotIdFor(CrystalType grade)
	{
		switch (grade)
		{
			case D:
			{
				return 2510;
			}
			case C:
			{
				return 2511;
			}
			case B:
			{
				return 2512;
			}
			case A:
			{
				return 2513;
			}
			case S:
			{
				return 2514;
			}
			default:
			{
				return 2509; // No Grade
			}
		}
	}

	/**
	 * Resolves the cheapest few tradeable items per grade for each gear slot from the datapack, once, into
	 * two loadouts: fighters (sword + LIGHT/HEAVY armor) and mages (magic weapon + MAGIC robe). FULL_ARMOR
	 * is skipped so it never conflicts with the separate legs piece.
	 */
	private static void buildGear()
	{
		if (_gearBuilt)
		{
			return;
		}
		synchronized (FIGHTER_GEAR)
		{
			if (_gearBuilt)
			{
				return;
			}
			initGearMap(FIGHTER_GEAR);
			initGearMap(MAGE_GEAR);
			for (ItemTemplate item : ItemData.getInstance().getAllItems())
			{
				if ((item == null) || !item.isEquipable() || !item.isTradeable() || (item.getReferencePrice() <= 0))
				{
					continue;
				}

				if (item instanceof Weapon)
				{
					if (item.isMagicWeapon())
					{
						MAGE_GEAR.get(BodyPart.R_HAND).get(item.getCrystalType()).add(item);
					}
					else if (((Weapon) item).getItemType() == WeaponType.SWORD)
					{
						FIGHTER_GEAR.get(BodyPart.R_HAND).get(item.getCrystalType()).add(item);
					}
				}
				else if (item instanceof Armor)
				{
					final BodyPart part = item.getBodyPart();
					if ((part != BodyPart.CHEST) && (part != BodyPart.LEGS) && (part != BodyPart.GLOVES) && (part != BodyPart.FEET) && (part != BodyPart.HEAD))
					{
						continue; // skip FULL_ARMOR (conflicts with separate legs) and non-armor slots
					}
					final ArmorType type = ((Armor) item).getItemType();
					if ((type == ArmorType.LIGHT) || (type == ArmorType.HEAVY))
					{
						FIGHTER_GEAR.get(part).get(item.getCrystalType()).add(item);
					}
					else if (type == ArmorType.MAGIC)
					{
						MAGE_GEAR.get(part).get(item.getCrystalType()).add(item);
					}
				}
			}
			trimGear(FIGHTER_GEAR);
			trimGear(MAGE_GEAR);
			_gearBuilt = true;
		}
	}

	private static void initGearMap(Map<BodyPart, EnumMap<CrystalType, List<ItemTemplate>>> set)
	{
		for (BodyPart slot : GEAR_SLOTS)
		{
			final EnumMap<CrystalType, List<ItemTemplate>> byGrade = new EnumMap<>(CrystalType.class);
			for (CrystalType grade : CrystalType.values())
			{
				byGrade.put(grade, new ArrayList<>());
			}
			set.put(slot, byGrade);
		}
	}

	/** Keeps only the cheapest few per slot/grade, so phantoms roll basic gear, not rare drops. */
	private static void trimGear(Map<BodyPart, EnumMap<CrystalType, List<ItemTemplate>>> set)
	{
		for (EnumMap<CrystalType, List<ItemTemplate>> byGrade : set.values())
		{
			for (List<ItemTemplate> list : byGrade.values())
			{
				list.sort(Comparator.comparingInt(ItemTemplate::getReferencePrice));
				if (list.size() > CANDIDATES_PER_SLOT)
				{
					list.subList(CANDIDATES_PER_SLOT, list.size()).clear();
				}
			}
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
	private void enableAutoHunt(Player phantom, boolean mage)
	{
		final AutoPlaySettingsHolder settings = phantom.getAutoPlaySettings();
		settings.setNextTargetMode(TARGET_MODE_MONSTER);
		// Long-range search so they actively seek mobs across the zone (short range left them standing idle
		// unless a mob aggroed them - AutoPlay doesn't roam beyond its search radius). Clumping is instead
		// held down by spaced spawns + respectful hunting (below).
		settings.setShortRange(false);
		settings.setRespectfulHunting(true); // skip mobs already being fought, so phantoms don't converge on one
		settings.setPickup(true);

		// Fighters auto-attack (action id 2). Mages are pure casters: no auto-attack, so AutoPlay only picks
		// the target and AutoUse casts their nukes - the mage combat tick keeps them at casting range and
		// kites them out when they run low on MP (it does the moving AutoPlay won't do for a caster).
		if (!mage)
		{
			phantom.getAutoUseSettings().getAutoActions().add(AUTO_ATTACK_ACTION);
		}

		AutoPlayTaskManager.getInstance().startAutoPlay(phantom);
		AutoUseTaskManager.getInstance().startAutoUseTask(phantom);
	}

	/** Despawns and forgets every phantom (also deletes their DB rows). */
	public int clear()
	{
		int removed = 0;
		for (PhantomData data : new ArrayList<>(_phantoms.values()))
		{
			despawn(data);
			removed++;
		}
		_phantoms.clear();
		for (Population population : _populations)
		{
			population.active = false;
			population.emptySince = 0;
		}
		return removed;
	}

	/**
	 * Stops the auto-hunt, removes the phantom from the world, forgets it, and deletes its character row.
	 * The row must go because phantoms are now spawned/despawned on demand - without it every zone visit
	 * would leak an orphan {@code phantom}-account character.
	 */
	private void despawn(PhantomData data)
	{
		final int objectId = data.player.getObjectId();
		try
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(data.player);
			AutoUseTaskManager.getInstance().stopAutoUseTask(data.player);
			data.player.deleteMe();
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to despawn phantom " + objectId + ": " + e.getMessage());
		}
		_phantoms.remove(objectId);
		try
		{
			GameClient.deleteCharByObjId(objectId);
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to delete phantom row " + objectId + ": " + e.getMessage());
		}
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
		ThreadPool.scheduleAtFixedRate(this::mageCombat, MAGE_TICK_INTERVAL, MAGE_TICK_INTERVAL);
		LOGGER.info(getClass().getSimpleName() + ": Phantom supervisor started.");
	}

	/**
	 * Keeps awake mage phantoms at casting range of their target and kites them out when low on MP, since
	 * the native AutoPlay never moves a pure caster. AutoUse does the actual nuking once they're in range.
	 */
	private void mageCombat()
	{
		for (PhantomData data : _phantoms.values())
		{
			if (!data.mage || data.dormant || data.resting)
			{
				continue;
			}
			final Player mage = data.player;
			try
			{
				if (mage.isDead() || mage.isCastingNow() || mage.isMovementDisabled())
				{
					continue; // don't interrupt a cast or fight a stun
				}
				if ((mage.getCurrentMpPercent() < MAGE_CAST_MP_PERCENT) && !mage.isInCombat() && !isMonsterNear(mage))
				{
					mage.setTarget(null);
					startRest(mage);
					data.resting = true;
					continue;
				}
				final WorldObject target = mage.getTarget();
				if (!(target instanceof Monster) || ((Monster) target).isDead())
				{
					continue; // AutoPlay will pick a target; nothing to position around yet
				}
				positionMage(mage, (Monster) target);
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Mage combat error for " + mage.getName() + ": " + e.getMessage());
			}
		}
	}

	/** Holds a mage at casting range to nuke; when out of MP it melees the target instead of standing idle. */
	private void positionMage(Player mage, Monster target)
	{
		// Out of MP to nuke: a pure caster has no auto-attack action, so AutoPlay leaves it passive and it
		// would just stand in combat range drinking potions. Melee the target with its weapon until MP comes
		// back, then casting resumes (the canCast branch below repositions and AutoUse nukes again).
		if (mage.getCurrentMpPercent() < MAGE_CAST_MP_PERCENT)
		{
			if (mage.getAI().getIntention() != Intention.ATTACK)
			{
				mage.setRunning();
				mage.getAI().setIntention(Intention.ATTACK, target);
			}
			return;
		}
		double dx = mage.getX() - target.getX();
		double dy = mage.getY() - target.getY();
		double distance = Math.hypot(dx, dy);
		if (Math.abs(distance - MAGE_CAST_RANGE) <= MAGE_RANGE_TOLERANCE)
		{
			return; // already in position - stand still so AutoUse can cast
		}
		if (distance < 1)
		{
			distance = 1;
		}
		// A point MAGE_CAST_RANGE units from the target, on the line toward the mage: walks in when too far,
		// backs off when too close.
		final int standX = target.getX() + (int) ((dx / distance) * MAGE_CAST_RANGE);
		final int standY = target.getY() + (int) ((dy / distance) * MAGE_CAST_RANGE);
		final Location destination = GeoEngine.getInstance().getValidLocation(mage, new Location(standX, standY, mage.getZ()));
		mage.setRunning();
		mage.getAI().setIntention(Intention.MOVE_TO, destination);
	}

	private void supervise()
	{
		final long now = System.currentTimeMillis();
		final List<Player> observers = onlineObservers();
		// Spawn populations a player has approached; despawn ones everyone has left (after a grace delay).
		updatePopulations(observers, now);
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
							// Only replace it if the zone is still active when the delay elapses (a player could
							// have left and the population deactivated in the meantime).
							ThreadPool.schedule(() ->
							{
								if (population.active)
								{
									deployOne(population);
								}
							}, RESPAWN_DELAY);
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

				// Proximity dormancy: only compute the auto-hunt while a real player is near (hysteresis).
				final double nearest = nearestObserverDistance(phantom, observers);
				if (data.dormant)
				{
					if (nearest <= WAKE_RANGE)
					{
						wake(phantom);
						data.dormant = false;
						data.resting = false;
					}
					continue; // stay parked while no one is near
				}
				if (nearest > SLEEP_RANGE)
				{
					sleep(phantom);
					data.dormant = true;
					data.resting = false;
					continue;
				}

				// Resting: when safe and low on HP/MP, sit to regen; stand when recovered or threatened.
				final boolean danger = phantom.isInCombat() || hasLiveMonsterTarget(phantom) || isMonsterNear(phantom);
				final boolean active = phantom.isMoving() || phantom.isCastingNow() || phantom.isAttackingNow();
				if (data.resting)
				{
					if (danger || ((phantom.getCurrentHpPercent() >= REST_STAND_PERCENT) && (phantom.getCurrentMpPercent() >= REST_STAND_PERCENT)))
					{
						endRest(phantom);
						data.resting = false;
					}
				}
				else if (!danger && ((phantom.getCurrentHpPercent() < REST_SIT_PERCENT) || (phantom.getCurrentMpPercent() < REST_SIT_PERCENT)))
				{
					startRest(phantom);
					data.resting = true;
				}
				else if (active)
				{
					data.idleTargetTicks = 0;
				}
				else if (hasLiveMonsterTarget(phantom))
				{
					data.idleTargetTicks++;
					if (data.idleTargetTicks >= IDLE_TARGET_CLEAR_TICKS)
					{
						phantom.setTarget(null);
						data.idleTargetTicks = 0;
						if (data.mage && (phantom.getCurrentMpPercent() < MAGE_CAST_MP_PERCENT) && !phantom.isInCombat() && !isMonsterNear(phantom))
						{
							startRest(phantom);
							data.resting = true;
						}
						else
						{
							roam(phantom, data);
						}
					}
				}
				// Roam when idle: nothing to fight, not already walking and not resting. Relocating it lets the
				// next AutoPlay scan pick up monsters it could not previously reach, so it stops standing still.
				else if (!data.resting && !danger && (phantom.getTarget() == null))
				{
					data.idleTargetTicks = 0;
					roam(phantom, data);
				}
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Supervise error for " + phantom.getName() + ": " + e.getMessage());
			}
		}
	}

	/** @return {@code true} if the phantom currently has a living monster target (engaged - don't rest). */
	private static boolean hasLiveMonsterTarget(Player phantom)
	{
		final WorldObject target = phantom.getTarget();
		return (target instanceof Monster) && !((Monster) target).isDead();
	}

	/**
	 * Walks an idle phantom to a random reachable spot inside its home area, so the next native AutoPlay
	 * scan can find monsters it could not previously reach. Without this a phantom that runs out of nearby
	 * mobs just stands still, because AutoPlay never roams beyond its own search radius.
	 */
	private void roam(Player phantom, PhantomData data)
	{
		final int radius = (data.population != null) ? Math.max(ROAM_MIN_DISTANCE + 100, data.population.radius) : ROAM_RADIUS;
		Location fallback = null;
		for (int attempt = 0; attempt < ROAM_ATTEMPTS; attempt++)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int distance = Rnd.get(ROAM_MIN_DISTANCE, radius);
			final int x = data.home.getX() + (int) (Math.cos(angle) * distance);
			final int y = data.home.getY() + (int) (Math.sin(angle) * distance);
			final Location destination = GeoEngine.getInstance().getValidLocation(phantom, new Location(x, y, data.home.getZ()));
			if (fallback == null)
			{
				fallback = destination;
			}
			if (GeoEngine.getInstance().canMoveToTarget(phantom.getX(), phantom.getY(), phantom.getZ(), destination.getX(), destination.getY(), destination.getZ(), phantom.getInstanceId()))
			{
				phantom.setRunning();
				phantom.getAI().setIntention(Intention.MOVE_TO, destination);
				return;
			}
		}
		if (fallback != null)
		{
			phantom.setRunning();
			phantom.getAI().setIntention(Intention.MOVE_TO, fallback);
		}
	}

	/** @return {@code true} if a live monster is close enough that the phantom should not sit to rest. */
	private static boolean isMonsterNear(Player phantom)
	{
		for (Monster monster : World.getInstance().getVisibleObjectsInRange(phantom, Monster.class, REST_DANGER_RANGE))
		{
			if (!monster.isDead())
			{
				return true;
			}
		}
		return false;
	}

	/** Sits the phantom down to regenerate, pausing the auto-hunt while it rests. */
	private void startRest(Player phantom)
	{
		if (phantom.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(phantom);
			AutoUseTaskManager.getInstance().stopAutoUseTask(phantom);
		}
		if (!phantom.isSitting())
		{
			phantom.sitDown();
		}
	}

	/** Stands the phantom back up and resumes the auto-hunt after resting. */
	private void endRest(Player phantom)
	{
		if (phantom.isSitting())
		{
			phantom.standUp();
		}
		wake(phantom);
	}

	/** Genuine, client-connected players (excludes phantoms and offline shops, which have no client). */
	private static List<Player> onlineObservers()
	{
		final List<Player> observers = new ArrayList<>();
		for (Player player : World.getInstance().getPlayers())
		{
			if (!player.isInOfflineMode() && !player.isDead())
			{
				observers.add(player);
			}
		}
		return observers;
	}

	/** Distance to the closest observer, or {@code MAX_VALUE} if there are none. */
	private static double nearestObserverDistance(Player phantom, List<Player> observers)
	{
		double best = Double.MAX_VALUE;
		for (Player observer : observers)
		{
			final double distance = phantom.calculateDistance2D(observer);
			if (distance < best)
			{
				best = distance;
			}
		}
		return best;
	}

	/** Resumes the native auto-hunt for a phantom a real player has approached. */
	private void wake(Player phantom)
	{
		if (phantom.isSitting())
		{
			phantom.standUp();
		}
		phantom.setRunning();
		if (!phantom.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().startAutoPlay(phantom);
			AutoUseTaskManager.getInstance().startAutoUseTask(phantom);
		}
	}

	/** Pauses the auto-hunt and freezes a phantom that no real player is near (saves the per-tick cost). */
	private void sleep(Player phantom)
	{
		if (phantom.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(phantom);
			AutoUseTaskManager.getInstance().stopAutoUseTask(phantom);
		}
		phantom.getAI().setIntention(Intention.IDLE); // stop any in-progress movement so it parks
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

	/** A unique, pronounceable character name (reuses the NPC name generator), checked against the DB. */
	private String nextName()
	{
		for (int attempt = 0; attempt < 50; attempt++)
		{
			final String name = FakePlayerAppearanceFactory.generateName();
			if (!CharInfoTable.getInstance().doesCharNameExist(name))
			{
				return name;
			}
		}
		// Extremely unlikely fallback.
		String name;
		do
		{
			name = "Phantom" + Rnd.get(100000);
		}
		while (CharInfoTable.getInstance().doesCharNameExist(name));
		return name;
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
