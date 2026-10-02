package com.escudocall.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Almacenamiento local: ajustes, lista negra, lista blanca e historial. */
object Store {

    data class BlEntry(val phone: String, val name: String)
    data class HistEntry(
        val phone: String,
        val name: String,
        val type: String,
        val action: String,
        val reason: String,
        val at: Long,
        var synced: Boolean
    )

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("escudo", Context.MODE_PRIVATE)

    // ---------- Ajustes ----------
    fun blockUnknown(c: Context) = sp(c).getBoolean("block_unknown", true)
    fun blockPrivate(c: Context) = sp(c).getBoolean("block_private", true)
    fun blockIntl(c: Context) = sp(c).getBoolean("block_intl", false)
    fun notifyOn(c: Context) = sp(c).getBoolean("notify", true)

    fun setFlag(c: Context, key: String, value: Boolean) {
        sp(c).edit().putBoolean(key, value).apply()
    }

    // ---------- Lista negra ----------
    fun blacklist(c: Context): MutableList<BlEntry> {
        val out = mutableListOf<BlEntry>()
        return try {
            val arr = JSONArray(sp(c).getString("blacklist", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(BlEntry(o.optString("phone"), o.optString("name")))
            }
            out
        } catch (e: Exception) {
            out
        }
    }

    fun saveBlacklist(c: Context, list: List<BlEntry>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("phone", e.phone).put("name", e.name))
        sp(c).edit().putString("blacklist", arr.toString()).apply()
    }

    fun inBlacklist(c: Context, number: String): Boolean =
        blacklist(c).any { sameNumber(it.phone, number) }

    fun addBlacklist(c: Context, phone: String, name: String) {
        val list = blacklist(c)
        if (list.none { sameNumber(it.phone, phone) }) {
            list.add(BlEntry(phone, name))
            saveBlacklist(c, list)
        }
    }

    fun removeBlacklist(c: Context, phone: String) {
        saveBlacklist(c, blacklist(c).filterNot { sameNumber(it.phone, phone) })
    }

    // ---------- Lista blanca (desde EscudoCall web) ----------
    fun whitelist(c: Context): List<String> {
        return try {
            val arr = JSONArray(sp(c).getString("whitelist", "[]"))
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveWhitelist(c: Context, list: List<String>) {
        val arr = JSONArray()
        for (p in list) arr.put(p)
        sp(c).edit().putString("whitelist", arr.toString()).apply()
    }

    fun inWhitelist(c: Context, number: String): Boolean =
        whitelist(c).any { sameNumber(it, number) }

    // ---------- Historial ----------
    private fun histFile(c: Context) = File(c.filesDir, "history.json")

    @Synchronized
    fun history(c: Context): MutableList<HistEntry> {
        val out = mutableListOf<HistEntry>()
        return try {
            val arr = JSONArray(histFile(c).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    HistEntry(
                        o.optString("phone"),
                        o.optString("name"),
                        o.optString("type"),
                        o.optString("action"),
                        o.optString("reason"),
                        o.optLong("at"),
                        o.optBoolean("s", false)
                    )
                )
            }
            out
        } catch (e: Exception) {
            out
        }
    }

    @Synchronized
    fun addHistory(c: Context, e: HistEntry) {
        val list = history(c)
        list.add(0, e)
        if (list.size > 500) list.subList(500, list.size).clear()
        writeHist(c, list)
    }

    @Synchronized
    fun markSynced(c: Context, at: Long) {
        val list = history(c)
        for (e in list) if (e.at == at) e.synced = true
        writeHist(c, list)
    }

    @Synchronized
    fun clearHistory(c: Context) {
        histFile(c).delete()
    }

    private fun writeHist(c: Context, list: List<HistEntry>) {
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(
                    JSONObject()
                        .put("phone", e.phone)
                        .put("name", e.name)
                        .put("type", e.type)
                        .put("action", e.action)
                        .put("reason", e.reason)
                        .put("at", e.at)
                        .put("s", e.synced)
                )
            }
            histFile(c).writeText(arr.toString())
        } catch (e: Exception) {
        }
    }

    // ---------- Sincronización ----------
    fun lastSync(c: Context) = sp(c).getLong("last_sync", 0L)
    fun setLastSync(c: Context, t: Long) {
        sp(c).edit().putLong("last_sync", t).apply()
    }

    // ---------- Números ----------
    fun digits(s: String) = s.filter { it.isDigit() }

    fun sameNumber(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        val da = digits(a)
        val db = digits(b)
        if (da.isEmpty() || db.isEmpty()) return false
        if (da == db) return true
        val la = if (da.length >= 10) da.takeLast(10) else da
        val lb = if (db.length >= 10) db.takeLast(10) else db
        return la == lb
    }

    fun isInternational(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("+") && !t.startsWith("+52")
    }
}
