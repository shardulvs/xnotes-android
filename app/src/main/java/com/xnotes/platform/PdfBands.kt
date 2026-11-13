package com.xnotes.platform

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.util.Matrix
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rooms
import com.xnotes.core.model.displayPoint
import com.xnotes.core.model.displayRects
import com.xnotes.core.model.sourceHeight
import com.xnotes.core.model.sourceWidth
import com.xnotes.core.pdf.PageFrame
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A source PDF page with room inserted into it, as it goes into an export. Its own content is
 * drawn once into a form, and the form is placed once for each tile the room cuts the page into,
 * clipped to the tile and moved where the room put it, so the page looks in a viewer exactly as
 * it does in the app. The page's annotations go with their tile: an annotation the room cuts
 * across is a link split in two, a form field kept whole with the upper band so it can still be
 * filled, or anything else drawn into the page where it is, cut like everything round it.
 *
 * Text drawn into several tiles reads there once for each, which is what copying from such a page
 * in a viewer shows.
 */
internal object PdfBands {

    /** What a band moves a point of [page]'s source by, in its user space. */
    private class Mover(val page: Page, val frame: PageFrame) {
        /** A user point on the page as it was, where the page with its room shows it. */
        fun point(p: Pt): Pt {
            val d = page.displayPoint(frame.fromUser(p))
            return frame.toUser(d.x, d.y)
        }

        /** The user-space shift of the tile holding source px rect [r]'s top-left corner. */
        fun shiftOf(r: Rect): Pt {
            val pieces = page.displayRects(r)
            val first = pieces.firstOrNull() ?: return Pt.ZERO
            val o = frame.toUser(r.left, r.top)
            val d = frame.toUser(first.left, first.top)
            return Pt(d.x - o.x, d.y - o.y)
        }
    }

    /**
     * Rewrite [pd], the export's copy of a source page, to show through [page]'s room. [frame] puts
     * the page as displayed on [pd]; call before the page's boxes grow.
     */
    fun band(doc: PDDocument, pd: PDPage, page: Page, frame: PageFrame) {
        val mover = Mover(page, frame)
        val kept = ArrayList<PDAnnotation>()
        val flat = ArrayList<PDAnnotation>()
        for (a in runCatching { pd.annotations }.getOrDefault(emptyList())) {
            val rect = a.rectangle
            if (rect == null) {
                kept += a
                continue
            }
            val src = sourceRect(frame, rect)
            val pieces = page.displayRects(src)
            val crossing = pieces.size > 1
            val visible = !a.isHidden && !a.isNoView
            when {
                !crossing -> shift(a.cosObject, mover.shiftOf(src)).also { kept += a }
                a.subtype == "Link" -> for (piece in pieces) kept += splitLink(a, frame.toUser(piece))
                a.subtype == "Widget" || a.subtype == "Popup" || !visible || a.normalAppearanceStream == null ->
                    shift(a.cosObject, mover.shiftOf(src)).also { kept += a }
                else -> flat += a
            }
        }
        val form = PDFormXObject(PDStream(doc))
        val resources = ownResources(pd.resources)
        form.resources = resources
        form.bBox = pd.mediaBox
        form.contentStream.createOutputStream(COSName.FLATE_DECODE).use { out ->
            if (pd.hasContents()) pd.contents.use { it.copyTo(out) }
            // What a cut annotation shows, drawn into the page under its tiles.
            val sb = StringBuilder("\n")
            for (a in flat) {
                val ap = a.normalAppearanceStream ?: continue
                val m = placement(ap.bBox, ap.matrix, a.rectangle) ?: continue
                val name = resources.add(ap as PDFormXObject)
                sb.append("q ").append(n(m[0])).append(' ').append(n(m[1])).append(' ').append(n(m[2])).append(' ')
                    .append(n(m[3])).append(' ').append(n(m[4])).append(' ').append(n(m[5])).append(" cm /")
                    .append(name.name).append(" Do Q\n")
            }
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        }
        pd.resources = PDResources()
        PDPageContentStream(doc, pd, PDPageContentStream.AppendMode.OVERWRITE, true, false).use { cs ->
            for (t in Rooms.tiles(page.rooms, page.sourceWidth, page.sourceHeight)) {
                val clip = frame.toUser(t.display)
                val o = frame.toUser(0.0, 0.0)
                val d = frame.toUser(t.dx, t.dy)
                cs.saveGraphicsState()
                cs.addRect(clip.left.toFloat(), clip.top.toFloat(), clip.w.toFloat(), clip.h.toFloat())
                cs.clip()
                cs.transform(Matrix(1f, 0f, 0f, 1f, (d.x - o.x).toFloat(), (d.y - o.y).toFloat()))
                cs.drawForm(form)
                cs.restoreGraphicsState()
            }
        }
        pd.annotations = kept
    }

    /**
     * Move every destination in [doc] that opens on a page with room to where that room put it:
     * the bookmarks', the links' and the named ones'. [pages] are those pages, by their page
     * dictionary, with where their content was drawn.
     */
    fun moveDestinations(doc: PDDocument, pages: Map<COSDictionary, Pair<Page, PageFrame>>) {
        if (pages.isEmpty()) return
        val movers = IdentityHashMap<COSDictionary, Mover>()
        for ((dict, v) in pages) movers[dict] = Mover(v.first, v.second)
        val done = Collections.newSetFromMap(IdentityHashMap<COSArray, Boolean>())
        fun move(dest: COSBase?) {
            val arr = resolve(dest) as? COSArray ?: return
            if (!done.add(arr) || arr.size() < 2) return
            val page = resolve(arr.get(0)) as? COSDictionary ?: return
            val m = movers[page] ?: return
            moveArray(arr, m)
        }
        fun moveAction(action: COSBase?) {
            val a = resolve(action) as? COSDictionary ?: return
            if (a.getCOSName(COSName.S)?.name == "GoTo") move(named(doc, a.getDictionaryObject(COSName.D)))
        }
        runCatching {
            val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES) as? COSDictionary
            var seen = 0
            fun walk(node: COSDictionary?, depth: Int) {
                var item = node?.getDictionaryObject(COSName.FIRST) as? COSDictionary
                while (item != null && seen++ < MAX_OUTLINE && depth < MAX_DEPTH) {
                    move(named(doc, item.getDictionaryObject(COSName.DEST)))
                    moveAction(item.getDictionaryObject(COSName.A))
                    walk(item, depth + 1)
                    item = item.getDictionaryObject(COSName.NEXT) as? COSDictionary
                }
            }
            walk(root, 0)
        }
        for (pd in doc.pages) {
            val annots = runCatching { pd.annotations }.getOrNull() ?: continue
            for (a in annots) {
                if (a.subtype != "Link") continue
                move(named(doc, a.cosObject.getDictionaryObject(COSName.DEST)))
                moveAction(a.cosObject.getDictionaryObject(COSName.A))
            }
        }
    }

    /** An explicit destination array's coordinates moved through [m]; unset ones stay unset. */
    private fun moveArray(arr: COSArray, m: Mover) {
        val type = (arr.getObject(1) as? COSName)?.name ?: return
        val box = m.frame.toUser(Rect(0.0, 0.0, m.page.sourceWidth, m.page.sourceHeight))
        fun num(i: Int): Double? = (arr.getObject(i) as? COSNumber)?.doubleValue()
        fun set(i: Int, v: Double) { if (i < arr.size() && arr.getObject(i) is COSNumber) arr.set(i, COSFloat(v.toFloat())) }
        when (type) {
            "XYZ" -> {
                val x = num(2)
                val y = num(3)
                val p = m.point(Pt(x ?: box.left, y ?: box.bottom))
                if (x != null) set(2, p.x)
                if (y != null) set(3, p.y)
            }
            "FitH", "FitBH" -> num(2)?.let { set(2, m.point(Pt(box.left, it)).y) }
            "FitV", "FitBV" -> num(2)?.let { set(2, m.point(Pt(it, box.bottom)).x) }
            "FitR" -> {
                val l = num(2) ?: return
                val b = num(3) ?: return
                val r = num(4) ?: return
                val t = num(5) ?: return
                val a = m.point(Pt(l, b))
                val c = m.point(Pt(r, t))
                set(2, minOf(a.x, c.x))
                set(3, minOf(a.y, c.y))
                set(4, maxOf(a.x, c.x))
                set(5, maxOf(a.y, c.y))
            }
        }
    }

    /** A named destination as the array it names, or [dest] as it is. */
    private fun named(doc: PDDocument, dest: COSBase?): COSBase? {
        val d = resolve(dest)
        val name = when (d) {
            is COSName -> d.name
            is com.tom_roush.pdfbox.cos.COSString -> d.string
            else -> return d
        }
        val found = runCatching {
            doc.documentCatalog.findNamedDestinationPage(
                com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination(name),
            )
        }.getOrNull() ?: return null
        return found.cosObject
    }

    private fun resolve(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    /** The page's resources as the form's own: the same entries, but a list of forms it can add to without touching any other page's. */
    private fun ownResources(src: PDResources?): PDResources {
        val dict = src?.cosObject?.let { COSDictionary(it) } ?: COSDictionary()
        val xo = dict.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
        dict.setItem(COSName.XOBJECT, if (xo != null) COSDictionary(xo) else COSDictionary())
        return PDResources(dict)
    }

    /** [rect], user space on [frame]'s page, as px of the page as it was before any room. */
    private fun sourceRect(frame: PageFrame, rect: PDRectangle): Rect {
        val a = frame.fromUser(Pt(rect.lowerLeftX.toDouble(), rect.lowerLeftY.toDouble()))
        val b = frame.fromUser(Pt(rect.upperRightX.toDouble(), rect.upperRightY.toDouble()))
        return Rect.fromPoints(a, b)
    }

    /** A link [a] cut down to the piece of it at [user]: a copy that goes where it went, and no longer claims the lines of text it covered. */
    private fun splitLink(a: PDAnnotation, user: Rect): PDAnnotation {
        val dict = COSDictionary(a.cosObject)
        dict.removeItem(COSName.getPDFName("QuadPoints"))
        val out = PDAnnotation.createAnnotation(dict)
        out.rectangle = PDRectangle(user.left.toFloat(), user.top.toFloat(), user.w.toFloat(), user.h.toFloat())
        return out
    }

    /** Move an annotation by [d] in user space: its box and the points it is drawn along. */
    private fun shift(dict: COSDictionary, d: Pt) {
        if (d.x == 0.0 && d.y == 0.0) return
        fun pairs(arr: COSArray?) {
            arr ?: return
            for (i in 0 until arr.size()) {
                val v = arr.getObject(i) as? COSNumber ?: continue
                arr.set(i, COSFloat((v.doubleValue() + if (i % 2 == 0) d.x else d.y).toFloat()))
            }
        }
        pairs(dict.getDictionaryObject(COSName.RECT) as? COSArray)
        for (key in listOf("QuadPoints", "Vertices", "L", "CL")) pairs(dict.getDictionaryObject(COSName.getPDFName(key)) as? COSArray)
        (dict.getDictionaryObject(COSName.getPDFName("InkList")) as? COSArray)?.let { list ->
            for (i in 0 until list.size()) pairs(list.getObject(i) as? COSArray)
        }
    }

    /**
     * The matrix that sets an appearance with [bbox] and its own [matrix] onto [rect], as a viewer
     * places it: the box, turned by the matrix, fitted to the rect.
     */
    private fun placement(bbox: PDRectangle?, matrix: Matrix?, rect: PDRectangle?): DoubleArray? {
        bbox ?: return null
        rect ?: return null
        val m = matrix ?: Matrix()
        val xs = DoubleArray(4)
        val ys = DoubleArray(4)
        val corners = listOf(bbox.lowerLeftX to bbox.lowerLeftY, bbox.upperRightX to bbox.lowerLeftY, bbox.upperRightX to bbox.upperRightY, bbox.lowerLeftX to bbox.upperRightY)
        for ((i, c) in corners.withIndex()) {
            xs[i] = (m.scaleX * c.first + m.shearX * c.second + m.translateX).toDouble()
            ys[i] = (m.shearY * c.first + m.scaleY * c.second + m.translateY).toDouble()
        }
        val l = xs.min()
        val b = ys.min()
        val w = xs.max() - l
        val h = ys.max() - b
        if (w <= 1e-6 || h <= 1e-6) return null
        val sx = rect.width / w
        val sy = rect.height / h
        return doubleArrayOf(sx, 0.0, 0.0, sy, rect.lowerLeftX - l * sx, rect.lowerLeftY - b * sy)
    }

    private fun n(v: Double): String = com.xnotes.core.pdf.PdfNumbers.format(v)

    private const val MAX_OUTLINE = 4000
    private const val MAX_DEPTH = 32
}
