package com.lagradost.cloudstream3.analytics

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.lagradost.cloudstream3.BuildConfig

object FirebaseInitializer {

    private const val TAG = "OpenstreamFirebase"
    private var isInitialized = false

    /**
     * Initializes Firebase App, Analytics, Crashlytics, and Performance.
     * Guaranteed to never throw an exception or crash the application.
     */
    fun init(context: Context) {
        if (isInitialized) return

        try {
            Log.d(TAG, "Initializing Firebase services...")
            val app = FirebaseApp.initializeApp(context.applicationContext)
            
            if (app != null) {
                // Initialize Analytics
                FirebaseAnalytics.getInstance(context.applicationContext).apply {
                    setAnalyticsCollectionEnabled(true)
                }

                // Initialize Crashlytics
                FirebaseCrashlytics.getInstance().apply {
                    setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
                    setCustomKey("app_version", BuildConfig.VERSION_NAME)
                    setCustomKey("app_version_code", BuildConfig.VERSION_CODE)
                    setCustomKey("android_version", Build.VERSION.SDK_INT)
                    setCustomKey("device_manufacturer", Build.MANUFACTURER)
                    setCustomKey("device_model", Build.MODEL)
                }

                // Initialize Performance
                FirebasePerformance.getInstance().isPerformanceCollectionEnabled = !BuildConfig.DEBUG

                isInitialized = true
                Log.d(TAG, "Firebase services initialized successfully for app: ${app.name}")
            } else {
                Log.w(TAG, "FirebaseApp initialization returned null.")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Firebase services: ${e.message}", e)
            // App continues running smoothly - Never crash because Firebase failed
        }
    }

    fun isReady(): Boolean = isInitialized
}
