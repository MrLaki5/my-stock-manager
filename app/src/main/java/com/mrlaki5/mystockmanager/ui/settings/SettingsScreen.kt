package com.mrlaki5.mystockmanager.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("OpenAI", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = apiKey,
                onValueChange = viewModel::setApiKey,
                label = { Text("OpenAI API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(
                "Stored encrypted on this device with a Keystore-backed key, and excluded " +
                    "from backups. It is sent only to api.openai.com."
            )

            Text("Model", style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.availableModels.forEach { candidate ->
                    FilterChip(
                        selected = candidate == model,
                        onClick = { viewModel.setModel(candidate) },
                        label = { Text(candidate) },
                    )
                }
            }
            Hint("gpt-4o-mini is much cheaper per image; gpt-4o tends to produce stronger keywords.")

            HorizontalDivider()
            NextcloudSection(viewModel)
        }
    }
}

@Composable
private fun NextcloudSection(viewModel: SettingsViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val dirty by viewModel.dirty.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val hasAccount by viewModel.hasAccount.collectAsStateWithLifecycle()
    val syncEnabled by viewModel.syncEnabled.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val pulling by viewModel.pulling.collectAsStateWithLifecycle()
    var confirmingPull by remember { mutableStateOf(false) }

    Text("NextCloud sync", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = draft.server,
        onValueChange = viewModel::setServer,
        label = { Text("Server address") },
        placeholder = { Text("cloud.example.com") },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.login,
        onValueChange = viewModel::setLogin,
        label = { Text("Username") },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.appPassword,
        onValueChange = viewModel::setAppPassword,
        label = { Text("App password") },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.folder,
        onValueChange = viewModel::setFolder,
        label = { Text("Folder on NextCloud") },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Hint(
        "Create an app password in NextCloud under Settings → Security. It is stored " +
            "encrypted on this device and excluded from backups."
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::saveAccount, enabled = dirty && draft.canSave && !busy) {
            Text("Save & test")
        }
        TextButton(onClick = viewModel::discardAccount, enabled = dirty && !busy) { Text("Discard") }
        if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }

    SwitchRow(
        title = "Sync events to NextCloud",
        subtitle = if (hasAccount) "Uploads every event and keeps the cloud folder up to date."
        else "Save and test an account first.",
        checked = syncEnabled,
        enabled = hasAccount,
        onCheckedChange = viewModel::setSyncEnabled,
    )
    SwitchRow(
        title = "Wi-Fi only",
        subtitle = "Full-size photos use a lot of mobile data.",
        checked = wifiOnly,
        enabled = hasAccount,
        onCheckedChange = viewModel::setWifiOnly,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = viewModel::syncNow, enabled = syncEnabled) { Text("Sync now") }
    }
    Hint(
        "Changes sync on their own. Sync now also re-checks NextCloud: anything deleted or " +
            "changed there is uploaded again, and images it refused are retried."
    )
    OutlinedButton(
        onClick = { confirmingPull = true },
        enabled = hasAccount && !pulling && !dirty,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Pull from NextCloud") }
    Hint(
        "Downloads everything in ${draft.folder.ifBlank { "the sync folder" }} onto this phone, for example " +
            "after reinstalling. It only runs when you tap it."
    )

    if (confirmingPull) {
        AlertDialog(
            onDismissRequest = { confirmingPull = false },
            title = { Text("Pull from NextCloud?") },
            text = {
                Text(
                    "Every event and image on NextCloud that is missing here is downloaded. Where an " +
                        "image exists on both, the phone's copy is replaced with the cloud's version. " +
                        "Nothing on this phone is deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingPull = false
                    viewModel.pullNow()
                }) { Text("Pull") }
            },
            dismissButton = { TextButton(onClick = { confirmingPull = false }) { Text("Cancel") } },
        )
    }
    Hint(
        "Sync is one-way: NextCloud follows the app, and nothing comes back unless you tap Pull. " +
            "While sync is on, deleting an event or image also deletes it on NextCloud (into " +
            "its trash bin). Anything deleted while sync is off stays on the cloud. Files you " +
            "add to the folder yourself are never touched. Changing the server, user or folder " +
            "uploads everything again to the new place."
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
