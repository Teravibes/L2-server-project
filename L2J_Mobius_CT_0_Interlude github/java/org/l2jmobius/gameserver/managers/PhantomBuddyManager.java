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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.groups.PartyDistributionType;
import org.l2jmobius.gameserver.model.groups.PartyMessageType;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/**
 * Personal support buddy brain.
 * <p>
 * A buddy is a support-class phantom (Elven Elder / Prophet / Warcryer) spawned idle in a town by
 * {@link PhantomManager}. A real player can whisper it, party it (the invite is auto-accepted server-side
 * by {@code RequestJoinParty}), and use it as a personal buffer/healer. Once partied this manager keeps the
 * owner buffed and topped up to roughly half health, keeps itself buffed, follows the owner, warns when its
 * MP runs low and sits to recover when safe, teleports to a named gatekeeper destination on command, and
 * survives a grace period if the owner teleports away or briefly logs off.
 * <p>
 * Everything here is rule-based and works with the LLM brain offline; the whisper command parser is
 * deterministic. (Phase 2 will route natural language through the brain on top of this.)
 * @author Claude
 */
public class PhantomBuddyManager implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(PhantomBuddyManager.class.getName());

	// Fast behaviour tick for engaged (partied) buddies: heal/buff/follow need to feel responsive.
	private static final long TICK_INTERVAL = 1000;
	// Heal the owner when below this, the "50% and above" the user asked for. Self-heal a bit lower.
	private static final int OWNER_HEAL_PERCENT = 50;
	private static final int SELF_HEAL_PERCENT = 40;
	// Re-cast a buff when it is missing or has less than this many seconds left, so it never fully drops.
	private static final int BUFF_REFRESH_SECONDS = 20;
	// MP watch: warn + (when safe) sit at the low mark; stand again once recovered to the high mark.
	private static final int MP_LOW_PERCENT = 25;
	private static final int MP_OK_PERCENT = 70;
	private static final long MP_WARN_COOLDOWN = 30000;
	// Only act on buffs/heals when the owner is close enough that a cast makes sense (else just follow).
	private static final int SUPPORT_RANGE = 900;
	private static final int FOLLOW_RANGE = 250;
	private static final int DANGER_RANGE = 700; // a monster this close means "don't sit to rest"
	// Grace before despawning an engaged buddy whose owner went offline; "brb"/"5 min" extends it.
	private static final long OFFLINE_GRACE = 120000;
	private static final long BRB_GRACE = 360000;
	// LLM brain bridge (optional). When a whisper isn't a recognised command, it is sent here for a natural
	// reply that can carry an action tag ([[FOLLOW]] / [[STAY]] / [[TP:place]] / [[GRACE:n]] / [[BUFF]] /
	// [[DISBAND]]). If the brain is offline the buddy just falls back to a short canned line.
	private static final HttpClient BRAIN_HTTP = HttpClient.newHttpClient();
	private static final String BRAIN_URL = "http://127.0.0.1:5000/chat";
	private static final Pattern TAG_FOLLOW = Pattern.compile("\\[\\[\\s*FOLLOW\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_STAY = Pattern.compile("\\[\\[\\s*STAY\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_BUFF = Pattern.compile("\\[\\[\\s*BUFF\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_DISBAND = Pattern.compile("\\[\\[\\s*DISBAND\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_TP = Pattern.compile("\\[\\[\\s*TP\\s*:\\s*([^\\]]+?)\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_GRACE = Pattern.compile("\\[\\[\\s*GRACE\\s*:\\s*(\\d+)\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern ANY_TAG = Pattern.compile("\\[\\[[^\\]]*\\]\\]");

	// Heal skills a buddy may know, best (highest id here = strongest) first.
	private static final int[] HEAL_PRIORITY =
	{
		1218, // Greater Battle Heal
		1217, // Greater Heal
		1015, // Battle Heal
		1011 // Heal
	};

	/** Per-buddy state. */
	private static class Buddy
	{
		final Player npc;
		Player owner; // null while idle in town; set when partied
		boolean following = true;
		long graceUntil; // 0 unless the owner is offline / said brb; despawn when exceeded
		long lastMpWarn;
		List<Skill> buffs; // beneficial buffs to maintain (lazy)
		Skill heal; // best heal it knows (lazy)

		Buddy(Player npc)
		{
			this.npc = npc;
		}
	}

	private final ConcurrentHashMap<Integer, Buddy> _buddies = new ConcurrentHashMap<>();
	private final Map<String, Location> _destinations = new HashMap<>(); // gatekeeper destination name -> spot
	private boolean _ticking = false;

	protected PhantomBuddyManager()
	{
		load();
	}

	@Override
	public void load()
	{
		// Reuse the real gatekeeper destination data so a buddy teleports to the exact spot a gatekeeper would
		// drop the player (Ruins of Agony, Cruma Tower, ...). All town teleporter lists are scanned into one
		// name -> location index.
		_destinations.clear();
		parseDatapackDirectory("data/teleporters/town", false);
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _destinations.size() + " teleport destinations for buddies.");
	}

	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode -> forEach(listNode, "npc", npcNode -> forEach(npcNode, "teleport", teleportNode -> forEach(teleportNode, "location", locationNode ->
		{
			final StatSet set = new StatSet(parseAttributes(locationNode));
			final String name = set.getString("name", "");
			if (!name.isEmpty() && set.contains("x"))
			{
				_destinations.putIfAbsent(normalize(name), new Location(set.getInt("x"), set.getInt("y"), set.getInt("z")));
			}
		}))));
	}

	// ===== Lifecycle (called by PhantomManager) =====

	/** Registers a freshly spawned buddy and gives it its starting self-buffs. */
	public void onBuddySpawned(Player buddy, String roleName)
	{
		final Buddy state = new Buddy(buddy);
		_buddies.put(buddy.getObjectId(), state);
		startTicking();
	}

	/**
	 * Per-buddy housekeeping on the slow {@link PhantomManager} supervisor tick (~5s): keep the buddy
	 * self-buffed while idle and enforce the offline/brb grace. Combat-speed work (healing, following) is on
	 * the fast tick below.
	 */
	public void supervise(Player buddy)
	{
		final Buddy state = _buddies.get(buddy.getObjectId());
		if (state == null)
		{
			return; // not tracked (e.g. just despawned)
		}
		if (buddy.isDead())
		{
			// A dead buddy is of no use; release the party bond and let PhantomManager recycle the corpse.
			release(state, false);
			return;
		}
		// Idle self-buff upkeep (partied buddies get this on the fast tick).
		if (state.owner == null)
		{
			maintainBuffs(state, buddy, false);
		}
	}

	// ===== Whisper command handling (called from ChatWhisper via FakePlayerChatManager) =====

	/** @return {@code true} if the named player is a live buddy this manager owns (so whispers route here). */
	public boolean isBuddy(Player player)
	{
		return (player != null) && _buddies.containsKey(player.getObjectId());
	}

	/**
	 * Handles a whisper a player sent to a buddy and returns the buddy's reply (delivered as a whisper back).
	 * Deterministic keyword parsing - works with the LLM brain offline.
	 */
	public String handleWhisper(Player owner, Player buddy, String message)
	{
		final Buddy state = _buddies.get(buddy.getObjectId());
		if ((state == null) || (owner == null) || (message == null))
		{
			return null;
		}
		return parseCommand(state, owner, buddy, message, false);
	}

	/**
	 * Handles a party-channel line: every buddy in the speaker's party that is bound to that speaker reacts to
	 * it with the same commands as a whisper and answers in party chat. Lets you drive your buffers from party
	 * chat, not only private whisper.
	 */
	public void handlePartyChat(Player speaker, String message)
	{
		if ((speaker == null) || (message == null) || !speaker.isInParty())
		{
			return;
		}
		for (Player member : speaker.getParty().getMembers())
		{
			final Buddy state = _buddies.get(member.getObjectId());
			if ((state == null) || (state.owner != speaker))
			{
				continue; // not a buddy, or a buddy serving someone else
			}
			final String reply = parseCommand(state, speaker, member, message, true);
			if ((reply != null) && !reply.isEmpty())
			{
				partyChat(member, reply);
			}
		}
	}

	/**
	 * Deterministic command parser shared by the whisper and party-chat paths. Returns the buddy's reply for
	 * the caller to deliver on the right channel, or {@code null} when the line was handed to the LLM brain
	 * (which delivers its own reply asynchronously on the same channel).
	 */
	private String parseCommand(Buddy state, Player owner, Player buddy, String message, boolean party)
	{
		final String text = message.toLowerCase().trim();

		// Party request: the buddy just agrees; the real bond forms when the player sends the invite (which
		// RequestJoinParty auto-accepts and routes to onInvited).
		if (containsAny(text, "party", "group", "join me", "wanna pt", "want to pt", "lets party", "let's party"))
		{
			return "sure, invite me";
		}

		// Follow / stay.
		if (containsAny(text, "follow", "come with", "stick with me", "on me"))
		{
			if (!isPartiedWith(state, owner))
			{
				return "party me first :)";
			}
			state.following = true;
			return "ok, following you";
		}
		if (containsAny(text, "stay", "wait here", "hold here", "stop following"))
		{
			state.following = false;
			buddy.getAI().setIntention(Intention.IDLE);
			return "k, waiting here";
		}

		// Grace extension (brb / give me a minute).
		if (containsAny(text, "brb", "be right back", "give me", "gimme", "min", "sec", "moment", "afk"))
		{
			state.graceUntil = System.currentTimeMillis() + BRB_GRACE;
			return "np, take your time";
		}

		// Disband / dismiss.
		if (containsAny(text, "disband", "leave party", "leave the party", "dismiss", "bye", "you can go", "thanks bye"))
		{
			if (isPartiedWith(state, owner))
			{
				release(state, true);
				return "gl hf o/";
			}
			return "we're not partied";
		}

		// Re-buff on demand.
		if (containsAny(text, "buff", "rebuff"))
		{
			if (!isPartiedWith(state, owner))
			{
				return "party me first :)";
			}
			return "buffing you up";
		}

		// Teleport to a named gatekeeper destination, e.g. "going to ruins of agony" / "tp cruma tower".
		final Location destination = matchDestination(text);
		if (destination != null)
		{
			if (!isPartiedWith(state, owner))
			{
				return "party me first :)";
			}
			teleportBuddy(state, owner, destination);
			return "ok, tping there now";
		}

		// Status query.
		if (containsAny(text, "mp?", "your mp", "how is your mp", "hp?", "status"))
		{
			return "hp " + buddy.getCurrentHpPercent() + "% / mp " + buddy.getCurrentMpPercent() + "%";
		}

		// Not a recognised command: hand it to the LLM brain (off-thread) for a natural reply that may carry an
		// action tag. Returns null here; the async task delivers the buddy's reply (on this same channel) when
		// it comes back.
		askBrainAsync(owner, buddy, message, party);
		return null;
	}

	/**
	 * Asks the LLM brain to interpret a free-form whisper (slang, abbreviations, chit-chat) and reply, off the
	 * network thread. The reply may carry an action tag which is executed and stripped before the buddy speaks.
	 */
	private void askBrainAsync(Player owner, Player buddy, String message, boolean party)
	{
		final int ownerId = owner.getObjectId();
		final int buddyId = buddy.getObjectId();
		ThreadPool.execute(() ->
		{
			final Buddy state = _buddies.get(buddyId);
			final Player ownerNow = (Player) World.getInstance().findObject(ownerId);
			if ((state == null) || (ownerNow == null) || state.npc.isDead())
			{
				return;
			}
			String reply = callBrain(state.npc.getName(), ownerNow.getName(), isPartiedWith(state, ownerNow), message);
			if ((reply == null) || reply.isEmpty())
			{
				reply = "hm? (try: party, follow, stay, a place to tp, or brb)";
			}
			else
			{
				reply = applyBuddyTags(reply, state, ownerNow, state.npc);
			}
			if (!reply.isEmpty())
			{
				// Answer on the same channel the order came in on.
				if (party && state.npc.isInParty())
				{
					partyChat(state.npc, reply);
				}
				else
				{
					whisper(state.npc, ownerNow, reply);
				}
			}
		});
	}

	/** Executes any action tag in the brain's reply and returns the cleaned, speakable text. */
	private String applyBuddyTags(String reply, Buddy state, Player owner, Player buddy)
	{
		final boolean partied = isPartiedWith(state, owner);
		if (partied && TAG_FOLLOW.matcher(reply).find())
		{
			state.following = true;
			ensureFollow(state, owner);
		}
		if (partied && TAG_STAY.matcher(reply).find())
		{
			state.following = false;
			buddy.getAI().setIntention(Intention.IDLE);
		}
		final Matcher grace = TAG_GRACE.matcher(reply);
		if (grace.find())
		{
			state.graceUntil = System.currentTimeMillis() + (Math.min(30, Integer.parseInt(grace.group(1))) * 60000L);
		}
		final Matcher tp = TAG_TP.matcher(reply);
		if (partied && tp.find())
		{
			final Location destination = matchDestination(tp.group(1));
			if (destination != null)
			{
				teleportBuddy(state, owner, destination);
			}
		}
		if (partied && TAG_DISBAND.matcher(reply).find())
		{
			release(state, true);
		}
		// [[BUFF]] needs no action: the buff loop already keeps the owner topped up.
		return ANY_TAG.matcher(reply).replaceAll("").trim();
	}

	/** Calls the brain bridge in BUDDY mode; returns the raw reply (possibly with tags), or null if offline. */
	private String callBrain(String buddyName, String ownerName, boolean partied, String message)
	{
		try
		{
			final HttpRequest request = HttpRequest.newBuilder() //
				.uri(URI.create(BRAIN_URL)) //
				.timeout(Duration.ofSeconds(20)) //
				.header("X-FPC", buddyName) //
				.header("X-Mode", "BUDDY") //
				.header("X-Player", ownerName) //
				.header("X-Partied", Boolean.toString(partied)) //
				.header("Content-Type", "text/plain; charset=utf-8") //
				.POST(HttpRequest.BodyPublishers.ofString(message)) //
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
			LOGGER.fine(getClass().getSimpleName() + ": Brain bridge unreachable: " + e.getMessage());
		}
		return null;
	}

	/**
	 * Forms the party server-side and binds the buddy to its owner. Called by {@code RequestJoinParty} when a
	 * player invites a buddy (a clientless player cannot answer the normal invite dialog, so we accept here).
	 * @return {@code true} if the buddy joined the player's party
	 */
	public boolean onInvited(Player owner, Player buddy)
	{
		final Buddy state = _buddies.get(buddy.getObjectId());
		if ((state == null) || (owner == null) || buddy.isDead())
		{
			return false;
		}
		if (buddy.isInParty())
		{
			return false; // already serving someone
		}
		try
		{
			if (!owner.isInParty())
			{
				final PartyDistributionType type = (owner.getPartyDistributionType() != null) ? owner.getPartyDistributionType() : PartyDistributionType.FINDERS_KEEPERS;
				owner.setParty(new Party(owner, type));
			}
			else if (owner.getParty().getMemberCount() >= 9)
			{
				return false;
			}
			buddy.joinParty(owner.getParty());
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to party buddy " + buddy.getName() + ": " + e.getMessage());
			return false;
		}
		state.owner = owner;
		state.following = true;
		state.graceUntil = 0;
		PhantomManager.getInstance().setBuddyEngaged(buddy, true); // exempt from the proximity despawn
		ensureFollow(state, owner);
		whisper(buddy, owner, "ok partied, i'll keep you buffed. say a place to tp, or brb if you need a sec");
		startTicking();
		return true;
	}

	// ===== Fast behaviour tick =====

	private synchronized void startTicking()
	{
		if (_ticking)
		{
			return;
		}
		_ticking = true;
		ThreadPool.scheduleAtFixedRate(this::tick, TICK_INTERVAL, TICK_INTERVAL);
	}

	private void tick()
	{
		final long now = System.currentTimeMillis();
		for (Buddy state : _buddies.values())
		{
			final Player buddy = state.npc;
			try
			{
				if ((buddy == null) || (World.getInstance().findObject(buddy.getObjectId()) == null))
				{
					_buddies.remove(buddy == null ? -1 : buddy.getObjectId());
					continue;
				}
				if (state.owner == null)
				{
					continue; // idle in town; self-buff is handled on the slow supervise tick
				}
				if (buddy.isDead())
				{
					release(state, false);
					continue;
				}
				if (!serveOwner(state, buddy, now))
				{
					continue; // released (party gone / owner offline past grace)
				}
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Buddy tick error for " + (buddy == null ? "?" : buddy.getName()) + ": " + e.getMessage());
			}
		}
	}

	/**
	 * One service step for a partied buddy: validate the bond, manage offline grace, then (when the owner is
	 * present) heal &gt; buff &gt; recover MP &gt; follow, in that priority.
	 * @return {@code false} if the buddy was released and should be skipped
	 */
	private boolean serveOwner(Buddy state, Player buddy, long now)
	{
		final Player owner = state.owner;

		// Party bond broken (owner disbanded, kicked the buddy, or left): stop serving.
		if (!isPartiedWith(state, owner))
		{
			release(state, false);
			return false;
		}

		// Owner offline (logged off): keep the buddy for a grace window so a quick relog / teleport desync
		// doesn't lose it, then despawn. "brb" extends graceUntil beyond the default.
		if (!owner.isOnline() || (World.getInstance().findObject(owner.getObjectId()) == null))
		{
			if (state.graceUntil == 0)
			{
				state.graceUntil = now + OFFLINE_GRACE;
			}
			if (now >= state.graceUntil)
			{
				release(state, false);
				return false;
			}
			return true; // wait out the grace
		}
		// Owner is back / present: clear the offline grace (a manual brb window is left until it elapses).
		if ((state.graceUntil != 0) && (state.graceUntil <= now))
		{
			state.graceUntil = 0;
		}

		// Don't act while casting; let the current spell finish.
		if (buddy.isCastingNow())
		{
			return true;
		}

		final boolean ownerInRange = buddy.calculateDistance2D(owner) <= SUPPORT_RANGE;

		// 1) Heal the owner up to ~half health, then self if low.
		if (ownerInRange && !owner.isDead() && (owner.getCurrentHpPercent() < OWNER_HEAL_PERCENT) && tryHeal(state, buddy, owner))
		{
			return true;
		}
		if ((buddy.getCurrentHpPercent() < SELF_HEAL_PERCENT) && tryHeal(state, buddy, buddy))
		{
			return true;
		}

		// 2) Keep the owner (and self) buffed.
		if (ownerInRange && maintainBuffs(state, buddy, true))
		{
			return true;
		}

		// 3) MP watch: warn, and when it is safe (owner not in combat, no monster near) sit to recover.
		if (handleMp(state, buddy, owner, now))
		{
			return true;
		}

		// 4) Default: keep following the owner.
		if (state.following && !buddy.isSitting() && (buddy.calculateDistance2D(owner) > FOLLOW_RANGE))
		{
			ensureFollow(state, owner);
		}
		return true;
	}

	/** Heals a target with the best heal the buddy knows. @return true if a heal was cast. */
	private boolean tryHeal(Buddy state, Player buddy, Player target)
	{
		final Skill heal = bestHeal(state, buddy);
		if ((heal == null) || (buddy.getCurrentMp() < heal.getMpConsume()) || buddy.isSkillDisabled(heal))
		{
			return false;
		}
		standIfSitting(buddy);
		buddy.setTarget(target);
		buddy.doCast(heal);
		return true;
	}

	/**
	 * Casts the next buff that the owner (or self) is missing or that is about to expire. Casts at most one
	 * per call so the buddy works through its list over several ticks instead of locking up.
	 * @param withOwner {@code true} to buff the owner too (false = idle, self only)
	 * @return {@code true} if a buff was cast this call
	 */
	private boolean maintainBuffs(Buddy state, Player buddy, boolean withOwner)
	{
		final List<Skill> buffs = buffList(state, buddy);
		if (buffs.isEmpty())
		{
			return false;
		}
		// Owner first, then self.
		if (withOwner && (state.owner != null) && !state.owner.isDead())
		{
			final Skill missing = firstMissingBuff(buffs, buddy, state.owner);
			if (missing != null)
			{
				return castBuff(buddy, state.owner, missing);
			}
		}
		final Skill selfMissing = firstMissingBuff(buffs, buddy, buddy);
		if (selfMissing != null)
		{
			return castBuff(buddy, buddy, selfMissing);
		}
		return false;
	}

	private Skill firstMissingBuff(List<Skill> buffs, Player buddy, Player target)
	{
		for (Skill buff : buffs)
		{
			if (buddy.getCurrentMp() < buff.getMpConsume())
			{
				continue;
			}
			final BuffInfo info = target.getEffectList().getBuffInfoBySkillId(buff.getId());
			if ((info == null) || (info.getTime() <= BUFF_REFRESH_SECONDS))
			{
				return buff;
			}
		}
		return null;
	}

	private boolean castBuff(Player buddy, Player target, Skill buff)
	{
		if (buddy.isSkillDisabled(buff))
		{
			return false;
		}
		standIfSitting(buddy);
		buddy.setTarget(target);
		buddy.doCast(buff);
		return true;
	}

	/**
	 * MP sustain: warn the owner once it dips, and when it is safe (owner not fighting, no monster near) sit
	 * to recover; stand once MP is back up or a threat appears.
	 * @return {@code true} if the buddy is resting (caller should not also follow)
	 */
	private boolean handleMp(Buddy state, Player buddy, Player owner, long now)
	{
		final int mp = buddy.getCurrentMpPercent();
		final boolean danger = owner.isInCombat() || isMonsterNear(buddy);
		if (buddy.isSitting())
		{
			if ((mp >= MP_OK_PERCENT) || danger)
			{
				buddy.standUp();
				return false;
			}
			return true; // still recovering
		}
		if (mp < MP_LOW_PERCENT)
		{
			if ((now - state.lastMpWarn) >= MP_WARN_COOLDOWN)
			{
				whisper(buddy, owner, "i'm low on mp");
				state.lastMpWarn = now;
			}
			if (!danger && buddy.isInsideRadius2D(owner.getX(), owner.getY(), owner.getZ(), SUPPORT_RANGE))
			{
				buddy.getAI().setIntention(Intention.IDLE);
				buddy.sitDown();
				return true;
			}
		}
		return false;
	}

	// ===== Helpers =====

	/** Lazily collect the buddy's maintainable buffs: active, continuous, beneficial, not the heal. */
	private List<Skill> buffList(Buddy state, Player buddy)
	{
		if (state.buffs == null)
		{
			final List<Skill> list = new ArrayList<>();
			for (Skill skill : buddy.getAllSkills())
			{
				if (skill.isPassive() || skill.isToggle() || !skill.isActive() || skill.isDebuff())
				{
					continue;
				}
				if (skill.isContinuous() && (skill.getEffectPoint() >= 0) && !isHealSkill(skill.getId()))
				{
					list.add(skill);
				}
			}
			state.buffs = list;
		}
		return state.buffs;
	}

	private Skill bestHeal(Buddy state, Player buddy)
	{
		if (state.heal == null)
		{
			for (int id : HEAL_PRIORITY)
			{
				final Skill known = buddy.getKnownSkill(id);
				if (known != null)
				{
					state.heal = known;
					break;
				}
			}
		}
		return state.heal;
	}

	private static boolean isHealSkill(int id)
	{
		for (int healId : HEAL_PRIORITY)
		{
			if (healId == id)
			{
				return true;
			}
		}
		return false;
	}

	/** Re-issues the follow intention toward the owner. */
	private void ensureFollow(Buddy state, Player owner)
	{
		if (!state.following || (state.npc == null) || state.npc.isDead())
		{
			return;
		}
		state.npc.setRunning();
		state.npc.getAI().setIntention(Intention.FOLLOW, owner);
	}

	private static void standIfSitting(Player buddy)
	{
		if (buddy.isSitting())
		{
			buddy.standUp();
		}
	}

	private boolean isPartiedWith(Buddy state, Player owner)
	{
		return (owner != null) && state.npc.isInParty() && owner.isInParty() && (state.npc.getParty() == owner.getParty());
	}

	private static boolean isMonsterNear(Player buddy)
	{
		for (Monster monster : World.getInstance().getVisibleObjectsInRange(buddy, Monster.class, DANGER_RANGE))
		{
			if (!monster.isDead())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Releases the buddy from its party and (when appropriate) sends it home / despawns it.
	 * @param state the buddy
	 * @param graceful {@code true} for an owner-requested disband (just leave + despawn); {@code false} for an
	 *            involuntary end (party gone, owner offline past grace, buddy dead)
	 */
	private void release(Buddy state, boolean graceful)
	{
		final Player buddy = state.npc;
		state.owner = null;
		state.graceUntil = 0;
		try
		{
			if (buddy.isInParty())
			{
				buddy.getParty().removePartyMember(buddy, PartyMessageType.LEFT);
			}
		}
		catch (Exception e)
		{
			// best effort
		}
		PhantomManager.getInstance().setBuddyEngaged(buddy, false); // proximity despawn may reclaim it now
		// Hand the body back to PhantomManager: it will be cleaned up by the normal proximity deactivate, but
		// if the buddy is far from its home town (followed the owner out) despawn it now so it doesn't linger.
		PhantomManager.getInstance().despawnBuddy(buddy);
		_buddies.remove(buddy.getObjectId());
	}

	private void whisper(Player buddy, Player owner, String text)
	{
		if ((owner != null) && owner.isOnline())
		{
			owner.sendPacket(new CreatureSay(buddy, ChatType.WHISPER, buddy.getName(), text));
		}
	}

	/** Speaks a line into the buddy's party channel so every member sees it (used when ordered via party chat). */
	private void partyChat(Player buddy, String text)
	{
		if (buddy.isInParty())
		{
			buddy.getParty().broadcastCreatureSay(new CreatureSay(buddy, ChatType.PARTY, buddy.getName(), text), buddy);
		}
	}

	/**
	 * Teleports a buddy to a destination and finalizes it for a clientless player. {@code teleToLocation} only
	 * calls {@code onTeleported()} (which re-spawns + broadcasts) for players whose client is <i>detached</i>;
	 * a buddy has no client at all, so without this call it would be {@code decayMe()}'d but never re-spawned -
	 * it shows on the radar but renders for nobody. We finalize it ourselves and re-broadcast its appearance,
	 * then re-grab follow once it arrives.
	 */
	private void teleportBuddy(Buddy state, Player owner, Location destination)
	{
		final Player buddy = state.npc;
		buddy.abortCast();
		buddy.teleToLocation(destination);
		buddy.onTeleported(); // no-op if teleToLocation already finalized it; required for the clientless buddy
		buddy.broadcastUserInfo();
		if (state.following)
		{
			ThreadPool.schedule(() -> ensureFollow(state, owner), 1500); // re-grab follow once arrived
		}
	}

	private Location matchDestination(String text)
	{
		final String normalized = normalize(text);
		// Direct contains: "going to ruins of agony" contains "ruins of agony".
		for (Map.Entry<String, Location> entry : _destinations.entrySet())
		{
			if (normalized.contains(entry.getKey()))
			{
				return entry.getValue();
			}
		}
		return null;
	}

	/** Lowercases and strips common filler ("the", "town/village of", punctuation) for forgiving matching. */
	private static String normalize(String value)
	{
		return value.toLowerCase().replace("town of", " ").replace("village of", " ").replace("the ", " ").replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
	}

	private static boolean containsAny(String text, String... needles)
	{
		for (String needle : needles)
		{
			if (text.contains(needle))
			{
				return true;
			}
		}
		return false;
	}

	public static PhantomBuddyManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomBuddyManager INSTANCE = new PhantomBuddyManager();
	}
}
