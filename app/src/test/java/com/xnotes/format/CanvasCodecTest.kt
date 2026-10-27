package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.ConnectorEnd
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Routing
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.pal.FontFace
import com.xnotes.core.model.TextStyle
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.SyncMark
import com.xnotes.core.model.TapeItem
import com.xnotes.core.model.TapeMode
import com.xnotes.core.model.TapePattern
import com.xnotes.core.model.TapeShape
import com.xnotes.core.stroke.Sample
import com.xnotes.core.shapes.Axis
import com.xnotes.core.shapes.Elbow
import com.xnotes.core.shapes.FreePath
import com.xnotes.core.shapes.Head
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CanvasCodecTest {

    private val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun imageFile(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File.createTempFile("img", null).apply { writeBytes(bytes); deleteOnExit() }

    private fun bytesOf(doc: InfiniteDocument): ByteArray =
        ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()

    private fun roundTrip(doc: InfiniteDocument): InfiniteDocument {
        val imageDir = Files.createTempDirectory("xnotes-canvas-img").toFile()
        return codec.read(ByteArrayInputStream(bytesOf(doc)), imageDir = imageDir)
    }

    /** A bundle whose manifest is [manifest] verbatim, for the forgiving-load tests. */
    private fun bundleOf(manifest: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json").apply { method = ZipEntry.DEFLATED })
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun readManifest(manifest: String): InfiniteDocument =
        codec.read(ByteArrayInputStream(bundleOf(manifest)))

    private fun manifestText(doc: InfiniteDocument): String {
        ZipInputStream(ByteArrayInputStream(bytesOf(doc))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "manifest.json") return zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        throw AssertionError("no manifest")
    }

    // --- round trip ---

    @Test fun fullRoundTrip() {
        val doc = InfiniteDocument(dpi = 150)
        doc.add(
            Stroke(
                Tool.CALLIGRAPHY,
                ToolConfig(6.0, true, 0.40, 0.60, Rgba(0, 230, 118, 255)),
                mutableListOf(Sample(10.0, 20.0, 0.5), Sample(-30.0, 40.0, 0.9)),
            ),
        )
        doc.add(ImageItem(ImageData(imageFile(), 64, 48), Rect(5.0, 6.0, 64.0, 48.0)))
        doc.add(ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(50.0, 30.0), Rgba(255, 92, 92, 255), 3.0, Rgba(255, 92, 92, 64)))
        doc.background = CanvasBackground(PagePattern.DOTS, Rgba(10, 20, 30, 200), 42.0, Rgba(1, 2, 3, 255))
        doc.lastView = Waypoint("", 1234.5, -678.25, 3.5)
        doc.waypoints.add(Waypoint("origin", 0.0, 0.0, 1.0))
        doc.waypoints.add(Waypoint("far", 90000.0, -80000.0, 0.05))

        val back = roundTrip(doc)

        assertEquals(150, back.dpi)
        assertEquals(3, back.itemCount)

        val stroke = back.items[0] as Stroke
        assertEquals(Tool.CALLIGRAPHY, stroke.tool)
        assertEquals(0.60, stroke.config.directionStrength, 1e-9)
        assertEquals(2, stroke.samples.size)
        assertEquals(Sample(10.0, 20.0, 0.5), stroke.samples[0])
        assertEquals(-30.0, stroke.samples[1].x, 1e-9)

        val image = back.items[1] as ImageItem
        assertEquals(Rect(5.0, 6.0, 64.0, 48.0), image.rect)
        assertEquals(64, image.image.width)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), image.image.file.readBytes())

        val shape = back.items[2] as ShapeItem
        assertEquals(ShapeKind.RECTANGLE, shape.shape)
        assertNotNull(shape.fillRgba)

        assertEquals(PagePattern.DOTS, back.background.pattern)
        assertEquals(Rgba(10, 20, 30, 200), back.background.patternColor)
        assertEquals(42.0, back.background.spacing, 1e-9)
        assertEquals(Rgba(1, 2, 3, 255), back.background.paperColor)

        assertEquals(3.5, back.lastView!!.zoom, 1e-9)
        assertEquals(1234.5, back.lastView!!.cx, 1e-9)
        assertEquals(listOf("origin", "far"), back.waypoints.map { it.name })
        assertEquals(0.05, back.waypoints[1].zoom, 1e-12)
    }

    @Test fun createdTimeRoundTripsAndIsOmittedWhenUnknown() {
        val stamped = InfiniteDocument(created = 1_789_720_071_123L)
        assertTrue(manifestText(stamped).contains("2026-09-18T08:27:51.123Z"))
        assertEquals(1_789_720_071_123L, roundTrip(stamped).created)
        val unknown = InfiniteDocument()
        assertTrue(!manifestText(unknown).contains("\"created\""))
        assertNull(roundTrip(unknown).created)
    }

    @Test fun aMalformedCreatedTimeLoadsAsUnknown() {
        assertNull(readManifest("""{"format":"xcanvas","created":"yesterday","items":[]}""").created)
        assertNull(readManifest("""{"format":"xcanvas","created":12,"items":[]}""").created)
    }

    @Test fun aPeekReadsTheCreatedTimeWithoutLoadingTheCanvas() {
        val dir = Files.createTempDirectory("xcanvas-peek").toFile()
        try {
            val doc = InfiniteDocument(created = 1_789_720_071_123L)
            doc.add(ImageItem(ImageData(imageFile(ByteArray(200_000) { (it % 251).toByte() }), 64, 48), Rect(0.0, 0.0, 64.0, 48.0)))
            val file = File(dir, "c.xcanvas").apply { writeBytes(bytesOf(doc)) }
            assertEquals(1_789_720_071_123L, java.io.RandomAccessFile(file, "r").use { codec.peek(it.channel) }!!.created)
            assertEquals(1_789_720_071_123L, file.inputStream().use { codec.peek(it) }!!.created)
            val plain = File(dir, "p.xcanvas").apply { writeBytes(bytesOf(InfiniteDocument())) }
            assertNull(java.io.RandomAccessFile(plain, "r").use { codec.peek(it.channel) }!!.created)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aPeekStopsAtTheItems() {
        val cut = """{"format":"xcanvas","created":"2026-09-18T08:27:51.123Z","items":[{"kind":"""
        assertEquals(1_789_720_071_123L, codec.peekManifest(ByteArrayInputStream(cut.toByteArray()))!!.created)
    }

    @Test fun aPeekAtSomethingElseComesBackEmpty() {
        assertNull(codec.peek(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
        assertNull(codec.peekManifest(ByteArrayInputStream("""{"format":"xnote","pages":[]}""".toByteArray())))
    }

    @Test fun anEmptyCanvasRoundTrips() {
        val back = roundTrip(InfiniteDocument())
        assertTrue(back.isEmpty)
        assertNull(back.lastView)
        assertTrue(back.waypoints.isEmpty())
        assertEquals(PagePattern.GRID, back.background.pattern)
    }

    @Test fun lockedItemsSurviveARoundTrip() {
        val doc = InfiniteDocument()
        doc.add(
            Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(1.0, 2.0, 1.0), Sample(3.0, 4.0, 1.0)))
                .apply { locked = true },
        )
        doc.add(Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(5.0, 6.0, 1.0), Sample(7.0, 8.0, 1.0))))
        doc.add(ImageItem(ImageData(imageFile(), 20, 10), Rect(0.0, 0.0, 20.0, 10.0)).apply { locked = true })
        doc.add(ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(9.0, 9.0), Rgba(1, 2, 3, 255)).apply { locked = true })

        val back = roundTrip(doc).items
        assertEquals(4, back.size)
        assertTrue(back[0].locked)
        assertTrue(!back[1].locked)
        assertTrue(back[2].locked)
        assertTrue(back[3].locked)
    }

    @Test fun aShapesLabelRoundTripsOnTheCanvas() {
        val doc = InfiniteDocument()
        val box = ShapeItem(ShapeKind.DIAMOND, Pt(0.0, 0.0), Pt(90.0, 60.0), Rgba(4, 5, 6, 255), 2.0)
        box.label = ShapeLabel("Yes?", TextStyle(Rgba(4, 5, 6, 200), 22.0, FontFace.HAND), FakeTextMeasurer())
        doc.add(box)
        doc.add(ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(9.0, 9.0), Rgba(1, 2, 3, 255)))
        val back = roundTrip(doc).items.map { it as ShapeItem }
        assertEquals("Yes?", back[0].label!!.text)
        assertEquals(TextStyle(Rgba(4, 5, 6, 200), 22.0, FontFace.HAND), back[0].label!!.style)
        assertEquals(null, back[1].label)
        assertEquals(1, Regex("\"label\"").findAll(manifestText(doc)).count())
    }

    @Test fun drawnTextRoundTripsUnderAKindOlderReadersSkip() {
        val doc = InfiniteDocument()
        val box = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(200.0, 100.0), Rgba(4, 5, 6, 255), 2.0, rotation = 0.3)
        box.label = ShapeLabel("ab", TextStyle(Rgba(4, 5, 6, 255), 10.0, FontFace.SANS), FakeTextMeasurer())
        val drawn = box.labelAsGlyphs()!!
        doc.add(drawn)
        val manifest = manifestText(doc)
        assertTrue(manifest.contains("\"kind\":\"glyphs\""))
        assertFalse("no point list for an older reader", manifest.contains("\"points\""))
        val back = roundTrip(doc).items.single() as ShapeItem
        assertEquals(ShapeKind.GLYPHS, back.shape)
        assertEquals(drawn.rotation, back.rotation, 1e-12)
        val want = drawn.paths().flatMap { it.contours }.flatMap { FreePath.vertices(it) }
        val got = back.paths().flatMap { it.contours }.flatMap { FreePath.vertices(it) }
        assertEquals(want.size, got.size)
        for ((w, g) in want.zip(got)) assertEquals(0.0, w.distanceTo(g), 1e-3)
    }

    @Test fun aFillRoundTripsUnderAKindOlderReadersSkip() {
        val doc = InfiniteDocument()
        val outer = listOf(Pt(-5000.0, 10.0), Pt(-4800.0, 10.0), Pt(-4800.0, 110.0), Pt(-5000.0, 110.0))
        val hole = listOf(Pt(-4950.0, 40.0), Pt(-4900.0, 40.0), Pt(-4900.0, 80.0), Pt(-4950.0, 80.0))
        val fill = ShapeItem.fill(listOf(outer, hole), Rgba(30, 140, 220, 255))
        doc.add(fill)
        val manifest = manifestText(doc)
        assertTrue(manifest.contains("\"kind\":\"fill\""))
        assertFalse("no point list for an older reader", manifest.contains("\"points\""))
        val back = roundTrip(doc).items.single() as ShapeItem
        assertEquals(ShapeKind.FILL, back.shape)
        assertEquals(Rgba(30, 140, 220, 255), back.fillRgba)
        val want = fill.paths().flatMap { it.contours }.flatMap { FreePath.vertices(it) }
        val got = back.paths().flatMap { it.contours }.flatMap { FreePath.vertices(it) }
        assertEquals(want.size, got.size)
        for ((w, g) in want.zip(got)) assertEquals(0.0, w.distanceTo(g), 1e-3)
        assertFalse("the hole stays open", back.contains(Pt(-4925.0, 60.0)))
    }

    @Test fun loadedItemsAreIndexedAndOrdered() {
        val doc = InfiniteDocument()
        val far = ShapeItem(ShapeKind.RECTANGLE, Pt(9000.0, 9000.0), Pt(9100.0, 9100.0), Rgba(1, 1, 1, 255))
        val near = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(50.0, 50.0), Rgba(2, 2, 2, 255))
        doc.add(far)
        doc.add(near)

        val back = roundTrip(doc)
        assertEquals(2, back.index.size)
        assertEquals(1, back.visibleItems(Rect(-10.0, -10.0, 100.0, 100.0)).size)
        // z-order is list order: the far shape was added first and stays behind.
        val all = back.visibleItems(Rect(-1e5, -1e5, 2e5, 2e5))
        assertEquals(9000.0, (all[0] as ShapeItem).start.x, 1e-9)
    }

    @Test fun everyStrokeToolRoundTrips() {
        val doc = InfiniteDocument()
        val tools = listOf(Tool.PEN, Tool.DASHED, Tool.CALLIGRAPHY, Tool.SPEED, Tool.TAPER, Tool.HIGHLIGHTER)
        for (t in tools) {
            doc.add(
                Stroke(
                    t,
                    ToolDefaults.configFor(t),
                    mutableListOf(Sample(0.0, 0.0, 1.0, 0.0), Sample(10.0, 10.0, 0.5, 16.0)),
                    speedScale = 2.5,
                ),
            )
        }
        val back = roundTrip(doc)
        assertEquals(tools, back.items.map { (it as Stroke).tool })
        val speed = back.items[3] as Stroke
        assertEquals(2.5, speed.speedScale, 1e-9)
        assertEquals(16.0, speed.samples[1].t, 1e-9) // times only survive for the speed pen
        assertEquals(0.0, (back.items[0] as Stroke).samples[1].t, 1e-9)
        val taper = back.items[4] as Stroke
        assertTrue(taper.config.taperEnabled)
        assertEquals(ToolDefaults.DEFAULT_TAPER_TIP, taper.config.taperMinFactor, 1e-9)
        val hl = back.items[5] as Stroke
        assertEquals(0.90, hl.config.highlighterAlpha, 1e-9)
        val dashed = back.items[1] as Stroke
        assertEquals(10.0, dashed.config.dashLength, 1e-9)
        assertEquals(8.0, dashed.config.dashGap, 1e-9)
    }

    @Test fun neonAndStraightSurvive() {
        val doc = InfiniteDocument()
        doc.add(
            Stroke(
                Tool.PEN,
                ToolConfig(neon = true, neonStrength = 0.9),
                mutableListOf(Sample(0.0, 0.0, 1.0), Sample(20.0, 0.0, 1.0)),
                straight = true,
            ),
        )
        val back = roundTrip(doc).items[0] as Stroke
        assertTrue(back.config.neon)
        assertEquals(0.9, back.config.neonStrength, 1e-9)
        assertTrue(back.straight)
    }

    @Test fun everyShapeKindRoundTrips() {
        val doc = InfiniteDocument()
        for (k in ShapeKind.all) {
            if (k == ShapeKind.POLYGON || k == ShapeKind.POLYLINE) continue
            doc.add(ShapeItem(k, Pt(0.0, 0.0), Pt(30.0, 20.0), Rgba(9, 9, 9, 255), 2.0))
        }
        val back = roundTrip(doc)
        assertEquals(
            ShapeKind.all.filter { it != ShapeKind.POLYGON && it != ShapeKind.POLYLINE },
            back.items.map { (it as ShapeItem).shape },
        )
    }

    @Test fun polygonVerticesRoundTripInContentSpace() {
        val doc = InfiniteDocument()
        val verts = listOf(Pt(10.0, 10.0), Pt(90.0, 20.0), Pt(50.0, 80.0))
        doc.add(ShapeItem.poly(ShapeKind.POLYGON, verts, Rgba(3, 3, 3, 255), 2.0, null, false, 0.6, false, 10.0, 8.0))
        val back = roundTrip(doc).items[0] as ShapeItem
        val out = back.vertices()!!
        assertEquals(3, out.size)
        for (i in verts.indices) {
            assertEquals(verts[i].x, out[i].x, 1e-6)
            assertEquals(verts[i].y, out[i].y, 1e-6)
        }
    }

    @Test fun dashedShapesRoundTrip() {
        val doc = InfiniteDocument()
        doc.add(
            ShapeItem(
                ShapeKind.ELLIPSE, Pt(0.0, 0.0), Pt(40.0, 40.0), Rgba(1, 2, 3, 255), 4.0,
                dashed = true, dashLength = 7.0, dashGap = 3.0,
            ),
        )
        val back = roundTrip(doc).items[0] as ShapeItem
        assertTrue(back.dashed)
        assertEquals(7.0, back.dashLength, 1e-9)
        assertEquals(3.0, back.dashGap, 1e-9)
    }

    @Test fun severalImagesKeepTheirOwnBytesAndSlots() {
        val doc = InfiniteDocument()
        doc.add(ImageItem(ImageData(imageFile(byteArrayOf(1)), 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        doc.add(Stroke(Tool.PEN, ToolConfig(), mutableListOf(Sample(0.0, 0.0, 1.0))))
        doc.add(
            ImageItem(ImageData(imageFile(byteArrayOf(2, 2)), 9, 9), Rect(9.0, 9.0, 9.0, 9.0), orientation = 90, angle = 0.75),
        )

        val back = roundTrip(doc)
        assertEquals(3, back.itemCount)
        assertTrue(back.items[0] is ImageItem)
        assertTrue(back.items[1] is Stroke)
        val second = back.items[2] as ImageItem
        assertEquals(90, second.orientation)
        assertEquals(0.75, second.angle, 1e-9)
        assertEquals(0.0, (back.items[0] as ImageItem).angle, 1e-9)
        assertArrayEquals(byteArrayOf(1), (back.items[0] as ImageItem).image.file.readBytes())
        assertArrayEquals(byteArrayOf(2, 2), second.image.file.readBytes())
    }

    @Test fun aMirroredImageRoundTrips() {
        val doc = InfiniteDocument()
        doc.add(ImageItem(ImageData(imageFile(), 8, 8), Rect(0.0, 0.0, 8.0, 8.0), orientation = 90, flip = Flip.XY))
        doc.add(ImageItem(ImageData(imageFile(), 8, 8), Rect(9.0, 0.0, 8.0, 8.0)))
        assertEquals("only the mirrored one says so", 1, Regex("\"flip\"").findAll(manifestText(doc)).count())
        val back = roundTrip(doc).items.map { it as ImageItem }
        assertEquals(Flip.XY, back[0].flip)
        assertEquals(90, back[0].orientation)
        assertEquals(Flip.NONE, back[1].flip)
    }

    @Test fun animageWhoseAssetIsMissingIsDroppedWithoutShiftingTheRest() {
        val doc = InfiniteDocument()
        doc.add(ImageItem(ImageData(imageFile(), 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        doc.add(ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(1.0, 1.0), Rgba(1, 1, 1, 255)))
        // Reading with no imageDir skips every asset, exactly like a validation-only read.
        val back = codec.read(ByteArrayInputStream(bytesOf(doc)))
        assertEquals(1, back.itemCount)
        assertTrue(back.items[0] is ShapeItem)
    }

    // --- bundle shape ---

    @Test fun theBundleIsADeflatedManifestPlusStoredAssets() {
        val doc = InfiniteDocument()
        doc.add(ImageItem(ImageData(imageFile(ByteArray(4096) { 7 }), 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        val names = mutableListOf<String>()
        val methods = mutableMapOf<String, Int>()
        ZipInputStream(ByteArrayInputStream(bytesOf(doc))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names += e.name
                zis.readBytes()
                methods[e.name] = e.method
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        // The manifest goes last, always: an in-place save replaces the tail of the file, which
        // only works while nothing sits behind it. See ZipTail.
        assertEquals(listOf("assets/image-000.png", "manifest.json"), names)
        assertEquals(ZipEntry.DEFLATED, methods["manifest.json"])
        assertEquals(ZipEntry.STORED, methods["assets/image-000.png"])
    }

    @Test fun theManifestCarriesItsFormatTag() {
        val text = manifestText(InfiniteDocument())
        assertTrue(text.startsWith("{\"format\":\"xcanvas\",\"version\":1,\"writer\":"))
        assertTrue("no page array belongs in a canvas manifest", !text.contains("\"pages\""))
    }

    @Test fun optionalSectionsAreOmittedWhenUnset() {
        val text = manifestText(InfiniteDocument())
        assertTrue(!text.contains("\"view\""))
        assertTrue(!text.contains("\"waypoints\""))
        assertTrue(!text.contains("\"paper_color\""))
        assertTrue(text.contains("\"background\""))
    }

    @Test fun resavingAnUntouchedCanvasIsByteStable() {
        val doc = InfiniteDocument()
        doc.add(
            Stroke(
                Tool.PEN, ToolConfig(),
                mutableListOf(Sample(1.23456, 2.34567, 0.87654), Sample(9.87654, 8.76543, 0.12345)),
            ),
        )
        doc.lastView = Waypoint("", 3.0, 4.0, 1.5)
        val first = manifestText(doc)
        val reloaded = codec.read(ByteArrayInputStream(bytesOf(doc)))
        assertEquals(first, manifestText(reloaded))
    }

    // --- forgiving load ---

    @Test fun aBundleWithNoManifestIsRejected() {
        val empty = ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()
        assertThrows(XCanvasFormatException::class.java) { codec.read(ByteArrayInputStream(empty)) }
    }

    @Test fun anXnoteBundleIsRejected() {
        assertThrows(XCanvasFormatException::class.java) {
            readManifest("""{"format":"xnote","version":1,"pages":[]}""")
        }
    }

    @Test fun garbageIsRejected() {
        assertThrows(XCanvasFormatException::class.java) {
            codec.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))
        }
    }

    @Test fun unknownItemKindsAreSkipped() {
        val doc = readManifest(
            """{"format":"xcanvas","items":[
               {"kind":"hologram","fancy":true},
               {"kind":"stroke","tool":"pen","samples":[[1,2,1]]},
               {"kind":"text","text":"nope"}
            ]}""",
        )
        assertEquals(1, doc.itemCount)
        assertTrue(doc.items[0] is Stroke)
    }

    @Test fun unknownTopLevelKeysAreSkipped() {
        val doc = readManifest(
            """{"format":"xcanvas","layers":[1,2,3],"future":{"a":{"b":[null,true]}},"dpi":300,"items":[]}""",
        )
        assertEquals(300, doc.dpi)
    }

    @Test fun unknownConfigKeysAreSkipped() {
        val doc = readManifest(
            """{"format":"xcanvas","items":[{"kind":"stroke","tool":"pen",
               "config":{"base_width":9,"sparkle":42},"samples":[[0,0,1]]}]}""",
        )
        assertEquals(9.0, (doc.items[0] as Stroke).config.baseWidth, 1e-9)
    }

    @Test fun missingFieldsTakeModelDefaults() {
        val doc = readManifest("""{"format":"xcanvas","items":[{"kind":"stroke"}]}""")
        val s = doc.items[0] as Stroke
        assertEquals(Tool.PEN, s.tool)
        assertEquals(ToolConfig().baseWidth, s.config.baseWidth, 1e-9)
        assertTrue(s.samples.isEmpty())
        assertEquals(150, doc.dpi)
        assertEquals(PagePattern.GRID, doc.background.pattern)
    }

    @Test fun anUnknownBackgroundPatternFallsBackToTheDefault() {
        val doc = readManifest("""{"format":"xcanvas","background":{"pattern":"hexagons"},"items":[]}""")
        assertEquals(PagePattern.GRID, doc.background.pattern)
    }

    @Test fun malformedWaypointsAreDroppedNotFatal() {
        val doc = readManifest(
            """{"format":"xcanvas","waypoints":[
               {"name":"good","cx":1,"cy":2,"zoom":3},
               "nonsense",
               {"name":"zeroZoom","cx":0,"cy":0,"zoom":0},
               {"name":"ok2","cx":-5,"cy":-6,"zoom":0.25}
            ],"items":[]}""",
        )
        assertEquals(listOf("good", "ok2"), doc.waypoints.map { it.name })
    }

    @Test fun aWrongTypedValueFallsBackRatherThanThrowing() {
        val doc = readManifest(
            """{"format":"xcanvas","dpi":"not a number","background":{"spacing":[1,2]},
               "items":[{"kind":"stroke","samples":"gone"}]}""",
        )
        assertEquals(150, doc.dpi)
        assertEquals(64.0, doc.background.spacing, 1e-9)
        assertTrue((doc.items[0] as Stroke).samples.isEmpty())
    }

    @Test fun numbersWrittenAsStringsStillParse() {
        val doc = readManifest(
            """{"format":"xcanvas","dpi":"300","items":[{"kind":"stroke",
               "config":{"base_width":"7.5","pressure_enabled":"false"},"samples":[["1","2","0.5"]]}]}""",
        )
        assertEquals(300, doc.dpi)
        val s = doc.items[0] as Stroke
        assertEquals(7.5, s.config.baseWidth, 1e-9)
        assertTrue(!s.config.pressureEnabled)
        assertEquals(1.0, s.samples[0].x, 1e-9)
    }

    @Test fun aSampleShortOfItsFieldsTakesDefaults() {
        val doc = readManifest("""{"format":"xcanvas","items":[{"kind":"stroke","samples":[[5],[6,7],[8,9,0.25,99]]}]}""")
        val s = doc.items[0] as Stroke
        assertEquals(Sample(5.0, 0.0, 1.0, 0.0), s.samples[0])
        assertEquals(Sample(6.0, 7.0, 1.0, 0.0), s.samples[1])
        assertEquals(Sample(8.0, 9.0, 0.25, 99.0), s.samples[2])
    }

    @Test fun anItemsArrayOfTheWrongShapeIsSkipped() {
        val doc = readManifest("""{"format":"xcanvas","items":{"not":"an array"}}""")
        assertTrue(doc.isEmpty)
    }

    @Test fun legacyTaperStrokesReloadTapered() {
        val doc = readManifest(
            """{"format":"xcanvas","items":[{"kind":"stroke","tool":"taper",
               "config":{"taper_enabled":true},"samples":[[0,0,1]]}]}""",
        )
        val s = doc.items[0] as Stroke
        assertTrue(s.config.taperEnabled)
        assertEquals(ToolDefaults.LEGACY_TAPER_TIP, s.config.taperMinFactor, 1e-9)
    }

    @Test fun legacyHighlightersReloadAtTheHistoricalAlpha() {
        val doc = readManifest(
            """{"format":"xcanvas","items":[{"kind":"stroke","tool":"highlighter","samples":[[0,0,1]]}]}""",
        )
        assertEquals(0.35, (doc.items[0] as Stroke).config.highlighterAlpha, 1e-9)
    }

    @Test fun farFlungCoordinatesSurviveTheRounding() {
        val doc = InfiniteDocument()
        doc.add(
            Stroke(
                Tool.PEN, ToolConfig(),
                mutableListOf(Sample(1_234_567.89, -987_654.32, 1.0)),
            ),
        )
        val s = roundTrip(doc).items[0] as Stroke
        assertEquals(1_234_567.89, s.samples[0].x, 1e-6)
        assertEquals(-987_654.32, s.samples[0].y, 1e-6)
    }

    @Test fun waypointNamesAreSanitizedOnTheWayIn() {
        val long = "x".repeat(Waypoint.MAX_NAME_LENGTH + 20)
        val doc = readManifest("""{"format":"xcanvas","waypoints":[{"name":"  $long  ","cx":0,"cy":0,"zoom":1}],"items":[]}""")
        assertEquals(Waypoint.MAX_NAME_LENGTH, doc.waypoints[0].name.length)
    }

    @Test fun writeCancelledAbortsTheAssetCopy() {
        val doc = InfiniteDocument()
        doc.add(ImageItem(ImageData(imageFile(ByteArray(256 * 1024)), 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        assertThrows(CanvasCodec.WriteCancelled::class.java) {
            codec.write(doc, ByteArrayOutputStream()) { true }
        }
    }

    // --- turned shapes ---

    private val ink = Rgba(1, 2, 3, 255)

    private fun turned(kind: ShapeKind, angle: Double = 0.5) =
        ShapeItem(kind, Pt(10.0, 20.0), Pt(110.0, 120.0), ink, 2.0).apply {
            applyTransform(Affine.rotateAbout(box.center, angle))
        }

    private fun outline(s: ShapeItem): List<Pt> =
        s.paths().first().contours.first().let { c -> listOf(c.start) + c.segments.map { it.end } }

    private fun assertSameCorners(want: List<Pt>, got: List<Pt>) {
        assertEquals(want.size, got.size)
        for (w in want) assertTrue("missing $w", got.any { it.distanceTo(w) < 1e-6 })
    }

    private fun withoutNewFields(manifest: String) =
        manifest.replace(Regex(",\"geom\":\"[a-z0-9_]+\""), "")
            .replace(Regex(",\"rect\":\\[[^\\]]*\\],\"angle\":[-0-9.eE]+"), "")

    @Test fun aFlippedShapeRoundTripsAndOlderReadersKeepItsOutline() {
        val doc = InfiniteDocument()
        val s = ShapeItem(ShapeKind.RIGHT_TRIANGLE, Pt(10.0, 20.0), Pt(110.0, 120.0), ink, 2.0).also { it.flip = Flip.X }
        s.applyTransform(Affine.rotateAbout(s.box.center, 0.5))
        doc.add(s)
        doc.add(ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(10.0, 10.0), ink, 2.0))
        val text = manifestText(doc)
        assertTrue(text.contains("\"flip_geom\":\"right_triangle\""))
        assertTrue(text.contains("\"flip\":\"x\""))
        assertFalse("never under the key older readers rebuild unflipped", text.contains("\"geom\""))
        val back = roundTrip(doc).items[0] as ShapeItem
        assertEquals(ShapeKind.RIGHT_TRIANGLE, back.shape)
        assertEquals(Flip.X, back.flip)
        assertEquals(0.5, back.rotation, 1e-12)
        assertEquals(Flip.NONE, (roundTrip(doc).items[1] as ShapeItem).flip)
        // A reader from before flips skips both keys and keeps the stand-in, which faces the right way.
        val old = readManifest(text.replace(Regex(",\"flip_geom\":\"[a-z0-9_]+\""), "").replace(Regex(",\"flip\":\"[a-z]+\""), "")).items[0] as ShapeItem
        assertEquals(ShapeKind.POLYGON, old.shape)
        assertSameCorners(s.vertexDots().map { it.at }, old.vertices()!!)
    }

    @Test fun aTurnedShapeRoundTripsAsItself() {
        for (kind in listOf(ShapeKind.RECTANGLE, ShapeKind.ELLIPSE, ShapeKind.CIRCLE, ShapeKind.TRIANGLE)) {
            val doc = InfiniteDocument()
            val s = turned(kind)
            doc.add(s)
            val back = roundTrip(doc).items[0] as ShapeItem
            assertEquals(kind, back.shape)
            assertEquals(s.rotation, back.rotation, 1e-12)
            assertEquals(s.box.x, back.box.x, 1e-9)
            assertEquals(s.box.w, back.box.w, 1e-9)
            assertNull(back.path)
        }
    }

    @Test fun anOlderReaderDrawsATurnedShapesStandIn() {
        val doc = InfiniteDocument()
        val s = turned(ShapeKind.RECTANGLE)
        doc.add(s)
        val text = manifestText(doc)
        assertTrue(text.contains("\"shape\":\"polygon\""))
        assertTrue(text.contains("\"geom\":\"rectangle\""))
        val legacy = withoutNewFields(text)
        assertFalse(legacy.contains("\"angle\""))
        assertSameCorners(outline(s), outline(readManifest(legacy).items[0] as ShapeItem))
    }

    @Test fun aTurnedEllipsesStandInLiesOnTheEllipse() {
        val doc = InfiniteDocument()
        val s = turned(ShapeKind.ELLIPSE, 0.3).also { it.applyTransform(Affine.scaleAlongAxes(it.box.center, 0.3, 1.0, 0.5)) }
        doc.add(s)
        val back = readManifest(withoutNewFields(manifestText(doc))).items[0] as ShapeItem
        assertEquals(ShapeKind.POLYGON, back.shape)
        val rx = s.box.w / 2.0
        val ry = s.box.h / 2.0
        for (v in back.vertices()!!) {
            val q = s.toLocal(v)
            val e = ((q.x - s.box.centerX) / rx).let { it * it } + ((q.y - s.box.centerY) / ry).let { it * it }
            assertEquals(1.0, e, 1e-9)
        }
    }

    @Test fun anUprightShapeWritesNothingNew() {
        val doc = InfiniteDocument()
        doc.add(ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(50.0, 30.0), ink, 2.0))
        doc.add(ShapeItem.poly(ShapeKind.POLYGON, listOf(Pt(0.0, 0.0), Pt(9.0, 1.0), Pt(4.0, 7.0)), ink))
        val text = manifestText(doc)
        assertFalse(text, text.contains("\"geom\""))
        assertFalse(text, text.contains("\"angle\""))
        assertFalse(text, text.contains("\"rect\""))
    }

    @Test fun aTurnedPolygonKeepsItsFrame() {
        val verts = listOf(Pt(0.0, 0.0), Pt(80.0, 10.0), Pt(60.0, 70.0), Pt(-10.0, 40.0))
        val s = ShapeItem.poly(ShapeKind.POLYGON, verts, ink).apply {
            applyTransform(Affine.rotateAbout(Pt(20.0, 20.0), 0.7))
        }
        val doc = InfiniteDocument()
        doc.add(s)
        assertFalse(manifestText(doc).contains("geom"))
        val back = roundTrip(doc).items[0] as ShapeItem
        assertEquals(ShapeKind.POLYGON, back.shape)
        assertEquals(0.7, back.rotation, 1e-12)
        val want = s.vertices()!!
        val got = back.vertices()!!
        for (i in want.indices) assertEquals(0.0, want[i].distanceTo(got[i]), 1e-9)
    }

    @Test fun anOldBakedRectangleComesBackAsARectangle() {
        // What earlier versions saved for a turned rectangle: a polygon of its four corners.
        val turn = Affine.rotateAbout(Pt(50.0, 25.0), 0.6)
        val corners = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 50.0), Pt(0.0, 50.0)).map(turn::apply)
        val doc = InfiniteDocument()
        doc.add(ShapeItem.poly(ShapeKind.POLYGON, corners, ink))
        val back = roundTrip(doc).items[0] as ShapeItem
        assertEquals(ShapeKind.RECTANGLE, back.shape)
        assertEquals(0.6, back.rotation, 1e-9)
        assertEquals(100.0, back.box.w, 1e-9)
        assertEquals(50.0, back.box.h, 1e-9)
        assertSameCorners(corners, outline(back))
    }

    @Test fun anOldBakedEllipseComesBackAsAnEllipse() {
        val turn = Affine.rotateAbout(Pt(50.0, 25.0), 0.6)
        val ellipse = ShapeItem(ShapeKind.ELLIPSE, Pt(0.0, 0.0), Pt(100.0, 50.0), ink, 2.0)
        val circle = ShapeItem(ShapeKind.CIRCLE, Pt(0.0, 0.0), Pt(50.0, 50.0), ink, 2.0)
        val doc = InfiniteDocument()
        doc.add(ShapeItem.poly(ShapeKind.POLYGON, ellipse.ellipsePolygon().map(turn::apply), ink))
        doc.add(ShapeItem.poly(ShapeKind.POLYGON, circle.ellipsePolygon().map(turn::apply), ink))
        val back = roundTrip(doc).items.map { it as ShapeItem }
        assertEquals(ShapeKind.ELLIPSE, back[0].shape)
        assertEquals(0.6, back[0].rotation, 1e-9)
        assertEquals(100.0, back[0].box.w, 1e-6)
        assertEquals(50.0, back[0].box.h, 1e-6)
        assertEquals(ShapeKind.CIRCLE, back[1].shape)
    }

    @Test fun aQuadThatIsNotQuiteARectangleStaysAPolygon() {
        val doc = InfiniteDocument()
        doc.add(ShapeItem.poly(ShapeKind.POLYGON, listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 50.0), Pt(0.0, 50.5)), ink))
        assertEquals(ShapeKind.POLYGON, (roundTrip(doc).items[0] as ShapeItem).shape)
    }

    @Test fun anUnknownGeomFallsBackToTheStandIn() {
        val doc = InfiniteDocument()
        val s = turned(ShapeKind.TRIANGLE)
        doc.add(s)
        val text = manifestText(doc).replace("\"geom\":\"triangle\"", "\"geom\":\"hexagram\"")
        val back = readManifest(text).items[0] as ShapeItem
        assertEquals(ShapeKind.POLYGON, back.shape)
        assertSameCorners(outline(s), outline(back))
    }

    // --- free outlines ---

    @Test fun aCurveRoundTripsAsItsCubic() {
        val controls = listOf(Pt(0.0, 0.0), Pt(30.0, 120.0), Pt(140.0, 110.0), Pt(160.0, 10.0))
        val curve = ShapeItem.free(
            ShapeKind.CURVE,
            listOf(com.xnotes.core.vector.VectorContour(controls[0], listOf(com.xnotes.core.vector.VectorSeg.Cubic(controls[1], controls[2], controls[3])), false)),
            ink, 2.0,
        )
        val doc = InfiniteDocument()
        doc.add(curve)
        val text = manifestText(doc)
        assertTrue(text.contains("\"shape\":\"curve\""))
        assertTrue(text.contains("\"path\":\"M"))
        val back = roundTrip(doc).items[0] as ShapeItem
        assertEquals(ShapeKind.CURVE, back.shape)
        val seg = back.paths().single().contours.single().segments.single() as com.xnotes.core.vector.VectorSeg.Cubic
        assertEquals(0.0, seg.c1.distanceTo(controls[1]), 1e-3)
        assertEquals(0.0, seg.end.distanceTo(controls[3]), 1e-3)
        // An older reader sees a polyline through the curve.
        val legacy = readManifest(text.replace(Regex(",\"rect\":\\[[^\\]]*\\]"), "").replace(Regex(",\"path\":\"[^\"]*\""), ""))
        val pts = (legacy.items[0] as ShapeItem).vertices()!!
        assertTrue(pts.size > 8)
        assertEquals(0.0, pts.last().distanceTo(controls[3]), 1e-6)
    }

    @Test fun oldFreeShapesWriteBackByteForByte() {
        val item = "{\"kind\":\"shape\",\"shape\":\"curve\",\"start\":[0,0],\"end\":[30,20]," +
            "\"stroke_rgba\":[9,9,9,255],\"stroke_width\":2,\"fill_rgba\":null," +
            "\"points\":[[0,0],[10,15],[20,20],[30,5]]}," +
            "{\"kind\":\"shape\",\"shape\":\"polygon\",\"start\":[0,0],\"end\":[9,7]," +
            "\"stroke_rgba\":[1,2,3,255],\"stroke_width\":3,\"fill_rgba\":[1,2,3,64]," +
            "\"points\":[[0,0],[9,1],[4,7]]}"
        val manifest = "{\"format\":\"xcanvas\",\"version\":1,\"dpi\":150,\"items\":[$item]}"
        val again = manifestText(readManifest(manifest))
        assertTrue(again, again.contains("\"items\":[$item]"))
    }

    @Test fun aTrianglesKnobRoundTripsBehindAStandIn() {
        val t = ShapeItem(ShapeKind.TRIANGLE, Pt(0.0, 0.0), Pt(100.0, 60.0), ink, 2.0)
        t.dragKnob(0, Pt(20.0, 0.0))
        val doc = InfiniteDocument()
        doc.add(t)
        val text = manifestText(doc)
        assertTrue(text, text.contains("\"shape\":\"polygon\""))
        assertTrue(text, text.contains("\"adj\":[0.2]"))
        val back = roundTrip(doc).items[0] as ShapeItem
        assertEquals(ShapeKind.TRIANGLE, back.shape)
        assertEquals(0.2, back.adj[0], 1e-12)
        assertNull(back.path)
    }

    @Test fun headsRoundTripAndAPlainArrowWritesNone() {
        val doc = InfiniteDocument()
        doc.add(ShapeItem(ShapeKind.ARROW, Pt(0.0, 0.0), Pt(50.0, 0.0), ink, 2.0))
        doc.add(ShapeItem(ShapeKind.LINE, Pt(0.0, 10.0), Pt(50.0, 10.0), ink, 2.0, headStart = com.xnotes.core.shapes.Head.FILLED_DIAMOND, headEnd = com.xnotes.core.shapes.Head.HOLLOW_TRIANGLE))
        val text = manifestText(doc)
        assertEquals(1, Regex("\"head_start\"").findAll(text).count())
        val back = roundTrip(doc).items.map { it as ShapeItem }
        assertEquals(com.xnotes.core.shapes.Head.OPEN, back[0].headEnd)
        assertEquals(com.xnotes.core.shapes.Head.FILLED_DIAMOND, back[1].headStart)
        assertEquals(com.xnotes.core.shapes.Head.HOLLOW_TRIANGLE, back[1].headEnd)
    }

    @Test fun everyBasicKindRoundTripsBehindAStandIn() {
        // Drawn text and fills go under kinds of their own, which older readers skip (their own tests).
        val kinds = ShapeKind.all.filter { !it.isLegacy && it != ShapeKind.GLYPHS && it != ShapeKind.FILL }
        val doc = InfiniteDocument()
        for (k in kinds) {
            val s = ShapeItem(k, Pt(0.0, 0.0), Pt(120.0, 80.0), ink, 2.0, n = if (k.nRange != null) 7 else k.defaultN)
            k.knobs.firstOrNull()?.let { s.dragKnob(0, Pt(30.0, 10.0)) }
            doc.add(s)
        }
        val text = manifestText(doc)
        val back = roundTrip(doc).items.map { it as ShapeItem }
        for ((i, k) in kinds.withIndex()) {
            val want = doc.items[i] as ShapeItem
            assertEquals(k, back[i].shape)
            assertEquals("$k count", want.n, back[i].n)
            assertTrue("$k knobs", want.adj.contentEquals(back[i].adj))
        }
        // Older readers see each as the polygon or polyline it looks like, never a stray rectangle.
        val legacy = readManifest(withoutNewFields(text).replace(Regex(",\"adj\":\\[[^\\]]*\\]"), "").replace(Regex(",\"n\":[0-9]+"), ""))
        for (item in legacy.items.map { it as ShapeItem }) {
            val ok = item.shape == ShapeKind.POLYGON || item.shape == ShapeKind.POLYLINE || (item.shape == ShapeKind.RECTANGLE && item.vertices() == null)
            assertTrue("an older reader would see ${item.shape} with ${item.vertices()?.size} points", ok)
        }
    }

    // --- connectors ---

    private fun gluedPair(): Triple<ShapeItem, ShapeItem, ConnectorItem> {
        val a = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 60.0), ink, 2.0)
        val b = ShapeItem(ShapeKind.ELLIPSE, Pt(300.0, 200.0), Pt(400.0, 260.0), ink, 2.0)
        val c = ConnectorItem(Routing.ELBOW, ConnectorEnd(Pt(100.0, 30.0), a, 5), ConnectorEnd(Pt(300.0, 230.0), b, 2), ink, 2.0, headEnd = Head.FILLED)
        c.elbow = Elbow(Axis.H, doubleArrayOf(200.0))
        return Triple(a, b, c)
    }

    /** [manifest] without its connector objects, as a reader that predates them sees it. */
    private fun withoutConnectors(manifest: String): String {
        var s = manifest
        while (true) {
            val at = s.indexOf(",\"connector\":{")
            if (at < 0) return s
            var depth = 0
            var i = s.indexOf('{', at)
            while (true) {
                if (s[i] == '{') depth++
                if (s[i] == '}' && --depth == 0) break
                i++
            }
            s = s.removeRange(at, i + 1)
        }
    }

    @Test fun aConnectorRoundTripsGluedToWhatItJoins() {
        val (a, b, c) = gluedPair()
        c.label = ShapeLabel("yes", TextStyle(ink, 12.0, FontFace.SANS), FakeTextMeasurer())
        c.labelAt = 0.3
        c.dashed = true
        val doc = InfiniteDocument()
        doc.add(a)
        doc.add(c)
        doc.add(b)
        val back = roundTrip(doc).items
        val bc = back[1] as ConnectorItem
        assertEquals(Routing.ELBOW, bc.routing)
        assertSame(back[0], bc.start.target)
        assertEquals(5, bc.start.point)
        assertSame(back[2], bc.end.target)
        assertEquals(2, bc.end.point)
        assertEquals(c.elbow, bc.elbow)
        assertEquals(c.start.at, bc.start.at)
        assertEquals(c.end.at, bc.end.at)
        assertEquals(Head.FILLED, bc.headEnd)
        assertEquals(Head.NONE, bc.headStart)
        assertTrue(bc.dashed)
        assertEquals("yes", bc.label!!.text)
        assertEquals(0.3, bc.labelAt, 0.0)
    }

    @Test fun onlyWhatAConnectorGluesToGetsAnId() {
        val (a, b, c) = gluedPair()
        val doc = InfiniteDocument()
        doc.add(ShapeItem(ShapeKind.RECTANGLE, Pt(500.0, 0.0), Pt(600.0, 50.0), ink))
        doc.add(a)
        doc.add(b)
        doc.add(c)
        val text = manifestText(doc)
        assertEquals(2, Regex("\"id\":").findAll(text).count())
        assertTrue(text.contains("\"item\":1") && text.contains("\"item\":2"))
        assertArrayEquals(bytesOf(doc), bytesOf(doc))
    }

    @Test fun aReaderThatPredatesConnectorsDrawsTheLineTheyRunAlong() {
        val (a, b, c) = gluedPair()
        val doc = InfiniteDocument()
        doc.add(a)
        doc.add(b)
        doc.add(c)
        val old = readManifest(withoutConnectors(manifestText(doc))).items[2] as ShapeItem
        assertEquals(ShapeKind.POLYLINE, old.shape)
        assertEquals(c.route(), old.vertices())
        assertEquals(Head.FILLED, old.headEnd)
    }

    @Test fun anEndGluedToSomethingNoLongerThereIsWrittenLoose() {
        val (a, _, c) = gluedPair()
        val doc = InfiniteDocument()
        doc.add(a)
        doc.add(c)
        assertEquals(1, Regex("\"item\":").findAll(manifestText(doc)).count())
        val back = roundTrip(doc).items
        val bc = back[1] as ConnectorItem
        assertSame(back[0], bc.start.target)
        assertNull(bc.end.target)
        assertEquals(c.end.at, bc.end.at)
    }

    @Test fun aStraightConnectorKeepsEvenASingleWaypoint() {
        val c = ConnectorItem(Routing.STRAIGHT, ConnectorEnd(Pt(0.0, 0.0)), ConnectorEnd(Pt(100.0, 0.0)), ink)
        c.waypoints = listOf(Pt(50.0, 40.0))
        val doc = InfiniteDocument()
        doc.add(c)
        val back = roundTrip(doc).items.single() as ConnectorItem
        assertEquals(Routing.STRAIGHT, back.routing)
        assertEquals(listOf(Pt(50.0, 40.0)), back.waypoints)
    }

    @Test fun aRoutingFromANewerVersionDrawsAsItsStandIn() {
        val (_, _, c) = gluedPair()
        c.start = c.start.loose()
        c.end = c.end.loose()
        val doc = InfiniteDocument()
        doc.add(c)
        val text = manifestText(doc).replace("\"routing\":\"elbow\"", "\"routing\":\"zigzag\"")
        val back = readManifest(text).items.single() as ConnectorItem
        assertEquals(Routing.STRAIGHT, back.routing)
        assertEquals(c.route(), back.route())
    }

    @Test fun anIdThatNamesNothingLeavesTheEndLoose() {
        val (a, b, c) = gluedPair()
        val doc = InfiniteDocument()
        doc.add(a)
        doc.add(b)
        doc.add(c)
        val text = manifestText(doc).replace("\"item\":2", "\"item\":9")
        val bc = readManifest(text).items[2] as ConnectorItem
        assertNotNull(bc.start.target)
        assertNull(bc.end.target)
    }

    @Test fun aConnectorFindsAnImageItGluesToOnceTheImageLoads() {
        val image = ImageItem(ImageData(imageFile(), 4, 4), Rect(0.0, 0.0, 40.0, 40.0))
        val c = ConnectorItem(Routing.STRAIGHT, ConnectorEnd(Pt(40.0, 20.0), image, 5), ConnectorEnd(Pt(200.0, 20.0)), ink)
        val doc = InfiniteDocument()
        doc.add(c)
        doc.add(image)
        val back = roundTrip(doc).items
        assertTrue(back[1] is ImageItem)
        assertSame(back[1], (back[0] as ConnectorItem).start.target)
    }

    @Test fun whereALinesLabelSitsRoundTrips() {
        val line = ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(200.0, 0.0), ink, 2.0)
        line.label = ShapeLabel("mid", TextStyle(ink, 12.0, FontFace.SANS), FakeTextMeasurer())
        line.labelAt = 0.2
        val doc = InfiniteDocument()
        doc.add(line)
        val back = roundTrip(doc).items.single() as ShapeItem
        assertEquals("mid", back.label!!.text)
        assertEquals(0.2, back.labelAt, 0.0)
    }

    @Test fun aRecordingAndTheInkTiedToItRoundTripOnTheCanvas() {
        val doc = InfiniteDocument(dpi = 150)
        doc.add(Stroke(Tool.PEN, ToolConfig(), mutableListOf(Sample(1.0, 1.0, 1.0))).apply { sync = SyncMark(2, 61_000) })
        doc.add(
            AudioItem(
                AudioData(imageFile(byteArrayOf(5, 5, 5)), "ogg", 90_000, byteArrayOf(1, 2, 3)),
                Rect(-500.0, 9000.0, 120.0, 26.0), "Kickoff", 2, FakeTextMeasurer(),
            ),
        )
        doc.add(ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(5.0, 5.0), Rgba(1, 2, 3, 255)))

        val back = roundTrip(doc)
        assertEquals(3, back.itemCount)
        assertEquals(SyncMark(2, 61_000), back.items[0].sync)
        val audio = back.items[1] as AudioItem
        assertEquals("ogg", audio.audio.ext)
        assertEquals(90_000, audio.audio.durationMs)
        assertArrayEquals(byteArrayOf(5, 5, 5), audio.audio.file.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), audio.audio.peaks)
        assertEquals(Rect(-500.0, 9000.0, 120.0, 26.0), audio.rect)
        assertEquals("Kickoff", audio.name)
        assertEquals(2, audio.rid)
        assertNull(back.items[2].sync)
        assertTrue(manifestText(back).contains("\"rec\":2,\"rec_ms\":61000"))
    }

    @Test fun tapeRoundTripsOnACanvas() {
        val doc = InfiniteDocument()
        val box = TapeItem(TapeShape(TapeMode.RECTANGLE, listOf(Pt(-5000.0, 40.0), Pt(-4800.0, 40.0)), 80.0), Rgba(120, 200, 160, 255), TapePattern.GRID)
        box.peeled = true
        doc.add(box)
        val back = roundTrip(doc).items.single() as TapeItem
        assertEquals(box.shape, back.shape)
        assertEquals(TapePattern.GRID, back.pattern)
        assertEquals(Rgba(120, 200, 160, 255), back.rgba)
        assertFalse(back.peeled)
    }

    @Test fun tapeLoadsForgivingly() {
        val doc = readManifest(
            """{"format":"xcanvas","items":[""" +
                """{"kind":"tape","mode":"straight","points":[[0,0],[50,0],[90,9]],"rgba":[1,2,3,255]},""" +
                """{"kind":"tape","mode":"zigzag","points":[[0,0],[10,0]],"width":-4,"pattern":"plaid"},""" +
                """{"kind":"tape","mode":"freehand","points":[[5,5]]}]}""",
        )
        val (strip, odd) = doc.items.map { it as TapeItem }
        assertEquals("a strip keeps its axis's two ends", listOf(Pt(0.0, 0.0), Pt(50.0, 0.0)), strip.shape.points)
        assertEquals(TapeItem.DEFAULT_WIDTH, strip.shape.width, 0.0)
        assertEquals(TapeMode.STRAIGHT, odd.mode)
        assertEquals(TapeItem.DEFAULT_WIDTH, odd.shape.width, 0.0)
        assertEquals(TapePattern.SOLID, odd.pattern)
        assertEquals(2, doc.items.size)
    }
}
