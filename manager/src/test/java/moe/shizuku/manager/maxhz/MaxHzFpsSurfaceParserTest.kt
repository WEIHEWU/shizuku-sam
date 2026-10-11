package moe.shizuku.manager.maxhz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaxHzFpsSurfaceParserTest {

    @Test
    fun parsesAnyForegroundTargetAndExpandsRelativeActivity() {
        val output = "mResumedActivity: ActivityRecord{abc123 u0 com.example.game/.MainActivity t42}"

        assertEquals(
            MaxHzForegroundTarget("com.example.game", "com.example.game.MainActivity"),
            MaxHzFpsSurfaceParser.foregroundTarget(output)
        )
    }

    @Test
    fun ranksUnrealSurfaceAheadOfWindowAndAcceptsGenericGameActivityLayer() {
        val layers = """
            com.example.unreal/com.epicgames.unreal.GameActivity#101
            SurfaceView[com.example.unreal/com.epicgames.unreal.GameActivity](BLAST)#102
            SurfaceView[GameActivity](BLAST)#103
            StatusBar#999
        """.trimIndent()

        val ranked = MaxHzFpsSurfaceParser.rankedLayers(
            layers,
            "com.example.unreal",
            "com.epicgames.unreal.GameActivity"
        )

        assertEquals(
            "SurfaceView[com.example.unreal/com.epicgames.unreal.GameActivity](BLAST)#102",
            ranked.first()
        )
        assertTrue(ranked.contains("SurfaceView[GameActivity](BLAST)#103"))
    }

    @Test
    fun identifiesUnitySurfaceByTheRuntimePackage() {
        val layers = """
            SurfaceView - com.example.unity/com.unity3d.player.UnityPlayerActivity#220
            com.example.unity/com.unity3d.player.UnityPlayerActivity#219
            SurfaceView - com.other.game/com.unity3d.player.UnityPlayerActivity#999
        """.trimIndent()

        val ranked = MaxHzFpsSurfaceParser.rankedLayers(
            layers,
            "com.example.unity",
            "com.unity3d.player.UnityPlayerActivity"
        )

        assertEquals(
            "SurfaceView - com.example.unity/com.unity3d.player.UnityPlayerActivity#220",
            ranked.first()
        )
    }

    @Test
    fun identifiesGenericNativeActivitySurfaceWithoutAnEngineSpecificRule() {
        val layers = """
            SurfaceView[NativeActivity](BLAST)#302
            com.example.native/android.app.NativeActivity#301
            NavigationBar#900
        """.trimIndent()

        val ranked = MaxHzFpsSurfaceParser.rankedLayers(
            layers,
            "com.example.native",
            "android.app.NativeActivity"
        )

        assertEquals("com.example.native/android.app.NativeActivity#301", ranked.first())
        assertTrue(ranked.contains("SurfaceView[NativeActivity](BLAST)#302"))
        assertEquals(2, ranked.size)
    }

    @Test
    fun doesNotSelectAnotherAppsSurfaceEvenWhenBothUseTheSameEngine() {
        val layers = """
            SurfaceView[com.example.foreground/com.unity3d.player.UnityPlayerActivity](BLAST)#401
            SurfaceView[com.example.background/com.unity3d.player.UnityPlayerActivity](BLAST)#999
        """.trimIndent()

        val ranked = MaxHzFpsSurfaceParser.rankedLayers(
            layers,
            "com.example.foreground",
            "com.unity3d.player.UnityPlayerActivity"
        )

        assertEquals(
            "SurfaceView[com.example.foreground/com.unity3d.player.UnityPlayerActivity](BLAST)#401",
            ranked.first()
        )
        assertEquals(1, ranked.size)
    }

    @Test
    fun usesActualPresentWhenProtectedSurfaceHidesFrameReadyTimestamp() {
        val base = 8_000_000_000L
        val output = buildString {
            appendLine("16666666")
            repeat(7) { index ->
                val timestamp = base + index * 16_666_667L
                appendLine("$timestamp $timestamp ${Long.MAX_VALUE}")
            }
        }

        val timestamps = MaxHzFpsSurfaceParser.presentedFrameTimestamps(output)
        val fps = MaxHzFpsSurfaceParser.calculateFps(timestamps, base + 110_000_000L)

        assertEquals(60.0, fps, 0.1)
    }

    @Test
    fun rejectsOldHistoryFromDestroyedLoadingSurface() {
        val timestamps = listOf(1_000_000_000L, 1_016_666_667L, 1_033_333_334L)

        assertEquals(
            -1.0,
            MaxHzFpsSurfaceParser.calculateFps(timestamps, 5_000_000_000L),
            0.0
        )
    }
}
