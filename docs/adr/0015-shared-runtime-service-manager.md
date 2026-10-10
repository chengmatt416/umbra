# 0015. The Shared Runtime Service Manager

Status: accepted. Architecture spec §9. Builds on the health supervisor (functional spec §30).

## Context

Agents and terminals share processes: the Linux environment's mount, the privileged and root helpers, one tmux
server per terminal group, the X desktop, the Laya sidecar. The health supervisor already probed subsystems and
restarted them with backoff and an in-memory circuit breaker. The spec asks for more: a logical service kept apart from
the process instance behind it, reference leases, draining that closes admission first, recovery only while something
needs the service and by one fenced owner at a time, a breaker and restart budget that a restart of the app does not
clear, health judged at four levels with no probe that changes anything, staged reattachment after a recovery, and an
elastic pool only for services that may be replicated.

A second recovery loop beside the supervisor would contradict "one recovery owner per service".

## Decision

1. **`SharedServices` (core/control) is the persisted authority, and the supervisor's `RecoveryGate`.** Before any
   restart the supervisor asks it for a permit; it refuses while the service drains, while no registered reference
   needs it, while its breaker is open, while another owner's lease is fresh, or once the restart budget (3 in 10
   minutes) is used, which opens the breaker. A permit carries an epoch; an outcome for an older epoch is ignored.
   Every probe's verdict reaches it: a half-open breaker closes after 3 healthy probes in a row. Cooldowns double with
   each opening, with jitter. Only an explicit, journaled reset closes a breaker by hand.
2. **References are the consumers Umbra knows of**, synced from live state: live sessions and open terminals hold the
   Linux environment, a group's terminals hold its tmux server, the decision router holds Laya while it serves.
   Which sessions use the X desktop is not tracked, so none is claimed. Zero references is never shown as proof that
   nothing uses a service.
3. **Draining closes admission and stops nothing.** New sessions and new terminals are refused with the reason; the
   work already holding the service keeps it. Nothing on the screen stops a process.
4. **Probes have no side effects.** For example: Linux: mounted, a command runs, omp and tmux are present, an echo
   round trip. Helpers: hello, a command, the expected uid (their process is only known by what they report: Android
   hides other users' processes). tmux: the server's pid in `/proc`, its socket lists sessions, the anchor session and
   Umbra's control clients, a `display-message` round trip. Laya's level 4 is not probed: a decision would run the
   model. A new process instance is journaled; the logical service stays.
5. **Staged reattachment**: after a recovery, new work is let into the service one, then two, four… per 10 s stage.
   A check made before a session exists does not count toward the ramp.
6. **No elastic pool**: every service here is a singleton (a mount, a display, privileged helpers, stateful tmux
   servers, one model in memory), and the screen says why for each.
7. A start the lockdown, the resource policy or a draining service would refuse is refused before a session exists.

## Consequences

* Services the supervisor does not restart (the root helper, the X desktop, Laya, tmux servers) are still probed,
  referenced and drainable; their recovery stays manual and the screen says how.
* Verified on the emulator: all levels for each service, the helper recovered by the supervisor through the gate
  (epoch 1), a remount attempt before root was back counted against the budget, a new root-helper process journaled
  as a new instance, draining Linux refusing a new session and a new terminal without leaving a failed session, and
  reopening admission.
