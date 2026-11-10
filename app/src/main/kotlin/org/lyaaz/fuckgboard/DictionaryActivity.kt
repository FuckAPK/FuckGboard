package org.lyaaz.fuckgboard

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.MutableCreationExtras
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.lyaaz.ui.FilePreferenceItem
import org.lyaaz.ui.PreferenceGroup
import org.lyaaz.ui.SettingsList
import org.lyaaz.ui.SettingsLoading
import org.lyaaz.ui.SettingsScaffold
import org.lyaaz.ui.theme.AppTheme
import rikka.shizuku.Shizuku

class DictionaryActivity : ComponentActivity() {
    private lateinit var dictionaryViewModel: DictionaryViewModel
    private var binderReceivedBeforeViewModel = false
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (::dictionaryViewModel.isInitialized) {
            dictionaryViewModel.shizukuBinderReceived()
        } else {
            binderReceivedBeforeViewModel = true
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (::dictionaryViewModel.isInitialized) dictionaryViewModel.shizukuBinderDead()
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST && ::dictionaryViewModel.isInitialized) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                try {
                    ShizukuDictionaryExecutor.requireRoot(this)
                    dictionaryViewModel.shizukuPermissionGranted()
                } catch (error: Exception) {
                    dictionaryViewModel.shizukuAuthorizationFailed(error)
                }
            } else {
                val permanentlyDenied = runCatching {
                    Shizuku.shouldShowRequestPermissionRationale()
                }.getOrDefault(false)
                dictionaryViewModel.shizukuAuthorizationFailed(
                    IOException(getString(
                        if (permanentlyDenied) R.string.dictionary_shizuku_denied_permanently
                        else R.string.dictionary_shizuku_denied
                    ))
                )
            }
        }
    }
    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        dictionaryViewModel.documentsPicked(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        // Privileged picker context may come from saved state, never launch Intent extras.
        val extras = MutableCreationExtras(defaultViewModelCreationExtras).apply {
            this[DEFAULT_ARGS_KEY] = Bundle.EMPTY
        }
        dictionaryViewModel = ViewModelProvider(
            viewModelStore, defaultViewModelProviderFactory, extras,
        )[DictionaryViewModel::class.java]
        if (binderReceivedBeforeViewModel) dictionaryViewModel.shizukuBinderReceived()
        if (dictionaryViewModel.state.accessMode == DictionaryAccessMode.ROOT_SHIZUKU &&
            dictionaryViewModel.state.operation != DictionaryOperation.PICKING && !Shizuku.pingBinder()
        ) {
            dictionaryViewModel.shizukuBinderDead()
        }
        enableEdgeToEdge()
        setContent {
            AppTheme(applyStatusBarColor = true) {
                val state = dictionaryViewModel.state
                LaunchedEffect(state.notice) {
                    state.notice?.let {
                        Toast.makeText(applicationContext, it.text, Toast.LENGTH_SHORT).show()
                        dictionaryViewModel.consumeNotice(it.id)
                    }
                }
                DictionaryScreen(
                    state = state,
                    onAccessModeChange = dictionaryViewModel::changeAccessMode,
                    onAuthorizeShizuku = ::authorizeShizuku,
                    onLanguageChange = dictionaryViewModel::changeLanguageInput,
                    onApplyLanguage = dictionaryViewModel::applyLanguage,
                    onImport = {
                        if (dictionaryViewModel.beginPicking()) {
                            try {
                                documentPicker.launch(arrayOf("*/*"))
                            } catch (error: Exception) {
                                dictionaryViewModel.pickerFailed(error)
                            }
                        }
                    },
                    onRefresh = dictionaryViewModel::refresh,
                    onDelete = dictionaryViewModel::requestDelete,
                    onCancelDelete = dictionaryViewModel::cancelDelete,
                    onConfirmDelete = dictionaryViewModel::confirmDelete
                )
            }
        }
    }

    private fun authorizeShizuku() {
        if (!dictionaryViewModel.beginShizukuAuthorization()) return
        try {
            // Asking for permission is explicit and only useful for a Root-started server.
            when {
                !Shizuku.pingBinder() ->
                    throw IOException(getString(R.string.authorization_error_shizuku_unavailable))
                Shizuku.isPreV11() || Shizuku.getVersion() < 13 ->
                    throw IOException(getString(R.string.authorization_error_shizuku_version))
                Shizuku.getUid() != 0 ->
                    throw IOException(getString(R.string.authorization_error_shizuku_root))
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    ShizukuDictionaryExecutor.requireRoot(this)
                    dictionaryViewModel.shizukuPermissionGranted()
                }
                Shizuku.shouldShowRequestPermissionRationale() ->
                    throw IOException(getString(R.string.dictionary_shizuku_denied_permanently))
                else -> Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            }
        } catch (error: Exception) {
            dictionaryViewModel.shizukuAuthorizationFailed(error)
        }
    }

    override fun onResume() {
        super.onResume()
        // A permission result can arrive while an Activity is being recreated.
        if (dictionaryViewModel.state.operation != DictionaryOperation.AUTHORIZING) return
        try {
            ShizukuDictionaryExecutor.requireRoot(this)
            dictionaryViewModel.shizukuPermissionGranted()
        } catch (error: Exception) {
            dictionaryViewModel.shizukuAuthorizationFailed(error)
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    private companion object {
        const val SHIZUKU_PERMISSION_REQUEST = 6321
    }
}

@Composable
internal fun DictionaryScreen(
    state: DictionaryUiState,
    onAccessModeChange: (DictionaryAccessMode) -> Unit,
    onAuthorizeShizuku: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onApplyLanguage: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    val idle = state.operation == DictionaryOperation.IDLE
    val locale = Locale.getDefault()
    val timeZone = TimeZone.getDefault()
    val dateFormat = remember(locale, timeZone.id) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", locale).apply { this.timeZone = timeZone }
    }
    SettingsScaffold(
        title = stringResource(R.string.dictionary_title),
        actions = {
            IconButton(onClick = onRefresh, enabled = idle) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dictionary_refresh))
            }
        }
    ) {
        SettingsList {
            item(key = "authorization") {
                PreferenceGroup {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DictionaryAccessMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.accessMode == mode,
                                    enabled = idle,
                                    onClick = { onAccessModeChange(mode) },
                                    label = {
                                        Text(stringResource(
                                            when (mode) {
                                                DictionaryAccessMode.DIRECT_ROOT -> R.string.dictionary_access_direct_root
                                                DictionaryAccessMode.ROOT_SHIZUKU -> R.string.dictionary_access_root_shizuku
                                            }
                                        ))
                                    }
                                )
                            }
                        }
                        if (state.accessMode == DictionaryAccessMode.ROOT_SHIZUKU) {
                            Text(
                                stringResource(R.string.dictionary_access_shizuku_hint),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = onAuthorizeShizuku, enabled = idle) {
                                Text(stringResource(R.string.dictionary_shizuku_authorize))
                            }
                        }
                    }
                }
            }
            item(key = "language") {
                PreferenceGroup {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = state.languageInput,
                            onValueChange = onLanguageChange,
                            enabled = idle,
                            singleLine = true,
                            label = { Text(stringResource(R.string.dictionary_language)) },
                            supportingText = { Text(stringResource(R.string.dictionary_language_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onApplyLanguage,
                                enabled = idle && state.languageInput.isNotBlank() &&
                                    state.languageInput.trim() != state.language
                            ) {
                                Text(stringResource(R.string.dictionary_apply_language))
                            }
                            Button(onClick = onImport, enabled = idle, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.dictionary_import))
                            }
                        }
                    }
                }
            }
            item(key = "target") {
                PreferenceGroup(highlighted = true) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.dictionary_target), style = MaterialTheme.typography.titleMedium)
                        SelectionContainer {
                            Text(
                                state.snapshot?.directory ?: stringResource(R.string.dictionary_target_pending),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            stringResource(R.string.dictionary_restart_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            state.error?.let { error ->
                item(key = "error") {
                    PreferenceGroup {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.dictionary_error_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            SelectionContainer {
                                Text(error, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item(key = "installed") {
                Text(
                    stringResource(R.string.dictionary_installed),
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val progressText = when (state.operation) {
                DictionaryOperation.REFRESHING -> R.string.dictionary_loading
                DictionaryOperation.PICKING -> R.string.dictionary_picking
                DictionaryOperation.IMPORTING -> R.string.dictionary_importing
                DictionaryOperation.DELETING -> R.string.dictionary_deleting
                DictionaryOperation.AUTHORIZING -> R.string.dictionary_shizuku_authorizing
                else -> null
            }
            if (progressText != null) {
                item(key = "progress") {
                    SettingsLoading(stringResource(progressText))
                }
            }
            val files = state.snapshot?.files.orEmpty()
            if (files.isEmpty() && state.snapshot != null && progressText == null) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.dictionary_empty),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(files, key = { "file:${it.name}" }) { file ->
                val modified = remember(file.modifiedMillis, dateFormat) {
                    dateFormat.format(Date(file.modifiedMillis))
                }
                PreferenceGroup {
                    FilePreferenceItem(
                        name = file.name,
                        details = modified,
                        deleteContentDescription = stringResource(R.string.dictionary_delete_action, file.name),
                        onDelete = { onDelete(file.name) },
                        enabled = idle
                    )
                }
            }
        }
    }
    if (state.operation == DictionaryOperation.CONFIRMING_DELETE && state.pendingDelete != null) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.dictionary_delete_title)) },
            text = { Text(stringResource(R.string.dictionary_delete_message, state.pendingDelete)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) {
                    Text(stringResource(R.string.dictionary_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) { Text(stringResource(R.string.dictionary_cancel)) }
            }
        )
    }
}

@Composable
private fun DictionaryPreviewContent(state: DictionaryUiState) {
    AppTheme {
        DictionaryScreen(
            state = state,
            onAccessModeChange = {},
            onAuthorizeShizuku = {},
            onLanguageChange = {},
            onApplyLanguage = {},
            onImport = {},
            onRefresh = {},
            onDelete = {},
            onCancelDelete = {},
            onConfirmDelete = {}
        )
    }
}

private val previewSnapshot = DictionarySnapshot(
    directory = "files/fuckgboard/zh_CN",
    files = listOf(
        DictionaryFile("zh_CN.dict", 1767225600000L),
        DictionaryFile("custom_words.dict", 1767312000000L)
    )
)

@Preview(name = "Dictionaries", locale = "zh-rCN", showBackground = true)
@Composable
private fun DictionaryScreenPreview() {
    DictionaryPreviewContent(DictionaryUiState(snapshot = previewSnapshot))
}

@Preview(name = "Empty dictionaries", locale = "zh-rCN", showBackground = true)
@Composable
private fun DictionaryEmptyPreview() {
    DictionaryPreviewContent(DictionaryUiState(snapshot = previewSnapshot.copy(files = emptyList())))
}

@Preview(name = "Delete confirmation", locale = "zh-rCN", showBackground = true)
@Composable
private fun DictionaryDeletePreview() {
    DictionaryPreviewContent(
        DictionaryUiState(
            snapshot = previewSnapshot,
            operation = DictionaryOperation.CONFIRMING_DELETE,
            pendingDelete = previewSnapshot.files.first().name
        )
    )
}

@Preview(name = "Operation error", locale = "zh-rCN", showBackground = true)
@Composable
private fun DictionaryErrorPreview() {
    DictionaryPreviewContent(DictionaryUiState(error = stringResource(R.string.dictionary_error_unknown)))
}

@Preview(name = "Root Shizuku authorization", locale = "zh-rCN", showBackground = true)
@Composable
private fun DictionaryShizukuPreview() {
    DictionaryPreviewContent(
        DictionaryUiState(accessMode = DictionaryAccessMode.ROOT_SHIZUKU)
    )
}
