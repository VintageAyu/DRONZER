package com.example.dronzer

import android.net.Uri
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.io.FileInputStream

object FirebaseManager {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    private fun createEntry(folderName: String, type: String, content: String, fileUrl: String? = null) {
        val deviceId = DeviceManager.deviceId
        val timestamp = System.currentTimeMillis()
        val typeSlug = type.lowercase().replace(" ", "_")
        val docId = "${timestamp}_$typeSlug"

        val logData = hashMapOf(
            "type" to type.uppercase(),
            "content" to content,
            "url" to (fileUrl ?: "N/A"),
            "timestamp" to FieldValue.serverTimestamp(),
            "deviceModel" to android.os.Build.MODEL,
            "deviceId" to deviceId
        )

        db.collection("devices").document(deviceId)
            .collection(folderName.lowercase())
            .document(docId)
            .set(logData)
            .addOnSuccessListener {
                Log.d("FirebaseManager", "SUCCESS: Entry $docId created")
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseManager", "FAILURE: Firestore entry creation failed", e)
            }
    }

    fun uploadReport(type: String, content: String) {
        createEntry("reports", type, content)
    }

    /**
     * Uploads a file using InputStream to avoid "Object does not exist" errors
     * caused by file path/URI resolution issues in some Android versions.
     */
    fun uploadFile(file: File, folder: String, onComplete: (Boolean) -> Unit) {
        if (!file.exists() || file.length() == 0L) {
            Log.e("FirebaseManager", "File missing or empty: ${file.path}")
            onComplete(false)
            return
        }

        val deviceId = DeviceManager.deviceId
        val storageRef = storage.reference.child("uploads/$deviceId/$folder/${file.name}")
        
        try {
            val stream = FileInputStream(file)
            Log.d("FirebaseManager", "Starting Stream upload: ${file.name} (${file.length()} bytes)")

            storageRef.putStream(stream)
                .addOnSuccessListener {
                    storageRef.downloadUrl.addOnSuccessListener { uri ->
                        val url = uri.toString()
                        createEntry(folder, folder, "File Uploaded: ${file.name}", url)
                        onComplete(true)
                    }.addOnFailureListener { e ->
                        Log.e("FirebaseManager", "URL failed: ${e.message}")
                        createEntry(folder, folder, "Upload OK, URL Error: ${e.message}")
                        onComplete(false)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("FirebaseManager", "Stream upload failed: ${e.message}")
                    createEntry(folder, folder, "Upload Failed: ${e.message}")
                    onComplete(false)
                }
                .addOnCompleteListener {
                    try { stream.close() } catch (e: Exception) {}
                }
        } catch (e: Exception) {
            Log.e("FirebaseManager", "InputStream error: ${e.message}")
            onComplete(false)
        }
    }

    fun uploadBytes(bytes: ByteArray, fileName: String, folder: String) {
        val deviceId = DeviceManager.deviceId
        val storageRef = storage.reference.child("uploads/$deviceId/$folder/$fileName")

        Log.d("FirebaseManager", "Starting Byte upload: $fileName")

        storageRef.putBytes(bytes)
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { uri ->
                    createEntry(folder, folder, "Image Data Uploaded: $fileName", uri.toString())
                }
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseManager", "Byte upload failed", e)
                createEntry(folder, folder, "Image Upload Failed: $fileName")
            }
    }
}
