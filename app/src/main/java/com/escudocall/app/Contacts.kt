package com.escudocall.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** Consulta de contactos: quién es tu gente y quién no. */
object Contacts {

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun displayName(c: Context, number: String): String? {
        if (!hasPermission(c) || number.isBlank()) return null
        val candidates = linkedSetOf(number.trim(), Store.digits(number))
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            try {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(candidate)
                )
                c.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null, null, null
                )?.use { cur ->
                    if (cur.moveToFirst()) {
                        val name = cur.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            } catch (e: Exception) {
                // sin permiso o error: seguimos con el siguiente candidato
            }
        }
        return null
    }

    fun isContact(c: Context, number: String): Boolean = displayName(c, number) != null

    fun count(c: Context): Int {
        if (!hasPermission(c)) return -1
        return try {
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone._ID),
                null, null, null
            )?.use { it.count } ?: 0
        } catch (e: Exception) {
            -1
        }
    }
}
