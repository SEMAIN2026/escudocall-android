package com.escudocall.app

import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.PhoneAccount
import java.util.concurrent.Executors

/**
 * Servicio oficial de Android (Call Screening). v3.1 "corte relámpago".
 *
 * CAMBIO CLAVE vs v3.0: la decisión se toma 100% EN RAM (ContactCache) y
 * respondToCall() se ejecuta DE INMEDIATO — en milisegundos, antes de que el
 * timbre suene de verdad. Todo lo pesado (buscar el nombre, historial,
 * notificación, sincronización) ocurre DESPUÉS, en segundo plano.
 *
 * Si la caché aún no está lista (proceso recién arrancado), usamos el camino
 * de respaldo (consultar proveedores antes de responder): más lento pero
 * seguro. La Guardia (GuardService) existe justamente para que esto casi
 * nunca pase.
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
 *
 *   ANTI-REDIAL (nuevo v3.1): si el mismo número vuelve a marcar en menos de
 *   90 segundos, o insiste 2+ veces en 48 h, queda en lista negra permanente
 *   al instante (antes había que esperar al segundo rechazo).
 */
class ScreeningService : CallScreeningService() {

    private val io = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()

    override fun onScreenCall(details: Call.Details) {
        val t0 = SystemClock.elapsedRealtime()
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

            // ---------- CAMINO RELÁMPAGO (RAM, microsegundos) ----------
            val fast = ContactCache.decide(this, raw, isPrivate)
            if (fast != null) {
                respondToCall(details, if (fast.blocked) block else allow)
                val ms = (SystemClock.elapsedRealtime() - t0).toInt()
                val known = if (fast.type == "contacto") (ContactCache.contactName(raw) ?: "") else ""
                finishInBackground(raw, isPrivate, fast.type, fast.reason, fast.blocked, ms, known)
                return
            }

            // ---------- CAMINO DE RESPALDO (caché no lista aún) ----------
            val cached = ContactCache.has(this, raw)
            val name = if (isPrivate) "" else (Contacts.displayName(this, raw) ?: "")
            val inContacts = if (cached != null) cached || name.isNotBlank() else name.isNotBlank()
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
            val ms = (SystemClock.elapsedRealtime() - t0).toInt()
            finishInBackground(raw, isPrivate, type, reason, action == "blocked", ms, name)
        } catch (e: Exception) {
            try {
                respondToCall(details, allow)
            } catch (e2: Exception) {
            }
        }
    }

    /**
     * Todo lo que NO urge se hace aquí, después de haber respondido al
     * sistema: nombre, anti-redial, historial, notificación y sincronización.
     */
    private fun finishInBackground(
        raw: String,
        isPrivate: Boolean,
        type: String,
        reason: String,
        blocked: Boolean,
        ms: Int,
        knownName: String
    ) {
        val at = System.currentTimeMillis()
        val num = if (isPrivate) "" else raw
        io.execute {
            try {
                var nm = knownName
                if (nm.isBlank() && num.isNotBlank()) {
                    nm = Contacts.displayName(this@ScreeningService, num) ?: ""
                }
                var finalReason = reason
                if (type == "contacto") finalReason = "Contacto: $nm"

                var autoBanned = false
                if (blocked && num.isNotBlank()) {
                    val gap = Store.sinceLastRejection(this@ScreeningService, num)
                    val hits = Store.recordRejection(this@ScreeningService, num)
                    if ((hits >= 2 || gap < 90_000L) &&
                        !Store.inBlacklist(this@ScreeningService, num)
                    ) {
                        Store.addBlacklist(
                            this@ScreeningService,
                            num,
                            "Auto · insiste ($hits llamadas)"
                        )
                        ContactCache.refresh(this@ScreeningService)
                        autoBanned = true
                    }
                }

                Store.addHistory(
                    this@ScreeningService,
                    Store.HistEntry(
                        num, nm, type,
                        if (blocked) "blocked" else "allowed",
                        finalReason, at, false, ms
                    )
                )
                if (blocked) {
                    Notifier.notifyBlocked(
                        this@ScreeningService,
                        num,
                        if (autoBanned) "$finalReason · ya quedó en lista negra" else finalReason
                    )
                }
                if (Turso.enabled) {
                    net.execute {
                        try {
                            Sync.run(this@ScreeningService)
                            ContactCache.syncLists(this@ScreeningService)
                        } catch (e: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
    }
}
