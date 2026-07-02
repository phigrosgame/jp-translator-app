# 日語即時翻譯字幕 APP

一個可以直接擷取手機系統音訊（其他 APP 的聲音），即時翻譯成中文字幕，並以懸浮視窗顯示的 Android 應用。

## 功能特色

- 🎤 **系統音訊擷取**：直接抓取其他 APP 的聲音（直播、遊戲、影片等），不用麥克風
- 🇯🇵 **日語語音辨識**：Google MLKit 離線日語語音識別
- 🌐 **離線翻譯**：Google MLKit 離線日譯中，不需要任何 API Key
- 📺 **懸浮字幕**：字幕懸浮在所有 APP 上方，可拖動位置
- 📜 **翻譯歷史**：保存翻譯記錄，可回看
- ⚡ **低延遲**：每 2.5 秒更新一次，延遲穩定

## 系統需求

- Android 10 (API 29) 以上
- 需要授予：麥克風權限、懸浮視窗權限、媒體投影權限
- 首次使用需要聯網下載語音和翻譯模型（約 50MB）

## 編譯方式

### 使用 Android Studio（推薦）

1. 下載並安裝 [Android Studio](https://developer.android.com/studio)
2. 打開 Android Studio，選擇 `Open an existing project`
3. 選擇這個 `JpTranslator` 資料夾
4. 等待 Gradle 同步完成（第一次會下載依賴，需要幾分鐘）
5. 連接手機或啟動模擬器
6. 點擊選單 `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`
7. 編譯完成後，點擊通知欄的 `locate` 可以找到 APK 檔案
   - 路徑：`app/build/outputs/apk/debug/app-debug.apk`

### 使用命令列

```bash
# 進入專案目錄
cd JpTranslator

# Linux/Mac
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug

# APK 輸出位置
# app/build/outputs/apk/debug/app-debug.apk
```

## 使用方式

1. 安裝 APK 到手機
2. 打開 APP
3. 點擊「開啟懸浮窗權限」，跳轉到設定頁面手動開啟
4. 點擊「開始翻譯」
5. 授予麥克風權限
6. 授予媒體投影權限（點擊「立即開始」）
7. 打開你要看的直播/影片/遊戲
8. 螢幕上方會出現懸浮字幕，自動翻譯日語
9. 可以拖動字幕調整位置
10. 點擊字幕上的 ✕ 可以關閉

## 關於模型下載

APP 首次啟動時會自動下載：
- 日語語音識別模型（約 30MB）
- 日語→中文翻譯模型（約 25MB）

下載完成後即可完全離線使用，不需要聯網。

## 注意事項

1. **系統音訊擷取** 需要 Android 10 以上，且只有部分 APP 支援（大部分直播、影片 APP 都可以）
2. **有 DRM 保護的 APP**（如 Netflix、Disney+）可能擷取不到聲音
3. **翻譯品質**：離線翻譯品質略遜於線上 API，但足夠日常使用
4. **語音辨識準確度** 受聲音清晰度影響，建議音量適中

## 專案結構

```
JpTranslator/
├── app/
│   ├── build.gradle              # APP 構建配置
│   ├── proguard-rules.pro        # 混淆規則
│   └── src/main/
│       ├── AndroidManifest.xml   # 權限和組件聲明
│       ├── java/com/jptranslator/app/
│       │   ├── MainActivity.kt           # 主畫面
│       │   ├── FloatingWindowService.kt  # 懸浮視窗服務
│       │   ├── AudioCaptureService.kt    # 音訊擷取服務
│       │   ├── TranslateHelper.kt        # 翻譯工具（MLKit 離線）
│       │   └── VoiceRecogHelper.kt       # 語音識別工具（MLKit）
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml     # 主畫面佈局
│           │   └── floating_window.xml   # 懸浮視窗佈局
│           └── values/
│               ├── strings.xml
│               ├── themes.xml
│               └── colors.xml
├── build.gradle                  # 專案構建配置
├── settings.gradle               # 專案設置
├── gradle.properties             # Gradle 屬性
├── gradle/wrapper/
│   └── gradle-wrapper.properties
└── README.md                     # 說明文件
```

## 常見問題

**Q: 為什麼擷取不到聲音？**
A: 確保手機是 Android 10 以上，並且授予了媒體投影權限。部分 APP 有 DRM 保護，不允許擷取音訊。

**Q: 懸浮視窗不顯示？**
A: 請確認有開啟「懸浮視窗」權限，在手機設定 → 應用程式 → 權限中查看。

**Q: 翻譯沒有反應？**
A: 首次使用需要下載語音和翻譯模型，請確保有聯網，等待幾分鐘讓模型下載完成。

**Q: 可以翻其他語言嗎？**
A: 可以，修改 `VoiceRecogHelper.kt` 中的語言代碼和 `TranslateHelper.kt` 中的翻譯語言對即可。

**Q: 延遲太高怎麼辦？**
A: 可以調整 `AudioCaptureService.kt` 中的 `processIntervalMs` 參數，數值越小延遲越低，但 CPU 佔用越高。
