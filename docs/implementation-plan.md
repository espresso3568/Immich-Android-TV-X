# Immich TV Plus：第一階段分析與實作計畫

分析日期：2026-10-20（Asia/Taipei）  
分析基準：`037cb7ce0d94f36113afd0bec19b84f23ec705ed`，版本 `6.2.0 (148)`。

## 最終實作與交付狀態（2026-10-20，優先閱讀）

使用者已授權一路完成開發、安裝建置工具與封裝 APK。以下是最終狀態；後面的中期紀錄與第一階段分析保留作為歷史快照，不能當成目前尚未完成的工作。

### 已完成的需求

- Screensaver 類型選「相簿」時，自動把所有選取相簿完整讀取、依素材 ID 去重、混合洗牌。不是每本各抽幾張，也不是只洗牌已載入的首頁。此來源沒有另外的 Shuffle 開關；其他來源沿用原有行為。
- 每次啟動建立新的隨機順序，不保存播放位置或已播歷史。每輪素材各出現一次，輪末重新洗牌；多頁時避免跨輪緊接著顯示同一頁。不同次啟動仍可能偶然出現相同照片。
- 一般相簿新增「隨機播放」設定，預設關閉、各相簿獨立。開啟時完整讀取目前篩選條件下的相簿，以使用者點選的照片作為起點，其餘頁面洗牌，播完後重洗。網格的日期排序與原本播放方式保留。
- App 名稱為 **Immich TV Plus**，Application ID 為 `com.jerry.immichtv.plus`，目前版本為 `1.0.2`／versionCode `3`。獨立資料、深層連結 scheme 與 Screensaver 元件設定可與原版區隔。
- 已建立專用 RSA 3072 簽署金鑰並產出正式 Release APK。Firebase 預設停用，私人版不需使用上游 Firebase 專案；APK 不預填私人 Immich 網址或 API Key。

### 實作範圍與資料流程

1. `api/AlbumAssetLoader.kt`：以 metadata API 逐相簿、逐頁讀取到 `canLoadMore=false`；每頁最多 1000 筆，依 ID 去重。失敗不回傳不完整成功結果，取消時停止工作。
2. `screensaver/ScreenSaverAssetLoader.kt`：完整相簿聯集 → 原有排除條件 → 新洗牌 → 直式配對 → `shuffleOnRepeat=true`、`loadMore=null`。系統服務與 App 預覽共用載入流程，各次啟動建立自己的播放清單。
3. `album/AlbumDetailsFragment.kt`、`assets/GenericAssetFragment.kt`、`shared/prefs/Preferences.kt`：新增一般相簿設定及完整清單載入分支；關閉 Shuffle 時維持原本分頁播放。
4. `mediaslider/.../util/ShuffleRound.kt`、`MediaSliderController.kt`、`MediaSliderView.kt`：管理正反向輪末、單頁循環、重新洗牌與影片頁釋放，防止舊播放器重複結束通知造成跳頁；補上載入尚未完成就退出的保護。
5. `shared/util/AssetUtil.kt`：保序直式配對改為線性走訪；同次啟動配對固定，後續每輪洗牌頁面，所有素材仍各出現一次。
6. Gradle、Manifest、字串與 Screensaver XML：名稱、套件識別、簽署、繁體中文新字串及獨立元件。修正原有系統 Screensaver 設定頁成功解析時沒有開啟 Activity 的問題。
7. `scripts/build-release.ps1`、`docs/release-guide.md`：重建與安裝說明。CI 的商店發佈預設關閉；本次沒有推送、發 PR 或發佈商店。
8. 認證修正：`api/ApiClientFactory.kt` 避免網路例外後重送未認證請求、確保只有一份 Key Header、拒絕空值／非法字元且不保留可能含 Key 的例外；`auth/GuidedFieldValue.kt` 修正欄位回收後可能讀為字串 `null`；`AuthFragmentStep2.kt`、`ApiClient.kt`、`ApiService.kt` 新增提交前伺服器驗證與儲存一致性檢查。1.0.2 再修正登入成功後使用新的 MainActivity task，釋放持有舊 Key 的 Activity 資料模型；加入只含狀態／布林／數量的診斷紀錄。

### 已完成的驗證

- 最終 Gradle 建置成功：Debug、Release、R8 與 Release 的必要 lint 檢查完成。
- 1.0.2 App 單元測試 167 項、播放器 7 項，共 **174 項通過，0 失敗、0 跳過**。涵蓋原有分頁／1001 筆、跨相簿去重、失敗與取消、直式配對、洗牌排列、正反向輪末、單頁、影片結束通知與退出保護，以及新增的 Header、網路例外、欄位讀取、伺服器認證回應及圖片 client 更換 Key 測試。
- 1.0.1 正式 APK 以 Android TV 獨立測試使用者及嚴格認證假伺服器驗證：錯誤 Key 留在登入頁；正確 Key 回應 200、`authStatus=true`，儲存／使用中值一致；可開相簿及圖片，強制關閉重開後仍可讀取相簿。已切回原使用者並刪除臨時測試使用者，原有設定保留。下列播放實測在 1.0.0 完成，本次未改播放程式。
- 1.0.2 正式 APK 的更換 Key 回歸：保留 App 程序及原有圖片 loader，重新登入另一把公開假 Key；首頁、相簿、metadata 搜尋、全新網址的縮圖／原圖均回應 200，沒有 401。主 Activity 已重建；強制關閉重開後仍能讀取相簿。測試使用者已刪除、切回原使用者，私人登入資料保留。
- Android TV API 36 模擬器：正式版首次啟動、以測試伺服器登入、相簿設定開關及相簿幻燈片通過；兩張照片連續 3 輪各播放一次。兩張時因避免跨輪相鄰重複，順序可固定交替，不能用這個例子判定洗牌沒運作。
- 同一正式版的真正系統 DreamService（`isPreview=false`）：相簿 A＝紅／綠、B＝綠／藍，去重後 3 張；連續 3 輪依序為「藍紅綠／紅藍綠／藍綠紅」，每輪完整且不重複。再次啟動第一輪為「綠紅藍」，沒有恢復上次位置。測試使用本機假資料，沒有讀取私人相片。
- `apksigner verify` 驗證成功，APK Signature Scheme v2 有效；`zipalign -c -P 16 4` 通過。正式 APK 沒有 debuggable 標記，包含 ARM64、ARMv7、x86、x86_64。
- 已連接使用者的 Immich v3.2.2，確認 Key 可驗證及讀取相簿／metadata；重新啟動 App 後也成功顯示相簿。尚未連接實體 TCL C765。真實大相簿準備時間、長時間運作、HDR／HEVC 等影片硬體解碼與 TCL 系統行為仍需實機驗收；混合影片的輪次控制已有單元測試，不能等同於實機解碼驗證。

### 工具、產物與金鑰

- 原本缺少的建置工具已補齊於 `%LOCALAPPDATA%\ImmichTVPlus`：Microsoft JDK 17.0.20.1+1、Android SDK platform 37、Build Tools 36.0.0、Platform Tools、API 36 Android TV 模擬器。沿用專案 Gradle 9.6.1／AGP 9.2.1／Kotlin 2.4.0；未改全機 Java 設定。
- 可安裝檔：`artifacts/ImmichTVPlus-1.0.2-release.apk`，30,130,613 bytes（約 28.7 MiB）；舊版仍保留供追溯。
- APK SHA-256：`010172aa335265ac6cd536e2de8dd67e3961f1cc35ce3c83fddf14120284298c`。
- 簽署憑證 SHA-256：`e712c6728e675325e595f95da7a03bbb33f4a13df37b7abf943cc126c808ce11`。
- 專用金鑰與設定位於 `%LOCALAPPDATA%\ImmichTVPlus\signing`；私人備份為 `C:\Users\jerry\ImmichTVPlus-PrivateBackup\immich-tv-plus-signing-backup.zip`。金鑰不在儲存庫或 APK 交付目錄，資料夾存取已限制。後續升級必須沿用這把金鑰。
- 驗證報告：`artifacts/verification-report.md`；安裝及重建步驟：`docs/release-guide.md`。Git 整合與授權檢查記錄見下一節；先前未提交的狀態已由本次整合取代。

### 2026-10-20 main 整合與授權

使用者確認模擬器驗收成功，並要求合併 main。本次將開發成果及授權說明提交至 `feature/immich-tv-plus`，再合併到本機 `main`，保留功能分支。整合前已 fetch origin，確認本機 main 與 origin/main 均在基準提交，沒有遠端分歧。此操作不包含推送 GitHub 或發佈 Release。

保留主專案 GPLv3 及內附播放器 Apache 2.0 授權，新增 `NOTICE.md`、`docs/licensing.md`、README 授權及修改日期說明，並為已修改的上游播放器檔案補上修改標示。合併時沒有更動播放或認證功能；沿用已通過 174 項測試及使用者驗收的 1.0.2 APK。授權標示屬於註解／文件補充，不另行改版封裝。

個人自用修改受授權允許；若提供 APK 給其他人，需依 GPLv3 安排對應完整來源，並保留適用授權及修改標示。來源封存依合併後的 main 產生，不包含私人簽署資料或本機設定。詳見 `docs/licensing.md`。

### 實機驗收與剩餘風險

1. 安裝 Plus、登入自己的 Immich；確認原版仍可開啟。系統 Screensaver 選取 Plus，來源選「相簿」並勾選多本相簿；此時自動混合洗牌。
2. 先用有重疊照片的兩本小相簿檢查完整輪次，再退出重開，確認從新洗牌開始。接著測超過 1000 筆的大相簿及含影片相簿。
3. 大相簿會先讀完 metadata 才開始，啟動時間及清單記憶體隨素材數增加；沒有先下載全部原始照片／影片。播放期間清單固定，伺服器新增素材要下次啟動才納入；已刪除或無權限素材可能載入失敗。
4. 直式合併會縮減頁數，但不縮減素材範圍。原有「排除指定相簿」取得排除 ID 的路徑仍有既存分頁限制，超大排除相簿另需改善；這與本次所選播放相簿的完整分頁載入不同。
5. 請在 TCL 測試影片結束、暫停／繼續、退出與長時間循環。這些屬於最後的裝置與資料驗收，目前沒有缺少會阻止重新建置 APK 的工具。

## 1.0.1 認證問題分析補充

使用者回報進入首頁後開啟相簿／照片出現 401，且相同 Key 可供其他工具使用。舊版登入並未向伺服器驗證，只檢查 URL／Key 非空。既有紀錄中有 `Invalid API key`，未觀察到認證 Header 建構例外或加密／解密失敗，因此不能將根因直接歸咎於 Key 填錯，也不能宣稱下列修正已解決該次 401。

1. 攔截器的舊 `try/catch` 同時包住 Header 建構與 `chain.proceed`。網路等例外後會重送原始、未帶 Key 的請求；新增測試先重現失敗，再修正為只處理 Header 建構錯誤，網路錯誤直接傳遞。改用 `header` 取代 `addHeader`，避免重複值。
2. 登入頁透過可能不存在的 View 讀欄位，對 null 呼叫 `toString()` 可能得到字面值 `null`。現在優先讀正在編輯的值，View 不存在時使用已提交的描述，且尊重使用者清空欄位。
3. 新登入流程使用 [Immich 官方認證控制器](https://github.com/immich-app/immich/blob/main/server/src/controllers/auth.controller.ts) 的 `POST /api/auth/validateToken`；只有 200 且 `authStatus=true` 才保存及導頁。查閱 current、v2.0.0、v2.5.0 的控制器均有該路徑，認證要求不另加 API permission。使用者實際伺服器版本仍待提供。
4. 診斷輸出限制為 HTTP 狀態、是否有 Header／值是否一致／數量、重新導向旗標及儲存一致性。沒有記錄 Key 內容。提交檢查停用 response-body debug logger。
5. 獨立 QA 使用公開假 Key，包含 `+`、`/`、`=`，嚴格假伺服器拒絕缺漏／錯誤／重複 Header。正式 APK 已通過登入、相簿／圖片與重開測試；沒有以使用者私人 Key 做假伺服器測試。

後續實測：真實伺服器為 v3.2.2，以 HTTPS 經 Cloudflare 連線。認證、相簿及 metadata API 直接測試均為 200；App 儲存檢查也一致，但重新登入後首頁仍出現 401。強制關閉再開則成功載入相簿。`TimelineFragment.onCreate` 使用 `ViewModelProvider(requireActivity(), TimelineViewModelFactory(apiClient))`，重新導航仍取回舊模型，其 fetch 閉包保留原本 API client。以公開假 Key 切換的隔離測試，也已在 1.0.1 重現驗證 200 後首頁仍 401。1.0.2 改在驗證與儲存成功後建立全新的主畫面 task，釋放舊 ViewModel；不清除登入或 Screensaver 設定。

同一隔離測試還發現圖片路徑與 metadata 不同：Glide 可以保留原有 loader，替換 registry 並不足以確保每個請求更換 Key，實際出現相簿 200、縮圖／原圖 401。`CustomGlideModule.kt` 改用每次請求即時取得目前 Key 的 provider；會先檢查請求的 scheme／host／port 與目前伺服器一致，避免舊伺服器請求取得新 Key。一般 Retrofit API 仍使用固定設定，兩種生命週期分別處理。新增測試確認同一圖片 client 更換 Key 後送出新值，以及 provider 拒絕時沒有網路請求。

測試過程只在記憶體中使用本機表單已填的設定，未輸出 Key、私人網址、相簿名稱或照片內容。臨時測試 Key 不必放在聊天或文件中。Cloudflare 另會將 Python 預設 User-Agent 擋為 403，使用 okhttp User-Agent 後相同請求為 200；這是診斷工具的差異，不能與 App 的 401 混為一談。

## 實作中期紀錄（歷史快照）

使用者已確認並要求繼續實作：優先處理 **Screensaver 選取多本相簿** 的情境。每次啟動重新讀取並洗牌合併清單，同一素材跨相簿以 ID 去重；同次播放每輪不重複，輪末重新洗牌。**不記住播放進度，也不保存跨次啟動的已播歷史**，因此不同次啟動仍可能偶然出現相同照片。

本輪原始碼已加入以下變更，尚待建置與真機驗證：

- 相簿來源從 metadata 第 1 頁讀到 `canLoadMore = false`，逐頁、逐相簿依序請求，每頁最多 1000 筆，避免不受限的全量併發。完整讀取失敗就顯示錯誤，不把部分清單當完整相簿。
- 完整清單先按 ID 去重、套用排除規則，再於背景洗牌及轉換頁面。停止使用相簿來源的「首批隨機抽樣＋後續附加完整相簿」，不再持續擴張同一佇列。
- 真正 Screensaver 與 App 內預覽均於相簿準備期間顯示載入指示，取得設定或顯示錯誤／無素材訊息後隱藏，避免大相簿等待時只有黑畫面。
- 直式照片仍可合併；配對於同工作階段維持固定，輪末洗牌頁面。保序的相鄰配對改為線性走訪，不再反覆移除 list 頭部。
- 共用播放器新增預設關閉的 `shuffleOnRepeat`，目前只由 Screensaver 的相簿來源啟用。跨輪替換資料時釋放舊影片頁、保留同一 adapter／listener／plugin 集合，避免輪次切換重複註冊。
- 兩頁以上時避免跨輪連續顯示同一頁；單頁可持續輪播。暫停／繼續不重洗。以目前頁面及轉場狀態阻擋重複影片結束事件。
- 新增分頁、去重、失敗／取消、1001 筆、洗牌不變條件、正反向邊界、單頁、影片結束事件及普通播放循環測試；擴充 Screensaver loader 與直式配對測試。

此模式在「Screensaver 類型＝相簿」時自動生效，**本輪沒有新增獨立 Shuffle 開關**。其他 Screensaver 來源與一般相簿的日期排序維持原有路徑。下面第 8 節的全來源開關方案是第一階段的原始建議，不能視為本輪已完成項目。

驗證狀態：已做原始碼檢視及 `git diff --check`，未安裝軟體、未執行 Gradle、未編譯或執行 JUnit。Windows 目前仍找不到可用 `java`／`javac`／`keytool`／`kotlinc`。因此「測試已新增」不代表「測試已通過」，播放器的輪末轉場仍須實際執行驗證。

下一階段需完成建置基線及上述測試，接著在 TCL C765 實測完整相簿準備時間、單張／影片輪末及長時間循環。一般相簿 Shuffle、App 更名／Application ID、獨立金鑰與 Release APK 尚未執行。本輪未產出可安裝 APK。

以下內容保留第一階段當時的分析快照；檔案行號及「目前程式」的描述均以該分析基準提交為準。

## 1. 範圍與結論

本階段只新增本文件。已唯讀檢查本機 Git、Kotlin／XML／Gradle、既有測試與 Windows 環境；未修改 App 原始碼、切換分支、更新 Submodule、安裝軟體、產生金鑰、執行 Gradle、編譯、執行測試或連線操作電視。官方文件查閱只用於核對建置工具及套件識別規則。

需求可沿用現有架構完成，主要修改會落在「素材完整載入」、「共用播放器的輪次管理」、「設定」與「App 識別／簽署」。目前相簿沒有 Shuffle；Screensaver 雖有隨機抽樣及 `.shuffled()`，卻沒有完整播放輪次，不能保證每輪不重複。

建議新增獨立的 Shuffle 開關，不把隨機功能塞進日期排序比較器。啟用時建立完整、以素材 ID 去重的播放清單，每輪播放一次全部素材，輪末重新洗牌；停用時沿用原有行為。目前本機尚未具備可用的 Android 建置環境，不能宣稱可成功編譯或已符合 TCL C765 的實機播放要求。

## 2. Git、分支與 Submodules

| 項目 | 檢查結果 |
| --- | --- |
| 工作目錄 | `C:\Users\jerry\ImmichTV-X\Immich-Android-TV-X` |
| 起始狀態 | `git status --porcelain=v1 --untracked-files=all` 無輸出；追蹤檔案與未追蹤檔案皆無待處理變更 |
| 目前分支 | `feature/immich-tv-plus`；未顯示遠端追蹤分支 |
| 其他本機分支 | `main`，追蹤 `origin/main`；目前本機記錄與功能分支同在 `037cb7c` |
| 最新提交 | `Create new release: 6.2.0 (148)`，提交日期 2026-10-16 |
| origin | `https://github.com/xyzhong987/Immich-Android-TV-X.git`；沒有設定 upstream remote |
| 專案來源 | README 指向 `giejay/Immich-Android-TV`；本次以目前 Fork 內容為準，未比對 upstream 差異 |
| 專案指示 | 已查找專案內及沿途父目錄，未找到適用的 `AGENTS.md` |

`.gitmodules` 宣告 `mediaslider` 指向 `https://github.com/giejay/MediaSliderView`，但目前 Git 內容與這份宣告不一致：

- `git submodule status` 及 `git submodule status --recursive` 都沒有輸出。
- Git index 沒有 `160000` gitlink；`git ls-tree HEAD mediaslider` 顯示 `040000 tree`。
- `mediaslider/` 內有 117 個由主儲存庫直接追蹤的檔案。因此現在應把它視為隨附的 library 原始碼，不必初始化 Submodule 才能修改播放器。
- 主專案 `settings.gradle` 實際包含 `:app` 與 `:mediaslider:mediaslider`。`mediaslider/` 內的獨立範例 app、wrapper 與根 build 檔不是主專案此次建置入口。

未執行 `git fetch`，因此遠端是否另有新提交並未確認；本文件的分支比較是本機快照。本階段不整理過時的 `.gitmodules`，避免擴大變更。

## 3. 實際檔案與資料流程

以下路徑皆相對儲存庫根目錄。為方便閱讀，使用兩個縮寫：

- `APP` = `app/src/main/java/nl/giejay/android/tv/immich/`
- `SLIDER` = `mediaslider/mediaslider/src/main/kotlin/nl/giejay/mediaslider/`

### 3.1 相簿排序與載入

| 檔案／位置 | 實際責任 |
| --- | --- |
| `APP/album/AlbumFragment.kt`、`APP/shared/prefs/AlbumsOrder.kt` | 相簿清單及相簿名稱、更新時間、素材數量等排序；與相簿內照片排序不同 |
| `APP/album/AlbumDetailsFragment.kt:21–40` | 讀取該相簿的篩選／排序設定，以 `albumIds` 查詢素材；新到舊送 `desc`，其他情況送 `asc` |
| `APP/shared/prefs/PhotosOrder.kt:8–31` | 目前只有 `NEWEST_OLDEST`、`OLDEST_NEWEST`；本機比較欄位為 EXIF 原始日期，缺值時用檔案修改日期 |
| `APP/shared/prefs/Preferences.kt:356–369,650–655` | 每相簿排序 key 為 `photos_sorting_<albumId>`；相簿設定畫面由 Kotlin 建立 |
| `APP/settings/AlbumDetailsSettingsFragment.kt` | 開啟對應相簿的設定畫面 |
| `APP/assets/GenericAssetFragment.kt:59–110` | 訂閱排序／內容類型變更，重新載入；排除標籤及指定相簿素材；執行本機日期排序 |
| `APP/shared/fragment/VerticalCardGridFragment.kt:152–231,283–291,358–362` | 網格從第 1 頁開始，每頁 50 筆；逐頁載入、分批顯示，每批排序後附加到既有清單 |
| `APP/api/ApiClient.kt:62–96,200–229`、`APP/api/service/ApiService.kt` | 一般查詢走 `POST search/metadata`；回應以 `nextPage != null` 表示仍可載入 |

流程：相簿設定 → `AlbumDetailsFragment.loadItems()` → API → 網格篩選／每批排序 → 顯示卡片。網格載入介面只保留 `List<Asset>`，沒有保留 API 的 `nextPage`，因此網格用「原始回傳筆數小於 50」推定最後一頁。

### 3.2 相簿幻燈片

1. `APP/assets/GenericAssetFragment.kt:134–177`：點擊照片後，將目前已載入的 `assets` 轉成播放器項目。使用 `toSliderItems(keepOrder = true, ...)`，起點由點擊素材的 ID 決定。
2. 同處建立 `loadMore` callback；播放器接近尾端時繼續呼叫原網格的分頁方法，並同步回填網格。
3. `APP/shared/util/AssetUtil.kt:17–76`：把照片／影片轉成 `SliderItemViewHolder`；可把兩張直式照片合成一頁，因此頁數不一定等於素材數。
4. `SLIDER/viewmodel/MediaSliderViewModel.kt` 保存 `MediaSliderConfiguration`，導航至 `APP/slider/ImmichMediaSlider.kt`。
5. `ImmichMediaSlider.kt:63–67` 載入 `MediaSliderView`。一般相簿進入後不會自動開始幻燈片；使用者按播放啟動。`timelineView` 的 Memories 模式才在此自動播放。
6. `SLIDER/view/MediaSliderController.kt:93–161,197–213`：照片靠計時器前進；影片收到 `STATE_ENDED` 才前進。現有功能包含暫停、前後切換及反向幻燈片。
7. 現有最後一頁的處理只跳回索引 0，沒有重洗牌，也沒有輪次狀態。

`SLIDER/view/MediaSliderView.kt:194–203` 在距尾端約 40 頁時非同步預載，`277–298` 附加及替換清單。`SLIDER/adapter/ScreenSlidePagerAdapter.kt:37–51,146–156` 更新資料會讓頁面重建，影片頁銷毀時釋放播放器。

### 3.3 Screensaver 與預覽

| 檔案 | 實際責任 |
| --- | --- |
| `APP/screensaver/ScreenSaverService.kt` | 真正的 Android `DreamService`；建立帶 API key 的共用播放器，收到設定後啟動幻燈片；停止時取消工作及釋放播放器 |
| `APP/screensaver/ScreenSaverPreviewFragment.kt` | App 內「立即開始」的預覽，與 DreamService 共用 loader／設定建構；預覽成功不等於系統螢幕保護程式已成功註冊 |
| `APP/screensaver/ScreenSaverAssetLoader.kt:71–101,141–254` | 選擇來源、排除素材、取得資料、建立 `MediaSliderConfiguration`；真正服務與預覽共用此流程 |
| `APP/screensaver/FilteredAssetLoader.kt:59–85` | 若整批都被排除且來源還能載入，退避等待後繼續取得；不是去重或輪次管理器 |
| `APP/screensaver/ScreenSaverType.kt` | 四種來源：`ALBUMS`、`RANDOM`、`RECENT`、`SIMILAR_TIME_PERIOD` |
| `APP/shared/prefs/Preferences.kt:185–203,616–631` | 預設來源為近期，預設不含影片、不播聲音；現無獨立 Shuffle 開關 |

Screensaver 相簿來源先對各相簿做隨機抽樣，數量約為每相簿 `max(50 / 相簿數, 1)`。空結果／錯誤會嘗試一般相簿查詢，後續載入則改用完整相簿資料。完整相簿方法 `ApiClient.listAssetsFromAlbum()` 會讀取相簿 `assetCount`，計算頁數，再抓各頁；不是只回傳一頁。

## 4. 現有隨機功能能重用到哪裡

| 現有邏輯 | 可重用部分 | 不足之處 |
| --- | --- | --- |
| `ApiClient.listAssets(random = true)` | 既有隨機瀏覽功能可保留 | `POST search/random` 每次獨立抽樣，程式主動移除 `page`／`order`，並一律回報 `canLoadMore = true`；無法據此知道全部播完 |
| `ApiClient.recentAssets()`、`similarAssets()` | 日期範圍計算可抽出重用 | 內部也使用隨機 API，不是依頁碼遍歷完整近期／季節性資料 |
| `ScreenSaverAssetLoader.kt:220,249,253` 的 `.shuffled()` | 可用同一種標準洗牌方式產生每輪順序 | 只處理當批資料，沒有已播 ID、完整母清單或輪末處理 |
| `AssetUtil.toSliderItems(keepOrder = false)` | 既有直式照片合併顯示可保留 | 只有啟用直式合併時才進入最後的洗牌；停用合併時直接回傳原順序，不能當作通用 Shuffle |
| `MediaSliderView.addItems()` 的 `.distinct()` | 目前有播放器項目的基本去重 | 比較整個資料物件，而非素材 ID；同一素材中繼資料／配對改變仍可能重複，且沒有每輪重洗 |
| `APP/assets/RandomAssetsFragment.kt` | 原有「隨機」瀏覽入口 | 仍為隨機抽樣後套用共用網格；不提供完整、不重複的播放輪次 |

結論：沿用現有 API、播放器與標準洗牌工具即可，但必須新增有限資料來源與輪次狀態；不能只在現有清單上補一次 `.shuffled()`。

## 5. 技術風險與處理原則

| 風險 | 程式碼觀察／影響 | 建議處理 |
| --- | --- | --- |
| 僅洗牌已載入頁面 | 相簿初始通常只有 50 筆；尾端載入未完成就可能跳回第一頁 | Shuffle 啟用時先完整取得此次素材清單，再建立輪次；一般模式保留原 lazy loading |
| 相簿重疊／抽樣重複 | Screensaver 首批抽樣後又載入完整相簿，同一素材也可能同屬多個相簿 | 在轉換播放器頁面之前，以 `Asset.id` 去重；排除清單也用 ID set |
| 分頁終止判斷 | 網格丟棄 `canLoadMore`，靠筆數判定；`clearState()` 未重設 `allPagesLoaded`；載入前先增加頁碼，錯誤可能漏頁 | 新的完整清單 loader 保留 `nextPage` 語意，成功後才推進頁碼；區分失敗與正常結束。既有網格問題列為回歸觀察，避免無關重構 |
| 完整相簿抓取方式 | `listAssetsFromAlbum()` 依相簿總數推算頁數，`pmap` 對相簿及頁面使用 `async/awaitAll`，沒有明確併發上限；不以每頁 `nextPage` 動態停止 | 重用查詢／篩選規則，新增逐頁或小量有上限併發的完整清單載入方法；每次 size 不超過專案的 1000 上限 |
| 大相簿記憶體與啟動延遲 | 完整 Asset、EXIF、人物資料及 SliderItem 可能同時留存；原播放器的無限隨機附加也會持續長大 | 清單只持有本輪所需資料／ID，不預先下載所有媒體；背景執行、可取消、有載入狀態；跨輪替換而非持續 append |
| 直式照片合併成本 | `AssetUtil` 有 list 頭部移除；無序配對還會反覆搜尋候選，資料大時有平方級成本 | 避免每輪重做昂貴人物配對；Shuffle 路徑採線性遍歷的相鄰直式配對，再洗牌頁面，成對照片於此次播放工作階段維持同組 |
| 頁面數與素材數不同 | 一頁可能含兩個 ID | 不重複驗收以 `ids()` 展開後的素材 ID 計算；不能只檢查頁面索引 |
| 輪末重建播放器 | `notifyDataSetChanged()` 會重建頁面／釋放影片；目前 `setItems()` 會重啟照片計時器 | 輪次切換集中於主執行緒，停止舊計時器／影片，再更新清單與位置；用轉場狀態防止影片結束、手動前進及計時器重複觸發 |
| 單一素材／空清單 | 相同索引可能不觸發翻頁 callback，造成單張照片計時中斷；空清單則可能索引錯誤 | 0 筆安全提示／退出；1 筆持續顯示或依規則重播，不要求順序變更；2 筆以上才避免跨輪連續同一素材 |
| 影片與壞檔 | 現有影片結束才前進，錯誤時跳下一項；Adapter 還有 SurfaceView → TextureView 重試 | 保留影片完整播放、聲音、seek、解碼重試及錯誤跳過；播放順序由同一個入口推進，避免跳兩項 |
| 所有素材都被排除 | 現有 FilteredAssetLoader 對可重試來源可能長時間退避等待；不代表有完整清單 | 有限來源讀完後明確回報 0 筆，不無限抽樣；網路失敗不能當成「全部播完」 |
| 共享／封存素材 | 程式已為共享相簿提供隨機空結果 fallback；相簿查詢不帶全域 `visibility=timeline` | 完整清單沿用相簿 scope 及既有可見性規則；驗證共享、封存、排除相簿及排除標籤 |
| 播放期間資料改變 | API 尚未提供給此 App 原子快照；載入期間伺服器增刪素材可能令頁碼結果移動 | 保證對象定義為此次成功收集的唯一 ID 清單；固定清單播放期間不插入新素材。重新進入時刷新；不宣稱可保證伺服器持續變動時的完整覆蓋 |

不重複保證適用於自動播放的單一輪次。使用者主動回上一張屬於回看；壞檔最多在本輪消耗一次佇列位置並跳過，不代表壞檔已成功顯示。暫停／繼續不得重洗牌。若改變來源或內容篩選，建立新工作階段。

對超大媒體庫，完整索引仍有 O(N) 空間成本。建議先以 1,001 與 10,000 筆資料驗證；若 TCL 記憶體或等待時間不可接受，再評估磁碟索引及按需取得中繼資料，屆時會是額外架構工作。不可用偷偷截斷清單或退回重複抽樣來聲稱完成本需求。

## 6. App 名稱、識別、Gradle 與簽署

### 6.1 現況

| 設定 | 目前內容 |
| --- | --- |
| App 名稱 | `app/src/main/res/values/strings.xml:18`：`Immich TV`，`translatable="false"` |
| Launcher label | `app/src/main/AndroidManifest.xml` 使用 `@string/app_name` |
| Screensaver label | Manifest 的 DreamService 硬編碼 `Immich`，需一併更名 |
| Gradle 專案名稱 | 根 `settings.gradle`：`Immich TV` |
| Application ID | `app/build.gradle:15`：`nl.giejay.android.tv.immich` |
| App namespace | `app/build.gradle:62`：`nl.giejay.android.tv.immich` |
| 播放器 namespace | `mediaslider/mediaslider/build.gradle`：`com.zeuskartik.mediaslider` |
| SDK | app 與實際 library 均 compile／target 37、min 24 |
| Java／Kotlin 輸出 | Java 17、Kotlin JVM target 17 |
| AGP／Kotlin／KSP | 版本目錄 `gradle/libs.versions.toml`：9.2.1／2.4.0／2.3.10 |
| 實際 Gradle wrapper | 根 `gradle/wrapper/gradle-wrapper.properties`：`gradle-9.6.1-all.zip`；不是版本目錄中名為 `gradle` 的 9.2.1，那是另一個 AGP dependency 別名 |
| Release | 開啟 R8 縮減與資源縮減，使用 `signingConfigs.release` |
| APK 檔名 | `ImmichTV-<versionName>.apk`，目前預期為 `ImmichTV-6.2.0.apk`；尚未產出 |
| Gradle heap | `gradle.properties`：`-Xmx2048m` |

官方 AGP 9.2 相容性表列出 JDK 17、最低 Gradle 9.4.1、Build Tools 36.0.0，支援最高 API 37；目前 wrapper 版本高於該最低值，但整套依賴及外掛能否在本機解析仍須後續實際驗證。[Android 官方 AGP 9.2 文件](https://developer.android.com/build/releases/agp-9-2-0-release-notes)

### 6.2 最小更名及共存修改

1. `app_name` 改為 **Immich TV Plus**，DreamService label 改用同一資源；同步更新 Gradle 專案名及 APK 輸出檔名，例如 `ImmichTVPlus-<versionName>-release.apk`。
2. Application ID 改為 **`com.jerry.immichtv.plus`**。保留 Kotlin package、app namespace 與 library namespace，避免為共存需求而全面搬移原始碼。Application ID 與 namespace 可以分開設定。[Android 官方套件識別說明](https://developer.android.com/build/configure-app-module)
3. Manifest 的自訂 provider 已使用 `${applicationId}.appcontextinitializer`，可自動獨立；後續檢查 merged manifest 的所有 provider authorities 及元件類別。
4. `app/src/main/res/xml/screensaver.xml` 目前為 `nl.giejay.android.tv.immich/.MainActivity`。保留原始碼 package 時，應改為完整元件 `com.jerry.immichtv.plus/nl.giejay.android.tv.immich.MainActivity`。XML 資源不應假定會像 Manifest 一樣自動展開 `${applicationId}`。
5. 更新 `app/src/main/res/layout/screensaver_adb.xml` 及 README 的服務元件說明。新的完整服務元件應為 `com.jerry.immichtv.plus/nl.giejay.android.tv.immich.screensaver.ScreenSaverService`。只換左側套件、仍用 `/.screensaver.ScreenSaverService` 會錯指至不存在的新 package 類別。
6. Manifest 與 `APP/homescreenchannels/ChannelDeepLinks.kt` 都使用 `immichtv` scheme；首頁頻道存的是 URI。建議 Plus 改用專屬 `immichtvplus` scheme，兩處同步，避免與原版共存時開錯 App。
7. 新 App 有獨立資料目錄、偏好及登入狀態；預期須重新輸入 Immich 伺服器與 API key，不自動搬移原版資料。原版與 Plus 可同時安裝；系統當下啟用哪個 Screensaver 仍由 TV 系統設定決定。

### 6.3 Firebase、額外資源與 CI

- `app/build.gradle` 套用 Google Services／Crashlytics 外掛；本機沒有 `app/google-services.json`。`app/google-services.example` 的 client package 仍是原 ID，未來需配合 Plus 調整；若使用正式 Firebase，應建立自己的 Android client，不沿用上游服務。
- Google Services 外掛會比對 JSON client 的 package name，找不到相符項目就失敗，單改 Application ID 不足以建置。[Firebase 官方說明](https://firebase.google.com/docs/android/google-services-plugin-and-file)
- `app/src/main/res/values/strings_other.xml` 缺少；README 要求由 `app/strings_other.xml.example` 準備。原始碼引用 `host_name`、`api_key`、`authentication_url`。後續使用安全佔位值，不把私人伺服器 API key 編入 APK。
- `APP/ImmichApplication.kt` 啟動時直接呼叫 FirebaseCrashlytics，因此「使用空白範例就能正常啟動」仍須 Debug／Release 驗證；不能只為略過 JSON 錯誤就移除外掛。
- `.github/workflows/android.yml` 使用 JDK 17；有簽署 secret 時只做 Release，沒有時做 Debug 與測試，故成功 Release CI 不等於已跑單元測試。
- `.github/workflows/release.yml` 會產出 APK／AAB，Play 上傳仍指定原套件，且發佈選項預設開啟。這不是本次私人 APK 的必要流程；後續若調整 CI，先改為 Plus 的建置產物驗證，不直接沿用上游商店發佈。
- 根 build 檔把 Spotless 設成 `enforceCheck false`；`check` 通過不代表格式檢查已嚴格執行。

### 6.4 獨立 Release 金鑰

現有簽署設定已支援以下環境變數，能沿用，不需新建整套簽署機制：

| 環境變數 | 目前未設定時的 fallback |
| --- | --- |
| `RELEASE_KEYSTORE_PATH` | `app/keystore`（相對 app module 的 `file()`） |
| `RELEASE_KEYSTORE_PASSWORD` | `dummy` |
| `RELEASE_KEY_ALIAS` | `immich` |
| `RELEASE_KEY_PASSWORD` | `dummy` |

本機四個變數都未設定，`app/keystore` 也不存在；未發現專案內的 `.jks`／`.keystore`。這些 fallback 不等於已有可用金鑰，也不會自動安全產生金鑰。

後續產生 Plus 專用長期 Release 金鑰，放在儲存庫之外，透過既有環境變數指定，並在簽署資訊缺漏時明確失敗。保存金鑰、alias、密碼及獨立備份；之後更新安裝需使用同一把金鑰。`.gitignore` 現在只有 `keystore` 規則，並未涵蓋任意名稱的 JKS，後續應補齊相關忽略規則，不能將金鑰或密碼提交。

交付時須以 `apksigner verify --verbose --print-certs` 驗證憑證及 APK 簽章，另記錄 SHA-256、Application ID、版本與 min SDK。確認使用的是 Plus 的 Release 憑證，並在電視驗收首次安裝及同金鑰更新；目前未產生任何金鑰或 APK。

## 7. Windows 環境檢查

| 項目 | 唯讀檢查結果 |
| --- | --- |
| Git | 可執行，已完成狀態及樹結構檢查 |
| `java`／`javac`／`keytool` | `Get-Command` 均找不到，無法取得版本 |
| JDK 設定 | Process／User／Machine 的 `JAVA_HOME` 皆空；常見 Java、Adoptium、Corretto、Zulu、`.jdks`、Gradle JDK 目錄未找到；解除安裝登錄清單未發現相符 JDK |
| `adb`／`sdkmanager` | `Get-Command` 均找不到 |
| SDK 設定 | 三個環境範圍的 `ANDROID_HOME`／`ANDROID_SDK_ROOT` 皆空；沒有根目錄 `local.properties` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`、`C:\Android\Sdk`、`D:\Android\Sdk` 等已查常用位置不存在 |
| Android Studio | Program Files、使用者 Programs、JetBrains Toolbox 常用位置未找到；Android Studio 本身不是命令列建置的必要條件 |
| Gradle | PATH 無獨立 `gradle`；專案 wrapper 檔案存在；使用者預設 `.gradle\wrapper\dists` 快取未找到，不需要另裝全域 Gradle |
| 設定與簽署 | 缺 `google-services.json`、`strings_other.xml`、Plus 專用 keystore／簽署環境變數 |
| 電視資訊 | 未連線；實際 Android API、CPU ABI、可用記憶體、ADB 與 Screensaver 設定能力均待確認 |

結論是「目前工作階段找不到可用 JDK／SDK，常見位置也未發現」，並非已掃描所有磁碟證明完全未安裝。

後續環境階段建議準備 JDK 17、Android SDK Command-line Tools、`platforms;android-37`、與 AGP 配套的 Build Tools（官方表列 36.0.0）、Platform Tools（ADB）；再設定 SDK 路徑、確認授權及 Gradle 依賴下載。是否需要其他版本以當時實際錯誤及依賴需求為準，不預先升降整套工具版本。目前未安裝、下載或執行任何建置。

## 8. 建議最小實作方案

### 8.1 使用者行為

- 在相簿設定加入「Shuffle 隨機播放」開關，按相簿 ID 保存，預設關閉。原有新到舊／舊到新排序繼續控制網格及普通播放。
- 在 Screensaver 設定另加 Shuffle 開關，預設關閉以保留舊行為。來源選擇與播放順序是不同設定；舊 `RANDOM` 是來源類型，不能充當新開關。
- 開關關閉時保留目前各來源的行為，包含既有 Screensaver 的隨機抽樣；不把它誤稱為有序模式。開啟時，ALBUMS／RANDOM／RECENT／季節來源皆建立相應的有限素材清單，並保證對該清單逐輪洗牌。
- 相簿仍可從點擊照片開始；首次啟動 Shuffle 時把目前照片所在頁納入首輪已選起點，其餘頁面隨機排列，避免起始照片立即重播。暫停繼續沿用剩餘清單；結束重開則建立新工作階段。

### 8.2 資料取得與輪次演算法

1. 新增完整素材載入 helper，從第 1 頁依 `nextPage` 取得有限清單，成功後才前進；全程在背景執行，支援取消，限定併發。先完成內容類型、排除標籤、排除相簿及素材 ID 去重。
2. 相簿 Shuffle 使用獨立載入工作，避免與網格共用可變 `currentPage`；不打亂網格 `assets`。Screensaver Shuffle 略過「首批抽樣再補完整清單」，直接使用完整來源。
3. Screensaver 的近期／季節性清單沿用日期範圍，但改以 `random = false` 的 metadata 分頁；全域隨機來源同樣先列舉可見素材。日期範圍在工作階段開始時固定，季節性各區間合併後依 ID 去重。不得改壞首頁原有的 recent／random API 行為。
4. 在 `MediaSliderConfiguration` 增加預設關閉的 Shuffle 旗標，新增可獨立測試的播放輪次類別，由相簿及 Screensaver 共用。保存完整母清單與本輪排列／游標，不靠重複隨機抽選找下一張。
5. 直式照片先使用保序的相鄰配對規則建立頁面，配對過程採線性走訪，再對頁面順序洗牌；每輪按展開後的 ID 保證各素材一次。為降低變更，第一版不每輪重算人物配對；配對於同工作階段固定。
6. 播到本輪末端後，由共用播放器切換新排列，而不是單純回索引 0。N > 1 時可交換新首項，避免等於上輪末項；這是額外改善，不要求每輪排列必定不同，尤其 N = 1／2 時不能這樣斷言。
7. 輪次替換集中處理 timer、ExoPlayer、pager、回報選取素材及 plugin 通知；不能每輪重新呼叫整個 `loadMediaSliderView()` 累積 listeners／plugins。完整清單模式停用既有無限 `loadMore`。
8. 正向／反向自動播放都要走同一輪次管理入口；手動上一張可回看，手動下一張視為消耗當前位置。Shuffle 關閉時原有方向及循環功能完全沿用。
9. 無素材、部分載入失敗、取消及素材失效分別處理。完整載入失敗時給予重試／退出，不把半份清單包裝成完整相簿；不在每輪發出全量網路請求。

### 8.3 預計修改範圍（尚未執行）

| 類別 | 最小涉及範圍 |
| --- | --- |
| 設定與文字 | `APP/shared/prefs/Preferences.kt`、字串資源；相簿／Screensaver 設定由既有 PrefScreen 擴充 |
| 相簿入口 | `APP/assets/GenericAssetFragment.kt`、必要時 `APP/album/AlbumDetailsFragment.kt`；加入預設普通模式的擴充點，僅相簿啟用，避免改到 Timeline／人物等入口 |
| 完整來源 | `APP/api/ApiClient.kt` 的小型查詢擴充，加一個可測試的完整清單 loader；既有 API service 可優先沿用 |
| Screensaver | `APP/screensaver/ScreenSaverAssetLoader.kt`；服務與預覽沿用同一建構入口 |
| 播放器 | `SLIDER/config/MediaSliderConfiguration.kt`、新輪次類別、`SLIDER/view/MediaSliderController.kt`、`SLIDER/view/MediaSliderView.kt`；Adapter 僅在實際需要穩定更新時調整 |
| 素材轉換 | `APP/shared/util/AssetUtil.kt` 的直式配對走訪，必要的去重／大清單效率修正 |
| 品牌與共存 | `app/build.gradle`、根 `settings.gradle`、`strings.xml`、Manifest、`res/xml/screensaver.xml`、ADB layout、`ChannelDeepLinks.kt`、README |
| 建置資源／簽署 | Plus 對應 Google Services 範例／本機設定、額外字串、`.gitignore`；必要時縮小調整 CI。私鑰在 repo 外 |
| 驗證 | 新輪次與完整分頁測試，擴充 Screensaver loader／篩選回歸測試；保留普通播放驗證 |

不需要新增資料庫、引入新的隨機套件、全面改 Kotlin package、更新所有依賴或重寫影片播放器。超大媒體庫若需要磁碟索引，另開後續階段評估。

## 9. 分階段驗收

以下均為後續工作，這次未執行命令或測試。

| 階段 | 工作 | 通過條件 |
| --- | --- | --- |
| 第一階段（本次） | 唯讀分析與本文件 | Git 最終只有 `docs/implementation-plan.md` 新增；無程式碼／環境改動，未編譯 |
| 第二階段：建立基線 | 補齊 JDK／SDK、本機必要資源，驗證現有版本 | wrapper 可啟動、Debug 可建置；記錄既有測試結果與原有日期排序、相簿播放、預覽的基線，先區分既有問題 |
| 第三階段：Shuffle 核心 | 有限來源、ID 去重、輪次管理與播放器接線 | 相簿及 Screensaver 連播至少 3 輪；每輪 ID 集合等於母清單，每個 ID 恰好一次；新輪重建排列；暫停繼續不重洗 |
| 第四階段：設定與回歸 | 開關、普通排序、多來源／影片 | 關閉 Shuffle 後新到舊、舊到新、前後切換、反向播放及 lazy loading 保持；Screensaver 預覽與真正服務一致 |
| 第五階段：品牌與 Release | 更名、Application ID、獨立簽署及 Release 縮減 | APK 顯示 Immich TV Plus、套件為 `com.jerry.immichtv.plus`、簽章驗證通過；Release 與 Debug 均能啟動，無 Firebase／R8 問題 |
| 第六階段：TCL C765 | 真機共存、系統 Screensaver、效能與更新 | 與原版同時存在且入口互不混淆；Plus 可從設定啟用 Screensaver、待機自動進入／喚醒退出正常；長播不黑畫面、不失控累積記憶體；同金鑰新版可覆蓋更新 Plus |

### 必要測試資料與判準

- **邊界數量：** 0、1、2、49、50、51、100、101、1,001 筆，以及 10,000 筆壓力資料。涵蓋整頁邊界、最後短頁及來源正常結束；不能只測小於一頁的相簿。
- **去重／篩選：** 多相簿共用照片、重複 ID 但中繼資料不同、整頁全部被排除、最後全部排除、共享相簿、封存素材、排除標籤與排除相簿。
- **失敗：** 第一頁失敗、中間頁失敗及重試、網路中斷、離開畫面取消。不得漏頁、重複 append 或誤報載入完畢。
- **照片／影片：** 純照片、純影片、混合、直式合併開／關、最後一項為影片、長影片、影片錯誤、seek、聲音開／關；每個前進事件只消耗一個位置。
- **操作：** 從相簿中間照片開始、播放／暫停／繼續、手動回看、正向與反向、退出重進、Screensaver 多次進出、預覽切回 App。
- **隨機測試：** 注入可重現的 random／seed，測集合與計數等不變條件。不可用「每次順序都不同」作為測試，避免合理的相同排列造成偶發失敗。
- **大清單：** 記錄首次準備時間、UI 是否能操作及取消、輪末延遲、記憶體峰值和連續多輪的變化；不預先下載完整照片／影片。效能門檻以 TCL 實測基線訂定，不能以桌面電腦結果代替。
- **APK／真機：** 檢查實機 API ≥ 24、CPU ABI 與 APK 內原生解碼 library 對應，記錄實際韌體。min SDK 合格不等於所有影片格式都能解碼，需測使用者實際的 JPEG／HEIC／影片素材及 TV Screensaver 能力。

現有 `ScreenSaverAssetLoaderTest`、`ScreenSaverAssetFilterTest`、`FilteredAssetLoaderTest` 等有可擴充的測試基礎；`AlbumDetailsFragmentTest.kt` 全檔已被註解，不能計為有效相簿測試。後續先新增純輪次／分頁邏輯測試，再跑相關回歸與 Release 實測。本階段未執行任何測試，因此沒有「測試通過」的宣稱。

## 10. 建議下一步與待補資訊

建議下一階段先建立可重現的建置基線，再實作共享 Shuffle 輪次；最後完成品牌、簽署及 TCL 驗收。不要同時把工具鏈升級、全專案 package 搬移及 Shuffle 混在一起。

後續驗收需要補足：Immich 伺服器版本（此 Fork README 宣告 v3.0.0 以上）、最大相簿／全域媒體庫大小、是否大量使用共享相簿與影片，以及 TCL C765 實際韌體／API／ABI。這些資訊不影響本次完成分析，但會決定大清單效能與相容性驗收範圍。

目前可交付成果只有本分析文件；環境建置、原始碼修改、獨立金鑰產生與 Release APK 仍屬後續階段。
