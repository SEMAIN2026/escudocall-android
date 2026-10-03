package com.escudocall.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object Notifier {

    private const val CH = "blocked_calls"

    fun ensure(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(
                CH,
                "Llamadas bloqueadas",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            ch.description = "Avisos de llamadas cortadas por EscudoCall"
            nm.createNotificationChannel(ch)
        }
    }

    fun notifyBlocked(c: Context, number: String, reason: String) {
        if (!Store.notifyOn(c)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        ensure(c)
        val shown = if (number.isBlank()) "Número privado"
        else if (number.startsWith("+")) number else "+$number"

        val pi = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Llamada bloqueada")
            .setContentText("$shown · $reason")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        c.getSystemService(NotificationManager::class.java)
            ?.notify((System.currentTimeMillis() and 0x7FFFFFFF).toInt(), n)
    }

    /** Aviso rojo: la protección no está operando (rol caído o falta permiso). */
    fun notifyProtectionOff(c: Context, message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        ensure(c)
        val pi = PendingIntent.getActivity(
            c, 1,
            Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("EscudoCall: protección INACTIVA")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        c.getSystemService(NotificationManager::class.java)
            ?.notify(2001, n)
    }
}
