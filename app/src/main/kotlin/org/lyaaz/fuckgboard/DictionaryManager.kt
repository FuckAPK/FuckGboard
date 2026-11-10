package org.lyaaz.fuckgboard

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DictionaryFile(val name: String, val modifiedMillis: Long)
data class DictionarySnapshot(val directory: String, val files: List<DictionaryFile>)
data class DictionaryImportResult(val imported: Int, val failures: List<String>)

/** SAF streams stay in app cache until complete; only root commands touch Gboard's data. */
class DictionaryManager(
    context: Context,
    private val executor: DictionaryExecutor = DirectRootDictionaryExecutor(context),
) : Closeable {
    private val context = context.applicationContext

    suspend fun list(language: String): DictionarySnapshot = withContext(Dispatchers.IO) {
        val target = target(language)
        val lines = execute(DictionaryRequest(DictionaryAction.LIST, target, language))
        val files = lines.mapNotNull { line ->
            val separator = line.indexOf('\t')
            if (separator <= 0) throw error(R.string.dictionary_error_io)
            val name = line.substring(separator + 1)
            if (!DictionaryCommands.validName(name)) return@mapNotNull null
            val seconds = line.substring(0, separator).toLongOrNull()
                ?: throw error(R.string.dictionary_error_io)
            if (seconds !in Long.MIN_VALUE / 1000..Long.MAX_VALUE / 1000) {
                throw error(R.string.dictionary_error_io)
            }
            DictionaryFile(name, seconds * 1000)
        }.sortedBy { it.name }
        DictionarySnapshot("${target.dataDir}/files/fuckgboard/$language", files)
    }

    suspend fun import(language: String, uris: List<Uri>): DictionaryImportResult =
        withContext(Dispatchers.IO) {
            val target = target(language)
            var imported = 0
            val failures = ArrayList<String>()
            for (uri in uris) {
                var label = uri.toString()
                var staging: File? = null
                try {
                    val document = document(uri)
                    label = document.name
                    if (!DictionaryCommands.validName(document.name)) {
                        throw error(R.string.dictionary_error_name)
                    }
                    val stage = File.createTempFile("dictionary-", ".tmp", context.cacheDir)
                    staging = stage
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw error(R.string.dictionary_error_provider)
                    input.use { source ->
                        FileOutputStream(stage).use { destination ->
                            source.copyTo(destination)
                            destination.flush()
                            destination.fd.sync()
                        }
                    }
                    execute(DictionaryRequest(
                        DictionaryAction.IMPORT, target, language, document.name,
                        stage.absolutePath, document.modifiedMillis,
                    ))
                    imported++
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    val reason = context.getString(R.string.dictionary_error_io)
                    val detail = exception.message?.let { "（$it）" }.orEmpty()
                    failures.add("$label：$reason$detail")
                } finally {
                    staging?.delete()
                }
            }
            DictionaryImportResult(imported, failures)
        }

    suspend fun delete(language: String, name: String): Unit = withContext(Dispatchers.IO) {
        if (!DictionaryCommands.validName(name)) throw error(R.string.dictionary_error_name)
        val target = target(language)
        execute(DictionaryRequest(DictionaryAction.DELETE, target, language, name))
        Unit
    }

    private data class Document(val name: String, val modifiedMillis: Long?)

    private fun target(language: String): DictionaryTarget {
        if (!DictionaryCommands.validLanguage(language)) throw error(R.string.dictionary_error_language)
        val application = try {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(GBOARD_PACKAGE, 0)
        } catch (exception: PackageManager.NameNotFoundException) {
            throw error(R.string.dictionary_error_gboard, exception)
        } catch (exception: SecurityException) {
            throw error(R.string.dictionary_error_gboard, exception)
        }
        val directory = application.dataDir
        if (directory.isNullOrEmpty() || !directory.startsWith('/') || directory.contains('\u0000') ||
            application.uid < 0) {
            throw error(R.string.dictionary_error_gboard)
        }
        return DictionaryTarget(directory.trimEnd('/'), application.uid)
    }

    private fun execute(request: DictionaryRequest): List<String> {
        val result = try {
            executor.execute(request)
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            if (exception is IOException) throw exception
            throw error(R.string.dictionary_error_io, exception)
        }
        if (result.code != 0) {
            val marker = result.err.firstOrNull { it.startsWith("FG_ERROR:") }?.substringAfter(':')
            val resource = when (marker) {
                "DIRECTORY" -> R.string.dictionary_error_directory
                "ACCESS" -> R.string.dictionary_error_access
                "LINK" -> R.string.dictionary_error_link
                "TARGET" -> R.string.dictionary_error_target
                "METADATA" -> R.string.dictionary_error_metadata
                "RESTORECON" -> R.string.dictionary_error_restorecon
                "COPY" -> R.string.dictionary_error_copy
                "RENAME" -> R.string.dictionary_error_rename
                else -> R.string.dictionary_error_io
            }
            val detail = if (resource == R.string.dictionary_error_io) {
                " (exit=${result.code})" + result.err.takeLast(3).joinToString("\n", prefix = "\n")
            } else ""
            throw IOException(context.getString(resource) + detail)
        }
        return result.out
    }

    /** Close on an IO thread: the owned shell or user-service may still be finishing a request. */
    override fun close() = executor.close()

    private fun document(uri: Uri): Document {
        val resolver = context.contentResolver
        fun query(withTime: Boolean): Document {
            val columns = if (withTime) {
                arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            } else {
                arrayOf(OpenableColumns.DISPLAY_NAME)
            }
            return resolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) throw error(R.string.dictionary_error_provider)
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex < 0 || cursor.isNull(nameIndex)) throw error(R.string.dictionary_error_provider)
                val name = cursor.getString(nameIndex) ?: throw error(R.string.dictionary_error_provider)
                val timeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val modified = if (timeIndex >= 0 && !cursor.isNull(timeIndex)) {
                    cursor.getLong(timeIndex).takeIf { it > 0 }
                } else null
                Document(name, modified)
            } ?: throw error(R.string.dictionary_error_provider)
        }
        return try {
            query(withTime = true)
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            // Some providers reject the optional column rather than returning null.
            try {
                query(withTime = false)
            } catch (fallback: Exception) {
                if (fallback is CancellationException) throw fallback
                throw error(R.string.dictionary_error_provider, fallback)
            }
        }
    }

    private fun error(resource: Int, cause: Throwable? = null): IOException =
        IOException(context.getString(resource), cause)

    private companion object {
        const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
    }
}
