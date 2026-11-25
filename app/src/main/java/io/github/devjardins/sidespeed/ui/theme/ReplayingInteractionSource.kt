package io.github.devjardins.sidespeed.ui.theme

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription

/**
 * An interaction source that tells a late listener what is already going on.
 *
 * tv-material's Surface starts listening for focus from an effect, after the control is
 * attached. When Compose gives a control focus while it is still attaching (for example
 * because the focused control on the previous screen was just removed), the focus event
 * goes out before anyone listens and is lost: the control has focus but draws no ring.
 * This source replays the focus, press and hover interactions still in progress to every
 * new listener, so the ring always matches the real focus.
 */
class ReplayingInteractionSource : MutableInteractionSource {
    private val live = MutableSharedFlow<Interaction>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val ongoing = mutableListOf<Interaction>()

    override val interactions: Flow<Interaction> =
        live.onSubscription { snapshot().forEach { emit(it) } }

    override suspend fun emit(interaction: Interaction) {
        track(interaction)
        live.emit(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        track(interaction)
        return live.tryEmit(interaction)
    }

    private fun snapshot(): List<Interaction> = synchronized(ongoing) { ongoing.toList() }

    private fun track(interaction: Interaction) = synchronized(ongoing) {
        when (interaction) {
            is FocusInteraction.Focus, is PressInteraction.Press, is HoverInteraction.Enter ->
                ongoing += interaction
            is FocusInteraction.Unfocus -> ongoing -= interaction.focus
            is PressInteraction.Release -> ongoing -= interaction.press
            is PressInteraction.Cancel -> ongoing -= interaction.press
            is HoverInteraction.Exit -> ongoing -= interaction.enter
            else -> Unit
        }
    }
}
