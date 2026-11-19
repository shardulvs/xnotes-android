package com.xnotes.core.importing

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.AddItem
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.History
import com.xnotes.core.infinite.AddCanvasItems
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.TextItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DropPlacementTest {
    private val area = Rect(0.0, 0.0, 1000.0, 800.0)
    private val color = Rgba(12, 34, 56)
    private val source = ImageData(File("dropped.png"), 2000, 1000)

    private fun place(content: List<DroppedContent>, at: Pt = Pt(200.0, 150.0),
                      constrained: Boolean = true, bounds: Rect = area, labels: Boolean = false) =
        DropPlacement.items(content, at, bounds, constrained, color, 18.0,
            TextItem.DEFAULT_FACE, FakeTextMeasurer(), textAsLabel = labels)

    @Test fun textPreservesWordsLineBreaksAndUsesTheDropPoint() {
        val text = "Texto arrastado 📝\nhttps://example.com"
        val item = place(listOf(DroppedContent.Text(text))).single() as TextItem
        assertEquals(text, item.text)
        assertEquals(Pt(200.0, 150.0), item.pos)
        assertEquals(color, item.rgba)
        assertEquals(18.0, item.pointSize, 0.0)
    }

    @Test fun imageIsCenteredAtDropAndScaledWithoutChangingAspect() {
        val item = place(listOf(DroppedContent.Image(source)), Pt(500.0, 400.0)).single() as ImageItem
        assertEquals(Rect(200.0, 250.0, 600.0, 300.0), item.bounds())
    }

    @Test fun pageEdgeAndMarginsKeepDroppedContentOnThePaper() {
        val paper = Rect(-50.0, -80.0, 1100.0, 960.0)
        val items = place(listOf(DroppedContent.Text("text"), DroppedContent.Image(source)),
            Pt(1049.0, 879.0), bounds = paper)
        for (item in items) {
            assertTrue(paper.contains(item.bounds().topLeft))
            assertTrue(paper.contains(Pt(item.bounds().right, item.bounds().bottom)))
        }
    }

    @Test fun infiniteCanvasKeepsNegativeCoordinatesOutsideVisibleArea() {
        val item = place(listOf(DroppedContent.Text("text")), Pt(-600.0, -900.0), constrained = false).single()
        assertEquals(Pt(-600.0, -900.0), item.bounds().topLeft)
    }

    @Test fun mixedDropIsOneUndoStepAndRedoRestoresTheSameItems() {
        val page = Page(1000.0, 800.0)
        val existing = TextItem(Pt.ZERO, text = "existing", measurer = FakeTextMeasurer())
        page.items.add(existing)
        val items = place(listOf(DroppedContent.Text("drop"), DroppedContent.Image(source)))
        assertNotEquals(items[0].bounds().center, items[1].bounds().center)
        page.items.addAll(items)
        val history = History()
        history.push(CompositeCommand(items.map { AddItem(page, it) }))
        history.undo()
        assertEquals(listOf(existing), page.items)
        assertFalse(history.canUndo)
        history.redo()
        assertEquals(listOf(existing) + items, page.items)
    }

    @Test fun infiniteTextUsesAnEditableVisibleLabelAndSupportsBatchUndo() {
        val items = place(listOf(DroppedContent.Text("editable text")), constrained = false, labels = true)
        val shape = items.single() as ShapeItem
        assertEquals("editable text", shape.label!!.text)
        assertTrue(shape.takesLabel)
        assertEquals(0, shape.strokeRgba.a)
        assertFalse(ItemMesher.mesh(shape)!!.isEmpty)
        val doc = InfiniteDocument()
        doc.addAll(items)
        val history = History()
        history.push(AddCanvasItems(doc, items))
        history.undo()
        assertEquals(0, doc.itemCount)
        history.redo()
        assertTrue(doc.containsRef(shape))
    }

    @Test fun infiniteDroppedTextSurvivesSavingAndReopening() {
        val doc = InfiniteDocument()
        doc.addAll(place(listOf(DroppedContent.Text("Keep this text 📝")), constrained = false, labels = true))
        val codec = com.xnotes.format.CanvasCodec(com.xnotes.core.FakeImageCodec(), FakeTextMeasurer())
        val bytes = java.io.ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()
        val back = codec.read(java.io.ByteArrayInputStream(bytes))
        val shape = back.items.single() as ShapeItem
        assertEquals("Keep this text 📝", shape.label!!.text)
        assertEquals(color, shape.label!!.style.rgba)
        assertEquals(0, shape.strokeRgba.a)
        assertFalse(ItemMesher.mesh(shape)!!.isEmpty)
    }

    @Test fun longPassagesUseTheAvailableWidthInsteadOfANarrowColumn() {
        val short = place(listOf(DroppedContent.Text("short text"))).single() as TextItem
        val long = place(listOf(DroppedContent.Text("A longer passage with many words. ".repeat(300)))).single() as TextItem
        assertTrue(long.width > short.width)
        assertTrue(long.width > 300.0)
        assertTrue(long.width <= area.w * 0.7)
    }

    @Test fun longInfiniteTextFitsInsideItsEditableHost() {
        val shape = place(listOf(DroppedContent.Text("Long imported paragraph. ".repeat(300))),
            constrained = false, labels = true).single() as ShapeItem
        val frame = shape.labelFrame()!!
        assertTrue(shape.box.contains(frame.topLeft))
        assertTrue(shape.box.contains(Pt(frame.right, frame.bottom)))
    }

    @Test fun blankTextAndInvalidImagesDoNotBecomeItems() {
        assertTrue(place(listOf(DroppedContent.Text(" \n"),
            DroppedContent.Image(ImageData(File("bad"), 0, 10)))).isEmpty())
    }
}
