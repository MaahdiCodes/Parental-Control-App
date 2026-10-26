package com.tvguardian.parent.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.tvguardian.core.PairingCodes
import com.tvguardian.parent.devices.DevicesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun AddTvDialog(familyId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("Living room TV") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsedCode = PairingCodes.parse(code)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add a TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Open TV Guardian on the TV. It shows a QR code and a 6-digit code.")
                OutlinedButton(onClick = {
                    error = null
                    // Google's code scanner: no camera permission needed, it runs its own scanning screen.
                    val scanner = GmsBarcodeScanning.getClient(
                        context,
                        GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
                    )
                    scope.launch {
                        try {
                            val raw = scanner.startScan().await().rawValue.orEmpty()
                            PairingCodes.parse(raw)?.let { code = it }
                                ?: run { error = "That isn't a TV Guardian QR code." }
                        } catch (e: CancellationException) {
                            // Scan screen closed without scanning.
                        } catch (e: Exception) {
                            error = "Couldn't scan: ${e.message}. You can type the code instead."
                        }
                    }
                }) { Text("Scan QR code on TV") }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("6-digit code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name for this TV") },
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && parsedCode != null && name.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        runCatching { DevicesRepository.pair(familyId, parsedCode!!, name.trim()) }
                            .onSuccess { onDismiss() }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Pairing…" else "Pair") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
