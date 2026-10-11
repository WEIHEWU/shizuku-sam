package moe.shizuku.manager.maxhz

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

object MaxHzForegroundApp {

    @Volatile
    private var cachedPackage: String? = null
    @Volatile
    private var cachedAt = 0L
    @Volatile
    private var cachedWindowPackage: String? = null
    @Volatile
    private var cachedWindowAt = 0L

    fun currentPackage(context: Context): String? {
        val now = System.currentTimeMillis()
        if (now - cachedAt < FOREGROUND_CACHE_MS) return cachedPackage

        val packageName = currentPackageFresh(context)
        cachedPackage = packageName
        cachedAt = now
        return packageName
    }

    fun currentRecentPackage(context: Context): String? {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - RECENT_FOREGROUND_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var foreground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.isForegroundEvent()) {
                foreground = event.packageName
            }
        }
        return foreground?.takeIf { it.isNotBlank() }
    }

    private fun currentPackageFresh(context: Context): String? {
        currentWindowPackage()?.let { return it }

        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - FOREGROUND_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var foreground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.isForegroundEvent()) {
                foreground = event.packageName
            }
        }
        if (!foreground.isNullOrBlank()) return foreground

        return manager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - USAGE_STATS_LOOKBACK_MS,
            now
        )
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    fun currentWindowPackage(): String? {
        val now = System.currentTimeMillis()
        if (now - cachedWindowAt < WINDOW_CACHE_MS) return cachedWindowPackage
        val out = runCatching {
            MaxHzShell.execRaw(
                """
                dumpsys activity activities 2>/dev/null | grep -m 3 -E 'mResumedActivity|topResumedActivity|ResumedActivity|mFocusedActivity'
                dumpsys window 2>/dev/null | grep -m 3 -E 'mCurrentFocus|mFocusedApp|mFocusedWindow'
                dumpsys window windows 2>/dev/null | grep -A 2 -E 'mCurrentFocus|mFocusedWindow'
                """.trimIndent(),
                timeoutMs = 900
            ).out
        }.getOrNull().orEmpty()
        val packageName = out.lineSequence()
            .firstNotNullOfOrNull { line ->
                ACTIVITY_RECORD_PATTERN.find(line)?.groupValues?.getOrNull(1)
                    ?: WINDOW_PATTERN.find(line)?.groupValues?.getOrNull(1)
                    ?: PACKAGE_SLASH_PATTERN.find(line)?.groupValues?.getOrNull(1)
            }
            ?.takeIf { it.isNotBlank() && it.contains('.') }
        cachedWindowPackage = packageName
        cachedWindowAt = now
        return packageName
    }

    @Suppress("DEPRECATION")
    private fun UsageEvents.Event.isForegroundEvent(): Boolean {
        return eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                eventType == UsageEvents.Event.ACTIVITY_RESUMED)
    }

    private const val FOREGROUND_CACHE_MS = 250L
    private const val WINDOW_CACHE_MS = 120L
    private const val RECENT_FOREGROUND_LOOKBACK_MS = 2_000L
    private const val FOREGROUND_LOOKBACK_MS = 5_000L
    private const val USAGE_STATS_LOOKBACK_MS = 5 * 60_000L
    private val ACTIVITY_RECORD_PATTERN = Regex("ActivityRecord\\{[^}]*\\s([a-zA-Z0-9._]+)/")
    private val PACKAGE_SLASH_PATTERN = Regex("\\s([a-zA-Z][a-zA-Z0-9._]*)/[a-zA-Z]")
    private val WINDOW_PATTERN = Regex("Window\\{[^}]*\\s([a-zA-Z0-9._]+)/")
}
