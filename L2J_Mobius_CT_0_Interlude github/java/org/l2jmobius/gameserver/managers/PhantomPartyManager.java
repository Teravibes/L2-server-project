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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
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

	private static final long TICK_INTERVAL = 1000;
	private static final int MAX_PER_REQUEST = 6; // never spawn more than this from one shout, slots permitting
	private static final int APPROACH_MIN = 1900; // how far out of sight a recruit spawns before walking in
	private static final int APPROACH_MAX = 2600;
	private static final long ARRIVE_RANGE = 220; // close enough to the leader to say "here, inv me"
	private static final long RECRUIT_TIMEOUT = 150000; // give up + despawn if never invited within this window
	private static final long SPAWN_STAGGER = 2500; // trickle arrivals so a group doesn't pop in on one tile
	private static final int FOLLOW_RANGE = 250;
	private static final int LEASH_RANGE = 1400; // free-hunting member is pulled back if it strays this far
	private static final long TRAVEL_GRACE = 180000; // after a "go to X" order, wait at the spot this long for the leader
	private static final int REGROUP_RANGE = 1500; // ...resuming normal follow once the leader arrives within this
	private static final int SUPPORT_RANGE = 900; // heal/buff/res only when the target is this close
	private static final int ASSIST_MAX_RANGE = 2200; // don't assist a mob the leader targeted across the map
	private static final int DANGER_RANGE = 700;
	private static final int OWNER_HEAL_PERCENT = 60;
	private static final int SELF_HEAL_PERCENT = 45;
	private static final int BUFF_REFRESH_SECONDS = 20;
	private static final int MP_REST_SIT = 80; // a caster sits to recover when below this and safe
	private static final int MP_REST_STAND = 95; // and stands once topped back up
	private static final long OFFLINE_GRACE = 120000;
	private static final long BRB_GRACE = 360000;
	private static final int RES_SKILL_ID = 1016; // Resurrection (granted to healers on spawn)

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
		boolean assist = true; // assist the leader's target (default) vs. free-hunt
		boolean following = true;
		boolean reminded; // already whispered "here, inv me" while waiting
		long pendingSince; // spawn time; despawn if never invited within RECRUIT_TIMEOUT
		long graceUntil;
		List<Skill> buffs; // lazy
		Skill heal; // lazy
		Skill res; // lazy
		int lastX; // follow-stuck watchdog
		int lastY;
		int stuckTicks;
		long castSince; // abort a wedged cast instead of freezing the member
		long travelUntil; // sent ahead to a destination; wait there (don't follow-yank back) until the leader arrives
		boolean rebuffing; // "rebuff" order: recast the full kit on the leader regardless of time left
		int rebuffIdx;
		boolean healNow; // "heal me" order: heal the leader once even at full HP
		Skill pendingBuff; // "give me X" order: a specific buff to cast on the leader next tick

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
			ThreadPool.schedule(() -> spawnAndApproach(leader, wanted, memberLevel), 1200L + (order * SPAWN_STAGGER) + Rnd.get(900));
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
			if (containsAny(text, "rebuff", "buff me", "buff us", "buff", "rebuf"))
			{
				state.rebuffing = true; // serve() recasts the leader's whole kit over the next ticks
				state.rebuffIdx = 0;
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
					release(state, false);
					continue;
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
			if ((t instanceof Monster) && !((Monster) t).isDead() && (owner.calculateDistance2D(t) <= ASSIST_MAX_RANGE))
			{
				standIfSitting(npc);
				if ((npc.getTarget() != t) || !npc.isInCombat())
				{
					npc.setTarget(t);
					npc.setRunning();
					npc.getAI().setIntention(Intention.ATTACK, t);
				}
				return; // AutoUse fires the role's skills/shots on this target
			}
			// Nothing to assist: a caster low on MP sits to recover when safe; otherwise stick with the leader.
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

	// ===== Support roles: heal the hurt, raise the dead, keep the party buffed =====

	private void supportTick(Member state, long now)
	{
		final Player npc = state.npc;
		final Player owner = state.owner;

		// 0) If the leader is out of support range, catching up beats trying to cast at nothing. This also keeps a
		// buffer from getting starved on an out-of-range cast and "stopping" - it always moves to you first.
		if (npc.calculateDistance2D(owner) > SUPPORT_RANGE)
		{
			if (state.following)
			{
				driveFollow(state, owner);
			}
			return;
		}

		// On-demand specific buff ("give me X"): cast it on the leader (honoured even if their archetype would
		// normally skip it - they explicitly asked).
		if (state.pendingBuff != null)
		{
			final Skill buff = state.pendingBuff;
			state.pendingBuff = null;
			if (!owner.isDead() && !npc.isSkillDisabled(buff) && (npc.getCurrentMp() >= buff.getMpConsume()))
			{
				standIfSitting(npc);
				npc.setTarget(owner);
				npc.doCast(buff);
				return;
			}
		}

		// On-demand "heal me": one heal on the leader even at full HP.
		if (state.healNow)
		{
			state.healNow = false;
			final Skill onDemandHeal = heal(state);
			if ((onDemandHeal != null) && !owner.isDead() && !npc.isSkillDisabled(onDemandHeal) && (npc.getCurrentMp() >= onDemandHeal.getMpConsume()))
			{
				standIfSitting(npc);
				npc.setTarget(owner);
				npc.doCast(onDemandHeal);
				return;
			}
		}

		// On-demand "rebuff": recast the leader's whole kit, one buff per tick, regardless of time left.
		if (state.rebuffing && forceRebuff(state))
		{
			return;
		}

		// 1) Raise any fallen real player in the party.
		final Skill res = res(state);
		if ((res != null) && !npc.isSkillDisabled(res) && (npc.getCurrentMp() >= res.getMpConsume()))
		{
			for (Player member : owner.getParty().getMembers())
			{
				if (member.isDead() && (member.getClient() != null) && (npc.calculateDistance2D(member) <= SUPPORT_RANGE))
				{
					standIfSitting(npc);
					npc.setTarget(member);
					npc.doCast(res);
					return;
				}
			}
		}

		// 2) Heal the most-hurt living party member below the threshold, then self.
		final Skill heal = heal(state);
		if (heal != null)
		{
			Player worst = null;
			int worstPct = OWNER_HEAL_PERCENT;
			for (Player member : owner.getParty().getMembers())
			{
				if (!member.isDead() && (npc.calculateDistance2D(member) <= SUPPORT_RANGE) && (member.getCurrentHpPercent() < worstPct))
				{
					worst = member;
					worstPct = member.getCurrentHpPercent();
				}
			}
			if ((worst == null) && (npc.getCurrentHpPercent() < SELF_HEAL_PERCENT))
			{
				worst = npc;
			}
			if ((worst != null) && !npc.isSkillDisabled(heal) && (npc.getCurrentMp() >= heal.getMpConsume()))
			{
				standIfSitting(npc);
				npc.setTarget(worst);
				npc.doCast(heal);
				return;
			}
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

		// 5) Default: follow the leader.
		if (state.following)
		{
			driveFollow(state, owner);
		}
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

	/**
	 * "rebuff" order: recast the leader's full archetype kit one buff per tick, ignoring how long the current
	 * one has left. Walks an index across the buff list so each wanted buff is recast once, then clears the flag.
	 * @return {@code true} while still rebuffing (a cast was issued)
	 */
	private boolean forceRebuff(Member state)
	{
		final Player npc = state.npc;
		final Player owner = state.owner;
		final List<Skill> all = buffs(state);
		final boolean caster = PhantomBuffs.isCaster(owner);
		while (state.rebuffIdx < all.size())
		{
			final Skill buff = all.get(state.rebuffIdx);
			state.rebuffIdx++;
			if (PhantomBuffs.wanted(buff.getId(), caster, PhantomBuffs.Tier.LEADER) && !npc.isSkillDisabled(buff) && (npc.getCurrentMp() >= buff.getMpConsume()) && (npc.calculateDistance2D(owner) <= SUPPORT_RANGE))
			{
				standIfSitting(npc);
				npc.setTarget(owner);
				npc.doCast(buff);
				return true;
			}
		}
		state.rebuffing = false;
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
				standIfSitting(npc);
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
		final boolean threat = underAttack(npc);
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
		// Sit to top MP back up during downtime whenever it isn't near full and it's safe and the leader is close.
		if ((mp < MP_REST_SIT) && !threat && !ownerFar)
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
				if (skill.isContinuous() && (skill.getEffectPoint() >= 0) && !isHealId(skill.getId()))
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
