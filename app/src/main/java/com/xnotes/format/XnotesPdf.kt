package com.xnotes.format

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The bytes that make a PDF an xnotes PDF: one any reader opens, carrying the whole `.xnote` as a
 * file attachment so the note reopens editable.
 *
 * An export writes the pages and the note in one revision, and its trailer names the attachment's
 * stream and the file's exact length under `/XNotes`. That length is the proof nothing else has
 * written the file since: an append or a rewrite by another app changes it or drops the key.
 *
 * A save between exports appends a **tail**, a PDF incremental update that replaces the stream
 * under the same object number with a newer note. The pages are then stale until the next export.
 * The note is the last large thing in a tail, so later saves splice it in place through [ZipTail]
 * over a [FileWindow] and rewrite only the few hundred bytes after it.
 *
 * A PDF becomes ours only through an export, and an export always ends in a cross-reference
 * table. A tail follows nothing but a file that is ours, so it is a table too: readers do not all
 * follow a chain that mixes tables and cross-reference streams.
 *
 * **A write that stops part way must never leave a file that reads as whole.** A tail is only
 * ever written past the end of what was whole before it, and its closing bytes go last, so until
 * they land the file has no readable end. [inspect] then finds where the unfinished tail began
 * and answers [Inspection.Torn]: the note underneath is intact, and the next save cuts the rest.
 */
internal object XnotesPdf {

    const val VERSION = 1

    /** The trailer key the marker sits under. */
    const val KEY = "XNotes"

    /** How many digits the marker's length is written in, so stamping it never moves a byte. */
    const val LENGTH_DIGITS = 13

    private const val TAIL_HEAD = "\n%xnotes-tail\n"
    private const val END_SCAN = 4096
    private const val CHUNK = 4096
    private const val MAX_CHUNK = 1 shl 20
    private const val MAX_SECTIONS = 64
    private const val ENTRY = 20
    private const val MAX_TABLE_STEPS = 100_000
    private const val SCAN_CHUNK = 1 shl 20
    private const val MAX_TORN_TRIES = 16
    private const val MAX_TABLE_OFFSET = 9_999_999_999L

    /** What a PDF's cross-reference chain says about it. */
    sealed class Inspection {
        /** Nothing of ours marks it: a plain PDF, or one another app has rewritten whole. */
        object Plain : Inspection()

        /** Its end does not read as a PDF's, and nothing of ours is found under it. Not a file to write into. */
        object Unreadable : Inspection()

        /** Ours and untouched. [stale] when it ends in a tail, so its pages are behind its note. */
        class Ours internal constructor(val stale: Boolean, internal val end: Section) : Inspection() {
            /** Where the tail it ends in starts, or null when it ends in an export. */
            val tailStart: Long? get() = end.mark?.tail
        }

        /** Ours for its first [ownLength] bytes, [own], with another app's bytes appended after. */
        class Appended internal constructor(val ownLength: Long, val own: Ours) : Inspection()

        /** Ours for its first [ownLength] bytes, [own], then a tail of ours that was never finished. */
        class Torn internal constructor(val ownLength: Long, val own: Ours) : Inspection()
    }

    /** A tail this session wrote, as it needs to be known to be rewritten. */
    class Tail internal constructor(
        /** Where the tail begins, which is where the file ended before it. */
        val start: Long,
        val zipStart: Long,
        val zipLength: Long,
        /** The file's length with the tail closed. */
        val end: Long,
        internal val objAt: Long,
        internal val frame: Frame,
    ) {
        /** The note inside [ch], the file this tail is the end of. */
        fun window(ch: FileChannel): FileWindow = FileWindow(ch, zipStart, zipLength)
    }

    internal enum class Kind { TABLE, STREAM }

    /** One cross-reference section: where it is, and what its trailer dictionary holds. */
    internal class Section(
        val kind: Kind,
        val at: Long,
        val size: Int,
        val root: ByteArray?,
        val info: ByteArray?,
        val id: ByteArray?,
        val encrypted: Boolean,
        val prev: Long?,
        val mark: Mark?,
    )

    /** The `/XNotes` entry of a trailer. */
    internal class Mark(val version: Int, val obj: Int, val length: Long, val lengthAt: Long, val tail: Long?)

    /** What a tail's closing bytes are built from, fixed for a session. */
    internal class Frame(
        val obj: Int,
        val lengthObj: Int,
        val root: ByteArray,
        val info: ByteArray?,
        val id: ByteArray?,
        val prev: Long,
    )

    private class Malformed : Exception()

    // --- reading ---

    /** What [ch] is. */
    fun inspect(ch: FileChannel): Inspection = try {
        inspect(ch, nested = false)
    } catch (_: Malformed) {
        Inspection.Unreadable
    }

    private fun inspect(ch: FileChannel, nested: Boolean): Inspection {
        val size = ch.size()
        var section = lastSection(ch) ?: return if (nested) Inspection.Unreadable else torn(ch)
        val seen = HashSet<Long>()
        var first = true
        while (seen.add(section.at) && seen.size <= MAX_SECTIONS) {
            val mark = section.mark
            if (mark != null && mark.version == VERSION) {
                if (first && mark.length == size) {
                    val whole = section.kind == Kind.TABLE && tailIsThere(ch, mark)
                    return if (whole) Inspection.Ours(mark.tail != null, section) else Inspection.Plain
                }
                // A marker that stops short of the end: ours up to there, if that much still reads as ours.
                if (!nested && mark.length in 1 until size) {
                    val own = inspect(FileWindow(ch, 0L, mark.length), nested = true)
                    if (own is Inspection.Ours) {
                        // What follows says whose it is: a tail of ours opens with its own line, however little of it landed.
                        return if (tailBegins(ch, mark.length)) Inspection.Torn(mark.length, own) else Inspection.Appended(mark.length, own)
                    }
                }
            }
            if (nested) break
            val prev = section.prev ?: break
            section = sectionAt(ch, prev) ?: break
            first = false
        }
        return Inspection.Plain
    }

    /**
     * What lies under an end that does not read: the latest place a tail of ours begins with a
     * whole file of ours before it. The search runs back through the file, which only a file
     * without a readable end pays for.
     */
    private fun torn(ch: FileChannel): Inspection {
        val head = TAIL_HEAD.toByteArray(Charsets.ISO_8859_1)
        var before = ch.size()
        repeat(MAX_TORN_TRIES) {
            val at = lastIndexBefore(ch, head, before) ?: return Inspection.Unreadable
            val own = inspect(FileWindow(ch, 0L, at), nested = true)
            if (own is Inspection.Ours) return Inspection.Torn(at, own)
            before = at + head.size - 1
        }
        return Inspection.Unreadable
    }

    /** Where [word] last starts in [ch] so that it ends before [before], or null. */
    private fun lastIndexBefore(ch: FileChannel, word: ByteArray, before: Long): Long? {
        var end = before
        while (end >= word.size) {
            val start = maxOf(0L, end - SCAN_CHUNK)
            val chunk = read(ch, start, (end - start).toInt())
            // Megabytes may go by here, so a byte is only looked at closely when it could start the word.
            var i = chunk.size - word.size
            while (i >= 0) {
                if (chunk[i] == word[0]) {
                    var k = 1
                    while (k < word.size && chunk[i + k] == word[k]) k++
                    if (k == word.size) return start + i
                }
                i--
            }
            if (start == 0L) return null
            end = start + word.size - 1 // a match may straddle two chunks
        }
        return null
    }

    /** Whether the bytes of [ch] from [at] on are the start of a tail, or as much of that start as there is. */
    private fun tailBegins(ch: FileChannel, at: Long): Boolean {
        val head = TAIL_HEAD.toByteArray(Charsets.ISO_8859_1)
        val there = read(ch, at, minOf(ch.size() - at, head.size.toLong()).toInt())
        return there.indices.all { there[it] == head[it] }
    }

    private fun tailIsThere(ch: FileChannel, mark: Mark): Boolean {
        val at = mark.tail ?: return true
        val head = TAIL_HEAD.toByteArray(Charsets.ISO_8859_1)
        return at >= 0L && at + head.size <= ch.size() && read(ch, at, head.size).contentEquals(head)
    }

    /** The note inside [ch], which [ours] describes, or null when its stream cannot be read as it lies. */
    fun embedded(ch: FileChannel, ours: Inspection.Ours): FileWindow? = try {
        val section = ours.end
        val at = offsetOf(ch, section, section.mark!!.obj) ?: throw Malformed()
        val (dict, lexer) = objectAt(ch, at, section.mark.obj)
        if (dict.entries.containsKey("Filter")) throw Malformed()
        lexer.skipSpace()
        if (lexer.word() != "stream") throw Malformed()
        val dataAt = at + lexer.afterEol()
        val length = when (val l = dict.entries["Length"]) {
            is Pdf.Num -> l.value
            is Pdf.Ref -> numberAt(ch, offsetOf(ch, section, l.num) ?: throw Malformed(), l.num)
            else -> throw Malformed()
        }
        if (length < 0L || dataAt + length > ch.size()) throw Malformed()
        FileWindow(ch, dataAt, length)
    } catch (_: Malformed) {
        null
    }

    // --- writing ---

    /**
     * Write [ch]'s length into the marker an export left open. False, having written nothing, when
     * the file does not end in one.
     */
    fun stamp(ch: FileChannel): Boolean {
        val mark = lastSection(ch)?.mark ?: return false
        write(ch, mark.lengthAt, digits(ch.size()))
        return true
    }

    /**
     * Append a tail holding the note [zip] to [ch], which has to be ours as it stands.
     *
     * Null, having written nothing, when it is not, or when the tail would reach past the offsets
     * a cross-reference table holds.
     */
    fun appendTail(ch: FileChannel, zip: FileChannel): Tail? {
        val last = lastSection(ch) ?: return null
        val mark = last.mark ?: return null
        if (last.kind != Kind.TABLE || last.encrypted || last.root == null) return null
        if (mark.version != VERSION || mark.length != ch.size() || !tailIsThere(ch, mark)) return null
        val frame = Frame(obj = mark.obj, lengthObj = last.size, root = last.root, info = last.info, id = last.id, prev = last.at)
        return writeTail(ch, ch.size(), frame, zip)
    }

    /**
     * Replace [tail], the tail this session wrote and nothing has written past, with one holding
     * [zip]. The old tail is cut off first, which leaves the file whole as it was before the tail.
     */
    fun rewriteTail(ch: FileChannel, tail: Tail, zip: FileChannel): Tail? {
        ch.truncate(tail.start)
        return writeTail(ch, tail.start, tail.frame, zip)
    }

    /**
     * Open [tail] for its note to be spliced in place: its closing bytes are cut off, so the file
     * has no readable end until [closeTail] writes them again, and returns the note.
     */
    fun openTail(ch: FileChannel, tail: Tail): FileWindow {
        ch.truncate(tail.zipStart + tail.zipLength)
        return tail.window(ch)
    }

    /** The last bytes of [ch], which with its length tell one save of a PDF from another. */
    fun ending(ch: FileChannel): ByteArray {
        val size = ch.size()
        val span = minOf(size, END_SCAN.toLong()).toInt()
        return read(ch, size - span, span)
    }

    /**
     * Close [tail] again after its note was spliced through [openTail] and now is [zipLength]
     * bytes: everything after the note is written anew and the file ends there.
     */
    fun closeTail(ch: FileChannel, tail: Tail, zipLength: Long): Tail? {
        val f = tail.frame
        val zipEnd = tail.zipStart + zipLength
        val out = ByteArrayOutputStream(512)
        out.put("\nendstream\nendobj\n")
        val lengthAt = zipEnd + out.size()
        out.put("${f.lengthObj} 0 obj\n$zipLength\nendobj\n")
        val xrefAt = zipEnd + out.size()
        if (xrefAt > MAX_TABLE_OFFSET) return null
        out.put("xref\n")
        if (f.obj + 1 == f.lengthObj) {
            out.put("${f.obj} 2\n${entry(tail.objAt)}${entry(lengthAt)}")
        } else {
            out.put("${f.obj} 1\n${entry(tail.objAt)}${f.lengthObj} 1\n${entry(lengthAt)}")
        }
        out.put("trailer\n<</Size ${f.lengthObj + 1}")
        val lenIndex = out.putTrailer(f, tail.start)
        out.put(">>\n")
        out.put("startxref\n$xrefAt\n%%EOF\n")
        val bytes = out.toByteArray()
        val end = zipEnd + bytes.size
        digits(end).copyInto(bytes, lenIndex)
        write(ch, zipEnd, bytes)
        ch.truncate(end)
        return Tail(tail.start, tail.zipStart, zipLength, end, tail.objAt, f)
    }

    private fun writeTail(ch: FileChannel, at: Long, f: Frame, zip: FileChannel): Tail? {
        val zipLength = zip.size()
        val head = (TAIL_HEAD + "${f.obj} 0 obj\n<</Type/EmbeddedFile/Length ${f.lengthObj} 0 R/Params<</Size ${f.lengthObj} 0 R>>>>\nstream\n")
            .toByteArray(Charsets.ISO_8859_1)
        val objAt = at + TAIL_HEAD.length
        val zipStart = at + head.size
        if (zipStart + zipLength + CHUNK > MAX_TABLE_OFFSET) return null
        write(ch, at, head)
        var done = 0L
        while (done < zipLength) {
            val n = ch.transferFrom(zip.position(done), zipStart + done, zipLength - done)
            if (n <= 0L) throw java.io.IOException("short write")
            done += n
        }
        return closeTail(ch, Tail(at, zipStart, zipLength, -1L, objAt, f), zipLength)
    }

    /** The trailer entries a tail carries on, and its marker; returns where the marker's length digits start. */
    private fun ByteArrayOutputStream.putTrailer(f: Frame, tailAt: Long): Int {
        put("/Root ")
        write(f.root)
        f.info?.let { put("/Info "); write(it) }
        f.id?.let { put("/ID "); write(it) }
        put("/Prev ${f.prev}/$KEY<</V $VERSION/Obj ${f.obj} 0 R/Tail $tailAt/Len (")
        val at = size()
        put("0".repeat(LENGTH_DIGITS) + ")>>")
        return at
    }

    private fun ByteArrayOutputStream.put(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

    private fun entry(at: Long): String = "%010d 00000 n \n".format(java.util.Locale.ROOT, at)

    private fun digits(n: Long): ByteArray =
        "%0${LENGTH_DIGITS}d".format(java.util.Locale.ROOT, n).toByteArray(Charsets.ISO_8859_1)

    // --- cross-reference sections ---

    /** The section the file's last `startxref` points at. */
    private fun lastSection(ch: FileChannel): Section? {
        val size = ch.size()
        val span = minOf(size, END_SCAN.toLong()).toInt()
        val end = read(ch, size - span, span)
        val at = lastIndexOf(end, "startxref") ?: return null
        val xref = try {
            Lexer(end, at + 9).whole()
        } catch (_: Malformed) {
            return null
        }
        return sectionAt(ch, xref)
    }

    /** The section at [xref], or null when there is none this can read there. */
    private fun sectionAt(ch: FileChannel, xref: Long): Section? {
        if (xref <= 0L || xref >= ch.size()) return null
        return try {
            val head = Lexer(read(ch, xref, minOf(ch.size() - xref, 32L).toInt()), 0)
            head.skipSpace()
            if (head.peekWord() == "xref") tableAt(ch, xref, xref + head.p + 4) else streamAt(ch, xref)
        } catch (_: Malformed) {
            null
        }
    }

    /** The table section at [at], read [from] just past its `xref`: the subsections are stepped over to its trailer. */
    private fun tableAt(ch: FileChannel, at: Long, from: Long): Section {
        var pos = from
        repeat(MAX_TABLE_STEPS) {
            val line = Lexer(read(ch, pos, minOf(ch.size() - pos, 64L).toInt()), 0)
            line.skipSpace()
            if (line.peekWord() == "trailer") {
                val (dict, base, lexer) = dictAt(ch, pos + line.p + 7)
                return section(Kind.TABLE, at, dict, base, lexer)
            }
            line.whole()
            val count = line.whole()
            pos += line.afterEol() + ENTRY * count
        }
        throw Malformed()
    }

    private fun streamAt(ch: FileChannel, at: Long): Section {
        val (dict, base, lexer) = dictAt(ch, at, header = true)
        if ((dict.entries["Type"] as? Pdf.Name)?.value != "XRef") throw Malformed()
        return section(Kind.STREAM, at, dict, base, lexer)
    }

    private fun section(kind: Kind, at: Long, dict: Pdf.Dict, base: Long, lexer: Lexer): Section {
        val e = dict.entries
        fun raw(key: String): ByteArray? = e[key]?.let { lexer.b.copyOfRange(it.start, it.end) }
        val size = (e["Size"] as? Pdf.Num)?.value ?: throw Malformed()
        if (size <= 0L || size > Int.MAX_VALUE - 4) throw Malformed()
        val mark = (e[KEY] as? Pdf.Dict)?.let { m ->
            val version = (m.entries["V"] as? Pdf.Num)?.value?.toInt() ?: return@let null
            val obj = when (val o = m.entries["Obj"]) {
                is Pdf.Ref -> if (o.gen == 0) o.num else return@let null
                is Pdf.Num -> o.value.toInt()
                else -> return@let null
            }
            val len = m.entries["Len"] as? Pdf.Str ?: return@let null
            // Written plain, digit for digit, or it is not a length this can stamp in place.
            if (len.end - len.start != LENGTH_DIGITS + 2 || lexer.b[len.start] != '('.code.toByte()) return@let null
            if (!len.bytes.all { it in '0'.code.toByte()..'9'.code.toByte() }) return@let null
            Mark(
                version = version,
                obj = obj,
                length = String(len.bytes, Charsets.ISO_8859_1).toLong(),
                lengthAt = base + len.start + 1,
                tail = (m.entries["Tail"] as? Pdf.Num)?.value,
            )
        }
        return Section(
            kind = kind,
            at = at,
            size = size.toInt(),
            root = raw("Root"),
            info = raw("Info"),
            id = raw("ID"),
            encrypted = e.containsKey("Encrypt"),
            prev = (e["Prev"] as? Pdf.Num)?.value,
            mark = mark,
        )
    }

    /** Where [section] says object [num] starts, or null when it does not list it. Only a table is looked into, which ours are. */
    private fun offsetOf(ch: FileChannel, section: Section, num: Int): Long? =
        if (section.kind == Kind.TABLE) offsetInTable(ch, section.at, num) else null

    private fun offsetInTable(ch: FileChannel, xref: Long, num: Int): Long? {
        val head = Lexer(read(ch, xref, minOf(ch.size() - xref, 32L).toInt()), 0)
        head.skipSpace()
        var pos = xref + head.p + 4
        repeat(MAX_TABLE_STEPS) {
            val line = Lexer(read(ch, pos, minOf(ch.size() - pos, 64L).toInt()), 0)
            line.skipSpace()
            if (line.peekWord() == "trailer") return null
            val first = line.whole()
            val count = line.whole()
            val entries = pos + line.afterEol()
            if (num >= first && num < first + count) {
                val row = read(ch, entries + ENTRY * (num - first), ENTRY)
                if (row[17] != 'n'.code.toByte()) return null
                return String(row, 0, 10, Charsets.ISO_8859_1).toLongOrNull()
            }
            pos = entries + ENTRY * count
        }
        return null
    }

    // --- objects ---

    private data class DictAt(val dict: Pdf.Dict, val base: Long, val lexer: Lexer)

    /** The dictionary at [at], after an object header when [header]; read in a chunk that grows until it holds it. */
    private fun dictAt(ch: FileChannel, at: Long, header: Boolean = false, num: Int = -1): DictAt {
        var span = CHUNK
        while (true) {
            val len = minOf(ch.size() - at, span.toLong()).toInt()
            val lexer = Lexer(read(ch, at, len), 0)
            try {
                if (header) {
                    val n = lexer.whole()
                    lexer.whole()
                    if (lexer.word() != "obj" || (num >= 0 && n != num.toLong())) throw Malformed()
                }
                val dict = lexer.value() as? Pdf.Dict ?: throw Malformed()
                return DictAt(dict, at, lexer)
            } catch (e: Malformed) {
                // Ran off the chunk rather than into something wrong: look at more of the file.
                if (!lexer.starved || len < span || span >= MAX_CHUNK) throw e
                span *= 8
            }
        }
    }

    /** The stream dictionary of object [num] at [at], with the lexer left just past it. */
    private fun objectAt(ch: FileChannel, at: Long, num: Int): Pair<Pdf.Dict, Lexer> {
        val (dict, _, lexer) = dictAt(ch, at, header = true, num = num)
        return dict to lexer
    }

    /** The whole number that object [num] at [at] is. */
    private fun numberAt(ch: FileChannel, at: Long, num: Int): Long {
        val lexer = Lexer(read(ch, at, minOf(ch.size() - at, 64L).toInt()), 0)
        if (lexer.whole() != num.toLong()) throw Malformed()
        lexer.whole()
        if (lexer.word() != "obj") throw Malformed()
        return lexer.whole()
    }

    // --- bytes ---

    private fun read(ch: FileChannel, at: Long, len: Int): ByteArray {
        if (at < 0L || len < 0 || at + len > ch.size()) throw Malformed()
        val buf = ByteBuffer.allocate(len)
        while (buf.hasRemaining()) {
            if (ch.read(buf, at + buf.position()) < 0) throw Malformed()
        }
        return buf.array()
    }

    private fun write(ch: FileChannel, at: Long, bytes: ByteArray): Long {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) {
            if (ch.write(buf, at + buf.position()) <= 0) throw java.io.IOException("short write")
        }
        return bytes.size.toLong()
    }

    private fun lastIndexOf(b: ByteArray, word: String): Int? {
        val w = word.toByteArray(Charsets.ISO_8859_1)
        for (i in b.size - w.size downTo 0) {
            if (w.indices.all { b[i + it] == w[it] }) return i
        }
        return null
    }

    // --- PDF syntax, as much of it as a trailer uses ---

    private sealed class Pdf(val start: Int, val end: Int) {
        class Num(start: Int, end: Int, val value: Long) : Pdf(start, end)
        class Real(start: Int, end: Int) : Pdf(start, end)
        class Ref(start: Int, end: Int, val num: Int, val gen: Int) : Pdf(start, end)
        class Name(start: Int, end: Int, val value: String) : Pdf(start, end)
        class Str(start: Int, end: Int, val bytes: ByteArray) : Pdf(start, end)
        class Arr(start: Int, end: Int, val items: List<Pdf>) : Pdf(start, end)
        class Dict(start: Int, end: Int, val entries: Map<String, Pdf>) : Pdf(start, end)
        class Word(start: Int, end: Int, val value: String) : Pdf(start, end)
    }

    private class Lexer(val b: ByteArray, var p: Int) {

        /** Set when reading stopped at the end of the bytes, not at a mistake in them. */
        var starved = false

        private fun fail(): Nothing = throw Malformed()

        private fun starve(): Nothing {
            starved = true
            throw Malformed()
        }

        private fun isSpace(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

        private fun isDelimiter(c: Int) =
            c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code ||
                c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

        private fun at(i: Int): Int = if (i < b.size) b[i].toInt() and 0xFF else -1

        fun skipSpace() {
            while (p < b.size) {
                val c = at(p)
                if (isSpace(c)) {
                    p++
                } else if (c == '%'.code) {
                    while (p < b.size && at(p) != 10 && at(p) != 13) p++
                } else {
                    return
                }
            }
        }

        /** The run of regular characters ahead, without moving past it. */
        fun peekWord(): String {
            var q = p
            while (q < b.size && !isSpace(at(q)) && !isDelimiter(at(q))) q++
            return String(b, p, q - p, Charsets.ISO_8859_1)
        }

        fun word(): String {
            skipSpace()
            val w = peekWord()
            if (w.isEmpty()) if (p >= b.size) starve() else fail()
            if (p + w.length >= b.size) starve() // it may go on past the chunk
            p += w.length
            return w
        }

        /** A whole number. */
        fun whole(): Long {
            skipSpace()
            val w = peekWord()
            if (p + w.length >= b.size) starve()
            val n = w.toLongOrNull() ?: fail()
            if (n < 0L) fail()
            p += w.length
            return n
        }

        /** Past the end of line the lexer stands before; returns the new position. */
        fun afterEol(): Int {
            while (at(p) == 32) p++
            when (at(p)) {
                13 -> p += if (at(p + 1) == 10) 2 else 1
                10 -> p++
                -1 -> starve()
                else -> fail()
            }
            return p
        }

        fun value(): Pdf {
            skipSpace()
            val start = p
            val c = at(p)
            return when {
                c < 0 -> starve()
                c == '/'.code -> name(start)
                c == '('.code -> literal(start)
                c == '<'.code && at(p + 1) == '<'.code -> dict(start)
                c == '<'.code -> hex(start)
                c == '['.code -> array(start)
                c == '+'.code || c == '-'.code || c == '.'.code || (c >= '0'.code && c <= '9'.code) -> number(start)
                else -> {
                    val w = word()
                    Pdf.Word(start, p, w)
                }
            }
        }

        private fun name(start: Int): Pdf.Name {
            p++
            val out = StringBuilder()
            while (true) {
                val c = at(p)
                if (c < 0) starve()
                if (isSpace(c) || isDelimiter(c)) break
                if (c == '#'.code) {
                    if (p + 2 >= b.size) starve()
                    out.append(((hexDigit(at(p + 1)) shl 4) or hexDigit(at(p + 2))).toChar())
                    p += 3
                } else {
                    out.append(c.toChar())
                    p++
                }
            }
            return Pdf.Name(start, p, out.toString())
        }

        private fun hexDigit(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> fail()
        }

        private fun literal(start: Int): Pdf.Str {
            p++
            val out = ByteArrayOutputStream()
            var depth = 1
            while (true) {
                val c = at(p)
                if (c < 0) starve()
                p++
                when (c) {
                    '('.code -> {
                        depth++
                        out.write(c)
                    }
                    ')'.code -> {
                        if (--depth == 0) return Pdf.Str(start, p, out.toByteArray())
                        out.write(c)
                    }
                    '\\'.code -> {
                        val e = at(p)
                        if (e < 0) starve()
                        p++
                        when (e) {
                            'n'.code -> out.write(10)
                            'r'.code -> out.write(13)
                            't'.code -> out.write(9)
                            'b'.code -> out.write(8)
                            'f'.code -> out.write(12)
                            13 -> if (at(p) == 10) p++
                            10 -> Unit
                            in '0'.code..'7'.code -> {
                                var v = e - '0'.code
                                var n = 1
                                while (n < 3 && at(p) in '0'.code..'7'.code) {
                                    v = (v shl 3) or (at(p) - '0'.code)
                                    p++
                                    n++
                                }
                                out.write(v and 0xFF)
                            }
                            else -> out.write(e)
                        }
                    }
                    else -> out.write(c)
                }
            }
        }

        private fun hex(start: Int): Pdf.Str {
            p++
            val out = ByteArrayOutputStream()
            var high = -1
            while (true) {
                val c = at(p)
                if (c < 0) starve()
                p++
                if (c == '>'.code) break
                if (isSpace(c)) continue
                val d = hexDigit(c)
                if (high < 0) {
                    high = d
                } else {
                    out.write((high shl 4) or d)
                    high = -1
                }
            }
            if (high >= 0) out.write(high shl 4)
            return Pdf.Str(start, p, out.toByteArray())
        }

        private fun array(start: Int): Pdf.Arr {
            p++
            val items = ArrayList<Pdf>()
            while (true) {
                skipSpace()
                val c = at(p)
                if (c < 0) starve()
                if (c == ']'.code) {
                    p++
                    return Pdf.Arr(start, p, items)
                }
                items.add(value())
            }
        }

        private fun dict(start: Int): Pdf.Dict {
            p += 2
            val entries = LinkedHashMap<String, Pdf>()
            while (true) {
                skipSpace()
                val c = at(p)
                if (c < 0) starve()
                if (c == '>'.code) {
                    if (at(p + 1) < 0) starve()
                    if (at(p + 1) != '>'.code) fail()
                    p += 2
                    return Pdf.Dict(start, p, entries)
                }
                if (c != '/'.code) fail()
                val key = name(p)
                entries[key.value] = value()
            }
        }

        /** A number, or the reference it begins when a generation and an `R` follow it. */
        private fun number(start: Int): Pdf {
            val w = peekWord()
            if (p + w.length >= b.size) starve()
            p += w.length
            val n = w.toLongOrNull() ?: return if (w.toDoubleOrNull() != null) Pdf.Real(start, p) else fail()
            if (n < 0L || n > Int.MAX_VALUE) return Pdf.Num(start, p, n)
            val back = p
            skipSpace()
            val g = peekWord()
            val gen = g.toIntOrNull()
            if (gen != null && gen >= 0 && g.all { it in '0'..'9' }) {
                p += g.length
                skipSpace()
                if (at(p) < 0) starve()
                if (at(p) == 'R'.code && peekWord() == "R") {
                    p++
                    return Pdf.Ref(start, p, n.toInt(), gen)
                }
            } else if (g.isEmpty() && p >= b.size) {
                starve()
            }
            p = back
            return Pdf.Num(start, back, n)
        }
    }
}
