package io.github.devjardins.sidespeed.ui.theme

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayingInteractionSourceTest {

    private fun TestScope.listen(source: ReplayingInteractionSource): MutableList<Interaction> {
        val seen = mutableListOf<Interaction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.interactions.collect { seen += it }
        }
        return seen
    }

    @Test
    fun `a listener that starts after focus still hears it`() = runTest {
        val source = ReplayingInteractionSource()
        val focus = FocusInteraction.Focus()
        source.emit(focus)

        val seen = listen(source)

        assertEquals(listOf<Interaction>(focus), seen)
    }

    @Test
    fun `tryEmit is remembered too`() = runTest {
        val source = ReplayingInteractionSource()
        val focus = FocusInteraction.Focus()
        source.tryEmit(focus)

        val seen = listen(source)

        assertEquals(listOf<Interaction>(focus), seen)
    }

    @Test
    fun `focus that has ended is not replayed`() = runTest {
        val source = ReplayingInteractionSource()
        val focus = FocusInteraction.Focus()
        source.emit(focus)
        source.emit(FocusInteraction.Unfocus(focus))

        val seen = listen(source)

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `presses and hovers are replayed until they end`() = runTest {
        val source = ReplayingInteractionSource()
        val press = PressInteraction.Press(Offset.Zero)
        val cancelled = PressInteraction.Press(Offset.Zero)
        val hover = HoverInteraction.Enter()
        val gone = HoverInteraction.Enter()
        source.emit(press)
        source.emit(cancelled)
        source.emit(PressInteraction.Cancel(cancelled))
        source.emit(hover)
        source.emit(gone)
        source.emit(HoverInteraction.Exit(gone))

        val seen = listen(source)

        assertEquals(listOf<Interaction>(press, hover), seen)

        source.emit(PressInteraction.Release(press))
        val later = listen(source)
        assertEquals(listOf<Interaction>(hover), later)
    }

    @Test
    fun `a listener keeps hearing new interactions after the replay`() = runTest {
        val source = ReplayingInteractionSource()
        val focus = FocusInteraction.Focus()
        source.emit(focus)
        val seen = listen(source)

        val unfocus = FocusInteraction.Unfocus(focus)
        source.emit(unfocus)

        assertEquals(listOf<Interaction>(focus, unfocus), seen)
    }
}
