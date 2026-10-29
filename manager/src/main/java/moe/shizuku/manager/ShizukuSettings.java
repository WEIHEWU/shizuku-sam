package moe.shizuku.manager;

import android.app.ActivityThread;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import java.lang.annotation.Retention;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import moe.shizuku.manager.service.WatchdogService;
import moe.shizuku.manager.utils.Token;
import moe.shizuku.manager.utils.EmptySharedPreferencesImpl;
import moe.shizuku.manager.utils.EnvironmentUtils;

import static java.lang.annotation.RetentionPolicy.SOURCE;

public class ShizukuSettings {

    public static final String NAME = "settings";
    public static final String NIGHT_MODE = "night_mode";
    public static final String BACKGROUND_STYLE = "background_style";
    public static final String BACKGROUND_STYLE_AURORA = "aurora";
    public static final String BACKGROUND_STYLE_SOLID_LIGHT = "solid_light";
    public static final String BACKGROUND_STYLE_SOLID_DARK = "solid_dark";
    public static final String BACKGROUND_STYLE_WALLPAPER = "wallpaper";
    public static final String BACKGROUND_WALLPAPER_URI = "background_wallpaper_uri";
    public static final String BACKGROUND_WALLPAPER_FIT = "background_wallpaper_fit";
    public static final String WALLPAPER_FIT_CROP = "crop";
    public static final String WALLPAPER_FIT_FIT = "fit";
    public static final String WALLPAPER_FIT_STRETCH = "stretch";
    public static final String LANGUAGE = "language";
    public static final String KEEP_START_ON_BOOT = "start_on_boot";
    public static final String WATCHDOG = "watchdog";
    public static final String LAST_SETTLED_STATE_RUNNING = "last_settled_state_running";
    public static final String KEY_LAST_SETTLED_STATE = "last_settled_shizuku_state";
    public static final String KEY_SERVER_STARTED_BUILD = "server_started_build";
    public static final String KEY_KNOWN_CLIENT_PACKAGES = "known_client_packages";
    public static final String TCP_MODE = "tcp_mode";
    public static final String TCP_PORT = "tcp_port";
    public static final String LAST_PORT = "last_port";
    public static final String KEY_AUTO_DISABLE_USB_DEBUGGING = "auto_disable_usb_debugging";
    public static final String KEY_FORCE_START_WADB = "force_start_wadb";
    public static final String SHOW_FOREGROUND_NOTIFICATION = "show_foreground_notification";

    private static SharedPreferences sPreferences;

    public static SharedPreferences getPreferences() {
        return sPreferences;
    }

    @NonNull
    private static Context getSettingsStorageContext(@NonNull Context context) {
        Context storageContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageContext = context.createDeviceProtectedStorageContext();
        } else {
            storageContext = context;
        }

        storageContext = new ContextWrapper(storageContext) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                try {
                    return super.getSharedPreferences(name, mode);
                } catch (IllegalStateException e) {
                    // SharedPreferences in credential encrypted storage are not available until after user is unlocked
                    return new EmptySharedPreferencesImpl();
                }
            }
        };

        return storageContext;
    }

    public static void initialize(Context context) {
        if (sPreferences == null) {
            sPreferences = getSettingsStorageContext(context)
                    .getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
    }

    @IntDef({
            LaunchMethod.UNKNOWN,
            LaunchMethod.ROOT,
            LaunchMethod.ADB,
    })
    @Retention(SOURCE)
    public @interface LaunchMethod {
        int UNKNOWN = -1;
        int ROOT = 0;
        int ADB = 1;
    }

    @LaunchMethod
    public static int getLastLaunchMode() {
        return getPreferences().getInt("mode", LaunchMethod.UNKNOWN);
    }

    public static void setLastLaunchMode(@LaunchMethod int method) {
        SharedPreferences.Editor editor = getPreferences().edit().putInt("mode", method);
        if (method == LaunchMethod.ROOT || method == LaunchMethod.ADB) {
            editor.putBoolean(LAST_SETTLED_STATE_RUNNING, true);
        }
        editor.apply();
    }

    public static boolean isLastSettledStateRunning() {
        return getPreferences().getBoolean(LAST_SETTLED_STATE_RUNNING, getLastLaunchMode() != LaunchMethod.UNKNOWN);
    }

    public static void setLastSettledStateRunning(boolean running) {
        getPreferences().edit()
                .putBoolean(LAST_SETTLED_STATE_RUNNING, running)
                .putString(KEY_LAST_SETTLED_STATE, running ? "RUNNING" : "STOPPED")
                .apply();
    }

    @Nullable
    public static String getLastSettledState() {
        String state = getPreferences().getString(KEY_LAST_SETTLED_STATE, null);
        if (state != null) {
            return state;
        }
        return isLastSettledStateRunning() ? "RUNNING" : "STOPPED";
    }

    public static void setLastSettledState(@NonNull String stateName) {
        SharedPreferences.Editor editor = getPreferences().edit()
                .putString(KEY_LAST_SETTLED_STATE, stateName);
        if ("RUNNING".equals(stateName)) {
            editor.putBoolean(LAST_SETTLED_STATE_RUNNING, true);
        } else if ("STOPPED".equals(stateName)) {
            editor.putBoolean(LAST_SETTLED_STATE_RUNNING, false);
        }
        editor.apply();
    }

    public static int getServerStartedBuild() {
        return getPreferences().getInt(KEY_SERVER_STARTED_BUILD, 0);
    }

    public static void setServerStartedBuild(int versionCode) {
        getPreferences().edit().putInt(KEY_SERVER_STARTED_BUILD, versionCode).apply();
    }

    @NonNull
    public static Set<String> getKnownClientPackages() {
        SharedPreferences prefs = getPreferences();
        if (prefs == null) return Collections.emptySet();
        Set<String> stored = prefs.getStringSet(KEY_KNOWN_CLIENT_PACKAGES, null);
        return stored != null ? new HashSet<>(stored) : Collections.emptySet();
    }

    public static void setKnownClientPackages(@NonNull Set<String> packages) {
        SharedPreferences prefs = getPreferences();
        if (prefs != null) {
            prefs.edit().putStringSet(KEY_KNOWN_CLIENT_PACKAGES, new HashSet<>(packages)).apply();
        }
    }

    public static String getAuthToken() {
        String authToken = getPreferences().getString("auth_token", null);
        if (TextUtils.isEmpty(authToken)) {
            authToken = generateAuthToken();
        }
        return authToken;
    }

    public static String generateAuthToken() {
        String token = Token.generateToken();
        getPreferences().edit().putString("auth_token", token).apply();
        return token;
    }

    public static boolean getWatchdog() {
        return getPreferences().getBoolean(WATCHDOG, true);
    }

    public static boolean isWatchdogRunning() {
        return WatchdogService.isRunning();
    }

    public static void setWatchdog(Context context, boolean enable) {
        getPreferences().edit().putBoolean(WATCHDOG, enable).apply();
        if (enable) {
            WatchdogService.start(context);
            moe.shizuku.manager.service.WatchdogWorker.enqueue(context);
            moe.shizuku.manager.service.WatchdogAlarmReceiver.scheduleNext(context);
        } else {
            WatchdogService.stop(context);
            moe.shizuku.manager.service.WatchdogWorker.cancel(context);
            moe.shizuku.manager.service.WatchdogAlarmReceiver.cancel(context);
        }
    }

    public static boolean getShowForegroundNotification() {
        return getPreferences().getBoolean(SHOW_FOREGROUND_NOTIFICATION, false);
    }

    public static void setShowForegroundNotification(Context context, boolean enable) {
        getPreferences().edit().putBoolean(SHOW_FOREGROUND_NOTIFICATION, enable).apply();
    }

    public static boolean getTcpMode() {
        return getPreferences().getBoolean(TCP_MODE, true);
    }

    public static void setTcpMode(boolean enable) {
        getPreferences().edit().putBoolean(TCP_MODE, enable).apply();
    }

    public static int getTcpPort() {
        try {
            return Integer.parseInt(getPreferences().getString(TCP_PORT, "5555"));
        } catch (NumberFormatException e) {
            return 5555;
        }
    }

    public static void setTcpPort(Integer port) {
        SharedPreferences.Editor editor = getPreferences().edit();
        if (port != null) {
            editor.putString(TCP_PORT, Integer.toString(port));
        } else {
            editor.remove(TCP_PORT);
        }
        editor.apply();
    }

    public static int getLastPort() {
        try {
            return getPreferences().getInt(LAST_PORT, -1);
        } catch (Throwable e) {
            return -1;
        }
    }

    public static void setLastPort(int port) {
        if (port >= 1 && port <= 65535) {
            getPreferences().edit().putInt(LAST_PORT, port).apply();
        }
    }

    public static boolean getAutoDisableUsbDebugging() {
        return getPreferences().getBoolean(KEY_AUTO_DISABLE_USB_DEBUGGING, false);
    }

    public static void setAutoDisableUsbDebugging(boolean enable) {
        getPreferences().edit().putBoolean(KEY_AUTO_DISABLE_USB_DEBUGGING, enable).apply();
    }

    public static boolean isForceStartWadbEnabled() {
        return getPreferences().getBoolean(KEY_FORCE_START_WADB, true);
    }

    public static void setForceStartWadbEnabled(boolean enable) {
        getPreferences().edit().putBoolean(KEY_FORCE_START_WADB, enable).apply();
    }

    /**
     * 背景样式：极光渐变（默认）/ 纯净浅色 / 纯净深色 / 自定义壁纸。
     * 与 NIGHT_MODE（跟随系统 / 浅色 / 深色）互相独立 —— 后两项会同时
     * 把整页明暗切到对应档位，不依赖系统夜间模式。
     */
    public static String getBackgroundStyle() {
        // 「背景」样式下拉框（极光/纯净浅色/纯净深色）已从设置中移除，
        // 故恒返回极光渐变，历史存过的 solid_* / wallpaper 值一律忽略。
        // 注意：自定义壁纸走独立的 BACKGROUND_WALLPAPER_URI，不受此处影响
        // （设置里的「自定义壁纸」入口仍可选图，也能在入口内移除）。
        return BACKGROUND_STYLE_AURORA;
    }

    public static void setBackgroundStyle(String value) {
        getPreferences().edit().putString(BACKGROUND_STYLE, value).apply();
    }

    /**
     * 自定义壁纸：用户从相册挑选的图片 content:// URI（已 takePersistableUriPermission）。
     * 为空表示未设置壁纸，回退到极光渐变。
     */
    @Nullable
    public static String getWallpaperUri() {
        return getPreferences().getString(BACKGROUND_WALLPAPER_URI, null);
    }

    public static void setWallpaperUri(@Nullable String uri) {
        SharedPreferences.Editor editor = getPreferences().edit();
        if (uri != null) {
            editor.putString(BACKGROUND_WALLPAPER_URI, uri);
        } else {
            editor.remove(BACKGROUND_WALLPAPER_URI);
        }
        editor.apply();
    }

    public static void clearWallpaper() {
        getPreferences().edit().remove(BACKGROUND_WALLPAPER_URI).apply();
    }

    /**
     * 壁纸填充方式：crop=裁剪填充（不变形，默认）/ fit=完整显示（不变形，可留白）
     * / stretch=拉伸填满（会变形）。
     */
    public static String getWallpaperFit() {
        String v = getPreferences().getString(BACKGROUND_WALLPAPER_FIT, WALLPAPER_FIT_CROP);
        if (WALLPAPER_FIT_FIT.equals(v) || WALLPAPER_FIT_STRETCH.equals(v)) {
            return v;
        }
        return WALLPAPER_FIT_CROP;
    }

    public static void setWallpaperFit(@Nullable String fit) {
        SharedPreferences.Editor editor = getPreferences().edit();
        if (fit != null) {
            editor.putString(BACKGROUND_WALLPAPER_FIT, fit);
        } else {
            editor.remove(BACKGROUND_WALLPAPER_FIT);
        }
        editor.apply();
    }

    @AppCompatDelegate.NightMode
    public static int getNightMode() {
        int defValue = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        if (EnvironmentUtils.isWatch(ActivityThread.currentActivityThread().getApplication())) {
            defValue = AppCompatDelegate.MODE_NIGHT_YES;
        }
        return getPreferences().getInt(NIGHT_MODE, defValue);
    }

    public static Locale getLocale() {
        String tag = getPreferences().getString(LANGUAGE, null);
        if (TextUtils.isEmpty(tag) || "SYSTEM".equals(tag)) {
            return Locale.getDefault();
        }
        return Locale.forLanguageTag(tag);
    }
}
