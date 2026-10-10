# 0013. The omp GUI

Status: accepted.

## Context

The app must be a GUI for omp that can do everything omp can: chat with tools, thinking and pictures, slash commands,
`!` bash, models and thinking levels, the session tree with branch and fork, compaction, todos, subagents, sign-in,
export. omp's terminal UI cannot be the app's UI, and a second implementation of omp's logic would drift from it.

## Decision

1. omp runs in `--mode rpc-ui`; the app speaks its RPC protocol (`runtime/omp`, `OmpClient`) and never reimplements
   what omp does.
2. **The conversation is omp's own transcript** of the current branch, plus what only the client saw (command output,
   notices), folded by a reducer into `ConversationState` (`core/orchestrator/Conversation.kt`): live while the
   session's omp runs, the saved transcript otherwise.
3. Commands go through `AgentSupervisor.omp { … }`: it starts the session's omp when needed (without sending it
   anything) and releases a process started only for a command when the session stays idle. After a command that
   changes the transcript (branch, fork, switch, new) the GUI reads it again.
4. omp's terminal UI is still available, in a terminal (`OmpTui`), for a session the GUI is not driving at the time.

## Consequences

* New omp features appear in the GUI through the same protocol; the pinned omp version (18.6.1) is what the tests
  run against (`scripts/omp-pinned.sh`).
* Verified against the real omp on the host (`OmpGuiTest`) and on the emulator by driving the app
  (`scripts/ondevice-omp-gui.py`).
