package com.escudocall.app

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.PhoneAccount
import java.util.concurrent.Executors

/**
 * Servicio oficial de Android (Call Screening).
 * El sistema llama a onScreenCall() por CADA llamada entrante y aquí
 * decidimos si pasa o se corta, antes de que suene.
 *
 * MODOS:
 *   Normal:
 *     1. Emergencias (911...)   -> SIEMPRE pasan
 *     2. Lista negra            -> SIEMPRE se corta
 *     3. Número privado         -> se corta (ajuste)
 *     4. Contactos (EXACTO)     -> SIEMPRE pasan
 *     5. Lista blanca (web)     -> pasan
 *     6. Internacional          -> se corta (ajuste, por defecto ON)
 *     7. Desconocidos           -> se cortan (ajuste)
 *
 *   MODO ESTRICTO (anti-cobradores; defecto ON):
 *     Solo pasan emergencias y contactos EXACTOS.
 *     TODO lo demás se corta: privados, desconocidos,
 *     internacionales y también la lista blanca web.
 *
 *   Extra: si un número se rechaza 2+ veces en 48 h se agrega solo
 *   a la lista negra ("Auto-bloqueado"), aunque luego apagues el modo estricto.
 */
class ScreeningService : CallScreeningService() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onScreenCall(details: Call.Details) {
        val allow = CallScreeningService.CallResponse.Builder().build()
        val block = CallScreeningService.CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipCallLog(true)
            .setSkipNotification(true)
            .build()

        try {
            if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
                respondToCall(details, allow)
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

            // El modo estricto SOLO aplica si puedo leer los contactos:
            // si no, fallaría en bloquear hasta a tu familia (fail-safe).
            val strict = Store.strictMode(this) && Contacts.hasPermission(this)

            var action = "allowed"
            var reason = ""
            var type = ""

            when {
                !isPrivate && Store.isEmergency(raw) -> {
                    type = "emergencia"; reason = "Número de emergencia: siempre pasa"
                }
                !isPrivate && Store.inBlacklist(this, raw) -> {
                    type = "lista_negra"; action = "blocked"; reason = "En tu lista negra"
                }
                isPrivate -> {
                    type = "privado"
                    if (strict || Store.blockPrivate(this)) {
                        action = "blocked"; reason = "Número privado"
                    } else {
                        reason = "Privado permitido (ajuste)"
                    }
                }
                inContacts -> {
                    type = "contacto"; reason = "Contacto: $name"
                }
                strict -> {
                    type = "estricto"
                    action = "blocked"
                    reason = "Modo estricto: solo tus contactos pasan"
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

            respondToCall(details, if (action == "blocked") block else allow)

            val at = System.currentTimeMillis()
            val num = if (isPrivate) "" else raw
            io.execute {
                try {
                    // Auto-bloqueo de insistentes: 2+ rechazos en 48 h -> lista negra
                    var autoBanned = false
                    if (action == "blocked" && num.isNotBlank()) {
                        val hits = Store.recordRejection(this@ScreeningService, num)
                        if (hits >= 2 && !Store.inBlacklist(this@ScreeningService, num)) {
                            Store.addBlacklist(
                                this@ScreeningService,
                                num,
                                "Auto · insiste ($hits llamadas)"
                            )
                            autoBanned = true
                        }
                    }

                    Store.addHistory(
                        this@ScreeningService,
                        Store.HistEntry(num, name, type, action, reason, at, false)
                    )
                    if (action == "blocked") {
                        Notifier.notifyBlocked(
                            this@ScreeningService,
                            num,
                            if (autoBanned) "$reason · ya quedó en lista negra" else reason
                        )
                    }
                    if (Turso.enabled) {
                        Sync.run(this@ScreeningService)
                    }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            try {
                respondToCall(details, allow)
            } catch (e2: Exception) {
            }
        }
    }
}
