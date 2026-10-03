package com.escudocall.app

import android.content.Context
import android.provider.ContactsContract

/**
 * Caché de contactos en RAM.
 *
 * La ventana del sistema para responder una llamada es de ~5 segundos desde
 * que se liga el servicio de screening. Si el teléfono mató el proceso
 * (típico en Xiaomi/Infinix/Tecno), arrancarlo de frío + consultar el proveedor
 * de contactos puede agotar la ventana y la llamada PASA por defecto.
 *
 * Esta caché mantiene en memoria los dígitos normalizados de todos los
 * contactos (número completo + últimos 10), para decidir en microsegundos.
 */
object ContactCache {

    @Volatile
    private var ready = false

    private val keys = HashSet<String>()

    fun refresh(c: Context) {
        if (!Contacts.hasPermission(c)) return
        Thread {
            try {
                val set = HashSet<String>(512)
                c.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val d = Store.digits(cur.getString(0) ?: "")
                        if (d.isNotEmpty()) {
                            set.add(d)
                            if (d.length >= 10) set.add(d.takeLast(10))
                        }
                    }
                }
                synchronized(keys) {
                    keys.clear()
                    keys.addAll(set)
                    ready = true
                }
            } catch (e: Exception) {
                // la caché vieja (o ninguna) se queda; la decisión usa respaldo
            }
        }.start()
    }

    /**
     * true/false = decisión de caché; null = caché aún no lista (usar respaldo).
     */
    fun has(c: Context, number: String): Boolean? {
        if (!ready) return null
        if (!Contacts.hasPermission(c)) return false
        val d = Store.digits(number)
        if (d.isEmpty()) return false
        synchronized(keys) {
            if (d in keys) return true
            if (d.length >= 10 && d.takeLast(10) in keys) return true
        }
        return false
    }
}
