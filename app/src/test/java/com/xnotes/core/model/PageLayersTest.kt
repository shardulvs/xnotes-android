package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PageLayersTest {

    private val ink = Rgba(1, 2, 3, 255)

    private fun fill() = ShapeItem.fill(listOf(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0))), ink)

    private fun box() = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(10.0, 10.0), ink)

    @Test fun onlyTheFillsAtTheVeryBottomGoUnderTheText() {
        assertEquals(0, PageLayers.underText(emptyList()))
        assertEquals(2, PageLayers.underText(listOf(fill(), fill(), box(), fill())))
        assertEquals(0, PageLayers.underText(listOf(box(), fill())))
    }

    @Test fun theTextPaintsBetweenThem() {
        val a = fill()
        val b = box()
        val c = fill()
        val order = ArrayList<Any>()
        PageLayers.paint(listOf(a, b, c), { order.add("text") }) { order.add(it) }
        assertEquals(listOf(a, "text", b, c), order)
    }
}
