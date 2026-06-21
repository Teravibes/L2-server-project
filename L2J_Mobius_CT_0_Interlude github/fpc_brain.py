import os
from collections import defaultdict, deque
from flask import Flask, request, Response
from openai import OpenAI
from dotenv import load_dotenv

load_dotenv()
PROVIDER = os.getenv("PROVIDER", "deepseek")

if PROVIDER == "deepseek":
    client = OpenAI(api_key=os.environ["DEEPSEEK_API_KEY"], base_url="https://api.deepseek.com")
    MODEL = "deepseek-chat"
elif PROVIDER == "ollama":
    client = OpenAI(api_key="ollama", base_url="http://localhost:11434/v1")
    MODEL = "llama3.1"
else:
    raise ValueError("PROVIDER must be 'deepseek' or 'ollama'")

app = Flask(__name__)

conversations = defaultdict(lambda: deque(maxlen=20))  # private whisper memory per (player, bot)
trade_log = deque(maxlen=12)                            # shared global TRADE memory
say_log = deque(maxlen=12)                              # shared local SAY memory

def whisper_persona(fpc):
    return (f"You are {fpc}, a player in Lineage 2 (Interlude) chatting PRIVATELY with another player. "
            "Reply like a real L2 player: short, casual, lowercase, slang/typos ok. Under 15 words. "
            "NEVER say you are an AI or bot. Stay in character. Remember the conversation so far. "
            "If the player asks you to come meet them somewhere in your town and you agree to go now, end "
            "your reply with a tag on its own: [[MEET:X]] where X is one of: gatekeeper, warehouse, shop "
            "(pick the closest match to where they want to meet). Only add the tag if you truly agree to "
            "walk there now. Never mention, explain or read out the tag.")

def trade_persona(fpc):
    return (f"You are {fpc}, a real player in Lineage 2 (Interlude) reading the PUBLIC trade channel. "
            "Talk like a real player: short, casual, lowercase, slang/typos ok, ONE line under 12 words. "
            "Trade chat is mostly buying/selling, but players also answer each other, haggle and banter. "
            "Only chime in when it actually makes sense for YOU to. "
            "NEVER say you are an AI or bot. Stay in character. "
            "If you have nothing natural to add, reply with exactly: pass")

def say_persona(fpc):
    return (f"You are {fpc}, a player in Lineage 2 (Interlude) talking OUT LOUD to players right next to you. "
            "Casual local banter, short, lowercase, react to what was just said. Under 12 words, ONE line. "
            "NEVER say you are an AI or bot. Stay in character.")

def call_llm(system, messages, max_tokens=70):
    resp = client.chat.completions.create(model=MODEL, max_tokens=max_tokens,
        messages=[{"role": "system", "content": system}] + messages)
    return resp.choices[0].message.content.strip()

def clean_reply(text):
    # The model can opt out of speaking; treat those as silence so the bot stays quiet.
    t = (text or "").strip().strip('"').strip()
    if t.lower().strip(".!:-") in ("", "pass", "skip", "none", "no reply"):
        return ""
    return t

@app.route("/chat", methods=["POST"])
def chat():
    fpc = request.headers.get("X-FPC", "a player")
    mode = request.headers.get("X-Mode", "WHISPER").upper()
    location = request.headers.get("X-Location", "").strip()
    # Where the bot actually is, so it can answer "where are you?" truthfully instead of inventing a spot.
    loc_note = (f" You are currently {location} in the game world; if asked where you are or where to "
                "meet, answer truthfully with that.") if location else ""
    message = request.get_data(as_text=True)
    reply = ""
    try:
        if mode == "WHISPER":
            player = request.headers.get("X-Player", "someone")
            hist = conversations[(player, fpc)]
            hist.append({"role": "user", "content": message})
            recent = "\n".join(trade_log) if trade_log else "(nothing recent)"
            system = whisper_persona(fpc) + loc_note + f"\n\nRecent public trade chat you saw:\n{recent}"
            reply = call_llm(system, list(hist), 80)
            hist.append({"role": "assistant", "content": reply})
        elif mode == "SAY":
            speaker = request.headers.get("X-Speaker", "")
            overheard = f"{speaker}: {message}" if speaker else message
            if message and overheard not in say_log:
                say_log.append(overheard)
            context = "\n".join(say_log) if say_log else "(quiet)"
            reply = call_llm(say_persona(fpc) + loc_note,
                [{"role": "user", "content": f"People near you just said:\n{context}\n\nReact with ONE short line."}], 60)
            say_log.append(f"{fpc}: {reply}")
        else:  # TRADE or AMBIENT
            speaker = request.headers.get("X-Speaker", "")
            overheard = f"{speaker}: {message}" if speaker else message
            if message and overheard not in trade_log:
                trade_log.append(overheard)
            context = "\n".join(trade_log) if trade_log else "(channel is quiet)"
            if mode == "AMBIENT":
                prompt = (f"Recent trade chat:\n{context}\n\n"
                          "Post ONE spontaneous trade line of your own (a WTS, a WTB, or a question). ONE line.")
            else:
                who = speaker or "someone"
                prompt = (f"Recent trade chat:\n{context}\n\n"
                          f"{who} just said: \"{message}\"\n"
                          "If this is something you'd naturally react to, reply to THEM directly: "
                          "answer their question, make/counter an offer, haggle, or banter. "
                          "Do NOT just post your own unrelated WTS/WTB ad. "
                          "If it has nothing to do with you, reply with exactly: pass")
            reply = clean_reply(call_llm(trade_persona(fpc) + loc_note, [{"role": "user", "content": prompt}], 60))
            if reply:
                trade_log.append(f"{fpc}: {reply}")
        print(f"[{PROVIDER}:{mode}:{fpc}] '{message}' -> '{reply}'")
    except Exception as e:
        print("Brain error:", e)
        reply = ""
    return Response(reply, mimetype="text/plain")

if __name__ == "__main__":
    print(f"FPC brain running: {PROVIDER} ({MODEL})")
    app.run(host="127.0.0.1", port=5000)