#!/usr/bin/env python3
"""Scene H (agents, 0.6): Home's Agents tab opens on Mia's morning brief and the two suggestions that matter; the
brief, the Kyoto goal with its milestones, the suggestions with their prepared steps, a watcher and a routine on the
Proactivity page; the autonomy levels explained as they change; then a message to Mia, answered in the chat.

Everything on screen is the real app. The proactive records (goals, suggestions, brief, watcher, routine) are seeded
with the debug command demo.proactive.seed — the agent's background work is not run for the camera — and nothing seeded
is enabled or authorized. The emulator's clock is set to 08:24 so the morning brief is the day's brief.

    python3 marketing/capture/scene_h_agents.py AGENT_ID [en|zh]
"""
import json
import sys
import time
from lib import *

AGENT = sys.argv[1]
LANG = sys.argv[2] if len(sys.argv) > 2 else "en"
b = umbractl.Bridge(); umbractl.setup()
reply = ("Today is light: stand-up at 10:00 (your notes are ready), the dentist at 14:00 and the Acme call at 14:30. Those two "
         "overlap, so I drafted a message moving the call to 16:00; it waits for your OK on the suggestion. The Kyoto plan "
         "is two milestones from done.") if LANG == "en" else \
        ("今天不忙：10:00 站立會議（筆記已備好）、14:00 牙醫、14:30 Acme 通話。後兩個重疊，我擬好了把通話改到 16:00 的訊息，"
         "等你在建議上確認。京都行程還差兩個里程碑。")
ASK = {"en": "What is on today?", "zh": "今天有什麼事？"}[LANG]   # no quotes: `input text` goes through a shell


def answer(request):
    """Only the user's question gets the answer; background wakes stay quiet (the chat hides "[quiet]")."""
    users = [m for m in request.get("messages", []) if m.get("role") == "user"]
    last = users[-1].get("content") if users else ""
    last = last if isinstance(last, str) else json.dumps(last, ensure_ascii=False)
    if ASK.rstrip("?？") in last:
        time.sleep(2.4)
        return say(reply)
    return say("[quiet]")


model = ScriptedModel([answer] * 12).start()
b.control("provider.set", {"id": "scripted", "base_url": model.url_for_emulator, "model": "scripted-model", "key": ""})
b.control("capsule.set", {"on": False})
ui_set(b, mode="simple", onboarded=True, effort="auto", homeAgents=True)
sh("settings", "put", "global", "auto_time", "0")
sh("su", "0", "toybox", "date", time.strftime("%m%d", time.localtime(time.time() + 86400)) + "0824" + time.strftime("%Y", time.localtime()) + ".00")
time.sleep(1)
b.control("demo.proactive.seed", {"agent": AGENT, "lang": "zh" if LANG == "zh" else "en"})
sh("input", "keyevent", "3"); time.sleep(1)
fresh_app("home", 3.5)

L = {"en": dict(goals="Goals", sugg="Suggestions", briefs="Briefs", watch="Watchers", routines="Routines", review="Review the reschedule draft",
                cancel="Cancel", msg="Message"),
     "zh": dict(goals="目標", sugg="建議", briefs="簡報", watch="監測", routines="例行工作", review="檢視改期草稿",
                cancel="取消", msg="訊息")}[LANG]

rec = Recorder("scene_h_" + LANG).start()
rec.mark("home"); time.sleep(3.2)
p = find("MORNING BRIEF") or find("晨報")
tap(*(p or (360, 380))); rec.mark("brief"); time.sleep(3.6)
swipe(360, 1250, 360, 760, 700); time.sleep(2.2)
swipe(360, 760, 360, 1250, 700); time.sleep(1.0)
tap_text(L["sugg"], exact=False, below=600); rec.mark("suggestions"); time.sleep(3.2)
swipe(360, 1300, 360, 760, 900); time.sleep(2.4)          # the card: what it found, the prepared message, the evidence
tap_text(L["review"], exact=True); rec.mark("review"); time.sleep(3.4)
tap_text(L["cancel"], exact=True); time.sleep(1.4)
swipe(360, 760, 360, 1300, 700); time.sleep(1.0)
tap_text(L["goals"], exact=False, below=600); rec.mark("goals"); time.sleep(3.8)
swipe(360, 1250, 360, 800, 800); time.sleep(2.0)
swipe(360, 800, 360, 1250, 800); time.sleep(0.8)
p = find(L["goals"], below=600)
swipe(620, p[1] if p else 687, 120, p[1] if p else 687, 500); time.sleep(1.2)   # the tab strip scrolls sideways
tap_text(L["watch"], exact=False, below=600); rec.mark("watchers"); time.sleep(3.0)
tap_text(L["routines"], exact=False, below=600); rec.mark("routines"); time.sleep(3.0)
tap_text("L4", exact=True); rec.mark("level4"); time.sleep(2.8)
tap_text("L2", exact=True); time.sleep(2.4)
tap_text("L3", exact=True); rec.mark("level3"); time.sleep(2.2)
sh("input", "keyevent", "4"); time.sleep(2.0)
rec.mark("home-again")
fresh_app("agent/" + AGENT, 2.5); rec.mark("chat")
tap_text(L["msg"], exact=True); time.sleep(0.8)
text(ASK)  # `input text` types ASCII only, so the scene is recorded in English
time.sleep(0.8); sh("input", "keyevent", "4"); time.sleep(0.6)
p = find("Send", exact=True) or find("傳送", exact=True)
tap(*(p or (643, 1480))); rec.mark("sent")
for _ in range(40):
    time.sleep(1)
    if any(ASK.rstrip("?？") in json.dumps(r, ensure_ascii=False) for r in model.agent_requests): break
time.sleep(7.0); rec.mark("answered")
sh("input", "keyevent", "3"); time.sleep(1.5)
rec.stop()
model.stop()
sh("settings", "put", "global", "auto_time", "1")
