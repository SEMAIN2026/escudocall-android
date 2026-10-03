package com.escudocall.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val es = Locale("es")

    private lateinit var statusChip: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var btnActivate: Button
    private lateinit var permContactsText: TextView
    private lateinit var permContactsBtn: TextView
    private lateinit var permNotifText: TextView
    private lateinit var permNotifBtn: TextView
    private lateinit var swStrict: Switch
    private lateinit var swUnknown: Switch
    private lateinit var swPrivate: Switch
    private lateinit var swIntl: Switch
    private lateinit var swNotify: Switch
    private lateinit var inputVerify: EditText
    private lateinit var btnVerify: Button
    private lateinit var verifyResult: TextView
    private lateinit var inputBlack: EditText
    private lateinit var btnAddBlack: Button
    private lateinit var listBlacklist: LinearLayout
    private lateinit var blackEmpty: TextView
    private lateinit var listHistory: LinearLayout
    private lateinit var histEmpty: TextView
    private lateinit var btnSync: Button
    private lateinit var btnUpdate: Button
    private lateinit var syncText: TextView

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> find(id: Int): T = findViewById(id) as T

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusChip = find(R.id.statusChip)
        statusTitle = find(R.id.statusTitle)
        statusSub = find(R.id.statusSub)
        btnActivate = find(R.id.btnActivate)
        permContactsText = find(R.id.permContactsText)
        permContactsBtn = find(R.id.permContactsBtn)
        permNotifText = find(R.id.permNotifText)
        permNotifBtn = find(R.id.permNotifBtn)
        swStrict = find(R.id.swStrict)
        swUnknown = find(R.id.swUnknown)
        swPrivate = find(R.id.swPrivate)
        swIntl = find(R.id.swIntl)
        swNotify = find(R.id.swNotify)
        inputVerify = find(R.id.inputVerify)
        btnVerify = find(R.id.btnVerify)
        verifyResult = find(R.id.verifyResult)
        inputBlack = find(R.id.inputBlack)
        btnAddBlack = find(R.id.btnAddBlack)
        listBlacklist = find(R.id.listBlacklist)
        blackEmpty = find(R.id.blackEmpty)
        listHistory = find(R.id.listHistory)
        histEmpty = find(R.id.histEmpty)
        btnSync = find(R.id.btnSync)
        btnUpdate = find(R.id.btnUpdate)
        syncText = find(R.id.syncText)

        swStrict.setOnCheckedChangeListener { _, checked ->
            Store.setFlag(this, "strict", checked)
            if (checked) {
                Toast.makeText(
                    this,
                    "Modo estricto: solo tus contactos y el 911 pasan",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        swUnknown.setOnCheckedChangeListener { _, checked ->
            Store.setFlag(this, "block_unknown", checked)
        }
        swPrivate.setOnCheckedChangeListener { _, checked ->
            Store.setFlag(this, "block_private", checked)
        }
        swIntl.setOnCheckedChangeListener { _, checked ->
            Store.setFlag(this, "block_intl", checked)
        }
        swNotify.setOnCheckedChangeListener { _, checked ->
            Store.setFlag(this, "notify", checked)
        }

        btnActivate.setOnClickListener { requestRole() }

        permContactsBtn.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1)
        }

        permNotifBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            } else {
                Toast.makeText(this, "En tu Android las notificaciones ya están permitidas", Toast.LENGTH_SHORT).show()
            }
        }

        btnVerify.setOnClickListener { showVerdict(inputVerify.text.toString().trim()) }

        btnAddBlack.setOnClickListener { addToBlacklist(inputBlack.text.toString().trim()) }

        btnSync.setOnClickListener { doSync(manual = true) }

        btnUpdate.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/SEMAIN2026/escudocall-android/releases/latest/download/EscudoCall.apk")
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(this, "No encontré un navegador para descargar", Toast.LENGTH_SHORT).show()
            }
        }

        find<TextView>(R.id.btnClearHist).setOnClickListener {
            Store.clearHistory(this)
            renderHistory()
            Toast.makeText(this, "Historial limpiado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        if (Turso.enabled) maybeAutoSync()
    }

    // ---------- Rol de screening (el permiso REAL de bloqueo) ----------

    private fun roleHeld(): Boolean {
        val rm = getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun requestRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            Toast.makeText(this, "Tu teléfono no ofrece el rol de filtro de llamadas", Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 10)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10) refreshAll()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshAll()
    }

    // ---------- Render ----------

    private fun refreshAll() {
        renderStatus()
        renderPerms()
        renderSwitches()
        renderBlacklist()
        renderHistory()
        renderSync()
    }

    private fun renderStatus() {
        val held = roleHeld()
        val hasContacts = Contacts.hasPermission(this)
        val diag = "Diagnóstico · Rol de filtrado: ${if (held) "OK" else "FALTA"} · " +
            "Contactos: ${if (hasContacts) "OK" else "FALTA"} · " +
            "Modo estricto: ${if (Store.strictMode(this)) "ON" else "off"}"

        when {
            held && hasContacts -> {
                statusChip.text = "ACTIVO"
                statusChip.setBackgroundResource(R.drawable.bg_chip_on)
                statusTitle.text = "Protección activa"
                statusTitle.setTextColor(getColor(R.color.green))
                statusSub.text = if (Store.strictMode(this)) {
                    "Modo estricto ON: corta todo lo que no sea tu gente. Revisa el historial para ver el motivo de cada llamada."
                } else {
                    "EscudoCall está filtrando cada llamada entrante: tus contactos pasan siempre, lo demás depende de tus filtros."
                }
                btnActivate.text = "Protección activada"
                btnActivate.isEnabled = false
                btnActivate.alpha = 0.55f
            }
            held -> {
                statusChip.text = "A MEDIAS"
                statusChip.setBackgroundResource(R.drawable.bg_chip_off)
                statusTitle.text = "Sin permiso de contactos"
                statusTitle.setTextColor(getColor(R.color.amber))
                statusSub.text = "El filtro está activo, pero SIN el permiso de contactos no sé quién es tu gente y por seguridad NO corto desconocidos. Dale el permiso en la tarjeta de abajo."
                btnActivate.text = "Protección activada"
                btnActivate.isEnabled = false
                btnActivate.alpha = 0.55f
            }
            else -> {
                statusChip.text = "INACTIVO"
                statusChip.setBackgroundResource(R.drawable.bg_chip_off)
                statusTitle.text = "Protección inactiva"
                statusTitle.setTextColor(getColor(R.color.red))
                statusSub.text = "Ninguna llamada está siendo filtrada. Toca el botón y acepta la ventana del sistema «Identificación de llamadas y spam». Si ya lo habías activado, el teléfono quitó el permiso: actívalo otra vez."
                btnActivate.text = "Activar protección"
                btnActivate.isEnabled = true
                btnActivate.alpha = 1f
            }
        }
        statusSub.text = "${statusSub.text}\n$diag"
    }

    private fun renderPerms() {
        val hasContacts = Contacts.hasPermission(this)
        permContactsText.text = if (hasContacts) {
            val n = Contacts.count(this)
            if (n >= 0) "Concedido · $n contactos" else "Concedido"
        } else {
            "Falta: sin esto no puedo saber quién es tu gente"
        }
        permContactsBtn.visibility = if (hasContacts) View.GONE else View.VISIBLE

        val hasNotif = if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        permNotifText.text = if (hasNotif) "Concedido" else "Opcional: aviso cuando se corta una llamada"
        permNotifBtn.visibility = if (hasNotif) View.GONE else View.VISIBLE
    }

    private fun renderSwitches() {
        swStrict.isChecked = Store.strictMode(this)
        swUnknown.isChecked = Store.blockUnknown(this)
        swPrivate.isChecked = Store.blockPrivate(this)
        swIntl.isChecked = Store.blockIntl(this)
        swNotify.isChecked = Store.notifyOn(this)
    }

    private fun showVerdict(number: String) {
        if (number.isBlank()) {
            verifyResult.text = "Escribe un número para verificar."
            verifyResult.setTextColor(getColor(R.color.muted))
            return
        }
        val isPriv = number.equals("privado", true) ||
            number.equals("private", true) ||
            number.equals("unknown", true)
        val name = if (isPriv) null else Contacts.displayName(this, number)
        val strict = Store.strictMode(this) && Contacts.hasPermission(this)

        val msg: String
        val colorRes: Int
        when {
            !isPriv && Store.isEmergency(number) -> {
                msg = "Pasaría: número de emergencia (siempre pasa)."; colorRes = R.color.green
            }
            !isPriv && Store.inBlacklist(this, number) -> {
                msg = "Se CORTARÍA: está en tu lista negra."; colorRes = R.color.red
            }
            isPriv && (strict || Store.blockPrivate(this)) -> {
                msg = "Se CORTARÍA: número privado u oculto."; colorRes = R.color.red
            }
            isPriv -> {
                msg = "Pasaría: los privados están permitidos en tus ajustes."; colorRes = R.color.green
            }
            name != null -> {
                msg = "Pasaría: es tu contacto ($name)."; colorRes = R.color.green
            }
            strict -> {
                msg = "Se CORTARÍA: modo estricto — solo tus contactos pasan."; colorRes = R.color.red
            }
            Store.inWhitelist(this, number) -> {
                msg = "Pasaría: está en tu lista blanca."; colorRes = R.color.green
            }
            Store.isInternational(number) && Store.blockIntl(this) -> {
                msg = "Se CORTARÍA: número internacional."; colorRes = R.color.red
            }
            Store.blockUnknown(this) && Contacts.hasPermission(this) -> {
                msg = "Se CORTARÍA: no está en tus contactos."; colorRes = R.color.red
            }
            !Contacts.hasPermission(this) -> {
                msg = "Sin el permiso de contactos no puedo confirmar; dale el permiso arriba."
                colorRes = R.color.muted
            }
            else -> {
                msg = "Pasaría: los desconocidos están permitidos en tus ajustes."; colorRes = R.color.green
            }
        }
        verifyResult.text = msg
        verifyResult.setTextColor(getColor(colorRes))
    }

    private fun addToBlacklist(number: String) {
        if (number.isBlank()) {
            Toast.makeText(this, "Escribe un número", Toast.LENGTH_SHORT).show()
            return
        }
        Store.addBlacklist(this, number, Contacts.displayName(this, number) ?: "")
        inputBlack.setText("")
        renderBlacklist()
    }

    private fun renderBlacklist() {
        listBlacklist.removeAllViews()
        val items = Store.blacklist(this)
        blackEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (e in items) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val t1 = TextView(this)
            t1.text = if (e.name.isNotBlank()) "${e.name} · ${e.phone}" else e.phone
            t1.textSize = 14f
            t1.setTextColor(getColor(R.color.text))

            val t2 = TextView(this)
            t2.text = "Se corta siempre"
            t2.textSize = 11f
            t2.setTextColor(getColor(R.color.muted))

            col.addView(t1)
            col.addView(t2)

            val del = TextView(this)
            del.text = "Quitar"
            del.textSize = 13f
            del.setTypeface(null, Typeface.BOLD)
            del.setTextColor(getColor(R.color.red))
            del.setPadding(dp(12), dp(6), dp(4), dp(6))
            del.setOnClickListener {
                Store.removeBlacklist(this, e.phone)
                renderBlacklist()
            }

            row.addView(col)
            row.addView(del)
            listBlacklist.addView(row)
        }
    }

    private fun renderHistory() {
        listHistory.removeAllViews()
        val items = Store.history(this).take(60)
        histEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("d MMM · HH:mm", es)

        for (e in items) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(10), dp(9), dp(10), dp(9))
            row.setBackgroundResource(
                if (e.action == "blocked") R.drawable.bg_badge_block else R.drawable.bg_badge_allow
            )
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            row.layoutParams = lp

            val shown = if (e.phone.isBlank()) "Número privado" else pretty(e.phone)

            val t1 = TextView(this)
            t1.text = if (e.action == "blocked") "BLOQUEADA · $shown" else "PERMITIDA · $shown"
            t1.textSize = 12f
            t1.setTypeface(null, Typeface.BOLD)
            t1.setTextColor(getColor(if (e.action == "blocked") R.color.red else R.color.green))

            val t2 = TextView(this)
            t2.text = e.reason
            t2.textSize = 12f
            t2.setTextColor(getColor(R.color.text))

            val t3 = TextView(this)
            t3.text = fmt.format(Date(e.at))
            t3.textSize = 10f
            t3.setTextColor(getColor(R.color.muted))

            row.addView(t1)
            row.addView(t2)
            row.addView(t3)
            listHistory.addView(row)
        }
    }

    private fun renderSync() {
        val t = Store.lastSync(this)
        syncText.text = if (t == 0L) {
            "Sin sincronizar todavía."
        } else {
            "Última sincronización: " + SimpleDateFormat("d MMM · HH:mm", es).format(Date(t))
        }
    }

    // ---------- Sincronización ----------

    private fun maybeAutoSync() {
        if (System.currentTimeMillis() - Store.lastSync(this) < 5 * 60 * 1000) return
        doSync(manual = false)
    }

    private fun doSync(manual: Boolean) {
        if (!Turso.enabled) {
            if (manual) {
                Toast.makeText(this, "Sincronización no configurada en este build", Toast.LENGTH_SHORT).show()
            }
            return
        }
        Thread {
            val ok = try {
                Sync.run(this)
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (manual) {
                    Toast.makeText(
                        this,
                        if (ok) "Sincronizado con EscudoCall" else "No pude sincronizar ahora",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                renderSync()
                renderBlacklist()
            }
        }.start()
    }

    // ---------- Utilidades ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pretty(p: String): String = if (p.startsWith("+")) p else "+$p"
}
