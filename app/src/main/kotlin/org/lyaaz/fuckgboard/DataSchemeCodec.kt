package org.lyaaz.fuckgboard

/** Recognizes runtime filesystem schemes and retains untouched protobuf fields verbatim. */
object DataSchemeCodec {
    fun parse(bytes: ByteArray): Scheme? {
        val cursor = Cursor(bytes)
        var rootStart = -1
        var rootLengthStart = -1
        var rootEnd = -1
        var hasPrefix = false
        val entries = ArrayList<Entry>()
        while (cursor.position < bytes.size) {
            if (!cursor.readTag(bytes.size)) return null
            when (cursor.field) {
                1 -> {
                    if (cursor.wire != 2) return null
                    val lengthStart = cursor.position
                    val end = cursor.readLengthEnd(bytes.size)
                    if (end < 0) return null
                    entries.add(cursor.readEntry(lengthStart, end) ?: return null)
                }
                2 -> {
                    if (cursor.wire != 2 || hasPrefix) return null
                    val end = cursor.readLengthEnd(bytes.size)
                    if (end != cursor.position) return null
                    hasPrefix = true
                }
                3 -> {
                    if (cursor.wire != 2 || rootStart >= 0) return null
                    rootLengthStart = cursor.position
                    rootEnd = cursor.readLengthEnd(bytes.size)
                    rootStart = cursor.position
                    if (rootEnd <= rootStart || bytes[rootStart] != '/'.code.toByte()) return null
                    if (!validUtf8Path(bytes, rootStart, rootEnd)) return null
                    cursor.position = rootEnd
                }
                else -> if (!cursor.skip(bytes.size)) return null
            }
        }
        if (entries.isEmpty() || !hasPrefix || rootStart < 0) return null
        return Scheme(bytes, rootLengthStart, rootStart, rootEnd,
            String(bytes, rootStart, rootEnd - rootStart, Charsets.UTF_8), entries)
    }

    class Entry internal constructor(
        val filename: String,
        internal val lengthStart: Int,
        internal val start: Int,
        internal val end: Int,
        internal val filenameLengthStart: Int,
        internal val filenameStart: Int,
        internal val filenameEnd: Int,
    )

    class Scheme internal constructor(
        private val original: ByteArray,
        private val rootLengthStart: Int,
        private val rootStart: Int,
        private val rootEnd: Int,
        val root: String,
        val entries: List<Entry>,
    ) {
        fun rewritePaths(root: String, filenames: List<String>): ByteArray {
            require(filenames.size == entries.size) { "Every entry needs a filename" }
            if (root == this.root && entries.indices.all { filenames[it] == entries[it].filename }) return original
            val rootLength = replacementLength(root, relative = false)
            val rootChanged = root != this.root
            val filenameLengths = IntArray(entries.size)
            var size = original.size.toLong()
            if (rootChanged) {
                size += rootLength - (rootEnd - rootStart)
                if (rootLength != rootEnd - rootStart) {
                    size += varintSize(rootLength) - (rootStart - rootLengthStart)
                }
            }
            for (index in entries.indices) {
                val entry = entries[index]
                val filename = filenames[index]
                val length = replacementLength(filename, relative = true)
                filenameLengths[index] = length
                if (filename == entry.filename) continue
                val payloadLength = rewrittenEntryLength(entry, length)
                size += payloadLength - (entry.end - entry.start)
                if (payloadLength != entry.end - entry.start) {
                    size += varintSize(payloadLength) - (entry.start - entry.lengthStart)
                }
            }
            require(size <= Int.MAX_VALUE) { "Scheme is too large" }
            val result = ByteArray(size.toInt())
            var source = 0
            var destination = 0
            var index = 0
            var rootPending = rootChanged
            while (index < entries.size || rootPending) {
                if (rootPending && (index == entries.size || rootLengthStart < entries[index].lengthStart)) {
                    val copyEnd = if (rootLength == rootEnd - rootStart) rootStart else rootLengthStart
                    original.copyInto(result, destination, source, copyEnd)
                    destination += copyEnd - source
                    if (copyEnd == rootLengthStart) destination = writeVarint(result, destination, rootLength)
                    destination = writeUtf8(result, destination, root)
                    source = rootEnd
                    rootPending = false
                } else {
                    val entry = entries[index]
                    val filename = filenames[index]
                    val length = filenameLengths[index++]
                    if (filename == entry.filename) continue
                    val payloadLength = rewrittenEntryLength(entry, length)
                    val copyEnd = if (payloadLength == entry.end - entry.start) entry.start else entry.lengthStart
                    original.copyInto(result, destination, source, copyEnd)
                    destination += copyEnd - source
                    if (copyEnd == entry.lengthStart) destination = writeVarint(result, destination, payloadLength)
                    val filenameCopyEnd = if (length == entry.filenameEnd - entry.filenameStart) {
                        entry.filenameStart
                    } else {
                        entry.filenameLengthStart
                    }
                    original.copyInto(result, destination, entry.start, filenameCopyEnd)
                    destination += filenameCopyEnd - entry.start
                    if (filenameCopyEnd == entry.filenameLengthStart) destination = writeVarint(result, destination, length)
                    destination = writeUtf8(result, destination, filename)
                    source = entry.filenameEnd
                }
            }
            original.copyInto(result, destination, source)
            return result
        }
    }

    private fun rewrittenEntryLength(entry: Entry, filenameLength: Int): Int {
        val oldLength = entry.filenameEnd - entry.filenameStart
        if (filenameLength == oldLength) return entry.end - entry.start
        val length = entry.end.toLong() - entry.start - oldLength - (entry.filenameStart - entry.filenameLengthStart) +
            filenameLength + varintSize(filenameLength)
        require(length <= Int.MAX_VALUE) { "Entry is too large" }
        return length.toInt()
    }

    private fun varintSize(value: Int): Int {
        var remaining = value
        var size = 1
        while (remaining >= 128) {
            size++
            remaining = remaining ushr 7
        }
        return size
    }

    private fun writeVarint(bytes: ByteArray, start: Int, value: Int): Int {
        var position = start
        var remaining = value
        while (remaining >= 128) {
            bytes[position++] = ((remaining and 0x7f) or 0x80).toByte()
            remaining = remaining ushr 7
        }
        bytes[position++] = remaining.toByte()
        return position
    }

    private class Cursor(private val bytes: ByteArray) {
        var position = 0
        var field = 0
        var wire = 0
        private var value = 0L

        // Keep all 64 bits: an unknown uint64 may legitimately have the sign bit set.
        private fun readVarint(end: Int): Boolean {
            value = 0
            for (index in 0..9) {
                if (position >= end) return false
                val next = bytes[position++].toInt() and 0xff
                if (index == 9 && next > 1) return false
                value = value or ((next and 0x7f).toLong() shl (index * 7))
                if (next < 128) return true
            }
            return false
        }

        fun readTag(end: Int): Boolean {
            if (!readVarint(end) || value ushr 32 != 0L || value < 8) return false
            field = (value ushr 3).toInt()
            wire = (value and 7).toInt()
            return true
        }

        fun readLengthEnd(end: Int): Int {
            if (!readVarint(end) || value < 0 || value > (end - position).toLong()) return -1
            return position + value.toInt()
        }

        fun readEntry(lengthStart: Int, end: Int): Entry? {
            val start = position
            var hasType = false
            var hasId = false
            var hasSource = false
            var filenameLengthStart = -1
            var filenameStart = -1
            var filenameEnd = -1
            while (position < end) {
                if (!readTag(end)) return null
                when (field) {
                    1 -> {
                        if (wire != 0 || hasType || !readVarint(end)) return null
                        hasType = true
                    }
                    2 -> {
                        if (wire != 2 || hasId || !skip(end)) return null
                        hasId = true
                    }
                    3 -> {
                        if (wire != 0 || hasSource || !readVarint(end) || value != 5L) return null
                        hasSource = true
                    }
                    4 -> {
                        if (wire != 2 || filenameStart >= 0) return null
                        filenameLengthStart = position
                        filenameEnd = readLengthEnd(end)
                        filenameStart = position
                        if (filenameEnd < 0 || !validUtf8Path(bytes, filenameStart, filenameEnd) ||
                            !validRelativePath(bytes, filenameStart, filenameEnd)) return null
                        position = filenameEnd
                    }
                    else -> if (!skip(end)) return null
                }
            }
            if (!hasSource || filenameStart < 0) return null
            return Entry(String(bytes, filenameStart, filenameEnd - filenameStart, Charsets.UTF_8),
                lengthStart, start, end, filenameLengthStart, filenameStart, filenameEnd)
        }

        fun skip(end: Int): Boolean {
            when (wire) {
                0 -> return readVarint(end)
                1 -> {
                    if (end - position < 8) return false
                    position += 8
                }
                2 -> {
                    val next = readLengthEnd(end)
                    if (next < 0) return false
                    position = next
                }
                5 -> {
                    if (end - position < 4) return false
                    position += 4
                }
                else -> return false
            }
            return true
        }
    }

    private fun validRelativePath(bytes: ByteArray, start: Int, end: Int): Boolean {
        var component = start
        for (position in start..end) {
            if (position != end && bytes[position] != '/'.code.toByte()) continue
            val length = position - component
            if (length == 0 || (bytes[component] == '.'.code.toByte() &&
                (length == 1 || (length == 2 && bytes[component + 1] == '.'.code.toByte())))) return false
            component = position + 1
        }
        return true
    }

    private fun validUtf8Path(bytes: ByteArray, start: Int, end: Int): Boolean {
        var position = start
        while (position < end) {
            val first = bytes[position++].toInt() and 0xff
            if (first < 128) {
                if (first == 0) return false
                continue
            }
            val count: Int
            val minimum: Int
            var codePoint: Int
            when (first) {
                in 0xc2..0xdf -> { count = 1; minimum = 0x80; codePoint = first and 0x1f }
                in 0xe0..0xef -> { count = 2; minimum = 0x800; codePoint = first and 0x0f }
                in 0xf0..0xf4 -> { count = 3; minimum = 0x10000; codePoint = first and 7 }
                else -> return false
            }
            if (end - position < count) return false
            repeat(count) {
                val next = bytes[position++].toInt() and 0xff
                if (next !in 0x80..0xbf) return false
                codePoint = (codePoint shl 6) or (next and 0x3f)
            }
            if (codePoint < minimum || codePoint > 0x10ffff || codePoint in 0xd800..0xdfff) return false
        }
        return true
    }

    private fun replacementLength(path: String, relative: Boolean): Int {
        require(if (relative) path.isNotEmpty() && !path.startsWith('/') else path.startsWith('/')) {
            "Expected ${if (relative) "relative filename" else "absolute root"}"
        }
        var position = 0
        var component = 0
        var length = 0L
        while (position < path.length) {
            val char = path[position++]
            require(char != '\u0000' && char !in '\udc00'..'\udfff') { "Path must be NUL-free Unicode" }
            if (relative && char == '/') {
                require(validComponent(path, component, position - 1)) { "Filename contains an invalid component" }
                component = position
            }
            length += when {
                char < '\u0080' -> 1
                char < '\u0800' -> 2
                char !in '\ud800'..'\udbff' -> 3
                else -> {
                    require(position < path.length && path[position++] in '\udc00'..'\udfff') {
                        "Path must be valid Unicode"
                    }
                    4
                }
            }
        }
        require(!relative || validComponent(path, component, path.length)) { "Filename contains an invalid component" }
        require(length <= Int.MAX_VALUE) { "Path is too large" }
        return length.toInt()
    }

    private fun validComponent(path: String, start: Int, end: Int): Boolean {
        val length = end - start
        return length > 0 && !(path[start] == '.' && (length == 1 || (length == 2 && path[start + 1] == '.')))
    }

    // The replacement has already been checked for unpaired UTF-16 surrogates.
    private fun writeUtf8(bytes: ByteArray, start: Int, path: String): Int {
        var position = start
        var index = 0
        while (index < path.length) {
            val char = path[index++].code
            when {
                char < 0x80 -> bytes[position++] = char.toByte()
                char < 0x800 -> {
                    bytes[position++] = (0xc0 or (char ushr 6)).toByte()
                    bytes[position++] = (0x80 or (char and 0x3f)).toByte()
                }
                char !in 0xd800..0xdbff -> {
                    bytes[position++] = (0xe0 or (char ushr 12)).toByte()
                    bytes[position++] = (0x80 or ((char ushr 6) and 0x3f)).toByte()
                    bytes[position++] = (0x80 or (char and 0x3f)).toByte()
                }
                else -> {
                    val codePoint = 0x10000 + ((char - 0xd800) shl 10) + (path[index++].code - 0xdc00)
                    bytes[position++] = (0xf0 or (codePoint ushr 18)).toByte()
                    bytes[position++] = (0x80 or ((codePoint ushr 12) and 0x3f)).toByte()
                    bytes[position++] = (0x80 or ((codePoint ushr 6) and 0x3f)).toByte()
                    bytes[position++] = (0x80 or (codePoint and 0x3f)).toByte()
                }
            }
        }
        return position
    }
}
