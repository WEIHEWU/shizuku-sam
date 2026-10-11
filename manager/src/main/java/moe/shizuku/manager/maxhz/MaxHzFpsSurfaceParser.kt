package moe.shizuku.manager.maxhz

import java.util.Locale

internal data class MaxHzForegroundTarget(
    val packageName: String,
    val activityClass: String?
)

/** Pure parsing helpers kept outside the Service so device-specific dumps can be regression tested. */
internal object MaxHzFpsSurfaceParser {

    fun foregroundTarget(output: String): MaxHzForegroundTarget? {
        output.lineSequence().forEach { line ->
            COMPONENT_PATTERN.find(line)?.let { match ->
                val packageName = match.groupValues[1]
                val rawActivity = match.groupValues[2]
                val activityClass = when {
                    rawActivity.isBlank() -> null
                    rawActivity.startsWith('.') -> packageName + rawActivity
                    rawActivity.contains('.') -> rawActivity
                    else -> "$packageName.$rawActivity"
                }
                return MaxHzForegroundTarget(packageName, activityClass)
            }
        }
        return null
    }

    fun rankedLayers(
        output: String,
        packageName: String,
        activityClass: String?
    ): List<String> {
        if (packageName.isBlank()) return emptyList()
        val packageLower = packageName.lowercase(Locale.US)
        val classLower = activityClass?.lowercase(Locale.US).orEmpty()
        val simpleClassLower = classLower.substringAfterLast('.').takeIf { it.length >= 4 }.orEmpty()

        return output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { layer ->
                val lower = layer.lowercase(Locale.US)
                if (isSystemLayer(lower)) return@mapNotNull null

                // Shared engine Activity names (for example UnityPlayerActivity) are not
                // unique. If SurfaceFlinger exposes an explicit owner component, never let
                // an identically named background game's layer enter the candidate list.
                val explicitLayerPackage = COMPONENT_PATTERN.find(layer)
                    ?.groupValues
                    ?.getOrNull(1)
                if (explicitLayerPackage != null &&
                    !explicitLayerPackage.equals(packageName, ignoreCase = true)
                ) {
                    return@mapNotNull null
                }

                val packageMatch = lower.contains(packageLower)
                val classMatch = classLower.isNotBlank() && lower.contains(classLower)
                val simpleClassMatch = simpleClassLower.isNotBlank() && lower.contains(simpleClassLower)
                if (!packageMatch && !classMatch && !simpleClassMatch) return@mapNotNull null

                var score = 0
                if (packageMatch) score += 1_000
                if (classMatch) score += 500
                if (simpleClassMatch) score += 200
                if (lower.contains("surfaceview")) score += 300
                if (lower.contains("blast")) score += 80
                if (lower.contains("gameactivity")) score += 40
                if (layer.contains('/')) score += 20
                val layerId = LAYER_ID_PATTERN.findAll(layer)
                    .mapNotNull { it.groupValues.getOrNull(1)?.toLongOrNull() }
                    .maxOrNull() ?: 0L
                RankedLayer(layer, score, layerId)
            }
            .distinctBy { it.name }
            .sortedWith(compareByDescending<RankedLayer> { it.score }.thenByDescending { it.layerId })
            .map { it.name }
            .take(MAX_LAYER_CANDIDATES)
            .toList()
    }

    fun presentedFrameTimestamps(output: String): List<Long> {
        return output.lineSequence().mapNotNull { line ->
            val columns = line.trim().split(WHITESPACE)
            if (columns.size < 3) return@mapNotNull null

            // SurfaceFlinger --latency columns are desired-present, actual-present and
            // frame-ready. Actual-present is the reliable clock for protected/native game
            // surfaces; frame-ready is often 0 or INT64_MAX on those layers.
            validTimestamp(columns.getOrNull(ACTUAL_PRESENT_COLUMN))
                ?: validTimestamp(columns.getOrNull(FRAME_READY_COLUMN))
        }.toList()
    }

    fun calculateFps(timestamps: List<Long>, nowNanos: Long): Double {
        if (timestamps.size < 2) return -1.0
        val maxTimestamp = timestamps.maxOrNull() ?: return -1.0
        if (nowNanos >= maxTimestamp && nowNanos - maxTimestamp > MAX_FRAME_AGE_NS) return -1.0

        val recent = timestamps
            .asSequence()
            .filter { it >= maxTimestamp - FPS_WINDOW_NS }
            .distinct()
            .sorted()
            .toList()
        if (recent.size < MIN_RECENT_TIMESTAMPS) return -1.0
        val duration = recent.last() - recent.first()
        if (duration <= 0L) return -1.0
        return (recent.size - 1) * NANOS_PER_SECOND.toDouble() / duration
    }

    private fun validTimestamp(value: String?): Long? {
        return value?.toLongOrNull()?.takeIf { it > 0L && it != Long.MAX_VALUE }
    }

    private fun isSystemLayer(value: String): Boolean {
        return SYSTEM_LAYER_MARKERS.any(value::contains)
    }

    private data class RankedLayer(val name: String, val score: Int, val layerId: Long)

    private const val MAX_LAYER_CANDIDATES = 8
    private const val ACTUAL_PRESENT_COLUMN = 1
    private const val FRAME_READY_COLUMN = 2
    private const val MIN_RECENT_TIMESTAMPS = 2
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val FPS_WINDOW_NS = NANOS_PER_SECOND
    private const val MAX_FRAME_AGE_NS = 2_500_000_000L
    private val WHITESPACE = Regex("\\s+")
    private val COMPONENT_PATTERN = Regex(
        "([a-zA-Z][a-zA-Z0-9._]*)/(\\.?[a-zA-Z0-9_.$]+)"
    )
    private val LAYER_ID_PATTERN = Regex("#(\\d+)")
    private val SYSTEM_LAYER_MARKERS = listOf(
        "statusbar",
        "navigationbar",
        "wallpaper",
        "screenshotlayer",
        "dim layer",
        "inputmethod",
        "fpsviewer",
        "moe.shizuku.privileged.api"
    )
}
