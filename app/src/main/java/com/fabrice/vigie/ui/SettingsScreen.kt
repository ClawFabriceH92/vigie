package com.fabrice.vigie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.vigie.BuildConfig
import com.fabrice.vigie.SurveillanceViewModel
import com.fabrice.vigie.VigieRuntime
import com.fabrice.vigie.data.SettingsStore
import com.fabrice.vigie.security.PinHasher
import com.fabrice.vigie.security.SecureGenerator
import com.fabrice.vigie.ui.theme.AlertRed
import com.fabrice.vigie.ui.theme.Amber
import com.fabrice.vigie.ui.theme.DeepNight
import com.fabrice.vigie.ui.theme.TrustGreen

@Composable
fun SettingsScreen(vm: SurveillanceViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val eventCount by vm.eventCount.collectAsStateWithLifecycle()
    val eventsSizeMb by vm.eventsSizeMb.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    var showPinDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showRemotePassword by remember { mutableStateOf(false) }
    var passwordJustChanged by remember { mutableStateOf(false) }

    // Message temporaire « mot de passe changé »
    LaunchedEffect(passwordJustChanged) {
        if (passwordJustChanged) {
            delay(4000)
            passwordJustChanged = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
    ) {
        Text("⚙️ Réglages", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        SectionCard("Sécurité") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Code PIN", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (settings.pinHash.isNotEmpty()) "Activé — verrouille l'app (défaut 0000)" else "Non défini",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showPinDialog = true }) { Text("Changer") }
            }
        }

        SectionCard("Détection de mouvement") {
            Text("Sensibilité : ${settings.motionThreshold}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.motionThreshold.toFloat(),
                onValueChange = { vm.updateSettings(settings.copy(motionThreshold = it.toInt())) },
                valueRange = 5f..50f,
                steps = 8,
            )
            Text(
                "Plus bas = détecte plus facilement (et plus de faux positifs)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Stepper("Photos par événement", settings.burstCount, "photo(s)", 1, 10, 1) { vm.updateSettings(settings.copy(burstCount = it)) }
            Stepper("Intervalle entre photos", (settings.burstIntervalMs / 100).toInt(), "× 100 ms", 3, 30, 1) { vm.updateSettings(settings.copy(burstIntervalMs = it * 100L)) }
            Stepper("Silence après un événement", settings.cooldownSec, "s", 10, 300, 10) { vm.updateSettings(settings.copy(cooldownSec = it)) }
        }

        SectionCard("Image") {
            Text("Qualité du flux (MJPEG) : ${settings.jpegQuality}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.jpegQuality.toFloat(),
                onValueChange = { vm.updateSettings(settings.copy(jpegQuality = it.toInt())) },
                valueRange = 50f..95f,
                steps = 8,
            )
            Text(
                "Plus haut = meilleure image mais flux plus lourd (80 par défaut)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("Résolution du flux : ${if (settings.analysisHeight >= 720) "HD (1280×720)" else "SD (640×480)"}", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.updateSettings(settings.copy(analysisHeight = 480)) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (settings.analysisHeight < 720) Amber else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) { Text("SD 640×480", color = if (settings.analysisHeight < 720) DeepNight else MaterialTheme.colorScheme.onSurfaceVariant) }
                Button(
                    onClick = { vm.updateSettings(settings.copy(analysisHeight = 720)) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (settings.analysisHeight >= 720) Amber else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) { Text("HD 1280×720", color = if (settings.analysisHeight >= 720) DeepNight else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(
                "Appliqué au prochain démarrage du service (relance l'app).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("🎥 Enregistrement vidéo : HD (720p), déclenchable à distance via /video/start, /video/stop, /video/list sur le port 8080. Les fichiers MP4 sont téléchargeables depuis /video/list.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Mode confiance (présence réseau)") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Activer le mode confiance", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Désarmé quand un périphérique de confiance est sur le réseau",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = settings.trustEnabled,
                    onCheckedChange = { vm.updateSettings(settings.copy(trustEnabled = it)) },
                )
            }
            if (settings.trustEnabled) {
                Spacer(Modifier.height(8.dp))
                Stepper("Scan réseau", settings.trustScanIntervalSec, "s", 30, 300, 15) { vm.updateSettings(settings.copy(trustScanIntervalSec = it)) }
                Stepper("Délai avant armement", settings.trustDisarmDelaySec, "s", 30, 600, 30) { vm.updateSettings(settings.copy(trustDisarmDelaySec = it)) }
            }
        }

        SectionCard("Stockage local") {
            Text(
                "$eventCount événement(s) · ${"%.1f".format(eventsSizeMb)} Mo",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showClearDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
            ) {
                Text("Tout supprimer")
            }
        }

        SectionCard("Accès à distance (flux + intercom)") {
            OutlinedTextField(
                value = settings.streamPort.toString(),
                onValueChange = { input ->
                    val port = input.filter { it.isDigit() }.take(5).toIntOrNull()
                    if (port != null && port in 1024..65535) {
                        vm.updateSettings(settings.copy(streamPort = port))
                    }
                },
                label = { Text("Port du serveur (flux + contrôle)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    TextButton(onClick = {
                        val newPort = (1024..65535).random()
                        vm.updateSettings(settings.copy(streamPort = newPort))
                    }) { Text("🎲 Aléatoire") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Intercom sur le port suivant (${if (settings.streamPort + 1 <= 65535) settings.streamPort + 1 else settings.streamPort - 1}). Redémarrage automatique des serveurs au changement.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.streamUser,
                onValueChange = { vm.updateSettings(settings.copy(streamUser = it)) },
                label = { Text("Utilisateur") },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = {
                        vm.updateSettings(settings.copy(streamUser = SecureGenerator.username()))
                    }) { Text("🎲 Aléatoire") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.streamPassword,
                onValueChange = { vm.updateSettings(settings.copy(streamPassword = it)) },
                label = { Text("Mot de passe") },
                singleLine = true,
                visualTransformation = if (showRemotePassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = !PinHasher.isStrong(settings.streamPassword),
                supportingText = {
                    Text(
                        if (PinHasher.isStrong(settings.streamPassword)) {
                            "✓ Mot de passe fort"
                        } else {
                            "Faible — 12 caractères min, lettre + chiffre + symbole requis"
                        },
                        color = if (PinHasher.isStrong(settings.streamPassword)) TrustGreen else AlertRed,
                    )
                },
                trailingIcon = {
                    Row {
                        TextButton(onClick = { showRemotePassword = !showRemotePassword }) {
                            Text(if (showRemotePassword) "🙈" else "👁")
                        }
                        TextButton(onClick = {
                            vm.updateSettings(settings.copy(streamPassword = SecureGenerator.password()))
                            showRemotePassword = true
                            passwordJustChanged = true
                        }) { Text("🎲 Aléatoire") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (passwordJustChanged) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "✅ Nouveau mot de passe généré et enregistré — note-le, il est affiché ci-dessus",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TrustGreen,
                )
            }
            Text(
                "Sans caractères ambigus (O/0, I/l/1…) — lisibles et recopiables. Utilisateur et mot de passe protègent le flux (port 8080) et l'intercom (port 8081).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Envoi de photos par email") {
            OutlinedTextField(
                value = settings.emailRecipient,
                onValueChange = { vm.updateSettings(settings.copy(emailRecipient = it)) },
                label = { Text("Adresse du destinataire") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Préremplie le destinataire quand tu envoies les photos d'un événement. Tu choisis ensuite ton app email (Gmail, etc.).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Mise à jour") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Mise à jour automatique", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Vérifie GitHub et installe la nouvelle version",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = settings.autoUpdate,
                    onCheckedChange = { vm.updateSettings(settings.copy(autoUpdate = it)) },
                )
            }
            Spacer(Modifier.height(8.dp))
            UpdateStatusRow(updateState)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.checkForUpdates() },
                    enabled = updateState.status != VigieRuntime.UpdateStatus.CHECKING,
                ) {
                    Text(
                        when (updateState.status) {
                            VigieRuntime.UpdateStatus.CHECKING -> "Vérification…"
                            else -> "Vérifier maintenant"
                        }
                    )
                }
                if (updateState.status == VigieRuntime.UpdateStatus.AVAILABLE) {
                    Button(onClick = { vm.installUpdate() }) {
                        Text("Télécharger v${updateState.info?.versionName ?: ""}")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Vigie v${BuildConfig.VERSION_NAME} — surveillance locale. Les photos restent sur cet appareil.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showPinDialog) {
        PinChangeDialog(
            vm = vm,
            onDismiss = { showPinDialog = false },
        )
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Tout supprimer ?") },
            text = { Text("Les $eventCount événement(s) et toutes les photos seront définitivement supprimés.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAllEvents()
                    showClearDialog = false
                }) { Text("Supprimer", color = AlertRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun PinChangeDialog(vm: SurveillanceViewModel, onDismiss: () -> Unit) {
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Changer le code PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = oldPin,
                    onValueChange = { oldPin = it },
                    label = { Text("Code actuel") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it },
                    label = { Text("Nouveau code (4 chiffres)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it },
                    label = { Text("Confirmer") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = AlertRed, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    oldPin.length != 4 || newPin.length != 4 || confirmPin.length != 4 ->
                        error = "Les codes doivent faire 4 chiffres"
                    newPin != confirmPin -> error = "Les nouveaux codes ne correspondent pas"
                    !vm.changePin(oldPin, newPin) -> error = "Code actuel incorrect"
                    else -> {
                        error = null
                        onDismiss()
                    }
                }
            }) { Text("Valider") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun UpdateStatusRow(state: VigieRuntime.UpdateUiState) {
    val text: String = when (state.status) {
        VigieRuntime.UpdateStatus.IDLE -> "Version installée : v${BuildConfig.VERSION_NAME}"
        VigieRuntime.UpdateStatus.CHECKING -> "Vérification de la dernière version…"
        VigieRuntime.UpdateStatus.UP_TO_DATE -> "✓ À jour (v${BuildConfig.VERSION_NAME})"
        VigieRuntime.UpdateStatus.AVAILABLE -> "⬆ Mise à jour v${state.info?.versionName ?: ""} disponible"
        VigieRuntime.UpdateStatus.DOWNLOADING -> "Téléchargement en cours… (notification Android)"
        VigieRuntime.UpdateStatus.ERROR -> state.message ?: "Erreur de vérification"
    }
    val color: Color = when (state.status) {
        VigieRuntime.UpdateStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        VigieRuntime.UpdateStatus.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
        VigieRuntime.UpdateStatus.UP_TO_DATE -> TrustGreen
        VigieRuntime.UpdateStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
        VigieRuntime.UpdateStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
        VigieRuntime.UpdateStatus.ERROR -> AlertRed
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun Stepper(
    label: String,
    value: Int,
    suffix: String,
    min: Int,
    max: Int,
    step: Int,
    onValue: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        StepButton("−", enabled = value > min) { onValue((value - step).coerceAtLeast(min)) }
        Text(
            "$value $suffix",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        StepButton("+", enabled = value < max) { onValue((value + step).coerceAtMost(max)) }
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            fontSize = 18.sp,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
