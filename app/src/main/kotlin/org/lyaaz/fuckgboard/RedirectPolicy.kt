package org.lyaaz.fuckgboard

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Selects per-entry filesystem paths without modifying either pack. */
class RedirectPolicy(
    filesDir: File,
    private val report: (String) -> Unit,
) {
    private val root = filesDir.canonicalFile.toPath()
    private val resourceRoot = File(root.toFile(), "superpacks/hmmdictionary").canonicalFile
    private val customLocation = File(root.toFile(), "fuckgboard")
    private val customRoot = customLocation.canonicalFile
    private val superpacksRoot = File(root.toFile(), "superpacks").canonicalFile.toPath()

    class Source internal constructor(val directory: File, val language: String)

    /** Classifies only canonical immediate children in the managed versioned namespace. */
    fun identifySource(directory: File): Source? {
        val original = directory.canonicalFile
        if (original.parentFile != resourceRoot || !original.toPath().startsWith(root)) return null
        val language = languagePrefix(original.name) ?: return null
        return Source(original, language)
    }

    /** Null leaves the original message, including native handling of official paths, untouched. */
    fun rewriteFor(source: Source, scheme: DataSchemeCodec.Scheme): ByteArray? {
        val original = source.directory
        val language = source.language
        report("filesystem scheme original=$original language=$language")
        val candidate = File(customLocation, language)
        return try {
            if (hasSymlink(candidate, root)) throw IOException("custom directory contains symlink: $candidate")
            if (!candidate.exists()) return null
            if (!candidate.isDirectory) throw IOException("custom path is not a directory: $candidate")
            val custom = candidate.canonicalFile
            if (custom.name != language || custom.parentFile != customRoot ||
                !custom.toPath().startsWith(root) || custom.toPath().startsWith(superpacksRoot)) {
                throw IOException("custom directory escapes its language-specific location: $custom")
            }
            val filenames = ArrayList<String>(scheme.entries.size)
            var selected = 0
            for (entry in scheme.entries) {
                val relative = entry.filename
                // The codec validates relative filenames; retain the official lexical path,
                // since canonicalizing it could change its symlink semantics.
                val official = File(original, relative)
                val replacement = customFile(candidate, custom, relative)
                val chosen = if (replacement == null) {
                    official
                } else {
                    selected++
                    replacement
                }
                filenames.add(root.relativize(chosen.toPath()).joinToString("/"))
            }
            if (selected == 0) return null
            report("overlay original=$original custom=$custom entries=$selected/${scheme.entries.size} root=$root")
            scheme.rewritePaths(root.toString(), filenames)
        } catch (e: IOException) {
            report("unchanged: ${e.message}")
            null
        } catch (e: SecurityException) {
            report("unchanged: ${e.message}")
            null
        }
    }

    private fun customFile(candidate: File, custom: File, relative: String): File? {
        val file = File(candidate, relative)
        return try {
            val reason = when {
                hasSymlink(file, candidate.toPath()) -> "symlink in custom path"
                !file.exists() -> return null
                !file.isFile -> "custom path is not a regular file"
                !file.canRead() -> "custom file is unreadable"
                file.length() == 0L -> "custom file is empty"
                else -> {
                    val canonical = file.canonicalFile
                    if (canonical.toPath().startsWith(custom.toPath())) return canonical
                    "custom path escapes directory"
                }
            }
            report("official fallback: $relative ($reason)")
            null
        } catch (e: IOException) {
            report("official fallback: $relative (${e.message})")
            null
        } catch (e: SecurityException) {
            report("official fallback: $relative (${e.message})")
            null
        }
    }

    private fun hasSymlink(file: File, boundary: Path): Boolean {
        var path = file.toPath()
        while (path != boundary) {
            if (Files.isSymbolicLink(path)) return true
            path = path.parent ?: return true
        }
        return false
    }

    private fun languagePrefix(name: String): String? {
        val parts = name.split('_')
        val language = parts[0]
        if (parts.size < 2 || language.length !in 2..3 || language.any { it !in 'a'..'z' }) return null
        var end = 1
        val script = parts[end]
        if (script.length == 4 && script[0] in 'A'..'Z' && script.drop(1).all { it in 'a'..'z' }) end++
        if (end < parts.size) {
            val region = parts[end]
            if (region.length == 2 && region.all { it in 'A'..'Z' } ||
                region.length == 3 && region.all { it in '0'..'9' }) end++
        }
        if (end == parts.size || parts.any { it.isEmpty() } ||
            name.any { it != '_' && it != '-' && it != '.' && !it.isLetterOrDigit() }) return null
        return parts.take(end).joinToString("_")
    }
}
