# Traceability: spec section → code → verification

"Spec" is `ANDROID_ON_DEVICE_AGENT_FUNCTIONAL_SPEC.md`. Verification levels:

* **unit**: pure logic, runs anywhere (`./gradlew check`);
* **omp**: the real omp binary on the host against a scripted model endpoint (skipped without omp);
* **emulator**: live tests against a real Android 16 arm64 emulator through adb (skipped without a device);
* **phone**: can only be shown on a real phone; `docs/ONDEVICE_TEST_PLAN.md` says how.

| § | Requirement | Where | Verified by |
| --- | --- | --- | --- |
| 1, 2 | Product, Android 16 only | `build-logic` (minSdk 36), `README.md` | build |
| 3 | omp runtime, persistent sessions, locked version | `runtime/omp` (RPC client, config writer, spawner), `core/orchestrator`, omp 18.6.1 pinned in `runtime-build/rootfs/Dockerfile` | unit (`OmpClientProtocolTest`, `RpcFrameDecoderTest`, `OmpConfigTest`), omp (`OmpIntegrationTest`, `AgentSessionTest`), emulator (`GuestOmpLiveTest`; whole agent runs through the installed app: `scripts/ondevice-scripted-agent.py`, `ondevice-scenario.py`) |
| 4 | Fast decision engine | `core/decision` (router, heuristics, `LayaHttpEngine`, `SwitchableEngine`), `app/.../LayaSidecar.kt`, `decide` tool | unit (`DecisionRouterTest`, `HeuristicDecisionEngineTest`, `LayaHttpEngineTest`); Laya sidecar install/start: phone (needs ~2 GB) |
| 5 | Android control | `platform/*`, `tools` (`UiExecutor`, `SystemExecutors`) | emulator (`DeviceBackendsLiveTest`, `HostSideLiveTest`, `OnDeviceLiveTest`) |
| 6 | Accessibility computer use | `platform/android` (`AccessibilityUiBackend`), `tools/ElementTargeting`, `core/model` (resolver) | unit (`ElementResolverTest`), emulator (`OnDeviceLiveTest`) |
| 7 | Visual computer use | `platform/vision` (marks, grids), `android_screenshot` | unit (`VisionTest`), emulator |
| 8 | Shizuku | `app/.../ShizukuLauncher.kt`, `platform/server/HelperUserService` | compiles; **phone** (needs Shizuku running). The helper it hosts is the one tested in `PrivilegedHelperLiveTest` |
| 9 | Root | `ProcessShell.root()`, `FirstAvailableShell`, `RootViaHelperShell` (ADR 0008), `platform/server` | unit (`FirstAvailableShellTest`), emulator (`PrivilegedHelperLiveTest`, the Linux environment installed through the app by way of the root helper) |
| 10 | Background workspace | `platform/server` (`VirtualDisplays`), `tools/DisplayLeaseManager`, `DisplaysScreen` | emulator (`PrivilegedHelperLiveTest`, `OnDeviceLiveTest`) |
| 11 | Linux CLI runtime | `runtime/linux` (`RootChrootRuntime`, `GuestOmpSpawner`, `GuestFileSet`), `runtime-build/rootfs`, `guest/` (scripts and packs, carried by the app: ADR 0009) | unit (`GuestFileSetTest`, `ProcessTableTest`), emulator (`RootChrootRuntimeLiveTest` incl. the root-owned file sync, `GuestOmpLiveTest`) |
| 12 | Linux graphical runtime | `guest/packs/gui.apt`, `guest/bin/umbra-gui` (Xvfb, openbox, AT-SPI) | emulator (`GuestGuiLiveTest`; `scripts/ondevice-scripted-gui.py` through the app) |
| 13 | Linux computer use | `runtime/linux/GuestGuiBackend`, `gui_*` tools | emulator (`GuestGuiLiveTest`; `scripts/ondevice-scripted-gui.py`) |
| 14 | Unified tool system | `tools/HostToolRegistry`, `UmbraToolHost`, `ToolBridgeServer` | omp (`AgentSessionTest.aHostToolCallIsRecorded…`), emulator |
| 15, 16 | Unified observation and action model | `core/model` | unit (`ActionSerializationTest`, `UiTreeRendererTest`) |
| 17 | Action verification | `tools/Verifier`, `tools/ActionPipeline` | emulator (`HostSideLiveTest`, `OnDeviceLiveTest`) |
| 18 | Log monitoring | `platform/logs` (`LogMonitor`, `CrashDetector`) | unit (`LogParsingTest`, `CrashDetectorTest`, `LogMonitorTest`), emulator |
| 19, 20 | Coding agent, autonomous development loop | omp built-ins in the guest; system prompt in `core/orchestrator/AgentPrompts`; `fixtures/crashy-login` | phone/emulator with a model: `scripts/acceptance-crashy-login.sh` |
| 21 | Multi-agent | omp `task` subagents, surfaced by `TimelineRecorder` and `ActivityCard`; several sessions run at once under `ResourceGovernor` | omp (subagent events handled), unit (`ResourceGovernorTest`); real subagent runs: phone with a model |
| 22 | Workspace management | `core/workspace`, `WorkspacesScreen` | unit (`WorkspaceManagerTest`) |
| 23 | Model providers | `runtime/omp/OmpConfig`, `app/.../AppSettings.kt`, `SecretStore`, `ModelsScreen` | unit (`OmpConfigTest`), emulator (`DiagnosticsScreen` model step with a key) |
| 24 | Session management | `core/session`, `core/orchestrator` | unit (`SessionStateMachineTest`, `FileSessionStoreTest`), omp (`AgentSessionTest`: complete, follow-up, pause/resume, stop, crash recovery, restart recovery) |
| 25 | Agent UI | `app/.../ui` | built; inspected on the emulator by screenshot: home, set up, settings, timeline, approval and question cards (see test plan) |
| 26 | Floating overlay → the Capsule (architecture spec §3.1, ADR 0014) | `app/.../capsule/` (`Capsule`, `CapsuleUi`, `ScreenContext`, `OverlayWindow`), `core/model/.../ScreenSelection.kt` (`Lasso`), `core/session/Trails.kt` | unit (`ScreenSelectionTest`, `TrailsTest`), emulator (camera-anchored capsule, composer without forced keyboard, Snapshot selection over Settings reaching a scripted model as picture and text, Previous / Current / Next from omp's todo plan, approval denied from the capsule) |
| 27 | Persistent notification | `app/.../AgentService.kt` | emulator (`scripts/ondevice-scenario.py`: Pause, Resume and Stop from the notification, approval buttons) |
| 28 | Approval and safety, what the agent can reach | `core/safety`, `core/orchestrator/OmpApprovals`, `AppApprovalLedger`, `RootChrootRuntime.secure()` and the other hardening (ADR 0010) | unit (`RiskClassifierTest`, `ShellRiskAnalyzerTest`, `UiRiskAnalyzerTest`, `SafetyGateTest`, `ApprovalPolicyTest`, `ScreenGuardTest`), omp (`AgentSessionTest`: ask, deny, approve, self-protection), emulator (a package removal is classified high risk, waits, and is denied from the app, the notification and the floating card; `RootChrootRuntimeLiveTest.theAgentCannotChangeTheSystemPartOfTheGuest`, `scripts/ondevice-confinement.py`) |
| 29 | Capability detection | `app/.../Probes.kt`, `core/model/capability`, `CapabilityProbe`, `SelfTest` | unit (`CapabilityDetectorTest`, `StrategySelectorTest`), emulator (diagnostics) |
| 30 | Recovery and reliability | `core/recovery`, `app/.../Subsystems.kt`, `AgentSession` (process restart, provider retry), `AgentSupervisor.recover` | unit (`HealthSupervisorTest`), omp (`AgentSessionTest`), emulator (`scripts/ondevice-recovery.py`: helper killed, agent process killed, app killed mid-task) |
| 30a | Shared Runtime Service Manager (architecture spec §9, ADR 0015) | `core/control/SharedServices.kt` (references, admission, persisted breaker and budget, fenced recovery owner, staged reattachment), `core/recovery` (`RecoveryGate`), `app/.../services/ServiceMonitor.kt` (L1–L4 probes), `ui/services/SharedServicesScreen.kt`, `TerminalService.probeGroup` | unit (`SharedServicesTest`, `HealthSupervisorTest.aGateHolds…`, `AdmissionTest`), emulator (levels of every service, helper recovered through the gate, draining refusing new sessions and terminals) |
| 30b | Credential Vault (architecture spec §12, ADR 0016) | `core/control/CredentialVault.kt` (scopes, exposure grants in a hash-chained journal, rotation and revocation), `data/SecretStore.kt` (`custody()`), `AppProviderResolver` (scopes applied), `ui/vault/CredentialVaultScreen.kt` | unit (`CredentialVaultTest`, incl. tampering detected), emulator (keystore level, exposures per session, the three scopes applied to real sessions) |
| 30c | Commit & Integration (architecture spec §11, ADR 0017) | `core/control/CommitGate.kt`, `CommandGit.kt`, `app/.../integration/Repositories.kt`, `ui/integration/CommitScreen.kt` | unit with real git (`CommitGateTest`), emulator (publish and read back in the guest; a moved branch refused) |
| 30g | Git credential broker (architecture spec §12, AC-015, ADR 0022) | `core/control/GitCredentials.kt` (`GitCredentials`, `GitBrokerScript`), `BrokeredGit.kt`, `app/.../integration/Repositories.kt` (`brokeredGit`, `brokerUnavailable`), `ui/vault/GitCredentialsCard.kt`, the commit screen's "Propose via" and "Fetch" | unit/integration with real git over HTTPS (`GitBrokerTest`), emulator ↔ a private HTTPS git server on the Mac (fetch and publish through the broker; an agent-side scan of `/proc` and files across the push finds no token and no root process) |
| 30h | Vault recovery (architecture spec §12.3, AC-018, ADR 0023) | `core/control/VaultBackup.kt`, `PassphraseSeal` (`SecurityEvidence.kt`), `CredentialVault.restored`, `GitCredentials` (`publishSuspended`), `CommitGate` (`WriteNotSent`), `ui/vault/RecoveryCard.kt` | unit (`VaultBackupTest`, `GitBrokerTest`), emulator (backup through the save dialog, restore through the picker, a wrong code refused, a restored credential refused to publish until granted, then publishing) |
| 30d | Distributed Nodes (architecture spec §10, ADR 0018) | `core/nodes` (pairing proofs, pinned TLS, manifests, RSP channel), `node-runner` (`umbra-node`), `core/control/NodeRegistry.kt` (incl. `NodeRevocation`), `app/.../nodes/NodeConnections.kt`, `ui/nodes/NodesScreen.kt` | unit/integration on the host (`NodeEndToEndTest`, `NodeRegistryTest`), emulator ↔ `umbra-node` on a Mac (pair, preflight, a job with manifest files, retention, revocation pending while offline then acknowledged) |
| 30e | Repository Hub (architecture spec §2.1, ADR 0019) | `core/control/RepositoryHub.kt`, `ui/integration/RepositoryHubScreen.kt`; policy enforced in `CommitGate` | unit with real git (`RepositoryHubTest`), emulator (register, preflight, clone) |
| 30f | Security & Evidence (architecture spec §13, ADR 0020) | `core/control/SecurityEvidence.kt` (`Evidence` format), node `anchor`/`anchors`, `umbra-node evidence-decrypt`, `ui/security/SecurityEvidenceScreen.kt` | unit (`SecurityEvidenceTest`, `NodeEndToEndTest`), emulator ↔ Mac (anchor and verify, incident, export decrypted) |
| 31 | Performance management | `core/perf`, `AndroidResourceSampler` | unit (`ThrottleTest`, `ResourceGovernorTest`, `HysteresisThrottleTest`) |
| 31a | Admission queue (architecture spec §5, AC-022, ADR 0021) | `core/orchestrator/AdmissionOrder.kt`, `AgentSupervisor.submit` / `queued` / `dequeue`, `Session.queuedAtMs`, Resource Manager's Admission card | unit (`AdmissionOrderTest`: 100 logical agents), omp (`AdmissionTest.requestsBeyondTheSlotsWaitAndAreAdmittedFairly`), emulator (five requests under real memory pressure: queued, drained, never beyond the slots in force) |
| 32 | User control | pause/resume/stop/delete/duplicate in UI and notification; Settings → *Access and data* (stop all, revoke helpers, screen control off, delete history); `AppSettings` (modes, instructions), approval history | omp (`AgentSessionTest`, incl. stop-all and delete-all), emulator (the four controls pressed by hand, each checked on the device) |
| 33 | Production acceptance | `fixtures/crashy-login`, `scripts/acceptance-crashy-login.sh`, `scripts/ondevice-scripted-acceptance.py` | the whole loop (build in Linux, install from the workspace, crash found in the log, fix, rebuild, reinstall, sign in) runs with a scripted model; the real model's run needs your key |
| 34 | Summary | — | — |

## Architecture spec acceptance criteria (AC-001 … AC-022)

The Architecture & Implementation Specification v0.1 (embedded in the Porcelain prototype) lists 22 acceptance
scenarios. Status is stated as it is: **met** (built and verified as the scenario asks), **partly** (the core of it,
with the gap named), **not built**.

| AC | Scenario | Status | Where, and how it is verified |
| --- | --- | --- | --- |
| 001 | The UI is killed during a tmux shell: the pane is re-validated, the job is not started twice | met | `TerminalService` (registry by terminal id → server epoch → pane); `TerminalServiceTest.aRestartedUiReattachesTheSamePane…` |
| 002 | The tmux server is killed: no pretending the shell lives; new server epoch; reconcile | met | same test ("a killed server is reported lost"), the terminal's status and epoch on screen |
| 003 | Two views and a resize: a view without input does not change the PTY size | met | `TerminalService.viewSize`; `TerminalServiceTest.onlyTheInputOwnersViewResizesThePane` |
| 004 | The user takes a terminal at once: the old owner's input is refused; the screen says its command may still run | met | `InputLeases` generations fenced at the dispatcher; `TerminalServiceTest.takingControlFencesTheAgentImmediately…`; the terminal says taking control "does not stop a command that is already running" |
| 005 | Android display taken back: related input fenced, dependent phases blocked, an independent build continues | met | screen-touching actions are refused at dispatch while the user holds the screen (`AppContainer.inputGate`) and recorded as a wait on the user; Planning marks the phase in progress "Blocked on display:0" and says commands and builds go on; emulator (a scripted session's tap refused, its shell commands before and after ran, the phase shown blocked, the wait graph edge to the user) |
| 006 | App restart during an emergency lockdown: it persists; a runner reconnecting does not lift it | met | `EmergencyControl`; `LeasesAndEmergencyTest.aLockdownFencesEveryLease…SurvivesARestart…` |
| 007 | A Gradle daemon shared by A and B: halting A cancels A's work, the daemon serving B stays | partly | shared services are held by references (`SharedServices`), so releasing A's leaves the service to B; there is no staged Execution Halt controller |
| 008 | PID reuse during a halt: identity mismatch refuses the signal, marked uncertain | not built | no Execution Halt controller |
| 009 | Root cgroups absent: degrade explicitly to Tier 1, never claim Tier 2 | not built | containment tiers belong to the Execution Halt controller |
| 010 | A service crash loop: breaker and restart budget survive a control-plane restart | met | `SharedServicesTest.theRestartBudgetOpensAPersistedBreaker…`; emulator (helper recovered through the gate) |
| 011 | A service restarts with 20 clients waiting: staged admission, bounded queue, unknown operations not resent | met | `SharedServicesTest.afterARecoveryNewWorkIsLetInInStages`; unknown outcomes settled by reading back (`CommitGate`) |
| 012 | A remote node disconnects with a valid offline lease: only its approved job continues; after expiry nothing new | met | node-enforced leases (`Jobs.enforceLeases`); `NodeEndToEndTest.theNodeEndsAJobWhenItsLeaseRunsOut…`; emulator (lease ran out offline) |
| 013 | Split brain between nodes A and B: B cannot get around the commit gate to overwrite a shared ref | met | nodes never push; every publish is a leased, read-back write (`CommitGateTest.aRemoteThatMovedAfterValidationIsNotOverwritten`) |
| 014 | The network drops after a git push: the remote ref is read before anything is sent again | met | `CommitGateTest.aWriteWhoseReportIsLostIsSettledByReadingTheRemote`, recovery at start-up |
| 015 | The agent tries to read a high-privilege token: it cannot; brokered actions only on authorized resources | met | git credential broker (ADR 0022); `GitBrokerTest`; emulator (an agent-side scan across a brokered push finds nothing) |
| 016 | A compromised credential broker: trust external revocation, isolate its permissions, unrelated work goes on | met | the user suspends the git broker as a whole (persisted, journaled) and every brokered use stops while model sessions go on; each credential is also scoped and revoked on its own; revocation at the issuer is the authority, the user's act, which the vault points to (Umbra calls no issuer); `GitBrokerTest`; emulator (fetch refused while suspended, an unrelated session completed, fetch again after resuming) |
| 017 | An offline compromised node: the screen says revocation pending, never confirmed stopped | met | node revocation (ADR 0018); `NodeRegistryTest`, `NodeEndToEndTest`; emulator (pending offline, acknowledged later) |
| 018 | A vault restore: independent recovery material and an integrity check; secrets come back without commit authority | met | vault recovery (ADR 0023); `VaultBackupTest`; emulator (restore refused to publish until granted) |
| 019 | The local event chain is rewritten: without an anchor no claim of tamper-proofness; with one it is detected | met | `SecurityEvidence`; `SecurityEvidenceTest.anAnchorCatchesHistoryRewritten…`; the screen marks a local chain "Not proof alone" |
| 020 | Evidence egress denied: no raw logs leave; only a minimal digest, after opt-in | met | anchors carry digests and journal heads only; the export is an encrypted file the user saves (ADR 0020) |
| 021 | A policy draft is simulated: the draft changes nothing running; apply writes a revision and shows converging | met | `ResourcePolicyTest.aDraftChangesNothingUntilPublished…`; Resource Manager shows Effective / Converging |
| 022 | 100 logical agents on a constrained phone: admission bounds real processes; fairness and queue visible | met | admission queue (ADR 0021); `AdmissionOrderTest` (100 agents), `AdmissionTest`; emulator under real memory pressure |

Not built, and why: the Execution Halt controller (AC-007's halt, AC-008, AC-009) needs process signalling with
identity checks and cgroup containment; it is outside what this build does. The existing controls remain: Emergency
input lockdown, Stop everything, stopping a session, taking back a terminal or the screen, node job leases.

## Architecture spec invariants (INV-001 … INV-014)

| INV | Invariant | How it holds |
| --- | --- | --- |
| 001 | UI ≠ process | runners are probed, never assumed: terminals reattach by asking tmux (`TerminalService`), nodes by `hello` and rejoin, sessions are paused with the reason after an app restart; Runners & Continuity shows probed state with its time |
| 002 | Control plane ≠ executor | the app keeps decisions and durable state (`ControlStore`); processes are held by tmux servers, the Linux environment, omp and `umbra-node` |
| 003 | Authorization is not inherited | project, session, terminal, screen, commit and node authority are separate leases and grants (`InputLeases`, `CommitGate` capabilities, node trust, credential scopes) |
| 004 | Unknown ≠ failed | `OUTCOME_UNKNOWN` settled by reading the remote; a node out of reach is "not seen", a revoked one "pending", its jobs "not confirmed stopped" |
| 005 | Human take back | Take Back Screen and Take control fence new agent input at once; both screens say a command already running is not stopped |
| 006 | Fencing at the write point | the dispatch gates (`AppContainer.inputGate`, the terminal input dispatcher with lease generations) and the remote's lease (`--force-with-lease`) |
| 007 | No fake atomicity or exactly-once | the commit gate's journal, conditional write and read-back; the screens say external effects are not exactly-once |
| 008 | No fake sandbox | Runners & Continuity measures the agent's process from inside it (kernel user, capabilities, no-new-privileges, seccomp, SELinux domain, namespaces, hidden processes, system files: `IsolationProbe`) and calls the result user-level, or none under proot; tmux groups say "not a sandbox" |
| 009 | No silent privilege widening | resume needs a contract; restore brings no publishing authority back; placement follows the versioned policy; queued admission adds no authority; no automatic failover |
| 010 | Durable emergency restrictions | the lockdown, node revocations and a suspended git broker are persisted and survive restarts until the user lifts them |
| 011 | Explicit provenance | plan revisions, policy revisions, service and runner epochs, node job policy revisions, promotion journals and incident versions, all in hash-chained journals |
| 012 | Outcome verification | the commit gate reads the remote back; node jobs come from the node's own account; action verification for Android (`Verifier`) |
| 013 | Least privilege for privileged parts | the agent runs without capabilities and cannot reach the helpers' tokens (ADR 0010); git tokens are used only by root and never given to an agent (ADR 0022); no SSH agent, deployment or signing material is held |
| 014 | Privacy-first evidence | journals stay on the phone; only digests are anchored, on a node the user chooses; the full export is encrypted under the user's passphrase |
