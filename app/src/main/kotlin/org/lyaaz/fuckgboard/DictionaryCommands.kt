package org.lyaaz.fuckgboard

/** The same commands are exercised by desktop shell fixtures; no Android dependencies here. */
internal object DictionaryCommands {
    const val TEMP_PREFIX = ".fuckgboard-import-"
    private val languagePattern = Regex("[a-z]{2,3}(?:_[A-Z][a-z]{3})?(?:_(?:[A-Z]{2}|[0-9]{3}))?")
    private val contextPattern = Regex("u:object_r:[A-Za-z0-9_]+:s0(?::c[0-9]+(?:[,.]c[0-9]+)*)?")

    fun validLanguage(value: String): Boolean = languagePattern.matches(value)

    fun validSecurityContext(value: String): Boolean = contextPattern.matches(value)

    fun validName(value: String): Boolean = value.isNotEmpty() && value != "." && value != ".." &&
        !value.startsWith(TEMP_PREFIX) && value.none { it == '/' || it == '\\' || it.isISOControl() }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun preamble(
        dataDir: String,
        language: String,
        uid: Int,
        create: Boolean,
        missingIsEmpty: Boolean = false,
        relabel: String = "restorecon -D -F",
    ): String {
        require(validLanguage(language))
        require(dataDir.startsWith('/') && !dataDir.contains('\u0000'))
        require(uid >= 0)
        return """
            (
            export LC_ALL=C
            fail() { printf 'FG_ERROR:%s\n' "${'$'}1" >&2; exit 1; }
            cd -P ${quote(dataDir)} || fail DIRECTORY
            for part in files fuckgboard ${quote(language)}; do
                [ ! -L "./${'$'}part" ] || fail LINK
                created=0
                if [ ! -e "./${'$'}part" ]; then
                    ${if (create) "(umask 077; mkdir -- \"./\$part\") || fail DIRECTORY; created=1" else if (missingIsEmpty) "exit 0" else "fail TARGET"}
                fi
                [ -d "./${'$'}part" ] || fail DIRECTORY
                expected="${'$'}(pwd -P)/${'$'}part"
                cd -P "./${'$'}part" || fail DIRECTORY
                [ "${'$'}(pwd -P)" = "${'$'}expected" ] || fail LINK
                if [ "${'$'}created" = 1 ]; then
                    chmod 0700 -- . || fail METADATA
                    ${if (create) "$relabel . || fail RESTORECON" else ""}
                    chown $uid:$uid -- . || fail METADATA
                fi
            ${if (create) """
                owner=${'$'}(stat -c '%u' -- .) || fail IO
                group=${'$'}(stat -c '%g' -- .) || fail IO
                mode=${'$'}(stat -c '%a' -- .) || fail IO
                # Match octal execute digits; mksh's leading-zero arithmetic is decimal.
                if [ "${'$'}owner" = $uid ]; then execute_pattern='*[1357]??'
                elif [ "${'$'}group" = $uid ]; then execute_pattern='*[1357]?'
                else execute_pattern='*[1357]'; fi
                case "${'$'}mode" in ${'$'}execute_pattern) ;; *) fail ACCESS ;; esac
            """.trimIndent() else ""}
            done
        """.trimIndent() + "\n"
    }

    fun list(dataDir: String, language: String, uid: Int): String =
        preamble(dataDir, language, uid, false, missingIsEmpty = true) + """
            for file in ./* ./.[!.]* ./..?*; do
                [ ! -L "${'$'}file" ] && [ -f "${'$'}file" ] || continue
                name=${'$'}{file#./}
                case "${'$'}name" in
                    ${TEMP_PREFIX}*|*\\*|*[[:cntrl:]]*) continue ;;
                esac
                modified=${'$'}(stat -c '%Y' -- "${'$'}file") || fail IO
                printf '%s\t%s\n' "${'$'}modified" "${'$'}name"
            done
            )
        """.trimIndent()

    fun delete(dataDir: String, language: String, uid: Int, name: String): String {
        require(validName(name))
        return preamble(dataDir, language, uid, false) + """
            target=${quote("./$name")}
            [ ! -L "${'$'}target" ] || fail LINK
            [ -f "${'$'}target" ] || fail TARGET
            rm -- "${'$'}target" || fail IO
            )
        """.trimIndent()
    }

    fun import(
        dataDir: String,
        language: String,
        uid: Int,
        name: String,
        source: String,
        modifiedMillis: Long?,
        sourceDescriptor: Int? = null,
        securityContext: String? = null,
    ): String {
        require(validName(name))
        require(source.startsWith('/') && !source.contains('\u0000'))
        require(sourceDescriptor == null || sourceDescriptor == 4)
        require(securityContext == null || validSecurityContext(securityContext))
        // Mirror aliases lack restorecon's package MLS lookup; use the verified CE root label.
        val relabel = if (securityContext == null) "restorecon -D -F" else "chcon ${quote(securityContext)}"
        val relabelTemp = if (securityContext == null) "$relabel \"${'$'}tmp\""
            else "$relabel /proc/self/fd/3 3<&3"
        val timestamp = modifiedMillis?.takeIf { it > 0 }?.let {
            // Android toybox and GNU touch both accept Unix timestamps with fractional seconds.
            val seconds = it / 1000
            val fraction = (it % 1000).toString().padStart(3, '0')
            "touch -m -d ${quote("@$seconds.$fraction")} /proc/self/fd/3 3<&3 || fail METADATA"
        }.orEmpty()
        return preamble(dataDir, language, uid, true, relabel = relabel) + """
            target=${quote("./$name")}
            check_target() {
                [ ! -L "${'$'}target" ] || fail LINK
                if [ -e "${'$'}target" ]; then [ -f "${'$'}target" ] || fail TARGET; fi
            }
            check_target
            tmp=${'$'}(mktemp './${TEMP_PREFIX}XXXXXXXXXX') || fail IO
            trap '[ -z "${'$'}tmp" ] || rm -f -- "${'$'}tmp"' EXIT
            trap 'exit 1' HUP INT TERM
            [ ! -L "${'$'}tmp" ] && [ -f "${'$'}tmp" ] || fail LINK
            # Open without truncation, then verify the pinned descriptor before writing.
            # mksh marks exec-opened descriptors close-on-exec; duplicate at each external use.
            exec 3<> "${'$'}tmp" || fail IO
            identity=${'$'}(stat -L -c '%d:%i' /proc/self/fd/3 3<&3) || fail IO
            check_temp() {
                [ ! -L "${'$'}tmp" ] && [ -f "${'$'}tmp" ] || fail LINK
                [ "${'$'}(readlink /proc/self/fd/3 3<&3)" = "${'$'}(pwd -P)/${'$'}{tmp#./}" ] || fail LINK
                [ "${'$'}(stat -c '%d:%i' -- "${'$'}tmp")" = "${'$'}identity" ] || fail LINK
                [ "${'$'}(stat -L -c '%h' /proc/self/fd/3 3<&3)" = 1 ] || fail LINK
            }
            check_temp
            cat -- ${quote(source)} >&3${if (sourceDescriptor == 4) " 4<&4" else ""} || fail COPY
            chmod 0600 /proc/self/fd/3 3<&3 || fail METADATA
            check_temp
            $timestamp
            $relabelTemp || fail RESTORECON
            check_temp
            chown $uid:$uid /proc/self/fd/3 3<&3 || fail METADATA
            check_temp
            check_target
            # -T prevents a raced-in destination directory from becoming a container.
            mv -fT -- "${'$'}tmp" "${'$'}target" || fail RENAME
            tmp=
            exec 3>&-
            )
        """.trimIndent()
    }
}
