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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerAppearance;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerStoreItem;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
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

	// The brain appends [[MEET:spot]] to a whisper when it agrees to walk over; we act on it then strip it.
	private static final Pattern MEET_TAG = Pattern.compile("\\[\\[\\s*MEET\\s*:\\s*([a-zA-Z]+)\\s*\\]\\]", Pattern.CASE_INSENSITIVE);

	// Datapack-verified town centres, so a bot can truthfully say where it is when asked.
	private static final String[] TOWN_NAMES =
	{
		"Talking Island", "Gludin", "Gludio", "Dion", "Giran", "Oren", "Aden", "Heine",
		"Goddard", "Rune", "Schuttgart", "the Elven Village", "the Dark Elf Village",
		"the Dwarven Village", "the Orc Village"
	};
	private static final int[][] TOWN_COORDS =
	{
		{ -83990, 243336 }, { -83520, 150560 }, { -14288, 122752 }, { 15670, 142980 }, { 83400, 147600 },
		{ 82200, 53500 }, { 146680, 25800 }, { 111360, 220890 }, { 147300, -56570 }, { 43800, -47700 },
		{ 87386, -143246 }, { 46926, 51511 }, { 12501, 16768 }, { 115072, -178176 }, { -44316, -113136 }
	};
	
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

		// Seated private-store vendors are treated as AFK shops: they do not answer whispers.
		final Npc bot = resolveBot(fpcName);
		if ((bot != null) && isStoreVendor(bot))
		{
			return;
		}

		// LLM brain hook (private whisper). Falls back to canned chat if the bridge is offline.
		// The bot's whereabouts go along so it can truthfully answer "where are you?".
		final String aiReply = askBrain(player.getName(), fpcName, message, nearestLocation(bot));
		if (aiReply != null)
		{
			sendChat(player, fpcName, handleMeetRequest(aiReply, player, bot));
			return;
		}
		
		final String text = message.toLowerCase();
		
		if (text.contains("can you see me"))
		{
			if (bot != null)
			{
				if (bot.calculateDistance2D(player) < 3000)
				{
					if (GeoEngine.getInstance().canSeeTarget(bot, player) && !player.isInvisible())
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
			// A WTS/WTB ad may make one relevant bot PM the player to set up a real trade; otherwise the
			// usual nearby-bot trade banter applies.
			if (tryTradeResponder(speaker, text))
			{
				return;
			}
			reactToChat(speaker, speaker.getName(), text, false, "TRADE");
		}
	}

	// Matches a trade ad and captures the direction (sell/buy) plus the item phrase after it.
	private static final Pattern TRADE_AD = Pattern.compile("(wts|selling|s>|wtb|buying|b>)\\s*(.*)", Pattern.CASE_INSENSITIVE);

	/**
	 * If the player posted a WTS/WTB for a recognisable item, arms a nearby roaming bot as the
	 * counterparty: it PMs the player, walks to a meet spot, and opens a real store on arrival.
	 * @return {@code true} if a responder was set up (so we skip the generic banter)
	 */
	private boolean tryTradeResponder(Player player, String text)
	{
		if (MESSAGES_THIS_MINUTE.get() >= MAX_MESSAGES_PER_MINUTE)
		{
			return false;
		}
		final Matcher matcher = TRADE_AD.matcher(text);
		if (!matcher.find())
		{
			return false;
		}
		final String token = matcher.group(1).toLowerCase();
		final boolean playerSelling = token.startsWith("wts") || token.startsWith("selling") || token.equals("s>");
		// Strip quantities/punctuation from the item phrase.
		final String phrase = matcher.group(2).replaceAll("[0-9]+(k|kk)?", " ").replaceAll("[^a-zA-Z ]", " ").trim();
		final ItemTemplate item = FakePlayerStoreFactory.findItemByName(phrase);
		if (item == null)
		{
			return false;
		}

		final Npc bot = FakePlayerBehaviorManager.getInstance().pickTradeResponder(player);
		if (bot == null)
		{
			return false;
		}

		// Player selling -> bot buys it; player buying -> bot sells it.
		final int storeType = playerSelling ? PrivateStoreType.BUY.getId() : PrivateStoreType.SELL.getId();
		final List<FakePlayerStoreItem> stock = playerSelling ? FakePlayerStoreFactory.dealBuyStock(item.getId()) : FakePlayerStoreFactory.dealSellStock(item.getId());
		if (stock.isEmpty())
		{
			return false;
		}
		final String title = FakePlayerStoreFactory.title(playerSelling ? "BUY" : "SELL", stock);
		FakePlayerBehaviorManager.getInstance().setupDeal(bot, storeType, stock, title);

		// Send it walking to its nearest gatekeeper; if it can't, drop the deal and let banter handle it.
		if (!FakePlayerBehaviorManager.getInstance().requestMeet(bot, "gatekeeper", player))
		{
			FakePlayerBehaviorManager.getInstance().setupDeal(bot, 0, null, null);
			return false;
		}

		final int unit = stock.get(0).getPrice();
		final String deal = (playerSelling ? "buy their " : "sell ") + item.getName() + " at " + unit + " adena each; tell them to meet you at the " + nearestLocation(bot).replace("in ", "").replace("near ", "") + " gatekeeper";
		final String fpcName = bot.getName();
		ThreadPool.schedule(() ->
		{
			final String line = callBridge(fpcName, "OFFER", player.getName(), "", text, nearestLocation(bot), deal);
			sendChat(player, fpcName, (line == null) || line.isEmpty() ? ("saw ur post, lets deal - meet me at the " + nearestLocation(bot).replace("in ", "").replace("near ", "") + " gatekeeper") : line);
		}, Rnd.get(MIN_DELAY, MAX_DELAY));
		MESSAGES_THIS_MINUTE.incrementAndGet();
		return true;
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
		final Set<String> seenNames = new HashSet<>();
		World.getInstance().forEachVisibleObjectInRange(origin, Npc.class, range, npc ->
		{
			// Dedupe by name: several spawns can share one fake player name, but a given
			// character should answer a message only once. AFK store vendors stay silent.
			if (npc.isFakePlayer() && !isStoreVendor(npc) && (npc != origin) && !npc.getName().equals(speakerName) && seenNames.add(npc.getName()))
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
		final String line = askBrainPublic(bot.getName(), speakerName, overheard, channel, nearestLocation(bot));
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
			if (npc.isFakePlayer() && !isStoreVendor(npc))
			{
				bots.add(npc);
			}
		});
		if (!bots.isEmpty())
		{
			botSpeaks(bots.get(Rnd.get(bots.size())), "", "", "AMBIENT");
		}
	}
	
	private String askBrain(String playerName, String fpcName, String message, String location)
	{
		return callBridge(fpcName, "WHISPER", playerName, "", message, location, "");
	}

	private String askBrainPublic(String fpcName, String speakerName, String overheard, String mode, String location)
	{
		return callBridge(fpcName, mode, "", speakerName, overheard, location, "");
	}

	private String callBridge(String fpcName, String mode, String playerName, String speakerName, String body, String location, String deal)
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
				.header("X-Location", location == null ? "" : location) //
				.header("X-Deal", deal == null ? "" : deal) //
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
		final Npc npc = resolveBot(fpcName);
		if (npc != null)
		{
			player.sendPacket(new CreatureSay(npc, ChatType.WHISPER, fpcName, message));
		}
	}

	/**
	 * @return the proper-cased name of a whisper-able roaming bot, or {@code null} if the name is not a
	 *         live fake player or it is an AFK store vendor (those are treated as offline shops)
	 */
	public String talkableBotName(String name)
	{
		final Npc bot = resolveBot(name);
		return ((bot == null) || isStoreVendor(bot)) ? null : bot.getName();
	}

	/**
	 * Resolves a fake player by name: template bots through the spawn table, procedurally generated bots
	 * by scanning the live world (their names are not in {@link FakePlayerData}).
	 */
	private Npc resolveBot(String name)
	{
		if ((name == null) || name.isEmpty())
		{
			return null;
		}
		// Template fake players are the only ones registered in FakePlayerData. Guard with getProperName
		// first: getNpcIdByName unboxes a null Integer (NPE) for unknown/generated names.
		final String proper = FakePlayerData.getInstance().getProperName(name);
		if (proper != null)
		{
			final Spawn spawn = SpawnTable.getInstance().getAnySpawn(FakePlayerData.getInstance().getNpcIdByName(proper));
			if ((spawn != null) && (spawn.getLastSpawn() != null))
			{
				return spawn.getLastSpawn();
			}
		}
		// Procedurally generated bots: their names are not in FakePlayerData, so scan the live world.
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (object.isNpc())
			{
				final Npc npc = object.asNpc();
				if (npc.isFakePlayer() && name.equalsIgnoreCase(npc.getName()))
				{
					return npc;
				}
			}
		}
		return null;
	}

	/**
	 * If the bot's whisper reply carries a {@code [[MEET:spot]]} tag, send it walking to that spot, then
	 * strip the tag so the player only sees the natural line.
	 * @return the cleaned reply text
	 */
	private String handleMeetRequest(String reply, Player player, Npc bot)
	{
		if (reply == null)
		{
			return "";
		}
		final Matcher matcher = MEET_TAG.matcher(reply);
		final boolean tagged = matcher.find();
		boolean cancelled = false;
		if ((bot != null) && !isStoreVendor(bot))
		{
			if (tagged)
			{
				final String spot = matcher.group(1);
				if ("cancel".equalsIgnoreCase(spot))
				{
					FakePlayerBehaviorManager.getInstance().cancelMeet(bot);
					cancelled = true;
				}
				else
				{
					FakePlayerBehaviorManager.getInstance().requestMeet(bot, spot, player);
				}
			}
			else
			{
				// No movement tag: if it is already waiting for this player, they are still engaged -> keep waiting.
				FakePlayerBehaviorManager.getInstance().noteMeetInteraction(bot, player);
			}
		}
		final String cleaned = matcher.replaceAll("").trim();
		if (!cleaned.isEmpty())
		{
			return cleaned;
		}
		return cancelled ? "k np" : "omw";
	}

	/** A seated private-store vendor is an AFK shop and never chats. */
	private static boolean isStoreVendor(Npc npc)
	{
		final FakePlayerAppearance look = npc.getFakePlayerAppearance();
		return (look != null) && (look.getPrivateStoreType() != 0);
	}

	/** @return a short phrase like "in Giran" or "near Aden" for the bot's current position. */
	private static String nearestLocation(Npc npc)
	{
		if (npc == null)
		{
			return "";
		}
		int best = -1;
		long bestDistanceSq = Long.MAX_VALUE;
		for (int i = 0; i < TOWN_COORDS.length; i++)
		{
			final long dx = npc.getX() - TOWN_COORDS[i][0];
			final long dy = npc.getY() - TOWN_COORDS[i][1];
			final long distanceSq = (dx * dx) + (dy * dy);
			if (distanceSq < bestDistanceSq)
			{
				bestDistanceSq = distanceSq;
				best = i;
			}
		}
		if (best < 0)
		{
			return "";
		}
		return (Math.sqrt(bestDistanceSq) < 3000 ? "in " : "near ") + TOWN_NAMES[best];
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