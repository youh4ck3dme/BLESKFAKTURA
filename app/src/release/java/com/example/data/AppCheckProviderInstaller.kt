package com.example.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.R
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

internal fun installAppCheckProvider(context: Context, @Suppress("UNUSED_PARAMETER") intent: Intent?) {
    val siteKey = runCatching { context.getString(R.string.recaptcha_site_key) }.getOrNull().orEmpty()
    if (siteKey.isNotBlank()) {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            RecaptchaAppCheckProviderFactory.getInstance()
        )
        Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
    } else {
        Log.e(TAG, "recaptcha_site_key string resource is blank or missing.")
    }
}
