package com.hy.assistant.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.hy.assistant.core.ContactMatcher
import com.hy.assistant.core.PhoneActions

/** Phone contacts lookup (needs READ_CONTACTS, asked the first time it's used). */
object Contacts {
    data class Contact(val name: String, val number: String)

    sealed interface Result {
        data class Found(val contact: Contact) : Result
        data class Ambiguous(val names: List<String>) : Result
        data object NotFound : Result
        data object NoPermission : Result
    }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun find(context: Context, query: String): Result {
        if (!hasPermission(context)) return Result.NoPermission
        val byName = LinkedHashMap<String, String>() // display name → first mobile/primary number
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.trim().orEmpty()
                val number = c.getString(1)?.trim().orEmpty()
                if (name.isEmpty() || number.isEmpty()) continue
                if (c.getInt(2) == 1 || name !in byName) byName[name] = number
            }
        }
        return when (val r = ContactMatcher.resolve(query, byName.keys)) {
            is ContactMatcher.Result.Unique -> Result.Found(Contact(r.name, byName.getValue(r.name)))
            is ContactMatcher.Result.Ambiguous -> Result.Ambiguous(r.candidates)
            ContactMatcher.Result.NotFound -> Result.NotFound
        }
    }

    /** Country calling code from the SIM (or network), for local numbers saved without "+…". */
    fun defaultCountryCode(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return PhoneActions.countryCode(tm?.simCountryIso?.ifBlank { null } ?: tm?.networkCountryIso)
    }
}
