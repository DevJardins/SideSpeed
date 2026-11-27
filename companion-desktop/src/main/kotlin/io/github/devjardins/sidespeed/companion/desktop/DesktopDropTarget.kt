package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.ui.CardBounds
import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File

/** Where a dragged file would land. */
sealed interface DropHit {
    data class Tv(val tvId: String) : DropHit

    /** No single TV can be chosen: the drop is refused and the hint shows. */
    data object Miss : DropHit
}

object DesktopDropHitTester {
    /**
     * [xPt] and [yPt] are AWT logical points relative to the content area. Card bounds are physical pixels, so each
     * card is divided by its own density first. A card that cannot take an install never matches. A point outside
     * every such card still picks the TV when exactly one can take an install; with several it is a miss.
     */
    fun hitTest(xPt: Double, yPt: Double, cards: Map<String, CardBounds>, reachableTvIds: Set<String>): DropHit {
        for ((id, b) in cards) {
            if (id !in reachableTvIds || b.density <= 0f) continue
            val left = b.leftPx / b.density
            val right = b.rightPx / b.density
            val top = b.topPx / b.density
            val bottom = b.bottomPx / b.density
            if (xPt >= left && xPt <= right && yPt >= top && yPt <= bottom) return DropHit.Tv(id)
        }
        return reachableTvIds.singleOrNull()?.let { DropHit.Tv(it) } ?: DropHit.Miss
    }
}

/** Accepts one dragged file on [component] and tells the window which card it is over. Runs on the AWT thread. */
class DesktopDropTarget(
    private val component: Component,
    private val cards: () -> Map<String, CardBounds>,
    private val reachableTvIds: () -> Set<String>,
    /** The card under the pointer (null when none) and whether the "drop on a card" hint should show. */
    private val onHover: (tvId: String?, hint: Boolean) -> Unit,
    private val onDropFile: (tvId: String, file: File) -> Unit,
) {
    private val target = object : DropTargetAdapter() {
        override fun dragEnter(e: DropTargetDragEvent) = hover(e)
        override fun dragOver(e: DropTargetDragEvent) = hover(e)
        override fun dragExit(e: DropTargetEvent) = onHover(null, false)

        override fun drop(e: DropTargetDropEvent) {
            val file = if (e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                e.acceptDrop(DnDConstants.ACTION_COPY)
                droppedFile(e)
            } else null
            val hit = hit(e.location.x.toDouble(), e.location.y.toDouble())
            if (file == null || hit !is DropHit.Tv) {
                e.dropComplete(false)
                // Keep the hint up: the file was refused because no single card was chosen.
                onHover(null, file != null)
                return
            }
            e.dropComplete(true)
            onHover(null, false)
            onDropFile(hit.tvId, file)
        }
    }

    fun attach() {
        component.dropTarget = DropTarget(component, DnDConstants.ACTION_COPY, target, true)
    }

    fun detach() {
        component.dropTarget = null
    }

    private fun hover(e: DropTargetDragEvent) {
        if (!e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            e.rejectDrag()
            return
        }
        e.acceptDrag(DnDConstants.ACTION_COPY)
        when (val hit = hit(e.location.x.toDouble(), e.location.y.toDouble())) {
            is DropHit.Tv -> onHover(hit.tvId, false)
            DropHit.Miss -> onHover(null, true)
        }
    }

    private fun hit(x: Double, y: Double): DropHit {
        // Card bounds are relative to the Compose content, which sits inside the window's frame insets.
        val insets = (component as? java.awt.Container)?.insets
        return DesktopDropHitTester.hitTest(x - (insets?.left ?: 0), y - (insets?.top ?: 0), cards(), reachableTvIds())
    }

    private fun droppedFile(e: DropTargetDropEvent): File? = try {
        (e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
            ?.firstOrNull { it is File && it.isFile } as File?
    } catch (_: Exception) {
        null
    }
}
