package com.escudocall.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.role.RoleManager

/**
 * Al encender el teléfono revisa que la protección siga puesta.
 * Algunos teléfonos (Xiaomi, Oppo, etc.) revocan el rol de filtro
 * al reiniciar: si eso pasa, avisamos con notificación al instante.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

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
                    "La protección quedó INACTIVA tras el reinicio. Toca aquí y actívala de nuevo."
                )
                return
            }

            if (!Contacts.hasPermission(context)) {
                Notifier.notifyProtectionOff(
                    context,
                    "Falta el permiso de CONTACTOS: sin él no puedo cortar desconocidos. Toca para arreglarlo."
                )
            }
        } catch (e: Exception) {
            // nunca rompemos el arranque del teléfono
        }
    }
}
