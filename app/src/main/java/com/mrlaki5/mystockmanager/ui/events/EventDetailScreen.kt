package com.mrlaki5.mystockmanager.ui.events

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.ui.components.ImageStateBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    onBack: () -> Unit,
    viewModel: EventDetailViewModel = hiltViewModel(),
) {
    val event by viewModel.event.collectAsStateWithLifecycle()
    val images by viewModel.images.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    var askingLocation by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)
    ) { uris -> viewModel.importImages(uris) }

    if (askingLocation) {
        LocationDialog(
            count = selection.size,
            initialLocation = event?.location.orEmpty(),
            onDismiss = { askingLocation = false },
            onConfirm = {
                viewModel.generateSelected(it)
                askingLocation = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(event?.name ?: "Event") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (images.isNotEmpty()) {
                        TextButton(onClick = {
                            if (selection.isEmpty()) viewModel.selectAll() else viewModel.clearSelection()
                        }) {
                            Text(if (selection.isEmpty()) "Select all" else "Clear")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (selection.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add images") },
                )
            }
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                GenerateBar(
                    count = selection.size,
                    onGenerate = { askingLocation = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (images.isEmpty()) {
                EmptyEvent(Modifier.fillMaxSize())
            } else {
                StatusSummary(
                    images = images,
                    albumName = event?.name?.let(viewModel::albumNameFor) ?: "",
                    location = event?.location,
                    onSelectUngenerated = viewModel::selectUngenerated,
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(images, key = { it.id }) { image ->
                        ImageCell(
                            image = image,
                            selected = image.id in selection,
                            onClick = { viewModel.toggleSelection(image.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationDialog(
    count: Int,
    initialLocation: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var location by remember { mutableStateOf(initialLocation) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate for $count image${if (count == 1) "" else "s"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location (optional)") },
                    placeholder = { Text("e.g. Kotor, Montenegro") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The model cannot tell where a photo was taken. Anything you enter is " +
                        "treated as fact and used in the title, description and keywords — " +
                        "buyers search by place. Leave it blank to skip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(location) }) { Text("Generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StatusSummary(
    images: List<ImageEntity>,
    albumName: String,
    location: String?,
    onSelectUngenerated: () -> Unit,
) {
    val generated = images.count { it.state == ImageState.GENERATED }
    val pending = images.count {
        it.state == ImageState.FILED || it.state == ImageState.GENERATION_FAILED
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$generated of ${images.size} generated",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                buildString {
                    append("Album: ")
                    append(albumName)
                    if (!location.isNullOrBlank()) append("  ·  ").also { append(location) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pending > 0) {
            TextButton(onClick = onSelectUngenerated) { Text("Select $pending pending") }
        }
    }
}

@Composable
private fun ImageCell(
    image: ImageEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = image.mediaStoreUri?.toUri(),
            contentDescription = image.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        ImageStateBadge(
            state = image.state,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
        )

        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun GenerateBar(count: Int, onGenerate: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            // The app draws edge-to-edge, so the bar must clear the system
            // navigation buttons itself or it renders underneath them.
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$count selected", modifier = Modifier.weight(1f))
            Button(onClick = onGenerate) { Text("Generate metadata") }
        }
    }
}

@Composable
private fun EmptyEvent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No images yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add images to this event. They are copied into the StockReady album, " +
                    "visible in your gallery and in upload pickers right away. Your camera " +
                    "roll is left untouched.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The system picker caps multi-select anyway; this is just an explicit upper bound. */
private const val MAX_PICK = 100
