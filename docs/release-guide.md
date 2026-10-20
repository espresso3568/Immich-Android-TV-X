# Immich TV Plus 安裝與 Release 說明

## 版本與安裝識別

- 名稱：Immich TV Plus。
- Application ID：`com.jerry.immichtv.plus`，可與原版共存，登入資料與設定彼此獨立。
- 目前版本：`1.0.2`，versionCode `3`；基於此 Fork 的上游 `6.2.0 (148)`。沿用 1.0.0 的專用簽署金鑰，可直接覆蓋更新。
- 最低 Android API：24。TV 的實際韌體、影片解碼能力與系統 Screensaver 行為仍應以實機驗收為準。
- 私人版本預設停用 Firebase；APK 不包含私人伺服器、API Key 或上游 Firebase 設定。首次啟動使用 API Key 登入。

## 在 TCL C765 安裝與使用

也可先在 Windows 雙擊專案根目錄的 `Start-TV-Test.cmd`，使用已備妥的電視模擬器；詳見 [PC 電視測試說明](pc-tv-testing.md)。

1. 將 `artifacts/ImmichTVPlus-1.0.2-release.apk` 複製到 USB 隨身碟，透過電視的檔案管理員開啟。依電視提示允許該檔案管理員安裝未知來源 App。
2. 開啟 **Immich TV Plus**，輸入自己的 Immich 伺服器網址與 API Key。這是獨立 App，需要重新登入；原版資料不會被覆蓋。
3. 設定 Screensaver 時，將類型設為「相簿」，選取需要的多本相簿。可先使用 App 內的「立即開始」預覽。
4. 所選相簿會完整載入、依素材 ID 去重並混合洗牌。每次啟動重新開始；每輪播放一次全部素材，播完再洗牌，不保存播放進度。不同次啟動仍可能偶然看到相同照片。
5. 在電視系統的螢幕保護程式設定選取 **Immich TV Plus**。預覽正常不代表系統已選用 Plus，需另行確認。
6. 一般相簿若要 Shuffle，進入相簿後將焦點移到最右邊的照片，再按方向鍵「右」開啟該相簿設定。開啟「隨機播放」（英文介面為 Shuffle playback），返回並開啟照片，按方向鍵「下」顯示播放控制列，再選正向幻燈片播放。日期排序仍控制網格瀏覽，關閉隨機播放可回到原有播放順序。

直式合併開啟時，兩張直式照片可能同時出現在一頁；輪次以素材 ID 計算，不會因為頁數較少而漏播。大相簿首次準備需要較久，期間會顯示載入指示。若來源載入失敗，不會把不完整資料誤當整本相簿。

若使用 ADB，可對已完成配對／連線的電視執行：

```powershell
adb install -r artifacts/ImmichTVPlus-1.0.2-release.apk
```

系統未顯示 Screensaver 選項時，Plus 服務的完整元件名稱為：

```text
com.jerry.immichtv.plus/nl.giejay.android.tv.immich.screensaver.ScreenSaverService
```

必要時使用 `adb shell settings put secure screensaver_components <上方完整元件名稱>` 選取服務；這會改變系統當下選用的 Screensaver。

## 建置環境與金鑰保管

本機工具位置：`%LOCALAPPDATA%\ImmichTVPlus`。JDK 路徑記錄在 `jdk-path.txt`，Android SDK 位於 `android-sdk`，不更動全機 Java 環境。

獨立 Release 金鑰位於 `%LOCALAPPDATA%\ImmichTVPlus\signing`：

- `immich-tv-plus-release.jks`：專用 RSA 3072 金鑰。
- `release-signing.json`：建置所需的 alias、密碼及金鑰路徑。這是私人檔案，不應分享或放進 Git。

後續每個更新版都必須使用這把金鑰，並提高 `app/build.gradle` 的 versionCode。請將金鑰與簽署設定備份到自己的安全位置；只備份 APK 無法恢復簽署能力。轉移電腦後需更新 JSON 中的 `keyStorePath`，並重新設定工具路徑。

已另外建立私人備份：`C:\Users\jerry\ImmichTVPlus-PrivateBackup\immich-tv-plus-signing-backup.zip`，包含金鑰、簽署設定及還原說明。這份 ZIP 含簽署密碼，請只自行保管；分享 App 時只提供 APK。建議再保存一份到自己的離線備份媒體。

同一台已設定好的 Windows 電腦可執行：

```powershell
.\scripts\build-release.ps1
```

腳本會執行 app 與播放器單元測試、建立經 R8 縮減的 Release、驗證簽章、複製 APK 並產生 SHA-256。簽署密碼只透過程序環境傳給建置工具，完成後還原環境變數。

`artifacts/SHA256SUMS.txt` 可用來確認複製前後 APK 是否相同：

```powershell
Get-FileHash .\artifacts\ImmichTVPlus-1.0.2-release.apk -Algorithm SHA256
```

## 驗證紀錄

1.0.2 正式 Release 完成 174 項單元測試。獨立 Android TV 測試重現了 1.0.1 更換 Key 後首頁仍 401；新版針對主畫面及圖片載入器分別更新認證，完整回歸結果見 [交付驗證報告](../artifacts/verification-report.md)。1.0.1 另已驗證錯誤 Key 擋下。1.0.0 已驗證一般相簿幻燈片及真正系統 Screensaver 的多相簿三輪播放；這次未變更播放程式。詳見 [實作紀錄](implementation-plan.md)。尚未接上實體 TCL C765，影片解碼、大相簿效能與長時間穩定性需以實機驗收。

## 登入與 401 修正

舊版提交登入只檢查欄位格式，進入首頁不代表伺服器接受 Key。1.0.1 改為先呼叫 Immich 的 `POST /api/auth/validateToken`，確認 HTTP 200 且 `authStatus=true` 才保存並進入首頁；失敗時留在登入頁顯示原因。請填主網址，App 會補上 `/api/`。

另外修正認證攔截器在網路例外時可能重送未帶 Key 的請求，以及登入欄位離開畫面後可能讀成字串 `null` 的問題。

1.0.2 進一步修正重新登入後首頁仍保留舊 Key 的問題。原有 Timeline 資料模型屬於 Activity，導航回首頁時仍使用初次建立的連線；現在驗證及保存成功後會建立新的主畫面工作階段，釋放舊模型。使用者伺服器 v3.2.2 的 Key 已能成功驗證及讀取相簿，重啟 App 後的真實相簿載入也已成功。

圖片載入器也改為每次請求取得目前的 Key，處理相簿清單正常但縮圖仍沿用舊 Key 的情況；舊伺服器的請求不會取得新伺服器的 Key。

診斷只記錄 HTTP 狀態、Header 是否帶上／值是否一致／數量、是否重新導向及儲存一致性，不記錄 Key 內容。測試 Key 請在本機 App 填寫，不必傳到對話中。
