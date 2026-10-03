package com.escudocall.app

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract

/**
 * Caché del escudo en RAM — el corazón del "corte relámpago" (v3.1).
 *
 * Guarda en memoria: contactos (claves numéricas + nombres), lista negra y
 * lista blanca. Así la decisión de cada llamada es pura aritmética en memoria
 * (microsegundos) y respondemos al sistema AL INSTANTE, sin tocar proveedores
 * dentro de la ventana que Android da para filtrar. El timbre apenas arranca
 * y ya se cortó.
 *
 * Se mantiene fresca:
 *  - al arrancar el proceso (App.onCreate)
 *  - al abrir la app o encender el teléfono (GuardService)
 *  - cuando cambian los contactos (ContentObserver)
 *  - cada ~15 minutos (WatchdogJob)
 */
object ContactCache {

    /** Veredicto listo para responder al sistema. */
    data class Decision(val blocked: Boolean, val type: String, val reason: String)

    @Volatile
    private var ready = false

    private val lock = Any()
    private val contacts = HashSet<String>()
    private val names = HashMap<String, String>()
    private val blackKeys = HashSet<String>()
    private val whiteKeys = HashSet<String>()

    @Volatile
    private var loading = false

    @Volatile
    private var pending = false

    @Volatile
    private var observerRegistered = false

    // ---------- Carga ----------

    /** Lista negra/blanca en RAM: leer SharedPreferences es instantáneo. */
    fun syncLists(c: Context) {
        val bl = HashSet<String>()
        for (e in Store.blacklist(c)) for (k in keysFor(e.phone)) bl.add(k)
        val wl = HashSet<String>()
        for (p in Store.whitelist(c)) for (k in keysFor(p)) wl.add(k)
        synchronized(lock) {
            blackKeys.clear(); blackKeys.addAll(bl)
            whiteKeys.clear(); whiteKeys.addAll(wl)
        }
    }

    /** Ligera: listas al instante + contactos en segundo plano. */
    fun refresh(c: Context) {
        syncLists(c)
        loadContactsAsync(c)
    }

    /** Primera carga completa (bloquea unos milisegundos): la usa la Guardia. */
    fun refreshSync(c: Context) {
        syncLists(c)
        try { loadContacts(c) } catch (e: Exception) {}
    }

    private fun loadContactsAsync(c: Context) {
        synchronized(this) {
            if (loading) { pending = true; return }
            loading = true
        }
        Thread {
            var again = false
            try { loadContacts(c) } catch (e: Exception) {}
            synchronized(this) {
                loading = false
                if (pending) { pending = false; again = true }
            }
            if (again) loadContactsAsync(c)
        }.start()
    }

    private fun loadContacts(c: Context) {
        if (!Contacts.hasPermission(c)) return
        val set = HashSet<String>(512)
        val nm = HashMap<String, String>(256)
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val d = Store.digits(cur.getString(0) ?: "")
                if (d.isEmpty()) continue
                val name = cur.getString(1) ?: ""
                set.add(d)
                if (d.length >= 10) set.add(d.takeLast(10))
                if (name.isNotBlank()) {
                    nm[d] = name
                    if (d.length >= 10) nm[d.takeLast(10)] = name
                }
            }
        }
        synchronized(lock) {
            contacts.clear(); contacts.addAll(set)
            names.clear(); names.putAll(nm)
            ready = true
        }
    }

    // ---------- Consulta ----------

    private fun keysFor(number: String): List<String> {
        val d = Store.digits(number)
        if (d.isEmpty()) return emptyList()
        return if (d.length >= 10) listOf(d, d.takeLast(10)) else listOf(d)
    }

    private fun inSet(set: HashSet<String>, number: String): Boolean {
        val ks = keysFor(number)
        if (ks.isEmpty()) return false
        synchronized(lock) {
            for (k in ks) if (k in set) return true
        }
        return false
    }

    /** true/false = decisión de caché; null = caché aún no lista (usar respaldo). */
    fun has(c: Context, number: String): Boolean? {
        if (!ready) return null
        if (!Contacts.hasPermission(c)) return false
        val ks = keysFor(number)
        if (ks.isEmpty()) return false
        synchronized(lock) {
            for (k in ks) if (k in contacts) return true
        }
        return false
    }

    fun contactName(number: String): String? {
        if (!ready) return null
        val ks = keysFor(number)
        if (ks.isEmpty()) return null
        synchronized(lock) {
            for (k in ks) { val n = names[k]; if (n != null) return n }
        }
        return null
    }

    /**
     * DECISIÓN COMPLETA SOLO EN RAM (mismo criterio que el camino lento).
     * Devuelve null si la caché no está lista todavía (proceso recién
     * arrancado): entonces el servicio usa el respaldo con proveedores.
     */
    fun decide(c: Context, raw: String, isPrivate: Boolean): Decision? {
        if (!ready) return null
        val hasC = Contacts.hasPermission(c)
        val strict = Store.strictMode(c) && hasC

        if (!isPrivate && Store.isEmergency(raw)) {
            return Decision(false, "emergencia", "Número de emergencia: siempre pasa")
        }
        if (!isPrivate && inSet(blackKeys, raw)) {
            return Decision(true, "lista_negra", "En tu lista negra")
        }
        if (isPrivate) {
            return if (strict || Store.blockPrivate(c))
                Decision(true, "privado", "Número privado")
            else
                Decision(false, "privado", "Privado permitido (ajuste)")
        }
        if (hasC && has(c, raw)) {
            val n = contactName(raw) ?: ""
            return Decision(false, "contacto", "Contacto: $n")
        }
        if (strict) {
            return Decision(true, "estricto", "Modo estricto: solo tus contactos pasan")
        }
        if (inSet(whiteKeys, raw)) {
            return Decision(false, "lista_blanca", "Lista blanca EscudoCall")
        }
        if (Store.isInternational(raw)) {
            return if (Store.blockIntl(c))
                Decision(true, "internacional", "Número internacional")
            else
                Decision(false, "internacional", "Internacional permitido (ajuste)")
        }
        return if (Store.blockUnknown(c) && hasC) {
            Decision(true, "desconocido", "No está en tus contactos")
        } else if (!hasC) {
            Decision(false, "desconocido", "Sin permiso de contactos")
        } else {
            Decision(false, "desconocido", "Desconocido permitido (ajuste)")
        }
    }

    fun summary(): String {
        synchronized(lock) {
            return if (ready) {
                "${contacts.size} contactos · ${blackKeys.size} en negra"
            } else "cargando…"
        }
    }

    // ---------- Observador de contactos ----------

    /**
     * Si el usuario guarda un contacto nuevo, la caché se actualiza sola
     * en segundos (antes solo se refrescaba al reiniciar el servicio).
     */
    fun ensureObserver(c: Context) {
        if (observerRegistered) return
        synchronized(this) {
            if (observerRegistered) return
            try {
                val app = c.applicationContext
                app.contentResolver.registerContentObserver(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    true,
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            try { refresh(app) } catch (e: Exception) {}
                        }
                    }
                )
                observerRegistered = true
            } catch (e: Exception) {
            }
        }
    }
}
