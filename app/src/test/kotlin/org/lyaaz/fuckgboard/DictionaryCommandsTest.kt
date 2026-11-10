package org.lyaaz.fuckgboard

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Runs production commands on real files, not source-text assertions or a fake shell runner. */
class DictionaryCommandsTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var data: File
    private lateinit var bin: File
    private var uid = -1
    private val directory get() = File(data, "files/fuckgboard/zh_CN")

    @Before fun prepare() {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue(File("/proc/self/fd").isDirectory)
        assumeTrue(Files.getFileStore(temporary.root.toPath()).supportsFileAttributeView("posix"))
        uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim().toInt()
        val gid = ProcessBuilder("id", "-g").start().inputStream.bufferedReader().readText().trim().toInt()
        // Gboard's primary gid equals its uid; keep real chown/chmod in these desktop tests.
        assumeTrue(uid == gid)
        data = temporary.newFolder("Gboard's data 中文")
        bin = temporary.newFolder("bin")
        File(bin, "restorecon").apply {
            writeText(
                "#!/bin/sh\n" +
                    "case \" \$* \" in *\" -D \"*) ;; *) exit 1 ;; esac\n" +
                    "for file in \"\$@\"; do :; done\n" +
                    "if [ \"\$file\" = . ]; then exit 0; fi\n" +
                    "exit \"\${FG_RESTORECON_STATUS:-0}\"\n",
            )
            check(setExecutable(true))
        }
    }

    private data class Result(val code: Int, val output: String)

    private fun run(command: String, restoreconStatus: Int = 0, shell: String = "/bin/sh"): Result {
        val builder = ProcessBuilder(shell, "-c", command).redirectErrorStream(true)
        builder.environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}"
        builder.environment()["FG_RESTORECON_STATUS"] = restoreconStatus.toString()
        val process = builder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return Result(process.waitFor(), output)
    }

    private fun source(contents: String = "new dictionary") = temporary.newFile().apply { writeText(contents) }

    private fun import(name: String, source: File, modified: Long? = null, language: String = "zh_CN") =
        DictionaryCommands.import(data.absolutePath, language, uid, name, source.absolutePath, modified)

    private fun list(language: String = "zh_CN") =
        DictionaryCommands.list(data.absolutePath, language, uid)

    private fun delete(name: String) = DictionaryCommands.delete(data.absolutePath, "zh_CN", uid, name)

    private fun expectFailure(command: String, code: String, restoreconStatus: Int = 0) {
        val result = run(command, restoreconStatus)
        assertTrue(result.output, result.code != 0)
        assertTrue(result.output, result.output.contains("FG_ERROR:$code"))
    }

    private fun assertNoTemporaryFiles() {
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(DictionaryCommands.TEMP_PREFIX) })
    }

    @Test fun validatesOnlyImmediateNamesAndHookLanguagePrefixes() {
        for (name in listOf("", ".", "..", "../x", "/x", "a/b", "a\\b", "a\u0000b", "a\nb", "a\tb", "a\u007fb", ".fuckgboard-import-private")) {
            assertFalse(name, DictionaryCommands.validName(name))
        }
        for (name in listOf("中文 空格", "-n", "a'b\"c", "$(id); * ?", "..ordinary", " trailing ")) {
            assertTrue(name, DictionaryCommands.validName(name))
        }
        for (language in listOf("ko", "zh_CN", "zh_TW", "zh_Hant_TW", "zh_Hant", "fil", "es_419")) {
            assertTrue(language, DictionaryCommands.validLanguage(language))
        }
        for (language in listOf("", "../zh_CN", "zh-CN", "ZH_CN", "zh_cn", "zh_CN_123", "zh__CN", "zh_HANT_TW")) {
            assertFalse(language, DictionaryCommands.validLanguage(language))
        }
    }

    @Test fun missingDirectoryListDoesNotCreateAnything() {
        val result = run(list())
        assertEquals(result.output, 0, result.code)
        assertEquals("", result.output)
        assertFalse(File(data, "files").exists())
        expectFailure(delete("missing"), "TARGET")
    }

    @Test fun preservesQuotesSpacesUnicodeAndOptionLikeNamesThroughImportListDelete() {
        val name = "-n 中文 ' \" $(touch escaped) ; * ? trailing "
        val input = source()
        val imported = run(import(name, input))
        assertEquals(imported.output, 0, imported.code)
        assertEquals(input.readText(), File(directory, name).readText())
        val listed = run(list())
        assertEquals(listed.output, 0, listed.code)
        assertEquals(name, listed.output.trimEnd('\n').substringAfter('\t'))
        assertFalse(File(directory, "escaped").exists())
        assertFalse(File(data, "escaped").exists())
        val deleted = run(delete(name))
        assertEquals(deleted.output, 0, deleted.code)
        assertFalse(File(directory, name).exists())
    }

    @Test fun createsPrivateDirectoriesAndFileAndReportsRealMtime() {
        val modified = 1_700_000_000_123L
        val result = run(import("dictionary", source(), modified))
        assertEquals(result.output, 0, result.code)
        for (path in listOf("files", "files/fuckgboard", "files/fuckgboard/zh_CN")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(File(data, path).toPath()))
        }
        val file = File(directory, "dictionary")
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()))
        assertEquals(uid, (Files.getAttribute(file.toPath(), "unix:uid") as Number).toInt())
        assertEquals(modified, file.lastModified())
        assertEquals("1700000000\tdictionary\n", run(list()).output)
        file.setLastModified(1_710_000_000_000L)
        assertEquals("1710000000\tdictionary\n", run(list()).output)
    }

    @Test fun mkshImportsOrdinarySourceWithoutRequiringAnOpenSourceDescriptor() {
        val shell = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .map { File(it, "mksh") }.firstOrNull { it.canExecute() }?.absolutePath
        assumeTrue("mksh is needed to exercise Android close-on-exec descriptor semantics", shell != null)
        val modified = 1_700_000_000_123L
        val input = source("mksh dictionary")
        val result = run(import("dictionary", input, modified), shell = requireNotNull(shell))
        assertEquals(result.output, 0, result.code)
        val file = File(directory, "dictionary")
        assertEquals(input.readText(), file.readText())
        assertEquals(modified, file.lastModified())
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()))
        assertEquals(uid, (Files.getAttribute(file.toPath(), "unix:uid") as Number).toInt())
        assertNoTemporaryFiles()
    }

    @Test fun importsThroughExistingMode771ParentWithoutChangingPermissions() {
        exerciseMode771Parent("/bin/sh")
    }

    @Test fun mkshImportsThroughExistingMode771ParentWithoutChangingPermissions() {
        val shell = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .map { File(it, "mksh") }.firstOrNull { it.canExecute() }?.absolutePath
        assumeTrue("mksh is needed to exercise Android octal permission semantics", shell != null)
        exerciseMode771Parent(requireNotNull(shell))
    }

    private fun exerciseMode771Parent(shell: String) {
        check(directory.mkdirs())
        val files = File(data, "files")
        Files.setPosixFilePermissions(files.toPath(), PosixFilePermissions.fromString("rwxrwx--x"))
        val before = Files.readAttributes(files.toPath(), "unix:uid,gid,mode")
        val input = source("dictionary through existing 0771 parent")
        val result = run(import("dictionary", input), shell = shell)
        assertEquals(result.output, 0, result.code)
        assertEquals(input.readText(), File(directory, "dictionary").readText())
        assertEquals(before, Files.readAttributes(files.toPath(), "unix:uid,gid,mode"))
        assertNoTemporaryFiles()
    }

    @Test fun replacesSameNameWithoutChangingExistingDirectoryModes() {
        check(directory.mkdirs())
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwxr-x---"))
        File(directory, "dictionary").writeText("old")
        val result = run(import("dictionary", source("replacement")))
        assertEquals(result.output, 0, result.code)
        assertEquals("replacement", File(directory, "dictionary").readText())
        assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(directory.toPath()))
        assertNoTemporaryFiles()
    }

    @Test fun importsWithoutRelabelingExistingParentDirectories() {
        check(directory.mkdirs())
        val sibling = File(data, "files/unrelated").apply { writeText("untouched") }
        val before = Files.readAttributes(sibling.toPath(), "unix:uid,gid,mode,lastModifiedTime")
        File(bin, "restorecon").writeText(
            "#!/bin/sh\nfor file in \"\$@\"; do :; done\n[ \"\$file\" != . ] || exit 1\nexit 0\n",
        )
        val result = run(import("dictionary", source("replacement")))
        assertEquals(result.output, 0, result.code)
        assertEquals("replacement", File(directory, "dictionary").readText())
        assertEquals("untouched", sibling.readText())
        assertEquals(before, Files.readAttributes(sibling.toPath(), "unix:uid,gid,mode,lastModifiedTime"))
    }

    @Test fun copyFailurePreservesOldContentAndCleansTemporaryFile() {
        check(directory.mkdirs())
        val old = File(directory, "dictionary").apply { writeText("old") }
        expectFailure(import(old.name, File(temporary.root, "missing source")), "COPY")
        assertEquals("old", old.readText())
        assertNoTemporaryFiles()
    }

    @Test fun restoreconFailurePreservesOldContentAndIsNotSuccess() {
        check(directory.mkdirs())
        val old = File(directory, "dictionary").apply { writeText("old") }
        expectFailure(import(old.name, source()), "RESTORECON", restoreconStatus = 1)
        assertEquals("old", old.readText())
        assertNoTemporaryFiles()
    }

    @Test fun newDirectoryRestoreconFailureIsNotSuccess() {
        File(bin, "restorecon").writeText("#!/bin/sh\nexit 1\n")
        expectFailure(import("dictionary", source()), "RESTORECON", restoreconStatus = 1)
        assertFalse(File(directory, "dictionary").exists())
    }

    @Test fun rejectsMalformedSecurityContexts() {
        for (context in listOf("u:object_r:app_data_file:s0", "u:object_r:app_data_file:s0:c1,c258,c512,c768", "u:object_r:app_data_file:s0:c0.c1023")) {
            assertTrue(context, DictionaryCommands.validSecurityContext(context))
        }
        for (context in listOf("", "u:r:ksu:s0", "u:object_r:app_data_file:s0\n", "u:object_r:app_data_file:s0;id", "../outside")) {
            assertFalse(context, DictionaryCommands.validSecurityContext(context))
        }
    }

    @Test fun explicitContextFailurePreservesOldContentAndExistingDirectories() {
        check(directory.mkdirs())
        val old = File(directory, "dictionary").apply { writeText("old") }
        val before = Files.readAttributes(directory.toPath(), "unix:uid,gid,mode")
        File(bin, "chcon").apply {
            writeText("#!/bin/sh\nexit 1\n")
            check(setExecutable(true))
        }
        val result = run(DictionaryCommands.import(
            data.absolutePath, "zh_CN", uid, old.name, source().absolutePath, null,
            securityContext = "u:object_r:app_data_file:s0:c1,c2",
        ))
        assertTrue(result.output, result.code != 0)
        assertTrue(result.output, result.output.contains("FG_ERROR:RESTORECON"))
        assertEquals("old", old.readText())
        assertEquals(before, Files.readAttributes(directory.toPath(), "unix:uid,gid,mode"))
        assertNoTemporaryFiles()
    }

    @Test fun everyManagedDirectoryComponentRejectsSymlinks() {
        val outside = temporary.newFolder("outside")
        File(outside, "sentinel").writeText("unchanged")
        for (component in listOf("files", "files/fuckgboard", "files/fuckgboard/zh_CN")) {
            val root = temporary.newFolder()
            val link = File(root, component)
            check(requireNotNull(link.parentFile).exists() || requireNotNull(link.parentFile).mkdirs())
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            expectFailure(DictionaryCommands.list(root.absolutePath, "zh_CN", uid), "LINK")
            expectFailure(DictionaryCommands.import(root.absolutePath, "zh_CN", uid, "sentinel", source().absolutePath, null), "LINK")
            expectFailure(DictionaryCommands.delete(root.absolutePath, "zh_CN", uid, "sentinel"), "LINK")
            assertEquals("unchanged", File(outside, "sentinel").readText())
        }
    }

    @Test fun listSkipsLinksDirectoriesFifosAndReservedOrControlNames() {
        check(directory.mkdirs())
        File(directory, "ordinary").writeText("dictionary")
        check(File(directory, "directory").mkdir())
        Files.createSymbolicLink(File(directory, "link").toPath(), File(directory, "ordinary").toPath())
        Files.createSymbolicLink(File(directory, "dangling").toPath(), File(directory, "absent").toPath())
        File(directory, "bad\nname").writeText("ignored")
        File(directory, "back\\slash").writeText("ignored")
        File(directory, DictionaryCommands.TEMP_PREFIX + "leftover").writeText("ignored")
        val fifo = ProcessBuilder("mkfifo", File(directory, "fifo").absolutePath).start()
        assertEquals(0, fifo.waitFor())
        val result = run(list())
        assertEquals(result.output, 0, result.code)
        assertEquals(listOf("ordinary"), result.output.lineSequence().filter { it.isNotEmpty() }.map { it.substringAfter('\t') }.toList())
        for (name in listOf("link", "dangling")) {
            expectFailure(import(name, source()), "LINK")
            expectFailure(delete(name), "LINK")
        }
        for (name in listOf("directory", "fifo")) {
            expectFailure(import(name, source()), "TARGET")
            expectFailure(delete(name), "TARGET")
        }
        assertTrue(Files.isSymbolicLink(File(directory, "link").toPath()))
        assertTrue(File(directory, "directory").isDirectory)
    }

    @Test fun refusesExistingDirectoryWithoutGboardTraversalRatherThanChangingIt() {
        check(directory.mkdirs())
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rw-------"))
        try {
            val result = run(import("dictionary", source()))
            assertTrue(result.output, result.code != 0)
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.toPath()))
        } finally {
            Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
        assertFalse(File(directory, "dictionary").exists())
    }
}
