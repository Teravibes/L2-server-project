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
	private static final int SUPPORT_RANGE = 900; // heal/buff/res only when the target is this close
	private static final int ASSIST_MAX_RANGE = 2200; // don't assist a mob the leader targeted across the map
	private static final int DANGER_RANGE = 700;
	private static final int OWNER_HEAL_PERCENT = 60;
	private static final int SELF_HEAL_PERCENT = 45;
	private static final int BUFF_REFRESH_SECONDS = 20;
	private static final int MP_LOW_PERCENT = 25;
	private static final int MP_OK_PERCENT = 70;
	private static final long OFFLINE_GRACE = 120000;
	private static final long BRB_GRACE = 360000;
	private static final int RES_SKILL_ID = 1016; // Resurrection (granted to healers on spawn)

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
	public void recruitFromShout(Player leader, List<PartyRole> roles)
	{
		if ((leader == null) || (roles == null) || roles.isEmpty())
		{
			return;
		}
		int slots = freeSlots(leader);
		if (slots <= 0)
		{
			leader.sendPacket(new CreatureSay(leader, ChatType.WHISPER, "Party", "your party is full"));
			return;
		}
		int spawned = 0;
		for (PartyRole role : roles)
		{
			if ((spawned >= slots) || (spawned >= MAX_PER_REQUEST))
			{
				break;
			}
			final PartyRole wanted = role;
			final int order = spawned;
			ThreadPool.schedule(() -> spawnAndApproach(leader, wanted), 1200L + (order * SPAWN_STAGGER) + Rnd.get(900));
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
	private void spawnAndApproach(Player leader, PartyRole role)
	{
		if ((leader == null) || !leader.isOnline())
		{
			return;
		}
		final double angle = Rnd.nextDouble() * 2 * Math.PI;
		final int distance = Rnd.get(APPROACH_MIN, APPROACH_MAX);
		final Location anchor = new Location(leader.getX() + (int) (Math.cos(angle) * distance), leader.getY() + (int) (Math.sin(angle) * distance), leader.getZ());
		final Player npc = PhantomManager.getInstance().spawnPartyMember(anchor, leader.getLevel(), role);
		if (npc == null)
		{
			return;
		}
		final Member member = new Member(npc, role);
		member.owner = leader;
		member.pendingSince = System.currentTimeMillis();
		_members.put(npc.getObjectId(), member);
		startTicking();
		npc.setRunning();
		npc.getAI().setIntention(Intention.FOLLOW, leader);
		// Answer the shout so it feels like a person reacting to the LFM, then it jogs over.
		shout(npc, omwLine(role));
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

	/** Handles a whisper to a recruited member; returns the reply to deliver as a whisper, or {@code null}. */
	public String handleWhisper(Player owner, Player member, String message)
	{
		final Member state = _members.get(member.getObjectId());
		if ((state == null) || (owner == null) || (message == null))
		{
			return null;
		}
		return command(state, owner, message);
	}

	/** Every recruited member bound to the party-chat speaker reacts to the line (drive the whole party at once). */
	public void handlePartyChat(Player speaker, String message)
	{
		if ((speaker == null) || (message == null) || !speaker.isInParty())
		{
			return;
		}
		for (Player member : speaker.getParty().getMembers())
		{
			final Member state = _members.get(member.getObjectId());
			if ((state == null) || (state.owner != speaker))
			{
				continue;
			}
			command(state, speaker, message);
		}
	}

	/** Deterministic group-command parser shared by whisper and party chat. Works with the brain offline. */
	private String command(Member state, Player owner, String message)
	{
		final String text = message.toLowerCase().trim();

		// Free-hunt vs assist toggle.
		if (containsAny(text, "attack freely", "free hunt", "go wild", "ffa", "hunt freely", "do your own", "attack anything"))
		{
			setFree(state, true);
			deliver(state, "k, hunting on my own");
			return null;
		}
		if (containsAny(text, "assist", "focus", "help me", "on my target", "kill my target", "attack my"))
		{
			setFree(state, false);
			state.following = true;
			deliver(state, "k, assisting you");
			return null;
		}

		// Follow / hold.
		if (containsAny(text, "follow", "come with", "on me", "regroup", "gather", "stack", "stick with"))
		{
			state.following = true;
			ensureFollow(state);
			deliver(state, "coming");
			return null;
		}
		if (containsAny(text, "stay", "wait here", "hold", "stop", "halt"))
		{
			state.following = false;
			setFree(state, false);
			state.npc.getAI().setIntention(Intention.IDLE);
			deliver(state, "holding here");
			return null;
		}

		// Grace.
		if (containsAny(text, "brb", "be right back", "give me", "gimme", "afk", "one sec", "1 sec", "moment"))
		{
			state.graceUntil = System.currentTimeMillis() + BRB_GRACE;
			deliver(state, "np");
			return null;
		}

		// Disband / dismiss.
		if (containsAny(text, "disband", "leave party", "leave the party", "dismiss", "you can go", "thanks bye", "thx bye", "bye", "gl hf"))
		{
			deliver(state, "gl hf o/");
			ThreadPool.schedule(() -> release(state, true), 1200);
			return null;
		}

		// Status.
		if (containsAny(text, "status", "hp?", "mp?", "you ok", "u ok"))
		{
			deliver(state, "hp " + state.npc.getCurrentHpPercent() + "% / mp " + state.npc.getCurrentMpPercent() + "%");
			return null;
		}

		// Buffer/healer on-demand: the maintenance loops already cover it, just acknowledge.
		if (state.isSupport() && containsAny(text, "buff", "heal", "rebuff", "res", "resurrect", "ress"))
		{
			deliver(state, "on it");
			return null;
		}

		// Unknown: a quick canned nudge (brain-off). Natural-language routing to the brain can layer on later.
		deliver(state, "say: assist, attack freely, follow, hold, brb or bye");
		return null;
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
			ensureFollow(state); // keep closing the distance
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

		if (npc.isCastingNow())
		{
			return true;
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
			// Nothing to assist: stick with the leader.
			if (state.following && !npc.isSitting() && (npc.calculateDistance2D(owner) > FOLLOW_RANGE))
			{
				ensureFollow(state);
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
		if (handleMp(state, now))
		{
			return;
		}

		// 5) Default: follow the leader.
		if (state.following && !npc.isSitting() && (npc.calculateDistance2D(owner) > FOLLOW_RANGE))
		{
			ensureFollow(state);
		}
	}

	private boolean maintainPartyBuffs(Member state)
	{
		final Player npc = state.npc;
		final List<Skill> buffs = buffs(state);
		if (buffs.isEmpty())
		{
			return false;
		}
		for (Player member : state.owner.getParty().getMembers())
		{
			if (member.isDead() || (npc.calculateDistance2D(member) > SUPPORT_RANGE))
			{
				continue;
			}
			final Skill missing = firstMissingBuff(buffs, npc, member);
			if (missing != null)
			{
				standIfSitting(npc);
				npc.setTarget(member);
				npc.doCast(missing);
				return true;
			}
		}
		final Skill selfMissing = firstMissingBuff(buffs, npc, npc);
		if (selfMissing != null)
		{
			standIfSitting(npc);
			npc.setTarget(npc);
			npc.doCast(selfMissing);
			return true;
		}
		return false;
	}

	private Skill firstMissingBuff(List<Skill> buffs, Player npc, Player target)
	{
		for (Skill buff : buffs)
		{
			if (npc.getCurrentMp() < buff.getMpConsume())
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

	private boolean handleMp(Member state, long now)
	{
		final Player npc = state.npc;
		final Player owner = state.owner;
		final int mp = npc.getCurrentMpPercent();
		final boolean danger = owner.isInCombat() || isMonsterNear(npc);
		if (npc.isSitting())
		{
			if ((mp >= MP_OK_PERCENT) || danger || (npc.calculateDistance2D(owner) > SUPPORT_RANGE))
			{
				npc.standUp();
				return false;
			}
			return true;
		}
		if ((mp < MP_LOW_PERCENT) && !danger && (npc.calculateDistance2D(owner) <= SUPPORT_RANGE))
		{
			npc.getAI().setIntention(Intention.IDLE);
			npc.sitDown();
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
