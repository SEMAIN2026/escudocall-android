package com.escudocall.app

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.PhoneAccount
import java.util.concurrent.Executors

/**
 * CAPA 2 DEL BLINDAJE — el "marcador predeterminado".
 *
 * Si el primer filtro (CallScreeningService) falla por la ventana de 5 segundos
 * o porque el fabricante mató el proceso, esta capa es la ÚLTIMA línea:
 * como app de teléfono, Android nos entrega CADA llamada en el primer timbre
 * y aquí la rechazamos en el acto si no es de tu gente.
 *
 * Se activa solo cuando el usuario activa "Blindaje total" (rol de marcador).
 */
class BlindajeService : InCallService() {

    companion object {
        @Volatile
        var activeCall: Call? = null
    }

    private val io = Executors.newSingleThreadExecutor()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        try {
            if (call.state == Call.STATE_RINGING) evaluateIncoming(call) else showUi(call)
        } catch (e: Exception) {
            try { showUi(call) } catch (e2: Exception) {}
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (activeCall === call) activeCall = null
    }

    private fun evaluateIncoming(call: Call) {
        val details = call.details
        val handle = details?.handle
        val raw = if (handle != null && handle.scheme == PhoneAccount.SCHEME_TEL) {
            (handle.schemeSpecificPart ?: "").trim()
        } else ""

        val isPrivate = raw.isBlank() || raw == "-1" ||
            raw.equals("unknown", true) ||
            raw.equals("restricted", true) ||
            raw.equals("privado", true)

        val cached = ContactCache.has(this, raw)
        val name = if (isPrivate) "" else (Contacts.displayName(this, raw) ?: "")
        val inContacts = if (cached != null) cached || name.isNotBlank() else name.isNotBlank()
        val strict = Store.strictMode(this) && Contacts.hasPermission(this)

        var blocked = false
        var type = ""
        var reason = ""

        when {
            !isPrivate && Store.isEmergency(raw) -> {
                type = "emergencia"; reason = "Número de emergencia"
            }
            !isPrivate && Store.inBlacklist(this, raw) -> {
                blocked = true; type = "blindaje"; reason = "En tu lista negra"
            }
            isPrivate -> {
                if (strict || Store.blockPrivate(this)) {
                    blocked = true; type = "blindaje"; reason = "Número privado"
                }
            }
            inContacts -> {
                type = "contacto"; reason = "Contacto: $name"
            }
            strict -> {
                blocked = true; type = "blindaje"; reason = "Blindaje: solo tus contactos pasan"
            }
            Store.inWhitelist(this, raw) -> {
                type = "lista_blanca"; reason = "Lista blanca EscudoCall"
            }
            Store.isInternational(raw) && Store.blockIntl(this) -> {
                blocked = true; type = "blindaje"; reason = "Número internacional"
            }
            Store.blockUnknown(this) && Contacts.hasPermission(this) -> {
                blocked = true; type = "blindaje"; reason = "No está en tus contactos"
            }
        }

        if (blocked) {
            try {
                call.reject(Call.REJECT_REASON_DECLINED)
            } catch (e: Exception) {
                try { call.reject() } catch (e2: Exception) {}
            }
            logBlocked(raw, name, type, reason)
        } else {
            showUi(call)
        }
    }

    private fun showUi(call: Call) {
        activeCall = call
        try {
            val i = Intent(this, CallActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    private fun logBlocked(num: String, name: String, type: String, reason: String) {
        val at = System.currentTimeMillis()
        io.execute {
            try {
                var autoBanned = false
                if (num.isNotBlank()) {
                    val hits = Store.recordRejection(this, num)
                    if (hits >= 2 && !Store.inBlacklist(this, num)) {
                        Store.addBlacklist(this, num, "Auto · insiste ($hits llamadas)")
                        autoBanned = true
                    }
                }
                Store.addHistory(
                    this,
                    Store.HistEntry(num, name, type, "blocked", reason, at, false)
                )
                Notifier.notifyBlocked(
                    this,
                    num,
                    if (autoBanned) "$reason · ya quedó en lista negra" else reason
                )
                if (Turso.enabled) Sync.run(this)
            } catch (e: Exception) {
            }
        }
    }
}
