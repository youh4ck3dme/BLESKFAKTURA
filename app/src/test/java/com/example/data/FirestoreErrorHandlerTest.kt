package com.example.data

import com.google.firebase.firestore.FirebaseFirestoreException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36])
class FirestoreErrorHandlerTest {
    @Test
    fun wrappedSdkFailure_keepsCodeWithoutLeakingPayloadOrDocumentPath() {
        val privatePayload = "synthetic-secret receipt-content person@example.invalid"
        val sdkError = FirebaseFirestoreException(
            privatePayload,
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
        val result = handleFirestoreError(
            IllegalStateException(privatePayload, sdkError),
            OperationType.WRITE,
            "users/private-uid/invoices/private-document",
            null
        )

        val json = JSONObject(result)
        assertEquals(2, json.length())
        assertEquals("write", json.getString("operationType"))
        assertEquals("PERMISSION_DENIED", json.getString("code"))
        val log = ShadowLog.getLogsForTag("FirestoreError").last()
        assertFalse(log.msg.contains(privatePayload))
        assertFalse(log.msg.contains("private-uid"))
        assertFalse(log.msg.contains("private-document"))
        assertFalse(result.contains(privatePayload))
        assertNull(log.throwable)
    }

    @Test
    fun nonSdkFailure_usesFixedUnknownCodeWithoutFirebaseInitialization() {
        val result = handleFirestoreError(
            IllegalArgumentException("synthetic-sensitive-detail"),
            OperationType.GET,
            null
        )

        assertEquals("UNKNOWN", JSONObject(result).getString("code"))
        assertFalse(result.contains("synthetic-sensitive-detail"))
        assertNull(ShadowLog.getLogsForTag("FirestoreError").last().throwable)
    }
}
