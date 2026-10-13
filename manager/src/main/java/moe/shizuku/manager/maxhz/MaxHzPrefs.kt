package moe.shizuku.manager.maxhz

import android.content.Context

object MaxHzPrefs {

    private const val NAME = "maxhz_control"
    private const val KEY_CONTROL_ENABLED = "control_enabled"
    private const val KEY_COMPATIBLE_SAVER_ENABLED = "compatible_saver_enabled"
    private const val KEY_QUICK_DOZE_ENABLED = "quick_doze_enabled"
    private const val KEY_QUICK_DOZE_MAINTENANCE_MINUTES = "quick_doze_maintenance_minutes"
    private const val KEY_QUICK_DOZE_NO_MAINTENANCE = "quick_doze_no_maintenance"
    private const val KEY_NIGHT_WAKEUP_GUARD_ENABLED = "night_wakeup_guard_enabled"
    private const val KEY_SCAN_GUARD_ENABLED = "scan_guard_enabled"
    private const val KEY_BATTERY_PROTECTION_ENABLED = "battery_protection_enabled"
    private const val KEY_BATTERY_LIMIT = "battery_limit"
    private const val KEY_BATTERY_RECHARGE = "battery_recharge"
    private const val KEY_PASS_THROUGH_ENABLED = "pass_through_enabled"
    private const val KEY_FPS_MONITOR_RUNNING = "fps_monitor_running"
    private const val KEY_APP_REFRESH_RULES = "app_refresh_rules"
    private const val KEY_SUPPORTED_REFRESH_RATES = "supported_refresh_rates"
    private const val KEY_SUPPORTED_REFRESH_RATES_DETECTED = "supported_refresh_rates_detected"
    private const val KEY_NIGHT_WAKEUP_GUARD_PACKAGES = "night_wakeup_guard_packages"
    private const val KEY_NIGHT_WAKEUP_GUARD_APPLIED_PACKAGES = "night_wakeup_guard_applied_packages"
    private const val KEY_NIGHT_WAKEUP_GUARD_SUGGESTIONS = "night_wakeup_guard_suggestions"
    private const val KEY_NIGHT_WAKEUP_GUARD_SUGGESTION_DETAILS = "night_wakeup_guard_suggestion_details"
    private const val KEY_NIGHT_WAKEUP_GUARD_LEGACY_DEFAULTS_REMOVED = "night_wakeup_guard_legacy_defaults_removed"
    private const val KEY_DOZE_RESTRICTED_PACKAGES = "doze_restricted_packages"
    private const val KEY_M_MAIN_MODE = "m_main_mode"
    private const val KEY_M_GPU_MODE = "m_gpu_mode"
    private const val KEY_M_REFRESH_120_ENABLED = "m_refresh_120_enabled"
    private const val KEY_M_VULKAN_ENABLED = "m_vulkan_enabled"
    private const val KEY_M_NETWORK_ENABLED = "m_network_enabled"
    private const val KEY_M_TOUCH_ENABLED = "m_touch_enabled"
    private const val KEY_M_THERMAL_ENABLED = "m_thermal_enabled"
    /**
     * 温控的两个方向必须分成两个 pref。
     *
     * 以前只有 `m_thermal_enabled` 一个：极致性能执行的是「放宽温控」
     * (applyMOriginalThermalCommands)，系统温控保护开关执行的也是「保护温控」
     * (applyMSafeThermal)，两者都往这同一个 pref 写 true —— 于是开了极致性能后，
     * 「系统温控保护」显示为「开启」，可系统实际跑的却是放宽温控。这是会真实
     * 影响发热的语义反转，必须有各自独立的状态位。
     */
    private const val KEY_M_THERMAL_PROTECT = "m_thermal_protect"
    private const val KEY_M_THERMAL_RELAXED = "m_thermal_relaxed"
    /**
     * 「极致性能模式」的单一开关位。
     *
     * 以前 isOn 是把 m_main_mode / m_gpu_mode / m_refresh_120 / m_vulkan /
     * m_thermal 五个 pref 与运算拼出来的「假聚合」—— 只要其中任意一个被单
     * 独改动，整个极致性能的开关就会莫名翻转。现在它就是它自己的状态。
     */
    private const val KEY_SUPER_PERFORMANCE_ENABLED = "super_performance_enabled"

    /**
     * 游戏会话是否正在生效。
     *
     * 一级页「极致性能模式」在它为 true 时硬锁置灰，避免用户在游戏进行中
     * 手动关掉游戏模式刚设好的性能状态。关掉游戏模式或退出游戏时清掉。
     */
    private const val KEY_GAME_AUTO_SESSION_ACTIVE = "game_auto_session_active"

    /** 进入游戏时是否放宽温控（取代了原来的三档档位） */
    private const val KEY_GAME_AUTO_THERMAL_RELAXED = "game_auto_thermal_relaxed"

    /**
     * 游戏模式三档目标档位常量：
     * - [GAME_PROFILE_DEFAULT]: 系统默认
     * - [GAME_PROFILE_SAVER]: 省电自适应高刷
     * - [GAME_PROFILE_PERFORMANCE]: 游戏性能模式
     */
    const val GAME_PROFILE_DEFAULT = "default"
    const val GAME_PROFILE_SAVER = "saver"
    const val GAME_PROFILE_PERFORMANCE = "performance"

    private const val KEY_GAME_AUTO_TARGET_PROFILE = "game_auto_target_profile"
    private const val KEY_GAME_TILE_MODE = "game_tile_mode"

    /** 游戏会话档位标识兼容常量 */
    const val GAME_AUTO_PROFILE_SUPER = "super"

    /** 进入游戏前的极致性能状态（快照三项之一） */
    private const val KEY_GAME_AUTO_SNAP_SUPER = "game_auto_snap_super"
    /** 进入游戏前的省电模式状态（快照三项之一） */
    private const val KEY_GAME_AUTO_SNAP_SAVER = "game_auto_snap_saver"
    /** 进入游戏前的温控方向（快照三项之一：protect / relaxed / 空） */
    private const val KEY_GAME_AUTO_SNAP_THERMAL = "game_auto_snap_thermal"
    /** 快照是否已拍。没有快照时退出游戏不能瞎还原，只能回系统默认 */
    private const val KEY_GAME_AUTO_SNAP_VALID = "game_auto_snap_valid"

    private const val KEY_M_TOMBSTONE_ENABLED = "m_tombstone_enabled"
    private const val KEY_M_GAME_VIDEO_SPEED_ENABLED = "m_game_video_speed_enabled"
    private const val KEY_M_CPU_RESPONSE_ENABLED = "m_cpu_response_enabled"
    private const val KEY_M_LOCATION_THROTTLE_ENABLED = "m_location_throttle_enabled"
    private const val KEY_M_AUTOSTART_LIMIT_ENABLED = "m_autostart_limit_enabled"
    private const val KEY_M_ZRAM_ENABLED = "m_zram_enabled"
    private const val KEY_M_DEXOPT_ENABLED = "m_dexopt_enabled"
    private const val KEY_GAME_AUTO_PERFORMANCE_ENABLED = "game_auto_performance_enabled"
    private const val KEY_GAME_AUTO_PERFORMANCE_PACKAGES = "game_auto_performance_packages"
    private const val KEY_GAME_AUTO_PACKAGE_PROFILES = "game_auto_package_profiles"
    /** @deprecated 三档档位已废弃，保留 key 只为把「极限」档迁移到 [KEY_GAME_AUTO_THERMAL_RELAXED] */
    private const val KEY_GAME_AUTO_PERFORMANCE_PROFILE = "game_auto_performance_profile"
    private const val KEY_GAME_AUTO_PERFORMANCE_EXIT_DELAY_SECONDS = "game_auto_performance_exit_delay_seconds"
    private const val KEY_GAME_AUTO_ACTIVE_PACKAGE = "game_auto_active_package"
    private const val KEY_GAME_AUTO_ACTIVE_PROFILE = "game_auto_active_profile"
    private const val KEY_RESULT_TEXT_PREFIX = "result_text_"
    private const val KEY_RESULT_COLLAPSED_PREFIX = "result_collapsed_"

    /**
     * 合法包名格式。原先在 13 处重复书写同一段正则字面量，
     * 统一抽成常量后既避免拼写漂移，也方便日后放宽/收紧规则只改一处。
     */
    private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

    /** 刷新率取值合法区间（Hz）。 */
    private const val MIN_REFRESH_HZ = 1
    private const val MAX_REFRESH_HZ = 240
    /** 快速 Doze 维护窗口合法区间（分钟）。 */
    private const val MIN_MAINTENANCE_MINUTES = 10
    private const val MAX_MAINTENANCE_MINUTES = 120

    private val LEGACY_NIGHT_WAKEUP_GUARD_DEFAULTS = setOf("com.tencent.mobileqq", "com.paget96.batteryguru")

    data class AppRefreshRule(
        val packageName: String,
        val adaptive: Boolean,
        val maxHz: Int,
        val minHz: Int
    )

    data class NightWakeupSuggestion(
        val packageName: String,
        val detail: String
    )

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun controlEnabled(context: Context) = prefs(context).getBoolean(KEY_CONTROL_ENABLED, false)
    fun setControlEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONTROL_ENABLED, enabled).apply()
    }

    fun compatibleSaverEnabled(context: Context) = prefs(context).getBoolean(KEY_COMPATIBLE_SAVER_ENABLED, false)
    fun setCompatibleSaverEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMPATIBLE_SAVER_ENABLED, enabled).apply()
    }

    fun quickDozeEnabled(context: Context) = prefs(context).getBoolean(KEY_QUICK_DOZE_ENABLED, false)
    fun setQuickDozeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_QUICK_DOZE_ENABLED, enabled).apply()
    }

    fun quickDozeMaintenanceMinutes(context: Context): Int {
        return prefs(context).getInt(KEY_QUICK_DOZE_MAINTENANCE_MINUTES, 80).coerceIn(MIN_MAINTENANCE_MINUTES, MAX_MAINTENANCE_MINUTES)
    }

    fun setQuickDozeMaintenanceMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(KEY_QUICK_DOZE_MAINTENANCE_MINUTES, minutes.coerceIn(MIN_MAINTENANCE_MINUTES, MAX_MAINTENANCE_MINUTES))
            .apply()
    }

    fun quickDozeNoMaintenance(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_QUICK_DOZE_NO_MAINTENANCE, false)
    }

    fun setQuickDozeNoMaintenance(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_QUICK_DOZE_NO_MAINTENANCE, enabled).apply()
    }

    fun nightWakeupGuardEnabled(context: Context) = prefs(context).getBoolean(KEY_NIGHT_WAKEUP_GUARD_ENABLED, false)
    fun setNightWakeupGuardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NIGHT_WAKEUP_GUARD_ENABLED, enabled).apply()
    }

    fun scanGuardEnabled(context: Context) = prefs(context).getBoolean(KEY_SCAN_GUARD_ENABLED, false)
    fun setScanGuardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCAN_GUARD_ENABLED, enabled).apply()
    }

    fun batteryProtectionEnabled(context: Context) = prefs(context).getBoolean(KEY_BATTERY_PROTECTION_ENABLED, false)
    fun setBatteryProtectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BATTERY_PROTECTION_ENABLED, enabled).apply()
    }

    fun batteryLimit(context: Context) = prefs(context).getInt(KEY_BATTERY_LIMIT, 80)
    fun batteryRecharge(context: Context) = prefs(context).getInt(KEY_BATTERY_RECHARGE, 75)
    fun passThroughEnabled(context: Context) = prefs(context).getBoolean(KEY_PASS_THROUGH_ENABLED, false)
    fun setPassThroughEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PASS_THROUGH_ENABLED, enabled).apply()
    }

    fun fpsMonitorRunning(context: Context) = prefs(context).getBoolean(KEY_FPS_MONITOR_RUNNING, false)
    fun setFpsMonitorRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_FPS_MONITOR_RUNNING, running).apply()
    }

    fun setBatteryProtectionConfig(
        context: Context,
        limit: Int,
        recharge: Int
    ) {
        prefs(context).edit()
            .putInt(KEY_BATTERY_LIMIT, limit)
            .putInt(KEY_BATTERY_RECHARGE, recharge)
            .apply()
    }

    fun appRefreshRules(context: Context): List<AppRefreshRule> {
        val raw = prefs(context).getString(KEY_APP_REFRESH_RULES, "") ?: ""
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 4) return@mapNotNull null
                val packageName = parts[0].trim()
                val maxHz = parts[2].toIntOrNull() ?: return@mapNotNull null
                val minHz = parts[3].toIntOrNull() ?: return@mapNotNull null
                if (packageName.isBlank()) return@mapNotNull null
                AppRefreshRule(
                    packageName = packageName,
                    adaptive = parts[1] == "adaptive",
                    maxHz = maxHz.coerceIn(MIN_REFRESH_HZ, MAX_REFRESH_HZ),
                    minHz = minHz.coerceIn(MIN_REFRESH_HZ, MAX_REFRESH_HZ)
                )
            }
            .toList()
    }

    fun appRefreshRuleFor(context: Context, packageName: String): AppRefreshRule? {
        return appRefreshRules(context).firstOrNull { it.packageName == packageName }
    }

    fun hasAppRefreshRules(context: Context): Boolean {
        return appRefreshRules(context).isNotEmpty()
    }

    fun saveAppRefreshRule(context: Context, rule: AppRefreshRule) {
        val normalized = rule.copy(
            packageName = rule.packageName.trim(),
            maxHz = rule.maxHz.coerceIn(MIN_REFRESH_HZ, MAX_REFRESH_HZ),
            minHz = rule.minHz.coerceIn(1, rule.maxHz.coerceIn(MIN_REFRESH_HZ, MAX_REFRESH_HZ))
        )
        if (normalized.packageName.isBlank()) return
        val rules = appRefreshRules(context)
            .filterNot { it.packageName == normalized.packageName }
            .plus(normalized)
            .sortedBy { it.packageName }
        prefs(context).edit().putString(KEY_APP_REFRESH_RULES, encodeAppRefreshRules(rules)).commit()
    }

    fun removeAppRefreshRule(context: Context, packageName: String) {
        val rules = appRefreshRules(context).filterNot { it.packageName == packageName.trim() }
        if (rules.isEmpty()) {
            prefs(context).edit().remove(KEY_APP_REFRESH_RULES).commit()
        } else {
            prefs(context).edit().putString(KEY_APP_REFRESH_RULES, encodeAppRefreshRules(rules)).commit()
        }
    }

    fun clearAppRefreshRules(context: Context) {
        prefs(context).edit().remove(KEY_APP_REFRESH_RULES).commit()
    }

    fun supportedRefreshRates(context: Context): List<Int> {
        val raw = prefs(context).getString(KEY_SUPPORTED_REFRESH_RATES, "") ?: ""
        return raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in MIN_REFRESH_HZ..MAX_REFRESH_HZ }
            .distinct()
            .sorted()
    }

    fun setSupportedRefreshRates(context: Context, rates: List<Int>) {
        val normalized = rates.filter { it in MIN_REFRESH_HZ..MAX_REFRESH_HZ }.distinct().sorted()
        prefs(context).edit()
            .putString(KEY_SUPPORTED_REFRESH_RATES, normalized.joinToString(","))
            .putBoolean(KEY_SUPPORTED_REFRESH_RATES_DETECTED, true)
            .apply()
    }

    fun supportedRefreshRatesDetected(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SUPPORTED_REFRESH_RATES_DETECTED, false)
    }

    fun setSupportedRefreshRatesDetected(context: Context, detected: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SUPPORTED_REFRESH_RATES_DETECTED, detected)
            .apply()
    }

    fun nightWakeupGuardPackages(context: Context): List<String> {
        removeLegacyNightWakeupGuardDefaults(context)
        val raw = prefs(context).getString(KEY_NIGHT_WAKEUP_GUARD_PACKAGES, "") ?: ""
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.matches(PACKAGE_NAME_PATTERN) }
            .distinct()
            .toList()
    }

    fun addNightWakeupGuardPackage(context: Context, packageName: String) {
        val normalized = packageName.trim()
        if (!normalized.matches(PACKAGE_NAME_PATTERN)) return
        val packages = nightWakeupGuardPackages(context)
            .plus(normalized)
            .distinct()
            .sorted()
        prefs(context).edit()
            .putString(KEY_NIGHT_WAKEUP_GUARD_PACKAGES, packages.joinToString("\n"))
            .apply()
    }

    fun removeNightWakeupGuardPackage(context: Context, packageName: String) {
        val packages = nightWakeupGuardPackages(context).filterNot { it == packageName.trim() }
        prefs(context).edit()
            .putString(KEY_NIGHT_WAKEUP_GUARD_PACKAGES, packages.joinToString("\n"))
            .apply()
    }

    fun setNightWakeupGuardPackages(context: Context, packages: Collection<String>) {
        val normalized = packages.map { it.trim() }
            .filter { it.matches(PACKAGE_NAME_PATTERN) }
            .distinct()
            .sorted()
        prefs(context).edit()
            .putString(KEY_NIGHT_WAKEUP_GUARD_PACKAGES, normalized.joinToString("\n"))
            .apply()
    }

    fun clearNightWakeupGuardPackages(context: Context) {
        prefs(context).edit()
            .remove(KEY_NIGHT_WAKEUP_GUARD_PACKAGES)
            .apply()
    }

    fun nightWakeupGuardAppliedPackages(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_NIGHT_WAKEUP_GUARD_APPLIED_PACKAGES, "") ?: ""
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.matches(PACKAGE_NAME_PATTERN) }
            .distinct()
            .toList()
    }

    fun addNightWakeupGuardAppliedPackages(context: Context, packages: List<String>) {
        val normalized = nightWakeupGuardAppliedPackages(context)
            .plus(packages.map { it.trim() })
            .filter { it.matches(PACKAGE_NAME_PATTERN) }
            .distinct()
            .sorted()
        prefs(context).edit()
            .putString(KEY_NIGHT_WAKEUP_GUARD_APPLIED_PACKAGES, normalized.joinToString("\n"))
            .apply()
    }

    fun clearNightWakeupGuardAppliedPackages(context: Context) {
        prefs(context).edit()
            .remove(KEY_NIGHT_WAKEUP_GUARD_APPLIED_PACKAGES)
            .apply()
    }

    fun removeNightWakeupGuardAppliedPackage(context: Context, packageName: String) {
        val packages = nightWakeupGuardAppliedPackages(context).filterNot { it == packageName.trim() }
        prefs(context).edit()
            .putString(KEY_NIGHT_WAKEUP_GUARD_APPLIED_PACKAGES, packages.joinToString("\n"))
            .apply()
    }

    fun nightWakeupGuardSuggestions(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_NIGHT_WAKEUP_GUARD_SUGGESTIONS, "") ?: ""
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.matches(PACKAGE_NAME_PATTERN) }
            .distinct()
            .toList()
    }

    fun setNightWakeupGuardSuggestions(context: Context, packages: List<String>) {
        val normalized = packages
            .map { it.trim() }
            .filter { it.matches(PACKAGE_NAME_PATTERN) }
            .distinct()
        prefs(context).edit()
            .putString(KEY_NIGHT_WAKEUP_GUARD_SUGGESTIONS, normalized.joinToString("\n"))
            .apply()
    }

    fun nightWakeupGuardSuggestionDetails(context: Context): List<NightWakeupSuggestion> {
        val raw = prefs(context).getString(KEY_NIGHT_WAKEUP_GUARD_SUGGESTION_DETAILS, "") ?: ""
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                val packageName = parts.getOrNull(0)?.trim().orEmpty()
                if (!packageName.matches(PACKAGE_NAME_PATTERN)) return@mapNotNull null
                NightWakeupSuggestion(
                    packageName = packageName,
                    detail = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: "扫描建议候选"
                )
            }
            .distinctBy { it.packageName }
            .toList()
    }

    fun setNightWakeupGuardSuggestionDetails(context: Context, suggestions: List<NightWakeupSuggestion>) {
        val normalized = suggestions
            .map {
                NightWakeupSuggestion(
                    packageName = it.packageName.trim(),
                    detail = it.detail.replace('\t', ' ').replace('\n', ' ').trim()
                )
            }
            .filter { it.packageName.matches(PACKAGE_NAME_PATTERN) }
            .distinctBy { it.packageName }
        prefs(context).edit()
            .putString(KEY_NIGHT_WAKEUP_GUARD_SUGGESTIONS, normalized.joinToString("\n") { it.packageName })
            .putString(KEY_NIGHT_WAKEUP_GUARD_SUGGESTION_DETAILS, normalized.joinToString("\n") { "${it.packageName}\t${it.detail}" })
            .apply()
    }

    fun dozeRestrictedPackages(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_DOZE_RESTRICTED_PACKAGES, "") ?: ""
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.matches(PACKAGE_NAME_PATTERN) }
            .toSet()
    }

    fun setDozePackageRestricted(context: Context, packageName: String, restricted: Boolean) {
        val current = dozeRestrictedPackages(context).toMutableSet()
        val pkg = packageName.trim()
        if (restricted) {
            current.add(pkg)
        } else {
            current.remove(pkg)
        }
        prefs(context).edit()
            .putString(KEY_DOZE_RESTRICTED_PACKAGES, current.joinToString("\n"))
            .apply()
    }

    fun mMainMode(context: Context): String = prefs(context).getString(KEY_M_MAIN_MODE, "off") ?: "off"
    fun setMMainMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_M_MAIN_MODE, mode).apply()
    }

    fun mGpuMode(context: Context): String = prefs(context).getString(KEY_M_GPU_MODE, "off") ?: "off"
    fun setMGpuMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_M_GPU_MODE, mode).apply()
    }

    fun mRefresh120Enabled(context: Context) = prefs(context).getBoolean(KEY_M_REFRESH_120_ENABLED, false)
    fun setMRefresh120Enabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_REFRESH_120_ENABLED, enabled).apply()
    }

    fun mVulkanEnabled(context: Context) = prefs(context).getBoolean(KEY_M_VULKAN_ENABLED, false)
    fun setMVulkanEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_VULKAN_ENABLED, enabled).apply()
    }

    fun mNetworkEnabled(context: Context) = prefs(context).getBoolean(KEY_M_NETWORK_ENABLED, false)
    fun setMNetworkEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_NETWORK_ENABLED, enabled).apply()
    }

    fun mTouchEnabled(context: Context) = prefs(context).getBoolean(KEY_M_TOUCH_ENABLED, false)
    fun setMTouchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_TOUCH_ENABLED, enabled).apply()
    }

    /** @deprecated 语义含混的保护/放宽共用位，保留只为读旧数据，新代码请用下面两个 */
    fun mThermalEnabled(context: Context) = prefs(context).getBoolean(KEY_M_THERMAL_ENABLED, false)
    fun setMThermalEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_THERMAL_ENABLED, enabled).apply()
    }

    /** 官方安全温控（保护方向）：防止设备过热 */
    fun mThermalProtect(context: Context) = prefs(context).getBoolean(KEY_M_THERMAL_PROTECT, false)
    fun setMThermalProtect(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_THERMAL_PROTECT, enabled).apply()
    }

    /** 放宽温控（性能方向）：极致性能开启时由它记录，与 [mThermalProtect] 互斥 */
    fun mThermalRelaxed(context: Context) = prefs(context).getBoolean(KEY_M_THERMAL_RELAXED, false)
    fun setMThermalRelaxed(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_THERMAL_RELAXED, enabled).apply()
    }

    /** 极致性能模式：一键开关自己的状态位 */
    fun superPerformanceEnabled(context: Context) =
        prefs(context).getBoolean(KEY_SUPER_PERFORMANCE_ENABLED, false)

    fun setSuperPerformanceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUPER_PERFORMANCE_ENABLED, enabled).apply()
    }

    /**
     * 极致性能状态的**唯一写入口**。
     *
     * 一级页一键开关、二级页一键满血、游戏模式三条路径都必须走这里。
     *
     * 以前游戏路径（`markGameAutoEntryApplied` / `markGameAutoExitApplied`）只写
     * 5 个子项、漏写 [KEY_SUPER_PERFORMANCE_ENABLED]，于是两个方向都会错：
     *   - 进游戏（极限档）后系统已是极致性能态，一级页开关却显示「关闭」；
     *     用户去关它，执行的是全套 restore，把游戏刚设好的性能状态全撤销。
     *   - 手动开极致性能 → 进游戏 → 退出游戏，子项被全写成 off，总开关却
     *     仍是 true → 开关显示「已开启」而系统实际已全部关闭，纯假亮。
     *
     * 收敛到一个函数后，总开关位与子项位必然同进同退，这类错配不可能再发生。
     *
     * @param thermalRelaxed 是否同时放宽温控。默认为 [on]，即开极致就放宽。
     *   游戏模式下由独立的「进游戏时放宽温控」开关控制，默认关 —— 放宽温控会
     *   明显发热，不该是默认行为。
     */
    fun markSuperPerformanceState(context: Context, on: Boolean, thermalRelaxed: Boolean = on) {
        setSuperPerformanceEnabled(context, on)
        setMMainMode(context, if (on) "performance" else "off")
        setMGpuMode(context, if (on) "performance" else "off")
        setMVulkanEnabled(context, on)
        setMRefresh120Enabled(context, on)
        // 温控方向分 protect / relaxed 两个位，绝不能混写：把它们写反会让
        // 「系统温控保护」显示为开启、系统实际却在放宽温控，是会真发热的语义反转。
        setMThermalRelaxed(context, on && thermalRelaxed)
        if (on && thermalRelaxed) setMThermalProtect(context, false)
        // 关闭时温控的 relaxed 方向必须一起复位，否则会出现
        // 「已开启 + 温控已恢复」这种自相矛盾的显示。
        if (!on) setMThermalRelaxed(context, false)
    }

    fun gameAutoSessionActive(context: Context) =
        prefs(context).getBoolean(KEY_GAME_AUTO_SESSION_ACTIVE, false)

    fun setGameAutoSessionActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_GAME_AUTO_SESSION_ACTIVE, active).apply()
    }

    /**
     * 进入游戏前拍一份状态快照。
     *
     * 退出游戏时按它还原，所以退出后的行为完全可预测：**回到你进游戏前的样子**。
     *
     * 以前的退出策略是三选一（日用省电 / 系统默认 / 保持当前），三个都有问题：
     *   - 「系统默认」会清掉用户手动开的极致性能（这就是上面说的假亮 bug）
     *   - 「日用省电」会无条件开省电，不管用户进游戏前开没开
     *   - 「保持当前」什么都不做，系统一直停在极致性能态默默耗电
     *
     * 快照方案是三者的超集，而且不需要用户做任何选择。
     */
    fun captureGameAutoSnapshot(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_GAME_AUTO_SNAP_SUPER, superPerformanceEnabled(context))
            .putBoolean(KEY_GAME_AUTO_SNAP_SAVER, compatibleSaverEnabled(context))
            .putString(
                KEY_GAME_AUTO_SNAP_THERMAL,
                when {
                    mThermalProtect(context) -> "protect"
                    mThermalRelaxed(context) -> "relaxed"
                    else -> ""
                }
            )
            .putBoolean(KEY_GAME_AUTO_SNAP_VALID, true)
            .apply()
    }

    /** 快照内容。没有有效快照时返回 null，此时退出游戏只能回系统默认。 */
    fun gameAutoSnapshot(context: Context): GameAutoSnapshot? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_GAME_AUTO_SNAP_VALID, false)) return null
        return GameAutoSnapshot(
            superPerformance = p.getBoolean(KEY_GAME_AUTO_SNAP_SUPER, false),
            saver = p.getBoolean(KEY_GAME_AUTO_SNAP_SAVER, false),
            thermal = p.getString(KEY_GAME_AUTO_SNAP_THERMAL, "") ?: ""
        )
    }

    fun clearGameAutoSnapshot(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_GAME_AUTO_SNAP_VALID, false)
            .remove(KEY_GAME_AUTO_SNAP_SUPER)
            .remove(KEY_GAME_AUTO_SNAP_SAVER)
            .remove(KEY_GAME_AUTO_SNAP_THERMAL)
            .apply()
    }

    /** 进入游戏前的状态快照 */
    data class GameAutoSnapshot(
        val superPerformance: Boolean,
        val saver: Boolean,
        /** "protect" / "relaxed" / ""（未设置温控） */
        val thermal: String
    )

    fun mTombstoneEnabled(context: Context) = prefs(context).getBoolean(KEY_M_TOMBSTONE_ENABLED, false)
    fun setMTombstoneEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_TOMBSTONE_ENABLED, enabled).apply()
    }

    fun mGameVideoSpeedEnabled(context: Context) = prefs(context).getBoolean(KEY_M_GAME_VIDEO_SPEED_ENABLED, false)
    fun setMGameVideoSpeedEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_GAME_VIDEO_SPEED_ENABLED, enabled).apply()
    }

    fun mCpuResponseEnabled(context: Context) = prefs(context).getBoolean(KEY_M_CPU_RESPONSE_ENABLED, false)
    fun setMCpuResponseEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_CPU_RESPONSE_ENABLED, enabled).apply()
    }

    fun mLocationThrottleEnabled(context: Context) = prefs(context).getBoolean(KEY_M_LOCATION_THROTTLE_ENABLED, false)
    fun setMLocationThrottleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_LOCATION_THROTTLE_ENABLED, enabled).apply()
    }

    fun mAutostartLimitEnabled(context: Context) = prefs(context).getBoolean(KEY_M_AUTOSTART_LIMIT_ENABLED, false)
    fun setMAutostartLimitEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_AUTOSTART_LIMIT_ENABLED, enabled).apply()
    }

    fun mZramEnabled(context: Context) = prefs(context).getBoolean(KEY_M_ZRAM_ENABLED, false)
    fun setMZramEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_M_ZRAM_ENABLED, enabled).apply()
    }

    fun mDexoptEnabled(context: Context) = prefs(context).getBoolean(KEY_M_DEXOPT_ENABLED, false)
    fun setMDexoptEnabled(context: Context, enabled: Boolean) {
        val editor = prefs(context).edit().putBoolean(KEY_M_DEXOPT_ENABLED, enabled)
        if (!enabled) {
            editor.remove("m_dexopt_compiled_map")
        }
        editor.apply()
    }

    fun mDexoptCompiledMap(context: Context): Map<String, String> {
        val raw = prefs(context).getString("m_dexopt_compiled_map", "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        return raw.split(";")
            .mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    parts[0].trim() to parts[1].trim()
                } else null
            }
            .toMap()
    }

    fun recordMDexoptCompiled(context: Context, packages: Collection<String>, mode: String) {
        val mutable = mDexoptCompiledMap(context).toMutableMap()
        if (mode == "reset") {
            packages.forEach { mutable.remove(it) }
        } else {
            packages.forEach { mutable[it] = mode }
        }
        val serialized = mutable.entries.joinToString(";") { "${it.key}=${it.value}" }
        prefs(context).edit().putString("m_dexopt_compiled_map", serialized).apply()
    }

    fun gameAutoPerformanceEnabled(context: Context) = prefs(context).getBoolean(KEY_GAME_AUTO_PERFORMANCE_ENABLED, false)
    fun setGameAutoPerformanceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GAME_AUTO_PERFORMANCE_ENABLED, enabled).apply()
    }

    fun gameAutoPerformancePackages(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_GAME_AUTO_PERFORMANCE_PACKAGES, emptySet()).orEmpty()
            .filter { it.matches(PACKAGE_NAME_PATTERN) }
            .toSet()
    }

    fun setGameAutoPerformancePackages(context: Context, packages: Set<String>) {
        val normalized = packages
            .map { it.trim() }
            .filter { it.matches(PACKAGE_NAME_PATTERN) }
            .toSet()
        val prunedProfiles = gameAutoPackageProfiles(context)
            .filterKeys { it in normalized }
        prefs(context).edit()
            .putStringSet(KEY_GAME_AUTO_PERFORMANCE_PACKAGES, normalized)
            .putString(KEY_GAME_AUTO_PACKAGE_PROFILES, serializeGamePackageProfiles(prunedProfiles))
            .apply()
    }

    fun addGameAutoPerformancePackage(context: Context, packageName: String) {
        val normalized = packageName.trim()
        if (!normalized.matches(PACKAGE_NAME_PATTERN)) return
        setGameAutoPerformancePackages(context, gameAutoPerformancePackages(context) + normalized)
    }

    fun removeGameAutoPerformancePackage(context: Context, packageName: String) {
        val normalized = packageName.trim()
        setGameAutoPerformancePackages(context, gameAutoPerformancePackages(context) - normalized)
    }

    private fun isValidGameProfile(profile: String?): Boolean {
        return profile == GAME_PROFILE_DEFAULT ||
            profile == GAME_PROFILE_SAVER ||
            profile == GAME_PROFILE_PERFORMANCE
    }

    private fun serializeGamePackageProfiles(profiles: Map<String, String>): String {
        return profiles.entries
            .filter { (pkg, profile) -> pkg.matches(PACKAGE_NAME_PATTERN) && isValidGameProfile(profile) }
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value}" }
    }

    fun gameAutoPackageProfiles(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_GAME_AUTO_PACKAGE_PROFILES, "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (entry in raw.split(';')) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            val sep = trimmed.indexOf('=')
            if (sep <= 0 || sep >= trimmed.lastIndex) continue
            val pkg = trimmed.substring(0, sep).trim()
            val profile = trimmed.substring(sep + 1).trim()
            if (pkg.matches(PACKAGE_NAME_PATTERN) && isValidGameProfile(profile)) {
                result[pkg] = profile
            }
        }
        return result
    }

    /**
     * 获取指定游戏包名单独配置的专属模式；若未单独指定则返回 null（表示跟随全局默认模式）。
     */
    fun gamePackageCustomProfile(context: Context, packageName: String?): String? {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return gameAutoPackageProfiles(context)[pkg]
    }

    /**
     * 获取指定游戏包名最终生效的目标模式：优先使用单游戏专属档位，否则回退到全局默认档位。
     */
    fun gamePackageTargetProfile(context: Context, packageName: String?): String {
        return gamePackageCustomProfile(context, packageName) ?: gameAutoTargetProfile(context)
    }

    /**
     * 设置或清除指定游戏包名的专属目标模式（传 null 表示恢复跟随全局默认）。
     */
    fun setGamePackageCustomProfile(context: Context, packageName: String, profile: String?) {
        val normalizedPkg = packageName.trim()
        if (!normalizedPkg.matches(PACKAGE_NAME_PATTERN)) return
        val current = gameAutoPackageProfiles(context).toMutableMap()
        if (profile == null || !isValidGameProfile(profile)) {
            current.remove(normalizedPkg)
        } else {
            current[normalizedPkg] = profile
        }
        prefs(context).edit()
            .putString(KEY_GAME_AUTO_PACKAGE_PROFILES, serializeGamePackageProfiles(current))
            .apply()
    }

    /**
     * 进入游戏时是否放宽温控。
     *
     * 取代了原来的三档档位（均衡 / 高性能 / 极限）。「极致」的定义已经是
     * 「所有东西调到最强最快、不在乎功耗」，中间的均衡/高性能两档没有存在意义，
     * 而且「均衡」档照样执行 CPU 提频和强制 120Hz，名字与行为不符。
     *
     * 放宽温控是唯一真正需要用户决策的维度 —— 它会让手机明显变烫，
     * 有人打游戏就是不想让手机烫手。默认 false：放宽温控不是默认行为。
     */
    fun gameAutoThermalRelaxed(context: Context): Boolean {
        val p = prefs(context)
        // 旧档位迁移：只有「极限」档执行过温控放宽，它映射为 true，其余为 false。
        // 迁移只做一次 —— 写入新 key 后就再也不读旧值了。
        if (!p.contains(KEY_GAME_AUTO_THERMAL_RELAXED)) {
            val migrated = p.getString(KEY_GAME_AUTO_PERFORMANCE_PROFILE, "high") == "extreme"
            p.edit().putBoolean(KEY_GAME_AUTO_THERMAL_RELAXED, migrated).apply()
            return migrated
        }
        return p.getBoolean(KEY_GAME_AUTO_THERMAL_RELAXED, false)
    }

    fun setGameAutoThermalRelaxed(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GAME_AUTO_THERMAL_RELAXED, enabled).apply()
    }

    /**
     * 进入游戏时的目标配置模式：
     * - [GAME_PROFILE_DEFAULT]: 系统默认
     * - [GAME_PROFILE_SAVER]: 省电自适应高刷
     * - [GAME_PROFILE_PERFORMANCE]: 游戏性能模式
     */
    fun gameAutoTargetProfile(context: Context): String {
        return when (val v = prefs(context).getString(KEY_GAME_AUTO_TARGET_PROFILE, GAME_PROFILE_PERFORMANCE)) {
            GAME_PROFILE_DEFAULT, GAME_PROFILE_SAVER, GAME_PROFILE_PERFORMANCE -> v
            else -> GAME_PROFILE_PERFORMANCE
        }
    }

    fun setGameAutoTargetProfile(context: Context, profile: String) {
        val normalized = when (profile) {
            GAME_PROFILE_DEFAULT, GAME_PROFILE_SAVER, GAME_PROFILE_PERFORMANCE -> profile
            else -> GAME_PROFILE_PERFORMANCE
        }
        prefs(context).edit().putString(KEY_GAME_AUTO_TARGET_PROFILE, normalized).apply()
    }

    fun gameTileMode(context: Context): Int {
        return prefs(context).getInt(KEY_GAME_TILE_MODE, 0)
    }

    fun setGameTileMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_GAME_TILE_MODE, mode).apply()
    }

    fun gameAutoPerformanceExitDelaySeconds(context: Context): Int {
        return when (val value = prefs(context).getInt(KEY_GAME_AUTO_PERFORMANCE_EXIT_DELAY_SECONDS, 60)) {
            30, 60, 120 -> value
            else -> 60
        }
    }

    fun setGameAutoPerformanceExitDelaySeconds(context: Context, seconds: Int) {
        val normalized = when (seconds) {
            30, 60, 120 -> seconds
            else -> 60
        }
        prefs(context).edit().putInt(KEY_GAME_AUTO_PERFORMANCE_EXIT_DELAY_SECONDS, normalized).apply()
    }

    fun gameAutoActivePackage(context: Context): String? {
        return prefs(context).getString(KEY_GAME_AUTO_ACTIVE_PACKAGE, null)
            ?.takeIf { it.matches(PACKAGE_NAME_PATTERN) }
    }

    /**
     * 当前游戏会话生效的档位标识（default / saver / performance）。
     */
    fun gameAutoActiveProfile(context: Context): String {
        val activePkg = gameAutoActivePackage(context)
        val saved = prefs(context).getString(KEY_GAME_AUTO_ACTIVE_PROFILE, null)
        return when (saved) {
            GAME_PROFILE_DEFAULT, GAME_PROFILE_SAVER, GAME_PROFILE_PERFORMANCE -> saved
            else -> gamePackageTargetProfile(context, activePkg)
        }
    }

    fun setGameAutoActiveSession(
        context: Context,
        packageName: String,
        profile: String = gamePackageTargetProfile(context, packageName)
    ) {
        val safePackage = packageName.takeIf { it.matches(PACKAGE_NAME_PATTERN) } ?: return
        val normalizedProfile = if (isValidGameProfile(profile)) profile else gamePackageTargetProfile(context, safePackage)
        prefs(context).edit()
            .putString(KEY_GAME_AUTO_ACTIVE_PACKAGE, safePackage)
            .putString(KEY_GAME_AUTO_ACTIVE_PROFILE, normalizedProfile)
            .apply()
    }

    fun clearGameAutoActiveSession(context: Context) {
        prefs(context).edit()
            .remove(KEY_GAME_AUTO_ACTIVE_PACKAGE)
            .remove(KEY_GAME_AUTO_ACTIVE_PROFILE)
            .apply()
    }

    fun clearMFeatureStates(context: Context) {
        prefs(context).edit()
            .putString(KEY_M_MAIN_MODE, "off")
            .putString(KEY_M_GPU_MODE, "off")
            .putBoolean(KEY_M_REFRESH_120_ENABLED, false)
            .putBoolean(KEY_M_VULKAN_ENABLED, false)
            .putBoolean(KEY_M_NETWORK_ENABLED, false)
            .putBoolean(KEY_M_TOUCH_ENABLED, false)
            .putBoolean(KEY_M_THERMAL_ENABLED, false)
            .putBoolean(KEY_M_THERMAL_PROTECT, false)
            .putBoolean(KEY_M_THERMAL_RELAXED, false)
            .putBoolean(KEY_SUPER_PERFORMANCE_ENABLED, false)
            .putBoolean(KEY_M_TOMBSTONE_ENABLED, false)
            .putBoolean(KEY_M_GAME_VIDEO_SPEED_ENABLED, false)
            .putBoolean(KEY_M_CPU_RESPONSE_ENABLED, false)
            .putBoolean(KEY_M_LOCATION_THROTTLE_ENABLED, false)
            .putBoolean(KEY_M_AUTOSTART_LIMIT_ENABLED, false)
            .putBoolean(KEY_M_ZRAM_ENABLED, false)
            .putBoolean(KEY_M_DEXOPT_ENABLED, false)
            .putBoolean(KEY_GAME_AUTO_PERFORMANCE_ENABLED, false)
            .putBoolean(KEY_GAME_AUTO_SESSION_ACTIVE, false)
            .putBoolean(KEY_GAME_AUTO_SNAP_VALID, false)
            .remove(KEY_GAME_AUTO_SNAP_SUPER)
            .remove(KEY_GAME_AUTO_SNAP_SAVER)
            .remove(KEY_GAME_AUTO_SNAP_THERMAL)
            .remove(KEY_GAME_AUTO_ACTIVE_PACKAGE)
            .remove(KEY_GAME_AUTO_ACTIVE_PROFILE)
            .apply()
    }

    fun resultText(context: Context, key: String): String? {
        return prefs(context).getString(KEY_RESULT_TEXT_PREFIX + key, null)
    }

    fun resultCollapsed(context: Context, key: String): Boolean {
        return prefs(context).getBoolean(KEY_RESULT_COLLAPSED_PREFIX + key, true)
    }

    fun saveResult(context: Context, key: String, text: String, collapsed: Boolean) {
        prefs(context).edit()
            .putString(KEY_RESULT_TEXT_PREFIX + key, text)
            .putBoolean(KEY_RESULT_COLLAPSED_PREFIX + key, collapsed)
            .apply()
    }

    fun clearResult(context: Context, key: String) {
        prefs(context).edit()
            .remove(KEY_RESULT_TEXT_PREFIX + key)
            .remove(KEY_RESULT_COLLAPSED_PREFIX + key)
            .apply()
    }

    fun setResultCollapsed(context: Context, key: String, collapsed: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_RESULT_COLLAPSED_PREFIX + key, collapsed)
            .apply()
    }

    private fun removeLegacyNightWakeupGuardDefaults(context: Context) {
        val preferences = prefs(context)
        if (preferences.getBoolean(KEY_NIGHT_WAKEUP_GUARD_LEGACY_DEFAULTS_REMOVED, false)) return
        val packages = (preferences.getString(KEY_NIGHT_WAKEUP_GUARD_PACKAGES, "") ?: "")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it in LEGACY_NIGHT_WAKEUP_GUARD_DEFAULTS }
            .distinct()
            .sorted()
            .toList()
        preferences.edit()
            .putString(KEY_NIGHT_WAKEUP_GUARD_PACKAGES, packages.joinToString("\n"))
            .putBoolean(KEY_NIGHT_WAKEUP_GUARD_LEGACY_DEFAULTS_REMOVED, true)
            .apply()
    }

    private fun encodeAppRefreshRules(rules: List<AppRefreshRule>): String {
        return rules.joinToString("\n") {
            listOf(
                it.packageName,
                if (it.adaptive) "adaptive" else "static",
                it.maxHz.toString(),
                it.minHz.toString()
            ).joinToString("\t")
        }
    }

    fun requiresBackgroundService(context: Context): Boolean {
        return controlEnabled(context) ||
            gameAutoPerformanceEnabled(context) ||
            nightWakeupGuardEnabled(context) ||
            batteryProtectionEnabled(context)
    }
}
