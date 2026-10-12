# Shizuku Sam (MaxHz Edition)

<p align="center">
  <b>Samsung One UI Performance Scheduler, Refresh Rate Controller & Battery Health Manager built on Shizuku</b><br>
  <sub>Miuix UI Components · Liquid Glass Floating Dock · Non-Root (ADB) & Root Dual-Mode Engine</sub>
</p>

<p align="center">
  <a href="./README.md">简体中文</a> | <a href="./README.en.md">English</a> | <a href="https://github.com/WEIHEWU/shizuku-sam/releases/latest"><b>⬇️ Download Latest Release (v13.8.2)</b></a>
</p>

---

## 📌 Overview

**Shizuku Sam** integrates the **Shizuku privileged service** with a purpose-built **Samsung Galaxy (One UI) hardware scheduler, charging protection, background freezer, and per-app refresh rate controller** in a single cohesive app.

The entire app is organized around a **3-tab Liquid Glass floating dock (`Shizuku` · `Hardware` · `App Rules`)** and **Miuix cards and top-level `WindowDialog` modals**.

---

## ✨ Real App Features (100% Matched to the Actual UI)

### 1. Tab 1: `Shizuku` (Privileged Service & Wireless Pairing)

1. **Hero Status Card (`Shizuku Sam`)**
   - **When Stopped**: Shows local wireless debugging port detection status and a one-tap **`Start via Wireless Debugging`** button.
   - **When Running**: Displays **Service Version**, **Execution Mode (`ROOT Mode` / `ADB Mode`)**, and **Authorized Apps count**, with quick actions:
     - **`Manage Authorized Apps`**: Grant or revoke Shizuku access for third-party apps.
     - **`Terminal (rish)`**: Export and run privileged shell commands in terminal apps.
     - **`Stop Service`**: Safely stop Shizuku via a Miuix `WindowDialog` confirmation prompt.
2. **`Wireless Debugging Pairing` (Shown when stopped)**
   - **`Hands-Free Auto Pairing`**: Uses the Accessibility Service to automatically detect the wireless debugging pairing dialog and fill in the 6-digit pairing code without manual split-screen typing.
   - **`Wireless Pairing Wizard`**: Step-by-step pairing guide or manual 6-digit code entry.
   - **`Developer Options`**: Jump directly to System Developer Options -> Wireless Debugging.
3. **`More Launch Methods` / `Management & Backup Channels`**
   - **`Start via Root` / `Restart via Root`**: Instant one-tap startup for rooted devices (Magisk / KernelSU / APatch) without PC or Wi-Fi.
   - **`Start via PC` / `PC ADB Launch Command`**: View and copy the `adb` starter script command.
   - **`Reconnect Debugging`** (Shown in ADB mode): Rescan local loopback wireless debugging ports and refresh the ADB connection.

---

### 2. Tab 2: `Hardware` (System Scheduling & Hardware Enhancements)

#### 1. `Performance Mode` (3-Mode Segmented Switch)
- **`System Default` (Balanced)**: Restores stock Samsung scheduling and native adaptive refresh rate, clearing all custom overrides.
- **`Adaptive Saver` (Battery Priority)**: Enables system power saving while unlocking **120Hz adaptive high refresh rate + Quick Doze**, solving One UI's stock 60Hz lock in Power Saving mode.
- **`Game Max` (Full Performance)**: Unlocks full CPU/GPU performance scheduling, high touch sampling, and 120Hz refresh rate for gaming.

#### 2. `Hardware & Experience Enhancements`
- **`Bypass Charging (Pass-Through)`**: Powers the motherboard directly from a PPS charger during gaming, bypassing battery charging to reduce heat and cycle wear (requires a PPS charger and battery ≥ 20%).
- **`Battery Charging Protection`**: Toggle directly via the switch or tap the row to open the Miuix `WindowDialog` and customize **Stop Charging (%)** and **Resume Charging (%)** thresholds (includes `80/75 Daily`, `85/80 Balanced`, and `90/85 Travel` presets).
- **`Game Auto High-Hz Boost` (`v13.8.2` Per-Game Target Profile Override)**:
  - Automatically switches performance and refresh rate when entering configured games, and **restores your exact pre-game daily snapshot upon exiting**.
  - Tap to configure the game list, set the **Global Default Target Profile**, or **assign an independent profile to each individual game**:
    - **`Follow Global Default`**
    - **`System Default (Lift Saver Throttle · Cool)`**: Ideal for lightweight games when your daily mode is `Adaptive Saver`—lifts Battery Saver CPU throttling without forcing maximum heat.
    - **`Game Performance Mode (Full Boost)`**: Ideal for heavy 3D games.
    - **`Adaptive Saver High-Hz (Saver + 120Hz)`**: Ideal for ultra-light casual/card games.
- **`Real-Time FPS Overlay`**: Displays zero-latency SurfaceFlinger real-time FPS and power draw in the top-left corner.
- **`Outdoor High Brightness Mode`**: Temporarily boosts peak physical brightness (HBM) under direct sunlight, with Quick Settings tile support.
- **`Battery Info & Health`**: Opens a Miuix `WindowDialog` displaying real-time battery level, temperature, charging status, current, voltage, **cycle count**, and **Samsung factory battery health (%)**.
- **`App Cold Launch Boost & Cleanup` (`v13.8.1` Per-App Selection)**:
  - Run ART compilation on **all third-party apps** or **specific user-selected apps**.
  - Supports three compilation modes: **`speed` (full AOT machine code compilation)**, **`speed-profile` (profile-guided compilation)**, and **`--reset` (reset compiled artifacts)**, plus one-tap system cache cleanup.

#### 3. `Background & Deep Sleep`
- **`Background Process Freezer (Tombstone)`**: Enables Android's native Freezer to freeze cached background processes and stop rogue CPU usage.
- **`Screen-Off Deep Sleep (Quick Doze)`**: Enters deep Doze rapidly after screen-off; tap the row to customize Doze step parameters and maintenance window intervals.
- **`Limit Background Autostart`**: Blocks background broadcast autostart and chain wakeups.
- **`Limit Background Location`**: Throttles background location requests to 30-minute intervals.
- **`Limit Scanning & Sync`**: Disables background implicit Wi-Fi / Bluetooth / nearby scanning and redundant auto-sync.

#### 4. `System Maintenance & Extensions`
- **`Force All Apps Resizable / Multi-Window`**: Allows all apps to run in split-screen and freeform pop-up windows.
- **`Standby Wakeup Guard`**: Identifies abnormal background wakeups and standby drain with one-tap suppression.
- **`Geek Lab`**:
  - **`Unlock Restricted Settings`**: Grants `ACCESS_RESTRICTED_SETTINGS` via privileged shell to bypass Android 13+ Accessibility restricted settings blocks.
  - **`Background Protection Whitelist`**: Manage the Doze battery optimization whitelist.
  - **`Quick System Diagnostics`**: Inspect current privileged channel, Accessibility service, and hardware node status.
- **`Restore System Defaults`**: Resets all custom scheduling, power saving, refresh rate, and system tweaks back to stock defaults.
- **`User Manual & Help`**: View documentation, usage guides, and troubleshooting tips.

---

### 3. Tab 3: `App Rules` (Per-App Refresh Rate Rules)

1. **`Foreground App Detection`**: Monitors active foreground apps via the Accessibility Service and switches refresh rates seamlessly.
2. **`Scenario Presets` (2×2 Quick Pills)**
   - **`Daily Adaptive` (`10~120Hz`)**
   - **`Video Saver` (`60Hz`)**
   - **`Reading` (`10~60Hz`)**
   - **`Gaming Max` (`120Hz`)**
3. **`App Rule Configuration`**
   - Pick any installed app and configure **`Adaptive Refresh Rate`**, **`Max Refresh Rate` (`120Hz` / `90Hz` / `60Hz` / `48Hz`)**, and **`Min Refresh Rate` (`60Hz` / `48Hz` / `30Hz` / `24Hz` / `10Hz`)**.
4. **`Configured Rules`**: Lists all active per-app rules with one-tap editing, individual deletion, or **`Clear All Rules`**.
5. **`Advanced High-Hz & Power Calibration`**
   - **`Why Some Apps (e.g. TikTok) Lock at 60Hz?`**: Tap **`Open Developer Options`** to jump to Developer Options and automatically scroll to Samsung's **"More settings" -> "Disable app compatibility policies"** to unlock 120Hz in video/streaming apps.
   - **`LTPO Adaptive Calibration`**: Tap **`Activate Adaptive Refresh`** to calibrate 1Hz~120Hz hardware adaptive scaling and clear residual refresh locks.

---

## 📦 Download & Build

### Prebuilt APK
Download the latest signed release APK from **[GitHub Releases](https://github.com/WEIHEWU/shizuku-sam/releases/latest)**:
- Latest Release: **[`Shizuku-Sam-v13.8.2.apk`](https://github.com/WEIHEWU/shizuku-sam/releases/download/v13.8.2/Shizuku-Sam-v13.8.2.apk)** (also archived under [`releases/`](./releases/))

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
