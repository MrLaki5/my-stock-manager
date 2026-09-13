package com.mrlaki5.mystockmanager.ui.images

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.metadata.model.MAX_KEYWORDS
import com.mrlaki5.mystockmanager.metadata.model.MIN_KEYWORDS
import com.mrlaki5.mystockmanager.ui.components.ImageStateBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageDetailScreen(
    onBack: () -> Unit,
    viewModel: ImageDetailViewModel = hiltViewModel(),
) {
    val image by viewModel.image.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val dirty by viewModel.dirty.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // An edit lives only in the draft until it is saved, so leaving would drop it with
    // nothing to show for the work. Both ways out are guarded, not just the back gesture.
    var confirmingLeave by remember { mutableStateOf(false) }
    val leave = { if (dirty) confirmingLeave = true else onBack() }
    BackHandler(enabled = dirty) { confirmingLeave = true }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text("Discard changes?") },
            text = {
                Text(
                    "These edits have not been written into the image yet. Leaving now " +
                        "loses them."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLeave = false
                    viewModel.discard()
                    onBack()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) { Text("Keep editing") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(image?.displayName ?: "Image", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val uri = image?.mediaStoreUri
                    if (uri != null) {
                        IconButton(onClick = { openInGallery(context, uri) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in gallery")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (dirty) {
                SaveBar(
                    canSave = draft?.canSave == true && !busy,
                    onDiscard = viewModel::discard,
                    onSave = viewModel::save,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val current = image
        if (current == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Preview(current)

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ImageStateBadge(state = current.state)
                Text(
                    "${current.widthPx} × ${current.heightPx}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            current.generationError?.takeIf { current.state == ImageState.GENERATION_FAILED }
                ?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    )
                }

            HorizontalDivider()

            val editable = draft
            when {
                // Nothing has been generated, so there is nothing to edit. Writing
                // metadata by hand from scratch is a different feature from correcting
                // what the model produced.
                current.title == null -> NoMetadata(
                    Modifier.fillMaxWidth().padding(16.dp, 24.dp),
                )

                editable != null -> MetadataEditor(
                    draft = editable,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Preview(image: ImageEntity) {
    // Falls back to square only if the stored dimensions are unusable; tall shots are
    // clamped so the metadata below stays reachable without a long scroll.
    val ratio = if (image.widthPx > 0 && image.heightPx > 0) {
        (image.widthPx.toFloat() / image.heightPx).coerceIn(0.6f, 2.0f)
    } else {
        1f
    }
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.Black),
    ) {
        AsyncImage(
            model = image.mediaStoreUri?.toUri(),
            contentDescription = image.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MetadataEditor(
    draft: MetadataDraft,
    viewModel: ImageDetailViewModel,
    modifier: Modifier = Modifier,
) {
    var editingKeyword by remember { mutableStateOf<Int?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = draft.title,
            onValueChange = viewModel::setTitle,
            label = { Text("Title") },
            isError = draft.title.isBlank(),
            supportingText = {
                Text(
                    if (draft.title.isBlank()) {
                        "A title is required"
                    } else {
                        "${draft.title.length} characters"
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.description,
            onValueChange = viewModel::setDescription,
            label = { Text("Description") },
            isError = draft.description.isBlank(),
            minLines = 3,
            supportingText = {
                if (draft.description.isBlank()) Text("A description is required")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        KeywordSection(
            keywords = draft.keywords,
            onEdit = { editingKeyword = it },
            onRemove = viewModel::removeKeyword,
            onAdd = viewModel::addKeyword,
        )

        OutlinedTextField(
            value = draft.category,
            onValueChange = viewModel::setCategory,
            label = { Text("Category") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    editingKeyword?.let { index ->
        if (index in draft.keywords.indices) {
            KeywordDialog(
                initial = draft.keywords[index],
                position = index,
                total = draft.keywords.size,
                onDismiss = { editingKeyword = null },
                onConfirm = {
                    viewModel.updateKeyword(index, it)
                    editingKeyword = null
                },
                onDelete = {
                    viewModel.removeKeyword(index)
                    editingKeyword = null
                },
                onMove = { by ->
                    viewModel.moveKeyword(index, by)
                    editingKeyword = index + by
                },
            )
        }
    }
}

@Composable
private fun KeywordSection(
    keywords: List<String>,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: (String) -> Unit,
) {
    var pending by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Keywords",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${keywords.size} / $MAX_KEYWORDS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            if (keywords.size < MIN_KEYWORDS) {
                "Both agencies want at least $MIN_KEYWORDS. Tap a keyword to edit it — " +
                    "order matters, leading keywords are weighted more heavily."
            } else {
                "Tap a keyword to edit, reorder or remove it. Order matters: leading " +
                    "keywords are weighted more heavily."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (keywords.size < MIN_KEYWORDS) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            keywords.forEachIndexed { index, keyword ->
                SuggestionChip(
                    onClick = { onEdit(index) },
                    label = { Text(keyword) },
                    icon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove $keyword",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(onClick = { onRemove(index) }),
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(),
                )
            }
        }

        OutlinedTextField(
            value = pending,
            onValueChange = { pending = it },
            label = { Text("Add a keyword") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    onAdd(pending)
                    pending = ""
                },
            ),
            trailingIcon = {
                IconButton(
                    onClick = {
                        onAdd(pending)
                        pending = ""
                    },
                    enabled = pending.isNotBlank(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add keyword")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun KeywordDialog(
    initial: String,
    position: Int,
    total: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var value by remember(initial, position) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit keyword") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Keyword") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Position ${position + 1} of $total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onMove(-1) }, enabled = position > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Move earlier")
                    }
                    IconButton(onClick = { onMove(1) }, enabled = position < total - 1) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Move later")
                    }
                }
                TextButton(onClick = onDelete) {
                    Text("Remove keyword", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text("Done")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SaveBar(canSave: Boolean, onDiscard: () -> Unit, onSave: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            // The app draws edge-to-edge, so the bar clears the navigation buttons itself.
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Unsaved changes", modifier = Modifier.weight(1f))
            TextButton(onClick = onDiscard) { Text("Discard") }
            Button(onClick = onSave, enabled = canSave) { Text("Save") }
        }
    }
}

@Composable
private fun NoMetadata(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No metadata yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Select this image in the event and tap Generate metadata. Once it has a " +
                "title, description and keywords you can correct any of them here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Hands the album copy to whatever the user uses to view photos. The URI is already a
 * MediaStore item, so it needs no FileProvider grant — only a read permission on the
 * receiving side, which the flag supplies.
 */
private fun openInGallery(context: android.content.Context, mediaStoreUri: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(mediaStoreUri.toUri(), "image/jpeg")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { if (it !is ActivityNotFoundException) throw it }
}
