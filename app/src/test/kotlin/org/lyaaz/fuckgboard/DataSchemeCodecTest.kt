package org.lyaaz.fuckgboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DataSchemeCodecTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun join(vararg parts: ByteArray) = parts.fold(byteArrayOf()) { result, part -> result + part }

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val result = ArrayList<Byte>()
        while (remaining and -128L != 0L) {
            result.add(((remaining.toInt() and 0x7f) or 0x80).toByte())
            remaining = remaining ushr 7
        }
        result.add(remaining.toByte())
        return result.toByteArray()
    }

    private fun tag(field: Int, wire: Int) = varint((field.toLong() shl 3) or wire.toLong())
    private fun scalar(field: Int, value: Long) = tag(field, 0) + varint(value)
    private fun data(field: Int, value: ByteArray) = join(tag(field, 2), varint(value.size.toLong()), value)
    private fun text(field: Int, value: String) = data(field, value.toByteArray(Charsets.UTF_8))
    private fun entry(filename: String = "base.marisa", source: Long = 5) =
        data(1, join(scalar(1, 7), text(2, "dictionary-id"), scalar(3, source), text(4, filename)))
    private fun minimalEntry(filename: String) = data(1, join(scalar(3, 5), text(4, filename)))
    private fun root(path: String = "/data/hmmdictionary/zh_CN_123") = text(3, path)
    private fun prefix() = data(2, byteArrayOf())
    private fun scheme(path: String = "/data/hmmdictionary/zh_CN_123", filename: String = "base.marisa") =
        join(entry(filename), prefix(), root(path))

    private val invalidUtf8 = listOf(
        bytes(0x80), bytes(0xc0, 0xaf), bytes(0xc1, 0xbf), bytes(0xc2), bytes(0xc2, 0x7f),
        bytes(0xe0, 0x80, 0xaf), bytes(0xe2, 0x82), bytes(0xed, 0xa0, 0x80),
        bytes(0xf0, 0x80, 0x80, 0xaf), bytes(0xf0, 0x90, 0x80),
        bytes(0xf4, 0x90, 0x80, 0x80), bytes(0xf5, 0x80, 0x80, 0x80), bytes(0xff),
    )

    @Test fun exposesOrderedFilenamesAndReturnsOriginalForUnchangedPaths() {
        val original = join(entry("first/model"), root(), entry("second/dictionary"), prefix())
        val parsed = DataSchemeCodec.parse(original)!!
        assertEquals("/data/hmmdictionary/zh_CN_123", parsed.root)
        assertEquals(listOf("first/model", "second/dictionary"), parsed.entries.map { it.filename })
        assertSame(original, parsed.rewritePaths(parsed.root, parsed.entries.map { it.filename }))
    }

    @Test fun relocatesCustomAndOfficialEntriesUnderOneAbsoluteRoot() {
        val original = join(entry("dictionary"), root(), entry("language_model"), prefix())
        val snapshot = original.copyOf()
        val parsed = DataSchemeCodec.parse(original)!!
        val filenames = listOf("fuckgboard/zh_CN/dictionary", "superpacks/hmmdictionary/zh_CN_123/language_model")
        val rewritten = parsed.rewritePaths("/data/user/0/gboard/files", filenames)
        assertArrayEquals(join(entry(filenames[0]), root("/data/user/0/gboard/files"), entry(filenames[1]), prefix()), rewritten)
        val relocated = DataSchemeCodec.parse(rewritten)!!
        assertEquals("/data/user/0/gboard/files", relocated.root)
        assertEquals(filenames, relocated.entries.map { it.filename })
        assertArrayEquals(snapshot, original)
        assertSame(original, parsed.rewritePaths(parsed.root, listOf("dictionary", "language_model")))
    }

    @Test fun permitsRootOnlyOrFilenameOnlyChangesAndLeavesOtherEntriesUntouched() {
        val original = join(entry("keep"), root("/old"), entry("change"), prefix(), entry("also-keep"))
        val parsed = DataSchemeCodec.parse(original)!!
        assertArrayEquals(join(entry("keep"), root("/"), entry("change"), prefix(), entry("also-keep")),
            parsed.rewritePaths("/", listOf("keep", "change", "also-keep")))
        assertArrayEquals(join(entry("keep"), root("/old"), entry("relocated/change"), prefix(), entry("also-keep")),
            parsed.rewritePaths("/old", listOf("keep", "relocated/change", "also-keep")))
    }

    @Test fun requiresRuntimePrefixRootAndAtLeastOneEntry() {
        val invalid = listOf(
            byteArrayOf(), join(prefix(), root()), join(entry(), root()), join(entry(), prefix()),
            join(entry(), text(2, "builtin/")), join(entry(), text(2, "builtin/"), root()),
            join(entry(), prefix(), prefix(), root()),
        )
        for (payload in invalid) assertNull(DataSchemeCodec.parse(payload))
    }

    @Test fun requiresOneFilesystemSourceAndOneFilenamePerEntry() {
        val invalidEntries = listOf(
            byteArrayOf(), text(4, "file"), scalar(3, 5),
            join(scalar(3, 0), text(4, "file")), join(scalar(3, 3), text(4, "file")),
            join(scalar(3, 6), text(4, "file")), join(scalar(3, -1L), text(4, "file")),
            join(scalar(3, 5), scalar(3, 5), text(4, "file")),
            join(scalar(3, 5), scalar(3, 6), text(4, "file")),
            join(scalar(3, 5), text(4, "one"), text(4, "two")),
        )
        for (payload in invalidEntries) {
            assertNull(DataSchemeCodec.parse(join(data(1, payload), prefix(), root())))
        }
        for (entries in listOf(join(entry(), entry(source = 3)), join(entry(source = 3), entry()))) {
            assertNull(DataSchemeCodec.parse(join(entries, prefix(), root())))
        }
    }

    @Test fun acceptsOptionalTypeAndIdButRejectsDuplicatesAndWrongWireTypes() {
        val required = join(scalar(3, 5), text(4, "file"))
        for (optional in listOf(byteArrayOf(), scalar(1, -1L), data(2, bytes(0xff)),
            join(scalar(1, 9), text(2, "data-id")))) {
            assertNotNull(DataSchemeCodec.parse(join(data(1, optional + required), prefix(), root())))
        }
        for (duplicate in listOf(join(scalar(1, 1), scalar(1, 2)), join(text(2, "one"), text(2, "two")))) {
            assertNull(DataSchemeCodec.parse(join(data(1, duplicate + required), prefix(), root())))
        }
        for (field in 1..4) {
            val expectedWire = if (field == 1 || field == 3) 0 else 2
            for (wire in 0..7) {
                if (wire == expectedWire) continue
                val payload = join(tag(field, wire), wireValue(wire), required)
                assertNull(DataSchemeCodec.parse(join(data(1, payload), prefix(), root())))
            }
        }
    }

    private fun wireValue(wire: Int) = when (wire) {
        0 -> bytes(0)
        1 -> ByteArray(8)
        2 -> bytes(0)
        5 -> ByteArray(4)
        else -> byteArrayOf()
    }

    @Test fun rejectsWrongWireTypesForTopLevelKnownFields() {
        for (wire in listOf(0, 1, 3, 4, 5, 6, 7)) {
            val value = wireValue(wire)
            assertNull(DataSchemeCodec.parse(join(tag(1, wire), value, prefix(), root())))
            assertNull(DataSchemeCodec.parse(join(entry(), tag(2, wire), value, root())))
            assertNull(DataSchemeCodec.parse(join(entry(), prefix(), tag(3, wire), value)))
        }
    }

    @Test fun rejectsDuplicateAndInvalidRootsAndUnsafeFilenames() {
        assertNull(DataSchemeCodec.parse(join(scheme(), root())))
        for (path in listOf("", "relative/path", "\\absolute-on-another-platform", "/with\u0000nul")) {
            assertNull(DataSchemeCodec.parse(scheme(path)))
        }
        for (filename in listOf("", "/absolute", "/", ".", "..", "./file", "../file", "a/./b", "a/../b",
            "a//b", "a/", "a/.", "a/..", "with\u0000nul")) {
            assertNull(DataSchemeCodec.parse(scheme(filename = filename)))
        }
        for (suffix in invalidUtf8) {
            assertNull(DataSchemeCodec.parse(join(entry(), prefix(), data(3, bytes(0x2f) + suffix))))
            val badEntry = data(1, join(scalar(3, 5), data(4, bytes(0x61) + suffix)))
            assertNull(DataSchemeCodec.parse(join(badEntry, prefix(), root())))
        }
    }

    @Test fun preservesUnknownFieldsVerbatimAtBothLevelsIncludingOpaqueNestedPayloads() {
        val unknown = join(
            scalar(5, -1L), scalar(6, Long.MIN_VALUE),
            tag(7, 1) + bytes(0, 1, 2, 3, 4, 5, 6, 255),
            data(8, bytes(0xff, 0, 0x1a, 0x80)),
            tag(9, 5) + bytes(255, 2, 0, 4), scalar(0x1fffffff, 42),
            data(10, join(scalar(3, 6), text(4, "../opaque"))),
        )
        fun nested(filename: String) = data(1, join(unknown, scalar(1, 99), scalar(3, 5),
            text(4, filename), data(2, bytes(0xff)), unknown))
        val original = join(unknown, nested("old"), prefix(), root("/old"), entry("keep"), unknown)
        val snapshot = original.copyOf()
        val parsed = DataSchemeCodec.parse(original)!!
        val rewritten = parsed.rewritePaths("/new/词典/😀", listOf("新/😀", "keep"))
        assertArrayEquals(join(unknown, nested("新/😀"), prefix(), root("/new/词典/😀"), entry("keep"), unknown), rewritten)
        assertArrayEquals(snapshot, original)
        assertEquals(listOf("新/😀", "keep"), DataSchemeCodec.parse(rewritten)!!.entries.map { it.filename })
        val opaqueOnly = data(1, join(data(5, join(scalar(3, 5), text(4, "file")))))
        assertNull(DataSchemeCodec.parse(join(opaqueOnly, prefix(), root())))
    }

    @Test fun adjustsRootAndNestedFilenameLengthsAcrossVarintBoundaries() {
        val rootLengths = listOf(1, 127, 128, 16383, 16384)
        for (oldLength in rootLengths) {
            val parsed = DataSchemeCodec.parse(scheme("/" + "a".repeat(oldLength - 1)))!!
            for (newLength in rootLengths) {
                val replacement = "/" + "b".repeat(newLength - 1)
                assertArrayEquals(scheme(replacement), parsed.rewritePaths(replacement, listOf("base.marisa")))
            }
        }
        // Minimal entries cross their own 127/128 and 16383/16384 lengths at 123/124 and 16378/16379.
        val filenameLengths = listOf(1, 123, 124, 127, 128, 16378, 16379, 16383, 16384)
        for (oldLength in filenameLengths) {
            val original = join(minimalEntry("a".repeat(oldLength)), root("/old"), prefix())
            val parsed = DataSchemeCodec.parse(original)!!
            for (newLength in filenameLengths) {
                val filename = "b".repeat(newLength)
                val rewritten = parsed.rewritePaths("/", listOf(filename))
                assertArrayEquals(join(minimalEntry(filename), root("/"), prefix()), rewritten)
                assertEquals(filename, DataSchemeCodec.parse(rewritten)!!.entries.single().filename)
            }
        }
    }

    @Test fun preservesOverlongTagsAndUnchangedLengthEncodings() {
        val entryTag = bytes(0x8a, 0)
        val entryLength = bytes(0x8a, 0)
        val filenameTag = bytes(0xa2, 0)
        val filenameLength = bytes(0x84, 0)
        val rootTag = bytes(0x9a, 0)
        val rootLength = bytes(0x84, 0)
        val original = join(entryTag, entryLength, scalar(3, 5), filenameTag, filenameLength,
            "file".toByteArray(), prefix(), rootTag, rootLength, "/old".toByteArray())
        val parsed = DataSchemeCodec.parse(original)!!
        assertSame(original, parsed.rewritePaths("/old", listOf("file")))
        assertArrayEquals(join(entryTag, entryLength, scalar(3, 5), filenameTag, filenameLength,
            "same".toByteArray(), prefix(), rootTag, rootLength, "/new".toByteArray()),
            parsed.rewritePaths("/new", listOf("same")))
        // Shrinking the filename length's encoding offsets a one-byte filename growth.
        assertArrayEquals(join(entryTag, entryLength, scalar(3, 5), filenameTag, bytes(5),
            "other".toByteArray(), prefix(), rootTag, bytes(1), bytes(0x2f)),
            parsed.rewritePaths("/", listOf("other")))
        val filename = "x".repeat(130)
        val payload = join(scalar(3, 5), filenameTag, varint(130), filename.toByteArray())
        assertArrayEquals(join(entryTag, varint(payload.size.toLong()), payload, prefix(), rootTag, rootLength,
            "/old".toByteArray()), parsed.rewritePaths("/old", listOf(filename)))
    }

    @Test fun validatesReplacementPathCountsUnicodeAndComponentsWithoutLossyEncoding() {
        val parsed = DataSchemeCodec.parse(scheme())!!
        for (filenames in listOf(emptyList(), listOf("one", "two"))) {
            assertThrows(IllegalArgumentException::class.java) { parsed.rewritePaths(parsed.root, filenames) }
        }
        for (path in listOf("", "relative", "/nul\u0000", "/\ud800", "/\udc00", "/\ud800x", "/\udc00\ud800")) {
            assertThrows(IllegalArgumentException::class.java) { parsed.rewritePaths(path, listOf("file")) }
        }
        for (filename in listOf("", "/absolute", ".", "..", "a//b", "a/", "a/./b", "a/../b", "a/.", "a/..",
            "nul\u0000", "\ud800", "\udc00", "\ud800x", "\udc00\ud800")) {
            assertThrows(IllegalArgumentException::class.java) { parsed.rewritePaths(parsed.root, listOf(filename)) }
        }
        for (filename in listOf("é/词/😀", "\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff", "\udbff\udfff", ".hidden/a..b")) {
            val rewritten = parsed.rewritePaths("/$filename", listOf(filename))
            val relocated = DataSchemeCodec.parse(rewritten)!!
            assertEquals("/$filename", relocated.root)
            assertEquals(filename, relocated.entries.single().filename)
            assertArrayEquals(scheme("/$filename", filename), rewritten)
        }
    }

    @Test fun rejectsMalformedTagsLengthsAndUnknownPayloadsWithoutThrowing() {
        val malformed = listOf(
            bytes(0), bytes(1), bytes(7), bytes(0x80), varint(1L shl 32), varint(-1L),
            ByteArray(11) { 0x80.toByte() }, tag(5, 0) + bytes(0x80),
            tag(5, 0) + ByteArray(10) { 0x80.toByte() },
            tag(5, 0) + ByteArray(9) { 0xff.toByte() } + bytes(2),
            tag(5, 1) + ByteArray(7), tag(5, 5) + ByteArray(3),
            tag(5, 2) + bytes(0x80), tag(5, 2) + bytes(2, 0),
            tag(5, 2) + varint(Long.MAX_VALUE), tag(5, 2) + varint(Long.MIN_VALUE), tag(5, 2) + varint(-1L),
            tag(5, 3), tag(5, 4), tag(5, 6), tag(5, 7),
        )
        for (suffix in malformed) {
            assertNull(DataSchemeCodec.parse(scheme() + suffix))
            val nested = data(1, join(scalar(3, 5), text(4, "file"), suffix))
            assertNull(DataSchemeCodec.parse(join(nested, prefix(), root())))
        }
        val truncatedFields = listOf(
            bytes(0x0a), bytes(0x0a, 0x80), bytes(0x0a, 3, 0x18, 5),
            bytes(0x12), bytes(0x12, 0x80), bytes(0x1a), bytes(0x1a, 0x80), bytes(0x1a, 2, 0x2f),
        )
        for (payload in truncatedFields) assertNull(DataSchemeCodec.parse(payload))
        // Nested values cannot borrow bytes from the following top-level prefix or root.
        for (payload in listOf(bytes(0x18), bytes(0x18, 0x80), bytes(0x22), bytes(0x22, 2, 0x61),
            bytes(0x08, 0x80), bytes(0x12, 2, 0x61))) {
            assertNull(DataSchemeCodec.parse(join(data(1, payload), prefix(), root())))
        }
    }
}
