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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrlaki5.mystockmanager.generation.GenerationProvider
import com.mrlaki5.mystockmanager.ondevice.ModelState
import com.mrlaki5.mystockmanager.ondevice.OnDeviceModel
import com.mrlaki5.mystockmanager.openai.OpenAiModels
import com.mrlaki5.mystockmanager.openai.VisionPrompt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val provider by viewModel.provider.collectAsStateWithLifecycle()
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
            val uriHandler = LocalUriHandler.current
            Text("Generate metadata with", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GenerationProvider.entries.forEach { candidate ->
                    FilterChip(
                        selected = candidate == provider,
                        onClick = { viewModel.setProvider(candidate) },
                        label = { Text(candidate.label) },
                    )
                }
            }
            when (provider) {
                GenerationProvider.OPENAI -> {
                    OpenAiSection(viewModel)
                    PromptSection(viewModel)
                }
                GenerationProvider.ON_DEVICE -> OnDeviceSection(viewModel)
            }

            HorizontalDivider()
            NextcloudSection(viewModel)

            HorizontalDivider()
            Hint("MyStockManager is open source under the MIT license.")
            Row {
                TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) { Text("Privacy policy") }
                TextButton(onClick = { uriHandler.openUri(SOURCE_CODE_URL) }) { Text("Source code") }
            }
        }
    }
}

private const val OPENAI_API_KEY_HELP_URL =
    "https://help.openai.com/en/articles/4936850-where-do-i-find-my-openai-api-key"

private const val NEXTCLOUD_APP_PASSWORD_HELP_URL =
    "https://docs.nextcloud.com/server/latest/user_manual/en/session_management.html"

private const val PRIVACY_POLICY_URL =
    "https://github.com/MrLaki5/my-stock-manager/blob/main/PRIVACY.md"

private const val SOURCE_CODE_URL = "https://github.com/MrLaki5/my-stock-manager"

@Composable
private fun OpenAiSection(viewModel: SettingsViewModel) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val reasoningEffort by viewModel.reasoningEffort.collectAsStateWithLifecycle()

    SectionTitle("OpenAI", OPENAI_API_KEY_HELP_URL, "How to get an OpenAI API key")
    OutlinedTextField(
        value = apiKey,
        onValueChange = viewModel::setApiKey,
        label = { Text("OpenAI API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Hint("Stored encrypted on this device. Sent only to api.openai.com.")

    Text("Model", style = MaterialTheme.typography.titleSmall)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        viewModel.availableModels.forEach { candidate ->
            FilterChip(
                selected = candidate == model,
                onClick = { viewModel.setModel(candidate) },
                label = { Text(candidate.id) },
            )
        }
    }
    Hint("${OpenAiModels.LUNA.id} is much cheaper per image; ${OpenAiModels.SOL.id} tends to produce stronger keywords.")

    Text("Reasoning", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        model.efforts.forEach { effort ->
            FilterChip(
                selected = effort == reasoningEffort,
                onClick = { viewModel.setReasoningEffort(effort) },
                label = { Text(effort.label) },
            )
        }
    }
    Hint("Higher reasoning can improve results but costs more and takes longer.")
}

@Composable
private fun OnDeviceSection(viewModel: SettingsViewModel) {
    val state by viewModel.modelState.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf(false) }

    SectionTitle("On-device models", OnDeviceModel.PAGE_URL, "About the on-device models")
    Hint(
        "LFM2.5-VL by Liquid AI writes the title and description, and SigLIP 2 by Google picks the keywords. " +
            "Both run on this phone: free, no account, and photos never leave the device. About 10 to 20 seconds " +
            "per image; the text is simpler than OpenAI's."
    )
    viewModel.lowMemoryGb?.let {
        Text(
            "This phone has %.1f GB of memory. The models need about 1 GB of it and may be slow or fail here.".format(it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    when (val current = state) {
        ModelState.Unsupported -> Text(
            "On-device generation needs a 64-bit processor, which this phone does not have.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ModelState.Missing -> {
            Button(onClick = viewModel::downloadModel) { Text("Download models (%.1f GB)".format(OnDeviceModel.TOTAL_BYTES / 1e9)) }
            Hint("Downloads once, over Wi-Fi only, from Hugging Face. LFM2.5-VL is under the LFM Open License.")
        }
        is ModelState.Downloading -> {
            LinearProgressIndicator(
                progress = { (current.bytes.toFloat() / current.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (current.waitingForWifi) "Waiting for Wi-Fi…"
                    else "%.2f of %.2f GB".format(current.bytes / 1e9, current.total / 1e9),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::cancelModelDownload) { Text("Cancel") }
            }
        }
        ModelState.Verifying -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Checking the download…", style = MaterialTheme.typography.bodyMedium)
        }
        ModelState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Models ready", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirmingDelete = true }) { Text("Delete models") }
        }
        is ModelState.Failed -> {
            Text(current.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Button(onClick = viewModel::downloadModel) { Text("Retry download") }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete models?") },
            text = { Text("Frees %.1f GB. You will need to download them again to generate on this phone.".format(OnDeviceModel.TOTAL_BYTES / 1e9)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.deleteModel()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PromptSection(viewModel: SettingsViewModel) {
    val prompt by viewModel.systemPrompt.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var confirmingReset by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { editing = true }) { Text("Edit system prompt") }
        TextButton(
            onClick = { confirmingReset = true },
            enabled = prompt != VisionPrompt.DEFAULT_SYSTEM,
        ) { Text("Reset to default") }
    }
    Hint("Customize how titles, descriptions and keywords are written.")

    if (editing) {
        var draft by remember { mutableStateOf(prompt) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("System prompt") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    minLines = 8,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    viewModel.setSystemPrompt(draft)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Reset prompt?") },
            text = { Text("Your custom prompt will be replaced with the built-in one.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    viewModel.resetSystemPrompt()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmingReset = false }) { Text("Cancel") } },
        )
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

    SectionTitle("NextCloud sync (optional)", NEXTCLOUD_APP_PASSWORD_HELP_URL, "How to create a NextCloud app password")
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
    Hint("Create an app password in NextCloud → Settings → Security. Stored encrypted on this device.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::saveAccount, enabled = dirty && draft.canSave && !busy) {
            Text("Save & test")
        }
        if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }

    SwitchRow(
        title = "Sync events to NextCloud",
        subtitle = if (hasAccount) "Uploads changes from this phone. Deleting here also deletes there."
        else "Save & test an account first.",
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
    Hint("Sync now: re-uploads anything missing or changed to NextCloud.")
    OutlinedButton(
        onClick = { confirmingPull = true },
        enabled = hasAccount && !pulling && !dirty,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Pull from NextCloud") }
    Hint("Pull: downloads the NextCloud folder to this phone, e.g. after a reinstall.")

    if (confirmingPull) {
        AlertDialog(
            onDismissRequest = { confirmingPull = false },
            title = { Text("Pull from NextCloud?") },
            text = {
                Text(
                    "Downloads everything missing here. Images on both sides are replaced with " +
                        "the cloud version. Nothing on this phone is deleted."
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
}

@Composable
private fun SectionTitle(title: String, helpUrl: String, helpDescription: String) {
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { uriHandler.openUri(helpUrl) }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = helpDescription,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
