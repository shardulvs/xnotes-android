package com.xnotes.format

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry

/**
 * Replaces the tail of an existing bundle in place, so a save does not have to rebuild the whole
 * file around assets that did not change.
 *
 * A `.xnote` is written assets first, manifest last. When only the ink changed, everything ahead of
 * the manifest is byte for byte what it should already be: for a note built from a 500-page PDF
 * that is essentially the entire file, re-read and re-written twice per save (once into the temp
 * bundle, once out through the storage provider) to record one stroke. Splicing writes only the
 * bytes that actually changed.
 *
 * The deflating, the checksums and the entry headers all stay with `java.util.zip`: the caller
 * writes the new tail into a small ordinary zip of its own, and this copies that zip's entry data
 * onto the end of the assets and rebuilds the central directory over both halves. The only zip
 * structures written here are the directory records (the kept ones verbatim, the new ones with one
 * offset field adjusted) and the 22-byte end record.
 *
 * Zip64 files, and files carrying a comment, are refused: this never writes either, so a bundle
 * with one did not come from here and is not one to patch blind.
 *
 * **The file is briefly not a valid zip**, between the first byte of the new tail and the last byte
 * of the end record. That window is the size of the manifest, tens of milliseconds. The path this
 * replaces has the same exposure for far longer: it truncates the destination and then spends over
 * a second refilling it.
 */
internal object ZipTail {

    private const val EOCD_SIG = 0x06054b50
    private const val CD_SIG = 0x02014b50
    private const val EOCD_LEN = 22
    private const val CD_FIXED = 46
    private const val LOCAL_SIG = 0x04034b50
    private const val LOCAL_HEADER = 30

    /** The u32 ceiling every offset and size in a plain (non-zip64) zip has to stay under. */
    private const val MAX_U32 = 0xFFFFFFFFL

    /** One entry as the central directory describes it, with that description's bytes verbatim. */
    class Entry(
        val name: String,
        val method: Int,
        val localOffset: Long,
        val size: Long,
        val record: ByteArray,
    ) {
        val crc: Long get() = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN).getInt(16).toLong() and MAX_U32
    }

    class Directory(val entries: List<Entry>, val offset: Long)

    /** Read [ch]'s central directory, or null when this is not a zip worth patching. */
    fun read(ch: FileChannel): Directory? {
        val size = ch.size()
        if (size < EOCD_LEN) return null
        // The end record sits last, after a comment of up to 64K that this never writes.
        val span = minOf(size, (EOCD_LEN + 0xFFFF).toLong()).toInt()
        val tail = map(ch, size - span, span) ?: return null
        var at = -1
        for (i in span - EOCD_LEN downTo 0) {
            if (tail.getInt(i) == EOCD_SIG) {
                at = i
                break
            }
        }
        if (at < 0) return null
        val count = tail.getShort(at + 10).toInt() and 0xFFFF
        val cdSize = tail.getInt(at + 12).toLong() and MAX_U32
        val cdOffset = tail.getInt(at + 16).toLong() and MAX_U32
        val commentLen = tail.getShort(at + 20).toInt() and 0xFFFF
        if (count == 0xFFFF || cdSize == MAX_U32 || cdOffset == MAX_U32) return null // zip64
        if (commentLen != 0 || cdOffset + cdSize > size) return null
        val cd = map(ch, cdOffset, cdSize.toInt()) ?: return null
        val entries = ArrayList<Entry>(count)
        var p = 0
        repeat(count) {
            if (p + CD_FIXED > cdSize || cd.getInt(p) != CD_SIG) return null
            val nameLen = cd.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = cd.getShort(p + 30).toInt() and 0xFFFF
            val cmtLen = cd.getShort(p + 32).toInt() and 0xFFFF
            val len = CD_FIXED + nameLen + extraLen + cmtLen
            if (p + len > cdSize) return null
            val record = ByteArray(len)
            cd.position(p)
            cd.get(record)
            entries.add(
                Entry(
                    name = String(record, CD_FIXED, nameLen, Charsets.UTF_8),
                    method = cd.getShort(p + 10).toInt() and 0xFFFF,
                    localOffset = cd.getInt(p + 42).toLong() and MAX_U32,
                    size = cd.getInt(p + 24).toLong() and MAX_U32,
                    record = record,
                ),
            )
            p += len
        }
        return Directory(entries, cdOffset)
    }

    /** Hand [reader] entry [name] of [ch], inflated when it was deflated, seeking straight to it; null when there is no such entry. */
    fun <T> readEntry(ch: FileChannel, name: String, reader: (InputStream) -> T): T? {
        val entry = read(ch)?.entries?.firstOrNull { it.name == name } ?: return null
        return readEntry(ch, entry, reader)
    }

    /** [readEntry] for an entry of a directory already read. A stored entry's stream runs past its end, so read only [Entry.size] bytes of it. */
    fun <T> readEntry(ch: FileChannel, entry: Entry, reader: (InputStream) -> T): T? {
        val head = ByteBuffer.allocate(LOCAL_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        while (head.hasRemaining()) if (ch.read(head, entry.localOffset + head.position()) < 0) return null
        if (head.getInt(0) != LOCAL_SIG) return null
        val start = entry.localOffset + LOCAL_HEADER + (head.getShort(26).toInt() and 0xFFFF) + (head.getShort(28).toInt() and 0xFFFF)
        val raw = BufferedInputStream(Channels.newInputStream(ch.position(start)), 64 * 1024)
        if (entry.method != ZipEntry.DEFLATED) return reader(raw)
        val inflater = Inflater(true)
        try {
            return reader(InflaterInputStream(raw, inflater, 64 * 1024))
        } finally {
            inflater.end()
        }
    }

    /**
     * Overwrite [dest] from [tailStart] on with the entry data from [tail] (an ordinary zip holding
     * just the new tail entries), then a central directory of [keep]'s records followed by [tail]'s,
     * then a new end record. [keep] must be exactly the entries of [dest] that live before
     * [tailStart]; their records are reused untouched because their positions have not moved.
     *
     * Returns the file's new length, or -1 when the result would not fit a plain zip's 32-bit
     * offsets, in which case nothing has been written.
     */
    fun splice(
        dest: FileChannel,
        tailStart: Long,
        keep: List<Entry>,
        tail: FileChannel,
        tailDir: Directory,
    ): Long {
        val dataLen = tailDir.offset // the tail zip's entries end where its directory begins
        var cdLen = 0L
        for (e in keep) cdLen += e.record.size
        for (e in tailDir.entries) cdLen += e.record.size
        val cdAt = tailStart + dataLen
        if (cdAt + cdLen + EOCD_LEN > MAX_U32) return -1
        if (keep.size + tailDir.entries.size > 0xFFFF) return -1

        var pos = tailStart
        var read = 0L
        while (read < dataLen) {
            val n = tail.transferTo(read, dataLen - read, WritableAt(dest, pos))
            if (n <= 0L) return -1
            read += n
            pos += n
        }
        for (e in keep) pos += writeAt(dest, pos, e.record)
        for (e in tailDir.entries) {
            // The only thing in a directory record that moved: where its local header now starts.
            val moved = e.record.copyOf()
            putU32(moved, 42, e.localOffset + tailStart)
            pos += writeAt(dest, pos, moved)
        }
        val end = ByteArray(EOCD_LEN)
        putU32(end, 0, EOCD_SIG.toLong())
        putU16(end, 8, keep.size + tailDir.entries.size) // entries on this disk
        putU16(end, 10, keep.size + tailDir.entries.size) // entries in total
        putU32(end, 12, cdLen)
        putU32(end, 16, cdAt)
        pos += writeAt(dest, pos, end)
        dest.truncate(pos)
        return pos
    }

    private fun map(ch: FileChannel, at: Long, len: Int): ByteBuffer? {
        if (at < 0 || len < 0 || at + len > ch.size()) return null
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        var read = 0
        while (read < len) {
            val n = ch.read(buf, at + read)
            if (n < 0) return null
            read += n
        }
        buf.rewind()
        return buf
    }

    private fun writeAt(ch: FileChannel, at: Long, bytes: ByteArray): Long {
        val buf = ByteBuffer.wrap(bytes)
        var written = 0
        while (written < bytes.size) {
            val n = ch.write(buf, at + written)
            if (n <= 0) throw java.io.IOException("short write")
            written += n
        }
        return bytes.size.toLong()
    }

    private fun putU16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v ushr 8) and 0xFF).toByte()
        b[at + 2] = ((v ushr 16) and 0xFF).toByte()
        b[at + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /** Adapts a positioned region of [ch] to the sink [FileChannel.transferTo] wants. */
    private class WritableAt(private val ch: FileChannel, private val at: Long) :
        java.nio.channels.WritableByteChannel {
        private var offset = 0L

        override fun write(src: ByteBuffer): Int {
            val n = ch.write(src, at + offset)
            offset += n
            return n
        }

        override fun isOpen(): Boolean = ch.isOpen

        override fun close() = Unit
    }
}
