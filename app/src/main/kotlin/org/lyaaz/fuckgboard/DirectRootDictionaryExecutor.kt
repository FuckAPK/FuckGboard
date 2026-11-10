package org.lyaaz.fuckgboard

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.IOException

/** Owns a private child shell; never changes libsu's process-wide default builder. */
class DirectRootDictionaryExecutor(context: Context) : DictionaryExecutor {
    private val context = context.applicationContext
    private var shell: Shell? = null
    private var closed = false

    @Synchronized
    override fun execute(request: DictionaryRequest): DictionaryCommandResult {
        check(!closed) { "Dictionary executor is closed" }
        val command = request.command()
        val current = shell?.takeIf { it.isAlive } ?: createShell().also { shell = it }
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = current.newJob().add(command).to(stdout, stderr).exec()
        return DictionaryCommandResult(result.code, stdout, stderr)
    }

    private fun createShell(): Shell {
        val created = try {
            // Select the child's existing global namespace; no mounts are changed.
            Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build()
        } catch (error: Exception) {
            throw IOException(context.getString(R.string.dictionary_error_root), error)
        }
        if (!created.isRoot) {
            created.close()
            throw IOException(context.getString(R.string.dictionary_error_root))
        }
        return created
    }

    @Synchronized
    override fun close() {
        closed = true
        shell?.close()
        shell = null
    }
}
