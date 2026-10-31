# Umbra 0.7.0

Version code **20** (0.6.5 is 19). Built from the `release-0.6.1` branch (15ab16c) plus the working tree.

Published 2026-10-31: `umbra-0.7.0.apk`, 937,435,725 bytes (894 MiB), SHA-256
`02f5f2a2fcca51136ec1ebf0e572d10a5f0de29d52c25df5421eaa463e44a8d1`, signed with the release key
(`b38060de…3a2231c3`, the same as earlier releases and the site's app links). update.json, both download pages and
/download/latest point at it; installed phones update over the air. As for 0.6.5, no `.sha256` object was uploaded,
so the scheduled GitHub mirror does not publish a release from uncommitted source.

## What changes for the user

- **Laya on more phones, on their best processor.** Snapdragon 7 Gen 1 joins the 19 chips whose NPU runs Laya from a
  file compiled ahead of time (Snapdragon 8 Gen 1 → 8 Elite Gen 5, Dimensity 900 → 9500). Every other chip of the last
  five years (mid-range Snapdragon, Snapdragon 888/778G, older Dimensity, all Exynos) runs Laya on its GPU, which now
  has three paths, each tried once: OpenCL (or what LiteRT picks) at fp16, then OpenGL ES, then fp32 on phones with
  8 GB or more. The GPU keeps the programs it compiled, so later starts are faster and use less power; Snapdragon NPUs
  run in Hexagon's balanced mode instead of holding the DSP at full clock.
- **Briefing, rebuilt around decisions.** What needs you comes first. Each suggestion says why it comes up now, what
  happens if you confirm (step by step), what it needs from you, what happens if you do nothing and what it is based
  on, in plain sentences, never raw ids or data. Calendar clashes say which events, when, on which calendar and by how
  much, in Chinese or English, with a button that asks the agent to prepare a fix (it never sends or moves anything
  without you). The confirm sheet shows the same account; a message draft is the message itself.
- **Agents remember better (SOUL and MEMORY).** Facts carry who they are about, how often they were confirmed and when;
  a fact that stops being true is replaced and kept under "No longer true" instead of being deleted; repeats are merged.
  Each wake the agent sees a small always-on profile plus the facts relevant to what is happening, and can search the
  rest. Daily notes keep what it did; an evening reflection turns lasting things into facts. New agents start from a
  sectioned SOUL.md (who I am, how I talk, what I care about, boundaries); an agent can suggest changes to its own SOUL,
  which you accept or decline on its Soul page.
- **Knowledge graphs (graphify).** Each coding project folder gets a code graph (calls, imports, classes), and each
  agent a graph of what it knows. A coding session an agent starts can query both before grepping. Agents can keep
  ongoing work in a named project folder whose graph grows with every session. graphify installs into Linux the first
  time a coding session needs it (pinned, hash-checked wheels).
- **Capsule no longer flickers.** The pill's window used to resize on every animation frame and every clock tick; now it
  changes size at most twice per change, and it rides out a brief accessibility-service restart instead of being torn
  down and rebuilt.
- **Battery.** Accessibility no longer wakes the app for every screen change in every app; the device log is read only
  while a task runs; health and resource checks slow down when idle; Linux service probes run every 15 minutes in the
  background instead of every minute; quiet heartbeats no longer wake a sleeping phone and back off when nothing
  happens; the Capsule stops refreshing while the screen is off.
- **Cards in answers.** Agents and coding sessions can answer with a card when structure helps: a schedule, several
  numbers, options to choose from, a checklist, a form, a status with steps. One hundred templates in ten families
  (planning, projects, metrics, money, travel, people, development, knowledge, home, agent), all drawn in the Ink style:
  paper and ink, colour only where it means something. Interactive cards (checklists, choices, forms, ratings, sliders)
  send your answer to the agent as your own message and then lock. Plain answers stay plain: at most two cards a reply,
  and only when they help. Developer → Card templates shows every template.
- **Live dashboards.** Ask an agent to keep an eye on something that changes (a budget file, a nightly build log, a
  folder of invoices, its own coding sessions) and it can start a dashboard: tiles drawn like cards, two to a row.
  Tiles linked to a file or folder update by themselves when it changes, without waking the model; the agent fills the
  rest on a schedule you can see, or when a watched file changes. The page says where each number comes from, when the
  agent looks again and what changed lately. Open it from the agent's chat (Dashboards) or its page.
- **Skills.** A new Skills entry in the menu searches GitHub for agent skills (SKILL.md collections), shows what each one
  does and its SKILL.md, and sends it to an agent to install, for every project or one project. The agent reads the skill
  and its scripts first. Installing for every project, or into one of your own projects, needs your go-ahead: picking it
  on the Skills page, or approving in the agent's chat. Each coding session can use all skills, a chosen few or none
  (Skills in the new-session sheet and the session's menu); sessions an agent starts get the skills that fit their task.
- **Publish HTML in one tap.** A page or app an agent made (HTML) shows Publish in its preview: one tap puts it on your
  own Cloudflare account (Pages, on pages.dev) and gives you the link to share. The first time, you connect Cloudflare
  with an account ID and a Pages token (stored encrypted on the phone). Later a changed page shows "Publish update" to the
  same link; Settings → Publishing lists your sites and takes them down. Agents now make HTML artifacts as static sites
  that publish as they are.
- **Agents ask instead of guessing.** When an agent or a coding session needs your decision or a missing detail, it
  asks: a few options to tap (the one it recommends is marked) or a question you answer in your own words. The question
  pops up over whatever you are doing in Umbra (not over the asking agent's chat, where it appears inline), as a
  notification you can answer from the shade (one tap for up to three options, a reply field for a typed answer), and
  on Telegram. The session waits for the answer; Later puts the pop-up away without answering.
- **Telegram does much more.** Cards arrive as formatted messages with buttons for their choices, checklists, ratings
  and sliders. New commands: /dashboards (open one, refresh it, pause it), /skills and /findskill (install a skill from
  GitHub), /sites (published pages), /memory, /timers and /soul (accept or decline a SOUL suggestion; new suggestions
  arrive on their own). Agents' questions come with answer buttons, or you reply to the message with your answer. An
  HTML page an agent sends has a Publish button. Suggestions read like the Briefing (why now, what will happen, what it
  needs from you) instead of raw action data. The command menu is in English and Chinese.

## Verification

- Unit tests: 1,085 passed, 0 failed (`:app:testDebugUnitTest` 367, `:core:orchestrator` 121, `:core:session` 60,
  `:core:perf` 25, `:core:recovery` 19, `:platform:logs` 36, and the other core modules from their saved results).
- On the emulator (umbra36, real app, real omp in the Linux guest; only the model scripted where a model was needed):
  - **Cards**: an agent called `show_card` and the flight card was drawn in its chat; a poll answered in the chat
    reached the model as the user's message "[Card · Team lunch] Thai" and the block locked; the card guide was in the
    system prompt. All 100 templates drawn from their examples in Developer → Card templates and reviewed by eye; the
    review fixed stray end lines, clipped two-column comparisons, percentages drawn against the largest value instead
    of 100, a highlighted column drawn shorter than its value, and lists told apart by colour alone.
  - **Dashboards**: created through `dashboard_create` with tiles bound to a real JSON file in a project folder;
    rewriting the file updated "Spent", "Budget used" and "By category" on the open page within seconds, without a
    model; the daily refresh shows as the agent's timer; history reads "budget.json changed".
  - **Skills**: real GitHub search, a collection page, SKILL.md; from the Skills page the agent ran `skill_read` and
    `skill_install` and the skill landed in `/workspace/.omp/skills` with no prompt (the user's pick is the go-ahead);
    an install the agent asked for on its own waited for "Install" in its chat, then landed; the Installed list and the
    per-session picker ("Skills · 1") work.
  - **Publishing**: the Publish chip on an HTML preview, the Cloudflare connect sheet, Settings → Publishing; the
    preview now loads the page's own stylesheet and images from its folder.
- Found and fixed while verifying: a single HTML file published without the files it references (a Kotlin scope
  mistake); the resource governor refusing new tasks for up to a minute and a half after a memory squeeze; refused
  cards still drawn; wide dashboard tiles drawn empty; a lone figure clipped to "51"; raw byte counts in a dashboard's
  history; agents' chat folders and leftover folders offered as skill destinations; approvals below the fold; dashboard
  folder and session tiles showing byte counts, ISO timestamps and session ids.
- Release review (Claude Haiku 5.5, extra-high effort): the first pass failed on four defects, all fixed with tests:
  a Laya file that failed to download ended the load before the GPU or CPU was tried; deleting a memory row could
  delete a different fact if the agent wrote memory in between (now by id and text); an agent removing its dashboard
  left a pending wake timer; card images could follow a redirect from https to plain http. BLAKE3 (the Pages upload
  keys) is now checked against all 35 official test vectors, up to 102,400 bytes. Also tightened: a new fact flagged
  every older fact about the user as possibly outdated; now only similar facts are flagged. The second pass (with
  questions and Telegram) failed on one defect, fixed: tapping the same option twice in a multi-question Telegram
  ask made Telegram refuse an unchanged edit, and that update was then retried forever, stalling the bot. Now a refused
  edit is ignored and an update that fails the same way every time is skipped (a dropped connection still retries).
  Also from that pass: question notifications left over from before a restart are cleared, and a card image whose
  address carries a key or stored credential is not fetched.
- **Questions** (an agent using omp's real `ask` tool with a scripted model): an option question popped up over Home,
  fully open, with the recommended answer marked, and one tap reached the model ("User selected: Italian"); a text
  question asked while Umbra was in the background posted a notification whose reply field answered it ("19:30"); an
  option question was answered from its notification button ("Book it"); both notifications went away after the
  answer; with the agent's chat open, the question showed in the chat instead of popping up and the typed answer
  reached the model. Found and fixed on the way: Android keeps a notification answered from its reply field and does
  not let the app cancel it, so it now turns into a quiet "Answered" that times out; the pop-up opened half-way with
  Skip and Later below the fold; the agent's header said "Waiting for approval" while it waited for an answer.
- **Telegram**: covered by unit tests (card rendering for every block kind, HTML escaping and splitting, button data
  within Telegram's 64 bytes, question keyboards and multi-question answers, plain-language suggestions without raw
  data, command parsing); not tried against a live bot.
- Also checked on the emulator after the review: SOUL suggestions accepted into SOUL.md; memory repeats confirmed and
  replaced facts kept as history; the Briefing's long actions stack instead of being cut; the heartbeat alarm does not
  wake the phone while timers stay exact; accessibility listens to window and text events only; the device-log reader
  stops when no task runs; a dashboard on a folder updates when a file is added.

## Not verified yet

- A live Cloudflare Pages deploy (needs the owner's account and token); the client is covered by tests with a fake
  transport (BLAKE3 vectors, file selection, request bodies, republish to the same project, take-down).
- The Telegram bot against Telegram itself (needs the owner's bot); the new commands, buttons and question replies
  are covered by unit tests only.
- Laya on a physical phone's NPU or GPU (no phone here); the reference check guards every path.
- graphify inside the Linux guest on a phone (verified on this Mac: build, query and the app's graph format).
- Everything on a physical phone: these checks ran on the emulator.
