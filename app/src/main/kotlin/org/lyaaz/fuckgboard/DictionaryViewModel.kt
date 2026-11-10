package org.lyaaz.fuckgboard

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class DictionaryOperation {
    IDLE, REFRESHING, PICKING, IMPORTING, CONFIRMING_DELETE, DELETING, AUTHORIZING
}

internal data class DictionaryNotice(val id: Long, val text: String)

internal data class DictionaryUiState(
    val accessMode: DictionaryAccessMode = DictionaryAccessMode.DIRECT_ROOT,
    val language: String = "zh_CN",
    val languageInput: String = "zh_CN",
    val snapshot: DictionarySnapshot? = null,
    val operation: DictionaryOperation = DictionaryOperation.IDLE,
    val pendingDelete: String? = null,
    val error: String? = null,
    val notice: DictionaryNotice? = null
)

class DictionaryViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("dictionary_access", Application.MODE_PRIVATE)
    private val managerMutex = Mutex()
    private var manager: DictionaryManager? = null
    private var managerGeneration = -1L
    private var generation = 0L
    private val picker = DictionaryPickerState(savedState)
    private var noticeId = 0L

    internal var state by mutableStateOf(
        DictionaryUiState(
            accessMode = DictionaryAccessMode.entries.firstOrNull {
                it.name == preferences.getString("mode", null)
            } ?: DictionaryAccessMode.DIRECT_ROOT,
            language = picker.pending?.language ?: "zh_CN",
            languageInput = picker.pending?.language ?: "zh_CN",
            operation = if (picker.pending == null) DictionaryOperation.IDLE else DictionaryOperation.PICKING
        )
    )
        private set

    init {
        if (picker.pending == null) load(showNotice = false)
    }

    internal fun changeAccessMode(mode: DictionaryAccessMode) {
        if (state.operation != DictionaryOperation.IDLE || state.accessMode == mode) return
        preferences.edit().putString("mode", mode.name).apply()
        ++generation
        state = state.copy(accessMode = mode, snapshot = null, error = null, notice = null)
        load(showNotice = false)
    }

    internal fun beginShizukuAuthorization(): Boolean {
        if (state.accessMode != DictionaryAccessMode.ROOT_SHIZUKU ||
            state.operation != DictionaryOperation.IDLE
        ) return false
        state = state.copy(operation = DictionaryOperation.AUTHORIZING, error = null)
        return true
    }

    internal fun shizukuAuthorizationFailed(error: Exception) {
        if (state.accessMode != DictionaryAccessMode.ROOT_SHIZUKU ||
            state.operation != DictionaryOperation.AUTHORIZING
        ) return
        state = state.copy(operation = DictionaryOperation.IDLE, error = readable(error))
    }

    internal fun shizukuPermissionGranted() {
        if (state.accessMode != DictionaryAccessMode.ROOT_SHIZUKU) return
        if (state.operation == DictionaryOperation.AUTHORIZING) {
            state = state.copy(operation = DictionaryOperation.IDLE)
        }
        shizukuBinderReceived()
    }

    internal fun shizukuBinderReceived() {
        if (state.accessMode != DictionaryAccessMode.ROOT_SHIZUKU ||
            state.operation != DictionaryOperation.IDLE
        ) return
        ++generation
        state = state.copy(snapshot = null, error = null, notice = null)
        load(showNotice = false)
    }

    internal fun shizukuBinderDead() {
        if (state.accessMode != DictionaryAccessMode.ROOT_SHIZUKU) return
        val staleGeneration = generation++
        picker.invalidate()
        val operation = when (state.operation) {
            DictionaryOperation.AUTHORIZING, DictionaryOperation.CONFIRMING_DELETE -> DictionaryOperation.IDLE
            else -> state.operation
        }
        state = state.copy(
            snapshot = null,
            pendingDelete = null,
            operation = operation,
            error = getApplication<Application>().getString(R.string.authorization_error_shizuku_disconnected),
            notice = null
        )
        viewModelScope.launch(Dispatchers.IO) {
            managerMutex.withLock {
                if (managerGeneration <= staleGeneration) closeManager()
            }
        }
    }

    private suspend fun <T> withManager(
        mode: DictionaryAccessMode,
        requestGeneration: Long,
        action: suspend (DictionaryManager) -> T
    ): T = withContext(Dispatchers.IO) {
        managerMutex.withLock {
            if (managerGeneration != requestGeneration) closeManager()
            val current = manager ?: DictionaryManager(
                getApplication<Application>().applicationContext,
                when (mode) {
                    DictionaryAccessMode.DIRECT_ROOT ->
                        DirectRootDictionaryExecutor(getApplication<Application>().applicationContext)
                    DictionaryAccessMode.ROOT_SHIZUKU ->
                        ShizukuDictionaryExecutor(getApplication<Application>().applicationContext)
                }
            ).also {
                manager = it
                managerGeneration = requestGeneration
            }
            action(current)
        }
    }

    // Called only under managerMutex on IO, including after viewModelScope is cancelled.
    private fun closeManager() {
        val previous = manager
        manager = null
        managerGeneration = -1L
        runCatching { previous?.close() }
    }

    override fun onCleared() {
        super.onCleared()
        CoroutineScope(Dispatchers.IO).launch {
            managerMutex.withLock { closeManager() }
        }
    }

    internal fun changeLanguageInput(value: String) {
        if (state.operation == DictionaryOperation.IDLE) {
            state = state.copy(languageInput = value)
        }
    }

    internal fun applyLanguage() {
        if (state.operation != DictionaryOperation.IDLE) return
        val language = state.languageInput.trim()
        if (language.isEmpty() || language == state.language) return
        state = state.copy(language = language, languageInput = language, snapshot = null)
        load(showNotice = true)
    }

    internal fun refresh() {
        if (state.operation == DictionaryOperation.IDLE) load(showNotice = true)
    }

    private fun load(showNotice: Boolean) {
        val language = state.language
        val mode = state.accessMode
        val requestGeneration = generation
        state = state.copy(operation = DictionaryOperation.REFRESHING, error = null)
        viewModelScope.launch {
            try {
                val snapshot = withManager(mode, requestGeneration) { it.list(language) }
                if (generation == requestGeneration) {
                    state = state.copy(
                        snapshot = snapshot,
                        notice = if (showNotice) notice(R.string.dictionary_refreshed) else state.notice
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == requestGeneration) {
                    state = state.copy(error = readable(error), notice = notice(R.string.dictionary_failed))
                }
            } finally {
                state = state.copy(operation = DictionaryOperation.IDLE)
            }
        }
    }

    // Capture language and backend before SAF; the result may arrive after rotation or binder death.
    internal fun beginPicking(): Boolean {
        if (state.operation != DictionaryOperation.IDLE) return false
        picker.begin(state.language, state.accessMode)
        state = state.copy(operation = DictionaryOperation.PICKING)
        return true
    }

    internal fun pickerFailed(error: Exception) {
        if (state.operation != DictionaryOperation.PICKING) return
        picker.clear()
        state = state.copy(
            operation = DictionaryOperation.IDLE,
            error = readable(error),
            notice = notice(R.string.dictionary_failed)
        )
    }

    internal fun documentsPicked(uris: List<Uri>) {
        if (state.operation != DictionaryOperation.PICKING) return
        val selection = picker.take(state.accessMode)
        if (uris.isEmpty()) {
            state = state.copy(operation = DictionaryOperation.IDLE)
            if (state.snapshot == null) load(showNotice = false)
            return
        }
        if (selection == null) {
            state = state.copy(
                operation = DictionaryOperation.IDLE,
                error = getApplication<Application>().getString(R.string.dictionary_error_picker_expired),
                notice = notice(R.string.dictionary_failed)
            )
            return
        }
        val language = selection.language
        mutate(DictionaryOperation.IMPORTING, language) { current ->
            val result = current.import(language, uris)
            MutationOutcome(
                message = getApplication<Application>().getString(
                    R.string.dictionary_imported, result.imported, result.failures.size
                ),
                error = result.failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
            )
        }
    }

    internal fun requestDelete(name: String) {
        if (state.operation != DictionaryOperation.IDLE ||
            state.snapshot?.files?.none { it.name == name } != false
        ) return
        state = state.copy(operation = DictionaryOperation.CONFIRMING_DELETE, pendingDelete = name)
    }

    internal fun cancelDelete() {
        if (state.operation == DictionaryOperation.CONFIRMING_DELETE) {
            state = state.copy(operation = DictionaryOperation.IDLE, pendingDelete = null)
        }
    }

    internal fun confirmDelete() {
        if (state.operation != DictionaryOperation.CONFIRMING_DELETE) return
        val name = state.pendingDelete ?: return
        val language = state.language
        state = state.copy(pendingDelete = null)
        mutate(DictionaryOperation.DELETING, language) { current ->
            current.delete(language, name)
            MutationOutcome(getApplication<Application>().getString(R.string.dictionary_deleted))
        }
    }

    private data class MutationOutcome(val message: String, val error: String? = null)

    private fun mutate(
        operation: DictionaryOperation,
        language: String,
        action: suspend (DictionaryManager) -> MutationOutcome
    ) {
        val mode = state.accessMode
        val requestGeneration = generation
        state = state.copy(operation = operation, error = null)
        viewModelScope.launch {
            val outcome = try {
                withManager(mode, requestGeneration, action)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                MutationOutcome(
                    getApplication<Application>().getString(R.string.dictionary_failed),
                    readable(error)
                )
            }
            var snapshot = state.snapshot
            var refreshError: String? = null
            // Always refresh, including partial imports and failures after a filesystem change.
            try {
                if (generation == requestGeneration) {
                    snapshot = withManager(mode, requestGeneration) { it.list(language) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                refreshError = getApplication<Application>().getString(R.string.dictionary_refresh_failed) +
                    ": " + readable(error)
            }
            if (generation != requestGeneration) {
                state = state.copy(operation = DictionaryOperation.IDLE)
                return@launch
            }
            state = state.copy(
                snapshot = snapshot,
                operation = DictionaryOperation.IDLE,
                error = listOfNotNull(outcome.error, refreshError).takeIf { it.isNotEmpty() }
                    ?.joinToString("\n\n"),
                notice = DictionaryNotice(
                    ++noticeId,
                    if (refreshError == null) outcome.message else outcome.message + "\n" +
                        getApplication<Application>().getString(R.string.dictionary_refresh_failed)
                )
            )
        }
    }

    internal fun consumeNotice(id: Long) {
        if (state.notice?.id == id) state = state.copy(notice = null)
    }

    private fun notice(resource: Int) =
        DictionaryNotice(++noticeId, getApplication<Application>().getString(resource))

    private fun readable(error: Exception): String = error.message?.takeIf { it.isNotBlank() }
        ?: getApplication<Application>().getString(R.string.dictionary_error_unknown)
}
