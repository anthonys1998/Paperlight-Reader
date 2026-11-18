package com.paperlight.reader.ui

import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.paperlight.reader.ai.GroqAiClient
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
import com.paperlight.reader.ai.GroqApiKeyStore

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun GroqApiKeyScreen(onClose: () -> Unit, onSaved: () -> Unit = {}) {
    val context = LocalContext.current
    val keyStore = remember(context) { GroqApiKeyStore(context) }
    var key by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasKey by remember { mutableStateOf(keyStore.hasKey()) }
    val prefs = remember { context.getSharedPreferences("groqModels", 0) }
    var selected by remember { mutableStateOf(prefs.getString("model", "").orEmpty()) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(hasKey, refresh) {
        if (hasKey) try {
            models = GroqAiClient(context).models()
            if (models.isEmpty()) result = "No chat models available."
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            result = failure.message.orEmpty()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Groq AI settings") },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Back") } }
                )
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Add your Groq API key to use AI-assisted website setup and note summaries. The key is encrypted on this device and sent only with requests to Groq’s API.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) }
                    }) {
                        Text("Get an API key in Groq Console")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                    }
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.trim(); error = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (hasKey) "Replace saved API key" else "Groq API key") },
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
                        Box {
                            OutlinedButton(enabled = models.isNotEmpty() && !checking, onClick = { expanded = true }) {
                                Text(selected.ifBlank { "Choose model" })
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                models.forEach { model ->
                                    DropdownMenuItem(text = { Text(model) }, onClick = {
                                        selected = model
                                        prefs.edit().putString("model", model).apply()
                                        expanded = false
                                        result = ""
                                    })
                                }
                            }
                        }
                        TextButton(onClick = { refresh++; result = "" }) { Text("Refresh models") }
                        prefs.getString("limits:$selected", null)?.let { Text(it) }

                        OutlinedButton(enabled = !checking && key.isBlank() && selected.isNotBlank(), onClick = {
                            scope.launch {
                                checking = true
                                result = ""
                                try {
                                    GroqAiClient(context).checkConnection()
                                    result = "Connected. Groq returned text using your saved key."
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
                        TextButton(enabled = !checking, onClick = { result = ""; keyStore.clear(); prefs.edit().clear().apply(); selected = ""; models = emptyList(); hasKey = false; key = ""; error = null; onSaved() }) { Text("Remove saved key") }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onClose) { Text("Done") }
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = key.length >= 20, onClick = {
                            try {
                                keyStore.saveKey(key)
                                hasKey = true
                                key = ""
                                prefs.edit().clear().apply()
                                selected = ""
                                models = emptyList()
                                result = ""
                                refresh++
                                onSaved()
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
