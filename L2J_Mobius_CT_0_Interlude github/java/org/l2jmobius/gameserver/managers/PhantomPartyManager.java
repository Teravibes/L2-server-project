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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.groups.PartyDistributionType;
import org.l2jmobius.gameserver.model.groups.PartyMessageType;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/**
 * Recruited combat-party brain.
 * <p>
 * When a real player shouts an LFM/LFP, {@link FakePlayerChatManager} parses the wanted roles and calls
 * {@link #recruitFromShout}. For each open slot this manager spawns a level-matched combat phantom of that role
 * (via {@link PhantomManager#spawnPartyMember}) a little way off, out of sight, then walks it to the player and
 * has it ask for an invite - so it reads as a real player who saw the shout and jogged over, not an NPC popping
 * into existence. The invite is auto-accepted server-side ({@code RequestJoinParty} -> {@link #onInvited}).
 * <p>
 * Once partied, a member follows the leader and (default) <b>assists</b> the leader's target - the party focus-
 * fires together; an "attack freely" order flips it to hunt nearby mobs on its own. Healers heal the hurt party
 * member and raise the dead; buffers keep the whole party buffed. Everything is rule-based and works with the
 * LLM brain offline; free-form orders fall through to the brain for a natural reply only when it is online.
 * @author Claude
 */
public class PhantomPartyManager
{
	private static final Logger LOGGER = Logger.getLogger(PhantomPartyManager.class.getName());

	/**
	 * Raid combat trace. When on, the manager logs (to the gameserver log) a periodic snapshot of every party with a
	 * raid boss engaged - boss HP and who it's hating, plus each member's role/HP/MP/action - and event lines for
	 * taunts, heals, res and deaths. Raid-only, so it's silent during normal farming. Toggle live with
	 * {@code //phantom debug on|off}. Defaults on so the first pull after a rebuild is already traced.
	 */
	public static volatile boolean DEBUG = true;
	private static final long RAID_LOG_INTERVAL = 1500; // throttle for the periodic snapshot
	private long _lastRaidLog;

	private static final long TICK_INTERVAL = 1000;
	private static final int MAX_PER_REQUEST = 8; // a full party (minus the leader) from one shout, slots permitting
	private static final int APPROACH_MIN = 700; // how far out a recruit spawns before walking in (close, so a raid party assembles fast)
	private static final int APPROACH_MAX = 1300;
	private static final long ARRIVE_RANGE = 220; // close enough to the leader to say "here, inv me"
	private static final long RECRUIT_TIMEOUT = 150000; // give up + despawn if never invited within this window
	private static final long SPAWN_STAGGER = 500; // small gap so arrivals don't pop on one tile, but a full comp still assembles in a few seconds
	private static final int FOLLOW_RANGE = 250;
	private static final int LEASH_RANGE = 1400; // free-hunting member is pulled back if it strays this far
	private static final long TRAVEL_GRACE = 180000; // after a "go to X" order, wait at the spot this long for the leader
	private static final int REGROUP_RANGE = 1500; // ...resuming normal follow once the leader arrives within this
	private static final int SUPPORT_RANGE = 900; // heal/buff/res only when the target is this close
	private static final int ASSIST_MAX_RANGE = 2200; // don't assist a mob the leader targeted across the map
	private static final int DANGER_RANGE = 700;
	private static final int OWNER_HEAL_PERCENT = 60;
	private static final int SELF_HEAL_PERCENT = 45;
	private static final int RAID_HEAL_PERCENT = 80; // under a raid, heal party members pre-emptively at this HP% (boss spikes outrun reactive 60% healing)
	private static final int RAID_TANK_HEAL_PERCENT = 90; // ...and keep the tank topped this high, since it soaks the boss
	private static final int CRITICAL_HEAL_PERCENT = 50; // a member this low is an emergency - heal it before topping the tank
	private static final int BUFF_REFRESH_SECONDS = 20;
	private static final int CASTER_CAST_RANGE = 650; // a nuker holds this far from the assist target so it nukes, never melees
	private static final int CASTER_RANGE_TOLERANCE = 150; // ...with this slack, so it isn't constantly re-positioning
	private static final int CASTER_MIN_MP = 20; // below this percent a nuker stops casting and rests instead of meleeing
	private static final int CASTER_SPREAD_STEP = 110; // lateral spacing between casters so several don't stack and eat one AoE
	private static final int MP_REST_SIT = 30; // a caster sits to recover when it drops to ~this and is safe
	private static final int MP_REST_STAND = 100; // and stays seated until MP is fully restored
	private static final long STAND_SUPPRESS = 30000; // a "stand" order keeps it on its feet this long before auto-rest resumes
	private static final long OFFLINE_GRACE = 120000;
	private static final long BRB_GRACE = 360000;
	private static final long CORPSE_GRACE = 60000; // keep a fallen member as a raisable corpse this long before despawning it
	private static final int RES_SKILL_ID = 1016; // Resurrection (granted to healers on spawn)
	private static final int AGGRESSION_ID = 28; // single-target taunt (knight tree) - "provokes a target to attack"
	private static final int AURA_OF_HATE_ID = 18; // AoE taunt (knight tree) - "provokes nearby enemies to attack"
	private static final int THREAT_SCAN_RANGE = 1000; // how far a tank looks for a mob loose on a squishy party member
	private static final long TAUNT_REFRESH_MS = 6000; // while already top of aggro, only re-taunt this often (its auto-attacks hold hate; spamming taunt just drains MP)
	private static final int RECHARGE_ID = 1013; // Recharge - an Elder/Shillien Elder refills a party caster's MP
	private static final int RECHARGE_MP_PERCENT = 45; // recharge a mana-user below this MP%

	// LLM brain bridge (optional): a free-form whisper/party-chat line that isn't a recognised command is sent
	// here for a natural reply that may carry an action tag, executed and stripped before the member speaks.
	private static final HttpClient BRAIN_HTTP = HttpClient.newHttpClient();
	private static final String BRAIN_URL = "http://127.0.0.1:5000/chat";
	private static final Pattern TAG_ASSIST = Pattern.compile("\\[\\[\\s*ASSIST\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_FREE = Pattern.compile("\\[\\[\\s*FREE\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_FOLLOW = Pattern.compile("\\[\\[\\s*(FOLLOW|GATHER)\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_STAY = Pattern.compile("\\[\\[\\s*(STAY|HOLD)\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_DISBAND = Pattern.compile("\\[\\[\\s*DISBAND\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_TP = Pattern.compile("\\[\\[\\s*TP\\s*:\\s*([^\\]]+?)\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern TAG_GRACE = Pattern.compile("\\[\\[\\s*GRACE\\s*:\\s*(\\d+)\\s*\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern ANY_TAG = Pattern.compile("\\[\\[[^\\]]*\\]\\]");

	// Heal skills a support member may know, strongest first (same table the buddy manager uses).
	private static final int[] HEAL_PRIORITY =
	{
		1218, // Greater Battle Heal
		1217, // Greater Heal
		1015, // Battle Heal
		1011 // Heal
	};

	/** Per-member state. */
	private static class Member
	{
		final Player npc;
		final PartyRole role;
		Player owner; // the recruiter; the party bond forms when the invite is accepted
		boolean partied;
		long deadSince; // 0 while alive; set when first seen dead so the corpse persists for a battle-res window
		boolean pullOrdered; // TANK only: the leader ordered the tank to initiate a raid pull ("tank attack")
		long pullSince; // when that order was given - release the rest of the party shortly after even if aggro reads flaky
		boolean assist = true; // assist the leader's target (default) vs. free-hunt
		boolean following = true;
		boolean reminded; // already whispered "here, inv me" while waiting
		long pendingSince; // spawn time; despawn if never invited within RECRUIT_TIMEOUT
		long graceUntil;
		List<Skill> buffs; // lazy
		Skill heal; // lazy
		Skill res; // lazy
		Skill aggression; // lazy (TANK): single-target taunt
		Skill auraOfHate; // lazy (TANK): AoE taunt
		boolean tauntLookedUp; // whether the two taunt skills have been resolved from the known list yet
		long lastTauntAt; // TANK: when it last taunted, so it doesn't re-taunt every tick and drain its own MP
		Skill recharge; // lazy (support): Recharge, to refill party casters' MP
		boolean rechargeLookedUp;
		int lastX; // follow-stuck watchdog
		int lastY;
		int stuckTicks;
		long castSince; // abort a wedged cast instead of freezing the member
		long travelUntil; // sent ahead to a destination; wait there (don't follow-yank back) until the leader arrives
		boolean rebuffing; // "rebuff" order: recast the full kit regardless of time left
		int rebuffIdx; // which buff in the list is next for the current target
		List<Player> rebuffQueue; // targets still owed a full kit (leader only, a named member, or the whole party)
		boolean healNow; // "heal me" order: heal the leader once even at full HP
		Skill pendingBuff; // "give me X" order: a specific buff to cast on the leader next tick
		long noSitUntil; // "stand" order: don't auto-sit for MP until this time (so it doesn't pop straight back down)

		Member(Player npc, PartyRole role)
		{
			this.npc = npc;
			this.role = role;
		}

		boolean isSupport()
		{
			return role.isSupport();
		}
	}

	private final ConcurrentHashMap<Integer, Member> _members = new ConcurrentHashMap<>();
	// Raid pull control: owner objIds whose party has been released to attack the current raid fight (the tank engaged
	// it, or the leader said "all attack"). Not present = the party holds against a raid until the tank initiates. It
	// is a per-fight flag (not per-target) so the party can freely switch between the boss and its adds once engaged;
	// it clears when no raid is engaged near the party (the fight is over), re-arming the hold for the next pull.
	private final Set<Integer> _released = ConcurrentHashMap.newKeySet();
	private static final long PULL_RELEASE_MS = 2500; // release the party this long after the tank engages, even if the aggro read lags
	private boolean _ticking = false;

	protected PhantomPartyManager()
	{
	}

	// ===== Recruitment =====

	/**
	 * Spawns and walks in one level-matched member per wanted role, up to the party's free slots. Staggered so a
	 * multi-role request trickles in like separate people answering the shout.
	 */
	public void recruitFromShout(Player leader, List<PhantomManager.Recruit> recruits, int level)
	{
		if ((leader == null) || (recruits == null) || recruits.isEmpty())
		{
			return;
		}
		int slots = freeSlots(leader);
		if (slots <= 0)
		{
			leader.sendPacket(new CreatureSay(leader, ChatType.WHISPER, "Party", "your party is full"));
			return;
		}
		// Optional requested level (e.g. "lfm buffer lvl 57"), clamped; otherwise match the recruiter's level.
		final int memberLevel = (level > 0) ? Math.max(1, Math.min(80, level)) : leader.getLevel();
		int spawned = 0;
		for (PhantomManager.Recruit recruit : recruits)
		{
			if ((spawned >= slots) || (spawned >= MAX_PER_REQUEST))
			{
				break;
			}
			final PhantomManager.Recruit wanted = recruit;
			final int order = spawned;
			ThreadPool.schedule(() -> spawnAndApproach(leader, wanted, memberLevel), 400L + (order * SPAWN_STAGGER) + Rnd.get(300));
			spawned++;
		}
	}

	/** Open party slots a recruiter can still fill, minus members already on the way to them. */
	private int freeSlots(Player leader)
	{
		final int cap = leader.isInParty() ? (9 - leader.getParty().getMemberCount()) : 8;
		int incoming = 0;
		for (Member m : _members.values())
		{
			if ((m.owner == leader) && !m.partied)
			{
				incoming++;
			}
		}
		return cap - incoming;
	}

	/** Spawns a member out of sight near the leader and starts it walking over. */
	private void spawnAndApproach(Player leader, PhantomManager.Recruit recruit, int level)
	{
		if ((leader == null) || !leader.isOnline())
		{
			return;
		}
		final double angle = Rnd.nextDouble() * 2 * Math.PI;
		final int distance = Rnd.get(APPROACH_MIN, APPROACH_MAX);
		final Location anchor = new Location(leader.getX() + (int) (Math.cos(angle) * distance), leader.getY() + (int) (Math.sin(angle) * distance), leader.getZ());
		final Player npc = PhantomManager.getInstance().spawnPartyMember(anchor, level, recruit.role, recruit.classId);
		if (npc == null)
		{
			return;
		}
		final Member member = new Member(npc, recruit.role);
		member.owner = leader;
		member.pendingSince = System.currentTimeMillis();
		_members.put(npc.getObjectId(), member);
		startTicking();
		npc.setRunning();
		npc.getAI().setIntention(Intention.FOLLOW, leader);
		// Answer the shout so it feels like a person reacting to the LFM, then it jogs over.
		shout(npc, omwLine(recruit.role));
	}

	private static String omwLine(PartyRole role)
	{
		switch (role)
		{
			case HEALER:
			{
				return Rnd.nextBoolean() ? "i can heal, omw" : "healer here, coming";
			}
			case BUFFER:
			{
				return Rnd.nextBoolean() ? "can buff, otw" : "i'll buff, omw";
			}
			case TANK:
			{
				return Rnd.nextBoolean() ? "tank here, omw" : "i can tank, coming";
			}
			case NUKER:
			{
				return Rnd.nextBoolean() ? "nuker, omw" : "mage here, coming";
			}
			default:
			{
				return Rnd.nextBoolean() ? "omw" : "coming, inv me";
			}
		}
	}

	// ===== Invite (called by RequestJoinParty) =====

	/** @return {@code true} if the player is a recruited member waiting on / serving an invite from this manager. */
	public boolean isRecruit(Player player)
	{
		return (player != null) && _members.containsKey(player.getObjectId());
	}

	/**
	 * Binds a recruited member into the inviting player's party (a clientless phantom can't answer the invite
	 * dialog, so accept it server-side). Called by {@code RequestJoinParty}.
	 * @return {@code true} if the member joined
	 */
	public boolean onInvited(Player owner, Player member)
	{
		final Member state = _members.get(member.getObjectId());
		if ((state == null) || (owner == null) || member.isDead() || member.isInParty())
		{
			return false;
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
			member.joinParty(owner.getParty());
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Failed to party recruit " + member.getName() + ": " + e.getMessage());
			return false;
		}
		state.owner = owner;
		state.partied = true;
		state.following = true;
		state.assist = true;
		state.graceUntil = 0;
		ensureFollow(state);
		deliver(state, "in, " + (state.isSupport() ? "i'll keep us up" : "i'll assist you - say 'attack freely' to let me hunt"));
		startTicking();
		return true;
	}

	// ===== Commands (whisper + party chat, called from chat handlers) =====

	/** Handles a whisper to a recruited member; a deterministic command, else a natural brain reply. */
	public String handleWhisper(Player owner, Player member, String message)
	{
		final Member state = _members.get(member.getObjectId());
		if ((state == null) || (owner == null) || (message == null))
		{
			return null;
		}
		if (!tryCommand(state, owner, message))
		{
			askBrainAsync(state, owner, message); // free-form -> natural reply (brain), canned nudge if offline
		}
		return null;
	}

	/**
	 * A party-chat line drives the whole party: deterministic commands ("assist", "follow", "go to X") apply to
	 * every recruited member at once. Free-form chatter gets a reply from just ONE member, so a full party doesn't
	 * all answer the same line at once.
	 */
	public void handlePartyChat(Player speaker, String message)
	{
		if ((speaker == null) || (message == null) || !speaker.isInParty())
		{
			return;
		}
		final String lower = message.toLowerCase();
		final List<Member> mine = new ArrayList<>();
		final List<Member> named = new ArrayList<>();
		for (Player member : speaker.getParty().getMembers())
		{
			final Member state = _members.get(member.getObjectId());
			if ((state == null) || (state.owner != speaker))
			{
				continue;
			}
			mine.add(state);
			if (lower.contains(state.npc.getName().toLowerCase()))
			{
				named.add(state);
			}
		}
		if (mine.isEmpty())
		{
			return;
		}
		// Address one member by name and only that member reacts; otherwise the line drives the whole party.
		final List<Member> targets = named.isEmpty() ? mine : named;
		boolean matched = false;
		for (Member state : targets)
		{
			if (tryCommand(state, speaker, message))
			{
				matched = true;
			}
		}
		if (matched)
		{
			return;
		}
		// Free-form chatter: named members each answer; if nobody was named, one member replies so a full party
		// doesn't all talk over each other.
		if (!named.isEmpty())
		{
			for (Member state : named)
			{
				askBrainAsync(state, speaker, message);
			}
		}
		else
		{
			askBrainAsync(mine.get(Rnd.get(mine.size())), speaker, message);
		}
	}

	/**
	 * Deterministic group-command parser shared by whisper and party chat (works with the brain offline).
	 * @return {@code true} if the line matched a known command (and was acted on); {@code false} for free-form
	 *         text the caller should route to the brain
	 */
	private boolean tryCommand(Member state, Player owner, String message)
	{
		final String text = message.toLowerCase().trim();

		// A specific buff by name ("give me might", "ww pls") - checked first so "give me"/"gimme" aren't eaten by
		// the grace ("brb") matcher. Grant it if the buffer knows it, else say it doesn't have it.
		if (state.isSupport())
		{
			final String requested = PhantomBuffs.requestedBuff(text);
			if (requested != null)
			{
				final Skill known = PhantomBuffs.findKnown(buffs(state), requested);
				if (known != null)
				{
					state.pendingBuff = known;
					deliver(state, "sure, " + known.getName().toLowerCase());
				}
				else
				{
					deliver(state, "i don't have " + requested);
				}
				return true;
			}
		}

		// Raid pull control. Against a raid the party HOLDS until the tank initiates (see combatTick); these orders
		// drive that. Only the tank acknowledges out loud so a full party doesn't chatter over each other.
		// "tank attack" - order the tank to pull the boss; the rest follow once it has aggro.
		if (containsAny(text, "tank attack", "tank pull", "tank go", "tank engage", "tank initiate", "tank in", "pull it", "pull the boss", "pull boss", "initiate"))
		{
			if (state.role == PartyRole.TANK)
			{
				state.pullOrdered = true;
				state.pullSince = System.currentTimeMillis();
				deliver(state, "pulling - hold dps till i have aggro");
			}
			return true;
		}
		// "all attack" - everyone engages the current raid right now (skip the tank-initiate).
		if (containsAny(text, "all attack", "everyone attack", "all in", "open fire", "engage all", "attack the raid", "everyone in", "burn it"))
		{
			_released.add(owner.getObjectId());
			if (state.role == PartyRole.TANK)
			{
				state.pullOrdered = true;
				state.pullSince = System.currentTimeMillis();
				deliver(state, "all in!");
			}
			return true;
		}
		// "hold fire" - re-engage the hold (stop feeding the raid, wait for the tank).
		if (containsAny(text, "hold fire", "hold dps", "wait for tank", "fall back", "stop dps", "back off"))
		{
			_released.remove(owner.getObjectId());
			state.pullOrdered = false;
			if (state.role == PartyRole.TANK)
			{
				deliver(state, "holding");
			}
			return true;
		}

		// Free-hunt vs assist toggle.
		if (containsAny(text, "attack freely", "free hunt", "go wild", "ffa", "hunt freely", "do your own", "attack anything"))
		{
			setFree(state, true);
			deliver(state, "k, hunting on my own");
			return true;
		}
		if (containsAny(text, "assist", "focus", "help me", "on my target", "kill my target", "attack my"))
		{
			setFree(state, false);
			state.following = true;
			deliver(state, "k, assisting you");
			return true;
		}

		// Stand up on demand (interrupts an MP rest): "stand", "stand up", "get up", "on your feet".
		if (containsAny(text, "stand up", "stand", "get up", "on your feet", "feet"))
		{
			state.noSitUntil = System.currentTimeMillis() + STAND_SUPPRESS; // don't pop straight back down
			if (state.npc.isSitting())
			{
				state.npc.standUp();
			}
			deliver(state, "up");
			return true;
		}

		// Follow / hold.
		if (containsAny(text, "follow", "come with", "on me", "regroup", "gather", "stack", "stick with"))
		{
			state.following = true;
			ensureFollow(state);
			deliver(state, "coming");
			return true;
		}
		if (containsAny(text, "stay", "wait here", "hold", "stop", "halt"))
		{
			state.following = false;
			setFree(state, false);
			state.npc.getAI().setIntention(Intention.IDLE);
			deliver(state, "holding here");
			return true;
		}

		// Grace.
		if (containsAny(text, "brb", "be right back", "give me", "gimme", "afk", "one sec", "1 sec", "moment"))
		{
			state.graceUntil = System.currentTimeMillis() + BRB_GRACE;
			deliver(state, "np");
			return true;
		}

		// Disband / dismiss.
		if (containsAny(text, "disband", "leave party", "leave the party", "dismiss", "you can go", "thanks bye", "thx bye", "bye", "gl hf"))
		{
			deliver(state, "gl hf o/");
			ThreadPool.schedule(() -> release(state, true), 1200);
			return true;
		}

		// Status.
		if (containsAny(text, "status", "hp?", "mp?", "you ok", "u ok"))
		{
			deliver(state, "hp " + state.npc.getCurrentHpPercent() + "% / mp " + state.npc.getCurrentMpPercent() + "%");
			return true;
		}

		// Support on-demand orders actually do the thing now (not just acknowledge).
		if (state.isSupport())
		{
			// "buff all / everyone / the party": fully (re)buff every living party member, one buff per tick.
			if (containsAny(text, "buff all", "buff everyone", "buff every", "buff the party", "buff party", "buff us all", "buff whole party", "full buff all", "fully buff"))
			{
				startRebuff(state, partyTargets(state));
				deliver(state, "buffing everyone");
				return true;
			}
			// "buff <name>": fully buff one named party member (e.g. the tank by name).
			final Player named = findPartyMemberByName(state, text);
			if ((named != null) && text.contains("buff"))
			{
				startRebuff(state, new ArrayList<>(List.of(named)));
				deliver(state, "buffing " + named.getName());
				return true;
			}
			if (containsAny(text, "rebuff", "buff me", "buff us", "buff", "rebuf"))
			{
				startRebuff(state, new ArrayList<>(List.of(state.owner))); // recast the leader's whole kit over the next ticks
				deliver(state, "rebuffing");
				return true;
			}
			if (containsAny(text, "heal me", "heal us", "heal", "hp"))
			{
				state.healNow = true; // heal the leader once even if not hurt
				deliver(state, "healing you");
				return true;
			}
			if (containsAny(text, "res", "resurrect", "ress", "revive", "rez"))
			{
				deliver(state, "rezzing"); // the res loop raises a fallen member automatically
				return true;
			}
		}

		// "go to / tp <place>": teleport this member to a named gatekeeper spot (reuses the buddy destination data,
		// so the whole party can regroup at the same place the leader's gatekeeper drops them).
		final Map.Entry<String, Location> dest = PhantomBuddyManager.getInstance().findDestination(text);
		if ((dest != null) && isTravelOrder(text))
		{
			teleportMember(state, dest.getValue());
			deliver(state, "omw to " + dest.getKey());
			return true;
		}

		return false; // not a known command -> caller routes free-form text to the brain
	}

	/** {@code true} when the line is an explicit order to travel now, not just mentioning a place. */
	private static boolean isTravelOrder(String text)
	{
		return containsAny(text, "tp", "teleport", "port to", "port us", "go to", "lets go", "let's go", "take us", "head to", "lets head", "move to", "warp", "lets tp", "go there");
	}

	private void setFree(Member state, boolean free)
	{
		state.assist = !free;
		PhantomManager.getInstance().setRecruitHunting(state.npc, free);
		if (!free)
		{
			ensureFollow(state);
		}
	}

	/**
	 * Sends a free-form order to the LLM brain (PARTY mode) off the network thread for a natural reply that may
	 * carry an action tag (assist / free / follow / stay / tp / disband / grace), executed then stripped. Falls
	 * back to a canned nudge if the brain is offline, so the member always answers.
	 */
	private void askBrainAsync(Member state, Player owner, String message)
	{
		final int memberId = state.npc.getObjectId();
		final int ownerId = owner.getObjectId();
		ThreadPool.execute(() ->
		{
			final Member s = _members.get(memberId);
			final Player o = (Player) World.getInstance().findObject(ownerId);
			if ((s == null) || (o == null) || s.npc.isDead())
			{
				return;
			}
			String reply = callBrain(s.npc.getName(), o.getName(), s.role, isPartiedWith(s), message);
			if ((reply == null) || reply.isEmpty())
			{
				reply = "say: assist, attack freely, follow, hold, a place to tp, brb or bye";
			}
			else
			{
				reply = applyTags(reply, s, o, message);
			}
			if (reply.isEmpty())
			{
				return;
			}
			final Player npc = s.npc;
			if (npc.isInParty())
			{
				npc.getParty().broadcastCreatureSay(new CreatureSay(npc, ChatType.PARTY, npc.getName(), reply), npc);
			}
			else if (o.isOnline())
			{
				o.sendPacket(new CreatureSay(npc, ChatType.WHISPER, npc.getName(), reply));
			}
		});
	}

	/** Calls the brain bridge in PARTY mode; returns the raw reply (possibly with tags), or null if offline. */
	private String callBrain(String name, String ownerName, PartyRole role, boolean partied, String message)
	{
		try
		{
			final HttpRequest request = HttpRequest.newBuilder() //
				.uri(URI.create(BRAIN_URL)) //
				.timeout(Duration.ofSeconds(20)) //
				.header("X-FPC", name) //
				.header("X-Mode", "PARTY") //
				.header("X-Player", ownerName) //
				.header("X-Role", role.name().toLowerCase()) //
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

	/** Executes any action tag in the brain's reply and returns the cleaned, speakable text. */
	private String applyTags(String reply, Member state, Player owner, String playerMessage)
	{
		final boolean partied = isPartiedWith(state);
		if (partied && TAG_ASSIST.matcher(reply).find())
		{
			setFree(state, false);
			state.following = true;
		}
		if (partied && TAG_FREE.matcher(reply).find())
		{
			setFree(state, true);
		}
		if (partied && TAG_FOLLOW.matcher(reply).find())
		{
			state.following = true;
			ensureFollow(state);
		}
		if (partied && TAG_STAY.matcher(reply).find())
		{
			state.following = false;
			setFree(state, false);
			state.npc.getAI().setIntention(Intention.IDLE);
		}
		final Matcher grace = TAG_GRACE.matcher(reply);
		if (grace.find())
		{
			state.graceUntil = System.currentTimeMillis() + (Math.min(30, Integer.parseInt(grace.group(1))) * 60000L);
		}
		final Matcher tp = TAG_TP.matcher(reply);
		if (partied && tp.find())
		{
			final Map.Entry<String, Location> dest = PhantomBuddyManager.getInstance().findDestination(tp.group(1));
			if (dest != null)
			{
				teleportMember(state, dest.getValue());
			}
		}
		if (partied && TAG_DISBAND.matcher(reply).find())
		{
			ThreadPool.schedule(() -> release(state, true), 1000);
		}
		return ANY_TAG.matcher(reply).replaceAll("").trim();
	}

	/**
	 * Teleports a recruited member and finalizes it for a clientless player ({@code onTeleported} re-spawns +
	 * broadcasts; without it the member shows on radar but renders for nobody), then re-grabs follow on arrival.
	 */
	private void teleportMember(Member state, Location destination)
	{
		final Player npc = state.npc;
		npc.abortCast();
		npc.teleToLocation(destination);
		npc.onTeleported();
		npc.broadcastUserInfo();
		// Commit to the destination: wait here for the leader rather than letting the follow watchdog immediately
		// teleport us back to where the leader still is (that was the "everyone tped to the player's spot" bug).
		state.travelUntil = System.currentTimeMillis() + TRAVEL_GRACE;
	}

	/**
	 * Keeps a member moving to {@code target}, re-kicking a stalled follow and teleporting it to catch up if it
	 * gets stuck or falls a long way behind - so a recruit never says "coming" while standing still.
	 */
	private void driveFollow(Member state, Player target)
	{
		final Player npc = state.npc;
		if (npc.isSitting())
		{
			return;
		}
		final double dist = npc.calculateDistance2D(target);
		if (dist <= FOLLOW_RANGE)
		{
			state.stuckTicks = 0;
			state.lastX = npc.getX();
			state.lastY = npc.getY();
			return;
		}
		if ((Math.abs(npc.getX() - state.lastX) + Math.abs(npc.getY() - state.lastY)) < 30)
		{
			state.stuckTicks++;
		}
		else
		{
			state.stuckTicks = 0;
		}
		if ((state.stuckTicks >= 4) || (dist > 2800))
		{
			npc.abortCast();
			npc.teleToLocation(new Location(target.getX() + Rnd.get(-60, 60), target.getY() + Rnd.get(-60, 60), target.getZ()));
			npc.onTeleported();
			npc.broadcastUserInfo();
			state.stuckTicks = 0;
		}
		else
		{
			npc.setRunning();
			npc.getAI().setIntention(Intention.FOLLOW, target);
		}
		state.lastX = npc.getX();
		state.lastY = npc.getY();
	}

	/** Speaks a member reply after a short human-like pause, on party chat if partied else a whisper. */
	private void deliver(Member state, String text)
	{
		if ((text == null) || text.isEmpty())
		{
			return;
		}
		final long delay = 800 + Rnd.get(1200);
		ThreadPool.schedule(() ->
		{
			final Player npc = state.npc;
			if (npc.isDead())
			{
				return;
			}
			if (npc.isInParty())
			{
				npc.getParty().broadcastCreatureSay(new CreatureSay(npc, ChatType.PARTY, npc.getName(), text), npc);
			}
			else if ((state.owner != null) && state.owner.isOnline())
			{
				state.owner.sendPacket(new CreatureSay(npc, ChatType.WHISPER, npc.getName(), text));
			}
		}, delay);
	}

	private void shout(Player npc, String text)
	{
		final CreatureSay cs = new CreatureSay(npc, ChatType.SHOUT, npc.getName(), text);
		for (Player player : World.getInstance().getPlayers())
		{
			player.sendPacket(cs);
		}
	}

	// ===== Slow housekeeping tick (called by PhantomManager supervisor ~5s) =====

	public void supervise(Player member)
	{
		final Member state = _members.get(member.getObjectId());
		if ((state != null) && member.isDead())
		{
			handleDead(state, System.currentTimeMillis());
		}
	}

	/**
	 * A fallen member is kept as a corpse so a healer can battle-res it, instead of despawning the instant it dies
	 * (the old behaviour that made the party melt permanently on a long fight). Each call: auto-accepts a pending
	 * resurrection (a clientless phantom can't click the confirm dialog, so we answer "yes" for it), starts the
	 * corpse window on first death, and only releases once that window elapses or the party/owner is gone.
	 */
	private void handleDead(Member state, long now)
	{
		final Player npc = state.npc;
		// A healer's Resurrection lands as a revive *request* (ConfirmDlg) the corpse must accept - do it server-side.
		if (npc.isReviveRequested())
		{
			npc.reviveAnswer(1); // it stands on the next tick, where deadSince is cleared and it rejoins
			return;
		}
		if (state.deadSince == 0)
		{
			state.deadSince = now; // open the res window
			if (DEBUG)
			{
				final Monster boss = engagedRaid(state);
				dbg("DEATH " + roleLabel(npc) + " '" + npc.getName() + "' died" + ((boss != null) ? (" | boss '" + boss.getName() + "' was hating " + describe(boss.getMostHated())) : ""));
			}
			return;
		}
		// Window elapsed, or there's no longer a party/owner to be raised by: let the corpse go.
		if (((now - state.deadSince) >= CORPSE_GRACE) || !isPartiedWith(state) || (state.owner == null) || !state.owner.isOnline())
		{
			release(state, false);
		}
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
		if (DEBUG && ((now - _lastRaidLog) >= RAID_LOG_INTERVAL))
		{
			_lastRaidLog = now;
			try
			{
				logRaidSnapshot();
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Raid snapshot log error: " + e.getMessage());
			}
		}
		for (Member state : _members.values())
		{
			final Player npc = state.npc;
			try
			{
				if ((npc == null) || (World.getInstance().findObject(npc.getObjectId()) == null))
				{
					_members.remove(npc == null ? -1 : npc.getObjectId());
					continue;
				}
				if (npc.isDead())
				{
					handleDead(state, now); // keep the corpse for a res window instead of despawning on the spot
					continue;
				}
				if (state.deadSince != 0)
				{
					// Stood back up since last tick (a healer raised it): clear the corpse timer and rejoin the fight.
					state.deadSince = 0;
					ensureFollow(state);
				}
				if (!state.partied)
				{
					pendingTick(state, now);
					continue;
				}
				serve(state, now);
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Party tick error for " + (npc == null ? "?" : npc.getName()) + ": " + e.getMessage());
			}
		}
	}

	// ===== Raid combat trace (toggle with //phantom debug on|off; raid-only so it's silent during farming) =====

	private void dbg(String line)
	{
		if (DEBUG)
		{
			LOGGER.info("PARTY-RAID " + line);
		}
	}

	/** One snapshot per engaged party: the boss (HP + who it's hating) and every member's role/HP/MP/action. */
	private void logRaidSnapshot()
	{
		final Set<Integer> seenParties = new HashSet<>();
		for (Member state : _members.values())
		{
			final Player owner = state.owner;
			if (!state.partied || (owner == null) || !owner.isOnline() || !owner.isInParty())
			{
				continue;
			}
			if (!seenParties.add(owner.getObjectId()))
			{
				continue; // already logged this party this pass
			}
			final Monster boss = engagedRaid(state);
			if (boss == null)
			{
				continue;
			}
			dbg("=== boss '" + boss.getName() + "' hp=" + boss.getCurrentHpPercent() + "% hating=" + describe(boss.getMostHated()) + " ===");
			for (Player member : owner.getParty().getMembers())
			{
				dbg("  " + roleLabel(member) + " '" + member.getName() + "' hp=" + member.getCurrentHpPercent() + "% mp=" + member.getCurrentMpPercent() + "% " + action(member));
			}
		}
	}

	/** A live raid boss in combat near this member, or {@code null} - the boss to report on / the trigger for the trace. */
	private Monster engagedRaid(Member state)
	{
		for (Monster mob : World.getInstance().getVisibleObjectsInRange(state.npc, Monster.class, ASSIST_MAX_RANGE))
		{
			if (!mob.isDead() && mob.isRaid() && mob.isInCombat())
			{
				return mob;
			}
		}
		return null;
	}

	/** Role tag for a party member: its recruited role, or PLAYER for a real human (the leader). */
	private String roleLabel(Player member)
	{
		final Member m = _members.get(member.getObjectId());
		return (m != null) ? m.role.name() : "PLAYER";
	}

	/** Short label for who a creature is (name + role), used for the boss's most-hated target. */
	private String describe(Creature who)
	{
		if (who == null)
		{
			return "none";
		}
		return who.isPlayer() ? (roleLabel((Player) who) + " '" + who.getName() + "'") : who.getName();
	}

	private static String action(Player npc)
	{
		if (npc.isDead())
		{
			return "DEAD";
		}
		if (npc.isSitting())
		{
			return "sitting";
		}
		if (npc.isCastingNow())
		{
			return "casting";
		}
		if (npc.isAttackingNow())
		{
			return "attacking";
		}
		return "idle";
	}

	/** A recruit on its way over: keep walking to the leader, ask for the invite on arrival, give up on timeout. */
	private void pendingTick(Member state, long now)
	{
		final Player npc = state.npc;
		final Player owner = state.owner;
		if ((owner == null) || !owner.isOnline() || ((now - state.pendingSince) > RECRUIT_TIMEOUT))
		{
			if ((owner != null) && owner.isOnline() && !state.reminded)
			{
				deliver(state, "guess not, gl");
			}
			release(state, false);
			return;
		}
		if (npc.calculateDistance2D(owner) <= ARRIVE_RANGE)
		{
			if (!state.reminded)
			{
				state.reminded = true;
				if (owner.isOnline())
				{
					owner.sendPacket(new CreatureSay(npc, ChatType.WHISPER, npc.getName(), "here, inv me"));
				}
			}
		}
		else if (state.following)
		{
			driveFollow(state, owner); // keep closing the distance; teleport in if pathing stalls
		}
	}

	/** One service step for a partied member. @return {@code false} if released. */
	private boolean serve(Member state, long now)
	{
		final Player npc = state.npc;
		final Player owner = state.owner;

		if (!isPartiedWith(state))
		{
			release(state, false);
			return false;
		}

		// Owner offline: hold on a grace window (extended by "brb"), then let go.
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
			return true;
		}
		if ((state.graceUntil != 0) && (state.graceUntil <= now))
		{
			state.graceUntil = 0;
		}

		// Cast watchdog: a clientless caster can wedge with its casting flag stuck; abort an over-long cast so the
		// member recovers instead of freezing (stops buffing/healing AND following).
		if (npc.isCastingNow())
		{
			if (state.castSince == 0)
			{
				state.castSince = now;
			}
			else if ((now - state.castSince) > 6000)
			{
				npc.abortCast();
				state.castSince = 0;
			}
			return true;
		}
		state.castSince = 0;

		// Sent ahead to a destination by a "go to X" order: wait there for the leader instead of being pulled
		// back by the follow watchdog. Resume normal behaviour once the leader arrives near us (or grace elapses).
		if (state.travelUntil > now)
		{
			if (npc.calculateDistance2D(owner) > REGROUP_RANGE)
			{
				return true; // hold at the destination
			}
			state.travelUntil = 0;
		}

		if (state.isSupport())
		{
			supportTick(state, now);
		}
		else
		{
			combatTick(state);
		}
		return true;
	}

	// ===== Combat roles: assist the leader's target, or free-hunt =====

	private void combatTick(Member state)
	{
		final Player npc = state.npc;
		final Player owner = state.owner;

		if (state.assist)
		{
			final WorldObject t = owner.getTarget();
			final boolean haveTarget = (t instanceof Monster) && !((Monster) t).isDead() && (owner.calculateDistance2D(t) <= ASSIST_MAX_RANGE);

			// Raid pull control: against a RAID target the party HOLDS until the tank initiates. The tank engages only
			// when ordered ("tank attack"); once it has the boss's threat the rest of the party is released to assist
			// (and can then freely switch between the boss and its adds). Normal mobs are unaffected. When no raid is
			// targeted AND none is engaged near the party, the fight is over - re-arm the hold for the next pull.
			final boolean raidTarget = haveTarget && ((Monster) t).isRaid();
			if (!raidTarget)
			{
				// Re-arm the hold once the fight is over, but only pay for the raid scan if we're actually in a pull
				// state (so normal farming, the common case, never scans here).
				if ((state.pullOrdered || _released.contains(owner.getObjectId())) && (engagedRaid(state) == null))
				{
					state.pullOrdered = false;
					_released.remove(owner.getObjectId());
				}
			}
			else if (!mayAttackRaid(state, (Monster) t))
			{
				holdForPull(state); // wait near the leader, don't feed the raid yet
				return;
			}

			if (haveTarget && state.role.mage)
			{
				// A nuker assists by casting from range - it must NEVER be given a physical ATTACK intention, which
				// walked it into melee to auto-hit the mob even on a full MP bar (it has no auto-attack action, so it
				// just stood there swinging its staff instead of nuking). Hold at cast range; AutoUse fires the nukes.
				// Out of MP, drop the target and fall through to the rest path so it sits to recharge instead of meleeing.
				if (npc.getCurrentMpPercent() >= CASTER_MIN_MP)
				{
					standIfSitting(npc);
					npc.setTarget(t);
					positionCaster(npc, (Monster) t);
					return; // AutoUse fires the role's nukes on this target
				}
				npc.setTarget(null);
			}
			else if (haveTarget)
			{
				standIfSitting(npc);
				// A tank actively holds threat: pin a raid boss with taunts, or yank back any mob that slipped onto a
				// squishy. If it taunted this tick, skip the attack re-issue and resume swinging next tick.
				if ((state.role == PartyRole.TANK) && maintainThreat(state, (Monster) t))
				{
					return;
				}
				if ((npc.getTarget() != t) || !npc.isInCombat())
				{
					npc.setTarget(t);
					npc.setRunning();
					npc.getAI().setIntention(Intention.ATTACK, t);
				}
				return; // AutoUse fires the role's skills/shots on this target
			}
			// Nothing to assist (or a nuker that just ran dry): a caster low on MP sits to recover when safe;
			// otherwise stick with the leader.
			if (restForMp(state))
			{
				return;
			}
			if (state.following)
			{
				driveFollow(state, owner);
			}
			return;
		}

		// Free-hunt mode: AutoPlay drives target selection; just leash the member back if it wanders off.
		if (npc.calculateDistance2D(owner) > LEASH_RANGE)
		{
			PhantomManager.getInstance().setRecruitHunting(npc, false);
			ensureFollow(state);
			ThreadPool.schedule(() ->
			{
				if (!npc.isDead() && !state.assist)
				{
					PhantomManager.getInstance().setRecruitHunting(npc, true); // resume hunting once back near the party
				}
			}, 4000);
		}
	}

	/**
	 * Raid pull gate. Against a RAID target: the TANK attacks only once ordered ("tank attack"), and on engaging
	 * releases the rest of the party (records the raid as released for the owner) the moment it holds the boss's
	 * threat, or after a short grace if the aggro read lags. Every other member attacks only once that release is
	 * recorded - so DPS never pulls the boss off the tank at the open.
	 */
	private boolean mayAttackRaid(Member state, Monster raid)
	{
		final int ownerId = state.owner.getObjectId();
		if (_released.contains(ownerId))
		{
			return true; // party already engaged this raid fight - assist freely, including switching to the adds
		}
		if (state.role == PartyRole.TANK)
		{
			if (!state.pullOrdered)
			{
				return false; // tank waits for the "tank attack" order before pulling
			}
			if ((raid.getMostHated() == state.npc) || ((System.currentTimeMillis() - state.pullSince) > PULL_RELEASE_MS))
			{
				_released.add(ownerId); // tank has the boss's threat - release the rest of the party
			}
			return true;
		}
		return false; // non-tank waits for the tank to engage (release)
	}

	/** A member waiting on the tank's pull: stop attacking and stay near the leader (no target, so AutoUse stays quiet). */
	private void holdForPull(Member state)
	{
		final Player npc = state.npc;
		standIfSitting(npc);
		npc.setTarget(null);
		if (npc.calculateDistance2D(state.owner) > FOLLOW_RANGE)
		{
			if (state.following)
			{
				driveFollow(state, state.owner);
			}
		}
		else if (npc.isInCombat() || npc.isAttackingNow())
		{
			npc.abortAttack();
			npc.getAI().setIntention(Intention.IDLE);
		}
	}

	/**
	 * Holds a nuker at casting range of the assist target so AutoUse can nuke it, instead of letting a physical
	 * ATTACK intention drag it into melee. Walks in when too far, backs off when too close; stands still (lets the
	 * cast happen) once already in the band. Mirrors the free-roam mage positioning in {@link PhantomManager}.
	 */
	private void positionCaster(Player npc, Monster target)
	{
		if (npc.isCastingNow() || npc.isMovementDisabled())
		{
			return; // don't interrupt a cast or fight a stun
		}
		double dx = npc.getX() - target.getX();
		double dy = npc.getY() - target.getY();
		double distance = Math.hypot(dx, dy);
		if (Math.abs(distance - CASTER_CAST_RANGE) <= CASTER_RANGE_TOLERANCE)
		{
			return; // already in position - stand still so AutoUse can cast
		}
		if (distance < 1)
		{
			distance = 1;
		}
		// AoE spread: offset each caster sideways by a fixed per-member amount so several don't stack on one tile and
		// all eat the same boss AoE. The offset is perpendicular to the target line and stable per member (objId),
		// so it fans them out around the boss without jittering.
		final double perp = Math.atan2(dy, dx) + (Math.PI / 2);
		final int lateral = (((npc.getObjectId() % 5) - 2) * CASTER_SPREAD_STEP); // -2..+2 lanes
		final int standX = target.getX() + (int) ((dx / distance) * CASTER_CAST_RANGE) + (int) (Math.cos(perp) * lateral);
		final int standY = target.getY() + (int) ((dy / distance) * CASTER_CAST_RANGE) + (int) (Math.sin(perp) * lateral);
		final Location destination = GeoEngine.getInstance().getValidLocation(npc, new Location(standX, standY, npc.getZ()));
		npc.setRunning();
		npc.getAI().setIntention(Intention.MOVE_TO, destination);
	}

	/**
	 * Active threat management for a TANK. It is NOT gated behind a raid - a tank holding aggro is useful in any
	 * group fight - but it behaves differently by situation so it doesn't waste taunt cooldowns on trash:
	 * <ul>
	 * <li><b>Raid boss</b> ({@code isRaid()}): keep threat pinned. Re-taunt the boss whenever the taunt is off
	 * cooldown, because a boss makes so much hate the tank must spam taunt to stay on top.</li>
	 * <li><b>Loose mob</b>: any mob whose most-hated is a non-tank party member (it slipped onto the healer/nuker/
	 * leader) is yanked back, in any fight - the nearest such mob is targeted.</li>
	 * <li><b>Plain trash, nothing loose</b>: do nothing special - ordinary melee hate from assisting is enough.</li>
	 * </ul>
	 * The taunt chosen is Aggression (single-target, longer range) when the victim is in cast range, otherwise Aura
	 * of Hate (a self-centred AoE) when the victim is inside its affect radius - so the tank uses whichever can
	 * actually land rather than casting into the void.
	 * @return {@code true} if a taunt was cast (or is in progress) this tick, so the caller skips re-issuing ATTACK
	 */
	private boolean maintainThreat(Member state, Monster assistTarget)
	{
		final Player npc = state.npc;
		if (npc.isCastingNow())
		{
			return true; // already taunting; let it land before swinging again
		}
		resolveTaunts(state);
		if ((state.aggression == null) && (state.auraOfHate == null))
		{
			return false; // too low-level to have a taunt yet - just melee (graceful degrade)
		}
		// Pick the mob to pin: a mob loose on a squishy first (nearest one), else the raid boss we're tanking.
		final List<Monster> loose = findLooseMobs(state);
		final Monster victim;
		if (!loose.isEmpty())
		{
			victim = nearest(npc, loose); // a mob slipped onto a squishy - always grab it back now
		}
		else if (assistTarget.isRaid())
		{
			// Already top of the boss's aggro? DON'T re-taunt every tick - the tank's own auto-attacks keep it on
			// top, so spamming Aggression/Aura of Hate every second just drained the tank's whole MP bar (then it
			// couldn't taunt when a DPS spike finally pulled aggro, and the party wiped). Re-taunt only if aggro is
			// NOT on us, or a slow refresh interval has elapsed as insurance.
			if ((assistTarget.getMostHated() == npc) && ((System.currentTimeMillis() - state.lastTauntAt) < TAUNT_REFRESH_MS))
			{
				return false; // threat is held - keep swinging and save the MP
			}
			victim = assistTarget;
		}
		else
		{
			return false; // plain trash and nothing slipped - ordinary melee hate is enough, save the taunt
		}
		// Prefer Aggression (single-target, precise, 400-800 range) when the victim is in range; otherwise fall back
		// to Aura of Hate (a self-centred AoE) when the victim is inside its affect radius (tank stands in the pack).
		if (castable(npc, state.aggression) && (npc.calculateDistance2D(victim) <= state.aggression.getCastRange()))
		{
			if (DEBUG && victim.isRaid())
			{
				dbg("TAUNT " + npc.getName() + " casts Aggression on '" + victim.getName() + "' (was hating " + describe(victim.getMostHated()) + ")");
			}
			state.lastTauntAt = System.currentTimeMillis();
			npc.setTarget(victim);
			npc.setRunning();
			npc.doCast(state.aggression);
			return true;
		}
		if (castable(npc, state.auraOfHate) && (npc.calculateDistance2D(victim) <= state.auraOfHate.getAffectRange()))
		{
			if (DEBUG && victim.isRaid())
			{
				dbg("TAUNT " + npc.getName() + " casts Aura of Hate near '" + victim.getName() + "' (was hating " + describe(victim.getMostHated()) + ")");
			}
			state.lastTauntAt = System.currentTimeMillis();
			npc.setTarget(victim); // AURA taunt centres its effect on the caster; a valid hostile target satisfies the cast
			npc.doCast(state.auraOfHate);
			return true;
		}
		return false; // taunt on cooldown / out of MP / victim out of reach - keep swinging, retry next tick
	}

	/** {@code true} if the member knows the skill and can cast it right now (not on cooldown, enough MP). */
	private static boolean castable(Player npc, Skill skill)
	{
		return (skill != null) && !npc.isSkillDisabled(skill) && (npc.getCurrentMp() >= skill.getMpConsume());
	}

	/** Lazily resolves the tank's two taunt skills from its known list (it may know one, both, or neither). */
	private void resolveTaunts(Member state)
	{
		if (state.tauntLookedUp)
		{
			return;
		}
		state.tauntLookedUp = true;
		state.aggression = state.npc.getKnownSkill(AGGRESSION_ID);
		state.auraOfHate = state.npc.getKnownSkill(AURA_OF_HATE_ID);
	}

	/** Living mobs near the tank whose most-hated is a non-tank party member (aggro slipped onto a squishy). */
	private List<Monster> findLooseMobs(Member state)
	{
		final Player npc = state.npc;
		final List<Monster> loose = new ArrayList<>();
		for (Monster mob : World.getInstance().getVisibleObjectsInRange(npc, Monster.class, THREAT_SCAN_RANGE))
		{
			if (mob.isDead())
			{
				continue;
			}
			final Creature hated = mob.getMostHated();
			if ((hated != null) && (hated != npc) && isPartyMember(state, hated))
			{
				loose.add(mob);
			}
		}
		return loose;
	}

	/** {@code true} if {@code who} is a member of this tank's party (so a mob on it is a threat to protect against). */
	private boolean isPartyMember(Member state, Creature who)
	{
		if (!who.isPlayer() || (state.owner == null) || !state.owner.isInParty())
		{
			return false;
		}
		for (Player member : state.owner.getParty().getMembers())
		{
			if (member == who)
			{
				return true;
			}
		}
		return false;
	}

	/** The nearest of a list of mobs to a point of origin. */
	private static Monster nearest(Player origin, List<Monster> mobs)
	{
		Monster best = null;
		double bestDist = Double.MAX_VALUE;
		for (Monster mob : mobs)
		{
			final double dist = origin.calculateDistance2D(mob);
			if (dist < bestDist)
			{
				bestDist = dist;
				best = mob;
			}
		}
		return best;
	}

	// ===== Support roles: heal the hurt, raise the dead, keep the party buffed =====

	private void supportTick(Member state, long now)
	{
		final Player npc = state.npc;
		final Player owner = state.owner;

		// During a raid a healer must keep the TANK in range - the tank fights the boss in melee while the leader
		// (often a caster) hangs back, so anchoring on the leader left the tank OUT of heal range and it died
		// un-healed (the wipe in the trace). Follow/range-gate on the tank in a raid, otherwise on the leader.
		final boolean raid = raidEngaged(state);
		final Player anchor = healAnchor(state, raid);

		// 0) If the anchor is out of support range, catching up beats trying to cast at nothing. This also keeps a
		// buffer from getting starved on an out-of-range cast and "stopping" - it always moves into range first.
		if (npc.calculateDistance2D(anchor) > SUPPORT_RANGE)
		{
			if (state.following)
			{
				driveFollow(state, anchor);
			}
			return;
		}

		// On-demand specific buff ("give me X"): cast it on the leader (honoured even if their archetype would
		// normally skip it - they explicitly asked).
		if (state.pendingBuff != null)
		{
			final Skill buff = state.pendingBuff;
			if (!owner.isDead() && !npc.isSkillDisabled(buff) && (npc.getCurrentMp() >= buff.getMpConsume()))
			{
				if (!readyToCast(npc))
				{
					return; // getting up first; cast on the next tick (pendingBuff kept so the order isn't lost)
				}
				state.pendingBuff = null;
				npc.setTarget(owner);
				npc.doCast(buff);
				return;
			}
			state.pendingBuff = null; // can't satisfy it right now (dead / oom / disabled) - drop the request
		}

		// On-demand "heal me": one heal on the leader even at full HP.
		if (state.healNow)
		{
			final Skill onDemandHeal = heal(state);
			if ((onDemandHeal != null) && !owner.isDead() && !npc.isSkillDisabled(onDemandHeal) && (npc.getCurrentMp() >= onDemandHeal.getMpConsume()))
			{
				if (!readyToCast(npc))
				{
					return; // getting up first; heal on the next tick (healNow kept so the order isn't lost)
				}
				state.healNow = false;
				npc.setTarget(owner);
				npc.doCast(onDemandHeal);
				return;
			}
			state.healNow = false; // can't satisfy it right now
		}

		// 1) Raise the fallen: a dead real player first, then a recruited bot member kept as a corpse (battle-res).
		final Skill res = res(state);
		if ((res != null) && !npc.isSkillDisabled(res) && (npc.getCurrentMp() >= res.getMpConsume()))
		{
			final Player corpse = findResTarget(state);
			if (corpse != null)
			{
				if (!readyToCast(npc))
				{
					return; // getting up first; res on the next tick
				}
				dbg("RES " + npc.getName() + " rezzing " + roleLabel(corpse) + " '" + corpse.getName() + "'");
				npc.setTarget(corpse);
				npc.doCast(res);
				return;
			}
		}

		// 2) Heal. Under a raid this is pre-emptive and tank-first (a boss spike outruns reactive 60%-only healing):
		// a critically low member is an emergency, otherwise the tank is kept topped, then the most-hurt member.
		// Outside a raid it's the old behaviour - the most-hurt member below the normal threshold, then self.
		final Skill heal = heal(state);
		if (heal != null)
		{
			Player worst = pickHealTarget(state, raid);
			if ((worst == null) && (npc.getCurrentHpPercent() < SELF_HEAL_PERCENT))
			{
				worst = npc;
			}
			if ((worst != null) && !npc.isSkillDisabled(heal) && (npc.getCurrentMp() >= heal.getMpConsume()))
			{
				if (!readyToCast(npc))
				{
					return; // getting up first; heal on the next tick
				}
				if (DEBUG && raid)
				{
					dbg("HEAL " + npc.getName() + " -> " + roleLabel(worst) + " '" + worst.getName() + "' (hp " + worst.getCurrentHpPercent() + "%) with " + heal.getName());
				}
				npc.setTarget(worst);
				npc.doCast(heal);
				return;
			}
		}

		// 2c) Recharge: an Elder/Shillien Elder refills a party caster's MP (healers first, then the tank, then other
		// casters). This is the sustain that keeps the healers from going dry on a long fight - without a recharger
		// the healers burn their whole mana pool keeping the tank up and then the party wipes when they hit 0.
		final Skill rech = recharge(state);
		if ((rech != null) && castable(npc, rech))
		{
			final Player drained = pickRechargeTarget(state);
			if (drained != null)
			{
				if (!readyToCast(npc))
				{
					return; // getting up first; recharge on the next tick
				}
				if (DEBUG && raid)
				{
					dbg("RECHARGE " + npc.getName() + " -> " + roleLabel(drained) + " '" + drained.getName() + "' (mp " + drained.getCurrentMpPercent() + "%)");
				}
				npc.setTarget(drained);
				npc.doCast(rech);
				return;
			}
		}

		// 2b) On-demand "(re)buff <who>": recast a full kit on each queued target, one buff per tick, regardless of
		// time left. Sits below res/heal so a long "buff all" can't get someone killed while it grinds through buffs.
		if (state.rebuffing && forceRebuff(state))
		{
			return;
		}

		// 3) Keep the whole party (and self) buffed; one buff per tick so it works through its list.
		if (maintainPartyBuffs(state))
		{
			return;
		}

		// 4) MP sustain: sit to recover when safe, stand on threat / recovery.
		if (restForMp(state))
		{
			return;
		}

		// 5) Default: stay near the anchor (the tank in a raid, else the leader).
		if (state.following)
		{
			driveFollow(state, anchor);
		}
	}

	/** Who a support should keep itself in range of: a HEALER sticks to the live tank during a raid (so it can heal it), else the leader. */
	private Player healAnchor(Member state, boolean raid)
	{
		if (raid && (state.role == PartyRole.HEALER))
		{
			final Player tank = findTank(state);
			if ((tank != null) && !tank.isDead())
			{
				return tank;
			}
		}
		return state.owner;
	}

	/**
	 * A fallen party member within range this healer should raise: a dead real player takes priority (a human is
	 * waiting to get back up), then a dead recruited bot member kept as a corpse. Skips itself and any corpse whose
	 * revive is already pending (it's about to stand), so multiple healers don't double-cast on the same target.
	 */
	private Player findResTarget(Member state)
	{
		final Player npc = state.npc;
		final Party party = state.owner.getParty();
		if (party == null)
		{
			return null;
		}
		Player botCorpse = null;
		for (Player member : party.getMembers())
		{
			if ((member == npc) || !member.isDead() || member.isReviveRequested() || (npc.calculateDistance2D(member) > SUPPORT_RANGE))
			{
				continue;
			}
			if (member.getClient() != null)
			{
				return member; // a fallen human - top priority
			}
			if ((botCorpse == null) && _members.containsKey(member.getObjectId()))
			{
				botCorpse = member; // a fallen recruited member - raise once no human needs it
			}
		}
		return botCorpse;
	}

	/**
	 * Chooses who this healer should heal this tick. Under a raid it is pre-emptive and tank-first: an emergency
	 * (any member below {@link #CRITICAL_HEAL_PERCENT}) is treated as the most-hurt member, otherwise the tank is
	 * kept topped to {@link #RAID_TANK_HEAL_PERCENT} (it eats the boss), then the most-hurt member below
	 * {@link #RAID_HEAL_PERCENT}. Outside a raid it's just the most-hurt member below {@link #OWNER_HEAL_PERCENT}.
	 */
	private Player pickHealTarget(Member state, boolean raid)
	{
		if (!raid)
		{
			return mostHurtBelow(state, OWNER_HEAL_PERCENT);
		}
		// Emergency first: whoever is critically low, even if it's not the tank, gets the heal now.
		final Player critical = mostHurtBelow(state, CRITICAL_HEAL_PERCENT);
		if (critical != null)
		{
			return critical;
		}
		// Then keep the tank topped so the next boss spike doesn't drop it from comfortable to dead - unless another
		// healer is already healing it (coordination: don't all stack on the tank and waste heals on overheal).
		final Player tank = findTank(state);
		if ((tank != null) && !tank.isDead() && (state.npc.calculateDistance2D(tank) <= SUPPORT_RANGE) && (tank.getCurrentHpPercent() < RAID_TANK_HEAL_PERCENT) //
			&& !((tank.getCurrentHpPercent() >= CRITICAL_HEAL_PERCENT) && beingHealedByAnother(state, tank)))
		{
			return tank;
		}
		// Then the most-hurt of everyone else, pre-emptively (higher threshold than normal farming).
		return mostHurtBelow(state, RAID_HEAL_PERCENT);
	}

	/**
	 * The most-hurt living party member in support range whose HP% is under {@code threshold}, or {@code null}.
	 * A member already being healed by another healer is skipped (so multiple healers spread out instead of all
	 * piling onto one target) UNLESS it is critically low, where stacking heals to save it is the right call.
	 */
	private Player mostHurtBelow(Member state, int threshold)
	{
		final Player npc = state.npc;
		final Party party = state.owner.getParty();
		if (party == null)
		{
			return (!state.owner.isDead() && (state.owner.getCurrentHpPercent() < threshold)) ? state.owner : null;
		}
		Player worst = null;
		int worstPct = threshold;
		for (Player member : party.getMembers())
		{
			if (member.isDead() || (npc.calculateDistance2D(member) > SUPPORT_RANGE) || (member.getCurrentHpPercent() >= worstPct))
			{
				continue;
			}
			if ((member.getCurrentHpPercent() >= CRITICAL_HEAL_PERCENT) && beingHealedByAnother(state, member))
			{
				continue; // another healer has this one covered - look for someone else
			}
			worst = member;
			worstPct = member.getCurrentHpPercent();
		}
		return worst;
	}

	/** {@code true} if another HEALER in the party is currently casting on {@code target} (so this one shouldn't pile on). */
	private boolean beingHealedByAnother(Member state, Player target)
	{
		for (Member m : _members.values())
		{
			if ((m != state) && (m.role == PartyRole.HEALER) && (m.owner == state.owner) && !m.npc.isDead() && m.npc.isCastingNow() && (m.npc.getTarget() == target))
			{
				return true;
			}
		}
		return false;
	}

	/** The Recharge skill this support knows (Elder/Shillien Elder have it; Bishops don't), or {@code null}. */
	private Skill recharge(Member state)
	{
		if (!state.rechargeLookedUp)
		{
			state.rechargeLookedUp = true;
			state.recharge = state.npc.getKnownSkill(RECHARGE_ID);
		}
		return state.recharge;
	}

	/**
	 * The party mana-user this support should Recharge: a HEALER first (so it can keep healing), then the TANK (so it
	 * can keep taunting), then another caster (BUFFER/NUKER) - whichever is most starved below {@link #RECHARGE_MP_PERCENT}
	 * and in range. Melee/archers are skipped (their MP isn't the bottleneck - they auto-attack with shots when dry).
	 */
	private Player pickRechargeTarget(Member state)
	{
		final Player npc = state.npc;
		Player best = null;
		int bestScore = Integer.MAX_VALUE;
		for (Member m : _members.values())
		{
			if ((m.owner != state.owner) || (m.npc == npc) || m.npc.isDead() || (npc.calculateDistance2D(m.npc) > SUPPORT_RANGE))
			{
				continue;
			}
			final int mp = m.npc.getCurrentMpPercent();
			if (mp >= RECHARGE_MP_PERCENT)
			{
				continue;
			}
			final int prio = (m.role == PartyRole.HEALER) ? 0 : (m.role == PartyRole.TANK) ? 1 : (m.isSupport() || (m.role == PartyRole.NUKER)) ? 2 : -1;
			if (prio < 0)
			{
				continue; // melee/archer don't need recharge
			}
			final int score = (prio * 1000) + mp; // higher priority first, then the most drained
			if (score < bestScore)
			{
				bestScore = score;
				best = m.npc;
			}
		}
		return best;
	}

	/** The recruited TANK in this healer's party (the one that should be kept topped under a raid), or {@code null}. */
	private Player findTank(Member state)
	{
		for (Member m : _members.values())
		{
			if ((m.role == PartyRole.TANK) && (m.owner == state.owner) && !m.npc.isDead())
			{
				return m.npc;
			}
		}
		return null;
	}

	/** {@code true} if a live raid boss is in combat near the party - drives pre-emptive healing and no MP-sitting. */
	private boolean raidEngaged(Member state)
	{
		return engagedRaid(state) != null;
	}

	private boolean maintainPartyBuffs(Member state)
	{
		if (buffs(state).isEmpty())
		{
			return false;
		}
		final Player owner = state.owner;
		// Buff SELF first (so e.g. Acumen lands and the buffer casts the rest faster), then the leader gets the
		// full archetype-appropriate kit, then the other members get the essentials.
		if (castFirstMissing(state, state.npc, PhantomBuffs.Tier.SELF))
		{
			return true;
		}
		if (castFirstMissing(state, owner, PhantomBuffs.Tier.LEADER))
		{
			return true;
		}
		for (Player member : owner.getParty().getMembers())
		{
			if ((member == owner) || (member == state.npc))
			{
				continue;
			}
			if (castFirstMissing(state, member, PhantomBuffs.Tier.MEMBER))
			{
				return true;
			}
		}
		return false;
	}

	/** Begins a forced (re)buff: every {@code targets} entry is owed a full archetype kit, served one cast per tick. */
	private void startRebuff(Member state, List<Player> targets)
	{
		state.rebuffQueue = targets;
		state.rebuffIdx = 0;
		state.rebuffing = !targets.isEmpty();
	}

	/** Every member of the leader's party (or just the leader if solo) - the target list for a "buff all" order. */
	private static List<Player> partyTargets(Member state)
	{
		final List<Player> targets = new ArrayList<>();
		final Player owner = state.owner;
		if (owner.isInParty())
		{
			targets.addAll(owner.getParty().getMembers());
		}
		else
		{
			targets.add(owner);
		}
		return targets;
	}

	/**
	 * The party member whose name appears in the order text ("buff Trevor"), or {@code null} if none is named. The
	 * acting member's OWN name is skipped, so "Ulondras buff Rhael" (which names both the healer being addressed and
	 * the target) resolves to the target Rhael, not the healer itself.
	 */
	private static Player findPartyMemberByName(Member state, String text)
	{
		final Player owner = state.owner;
		if (!owner.isInParty())
		{
			return null;
		}
		for (Player member : owner.getParty().getMembers())
		{
			if (member == state.npc)
			{
				continue; // don't match the addressed member's own name - we want the target it should buff
			}
			final String name = member.getName().toLowerCase();
			if (!name.isEmpty() && text.contains(name))
			{
				return member;
			}
		}
		return null;
	}

	/**
	 * "(re)buff" order: recast a full archetype kit on each queued target one buff per tick, ignoring how long the
	 * current one has left. Walks an index across the buff list per target; when a target's kit is done (or it is
	 * dead / out of range) it advances to the next, clearing the flag once the queue empties.
	 * @return {@code true} while still rebuffing (a cast was issued or the member is standing up to cast)
	 */
	private boolean forceRebuff(Member state)
	{
		final Player npc = state.npc;
		final List<Skill> all = buffs(state);
		while ((state.rebuffQueue != null) && !state.rebuffQueue.isEmpty())
		{
			final Player target = state.rebuffQueue.get(0);
			if ((target == null) || target.isDead() || (npc.calculateDistance2D(target) > SUPPORT_RANGE))
			{
				state.rebuffQueue.remove(0); // can't reach/buff this one now - skip to the next target
				state.rebuffIdx = 0;
				continue;
			}
			final boolean caster = PhantomBuffs.isCaster(target);
			while (state.rebuffIdx < all.size())
			{
				final Skill buff = all.get(state.rebuffIdx);
				if (PhantomBuffs.wanted(buff.getId(), caster, PhantomBuffs.Tier.LEADER) && !npc.isSkillDisabled(buff) && (npc.getCurrentMp() >= buff.getMpConsume()))
				{
					if (!readyToCast(npc))
					{
						return true; // getting up first; cast this same buff next tick (index NOT advanced, so it isn't skipped)
					}
					state.rebuffIdx++;
					npc.setTarget(target);
					npc.doCast(buff);
					return true;
				}
				state.rebuffIdx++; // this buff isn't wanted/castable - move past it
			}
			state.rebuffQueue.remove(0); // finished this target's full kit - on to the next
			state.rebuffIdx = 0;
		}
		state.rebuffing = false;
		state.rebuffQueue = null;
		return false;
	}

	/** Casts the first buff this target is missing/about-to-lose that its archetype + tier actually wants. */
	private boolean castFirstMissing(Member state, Player target, PhantomBuffs.Tier tier)
	{
		final Player npc = state.npc;
		if (target.isDead() || (npc.calculateDistance2D(target) > SUPPORT_RANGE))
		{
			return false;
		}
		final boolean caster = PhantomBuffs.isCaster(target);
		for (Skill buff : buffs(state))
		{
			if (!PhantomBuffs.wanted(buff.getId(), caster, tier) || (npc.getCurrentMp() < buff.getMpConsume()))
			{
				continue;
			}
			final BuffInfo info = target.getEffectList().getBuffInfoBySkillId(buff.getId());
			if ((info == null) || (info.getTime() <= BUFF_REFRESH_SECONDS))
			{
				if (!readyToCast(npc))
				{
					return true; // getting up first; cast on the next tick
				}
				npc.setTarget(target);
				npc.doCast(buff);
				return true;
			}
		}
		return false;
	}

	/**
	 * MP sustain for casters (support roles + nuker): sit to recharge WHILE the party fights, standing instantly
	 * if a monster comes at the member itself or the leader runs off. Using the leader's combat state here would
	 * mean a caster farming with you is "in danger" almost always and never actually sits.
	 * @return {@code true} if the member is resting (caller should not also follow)
	 */
	private boolean restForMp(Member state)
	{
		final Player npc = state.npc;
		final Player owner = state.owner;
		if (!state.isSupport() && (state.role != PartyRole.NUKER))
		{
			return false; // melee roles don't sit for MP
		}
		final int mp = npc.getCurrentMpPercent();
		// "threat" must mean "something is actually attacking ME" - not just "a monster exists nearby". While the
		// party farms there is always a live mob within DANGER_RANGE, so the old isMonsterNear() check left a
		// caster permanently "in danger" and it never sat - it just drained to empty (see the gameserver log:
		// threat=true every tick from 45% down to 1%). A real support sits to recharge between casts and only
		// pops up when a mob comes at it directly, which is exactly what underAttack() detects.
		// A raid counts as "threat" even though the tank is holding aggro (so the healer itself isn't being hit):
		// a support must NEVER sit to meditate mid-boss - a sitting healer heals nothing and the party wipes. This
		// is the normal-farm "sit between pulls" behaviour staying intact, but switched off while a raid is engaged.
		final boolean threat = underAttack(npc) || raidEngaged(state);
		final boolean ownerFar = npc.calculateDistance2D(owner) > SUPPORT_RANGE;
		if (npc.isSitting())
		{
			if ((mp >= MP_REST_STAND) || threat || ownerFar)
			{
				LOGGER.info("===== PARTY-MP [stand] " + npc.getName() + " ===== standing (recovered=" + (mp >= MP_REST_STAND) + " underAttack=" + threat + " ownerFar=" + ownerFar + " mp=" + mp + "%)");
				npc.standUp();
				return false;
			}
			return true;
		}
		// Sit to recover MP when it drops to the low mark, it's safe, the leader is close, and a recent "stand"
		// order isn't still holding it on its feet.
		if ((mp < MP_REST_SIT) && !threat && !ownerFar && (System.currentTimeMillis() >= state.noSitUntil))
		{
			// sitDown(false) bypasses the "Cannot sit while casting" guard (a clientless caster's cast flag can
			// linger and otherwise blocks the sit forever - it warns but never actually sits).
			npc.abortCast();
			npc.getAI().setIntention(Intention.IDLE);
			npc.sitDown(false);
			if (npc.isSitting())
			{
				LOGGER.info("===== PARTY-MP [sit] " + npc.getName() + " ===== sitting to recover MP (mp=" + mp + "%)");
			}
			else
			{
				LOGGER.info("===== PARTY-MP [sit-FAILED] " + npc.getName() + " ===== sitDown(false) rejected at mp=" + mp + "%:"
					+ " sitInProgress=" + npc.isSittingProgress()
					+ " castingNow=" + npc.isCastingNow()
					+ " attackingNow=" + npc.isAttackingNow()
					+ " attackDisabled=" + npc.isAttackDisabled()
					+ " immobilized=" + npc.isImmobilized()
					+ " outOfControl=" + npc.isOutOfControl()
					+ " paralyzed=" + npc.isParalyzed());
			}
			return true;
		}
		return false;
	}

	// ===== Helpers =====

	private List<Skill> buffs(Member state)
	{
		if (state.buffs == null)
		{
			final List<Skill> list = new ArrayList<>();
			for (Skill skill : state.npc.getAllSkills())
			{
				if (skill.isPassive() || skill.isToggle() || !skill.isActive() || skill.isDebuff())
				{
					continue;
				}
				// Skip anything that heals - a continuous group heal/HoT (e.g. the Elder's) otherwise leaks into the
				// buff list and gets recast on cooldown "for no reason", looking like a constant group-heal spam.
				if (skill.isContinuous() && (skill.getEffectPoint() >= 0) && !isHealId(skill.getId()) && !skill.hasEffectType(EffectType.HEAL, EffectType.CPHEAL, EffectType.MANAHEAL_PERCENT))
				{
					list.add(skill);
				}
			}
			state.buffs = list;
		}
		return state.buffs;
	}

	private Skill heal(Member state)
	{
		if (state.heal == null)
		{
			for (int id : HEAL_PRIORITY)
			{
				final Skill known = state.npc.getKnownSkill(id);
				if (known != null)
				{
					state.heal = known;
					break;
				}
			}
		}
		return state.heal;
	}

	private Skill res(Member state)
	{
		if ((state.res == null) && (state.role == PartyRole.HEALER))
		{
			state.res = state.npc.getKnownSkill(RES_SKILL_ID);
		}
		return state.res;
	}

	private static boolean isHealId(int id)
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

	private void ensureFollow(Member state)
	{
		final Player npc = state.npc;
		if (!state.following || (state.owner == null) || npc.isDead())
		{
			return;
		}
		npc.setRunning();
		npc.getAI().setIntention(Intention.FOLLOW, state.owner);
	}

	private static void standIfSitting(Player npc)
	{
		if (npc.isSitting())
		{
			npc.standUp();
		}
	}

	/**
	 * Gate a cast behind standing up. standUp() is a ~2.5s animation and a resting member is paralyzed while
	 * seated, so a spell fired in the same tick would silently fail (checkUseMagicConditions rejects paralysis).
	 * @return {@code true} if the member is already standing and may cast now; {@code false} if it just began
	 *         getting up - the caller must return and retry next tick (without consuming any one-shot order) so the
	 *         action lands once it is on its feet.
	 */
	private static boolean readyToCast(Player npc)
	{
		if (npc.isSitting())
		{
			npc.standUp();
			return false;
		}
		return true;
	}

	private boolean isPartiedWith(Member state)
	{
		final Player owner = state.owner;
		return (owner != null) && state.npc.isInParty() && owner.isInParty() && (state.npc.getParty() == owner.getParty());
	}

	private static boolean isMonsterNear(Player npc)
	{
		for (Monster monster : World.getInstance().getVisibleObjectsInRange(npc, Monster.class, DANGER_RANGE))
		{
			if (!monster.isDead())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @return {@code true} if a live monster is actually engaged on {@code npc} (targeting it), i.e. something is
	 *         coming at the support itself. Unlike {@link #isMonsterNear(Player)} (any mob in the area), this stays
	 *         {@code false} while the party simply farms nearby, so a caster can sit to recover MP between casts and
	 *         only stands when a mob turns on it.
	 */
	private static boolean underAttack(Player npc)
	{
		for (Monster monster : World.getInstance().getVisibleObjectsInRange(npc, Monster.class, DANGER_RANGE))
		{
			if (!monster.isDead() && (monster.getTarget() == npc))
			{
				return true;
			}
		}
		return false;
	}

	/** Releases a member from its party and despawns it. */
	private void release(Member state, boolean graceful)
	{
		final Player npc = state.npc;
		_members.remove(npc.getObjectId());
		try
		{
			if (npc.isInParty())
			{
				npc.getParty().removePartyMember(npc, PartyMessageType.LEFT);
			}
		}
		catch (Exception e)
		{
			// best effort
		}
		PhantomManager.getInstance().despawnRecruit(npc);
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

	public static PhantomPartyManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomPartyManager INSTANCE = new PhantomPartyManager();
	}
}
