package com.escudocall.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.role.RoleManager

/**
 * Re-arma el escudo al ENCENDER el teléfono y — nuevo en v3.3 — al
 * ACTUALIZAR/INSTALAR la app (MY_PACKAGE_REPLACED).
 *
 * Antes: al instalar una actualización el proceso quedaba MUERTO y nada lo
 * despertaba hasta la siguiente llamada; ese arranque en frío era la ventana
 * donde un número desconocido timbraba varios segundos antes de cortarse
 * (o pasaba completo si el arranque agotaba la ventana del sistema).
 * Ahora: segundos después de instalar, la Guardia ya está encendida y la
 * caché RAM llena, sin necesidad de abrir la app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val boot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        val updated = action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!boot && !updated) return

        try {
            val held = try {
                val rm = context.getSystemService(RoleManager::class.java)
                rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) ?: false
            } catch (e: Exception) {
                false
            }

            if (!held) {
                Notifier.notifyProtectionOff(
                    context,
                    "La protección quedó INACTIVA. Toca aquí y actívala de nuevo."
                )
            } else {
                // protección puesta: levantar la guardia y el vigilante
                try {
                    context.startForegroundService(Intent(context, GuardService::class.java))
                } catch (e: Exception) {
                }
                WatchdogJob.schedule(context)
                ContactCache.refresh(context)

                if (!Contacts.hasPermission(context)) {
                    Notifier.notifyProtectionOff(
                        context,
                        "Falta el permiso de CONTACTOS: sin él no puedo cortar desconocidos. Toca para arreglarlo."
                    )
                }
            }
        } catch (e: Exception) {
            // nunca rompemos el arranque del teléfono
        }
    }
}
