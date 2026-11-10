package org.lyaaz.fuckgboard

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictionaryServiceValidationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = DictionaryTarget("/data/user/10/com.google.android.inputmethod.latin", 1010123)

    @Test fun rejectsOtherPackagesUsersAndUids() {
        val request = DictionaryRequest(DictionaryAction.LIST, target, "zh_CN")
        DictionaryServiceValidation.validate(request, target)
        for (changed in listOf(
            target.copy(dataDir = "/data/user/10/another.package"),
            target.copy(dataDir = "/data/user/0/com.google.android.inputmethod.latin"),
            target.copy(dataDir = target.dataDir + "/../another.package"),
            target.copy(uid = 0),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                DictionaryServiceValidation.validate(request.copy(target = changed), target)
            }
        }
    }

    @Test fun rejectsInvalidLanguageNamesAndIrrelevantFields() {
        val list = DictionaryRequest(DictionaryAction.LIST, target, "ko")
        val delete = list.copy(action = DictionaryAction.DELETE, name = "dictionary")
        val import = delete.copy(action = DictionaryAction.IMPORT, source = "/cache/dictionary-a.tmp")
        for (valid in listOf(list, delete, import, import.copy(modifiedMillis = 1L))) {
            DictionaryServiceValidation.validate(valid, target)
        }
        for (invalid in listOf(
            list.copy(language = "../ko"), list.copy(name = "x"),
            list.copy(source = "/etc/passwd"), list.copy(modifiedMillis = 1L),
            delete.copy(name = "../outside"), delete.copy(name = "/outside"),
            delete.copy(name = "a\nb"), delete.copy(name = ".fuckgboard-import-private"),
            delete.copy(source = "/etc/passwd"), delete.copy(modifiedMillis = 1L),
            import.copy(name = ".."), import.copy(source = null),
            import.copy(modifiedMillis = -1L), import.copy(modifiedMillis = 0L),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                DictionaryServiceValidation.validate(invalid, target)
            }
        }
    }

    @Test fun acceptsOnlyImmediateCacheStagingPaths() {
        val cache = temporary.newFolder("app", "cache")
        val source = File(cache, "dictionary-123.tmp").apply { writeText("dictionary") }
        assertEquals(source, DictionaryServiceValidation.stagingFile(source.absolutePath, cache))
        for (invalid in listOf(
            "/etc/passwd", "dictionary-123.tmp", source.absolutePath + "/../dictionary-123.tmp",
            File(cache, "../dictionary-123.tmp").absolutePath,
            File(cache, "sub/dictionary-123.tmp").absolutePath,
            File(cache.parentFile, "cache-other/dictionary-123.tmp").absolutePath,
            File(cache, "dictionary-.tmp").absolutePath,
            File(cache, "dictionary-123.txt").absolutePath,
            File(cache, "unrelated.tmp").absolutePath,
            File(cache, "dictionary-a\n.tmp").absolutePath,
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                DictionaryServiceValidation.stagingFile(invalid, cache)
            }
        }
    }

    private fun shellUid(): Int {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue(File("/proc/self/fd").isDirectory)
        assumeTrue(Files.getFileStore(temporary.root.toPath()).supportsFileAttributeView("posix"))
        val process = ProcessBuilder("id", "-u").start()
        val uid = process.inputStream.bufferedReader().use { it.readText().trim().toInt() }
        assertEquals(0, process.waitFor())
        return uid
    }

    private fun runCommand(
        command: String,
        bin: File? = null,
        restoreconStatus: Int = 0,
        shell: String = "/bin/sh",
    ): Pair<Int, String> {
        val builder = ProcessBuilder(shell, "-c", command).redirectErrorStream(true)
        if (bin != null) builder.environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}"
        builder.environment()["FG_RESTORECON_STATUS"] = restoreconStatus.toString()
        val process = builder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }

    private fun runImportValidation(source: File, app: File): Pair<Int, String> {
        val uid = shellUid()
        val request = DictionaryRequest(
            DictionaryAction.IMPORT, target.copy(dataDir = File(app, "missing-gboard").absolutePath),
            "ko", "dictionary", source.absolutePath,
        )
        val command = DictionaryServiceValidation.importCommand(request, app, uid)
        return runCommand(command)
    }

    private fun rejectsSource(source: File, app: File) {
        val (code, output) = runImportValidation(source, app)
        assertTrue(output, code != 0)
        assertTrue(output, output.contains("FG_ERROR:SOURCE"))
    }

    @Test fun rejectsSourceLinksDanglingLinksAndNonRegularFilesInRealShell() {
        val app = temporary.newFolder("app")
        val cache = File(app, "cache").apply { mkdir() }
        val outside = temporary.newFile("outside")
        val sourceLink = File(cache, "dictionary-link.tmp")
        Files.createSymbolicLink(sourceLink.toPath(), outside.toPath())
        rejectsSource(sourceLink, app)
        val dangling = File(cache, "dictionary-dangling.tmp")
        Files.createSymbolicLink(dangling.toPath(), File(app, "missing").toPath())
        rejectsSource(dangling, app)
        val hardLink = File(cache, "dictionary-hardlink.tmp")
        Files.createLink(hardLink.toPath(), outside.toPath())
        rejectsSource(hardLink, app)
        val directory = File(cache, "dictionary-directory.tmp").apply { mkdir() }
        rejectsSource(directory, app)
    }

    @Test fun rejectsCacheAndPackageDirectorySymlinksInRealShell() {
        val app = temporary.newFolder("app")
        val outside = temporary.newFolder("outside")
        File(outside, "dictionary-a.tmp").writeText("dictionary")
        val cache = File(app, "cache")
        Files.createSymbolicLink(cache.toPath(), outside.toPath())
        rejectsSource(File(cache, "dictionary-a.tmp"), app)
        val linkedApp = File(temporary.root, "linked-app")
        Files.createSymbolicLink(linkedApp.toPath(), app.toPath())
        rejectsSource(File(linkedApp, "cache/dictionary-a.tmp"), linkedApp)
    }

    @Test fun quotesStagingNamesAndPreservesCommandFailureStatus() {
        val app = temporary.newFolder("app")
        val cache = File(app, "cache").apply { mkdir() }
        val source = File(cache, "dictionary-${'$'}(touch PWNED);'*.tmp").apply { writeText("dictionary") }
        val (code, output) = runImportValidation(source, app)
        // The real common command fails because this fixture has no Gboard target.
        // Source validation succeeded, and the wrapper must retain that nonzero status.
        assertTrue(output, code != 0)
        assertFalse(output, output.contains("FG_ERROR:SOURCE"))
        assertTrue(output, output.contains("FG_ERROR:DIRECTORY"))
        assertFalse(File(cache, "PWNED").exists())
    }

    @Test fun physicalAliasesImportWithoutAuthorizingPhysicalPathsOrChangingFailedReplacements() {
        exercisePhysicalAliases("/bin/sh")
    }

    @Test fun mkshPreservesPinnedSourceAndDestinationDescriptorsAcrossExternalCommands() {
        val shell = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .map { File(it, "mksh") }.firstOrNull { it.canExecute() }?.absolutePath
        assumeTrue("mksh is needed to exercise Android close-on-exec descriptor semantics", shell != null)
        exercisePhysicalAliases(requireNotNull(shell))
    }

    private fun exercisePhysicalAliases(shell: String) {
        val uid = shellUid()
        val groupProcess = ProcessBuilder("id", "-g").start()
        val gid = groupProcess.inputStream.bufferedReader().use { it.readText().trim().toInt() }
        assertEquals(0, groupProcess.waitFor())
        assumeTrue(uid == gid)

        val logicalApp = File(temporary.root, "logical own app")
        val logicalGboard = File(temporary.root, "logical Gboard")
        val physicalApp = temporary.newFolder("physical own app")
        val physicalCache = File(physicalApp, "cache").apply { check(mkdir()) }
        val physicalGboard = temporary.newFolder("physical Gboard")
        val bytes = byteArrayOf(0, 1, 10, 39, 127, -1)
        val source = File(physicalCache, "dictionary-quoted ' 中文.tmp").apply { writeBytes(bytes) }
        val logicalSource = File(logicalApp, "cache/${source.name}")
        val outside = temporary.newFile("dictionary-outside.tmp").apply { writeText("sentinel") }
        val outsideBefore = Files.readAttributes(outside.toPath(), "unix:uid,gid,mode,lastModifiedTime")
        val modified = 1_700_000_000_123L
        val request = DictionaryRequest(
            DictionaryAction.IMPORT, DictionaryTarget(logicalGboard.absolutePath, uid),
            "ko", "dictionary", logicalSource.absolutePath, modified,
        )
        val physicalTarget = request.target.copy(dataDir = physicalGboard.absolutePath)
        val bin = temporary.newFolder("alias-bin")
        File(bin, "restorecon").apply {
            writeText(
                "#!/bin/sh\n" +
                    "case \" \$* \" in *\" -D \"*) ;; *) exit 1 ;; esac\n" +
                    "for file in \"\$@\"; do :; done\n" +
                    "[ \"\$file\" != . ] || exit 0\n" +
                    "exit \"\${FG_RESTORECON_STATUS:-0}\"\n",
            )
            check(setExecutable(true))
        }
        fun command(candidate: DictionaryRequest = request) = DictionaryServiceValidation.importCommand(
            candidate, logicalApp, uid, targetOverride = physicalTarget, ownPhysicalData = physicalApp,
        )

        DictionaryServiceValidation.validate(request, request.target)
        assertThrows(IllegalArgumentException::class.java) {
            DictionaryServiceValidation.validate(request.copy(target = physicalTarget), request.target)
        }
        for (invalid in listOf(
            source.absolutePath,
            File(logicalApp, "cache/../cache/${source.name}").absolutePath,
            File(logicalApp, "cache/sub/../${source.name}").absolutePath,
            File(logicalApp, "cache/../../${outside.name}").absolutePath,
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                command(request.copy(source = invalid))
            }
        }

        val (code, output) = runCommand(command(), bin, shell = shell)
        assertEquals(output, 0, code)
        val directory = File(physicalGboard, "files/fuckgboard/ko")
        val imported = File(directory, "dictionary")
        assertArrayEquals(bytes, imported.readBytes())
        assertEquals(modified, imported.lastModified())
        assertFalse(logicalApp.exists())
        assertFalse(logicalGboard.exists())

        source.writeText("failed replacement")
        val (failedCode, failedOutput) = runCommand(command(), bin, restoreconStatus = 1, shell = shell)
        assertTrue(failedOutput, failedCode != 0)
        assertTrue(failedOutput, failedOutput.contains("FG_ERROR:RESTORECON"))
        assertArrayEquals(bytes, imported.readBytes())
        assertEquals(modified, imported.lastModified())
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(DictionaryCommands.TEMP_PREFIX) })
        assertEquals("sentinel", outside.readText())
        assertEquals(outsideBefore, Files.readAttributes(outside.toPath(), "unix:uid,gid,mode,lastModifiedTime"))
        assertFalse(logicalApp.exists())
        assertFalse(logicalGboard.exists())
    }

    @Test fun physicalSourceAliasesRetainLinkFileTypeAndUidChecks() {
        val uid = shellUid()
        val logicalApp = File(temporary.root, "logical-app")
        val physicalApp = temporary.newFolder("physical-app")
        val cache = File(physicalApp, "cache").apply { check(mkdir()) }
        val outside = temporary.newFile("outside-sentinel").apply { writeText("untouched") }
        val before = Files.readAttributes(outside.toPath(), "unix:uid,gid,mode,lastModifiedTime")
        val sourceLink = File(cache, "dictionary-link.tmp")
        Files.createSymbolicLink(sourceLink.toPath(), outside.toPath())
        val dangling = File(cache, "dictionary-dangling.tmp")
        Files.createSymbolicLink(dangling.toPath(), File(physicalApp, "absent").toPath())
        val hardLink = File(cache, "dictionary-hardlink.tmp")
        Files.createLink(hardLink.toPath(), outside.toPath())
        val directory = File(cache, "dictionary-directory.tmp").apply { check(mkdir()) }
        val ordinary = File(cache, "dictionary-ordinary.tmp").apply { writeText("dictionary") }
        val physicalTarget = target.copy(dataDir = File(temporary.root, "missing-target").absolutePath)
        fun rejects(file: File, physicalData: File = physicalApp, appUid: Int = uid) {
            val request = DictionaryRequest(
                DictionaryAction.IMPORT, target, "ko", "dictionary",
                File(logicalApp, "cache/${file.name}").absolutePath,
            )
            val command = DictionaryServiceValidation.importCommand(
                request, logicalApp, appUid, targetOverride = physicalTarget, ownPhysicalData = physicalData,
            )
            val (code, output) = runCommand(command)
            assertTrue(output, code != 0)
            assertTrue(output, output.contains("FG_ERROR:SOURCE"))
        }
        for (file in listOf(sourceLink, dangling, hardLink, directory)) rejects(file)
        rejects(ordinary, appUid = if (uid == 0) 1 else 0)
        val linkedApp = File(temporary.root, "linked-physical-app")
        Files.createSymbolicLink(linkedApp.toPath(), physicalApp.toPath())
        rejects(ordinary, physicalData = linkedApp)
        val appWithLinkedCache = temporary.newFolder("physical-app-with-linked-cache")
        Files.createSymbolicLink(File(appWithLinkedCache, "cache").toPath(), cache.toPath())
        rejects(ordinary, physicalData = appWithLinkedCache)
        assertEquals("untouched", outside.readText())
        assertEquals(before, Files.readAttributes(outside.toPath(), "unix:uid,gid,mode,lastModifiedTime"))
        assertFalse(logicalApp.exists())
        assertFalse(File(physicalTarget.dataDir).exists())
    }
}
