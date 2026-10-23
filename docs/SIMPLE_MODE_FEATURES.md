# Umbra Simple Mode Features

This guide lists the features available in Umbra's **Simple** interface, in plain language. It describes the current repository, including the session-file and assistant work recorded under 0.4.1. Some features need permissions, a connected account, or extra setup.

To choose this mode, open **Settings → Simple**.

## 1. Get started

- Follow the setup screens to choose a mode, connect an AI account, and grant permissions.
- Enable accessibility so Umbra can read and operate apps.
- Allow the floating Capsule to appear over other apps.
- Allow notifications for background work and approval requests.
- Set up the bundled Linux environment used by the agent. The bundled installation works without downloading the environment separately.
- Use Umbra with or without root. Some phone-control features need the privileged helper, started through root, Shizuku, or ADB.
- Connect an AI model before asking the agent to work. Most model providers need internet access.

## 2. Home screen

- Switch between **Agents** and **Coding**. Umbra remembers the selected tab.
- Type a request in the message field.
- Choose a model and thinking effort using the chips below the message field, for the default assistant or the next coding task.
- Use voice input when Android speech recognition is available.
- Review or remove attached screen-context items before sending.
- In Agents, send the request to your default assistant and open its chat. Umbra creates a default assistant when needed.
- In Coding, start a coding task directly from the message field.
- See coding tasks that are working, paused, waiting for you, or queued.
- See the task's current step or status and when it was last updated.
- Open a task to continue its conversation.
- See up to eight recently finished coding tasks on Home.
- Review pending approvals from a task card. Deny there, or approve there when the request allows a single tap; other requests open for review.

The floating menu contains **Select screen**, **Accounts & models**, and **Settings**, plus two icon buttons for a new coding session and a new agent. Left-handed mode moves and mirrors these controls.

## 3. Create a personal assistant

- Create multiple assistants with separate conversations and settings.
- Give each assistant a name and choose an avatar or a letter avatar.
- Choose a role template and edit its starting instructions.
- Choose which message apps and notifications it watches. A source also needs a working connection and permission.
- Choose its AI model and thinking effort, or leave them on Auto.
- Start the assistant and let it check for work in the background.
- Use the default 10-minute wake interval shown in Simple mode. Changing the interval requires Power mode.

## 4. Assistant chat and profile

- Talk to an assistant in its own chat and send follow-up requests.
- See broad activity labels and an animated thinking indicator while it works.
- Answer its questions and review approval requests.
- Attach pictures, files, or folders through the chat's attachment controls.
- Let the assistant hand coding work to a separate coding session.
- Tap a delegation or report event to open the related coding work.
- Receive the coding result back in the assistant conversation.
- Rename the assistant or change its avatar.
- Start or stop the assistant, or delete it after confirmation.
- Choose which assistant is the default for quick requests.
- Change its model and thinking effort from its profile.

### Instructions and memory

- Read and edit the assistant's instructions, stored as `SOUL.md`.
- Browse and search its saved memory.
- Add, edit, or remove memory entries.
- Edit the full memory file when needed.
- Forget memories from a selected date range.

### Timers, rules, and background work

- View and manage scheduled reminders and timers.
- Add scheduled work with a note and time.
- Add work to the assistant's queue.
- Add rules that match a source and message text, then tell the assistant what to do.
- Choose the sources each assistant watches.
- Let the optional Laya decision service choose relevant assistants among those subscribed to a source, including several assistants when appropriate.
- Keep explicit routing rules in force. If Laya is unavailable or unsure, subscriptions remain the fallback.
- Process ordinary notification updates during background checks; incoming messages can trigger immediate work.
- Keep routine wake summaries out of the chat and avoid generic completion notifications for assistant turns.

### Wake and battery settings

- See today's wake count, run count, and estimated battery use.
- Enable slower background checks when the phone is hot.
- Enable pausing when the battery is low.
- Set quiet hours for background wake behavior.
- Open Android's battery-optimization settings to allow background operation.
- View the wake interval; its slider is disabled in Simple mode.

## 5. Coding sessions

### Start a session

- Start quickly from Home, or use **New coding session** for more choices.
- Choose an existing workspace or an independent session.
- Choose the AI model and thinking effort, including Auto.
- Attach a file or connect a device folder before starting.
- Choose browser behavior: **Auto**, **DOM** for page elements, or **Visual** for screenshots and clicks.
- Choose an assistant to receive the result, or choose nobody.
- Describe the task and start it.

### Work in chat

- Read messages, results, and compact summaries of tool activity.
- Send follow-up instructions, pictures, and file references.
- Answer agent questions and approve or deny requested actions.
- Watch an available live display preview and expand it.
- Open related parent or child sessions.
- See when a task is queued, why it is waiting, and remove it from the queue.
- Use the session menu to pause, resume, stop, run again, or delete a session.
- Open the session directory and conversation controls.
- Set a resume contract: the conditions under which the task may resume automatically after a restart.
- Keep saved conversation history across app restarts. Automatic continuation depends on the resume contract.

Simple mode shows the conversation view with compact tool details. The Activity, Terminal, and Changes tabs and technical header controls are hidden. The session menu still includes an action to open the coding agent's terminal interface, but Simple mode keeps the page on Chat; use Power mode to view the terminal tab.

### What the agent can do

- Write, edit, build, and test code in the phone's Linux environment.
- Run shell commands and use installed development tools.
- Work with Git repositories and project files.
- Use the browser when its package is installed. Visual browsing also needs the desktop environment.
- Read the phone screen and screenshots, tap controls, type, scroll, and open apps.
- Use available notification, clipboard, file, package, process, system-information, and log tools, subject to permissions and approval rules.
- Check the result of phone actions and inspect crashes or logs when access is available.
- Work on a background display where supported, while you continue using the phone.

These capabilities depend on setup and installed tools. Simple mode changes the interface; it does not remove the agent's underlying coding and phone tools.

## 6. Files, folders, and recovery

- Use the paperclip to choose a picture, device file, or folder through Android's picker.
- Attach a device file through a shortcut to its original. Edits can change that original file.
- Connect a device folder as the session's working directory.
- Import cloud documents as copies.
- Grant Android file access when direct access to originals requires it.
- Change connected files or folders while the session is idle or paused.
- Insert a connected file's reference into your message.
- Keep Git protection enabled by default, or change its switch.
- Save private checkpoints before work and after turns, including ignored files and uncommitted changes.
- Preserve an existing repository's staging index and current commit while making private checkpoints.
- Browse checkpoint history and recover into a separate managed workspace.
- Disconnect managed file or folder connections while preserving originals and checkpoint history.
- Keep checkpoint history usable after restart or after original-file access is removed.
- Carry connected-file protection into delegated coding work.

## 7. Floating Capsule

- Keep a small floating control near the camera area while using other apps.
- Expand or collapse it to see current work.
- See the task title, elapsed time, current step, and progress summary.
- Type or dictate a request without returning to the full app.
- Talk to the default assistant from the idle Capsule when assistant routing is enabled.
- Review pending approvals; approve supported requests once, deny them, or open the app for fuller review.
- Open questions that need your answer.
- Open a failed task or dismiss its failure notice.
- Open recent tasks when the idle Capsule is in task mode.

### Select screen

- Start a screen selection from the menu or Capsule.
- Circle part of another app's screen.
- Attach the selected picture and available text to a request.
- Add an instruction, review the context item, and send it.

### Screen recording

- Start a recording from the Capsule after Android asks for screen-capture permission.
- Tap the Capsule to stop; recordings also stop automatically after three minutes.
- Use the recording as context for a request.
- Give the model a sheet of sampled frames with timestamps and a screen-text timeline, rather than assuming it receives the full video.

Simple mode hides the Capsule's multi-task switching strip.

## 8. Message apps

Open **Settings → Message apps**, or an assistant's message-source settings.

- Configure sources for SMS, WhatsApp, LINE, Telegram, Messenger, Instagram, Snapchat, X, and iMessage through BlueBubbles/OpenBubbles.
- Choose available connection methods and review their setup state. A listed method does not mean its transport is implemented.
- Let assistants list and read messages exposed by working methods.
- Send through supported methods after the required approval.
- Set per-chat reply permissions. Only explicitly permitted, known one-to-one chats can skip approval; groups and unknown chat types ask.
- Treat a successful send as submitted or accepted. Umbra does not claim confirmed delivery or automatically retry a send.

| Method | Current behavior |
| --- | --- |
| Android notifications | Read retained notification content; reply when the notification exposes a supported reply action. This does not provide full account history. |
| SMS | Read and send through Android with SMS permissions; incoming SMS can reach subscribed assistants. Verification-code SMS are excluded. |
| BlueBubbles/OpenBubbles API | Read and send text with a configured HTTPS server and password. |
| X API | Read and send text with a configured user token. Sends always require approval. |
| Visual phone tools | Operate an installed app through normal screen tools. The dedicated resumable visual messaging worker is unfinished. |
| Other API or unofficial methods | Some setup choices are present, but the remaining transports and login flows are unfinished. |

Background message-app actions yield while you use that app and for five seconds after you leave it. Active sessions are prevented from operating the same messaging app on different displays at the same time.

## 9. Notification reader

Open **Settings → Notifications** and grant Android notification access.

- Choose a policy for each app: **Use**, **Context**, or **Ignore**.
- Let subscribed assistants query the retained notification log.
- Browse retained notifications in the app.
- Choose retention of one, seven, or thirty days; the default is seven.
- Forget retained records through a confirmation flow.
- Delete an app's retained records by setting it to Ignore.
- Ignore verification-code notifications by default. If explicitly enabled, redact codes before saving.
- Ignore financial and authentication apps by default.
- Encrypt retained notification content on the phone using an Android Keystore-backed key. Retention timestamps and record digests remain metadata.

## 10. Telegram remote control

Open an assistant's **Telegram** page.

- Connect your own Telegram bot token.
- Link one Telegram account through a temporary one-time link.
- Send text, photos, documents, and voice notes to the selected assistant. Voice transcription depends on the available transcription setup.
- Receive replies, reminders, and approval alerts in Telegram.
- Approve, decline, or request changes through supported approval buttons.
- Use `/status` to check work and `/stop` to stop the selected assistant's current work.
- Use `/agents` to switch assistants when switching is enabled.
- Configure bot options or unlink the account.
- Keep Telegram-originated replies and alerts on Telegram until a new request arrives in the app.

The bot implementation exists, but the repository's handoff notes still list real-account testing of voice, media, and remote approvals as outstanding.

## 11. AI accounts and models

- Open **Accounts & models** from the floating menu or Settings.
- Connect supported providers using account sign-in where offered or an API key.
- Open or copy a sign-in link, cancel sign-in, sign out, or remove a saved key.
- Add other providers through the model configuration screen, including compatible custom endpoints.
- Use providers such as Anthropic, OpenAI, Google, and OpenRouter.
- Choose the default model in Settings, or use automatic model selection.
- Choose model and thinking effort separately for each assistant or new coding session.
- Use only the thinking levels supported by the selected model.

## 12. Appearance and general settings

- Switch between Simple, Power, and Dev modes.
- Choose the Porcelain, Liquid, or Apple theme.
- Choose light, dark, or system appearance.
- Customize available theme colors and accents, and reduce motion.
- Try sample controls in the appearance preview.
- Choose a left-handed or right-handed layout.
- Turn the floating Capsule on or off.
- Review required permissions and open Android's permission screens.
- Use English or Traditional Chinese; follow the phone language or select an app language in Android settings.
- Enable, install, start, or stop the optional Laya decision service through its settings.
- Save standing instructions for future work.
- Choose whether approval requests create notifications.
- Enable or disable live screen context.

## 13. Approvals and taking back control

Open **Settings → More settings** for these controls.

- Choose **Cautious**, **Balanced**, or **Autonomous** approval behavior.
- Review the approval history, including requests and your decisions.
- Approve or deny requests from supported chat, Home, notification, Capsule, and linked Telegram surfaces. Higher-risk requests may need fuller review.
- Keep mandatory approval rules for sensitive actions. Choosing Autonomous does not grant unrestricted access.
- Prevent the agent from operating Umbra itself or answering its own approval prompts.
- Pause, resume, or stop a task from available session and notification controls.
- Stop all work through the global stop control.
- Revoke helper access.
- Turn screen control off.
- Delete saved history after confirmation.
- Keep emergency input restrictions in effect across restarts when a lockdown is active.

Taking back screen input blocks further agent input; it does not by itself stop shell commands already running. The separate staged Execution Halt controller described in the architecture specification is not built.

## 14. App updates and helper settings

- See the installed app version and update state.
- Check for updates manually.
- Enable or disable automatic updates and Wi-Fi-only automatic downloads.
- Resume an interrupted APK download.
- Check the APK's size, checksum, package, version, and signing identity before installation.
- Install when tasks are idle, through the helper or root when available, otherwise through Android's installer.
- Review and start the privileged helper from More settings.

## 15. What Simple mode hides

The normal Simple menu does not expose workspace management, screen management, distributed nodes, the Safety hub, or the Developer hub. The main Settings page also hides its direct Linux shortcut.

However, **Settings → More settings** still provides these pages without switching modes:

- **Linux setup:** check setup requirements, install or update the bundled environment, install from a file or HTTPS URL, and remove the environment after confirmation. Install individual add-on packs or all missing packs for Android builds, browser, development tools, desktop, Java, and Node.
- **Workspaces:** see storage use and linked tasks, browse folders, preview supported files, share individual files or a ZIP, and delete a workspace after confirmation when no unfinished task uses it.
- **Processes:** inspect running apps and Linux processes, close an app, or end a Linux process when the needed access is available.
- **Displays:** inspect available displays and previews, and remove supported secondary displays.
- **Diagnostics:** run or cancel the built-in checks, see passed/failed/skipped results, and copy the report.

Simple mode hides Home's task-filter chips, extra task metadata, the workspace chip in Home's composer, and the coding session's technical tabs and header. Model and effort chips remain available on Home; workspace selection is also available in the new-session sheet.

Use Power or Dev mode for normal navigation to the dedicated terminal, planning, resources, runtime, shared-service, repository publishing, remote-node, credential-vault, and security-evidence dashboards. Some screens can also open through existing links or notifications.

## 16. Current limits

- Android 16 is required by the current project.
- Browser operation needs the browser package; full-session browser and Android visual-desktop testing remains incomplete in the implementation notes.
- Real-time API subscriptions, complete encrypted message history, Telegram user-account login, professional Meta APIs, and unofficial client login flows remain unfinished.
- Quiet-hour wake controls exist, but the proposed messaging worker's complete call and quiet-hour checks, safe draft cleanup, and automatic resume are unfinished.
- Remote VNC desktop control, QR pairing, the Go node rewrite, and desktop installers are planned work, not current Simple-mode features.

## Sources

- [Project overview and requirements](../README.md)
- [Feature and verification map](traceability.md)
- [Assistant and messaging implementation status](design/agents-implementation-status.md)
- [Mode-dependent navigation](../app/src/main/kotlin/app/umbra/agent/ui/shell/PorcelainApp.kt)
- [Home screen](../app/src/main/kotlin/app/umbra/agent/ui/home/HomeScreen.kt)
- [New agent and coding-session controls](../app/src/main/kotlin/app/umbra/agent/ui/agents/NewSessionSheets.kt)
- [Assistant profile, memory, schedules, and wake settings](../app/src/main/kotlin/app/umbra/agent/ui/agents/AgentPages.kt)
- [Coding conversation and mode restrictions](../app/src/main/kotlin/app/umbra/agent/ui/current/CurrentScreen.kt)
- [File attachment and recovery controls](../app/src/main/kotlin/app/umbra/agent/ui/current/SessionFileButton.kt)
- [Capsule controls](../app/src/main/kotlin/app/umbra/agent/capsule/CapsuleUi.kt)
- [Main settings](../app/src/main/kotlin/app/umbra/agent/ui/settings/SettingsScreen.kt)
- [More settings and safety controls](../app/src/main/kotlin/app/umbra/agent/ui/SettingsScreen.kt)
