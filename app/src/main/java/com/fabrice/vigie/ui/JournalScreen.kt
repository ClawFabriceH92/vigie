package com.fabrice.vigie.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fabrice.vigie.SurveillanceViewModel
import com.fabrice.vigie.data.EventStore
import com.fabrice.vigie.ui.theme.AlertRed
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun JournalScreen(vm: SurveillanceViewModel) {
    val eventCount by vm.eventCount.collectAsStateWithLifecycle()
    var events by remember { mutableStateOf(vm.listEvents()) }
    var selected by remember { mutableStateOf<EventStore.Event?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    fun refresh() {
        events = vm.listEvents()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
    ) {
        Text("📖 Journal des événements", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "$eventCount événement(s) — photos conservées localement",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (events.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🌙", fontSize = 48.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Aucun événement pour l'instant.\nLes mouvements détectés en mode armé apparaîtront ici.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(events, key = { it.id }) { event ->
                    EventRow(event) { selected = event }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = AlertRed,
                        onClick = { confirmAll = true },
                    ) {
                        Text(
                            "🗑 Tout supprimer ($eventCount événement(s))",
                            modifier = Modifier.padding(vertical = 14.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    selected?.let { event ->
        EventDetailDialog(event, vm, onClose = { selected = null })
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("⚠️ Tout supprimer ?") },
            text = { Text("Action irréversible. Tous les $eventCount événement(s) et leurs photos seront définitivement supprimés de cet appareil.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAllEvents()
                    confirmAll = false
                    refresh()
                }) { Text("Tout supprimer", color = AlertRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun EventRow(event: EventStore.Event, onClick: () -> Unit) {
    val date = remember(event.id) {
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date(event.timestamp))
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(AlertRed.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("⚠️", fontSize = 20.sp)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (event.mode == "manuel") "Photo manuelle" else "Mouvement détecté",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${event.photos.size} photo(s)", style = MaterialTheme.typography.labelLarge)
                if (event.score > 0f) {
                    Text("score ${event.score.toInt()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EventDetailDialog(event: EventStore.Event, vm: SurveillanceViewModel, onClose: () -> Unit) {
    val date = remember(event.id) {
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date(event.timestamp))
    }
    var deleting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Événement — $date") },
        text = {
            if (deleting) {
                Text("Suppression…")
            } else if (event.photos.isEmpty()) {
                Text("Aucune photo dans cet événement.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(320.dp),
                ) {
                    items(event.photos) { photo ->
                        val file = vm.photoFile(event.id, photo)
                        LocalPhoto(file)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                deleting = true
                vm.deleteEvent(event.id)
                deleting = false
                onClose()
            }) { Text("Supprimer", color = AlertRed) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Fermer") }
        },
    )
}

@Composable
private fun LocalPhoto(file: File) {
    val bitmap = remember(file.path) {
        BitmapFactory.Options().apply { inSampleSize = 3 }
            .let { opts -> BitmapFactory.decodeFile(file.path, opts) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = file.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
