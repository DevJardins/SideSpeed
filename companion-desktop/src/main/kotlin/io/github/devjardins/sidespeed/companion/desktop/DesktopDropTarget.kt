package io.github.devjardins.sidespeed.companion.desktop

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import io.github.devjardins.sidespeed.companion.ui.InstallDropHook
import java.awt.datatransfer.DataFlavor
import java.io.File

/** Where a dropped file lands. */
sealed interface DropHit {
    data class Tv(val tvId: String) : DropHit

    /** No single TV can be chosen: the drop is refused and the hint shows. */
    data object Miss : DropHit
}

/** The pure decisions behind a drop, kept apart from Compose so they can be tested. */
object DesktopDrop {
    /** The one dropped file when it is a single app file SideSpeed can install; null for anything else. */
    fun singleInstallFile(files: List<*>?): File? {
        val file = files?.singleOrNull() as? File ?: return null
        return file.takeIf { it.isFile && DesktopInstallPicker.isInstallFileName(it.name) }
    }

    /**
     * The TV a drop goes to. [cardTvId] is the card under the pointer (null outside every card). A card that cannot
     * take an install never matches. Outside the cards, the drop still picks the TV when exactly one can take an
     * install; with several it is a miss.
     */
    fun target(cardTvId: String?, installableTvIds: Set<String>): DropHit {
        if (cardTvId != null && cardTvId in installableTvIds) return DropHit.Tv(cardTvId)
        return installableTvIds.singleOrNull()?.let { DropHit.Tv(it) } ?: DropHit.Miss
    }
}

/**
 * Makes Home accept dropped app files through Compose's own drop targets (Compose owns the window's AWT drop
 * target, so a frame-level one never hears a drag). A card's target sits inside the Home target, and Compose hands
 * a drag to the innermost target under the pointer, so the Home target only sees drags outside every card.
 * All callbacks run on the UI thread.
 */
class DesktopInstallDrop(private val onDropFile: (tvId: String, file: File) -> Unit) : InstallDropHook {
    /** The card the dragged file would land on, or null. */
    var highlightTvId by mutableStateOf<String?>(null)
        private set

    /** Whether the "drop on a card" hint shows: several TVs and the file is outside every card. */
    var hintVisible by mutableStateOf(false)
        private set

    fun clear() {
        highlightTvId = null
        hintVisible = false
    }

    private fun show(hit: DropHit) {
        when (hit) {
            is DropHit.Tv -> { highlightTvId = hit.tvId; hintVisible = false }
            DropHit.Miss -> { highlightTvId = null; hintVisible = true }
        }
    }

    @Composable
    override fun card(tvId: String): Modifier {
        val target = remember(tvId) {
            object : DragAndDropTarget {
                override fun onStarted(event: DragAndDropEvent) = clear()
                override fun onEntered(event: DragAndDropEvent) = show(DropHit.Tv(tvId))
                override fun onExited(event: DragAndDropEvent) { if (highlightTvId == tvId) highlightTvId = null }
                override fun onEnded(event: DragAndDropEvent) { if (highlightTvId == tvId) highlightTvId = null }
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    clear()
                    val file = droppedFile(event) ?: return false
                    onDropFile(tvId, file)
                    return true
                }
            }
        }
        return Modifier.dragAndDropTarget(shouldStartDragAndDrop = ::carriesFiles, target = target)
    }

    @Composable
    override fun home(installableTvIds: Set<String>): Modifier {
        val installable by rememberUpdatedState(installableTvIds)
        val target = remember {
            object : DragAndDropTarget {
                override fun onStarted(event: DragAndDropEvent) = clear()
                override fun onEntered(event: DragAndDropEvent) = show(DesktopDrop.target(null, installable))
                override fun onMoved(event: DragAndDropEvent) = show(DesktopDrop.target(null, installable))
                // Leaving Home for a card, or leaving the window, takes the hint down with it.
                override fun onExited(event: DragAndDropEvent) = clear()
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val file = droppedFile(event)
                    val hit = DesktopDrop.target(null, installable)
                    if (file == null || hit !is DropHit.Tv) {
                        // A refused drop keeps the hint up when only the choice of TV was missing.
                        highlightTvId = null
                        hintVisible = file != null && hit == DropHit.Miss
                        return false
                    }
                    clear()
                    onDropFile(hit.tvId, file)
                    return true
                }
            }
        }
        return Modifier
            .dragAndDropTarget(shouldStartDragAndDrop = { installable.isNotEmpty() && carriesFiles(it) }, target = target)
            // A left-over hint goes away on the next press anywhere on Home. The press is only watched, never used.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press && hintVisible) hintVisible = false
                    }
                }
            }
    }

    // awtTransferable is the desktop way to read what is being dragged in Compose 1.12.
    @OptIn(ExperimentalComposeUiApi::class)
    private fun carriesFiles(event: DragAndDropEvent): Boolean =
        runCatching { event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }.getOrDefault(false)

    /** The file list is read only on drop: some systems refuse to hand it over while the drag is still moving. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun droppedFile(event: DragAndDropEvent): File? = try {
        DesktopDrop.singleInstallFile(event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
    } catch (_: Exception) {
        null
    }
}
