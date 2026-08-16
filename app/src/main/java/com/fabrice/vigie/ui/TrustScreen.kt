package com.fabrice.vigie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.vigie.SurveillanceViewModel
import com.fabrice.vigie.trust.NetworkScanner
import com.fabrice.vigie.ui.theme.Amber
import com.fabrice.vigie.ui.theme.TrustGreen

@Composable
fun TrustScreen(vm: SurveillanceViewModel) {
    val devices by vm.lastScanDevices.collectAsStateWithLifecycle()
    val isScanning by vm.isScanning.collectAsStateWithLifecycle()
    val lastScanAt by vm.lastScanAt.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
    ) {
        Text("📡 Périphériques de confiance", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Quand un de ces appareils est détecté sur le réseau, la surveillance est désactivée. " +
                "Quand aucun n'est présent depuis le délai réglé, elle s'arme toute seule.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Réseau local", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            isScanning -> "Scan en cours…"
                            lastScanAt != null -> "Dernier scan il y a ${formatAge(lastScanAt!!)}"
                            else -> "Aucun scan encore effectué"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                } else {
                    TextButton(onClick = { vm.rescanNow() }) { Text("Scanner") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (devices.isEmpty() && !isScanning) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📡", fontSize = 48.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Appuie sur « Scanner » pour détecter les appareils du réseau.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.ip }) { device ->
                    DeviceTrustRow(device, vm, tick) {
                        tick++
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceTrustRow(
    device: NetworkScanner.Device,
    vm: SurveillanceViewModel,
    tick: Int,
    onToggled: () -> Unit,
) {
    val trusted = remember(device.mac, device.ip, tick) { vm.isDeviceTrusted(device.mac, device.ip) }
    val displayName = remember(device) {
        when {
            device.hostname.isNotBlank() -> device.hostname
            else -> device.ip
        }
    }
    val vendor = remember(device) { NetworkScanner.vendorFor(device.mac) }
    val hasMac = device.mac.isNotEmpty()
    val canTrust = hasMac || device.ip.isNotEmpty()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (trusted) TrustGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(if (trusted) TrustGreen else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (trusted) "✓" else "•", color = if (trusted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (!device.alive) {
                        Spacer(Modifier.size(6.dp))
                        Text("(récent)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    if (hasMac) {
                        "${device.mac}${if (vendor.isNotEmpty()) " · $vendor" else ""}"
                    } else {
                        "MAC masquée — confiance par IP"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (device.ip.isNotEmpty()) {
                    Text(device.ip, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (canTrust) {
                Switch(checked = trusted, onCheckedChange = { on ->
                    if (on) {
                        if (hasMac) vm.addTrustedDevice(device.mac, displayName)
                        else vm.addTrustedDeviceByIp(device.ip, displayName)
                    } else {
                        if (hasMac) vm.removeTrustedDevice(device.mac)
                        else vm.removeTrustedDeviceByIp(device.ip)
                    }
                    onToggled()
                })
            }
        }
    }
}
