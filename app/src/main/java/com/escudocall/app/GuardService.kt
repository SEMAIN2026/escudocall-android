package com.escudocall.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Guardia en primer plano: mantiene el proceso vivo para que, cuando llegue
 * una llamada, el sistema ligue nuestro ScreeningService AL INSTANTE y
 * respondamos en milisegundos. Si el proceso está muerto (lo matan los OEM),
 * el arranque en frío agrega segundos de timbre antes del corte.
 *
 * v3.1: arranque a prueba de balas. En Android 14+ el tipo phoneCall puede
 * ser negado según el estado del teléfono; antes, el respaldo sin tipo
 * también fallaba con targetSdk 35 y el servicio MORÍA en silencio (causa
 * principal del retraso de 5-10 s). Ahora probamos: phoneCall -> specialUse
 * -> normal, y la caché se llena completa aquí mismo.
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
            .setContentText("Corte relámpago listo · contactos siempre pasan")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        startForegroundBulletproof(id, n)

        // Primera carga COMPLETA de la caché (listas + contactos): así la
        // primera llamada tras abrir la app ya se corta en milisegundos.
        try {
            ContactCache.refreshSync(this)
            ContactCache.ensureObserver(this)
        } catch (e: Exception) {
        }
        WatchdogJob.schedule(this)
    }

    private fun startForegroundBulletproof(id: Int, n: Notification) {
        try {
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            return
        } catch (e: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                return
            } catch (e: Exception) {
            }
        }
        try {
            startForeground(id, n)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ContactCache.refresh(this)
            ContactCache.ensureObserver(this)
        } catch (e: Exception) {
        }
        return START_STICKY
    }
}
