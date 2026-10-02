package com.escudocall.app

import android.content.Context

/**
 * Sincronización local <-> Turso (EscudoCall web):
 *  - Empuja el historial aún no sincronizado.
 *  - Trae la lista negra y la lista blanca del panel web y las fusiona.
 */
object Sync {

    fun run(c: Context): Boolean {
        if (!Turso.enabled) return false

        // 1) empujar historial no sincronizado
        val unsynced = Store.history(c).filter { !it.synced }
        for (e in unsynced) {
            val pushed = Turso.pushCall(
                if (e.phone.isBlank()) "privado" else e.phone,
                e.name,
                e.type,
                e.action,
                e.reason
            )
            if (pushed) Store.markSynced(c, e.at)
        }

        // 2) traer lista negra del panel web y fusionar
        val remote = Turso.pullBlacklist()
        if (remote.isNotEmpty()) {
            val merged = Store.blacklist(c)
            for (r in remote) {
                if (merged.none { Store.sameNumber(it.phone, r.phone) }) merged.add(r)
            }
            Store.saveBlacklist(c, merged)
        }

        // 3) traer lista blanca del panel web
        val wl = Turso.pullWhitelist()
        if (wl.isNotEmpty()) {
            Store.saveWhitelist(c, wl)
        }

        Store.setLastSync(c, System.currentTimeMillis())
        return true
    }
}
