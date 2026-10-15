# Android Agent — Architecture & Implementation Specification

**版本：** v0.1（Architecture Baseline）  
**日期：** 2026-10-10  
**狀態：** 已確認的產品／架構決策整合；**不是**已實作、已通過測試或 Android 平台能力保證。  
**平台目標：** Android 16；支援普通權限路徑，進階功能視裝置與使用者授權加入 Shizuku、Termux、Termux:X11／Debian、Root Sidecar 與遠端節點。  
**設計語言：** Porcelain in Motion。  
**用途：** 作為 Coding Agents 的共同架構規範、Implementation Plans、接口契約與驗收測試基準。

> **閱讀規則**：`MUST` = 已定案的硬要求；`SHOULD` = 建議預設；`CONDITIONAL` = 僅在能力測試與權限有效後提供。本文提到的協定、狀態、Schema 均屬待實作的自訂設計，不是 Android、Termux 或 tmux 已內建的產品功能。

---

## 0. 核心設計不變條件（Invariants）

- **INV-001 — UI ≠ Process**：Porcelain UI 重啟，不等於 Agent Runner、PTY、tmux Server 或遠端節點重啟；必須以實際探測重連。
- **INV-002 — Control Plane ≠ Executor**：Control Plane 負責決策、政策與 durable state；實際 Process 由其 Runner／OS 環境持有。
- **INV-003 — Authorization 不可繼承**：Workflow、Project、Session、Runner、Root 與 Commit Gate 的權限彼此獨立；上層協調權不是下層資源操作權。
- **INV-004 — Unknown ≠ Failed**：網路中斷、Heartbeat 消失、Commit 回應遺失均不能視為操作尚未發生，更不可盲目重送。
- **INV-005 — Human Take Back**：人類對本機 Display 與受管 Terminal Input 可立即收回新的 Agent 輸入權；不能承諾撤回已送出的位元組或停止已在執行的程式。
- **INV-006 — Fencing 必須在真正寫入點執行**：比較大的 Epoch／新 Token 只有在實際資源 Gate 驗證時才有排他性；不能靠 Client 宣稱防止舊 Agent 寫入。
- **INV-007 — No fake atomicity / exactly once**：不承諾 Git、部署、Artifact Store 等異質外部系統跨系統原子交易或 Exactly-once；採可查證、條件提交、隔離與 forward recovery。
- **INV-008 — No fake sandbox**：tmux Group、Unix Process Group、PRoot 或 cgroup 資源控制**不代表**完整安全隔離；依實際 UID、SELinux、namespace 與授權回報真實能力。
- **INV-009 — No silent privilege widening**：擴容、Failover、遠端 Placement、Resume、Recovery、Secret Restore 均不得提升原有授權與資料外傳範圍。
- **INV-010 — Durable emergency restrictions**：全域 Lockdown、Trust Quarantine 和撤銷需持久化；不會因 App、Runner 或裝置重啟自動清除。
- **INV-011 — Explicit provenance**：Policy Revision、Plan Revision、Service Epoch、Runner Epoch、Job Attempt、Resource Version、Authorization 都必須可追溯。
- **INV-012 — Operation outcome verification**：終止或復原不僅檢查 Process Exit，還須核對 Git、Artifacts、檔案、Android 狀態與可能的外部副作用。
- **INV-013 — Privileged components least privilege**：一般 Agent 不取得無限制的 Root、SSH Agent Socket、Git PAT、部署憑證或 Signing Private Key。
- **INV-014 — Privacy-first evidence**：一般 Security Logs 本機保存；重大事件僅在事先授權後錨定最小必要摘要於獨立信任邊界；完整證據匯出須單獨授權與加密。

## 1. 系統全景與 Process Ownership

### 1.1 分層

```text
Porcelain UI Host ─────────── Camera Capsule / Workspace / Inspector / Notifications
         │
Android Control Plane ─────── Session + Project + Repository + Scheduler + Policy
         │                             │
         ├── Durable State Store ──────┤ Event Log / Snapshots / Journals / Leases
         │                             │
         ├── Security Decision Plane ──┤ Permissions / SERE / SCCA / Commit Authority
         │                             │
         └── Runner Supervisor Protocol (RSP)
                 ├── Android Native Adapter (Accessibility / permitted device tools)
                 ├── Termux Adapter → tmux Control Mode → grouped tmux servers/panes
                 ├── Debian / Termux:X11 Adapter → X server + GUI clients + viewer
                 ├── Optional Root Sidecar → scoped privileged workers
                 └── Remote Node Adapters → authenticated distributed runners
```

### 1.2 Ownership Matrix

| 邊界 | State Owner | 真正 Process Owner | 恢復原則 |
|---|---|---|---|
| UI View / Tabs / Editor | UI / durable view store | Android Activity | 恢復 View State，不宣稱恢復 Runtime |
| Session / Plan / Policy | Control Plane / DB | 無單一 Process | DB event/snapshot 載入、驗證版本 |
| CLI Terminal | Terminal Registry | Termux UID 下 tmux Server / Shell | 首先辨識原 Server、Pane、Session、tmux Epoch |
| Debian GUI | GUI Registry | X Server、proot／GUI clients 各自 Owner | 分別查驗 server/socket/client/viewer |
| Root Task | Root Sidecar / Operations Journal | Privileged Worker | 重新驗證操作與授權；不能以 `su` 存在替代 Preflight |
| Remote Job | Distributed Job Registry | 遠端 Runner / OS | Offline Contract、lease、journal 和結果對帳 |
| Git / Deploy Commit | DCAF Promotion Journal | Resource Commit Gate 與實際外部資源 | 以資源端 version/fence/receipt 確認 |

### 1.3 Android 平台界線（均列為實測門檻）

Foreground Service、背景啟動、JobScheduler quota、BOOT_COMPLETED、Direct Boot、Accessibility、Shizuku、Root Manager、Termux 外部命令呼叫、Termux:X11、X server/socket、SELinux、cgroup、pidfd：**任何一項不可只靠設計文件視為支援**。定義裝置 / ROM / kernel / permission capability matrix；每次 OS/ROM/Root 環境變更必須重新評估相關能力。首次解鎖前的 credential-encrypted 工作內容與 Secrets 不可當作可用。Root Daemon 不是不死程序，亦不可要求全域停用 SELinux 作為生產預設。

## 2. 資料與域模型（Domain Model）

**Project、Repository、Session、Worktree、View、Window、Agent、Phase、Job、Process、Resource、Service、Credential 均為不同實體。**

### 2.1 身分與關聯

- `Project(id, policy_ref, budget_ref)`：容納 Sessions、Project Context Links，可關聯多個 Repositories。
- `Session(id, owning_project_id nullable, linked_context_ids[], resume_contract_id, status)`：可完全獨立；最多一個 Owning Project；Move Session 與 Link Context 為不同操作。
- `Repository(id, canonical_location, integration_policy)`；全域 Repository Hub 為權威入口；`Worktree(id, repository_id, ref, path, owner)` 可對應不同 Session/Project。Worktree、shared refs 與獨立 clone 的差異必須向使用者顯示。
- `Agent(id, session_id, parent_agent_id nullable)`：Delegation Tree。
- `PlanRevision(id, base_revision, graph_hash, status)`；`Phase(id, revision_id, deps[], safety_class)`：Phase DAG；Versioned Dynamic Plan，不能竄改歷史。
- `Job(id, phase_id, required_resource_bundle)`；`Attempt(id, job_id, runner_id, epoch, checkpoint_ref, state)`：執行嘗試；Failover 產生新 Attempt 而非覆寫舊 Attempt。
- `Terminal(id, owning_session, runner_group_id, tmux_session, pane_id, server_epoch, input_lease_id, stable_virtual_size)`；實際 pane ID 不作永恆身分。
- `ProcessInstance(id, attempt_id, runner_epoch, boot_id, pid, proc_start_identity, pgid, containment_tier, last_verified)`：PID 不能單獨識別程序。
- `SharedService(id, resource_scope, replication_mode, trust_profile)`；`ServiceInstance(id, service_id, epoch, runner_id, health_state)`；`ReferenceLease(consumer_job, instance_id, epoch, state)`。
- `ResourceLease(id, resource_id, holder, generation, expiration, policy_revision)`；使用者 Take Back 要 fence 舊 generation。
- `CapabilityGrant(id, principal, action, resource_scope, attempt_id, policy_revision, expires_at, uses_left)`；Credential 與 Capability 分離。
- `OperationJournalEntry(id, operation_id, attempt, preconditions, intended_effect, outcome, evidence_refs)`；`SecurityEvidenceEvent` 另受資料最小化限制。

### 2.2 必須分開的版本序列

`PlanRevision`、`ResourcePolicyRevision`、`SessionResumeContractVersion`、`RunnerEpoch`、`ServiceInstanceEpoch`、`InputLeaseGeneration`、`ExecutionAttemptEpoch`、`CommitResourceVersion`、`SecurityIncidentVersion` 不得混成單一整數或互相當成資源安全證明。

## 3. Porcelain in Motion 與 Workspace UX

### 3.1 視覺與導航

- 灰綠瓷釉、柔和高光、細緻抬升和收斂動畫；Respect Reduce Motion／溫度／電量。
- 相機挖孔定位的 **Material Capsule** 永久位於原位置；Collapsed 顯示 Orb+任務，閒置 Mini Island；Expanded 顯示 Previous/Current/Next、Session 與 Step 各自分隔、批准與 Inline Chat。Attention Focus 不強迫切換 Workspace。
- 使用者點擊閒置 Capsule 時展開 Smart Composer，不強制彈出鍵盤。頂部與底部 Composer 共用 Draft 和 Context；麥克風歸屬原觸發介面。
- **Snapshot selection**：類 Circle-to-Search 的截圖圈選與 Accessibility Context，選取時顯示小工具列，Done 後返回原 App，底部 Quick Composer 含 Context Chip。單次 Snapshot 預設，Live Context 明確 opt-in。
- **Background-first + Conditional Takeover**：App 在背景完成可執行工作；需要主螢幕時明確有可見控制狀態與使用者隨時 Take Back。

### 3.2 View、Window 和 Preset

- Phone：Primary Canvas + Inspector Drawer；寬螢幕若系統允許使用真 Android Window，否則 in-app Docking。同 Session 在多 View 呈現不重啟 Runner。
- Drag-out 預設 Move；Duplicate 明確另操作。One input owner per PTY 或 physical display；Viewer 可多個 Read-only。
- Presets：Coding、Debugging、Monitoring、Review、Custom；Contextual Suggestions，不強制換 preset。套用 preset 保留未匹配 Views 於 View Shelf（Draft、Scroll、Cursor、Log 位置可重建）。
- Adaptive Tab Strip：寬視窗顯示，手機緊湊；可 Always Show / Compact Only。Global Recents。Project Dashboard / Quick Return / Notification 深連結各自正確。
- 使用者設定的 Workspace、Open Views、UI Focus、Run Status 為不同概念。UI 重啟後只可顯示經 Runner 驗證的 `Running`。

## 4. Resource Scheduler、Agent Planning 與 Integration

### 4.1 多圖模型

- Delegation Tree：誰委派給誰。
- Phase Dependency DAG：任務相依和 Plan Revision。
- Resource Wait Graph：誰持有什麼、誰等待什麼、死鎖偵測。
- Actual Process Ownership Graph：哪些真實 Process/Process Group 屬於哪些 Job Attempts。
- Security Trust/Exposure Graph：Secrets、Brokers、Capabilities、Node、資源寫入之信任關係。

上述關係不能以單一父子樹取代。

### 4.2 排程

- 區分 Attention Priority、Execution Priority、Preemption Policy。
- 常態 **Safe-point Preemption**；受管輸入的人類立即 Take Back 與安全中斷是分開操作。Resource Handoff 需完整 Audit。
- 有界實體 Runner/Worker，允許大量 Logical Agents；Admission 根據實測 CPU/RAM/GPU/thermal，不假設 100 Agents = 100 同時 Process。
- Guaranteed Minimum + Elastic Sharing：各 Project min / max / weight，剩餘額度可借用並在安全點回收；獨立 Session 可 Shared Pool 或 Dedicated Budget；Dedicated Budget 可從 Parent Pool 分配或 Global Reservation，禁止重複計算。
- Resource Phases：Code / Build / Deploy / UI Test / Verify；必要 Scarce Resource Bundle 採協調 Admission，避免等待 RAM 時長時間持有 Android Display。
- Policy Studio：Draft → Simulate → Apply；發布新 Revision 後 `Published`、`Effective`、`Converging` 狀態須分開。恢復舊版本是新 Draft，不重寫歷史。
- Scheduler Time Travel 是事件/快照回放的唯讀視圖；Counterfactual Simulation 不可真的重跑不可逆外部操作。

### 4.3 Planning & promotion

- Agent 可提出未來 Phase / Dependencies 修訂；immutable Plan Revision；正在執行的 Phase 不被偷偷改寫。Plan Revision 和 Resource Policy Version 分離。
- 可相容且驗證通過的 concurrent proposals 可自動合併；語意不明、scope 改變、依賴衝突進入仲裁。
- Bounded agent negotiation → Scoped Coordinator → Human，無權限的 Coordinator 不因仲裁取得 Root/Screen/Repo write 能力。
- Speculative Candidates 只在隔離、預算、資料權限條件下執行；結果進 Evidence-gated Selection，可選「皆不採納」。
- Integration Staging Worktree、Source/Plan Integration Gates、Promotion Coordinator、耐久 Journal 與資源端版本前置條件；Node/Agent 成功不等於允許 Push 或 Deploy。

## 5. Runner Supervisor Protocol（RSP）

### 5.1 API 分類（協定設計草案）

| Domain | Command / Event | 使用目的 |
|---|---|---|
| Identity | `HELLO`, `CAPABILITIES`, `ATTEST` | 互相驗證與裝置能力聲明 |
| Execution | `START_JOB`, `CANCEL_JOB`, `SIGNAL_JOB`, `GET_JOB_STATUS` | 受管 Job / Process |
| Terminal | `ATTACH_TERMINAL`, `DETACH_TERMINAL`, `SEND_INPUT`, `RESIZE` | tmux IO 與 View Attachment |
| Live status | `HEARTBEAT`, `RUNNER_STATUS`, `OUTPUT_EVENT`, `PROCESS_EXITED` | 狀態與 Event Sequence |
| Recovery | `CHECKPOINT`, `RECONCILE`, `RESUME_ATTEMPT` | 安全重連／恢復 |
| Resource | `ACQUIRE_LEASE`, `RELEASE_LEASE`, `FENCE_GENERATION` | 協調 Lease 與失效 |
| Emergency | `APPLY_LOCKDOWN`, `REQUEST_HALT`, `VERIFY_HALT` | 本地 Fencing、分階段終止 |

### 5.2 共同 Envelope

```json
{
  "protocol_version": "rsp/1",
  "request_id": "req-0001",
  "session_id": "session-07",
  "job_id": "job-084",
  "attempt_id": "attempt-03",
  "runner_id": "termux-main",
  "runner_epoch": 18,
  "operation_id": "build-084",
  "expected_state_version": 31,
  "capability_grant_id": "grant-103",
  "command": "GET_JOB_STATUS"
}
```

- 所有控制指令在實際 Runner／Gate 再驗證；傳輸通道需互相驗證，**localhost 不等於可信**。
- `request_id` 用於重複傳輸識別；`operation_id` 用於邏輯操作對帳；兩者不能取代外部副作用的結果驗證。
- Snapshot + monotonically increasing Event Sequence；History Gap 要重新同步 Snapshot，不能假裝事件不存在。
- 每個 Runner 須有實際能力清單與 explicit `Unsupported`；不可假定所有 RSP 指令對所有 Adapter 均可用。
- 所有外部資料、Tool Output 和 Agent 提供的 commands 屬不可信輸入；依 Operation Type 和 Capability Scope 驗證。

## 6. Termux / tmux / Debian GUI

### 6.1 tmux-centered Persistence

- **不自行重造完整 PTY Multiplexer**；Termux Adapter 使用 tmux，優先利用 Control Mode 接收 Pane Events / Output，必要時為不同通訊目標分開 IO 隊列。
- **Per-Runtime Group tmux Servers**：主要以 Runtime Environment、Trust Boundary、Workload Type 分組，Project 可進一步劃分。Group 的 Socket / Server Epoch 不代表 OS 安全沙箱。
- Terminal Registry 映射 stable `Terminal ID → Runner Group → tmux Server Epoch → Session → Pane`；UI View 不擁有 PTY。
- 受管 Terminal Input 必須經同一序列化 Dispatcher，檢查 Lease Generation／Owner Epoch。直接 attach 外部 tmux Socket 的 Clients 可能繞過 Agent 的 Input Lease；嚴格模式需要實際限制相關 Socket access。
- **Coordinated Handoff + Immediate Override**：正常請求 safe-point handoff；使用者可立即 fence Agent 新輸入。`Interrupt` / `Terminate` 是另外操作，不假設原 shell command 已終止。
- **Input Owner-driven Resize**：目前控制者 View 決定 panes 尺寸；無前景 View 的 Agent 用 Stable Virtual Size，Power User 可固定尺寸；Read-only Views 不影響大小，resize 合併與節流。
- Scrollback ≠ durable execution log；Log 需有界、可分段、可搜尋並適度遮罩敏感資訊。
- tmux Broker/Server 死亡、Socket 無法連線、Pane 結束、Adapter 失聯等分開診斷。tmux Server 死亡後不能假裝原交互 Process 可恢復。
- Termux `RUN_COMMAND`（若可用且授權）可作 bootstrap；**不可只依賴命令回傳作長時間 PTY 即時串流**。

### 6.2 Debian GUI

- 分別跟蹤 X Server、Desktop / Window Manager、各 GUI Clients、Viewer、Input Lease、Recovery State。
- GUI Viewer 被關閉不代表 X Server 或 GUI Clients 已停止；X Server 死亡也不保證 Clients 能無縫接新 X Server。
- proot / Termux:X11 的 socket 路徑、共享 temp、Android app host 的實際整合需實測。若要將其他 App 畫面嵌入 Canvas 必須另有合法且可用的 Surface／Capture／Streaming 介面。

## 7. Unified Input Lease Architecture（UILA）

- Terminal、Debian GUI 和 Android Physical Display 各自有獨立 Resource Lease；Workflow Coordinator 協調依賴 Phases，**不繼承實際輸入權**。
- User Take Back 特定 Resource 時：立即拒絕新受管 Agent Input；依賴該資源的 Phases `Blocked/Replanning`，不相關 Phases 可繼續。
- Workflow-level Hold：隔離該 Workflow 的受管 Input，不影響無關 Workflows；已啟動 Process 另由 Execution Safety Controller 評估。
- Input Lease `resource_id`, `owner`, `generation`, `runner_epoch`, `expiry`, `state`；優先使用底層 Dispatcher 順序化「撤銷」與「發送」，明確界定成功接管時點。
- Terminal Input 和 GUI/Display Input 分別提供 Size Authority；Android 主螢幕尺寸由系統管理。
- Resource Wait Graph 用於 deadlock detection；共同需要的 Scarce Inputs 可用 Bundle Admission，或逐 Phase 放棄不再需要的 Lease。
- Unified Control Timeline 記錄 acquire、handoff、takeback、revoked、blocked、reconcile、restore。

## 8. Recovery & Emergency Execution Safety

### 8.1 三層狀態分離

- Presentation State：Views、Tabs、Drafts、Cursor、Layout。
- Durable Control State：Sessions、Plans、Policies、Journals、Permissions、Checkpoint、Event Log。
- Execution State：真實 Process、tmux、PTY、GUI、Root、遠端 Node。

**Operation-aware Autonomous Recovery**：優先 reattach 存活程序；被確認安全與符合授權的操作可 semantic-checkpoint resume；Unknown Outcome 須 reconcile；不得盲目重播外部副作用。

**Session-scoped Resume Contract**：指定是否允許重開機後恢復、期限、Operation Classes、資源與前置條件；解鎖後、平台允許時執行。合約不是權限授予，Display／Root 等權限仍須驗證。

### 8.2 Two-tier Emergency Control

| 控制 | 行為 | 不應聲稱 |
|---|---|---|
| Emergency Input Lockdown | 持久化新 Fencing Generation，禁止新的受管輸入及具副作用 Tool Dispatch | 不能宣稱已送出的指令都被取消 |
| Emergency Execution Halt | Policy-driven staged：fence → checkpoint/safe point → authorized graceful / force stop → reconcile | 不能宣稱 `kill` 已回滾 Git / 外部 API / 檔案變更 |

- **Dependency-aware Halt Scope**：指定 Agent、Affected Subgraph 或 Entire Workflow；分 Requested Scope 與 Verified Process Scope。
- **Capability-tiered Process Containment**：Tier 0 observed、Tier 1 managed wrapper/process group、Tier 2 only when verified kernel-scoped process grouping available；`Restricted` 對未知身分與權限不足。
- 不依 `pkill` 名稱、PID 單獨值或 tmux `kill-server` 廣泛終止；Process 身分包括 boot、start identity、runner epoch；能用 pidfd 時優先用適用 API。
- Shared Gradle/Language Server/Inference Worker 等獨立 Shared Service Registry、Consumer Reference Leases；停止 A 時不直接 kill 同時服務 B 的 Shared Process；只有排空、確認沒有其他使用者或經擴大授權才可停止。
- `Requested`, `Acknowledged`, `Fenced`, `Stopping`, `Stopped`, `Partial`, `Unreachable`, `Outcome Unknown` 分開顯示。
- Emergency Lockdown 不能因 Process Stop 或 App 重啟自動解除；先解鎖 Inputs，再對帳和個別允許 Resume。

## 9. Shared Runtime Service Manager（SRSM）

- Reference-aware Shared Process Governance：Logical Service Identity 與 Process Instance Identity 分離；Lease 消失不代表 in-flight operation 結束，也不代表不存在未知外部 Consumer。
- `Draining` 前先關閉新 Admission；原子性檢查 Registered References 與已知 in-flight work，再決定停止或恢復服務。
- **Demand-aware Coordinated Recovery**：按實際 Consumer Demand、Service Safety、Permissions、Circuit Breaker 與實測資源決定是否重啟；每 logical service 同時僅有可驗證 fenced Recovery Owner。
- Circuit Breaker Closed / Open / Half-open；Backoff、Jitter、Attempts/Window、Stability Threshold、Restart Budget；不能由 UI 重新開啟就清零。
- L1 Process Alive、L2 Transport、L3 Readiness、L4 Safe Functional Health 各自判定；不得用有不可逆副作用的 Probe。
- **Adaptive Staged Reattachment**：運行中請求獨立 Reconciliation，分批開放 Admission，依 error/latency/throughput/pressure 調整 Concurrency，隊列有界並支援 backpressure、deadline 和 fairness。
- **Capability-aware Elastic Service Pool**：僅在服務聲明可複製、Placement 安全、資源成本允許時水平擴展；Stateful Affinity/Singleton 不任意複製；Scale-in 需 drain/reconcile。

## 10. Distributed Execution Architecture（DEA）

### 10.1 Placement

- **Policy-bound Autonomous Placement**：允許節點、資料敏感度、遠端資料外傳、Trust、Resource、Bandwidth、Cost Ceiling、Session/Project Scope 皆受版本化政策約束。
- Node Enrollment：身份金鑰、雙向配對、Capability Preflight、Trust Tier、獨立的 Secret/Permission Grant。Identity Pairing 不等於硬體 Attestation 或完整安全保證。
- Data Transfer Manifest 必須先列出 Allowlist、實際解析的檔案、symlink/path escape 防護、Secrets Scope、Max Bytes、Retention；最小化上傳，Remote Workspace 不默認繼承本機全部 Secrets。

### 10.2 Offline 與 failover

- **Lease-bounded Offline Execution**：每 Attempt 綁定 Offline Contract（Node/Job/Epoch/期限/允許 Phases/Operations/資料/資源/成本）；失聯時只允許預先核准的離線安全工作。到期停止新派發，並按政策處理已執行程序；無法保證當下停止。
- 遠端 Boot、時鐘回撥、Runner Epoch 改變 → 保守重新驗證 Lease。未連線不能保證已收到手機 Emergency Revocation。
- **Checkpoint-aware Conditional Failover**：原 Node 失聯 ≠ 停止。僅對可安全重算且有有效 Checkpoint 的 Phase，在獨立 Workspace/Artifact Namespace 中允許候選 Attempt。不能雙重取得共享 Writer/Deployment 權限。
- Rejoin 交換 Journals、Event Sequences、Resource Versions、Artifact Hashes、External Effect Receipts；A/B Attempt 分開保留並 Evidence-gated Selection（可皆不選）。

## 11. Distributed Commit Authority Framework（DCAF）

- **Resource-scoped Authoritative Commit Gates**：Control Plane 管理全域 Policy / Approval；實際 Git Ref、Artifact Registry、Deployment、DB 或 Shared FS 的 Gate 必須在真正寫入路徑驗證 Action、Attempt、Scope、Expected Version、Fencing。
- 請求生命週期 `VALIDATE → PREPARE (durable intent) → FENCE → COMMIT → VERIFY → FINALIZE`；外部結果未知時 `OUTCOME_UNKNOWN`，先查證再重試。
- 使用 isolated staging worktrees、content-addressed artifacts、conditional remote ref update / compare-and-swap（僅在資源支援時）；禁止未經授權強制 Push。
- 異質資源跨系統沒有保證 atomic commit；持久化 Promotion Journal 與 Checkpointed Forward Recovery；補償只在真正可逆且授權時進行。
- **Credential Broker + Scoped Commit Capabilities**：一般 Agent 不能直接持有長期 Git PAT、SSH Private Key、Cloud Deploy Token 或 Signing Key；Broker／可信 Adapter 代理高權限操作。Capability 限定 Resource、Action、Branch/Target、Job Attempt、Expiry、Use Count；Gate 強制去重／核銷。

## 12. Secure Credential & Capability Architecture（SCCA）

### 12.1 Scoped Broker Federation

- **Resource-scoped Credential Brokers**：Broker Registry 與 Scoped Credentials；不同 Broker 可位於 Android、受信任 PC、Cloud Security Node。**不自動複製 Secrets 到所有 Nodes**。
- Broker Enrollment 必須獨立於 Runner Enrollment；受信任 Coding Runner 不因此取得 Credential Custody。
- Android Keystore／可驗證硬體保護可用時優先；遠端 Linux/Cloud 可用 TPM、KMS 或相容的高信任 Vault，具體能力需 Preflight。
- Git：優先可範圍限定且短期的 Provider Credentials 與受信任 Brokered Git Operations；Provider API 自身若不支援 Branch Scope，Gate 及 Provider-side Rules 必須補強，不能聲稱 token 宣告即生效。
- API Proxy 優於直接注入高權限 Secrets；確需暴露，記錄 Secret Exposure Grant 和風險，無法事後保證未被複製。

### 12.2 Key Custody / recovery

- **Policy-tiered Key Custody**：Device-bound nonexportable（遺失則重配對／輪替）；Recoverable（只有本來可匯出的 Secret 才能 envelope-encrypted backup）；High-assurance Signing（獨立受信任 Signing Service / formal DR）。
- **Risk-adaptive Recovery Authorization**：RAL1 Standard、RAL2 Elevated、RAL3 Critical（自訂等級，不代表第三方認證）。Threshold Recovery、可信裝置或獨立授權，必須在實際解密／授權路徑強制；單一 Key 可離線解密時，UI 的第二批准無安全效力。
- Secret Recovery、Broker Identity Recovery、Resource Authority Recovery 相互獨立。Break-glass 是預先核准、範圍有限、可審計且可能延遲生效的替代恢復路徑，不是超級管理員捷徑。
- Revoke Capability、Rotate Credential、Revoke Node Trust 分開操作；失聯節點只能標記等待撤銷／有限租約未到期，不能宣稱撤銷已即時生效。

## 13. Security Incident Response Architecture（SIRA）與證據

### 13.1 Evidence-tiered Autonomous Containment

| 分級 | 證據 | 預設反應 |
|---|---|---|
| L1 Observation | 啟發式異常、低信心 | 記錄、提高監控 |
| L2 Credible Risk | 可疑高風險存取、身分異常 | 暫停受影響敏感操作、要求重新驗證 |
| L3 Verified Violation | 具體 Policy / Signature / Capability 違規 | 拒絕、隔離受影響 Resource/Principal，追查曝光 |
| L4 Critical Trust | Root / Control Plane 根信任已受破壞或無法界定的高權限事件 | 擴大高權限 Global Lockdown、獨立可信環境復原 |

- 證據分級 ≠ 事件影響範圍，且密碼學驗證失敗不必然證明整台 Node 遭入侵；确定性拒絕不需 AI 風險評分同意。
- **Dependency-aware Security Containment** 使用 Security Trust/Exposure Graph 追蹤 Broker→Secret→Capability→Consumer→Commit Gate→Resource、以及 Node 和共享 Secret Exposure。最小化隔離可驗證的受影響子圖；當根信任危及全域或無法界定邊界，擴大 Lockdown。
- Root Sidecar / Control Plane Compromise 後本機回報與 Logs 不再被當成可信證明。復原需要可信環境、密鑰撤銷／輪替、重新配對和受影響操作對帳。
- State: `Detected → Triaged → Contained → Investigating/Remediating → Trust Restoration Gate → Resolved`，存在部分處置、無法聯繫等副狀態。Incident Resolved 不自動 Resume All。

### 13.2 Privacy-tiered Evidence Custody（本次定案）

**目標：Privacy-first，但避免 Root 遭入侵後所有安全證據只留在受害裝置。**

- `Local Standard`：普通 Event Logs 在本機，最小化收集、可配置 Retention、存取權與加密；Secret Value 禁止入 Log。
- `Independent Digest Anchoring`：重大安全事件在已有合法授權且網路可用時，送出**最小必要**的事件摘要／簽章／Hash Anchor 到獨立可信節點，不預設上傳原始 Terminal Logs 或私人檔案；Hash 及頻率也可能透露存在與時間資訊，仍屬資料政策管控範圍。
- `Encrypted Incident Evidence`：完整證據只有在明確 Incident Policy、Data Egress Scope 和 Access Policy 允許時才加密備份／受控匯出；備份鍵與受害 Runner 不應共用全部信任邊界。
- `Verification & Chain of Custody`：每筆事件包括 `incident_id`, `event_id`, `source_trust`, `event_time`, `sequence`, `prev_digest`, `evidence_digest`, `policy_version`, `access_audit`。建立不可變版本審計（實作上取決於 Append-only/WORM/可信外部 Anchor 能力）。**單純本機 hash chain 不足以對抗有完整寫入權的攻擊者**；離線時也無法保證現有紀錄未遺失或遭改寫。
- `Per-project Privacy`：個別 Project / Session 可設定證據分類、Retention、獨立 Anchor 去向及完整證據匯出限制；重大安全必要限制不能繞過既定使用者資料保護政策。
- `Evidence Access`：按 Role/Incident/Approval 最小權限瀏覽；揭露原始檔案時獨立授權與審計。AI Incident Summary 只能根據允許的脫敏資料生成。

## 14. 標準狀態機（最小集合）

| 實體 | 主要狀態 | 不變條件 |
|---|---|---|
| Runner | `Connected`, `Detached`, `Recovering`, `Unavailable`, `Terminated` | Detached ≠ dead |
| Operation | `Planned`, `Admitted`, `Running`, `ConfirmedCompleted`, `ConfirmedNotCompleted`, `OutcomeUnknown`, `Blocked` | Unknown 不盲目重播 |
| Terminal | `Attached`, `Detached`, `PaneExited`, `ServerLost`, `HistoryGap` | View detach 不 kill shell |
| Input Lease | `Requested`, `Active`, `HandoffPending`, `Revoked`, `Expired`, `Uncertain` | owner generation 過期不得輸入 |
| Service | `Starting`, `Ready`, `Degraded`, `Draining`, `Offline`, `Quarantined` | Health Ready ≠ consumer operations done |
| Circuit Breaker | `Closed`, `Open`, `HalfOpen` | 重啟 App 不清除 restart budget |
| Distributed Attempt | `Running`, `OfflineAuthorized`, `OfflineRestricted`, `Candidate`, `Reconciling`, `Succeeded`, `Failed`, `Unknown` | Candidate ≠ commit authority |
| Commit | `Prepared`, `Committing`, `Committed`, `Rejected`, `OutcomeUnknown`, `Reconciling` | receipt/remote-state authoritative |
| Incident | `Detected`, `Triaged`, `Contained`, `Investigating`, `Remediating`, `RestoringTrust`, `Resolved` | 關閉 Incident 不自動放行 |

## 15. 優先實作順序（按依賴性，不是工時承諾）

### Stage 0 — Spike & Capability Lab

- Android 16 / 指定 ROM 的 permissions/background/Direct Boot/Accessibility/Root/Shizuku/Termux/X11 能力矩陣。
- 小型 Termux Runner Spike：`RUN_COMMAND` bootstrap、tmux Control Mode、Output、Attach/Detach、Pane identification、UI kill/reattach。
- Root Sidecar Threat Model 與 SELinux Enforcing 測試。
- 產出設備相容性表與目前 `CONDITIONAL` 功能清單。**沒過測試不能寫成產品已支援。**

### Stage 1 — Durable Local Core

- SQLite durable domain store、schema migrations、journals、event sequence、snapshot、atomic revision apply。
- Project／Repository／Independent Session／Worktree 建模與 Workspace 基本導航。
- Runner Supervisor Protocol v1 + Local Native/Termux Adapter，真實 state reconciliation。

### Stage 2 — Terminal / Porcelain MVP

- Per-Group tmux Registry；single input dispatcher；Live Terminal Renderer；log persistence。
- Multi-Viewer、Move/Attach、View Shelf、Input Owner Resize、Manual Take Back。
- Material Capsule、Composer、Snapshot Context 的安全 UX 最小版本。

### Stage 3 — Scheduling & Local Agents

- Delegation Tree + Plan DAG + Job/Attempt + resource graph + admission + real process registry。
- Agent coding worktree isolation、Terminal tool calls、Build/Test、Approval Inbox。
- Safe-point preemption；guarantee/elastic budget；Draft/Simulate/Apply policy revisions。

### Stage 4 — Local Safety & Continuity

- Operation-aware Recovery、Session Resume Contract、Checkpoint、Post-Halt Reconciliation。
- Emergency Input Lockdown + Policy-driven Halt + affected subgraph management。
- Root Sidecar optional capability + Debian GUI adapter；不把「功能存在」當作「所有裝置可用」。

### Stage 5 — Integration & Shared Services

- Staging Integration Worktrees、evidence-gated promotion、Git Commit Gate、Promotion Journal。
- Shared Service Registry/Reference Leases、Crash Recovery、Circuit Breaker、adaptive reattachment。
- Elastic Local Service Pools；成本／效能證據。

### Stage 6 — Distributed Runtime

- Remote Node Enrollment、authenticated RSP、Placement Policy、Data Egress Manifest。
- Offline Lease/Fencing、immutable Artifact Namespace、conditional candidate failover、reconnect reconciliation。
- Remote pooling/scale-out、cost ceilings and backpressure。

### Stage 7 — Credentials & Security Incidents

- Scoped Brokers/Commit Capabilities、Vault/KMS adapters、credential rotation、RAL 1–3 Recovery。
- Security Exposure Graph、Evidence-tiered Response、Privacy-tiered Evidence Custody、Independent Anchoring。
- Device loss、Root compromise、Broker compromise、Remote partition failure drills。

### Stage 8 — Hardening & Release

- Crash/fault injection；instrumented Android/Termux/X11 tests；migration/backup/restore；battery/thermal tests。
- Security review、permission/privacy disclosures、observability、accessibility/reduced-motion、UX polish。
- Stage acceptance gates must be verified on representative hardware; no production claim based on simulated UI alone。

## 16. 驗收測試（精選不可省略）

| Test ID | Scenario | 必須可觀察的驗收條件 |
|---|---|---|
| AC-001 | Kill Porcelain UI during tmux shell | UI 重建後重新驗證原 pane；同一 Job 不被重複啟動 |
| AC-002 | Kill tmux server | 不假裝原 shell 存活；ServerEpoch 更新；Attempt 進 Reconcile |
| AC-003 | Two views + terminal resize | 非 Input Owner View 不改變 PTY size；移交後有界 resize |
| AC-004 | User immediate terminal takeover | 受管 Dispatcher 不接受舊 Owner 新 input；明確顯示原 foreground command 可能仍在跑 |
| AC-005 | Android display takeover | 相關 input fenced、依賴 Phases blocked；獨立 Build 可繼續 |
| AC-006 | App restart with Emergency Lockdown | Lockdown 持久化；Runner 重連不得自動解除 |
| AC-007 | Shared Gradle daemon with A/B | Halt A 時 A 工作按能力取消，不直接 kill 仍服務 B 的 daemon |
| AC-008 | PID reuse during halt | 受檢 PID identity 不匹配則拒絕 signal 與標記 uncertain |
| AC-009 | Root cgroup capability absent | 降級到明確 Tier1/Restricted，不聲稱 Tier2 |
| AC-010 | Service crash loop | Circuit breaker & restart budget 跨 Control Plane 重啟保留 |
| AC-011 | Service restart + 20 waiting clients | 分批 Admission、限制 Queue；未知操作不得自動重送 |
| AC-012 | Remote disconnect + valid offline lease | 僅已核准安全 Phases 繼續；到期禁止新的受管敏感操作 |
| AC-013 | Split brain Node A/B | B candidate 隔離，無法繞過資源端 commit gate 覆寫共享 Ref |
| AC-014 | Network drop after Git push | 復原先查 remote ref，未確認時不盲目 push 第二次 |
| AC-015 | Agent tries to read high-privilege PAT | Agent 不可讀；brokered action 僅限授權資源和操作 |
| AC-016 | Compromised Credential Broker | 信任外部撤銷控制；隔離相關權限；無關工作可繼續 |
| AC-017 | Offline compromised node | UI 標示 revocation pending，不顯示已確認停止 |
| AC-018 | Recoverable Vault restore | 必須有獨立 Recovery Material 與完整性驗證；Secret 還原不恢復提交權 |
| AC-019 | Local event chain rewritten | 若未獨立 Anchor，UI 不宣稱可證明不可竄改；存在 Anchor 才能比對偵測 |
| AC-020 | Evidence egress denied | 不上傳原始 logs/evidence；僅合法最小摘要在預先授權後可送出 |
| AC-021 | Policy draft simulation | Draft 不改寫運行中權限；Apply 寫新 revision 並保留 Converging 狀態 |
| AC-022 | 100 logical agents on constrained device | Admission 限制實際並行 Process；可查看 fairness/queue，不 OOM 作為成功條件 |

## 17. 主要安全風險與設計應對

| 風險 | 緩解 | 不可保證 |
|---|---|---|
| Android 系統終止背景程序 | 分離 Durable State / Runner，實際 Probe + Checkpoint | 不保證 24/7 常駐 |
| tmux Socket 被相同 UID 程序直接使用 | Restrict socket path、adapter mediation、least privilege | 同 UID 本身不是安全邊界 |
| Root / Broker 遭入侵 | Trust invalidation、outside-of-device credential revocation、獨立 evidence anchor | 已完整入侵的主機可操縱本地回報 |
| 遠端分區時的舊執行者 | 短租約、持久化 fence、isolate candidates、resource-side commit gate | 不能即時向離線節點發出新命令 |
| 不可逆操作重複執行 | operation journal、receipt、version preconditions、idempotency where supported | Arbitrary APIs 無保證 exactly-once |
| Secret 外露 | Brokered Actions、短期限定 Token、Vault、Proxy | 讀過 Secret 的惡意程序可能複製 |
| Evidence tampering | 事件摘要、獨立可信錨點、最小必要加密 Evidence | 只有本機 hash chain 不足證不可竄改 |
| Prompt injection / untrusted tool output | Agent output 不代表授權、Tool Scope enforced at Gate、approval side effects | 模型指示不能作為可信安全邊界 |

## 18. 待實測與待補充規格（非重新開啟已定案選擇）

1. Android 16 在目標手機 / ROM 上使用 Foreground Services、背景 GUI、Accessibility、Shizuku、Direct Boot 的真實能力與政策合規。
2. Root Manager、SELinux、pidfd、cgroup v1/v2、proot、Termux:X11 相關可用能力和失效模式。
3. termux tmux Control Mode 的真實封包、Output/History 截斷、Control Client 重連及 IO backpressure 測試。
4. 跨 App/UID 認證與 Unix sockets/Binder 的最終威脅模型；Device-Owner、Root、一般 App 差異。
5. Job/attempt wrapper 的可攔截副作用邊界、Process Group 脫離和共享 Build Worker 的歸屬證明。
6. 實際 Git Provider、Artifact Store 與 Deployment Adapter 的 Conditional Update、撤銷和 Credential Scope 能力。
7. Remote Trust Bootstrapping、Attestation、Key Recovery、cost metering 的最小可行配置。
8. 包含編譯、GUI 和 24/7 CPU / thermal 的代表性機型基準及 Failover 故障演練。
9. 法遵、隱私權、使用者提示和遠端資料保留之產品政策。

## 19. 開發規範與交付形式

- 每個 Stage 先產生 `module-contract.md`、`schema.sql` 或 typed model、`interface-contracts.md`、`threat-model.md`、`test-plan.md` 和實際 implementation PRs。
- Coordinator、Runner、Gate 之間不跨越授權邊界直接共用可變狀態；所有外部指令需版本與身分驗證。
- UI 原型只能證明 Interaction 需求，不可替代 Process / Security 實測。
- 每個新 Adapter 必須有 **Capability Preflight + Negative Tests + Crash/Offline/Reconnect Tests** 才能標記 `Supported`。
- 必須讓使用者可辨識 `Requested`、`Acknowledged`、`Enforced`、`Verified` 四種不同的成功程度。
- 所有 agent implementation 工作從 Stage 0 的可執行 Spike 開始，先攻克 Android/Termux/Root 真實能力限制，再擴充大型 Runtime 與跨節點治理。

---

**版本定位**：v0.1 為多輪已鎖定決策的整合基準。下一版 v0.2 應以 Stage 0 原型測試結果更新 API 具體形狀、設備能力矩陣、資料表 DDL、錯誤碼、重要狀態轉移與各 Adapter 的執行前置條件。此文件不宣稱任何 Android Agent 程式碼已產生、執行或通過驗收。
