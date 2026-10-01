package com.vibemusic.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.vibemusic.android.R
import com.vibemusic.android.data.LANG_KEY
import com.vibemusic.android.data.appDataStore
import com.vibemusic.android.ui.PlayerViewModel
import kotlinx.coroutines.launch

/** Настройки: язык интерфейса, Deezer. */
@Composable
fun SettingsScreen(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deezerArl by viewModel.deezerArl.collectAsState()
    var showDeezer by remember { mutableStateOf(false) }
    var deezerArlInput by remember { mutableStateOf("") }

    // Текущий язык читаем из DataStore (переключение = recreate активности).
    val lang by context.appDataStore.data.collectAsState(initial = "system")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        )

        // ---------- Язык ----------
        Text(
            stringResource(R.string.settings_lang),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        listOf(
            "system" to stringResource(R.string.lang_system),
            "ru" to stringResource(R.string.lang_ru),
            "en" to stringResource(R.string.lang_en),
        ).forEach { (code, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
            ) {
                RadioButton(
                    selected = lang == code,
                    onClick = {
                        scope.launch {
                            context.appDataStore.edit { it[LANG_KEY] = code }
                            (context as? android.app.Activity)?.recreate()
                        }
                    },
                )
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---------- Deezer ----------
        Text(
            stringResource(R.string.settings_deezer),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (deezerArl.isBlank()) stringResource(R.string.deezer_disconnected)
                    else stringResource(R.string.deezer_connected),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (deezerArl.isNotBlank()) {
                    Text(
                        stringResource(R.string.deezer_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { deezerArlInput = ""; showDeezer = true }) {
                Text(
                    if (deezerArl.isBlank()) stringResource(R.string.deezer_connect)
                    else stringResource(R.string.deezer_change),
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    if (showDeezer) {
        AlertDialog(
            onDismissRequest = { showDeezer = false },
            title = { Text(stringResource(R.string.deezer_connect_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.deezer_howto),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = deezerArlInput,
                        onValueChange = { deezerArlInput = it },
                        label = { Text("ARL") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDeezerArl(deezerArlInput.trim())
                    showDeezer = false
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeezer = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
