package com.xnotes.format

import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * The end of [base] from [start] on, seen as a file of its own: position 0 is [start] and it is
 * [length] bytes long. A note embedded in a PDF is read and spliced through one, so [ZipTail] and
 * the codec keep working in offsets relative to the note.
 *
 * Writing past the end grows it, and truncating it cuts [base] there, whatever followed the window.
 * Closing it leaves [base] open.
 */
internal class FileWindow(private val base: FileChannel, val start: Long, length: Long) : FileChannel() {

    private var length = length
    private var pos = 0L

    override fun size(): Long = length

    override fun position(): Long = pos

    override fun position(newPosition: Long): FileChannel {
        require(newPosition >= 0L)
        pos = newPosition
        return this
    }

    override fun read(dst: ByteBuffer): Int {
        val n = read(dst, pos)
        if (n > 0) pos += n
        return n
    }

    override fun read(dst: ByteBuffer, position: Long): Int {
        if (position >= length) return -1
        val room = minOf(dst.remaining().toLong(), length - position).toInt()
        if (room == 0) return 0
        // The window ends before the file may, so the read is held to what is left of the window.
        val limit = dst.limit()
        dst.limit(dst.position() + room)
        try {
            return base.read(dst, start + position)
        } finally {
            dst.limit(limit)
        }
    }

    override fun read(dsts: Array<ByteBuffer>, offset: Int, length: Int): Long {
        var total = 0L
        for (i in offset until offset + length) {
            if (!dsts[i].hasRemaining()) continue
            val n = read(dsts[i])
            if (n < 0) return if (total == 0L) -1L else total
            total += n
            if (dsts[i].hasRemaining()) break
        }
        return total
    }

    override fun write(src: ByteBuffer): Int {
        val n = write(src, pos)
        pos += n
        return n
    }

    override fun write(src: ByteBuffer, position: Long): Int {
        val n = base.write(src, start + position)
        if (position + n > length) length = position + n
        return n
    }

    override fun write(srcs: Array<ByteBuffer>, offset: Int, length: Int): Long {
        var total = 0L
        for (i in offset until offset + length) {
            while (srcs[i].hasRemaining()) total += write(srcs[i])
        }
        return total
    }

    override fun truncate(size: Long): FileChannel {
        require(size >= 0L)
        if (size <= length) {
            base.truncate(start + size)
            length = size
        }
        if (pos > size) pos = size
        return this
    }

    override fun force(metaData: Boolean) = base.force(metaData)

    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long {
        if (position >= length) return 0L
        return base.transferTo(start + position, minOf(count, length - position), target)
    }

    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long {
        val n = base.transferFrom(src, start + position, count)
        if (position + n > length) length = position + n
        return n
    }

    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer =
        throw UnsupportedOperationException()

    override fun lock(position: Long, size: Long, shared: Boolean): FileLock =
        throw UnsupportedOperationException()

    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock =
        throw UnsupportedOperationException()

    override fun implCloseChannel() = Unit
}

/**
 * One channel over a descriptor that Android only hands out as two: [reader] for what is read and
 * [writer] for what is written, at positions given outright. Closing it leaves both open.
 */
internal class ReadWriteChannel(private val reader: FileChannel, private val writer: FileChannel) : FileChannel() {

    private var pos = 0L

    override fun size(): Long = writer.size()

    override fun position(): Long = pos

    override fun position(newPosition: Long): FileChannel {
        require(newPosition >= 0L)
        pos = newPosition
        return this
    }

    override fun read(dst: ByteBuffer): Int {
        val n = reader.read(dst, pos)
        if (n > 0) pos += n
        return n
    }

    override fun read(dst: ByteBuffer, position: Long): Int = reader.read(dst, position)

    override fun read(dsts: Array<ByteBuffer>, offset: Int, length: Int): Long {
        var total = 0L
        for (i in offset until offset + length) {
            if (!dsts[i].hasRemaining()) continue
            val n = read(dsts[i])
            if (n < 0) return if (total == 0L) -1L else total
            total += n
            if (dsts[i].hasRemaining()) break
        }
        return total
    }

    override fun write(src: ByteBuffer): Int {
        val n = writer.write(src, pos)
        pos += n
        return n
    }

    override fun write(src: ByteBuffer, position: Long): Int = writer.write(src, position)

    override fun write(srcs: Array<ByteBuffer>, offset: Int, length: Int): Long {
        var total = 0L
        for (i in offset until offset + length) {
            while (srcs[i].hasRemaining()) total += write(srcs[i])
        }
        return total
    }

    override fun truncate(size: Long): FileChannel {
        writer.truncate(size)
        if (pos > size) pos = size
        return this
    }

    override fun force(metaData: Boolean) = writer.force(metaData)

    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long =
        reader.transferTo(position, count, target)

    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long =
        writer.transferFrom(src, position, count)

    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer =
        throw UnsupportedOperationException()

    override fun lock(position: Long, size: Long, shared: Boolean): FileLock =
        throw UnsupportedOperationException()

    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock =
        throw UnsupportedOperationException()

    override fun implCloseChannel() = Unit
}
