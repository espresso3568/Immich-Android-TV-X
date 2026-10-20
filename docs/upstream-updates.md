# 跟進原作更新與提出隨機播放功能

更新日期：2026-10-20。

## 本次 Fork 與原始碼更新

GitHub 帳號為 `espresso3568`。原先電腦上的資料夾是從 `xyzhong987` 專案 clone，並不是此帳號名下的 Fork。本次已建立 [espresso3568/Immich-Android-TV-X](https://github.com/espresso3568/Immich-Android-TV-X)，將 Plus 的 main 原始碼推送到此 Fork。

遠端角色如下：

| 名稱 | 用途 | 網址 |
| --- | --- | --- |
| origin | 自己的 Fork，存放 Plus main | https://github.com/espresso3568/Immich-Android-TV-X |
| upstream | 原作者，未來比較更新用 | https://github.com/giejay/Immich-Android-TV |
| fork-base | 保留原本 clone 的來源 | https://github.com/xyzhong987/Immich-Android-TV-X |

本次核對 `upstream/main` 與原始基準同為 `037cb7ce0d94f36113afd0bec19b84f23ec705ed`，目前沒有額外上游變更要合併。此狀態是當日快照，之後以 fetch 結果為準。

依使用者選擇，APK 只在自己的設備使用，不上傳 GitHub。已移除自動 Release 發佈工作流程；Android CI 只建置 Debug 並執行單元測試，不讀取簽署秘密、不上傳 APK、不發佈 Release。私人金鑰與伺服器設定仍保留在本機，`artifacts/` 不在 Git 追蹤範圍。

## 下次原作有更新時

你不用自己重寫隨機功能，只要交代：

> 請同步原作者更新，保留 Immich TV Plus 的完整相簿洗牌、每輪不重複、每次啟動重新洗牌、名稱及 Application ID，跑完測試，再用原本金鑰封裝給我自用。

工程處理方式是把上游的新改動合併進自己的版本。不能保證永遠零衝突或一鍵完成；上游若改了相簿 API、播放器或設定，需要檢查並調整相交處。

供維護者使用的步驟：

1. 確認工作目錄乾淨、記錄目前 main commit，fetch `origin` 與 `upstream/main`。
2. 從 Plus main 建立獨立同步分支，例如 `sync/upstream-20261101`；先比較 `main...upstream/main`，有更新才 merge，不直接用上游覆寫 main。
3. 解衝突時保留 Plus 的 `com.jerry.immichtv.plus`、名稱、自有 versionCode、預設停用 Firebase 及本機簽署方式。保留原有排序選項和不保存播放進度的需求。
4. 重點檢查 `AlbumAssetLoader`、`ScreenSaverAssetLoader`、`GenericAssetFragment`、播放器 `ShuffleRound`／`MediaSliderController`／`MediaSliderView`，以及認證與 Glide Key provider。
5. 確認上游 JDK、SDK、Gradle 要求；跑既有 174 項及上游新增測試。實測跨相簿去重、超過 1000 筆分頁、輪末重洗、退出重啟、影片切換與重新登入後圖片認證。
6. 功能驗證後提高 Plus 的 versionCode，再用既有專用金鑰建置；先模擬器更新安裝，再到 TCL 驗收，最後合併並推送自己的 main。

GitHub 官方的 [Fork 同步說明](https://docs.github.com/en/pull-requests/how-tos/work-with-forks/syncing-a-fork) 採用 fetch／merge。此專案已有客製修改，不應用強制同步／重設為上游的方式丟棄 Plus 修改。也不要直接安裝原版 APK，期待它自動繼承 Plus 的功能及資料；原版是不同 Application ID。

## 是否建議向原作提出功能

建議提出可獨立審查的功能 PR，長期可減少自己反覆維護的成本。原作 README 明確歡迎具體 bug report 與 PR；採納與時程仍由維護者決定。

已查到原作 [issue #259](https://github.com/giejay/Immich-Android-TV/issues/259) 討論過指定相簿的隨機排序回歸，該議題已關閉，因此應先理解原作現有處理，再說明本次加強的是完整輪次，而非僅要求加入一行 `shuffled()`。

建議依序整理：

1. Screensaver 的完整多相簿聯集、素材 ID 去重、每輪不重複、輪末重洗、每次啟動新順序及分頁測試。
2. 一般相簿的可選 Shuffle，保留原有日期排序與預設行為。
3. 若涉及 MediaSliderView 的通用輪次 API，依原作播放器依賴方式協調對應播放器 PR，避免直接把本專案的內附播放器複製方式塞入原作。

對原作的 PR 應從最新 upstream 建立乾淨分支，只帶相關功能與測試。Plus 品牌、Application ID、自用簽署、Windows 模擬器與不相關認證修正各自保留，不混入隨機功能 PR。本次主功能 commit 包含多種客製修改，不能直接整顆當成上游的隨機功能 PR。

最初僅整理建議及更新來源。後續依使用者明確授權，已於 2026-10-20 提交 [原作 PR #283](https://github.com/giejay/Immich-Android-TV/pull/283)：**Shuffle complete album playlists and reshuffle each playback round**。

PR 使用 `feature/complete-album-shuffle` 分支，直接從原作 `037cb7c` 建立，只有一個功能提交 `a7c9b27`、19 個功能及測試檔案，不包含 Plus 主分支的提交歷史。原作名稱、Application ID、建置及登入方式保留；沒有帶入私人版設定、API Key、伺服器位置、簽署資料、APK 或個人操作文件。已核對本機及 GitHub 實際公開的差異，網址字面值均可追溯到原作既有測試範例；提交作者使用 GitHub noreply 郵件。

該獨立分支的 App 151 項及播放器 7 項，共 158 項測試全數通過，Debug 建置成功。私人版的額外認證修正及測試未混入此 PR，因此數量與 Plus 主分支的 174 項不同。PR 說明另有清楚區分：先前的 Android TV 模擬器實測來自相同隨機實作的下游版本，這次乾淨原作分支則另行完成自動測試及建置。

提交當下，PR 為正式開啟狀態，GitHub Android CI 顯示 `action_required`，等待原作維護者處理；後續審查及檢查狀態以 PR 頁面為準。
