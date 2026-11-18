package com.example.base

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
abstract class FirestoreEmulatorTestBase {

    protected lateinit var context: Context
    protected lateinit var firestore: FirebaseFirestore
    protected lateinit var auth: FirebaseAuth
    protected lateinit var databaseId: String

    @Before
    open fun setUpFirebase() {
        context = ApplicationProvider.getApplicationContext()
        val resId = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
        databaseId = if (resId != 0) {
            context.getString(resId)
        } else {
            "ai-studio-android-bleskovf-056698ab-4e20-4c12-b4ec-52ee9d53a81d"
        }

        // Never reuse the default app: it may point to a real project.
        // Auth caches emulator listeners by API key, so keep this app alive until the test JVM exits.
        // Gradle's forkEvery = 1 isolates test classes in separate JVMs.
        val existingApp = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
        val app = existingApp ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId("sk.bizagent.bleskovafaktura")
                .setProjectId(PROJECT_ID)
                .setApiKey(API_KEY)
                .build(),
            APP_NAME
        )
        check(app.options.projectId == PROJECT_ID && app.options.apiKey == API_KEY) {
            "The Firebase emulator test app must use isolated demo options."
        }
        firestore = FirebaseFirestore.getInstance(app, databaseId)
        auth = FirebaseAuth.getInstance(app)
        if (existingApp == null) {
            firestore.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
            firestore.firestoreSettings = FirebaseFirestoreSettings.Builder(firestore.firestoreSettings)
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
            auth.useEmulator(EMULATOR_HOST, AUTH_PORT)
        }
        check(firestore.firestoreSettings.host == "$EMULATOR_HOST:$FIRESTORE_PORT" &&
            !firestore.firestoreSettings.isSslEnabled) { "Firestore tests must use the local emulator." }
    }

    @After
    open fun tearDownFirebase() {
        if (::auth.isInitialized) auth.signOut()
    }

    protected suspend fun signInTestUser(email: String): String = withContext(Dispatchers.IO) {
        withTimeout(AUTH_TIMEOUT_MS) {
            val result = try {
                auth.signInWithEmailAndPassword(email, DEFAULT_PASSWORD).await()
            } catch (_: FirebaseAuthInvalidUserException) {
                auth.createUserWithEmailAndPassword(email, DEFAULT_PASSWORD).await()
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
        const val APP_NAME = "firestore-emulator-tests"
        const val API_KEY = "fake-api-key-for-emulator"
        const val EMULATOR_HOST = "127.0.0.1"
        const val FIRESTORE_PORT = 8085
        const val AUTH_PORT = 9099
        const val PROJECT_ID = "demo-no-project"
        const val DEFAULT_PASSWORD = "password123"
        const val AUTH_TIMEOUT_MS = 5000L
    }
}
