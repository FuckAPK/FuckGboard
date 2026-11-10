package org.lyaaz.fuckgboard

import androidx.lifecycle.SavedStateHandle

/** Only an outstanding picker request is saved, never URIs or an in-flight mutation. */
internal class DictionaryPickerState(private val savedState: SavedStateHandle) {
    data class Selection(val language: String, val mode: DictionaryAccessMode, val valid: Boolean = true)

    var pending: Selection? = restore()
        private set

    init {
        if (pending == null) savedState.remove<Any>(KEY)
    }

    fun begin(language: String, mode: DictionaryAccessMode) {
        check(pending == null) { "A dictionary picker is already pending" }
        pending = Selection(language, mode)
        save()
    }

    fun invalidate() {
        val current = pending ?: return
        if (!current.valid) return
        pending = current.copy(valid = false)
        save()
    }

    fun take(mode: DictionaryAccessMode): Selection? {
        val selection = pending?.takeIf { it.valid && it.mode == mode }
        clear()
        return selection
    }

    fun clear() {
        pending = null
        savedState.remove<Any>(KEY)
    }

    private fun save() {
        val selection = checkNotNull(pending)
        savedState[KEY] = arrayListOf(selection.language, selection.mode.name, selection.valid.toString())
    }

    private fun restore(): Selection? {
        val values = savedState.get<Any>(KEY) as? List<*> ?: return null
        if (values.size != 3) return null
        val language = values[0] as? String ?: return null
        val mode = DictionaryAccessMode.entries.firstOrNull { it.name == values[1] } ?: return null
        val valid = (values[2] as? String)?.toBooleanStrictOrNull() ?: return null
        return Selection(language, mode, valid)
    }

    private companion object {
        const val KEY = "dictionary.pending_picker"
    }
}
