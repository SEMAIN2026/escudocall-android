package com.escudocall.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cliente HTTP para la API pipeline v2 de Turso (libsql). Cero dependencias. */
object Turso {

    val url: String get() = BuildConfig.TURSO_URL.trimEnd('/')
    val token: String get() = BuildConfig.TURSO_TOKEN
    val enabled: Boolean get() = url.isNotBlank() && token.isNotBlank()

    private fun stmt(sql: String, args: List<String>): JSONObject {
        val s = JSONObject()
        s.put("sql", sql)
        if (args.isNotEmpty()) {
            val a = JSONArray()
            for (v in args) a.put(JSONObject().put("type", "text").put("value", v))
            s.put("args", a)
        }
        return JSONObject().put("type", "execute").put("stmt", s)
    }

    private fun pipeline(stmts: List<JSONObject>): JSONArray? {
        if (!enabled) return null
        var conn: HttpURLConnection? = null
        return try {
            val reqs = JSONArray()
            for (s in stmts) reqs.put(s)
            reqs.put(JSONObject().put("type", "close"))
            val body = JSONObject().put("requests", reqs)

            conn = URL("$url/v2/pipeline").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val text = conn.inputStream.bufferedReader().readText()
            JSONObject(text).optJSONArray("results")
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun pushCall(phone: String, name: String, numberType: String, action: String, reason: String): Boolean {
        val res = pipeline(
            listOf(
                stmt(
                    "INSERT INTO calls (phone, name, number_type, action, reason) VALUES (?, ?, ?, ?, ?)",
                    listOf(phone, name, numberType, action, reason)
                )
            )
        )
        return res != null && res.length() > 0
    }

    private fun rows(res: JSONArray?): JSONArray? = try {
        res?.getJSONObject(0)
            ?.optJSONObject("response")
            ?.optJSONObject("result")
            ?.optJSONArray("rows")
    } catch (e: Exception) {
        null
    }

    private fun rowValue(row: JSONObject?, i: Int): String = try {
        row?.optJSONArray("args")?.getJSONObject(i)?.optString("value") ?: ""
    } catch (e: Exception) {
        ""
    }

    fun pullBlacklist(): List<Store.BlEntry> {
        val r = rows(
            pipeline(
                listOf(
                    stmt("SELECT phone, name FROM blacklist ORDER BY created_at DESC LIMIT 500", emptyList())
                )
            )
        ) ?: return emptyList()

        val out = mutableListOf<Store.BlEntry>()
        for (i in 0 until r.length()) {
            val row = r.optJSONObject(i) ?: continue
            val phone = rowValue(row, 0)
            if (phone.isNotBlank()) out.add(Store.BlEntry(phone, rowValue(row, 1)))
        }
        return out
    }

    fun pullWhitelist(): List<String> {
        val r = rows(
            pipeline(
                listOf(
                    stmt("SELECT phone FROM whitelist ORDER BY created_at DESC LIMIT 500", emptyList())
                )
            )
        ) ?: return emptyList()

        val out = mutableListOf<String>()
        for (i in 0 until r.length()) {
            val p = rowValue(r.optJSONObject(i), 0)
            if (p.isNotBlank()) out.add(p)
        }
        return out
    }
}
