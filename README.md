# Shizuku Sam

[English](README.en.md) · **[📥 点击直接下载最新安装包 (Shizuku-Sam-v13.8.0.apk · 3.4 MB)](https://github.com/WEIHEWU/shizuku-sam/raw/main/releases/Shizuku-Sam-v13.8.0.apk)** · [Releases 历史版本页面](https://github.com/WEIHEWU/shizuku-sam/releases)

基于官方 **Shizuku (`moe.shizuku.privileged.api`)** 深度定制的三星 One UI 8.5 / Galaxy 旗舰增强版，采用 **Miuix (HyperOS / MIUIX KMP)** 原生阻尼与 Bento 卡片交互体系，集成 **无 Wi-Fi 重启自启（Loopback 端口扫描直连）**、**三通道权限引擎（Root / Shizuku / Device Owner）** 以及 **100% 真实生效的系统硬件与显示深度调校套件**。

当前版本：`13.8.0`（`versionCode 130800`）。

> 针对三星 Galaxy S24 Ultra（One UI 8.5 / Android 16）深度适配与实机验证，同时完全兼容标准 Shizuku API 生态。

## 核心特性

### 1. Shizuku 增强内核与无 Wi-Fi 重启自启
- **100% 兼容官方 Shizuku 生态**：保持 `moe.shizuku.privileged.api` 包名与 Binder 服务接口，所有依赖 Shizuku 的第三方应用无缝调用。
- **无 Wi-Fi 环境下开机自启**：
  - 结合 `WRITE_SECURE_SETTINGS` 自动拉起系统无线调试（`adb_wifi_enabled=1`），并在无 Wi-Fi 或 mDNS 广播受阻时通过本地回环（`127.0.0.1`）高并发端口扫描探测 ADB TLS 端口完成自启。
- **三通道执行引擎 (`SimEngine`)**：
  - 自动按 **Root (`su`) → Shizuku (`rish`/Binder) → Device Owner (DPM)** 优先级路由底层指令，实时校验硬件节点与系统表项回读结果。

### 2. 显示与触控精调（零安慰剂，全量底层直写）
- **全局高刷锁定与 LTPO 帧率控制**：真实读写 `Settings.Secure [refresh_rate_mode]`、`Settings.System [peak_refresh_rate]` 与 `min_refresh_rate`（支持自适应 120Hz / 强制全局 120Hz / 标准 60Hz），并提供 `SurfaceFlinger` 硬件图层锁帧与零延迟实时 FPS 悬浮窗（`dumpsys SurfaceFlinger --latency`）。
- **三星游戏 Service Mode 240Hz 触控采样率**：实时检测 `sec_tsp` 触控芯片状态（`get_game_mode`）与 `game_touch_sampling_rate` 表项，并提供可视化实时多指触控采样率 / 报点延迟测试面板。
- **屏幕色彩模式与 AMOLED 硬件级极暗 (`Reduce Bright Colors`)**：直写 `sec_display_preset_index` 与 `reduce_bright_colors_activated` / `reduce_bright_colors_level`。
- **三星原生双击亮屏 (`double_tab_to_wake_up`) 与双击锁屏 (`double_tab_to_sleep`)**。
- **自定义 DPI / 分辨率缩放与无级过渡动画倍速（`0.0x ~ 2.0x`）**：内置防黑屏安全边界与一键恢复出厂默认尺寸/密度。

### 3. 性能、调度与内存管理
- **SoC 硬件频率与州长（Governor）锁定**：支持按 CPU Cluster 独立调节 `scaling_governor` 与 `scaling_min_freq` / `scaling_max_freq`（需 Root）。
- **三星性能模式直控**：直接读写 `sem_enhanced_cpu_responsiveness` 与 `low_power` 系统状态。
- **后台进程上限 (`ActivityManager.setProcessLimit`) 与三星 RAM Plus (`ram_expand_size`) 调节**。
- **应用 DEX 编译加速 (`cmd package compile -m speed-profile / everything`)** 与全量应用缓存回收（`pm trim-caches`）。
- **内核级内存回收 (`drop_caches`) 与 `fstrim` 闪存碎片整理**。

### 4. 电池健康、充电控制与网络净化
- **三星硬件充电保护 (`protect_battery` / `battery_protection_threshold`) 与快充总成控制**：Miuix 原生居中弹窗调节充电阈值，直通 `adaptive_fast_charging`、`super_fast_charging`、`wireless_fast_charging` 与游戏旁路直供电（`pass_through`）。
- **深度 Doze 一键休眠 (`dumpsys deviceidle force-idle`) 与待机策略优化**。
- **Private DNS (DoT) 加密解析**：内置 AdGuard DNS、Cloudflare `1.1.1.1`、阿里 DNS、腾讯 DNSPod 与自定义 DoT 主机名一键切换。
- **沉浸状态栏 (`policy_control`)、状态栏图标精简 (`icon_blacklist`)、多任务后台模糊防窥与剪切板隐私守护**。

## 构建说明

需要 JDK 21 与 Android SDK（`compileSdk 37` / `NDK 29.0.13113456`）。

```bash
./gradlew :manager:assembleRelease
```

构建产物位于 `manager/build/outputs/apk/release/shizuku-v13.8.0-release.apk`。
- 若根目录存在 `signing.properties`（配置 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEYSTORE_ALIAS`、`KEYSTORE_ALIAS_PASSWORD`），将使用正式签名密钥构建；
- 若未提供 `signing.properties`，构建脚本会自动回退至 Android Debug Keystore，克隆仓库后即可直接编译运行。

## 免责声明

本项目仅供个人学习、系统研究与设备调试使用。修改系统底层属性、频率节点或分辨率存在一定风险，请自行评估并承担相应后果。

## License

Apache License 2.0。详见 [LICENSE](LICENSE)。
基于 [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) 开源项目构建。
