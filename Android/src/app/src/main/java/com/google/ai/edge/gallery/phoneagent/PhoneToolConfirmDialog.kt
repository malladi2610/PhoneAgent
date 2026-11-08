package com.google.ai.edge.gallery.phoneagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Confirm card for instant outward phone actions (spec M1 §5.4), e.g.
 * "Send SMS to +49 151 …: 'Running 10 min late'". Dismissing counts as Deny.
 */
@Composable
fun PhoneToolConfirmDialog(summary: String, onResult: (allowed: Boolean) -> Unit) {
  AlertDialog(
    onDismissRequest = { onResult(false) },
    title = {
      Text(
        "Allow this action?",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(summary, style = MaterialTheme.typography.bodyLarge)
        Text(
          "PhoneAgent will do this right away.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = { Button(onClick = { onResult(true) }) { Text("Allow") } },
    dismissButton = { OutlinedButton(onClick = { onResult(false) }) { Text("Deny") } },
  )
}
