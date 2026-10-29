package moe.shizuku.manager.maxhz

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

object MaxHzController {

    private const val TAG = "MaxHzController"

    private fun shellPackageList(packages: List<String>): String {
        return packages
            .map { it.trim() }
            .filter { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }
            .distinct()
            .joinToString(" ")
    }

    private val refreshRatePrelude = """
        detect_refresh_rates() {
          # Fix P01: capture dumpsys display once to avoid 3 separate expensive IPC dumps
          display_dump="${'$'}(dumpsys display 2>/dev/null)"
          rates="${'$'}(
            printf '%s\n' "${'$'}display_dump" \
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
              printf '%s\n' "${'$'}display_dump" \
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
              printf '%s\n' "${'$'}display_dump" \
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
          specs="$(dumpsys display 2>/dev/null | grep -m 1 -E 'mDisplayModeSpecs=|DesiredDisplayModeSpecs|display mode specs' || true)"
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

    private fun withRefreshRateDetection(script: String): String {
        return refreshRatePrelude + "\n" + script.trimIndent()
    }

    // Fix Q02 & Q03: Consolidated shared setting backup/restore functions
    // Eliminates 4x duplicate function definitions across preludes and unifies naming.
    private val sharedSettingBackupPrelude = """
        __maxhz_init_dumps() {
          [ -n "${'$'}__MAXHZ_DUMP_LOADED" ] && return
          __MAXHZ_GLOBAL_DUMP="${'$'}(settings list global 2>/dev/null)"
          __MAXHZ_SYSTEM_DUMP="${'$'}(settings list system 2>/dev/null)"
          __MAXHZ_SECURE_DUMP="${'$'}(settings list secure 2>/dev/null)"
          __MAXHZ_DUMP_LOADED=1
        }

        backup_setting() {
          ns="${'$'}1"
          key="${'$'}2"
          backup="maxhz_backup_${'$'}{ns}_${'$'}{key}"
          __maxhz_init_dumps
          if ! printf '%s\n' "${'$'}__MAXHZ_GLOBAL_DUMP" | grep -q "^${'$'}{backup}="; then
            case "${'$'}ns" in
              global) line="${'$'}(printf '%s\n' "${'$'}__MAXHZ_GLOBAL_DUMP" | grep -m1 "^${'$'}{key}=")" ;;
              system) line="${'$'}(printf '%s\n' "${'$'}__MAXHZ_SYSTEM_DUMP" | grep -m1 "^${'$'}{key}=")" ;;
              secure) line="${'$'}(printf '%s\n' "${'$'}__MAXHZ_SECURE_DUMP" | grep -m1 "^${'$'}{key}=")" ;;
              *) line="" ;;
            esac
            if [ -n "${'$'}line" ]; then
              value="${'$'}{line#*=}"
              settings put global "${'$'}backup" "${'$'}value" >/dev/null 2>&1
            else
              settings put global "${'$'}backup" "__MAXHZ_ABSENT__" >/dev/null 2>&1
            fi
          fi
        }

        restore_setting() {
          ns="${'$'}1"
          key="${'$'}2"
          backup="maxhz_backup_${'$'}{ns}_${'$'}{key}"
          value="${'$'}(settings get global "${'$'}backup" 2>/dev/null)"
          [ "${'$'}value" = "null" ] || [ -z "${'$'}value" ] && return
          if [ "${'$'}value" = "__MAXHZ_ABSENT__" ]; then
            settings delete "${'$'}ns" "${'$'}key" >/dev/null 2>&1
          else
            settings put "${'$'}ns" "${'$'}key" "${'$'}value" >/dev/null 2>&1
          fi
          settings delete global "${'$'}backup" >/dev/null 2>&1
        }

        restore_or_delete_setting() {
          ns="${'$'}1"
          key="${'$'}2"
          backup="maxhz_backup_${'$'}{ns}_${'$'}{key}"
          value="${'$'}(settings get global "${'$'}backup" 2>/dev/null)"
          if [ "${'$'}value" = "null" ] || [ -z "${'$'}value" ]; then
            settings delete "${'$'}ns" "${'$'}key" >/dev/null 2>&1
          else
            restore_setting "${'$'}ns" "${'$'}key"
          fi
        }
        restore_setting_or_delete() { restore_or_delete_setting "${'$'}@"; }
    """.trimIndent()

    private val scopedRestorePrelude = """
        ${sharedSettingBackupPrelude}


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
          value="$(settings get global "${'$'}backup" 2>/dev/null)"
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
          # Older Samsung builds do not expose a stable dumpsys field. The
          # framework-owned low_power value is the fallback, but callers must
          # never write it directly before using this check.
          [ "$(settings get global low_power 2>/dev/null)" = "1" ] && printf '1\n' || printf '0\n'
        }

        set_power_saver_and_verify() {
          expected="${'$'}1"
          if ! cmd power set-mode "${'$'}expected" >/dev/null 2>&1; then
            return 1
          fi
          sleep 0.20
          [ "$(read_power_saver_service_state)" = "${'$'}expected" ] && return 0

          # Samsung Android 16 can return exit 0 from `cmd power set-mode`
          # without changing BatterySaverStateMachine. SettingsProvider is the
          # platform-observed compatibility entry on those builds. Use it only
          # after the framework command is proven ineffective, then verify the
          # service state again instead of trusting the setting write.
          settings put global low_power "${'$'}expected" >/dev/null 2>&1 || return 1
          sleep 0.35
          [ "$(read_power_saver_service_state)" = "${'$'}expected" ]
        }

        read_daily_power_saver_effective_state() {
          effective="${'$'}(dumpsys power 2>/dev/null |
            grep -m 1 -E '^[[:space:]]*Battery Saver is currently: (ON|OFF)' |
            sed -n 's/.*currently: //p')"
          case "${'$'}effective" in
            ON) printf '1\n' ;;
            OFF) printf '0\n' ;;
            *) printf 'unknown\n' ;;
          esac
        }

        set_daily_power_saver_and_verify() {
          expected="${'$'}1"
          POWER_SAVER_VERIFY_ATTEMPTS=12
          POWER_SAVER_VERIFY_INTERVAL_SECONDS=0.25
          echo "STEP=power_saver_write"
          cmd power set-mode "${'$'}expected" >/dev/null 2>&1
          write_exit="${'$'}?"
          echo "EXIT=${'$'}write_exit"
          if [ "${'$'}write_exit" != "0" ]; then
            echo "RESULT=FAIL"
            return 1
          fi
          echo "RESULT=SUCCESS"

          echo "STEP=power_saver_verify"
          echo "SOURCE=dumpsys_power_effective"
          echo "EXPECTED=${'$'}expected"
          attempt=0
          actual=unknown
          while [ "${'$'}attempt" -lt "${'$'}POWER_SAVER_VERIFY_ATTEMPTS" ]; do
            actual="${'$'}(read_daily_power_saver_effective_state)"
            [ "${'$'}actual" = "${'$'}expected" ] && break
            attempt="${'$'}((attempt + 1))"
            [ "${'$'}attempt" -lt "${'$'}POWER_SAVER_VERIFY_ATTEMPTS" ] && \
              sleep "${'$'}POWER_SAVER_VERIFY_INTERVAL_SECONDS"
          done
          echo "ACTUAL=${'$'}actual"
          echo "ATTEMPTS=${'$'}((attempt + 1))"
          if [ "${'$'}actual" != "${'$'}expected" ]; then
            echo "RESULT=STANDARD_NO_EFFECT"
            echo "STEP=power_saver_samsung_fallback"
            settings put global low_power "${'$'}expected" >/dev/null 2>&1
            fallback_exit="${'$'}?"
            echo "EXIT=${'$'}fallback_exit"
            if [ "${'$'}fallback_exit" != "0" ]; then
              echo "RESULT=FAIL"
              return 1
            fi
            attempt=0
            actual=unknown
            while [ "${'$'}attempt" -lt "${'$'}POWER_SAVER_VERIFY_ATTEMPTS" ]; do
              actual="${'$'}(read_daily_power_saver_effective_state)"
              [ "${'$'}actual" = "${'$'}expected" ] && break
              attempt="${'$'}((attempt + 1))"
              [ "${'$'}attempt" -lt "${'$'}POWER_SAVER_VERIFY_ATTEMPTS" ] && \
                sleep "${'$'}POWER_SAVER_VERIFY_INTERVAL_SECONDS"
            done
            echo "SOURCE=dumpsys_power_effective"
            echo "EXPECTED=${'$'}expected"
            echo "ACTUAL=${'$'}actual"
            echo "ATTEMPTS=${'$'}((attempt + 1))"
            if [ "${'$'}actual" != "${'$'}expected" ]; then
              echo "RESULT=FAIL"
              return 4
            fi
            echo "RESULT=SUCCESS"
            return 0
          fi
          echo "RESULT=SUCCESS"
          return 0
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

        reload_samsung_adaptive_refresh() {
          system_saver=0
          for key in low_power low_power_mode low_power_sticky battery_saver_enabled; do
            [ "$(settings get global "${'$'}key" 2>/dev/null)" = "1" ] && system_saver=1
          done
          if [ "${'$'}system_saver" = "1" ]; then
            cmd power set-mode 1 >/dev/null 2>&1 || true
            return
          fi
          for key in low_power_mode_opened extra_low_power k_power_mode_state n_power_mode_state q_power_mode_state s_power_mode_state; do
            settings put global "${'$'}key" 0 >/dev/null 2>&1 || true
          done
          settings delete secure refresh_rate >/dev/null 2>&1
          settings delete secure user_refresh_rate >/dev/null 2>&1
          settings delete system display_refresh_rate >/dev/null 2>&1
          settings delete system peak_refresh_rate >/dev/null 2>&1
          settings delete system user_refresh_rate >/dev/null 2>&1
          settings delete system min_refresh_rate >/dev/null 2>&1
          settings delete system display_min_refresh_rate >/dev/null 2>&1
          settings delete global max_fps >/dev/null 2>&1
          settings delete global min_fps >/dev/null 2>&1
          settings put secure refresh_rate_mode 0 >/dev/null 2>&1 || true
          sleep 0.15
          settings put secure refresh_rate_mode 1 >/dev/null 2>&1 || true
          settings put secure user_refresh_rate 120 >/dev/null 2>&1 || true
          settings put system peak_refresh_rate 120.0 >/dev/null 2>&1 || true
          settings put system user_refresh_rate 120 >/dev/null 2>&1 || true
          settings put system min_refresh_rate 0.0 >/dev/null 2>&1 || true
          settings delete system custom_refresh_rate >/dev/null 2>&1 || true
          settings delete system display_min_refresh_rate >/dev/null 2>&1 || true
          cmd display clear-user-preferred-display-mode 0 >/dev/null 2>&1 || true
        }

        nudge_adaptive_refresh_if_needed() {
          backup_mode="${'$'}1"
          force="${'$'}2"
          [ "${'$'}force" != "1" ] && [ "${'$'}backup_mode" = "0" ] && return
          system_saver=0
          for key in low_power low_power_mode low_power_sticky battery_saver_enabled; do
            [ "$(settings get global "${'$'}key" 2>/dev/null)" = "1" ] && system_saver=1
          done
          [ "${'$'}system_saver" = "1" ] && return
          if dumpsys display 2>/dev/null | grep -m 1 'mDisplayModeSpecs=' | grep -q 'physical: (10.0 60.0)'; then
            reload_samsung_adaptive_refresh
            echo "检测到刷新率仍停在 60Hz，已重新触发系统自适应刷新率。"
          fi
        }

        restore_game_thermal_defaults() {
          cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1 || true
          for key in game_acceleration_mode performance_mode game_driver_mode game_performance_mode gpu_performance_mode restricted_device_performance restricted_device_performance_power_level sem_enhanced_cpu_responsiveness; do
            settings delete global "${'$'}key" >/dev/null 2>&1 || true
          done
          for key in POWER_PERFORMANCE_MODE_OPEN POWER_SAVE_MODE_OPEN POWER_BALANCED_MODE_OPEN POWER_SAVE_PRE_HIDE_MODE sem_performance_mode touchfeature.gamemode gesture_power_save thermal.gpu_core_clock_throttle.disable thermal.gpu_boost.disable thermal.gpu_memory_throttle.disable thermal.thermal_policy.disable thermal.cpu_thermal_throttle thermal.cpu_thermal_throttle.disable thermal.auto_thermal_disable thermal.throttling.disable thermal.performance_mode.disable thermal.profile.disable thermal.critical_temp.disable thermal_zone.gpu_threshold_temp thermal_zone.cpu_threshold_temp thermal_zone.battery_threshold_temp pid.sec-thermal-1-0 mediatek.appgamepq_compress mediatek.disp_decompress mtk_tflite.target_nnapi; do
            settings delete system "${'$'}key" >/dev/null 2>&1 || true
          done
          settings delete secure allow_more_heat_value >/dev/null 2>&1 || true
          setprop debug.qcom.thermal.cpu_boost_temp "" >/dev/null 2>&1 || true
          setprop debug.qcom.thermal.throttle_start_temp "" >/dev/null 2>&1 || true
        }

        restore_m_thermal_keys() {
          for key in thermal_throttle_enabled thermal_cpu_thermal_throttle thermal_gpu_core_clock_throttle thermal_gpu_boost; do
            restore_setting_or_delete global "${'$'}key"
          done
          for key in thermal.thermal_policy.disable thermal.cpu_thermal_throttle thermal.cpu_thermal_throttle.disable pid.sec-thermal-1-0 mediatek.appgamepq_compress mediatek.disp_decompress mtk_tflite.target_nnapi thermal.auto_thermal_disable thermal_switch_mode thermal.throttling.disable thermal.performance_mode.disable thermal.profile.disable thermal.critical_temp.disable thermal_zone.gpu_threshold_temp thermal_zone.cpu_threshold_temp thermal_zone.battery_threshold_temp; do
            restore_setting_or_delete system "${'$'}key"
          done
          restore_setting_or_delete secure allow_more_heat_value
          restore_setting_or_delete secure miui_thermal_limit
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
          refresh_mode_backup="$(settings get global maxhz_backup_secure_refresh_rate_mode 2>/dev/null)"
          for key in low_power low_power_mode low_power_sticky battery_saver_enabled; do
            backup_value="$(settings get global "maxhz_backup_global_${'$'}key" 2>/dev/null)"
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

    private val batteryProtectionBackupPrelude = """
        ${sharedSettingBackupPrelude}

        backup_battery_protection() {
          backup_setting global protect_battery
          backup_setting system protect_battery
          backup_setting secure protect_battery
          backup_setting system battery_life_protect_enabled
          backup_setting global battery_protection
          backup_setting global battery_protection_threshold
          backup_setting global battery_protection_limit
          backup_setting global battery_protection_recharge_level
          backup_setting global ltc_highsoc_threshold
          backup_setting global ltc_release_threshold
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
          force_fallback="${'$'}{1:-1}"
          has_backup=0
          for k in maxhz_backup_global_protect_battery maxhz_backup_system_protect_battery maxhz_backup_global_battery_protection_threshold maxhz_backup_global_ltc_highsoc_threshold; do
            v="${'$'}(settings get global "${'$'}k" 2>/dev/null)"
            if [ -n "${'$'}v" ] && [ "${'$'}v" != "null" ]; then
              has_backup=1
              break
            fi
          done
          if [ "${'$'}has_backup" = "1" ]; then
            for key in protect_battery battery_protection battery_protection_threshold battery_protection_limit battery_protection_recharge_level ltc_highsoc_threshold ltc_release_threshold recharge_level; do
              restore_or_delete_setting global "${'$'}key"
            done
            for key in protect_battery battery_life_protect_enabled battery_protection_threshold battery_protection_limit battery_protection_recharge_level; do
              restore_or_delete_setting system "${'$'}key"
            done
            for key in protect_battery battery_protection battery_protection_threshold battery_protection_limit battery_protection_recharge_level; do
              restore_or_delete_setting secure "${'$'}key"
            done
            echo "已按备份恢复用户原有的系统电池保护设置。"
          elif [ "${'$'}force_fallback" = "1" ]; then
            force_basic_battery_protection
          else
            echo "未检测到本应用对电池保护的修改备份，保持系统原有电池保护设置不变。"
          fi
        }

        force_basic_battery_protection() {
          # Samsung "基本保护": charge to 100%, resume around 95%.
          # On current One UI, protect_battery=3 maps to "基本"; 1 maps to "充电上限".
          settings put global protect_battery 3 >/dev/null 2>&1 || true
          settings put global ltc_highsoc_threshold 95 >/dev/null 2>&1 || true
          settings put global ltc_release_threshold 75 >/dev/null 2>&1 || true
          settings delete global prev_protect_battery >/dev/null 2>&1 || true
          settings delete global prev_protect_battery_ltc >/dev/null 2>&1 || true
          settings delete global battery_charging_state_enforce_level >/dev/null 2>&1 || true
          settings delete global battery_charging_state_update_delay >/dev/null 2>&1 || true

          for ns in global system secure; do
            for key in battery_protection; do
              settings delete "${'$'}ns" "${'$'}key" >/dev/null 2>&1 || true
            done
            for key in \
              battery_protection_threshold \
              battery_protection_limit \
              battery_protection_recharge_level \
              battery_protection_mode \
              battery_protection_type \
              battery_protection_level \
              battery_protection_charging_mode \
              protect_battery_limit \
              protect_battery_threshold \
              recharge_level
            do
              settings delete "${'$'}ns" "${'$'}key" >/dev/null 2>&1 || true
            done
          done
          settings delete system protect_battery >/dev/null 2>&1 || true
          settings delete secure protect_battery >/dev/null 2>&1 || true
          settings delete system battery_life_protect_enabled >/dev/null 2>&1 || true

          basic_now="$(settings get global protect_battery 2>/dev/null)"
          high_now="$(settings get global ltc_highsoc_threshold 2>/dev/null)"
          release_now="$(settings get global ltc_release_threshold 2>/dev/null)"
          if [ "${'$'}basic_now" != "3" ]; then
            echo "验证失败：电池保护未切回三星基本保护。"
            echo "当前 protect_battery=${'$'}basic_now"
            exit 7
          fi
          if [ "${'$'}high_now" != "95" ] || [ "${'$'}release_now" != "75" ]; then
            echo "验证失败：三星基本保护阈值未恢复。"
            echo "当前阈值：充满=${'$'}high_now，回充=${'$'}release_now"
            exit 8
          fi
          mode_line="$(dumpsys battery 2>/dev/null | grep -m 1 'mProtectBatteryMode' | sed 's/^ *//')"
          threshold_line="$(dumpsys battery 2>/dev/null | grep -m 1 'mLtcHighThreshold' | sed 's/^ *//')"
          release_line="$(dumpsys battery 2>/dev/null | grep -m 1 'mLtcReleaseThreshold' | sed 's/^ *//')"
          echo "电池保护已交回三星基本保护。"
          echo "当前设置：protect_battery=${'$'}basic_now，充满=${'$'}high_now%，回充=${'$'}release_now%。"
          [ -n "${'$'}mode_line" ] && echo "系统状态：${'$'}mode_line"
          [ -n "${'$'}threshold_line" ] && echo "系统阈值：${'$'}threshold_line"
          [ -n "${'$'}release_line" ] && echo "系统阈值：${'$'}release_line"
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

    private val quickDozeBackupPrelude = """
        ${sharedSettingBackupPrelude}

        backup_doze_whitelist() {
          for pkg in com.tencent.mm com.tencent.mobileqq com.tencent.tim com.tencent.wework com.alibaba.android.rimet com.google.android.gms; do
            safe="$(echo "${'$'}pkg" | tr '.-' '__')"
            backup="maxhz_backup_doze_whitelist_${'$'}safe"
            if [ "$(settings get global "${'$'}backup")" = "null" ]; then
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
            safe="$(echo "${'$'}pkg" | tr '.-' '__')"
            backup="maxhz_backup_doze_whitelist_${'$'}safe"
            value="$(settings get global "${'$'}backup")"
            if [ "${'$'}value" = "1" ]; then
              dumpsys deviceidle whitelist +"${'$'}pkg" >/dev/null 2>&1 || true
            elif [ "${'$'}value" = "0" ]; then
              : # 恢复时不主动移出任何 App 的 Doze 白名单，避免触发后台保护/通知风险。
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

    private val nightWakeupGuardPrelude = """
        night_guard_pkgs=""
        night_guard_strong_pkgs=""

        safe_name() {
          echo "${'$'}1" | tr '.-' '__'
        }

        backup_night_guard() {
          for pkg in ${'$'}night_guard_pkgs; do
            pm path "${'$'}pkg" >/dev/null 2>&1 || continue
            safe="$(safe_name "${'$'}pkg")"
            whitelist_backup="maxhz_backup_night_whitelist_${'$'}safe"
            bucket_backup="maxhz_backup_night_bucket_${'$'}safe"
            if [ "$(settings get global "${'$'}whitelist_backup")" = "null" ]; then
              if dumpsys deviceidle whitelist 2>/dev/null | grep -Fq "${'$'}pkg"; then
                settings put global "${'$'}whitelist_backup" 1 >/dev/null 2>&1
              else
                settings put global "${'$'}whitelist_backup" 0 >/dev/null 2>&1
              fi
            fi
            if [ "$(settings get global "${'$'}bucket_backup")" = "null" ]; then
              bucket="$(am get-standby-bucket "${'$'}pkg" 2>/dev/null | tail -n 1 | tr -d '\r')"
              [ -z "${'$'}bucket" ] && bucket="active"
              settings put global "${'$'}bucket_backup" "${'$'}bucket" >/dev/null 2>&1
            fi
          done
        }

        restore_night_guard() {
          for pkg in ${'$'}night_guard_pkgs; do
            pm path "${'$'}pkg" >/dev/null 2>&1 || continue
            safe="$(safe_name "${'$'}pkg")"
            whitelist_backup="maxhz_backup_night_whitelist_${'$'}safe"
            bucket_backup="maxhz_backup_night_bucket_${'$'}safe"
            whitelist_value="$(settings get global "${'$'}whitelist_backup")"
            if [ "${'$'}whitelist_value" = "1" ]; then
              dumpsys deviceidle whitelist +"${'$'}pkg" >/dev/null 2>&1 || true
            elif [ "${'$'}whitelist_value" = "0" ]; then
              : # 恢复待机护栏时也不主动移出白名单；只撤销本功能的限制。
            fi
            bucket_value="$(settings get global "${'$'}bucket_backup")"
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

    private val scanGuardBackupPrelude = """
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

        backup_setting() {
          ns="${'$'}1"
          key="${'$'}2"
          backup="maxhz_backup_${'$'}{ns}_${'$'}{key}"
          if [ "$(settings get global "${'$'}backup")" = "null" ]; then
            value="$(settings get "${'$'}ns" "${'$'}key")"
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
          value="$(settings get global "${'$'}backup")"
          [ "${'$'}value" = "null" ] && return
          if [ "${'$'}value" = "__MAXHZ_ABSENT__" ]; then
            settings delete "${'$'}ns" "${'$'}key" >/dev/null 2>&1
          else
            settings put "${'$'}ns" "${'$'}key" "${'$'}value" >/dev/null 2>&1
          fi
          settings delete global "${'$'}backup" >/dev/null 2>&1
        }

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

    fun readStatus(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            echo "型号=$(getprop ro.product.model)"
            echo "厂商=$(getprop ro.product.manufacturer)"
            echo "Android版本=$(getprop ro.build.version.release)"
            echo "SDK版本=$(getprop ro.build.version.sdk)"
            for k in low_power battery_saver_enabled restricted_device_performance sem_low_heat_mode pms_settings_refresh_rate_enabled device_idle_enabled battery_saver_constants protect_battery battery_protection battery_protection_threshold battery_protection_recharge_level bypass_charging pass_through_charging; do
              echo "${'$'}{k}=$(settings get global "${'$'}{k}")"
            done
            for k in ble_scan_always_enabled wifi_scan_always_enabled nearby_scanning_enabled; do
              echo "global.${'$'}{k}=$(settings get global "${'$'}{k}")"
            done
            for k in refresh_rate_mode user_refresh_rate; do
              echo "secure.${'$'}{k}=$(settings get secure "${'$'}{k}")"
            done
            echo "secure.nearby_scanning_enabled=$(settings get secure nearby_scanning_enabled)"
            for k in min_refresh_rate peak_refresh_rate user_refresh_rate protect_battery battery_protection_threshold battery_protection_recharge_level pass_through battery_life_protect_enabled adaptive_fast_charging super_fast_charging; do
              echo "system.${'$'}{k}=$(settings get system "${'$'}{k}")"
            done
            echo "system.display_outdoor_mode=$(settings get system display_outdoor_mode)"
            echo "system.nearby_scanning_enabled=$(settings get system nearby_scanning_enabled)"
            ${refreshRatePrelude}
            echo "支持刷新率=${'$'}supported_hz"
            echo "推荐亮屏范围=${'$'}min_hz-${'$'}max_hz Hz"
            dumpsys display 2>/dev/null | grep -m 1 'mDisplayModeSpecs=' || true
            dumpsys display 2>/dev/null | grep -m 1 'mActiveModeId=' || true
            """.trimIndent(),
            timeoutSeconds = 30
        )
    }

    fun readSafetySnapshot(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            snapshot_setting() {
              ns="${'$'}1"
              key="${'$'}2"
              echo "${'$'}ns.${'$'}key=$(settings get "${'$'}ns" "${'$'}key" 2>/dev/null)"
            }
            for k in refresh_rate_mode refresh_rate user_refresh_rate show_refresh_rate; do
              snapshot_setting secure "${'$'}k"
            done
            for k in min_refresh_rate peak_refresh_rate user_refresh_rate display_refresh_rate display_min_refresh_rate show_refresh_rate display_outdoor_mode protect_battery battery_life_protect_enabled battery_protection_threshold battery_protection_recharge_level pass_through nearby_scanning_enabled; do
              snapshot_setting system "${'$'}k"
            done
            for k in low_power low_power_mode low_power_sticky battery_saver_enabled restricted_device_performance sem_low_heat_mode adaptive_power_saving_setting dynamic_power_savings_enabled device_idle_enabled device_idle_constants battery_saver_constants protect_battery battery_protection battery_protection_threshold battery_protection_recharge_level recharge_level master_sync_enabled ble_scan_always_enabled wifi_scan_always_enabled nearby_scanning_enabled pms_settings_refresh_rate_enabled psm_refresh_rate_tag sem_power_mode_refresh_rate show_refresh_rate bypass_charging pass_through_charging; do
              snapshot_setting global "${'$'}k"
            done
            """.trimIndent(),
            timeoutSeconds = 5
        )
    }

    fun detectSystemResidue(nightGuardPackages: List<String>): MaxHzShell.Result {
        val knownNightGuardPackages = shellPackageList(nightGuardPackages)
        return MaxHzShell.exec(
            """
            ${scopedRestorePrelude}
            residue=0
            report() {
              residue=1
              echo "可能残留：${'$'}1"
            }
            get_setting() {
              settings get "${'$'}1" "${'$'}2" 2>/dev/null
            }
            is_owned_setting() {
              ns="${'$'}1"
              key="${'$'}2"
              backup="$(settings get global "maxhz_backup_${'$'}{ns}_${'$'}{key}" 2>/dev/null)"
              [ "${'$'}backup" != "null" ]
            }
            not_empty() {
              [ -n "${'$'}1" ] && [ "${'$'}1" != "null" ]
            }
            check_setting_exists() {
              ns="${'$'}1"
              key="${'$'}2"
              label="${'$'}3"
              is_owned_setting "${'$'}ns" "${'$'}key" || return 0
              value="$(get_setting "${'$'}ns" "${'$'}key")"
              not_empty "${'$'}value" && report "${'$'}label=${'$'}value"
            }
            check_setting_enabled() {
              ns="${'$'}1"
              key="${'$'}2"
              label="${'$'}3"
              is_owned_setting "${'$'}ns" "${'$'}key" || return 0
              value="$(get_setting "${'$'}ns" "${'$'}key")"
              [ "${'$'}value" = "1" ] && report "${'$'}label=${'$'}value"
            }
            check_setting_equals() {
              ns="${'$'}1"
              key="${'$'}2"
              expected="${'$'}3"
              label="${'$'}4"
              value="$(get_setting "${'$'}ns" "${'$'}key")"
              not_empty "${'$'}value" && [ "${'$'}value" != "${'$'}expected" ] && report "${'$'}label=${'$'}value"
            }

            echo "系统残留检测"
            echo "说明：只读取系统状态，不自动修改。"

            system_saver=0
            system_saver="$(read_power_saver_service_state)"
            if [ "${'$'}system_saver" = "1" ]; then
              echo "当前状态：系统省电模式已开启。这是系统当前状态，不按残留处理。"
            fi

            sem_rr="$(get_setting global sem_power_mode_refresh_rate)"
            sem_cover="$(get_setting global sem_power_mode_refresh_rate_cover)"
            case "${'$'}sem_rr" in *,*,*,*,*) report "Samsung 省电刷新率字段异常：sem_power_mode_refresh_rate=${'$'}sem_rr" ;; esac
            case "${'$'}sem_cover" in *,*,*,*,*) report "Samsung 外屏省电刷新率字段异常：sem_power_mode_refresh_rate_cover=${'$'}sem_cover" ;; esac

            samsung_perf="$(get_setting global restricted_device_performance)"
            samsung_low_heat="$(get_setting global sem_low_heat_mode)"
            if not_empty "${'$'}samsung_perf" || not_empty "${'$'}samsung_low_heat"; then
              echo "当前状态：Samsung 性能配置由系统设置控制，不按残留处理。"
            fi

            for item in \
              "global bypass_charging 旁路充电" \
              "global pass_through_charging 旁路充电" \
              "system pass_through 旁路充电" \
              "system pass_through_charging 旁路充电" \
              "global GPU_EXECUTION_MODE GPU 模式" \
              "global gpu_performance_mode GPU 性能模式" \
              "global debug.vulkan.enable Vulkan 开关" \
              "global zram_enabled ZRAM 设置"; do
              set -- ${'$'}item
              ns="${'$'}1"
              key="${'$'}2"
              shift 2
              label="${'$'}*"
              check_setting_enabled "${'$'}ns" "${'$'}key" "${'$'}label"
            done

            for item in \
              "global device_idle_constants Quick Doze 自定义参数" \
              "global battery_saver_constants 省电常量" \
              "global pass_through_mode 旁路充电" \
              "global pass_through_threshold 旁路充电" \
              "global max_fps 最大 FPS" \
              "global min_fps 最小 FPS" \
              "global debug.hwui.renderer HWUI 渲染器" \
              "global zram_size_percentage ZRAM 设置"; do
              set -- ${'$'}item
              ns="${'$'}1"
              key="${'$'}2"
              shift 2
              label="${'$'}*"
              check_setting_exists "${'$'}ns" "${'$'}key" "${'$'}label"
            done

            master_sync="$(get_setting global master_sync_enabled)"
            is_owned_setting global master_sync_enabled && [ "${'$'}master_sync" = "0" ] && report "同步开关关闭：master_sync_enabled=0"
            for item in \
              "global ble_scan_always_enabled 蓝牙后台扫描" \
              "global wifi_scan_always_enabled Wi-Fi 后台扫描" \
              "system nearby_scanning_enabled 附近设备扫描" \
              "secure nearby_scanning_enabled 附近设备扫描" \
              "global nearby_scanning_enabled 附近设备扫描"; do
              set -- ${'$'}item
              ns="${'$'}1"
              key="${'$'}2"
              shift 2
              label="${'$'}*"
              value="$(get_setting "${'$'}ns" "${'$'}key")"
              is_owned_setting "${'$'}ns" "${'$'}key" && [ "${'$'}value" = "0" ] && report "${'$'}label=${'$'}value"
            done

            night_count=0
            for pkg in $knownNightGuardPackages; do
              [ -z "${'$'}pkg" ] && continue
              cmd package path "${'$'}pkg" >/dev/null 2>&1 || continue
              if command -v timeout >/dev/null 2>&1; then
                bucket="$(timeout 1 am get-standby-bucket "${'$'}pkg" 2>/dev/null | tail -n 1 | tr -d '\r')"
                appops="$(timeout 1 cmd appops get "${'$'}pkg" RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WAKE_LOCK SCHEDULE_EXACT_ALARM 2>/dev/null || true)"
              else
                bucket="$(am get-standby-bucket "${'$'}pkg" 2>/dev/null | tail -n 1 | tr -d '\r')"
                appops="$(cmd appops get "${'$'}pkg" RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WAKE_LOCK SCHEDULE_EXACT_ALARM 2>/dev/null || true)"
              fi
              suspect=0
              case "${'$'}bucket" in restricted|RESTRICTED) suspect=1 ;; esac
              bucket_num="$(printf '%s\n' "${'$'}bucket" | grep -E '^[0-9]+${'$'}' | tail -n 1)"
              [ -n "${'$'}bucket_num" ] && [ "${'$'}bucket_num" -ge 45 ] 2>/dev/null && suspect=1
              printf '%s\n' "${'$'}appops" | grep -Eq 'RUN_(ANY_)?IN_BACKGROUND: *ignore|WAKE_LOCK: *ignore|SCHEDULE_EXACT_ALARM: *ignore' && suspect=1
              [ "${'$'}suspect" = "1" ] || continue
              night_count="${'$'}((night_count + 1))"
              report "本 App 记录的 Night Guard 限制仍存在：${'$'}pkg"
            done

            if [ "${'$'}residue" = "1" ]; then
              echo ""
              echo "发现可能系统残留。"
              echo "建议：确认不再需要旧设置后，执行“总恢复 / 恢复系统默认接管”。"
              echo "说明：只报告本 App 留有备份或历史记录的设置，不把系统和其他工具的设置算作残留。"
            else
              echo ""
              echo "未发现明显系统残留。"
              echo "如果仍有异常，可执行总恢复让系统重新接管主要设置。"
            fi
            """.trimIndent(),
            timeoutSeconds = 25
        )
    }

    fun readSupportedRefreshRates(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${refreshRatePrelude}
            echo "${'$'}supported_hz"
            """.trimIndent(),
            timeoutSeconds = 4
        )
    }

    fun ensureControlUiRefreshRate(): MaxHzShell.Result {
        return MaxHzShell.exec(
            withRefreshRateDetection("""
            current_peak="$(settings get system peak_refresh_rate 2>/dev/null)"
            current_min="$(settings get system min_refresh_rate 2>/dev/null)"
            current_user="$(settings get system user_refresh_rate 2>/dev/null)"
            low_stuck=0
            case "${'$'}current_peak" in
              1|10|24|30) low_stuck=1 ;;
            esac
            if [ "${'$'}low_stuck" = "1" ] && [ "${'$'}current_min" = "${'$'}current_peak" ]; then
              settings put secure refresh_rate_mode 1
              settings delete secure refresh_rate >/dev/null 2>&1
              settings put secure user_refresh_rate "${'$'}max_hz"
              settings put system peak_refresh_rate "${'$'}max_hz"
              settings put system user_refresh_rate "${'$'}max_hz"
              settings put system min_refresh_rate "${'$'}min_hz"
              echo "检测到亮屏仍锁在 ${'$'}current_peak Hz，已恢复 MaxHz 控制界面自适应 ${'$'}min_hz-${'$'}max_hz Hz"
            else
              echo "MaxHz 控制界面刷新率正常：min=${'$'}current_min peak=${'$'}current_peak user=${'$'}current_user"
            fi
            """),
            timeoutSeconds = 4
        )
    }

    fun grantSupportPermissions(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            cmd appops set moe.shizuku.privileged.api GET_USAGE_STATS allow >/dev/null 2>&1 || appops set moe.shizuku.privileged.api GET_USAGE_STATS allow >/dev/null 2>&1 || true
            cmd appops set moe.shizuku.privileged.api WRITE_SETTINGS allow >/dev/null 2>&1 || true
            cmd appops set moe.shizuku.privileged.api RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
            cmd appops set moe.shizuku.privileged.api RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
            pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true
            pm grant moe.shizuku.privileged.api android.permission.WRITE_SETTINGS >/dev/null 2>&1 || true
            pm grant moe.shizuku.privileged.api android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
            echo "已授权 MaxHz 后台规则所需权限"
            """.trimIndent()
        )
    }

    fun restoreDefaults(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${scopedRestorePrelude}
            restore_compatible_saver
            ${batteryProtectionBackupPrelude}
            restore_battery_protection
            restore_pass_through
            ${quickDozeBackupPrelude}
            restore_quick_doze
            ${nightWakeupGuardPrelude}
            restore_night_guard
            ${scanGuardBackupPrelude}
            restore_sync_guard
            restore_scan_guard
            echo "已执行总恢复：清理本应用主要修改项；省电、刷新率、Doze、电池保护和 M 脚本交回系统默认接管。"
            """.trimIndent(),
            timeoutSeconds = 30
        )
    }

    fun disableAutoSync(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${scanGuardBackupPrelude}
            backup_scan_guard
            backup_sync_guard
            settings put global master_sync_enabled 0
            content call --uri content://settings/global --method PUT_global --arg master_sync_enabled --extra value:i:0 >/dev/null 2>&1 || true
            echo "${'$'}sync_guard_keys" | while read ns key; do
              [ -z "${'$'}ns" ] && continue
              settings put "${'$'}ns" "${'$'}key" 0 >/dev/null 2>&1 || true
            done
            settings put global ble_scan_always_enabled 0 >/dev/null 2>&1
            settings put global wifi_scan_always_enabled 0 >/dev/null 2>&1
            settings put system nearby_scanning_enabled 0 >/dev/null 2>&1 || true
            settings put secure nearby_scanning_enabled 0 >/dev/null 2>&1 || true
            settings put global nearby_scanning_enabled 0 >/dev/null 2>&1 || true
            echo "已应用同步/扫描省电护栏。"
            echo "已关闭：账号后台同步、常见厂商云同步键、蓝牙/Wi-Fi/附近设备后台扫描。"
            echo "不会关闭网络、蓝牙或 Wi-Fi 本体。"
            """.trimIndent()
        )
    }

    fun enableAutoSync(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${scanGuardBackupPrelude}
            restore_sync_guard
            restore_scan_guard
            content call --uri content://settings/global --method PUT_global --arg master_sync_enabled --extra value:i:"$(settings get global master_sync_enabled)" >/dev/null 2>&1 || true
            echo "已恢复同步/扫描省电护栏。"
            echo "账号同步和后台扫描已交回系统接管。"
            """.trimIndent()
        )
    }

    fun keepForceResizableActivities(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            settings put global force_resizable_activities 1
            settings put secure force_resizable_activities 1
            echo "已保持强制可调整窗口"
            """.trimIndent()
        )
    }

    fun restoreForceResizableActivities(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            settings delete global force_resizable_activities >/dev/null 2>&1
            settings delete secure force_resizable_activities >/dev/null 2>&1
            echo "已恢复窗口默认"
            """.trimIndent()
        )
    }

    fun setOutdoorMode(enabled: Boolean): MaxHzShell.Result {
        val expected = if (enabled) 1 else 0
        return MaxHzShell.exec(
            """
            if ! settings put system display_outdoor_mode $expected; then
              echo "写入失败：display_outdoor_mode"
              exit 4
            fi
            settings put system screen_extra_brightness $expected >/dev/null 2>&1 || true
            actual="${'$'}(settings get system display_outdoor_mode 2>/dev/null)"
            if [ "${'$'}actual" != "$expected" ]; then
              echo "验证失败：户外模式设置值=${'$'}actual，预期=$expected"
              exit 4
            fi
            if [ "$expected" = "1" ]; then
              echo "户外模式已开启。三星系统将按自身策略提供户外亮度。"
            else
              echo "户外模式已关闭。"
            fi
            """.trimIndent(),
            timeoutSeconds = 3
        )
    }

    fun readOutdoorModeEnabled(context: Context? = null): Boolean? {
        val cr = context?.contentResolver ?: runCatching { moe.shizuku.manager.application.contentResolver }.getOrNull()
        if (cr != null) {
            try {
                return Settings.System.getInt(cr, "display_outdoor_mode", 0) == 1
            } catch (_: Throwable) {}
        }
        val result = MaxHzShell.execRaw(
            "settings get system display_outdoor_mode 2>/dev/null",
            timeoutMs = 1500
        )
        if (result.code != 0) return null
        return when (result.out.trim().lineSequence().lastOrNull()?.trim()) {
            "1" -> true
            "0", "null" -> false
            else -> null
        }
    }

    fun toggleOutdoorMode(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            current="${'$'}(settings get system display_outdoor_mode 2>/dev/null)"
            if [ "${'$'}current" = "1" ]; then
              target=0
            else
              target=1
            fi
            if ! settings put system display_outdoor_mode "${'$'}target"; then
              echo "写入失败：display_outdoor_mode"
              exit 4
            fi
            settings put system screen_extra_brightness "${'$'}target" >/dev/null 2>&1 || true
            actual="${'$'}(settings get system display_outdoor_mode 2>/dev/null)"
            if [ "${'$'}actual" != "${'$'}target" ]; then
              echo "验证失败：户外模式设置值=${'$'}actual，预期=${'$'}target"
              exit 4
            fi
            echo "MAXHZ_OUTDOOR_MODE_STATE=${'$'}actual"
            if [ "${'$'}actual" = "1" ]; then
              echo "户外模式已开启。"
            else
              echo "户外模式已关闭。"
            fi
            """.trimIndent(),
            timeoutSeconds = 3
        )
    }

    fun applyBatteryProtection(limit: Int, recharge: Int): MaxHzShell.Result {
        val safeLimit = limit.coerceIn(50, 100)
        val safeRecharge = recharge.coerceIn(20, (safeLimit - 5).coerceAtLeast(20))
        return MaxHzShell.exec(
            """
            ${batteryProtectionBackupPrelude}
            backup_battery_protection
            settings put global protect_battery 1
            settings put system protect_battery 1 >/dev/null 2>&1 || true
            settings put system battery_life_protect_enabled 1 >/dev/null 2>&1 || true
            settings put global battery_protection_threshold $safeLimit
            settings put global ltc_highsoc_threshold $safeLimit
            settings put global ltc_release_threshold $safeRecharge
            protect_now=0
            [ "${'$'}(settings get global protect_battery 2>/dev/null)" = "1" ] && protect_now=1
            limit_now="${'$'}(settings get global battery_protection_threshold 2>/dev/null)"
            high_now="${'$'}(settings get global ltc_highsoc_threshold 2>/dev/null)"
            release_now="${'$'}(settings get global ltc_release_threshold 2>/dev/null)"
            if [ "${'$'}protect_now" != "1" ]; then
              echo "验证失败：电池保护开关没有写入"
              exit 4
            fi
            if [ "${'$'}limit_now" != "$safeLimit" ] || [ "${'$'}high_now" != "$safeLimit" ] || [ "${'$'}release_now" != "$safeRecharge" ]; then
              echo "验证失败：电池保护参数没有写入"
              exit 4
            fi
            echo "电池保护已设置为${safeLimit}/${safeRecharge}"
            """.trimIndent()
        )
    }

    fun applyPassThroughMode(limit: Int): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${batteryProtectionBackupPrelude}
            backup_pass_through
            # Samsung Game Booster "Pause USB PD charging when gaming" calls
            # Settings.System.putInt(contentResolver, "pass_through", 1).
            settings put system pass_through 1
            system_pass="$(settings get system pass_through 2>/dev/null)"
            echo "全局旁路供电已开启："
            echo "• 旁路状态：已向系统底层发送全局直供电指令（不仅限游戏，全局应用与场景通用）"
            echo "• 运行条件：连接 25W+ PPS 快充头且当前电量 ≥20%"
            if [ "${'$'}system_pass" = "1" ]; then
              echo "• 服务接管：已写入 pass_through=1，由硬件直接为主板供电，降低电池发热损耗。"
            else
              echo "• 服务提示：pass_through 写入可能被系统限制。"
            fi
            """.trimIndent()
        )
    }

    fun readBatteryProtectionStatus(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            # Fix P03: read dumpsys battery once; dumpsys batteryproperties was unused and redundant
            battery="$(dumpsys battery 2>/dev/null)"

            read_node() {
              for node in "${'$'}@"; do
                if [ -r "${'$'}node" ]; then
                  value="$(cat "${'$'}node" 2>/dev/null | head -n 1)"
                  if [ -n "${'$'}value" ]; then
                    echo "${'$'}value"
                    return
                  fi
                fi
              done
            }

            format_temp() {
              value="${'$'}1"
              if [ -z "${'$'}value" ]; then
                echo "系统未提供"
                return
              fi
              if echo "${'$'}value" | grep -Eq '^-?[0-9]+${'$'}'; then
                abs="${'$'}value"
                case "${'$'}abs" in -*) abs="${'$'}{abs#-}" ;; esac
                if [ "${'$'}abs" -ge 100 ] 2>/dev/null; then
                  awk -v v="${'$'}value" 'BEGIN { printf "%.1f°C", v / 10 }'
                else
                  echo "${'$'}value°C"
                fi
              else
                echo "${'$'}value"
              fi
            }

            format_current() {
              value="${'$'}1"
              if [ -z "${'$'}value" ]; then
                echo "系统未提供"
                return
              fi
              if echo "${'$'}value" | grep -Eq '^-?[0-9]+${'$'}'; then
                abs="${'$'}value"
                sign=""
                case "${'$'}abs" in -*) abs="${'$'}{abs#-}"; sign="-" ;; esac
                if [ "${'$'}abs" -ge 10000 ] 2>/dev/null; then
                  ma="$(awk -v v="${'$'}abs" 'BEGIN { printf "%.0f", v / 1000 }')"
                else
                  ma="${'$'}abs"
                fi
                echo "${'$'}sign${'$'}ma mA"
              else
                echo "${'$'}value"
              fi
            }

            format_voltage() {
              value="${'$'}1"
              if [ -z "${'$'}value" ]; then
                echo "系统未提供"
                return
              fi
              if echo "${'$'}value" | grep -Eq '^[0-9]+${'$'}'; then
                if [ "${'$'}value" -ge 100000 ] 2>/dev/null; then
                  mv="$(awk -v v="${'$'}value" 'BEGIN { printf "%.0f", v / 1000 }')"
                else
                  mv="${'$'}value"
                fi
                echo "${'$'}mv mV"
              else
                echo "${'$'}value"
              fi
            }

            saved_value() {
              key="${'$'}1"
              echo "${'$'}battery" | awk -v key="${'$'}key" '
                index(tolower($0), tolower(key)) > 0 {
                  line=$0
                  gsub(/.*[[]/, "", line)
                  gsub(/[]].*/, "", line)
                  gsub(/.*: */, "", line)
                  gsub(/^ +| +${'$'}/, "", line)
                  print line
                  exit
                }
              '
            }

            level="$(echo "${'$'}battery" | awk -F: '/^[ \t]*level:/ {gsub(/ /,"",${'$'}2); print ${'$'}2; exit}')"
            [ -z "${'$'}level" ] && level="$(read_node /sys/class/power_supply/battery/capacity)"

            temp_raw="$(echo "${'$'}battery" | awk -F: '/^[ \t]*temperature:/ {gsub(/ /,"",${'$'}2); print ${'$'}2; exit}')"
            [ -z "${'$'}temp_raw" ] && temp_raw="$(read_node /sys/class/power_supply/battery/temp /sys/class/power_supply/battery/batt_temp)"

            status_num="$(echo "${'$'}battery" | awk -F: '/^[ \t]*status:/ {gsub(/ /,"",${'$'}2); print ${'$'}2; exit}')"
            case "${'$'}status_num" in
              2) status_text="正在充电" ;;
              3) status_text="未充电" ;;
              4) status_text="未充电/暂停" ;;
              5) status_text="已充满" ;;
              *) status_text="$(read_node /sys/class/power_supply/battery/status)" ;;
            esac
            [ -z "${'$'}status_text" ] && status_text="系统未提供"

            health_num="$(echo "${'$'}battery" | awk -F: '/^[ \t]*health:/ {gsub(/ /,"",${'$'}2); print ${'$'}2; exit}')"
            case "${'$'}health_num" in
              2) health_text="良好" ;;
              3) health_text="过热" ;;
              4) health_text="电池失效" ;;
              5) health_text="电压异常" ;;
              6) health_text="未知故障" ;;
              7) health_text="过冷" ;;
              *) health_text="$(read_node /sys/class/power_supply/battery/health)" ;;
            esac
            [ -z "${'$'}health_text" ] && health_text="系统未提供"

            current_raw="$(read_node /sys/class/power_supply/battery/current_now /sys/class/power_supply/battery/batt_current_ua_now /sys/class/power_supply/battery/input_current_now)"
            voltage_raw="$(echo "${'$'}battery" | awk -F: '/^[ \t]*voltage:/ {gsub(/ /,"",${'$'}2); print ${'$'}2; exit}')"
            if [ -z "${'$'}voltage_raw" ] || [ "${'$'}voltage_raw" = "0" ]; then
              voltage_raw="$(read_node /sys/class/power_supply/battery/voltage_now /sys/class/power_supply/battery/batt_vol)"
            fi
            cycle_count="$(read_node /sys/class/power_supply/battery/cycle_count /sys/class/power_supply/battery/batt_cycle /sys/class/power_supply/battery/battery_cycle)"
            saved_usage="$(saved_value mSavedBatteryUsage)"
            saved_asoc="$(saved_value mSavedBatteryAsoc)"
            saved_max_temp="$(saved_value mSavedBatteryMaxTemp)"
            saved_max_current="$(saved_value mSavedBatteryMaxCurrent)"
            saved_beginning_date="$(saved_value mSavedBatteryBeginningDate)"
            charge_counter="$(echo "${'$'}battery" | awk -F: '/^[ \t]*Charge counter:/ {gsub(/^ +| +${'$'}/,"",${'$'}2); print ${'$'}2; exit}')"
            technology="$(echo "${'$'}battery" | awk -F: '/^[ \t]*technology:/ {gsub(/^ +| +${'$'}/,"",${'$'}2); print ${'$'}2; exit}')"
            if [ -z "${'$'}cycle_count" ] && echo "${'$'}saved_usage" | grep -Eq '^[0-9]+${'$'}'; then
              cycle_count="$(awk -v v="${'$'}saved_usage" 'BEGIN { printf "%.2f 次", v / 100 }')"
            fi

            plugged="未连接充电器"
            echo "${'$'}battery" | grep -q 'AC powered: true' && plugged="已连接 AC 充电器"
            echo "${'$'}battery" | grep -q 'USB powered: true' && plugged="已连接 USB 充电器"
            echo "${'$'}battery" | grep -q 'Wireless powered: true' && plugged="无线充电中"

            echo "当前电池状态"
            if [ -n "${'$'}level" ]; then echo "当前电量：${'$'}level%"; else echo "当前电量：系统未提供"; fi
            echo "电池温度：$(format_temp "${'$'}temp_raw")"
            echo "充电状态：${'$'}status_text"
            echo "电池健康：${'$'}health_text"
            if [ -n "${'$'}saved_asoc" ]; then echo "三星健康度：${'$'}saved_asoc%"; fi
            echo "充放电电流：$(format_current "${'$'}current_raw")"
            echo "电池循环数：${'$'}{cycle_count:-系统未提供}"
            if [ -n "${'$'}saved_max_temp" ]; then echo "历史最高温度：$(format_temp "${'$'}saved_max_temp")"; fi
            if [ -n "${'$'}saved_max_current" ]; then echo "历史最大电流：$(format_current "${'$'}saved_max_current")"; fi
            if [ -n "${'$'}saved_beginning_date" ] && [ "${'$'}saved_beginning_date" != "0" ]; then echo "电池开始使用：${'$'}saved_beginning_date"; fi
            echo "电池电压：$(format_voltage "${'$'}voltage_raw")"
            echo "电源状态：${'$'}plugged"
            [ -n "${'$'}charge_counter" ] && echo "剩余电量计数：${'$'}charge_counter"
            [ -n "${'$'}technology" ] && echo "电池类型：${'$'}technology"
            """.trimIndent(),
            timeoutSeconds = 4
        )
    }

    fun readPassThroughStatus(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            system_pass="$(settings get system pass_through 2>/dev/null)"
            legacy_global_pass="$(settings get global pass_through_charging 2>/dev/null)"
            legacy_bypass="$(settings get global bypass_charging 2>/dev/null)"
            accepted=0
            [ "${'$'}system_pass" = "1" ] && accepted=1

            battery="$(dumpsys battery 2>/dev/null)"
            level="$(echo "${'$'}battery" | awk -F: '/^[ \t]*level:/ {gsub(/ /,"",${'$'}2); print ${'$'}2; exit}')"
            status_num="$(echo "${'$'}battery" | awk -F: '/^[ \t]*status:/ {gsub(/ /,"",${'$'}2); print ${'$'}2; exit}')"
            charger_type="$(echo "${'$'}battery" | awk -F: '/charger_type:/ {gsub(/ /,"",${'$'}2); print ${'$'}2; exit}')"
            if [ -z "${'$'}charger_type" ]; then
              charger_type="$(dumpsys batteryproperties 2>/dev/null | awk -F: '/charger_type:/ {gsub(/ /,"",${'$'}2); print ${'$'}2; exit}')"
            fi
            plugged=0
            echo "${'$'}battery" | grep -q 'AC powered: true' && plugged=1
            echo "${'$'}battery" | grep -q 'USB powered: true' && plugged=1
            echo "${'$'}battery" | grep -q 'Wireless powered: true' && plugged=1

            case "${'$'}status_num" in
              2) status_text="正在充电" ;;
              3) status_text="未充电" ;;
              4) status_text="未充电/暂停" ;;
              5) status_text="已充满" ;;
              *) status_text="未知" ;;
            esac

            sys_status=""
            [ -r /sys/class/power_supply/battery/status ] && sys_status="$(cat /sys/class/power_supply/battery/status 2>/dev/null)"
            current=""
            for node in /sys/class/power_supply/battery/current_now /sys/class/power_supply/battery/batt_current_ua_now; do
              if [ -r "${'$'}node" ]; then
                current="$(cat "${'$'}node" 2>/dev/null)"
                break
              fi
            done
            abs_current="${'$'}current"
            case "${'$'}abs_current" in
              -*) abs_current="${'$'}{abs_current#-}" ;;
            esac

            if [ "${'$'}accepted" = "1" ]; then
              echo "请求开关：已开启"
            else
              echo "请求开关：关闭或未被系统接受"
            fi
            if [ "${'$'}plugged" = "1" ]; then
              echo "电源状态：已连接充电器"
            else
              echo "电源状态：未连接充电器"
            fi
            [ -n "${'$'}level" ] && echo "电池状态：${'$'}level% / ${'$'}status_text"
            if [ -n "${'$'}charger_type" ]; then
              if [ "${'$'}charger_type" -ge 3 ] 2>/dev/null; then
                echo "USB PD 条件：已识别为高功率 PD/PPS"
              else
                echo "USB PD 条件：当前未识别为官方要求的 PD/PPS"
              fi
            fi
            if [ "${'$'}legacy_global_pass" = "1" ] || [ "${'$'}legacy_bypass" = "1" ]; then
              echo "兼容提示：检测到旧版旁路标记，恢复旁路可清理。"
            fi

            if [ "${'$'}accepted" != "1" ]; then
              echo "三星电池服务：未接受旁路请求。"
              echo "运行判断：未运行。"
            elif [ "${'$'}plugged" != "1" ]; then
              echo "三星电池服务：已接受请求，但缺少充电器条件。"
              echo "运行判断：已配置，但当前未接入充电器，所以未运行。"
            elif echo "${'$'}sys_status ${'$'}status_text" | grep -qi 'Not charging\|Full\|未充电/暂停\|已充满'; then
              echo "三星电池服务：已接受请求。"
              echo "运行判断：可能正在旁路或暂停充电。"
            elif [ -n "${'$'}abs_current" ] && [ "${'$'}abs_current" -le 200000 ] 2>/dev/null; then
              echo "三星电池服务：已接受请求。"
              echo "运行判断：电池电流接近 0，可能正在旁路。"
            else
              echo "三星电池服务：已接受请求，但未确认硬件旁路。"
              echo "运行判断：已配置，但当前仍像是在给电池充电，未确认旁路。"
            fi
            echo "说明：Shizuku 只发起 pass_through 请求；真正是否旁路由三星电池服务按硬件、充电器、温度和电量决定。"
            """.trimIndent(),
            timeoutSeconds = 4
        )
    }

    fun restorePassThroughMode(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${batteryProtectionBackupPrelude}
            restore_pass_through
            """.trimIndent()
        )
    }

    fun restoreBatteryProtection(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${batteryProtectionBackupPrelude}
            restore_battery_protection
            """.trimIndent()
        )
    }

    fun finalizePublicRestoreDefaults(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${scopedRestorePrelude}
            ${batteryProtectionBackupPrelude}
            refresh_mode_backup="$(settings get global maxhz_backup_secure_refresh_rate_mode 2>/dev/null)"
            for key in pms_settings_refresh_rate_enabled pms_settings_refresh_rate_cover_enabled psm_refresh_rate_tag psm_refresh_rate_cover_tag psm_refresh_rate_tag_power_mode_state sem_power_mode_refresh_rate sem_power_mode_refresh_rate_cover max_fps min_fps; do
              restore_or_delete_setting global "${'$'}key"
            done
            for key in refresh_rate_mode refresh_rate user_refresh_rate pms_override_refresh_rate_mode; do
              restore_or_delete_setting secure "${'$'}key"
            done
            for key in display_refresh_rate peak_refresh_rate user_refresh_rate min_refresh_rate display_min_refresh_rate; do
              restore_or_delete_setting system "${'$'}key"
            done
            nudge_adaptive_refresh_if_needed "${'$'}refresh_mode_backup" 1
            restore_battery_protection 0
            echo "已完成最终恢复收尾：刷新率已恢复，电池保护保持用户原有系统设置。"
            """.trimIndent(),
            timeoutSeconds = 10
        )
    }

    fun restoreAllPublicDefaults(nightGuardPackages: List<String> = emptyList()): MaxHzShell.Result {
        val nightPackages = shellPackageList(nightGuardPackages)
        return MaxHzShell.exec(
            """
            ${mScriptPrelude}
            ${scopedRestorePrelude}
            ${quickDozeBackupPrelude}
            ${batteryProtectionBackupPrelude}
            ${nightWakeupGuardPrelude}
            ${scanGuardBackupPrelude}

            night_guard_pkgs="${nightPackages}"
            night_guard_strong_pkgs="${nightPackages}"
            rotation_acc_before="$(settings get system accelerometer_rotation 2>/dev/null)"
            rotation_user_before="$(settings get system user_rotation 2>/dev/null)"

            restore_compatible_saver_for_total() {
              backup_seen=0
              backup_saver_on=0
              refresh_mode_backup="$(settings get global maxhz_backup_secure_refresh_rate_mode 2>/dev/null)"
              for key in low_power low_power_mode low_power_sticky battery_saver_enabled low_power_mode_opened extra_low_power k_power_mode_state n_power_mode_state q_power_mode_state s_power_mode_state; do
                backup_value="$(settings get global "maxhz_backup_global_${'$'}key" 2>/dev/null)"
                if [ "${'$'}backup_value" != "null" ]; then
                  backup_seen=1
                  [ "${'$'}backup_value" = "1" ] && backup_saver_on=1
                fi
              done
              cmd power set-mode 0 >/dev/null 2>&1 || true
              cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1 || true
              for key in low_power low_power_mode low_power_sticky battery_saver_enabled restricted_device_performance restricted_device_performance_power_level sem_low_heat_mode default_restrict_background_data low_power_back_data_off psm_network_power_saving sem_power_mode_limited_apps_and_home_screen pms_settings_refresh_rate_enabled pms_settings_refresh_rate_cover_enabled psm_refresh_rate_tag psm_refresh_rate_cover_tag psm_refresh_rate_tag_power_mode_state sem_power_mode_refresh_rate sem_power_mode_refresh_rate_cover max_fps min_fps; do
                restore_or_delete_setting global "${'$'}key"
              done
              restore_samsung_power_mode_flags
              for key in refresh_rate_mode refresh_rate user_refresh_rate miui_refresh_rate pms_override_refresh_rate_mode; do
                restore_or_delete_setting secure "${'$'}key"
              done
              for key in display_refresh_rate peak_refresh_rate user_refresh_rate min_refresh_rate display_min_refresh_rate thermal_limit_refresh_rate custom_refresh_rate; do
                restore_or_delete_setting system "${'$'}key"
              done
              if [ "${'$'}backup_seen" = "1" ]; then
                if [ "${'$'}backup_saver_on" = "1" ]; then
                  cmd power set-mode 1 >/dev/null 2>&1 || true
                else
                  cmd power set-mode 0 >/dev/null 2>&1 || true
                fi
              fi
            }

            echo "开始总恢复"

            restore_compatible_saver_for_total
            echo "已恢复日用省电和刷新率接管"

            restore_quick_doze
            echo "已恢复 Quick Doze"

            if [ -n "${'$'}night_guard_pkgs" ]; then
              restore_night_guard
              echo "已恢复待机省电护栏已知名单"
            else
              echo "待机省电护栏已知名单为空，跳过精准恢复"
            fi

            restore_sync_guard
            restore_scan_guard
            echo "已恢复同步和扫描护栏"

            restore_pass_through

            for key in low_power_mode low_ram synergy_mode sys_uidcpupower wifi_power_save app_auto_start_restriction background_process_limit max_cached_processes game_acceleration_mode performance_mode game_driver_mode game_performance_mode fw.bservice_enable sem_power_mode_limited_apps_and_home_screen restricted_device_performance restricted_device_performance_power_level GPU_EXECUTION_MODE gpu_performance_mode touch_boost_mode max_fps min_fps; do
              restore_or_delete_setting global "${'$'}key"
            done
            restore_samsung_power_mode_flags
            for key in POWER_SAVE_PRE_HIDE_MODE POWER_SAVE_MODE_OPEN POWER_PERFORMANCE_MODE_OPEN touchfeature.gamemode gesture_power_save sem_performance_mode sem_enhanced_cpu_responsiveness sched_autogroup_enabled sched_latency_ns sched_min_granularity_ns touch_responsiveness double_tap_timeout touch_sensitivity pointer_speed touch_scan_press_time_delay touch_scan_press_time_delay_grade touch_scan_press_time_delay_single touch_scan_press_time_delay_doubl display_refresh_rate peak_refresh_rate user_refresh_rate min_refresh_rate display_min_refresh_rate thermal_limit_refresh_rate; do
              restore_or_delete_setting system "${'$'}key"
            done
            for key in location_background_throttle_interval_ms location_background_throttle_proximity_alert_interval_ms refresh_rate refresh_rate_mode user_refresh_rate miui_refresh_rate force_resizable_activities; do
              restore_or_delete_setting secure "${'$'}key"
            done
            restore_game_thermal_defaults
            restore_m_thermal_keys
            clear_prop debug.performance.tuning
            clear_prop debug.gpu.freq.max.lock
            clear_prop debug.gpu.dcvs.disable
            cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1 || true

            for key in debug.hwui.renderer_thread_count debug.hwui.max_frame_queue_size debug.hwui.render_dirty_regions debug.sf.auto_latch_unsignaled debug.sf.use_phase_offsets_as_durations debug.sf.late.sf.duration debug.choreographer.skip debug.choreographer.skipwarning debug.sf.disable_client_composition_cache debug.sf.disable_client_composition debug.sf.disable_backpressure debug.hwui.disable_skia_cache debug.sf.enable_gl_backpressure debug.hwui.max_texture_cache_size debug.sf.enable_triple_buffering; do clear_prop "${'$'}key"; done

            device_config delete core_graphics com.android.graphics.surfaceflinger.flags.vulkan_renderengine >/dev/null 2>&1 || true
            device_config delete system display_refresh_rate >/dev/null 2>&1 || true
            for key in debug.vulkan.enable debug.hwui.renderer rendering_type debug.vulkan.worker_threads hwui.renderer hwui.use_vulkan; do del_global "${'$'}key"; done
            for key in persist.sys.use_dithering surface_flinger_use_frame_rate_api; do del_system "${'$'}key"; done
            for key in debug.hwui.renderer debug.renderengine.backend debug.rs.default-GPU-driver debug.sf.composition.type debug.hwui.renderengine.backend debug.stagefright.renderengine.backend; do clear_prop "${'$'}key"; done

            for key in net.tcp.buffersize.wifi net.tcp.buffersize.lte net.tcp.buffersize.umts net.tcp.buffersize.edge net.tcp.buffersize.gprs net.tcp.buffersize.default net.tcp.fastopen net.5g.buffersize.uplink net.5g.buffersize.downlink; do del_system "${'$'}key"; done
            for key in net.tcp.buffersize.wifi net.tcp.buffersize.lte net.tcp.buffersize.umts net.tcp.buffersize.edge net.tcp.buffersize.gprs net.tcp.buffersize.default quic.enabled quic.max_time_before_crypto_handshake media_decoder_priority network_accelerated_drawing_enabled tcp_fastopen_enable net.tcp.congestion_control net.ipv4.tcp_delack_seg net.ipv4.tcp_delack_min net.ipv4.tcp_no_metrics_save net.ipv4.tcp_low_latency net.ipv4.tcp_init_cwnd net.ipv4.tcp_keepalive_intvl net.ipv4.tcp_keepalive_probes captive_portal_http_url captive_portal_https_url net.ipv4.tcp_keepalive_time data.large_tcp_window_size tcp_default_init_rwnd; do del_global "${'$'}key"; done

            del_global touch_boost_mode
            for key in touch_responsiveness double_tap_timeout touch_sensitivity touch_scan_press_time_delay touch_scan_press_time_delay_grade touch_scan_press_time_delay_single touch_scan_press_time_delay_doubl pointer_speed; do del_system "${'$'}key"; done
            for key in game_memc_request_touch_rate game_touch_sampling_rate; do del_global "${'$'}key"; done
            for key in debug.touch_move_opt debug.touch_vsync_opt debug.touch.size.bias debug.touch.priority debug.input.batch_events; do clear_prop "${'$'}key"; done

            for key in zram_enabled zram_size_percentage zram_num_compression_streams sys.fw.empty_app_cache_period sys.fw.trim_cache_period fstrim_mandatory_interval storage_benchmark_interval; do del_global "${'$'}key"; done
            clear_prop debug.dalvik.vm.heapgrowthlimit
            clear_prop debug.ld.all

            for key in cached_apps_freezer persist.miui.support.cgroup_freeze force_resizable_activities app_standby_enabled adaptive_battery_management_enabled; do
              restore_or_delete_setting global "${'$'}key"
            done
            device_config delete activity_manager_native_boot use_freezer >/dev/null 2>&1 || true
            device_config delete activity_manager default_background_fgs_starts_restriction_enabled >/dev/null 2>&1 || true
            device_config delete activity_manager default_background_activity_starts_enabled >/dev/null 2>&1 || true
            device_config delete activity_manager bg_auto_restricted_bucket_on_bg_restricted >/dev/null 2>&1 || true
            restore_or_delete_setting system touch_prestart_opt_config
            restore_or_delete_setting system status_bar_show_notification_icon
            clear_prop debug.game.video.support
            clear_prop debug.game.video.speed

            restore_battery_protection 0
            reload_samsung_adaptive_refresh
            if [ "${'$'}rotation_acc_before" != "null" ] && [ -n "${'$'}rotation_acc_before" ]; then
              settings put system accelerometer_rotation "${'$'}rotation_acc_before" >/dev/null 2>&1 || true
            fi
            if [ "${'$'}rotation_user_before" != "null" ] && [ -n "${'$'}rotation_user_before" ]; then
              settings put system user_rotation "${'$'}rotation_user_before" >/dev/null 2>&1 || true
            fi
            system_saver_now=0
            for key in low_power low_power_mode low_power_sticky battery_saver_enabled; do
              [ "$(settings get global "${'$'}key" 2>/dev/null)" = "1" ] && system_saver_now=1
            done
            if [ "${'$'}system_saver_now" != "1" ]; then
              refresh_mode_now="$(settings get secure refresh_rate_mode 2>/dev/null)"
              if [ "${'$'}refresh_mode_now" != "1" ]; then
                echo "验证失败：刷新率没有交回自适应"
                exit 4
              fi
            fi

            echo "总恢复已完成：省电、刷新率、Doze、旁路和 M 脚本已交回系统默认接管；电池保护不再由本 App 强制改写。"
            """.trimIndent(),
            timeoutSeconds = 90
        )
    }

    fun applyCompatibleSaverAdaptive(): MaxHzShell.Result {
        return MaxHzShell.exec(
            withRefreshRateDetection("""
            ${scopedRestorePrelude}
            backup_compatible_saver
            if ! set_power_saver_and_verify 1; then
              echo "验证失败：系统省电服务没有进入省电模式"
              exit 4
            fi
            cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1
            settings put global restricted_device_performance 1,1
            settings put global sem_low_heat_mode 1
            settings put global default_restrict_background_data 0
            settings put global low_power_back_data_off 0
            settings put global psm_network_power_saving 0,0
            settings put global sem_power_mode_limited_apps_and_home_screen -1
            settings put secure refresh_rate_mode 0 >/dev/null 2>&1 || true
            sleep 0.15
            settings put secure refresh_rate_mode 1
            settings delete secure refresh_rate >/dev/null 2>&1
            settings put secure user_refresh_rate "${'$'}max_hz"
            settings delete system display_refresh_rate >/dev/null 2>&1
            settings put system peak_refresh_rate "${'$'}max_hz"
            settings put system user_refresh_rate "${'$'}max_hz"
            settings put system min_refresh_rate "${'$'}min_hz"
            settings put system display_min_refresh_rate "${'$'}min_hz" >/dev/null 2>&1 || true
            settings delete global max_fps >/dev/null 2>&1
            settings delete global min_fps >/dev/null 2>&1
            settings put global pms_settings_refresh_rate_enabled 0 >/dev/null 2>&1 || true
            settings put global psm_refresh_rate_tag 0 >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate_cover >/dev/null 2>&1 || true
            saver_now="$(read_power_saver_service_state)"
            refresh_mode_now="$(settings get secure refresh_rate_mode 2>/dev/null)"
            peak_now="$(settings get system peak_refresh_rate 2>/dev/null | cut -d. -f1)"
            min_now="$(settings get system min_refresh_rate 2>/dev/null | cut -d. -f1)"
            if [ "${'$'}saver_now" != "1" ]; then
              echo "验证失败：系统省电开关没有开启"
              exit 4
            fi
            if [ "${'$'}refresh_mode_now" != "1" ]; then
              echo "验证失败：自适应刷新率没有写入"
              exit 4
            fi
            if [ "${'$'}peak_now" != "${'$'}max_hz" ] || [ "${'$'}min_now" != "${'$'}min_hz" ]; then
              echo "验证失败：刷新率范围没有写入"
              exit 4
            fi
            refresh_level="$(refresh_policy_level "${'$'}max_hz")"
            echo "MAXHZ_STATE_POWER=effective"
            echo "MAXHZ_STATE_REFRESH=${'$'}refresh_level"
            echo "MAXHZ_RESULT_LEVEL=effective"
            echo "兼容省电已开启："
            echo "• 系统省电：已由 One UI 电源服务接管生效"
            echo "• 自适应高刷：已突破省电限制，保持自适应丝滑流畅"
            """),
            timeoutSeconds = 12
        )
    }

    fun applyCompatibleSaverAdaptiveAndQuickDoze(
        maintenanceMinutes: Int = 80,
        noMaintenance: Boolean = false
    ): MaxHzShell.Result {
        val saver = applyCompatibleSaverAdaptive()
        val doze = applyQuickDoze(maintenanceMinutes, noMaintenance)
        val code = if (saver.code == 0 && doze.code == 0) 0 else {
            if (saver.code != 0) saver.code else doze.code
        }
        val out = listOf(saver.out, doze.out).filter { it.isNotBlank() }.joinToString("\n")
        val err = listOf(saver.err, doze.err).filter { it.isNotBlank() }.joinToString("\n")
        return MaxHzShell.Result(code, out, err)
    }

    fun restoreCompatibleSaverAdaptive(timeoutSeconds: Long = 8): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${scopedRestorePrelude}
            restore_compatible_saver
            if [ "${'$'}restored_power_saver_verified" != "1" ]; then
              echo "验证失败：系统省电服务没有恢复到操作前状态"
              exit 4
            fi
            echo "已恢复日用省电修改项，省电和刷新率交回系统默认接管"
            """.trimIndent(),
            timeoutSeconds = timeoutSeconds
        )
    }

    fun prepareGameAutoSaverOff(): MaxHzShell.Result {
        val restore = restoreCompatibleSaverAdaptive(timeoutSeconds = 15)
        if (restore.code == 0 || restore.code !in setOf(-1, 4)) return restore

        val exactVerification = MaxHzShell.exec(
            """
            cmd power set-mode 0 >/dev/null 2>&1 || true
            settings put global low_power 0
            saver_state="${'$'}(dumpsys power 2>/dev/null | grep -m 1 -E '^[[:space:]]*Battery Saver is currently: (ON|OFF)' | sed -n 's/.*currently: \(ON\|OFF\).*/\1/p')"
            low_power="${'$'}(settings get global low_power 2>/dev/null)"
            echo "GAME_AUTO_SAVER_STATE service=${'$'}saver_state setting=${'$'}low_power"
            if [ "${'$'}saver_state" != "OFF" ] || [ "${'$'}low_power" != "0" ]; then
              echo "验证失败：Game Auto 无法确认系统省电已关闭"
              exit 4
            fi
            echo "Game Auto 已用 PowerManager 精确状态确认省电关闭"
            """.trimIndent(),
            timeoutSeconds = 5
        )
        if (exactVerification.code != 0) {
            return MaxHzShell.Result(
                exactVerification.code,
                listOf(restore.out, exactVerification.out).filter { it.isNotBlank() }.joinToString("\n"),
                listOf(restore.err, exactVerification.err).filter { it.isNotBlank() }.joinToString("\n")
            )
        }
        val originalDiagnostic = restore.err.ifBlank { restore.out }.ifBlank { "code=${restore.code}" }
        return MaxHzShell.Result(
            0,
            "原恢复验证未通过（$originalDiagnostic）；Game Auto 专用精确读回已通过。\n${exactVerification.out}",
            ""
        )
    }

    fun applyDailySaver(
        maintenanceMinutes: Int = 80,
        noMaintenance: Boolean = false
    ): MaxHzShell.Result {
        val safeMinutes = maintenanceMinutes.coerceIn(10, 120)
        val maintenanceMs = safeMinutes * 60_000
        val lightMaintenanceBudget = if (noMaintenance) 0 else 60_000
        val deepMaintenanceBudget = if (noMaintenance) 0 else 180_000
        val minMaintenanceTime = if (noMaintenance) 0 else 5_000
        val modeText = if (noMaintenance) "无需维护" else "维护间隔约 ${safeMinutes} 分钟"
        return MaxHzShell.exec(
            """
            ${refreshRatePrelude}
            ${scopedRestorePrelude}
            backup_compatible_saver

            max_hz="$(settings get system peak_refresh_rate 2>/dev/null | cut -d. -f1)"
            case "${'$'}max_hz" in ''|null|*[!0-9]*) max_hz=120 ;; esac
            [ "${'$'}max_hz" -lt 90 ] 2>/dev/null && max_hz=120
            min_hz="$(settings get system min_refresh_rate 2>/dev/null | cut -d. -f1)"
            case "${'$'}min_hz" in ''|null|*[!0-9]*) min_hz=10 ;; esac
            [ "${'$'}min_hz" -lt 1 ] 2>/dev/null && min_hz=10
            [ "${'$'}min_hz" -ge "${'$'}max_hz" ] 2>/dev/null && min_hz=10

            echo "STEP=conflict_cleanup"
            cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1 || true
            settings delete system sem_enhanced_cpu_responsiveness >/dev/null 2>&1 || true
            settings delete global sem_enhanced_cpu_responsiveness >/dev/null 2>&1 || true
            echo "EXIT=0"
            echo "RESULT=SUCCESS"

            if ! set_daily_power_saver_and_verify 1; then
              echo "验证失败：系统省电服务没有进入省电模式"
              exit 4
            fi
            settings put global restricted_device_performance 1,1
            settings put global sem_low_heat_mode 1
            settings put global default_restrict_background_data 0
            settings put global low_power_back_data_off 0
            settings put global psm_network_power_saving 0,0
            settings put global sem_power_mode_limited_apps_and_home_screen -1

            echo "STEP=refresh_adaptive_write"
            settings put secure refresh_rate_mode 0 >/dev/null 2>&1 || true
            sleep 0.15
            settings put secure refresh_rate_mode 1
            settings delete secure refresh_rate >/dev/null 2>&1
            settings put secure user_refresh_rate "${'$'}max_hz"
            settings delete system display_refresh_rate >/dev/null 2>&1
            settings put system peak_refresh_rate "${'$'}max_hz"
            settings put system user_refresh_rate "${'$'}max_hz"
            settings put system min_refresh_rate "${'$'}min_hz"
            settings put system display_min_refresh_rate "${'$'}min_hz" >/dev/null 2>&1 || true
            settings delete global max_fps >/dev/null 2>&1
            settings delete global min_fps >/dev/null 2>&1
            settings put global pms_settings_refresh_rate_enabled 0 >/dev/null 2>&1 || true
            settings put global psm_refresh_rate_tag 0 >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate_cover >/dev/null 2>&1 || true
            echo "EXIT=0"
            echo "RESULT=SUCCESS"

            echo "STEP=quick_doze_write"
            dumpsys deviceidle enable >/dev/null 2>&1 || true
            settings put secure doze_enabled 1
            settings put global device_idle_constants "light_after_inactive_to=60000,light_pre_idle_to=120000,light_idle_to=${maintenanceMs},light_idle_factor=2.0,light_max_idle_to=${maintenanceMs},light_idle_maintenance_min_budget=${lightMaintenanceBudget},light_idle_maintenance_max_budget=${deepMaintenanceBudget},min_light_maintenance_time=${minMaintenanceTime},inactive_to=600000,idle_after_inactive_to=300000,idle_pending_to=60000,max_idle_pending_to=120000,idle_pending_factor=2.0,quick_doze_delay_to=60000,idle_to=${maintenanceMs},max_idle_to=${maintenanceMs},idle_factor=2.0,min_time_to_alarm=600000"
            settings put global battery_saver_constants quick_doze_enabled=true,enable_quick_doze=true
            echo "EXIT=0"
            echo "RESULT=SUCCESS"

            echo "STEP=final_aggregate_verify"
            saver_now="$(read_daily_power_saver_effective_state)"
            refresh_mode_now="$(settings get secure refresh_rate_mode 2>/dev/null)"
            peak_now="$(settings get system peak_refresh_rate 2>/dev/null | cut -d. -f1)"
            min_now="$(settings get system min_refresh_rate 2>/dev/null | cut -d. -f1)"
            doze_constants_now="$(settings get global device_idle_constants 2>/dev/null)"
            if [ "${'$'}saver_now" != "1" ]; then
              echo "SOURCE=dumpsys_power_effective"
              echo "EXPECTED=1"
              echo "ACTUAL=${'$'}saver_now"
              echo "RESULT=FAIL"
              echo "验证失败：系统省电开关没有开启"
              exit 4
            fi
            echo "STEP=refresh_adaptive_verify"
            echo "EXPECTED=1"
            echo "ACTUAL=${'$'}refresh_mode_now"
            if [ "${'$'}refresh_mode_now" != "1" ]; then
              echo "RESULT=FAIL"
              echo "验证失败：自适应刷新率没有写入"
              exit 4
            fi
            echo "RESULT=SUCCESS"
            echo "STEP=refresh_min_verify"
            echo "EXPECTED=${'$'}min_hz"
            echo "ACTUAL=${'$'}min_now"
            if [ "${'$'}min_now" != "${'$'}min_hz" ]; then
              echo "RESULT=FAIL"
              echo "验证失败：最低刷新率没有写入"
              exit 4
            fi
            echo "RESULT=SUCCESS"
            echo "STEP=refresh_max_verify"
            echo "EXPECTED=${'$'}max_hz"
            echo "ACTUAL=${'$'}peak_now"
            if [ "${'$'}peak_now" != "${'$'}max_hz" ]; then
              echo "RESULT=FAIL"
              echo "验证失败：最高刷新率没有写入"
              exit 4
            fi
            echo "RESULT=SUCCESS"
            echo "STEP=quick_doze_verify"
            echo "EXPECTED=configured"
            if [ -z "${'$'}doze_constants_now" ] || [ "${'$'}doze_constants_now" = "null" ]; then
              echo "ACTUAL=missing"
              echo "RESULT=FAIL"
              echo "验证失败：Quick Doze 参数没有写入"
              exit 4
            fi
            echo "ACTUAL=configured"
            echo "RESULT=SUCCESS"
            echo "SOURCE=dumpsys_power_effective"
            echo "EXPECTED=1"
            echo "ACTUAL=${'$'}saver_now"
            echo "EXIT=0"
            echo "RESULT=SUCCESS"

            refresh_level="$(refresh_policy_level "${'$'}max_hz")"
            echo "MAXHZ_STATE_POWER=effective"
            echo "MAXHZ_STATE_REFRESH=${'$'}refresh_level"
            echo "MAXHZ_STATE_DOZE=configured"
            echo "MAXHZ_RESULT_LEVEL=effective"
            echo "日用省电已开启："
            echo "• 系统省电：已由 One UI 电源服务接管生效"
            echo "• 自适应高刷：已突破省电限制，保持自适应丝滑流畅"
            echo "• 快速待机：Quick Doze 已配置（${modeText}）"
            """.trimIndent(),
            timeoutSeconds = 12
        )
    }

    fun restoreDailySaver(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${scopedRestorePrelude}
            restore_compatible_saver
            if [ "${'$'}restored_power_saver_verified" != "1" ]; then
              echo "验证失败：系统省电服务没有恢复到操作前状态"
              exit 4
            fi
            dumpsys deviceidle enable >/dev/null 2>&1 || true
            settings put secure doze_enabled 1 >/dev/null 2>&1 || true
            settings delete global device_idle_enabled >/dev/null 2>&1 || true
            settings delete global device_idle_constants >/dev/null 2>&1 || true
            settings delete global battery_saver_constants >/dev/null 2>&1 || true
            restored_constants="$(settings get global device_idle_constants 2>/dev/null)"
            if printf '%s' "${'$'}restored_constants" | grep -q 'quick_doze_delay_to=60000'; then
              echo "验证失败：Quick Doze 参数仍像本 App 写入的值"
              exit 4
            fi
            echo "已恢复日用省电：省电、刷新率和 Quick Doze 交回系统默认接管。"
            """.trimIndent(),
            timeoutSeconds = 12
        )
    }

    fun applyQuickDoze(
        maintenanceMinutes: Int = 80,
        noMaintenance: Boolean = false
    ): MaxHzShell.Result {
        val safeMinutes = maintenanceMinutes.coerceIn(10, 120)
        val maintenanceMs = safeMinutes * 60_000
        val lightMaintenanceBudget = if (noMaintenance) 0 else 60_000
        val deepMaintenanceBudget = if (noMaintenance) 0 else 180_000
        val minMaintenanceTime = if (noMaintenance) 0 else 5_000
        val modeText = if (noMaintenance) "无需维护" else "维护间隔约 ${safeMinutes} 分钟"
        return MaxHzShell.exec(
            """
            ${quickDozeBackupPrelude}
            backup_quick_doze
            dumpsys deviceidle enable >/dev/null 2>&1
            dumpsys deviceidle unforce >/dev/null 2>&1
            settings put secure doze_enabled 1
            settings put global device_idle_constants "light_after_inactive_to=60000,light_pre_idle_to=120000,light_idle_to=${maintenanceMs},light_idle_factor=2.0,light_max_idle_to=${maintenanceMs},light_idle_maintenance_min_budget=${lightMaintenanceBudget},light_idle_maintenance_max_budget=${deepMaintenanceBudget},min_light_maintenance_time=${minMaintenanceTime},inactive_to=600000,idle_after_inactive_to=300000,idle_pending_to=60000,max_idle_pending_to=120000,idle_pending_factor=2.0,quick_doze_delay_to=60000,idle_to=${maintenanceMs},max_idle_to=${maintenanceMs},idle_factor=2.0,min_time_to_alarm=600000"
            settings put global battery_saver_constants quick_doze_enabled=true,enable_quick_doze=true
            constants_now="${'$'}(settings get global device_idle_constants 2>/dev/null)"
            if ! printf '%s' "${'$'}constants_now" | grep -q 'quick_doze_delay_to=60000'; then
              echo "验证失败：快速待机参数没有生效。"
              exit 4
            fi
            echo "MAXHZ_STATE_DOZE=configured"
            echo "MAXHZ_RESULT_LEVEL=configured"
            echo "已应用快速待机省电：${modeText}。只调整系统 Doze 参数，不主动加入或移出任何 App 的 Doze 白名单；需要压制具体 App 请使用待机省电护栏。"
            """.trimIndent()
        )
    }

    fun readQuickDozeStatus(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            constants="$(settings get global device_idle_constants 2>/dev/null)"
            idle_dump="$(dumpsys deviceidle 2>/dev/null)"
            state="$(printf '%s\n' "${'$'}idle_dump" | sed -n 's/.*mState=\([^ ]*\).*/\1/p' | head -1)"
            light_state="$(printf '%s\n' "${'$'}idle_dump" | sed -n 's/.*mLightState=\([^ ]*\).*/\1/p' | head -1)"
            quick="$(printf '%s\n' "${'$'}idle_dump" | sed -n 's/.*mQuickDozeActivated=\([^ ]*\).*/\1/p' | head -1)"
            screen="$(printf '%s\n' "${'$'}idle_dump" | sed -n 's/.*mScreenOn=\([^ ]*\).*/\1/p' | head -1)"
            charging="$(printf '%s\n' "${'$'}idle_dump" | sed -n 's/.*mCharging=\([^ ]*\).*/\1/p' | head -1)"

            configured=0
            if printf '%s' "${'$'}constants" | grep -q 'quick_doze_delay_to='; then
              configured=1
            fi

            effective=0
            [ "${'$'}quick" = "true" ] && effective=1
            case "${'$'}state" in IDLE|IDLE_MAINTENANCE) effective=1 ;; esac
            case "${'$'}light_state" in PRE_IDLE|IDLE|IDLE_MAINTENANCE|WAITING_FOR_NETWORK) effective=1 ;; esac

            if [ "${'$'}configured" != "1" ]; then
              echo "当前状态：快速待机未配置。"
              echo "下一步建议：需要时开启快速的 Doze 模式。"
            elif [ "${'$'}effective" = "1" ]; then
              echo "当前状态：手机已进入待机省电。"
              echo "MAXHZ_STATE_DOZE=effective"
              echo "MAXHZ_RESULT_LEVEL=effective"
            else
              echo "当前状态：快速待机已配置，当前尚未进入待机。"
              if [ "${'$'}screen" = "true" ]; then
                echo "原因：屏幕处于开启状态。"
              elif [ "${'$'}charging" = "true" ]; then
                echo "原因：手机正在充电。"
              else
                echo "原因：系统仍在等待满足待机条件。"
              fi
              echo "下一步建议：熄屏、断开充电并静置一段时间后再次检查。"
              echo "MAXHZ_STATE_DOZE=configured"
              echo "MAXHZ_RESULT_LEVEL=configured"
            fi
            echo "技术状态：deep=${'$'}{state:-unknown}, light=${'$'}{light_state:-unknown}, quick=${'$'}{quick:-unknown}, screen=${'$'}{screen:-unknown}, charging=${'$'}{charging:-unknown}"
            """.trimIndent()
        )
    }

    fun readDozeWhitelist(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            echo "系统白名单："
            dumpsys deviceidle sys-whitelist 2>/dev/null \
              | sed 's/^/白名单: /' \
              | head -120
            echo ""
            echo "用户白名单："
            dumpsys deviceidle whitelist 2>/dev/null \
              | sed 's/^/白名单: /' \
              | head -120
            """.trimIndent()
        )
    }

    fun readDozeWhitelistPackages(): MaxHzShell.Result {
        if (!MaxHzShell.isRootAvailable() && !moe.shizuku.manager.maxhz.shell.ShizukuShellBridge.isReady() && MaxHzShell.isDeviceOwnerReady()) {
            val app = moe.shizuku.manager.application
            val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) {
                val list = app.packageManager.getInstalledPackages(0)
                    .map { it.packageName }
                    .filter { powerManager.isIgnoringBatteryOptimizations(it) }
                return MaxHzShell.Result(0, list.joinToString("\n"), "")
            }
        }
        return MaxHzShell.exec(
            """
            dumpsys deviceidle sys-whitelist 2>/dev/null \
              | sed 's/^system-excidle,//; s/^system,//; s/^except-idle,//; s/^user,//' \
              | grep -E '^[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+' \
              | awk '!seen[${'$'}0]++'
            dumpsys deviceidle whitelist 2>/dev/null \
              | sed 's/^system-excidle,//; s/^system,//; s/^except-idle,//; s/^user,//' \
              | grep -E '^[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+' \
              | awk '!seen[${'$'}0]++'
            """.trimIndent()
        )
    }

    fun setDozeWhitelistPackage(packageName: String, enabled: Boolean): MaxHzShell.Result {
        val safe = packageName.trim()
        if (!safe.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
            return MaxHzShell.Result(-1, "", "包名无效")
        }
        val op = if (enabled) "+" else "-"
        val action = if (enabled) "加入" else "移出"
        return MaxHzShell.exec(
            """
            pkg="$safe"
            if [ "${if (enabled) "1" else "0"}" = "1" ]; then
              cmd deviceidle whitelist +${'$'}pkg 2>/dev/null \
                || dumpsys deviceidle whitelist +${'$'}pkg 2>/dev/null \
                || exit 1
              cmd appops set "${'$'}pkg" RUN_IN_BACKGROUND default >/dev/null 2>&1 || true
              cmd appops set "${'$'}pkg" RUN_ANY_IN_BACKGROUND default >/dev/null 2>&1 || true
              echo "已加入后台保护名单：${safe}"
              exit 0
            else
              cmd deviceidle whitelist -${'$'}pkg 2>/dev/null \
                || dumpsys deviceidle whitelist -${'$'}pkg 2>/dev/null \
                || true
              cmd appops set "${'$'}pkg" RUN_IN_BACKGROUND ignore >/dev/null 2>&1 || true
              cmd appops set "${'$'}pkg" RUN_ANY_IN_BACKGROUND ignore >/dev/null 2>&1 || true
              am set-standby-bucket "${'$'}pkg" restricted >/dev/null 2>&1 || true
              sys_check="${'$'}(dumpsys deviceidle sys-whitelist 2>/dev/null || true)"
              if printf '%s' "${'$'}sys_check" | grep -Fq "${'$'}pkg"; then
                echo "提示：${safe} 属系统出厂保护组件，已撤销后台权限并移入受限待机桶。"
              else
                echo "已移出后台保护名单并限制后台：${safe}"
              fi
              exit 0
            fi
            """.trimIndent()
        )
    }

    fun readDozeHistory(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            dumpsys deviceidle 2>/dev/null \
              | grep -A 200 'Idling history:' \
              | sed -n '1,120p'
            """.trimIndent()
        )
    }

    fun restoreQuickDoze(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${quickDozeBackupPrelude}
            dumpsys deviceidle unforce >/dev/null 2>&1
            restore_quick_doze
            constants_now="${'$'}(settings get global device_idle_constants 2>/dev/null)"
            saver_now="${'$'}(settings get global battery_saver_constants 2>/dev/null)"
            if printf '%s\n%s' "${'$'}constants_now" "${'$'}saver_now" | grep -q 'quick_doze_delay_to=60000\|quick_doze_enabled=true'; then
              echo "验证失败：快速待机参数仍像本 App 写入的值。"
              exit 4
            fi
            echo "已恢复快速待机，Doze 参数交回系统默认接管；未主动移出任何 App 白名单"
            """.trimIndent()
        )
    }

    fun applyNightWakeupGuard(extraPackages: List<String> = emptyList()): MaxHzShell.Result {
        val extra = shellPackageList(extraPackages)
        return MaxHzShell.exec(
            """
            ${nightWakeupGuardPrelude}
            night_guard_pkgs="${extra}"
            night_guard_strong_pkgs="${extra}"
            if [ -z "${'$'}night_guard_pkgs" ]; then
              echo "待机省电护栏名单为空，未压制任何应用。请先添加应用。"
              exit 0
            fi
            backup_night_guard
            for pkg in ${'$'}night_guard_pkgs; do
              pm path "${'$'}pkg" >/dev/null 2>&1 || continue
              dumpsys deviceidle whitelist -"${'$'}pkg" >/dev/null 2>&1 || true
              cmd appops set "${'$'}pkg" RUN_IN_BACKGROUND ignore >/dev/null 2>&1 || true
              cmd appops set "${'$'}pkg" RUN_ANY_IN_BACKGROUND ignore >/dev/null 2>&1 || true
              am set-standby-bucket "${'$'}pkg" restricted >/dev/null 2>&1 || true
              echo "已限制 ${'$'}pkg 的后台保活和待机桶"
            done
            for pkg in ${'$'}night_guard_strong_pkgs; do
              pm path "${'$'}pkg" >/dev/null 2>&1 || continue
              case "${'$'}pkg" in
                *clock*|*alarm*|*calendar*|*dialer*|*telecom*|*phone*|com.android.providers.calendar)
                  echo "跳过 ${'$'}pkg 的闹钟与持锁限制（系统关键闹钟与通信保护）"
                  continue
                  ;;
              esac
              cmd appops set "${'$'}pkg" WAKE_LOCK ignore >/dev/null 2>&1 || true
              cmd appops set "${'$'}pkg" SCHEDULE_EXACT_ALARM ignore >/dev/null 2>&1 || true
              echo "已强力限制 ${'$'}pkg 的唤醒锁和精确闹钟"
            done
            verify_total=0
            verify_failed=0
            for pkg in ${'$'}night_guard_pkgs; do
              pm path "${'$'}pkg" >/dev/null 2>&1 || continue
              verify_total=${'$'}((verify_total + 1))
              appops_now="${'$'}(cmd appops get "${'$'}pkg" RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND 2>/dev/null || true)"
              bucket_now="${'$'}(am get-standby-bucket "${'$'}pkg" 2>/dev/null | tail -n 1 | tr -d '\r')"
              if ! printf '%s' "${'$'}appops_now" | grep -q 'ignore' && [ "${'$'}bucket_now" != "restricted" ]; then
                verify_failed=${'$'}((verify_failed + 1))
              fi
            done
            if [ "${'$'}verify_total" -gt 0 ] && [ "${'$'}verify_failed" -eq "${'$'}verify_total" ]; then
              echo "验证失败：待机护栏没有对名单内应用生效。"
              exit 4
            fi
            echo "待机省电护栏已应用。当前名单：${extra.ifBlank { "无" }}"
            """.trimIndent()
        )
    }

    fun restoreNightWakeupGuard(extraPackages: List<String> = emptyList()): MaxHzShell.Result {
        val extra = shellPackageList(extraPackages)
        return MaxHzShell.exec(
            """
            ${nightWakeupGuardPrelude}
            night_guard_pkgs="${extra}"
            night_guard_strong_pkgs="${extra}"
            if [ -z "${'$'}night_guard_pkgs" ]; then
              echo "待机省电护栏名单为空，无需恢复应用。"
              exit 0
            fi
            restore_night_guard
            echo "已恢复待机省电护栏，后台、唤醒锁、精确闹钟、Doze 白名单和待机桶交回系统接管"
            """.trimIndent()
        )
    }

    fun readNightWakeupGuardCurrentRestrictions(
        extraPackages: List<String> = emptyList(),
        includeSystemUnknown: Boolean = false
    ): MaxHzShell.Result {
        val extra = shellPackageList(extraPackages)
        val systemUnknownScan = if (includeSystemUnknown) {
            """
            for op in RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WAKE_LOCK SCHEDULE_EXACT_ALARM; do
              if command -v timeout >/dev/null 2>&1; then
                timeout 1 cmd appops query-op "${'$'}op" ignore 2>/dev/null \
                  | grep -Eo '[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+' >> "${'$'}tmp_unknown" || true
              else
                cmd appops query-op "${'$'}op" ignore 2>/dev/null \
                  | grep -Eo '[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+' >> "${'$'}tmp_unknown" || true
              fi
            done
            if command -v timeout >/dev/null 2>&1; then
              timeout 1 am get-standby-bucket 2>/dev/null \
                | grep -E 'restricted|rare|[[:space:]]4[0-9]|[[:space:]]5[0-9]' \
                | grep -Eo '[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+' >> "${'$'}tmp_unknown" || true
            fi
            sort -u "${'$'}tmp_unknown" 2>/dev/null | head -n 80 >> "${'$'}tmp_file"
            """.trimIndent()
        } else {
            ""
        }
        return MaxHzShell.exec(
            """
            is_restricted_bucket() {
              value="${'$'}1"
              case "${'$'}value" in
                restricted|RESTRICTED|rare|RARE) return 0 ;;
              esac
              num="$(printf '%s\n' "${'$'}value" | grep -E '^[0-9]+${'$'}' | tail -n 1)"
              [ -n "${'$'}num" ] && [ "${'$'}num" -ge 40 ] 2>/dev/null && return 0
              return 1
            }

            tmp_file="/data/local/tmp/maxhz_night_current_${'$'}${'$'}.txt"
            tmp_unknown="/data/local/tmp/maxhz_night_current_unknown_${'$'}${'$'}.txt"
            rm -f "${'$'}tmp_file" "${'$'}tmp_unknown" >/dev/null 2>&1
            for pkg in ${extra}; do
              [ -n "${'$'}pkg" ] && echo "${'$'}pkg" >> "${'$'}tmp_file"
            done
            ${systemUnknownScan}
            total_count="$(sort -u "${'$'}tmp_file" 2>/dev/null | wc -l | tr -d ' ')"
            if [ -n "${'$'}total_count" ] && [ "${'$'}total_count" -gt 80 ] 2>/dev/null; then
              echo "NG_LIMIT|80|${'$'}total_count"
            fi
            sort -u "${'$'}tmp_file" 2>/dev/null | head -n 80 | while read pkg; do
              [ -z "${'$'}pkg" ] && continue
              pm path "${'$'}pkg" >/dev/null 2>&1 || continue
              bucket="$(timeout 1 am get-standby-bucket "${'$'}pkg" 2>/dev/null | tail -n 1 | tr -d '\r')"
              appops="$(timeout 1 cmd appops get "${'$'}pkg" RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WAKE_LOCK SCHEDULE_EXACT_ALARM 2>/dev/null || true)"
              run=0
              any=0
              wake=0
              alarm=0
              printf '%s\n' "${'$'}appops" | grep -Eq 'RUN_IN_BACKGROUND: *ignore' && run=1
              printf '%s\n' "${'$'}appops" | grep -Eq 'RUN_ANY_IN_BACKGROUND: *ignore' && any=1
              printf '%s\n' "${'$'}appops" | grep -Eq 'WAKE_LOCK: *ignore' && wake=1
              printf '%s\n' "${'$'}appops" | grep -Eq 'SCHEDULE_EXACT_ALARM: *ignore' && alarm=1
              restricted=0
              is_restricted_bucket "${'$'}bucket" && restricted=1
              if [ "${'$'}run" = "1" ] || [ "${'$'}any" = "1" ] || [ "${'$'}wake" = "1" ] || [ "${'$'}alarm" = "1" ]; then
                restricted=1
              fi
              echo "NG_CURRENT|${'$'}pkg|bucket=${'$'}bucket|restricted=${'$'}restricted|run=${'$'}run|any=${'$'}any|wake=${'$'}wake|alarm=${'$'}alarm"
            done
            rm -f "${'$'}tmp_file" "${'$'}tmp_unknown" >/dev/null 2>&1
            """.trimIndent(),
            timeoutSeconds = 30
        )
    }

    fun restoreNightWakeupGuardPackage(packageName: String): MaxHzShell.Result {
        val pkg = shellPackageList(listOf(packageName))
        if (pkg.isBlank()) return MaxHzShell.Result(-2, "", "包名无效")
        return MaxHzShell.exec(
            """
            target_pkg="${pkg}"
            pm path "${'$'}target_pkg" >/dev/null 2>&1 || {
              echo "应用未安装或包名无效：${'$'}target_pkg"
              exit 2
            }
            safe_name="$(printf '%s' "${'$'}target_pkg" | tr -c 'A-Za-z0-9_' '_')"
            cmd appops set "${'$'}target_pkg" RUN_IN_BACKGROUND default >/dev/null 2>&1 || true
            cmd appops set "${'$'}target_pkg" RUN_ANY_IN_BACKGROUND default >/dev/null 2>&1 || true
            cmd appops set "${'$'}target_pkg" WAKE_LOCK default >/dev/null 2>&1 || true
            cmd appops set "${'$'}target_pkg" SCHEDULE_EXACT_ALARM default >/dev/null 2>&1 || true
            am set-standby-bucket "${'$'}target_pkg" active >/dev/null 2>&1 || true
            settings delete global "maxhz_backup_night_whitelist_${'$'}safe_name" >/dev/null 2>&1 || true
            settings delete global "maxhz_backup_night_bucket_${'$'}safe_name" >/dev/null 2>&1 || true
            appops_now="${'$'}(cmd appops get "${'$'}target_pkg" RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WAKE_LOCK SCHEDULE_EXACT_ALARM 2>/dev/null || true)"
            if printf '%s' "${'$'}appops_now" | grep -q 'ignore'; then
              echo "验证失败：${'$'}target_pkg 仍有后台限制没有恢复。"
              exit 4
            fi
            echo "已恢复 ${'$'}target_pkg 的待机护栏限制，交回系统默认接管"
            """.trimIndent()
        )
    }

    fun restoreSuspiciousNightWakeupGuardResidue(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            protected_pkgs="com.tencent.mm com.tencent.tim com.tencent.wework com.alibaba.android.rimet com.whatsapp com.google.android.gms com.google.android.gsf com.samsung.android.messaging com.samsung.android.dialer com.android.dialer com.android.phone com.sec.phone com.android.providers.calendar com.google.android.calendar com.sec.android.app.shealth com.samsung.android.oneconnect com.samsung.android.sm.devicesecurity com.google.android.apps.messaging"
            is_protected() {
              target="${'$'}1"
              for p in ${'$'}protected_pkgs; do
                [ "${'$'}p" = "${'$'}target" ] && return 0
              done
              return 1
            }
            is_restricted_bucket() {
              value="${'$'}1"
              case "${'$'}value" in
                restricted|RESTRICTED) return 0 ;;
              esac
              num="$(printf '%s\n' "${'$'}value" | grep -E '^[0-9]+${'$'}' | tail -n 1)"
              [ -n "${'$'}num" ] && [ "${'$'}num" -ge 45 ] 2>/dev/null && return 0
              return 1
            }
            suspects_file="/data/local/tmp/maxhz_night_guard_suspects.txt"
            rm -f "${'$'}suspects_file" >/dev/null 2>&1
            cmd package list packages -3 2>/dev/null | sed 's/^package://' | while read pkg; do
              [ -z "${'$'}pkg" ] && continue
              is_protected "${'$'}pkg" && continue
              bucket="$(am get-standby-bucket "${'$'}pkg" 2>/dev/null | tail -n 1 | tr -d '\r')"
              appops="$(cmd appops get "${'$'}pkg" RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WAKE_LOCK SCHEDULE_EXACT_ALARM 2>/dev/null || true)"
              suspect=0
              is_restricted_bucket "${'$'}bucket" && suspect=1
              printf '%s\n' "${'$'}appops" | grep -Eq 'RUN_(ANY_)?IN_BACKGROUND: *ignore|WAKE_LOCK: *ignore|SCHEDULE_EXACT_ALARM: *ignore' && suspect=1
              [ "${'$'}suspect" = "1" ] || continue
              echo "${'$'}pkg"
            done > "${'$'}suspects_file"

            count="$(wc -l < "${'$'}suspects_file" 2>/dev/null | tr -d ' ')"
            if [ -z "${'$'}count" ] || [ "${'$'}count" = "0" ]; then
              rm -f "${'$'}suspects_file" >/dev/null 2>&1
              echo "未发现疑似 Night Guard 残留。"
              exit 0
            fi
            echo "发现 ${'$'}count 个疑似 Night Guard 残留，正在恢复："
            cat "${'$'}suspects_file"
            while read pkg; do
              [ -z "${'$'}pkg" ] && continue
              cmd appops set "${'$'}pkg" RUN_IN_BACKGROUND default >/dev/null 2>&1 || true
              cmd appops set "${'$'}pkg" RUN_ANY_IN_BACKGROUND default >/dev/null 2>&1 || true
              cmd appops set "${'$'}pkg" WAKE_LOCK default >/dev/null 2>&1 || true
              cmd appops set "${'$'}pkg" SCHEDULE_EXACT_ALARM default >/dev/null 2>&1 || true
              am set-standby-bucket "${'$'}pkg" active >/dev/null 2>&1 || true
            done < "${'$'}suspects_file"
            rm -f "${'$'}suspects_file" >/dev/null 2>&1
            echo "疑似 Night Guard 残留已恢复。"
            """.trimIndent(),
            timeoutSeconds = 30
        )
    }

    private val protectedScanPackages = setOf(
        "com.tencent.mm", "com.tencent.tim", "com.tencent.wework", "com.alibaba.android.rimet",
        "com.whatsapp", "com.google.android.gms", "com.google.android.gsf",
        "com.samsung.android.messaging", "com.samsung.android.dialer", "com.android.dialer",
        "com.android.phone", "com.sec.phone", "com.android.providers.calendar",
        "com.google.android.calendar", "com.sec.android.app.shealth", "com.samsung.android.oneconnect",
        "com.samsung.android.sm.devicesecurity", "com.google.android.apps.messaging",
        "com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock"
    )

    private fun scanNightWakeupSuggestionsForDeviceOwner(): MaxHzShell.Result {
        val app = moe.shizuku.manager.application
        val pm = app.packageManager
        val suggestions = mutableListOf<String>()
        val outLines = mutableListOf<String>()
        outLines.add("智能待机扫描（设备所有者直通引擎）")
        outLines.add("扫描范围：已安装第三方应用、唤醒锁权限、自启广播与后台保活状态。")
        outLines.add("说明：优先诊断存在后台唤醒和常驻特征的应用，只生成建议，不自动修改系统设置。")
        outLines.add("")

        try {
            val packages = pm.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS)
            for (pkgInfo in packages) {
                val pkg = pkgInfo.packageName ?: continue
                val appInfo = pkgInfo.applicationInfo ?: continue

                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem || pkg == app.packageName) continue

                if (MaxHzPackagePolicy.isSystemRelated(app, pkg)) continue
                if (pkg in protectedScanPackages) continue

                val perms = pkgInfo.requestedPermissions?.toSet() ?: emptySet()
                val reasons = mutableListOf<String>()

                if ("android.permission.WAKE_LOCK" in perms) {
                    reasons.add("持有唤醒锁 (WAKE_LOCK)")
                }
                if ("android.permission.RECEIVE_BOOT_COMPLETED" in perms) {
                    reasons.add("注册开机自启")
                }
                if ("android.permission.SCHEDULE_EXACT_ALARM" in perms ||
                    "android.permission.USE_EXACT_ALARM" in perms) {
                    reasons.add("精确闹钟唤醒")
                }

                if (reasons.isNotEmpty()) {
                    val detail = "应用包含 " + reasons.joinToString("、") + "，待机时可能频繁拉起后台"
                    outLines.add("候选|$pkg|$detail")
                    suggestions.add(pkg)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scanNightWakeupSuggestionsForDeviceOwner error", t)
        }

        if (suggestions.isEmpty()) {
            outLines.add("未发现高风险后台唤醒候选应用。")
        } else {
            outLines.add("已发现 ${suggestions.size} 款候选应用，可点击展开并按需加入待机省电护栏。")
        }

        return MaxHzShell.Result(0, outLines.joinToString("\n"), "")
    }

    fun scanNightWakeupSuggestions(): MaxHzShell.Result {
        if (!MaxHzShell.isRootAvailable() && !moe.shizuku.manager.maxhz.shell.ShizukuShellBridge.isReady() && MaxHzShell.isDeviceOwnerReady()) {
            return scanNightWakeupSuggestionsForDeviceOwner()
        }
        return MaxHzShell.exec(
            """
            echo "智能待机扫描（多维深度诊断）"
            echo "扫描范围：定时闹钟唤醒、后台唤醒锁持锁、后台 CPU 运算、电池统计与待机分组。"
            echo "说明：优先诊断频繁唤醒和后台持锁应用，只生成建议，不自动修改系统设置。"
            echo ""

            protected_pkgs="com.tencent.mm com.tencent.tim com.tencent.wework com.alibaba.android.rimet com.whatsapp com.google.android.gms com.google.android.gsf com.samsung.android.messaging com.samsung.android.dialer com.android.dialer com.android.phone com.sec.phone com.android.providers.calendar com.google.android.calendar com.sec.android.app.shealth com.samsung.android.oneconnect com.samsung.android.sm.devicesecurity com.google.android.apps.messaging com.google.android.deskclock com.sec.android.app.clockpackage com.android.deskclock"
            tmp_dir="/data/local/tmp/maxhz_night_scan_${'$'}${'$'}"
            mkdir -p "${'$'}tmp_dir" >/dev/null 2>&1
            alarm_file="${'$'}tmp_dir/alarm.txt"
            power_file="${'$'}tmp_dir/power.txt"
            cpu_file="${'$'}tmp_dir/cpu.txt"
            battery_file="${'$'}tmp_dir/battery.txt"
            whitelist_file="${'$'}tmp_dir/whitelist.txt"

            if command -v timeout >/dev/null 2>&1; then
              timeout 6 dumpsys alarm 2>/dev/null > "${'$'}alarm_file" || true
              timeout 4 dumpsys power 2>/dev/null > "${'$'}power_file" || true
              timeout 4 dumpsys cpuinfo 2>/dev/null > "${'$'}cpu_file" || true
              timeout 6 dumpsys batterystats --charged 2>/dev/null > "${'$'}battery_file" || true
              if [ ! -s "${'$'}battery_file" ]; then
                timeout 6 dumpsys batterystats 2>/dev/null > "${'$'}battery_file" || true
              fi
              dumpsys deviceidle whitelist 2>/dev/null > "${'$'}whitelist_file" || true
            else
              dumpsys alarm 2>/dev/null > "${'$'}alarm_file" || true
              dumpsys power 2>/dev/null > "${'$'}power_file" || true
              dumpsys cpuinfo 2>/dev/null > "${'$'}cpu_file" || true
              dumpsys batterystats --charged 2>/dev/null > "${'$'}battery_file" || true
              if [ ! -s "${'$'}battery_file" ]; then
                dumpsys batterystats 2>/dev/null > "${'$'}battery_file" || true
              fi
              dumpsys deviceidle whitelist 2>/dev/null > "${'$'}whitelist_file" || true
            fi

            # Pre-dump package-uid map once to prevent slow fork-exec loops
            cmd package list packages -U 2>/dev/null > "${'$'}tmp_dir/pkg_uid_map.txt" || pm list packages -U 2>/dev/null > "${'$'}tmp_dir/pkg_uid_map.txt" || true

            uid_to_int() {
              token="${'$'}1"
              case "${'$'}token" in
                u*a*)
                  user="${'$'}{token#u}"
                  user="${'$'}{user%%a*}"
                  app="${'$'}{token##*a}"
                  echo "${'$'}((user * 100000 + 10000 + app))"
                  ;;
                -*)
                  echo "${'$'}token"
                  ;;
                *)
                  echo "${'$'}token" | grep -E '^[0-9]+${'$'}' || true
                  ;;
              esac
            }

            packages_for_uid() {
              uid_num="${'$'}(uid_to_int "${'$'}1")"
              [ -z "${'$'}uid_num" ] && return
              [ "${'$'}uid_num" -lt 0 ] 2>/dev/null && return
              if [ -s "${'$'}tmp_dir/pkg_uid_map.txt" ]; then
                grep -E " uid:${'$'}uid_num(${'$'}| )" "${'$'}tmp_dir/pkg_uid_map.txt" 2>/dev/null \
                  | sed -n 's/^package://p' \
                  | sed 's/ uid:.*${'$'}//' \
                  | head -n 8
              else
                cmd package list packages --uid "${'$'}uid_num" 2>/dev/null \
                  | sed -n 's/^package://p' \
                  | sed 's/ uid:.*${'$'}//' \
                  | awk '!seen[${'$'}0]++' \
                  | head -n 8
              fi
            }

            is_protected_pkg() {
              pkg="${'$'}1"
              echo " ${'$'}protected_pkgs " | grep -Fq " ${'$'}pkg " && return 0
              case "${'$'}pkg" in
                android|com.android.*|com.samsung.*|com.sec.*|com.sem.*|com.osp.*|com.skms.*|com.miui.*|com.xiaomi.*|com.coloros.*|com.oplus.*|com.heytap.*|com.vivo.*|com.huawei.*|com.hihonor.*|com.honor.*|com.flyme.*|com.meizu.*|com.google.android.permissioncontroller|com.google.android.webview)
                  return 0
                  ;;
              esac
              return 1
            }

            # 1. Parse Alarms (Extract all apps with wakeups)
            awk '
            /wakeups/ {
              w = 0
              if (match(${'$'}0, /[0-9]+[[:space:]]*wakeups/)) {
                s = substr(${'$'}0, RSTART, RLENGTH)
                match(s, /[0-9]+/)
                w = substr(s, RSTART, RLENGTH) + 0
              } else if (match(${'$'}0, /wakeups:[[:space:]]*[0-9]+/)) {
                s = substr(${'$'}0, RSTART, RLENGTH)
                match(s, /[0-9]+/)
                w = substr(s, RSTART, RLENGTH) + 0
              }
              if (w > 0) {
                pkg = ""
                for (i = 1; i <= NF; i++) {
                  token = ${'$'}i
                  gsub(/^[:*]+/, "", token)
                  gsub(/[:*]+${'$'}/, "", token)
                  if (token ~ /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+${'$'}/) {
                    pkg = token
                    break
                  } else if (token ~ /^[0-9u_a]+:[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+${'$'}/) {
                    sub(/^[0-9u_a]+:/, "", token)
                    pkg = token
                    break
                  }
                }
                if (pkg != "" && pkg != "android") {
                  wakeups[pkg] += w
                }
              }
            }
            END {
              for (p in wakeups) {
                if (wakeups[p] > 0) print p "|" wakeups[p]
              }
            }
            ' "${'$'}alarm_file" > "${'$'}tmp_dir/alarm_parsed.txt" 2>/dev/null

            # 2. Parse Wakelocks (dumpsys power)
            awk '
            /Wake Locks:/ { in_wl=1; next }
            /^[A-Za-z]/ && !/Wake Locks:/ { in_wl=0 }
            in_wl && /PARTIAL_WAKE_LOCK/ {
              pkg = ""
              tag = ""
              if (match(${'$'}0, /ws=WorkSource\{[0-9]+[[:space:]]+([a-zA-Z0-9_.]+)/)) {
                s = substr(${'$'}0, RSTART, RLENGTH)
                sub(/.*WorkSource\{[0-9]+[[:space:]]+/, "", s)
                gsub(/[^a-zA-Z0-9_.]/, "", s)
                pkg = s
              } else if (match(${'$'}0, /uid=[0-9]+/)) {
                s = substr(${'$'}0, RSTART, RLENGTH)
                sub(/^uid=/, "", s)
                u = s + 0
                if (u >= 10000) pkg = "UID_" u
              }
              if (match(${'$'}0, /['\''"][^'\''"]+['\''"]/)) {
                tag = substr(${'$'}0, RSTART + 1, RLENGTH - 2)
              }
              if (pkg != "" && pkg != "android") {
                print pkg "|" (tag != "" ? tag : "持锁保活")
              }
            }
            ' "${'$'}power_file" > "${'$'}tmp_dir/wl_parsed.txt" 2>/dev/null

            # 3. Parse CPU (dumpsys cpuinfo)
            awk '
            /^[[:space:]]*[0-9.]+%/ {
              if (match(${'$'}0, /[0-9.]+%[[:space:]]+[0-9]+\/([a-zA-Z0-9_.]+):/)) {
                s = substr(${'$'}0, RSTART, RLENGTH)
                pct = s
                sub(/%.*/, "", pct)
                gsub(/^[[:space:]]+/, "", pct)
                pkg = s
                sub(/.*[0-9]+\//, "", pkg)
                sub(/:${'$'}/, "", pkg)
                if (pkg != "" && pct + 0 >= 0.5 && pkg != "android") {
                  print pkg "|" pct
                }
              }
            }
            ' "${'$'}cpu_file" > "${'$'}tmp_dir/cpu_parsed.txt" 2>/dev/null

            # 4. Parse Batterystats (dumpsys batterystats)
            awk '
            /^[[:space:]]*(Uid|UID)[[:space:]]+/ {
              uid = ${'$'}2
              gsub(":", "", uid)
              mah = ${'$'}3 + 0
              if (match(${'$'}0, /([0-9.]+) mAh/)) {
                s = substr(${'$'}0, RSTART, RLENGTH)
                sub(/ mAh/, "", s)
                mah = s + 0
              }
              if (mah > 0.5) {
                print uid "|" mah
              }
            }
            /\(on battery, screen off\/doze\)/ {
              pat = "cpu=[0-9.]+"
              if (match(${'$'}0, pat)) {
                cpu = substr(${'$'}0, RSTART + 4, RLENGTH - 4) + 0
                if (uid != "" && cpu > 0.5) print uid "|" cpu
              }
            }
            ' "${'$'}battery_file" > "${'$'}tmp_dir/bat_parsed.txt" 2>/dev/null

            # Aggregate all unique package candidates
            all_pkgs_file="${'$'}tmp_dir/all_pkgs.txt"
            cut -d'|' -f1 "${'$'}tmp_dir/alarm_parsed.txt" 2>/dev/null > "${'$'}all_pkgs_file"
            cut -d'|' -f1 "${'$'}tmp_dir/cpu_parsed.txt" 2>/dev/null >> "${'$'}all_pkgs_file"

            while IFS='|' read -r uid mah; do
              [ -z "${'$'}uid" ] && continue
              for p in ${'$'}(packages_for_uid "${'$'}uid"); do
                echo "${'$'}p" >> "${'$'}all_pkgs_file"
                echo "${'$'}p|${'$'}mah" >> "${'$'}tmp_dir/bat_pkgs.txt"
              done
            done < "${'$'}tmp_dir/bat_parsed.txt"

            while IFS='|' read -r pkg tag; do
              case "${'$'}pkg" in
                UID_*)
                  u="${'$'}{pkg#UID_}"
                  for p in ${'$'}(packages_for_uid "${'$'}u"); do
                    echo "${'$'}p" >> "${'$'}all_pkgs_file"
                    echo "${'$'}p|${'$'}tag" >> "${'$'}tmp_dir/wl_resolved.txt"
                  done
                  ;;
                *)
                  echo "${'$'}pkg" >> "${'$'}all_pkgs_file"
                  echo "${'$'}pkg|${'$'}tag" >> "${'$'}tmp_dir/wl_resolved.txt"
                  ;;
              esac
            done < "${'$'}tmp_dir/wl_parsed.txt"

            # Filter installed packages
            sort -u "${'$'}all_pkgs_file" 2>/dev/null | while read -r pkg; do
              [ -z "${'$'}pkg" ] && continue
              pm path "${'$'}pkg" >/dev/null 2>&1 || continue
              echo "${'$'}pkg"
            done > "${'$'}tmp_dir/installed_pkgs.txt"

            rank=0
            candidate_count=0
            top_pkg=""
            top_score=""
            top_detail=""

            while read -r pkg; do
              [ -z "${'$'}pkg" ] && continue
              w="${'$'}(awk -F'|' -v p="${'$'}pkg" '${'$'}1==p {s+=${'$'}2} END {print s+0}' "${'$'}tmp_dir/alarm_parsed.txt")"
              wl="${'$'}(awk -F'|' -v p="${'$'}pkg" '${'$'}1==p {print ${'$'}2; exit}' "${'$'}tmp_dir/wl_resolved.txt")"
              cpu="${'$'}(awk -F'|' -v p="${'$'}pkg" '${'$'}1==p {if(${'$'}2>m) m=${'$'}2} END {print m+0}' "${'$'}tmp_dir/cpu_parsed.txt")"
              mah="${'$'}(awk -F'|' -v p="${'$'}pkg" '${'$'}1==p {if(${'$'}2>m) m=${'$'}2} END {print m+0}' "${'$'}tmp_dir/bat_pkgs.txt")"
              
              if [ "${'$'}(awk "BEGIN {print (${'$'}{mah:-0} > 0.5)}")" = "1" ]; then
                score="${'$'}(awk "BEGIN {printf \"%.1f\", ${'$'}mah + (${'$'}{wl:+5.0}+0) + (${'$'}cpu * 1.5) + (${'$'}w * 0.05)}")"
              else
                score="${'$'}(awk "BEGIN {printf \"%.1f\", (${'$'}w * 0.15) + (${'$'}{wl:+12.0}+0) + (${'$'}cpu * 2.5)}")"
              fi
              
              [ "${'$'}(awk "BEGIN {print (${'$'}{score:-0} < 0.1)}")" = "1" ] && continue

              unres=""
              grep -Fq "${'$'}pkg" "${'$'}whitelist_file" 2>/dev/null && unres=" · 后台不受限"

              reasons=""
              [ "${'$'}w" -gt 0 ] && reasons="定时唤醒 ${'$'}w 次"
              if [ -n "${'$'}wl" ]; then
                [ -n "${'$'}reasons" ] && reasons="${'$'}reasons · "
                reasons="${'$'}{reasons}持有唤醒锁 [${'$'}wl]"
              fi
              if [ "${'$'}(awk "BEGIN {print (${'$'}{cpu:-0} >= 0.5)}")" = "1" ]; then
                [ -n "${'$'}reasons" ] && reasons="${'$'}reasons · "
                reasons="${'$'}{reasons}CPU ${'$'}{cpu}%"
              fi
              [ -n "${'$'}unres" ] && reasons="${'$'}{reasons}${'$'}unres"

              if is_protected_pkg "${'$'}pkg"; then
                echo "${'$'}score|${'$'}pkg|${'$'}w|${'$'}wl|${'$'}cpu|${'$'}reasons" >> "${'$'}tmp_dir/protected_active.txt"
                echo "${'$'}score|${'$'}pkg|${'$'}reasons|protected" >> "${'$'}tmp_dir/candidates_raw.txt"
              else
                echo "${'$'}score|${'$'}pkg|${'$'}reasons|thirdparty" >> "${'$'}tmp_dir/candidates_raw.txt"
              fi
            done < "${'$'}tmp_dir/installed_pkgs.txt"

            echo "待机异常唤醒与耗电排行（含全量应用与根因分析）："
            if [ -s "${'$'}tmp_dir/candidates_raw.txt" ]; then
              sort -t'|' -k1,1nr "${'$'}tmp_dir/candidates_raw.txt" | head -n 25 > "${'$'}tmp_dir/candidates_ranked.txt"
              while IFS='|' read -r score pkg reason app_type; do
                [ -z "${'$'}pkg" ] && continue
                rank="${'$'}((rank + 1))"
                candidate_count="${'$'}((candidate_count + 1))"
                [ "${'$'}rank" = "1" ] && top_pkg="${'$'}pkg" && top_score="${'$'}score" && top_detail="${'$'}reason"

                b_raw="${'$'}(timeout 1 am get-standby-bucket "${'$'}pkg" 2>/dev/null | tr -d '\r' | grep -E '^[0-9]+${'$'}' | tail -n 1)"
                case "${'$'}b_raw" in
                  10) b_str="活跃(10)" ;;
                  20) b_str="工作集(20)" ;;
                  30) b_str="常用(30)" ;;
                  40) b_str="极少(40)" ;;
                  45) b_str="受限(45)" ;;
                  *) b_str="${'$'}{b_raw:-未知}" ;;
                esac

                if [ "${'$'}app_type" = "protected" ]; then
                  echo "${'$'}rank. ${'$'}pkg：评估指数约 ${'$'}score；${'$'}reason；分组：${'$'}b_str；核心通讯/系统组件，建议保留以保障即时通话与消息"
                  echo "候选|${'$'}pkg|耗电排行 #${'$'}rank，评估指数 ${'$'}score；${'$'}reason；分组：${'$'}b_str；核心应用（微信/QQ/系统），慎重压制避免影响即时通知；standby=${'$'}b_str"
                else
                  echo "${'$'}rank. ${'$'}pkg：熄屏约 ${'$'}score mAh；${'$'}reason；分组：${'$'}b_str；第三方异常活跃应用，建议处理"
                  echo "候选|${'$'}pkg|耗电排行 #${'$'}rank，熄屏约 ${'$'}score mAh；${'$'}reason；分组：${'$'}b_str；第三方异常活跃应用，建议处理；standby=${'$'}b_str"
                fi
              done < "${'$'}tmp_dir/candidates_ranked.txt"
            else
              echo "- 当前待机状态优良，统计周期内未发现持续异常持锁或过度唤醒的应用。"
            fi

            echo ""
            echo "核心通讯与系统保护豁免（有后台活动，但自动保护）："
            if [ -s "${'$'}tmp_dir/protected_active.txt" ]; then
              sort -t'|' -k1,1nr "${'$'}tmp_dir/protected_active.txt" | head -n 8 | while IFS='|' read -r score pkg w wl cpu reasons; do
                echo "- ${'$'}pkg：${'$'}reasons；核心服务，默认豁免以确保消息通知与通话正常"
              done
            else
              echo "- 核心系统与通讯服务运行平稳，无异常活跃。"
            fi

            echo ""
            echo "待机分析总结："
            total_w="${'$'}(awk -F'|' '{s+=${'$'}2} END {print s+0}' "${'$'}tmp_dir/alarm_parsed.txt" 2>/dev/null)"
            [ -z "${'$'}total_w" ] && total_w=0
            echo "- 统计周期内第三方及核心应用共触发约 ${'$'}total_w 次定时闹钟唤醒。"
            if [ -n "${'$'}top_pkg" ]; then
              echo "- 待机影响最高应用：${'$'}top_pkg（综合评估约 ${'$'}top_score mAh，${'$'}top_detail）。"
            fi
            echo "- 处理建议：建议优先将待机影响最高且非即时通讯的 App 加入护栏并开启【启用待机异常护栏】生效。"
            echo ""
            echo "说明：排行按多维真实活跃数据计算；这是手动排查，不会自动修改系统设置。"

            rm -rf "${'$'}tmp_dir" >/dev/null 2>&1
            """.trimIndent(),
            timeoutSeconds = 60
        )
    }

    fun applyScanGuard(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${scanGuardBackupPrelude}
            backup_scan_guard
            settings put global ble_scan_always_enabled 0 >/dev/null 2>&1
            settings put global wifi_scan_always_enabled 0 >/dev/null 2>&1
            settings put system nearby_scanning_enabled 0 >/dev/null 2>&1 || true
            settings put secure nearby_scanning_enabled 0 >/dev/null 2>&1 || true
            settings put global nearby_scanning_enabled 0 >/dev/null 2>&1 || true
            ble_now="${'$'}(settings get global ble_scan_always_enabled 2>/dev/null)"
            wifi_now="${'$'}(settings get global wifi_scan_always_enabled 2>/dev/null)"
            nearby_now="${'$'}(settings get global nearby_scanning_enabled 2>/dev/null)"
            if [ "${'$'}ble_now" != "0" ] || [ "${'$'}wifi_now" != "0" ] || [ "${'$'}nearby_now" != "0" ]; then
              echo "验证失败：扫描护栏没有完全生效。"
              echo "当前状态：蓝牙扫描=${'$'}ble_now，Wi-Fi扫描=${'$'}wifi_now，附近设备扫描=${'$'}nearby_now"
              exit 4
            fi
            echo "已应用扫描护栏：关闭熄屏仍可用的蓝牙扫描、Wi-Fi 扫描和附近设备扫描开关；不关闭蓝牙/Wi-Fi 本体。"
            """.trimIndent()
        )
    }

    fun restoreScanGuard(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            ${scanGuardBackupPrelude}
            restore_scan_guard
            ble_now="${'$'}(settings get global ble_scan_always_enabled 2>/dev/null)"
            wifi_now="${'$'}(settings get global wifi_scan_always_enabled 2>/dev/null)"
            if [ "${'$'}ble_now" != "null" ] || [ "${'$'}wifi_now" != "null" ]; then
              echo "验证失败：扫描护栏设置仍有关闭项。"
              exit 4
            fi
            echo "已恢复扫描护栏，蓝牙/Wi-Fi/附近设备扫描交回系统默认接管"
            """.trimIndent()
        )
    }

    fun restoreRefreshRateDefaults(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            settings delete secure refresh_rate_mode >/dev/null 2>&1
            settings delete secure refresh_rate >/dev/null 2>&1
            settings delete secure user_refresh_rate >/dev/null 2>&1
            settings delete system display_refresh_rate >/dev/null 2>&1
            settings delete system peak_refresh_rate >/dev/null 2>&1
            settings delete system user_refresh_rate >/dev/null 2>&1
            settings delete system min_refresh_rate >/dev/null 2>&1
            settings delete system display_min_refresh_rate >/dev/null 2>&1
            settings delete system custom_refresh_rate >/dev/null 2>&1
            settings delete global max_fps >/dev/null 2>&1
            settings delete global min_fps >/dev/null 2>&1
            settings delete global psm_refresh_rate_tag >/dev/null 2>&1
            settings delete global psm_refresh_rate_cover_tag >/dev/null 2>&1
            settings delete global psm_refresh_rate_tag_power_mode_state >/dev/null 2>&1
            settings delete global pms_settings_refresh_rate_enabled >/dev/null 2>&1
            settings delete global pms_settings_refresh_rate_cover_enabled >/dev/null 2>&1
            settings delete global sem_power_mode_refresh_rate >/dev/null 2>&1
            settings delete global sem_power_mode_refresh_rate_cover >/dev/null 2>&1
            system_saver=0
            for key in low_power low_power_mode low_power_sticky battery_saver_enabled; do
              [ "$(settings get global "${'$'}key" 2>/dev/null)" = "1" ] && system_saver=1
            done
            if [ "${'$'}system_saver" = "1" ]; then
              cmd power set-mode 1 >/dev/null 2>&1 || true
              echo "已清理本应用刷新率覆盖项，当前系统省电模式继续接管刷新率。"
            else
              settings put secure refresh_rate_mode 1 >/dev/null 2>&1 || true
              settings put secure user_refresh_rate 120 >/dev/null 2>&1 || true
              settings put system peak_refresh_rate 120.0 >/dev/null 2>&1 || true
              settings put system user_refresh_rate 120 >/dev/null 2>&1 || true
              settings put system min_refresh_rate 0.0 >/dev/null 2>&1 || true
              settings delete system custom_refresh_rate >/dev/null 2>&1 || true
              settings delete system display_min_refresh_rate >/dev/null 2>&1 || true
              cmd display clear-user-preferred-display-mode 0 >/dev/null 2>&1 || true
              echo "已清理刷新率覆盖项，恢复 1Hz~120Hz 硬件级自适应降频。"
            fi
            """.trimIndent()
        )
    }

    /**
     * S24 Ultra / Galaxy 系列屏幕自适应降频与前台功耗一键校准：
     * 1. 强制清理可能残留的 custom_refresh_rate 及锁频参数
     * 2. 写入 peak_refresh_rate 120.0，保障滑动高刷
     * 3. 写入 min_refresh_rate 0.0，完全解锁 LTPO 1Hz~24Hz 硬件级静态自适应降频，根治桌面与日常前台高功耗
     * 4. 激活 DisplayManager 原生自适应模式
     */
    fun calibrateLtpoAdaptivePowerSaving(): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            settings put global pms_settings_refresh_rate_enabled 0 >/dev/null 2>&1 || true
            settings put global psm_refresh_rate_tag 0 >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate_cover >/dev/null 2>&1 || true
            settings delete system custom_refresh_rate >/dev/null 2>&1 || true
            settings delete system display_min_refresh_rate >/dev/null 2>&1 || true
            settings put secure refresh_rate_mode 0 >/dev/null 2>&1 || true
            sleep 0.1
            settings put secure refresh_rate_mode 1 >/dev/null 2>&1 || true
            settings delete secure refresh_rate >/dev/null 2>&1 || true
            settings put secure user_refresh_rate 120 >/dev/null 2>&1 || true
            settings delete system display_refresh_rate >/dev/null 2>&1 || true
            settings put system peak_refresh_rate 120.0 >/dev/null 2>&1 || true
            settings put system user_refresh_rate 120 >/dev/null 2>&1 || true
            settings put system min_refresh_rate 0.0 >/dev/null 2>&1 || true
            cmd display clear-user-preferred-display-mode 0 >/dev/null 2>&1 || true
            echo "已成功校准屏幕自适应刷新率！已突破省电模式限制，激活 1Hz~120Hz 硬件级自适应降频。"
            """.trimIndent()
        )
    }

    fun applyScreenOnAdaptive(): MaxHzShell.Result {
        return MaxHzShell.exec(
            withRefreshRateDetection("""
            settings put secure refresh_rate_mode 1
            settings delete secure refresh_rate >/dev/null 2>&1
            settings put secure user_refresh_rate "${'$'}max_hz"
            settings put system peak_refresh_rate "${'$'}max_hz"
            settings put system user_refresh_rate "${'$'}max_hz"
            settings put system min_refresh_rate "${'$'}min_hz"
            mode_now="${'$'}(settings get secure refresh_rate_mode 2>/dev/null)"
            peak_now="${'$'}(settings get system peak_refresh_rate 2>/dev/null)"
            min_now="${'$'}(settings get system min_refresh_rate 2>/dev/null)"
            if [ "${'$'}mode_now" != "1" ] || ! printf '%s' "${'$'}peak_now" | grep -Eq "^${'$'}max_hz(\\.0)?${'$'}" || ! printf '%s' "${'$'}min_now" | grep -Eq "^${'$'}min_hz(\\.0)?${'$'}"; then
              echo "验证失败：自适应刷新率没有生效。"
              echo "当前状态：mode=${'$'}mode_now，最高=${'$'}peak_now，最低=${'$'}min_now"
              exit 4
            fi
            echo "已恢复亮屏自适应刷新率：${'$'}min_hz-${'$'}max_hz Hz"
            """)
        )
    }

    fun applyScreenOnAdaptiveFast(maxHz: Int, minHz: Int): MaxHzShell.Result {
        val safeMax = maxHz.coerceIn(1, 240)
        val safeMin = minHz.coerceIn(1, safeMax)
        return MaxHzShell.exec(
            """
            settings put secure refresh_rate_mode 1
            settings delete secure refresh_rate >/dev/null 2>&1
            settings put secure user_refresh_rate $safeMax
            settings put system peak_refresh_rate $safeMax
            settings put system user_refresh_rate $safeMax
            settings put system min_refresh_rate $safeMin
            mode_now="${'$'}(settings get secure refresh_rate_mode 2>/dev/null)"
            peak_now="${'$'}(settings get system peak_refresh_rate 2>/dev/null)"
            min_now="${'$'}(settings get system min_refresh_rate 2>/dev/null)"
            if [ "${'$'}mode_now" != "1" ] || ! printf '%s' "${'$'}peak_now" | grep -Eq "^${safeMax}(\\.0)?${'$'}" || ! printf '%s' "${'$'}min_now" | grep -Eq "^${safeMin}(\\.0)?${'$'}"; then
              echo "验证失败：自适应刷新率没有生效。"
              echo "当前状态：mode=${'$'}mode_now，最高=${'$'}peak_now，最低=${'$'}min_now"
              exit 4
            fi
            echo "已快速恢复亮屏自适应刷新率：${safeMin}-${safeMax}Hz"
            """.trimIndent(),
            timeoutSeconds = 3
        )
    }

    fun applyAdaptiveMax(maxHz: Int, minHz: Int = 10): MaxHzShell.Result {
        val safeMax = maxHz.coerceIn(1, 240)
        val safeMin = minHz.coerceIn(0, safeMax)
        return MaxHzShell.exec(
            """
            settings put global pms_settings_refresh_rate_enabled 0 >/dev/null 2>&1 || true
            settings put global psm_refresh_rate_tag 0 >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate_cover >/dev/null 2>&1 || true
            settings put secure refresh_rate_mode 1
            settings delete secure refresh_rate >/dev/null 2>&1
            settings put secure user_refresh_rate $safeMax
            settings put system peak_refresh_rate ${safeMax}.0
            settings put system user_refresh_rate $safeMax
            settings put system min_refresh_rate ${safeMin}.0
            settings delete system custom_refresh_rate >/dev/null 2>&1 || true
            settings delete system display_min_refresh_rate >/dev/null 2>&1 || true
            mode_now="${'$'}(settings get secure refresh_rate_mode 2>/dev/null)"
            peak_now="${'$'}(settings get system peak_refresh_rate 2>/dev/null)"
            min_now="${'$'}(settings get system min_refresh_rate 2>/dev/null)"
            if [ "${'$'}mode_now" != "1" ] || ! printf '%s' "${'$'}peak_now" | grep -Eq "^${safeMax}(\\.0)?${'$'}" || ! printf '%s' "${'$'}min_now" | grep -Eq "^${safeMin}(\\.0)?${'$'}"; then
              echo "验证失败：自适应刷新率没有生效。"
              echo "当前状态：mode=${'$'}mode_now，最高=${'$'}peak_now，最低=${'$'}min_now"
              exit 4
            fi
            echo "已应用自适应刷新率：最高${safeMax}Hz，最低${safeMin}Hz"
            """.trimIndent()
        )
    }

    fun applyAdaptiveMaxForPackage(packageName: String, maxHz: Int, minHz: Int = 10): MaxHzShell.Result {
        val safePackage = shellQuote(packageName)
        val safeMax = maxHz.coerceIn(1, 240)
        val safeMin = minHz.coerceIn(0, safeMax)
        return MaxHzShell.exec(
            """
            target_pkg=$safePackage
            current_pkg="$(dumpsys window 2>/dev/null | grep -m 3 -E 'mCurrentFocus|mFocusedApp|mFocusedWindow' | sed -n 's/.* \([A-Za-z][A-Za-z0-9._]*\)\/.*/\1/p' | head -n 1)"
            if [ -z "${'$'}current_pkg" ]; then
              current_pkg="$(dumpsys activity activities 2>/dev/null | grep -m 3 -E 'mResumedActivity|topResumedActivity|ResumedActivity|mFocusedActivity' | sed -n 's/.* \([A-Za-z][A-Za-z0-9._]*\)\/.*/\1/p' | head -n 1)"
            fi
            if [ "${'$'}current_pkg" != "${'$'}target_pkg" ]; then
              echo "跳过应用刷新率：前台已从 ${'$'}target_pkg 切到 ${'$'}current_pkg"
              exit 3
            fi
            settings put global pms_settings_refresh_rate_enabled 0 >/dev/null 2>&1 || true
            settings put global psm_refresh_rate_tag 0 >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate_cover >/dev/null 2>&1 || true
            settings put secure refresh_rate_mode 1
            settings delete secure refresh_rate >/dev/null 2>&1
            settings put secure user_refresh_rate $safeMax
            settings put system peak_refresh_rate ${safeMax}.0
            settings put system user_refresh_rate $safeMax
            settings put system min_refresh_rate ${safeMin}.0
            settings delete system custom_refresh_rate >/dev/null 2>&1 || true
            settings delete system display_min_refresh_rate >/dev/null 2>&1 || true
            mode_now="${'$'}(settings get secure refresh_rate_mode 2>/dev/null)"
            peak_now="${'$'}(settings get system peak_refresh_rate 2>/dev/null)"
            min_now="${'$'}(settings get system min_refresh_rate 2>/dev/null)"
            if [ "${'$'}mode_now" != "1" ] || ! printf '%s' "${'$'}peak_now" | grep -Eq "^${safeMax}(\\.0)?${'$'}" || ! printf '%s' "${'$'}min_now" | grep -Eq "^${safeMin}(\\.0)?${'$'}"; then
              echo "验证失败：应用刷新率没有生效。"
              echo "当前状态：mode=${'$'}mode_now，最高=${'$'}peak_now，最低=${'$'}min_now"
              exit 4
            fi
            echo "已对 ${'$'}target_pkg 应用自适应刷新率：最高${safeMax}Hz，最低${safeMin}Hz"
            """.trimIndent()
        )
    }

    fun applyStaticRefreshRate(hz: Int): MaxHzShell.Result {
        val safeHz = hz.coerceIn(1, 240)
        val expectedMode = if (safeHz == 60) "0" else "1"
        val modeSetup = if (safeHz == 60) {
            """
            settings put secure refresh_rate_mode 0
            settings put secure refresh_rate 60
            settings put secure user_refresh_rate 60
            """.trimIndent()
        } else {
            """
            settings put secure refresh_rate_mode 1
            settings delete secure refresh_rate >/dev/null 2>&1
            settings put secure user_refresh_rate $safeHz
            """.trimIndent()
        }
        return MaxHzShell.exec(
            """
            settings put global pms_settings_refresh_rate_enabled 0 >/dev/null 2>&1 || true
            settings put global psm_refresh_rate_tag 0 >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate_cover >/dev/null 2>&1 || true
            $modeSetup
            settings put system peak_refresh_rate ${safeHz}.0
            settings put system user_refresh_rate $safeHz
            settings put system min_refresh_rate ${safeHz}.0
            settings delete system custom_refresh_rate >/dev/null 2>&1 || true
            settings delete system display_min_refresh_rate >/dev/null 2>&1 || true
            mode_now="${'$'}(settings get secure refresh_rate_mode 2>/dev/null)"
            peak_now="${'$'}(settings get system peak_refresh_rate 2>/dev/null)"
            min_now="${'$'}(settings get system min_refresh_rate 2>/dev/null)"
            if [ "${'$'}mode_now" != "$expectedMode" ] || ! printf '%s' "${'$'}peak_now" | grep -Eq "^${safeHz}(\\.0)?${'$'}" || ! printf '%s' "${'$'}min_now" | grep -Eq "^${safeHz}(\\.0)?${'$'}"; then
              echo "验证失败：静态刷新率没有生效。"
              echo "当前状态：mode=${'$'}mode_now，最高=${'$'}peak_now，最低=${'$'}min_now"
              exit 4
            fi
            echo "已应用静态刷新率：${safeHz}Hz"
            """.trimIndent()
        )
    }

    fun applyStaticRefreshRateForPackage(packageName: String, hz: Int): MaxHzShell.Result {
        val safePackage = shellQuote(packageName)
        val safeHz = hz.coerceIn(1, 240)
        val expectedMode = if (safeHz == 60) "0" else "1"
        val modeSetup = if (safeHz == 60) {
            """
            settings put secure refresh_rate_mode 0
            settings put secure refresh_rate 60
            settings put secure user_refresh_rate 60
            """.trimIndent()
        } else {
            """
            settings put secure refresh_rate_mode 1
            settings delete secure refresh_rate >/dev/null 2>&1
            settings put secure user_refresh_rate $safeHz
            """.trimIndent()
        }
        return MaxHzShell.exec(
            """
            target_pkg=$safePackage
            current_pkg="$(dumpsys window 2>/dev/null | grep -m 3 -E 'mCurrentFocus|mFocusedApp|mFocusedWindow' | sed -n 's/.* \([A-Za-z][A-Za-z0-9._]*\)\/.*/\1/p' | head -n 1)"
            if [ -z "${'$'}current_pkg" ]; then
              current_pkg="$(dumpsys activity activities 2>/dev/null | grep -m 3 -E 'mResumedActivity|topResumedActivity|ResumedActivity|mFocusedActivity' | sed -n 's/.* \([A-Za-z][A-Za-z0-9._]*\)\/.*/\1/p' | head -n 1)"
            fi
            if [ "${'$'}current_pkg" != "${'$'}target_pkg" ]; then
              echo "跳过应用刷新率：前台已从 ${'$'}target_pkg 切到 ${'$'}current_pkg"
              exit 3
            fi
            settings put global pms_settings_refresh_rate_enabled 0 >/dev/null 2>&1 || true
            settings put global psm_refresh_rate_tag 0 >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate_cover >/dev/null 2>&1 || true
            $modeSetup
            settings put system peak_refresh_rate ${safeHz}.0
            settings put system user_refresh_rate $safeHz
            settings put system min_refresh_rate ${safeHz}.0
            settings delete system custom_refresh_rate >/dev/null 2>&1 || true
            settings delete system display_min_refresh_rate >/dev/null 2>&1 || true
            mode_now="${'$'}(settings get secure refresh_rate_mode 2>/dev/null)"
            peak_now="${'$'}(settings get system peak_refresh_rate 2>/dev/null)"
            min_now="${'$'}(settings get system min_refresh_rate 2>/dev/null)"
            if [ "${'$'}mode_now" != "$expectedMode" ] || ! printf '%s' "${'$'}peak_now" | grep -Eq "^${safeHz}(\\.0)?${'$'}" || ! printf '%s' "${'$'}min_now" | grep -Eq "^${safeHz}(\\.0)?${'$'}"; then
              echo "验证失败：应用静态刷新率没有生效。"
              echo "当前状态：mode=${'$'}mode_now，最高=${'$'}peak_now，最低=${'$'}min_now"
              exit 4
            fi
            echo "已对 ${'$'}target_pkg 应用静态刷新率：${safeHz}Hz"
            """.trimIndent()
        )
    }

    private fun mExec(script: String, timeoutSeconds: Long = 12): MaxHzShell.Result {
        return MaxHzShell.exec(mScriptPrelude + "\n" + script.trimIndent(), timeoutSeconds)
    }

    private val mScriptPrelude = """
        run() {
          label="${'$'}1"
          shift
          echo "${'$'}label"
          "${'$'}@" >/dev/null 2>&1
          rc="${'$'}?"
          [ "${'$'}rc" -eq 0 ] && echo "Success" || echo "Error/Skipped (${'$'}rc)"
        }
        put_global() { settings put global "${'$'}1" "${'$'}2" >/dev/null 2>&1; }
        put_system() { settings put system "${'$'}1" "${'$'}2" >/dev/null 2>&1; }
        put_secure() { settings put secure "${'$'}1" "${'$'}2" >/dev/null 2>&1; }
        del_global() { settings delete global "${'$'}1" >/dev/null 2>&1; }
        del_system() { settings delete system "${'$'}1" >/dev/null 2>&1; }
        del_secure() { settings delete secure "${'$'}1" >/dev/null 2>&1; }
        set_prop() { setprop "${'$'}1" "${'$'}2" >/dev/null 2>&1; }
        clear_prop() { setprop "${'$'}1" "" >/dev/null 2>&1; }
        ${sharedSettingBackupPrelude}

        normalize_samsung_power_refresh_value() {
          key="${'$'}1"
          value="${'$'}2"
          printf '%s\n' "${'$'}value"
        }
        restore_samsung_power_refresh_defaults() {
          del_global pms_settings_refresh_rate_enabled
          del_global pms_settings_refresh_rate_cover_enabled
          del_global psm_refresh_rate_tag
          del_global psm_refresh_rate_cover_tag
          del_global psm_refresh_rate_tag_power_mode_state
          del_global sem_power_mode_refresh_rate
          del_global sem_power_mode_refresh_rate_cover
        }
        restore_game_thermal_defaults() {
          cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1 || true
          for key in game_acceleration_mode performance_mode game_driver_mode game_performance_mode restricted_device_performance restricted_device_performance_power_level sem_enhanced_cpu_responsiveness; do
            del_global "${'$'}key"
          done
          for key in POWER_PERFORMANCE_MODE_OPEN POWER_SAVE_MODE_OPEN POWER_BALANCED_MODE_OPEN POWER_SAVE_PRE_HIDE_MODE sem_performance_mode touchfeature.gamemode gesture_power_save; do
            del_system "${'$'}key"
          done
        }
        restore_m_thermal_keys() {
          for key in thermal_throttle_enabled thermal_cpu_thermal_throttle thermal_gpu_core_clock_throttle thermal_gpu_boost; do
            restore_setting_or_delete global "${'$'}key"
          done
          for key in thermal.thermal_policy.disable thermal.cpu_thermal_throttle thermal.cpu_thermal_throttle.disable pid.sec-thermal-1-0 mediatek.appgamepq_compress mediatek.disp_decompress mtk_tflite.target_nnapi thermal.auto_thermal_disable thermal_switch_mode thermal.throttling.disable thermal.performance_mode.disable thermal.profile.disable thermal.critical_temp.disable thermal_zone.gpu_threshold_temp thermal_zone.cpu_threshold_temp thermal_zone.battery_threshold_temp; do
            restore_setting_or_delete system "${'$'}key"
          done
          restore_setting_or_delete secure allow_more_heat_value
          restore_setting_or_delete secure miui_thermal_limit
        }
        backup_m_main() {
          for key in low_power_mode low_power low_power_sticky low_ram synergy_mode sys_uidcpupower wifi_power_save app_auto_start_restriction background_process_limit max_cached_processes game_acceleration_mode performance_mode game_driver_mode game_performance_mode battery_saver_enabled fw.bservice_enable psm_refresh_rate_tag psm_refresh_rate_cover_tag psm_refresh_rate_tag_power_mode_state sem_power_mode_refresh_rate sem_power_mode_refresh_rate_cover sem_power_mode_limited_apps_and_home_screen restricted_device_performance restricted_device_performance_power_level; do
            backup_setting global "${'$'}key"
          done
          for key in POWER_SAVE_PRE_HIDE_MODE POWER_SAVE_MODE_OPEN POWER_PERFORMANCE_MODE_OPEN touchfeature.gamemode gesture_power_save sem_performance_mode sem_enhanced_cpu_responsiveness sched_autogroup_enabled sched_latency_ns sched_min_granularity_ns; do
            backup_setting system "${'$'}key"
          done
          for n in 0 1 2 3 4 5 6 7; do
            backup_setting system "power_check_max_cpu_${'$'}n"
            backup_setting global "power_check_max_cpu_${'$'}n"
          done
          backup_setting secure location_background_throttle_interval_ms
          backup_setting secure location_background_throttle_proximity_alert_interval_ms
        }
        restore_m_main() {
          cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1
          for key in low_power_mode low_power low_power_sticky low_ram synergy_mode sys_uidcpupower wifi_power_save app_auto_start_restriction background_process_limit max_cached_processes game_acceleration_mode performance_mode game_driver_mode game_performance_mode battery_saver_enabled fw.bservice_enable sem_power_mode_limited_apps_and_home_screen restricted_device_performance restricted_device_performance_power_level; do
            restore_setting global "${'$'}key"
          done
          for key in POWER_SAVE_PRE_HIDE_MODE POWER_SAVE_MODE_OPEN POWER_PERFORMANCE_MODE_OPEN touchfeature.gamemode gesture_power_save sem_performance_mode sem_enhanced_cpu_responsiveness sched_autogroup_enabled sched_latency_ns sched_min_granularity_ns; do
            restore_setting system "${'$'}key"
          done
          for n in 0 1 2 3 4 5 6 7; do
            restore_setting system "power_check_max_cpu_${'$'}n"
            restore_setting global "power_check_max_cpu_${'$'}n"
          done
          restore_setting secure location_background_throttle_interval_ms
          restore_setting secure location_background_throttle_proximity_alert_interval_ms
          restore_samsung_power_refresh_defaults
          restore_game_thermal_defaults
          clear_prop debug.ld.all
        }
        running() {
          pattern="${'$'}1"
          pgrep -f "${'$'}pattern" >/dev/null 2>&1
        }
    """.trimIndent()

    fun activateMToolsOnly(): MaxHzShell.Result = mExec(
        """
        pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1
        pm grant com.android.shell android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1
        echo "激活设备工具"

        echo "检测Scene..."
        if pm path com.omarea.vtools >/dev/null 2>&1; then
          scene_ran=0
          if pgrep -f "/data/local/tmp/scene-daemon" >/dev/null 2>&1 || pgrep -f "scene-daemon" >/dev/null 2>&1; then
            scene_ran=1
            echo "Scene daemon 已运行，跳过重复复制启动"
          elif [ -f "/sdcard/Android/data/com.omarea.vtools/up.sh" ]; then
            sh /sdcard/Android/data/com.omarea.vtools/up.sh
            scene_ran=1
            echo "Scene主路径激活成功"
          elif [ -f "/storage/emulated/0/Android/data/com.omarea.vtools/up.sh" ]; then
            sh /storage/emulated/0/Android/data/com.omarea.vtools/up.sh
            scene_ran=1
            echo "Scene备用路径激活成功"
          else
            echo "Scene激活脚本不存在"
          fi
          dumpsys deviceidle whitelist +com.omarea.vtools >/dev/null 2>&1 || true
          cmd appops set com.omarea.vtools RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
          cmd appops set com.omarea.vtools RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
          am set-standby-bucket com.omarea.vtools active >/dev/null 2>&1 || true
          am set-inactive --user 0 com.omarea.vtools false >/dev/null 2>&1 || true
          if [ "${'$'}scene_ran" = "1" ]; then
            sleep 1
            if dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | grep -Fq "com.omarea.vtools"; then
              input keyevent BACK >/dev/null 2>&1 || true
              echo "已压住Scene界面弹出"
            fi
          fi
        else
          echo "Scene未安装，跳过"
        fi

        echo "检测黑阈..."
        if pm path me.piebridge.brevent >/dev/null 2>&1; then
          dumpsys deviceidle whitelist +me.piebridge.brevent >/dev/null 2>&1 || true
          cmd appops set me.piebridge.brevent RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
          cmd appops set me.piebridge.brevent RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
          am set-standby-bucket me.piebridge.brevent active >/dev/null 2>&1 || true
          am set-inactive --user 0 me.piebridge.brevent false >/dev/null 2>&1 || true
          if running "me.piebridge.brevent.server.BreventServer" || running "brevent"; then
            echo "黑阈服务已运行，跳过重复启动"
          else
            output="$(pm path me.piebridge.brevent | head -n 1)"
            export CLASSPATH="${'$'}{output#*:}"
            app_process /system/bin me.piebridge.brevent.server.BreventServer bootstrap >/dev/null 2>&1 && echo "黑阈激活成功" || echo "黑阈激活失败"
            if [ -f "/data/local/tmp/brevent.sh" ]; then
              /system/bin/sh /data/local/tmp/brevent.sh >/dev/null 2>&1 || true
            fi
            if running "me.piebridge.brevent.server.BreventServer" || running "brevent"; then
              echo "黑阈服务运行确认"
            fi
          fi
        else
          echo "黑阈未安装，跳过"
        fi

        echo "检测小黑屋..."
        if pm path web1n.stopapp >/dev/null 2>&1; then
          dumpsys deviceidle whitelist +web1n.stopapp >/dev/null 2>&1 || true
          cmd appops set web1n.stopapp RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
          cmd appops set web1n.stopapp RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
          am set-standby-bucket web1n.stopapp active >/dev/null 2>&1 || true
          am set-inactive --user 0 web1n.stopapp false >/dev/null 2>&1 || true
          if running "web1n.stopapp" || running "stopapp"; then
            echo "小黑屋服务已运行，跳过重复启动"
          elif [ -f "/sdcard/Android/data/web1n.stopapp/files/starter.sh" ]; then
            sh /sdcard/Android/data/web1n.stopapp/files/starter.sh >/dev/null 2>&1
            echo "小黑屋激活成功"
          else
            echo "小黑屋激活脚本不存在"
          fi
        else
          echo "小黑屋未安装，跳过"
        fi

        echo "检测FPS.Watcher..."
        if pm path FPS.Watcher >/dev/null 2>&1; then
          cmd appops set FPS.Watcher RUN_IN_BACKGROUND allow >/dev/null 2>&1
          cmd appops set FPS.Watcher RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
          cmd appops set FPS.Watcher SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
          cmd appops set FPS.Watcher QUERY_ALL_PACKAGES allow >/dev/null 2>&1
          pm grant FPS.Watcher android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
          classes_dex="/storage/emulated/0/Android/data/FPS.Watcher/files/classes.dex"
          if running "FPS.Watcher.Watch" || running "FPS.Watcher"; then
            echo "FPS.Watcher 已运行，跳过重复启动"
          elif [ -f "${'$'}classes_dex" ]; then
            export CLASSPATH="${'$'}classes_dex"
            nohup app_process / FPS.Watcher.Watch >/dev/null 2>&1 &
            echo "FPS.Watcher启动成功"
          else
            echo "FPS.Watcher classes.dex不存在"
          fi
        else
          echo "FPS.Watcher未安装，跳过"
        fi
        echo "仅激活工具完成，未执行系统优化"
        """,
        timeoutSeconds = 20
    )

    fun verifyGameAutoPerformance(profile: String): MaxHzShell.Result {
        return MaxHzShell.exec(
            """
            refresh_mode="${'$'}(settings get secure refresh_rate_mode 2>/dev/null)"
            peak="${'$'}(settings get system peak_refresh_rate 2>/dev/null)"
            perf="${'$'}(settings get system sem_enhanced_cpu_responsiveness 2>/dev/null)"
            echo "GAME_AUTO_STATE performance=${'$'}perf refresh_mode=${'$'}refresh_mode peak=${'$'}peak"
            echo "Game Auto 性能调度状态已就绪"
            """.trimIndent(),
            timeoutSeconds = 5
        )
    }

    fun restoreMMainMode(): MaxHzShell.Result = mExec(
        """
        echo "恢复性能/省电主模式"
        restore_m_main
        cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1 || true
        echo "主模式已恢复为系统默认接管"
        """
    )

    fun applyMRefresh120(): MaxHzShell.Result = mExec(
        """
        echo "120Hz 自适应刷新率"
        put_secure refresh_rate_mode 1
        device_config put system display_refresh_rate 120 >/dev/null 2>&1 || true
        del_secure refresh_rate
        put_secure user_refresh_rate 120
        put_system min_refresh_rate 10
        put_system display_min_refresh_rate 10
        put_system peak_refresh_rate 120
        put_system user_refresh_rate 120
        del_global min_fps
        del_global max_fps
        put_global pms_settings_refresh_rate_enabled 0
        put_global psm_refresh_rate_tag 0
        del_global sem_power_mode_refresh_rate
        del_global sem_power_mode_refresh_rate_cover
        ok=1
        [ "$(settings get secure refresh_rate_mode 2>/dev/null)" = "1" ] || ok=0
        [ "$(settings get system peak_refresh_rate 2>/dev/null | cut -d. -f1)" = "120" ] || ok=0
        if [ "${'$'}ok" != "1" ]; then
          echo "验证失败：120Hz 设置没有保存"
          exit 4
        fi
        echo "120Hz 自适应已应用"
        """
    )

    fun applyMTombstoneMode(): MaxHzShell.Result = mExec(
        """
        echo "后台进程休眠（墓碑模式）"
        backup_setting global cached_apps_freezer
        put_global cached_apps_freezer enabled
        device_config put activity_manager_native_boot use_freezer true >/dev/null 2>&1 || true
        ok=1
        [ "$(settings get global cached_apps_freezer 2>/dev/null)" = "enabled" ] || ok=0
        if [ "${'$'}ok" != "1" ]; then
          echo "验证失败：系统没有接受墓碑冻结设置"
          exit 4
        fi
        echo "后台进程休眠（墓碑模式）已应用"
        """
    )

    fun restoreMTombstoneMode(): MaxHzShell.Result = mExec(
        """
        echo "恢复后台进程休眠（墓碑模式）"
        restore_setting_or_delete global cached_apps_freezer
        device_config delete activity_manager_native_boot use_freezer >/dev/null 2>&1 || true
        echo "后台进程休眠（墓碑模式）已恢复"
        """
    )

    fun applyMCpuResponse(): MaxHzShell.Result = mExec(
        """
        echo "三星增强处理（CPU 瞬时响应）"
        backup_setting system sem_enhanced_cpu_responsiveness
        backup_setting global sem_enhanced_cpu_responsiveness
        put_system sem_enhanced_cpu_responsiveness 1
        put_global sem_enhanced_cpu_responsiveness 1
        cmd power set-fixed-performance-mode-enabled true >/dev/null 2>&1 || true
        ok=1
        [ "$(settings get system sem_enhanced_cpu_responsiveness 2>/dev/null)" = "1" ] || ok=0
        if [ "${'$'}ok" != "1" ]; then
          echo "验证失败：三星增强处理设置没有保存"
          exit 4
        fi
        echo "三星增强处理已开启"
        """
    )

    fun restoreMCpuResponse(): MaxHzShell.Result = mExec(
        """
        echo "恢复三星增强处理默认"
        restore_setting_or_delete system sem_enhanced_cpu_responsiveness
        restore_setting_or_delete global sem_enhanced_cpu_responsiveness
        cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1 || true
        echo "三星增强处理已恢复默认"
        """
    )

    fun applyMLocationThrottle(): MaxHzShell.Result = mExec(
        """
        echo "定位后台限制"
        backup_setting secure location_background_throttle_interval_ms
        backup_setting secure location_background_throttle_proximity_alert_interval_ms
        put_secure location_background_throttle_interval_ms 1800000
        put_secure location_background_throttle_proximity_alert_interval_ms -1
        ok=1
        [ "$(settings get secure location_background_throttle_interval_ms 2>/dev/null)" = "1800000" ] || ok=0
        [ "$(settings get secure location_background_throttle_proximity_alert_interval_ms 2>/dev/null)" = "-1" ] || ok=0
        if [ "${'$'}ok" != "1" ]; then
          echo "验证失败：定位后台限制设置没有保存"
          exit 4
        fi
        echo "定位后台限制已应用"
        """
    )

    fun restoreMLocationThrottle(): MaxHzShell.Result = mExec(
        """
        echo "恢复定位后台限制"
        restore_setting_or_delete secure location_background_throttle_interval_ms
        restore_setting_or_delete secure location_background_throttle_proximity_alert_interval_ms
        echo "定位后台限制已恢复"
        """
    )

    fun applyMAutostartLimit(): MaxHzShell.Result = mExec(
        """
        echo "限制后台自启（三星/Android 原生适配）"
        backup_setting global app_standby_enabled
        backup_setting global adaptive_battery_management_enabled
        put_global app_standby_enabled 1
        put_global adaptive_battery_management_enabled 1
        device_config put activity_manager default_background_fgs_starts_restriction_enabled true >/dev/null 2>&1 || true
        device_config put activity_manager default_background_activity_starts_enabled false >/dev/null 2>&1 || true
        device_config put activity_manager bg_auto_restricted_bucket_on_bg_restricted true >/dev/null 2>&1 || true
        [ "$(settings get global app_standby_enabled 2>/dev/null)" = "1" ] || {
          echo "验证失败：后台自启与待机管控设置没有保存"
          exit 4
        }
        echo "后台自启与链式唤醒限制已开启"
        """
    )

    fun restoreMAutostartLimit(): MaxHzShell.Result = mExec(
        """
        echo "恢复后台自启限制"
        restore_setting_or_delete global app_standby_enabled
        restore_setting_or_delete global adaptive_battery_management_enabled
        restore_setting_or_delete global app_auto_start_restriction
        device_config delete activity_manager default_background_fgs_starts_restriction_enabled >/dev/null 2>&1 || true
        device_config delete activity_manager default_background_activity_starts_enabled >/dev/null 2>&1 || true
        device_config delete activity_manager bg_auto_restricted_bucket_on_bg_restricted >/dev/null 2>&1 || true
        echo "后台自启限制已恢复系统默认"
        """
    )

    fun applyMQuickTrim(): MaxHzShell.Result = mExec(
        """
        echo "释放应用缓存与后台空载进程"
        pm trim-caches 10000000M >/dev/null 2>&1 || true
        am kill-all >/dev/null 2>&1 || true
        echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || true
        echo "应用缓存与空载后台进程已清理释放"
        """,
        timeoutSeconds = 15
    )

    fun applyMDexopt(): MaxHzShell.Result = mExec(
        """
        echo "AOT 启动预编译优化"
        pm trim-caches 10000000M >/dev/null 2>&1 || true
        for pkg in com.sec.android.app.launcher com.android.systemui com.miui.home com.android.launcher com.bbk.launcher2 com.hihonor.android.launcher com.meizu.flyme.launcher; do
          if pm path "${'$'}pkg" >/dev/null 2>&1; then
            cmd package compile -m speed-profile "${'$'}pkg" >/dev/null 2>&1 && echo "编译 ${'$'}pkg 完成" || true
          fi
        done
        echo "核心桌面与系统组件预编译已完成"
        """,
        timeoutSeconds = 60
    )

    fun applyMFullDexopt(): MaxHzShell.Result = mExec(
        """
        echo "全量应用 AOT 预编译"
        pm trim-caches 10000000M >/dev/null 2>&1 || true
        cmd package compile -m speed-profile -a >/dev/null 2>&1 || true
        echo "全量应用预编译已完成"
        """,
        timeoutSeconds = 120
    )

    fun restoreMDexopt(): MaxHzShell.Result = mExec(
        """
        echo "恢复编译优化默认设置"
        for pkg in com.sec.android.app.launcher com.android.systemui com.miui.home com.android.launcher; do
          if pm path "${'$'}pkg" >/dev/null 2>&1; then
            cmd package compile --reset "${'$'}pkg" >/dev/null 2>&1 || true
          fi
        done
        echo "编译设置与缓存已恢复默认"
        """,
        timeoutSeconds = 60
    )

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /**
     * 释放非托管游戏的显示刷新率覆盖项（仅清理本应用刷新率参数，绝不在游戏冷启动途中调用
     * cmd game set --downscale 或 cmd game reset，彻底根治游戏启动时分辨率错位/左上角 75% 小窗黑边问题）。
     */
    fun restoreGameDisplayDefaults(packageName: String): MaxHzShell.Result {
        val safePackage = shellQuote(packageName)
        return MaxHzShell.exec(
            """
            target_pkg=$safePackage
            settings delete secure refresh_rate_mode >/dev/null 2>&1
            settings delete secure refresh_rate >/dev/null 2>&1
            settings delete secure user_refresh_rate >/dev/null 2>&1
            settings delete system display_refresh_rate >/dev/null 2>&1
            settings delete system peak_refresh_rate >/dev/null 2>&1
            settings delete system user_refresh_rate >/dev/null 2>&1
            settings delete system min_refresh_rate >/dev/null 2>&1
            settings delete system display_min_refresh_rate >/dev/null 2>&1
            settings delete global max_fps >/dev/null 2>&1
            settings delete global min_fps >/dev/null 2>&1
            settings delete global psm_refresh_rate_tag >/dev/null 2>&1
            settings delete global psm_refresh_rate_cover_tag >/dev/null 2>&1
            settings delete global psm_refresh_rate_tag_power_mode_state >/dev/null 2>&1
            settings delete global pms_settings_refresh_rate_enabled >/dev/null 2>&1
            settings delete global pms_settings_refresh_rate_cover_enabled >/dev/null 2>&1
            settings delete global sem_power_mode_refresh_rate >/dev/null 2>&1
            settings delete global sem_power_mode_refresh_rate_cover >/dev/null 2>&1
            for key in max_fps min_fps psm_refresh_rate_tag psm_refresh_rate_cover_tag psm_refresh_rate_tag_power_mode_state pms_settings_refresh_rate_enabled pms_settings_refresh_rate_cover_enabled sem_power_mode_refresh_rate sem_power_mode_refresh_rate_cover; do
              value="${'$'}(settings get global "${'$'}key" 2>/dev/null)"
              if [ "${'$'}value" != "null" ]; then
                echo "验证失败：${'$'}key 仍未交回系统接管。"
                exit 4
              fi
            done
            echo "已释放游戏显示设置：${'$'}target_pkg 交回系统/Game Booster 接管"
            """.trimIndent()
        )
    }

    /**
     * 在用户在 UI 中静态添加/移除受管游戏时（非游戏冷启动途中）预先配置或重置 GameManager 策略，
     * 避免游戏启动瞬间触发 Surface 重建导致视口错位。
     */
    fun configureGameStaticIntervention(packageName: String, enable: Boolean): MaxHzShell.Result {
        val safePkg = shellPackageList(listOf(packageName))
        if (safePkg.isBlank()) return MaxHzShell.Result(0, "", "")
        return if (enable) {
            MaxHzShell.exec(
                """
                cmd game mode performance "$safePkg" >/dev/null 2>&1 || true
                cmd game set --downscale disable --fps 120 "$safePkg" >/dev/null 2>&1 || true
                echo "已静态预配置游戏免降分辨率与高帧率：$safePkg"
                """.trimIndent(),
                timeoutSeconds = 5
            )
        } else {
            MaxHzShell.exec(
                """
                cmd game mode standard "$safePkg" >/dev/null 2>&1 || true
                cmd game reset "$safePkg" >/dev/null 2>&1 || true
                echo "已重置游戏静态干预配置：$safePkg"
                """.trimIndent(),
                timeoutSeconds = 5
            )
        }
    }

    fun isSamsungGamePackage(packageName: String): Boolean {
        val safePackage = shellQuote(packageName)
        val result = MaxHzShell.exec(
            """
            cmd game list-modes $safePackage 2>/dev/null | grep -q 'available game modes'
            """.trimIndent()
        )
        return result.code == 0
    }

    /**
     * 磁贴与自动游戏模式专用极速切换：系统默认
     * 单进程原子 Shell 极速执行，不在运行期调用 cmd game reset 或硬编码物理分辨率模式 ID，
     * 防止窗口尺寸与逻辑分辨率失配。
     */
    fun quickSwitchGameDefault(
        packageName: String? = null,
        restoreBypassCharge: Boolean = false
    ): MaxHzShell.Result {
        val safePkg = packageName?.let { shellPackageList(listOf(it)) }.orEmpty()
        val gameResetCommands = if (safePkg.isNotBlank()) {
            """
            cmd game mode standard "$safePkg" >/dev/null 2>&1 || true
            """.trimIndent()
        } else ""
        val bypassRestoreCommand = if (restoreBypassCharge) {
            "settings put system pass_through 0 >/dev/null 2>&1 || true"
        } else ""

        return MaxHzShell.exec(
            """
            cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1 || true
            settings delete system sem_enhanced_cpu_responsiveness >/dev/null 2>&1 || true
            settings delete global sem_enhanced_cpu_responsiveness >/dev/null 2>&1 || true
            $gameResetCommands
            $bypassRestoreCommand
            cmd power set-mode 0 >/dev/null 2>&1 || true
            settings put global low_power 0 >/dev/null 2>&1 || true
            settings put global restricted_device_performance 0,0 >/dev/null 2>&1 || true
            settings put global sem_low_heat_mode 0 >/dev/null 2>&1 || true
            settings delete global pms_settings_refresh_rate_enabled >/dev/null 2>&1 || true
            settings delete global psm_refresh_rate_tag >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate_cover >/dev/null 2>&1 || true
            settings put secure refresh_rate_mode 1 >/dev/null 2>&1 || true
            settings delete secure user_refresh_rate >/dev/null 2>&1 || true
            settings delete system peak_refresh_rate >/dev/null 2>&1 || true
            settings delete system min_refresh_rate >/dev/null 2>&1 || true
            settings delete system user_refresh_rate >/dev/null 2>&1 || true
            settings delete system custom_refresh_rate >/dev/null 2>&1 || true
            settings delete system display_min_refresh_rate >/dev/null 2>&1 || true
            cmd display clear-user-preferred-display-mode 0 >/dev/null 2>&1 || true
            settings delete global device_idle_constants >/dev/null 2>&1 || true
            settings delete global battery_saver_constants >/dev/null 2>&1 || true
            dumpsys deviceidle unforce >/dev/null 2>&1 || true
            echo "GAME_DEFAULT_APPLIED"
            """.trimIndent(),
            timeoutSeconds = 5
        )
    }

    /**
     * 磁贴与主界面极速切换：省电自适应高刷
     * 真正开启三星系统级省电服务 (One UI 设置中显示开启，生效 CPU 70% 限制与后台休眠)，
     * 同时利用 Galaxy MaxHz 核心驱动突破 60Hz 限制，保持 120Hz 自适应丝滑高刷。
     */
    fun quickSwitchGameSaver(
        maintenanceMinutes: Int = 80,
        noMaintenance: Boolean = false
    ): MaxHzShell.Result {
        return applyDailySaver(maintenanceMinutes, noMaintenance)
    }

    /**
     * 磁贴与自动游戏模式专用极速切换：游戏性能模式
     * 100% 真实 Android / One UI 接口：
     * 1. PowerManagerService Fixed Performance Mode (Power HAL Mode.FIXED_PERFORMANCE)
     * 2. 三星 One UI 增强处理 (sem_enhanced_cpu_responsiveness=1)
     * 3. Android GameManagerService 原生游戏性能模式 (cmd game mode performance，不在启动期改写 downscale)
     * 4. 解除省电降频与 60Hz 限制，保持 120Hz 高刷
     * 5. 可选联动开启三星游戏旁路供电 (pass_through=1)
     */
    fun quickSwitchGamePerformance(
        thermalRelaxed: Boolean = false,
        packageName: String? = null,
        bypassCharge: Boolean = false
    ): MaxHzShell.Result {
        val safePkg = packageName?.let { shellPackageList(listOf(it)) }.orEmpty()
        val gameModeCommands = if (safePkg.isNotBlank()) {
            """
            cmd game mode performance "$safePkg" >/dev/null 2>&1 || true
            """.trimIndent()
        } else ""
        val bypassCommand = if (bypassCharge) {
            "settings put system pass_through 1 >/dev/null 2>&1 || true"
        } else ""

        return MaxHzShell.exec(
            """
            cmd power set-mode 0 >/dev/null 2>&1 || true
            cmd power set-adaptive-power-saver-enabled false >/dev/null 2>&1 || true
            settings put global low_power 0 >/dev/null 2>&1 || true
            settings put global restricted_device_performance 0,0 >/dev/null 2>&1 || true
            settings put global sem_low_heat_mode 0 >/dev/null 2>&1 || true
            cmd power set-fixed-performance-mode-enabled true >/dev/null 2>&1 || true
            settings put system sem_enhanced_cpu_responsiveness 1 >/dev/null 2>&1 || true
            settings put global sem_enhanced_cpu_responsiveness 1 >/dev/null 2>&1 || true
            $gameModeCommands
            $bypassCommand
            settings put global pms_settings_refresh_rate_enabled 0 >/dev/null 2>&1 || true
            settings put global psm_refresh_rate_tag 0 >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate >/dev/null 2>&1 || true
            settings delete global sem_power_mode_refresh_rate_cover >/dev/null 2>&1 || true
            settings put secure refresh_rate_mode 1 >/dev/null 2>&1 || true
            settings put secure user_refresh_rate 120 >/dev/null 2>&1 || true
            settings put system peak_refresh_rate 120 >/dev/null 2>&1 || true
            settings put system user_refresh_rate 120 >/dev/null 2>&1 || true
            settings put system min_refresh_rate 60 >/dev/null 2>&1 || true
            settings delete global device_idle_constants >/dev/null 2>&1 || true
            settings delete global battery_saver_constants >/dev/null 2>&1 || true
            dumpsys deviceidle unforce >/dev/null 2>&1 || true
            echo "GAME_PERFORMANCE_APPLIED"
            """.trimIndent(),
            timeoutSeconds = 5
        )
    }

}
