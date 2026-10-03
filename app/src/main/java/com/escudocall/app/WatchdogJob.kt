package com.escudocall.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Vigilante: cada ~15 minutos comprueba que el rol de filtrado y el permiso
 * de contactos sigan puestos. Si el teléfono los quitó (actualizaciones,
 * reinicios, políticas del fabricante), avisa con notificación al momento
 * en vez de descubrirlo cuando ya te llamaron los cobradores.
 */
class WatchdogJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            val rm = getSystemService(RoleManager::class.java)
            val held = rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) ?: false
            if (!held) {
                Notifier.notifyProtectionOff(
                    this,
                    "El teléfono quitó el permiso de FILTRAR llamadas. Toca aquí y actívalo otra vez."
                )
            } else if (!Contacts.hasPermission(this)) {
                Notifier.notifyProtectionOff(
                    this,
                    "Se perdió el permiso de CONTACTOS: no puedo cortar desconocidos. Toca para arreglarlo."
                )
            }
            // mantener la caché de contactos fresca mientras tanto
            ContactCache.refresh(this)
            ContactCache.ensureObserver(this)
            // si la Guardia fue asesinada, darle otro respiro (si el sistema
            // lo permite desde un Job; si no, START_STICKY la traerá de vuelta)
            try {
                startForegroundService(Intent(this, GuardService::class.java))
            } catch (e: Exception) {
            }
        } catch (e: Exception) {
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 1001

        fun schedule(c: Context) {
            try {
                val js = c.getSystemService(JobScheduler::class.java) ?: return
                val info = JobInfo.Builder(
                    JOB_ID,
                    ComponentName(c, WatchdogJob::class.java)
                )
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
                js.schedule(info)
            } catch (e: Exception) {
            }
        }
    }
}
