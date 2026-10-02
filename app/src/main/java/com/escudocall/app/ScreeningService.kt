package com.escudocall.app

import android.telecom.Call
import android.telecom.CallResponse
import android.telecom.CallScreeningService
import android.telecom.PhoneAccount
import java.util.concurrent.Executors

/**
 * Servicio oficial de Android (Call Screening).
 * El sistema llama a onScreenCall() por CADA llamada entrante y aquí
 * decidimos si pasa o se corta, antes de que suene.
 *
 * Prioridad (igual que EscudoCall web):
 *   1. Lista negra            -> SIEMPRE se corta
 *   2. Número privado         -> se corta (ajuste)
 *   3. Contactos              -> SIEMPRE pasan
 *   4. Lista blanca (web)     -> pasan
 *   5. Internacional          -> se corta (ajuste)
 *   6. Desconocidos           -> se cortan (ajuste)
 */
class ScreeningService : CallScreeningService() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onScreenCall(details: Call.Details) {
        val allow = CallResponse.Builder().build()
        val block = CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipCallLog(true)
            .setSkipNotification(true)
            .build()

        try {
            if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
                respond(details, allow)
                return
            }

            val handle = details.handle
            val raw = if (handle != null && handle.scheme == PhoneAccount.SCHEME_TEL) {
                (handle.schemeSpecificPart ?: "").trim()
            } else ""

            val isPrivate = raw.isBlank() || raw == "-1" ||
                raw.equals("unknown", true) ||
                raw.equals("restricted", true) ||
                raw.equals("privado", true)

            val name = if (isPrivate) "" else (Contacts.displayName(this, raw) ?: "")
            val inContacts = name.isNotBlank()

            var action = "allowed"
            var reason = ""
            var type = ""

            when {
                !isPrivate && Store.inBlacklist(this, raw) -> {
                    type = "lista_negra"; action = "blocked"; reason = "En tu lista negra"
                }
                isPrivate -> {
                    type = "privado"
                    if (Store.blockPrivate(this)) {
                        action = "blocked"; reason = "Número privado"
                    } else {
                        reason = "Privado permitido (ajuste)"
                    }
                }
                inContacts -> {
                    type = "contacto"; reason = "Contacto: $name"
                }
                Store.inWhitelist(this, raw) -> {
                    type = "lista_blanca"; reason = "Lista blanca EscudoCall"
                }
                Store.isInternational(raw) -> {
                    type = "internacional"
                    if (Store.blockIntl(this)) {
                        action = "blocked"; reason = "Número internacional"
                    } else {
                        reason = "Internacional permitido (ajuste)"
                    }
                }
                else -> {
                    type = "desconocido"
                    if (Store.blockUnknown(this) && Contacts.hasPermission(this)) {
                        action = "blocked"; reason = "No está en tus contactos"
                    } else if (!Contacts.hasPermission(this)) {
                        reason = "Sin permiso de contactos"
                    } else {
                        reason = "Desconocido permitido (ajuste)"
                    }
                }
            }

            respond(details, if (action == "blocked") block else allow)

            val at = System.currentTimeMillis()
            val num = if (isPrivate) "" else raw
            io.execute {
                try {
                    Store.addHistory(
                        this@ScreeningService,
                        Store.HistEntry(num, name, type, action, reason, at, false)
                    )
                    if (action == "blocked") {
                        Notifier.notifyBlocked(this@ScreeningService, num, reason)
                    }
                    if (Turso.enabled) {
                        Sync.run(this@ScreeningService)
                    }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            try {
                respond(details, allow)
            } catch (e2: Exception) {
            }
        }
    }
}
