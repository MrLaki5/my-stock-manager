package com.mrlaki5.mystockmanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrlaki5.mystockmanager.work.GenerationAlerts
import com.mrlaki5.mystockmanager.work.GenerationFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class GenerationAlertsViewModel @Inject constructor(
    private val alerts: GenerationAlerts,
) : ViewModel() {
    val failures: StateFlow<List<GenerationFailure>> = alerts.failures

    fun dismiss() = alerts.dismiss()
}

/** Shown above every screen, since background generation can fail wherever the user is. */
@Composable
fun GenerationFailureDialog(viewModel: GenerationAlertsViewModel = hiltViewModel()) {
    val failures by viewModel.failures.collectAsStateWithLifecycle()
    if (failures.isEmpty()) return

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text("Metadata generation did not finish") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                failures.forEach { failure ->
                    val count = failure.imageIds.size
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(failure.reason, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "$count image${if (count == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "These images were returned to the state they had before. Select them and generate again once the problem is fixed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("OK") } },
    )
}
