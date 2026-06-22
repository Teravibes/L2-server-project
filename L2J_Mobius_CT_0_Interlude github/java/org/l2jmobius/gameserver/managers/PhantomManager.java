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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.templates.PlayerTemplate;

/**
 * Real-Player phantom system (vertical slice).
 * <p>
 * Unlike the NPC-based {@link FakePlayerBehaviorManager} (which renders NPCs to <i>look</i> like
 * players), a phantom here is a genuine {@link Player} database object with no client attached - the
 * same clientless-Player pattern Mobius already uses for offline traders
 * ({@code OfflineTraderTable.restoreOfflineTraders}). That gives a phantom real skills, inventory,
 * stats, leveling and PvP eligibility that an NPC cannot have.
 * <p>
 * This slice does the minimum end-to-end: create/spawn a clientless fighter, then tick it to seek and
 * melee the nearest monster, resting when there is nothing to hit. Skills, buffs, shots, PvP, gearing,
 * persistence and procedural identities are deliberately left for later increments.
 * @author Claude
 */
public class PhantomManager
{
	private static final Logger LOGGER = Logger.getLogger(PhantomManager.class.getName());

	private static final String ACCOUNT_NAME = "phantom";
	// How often each phantom re-evaluates what to do. A clientless Player's AI is event-driven, so we
	// re-issue its intention on every tick to keep combat moving forward (mirrors the offline-trader idea
	// of the server driving a Player that has no client of its own).
	private static final long TICK_INTERVAL = 4000;
	// How far a phantom looks for something to kill.
	private static final int HUNT_RANGE = 1600;

	private final List<Player> _phantoms = new ArrayList<>();
	private final ConcurrentHashMap<Integer, Player> _byObjectId = new ConcurrentHashMap<>();
	private boolean _ticking = false;

	protected PhantomManager()
	{
	}

	/**
	 * Creates a brand-new clientless fighter phantom and spawns it at the given location, then registers
	 * it with the hunting tick.
	 * @param location where to spawn
	 * @return the spawned phantom, or {@code null} on failure
	 */
	public Player spawnPhantom(Location location)
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

			// Full bars and an online flag so it behaves like a live character, then drop it into the world
			// exactly the way offline traders are restored - no GameClient is ever attached.
			phantom.setOnlineStatus(true, false);
			phantom.setCurrentHp(phantom.getMaxHp());
			phantom.setCurrentMp(phantom.getMaxMp());
			phantom.setCurrentCp(phantom.getMaxCp());
			phantom.spawnMe(location.getX(), location.getY(), location.getZ());
			phantom.setOnlineStatus(true, true);
			phantom.broadcastUserInfo();
			phantom.setRunning();

			_phantoms.add(phantom);
			_byObjectId.put(phantom.getObjectId(), phantom);
			startTicking();
			LOGGER.info(getClass().getSimpleName() + ": Spawned phantom '" + phantom.getName() + "' (objId=" + phantom.getObjectId() + ").");
			return phantom;
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to spawn phantom: " + e.getMessage());
			return null;
		}
	}

	/** Despawns and forgets every phantom (slice cleanup; does not delete the DB rows). */
	public int clear()
	{
		int removed = 0;
		for (Player phantom : new ArrayList<>(_phantoms))
		{
			try
			{
				phantom.deleteMe();
				removed++;
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Failed to despawn phantom " + phantom.getObjectId() + ": " + e.getMessage());
			}
		}
		_phantoms.clear();
		_byObjectId.clear();
		return removed;
	}

	public int getCount()
	{
		return _phantoms.size();
	}

	private synchronized void startTicking()
	{
		if (_ticking)
		{
			return;
		}
		_ticking = true;
		ThreadPool.scheduleAtFixedRate(this::tick, TICK_INTERVAL, TICK_INTERVAL);
		LOGGER.info(getClass().getSimpleName() + ": Phantom tick started.");
	}

	private void tick()
	{
		for (Player phantom : new ArrayList<>(_phantoms))
		{
			try
			{
				// Drop phantoms that died or otherwise left the world; this slice does not respawn them yet.
				if (phantom.isDead() || (World.getInstance().findObject(phantom.getObjectId()) == null))
				{
					_phantoms.remove(phantom);
					_byObjectId.remove(phantom.getObjectId());
					continue;
				}
				process(phantom);
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Tick error for " + phantom.getName() + ": " + e.getMessage());
			}
		}
	}

	private void process(Player phantom)
	{
		// Let an in-progress attack run; we only redirect when idle so we do not interrupt every swing.
		if (phantom.isAttackingNow() || phantom.isCastingNow())
		{
			return;
		}

		final Monster target = nearestMonster(phantom);
		if (target != null)
		{
			phantom.setRunning();
			phantom.setTarget(target);
			// ATTACK drives the full engine path: walk into range via geodata, then auto-attack.
			phantom.getAI().setIntention(Intention.ATTACK, target);
			return;
		}

		// Nothing to hit nearby: sit and recover instead of pacing around.
		if (!phantom.isSitting())
		{
			phantom.getAI().setIntention(Intention.IDLE);
			phantom.sitDown();
		}
	}

	private Monster nearestMonster(Player phantom)
	{
		final List<Monster> found = new ArrayList<>();
		World.getInstance().forEachVisibleObjectInRange(phantom, Monster.class, HUNT_RANGE, monster ->
		{
			if (!monster.isDead() && !monster.isAlikeDead())
			{
				found.add(monster);
			}
		});
		Monster nearest = null;
		double best = Double.MAX_VALUE;
		for (Monster monster : found)
		{
			final double distance = phantom.calculateDistance2D(monster);
			if (distance < best)
			{
				best = distance;
				nearest = monster;
			}
		}
		return nearest;
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
