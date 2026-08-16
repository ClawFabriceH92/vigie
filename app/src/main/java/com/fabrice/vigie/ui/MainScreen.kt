package com.fabrice.vigie.ui

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.vigie.SurveillanceMode
import com.fabrice.vigie.SurveillanceViewModel
import com.fabrice.vigie.ui.theme.AlertRed
import com.fabrice.vigie.ui.theme.Amber
import com.fabrice.vigie.ui.theme.Cream
import com.fabrice.vigie.ui.theme.DeepNight
import com.fabrice.vigie.ui.theme.NightBlue
import com.fabrice.vigie.ui.theme.TrustGreen

/**
 * Écran principal : le preview caméra est lié à l'activité (affichage seul),
 * l'analyse (détection + flux) et le burst vivent dans VigieService.
 */
@Composable
fun MainScreen(vm: SurveillanceViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    // Preview lié à l'activité — NE PAS unbindAll (l'analyse du service est liée au sien)
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build())
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(context))
    }

    Box(Modifier.fillMaxSize().background(DeepNight)) {
        // Caméra toujours affichée : visible sur l'accueil, cachée sinon
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .then(if (screen == 0) Modifier else Modifier.alpha(0f)),
        )

        when (screen) {
            0 -> HomeOverlay(vm)
            1 -> JournalScreen(vm)
            2 -> TrustScreen(vm)
            3 -> SettingsScreen(vm)
        }

        BottomNav(vm, screen, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BottomNav(vm: SurveillanceViewModel, screen: Int, modifier: Modifier = Modifier) {
    val items = listOf(
        Triple(0, "👁", "Caméra"),
        Triple(1, "📖", "Journal"),
        Triple(2, "📡", "Confiance"),
        Triple(3, "⚙️", "Réglages"),
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = NightBlue,
    ) {
        NavigationBar(containerColor = Color.Transparent) {
            items.forEach { (idx, icon, label) ->
                NavigationBarItem(
                    selected = screen == idx,
                    onClick = { vm.setScreen(idx) },
                    icon = { Text(icon, fontSize = 22.sp) },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Amber,
                        indicatorColor = Amber.copy(alpha = 0.30f),
                        unselectedIconColor = Cream,
                        unselectedTextColor = Cream,
                    ),
                )
            }
        }
    }
}

// ---------- Accueil : aperçu caméra + statut + contrôles ----------

@Composable
private fun HomeOverlay(vm: SurveillanceViewModel) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val isManual by vm.isManual.collectAsStateWithLifecycle()
    val armingRemaining by vm.armingRemainingSec.collectAsStateWithLifecycle()
    val trustedPresent by vm.trustedPresent.collectAsStateWithLifecycle()
    val localIp by vm.localIp.collectAsStateWithLifecycle()
    val lastEvent by vm.lastEvent.collectAsStateWithLifecycle()
    val burstActive by vm.burstActive.collectAsStateWithLifecycle()
    val streamRunning by vm.streamRunning.collectAsStateWithLifecycle()
    val intercomMuted by vm.intercomMuted.collectAsStateWithLifecycle()
    val videoRecording by vm.videoRecording.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // Dégradé pour la lisibilité
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color(0xCC000000))))
        )

        // Statut en haut
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusBadge(mode, armingRemaining)
            if (isManual) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Color(0x66FFFFFF),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "Mode manuel — touche « Mode auto » pour revenir à la confiance",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
            if (burstActive) {
                Spacer(Modifier.height(8.dp))
                Surface(color = AlertRed, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        "📸 Captures en cours…",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }

        // Contrôles en bas (au-dessus de la NavigationBar)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (trustedPresent.isNotEmpty()) {
                Text(
                    "✅ ${trustedPresent.size} périphérique(s) de confiance présent(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFB8F5C9),
                )
                Spacer(Modifier.height(6.dp))
            }
            when (mode) {
                SurveillanceMode.ARMED -> MainActionButton("Désarmer", AlertRed) { vm.setManualMode(false) }
                SurveillanceMode.DISARMED -> MainActionButton("Armer", TrustGreen) { vm.setManualMode(true) }
                SurveillanceMode.ARMING -> MainActionButton("Désarmer", AlertRed) { vm.setManualMode(false) }
                SurveillanceMode.STARTING -> {}
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                color = if (videoRecording) AlertRed else Amber,
                shape = RoundedCornerShape(14.dp),
                onClick = {
                    if (videoRecording) vm.stopVideoRecording()
                    else vm.startVideoRecording()
                },
            ) {
                Text(
                    if (videoRecording) "⏹ Arrêter l'enregistrement vidéo" else "🎥 Démarrer l'enregistrement vidéo",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (videoRecording) Color.White else DeepNight,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (isManual) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Amber,
                    shape = RoundedCornerShape(14.dp),
                    onClick = { vm.setAutoMode() },
                ) {
                    Text(
                        "↩ Mode auto (confiance)",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = DeepNight,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (streamRunning) "Flux : http://${localIp ?: "?"}:${vm.settings.value.streamPort}/stream · Intercom : :${if (vm.settings.value.streamPort + 1 <= 65535) vm.settings.value.streamPort + 1 else vm.settings.value.streamPort - 1}" else "Flux indisponible",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB8C7DA),
            )
            if (streamRunning) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = if (intercomMuted) Color(0x66FFFFFF) else Amber,
                    shape = RoundedCornerShape(14.dp),
                    onClick = { vm.setIntercomMuted(!intercomMuted) },
                ) {
                    Text(
                        if (intercomMuted) "🔇 Haut-parleur intercom : muet (toucher pour activer)" else "🔊 Haut-parleur intercom : actif, volume max (toucher pour couper)",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (intercomMuted) Color(0xFFB8C7DA) else DeepNight,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            lastEvent?.let { ev ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "Dernier événement : ${ev.photos.size} photo(s) il y a ${formatAge(ev.timestamp)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFE3B75C),
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(mode: SurveillanceMode, armingRemaining: Long) {
    val (color, label) = when (mode) {
        SurveillanceMode.ARMED -> TrustGreen to "Armé — détection active"
        SurveillanceMode.DISARMED -> Color(0xFF757575) to "Désarmé"
        SurveillanceMode.ARMING -> Amber to "Armement dans ${armingRemaining}s"
        SurveillanceMode.STARTING -> Amber to "Démarrage…"
    }
    Surface(color = color, shape = RoundedCornerShape(18.dp), shadowElevation = 6.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(10.dp).background(Color.White, RoundedCornerShape(5.dp)))
            Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MainActionButton(label: String, color: Color, onClick: () -> Unit) {
    Surface(
        color = color,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 8.dp,
        onClick = onClick,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 40.dp, vertical = 14.dp),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

internal fun formatAge(ts: Long): String {
    val delta = System.currentTimeMillis() - ts
    return when {
        delta < 60_000 -> "${delta / 1000}s"
        delta < 3_600_000 -> "${delta / 60_000}min"
        else -> "${delta / 3_600_000}h"
    }
}
