package com.fabrice.vigie.power

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import com.fabrice.vigie.R

/**
 * Alerte batterie faible + coupure/reprise de courant.
 *
 * - ACTION_POWER_DISCONNECTED → notification « coupure de courant » (sur batterie)
 * - ACTION_POWER_CONNECTED    → notification « courant rétabli »
 * - ACTION_BATTERY_LOW        → notification « batterie faible »
 *
 * Enregistré dynamiquement (le manifest ne reçoit plus certains broadcasts
 * système depuis Android 8) ; le service foreground le tient vivant.
 */
class PowerMonitorReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val threshold = com.fabrice.vigie.VigieRuntime.settings.value.batteryAlertThreshold
        when (intent.action) {
            Intent.ACTION_POWER_DISCONNECTED -> notify(
                context, nm, ID_POWER,
                "⚡ Coupure de courant",
                "Vigie fonctionne sur batterie — branche le téléphone dès que possible.",
            )
            Intent.ACTION_POWER_CONNECTED -> notify(
                context, nm, ID_POWER,
                "🔌 Courant rétabli",
                "Le téléphone Vigie est de nouveau branché.",
            )
            Intent.ACTION_BATTERY_LOW -> {
                if (threshold > 0) {
                    val level = batteryLevel(context)
                    notify(
                        context, nm, ID_BATTERY,
                        "🔋 Batterie faible",
                        "Niveau : $level% (alerte configurée à $threshold%).",
                    )
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "vigie_power"
        private const val ID_POWER = 20
        private const val ID_BATTERY = 21

        fun register(context: Context) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
            }
            context.registerReceiver(PowerMonitorReceiver(), filter)
        }

        fun batteryLevel(context: Context): Int {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Batterie & courant",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alertes batterie faible et coupure/reprise de courant"
        }
        nm.createNotificationChannel(channel)
    }

    private fun notify(context: Context, nm: NotificationManager, id: Int, title: String, text: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id, notif)
        } catch (_: Exception) {
        }
    }
}
