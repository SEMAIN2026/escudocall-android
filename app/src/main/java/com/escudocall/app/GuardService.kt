package com.escudocall.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Guardia en primer plano: mantiene el proceso vivo para que, cuando llegue
 * una llamada, el sistema ligue nuestro ScreeningService AL INSTANTE y
 * respondamos dentro de la ventana de ~5 segundos. Si el proceso está muerto
 * (lo matan los OEM), el arranque en frío puede tardar más y la llamada PASA.
 *
 * Es la capa "0" del blindaje. Sin icono flotante raro: solo una notificación
 * fija y silenciosa "Protegiendo tus llamadas".
 */
class GuardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = "guard"
        val id = 3001
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(ch) == null) {
            val channel = NotificationChannel(
                ch, "Protección en vivo", NotificationManager.IMPORTANCE_MIN
            )
            channel.description = "Mantiene el escudo listo para cada llamada"
            channel.setShowBadge(false)
            nm?.createNotificationChannel(channel)
        }

        val pi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, ch)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("EscudoCall protege tus llamadas")
            .setContentText("Contactos siempre pasan · desconocidos se cortan")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        try {
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } catch (e: Exception) {
            startForeground(id, n)
        }

        ContactCache.refresh(this)
        WatchdogJob.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ContactCache.refresh(this)
        return START_STICKY
    }
}
