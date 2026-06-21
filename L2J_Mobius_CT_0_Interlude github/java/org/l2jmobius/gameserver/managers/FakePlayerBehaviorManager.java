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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.w3c.dom.Document;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.xml.FakePlayerData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerAppearance;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.spawns.Spawn;

/**
 * Gives fake players a sense of purpose: instead of standing still (or following a single hand-drawn
 * route), each fake player is assigned a lightweight behavior profile and driven by a cheap finite
 * state machine. The state machine only issues high-level movement goals through the normal AI
 * intention system, so all pathfinding, geodata and combat are handled by the existing engine.
 * <p>
 * Design notes:
 * <ul>
 * <li>No LLM / heavy logic is used in the tick loop - this scales to hundreds of bots.</li>
 * <li>Bots already controlled by {@link WalkingManager} routes are left untouched.</li>
 * <li>Combat is delegated to {@code AttackableAI}; the FSM simply backs off while fighting.</li>
 * </ul>
 * @author Claude
 */
public class FakePlayerBehaviorManager implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerBehaviorManager.class.getName());

	// How often we look for newly spawned / despawned fake players.
	private static final long DISCOVERY_INTERVAL = 30000;
	// How often each bot's state machine is evaluated.
	private static final long BEHAVIOR_INTERVAL = 3000;
	// After a fight we wait this long before resuming wandering.
	private static final long COMBAT_BACKOFF = 6000;
	// A killed field bot is replaced (with a fresh identity) after roughly this long.
	private static final long RESPAWN_DELAY = 45000;

	// Procedural deployment: where auto-spawned bots are scattered and how wide.
	// Default center is the Giran town square area; tune to taste.
	private static final Location DEPLOY_CENTER = new Location(83400, 147600, -3400);
	private static final int DEPLOY_RADIUS = 1500;
	// Delay before deploying, to let the world and geodata finish loading.
	private static final long DEPLOY_DELAY = 15000;
	// Level range rolled for generated bots (used for flavor now; drives zone choice in Phase 2b).
	private static final int DEPLOY_MIN_LEVEL = 1;
	private static final int DEPLOY_MAX_LEVEL = 40;

	private enum ProfileType
	{
		/** Random short hops around a home anchor (town life, idle farming spot). */
		WANDER,
		/** Cycles through an ordered list of points (guards, travellers). */
		PATROL,
		/** Moves to a RANDOM point from the list each time (with long idles): "purposeful" town movement
		 * between points of interest like the gatekeeper, warehouse and shops. */
		VISIT,
		/** Hunts: heads toward the nearest monster in the zone; if none is near, roams elsewhere within
		 * the zone to find some. Spreads bots out across a hunting ground instead of clustering. */
		FARM
	}

	private enum Phase
	{
		IDLE,
		MOVING
	}

	private static class Profile
	{
		String name;
		ProfileType type = ProfileType.WANDER;
		int radius = 600; // WANDER: how far from the anchor a hop may land
		boolean run = false;
		int pauseMin = 8; // seconds to idle between hops
		int pauseMax = 25;
		final List<Location> points = new ArrayList<>(); // PATROL waypoints
	}

	private static class BotState
	{
		final Profile profile;
		final Location home;
		final int radius; // wander radius around the home anchor (from the population, else the profile)
		final Population population; // owning group (null for discovered bots); used for respawn
		Phase phase = Phase.IDLE;
		long nextActionTime;
		int patrolIndex;

		BotState(Profile profile, Location home, int radius, Population population)
		{
			this.profile = profile;
			this.home = home;
			this.radius = radius;
			this.population = population;
		}
	}

	private static class Population
	{
		String name;
		Location center;
		int radius = 1000;
		int count;
		int minLevel = 1;
		int maxLevel = 60;
		String profileName;
		Race race; // optional dominant race for this group (e.g. a Dwarven village)
		boolean respawn; // field bots die; respawn a fresh replacement to keep the zone populated
		String storeType; // null, or SELL / BUY / PACKAGE / CRAFT -> seated private-store vendors
		final List<Location> polygon = new ArrayList<>(); // optional area; bots spawn inside it instead of a circle
	}

	private final Map<String, Profile> _profiles = new HashMap<>();
	private final Map<String, String> _assignByName = new HashMap<>(); // lowercase fpc name -> profile
	private final Map<Integer, String> _assignByNpcId = new HashMap<>(); // npc id -> profile
	private final List<Population> _populations = new ArrayList<>(); // procedural deployment groups
	private String _defaultProfile = null;

	private final Map<Integer, BotState> _bots = new ConcurrentHashMap<>(); // objectId -> state
	private boolean _started = false;
	private int _baseId; // base template id used for all generated bots (resolved at deploy)

	protected FakePlayerBehaviorManager()
	{
		if (FakePlayersConfig.FAKE_PLAYERS_ENABLED && FakePlayersConfig.FAKE_PLAYER_BEHAVIOR)
		{
			load();
		}
	}

	@Override
	public void load()
	{
		_profiles.clear();
		_assignByName.clear();
		_assignByNpcId.clear();
		_populations.clear();
		parseDatapackFile("data/FakePlayerBehavior.xml");
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _profiles.size() + " behavior profiles and " + _populations.size() + " populations.");
		start();
	}

	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode ->
		{
			forEach(listNode, "profile", profileNode ->
			{
				final StatSet set = new StatSet(parseAttributes(profileNode));
				final Profile profile = new Profile();
				profile.name = set.getString("name");
				profile.type = Enum.valueOf(ProfileType.class, set.getString("type", "WANDER").toUpperCase());
				profile.radius = set.getInt("radius", 600);
				profile.run = set.getBoolean("run", false);
				profile.pauseMin = set.getInt("pauseMin", 8);
				profile.pauseMax = set.getInt("pauseMax", 25);
				forEach(profileNode, "point", pointNode ->
				{
					final StatSet p = new StatSet(parseAttributes(pointNode));
					profile.points.add(new Location(p.getInt("x"), p.getInt("y"), p.getInt("z")));
				});
				_profiles.put(profile.name, profile);
			});

			forEach(listNode, "assign", assignNode ->
			{
				final StatSet set = new StatSet(parseAttributes(assignNode));
				final String profileName = set.getString("profile");
				if (set.contains("npcId"))
				{
					_assignByNpcId.put(set.getInt("npcId"), profileName);
				}
				if (set.contains("name"))
				{
					_assignByName.put(set.getString("name").toLowerCase(), profileName);
				}
			});

			forEach(listNode, "default", defaultNode ->
			{
				_defaultProfile = new StatSet(parseAttributes(defaultNode)).getString("profile");
			});

			forEach(listNode, "population", populationNode ->
			{
				final StatSet set = new StatSet(parseAttributes(populationNode));
				final Population population = new Population();
				population.name = set.getString("name", "unnamed");
				population.center = new Location(set.getInt("x"), set.getInt("y"), set.getInt("z"));
				population.radius = set.getInt("radius", 1000);
				population.count = set.getInt("count", 0);
				population.minLevel = set.getInt("minLevel", 1);
				population.maxLevel = set.getInt("maxLevel", 60);
				population.respawn = set.getBoolean("respawn", false);
				population.storeType = set.contains("store") ? set.getString("store") : null;
				population.profileName = set.getString("profile", _defaultProfile);
				forEach(populationNode, "point", pointNode ->
				{
					final StatSet p = new StatSet(parseAttributes(pointNode));
					population.polygon.add(new Location(p.getInt("x"), p.getInt("y"), p.getInt("z", population.center.getZ())));
				});
				if (set.contains("race"))
				{
					try
					{
						population.race = Race.valueOf(set.getString("race").toUpperCase());
					}
					catch (Exception e)
					{
						LOGGER.warning(getClass().getSimpleName() + ": Unknown race '" + set.getString("race") + "' in population '" + population.name + "'.");
					}
				}
				_populations.add(population);
			});
		});
	}

	private void start()
	{
		if (_started || _profiles.isEmpty())
		{
			return;
		}
		_started = true;
		if (!_populations.isEmpty() || (FakePlayersConfig.FAKE_PLAYER_DEPLOY_COUNT > 0))
		{
			ThreadPool.schedule(this::deploy, DEPLOY_DELAY);
		}
		ThreadPool.scheduleAtFixedRate(this::discover, DISCOVERY_INTERVAL, DISCOVERY_INTERVAL);
		ThreadPool.scheduleAtFixedRate(this::tick, BEHAVIOR_INTERVAL, BEHAVIOR_INTERVAL);
		LOGGER.info(getClass().getSimpleName() + ": Fake player behavior enabled.");
	}

	/**
	 * Procedurally deploys fake players. Each {@code <population>} defines a group (center, radius,
	 * count, level range, behavior profile); bots are scattered within the group, anchored to the
	 * group center so clusters stay tight, and registered with the behavior FSM. If no populations are
	 * defined it falls back to a single group of {@code FakePlayerDeployCount} bots at {@link #DEPLOY_CENTER}.
	 */
	private void deploy()
	{
		// All generated bots share one base template; their look is overridden per-instance.
		_baseId = FakePlayersConfig.FAKE_PLAYER_BASE_NPC_ID;
		if (_baseId <= 0)
		{
			final List<Integer> templateIds = new ArrayList<>(FakePlayerData.getInstance().getFakePlayerIds());
			if (templateIds.isEmpty())
			{
				LOGGER.warning(getClass().getSimpleName() + ": No fake player templates found - cannot deploy bots.");
				return;
			}
			_baseId = templateIds.get(0);
		}

		int deployed = 0;
		if (_populations.isEmpty())
		{
			final Population fallback = new Population();
			fallback.name = "default";
			fallback.center = DEPLOY_CENTER;
			fallback.radius = DEPLOY_RADIUS;
			fallback.count = FakePlayersConfig.FAKE_PLAYER_DEPLOY_COUNT;
			fallback.minLevel = DEPLOY_MIN_LEVEL;
			fallback.maxLevel = DEPLOY_MAX_LEVEL;
			fallback.profileName = _defaultProfile;
			for (int i = 0; i < fallback.count; i++)
			{
				if (deployOne(fallback))
				{
					deployed++;
				}
			}
		}
		else
		{
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
		}

		LOGGER.info("===== " + deployed + " BOTS DEPLOYED =====");
	}

	/**
	 * Spawns a single bot in the given population: scatters it within the group, gives it a generated
	 * identity, and registers it with the behavior FSM.
	 * @param population the group to spawn into
	 * @return {@code true} if a bot was spawned
	 */
	private boolean deployOne(Population population)
	{
		final Profile profile = population.profileName == null ? null : _profiles.get(population.profileName);
		try
		{
			final int x;
			final int y;
			if (population.polygon.size() >= 3)
			{
				// Area population: spawn at a random point inside the drawn shape.
				final Location pt = randomPointInPolygon(population);
				x = pt.getX();
				y = pt.getY();
			}
			else
			{
				final double angle = Rnd.nextDouble() * 2 * Math.PI;
				final int distance = Rnd.get(0, population.radius);
				x = population.center.getX() + (int) (Math.cos(angle) * distance);
				y = population.center.getY() + (int) (Math.sin(angle) * distance);
			}
			final Location loc = GeoEngine.getInstance().getValidLocation(population.center, new Location(x, y, population.center.getZ()));
			// Snap to the ground height. With geodata loaded this corrects open-field Z automatically;
			// without geodata it is a no-op (so field bots need geodata to place reliably outdoors).
			final int groundZ = GeoEngine.getInstance().getHeight(loc.getX(), loc.getY(), loc.getZ());

			final Spawn spawn = new Spawn(_baseId);
			spawn.setXYZ(loc.getX(), loc.getY(), groundZ);
			spawn.setHeading(Rnd.get(65536));
			spawn.setAmount(1);
			// We own respawn ourselves (with a fresh identity) so the engine's template respawn is off.
			spawn.stopRespawn();
			final Npc npc = spawn.doSpawn(false);
			if (npc != null)
			{
				// Give the bot its own procedurally generated identity and broadcast the new look.
				final FakePlayerAppearance look = FakePlayerAppearanceFactory.generate(population.minLevel, population.maxLevel, population.race);
				if (population.storeType != null)
				{
					final String kind = population.storeType.toUpperCase();
					final int storeId = kind.equals("BUY") ? PrivateStoreType.BUY.getId() : kind.equals("PACKAGE") ? PrivateStoreType.PACKAGE_SELL.getId() : (kind.equals("CRAFT") || kind.equals("MANUFACTURE")) ? PrivateStoreType.MANUFACTURE.getId() : PrivateStoreType.SELL.getId();
					look.setStore(storeId, FakePlayerAppearanceFactory.storeTitle(kind));
				}
				npc.setFakePlayerAppearance(look);
				npc.broadcastInfo();

				if (profile != null)
				{
					// Anchor to the population center (not the scatter point) so clusters stay tight.
					_bots.put(npc.getObjectId(), new BotState(profile, population.center, population.radius, population));
				}
				return true;
			}
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to deploy bot in '" + population.name + "' (baseId=" + _baseId + "): " + e.getMessage());
		}
		return false;
	}

	/**
	 * Scans the world for fake players, registering newly spawned ones and dropping despawned ones.
	 * Runs infrequently; the per-bot tick works off the registered map.
	 */
	private void discover()
	{
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (!object.isNpc())
			{
				continue;
			}
			final Npc npc = object.asNpc();
			if (!npc.isFakePlayer() || _bots.containsKey(npc.getObjectId()))
			{
				continue;
			}
			// Leave route-driven fake players to the WalkingManager.
			if (WalkingManager.getInstance().isTargeted(npc))
			{
				continue;
			}
			final Profile profile = resolveProfile(npc);
			if (profile != null)
			{
				_bots.put(npc.getObjectId(), new BotState(profile, new Location(npc.getX(), npc.getY(), npc.getZ()), profile.radius, null));
			}
		}
	}

	/**
	 * Picks a random point inside a population's polygon (rejection sampling), falling back to the
	 * centroid if sampling fails.
	 * @param pop the area population
	 * @return a location inside the polygon
	 */
	private Location randomPointInPolygon(Population pop)
	{
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		for (Location v : pop.polygon)
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
			if (isInPolygon(rx, ry, pop.polygon))
			{
				return new Location(rx, ry, pop.center.getZ());
			}
		}
		return pop.center;
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
	 * Finds the closest living monster within range that is still inside the bot's zone.
	 * @param npc the hunting bot
	 * @param state its behavior state (for the zone anchor/radius)
	 * @param range how far to look
	 * @return the nearest in-zone monster, or {@code null} if none
	 */
	private Monster nearestMonster(Npc npc, BotState state, int range)
	{
		final List<Monster> found = new ArrayList<>();
		World.getInstance().forEachVisibleObjectInRange(npc, Monster.class, range, monster ->
		{
			if (!monster.isDead() && monster.isInsideRadius2D(state.home, state.radius + 400))
			{
				found.add(monster);
			}
		});
		Monster nearest = null;
		double best = Double.MAX_VALUE;
		for (Monster monster : found)
		{
			final double distance = npc.calculateDistance2D(monster);
			if (distance < best)
			{
				best = distance;
				nearest = monster;
			}
		}
		return nearest;
	}

	private Profile resolveProfile(Npc npc)
	{
		String name = _assignByName.get(npc.getName().toLowerCase());
		if (name == null)
		{
			name = _assignByNpcId.get(npc.getId());
		}
		if (name == null)
		{
			name = _defaultProfile;
		}
		return name == null ? null : _profiles.get(name);
	}

	private void tick()
	{
		final long now = System.currentTimeMillis();
		for (Map.Entry<Integer, BotState> entry : _bots.entrySet())
		{
			final WorldObject object = World.getInstance().findObject(entry.getKey());
			if ((object == null) || !object.isNpc())
			{
				_bots.remove(entry.getKey());
				continue;
			}

			final Npc npc = object.asNpc();
			if (npc.isDead())
			{
				final BotState dead = _bots.remove(entry.getKey());
				// Replace fallen field bots with a fresh hunter so the zone stays populated.
				if ((dead != null) && (dead.population != null) && dead.population.respawn)
				{
					ThreadPool.schedule(() -> deployOne(dead.population), RESPAWN_DELAY);
				}
				continue;
			}

			try
			{
				process(npc, entry.getValue(), now);
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Behavior error for " + npc.getName() + ": " + e.getMessage());
			}
		}
	}

	private void process(Npc npc, BotState state, long now)
	{
		// Private-store vendors are seated and never move.
		final FakePlayerAppearance look = npc.getFakePlayerAppearance();
		if ((look != null) && (look.getPrivateStoreType() != 0))
		{
			return;
		}

		// Let the combat AI run uninterrupted; resume wandering shortly after the fight.
		if (npc.isInCombat() || npc.isAttackingNow())
		{
			state.phase = Phase.IDLE;
			state.nextActionTime = now + COMBAT_BACKOFF;
			return;
		}

		if (state.phase == Phase.MOVING)
		{
			if (npc.isMoving())
			{
				return; // still travelling
			}
			// Arrived: idle for a while before the next goal.
			state.phase = Phase.IDLE;
			state.nextActionTime = now + (Rnd.get(state.profile.pauseMin, state.profile.pauseMax) * 1000L);
			return;
		}

		// IDLE: wait out the pause, then pick the next destination.
		if (now < state.nextActionTime)
		{
			return;
		}

		final Location destination = nextDestination(npc, state);
		if (destination == null)
		{
			state.nextActionTime = now + (state.profile.pauseMin * 1000L);
			return;
		}

		if (state.profile.run)
		{
			npc.setRunning();
		}
		else
		{
			npc.setWalking();
		}
		npc.getAI().setIntention(Intention.MOVE_TO, destination);
		state.phase = Phase.MOVING;
	}

	private Location nextDestination(Npc npc, BotState state)
	{
		final Profile profile = state.profile;
		if (profile.type == ProfileType.PATROL)
		{
			if (profile.points.isEmpty())
			{
				return null;
			}
			final Location point = profile.points.get(state.patrolIndex % profile.points.size());
			state.patrolIndex++;
			return GeoEngine.getInstance().getValidLocation(npc, point);
		}

		if (profile.type == ProfileType.VISIT)
		{
			if (profile.points.isEmpty())
			{
				return null;
			}
			// Head to a random point of interest, so movement looks purposeful (and idles long on arrival).
			return GeoEngine.getInstance().getValidLocation(npc, profile.points.get(Rnd.get(profile.points.size())));
		}

		if (profile.type == ProfileType.FARM)
		{
			// Head toward the nearest live monster inside the zone; if there is none, fall through to a
			// roam so the bot relocates and looks for mobs elsewhere within the zone.
			final int searchRange = Math.min(2200, Math.max(800, state.radius));
			final Monster target = nearestMonster(npc, state, searchRange);
			if (target != null)
			{
				return GeoEngine.getInstance().getValidLocation(npc, new Location(target.getX(), target.getY(), target.getZ()));
			}
		}

		// WANDER: random reachable point within the bot's radius of the home anchor.
		final int radius = Math.max(100, state.radius);
		for (int attempt = 0; attempt < 5; attempt++)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int distance = Rnd.get(radius / 4, radius);
			final int x = state.home.getX() + (int) (Math.cos(angle) * distance);
			final int y = state.home.getY() + (int) (Math.sin(angle) * distance);
			final Location candidate = GeoEngine.getInstance().getValidLocation(npc, new Location(x, y, state.home.getZ()));
			// Only accept a spot the bot can walk to in a straight line (no wall between), so they stop
			// picking points behind buildings and running into walls.
			if (npc.isInsideRadius2D(candidate, radius + 100) && GeoEngine.getInstance().canMoveToTarget(npc, candidate))
			{
				return candidate;
			}
		}
		return null;
	}

	public static FakePlayerBehaviorManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final FakePlayerBehaviorManager INSTANCE = new FakePlayerBehaviorManager();
	}
}
