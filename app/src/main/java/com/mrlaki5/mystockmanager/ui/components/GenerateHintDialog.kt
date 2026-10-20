package com.mrlaki5.mystockmanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Asks for the optional hint before queueing; [replacesExisting] warns that current metadata will be overwritten. */
@Composable
fun GenerateHintDialog(
    count: Int,
    initialHint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    replacesExisting: Boolean = false,
) {
    var hint by remember { mutableStateOf(initialHint) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate for $count image${if (count == 1) "" else "s"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it },
                    label = { Text("Hint (optional)") },
                    placeholder = { Text("e.g. Kotor, Montenegro · 1972 Fiat 500 at a classic car show") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Context the model cannot see in the photo: where it was taken, what the " +
                        "subject is, the event it shows. Anything you enter is treated as fact " +
                        "and used in the title, description and keywords; a place it names also " +
                        "leads the caption. Leave it blank to skip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (replacesExisting) {
                    Text(
                        "This replaces the current title, description, keywords and category, including any hand edits.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hint) }) { Text("Generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
