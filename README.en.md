# Shizuku Sam

[中文说明](README.md) · **[📥 Direct Download Latest APK (Shizuku-Sam-v13.8.1.apk · 3.4 MB)](https://github.com/WEIHEWU/shizuku-sam/raw/main/releases/Shizuku-Sam-v13.8.1.apk)** · [Releases](https://github.com/WEIHEWU/shizuku-sam/releases)

An enhanced edition of **Shizuku (`moe.shizuku.privileged.api`)** tailored for Samsung One UI 8.5 / Galaxy flagships, built with **Miuix (`top.yukonga.miuix.kmp`)** UI, featuring **Wi-Fi-free Boot Auto-Start (Loopback ADB TLS port scanner)**, a **3-Channel Execution Engine (Root / Shizuku / Device Owner)**, and a **100% genuine, zero-placebo hardware & system tuning suite**.

Current version: `13.8.1` (`versionCode 130801`).

> Tested and verified on Samsung Galaxy S24 Ultra (One UI 8.5 / Android 16), while remaining 100% compatible with the standard Shizuku API ecosystem.

## Key Features

### 1. Enhanced Shizuku Core & Wi-Fi-Free Boot Auto-Start
- **100% Shizuku Ecosystem Compatibility**: Retains `moe.shizuku.privileged.api` and full Binder IPC compatibility for all Shizuku client apps.
- **Boot Auto-Start Without Wi-Fi**:
  - Automatically enables Wireless Debugging (`adb_wifi_enabled=1`) via `WRITE_SECURE_SETTINGS` and discovers the local ADB TLS port via concurrent `127.0.0.1` loopback scanning when Wi-Fi or mDNS is unavailable.
- **3-Channel Execution Engine (`SimEngine`)**:
  - Automatically routes privileged operations through **Root (`su`) → Shizuku (`rish`/Binder) → Device Owner (DPM)** with real-time state readback verification.

### 2. Display & Touch Tuning (100% Real System Writes, Zero Placebo)
- **Refresh Rate & LTPO Control**: Directly configures `Settings.Secure [refresh_rate_mode]`, `Settings.System [peak_refresh_rate]`, and `min_refresh_rate` (Adaptive 120Hz / Forced 120Hz / Standard 60Hz), plus `SurfaceFlinger` hardware frame rate locking and zero-lag real-time FPS monitor (`dumpsys SurfaceFlinger --latency`).
- **Samsung 240Hz Touch Sampling & Live Touch Tester**: Reads/writes `game_touch_sampling_rate` and `sec_tsp` hardware status (`get_game_mode`), complete with an interactive multi-touch Hz & latency test pad.
- **Screen Color Mode & Hardware Extra Dim (`Reduce Bright Colors`)**: Directly controls `sec_display_preset_index`, `reduce_bright_colors_activated`, and `reduce_bright_colors_level`.
- **Samsung Native Double-Tap to Wake (`double_tab_to_wake_up`) & Double-Tap to Sleep (`double_tab_to_sleep`)**.
- **Custom DPI / Resolution Scaling & Continuous Animation Speed (`0.0x ~ 2.0x`)** with one-tap factory reset safety guards.

### 3. Performance, CPU Governor & Memory Management
- **Per-Cluster CPU Governor & Frequency Locking**: Adjusts `scaling_governor`, `scaling_min_freq`, and `scaling_max_freq` per CPU cluster (Root required).
- **Samsung Performance Profile**: Directly toggles `sem_enhanced_cpu_responsiveness` and `low_power`.
- **Background Process Limit (`ActivityManager.setProcessLimit`) & Samsung RAM Plus (`ram_expand_size`)**.
- **App DEX Compilation (Per-app custom selection with `-m speed` full AOT / `-m speed-profile`, core apps, or all apps)**, system-wide cache trimming (`pm trim-caches`), kernel pagecache reclamation (`drop_caches`), and `fstrim`.

### 4. Battery Health, Charging & Network Privacy
- **Samsung Battery Protection (`protect_battery` / `battery_protection_threshold`) & Fast Charging Controls**: Miuix modal dialog for charging threshold & direct toggles for `adaptive_fast_charging`, `super_fast_charging`, `wireless_fast_charging`, and bypass power (`pass_through`).
- **Deep Doze Force-Idle (`dumpsys deviceidle force-idle`)**.
- **Private DNS (DoT) Switcher**: Built-in presets for AdGuard DNS, Cloudflare `1.1.1.1`, AliDNS, DNSPod, and custom DoT hostnames.
- **Immersive Mode (`policy_control`), Status Bar Icon Filter (`icon_blacklist`), Recents Privacy Blur, and Clipboard Guard**.

## Build

Requires JDK 21 and Android SDK (`compileSdk 37` / `NDK 29.0.13113456`).

```bash
./gradlew :manager:assembleRelease
```

Output APK: `manager/build/outputs/apk/release/shizuku-v13.8.1-release.apk`。
- If `signing.properties` is present in the project root, the release keystore is used.
- Otherwise, the build script automatically falls back to the Android Debug Keystore so cloned repositories build out of the box.

## License

Apache License 2.0. See [LICENSE](LICENSE).
Based on [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku).
