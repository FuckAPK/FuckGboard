package org.lyaaz.fuckgboard

import java.io.File

/** Pure validation shared with local attack-path tests; the service also checks OS metadata. */
internal object DictionaryServiceValidation {
    fun validate(request: DictionaryRequest, expected: DictionaryTarget) {
        require(request.target == expected) { "Target does not match this user's Gboard" }
        require(DictionaryCommands.validLanguage(request.language))
        when (request.action) {
            DictionaryAction.LIST -> require(
                request.name == null && request.source == null && request.modifiedMillis == null,
            )
            DictionaryAction.DELETE -> require(
                request.name?.let(DictionaryCommands::validName) == true &&
                    request.source == null && request.modifiedMillis == null,
            )
            DictionaryAction.IMPORT -> {
                require(request.name?.let(DictionaryCommands::validName) == true)
                require(request.source != null)
                require(request.modifiedMillis == null || request.modifiedMillis > 0)
            }
        }
    }

    /** Manager staging is always an immediate cache child, not an arbitrary cache subtree. */
    fun stagingFile(source: String, cache: File): File {
        require(source.startsWith('/') && source.none { it.isISOControl() })
        val file = File(source)
        require(file.parent == cache.absolutePath)
        require(file.name.startsWith("dictionary-") && file.name.endsWith(".tmp"))
        require(file.name.length > "dictionary-.tmp".length)
        return file
    }

    /** Validates logical staging paths, then pins the source in the resolved physical cache. */
    fun importCommand(
        request: DictionaryRequest,
        ownData: File,
        appUid: Int,
        targetOverride: DictionaryTarget = request.target,
        ownPhysicalData: File = ownData,
        targetContext: String? = null,
    ): String {
        require(request.action == DictionaryAction.IMPORT && appUid >= 0)
        val cache = File(ownData, "cache")
        // Authorize the caller's logical path before using only its basename in the resolved cache.
        val source = stagingFile(requireNotNull(request.source), cache)
        val quote = DictionaryCommands::quote
        return """
            (
            export LC_ALL=C
            source_fail() { printf 'FG_ERROR:SOURCE\n' >&2; exit 1; }
            [ ! -L ${quote(ownPhysicalData.absolutePath)} ] && [ -d ${quote(ownPhysicalData.absolutePath)} ] || source_fail
            cd -P ${quote(ownPhysicalData.absolutePath)} || source_fail
            [ ! -L ./cache ] && [ -d ./cache ] || source_fail
            cache="${'$'}(pwd -P)/cache"
            cd -P ./cache || source_fail
            [ "${'$'}(pwd -P)" = "${'$'}cache" ] || source_fail
            source=${quote("./${source.name}")}
            [ ! -L "${'$'}source" ] && [ -f "${'$'}source" ] || source_fail
            identity=${'$'}(stat -c '%u:%h:%d:%i' -- "${'$'}source") || source_fail
            case "${'$'}identity" in $appUid:1:*) ;; *) source_fail ;; esac
            exec 4< "${'$'}source" || source_fail
            [ -f /proc/self/fd/4 ] || source_fail
            [ "${'$'}(stat -L -c '%u:%h:%d:%i' /proc/self/fd/4 4<&4)" = "${'$'}identity" ] || source_fail
            [ "${'$'}(readlink /proc/self/fd/4 4<&4)" = "${'$'}cache"/${quote(source.name)} ] || source_fail
            ${request.command(
                sourceOverride = "/proc/self/fd/4", targetOverride = targetOverride,
                sourceDescriptorOverride = 4, targetContextOverride = targetContext,
            )}
            )
        """.trimIndent()
    }
}
