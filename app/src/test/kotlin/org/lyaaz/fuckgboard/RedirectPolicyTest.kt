package org.lyaaz.fuckgboard

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RedirectPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    private val filesDir get() = File(temporary.root, "files")

    private fun original(pack: String = "zh_CN_123") =
        File(filesDir, "superpacks/hmmdictionary/$pack").apply { mkdirs() }

    private fun custom(language: String = "zh_CN") =
        File(filesDir, "fuckgboard/$language").apply { mkdirs() }

    private fun put(root: File, filename: String, contents: String): File =
        File(root, filename).apply { requireNotNull(parentFile).mkdirs(); writeText(contents) }

    private fun policy(report: (String) -> Unit = {}) = RedirectPolicy(filesDir, report)

    private fun scheme(directory: File, vararg filenames: String): DataSchemeCodec.Scheme {
        fun field(number: Int, value: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            output.write((number shl 3) or 2)
            var length = value.size
            while (length >= 128) {
                output.write((length and 0x7f) or 0x80)
                length = length ushr 7
            }
            output.write(length)
            output.write(value)
            return output.toByteArray()
        }
        val output = ByteArrayOutputStream()
        for ((index, filename) in filenames.withIndex()) {
            val entry = byteArrayOf(8, 1) + field(2, "data-$index".toByteArray()) +
                byteArrayOf(24, 5) + field(4, filename.toByteArray())
            output.write(field(1, entry))
        }
        output.write(field(2, byteArrayOf()))
        output.write(field(3, directory.canonicalPath.toByteArray()))
        return requireNotNull(DataSchemeCodec.parse(output.toByteArray()))
    }

    private fun rewrite(
        policy: RedirectPolicy,
        directory: File,
        vararg filenames: String,
    ): DataSchemeCodec.Scheme? {
        val source = policy.identifySource(directory) ?: return null
        val bytes = policy.rewriteFor(source, scheme(directory, *filenames)) ?: return null
        return requireNotNull(DataSchemeCodec.parse(bytes))
    }

    private fun resolved(scheme: DataSchemeCodec.Scheme, index: Int): File =
        File(scheme.root, scheme.entries[index].filename)

    @Test fun oneFileSparseOverlayReadsCustomDictionaryAndOfficialModel() {
        val original = original()
        val custom = custom()
        put(original, "pinyin_system_dictionary", "official dictionary")
        put(original, "model.bin", "official model")
        put(custom, "pinyin_system_dictionary", "unique custom dictionary")
        val result = requireNotNull(rewrite(policy(), original, "pinyin_system_dictionary", "model.bin"))
        assertEquals(filesDir.canonicalPath, result.root)
        assertEquals("fuckgboard/zh_CN/pinyin_system_dictionary", result.entries[0].filename)
        assertEquals("superpacks/hmmdictionary/zh_CN_123/model.bin", result.entries[1].filename)
        assertEquals("unique custom dictionary", resolved(result, 0).readText())
        assertEquals("official model", resolved(result, 1).readText())
        assertEquals("official dictionary", File(original, "pinyin_system_dictionary").readText())
        assertFalse(File(custom, "model.bin").exists())
    }

    @Test fun selectsEveryReferencedCustomFileIncludingNestedPaths() {
        val original = original()
        val custom = custom()
        val names = arrayOf("dict/base.marisa", "nested/models/token.bin", "official.bin")
        for (name in names) put(original, name, "official $name")
        put(custom, names[0], "custom dictionary")
        put(custom, names[1], "custom tokens")
        val result = requireNotNull(rewrite(policy(), original, *names))
        assertEquals("custom dictionary", resolved(result, 0).readText())
        assertEquals("custom tokens", resolved(result, 1).readText())
        assertEquals("official official.bin", resolved(result, 2).readText())
        assertTrue(result.entries.all { entry -> entry.filename.split('/').none { it == ".." } })
    }

    @Test fun noMatchingCustomFileLeavesOriginalMessageUnchangedAndIgnoresExtras() {
        val original = original()
        put(original, "model.bin", "official")
        assertNull(rewrite(policy(), original, "model.bin"))
        val custom = custom()
        put(custom, "unreferenced.bin", "not enrolled")
        Files.createSymbolicLink(File(custom, "unused-link").toPath(), original.toPath())
        assertNull(rewrite(policy(), original, "model.bin"))
        put(custom, "model.bin", "custom")
        val result = requireNotNull(rewrite(policy(), original, "model.bin"))
        assertEquals(1, result.entries.size)
        assertEquals("custom", resolved(result, 0).readText())
    }

    @Test fun followsVersionChangesAndKeepsLanguagesIndependent() {
        val policy = policy()
        put(custom(), "dictionary", "Chinese")
        for (pack in listOf("zh_CN_123", "zh_CN_2027_release_v2")) {
            val original = original(pack)
            put(original, "dictionary", "official")
            assertEquals("Chinese", resolved(requireNotNull(rewrite(policy, original, "dictionary")), 0).readText())
        }
        val korean = original("ko_2026")
        put(korean, "dictionary", "official Korean")
        assertNull(rewrite(policy, korean, "dictionary"))
        put(custom("ko"), "dictionary", "Korean")
        assertEquals("Korean", resolved(requireNotNull(rewrite(policy, korean, "dictionary")), 0).readText())
        assertNull(rewrite(policy, original("zh_TW_2026"), "dictionary"))
    }

    @Test fun preservesScriptAndNumericRegionPrefixes() {
        for ((pack, language) in listOf("zh_Hant_TW_build_2" to "zh_Hant_TW", "es_419_123" to "es_419")) {
            val original = original(pack)
            put(custom(language), "dictionary", language)
            val policy = policy()
            assertEquals(language, requireNotNull(policy.identifySource(original)).language)
            assertEquals(language, resolved(requireNotNull(rewrite(policy, original, "dictionary")), 0).readText())
        }
    }

    @Test fun identifiesOnlyExactCanonicalManagedVersionDirectories() {
        val original = original()
        val policy = policy()
        assertEquals(original.canonicalFile, requireNotNull(policy.identifySource(File(original, "../zh_CN_123"))).directory)
        assertNull(policy.identifySource(File(filesDir, "elsewhere/zh_CN_123")))
        assertNull(policy.identifySource(File(original, "ko_123")))
        for (name in listOf("zh_CN", "zh_Hant_TW", "ko", "zh_CN__123", "zh_CN_123/../..")) {
            assertNull(policy.identifySource(File(original.parentFile, name)))
        }
        val outside = File(temporary.root, "outside/zh_CN_456").apply { mkdirs() }
        val alias = File(original.parentFile, "zh_CN_456")
        Files.createSymbolicLink(alias.toPath(), outside.toPath())
        assertNull(policy.identifySource(alias))
    }

    @Test fun preservesMissingAndSymlinkedOfficialReferencesWithoutCanonicalizingThem() {
        val original = original()
        put(custom(), "dictionary", "custom")
        val target = put(File(temporary.root, "official-target"), "model.bin", "linked official model")
        Files.createSymbolicLink(File(original, "model.bin").toPath(), target.toPath())
        val result = requireNotNull(rewrite(policy(), original, "dictionary", "pinyin_initial_token_expansion", "model.bin"))
        assertEquals("custom", resolved(result, 0).readText())
        assertEquals("superpacks/hmmdictionary/zh_CN_123/pinyin_initial_token_expansion", result.entries[1].filename)
        assertFalse(resolved(result, 1).exists())
        assertEquals("superpacks/hmmdictionary/zh_CN_123/model.bin", result.entries[2].filename)
        assertTrue(Files.isSymbolicLink(resolved(result, 2).toPath()))
        assertEquals("linked official model", resolved(result, 2).readText())
    }

    @Test fun invalidCustomFilesFallBackIndividuallyWhileValidCustomStillLoads() {
        val original = original()
        val custom = custom()
        val names = arrayOf("valid", "empty", "directory", "link", "escape/model", "dangling")
        for (name in names) put(original, name, "official $name")
        put(custom, "valid", "valid custom")
        put(custom, "empty", "")
        File(custom, "directory").mkdir()
        Files.createSymbolicLink(File(custom, "link").toPath(), File(original, "link").toPath())
        val outside = File(temporary.root, "outside").apply { mkdirs() }
        put(outside, "model", "must not load")
        Files.createSymbolicLink(File(custom, "escape").toPath(), outside.toPath())
        Files.createSymbolicLink(File(custom, "dangling").toPath(), File(outside, "missing").toPath())
        val result = requireNotNull(rewrite(policy(), original, *names))
        assertEquals("valid custom", resolved(result, 0).readText())
        for (index in 1 until names.size) {
            assertEquals("official ${names[index]}", resolved(result, index).readText())
        }
    }

    @Test fun refusesSymlinkedLanguageDirectoriesAndWrongLocaleAliases() {
        val original = original()
        val custom = custom()
        val external = File(temporary.root, "external/zh_CN").apply { mkdirs() }
        put(external, "dictionary", "external")
        assertTrue(custom.delete())
        Files.createSymbolicLink(custom.toPath(), external.toPath())
        assertNull(rewrite(policy(), original, "dictionary"))
        assertTrue(custom.delete())
        val korean = custom("ko")
        put(korean, "dictionary", "Korean")
        Files.createSymbolicLink(custom.toPath(), korean.toPath())
        assertNull(rewrite(policy(), original, "dictionary"))
    }

    @Test fun refusesCustomRootSymlinkEscapesIncludingManagedAndSiblingLocations() {
        val original = original()
        val customRoot = File(filesDir, "fuckgboard")
        val targets = listOf(
            File(temporary.root, "external"),
            File(filesDir, "alternate"),
            File(filesDir, "superpacks/hmmdictionary"),
        )
        for (target in targets) {
            put(File(target, "zh_CN"), "dictionary", "must not load")
            Files.createSymbolicLink(customRoot.toPath(), target.toPath())
            assertNull(rewrite(policy(), original, "dictionary"))
            assertTrue(customRoot.delete())
        }
    }

    @Test fun rejectsSourceNamespaceSymlinkEscapesOutsideFilesDirectory() {
        File(filesDir, "superpacks").mkdirs()
        val outside = File(temporary.root, "external/hmmdictionary").apply { mkdirs() }
        val original = File(outside, "zh_CN_123").apply { mkdirs() }
        Files.createSymbolicLink(File(filesDir, "superpacks/hmmdictionary").toPath(), outside.toPath())
        put(custom(), "dictionary", "custom")
        assertNull(policy().identifySource(original))
        assertNull(policy().identifySource(File(filesDir, "superpacks/hmmdictionary/zh_CN_123")))
    }

    @Test fun usesCanonicalFilesDirectoryAsFixedRootEvenWhenCallerUsesAlias() {
        val original = original()
        put(custom(), "dictionary", "custom")
        put(original, "model", "official model")
        val alias = File(temporary.root, "files-alias")
        Files.createSymbolicLink(alias.toPath(), filesDir.toPath())
        val result = requireNotNull(rewrite(RedirectPolicy(alias) {}, original, "dictionary", "model"))
        assertEquals(filesDir.canonicalPath, result.root)
        assertEquals("custom", resolved(result, 0).readText())
        assertEquals("official model", resolved(result, 1).readText())
    }
}
