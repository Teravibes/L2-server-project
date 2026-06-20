/*
 * Copyright (c) 2013 L2jMobius
 * ... (license header unchanged) ...
 */
package org.l2jmobius.gameserver.managers;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.w3c.dom.Document;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.holders.FakePlayerChatHolder;
import org.l2jmobius.gameserver.data.xml.FakePlayerData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/**
 * @author Mobius
 */
public class FakePlayerChatManager implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerChatManager.class.getName());
	
	private static final List<FakePlayerChatHolder> MESSAGES = new ArrayList<>();
	private static final int MIN_DELAY = 5000;
	private static final int MAX_DELAY = 15000;
	
	// LLM bridge
	private static final HttpClient BRAIN_HTTP = HttpClient.newHttpClient();
	private static final String BRAIN_URL = "http://127.0.0.1:5000/chat";
	
	// ===== Social tuning knobs =====
	private static final boolean SOCIAL_ENABLED = true;
	private static final int REPLY_CHANCE_TO_PLAYER = 50; // % chance a nearby bot reacts to a player (throttle 1)
	private static final int REPLY_CHANCE_TO_BOT = 15; // % chance bot-to-bot (throttle 2: damping)
	private static final int MAX_REPLIERS = 2; // at most N bots answer one line
	private static final int SOCIAL_RANGE = 3000; // trade: how close a bot must be to react
	private static final int SAY_RANGE = 1250; // say: local hearing/broadcast range
	private static final int MAX_MESSAGES_PER_MINUTE = 8; // global rate cap (throttle 3)
	private static final long AMBIENT_INTERVAL = 240000; // spontaneous trade line every ~4 min
	private static final AtomicInteger MESSAGES_THIS_MINUTE = new AtomicInteger();
	private static boolean SOCIAL_STARTED = false;
	
	protected FakePlayerChatManager()
	{
		load();
	}
	
	@Override
	public void load()
	{
		if (FakePlayersConfig.FAKE_PLAYERS_ENABLED)
		{
			FakePlayerData.getInstance().report();
			if (FakePlayersConfig.FAKE_PLAYER_CHAT)
			{
				MESSAGES.clear();
				parseDatapackFile("data/FakePlayerChatData.xml");
				LOGGER.info(getClass().getSimpleName() + ": Loaded " + MESSAGES.size() + " chat templates.");
				startSocial();
			}
		}
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode -> forEach(listNode, "fakePlayerChat", fakePlayerChatNode ->
		{
			final StatSet set = new StatSet(parseAttributes(fakePlayerChatNode));
			MESSAGES.add(new FakePlayerChatHolder(set.getString("fpcName"), set.getString("searchMethod"), set.getString("searchText"), set.getString("answers")));
		}));
	}
	
	public void manageChat(Player player, String fpcName, String message)
	{
		ThreadPool.schedule(() -> manageResponce(player, fpcName, message), Rnd.get(MIN_DELAY, MAX_DELAY));
	}
	
	public void manageChat(Player player, String fpcName, String message, int minDelay, int maxDelay)
	{
		ThreadPool.schedule(() -> manageResponce(player, fpcName, message), Rnd.get(minDelay, maxDelay));
	}
	
	private void manageResponce(Player player, String fpcName, String message)
	{
		if (player == null)
		{
			return;
		}
		
		// LLM brain hook (private whisper). Falls back to canned chat if the bridge is offline.
		final String aiReply = askBrain(player.getName(), fpcName, message);
		if (aiReply != null)
		{
			sendChat(player, fpcName, aiReply);
			return;
		}
		
		final String text = message.toLowerCase();
		
		if (text.contains("can you see me"))
		{
			final Spawn spawn = SpawnTable.getInstance().getAnySpawn(FakePlayerData.getInstance().getNpcIdByName(fpcName));
			if (spawn != null)
			{
				final Npc npc = spawn.getLastSpawn();
				if (npc != null)
				{
					if (npc.calculateDistance2D(player) < 3000)
					{
						if (GeoEngine.getInstance().canSeeTarget(npc, player) && !player.isInvisible())
						{
							sendChat(player, fpcName, Rnd.nextBoolean() ? "i am not blind" : Rnd.nextBoolean() ? "of course i can" : "yes");
						}
						else
						{
							sendChat(player, fpcName, Rnd.nextBoolean() ? "i know you are around" : Rnd.nextBoolean() ? "not at the moment :P" : "no, where are you?");
						}
					}
					else
					{
						sendChat(player, fpcName, Rnd.nextBoolean() ? "nope, can't see you" : Rnd.nextBoolean() ? "nope" : "no");
					}
					return;
				}
			}
		}
		
		for (FakePlayerChatHolder chatHolder : MESSAGES)
		{
			if (!chatHolder.getFpcName().equals(fpcName) && !chatHolder.getFpcName().equals("ALL"))
			{
				continue;
			}
			switch (chatHolder.getSearchMethod())
			{
				case "EQUALS":
				{
					if (text.equals(chatHolder.getSearchText().get(0)))
					{
						sendChat(player, fpcName, chatHolder.getAnswers().get(Rnd.get(chatHolder.getAnswers().size())));
					}
					break;
				}
				case "STARTS_WITH":
				{
					if (text.startsWith(chatHolder.getSearchText().get(0)))
					{
						sendChat(player, fpcName, chatHolder.getAnswers().get(Rnd.get(chatHolder.getAnswers().size())));
					}
					break;
				}
				case "CONTAINS":
				{
					boolean allFound = true;
					for (String word : chatHolder.getSearchText())
					{
						if (!text.contains(word))
						{
							allFound = false;
						}
					}
					if (allFound)
					{
						sendChat(player, fpcName, chatHolder.getAnswers().get(Rnd.get(chatHolder.getAnswers().size())));
					}
					break;
				}
			}
		}
	}
	
	// ===== Social: trade (global) + say (local) + ambient + bot banter =====
	
	private void startSocial()
	{
		if (!SOCIAL_ENABLED || SOCIAL_STARTED)
		{
			return;
		}
		SOCIAL_STARTED = true;
		ThreadPool.scheduleAtFixedRate(() -> MESSAGES_THIS_MINUTE.set(0), 60000, 60000); // reset rate cap each minute
		ThreadPool.scheduleAtFixedRate(this::ambientTradeChat, AMBIENT_INTERVAL, AMBIENT_INTERVAL);
		LOGGER.info(getClass().getSimpleName() + ": Server-wide social chat enabled.");
	}
	
	// Called from ChatTrade when a real player uses trade (+) chat.
	public void overheardTradeChat(Player speaker, String text)
	{
		if (SOCIAL_ENABLED && (speaker != null) && (text != null) && !text.isEmpty())
		{
			reactToChat(speaker, speaker.getName(), text, false, "TRADE");
		}
	}
	
	// Called from ChatGeneral when a real player uses normal (say) chat.
	public void overheardSay(Player speaker, String text)
	{
		if (SOCIAL_ENABLED && (speaker != null) && (text != null) && !text.isEmpty())
		{
			reactToChat(speaker, speaker.getName(), text, false, "SAY");
		}
	}
	
	private void reactToChat(Creature origin, String speakerName, String text, boolean speakerIsBot, String channel)
	{
		if (MESSAGES_THIS_MINUTE.get() >= MAX_MESSAGES_PER_MINUTE)
		{
			return; // throttle 3: global cap
		}
		
		final int range = channel.equals("SAY") ? SAY_RANGE : SOCIAL_RANGE;
		final List<Npc> bots = new ArrayList<>();
		World.getInstance().forEachVisibleObjectInRange(origin, Npc.class, range, npc ->
		{
			if (npc.isFakePlayer() && (npc != origin))
			{
				bots.add(npc);
			}
		});
		if (bots.isEmpty())
		{
			return;
		}
		
		final int chance = speakerIsBot ? REPLY_CHANCE_TO_BOT : REPLY_CHANCE_TO_PLAYER;
		Collections.shuffle(bots);
		int repliers = 0;
		for (Npc bot : bots)
		{
			if (repliers >= MAX_REPLIERS)
			{
				break;
			}
			if (Rnd.get(100) >= chance) // throttles 1 & 2
			{
				continue;
			}
			repliers++;
			final Npc replier = bot;
			ThreadPool.schedule(() -> botSpeaks(replier, speakerName, text, channel), Rnd.get(MIN_DELAY, MAX_DELAY));
		}
	}
	
	private void botSpeaks(Npc bot, String speakerName, String overheard, String channel)
	{
		if (MESSAGES_THIS_MINUTE.get() >= MAX_MESSAGES_PER_MINUTE)
		{
			return;
		}
		// SAY is local: don't waste an LLM call if no player is close enough to hear it.
		if (channel.equals("SAY") && !hasPlayerInRange(bot, SAY_RANGE))
		{
			return;
		}
		final String line = askBrainPublic(bot.getName(), speakerName, overheard, channel);
		if ((line == null) || line.isEmpty())
		{
			return;
		}
		if (channel.equals("SAY"))
		{
			sendSayChat(bot, line);
		}
		else
		{
			sendTradeChat(bot, line); // TRADE and AMBIENT both broadcast to global trade
		}
		MESSAGES_THIS_MINUTE.incrementAndGet();
		
		// bot-to-bot banter (damped); ambient continues as trade banter.
		reactToChat(bot, bot.getName(), line, true, channel.equals("SAY") ? "SAY" : "TRADE");
	}
	
	private boolean hasPlayerInRange(Npc npc, int range)
	{
		final boolean[] found =
		{
			false
		};
		World.getInstance().forEachVisibleObjectInRange(npc, Player.class, range, player -> found[0] = true);
		return found[0];
	}

	private void sendTradeChat(Npc npc, String text)
	{
		final CreatureSay cs = new CreatureSay(npc, ChatType.TRADE, npc.getName(), text);
		for (Player player : World.getInstance().getPlayers()) // GLOBAL
		{
			player.sendPacket(cs);
		}
	}
	
	private void sendSayChat(Npc npc, String text)
	{
		final CreatureSay cs = new CreatureSay(npc, ChatType.GENERAL, npc.getName(), text);
		World.getInstance().forEachVisibleObjectInRange(npc, Player.class, SAY_RANGE, player -> player.sendPacket(cs)); // LOCAL
	}
	
	private void ambientTradeChat()
	{
		if (!SOCIAL_ENABLED || (MESSAGES_THIS_MINUTE.get() >= MAX_MESSAGES_PER_MINUTE))
		{
			return;
		}
		final List<Player> players = new ArrayList<>(World.getInstance().getPlayers());
		if (players.isEmpty())
		{
			return; // nobody online to hear it
		}
		final Player witness = players.get(Rnd.get(players.size()));
		final List<Npc> bots = new ArrayList<>();
		World.getInstance().forEachVisibleObjectInRange(witness, Npc.class, SOCIAL_RANGE, npc ->
		{
			if (npc.isFakePlayer())
			{
				bots.add(npc);
			}
		});
		if (!bots.isEmpty())
		{
			botSpeaks(bots.get(Rnd.get(bots.size())), "", "", "AMBIENT");
		}
	}
	
	private String askBrain(String playerName, String fpcName, String message)
	{
		return callBridge(fpcName, "WHISPER", playerName, "", message);
	}
	
	private String askBrainPublic(String fpcName, String speakerName, String overheard, String mode)
	{
		return callBridge(fpcName, mode, "", speakerName, overheard);
	}
	
	private String callBridge(String fpcName, String mode, String playerName, String speakerName, String body)
	{
		try
		{
			final HttpRequest request = HttpRequest.newBuilder() //
				.uri(URI.create(BRAIN_URL)) //
				.timeout(Duration.ofSeconds(20)) //
				.header("X-FPC", fpcName) //
				.header("X-Mode", mode) //
				.header("X-Player", playerName) //
				.header("X-Speaker", speakerName) //
				.header("Content-Type", "text/plain; charset=utf-8") //
				.POST(HttpRequest.BodyPublishers.ofString(body)) //
				.build();
			final HttpResponse<String> response = BRAIN_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 200)
			{
				final String reply = response.body().trim();
				if (!reply.isEmpty())
				{
					return reply;
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Brain bridge unreachable: " + e.getMessage());
		}
		return null;
	}
	
	public void sendChat(Player player, String fpcName, String message)
	{
		final Spawn spawn = SpawnTable.getInstance().getAnySpawn(FakePlayerData.getInstance().getNpcIdByName(fpcName));
		if (spawn != null)
		{
			final Npc npc = spawn.getLastSpawn();
			if (npc != null)
			{
				player.sendPacket(new CreatureSay(npc, ChatType.WHISPER, fpcName, message));
			}
		}
	}
	
	public static FakePlayerChatManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final FakePlayerChatManager INSTANCE = new FakePlayerChatManager();
	}
}