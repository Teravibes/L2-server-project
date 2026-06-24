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
package handlers.chat.commands.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.data.xml.RouteData;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * GM commands for recording bot movement routes in-game.
 *
 * <ul>
 * <li>{@code //record_route <name>} — starts capturing your position; a waypoint is appended
 *     every time you move more than 150 units from the last captured point.</li>
 * <li>{@code //stop_route} — stops recording and writes the route to
 *     {@code data/routes/<name>.xml}.</li>
 * <li>{@code //list_routes} — lists all saved route names.</li>
 * </ul>
 */
public class AdminFpcRoute implements IAdminCommandHandler
{
	private static final int MIN_POINT_DISTANCE = 150;

	private static final String[] ADMIN_COMMANDS =
	{
		"admin_record_route",
		"admin_stop_route",
		"admin_list_routes"
	};

	/** objectId -> recording session */
	private static final Map<Integer, Session> SESSIONS = new ConcurrentHashMap<>();

	private static class Session
	{
		final String name;
		final List<Location> points = new ArrayList<>();
		Location last;

		Session(String name, Location start)
		{
			this.name = name;
			this.last = start;
			points.add(start);
		}
	}

	@Override
	public boolean onCommand(String command, Player player)
	{
		if (command.startsWith("admin_record_route"))
		{
			final String[] parts = command.split("\\s+", 2);
			if (parts.length < 2 || parts[1].isBlank())
			{
				player.sendMessage("Usage: //record_route <name>  (e.g. //record_route gludio_market)");
				return false;
			}
			final String name = parts[1].trim();
			if (SESSIONS.containsKey(player.getObjectId()))
			{
				player.sendMessage("Already recording '" + SESSIONS.get(player.getObjectId()).name + "'. Use //stop_route first.");
				return false;
			}
			final Location start = new Location(player.getX(), player.getY(), player.getZ());
			SESSIONS.put(player.getObjectId(), new Session(name, start));
			player.sendMessage("Recording route '" + name + "'. Walk the path, then type //stop_route.");
			player.sendMessage("A waypoint is saved every " + MIN_POINT_DISTANCE + " units. First point: " + start.getX() + ", " + start.getY() + ", " + start.getZ());
			return true;
		}

		if (command.equals("admin_stop_route"))
		{
			final Session session = SESSIONS.remove(player.getObjectId());
			if (session == null)
			{
				player.sendMessage("Not recording. Start with //record_route <name>.");
				return false;
			}
			// Capture final position.
			final Location cur = new Location(player.getX(), player.getY(), player.getZ());
			if (distSq(cur, session.last) > (MIN_POINT_DISTANCE * MIN_POINT_DISTANCE))
			{
				session.points.add(cur);
			}
			if (session.points.size() < 2)
			{
				player.sendMessage("Route '" + session.name + "' has only " + session.points.size() + " point(s) — not saved. Walk further before stopping.");
				return false;
			}
			RouteData.getInstance().saveRoute(session.name, session.points);
			player.sendMessage("Route '" + session.name + "' saved with " + session.points.size() + " waypoints.");
			return true;
		}

		if (command.equals("admin_list_routes"))
		{
			final var names = RouteData.getInstance().getRouteNames();
			if (names.isEmpty())
			{
				player.sendMessage("No routes saved yet.");
			}
			else
			{
				player.sendMessage("Saved routes (" + names.size() + "): " + String.join(", ", names));
			}
			return true;
		}

		return false;
	}

	/**
	 * Called by the movement system each time a recording player moves. Appends a waypoint when
	 * the player has travelled far enough from the last captured point.
	 */
	public static void onPlayerMoved(Player player)
	{
		final Session session = SESSIONS.get(player.getObjectId());
		if (session == null)
		{
			return;
		}
		final Location cur = new Location(player.getX(), player.getY(), player.getZ());
		if (distSq(cur, session.last) >= (MIN_POINT_DISTANCE * MIN_POINT_DISTANCE))
		{
			session.points.add(cur);
			session.last = cur;
			if ((session.points.size() % 10) == 0)
			{
				player.sendMessage("[Route recording] " + session.points.size() + " waypoints so far.");
			}
		}
	}

	public static boolean isRecording(Player player)
	{
		return SESSIONS.containsKey(player.getObjectId());
	}

	private static long distSq(Location a, Location b)
	{
		final long dx = a.getX() - b.getX();
		final long dy = a.getY() - b.getY();
		return (dx * dx) + (dy * dy);
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
