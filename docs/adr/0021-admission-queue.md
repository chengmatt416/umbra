# 0021. Admission queue: logical agents wait, processes stay bounded

Status: accepted. Architecture spec §5, AC-022. Builds on the resource governor (functional spec §31) and the
resource policy's project budgets.

## Context

A request the phone could not run at once was refused: "the phone is already running as many agent sessions as it
can handle". AC-022 asks for the opposite behaviour under load (100 logical agents on a constrained device): admission
limits the processes that actually run, the rest wait, the queue and its fairness can be seen, and surviving without
an out-of-memory kill is not what counts as success. The queue draining is.

## Decision

1. **A queued request is a session without a process.** `AgentSupervisor.submit` starts the request when the governor
   (memory, storage, thermal), the project's budget and the shared services admit it and nothing waits ahead of it;
   otherwise it creates the session and marks it `queuedAtMs`. It has no omp process, holds no slot and uses no
   memory beyond its record. New work from the home composer and the Capsule is submitted; `launch` keeps its refuse-now
   meaning for callers that want it.
2. **Fair order** (`AdmissionOrder`): the next session comes from the owner (a project, or the independent sessions)
   with the fewest running now, and within it the one waiting longest. An owner whose budget is full is passed over
   without blocking the others. With one slot this is plain longest-waiting first, since nothing runs at the moment of
   choice.
3. **Admission is driven by change**: a session ending, and a check every two seconds (pressure easing, a lockdown
   lifted, a service recovered). Each admission takes a governor slot through the same `send` path as any start, so
   the bound on processes is the governor's, unchanged. A start that loses its slot in between goes back to its place.
4. **Bounded**: at most 256 wait; beyond that, and for a request with pictures (they are not kept for later), the
   request is refused with the reason. During an emergency lockdown nothing is admitted and nothing new is accepted.
   Stop everything empties the queue. A queued session can be taken out of the queue (it is stopped before it ran).
5. **Visible**: sessions show "Queued"; the session view says why it waits; Resource Manager's Admission card shows
   running against slots, each owner's running and waiting counts and oldest wait, the reason, and the forecast order
   (labelled as a forecast: a finished session elsewhere changes it).

## Consequences

* A queued request survives an app restart (it is a stored session) and is admitted after it.
* The governor still decides how many run; on a 2 GB emulator that is one or two, moving with memory pressure.
* Verified: `AdmissionOrderTest` (100 logical agents over three owners and three slots: never more than three
  running, the first nine starts three per owner although one project queued 70 first, longest-waiting first within
  an owner, a full budget passed over), `AdmissionTest.requestsBeyondTheSlotsWaitAndAreAdmittedFairly` (real omp, two
  slots, six requests: four queued, never more than two running, the independent request admitted before the busy
  project's third); on the emulator five requests with one to two slots under real memory pressure: four queued, all
  completed, no start beyond the slots then in force, the Admission card showing the queue and its reason.
