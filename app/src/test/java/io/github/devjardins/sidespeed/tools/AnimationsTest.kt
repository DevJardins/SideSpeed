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

    private fun setupFireOs(helperUp: Boolean = true): Triple<FakeAdbBackend, FakeUpdateStore, Animations> {
        val fake = FakeAdbBackend()
        val store = FakeUpdateStore()
        return Triple(fake, store, Animations(ToolsCommands(fake) { helperUp }, store, isFireOs = true))
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
        scriptGets(fake, "0.5", "0.5", "0.5")
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
            fake.commands.filter { it.startsWith("settings put") },
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

    @Test fun refusedPutMapsToRefused() = runTest {
        val (fake, store, animations) = setup().also { (f, _, _) ->
            scriptGets(f, "1", "1", "1")
            f.on("settings put global window_animation_scale 0.5") { listOf("refused") to 126 }
        }
        // Refusal is not "restart the helper": it surfaces as-is.
        assertEquals(ToolResult.Refused, animations.apply(AnimationLevel.FASTER))
        // The first put never landed, so nothing may be saved.
        assertNull(store.animationOriginals.first())
    }

    @Test fun restoreOfNonStandardOriginalSucceeds() = runTest {
        val (fake, store, animations) = setup()
        store.setAnimationOriginals(AnimationOriginals("0.333", "12", "0.000001"))
        scriptGets(fake, "0.5", "0.5", "0.5")
        for (key in AnimationKey.entries) {
            val saved = mapOf(
                AnimationKey.WINDOW to "0.333",
                AnimationKey.TRANSITION to "12",
                AnimationKey.ANIMATOR to "0.000001",
            ).getValue(key)
            fake.on("settings put global ${key.key} $saved") { emptyList<String>() to 0 }
        }
        assertEquals(ToolResult.Ok(Unit), animations.restore())
        assertNull(store.animationOriginals.first())
    }

    @Test fun secondPutFailsRollsBackFirstAndSavesNothing() = runTest {
        val (fake, store, animations) = setup().also { (f, _, _) ->
            scriptGets(f, "1", "1", "1")
            f.on("settings put global window_animation_scale 0.5") { emptyList<String>() to 0 }
            f.on("settings put global transition_animation_scale 0.5") { listOf("boom") to 1 }
            f.on("settings put global animator_duration_scale 0.5") { emptyList<String>() to 0 }
            f.on("settings put global window_animation_scale 1") { emptyList<String>() to 0 }
        }
        val result = animations.apply(AnimationLevel.FASTER)
        assertTrue(result is ToolResult.Failed)
        // Failed put stops the sequence; the landed write is rolled back to the snapshot.
        assertEquals(
            listOf(
                "settings put global window_animation_scale 0.5",
                "settings put global transition_animation_scale 0.5",
                "settings put global window_animation_scale 1",
            ),
            fake.commands.filter { it.startsWith("settings put") },
        )
        // Rollback restored everything, so no originals are kept.
        assertNull(store.animationOriginals.first())
    }

    @Test fun restorePartialFailureRollsBackAndKeepsOriginals() = runTest {
        val (fake, store, animations) = setup()
        store.setAnimationOriginals(AnimationOriginals("1", "1", "1"))
        scriptGets(fake, "0.5", "0.5", "0.5")
        fake.on("settings put global window_animation_scale 1") { emptyList<String>() to 0 }
        fake.on("settings put global transition_animation_scale 1") { listOf("boom") to 1 }
        fake.on("settings put global animator_duration_scale 1") { emptyList<String>() to 0 }
        fake.on("settings put global window_animation_scale 0.5") { emptyList<String>() to 0 }

        val result = animations.restore()
        assertTrue(result is ToolResult.Failed)
        assertEquals(
            listOf(
                "settings put global window_animation_scale 1",
                "settings put global transition_animation_scale 1",
                "settings put global window_animation_scale 0.5",
            ),
            fake.commands.filter { it.startsWith("settings put") },
        )
        // Retry must still be possible: the saved originals survive a failed restore.
        assertEquals(AnimationOriginals("1", "1", "1"), store.animationOriginals.first())
    }

    @Test fun matchLevelAcceptsCanonicalNumberForms() {
        assertEquals(AnimationLevel.NORMAL, matchLevel(listOf("1", "1", "1")))
        assertEquals(AnimationLevel.NORMAL, matchLevel(listOf("1.0", "1", "1.0")))
        assertEquals(AnimationLevel.FASTER, matchLevel(listOf("0.5", "0.5", "0.5")))
        assertEquals(AnimationLevel.FASTER, matchLevel(listOf("0.50", "0.5", "0.50")))
        assertEquals(AnimationLevel.OFF, matchLevel(listOf("0", "0", "0")))
        assertEquals(AnimationLevel.OFF, matchLevel(listOf("0.0", "0", "0.00")))
    }

    @Test fun fireOsApplyFasterNeverWritesAnimator() = runTest {
        val (fake, _, animations) = setupFireOs()
        scriptGets(fake, "1", "1", "1")
        fake.on("settings put global window_animation_scale 0.5") { emptyList<String>() to 0 }
        fake.on("settings put global transition_animation_scale 0.5") { emptyList<String>() to 0 }
        // No animator put is scripted: an attempt would exit 127 and fail the apply.

        assertEquals(ToolResult.Ok(Unit), animations.apply(AnimationLevel.FASTER))
        assertEquals(
            listOf(
                "settings put global window_animation_scale 0.5",
                "settings put global transition_animation_scale 0.5",
            ),
            fake.commands.filter { it.startsWith("settings put") },
        )
    }

    @Test fun fireOsApplyOffNeverWritesAnimator() = runTest {
        val (fake, _, animations) = setupFireOs()
        scriptGets(fake, "1", "1", "1")
        fake.on("settings put global window_animation_scale 0") { emptyList<String>() to 0 }
        fake.on("settings put global transition_animation_scale 0") { emptyList<String>() to 0 }

        assertEquals(ToolResult.Ok(Unit), animations.apply(AnimationLevel.OFF))
        assertEquals(
            listOf(
                "settings put global window_animation_scale 0",
                "settings put global transition_animation_scale 0",
            ),
            fake.commands.filter { it.startsWith("settings put") },
        )
    }

    @Test fun fireOsRestoreRestoresAnimatorIfOriginalSaved() = runTest {
        val (fake, store, animations) = setupFireOs()
        store.setAnimationOriginals(AnimationOriginals("1", "1", "1"))
        scriptGets(fake, "0.5", "0.5", "0.5")
        fake.on("settings put global window_animation_scale 1") { emptyList<String>() to 0 }
        fake.on("settings put global transition_animation_scale 1") { emptyList<String>() to 0 }
        fake.on("settings put global animator_duration_scale 1") { emptyList<String>() to 0 }

        assertEquals(ToolResult.Ok(Unit), animations.restore())
        assertEquals(
            listOf(
                "settings put global window_animation_scale 1",
                "settings put global transition_animation_scale 1",
                "settings put global animator_duration_scale 1",
            ),
            fake.commands.filter { it.startsWith("settings put") },
        )
        assertNull(store.animationOriginals.first())
    }

    @Test fun fireOsApplyFailureRollsBackOnlyWrittenKeys() = runTest {
        val (fake, store, animations) = setupFireOs().also { (f, _, _) ->
            scriptGets(f, "1", "1", "1")
            f.on("settings put global window_animation_scale 0.5") { emptyList<String>() to 0 }
            f.on("settings put global transition_animation_scale 0.5") { listOf("boom") to 1 }
            f.on("settings put global window_animation_scale 1") { emptyList<String>() to 0 }
        }
        val result = animations.apply(AnimationLevel.FASTER)
        assertTrue(result is ToolResult.Failed)
        assertEquals(
            listOf(
                "settings put global window_animation_scale 0.5",
                "settings put global transition_animation_scale 0.5",
                "settings put global window_animation_scale 1",
            ),
            fake.commands.filter { it.startsWith("settings put") },
        )
        assertNull(store.animationOriginals.first())
    }

    @Test fun fireOsMatchLevelIgnoresAnimator() {
        val faster = mapOf(
            AnimationKey.WINDOW to "0.5",
            AnimationKey.TRANSITION to "0.5",
            AnimationKey.ANIMATOR to "1",
        )
        assertEquals(AnimationLevel.FASTER, matchLevel(faster, isFireOs = true))
        // The same readings on a non-Fire OS box are mixed and match nothing.
        assertNull(matchLevel(faster, isFireOs = false))
        assertNull(matchLevel(listOf("0.5", "0.5", "1.0")))
        // Animator value does not affect the Fire OS match at all.
        assertEquals(
            AnimationLevel.NORMAL,
            matchLevel(
                mapOf(
                    AnimationKey.WINDOW to "1",
                    AnimationKey.TRANSITION to "1.0",
                    AnimationKey.ANIMATOR to "0",
                ),
                isFireOs = true,
            ),
        )
        assertEquals(
            AnimationLevel.OFF,
            matchLevel(
                mapOf(
                    AnimationKey.WINDOW to "0",
                    AnimationKey.TRANSITION to "0.0",
                    AnimationKey.ANIMATOR to "1",
                ),
                isFireOs = true,
            ),
        )
        // Window/transition disagreement still matches nothing on Fire OS.
        assertNull(
            matchLevel(
                mapOf(
                    AnimationKey.WINDOW to "0.5",
                    AnimationKey.TRANSITION to "1",
                    AnimationKey.ANIMATOR to "0.5",
                ),
                isFireOs = true,
            ),
        )
    }

    @Test fun matchLevelRejectsMixedOrUnreadable() {
        assertNull(matchLevel(listOf("0.5", "1", "0.5")))
        assertNull(matchLevel(listOf("1", null, "1")))
        assertNull(matchLevel(listOf("1", "abc", "1")))
        assertNull(matchLevel(emptyList()))
    }
}
