package com.example.data

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [24, 36])
class AppCheckDebugConfigurationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var app: FirebaseApp
    // Synthetic, never registered or exchanged. No network attestation is requested by these tests.
    private val syntheticSecret = "00000000-0000-4000-8000-000000000001"

    @Before fun initializeIsolatedDefaultApp() {
        check(FirebaseApp.getApps(context).isEmpty())
        app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId("demo-debug-config")
            .setApplicationId("1:1234567890:android:syntheticdebug")
            .setApiKey("fake-debug-api-key")
            .build())
        FirebaseAppCheck.getInstance(app).setTokenAutoRefreshEnabled(false)
    }

    @After fun deleteIsolatedApp() { if (::app.isInitialized) app.delete() }

    private fun preferences() = context.getSharedPreferences(
        "com.google.firebase.appcheck.debug.store.${app.persistenceKey}", Context.MODE_PRIVATE
    )

    @Test
    fun privateLaunchSecret_isPersistedForThisAppWithoutLoggingItsValue() {
        ShadowLog.clear()
        configureAppCheck(context, Intent().putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", syntheticSecret))
        assertEquals(syntheticSecret, preferences().getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null))
        val logs = ShadowLog.getLogsForTag("AppCheckInit")
        assertTrue(logs.any { it.msg.contains("DebugAppCheckProviderFactory installed") })
        assertFalse(logs.any { it.msg.contains(syntheticSecret) || it.throwable != null })
    }

    @Test
    fun absentLaunchExtra_doesNotEraseAnExistingInstallationSecret() {
        preferences().edit().putString("com.google.firebase.appcheck.debug.DEBUG_SECRET", syntheticSecret).commit()
        configureAppCheck(context, Intent())
        assertEquals(syntheticSecret, preferences().getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null))
    }
}
