# Umbra UX 修復計畫

依據：`docs/review/umbra-ux-report.html`（2026-10-11，44 項發現：高 8、中 25、低 11）
撰寫：2026-10-14
設計工具：taste-skill（Leonxlnx/taste-skill，已全域安裝於 `~/.claude/skills/`；本次以 `redesign-existing-projects` 為主，`minimalist-ui` 的產品介面規則為輔）

## 原則

1. **不改產品識別。** Porcelain in Motion（灰綠釉色、陶瓷卡片、按壓回饋）是鎖定的產品設計。taste-skill 用來「在 Porcelain 裡修正」：字體、單一強調色、對齊、層級、空狀態、密度，不換風格。
2. **行為先於外觀。** 先修會刪資料、誤核准、誤停止的地方（第 1 階段），再做視覺。
3. **誠實狀態不能丟。** 報告「做得好的地方」全部保留：不保證什麼就寫出來、狀態用詞誠實、首頁不顯示假進度。
4. **真實驗證。** 每個階段在模擬器 `umbra36` 上安裝真的 APK、操作、截圖確認；不加假資料或假後端。
5. **改到哪裡，字串就抽到哪裡。** 第 2 階段先建好 `strings.xml` 與 `values-zh-rTW`，之後新寫或改寫的文字一律進資源檔；最後一輪掃完其餘畫面。

## taste-skill 對 Porcelain 的診斷（redesign 稽核）

| 稽核項 | 現況 | 修正 |
| --- | --- | --- |
| 字體 | 系統預設 Roboto + Noto Serif（原型是 Inter + Georgia） | 內建 OFL 字體：標題襯線 Newsreader、介面無襯線 Geist；數字用等寬數字 |
| 強調色 | Porcelain 綠之外，Material 頁面混用飽和藍 `#3B82F6`、亮綠、亮紅（`Semantic`） | 全部改用 Porcelain 的 Tone（Good/Neutral/Warn/Danger），只保留一個強調色 |
| 兩套頁首 | 8 頁 Material `TopAppBar`、其餘 Porcelain | 一個 `PorcelainPage` 頁首：返回、標題、緊急盾牌 |
| 對齊 | Home 混合置中與靠左；分段控制只佔 60%；空狀態靠上 | 每個畫面只用一種對齊；分段控制填滿等寬；空狀態垂直置中並給下一步 |
| 按鈕層級 | Snapshot 三鈕相接、寬度跳動；確認對話框用 Material 樣式 | 主次分明、固定寬度、實心底座；`PConfirm` 統一確認對話框 |
| 圖示 | 檔案瀏覽用 emoji（📁📄） | 改用 Porcelain 線條圖示 |
| 文字 | 長段說明直接放在開關下；規格術語上主畫面 | 一句話說明，「為什麼」收進展開；術語收進「詳情」 |
| 狀態 | 部分畫面沒有空狀態、停用按鈕沒有原因 | 補空狀態、停用原因、倒數 |

## 階段與項目

狀態欄：☐ 未做　◐ 進行中　☑ 完成（註明是否已在模擬器驗證）　⊘ 不做（附原因）

進度細節與剩餘工作：`docs/review/umbra-ux-fix-progress.md`

### 第 1 階段　資料與安全（先做）

| 編號 | 修正內容 | 主要檔案 | 狀態 |
| --- | --- | --- | --- |
| C-01 | 工作區刪除：移除 `force`；新增 `AgentSupervisor.deleteWorkspace`：任務仍在排隊、執行、等待或暫停就拒絕，其餘先解除連結再以非強制方式刪除；確認對話框列出連結的任務與大小，寫明不可復原 | `WorkspacesScreen.kt`、`AgentSupervisor.kt` | ☑ 模擬器已驗證 |
| C-02 | 任務刪除加確認：「刪除對話紀錄與私有檔案，保留工作區」 | `CurrentScreen.kt`、`SessionsScreen.kt` | ☑ 已實作（編譯通過） |
| C-03 | 核准歷史不再刪檔：「Clear」改為「從清單隱藏」，紀錄檔保持 append-only，清單只隱藏某時間點之前的項目；加確認 | `ApprovalHistoryScreen.kt`、`AppApprovalLedger.kt`、`AppSettings.kt` | ☑ 模擬器已驗證 |
| C-04 / X-05 | 停止語意統一成三個動詞：暫停（可恢復）、停止（結束任務）、鎖定（擋住代理）。「全部停止」在設定、通知、緊急頁都呼叫同一個 `stopAll()`（含排隊），按鈕寫明範圍；通知上的按鈕改為開啟 App 內確認（列出將停止的執行中、暫停、排隊數） | `SettingsScreen.kt`、`AgentService.kt`、`MainActivity.kt`、`EmergencyScreen.kt` | ☑ 模擬器已驗證 |
| C-05 | 移除 Linux 環境加確認：顯示佔用大小、會停止的功能、如何重裝 | `SetupScreen.kt` | ☑ 已實作（編譯通過） |
| C-06 | 撤銷 helper 加確認：事先列出會關閉的功能（背景顯示器、shell 工具、系統設定） | `SettingsScreen.kt` | ☑ 已實作（編譯通過） |
| C-07 / C-08 | 一條核准規則，依介面能提供的脈絡分級，寫在 core 並有單元測試：通知列只能一鍵核准 LOW/MEDIUM，HIGH 與 CRITICAL 改為「在收件匣檢視」；膠囊可核准到 HIGH，CRITICAL 開收件匣；收件匣與 Current 可核准所有等級，但 CRITICAL 需要第二步確認並說明影響 | `core/safety`、`AttentionCards.kt`、`CapsuleUi.kt`、`AgentService.kt` | ☑ 模擬器已驗證 |
| C-09 | 「Take Back Screen」改為「收回手機螢幕」；按下後顯示仍在背景顯示器工作的任務數，並提供「也暫停背景工作」 | `EmergencyScreen.kt`、`CapsuleUi.kt` | ☑ 已實作（編譯通過） |
| C-10 | 「何時詢問你」加一句「立即套用到所有任務，包含執行中的」；確認 `Session.approvalMode` 無讀取者後移除寫入 | `SettingsScreen.kt`、`AgentSupervisor.kt` | ☑ 已實作（編譯通過） |
| X-06 | 核准回答減為兩個：「只這次」與「本任務內允許（最窄的範圍）」；不再預設給最寬的「這一類動作」 | `AttentionCards.kt`、`CapsuleUi.kt` | ☑ 已實作（編譯通過） |
| S-04 | 核准卡片顯示剩餘時間（收件匣、膠囊、Current）；剩 1 分鐘時再發一次通知 | `AttentionCards.kt`、`CapsuleUi.kt`、`AgentService.kt` | ☑ 模擬器已驗證 |
| — | 關於頁寫「GNU GPL v3」，但授權已改為 MIT | `SettingsScreen.kt` | ☑ |

### 第 2 階段　設計基礎（taste-skill）

| 編號 | 修正內容 | 狀態 |
| --- | --- | --- |
| 基礎 | 內建字體（Newsreader、Geist），更新 `PorcelainTheme` 字級；`Semantic` 顏色改映射到 Porcelain Tone；新增 `PorcelainPage`（返回頁首）、`PConfirm`（確認對話框）、`PEmpty`（置中空狀態）、`ScrollFade`（可捲動提示）、`DisabledReason` | ☑ 已實作（編譯通過） |
| F-03 | 建立 `res/values/strings.xml` 與 `res/values-zh-rTW/strings.xml`；之後新寫的文字一律走資源 | ☑ 已實作 |
| L-04 | Settings、Setup、Models、Workspaces（含檔案瀏覽）、Processes、Displays、Diagnostics、Approval history 改用 Porcelain 頁首與元件，頂列都有緊急盾牌 | ☑ 已實作（編譯通過） |

### 第 3 階段　卡住的地方與首次設定

| 編號 | 修正內容 | 狀態 |
| --- | --- | --- |
| X-01 | 設定頁首屏只顯示 3 個必要項目（螢幕控制、Linux、AI 模型）與進度；其餘收進「進階」。chroot/proot 自動選擇，不出現在首屏 | ☑ 已實作（編譯通過） |
| S-01 | 偵測受限設定（Android 13+ 的 `ACCESS_RESTRICTED_SETTINGS`）時，自動顯示三個圖示步驟，每步一個按鈕（開 App 資訊 → ⋮ → 允許受限設定 → 回來開啟） | ☑ 模擬器已驗證 |
| S-02 | 「列出可用模型」旁直接寫「需要先安裝 Linux 環境（約 2 GB）」並連到安裝頁；未安裝時按鈕停用並顯示原因 | ☑ 已實作（編譯通過） |
| S-03 | Commit 頁頂部：proot 手機無法使用 broker 的說明與可用替代方式 | ☑ 已實作（編譯通過） |
| S-05 | 診斷「全部執行」加進度與取消 | ☑ 已實作（編譯通過） |
| S-06 | 節點配對標為「給開發者」，移到進階區 | ☑ 已實作（編譯通過） |
| S-07 | 停用按鈕旁一行原因（Clone 需要 Linux、Link 需要專案） | ☑ 已實作（編譯通過） |
| S-08 | 解除鎖定前列出將保持暫停的任務；解除後提供「全部恢復」 | ☑ 模擬器已驗證 |
| F-04 | 未在實機驗證的路徑（Shizuku、Laya）加「尚未在實機驗證」標記，收進進階 | ☑ 已實作（編譯通過） |

### 第 4 階段　密度、白話與版面

| 編號 | 修正內容 | 狀態 |
| --- | --- | --- |
| D-03 / L-03 | Current 頁首：標題單行可展開；狀態 chip 收進「狀態」列（右側漸層提示可捲動）；分段控制填滿等寬 | ☑ 已實作（編譯通過） |
| X-02 | 任務內面板分「基本」（追問、暫停、停止）與「進階」 | ☑ 已實作（編譯通過） |
| X-03 | Shared Services、Commit、Planning 的標題改白話，規格術語收進「詳情」 | ☑ 已實作（編譯通過） |
| X-04 | 提交流程改為兩句結論：「可以直接發布」或「會改寫遠端歷史，需要另外確認」；五步細節收進「詳情」 | ☑ 已實作（編譯通過） |
| X-07 | 終端機接管：「接管」與「請求交接」兩個按鈕，歸還放在接管狀態內 | ☑ 已實作（編譯通過） |
| D-01 | 金庫：預設顯示最近三筆（動作、時間、結果），原始 JSON 收進「查看紀錄」 | ☑ 已實作（編譯通過） |
| D-02 | 節點工作：一行摘要 + 狀態標籤，展開看租約與 revision | ☑ 已實作（編譯通過） |
| D-04 | 安全與證據：只顯示目前狀態與一個主要動作，其餘收進「隱私設定」 | ☑ 已實作（編譯通過） |
| D-05 | 設定與安裝頁說明句改一句話，「為什麼」收進展開 | ☑ 已實作（編譯通過） |
| D-06 / L-01 | 規劃頁預設顯示進度，三種圖放進分頁；Session 選擇改為可換行的卡片並以省略號截斷 | ☑ 已實作（編譯通過） |
| L-02 | Snapshot 工具列：實心底座、Clear 常駐（未選取時停用）、主次分明且寬度固定 | ☑ 已實作（編譯通過） |
| L-05 | Home 統一對齊 | ☑ 已實作（編譯通過） |
| L-06 | 收件匣空狀態垂直置中並給下一步 | ☑ 已實作（編譯通過） |
| L-07 | 資源頁「Independent sessions」縮短為「Independent」 | ☑ 已實作（編譯通過） |

### 第 5 階段　功能缺口

| 編號 | 修正內容 | 狀態 |
| --- | --- | --- |
| F-01 | Emergency 頁為「停止」列出能保證與不能保證的事。分階段 Execution Halt（AC-008、AC-009）需要程序訊號與 cgroup 控制，這個環境不允許實作，本計畫不做，畫面不宣稱能安全停止 | ☑ 只做說明（halt controller 不做） |
| F-02 | 節點失聯的工作卡片標示「需要手動重新派工」，提供需要核准的一鍵重派（同一指令，同一或另一節點） | ☑ 已實作（編譯通過） |
| F-05 | 新增唯讀「Reference」頁：架構規格 v0.1 全文（隨 App 附帶）與 Porcelain 設計系統（真實元件展示） | ☑ 已實作（編譯通過） |

### 第 6 階段　中文化收尾與驗證

| 編號 | 修正內容 | 狀態 |
| --- | --- | --- |
| F-03 | 掃完其餘畫面的字串；core 模組產生的訊息（錯誤、代理看到的文字）維持英文，在報告中註明 | ☑ 1665 個字串，英文與繁中鍵值一致 |
| 驗證 | `./gradlew check`；模擬器安裝；英文與繁中各截一次每個改過的畫面；taste-skill 的「截圖 → 找前三個問題 → 修」最多三輪 | ☑ check 綠燈（806 測試、lint 0 錯誤）；英文與繁中各 24 個畫面已截圖 |
| L-08 | 宣傳截圖重錄（錄製時關閉通知）。重新部署網站屬於對外發布，需要你同意後才做 | ☐ 待你同意 |

## 不做或部分做的項目

- **F-01 halt controller**：需要程序訊號與 cgroup 控制，先前在這個環境被安全機制擋下，不再嘗試。只改畫面說明。
- **core 模組字串**：`core/*` 是純 JVM 模組，沒有 Android 資源；它們的訊息多半是給代理看的，維持英文。
- **L-08 宣傳素材**：可以重錄，但部署到 umbra-agent.pages.dev 要你點頭。

## 驗證方式

- 單元測試：核准分級規則、`deleteWorkspace` 拒絕條件、核准紀錄隱藏不刪檔。
- 模擬器（`umbra36`）：每個階段結束安裝 debug APK，實際點擊對應流程（刪除工作區、刪除任務、清單隱藏、通知核准按鈕、停止全部、收回螢幕、受限設定步驟、倒數），截圖存 `docs/review/after/`。
- 繁中：切換系統語言為 zh-TW 再截一輪，檢查截斷與換行。
