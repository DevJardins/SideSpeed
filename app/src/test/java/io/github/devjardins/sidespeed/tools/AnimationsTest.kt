package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.adb.FakeAdbBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationsTest {
    private fun setup(helperUp: Boolean = true): Triple<FakeAdbBackend, FakeUpdateStore, Animations> {
        val fake = FakeAdbBackend()
        val store = FakeUpdateStore()
        return Triple(fake, store, Animations(ToolsCommands(fake) { helperUp }, store))
    }

    private fun scriptGets(fake: FakeAdbBackend, window: String, transition: String, animator: String) {
        fake.on("settings get global window_animation_scale") { listOf(window) to 0 }
        fake.on("settings get global transition_animation_scale") { listOf(transition) to 0 }
        fake.on("settings get global animator_duration_scale") { listOf(animator) to 0 }
    }

    private fun scriptPuts(fake: FakeAdbBackend, value: String) {
        for (key in AnimationKey.entries) {
            fake.on("settings put global ${key.key} $value") { emptyList<String>() to 0 }
        }
    }

    @Test fun currentReadsAllThreeScales() = runTest {
        val (_, _, animations) = setup().also { (fake, _, _) ->
            scriptGets(fake, "1", "0.5", "1")
        }
        assertEquals(
            mapOf(
                AnimationKey.WINDOW to "1",
                AnimationKey.TRANSITION to "0.5",
                AnimationKey.ANIMATOR to "1",
            ),
            animations.current(),
        )
    }

    @Test fun firstApplySavesOriginalsOnce() = runTest {
        val (fake, store, animations) = setup()
        scriptGets(fake, "1", "1", "1")
        scriptPuts(fake, "0.5")
        fake.on("settings put global window_animation_scale 0") { emptyList<String>() to 0 }
        fake.on("settings put global transition_animation_scale 0") { emptyList<String>() to 0 }
        fake.on("settings put global animator_duration_scale 0") { emptyList<String>() to 0 }

        assertEquals(ToolResult.Ok(Unit), animations.apply(AnimationLevel.FASTER))
        assertEquals(AnimationOriginals("1", "1", "1"), store.animationOriginals.first())

        // Second apply overwrites the scales but must not overwrite the saved originals.
        assertEquals(ToolResult.Ok(Unit), animations.apply(AnimationLevel.OFF))
        assertEquals(AnimationOriginals("1", "1", "1"), store.animationOriginals.first())
    }

    @Test fun restoreWritesSavedOriginalsThenClears() = runTest {
        val (fake, store, animations) = setup()
        store.setAnimationOriginals(AnimationOriginals("1", "1.5", null))
        fake.on("settings put global window_animation_scale 1") { emptyList<String>() to 0 }
        fake.on("settings put global transition_animation_scale 1.5") { emptyList<String>() to 0 }
        fake.on("settings put global animator_duration_scale 1") { emptyList<String>() to 0 }

        assertEquals(ToolResult.Ok(Unit), animations.restore())
        assertEquals(
            listOf(
                "settings put global window_animation_scale 1",
                "settings put global transition_animation_scale 1.5",
                "settings put global animator_duration_scale 1",
            ),
            fake.commands,
        )
        assertNull(store.animationOriginals.first())
    }

    @Test fun restoreWithNothingSavedIsNoop() = runTest {
        val (fake, _, animations) = setup()
        assertEquals(ToolResult.Ok(Unit), animations.restore())
        assertTrue(fake.commands.isEmpty())
    }

    @Test fun helperDownIsSurfaced() = runTest {
        val (_, _, animations) = setup(helperUp = false)
        assertEquals(ToolResult.HelperDown, animations.apply(AnimationLevel.FASTER))
        // current() never throws; unreadable scales read as null.
        assertEquals(
            mapOf(AnimationKey.WINDOW to null, AnimationKey.TRANSITION to null, AnimationKey.ANIMATOR to null),
            animations.current(),
        )
    }

    @Test fun staleHelperMapsToHelperOutdated() = runTest {
        val (fake, _, animations) = setup().also { (f, _, _) ->
            scriptGets(f, "1", "1", "1")
            f.on("settings put global window_animation_scale 0.5") { listOf("refused") to 126 }
        }
        assertEquals(ToolResult.HelperOutdated, animations.apply(AnimationLevel.FASTER))
    }
}
