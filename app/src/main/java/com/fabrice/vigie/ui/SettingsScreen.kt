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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.vigie.BuildConfig
import com.fabrice.vigie.SurveillanceViewModel
import com.fabrice.vigie.VigieRuntime
import com.fabrice.vigie.data.SettingsStore
import com.fabrice.vigie.ui.theme.AlertRed
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
                        if (settings.pinHash.isNotEmpty()) "Activé — verrouille l'app" else "Non défini",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showPinDialog = true }) { Text("Changer") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.streamPassword,
                onValueChange = { vm.updateSettings(settings.copy(streamPassword = it)) },
                label = { Text("Mot de passe du flux") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Protège http://<ip>:8080 (flux réseau + distant)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

        SectionCard("Mode confiance (présence réseau)") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Activer le mode confiance", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Désarmé quand un périphérique de confiance est sur le réseau",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

        SectionCard("Mise à jour") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Mise à jour automatique", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Vérifie GitHub et installe la nouvelle version",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
