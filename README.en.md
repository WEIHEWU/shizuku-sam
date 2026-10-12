# Shizuku Sam

[中文说明](README.md) · **[📥 Direct Download Latest APK (Shizuku-Sam-v13.8.2.apk · 3.4 MB)](https://github.com/WEIHEWU/shizuku-sam/releases/download/v13.8.2/Shizuku-Sam-v13.8.2.apk)** · [Releases](https://github.com/WEIHEWU/shizuku-sam/releases)

An enhanced edition of **Shizuku (`moe.shizuku.privileged.api`)** crafted for Samsung One UI / Galaxy flagships, built with **Miuix (`top.yukonga.miuix.kmp`)** components and a floating frosted-glass navigation dock. It integrates **Accessibility Auto-Pairing for Wireless Debugging**, **Wi-Fi-Free Boot Auto-Start**, **Device Owner (Dhizuku) rootless privilege support**, and a genuine **Samsung performance & power management suite** (per-game mode switching, battery protection thresholds, bypass charging, ART machine-code AOT compilation, and per-app LTPO refresh rate rules).

Current version: `v13.8.2` (`versionCode 130802`).

---

## Core Features (100% Matched to Actual App Modules)

The app is organized into three primary tabs: **Privilege Service**, **Performance & Hardware**, and **App Rules**.

### 1. Privilege Service — Shizuku Core & Multi-Channel Activation
- **100% Shizuku Ecosystem Compatibility**: Retains the `moe.shizuku.privileged.api` package name and standard Binder IPC interface so all Shizuku-dependent apps work seamlessly, with built-in `rish` shell export.
- **Wireless Debugging Auto-Pairing Assistant (`AdbPairingDetector`)**: Automatically detects the 6-digit pairing code and port from the system "Pair device with pairing code" dialog via Accessibility and completes pairing in the background without manual input.
- **Wi-Fi-Free Boot Auto-Start**: Uses `WRITE_SECURE_SETTINGS` to enable Wireless Debugging (`adb_wifi_enabled=1`) and scans local loopback (`127.0.0.1`) ports to connect ADB TLS even when Wi-Fi or mDNS is unavailable.
- **Multi-Channel Privilege Support**: Supports Wireless ADB, Root (`su`), and Device Owner (Dhizuku-compatible rootless persistent privilege).

---

### 2. Performance & Hardware — Samsung Tuning, Per-Game Auto Mode & Battery Health

#### Core Performance Profiles (with Quick Settings Tile support)
- **System Default (Balanced)**: Restores standard Samsung CPU/GPU scheduling and LTPO adaptive refresh rate.
- **Adaptive Power Saver (Battery First)**: Enables system power saving while bypassing Samsung's 60Hz power-saver lock to maintain smooth 10~120Hz adaptive refresh rate.
- **Game Performance (Full Release)**: Enables enhanced CPU/GPU responsiveness, Vulkan rendering optimizations, game touch boost, and full 120Hz refresh rate.

#### Game Auto High-Refresh & Mode Switcher (`v13.8.2` New: Per-Game Profile Override)
- **Zero Background Polling**: Event-driven via Accessibility window state changes with zero idle battery drain.
- **Per-Game Target Mode (`v13.8.2`)**:
  - In addition to the global default target mode, each game in the list has its own **mode pill (`System Default ▾` / `Performance ▾` / `Adaptive Saver ▾`)**:
  - Ideal for users who stay in **Adaptive Power Saver** during daily use: set lightweight/casual games to **System Default** (automatically lifts power-saver CPU throttling without overheating) and heavy 3D games to **Game Performance** (full CPU/GPU boost).
- **Pre-Game Snapshot & 100% Accurate Restore**: Captures your daily state before entering the first game, dynamically switches profiles when hopping between games of different tiers without overwriting the initial snapshot, and restores your exact daily mode after exiting for the configured buffer time (`30s / 60s / 120s`).

#### Battery Health, Bypass Charging & Display Tools
- **Bypass Charging (Pass-Through)**: Routes power directly to the motherboard during gaming to reduce heat and battery wear (requires a PPS charger and battery ≥ 20%).
- **Charging Limit Protection**: Miuix top-level `WindowDialog` with quick presets (`80%/75% Daily`, `85%/80% Balanced`, `90%/85% Travel`) and custom stop/recharge thresholds.
- **Real-Time Battery Info & Health**: Live readout of battery level, temperature, current, voltage, cycle count, and Samsung factory health percentage.
- **Live FPS & Power Overlay**: Displays real-time `SurfaceFlinger` FPS and battery power draw (`W` / `mA`) in the top-left corner.
- **Outdoor High-Brightness Mode (HBM)**: Boosts peak display brightness under direct sunlight, with Quick Settings Tile support.

#### App Cold-Start Boost (ART AOT Compilation)
- **Per-App Custom AOT Compilation (`v13.8.1`)**: Multi-select installed apps with search and status filters (`Not yet speed` / `Already speed` / `All`), and run:
  - **Full Machine Code (`-m speed -f`)** for zero JIT stutter on critical apps and games;
  - **Profile-Guided (`-m speed-profile -f`)** for balanced speed and storage;
  - **Reset Compilation (`--reset`)**.
- **One-Tap Batch Boost & Cache Cleanup**: Batch `speed-profile` compilation for all user apps and redundant code cache cleanup.

#### Background & Deep Doze Governance
- **Cached App Freezer (Tombstone)**: Freezes background cached processes via Android's native Freezer to stop background CPU drain.
- **Quick Doze Parameters**: Accelerates deep Doze entry after screen-off with customizable maintenance window intervals.
- **Background Autostart Limit / Background Location Throttle (30-min interval) / Background Scan & Sync Guard**.
- **Night Wakeup Guard**: Diagnoses abnormal standby wakeups and restricts offending apps.
- **Force Multi-Window / Freeform for All Apps** & **Geek Lab** (Unlock Restricted Settings, Doze Whitelist Manager, System Diagnostics, One-Tap Restore Defaults).

---

### 3. App Rules — Per-App Refresh Rate & LTPO Calibration
- **Scenario Presets**: One-tap presets for `Daily Scrolling (10~120Hz)`, `Video Playback (60Hz)`, `Static Reading (10~60Hz)`, and `Full-FPS Gaming (120Hz)`.
- **Per-App Custom Rules**: Configure Adaptive vs. Fixed mode, Max Refresh Rate (`120Hz / 90Hz / 60Hz / 48Hz`), and Min LTPO Refresh Rate (`60Hz / 48Hz / 30Hz / 24Hz / 10Hz`) per app using top-level `WindowDropdownPreference`.
- **Streaming High-Refresh Compatibility Shortcut**: Opens Developer Options and auto-scrolls directly to "Disable app compatibility policies".
- **LTPO Adaptive Downclock & Power Calibration**: One-tap calibration for LTPO idle frequency scaling.

---

## Build

Requires JDK 21 and Android SDK (`compileSdk 37` / `NDK 29.0.13113456`).

```bash
./gradlew :manager:assembleRelease
```

Output APK: `manager/build/outputs/apk/release/shizuku-v13.8.2-release.apk`.

## License

Apache License 2.0. See [LICENSE](LICENSE).
Based on [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku), with UI powered by [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix).
