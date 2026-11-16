package com.example.base

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
abstract class FirestoreEmulatorTestBase {

    protected lateinit var context: Context
    protected lateinit var firestore: FirebaseFirestore
    protected lateinit var auth: FirebaseAuth
    protected lateinit var databaseId: String
    private lateinit var emulatorApp: FirebaseApp

    @Before
    open fun setUpFirebase() {
        context = ApplicationProvider.getApplicationContext()
        val resId = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
        databaseId = if (resId != 0) {
            context.getString(resId)
        } else {
            "ai-studio-android-bleskovf-056698ab-4e20-4c12-b4ec-52ee9d53a81d"
        }

        // Never borrow the default FirebaseApp: a build can contain real release configuration.
        val instanceId = UUID.randomUUID().toString().replace("-", "")
        // Firebase Auth's emulator registry is keyed by API key. Reusing one key across
        // deleted apps can notify an Auth instance whose FirebaseApp was already deleted.
        val emulatorApiKey = "AIza" + instanceId + "abc"
        emulatorApp = FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId("1:1234567890:android:abcdef1234567890")
                .setProjectId(PROJECT_ID)
                .setApiKey(emulatorApiKey)
                .build(),
            "repository-emulator-$instanceId"
        )
        firestore = FirebaseFirestore.getInstance(emulatorApp, databaseId)
        firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
        // Set the endpoint last; a new settings builder after useEmulator would reset the host.
        firestore.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
        check(firestore.firestoreSettings.host == "$EMULATOR_HOST:$FIRESTORE_PORT")
        check(emulatorApp.options.projectId == PROJECT_ID)
        auth = FirebaseAuth.getInstance(emulatorApp)
        auth.useEmulator(EMULATOR_HOST, AUTH_PORT)
    }

    @After
    open fun tearDownFirebase() {
        if (::auth.isInitialized) auth.signOut()
        try {
            if (::firestore.isInitialized) runBlocking {
                withTimeout(5000L) { firestore.terminate().await() }
            }
        } finally {
            if (::emulatorApp.isInitialized) emulatorApp.delete()
        }
    }

    protected suspend fun signInTestUser(email: String): String = withContext(Dispatchers.IO) {
        withTimeout(AUTH_TIMEOUT_MS) {
            val result = try {
                auth.createUserWithEmailAndPassword(email, DEFAULT_PASSWORD).await()
            } catch (_: FirebaseAuthUserCollisionException) {
                auth.signInWithEmailAndPassword(email, DEFAULT_PASSWORD).await()
            }
            checkNotNull(result.user?.uid) { "User auth failed" }
        }
    }

    protected fun Throwable.unwrapFirestoreException(): FirebaseFirestoreException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is FirebaseFirestoreException) return current
            current = current.cause
        }
        return null
    }

    private companion object {
        const val EMULATOR_HOST = "127.0.0.1"
        const val FIRESTORE_PORT = 8085
        const val AUTH_PORT = 9099
        const val PROJECT_ID = "demo-no-project"
        const val DEFAULT_PASSWORD = "password123"
        const val AUTH_TIMEOUT_MS = 5000L
    }
}
