package com.fabrice.vigie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fabrice.vigie.ui.theme.Amber
import com.fabrice.vigie.ui.theme.AlertRedLight
import com.fabrice.vigie.ui.theme.DeepNight
import com.fabrice.vigie.ui.theme.NightBlue

enum class PinMode { CREATE, VERIFY }

/**
 * Écran de verrouillage : création (2 saisies) ou vérification du PIN.
 * - CREATE : onPinValidated(pin) appelé après confirmation
 * - VERIFY : onVerify(pin) doit retourner true pour déverrouiller
 */
@Composable
fun PinScreen(
    mode: PinMode,
    onPinValidated: (String) -> Unit = {},
    onVerify: (String) -> Boolean = { false },
) {
    var phase by remember { mutableStateOf(if (mode == PinMode.CREATE) 1 else 0) }
    var firstPin by remember { mutableStateOf("") }
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val title = when {
        mode == PinMode.CREATE && phase == 1 -> "Créer un code"
        mode == PinMode.CREATE && phase == 2 -> "Confirmer le code"
        else -> "Vigie est verrouillée"
    }
    val subtitle = when {
        error != null -> error!!
        mode == PinMode.CREATE && phase == 1 -> "4 chiffres pour protéger l'application"
        mode == PinMode.CREATE && phase == 2 -> "Saisis-le une seconde fois"
        else -> "Entre ton code pour continuer"
    }

    fun onDigit(d: String) {
        if (entered.length >= 4) return
        val next = entered + d
        entered = next
        if (next.length == 4) {
            when {
                mode == PinMode.CREATE && phase == 1 -> {
                    firstPin = next
                    entered = ""
                    phase = 2
                }
                mode == PinMode.CREATE && phase == 2 -> {
                    if (next == firstPin) {
                        onPinValidated(next)
                    } else {
                        error = "Les codes ne correspondent pas"
                        entered = ""
                        phase = 1
                        firstPin = ""
                    }
                }
                else -> {
                    if (!onVerify(next)) {
                        error = "Code incorrect"
                        entered = ""
                    } else {
                        error = null
                    }
                }
            }
        }
    }

    fun onBackspace() {
        if (entered.isNotEmpty()) entered = entered.dropLast(1)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = DeepNight) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            Text("👁", fontSize = 44.sp)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) AlertRedLight else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            // Points de saisie
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(4) { i ->
                    val filled = i < entered.length
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(
                                color = when {
                                    filled -> Amber
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = CircleShape,
                            )
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
            Keypad(onDigit = ::onDigit, onBackspace = ::onBackspace)
        }
    }
}

@Composable
private fun Keypad(onDigit: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (key in row) {
                    when {
                        key.isEmpty() -> Spacer(Modifier.size(76.dp))
                        key == "⌫" -> KeyButton(key, isBack = true, onClick = onBackspace)
                        else -> KeyButton(key) { onDigit(key) }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, isBack: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .background(NightBlue, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = if (isBack) 24.sp else 26.sp,
            fontWeight = FontWeight.Bold,
            color = if (isBack) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
        )
    }
}
