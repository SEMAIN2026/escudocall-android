package com.escudocall.app

import android.app.Application

/**
 * Arranque del proceso. Cuando el sistema despierta EscudoCall para filtrar
 * una llamada, esto corre ANTES que onScreenCall: llenamos la caché RAM
 * (listas al instante, contactos en segundo plano) para que la decisión
 * sea de microsegundos. Nada de red ni servicios aquí.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            ContactCache.syncLists(this)
            ContactCache.refresh(this)
            ContactCache.ensureObserver(this)
        } catch (e: Exception) {
        }
    }
}
