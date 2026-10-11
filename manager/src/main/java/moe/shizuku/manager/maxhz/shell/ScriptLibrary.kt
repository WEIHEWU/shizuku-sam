package moe.shizuku.manager.maxhz.shell

/**
 * MaxHz Shell 脚本公共基础库。
 *
 * 统一存储公共 Shell 函数段，消除各脚本构建模块之间的重复代码。
 */
object ScriptLibrary {

    // -----------------------------------------------------------------------
    // 1. 刷新率检测 Prelude
    // -----------------------------------------------------------------------
    val REFRESH_RATE_DETECT: String = """
detect_refresh_rates() {
  rates="${'$'}(
    dumpsys display 2>/dev/null \
      | grep -m 1 'supportedRefreshRates' \
      | sed -n 's/.*supportedRefreshRates \[\([^]]*\)\].*/\1/p' \
      | tr ',' '\n' \
      | sed 's/^ *//; s/ *${'$'}//' \
      | cut -d. -f1 \
      | grep -E '^[0-9]+${'$'}' \
      | sort -n \
      | uniq
  )"
  if [ -z "${'$'}rates" ]; then
    rates="${'$'}(
      dumpsys display 2>/dev/null \
        | grep -o 'fps=[0-9.]*' \
        | cut -d= -f2 \
        | cut -d. -f1 \
        | grep -E '^[0-9]+${'$'}' \
        | sort -n \
        | uniq
    )"
  fi
  if [ -z "${'$'}rates" ]; then
    rates="${'$'}(
      dumpsys display 2>/dev/null \
        | grep -o 'peakRefreshRate=[0-9.]*' \
        | cut -d= -f2 \
        | cut -d. -f1 \
        | grep -E '^[0-9]+${'$'}' \
        | sort -n \
        | uniq
    )"
  fi
  [ -z "${'$'}rates" ] && rates="60
120"

  min_hz="${'$'}(echo "${'$'}rates" | head -n 1)"
  max_hz="${'$'}(echo "${'$'}rates" | tail -n 1)"

  [ -z "${'$'}min_hz" ] && min_hz=60
  [ -z "${'$'}max_hz" ] && max_hz=60
  supported_hz="${'$'}(echo "${'$'}rates" | tr '\n' ' ' | sed 's/ *${'$'}//')"
}

refresh_policy_level() {
  expected_max="${'$'}1"
  specs="${'$'}(dumpsys display 2>/dev/null | grep -m 1 -E 'mDisplayModeSpecs=|DesiredDisplayModeSpecs|display mode specs' || true)"
  if [ -z "${'$'}specs" ]; then
    printf 'configured\n'
    return
  fi
  if printf '%s\n' "${'$'}specs" | grep -Eq "(^|[^0-9])${'$'}{expected_max}([.]0+)?([^0-9]|${'$'})"; then
    printf 'effective\n'
  else
    printf 'configured\n'
  fi
}
detect_refresh_rates
""".trimIndent()

    // -----------------------------------------------------------------------
    // 2. Scoped Restore Prelude
    // -----------------------------------------------------------------------
    val SCOPED_RESTORE: String = """
backup_setting() {
  ns="${'$'}1"
  key="${'$'}2"
  backup="maxhz_backup_${'$'}{ns}_${'$'}{key}"
  if [ "${'$'}(settings get global "${'$'}backup")" = "null" ]; then
    value="${'$'}(settings get "${'$'}ns" "${'$'}key")"
    if [ "${'$'}value" = "null" ]; then
      settings put global "${'$'}backup" "__MAXHZ_ABSENT__" >/dev/null 2>&1
    else
      settings put global "${'$'}backup" "${'$'}value" >/dev/null 2>&1
    fi
  fi
}

restore_setting() {
  ns="${'$'}1"
  key="${'$'}2"
  backup="maxhz_backup_${'$'}{ns}_${'$'}{key}"
  value="${'$'}(settings get global "${'$'}backup")"
  [ "${'$'}value" = "null" ] && return
  if [ "${'$'}value" = "__MAXHZ_ABSENT__" ]; then
    settings delete "${'$'}ns" "${'$'}key" >/dev/null 2>&1
  else
    value="${'$'}(normalize_samsung_power_refresh_value "${'$'}key" "${'$'}value")"
    settings put "${'$'}ns" "${'$'}key" "${'$'}value" >/dev/null 2>&1
  fi
  settings delete global "${'$'}backup" >/dev/null 2>&1
}

restore_setting_or_delete() {
  ns="${'$'}1"
  key="${'$'}2"
  backup="maxhz_backup_${'$'}{ns}_${'$'}{key}"
  value="${'$'}(settings get global "${'$'}backup")"
  if [ "${'$'}value" = "null" ]; then
    settings delete "${'$'}ns" "${'$'}key" >/dev/null 2>&1
  else
    restore_setting "${'$'}ns" "${'$'}key"
  fi
}

restore_or_delete_setting() {
  ns="${'$'}1"
  key="${'$'}2"
  backup="maxhz_backup_${'$'}{ns}_${'$'}{key}"
  value="${'$'}(settings get global "${'$'}backup")"
  if [ "${'$'}value" = "null" ]; then
    settings delete "${'$'}ns" "${'$'}key" >/dev/null 2>&1
  else
    restore_setting "${'$'}ns" "${'$'}key"
  fi
}

normalize_samsung_power_refresh_value() {
  key="${'$'}1"
  value="${'$'}2"
  printf '%s\n' "${'$'}value"
}

restore_samsung_power_refresh_defaults() {
  settings delete global pms_settings_refresh_rate_enabled >/dev/null 2>&1
  settings delete global pms_settings_refresh_rate_cover_enabled >/dev/null 2>&1
  settings delete global psm_refresh_rate_tag >/dev/null 2>&1
  settings delete global psm_refresh_rate_cover_tag >/dev/null 2>&1
  settings delete global psm_refresh_rate_tag_power_mode_state >/dev/null 2>&1
  settings delete global sem_power_mode_refresh_rate >/dev/null 2>&1
  settings delete global sem_power_mode_refresh_rate_cover >/dev/null 2>&1
}

restore_samsung_power_state_key() {
  key="${'$'}1"
  backup="maxhz_backup_global_${'$'}key"
  value="${'$'}(settings get global "${'$'}backup" 2>/dev/null)"
  if [ "${'$'}value" = "1" ]; then
    settings put global "${'$'}key" 1 >/dev/null 2>&1 || true
  else
    settings put global "${'$'}key" 0 >/dev/null 2>&1 || true
  fi
  settings delete global "${'$'}backup" >/dev/null 2>&1 || true
}

restore_samsung_power_mode_flags() {
  for key in low_power_mode_opened extra_low_power k_power_mode_state n_power_mode_state q_power_mode_state s_power_mode_state; do
    restore_samsung_power_state_key "${'$'}key"
  done
}

read_power_saver_service_state() {
  if dumpsys power 2>/dev/null | grep -Eqi \
    'mLowPowerModeEnabled=true|mBatterySaverEnabled=true|full_enabled=true|battery[ _-]*saver[^\n]*enabled[=: ]+true'; then
    printf '1\n'
    return
  fi
  if dumpsys power 2>/dev/null | grep -Eqi \
    'mLowPowerModeEnabled=false|mBatterySaverEnabled=false|full_enabled=false|battery[ _-]*saver[^\n]*enabled[=: ]+false'; then
    printf '0\n'
    return
  fi
  [ "${'$'}(settings get global low_power 2>/dev/null)" = "1" ] && printf '1\n' || printf '0\n'
}

set_power_saver_and_verify() {
  expected="${'$'}1"
  if ! cmd power set-mode "${'$'}expected" >/dev/null 2>&1; then
    return 1
  fi
  sleep 0.20
  [ "${'$'}(read_power_saver_service_state)" = "${'$'}expected" ] && return 0
  settings put global low_power "${'$'}expected" >/dev/null 2>&1 || return 1
  sleep 0.35
  [ "${'$'}(read_power_saver_service_state)" = "${'$'}expected" ]
}

release_refresh_control() {
  settings delete secure refresh_rate >/dev/null 2>&1
  settings delete secure user_refresh_rate >/dev/null 2>&1
  settings delete system display_refresh_rate >/dev/null 2>&1
  settings delete system peak_refresh_rate >/dev/null 2>&1
  settings delete system user_refresh_rate >/dev/null 2>&1
  settings delete system min_refresh_rate >/dev/null 2>&1
  settings delete system display_min_refresh_rate >/dev/null 2>&1
  settings delete global max_fps >/dev/null 2>&1
  settings delete global min_fps >/dev/null 2>&1
  restore_samsung_power_refresh_defaults
}

backup_compatible_saver() {
  for key in low_power low_power_mode low_power_sticky battery_saver_enabled low_power_mode_opened extra_low_power k_power_mode_state n_power_mode_state q_power_mode_state s_power_mode_state restricted_device_performance sem_low_heat_mode default_restrict_background_data low_power_back_data_off psm_network_power_saving sem_power_mode_limited_apps_and_home_screen pms_settings_refresh_rate_enabled pms_settings_refresh_rate_cover_enabled psm_refresh_rate_tag psm_refresh_rate_cover_tag psm_refresh_rate_tag_power_mode_state sem_power_mode_refresh_rate sem_power_mode_refresh_rate_cover max_fps min_fps; do
    backup_setting global "${'$'}key"
  done
  for key in refresh_rate_mode refresh_rate user_refresh_rate pms_override_refresh_rate_mode; do
    backup_setting secure "${'$'}key"
  done
  for key in POWER_SAVE_MODE_OPEN display_refresh_rate peak_refresh_rate user_refresh_rate min_refresh_rate display_min_refresh_rate; do
    backup_setting system "${'$'}key"
  done
}

restore_compatible_saver() {
  backup_seen=0
  backup_saver_on=0
  restored_power_saver_expected=""
  restored_power_saver_verified=1
  refresh_mode_backup="${'$'}(settings get global maxhz_backup_secure_refresh_rate_mode 2>/dev/null)"
  for key in low_power low_power_mode low_power_sticky battery_saver_enabled; do
    backup_value="${'$'}(settings get global "maxhz_backup_global_${'$'}key" 2>/dev/null)"
    if [ "${'$'}backup_value" != "null" ]; then
      backup_seen=1
      [ "${'$'}backup_value" = "1" ] && backup_saver_on=1
    fi
  done
  cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1
  for key in low_power low_power_mode low_power_sticky battery_saver_enabled restricted_device_performance sem_low_heat_mode default_restrict_background_data low_power_back_data_off psm_network_power_saving sem_power_mode_limited_apps_and_home_screen pms_settings_refresh_rate_enabled pms_settings_refresh_rate_cover_enabled psm_refresh_rate_tag psm_refresh_rate_cover_tag psm_refresh_rate_tag_power_mode_state sem_power_mode_refresh_rate sem_power_mode_refresh_rate_cover max_fps min_fps; do
    restore_or_delete_setting global "${'$'}key"
  done
  restore_samsung_power_mode_flags
  for key in refresh_rate_mode refresh_rate user_refresh_rate pms_override_refresh_rate_mode; do
    restore_or_delete_setting secure "${'$'}key"
  done
  for key in POWER_SAVE_MODE_OPEN display_refresh_rate peak_refresh_rate user_refresh_rate min_refresh_rate display_min_refresh_rate; do
    restore_or_delete_setting system "${'$'}key"
  done
  if [ "${'$'}backup_seen" = "1" ]; then
    if [ "${'$'}backup_saver_on" = "1" ]; then
      restored_power_saver_expected=1
    else
      restored_power_saver_expected=0
    fi
    set_power_saver_and_verify "${'$'}restored_power_saver_expected" || restored_power_saver_verified=0
  fi
  nudge_adaptive_refresh_if_needed "${'$'}refresh_mode_backup"
}
""".trimIndent()

    // -----------------------------------------------------------------------
    // 3. Quick Doze Backup Prelude
    // -----------------------------------------------------------------------
    val QUICK_DOZE_BACKUP: String = """
backup_doze_whitelist() {
  for pkg in com.tencent.mm com.tencent.mobileqq com.tencent.tim com.tencent.wework com.alibaba.android.rimet com.google.android.gms; do
    safe="${'$'}(echo "${'$'}pkg" | tr '.-' '__')"
    backup="maxhz_backup_doze_whitelist_${'$'}safe"
    if [ "${'$'}(settings get global "${'$'}backup")" = "null" ]; then
      if dumpsys deviceidle whitelist 2>/dev/null | grep -Fq "${'$'}pkg"; then
        settings put global "${'$'}backup" 1 >/dev/null 2>&1
      else
        settings put global "${'$'}backup" 0 >/dev/null 2>&1
      fi
    fi
  done
}

restore_doze_whitelist() {
  for pkg in com.tencent.mm com.tencent.mobileqq com.tencent.tim com.tencent.wework com.alibaba.android.rimet com.google.android.gms; do
    safe="${'$'}(echo "${'$'}pkg" | tr '.-' '__')"
    backup="maxhz_backup_doze_whitelist_${'$'}safe"
    value="${'$'}(settings get global "${'$'}backup")"
    if [ "${'$'}value" = "1" ]; then
      dumpsys deviceidle whitelist +"${'$'}pkg" >/dev/null 2>&1 || true
    fi
    settings delete global "${'$'}backup" >/dev/null 2>&1
  done
}

backup_quick_doze() {
  backup_setting global doze_enabled
  backup_setting system doze_enabled
  backup_setting secure doze_enabled
  backup_setting global device_idle_enabled
  backup_setting global device_idle_constants
  backup_setting global battery_saver_constants
  backup_doze_whitelist
}

restore_quick_doze() {
  dumpsys deviceidle enable >/dev/null 2>&1
  dumpsys deviceidle unforce >/dev/null 2>&1
  settings put global doze_enabled 1 >/dev/null 2>&1
  settings put system doze_enabled 1 >/dev/null 2>&1
  settings put secure doze_enabled 1 >/dev/null 2>&1
  settings delete global device_idle_enabled >/dev/null 2>&1
  settings delete global device_idle_constants >/dev/null 2>&1
  settings delete global battery_saver_constants >/dev/null 2>&1
  restore_doze_whitelist
  echo "Quick Doze 已恢复为系统默认接管。"
}
""".trimIndent()

    // -----------------------------------------------------------------------
    // 4. Night Wakeup Guard Prelude
    // -----------------------------------------------------------------------
    val NIGHT_WAKEUP_GUARD: String = """
night_guard_pkgs=""
night_guard_strong_pkgs=""

safe_name() {
  echo "${'$'}1" | tr '.-' '__'
}

backup_night_guard() {
  for pkg in ${'$'}night_guard_pkgs; do
    pm path "${'$'}pkg" >/dev/null 2>&1 || continue
    safe="${'$'}(safe_name "${'$'}pkg")"
    whitelist_backup="maxhz_backup_night_whitelist_${'$'}safe"
    bucket_backup="maxhz_backup_night_bucket_${'$'}safe"
    if [ "${'$'}(settings get global "${'$'}whitelist_backup")" = "null" ]; then
      if dumpsys deviceidle whitelist 2>/dev/null | grep -Fq "${'$'}pkg"; then
        settings put global "${'$'}whitelist_backup" 1 >/dev/null 2>&1
      else
        settings put global "${'$'}whitelist_backup" 0 >/dev/null 2>&1
      fi
    fi
    if [ "${'$'}(settings get global "${'$'}bucket_backup")" = "null" ]; then
      bucket="${'$'}(am get-standby-bucket "${'$'}pkg" 2>/dev/null | tail -n 1 | tr -d '\r')"
      [ -z "${'$'}bucket" ] && bucket="active"
      settings put global "${'$'}bucket_backup" "${'$'}bucket" >/dev/null 2>&1
    fi
  done
}

restore_night_guard() {
  for pkg in ${'$'}night_guard_pkgs; do
    pm path "${'$'}pkg" >/dev/null 2>&1 || continue
    safe="${'$'}(safe_name "${'$'}pkg")"
    whitelist_backup="maxhz_backup_night_whitelist_${'$'}safe"
    bucket_backup="maxhz_backup_night_bucket_${'$'}safe"
    whitelist_value="${'$'}(settings get global "${'$'}whitelist_backup")"
    if [ "${'$'}whitelist_value" = "1" ]; then
      dumpsys deviceidle whitelist +"${'$'}pkg" >/dev/null 2>&1 || true
    fi
    bucket_value="${'$'}(settings get global "${'$'}bucket_backup")"
    if [ "${'$'}bucket_value" != "null" ]; then
      am set-standby-bucket "${'$'}pkg" "${'$'}bucket_value" >/dev/null 2>&1 || true
    fi
    cmd appops set "${'$'}pkg" RUN_IN_BACKGROUND default >/dev/null 2>&1 || true
    cmd appops set "${'$'}pkg" RUN_ANY_IN_BACKGROUND default >/dev/null 2>&1 || true
    cmd appops set "${'$'}pkg" WAKE_LOCK default >/dev/null 2>&1 || true
    cmd appops set "${'$'}pkg" SCHEDULE_EXACT_ALARM default >/dev/null 2>&1 || true
    settings delete global "${'$'}whitelist_backup" >/dev/null 2>&1
    settings delete global "${'$'}bucket_backup" >/dev/null 2>&1
  done
}
""".trimIndent()

    // -----------------------------------------------------------------------
    // 5. Scan Guard Backup Prelude
    // -----------------------------------------------------------------------
    val SCAN_GUARD_BACKUP: String = """
sync_guard_keys='
  global auto_sync_enabled
  global sync_automatically
  secure sync_automatically
  system sync_automatically
  global cloud_sync_enabled
  secure cloud_sync_enabled
  system cloud_sync_enabled
  global miui_cloud_sync
  secure miui_cloud_sync
  system miui_cloud_sync
  global xiaomi_cloud_sync
  secure xiaomi_cloud_sync
  system xiaomi_cloud_sync
  global hms_cloud_sync
  secure hms_cloud_sync
  system hms_cloud_sync
  global huawei_cloud_sync
  secure huawei_cloud_sync
  system huawei_cloud_sync
  global oppo_cloud_sync
  secure oppo_cloud_sync
  system oppo_cloud_sync
  global heytap_cloud_sync
  secure heytap_cloud_sync
  system heytap_cloud_sync
  global vivo_cloud_sync
  secure vivo_cloud_sync
  system vivo_cloud_sync
  global originos_cloud_sync
  secure originos_cloud_sync
  system originos_cloud_sync
  global honor_cloud_sync
  secure honor_cloud_sync
  system honor_cloud_sync
  global meizu_cloud_sync
  secure meizu_cloud_sync
  system meizu_cloud_sync
'

backup_scan_guard() {
  backup_setting global ble_scan_always_enabled
  backup_setting global wifi_scan_always_enabled
  backup_setting system nearby_scanning_enabled
  backup_setting secure nearby_scanning_enabled
  backup_setting global nearby_scanning_enabled
}

backup_sync_guard() {
  backup_setting global master_sync_enabled
  echo "${'$'}sync_guard_keys" | while read ns key; do
    [ -z "${'$'}ns" ] && continue
    backup_setting "${'$'}ns" "${'$'}key"
  done
}

restore_scan_guard() {
  settings delete global ble_scan_always_enabled >/dev/null 2>&1
  settings delete global wifi_scan_always_enabled >/dev/null 2>&1
  settings delete system nearby_scanning_enabled >/dev/null 2>&1
  settings delete secure nearby_scanning_enabled >/dev/null 2>&1
  settings delete global nearby_scanning_enabled >/dev/null 2>&1
}

restore_sync_guard() {
  settings put global master_sync_enabled 1 >/dev/null 2>&1
  echo "${'$'}sync_guard_keys" | while read ns key; do
    [ -z "${'$'}ns" ] && continue
    settings delete "${'$'}ns" "${'$'}key" >/dev/null 2>&1
  done
}
""".trimIndent()

    // -----------------------------------------------------------------------
    // 6. Battery Protection Backup Prelude
    // -----------------------------------------------------------------------
    val BATTERY_PROTECTION_BACKUP: String = """
backup_battery_protection() {
  backup_setting global protect_battery
  backup_setting system protect_battery
  backup_setting secure protect_battery
  backup_setting system battery_life_protect_enabled
  backup_setting global battery_protection
  backup_setting global battery_protection_mode
  backup_setting secure battery_protection_mode
  backup_setting system battery_protection_mode
  backup_setting global battery_protection_threshold
  backup_setting global battery_protection_limit
  backup_setting global battery_protection_recharge_level
  backup_setting global recharge_level
  backup_setting system battery_protection_threshold
  backup_setting system battery_protection_limit
  backup_setting system battery_protection_recharge_level
  backup_setting secure battery_protection
  backup_setting secure battery_protection_threshold
  backup_setting secure battery_protection_limit
  backup_setting secure battery_protection_recharge_level
}

backup_pass_through() {
  backup_setting system pass_through
  backup_setting global bypass_charging
  backup_setting global pass_through_charging
  backup_setting global pass_through_mode
  backup_setting global pass_through_threshold
  backup_setting system pass_through_charging
}

restore_battery_protection() {
  restore_or_delete_setting global protect_battery
  restore_or_delete_setting system protect_battery
  restore_or_delete_setting secure protect_battery
  restore_or_delete_setting system battery_life_protect_enabled
  restore_or_delete_setting global battery_protection
  restore_or_delete_setting global battery_protection_mode
  restore_or_delete_setting secure battery_protection_mode
  restore_or_delete_setting system battery_protection_mode
  restore_or_delete_setting global battery_protection_threshold
  restore_or_delete_setting global battery_protection_limit
  restore_or_delete_setting global battery_protection_recharge_level
  restore_or_delete_setting global recharge_level
  restore_or_delete_setting system battery_protection_threshold
  restore_or_delete_setting system battery_protection_limit
  restore_or_delete_setting system battery_protection_recharge_level
  restore_or_delete_setting secure battery_protection
  restore_or_delete_setting secure battery_protection_threshold
  restore_or_delete_setting secure battery_protection_limit
  restore_or_delete_setting secure battery_protection_recharge_level
  echo "电池保护设置已恢复为系统默认接管。"
}
restore_pass_through() {
  settings put system pass_through 0 >/dev/null 2>&1
  settings delete global bypass_charging >/dev/null 2>&1
  settings delete global pass_through_charging >/dev/null 2>&1
  settings delete global pass_through_mode >/dev/null 2>&1
  settings delete global pass_through_threshold >/dev/null 2>&1
  settings delete system pass_through_charging >/dev/null 2>&1
  echo "旁路充电已恢复为系统默认关闭。"
}
""".trimIndent()

    fun withRefreshRateDetection(script: String): String {
        return REFRESH_RATE_DETECT + "\n" + script.trimIndent()
    }
}
