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
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;

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

	private enum ProfileType
	{
		/** Random short hops around a home anchor (town life, idle farming spot). */
		WANDER,
		/** Cycles through an ordered list of points (guards, travellers). */
		PATROL
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
		Phase phase = Phase.IDLE;
		long nextActionTime;
		int patrolIndex;

		BotState(Profile profile, Location home)
		{
			this.profile = profile;
			this.home = home;
		}
	}

	private final Map<String, Profile> _profiles = new HashMap<>();
	private final Map<String, String> _assignByName = new HashMap<>(); // lowercase fpc name -> profile
	private final Map<Integer, String> _assignByNpcId = new HashMap<>(); // npc id -> profile
	private String _defaultProfile = null;

	private final Map<Integer, BotState> _bots = new ConcurrentHashMap<>(); // objectId -> state
	private boolean _started = false;

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
		parseDatapackFile("data/FakePlayerBehavior.xml");
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _profiles.size() + " behavior profiles.");
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
		});
	}

	private void start()
	{
		if (_started || _profiles.isEmpty())
		{
			return;
		}
		_started = true;
		ThreadPool.scheduleAtFixedRate(this::discover, DISCOVERY_INTERVAL, DISCOVERY_INTERVAL);
		ThreadPool.scheduleAtFixedRate(this::tick, BEHAVIOR_INTERVAL, BEHAVIOR_INTERVAL);
		LOGGER.info(getClass().getSimpleName() + ": Fake player behavior enabled.");
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
				_bots.put(npc.getObjectId(), new BotState(profile, new Location(npc.getX(), npc.getY(), npc.getZ())));
			}
		}
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
				_bots.remove(entry.getKey());
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

		// WANDER: random reachable point within the profile radius of the home anchor.
		for (int attempt = 0; attempt < 5; attempt++)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int distance = Rnd.get(profile.radius / 4, profile.radius);
			final int x = state.home.getX() + (int) (Math.cos(angle) * distance);
			final int y = state.home.getY() + (int) (Math.sin(angle) * distance);
			final Location candidate = GeoEngine.getInstance().getValidLocation(npc, new Location(x, y, state.home.getZ()));
			if (npc.isInsideRadius2D(candidate, profile.radius + 100))
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
