package org.lyaaz.fuckgboard

import java.io.Closeable

/** A choice made by the manager UI, never by the Gboard hook. */
enum class DictionaryAccessMode { DIRECT_ROOT, ROOT_SHIZUKU }
enum class DictionaryAction { LIST, IMPORT, DELETE }

data class DictionaryTarget(val dataDir: String, val uid: Int)

data class DictionaryRequest(
    val action: DictionaryAction,
    val target: DictionaryTarget,
    val language: String,
    val name: String? = null,
    val source: String? = null,
    val modifiedMillis: Long? = null,
) {
    internal fun command(
        sourceOverride: String? = source,
        targetOverride: DictionaryTarget = target,
        sourceDescriptorOverride: Int? = null,
        targetContextOverride: String? = null,
    ): String = when (action) {
        DictionaryAction.LIST -> DictionaryCommands.list(targetOverride.dataDir, language, targetOverride.uid)
        DictionaryAction.IMPORT -> DictionaryCommands.import(
            targetOverride.dataDir, language, targetOverride.uid, requireNotNull(name), requireNotNull(sourceOverride), modifiedMillis,
            sourceDescriptor = sourceDescriptorOverride,
            securityContext = targetContextOverride,
        )
        DictionaryAction.DELETE -> DictionaryCommands.delete(
            targetOverride.dataDir, language, targetOverride.uid, requireNotNull(name),
        )
    }
}

data class DictionaryCommandResult(val code: Int, val out: List<String>, val err: List<String>)

/** Implementations own only their private shell or this app's bound user service. */
interface DictionaryExecutor : Closeable {
    /** Blocking. Call only from the manager's IO dispatcher. */
    fun execute(request: DictionaryRequest): DictionaryCommandResult
}
