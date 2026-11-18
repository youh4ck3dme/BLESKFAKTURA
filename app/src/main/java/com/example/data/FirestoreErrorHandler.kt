package com.example.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    @Suppress("UNUSED_PARAMETER") path: String?,
    @Suppress("UNUSED_PARAMETER") auth: FirebaseAuth? = null
): String {
    // SDK messages, document paths and auth profiles can contain private account/receipt data.
    // Keep only the operation and the SDK's finite error-code enum, including wrapped errors.
    val firestoreError = generateSequence(exception) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    val errorInfoJson = JSONObject().apply {
        put("operationType", operationType.value)
        put("code", firestoreError?.code?.name ?: "UNKNOWN")
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore operation failed: $jsonString")
    return jsonString
}
