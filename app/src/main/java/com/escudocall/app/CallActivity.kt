package com.escudocall.app

import android.app.Activity
import android.os.Bundle
import android.telecom.Call
import android.telecom.VideoProfile
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * Pantalla mínima de llamada para el modo Blindaje (somos el marcador).
 * Solo para llamadas que SÍ pasan (contactos) y para llamadas salientes.
 * Las bloqueadas nunca llegan aquí: se cortan antes de timbrar.
 */
class CallActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var who: TextView
    private lateinit var btnAnswer: Button
    private lateinit var btnEnd: Button

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            runOnUiThread { render(state) }
        }

        override fun onDisconnected(call: Call, state: Int) {
            runOnUiThread { render(state) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : android.view.View> find(id: Int): T = findViewById(id) as T

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } catch (e: Exception) {
        }
        setContentView(R.layout.activity_call)

        status = find(R.id.callStatus)
        who = find(R.id.callWho)
        btnAnswer = find(R.id.btnAnswer)
        btnEnd = find(R.id.btnEnd)

        val c = BlindajeService.activeCall
        if (c == null) {
            finish()
            return
        }
        try { c.registerCallback(cb) } catch (e: Exception) {}

        val details = c.details
        var num = ""
        try {
            val h = details?.handle
            if (h != null && h.scheme == "tel") num = h.schemeSpecificPart ?: ""
        } catch (e: Exception) {
        }
        val nm = if (num.isBlank()) "" else (Contacts.displayName(this, num) ?: "")
        who.text = when {
            nm.isNotBlank() -> nm
            num.isNotBlank() -> if (num.startsWith("+")) num else "+$num"
            else -> "Número desconocido"
        }

        btnAnswer.setOnClickListener {
            try { c.answer(VideoProfile.STATE_AUDIO) } catch (e: Exception) {
                Toast.makeText(this, "No pude contestar", Toast.LENGTH_SHORT).show()
            }
        }
        btnEnd.setOnClickListener {
            try {
                if (c.state == Call.STATE_RINGING) c.reject(Call.REJECT_REASON_DECLINED)
                else c.hangup()
            } catch (e: Exception) {
                try { c.hangup() } catch (e2: Exception) {}
            }
            finish()
        }

        render(c.state)
    }

    private fun render(state: Int) {
        when (state) {
            Call.STATE_RINGING -> {
                status.text = "Llamada entrante"
                btnAnswer.visibility = android.view.View.VISIBLE
                btnEnd.text = "Rechazar"
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                status.text = "Llamando…"
                btnAnswer.visibility = android.view.View.GONE
                btnEnd.text = "Colgar"
            }
            Call.STATE_ACTIVE, Call.STATE_HOLDING -> {
                status.text = "En llamada"
                btnAnswer.visibility = android.view.View.GONE
                btnEnd.text = "Colgar"
            }
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING, Call.STATE_NEW -> {
                finish()
            }
        }
    }

    override fun onDestroy() {
        try { BlindajeService.activeCall?.unregisterCallback(cb) } catch (e: Exception) {}
        super.onDestroy()
    }
}
