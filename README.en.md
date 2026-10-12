# Shizuku Sam (MaxHz Edition)

<p align="center">
  <b>Samsung One UI Performance Scheduler, Refresh Rate Controller & Battery Health Manager built on Shizuku</b><br>
  <sub>Miuix UI Components · Liquid Glass Floating Dock · Non-Root (ADB) & Root Dual-Mode Engine</sub>
</p>

<p align="center">
  <a href="./README.md">简体中文</a> | <a href="./README.en.md">English</a> | <a href="https://github.com/WEIHEWU/shizuku-sam/releases/latest"><b>⬇️ Download Latest Release (v13.8.4)</b></a>
</p>

---

## 📌 Overview

**Shizuku Sam** integrates the **Shizuku privileged service** with a purpose-built **Samsung Galaxy (One UI) system scheduler & hardware enhancer, background & deep sleep controller, and per-app refresh rate rule manager** in a single cohesive application.

The entire app (Level 1, Level 2, Level 3 screens, and all dialogs) is unified under the **Miuix (`top.yukonga.miuix.kmp`) UI system**, a **3-tab Liquid Glass floating dock (`Shizuku` · `Hardware` · `App Rules`)**, and **on-demand subtitle toggles (clean single-line titles by default + long-press any row/card to toggle its explanation in place + a `显示说明 / 隐藏说明` pill button on every section/page header)**. Every section, card title, switch, and secondary screen listed below corresponds 1:1 with the actual `v13.8.4` app UI.

---

## ✨ Complete UI & Secondary Screen Reference

### 1. Tab 1: `Shizuku` (Wireless Pairing & Privileged Service Channel)

1. **Hero Status Card (`Shizuku Sam`)**
   - **When Stopped (`Pending`)**: Shows wireless debugging environment or local loopback port detection status, with a primary **`Start via Wireless Debugging`** button.
   - **When Running (`Root / ADB · v13.x`)**: Displays three status tiles (**`Service Version`**, **`Execution Mode` (`ROOT Mode` / `ADB Mode`)**, and **`Authorized Apps`**) and three action buttons:
     - **`Manage Authorized Apps`**: Manage third-party apps granted Shizuku permission.
     - **`Terminal (rish)`**: Export `rish` scripts to run privileged shell commands in terminal apps.
     - **`Stop Service`**: Opens a top-level Miuix `WindowDialog` confirmation prompt to stop the Shizuku service.
2. **`Wireless Debugging Pairing` (Shown when stopped)**
   - **`Hands-Free Auto Pairing`**: Enable the Accessibility Service to automatically detect the wireless debugging pairing dialog and enter the 6-digit pairing code.
   - **`Wireless Pairing Wizard`**: View step-by-step pairing instructions or manually enter the 6-digit code.
   - **`Developer Options`**: Jump directly to System Settings -> Developer Options -> Wireless Debugging.
3. **`More Launch Methods` (Shown when stopped)**
   - **`Start via Root`**: One-tap direct startup for rooted devices without PC or Wi-Fi.
   - **`Start via PC`**: Connect your phone to a PC via USB and run the starter script in terminal.
4. **`Management & Backup Channels` (Shown when running)**
   - **`Reconnect Debugging`** (ADB mode only): Rescan local loopback wireless debugging ports and refresh the ADB token connection.
   - **`Restart via Root`**: Switch the running service to Root mode.
   - **`PC ADB Launch Command`**: View and copy the PC `adb` starter command.

---

### 2. Tab 2: `Hardware` (System Scheduling & Hardware Enhancements)

#### 1. `Performance Mode` (3-Mode Segmented Switch)
- **`System Default` (`Balanced`)**: Restores stock Samsung scheduling and adaptive refresh rate, clearing all custom scripts and limits.
- **`Adaptive Saver` (`Battery Priority`)**: Enables light system power saving while unlocking **120Hz adaptive high refresh rate + Quick Doze**.
- **`Game Max` (`Full Performance`)**: Locks CPU/GPU full performance scheduling and fast touch response with unthrottled refresh rate.

#### 2. `Hardware & Experience Enhancements`
- **`Bypass Charging (Pass-Through)`**: Powers the motherboard directly while gaming on a charger, keeping the phone cool and protecting battery lifespan (requires a PPS charger and battery ≥ 20%).
- **`Battery Charging Protection (80% / 75%)`**:
  - Toggle the right-hand switch to start/stop charging protection directly;
  - Tap the row to open the Miuix `WindowDialog` with **`80/75 Daily`**, **`85/80 Balanced`**, and **`90/85 Travel`** presets, custom **Stop Charging (%)** and **Resume Charging (%)** inputs, **`Restore Default`**, and **`Apply & Enable`**.
- **`Game Auto High-Hz Boost`** (Tap to open the **`Game Mode Auto Switch`** secondary screen):
  - **`Game Mode Auto Switch`**: Master switch **`Enable Game Auto Switch`**; detects foreground games to apply target performance and restores your pre-game daily snapshot upon exit.
  - **`Default In-Game Target Mode`**: Choose **`System Default`** / **`Adaptive Saver High-Hz`** / **`Game Performance Mode`** as the global fallback for games without a custom override.
  - **`Background Hold Duration`**: Choose **`30s`** / **`60s (Recommended)`** / **`120s`**.
  - **`Game List` (`v13.8.2` Per-Game Target Profile Override)**:
    - Action buttons: **`Add Game`**, **`Add to Quick Settings`** (adds the Game Mode QS Tile), and **`Clear List`**;
    - Each game row includes a dedicated mode pill (**`System Default ▾`** / **`Performance Mode ▾`** / **`Saver High-Hz ▾`**) to assign a per-game target profile:
      1. **`Follow Global Default`**
      2. **`System Default (Lift Saver Throttle · Cool)`** (ideal when daily mode is `Adaptive Saver`—lifts Battery Saver CPU throttling for lightweight games without excess heat)
      3. **`Game Performance Mode (Full Boost)`** (ideal for heavy 3D games)
      4. **`Adaptive Saver High-Hz (Saver + 120Hz)`** (ideal for casual/card games)
- **`Real-Time FPS Overlay`**: Displays zero-latency SurfaceFlinger real-time FPS and power consumption in the top-left corner.
- **`Outdoor High Brightness Mode`**: Temporarily boosts peak physical brightness under direct sunlight.
- **`Battery Info & Health`**: Opens a scrollable Miuix `WindowDialog` reading real-time battery level, temperature, charging status, current, voltage, cycle count, and Samsung factory health, with a **`Refresh Data`** button.
- **`App Cold Launch Boost & Cleanup`** (Tap to open the **`App Launch Boost`** secondary screen):
  - Card 1 **`ART Machine Code Precompilation`**: Four action buttons:
    - **`Selected Apps Precompile`** (`v13.8.1`): Opens a multi-select app picker supporting **`Full Speed (`speed`)`**, **`Profile-Guided (`speed-profile`)`**, and **`Reset Cache (`--reset`)`** modes, **`User Apps` / `All` / `System Apps`** filters, search, select all/clear, and real-time per-app compilation progress;
    - **`Core Apps Precompile`**: Compiles system and core high-frequency apps;
    - **`All Apps Precompile`**: Runs `speed-profile` compilation across all apps;
    - **`Clear Compiled Cache`**: Resets compiled state.
  - Card 2 **`App Cache & Cached Process Cleanup`**: Tap **`Clean & Release Now`** to run `pm trim-caches` and `am kill-all`.

#### 3. `Background & Deep Sleep`
- **`Background Process Freezer (Tombstone)`**: Enables Android's native Freezer to freeze cached background processes.
- **`Screen-Off Deep Sleep Parameters`** (Tap to open the **`Screen-Off Deep Sleep`** secondary screen):
  - Master **`On / Off`** switch;
  - Maintenance settings: **`No Maintenance`** switch + **`Initial Maintenance Window Interval`** slider (`10 min ~ 120 min`);
  - **`Manage Battery Usage`** card: **`Check Current Standby State`**, **`Standby Wakeup Guard`**, **`Background Protection List`**, **`Recent Doze History`**, and **`Manage Battery Usage`**.
- **`Limit Background Autostart`**: Blocks background broadcast autostart and chain wakeups.
- **`Limit Background Location`**: Limits background location request intervals to 30 minutes.
- **`Limit Scanning & Sync`**: Disables background implicit Wi-Fi / Bluetooth / nearby scanning and redundant auto-sync.

#### 4. `System Maintenance & Extensions`
- **`Force All Apps Resizable / Multi-Window`**: Unlocks window restrictions so all apps support split-screen and pop-up view.
- **`Standby Wakeup Guard`** (Tap to open secondary screen):
  - Includes **`Enable Standby Wakeup Guard`**, **`Start Scan`** (analyzes battery/alarm/standby bucket stats to recommend abnormal apps with **`One-Tap Add Suggested`**), and **`Guard List`** management.
- **`Geek Lab`** (Opens 3-item dialog):
  - **`Unlock Restricted Settings`**: Grants `ACCESS_RESTRICTED_SETTINGS` via privileged command to bypass Android 13+ Accessibility restricted settings blocks;
  - **`Background Protection Whitelist`** (Opens **`Background Protection List`**): Filter by **`All` / `User Apps` / `System Apps`**, search, and toggle Doze whitelist protection or AppOps background restriction per app;
  - **`Quick System Diagnostics`**: Inspects active privileged channel, Accessibility service, and bypass charging node support.
- **`Restore System Defaults`**: Clears all custom scheduling, power saving, refresh rate, and system tweaks back to stock defaults.
- **`User Manual & Help`**: Opens documentation and troubleshooting guides.

---

### 3. Tab 3: `App Rules` (Per-App Refresh Rate Rules)

1. **`Foreground App Detection`**: Monitors active foreground apps via the Accessibility Service and switches refresh rates seamlessly.
2. **`Scenario Presets` (2×2 Quick Pills)**
   - **`Daily Adaptive` (`10~120Hz`)**
   - **`Video Saver` (`60Hz`)**
   - **`Reading` (`10~60Hz`)**
   - **`Gaming Max` (`120Hz`)**
3. **`App Rule Configuration`**
   - Select any installed app, toggle **`Adaptive Refresh Rate`**, choose **`Max Refresh Rate`** (`120Hz` / `90Hz` / `60Hz` / `48Hz`) and **`Min Refresh Rate`** (`60Hz` / `48Hz` / `30Hz` / `24Hz` / `10Hz`) via top-level Miuix `WindowDropdownPreference` menus, and **`Save Rule`** or **`Delete Rule`**.
4. **`Configured Rules (N)`**: Displays all configured per-app rules with one-tap editing, individual `✕` removal, or **`Clear All Rules`**.
5. **`Advanced High-Hz & Power Calibration`**
   - **`Why Some Apps (e.g. TikTok) Lock at 60Hz?`**: Tap **`Open Developer Options`** to jump to Developer Options and automatically scroll to Samsung's **"More settings" -> "Disable app compatibility policies"** to unlock 120Hz in video/streaming apps.
   - **`LTPO Adaptive Calibration`**: Tap **`Activate Adaptive Refresh`** to activate 1Hz~120Hz hardware adaptive refresh and clear residual locks.

---

### 4. Quick Settings Tiles & Top-Right `Settings`

- **Quick Settings Tiles**:
  - **`Game Mode` Tile**: Cycle through `System Default` / `Adaptive Saver` / `Game Max` from the notification shade.
  - **`Outdoor Mode` Tile**: Toggle peak outdoor brightness (HBM) from the notification shade.
- **Top-Right `Settings` Screen (`SettingsGlassScreen`)**:
  - **`Startup & Persistence`**: `Start on boot (root)`, `Watchdog service`, `Keep ADB TCP mode after wireless start`, `ADB TCP port`.
  - **`Appearance & Theme`**: `Dark theme` (Follow system / Always on / Always off), `Black night theme` (Pure black OLED), `Use system theme color` (Android 12+).
  - **`Language & Internationalization`**: Switch app display language.

---

## 📦 Download & Build

### Prebuilt APK
Download the latest signed release APK from **[GitHub Releases](https://github.com/WEIHEWU/shizuku-sam/releases/latest)**:
- Latest Release: **[`Shizuku-Sam-v13.8.4.apk`](https://github.com/WEIHEWU/shizuku-sam/releases/download/v13.8.4/Shizuku-Sam-v13.8.4.apk)** (also archived under [`releases/`](./releases/))

### Build from Source
- **JDK**: Java 21
- **Android SDK**: `compileSdk 36` / `targetSdk 36` / `minSdk 26`

```bash
git clone https://github.com/WEIHEWU/shizuku-sam.git
cd shizuku-sam
./gradlew :manager:assembleRelease
```

---

## 📄 License & Credits

- Built upon [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) under the [Apache License 2.0](./LICENSE).
- UI powered by [miuix-kotlin-multiplatform](https://github.com/miuix-kotlin-multiplatform/miuix) and [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass).
