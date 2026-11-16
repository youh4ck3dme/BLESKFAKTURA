package com.example.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

private const val TAG = "AppCheckInit"

internal fun installAppCheckProvider(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") intent: Intent?
) {
    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
        PlayIntegrityAppCheckProviderFactory.getInstance()
    )
    Log.d(TAG, "PlayIntegrityAppCheckProviderFactory installed.")
}
