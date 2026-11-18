package com.paperlight.reader.ui

import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.paperlight.reader.ai.GeminiAiClient
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.paperlight.reader.ai.GeminiApiKeyStore

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun GeminiApiKeyScreen(onClose: () -> Unit, onSaved: () -> Unit = {}) {
    val context = LocalContext.current
    val keyStore = remember(context) { GeminiApiKeyStore(context) }
    var key by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasKey by remember { mutableStateOf(keyStore.hasKey()) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Gemini AI settings") },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Back") } }
                )
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Add your Gemini API key to use AI-assisted website setup and note summaries. The key is encrypted on this device and sent only with requests to Google’s Gemini API.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "This uses your Gemini API project quota, not the message limit in the Gemini app. Free-tier data handling and limits are set by Google.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Model selection is automatic. The API resolves Gemini Flash to its current version.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
                    }) {
                        Text("Get an API key in AI Studio")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                    }
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.trim(); error = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (hasKey) "Replace saved API key" else "Gemini API key") },
                        singleLine = true,
                        isError = error != null,
                        supportingText = { error?.let { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    if (visible) "Hide API key" else "Show API key")
                            }
                        }
                    )
                    if (hasKey) {
                        OutlinedButton(enabled = !checking && key.isBlank(), onClick = {
                            scope.launch {
                                checking = true
                                result = ""
                                try {
                                    GeminiAiClient(context).checkConnection()
                                    result = "Connected. Gemini returned text using your saved key."
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    result = failure.message ?: "Connection failed."
                                } finally {
                                    checking = false
                                }
                            }
                        }) { Text(if (checking) "Checking…" else "Test saved key") }
                        if (result.isNotBlank()) Text(result)
                        Text("An API key is saved on this device.", color = MaterialTheme.colorScheme.primary)
                        TextButton(enabled = !checking, onClick = { result = ""; keyStore.clear(); hasKey = false; key = ""; error = null; onSaved() }) { Text("Remove saved key") }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onClose) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = key.length >= 20, onClick = {
                            try {
                                keyStore.saveKey(key)
                                hasKey = true
                                key = ""
                                onSaved()
                                onClose()
                            } catch (failure: Exception) {
                                error = failure.message ?: "Could not save this API key."
                            }
                        }) { Text("Save key") }
                    }
                }
            }
        }
    }
}
