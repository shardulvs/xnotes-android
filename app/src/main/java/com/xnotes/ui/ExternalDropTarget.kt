package com.xnotes.ui

import android.view.DragEvent
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.xnotes.core.geometry.Pt
import com.xnotes.platform.ExternalDrop

/** AndroidView's native children do not receive Compose's drag target negotiation. */
fun Modifier.externalDropTarget(receiver: ExternalDrop.Receiver): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val target = remember(receiver) {
        object : DragAndDropTarget {
            private fun forward(action: Int, event: DragAndDropEvent): Boolean {
                val native = event.toAndroidDragEvent()
                return receiver.handle(action, native, Pt((native.x - origin.x).toDouble(), (native.y - origin.y).toDouble()))
            }
            override fun onDrop(event: DragAndDropEvent): Boolean = forward(DragEvent.ACTION_DROP, event)
            override fun onStarted(event: DragAndDropEvent) { forward(DragEvent.ACTION_DRAG_STARTED, event) }
            override fun onEntered(event: DragAndDropEvent) { forward(DragEvent.ACTION_DRAG_ENTERED, event) }
            override fun onMoved(event: DragAndDropEvent) { forward(DragEvent.ACTION_DRAG_LOCATION, event) }
            override fun onExited(event: DragAndDropEvent) { forward(DragEvent.ACTION_DRAG_EXITED, event) }
            override fun onEnded(event: DragAndDropEvent) { forward(DragEvent.ACTION_DRAG_ENDED, event) }
        }
    }
    onGloballyPositioned { origin = it.positionInRoot() }
        .dragAndDropTarget(
            shouldStartDragAndDrop = { ExternalDrop.accepts(it.toAndroidDragEvent().clipDescription) },
            target = target,
        )
}
