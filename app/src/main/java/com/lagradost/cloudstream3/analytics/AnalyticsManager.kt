package com.lagradost.cloudstream3.analytics

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace
import com.lagradost.cloudstream3.BuildConfig
import java.util.concurrent.ConcurrentHashMap

object AnalyticsManager {

    private const val TAG = "OpenstreamAnalytics"
    private var firebaseAnalytics: FirebaseAnalytics? = null
    private val activeTraces = ConcurrentHashMap<String, Trace>()

    /**
     * Initializes AnalyticsManager with Application Context
     */
    fun init(context: Context) {
        try {
            FirebaseInitializer.init(context)
            if (FirebaseInitializer.isReady()) {
                firebaseAnalytics = FirebaseAnalytics.getInstance(context.applicationContext)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to setup AnalyticsManager: ${e.message}", e)
        }
    }

    // =========================================================================
    // 1. SCREEN TRACKING
    // =========================================================================

    fun logScreen(screenName: String) {
        val params = Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenName)
        }
        sendEvent(FirebaseAnalytics.Event.SCREEN_VIEW, "Screen View: $screenName", params)
    }

    // =========================================================================
    // 2. MOVIE & TV ANALYTICS
    // =========================================================================

    fun logMovieOpen(title: String, id: String?, provider: String?, type: String?, language: String? = null) {
        val params = Bundle().apply {
            putString("title", title)
            putString("movie_id", id ?: "unknown")
            putString("provider", provider ?: "unknown")
            putString("type", type ?: "unknown")
            putString("language", language ?: "en")
        }
        sendEvent("movie_open", "Movie Open", params)
    }

    fun logEpisodeOpen(title: String, id: String?, provider: String?, season: Int?, episode: Int?) {
        val params = Bundle().apply {
            putString("title", title)
            putString("series_id", id ?: "unknown")
            putString("provider", provider ?: "unknown")
            putInt("season", season ?: 0)
            putInt("episode", episode ?: 0)
        }
        sendEvent("episode_open", "Episode Open", params)
    }

    fun logPlaybackStarted(title: String, provider: String?, quality: String?, subtitleEnabled: Boolean, source: String?) {
        val params = Bundle().apply {
            putString("title", title)
            putString("provider", provider ?: "unknown")
            putString("quality", quality ?: "auto")
            putBoolean("subtitle_enabled", subtitleEnabled)
            putString("source", sanitizeUrl(source ?: "unknown"))
        }
        sendEvent("playback_started", "Playback Started", params)
    }

    fun logPlaybackCompleted(title: String, provider: String?, watchTimeSeconds: Long, completionPercent: Float) {
        val params = Bundle().apply {
            putString("title", title)
            putString("provider", provider ?: "unknown")
            putLong("watch_time_seconds", watchTimeSeconds)
            putFloat("completion_percent", completionPercent)
        }
        sendEvent("playback_completed", "Playback Completed", params)
    }

    fun logPlaybackError(provider: String?, errorType: String, errorMessage: String?) {
        val params = Bundle().apply {
            putString("provider", provider ?: "unknown")
            putString("error_type", errorType)
            putString("error_message", errorMessage ?: "unknown")
        }
        sendEvent("playback_error", "Playback Error", params)
        recordException(Exception("PlaybackError [$errorType]: $errorMessage"), provider = provider)
    }

    // =========================================================================
    // 3. SEARCH ANALYTICS
    // =========================================================================

    fun logSearch(query: String, resultCount: Int) {
        val params = Bundle().apply {
            putString(FirebaseAnalytics.Param.SEARCH_TERM, sanitizeQuery(query))
            putInt("result_count", resultCount)
        }
        sendEvent(FirebaseAnalytics.Event.SEARCH, "Search Performed", params)
    }

    fun logSearchSelect(query: String, selectedTitle: String, provider: String?) {
        val params = Bundle().apply {
            putString(FirebaseAnalytics.Param.SEARCH_TERM, sanitizeQuery(query))
            putString("selected_title", selectedTitle)
            putString("provider", provider ?: "unknown")
        }
        sendEvent("search_item_select", "Search Item Selected", params)
    }

    // =========================================================================
    // 4. EXTENSION ANALYTICS
    // =========================================================================

    fun logExtensionInstalled(pluginName: String, pluginId: String, version: Int) {
        val params = Bundle().apply {
            putString("extension_name", pluginName)
            putString("extension_id", pluginId)
            putInt("version", version)
        }
        sendEvent("extension_installed", "Extension Installed", params)
    }

    fun logExtensionUpdated(pluginName: String, pluginId: String, version: Int) {
        val params = Bundle().apply {
            putString("extension_name", pluginName)
            putString("extension_id", pluginId)
            putInt("version", version)
        }
        sendEvent("extension_updated", "Extension Updated", params)
    }

    fun logExtensionRemoved(pluginName: String, pluginId: String) {
        val params = Bundle().apply {
            putString("extension_name", pluginName)
            putString("extension_id", pluginId)
        }
        sendEvent("extension_removed", "Extension Removed", params)
    }

    fun logExtensionStatus(pluginName: String, enabled: Boolean) {
        val params = Bundle().apply {
            putString("extension_name", pluginName)
            putBoolean("enabled", enabled)
        }
        val eventName = if (enabled) "extension_enabled" else "extension_disabled"
        sendEvent(eventName, "Extension ${if (enabled) "Enabled" else "Disabled"}", params)
    }

    fun logExtensionFailed(pluginName: String, error: String?) {
        val params = Bundle().apply {
            putString("extension_name", pluginName)
            putString("error_message", error ?: "unknown")
        }
        sendEvent("extension_failed", "Extension Failed", params)
        recordException(Exception("ExtensionFailed [$pluginName]: $error"), extension = pluginName)
    }

    fun logRepositoryRefresh(url: String, durationMs: Long, extensionCount: Int, success: Boolean) {
        val params = Bundle().apply {
            putString("repo_url", sanitizeUrl(url))
            putLong("duration_ms", durationMs)
            putInt("extension_count", extensionCount)
            putBoolean("success", success)
        }
        sendEvent("repository_refreshed", "Repository Refreshed", params)
    }

    fun logRepositoryFailed(url: String, error: String?) {
        val params = Bundle().apply {
            putString("repo_url", sanitizeUrl(url))
            putString("error_message", error ?: "unknown")
        }
        sendEvent("repository_failed", "Repository Failed", params)
        recordException(Exception("RepositoryFailed [$url]: $error"))
    }

    // =========================================================================
    // 5. DOWNLOAD ANALYTICS
    // =========================================================================

    fun logDownloadStarted(title: String, quality: String?, provider: String?) {
        val params = Bundle().apply {
            putString("title", title)
            putString("quality", quality ?: "auto")
            putString("provider", provider ?: "unknown")
        }
        sendEvent("download_started", "Download Started", params)
    }

    fun logDownloadPaused(title: String) {
        val params = Bundle().apply { putString("title", title) }
        sendEvent("download_paused", "Download Paused", params)
    }

    fun logDownloadResumed(title: String) {
        val params = Bundle().apply { putString("title", title) }
        sendEvent("download_resumed", "Download Resumed", params)
    }

    fun logDownloadCancelled(title: String) {
        val params = Bundle().apply { putString("title", title) }
        sendEvent("download_cancelled", "Download Cancelled", params)
    }

    fun logDownloadCompleted(title: String, sizeBytes: Long, durationMs: Long) {
        val params = Bundle().apply {
            putString("title", title)
            putLong("size_bytes", sizeBytes)
            putLong("duration_ms", durationMs)
        }
        sendEvent("download_completed", "Download Completed", params)
    }

    fun logDownloadFailed(title: String, error: String?) {
        val params = Bundle().apply {
            putString("title", title)
            putString("error_message", error ?: "unknown")
        }
        sendEvent("download_failed", "Download Failed", params)
        recordException(Exception("DownloadFailed [$title]: $error"))
    }

    // =========================================================================
    // 6. SETTINGS ANALYTICS
    // =========================================================================

    fun logSettingsChanged(settingKey: String, newValue: String) {
        val params = Bundle().apply {
            putString("setting_key", settingKey)
            putString("new_value", newValue)
        }
        sendEvent("settings_changed", "Settings Changed", params)
    }

    // =========================================================================
    // 7. CRASHLYTICS & EXCEPTION RECORDING
    // =========================================================================

    fun recordException(
        throwable: Throwable,
        provider: String? = null,
        extension: String? = null,
        movie: String? = null
    ) {
        try {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Exception Recorded [$provider / $extension / $movie]: ${throwable.message}", throwable)
            }

            val crashlytics = FirebaseCrashlytics.getInstance()
            provider?.let { crashlytics.setCustomKey("provider", it) }
            extension?.let { crashlytics.setCustomKey("extension", it) }
            movie?.let { crashlytics.setCustomKey("movie", it) }
            crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
            crashlytics.setCustomKey("android_version", Build.VERSION.SDK_INT)
            crashlytics.recordException(throwable)
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // 8. PERFORMANCE MONITORING TRACES
    // =========================================================================

    fun startTrace(traceName: String) {
        try {
            val trace = FirebasePerformance.getInstance().newTrace(traceName)
            trace.start()
            activeTraces[traceName] = trace
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Started Performance Trace: $traceName")
            }
        } catch (_: Throwable) {}
    }

    fun stopTrace(traceName: String) {
        try {
            val trace = activeTraces.remove(traceName)
            trace?.stop()
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Stopped Performance Trace: $traceName")
            }
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // INTERNAL HELPERS & PRIVACY SANITIZATION
    // =========================================================================

    private fun sendEvent(eventName: String, displayTitle: String, bundle: Bundle) {
        try {
            // Automatically append universal system metadata to every event
            bundle.putString("app_version", BuildConfig.VERSION_NAME)
            bundle.putInt("app_version_code", BuildConfig.VERSION_CODE)
            bundle.putInt("android_version", Build.VERSION.SDK_INT)
            bundle.putString("device_manufacturer", Build.MANUFACTURER)
            bundle.putString("device_model", Build.MODEL)
            bundle.putLong("timestamp", System.currentTimeMillis())

            // Log event to Firebase Analytics
            firebaseAnalytics?.logEvent(eventName, bundle)

            // Log to Logcat if in Debug build
            if (BuildConfig.DEBUG) {
                val sb = StringBuilder()
                sb.append("Analytics:\n").append(displayTitle)
                for (key in bundle.keySet()) {
                    val value = bundle.get(key)
                    if (key != "app_version" && key != "android_version" && key != "timestamp") {
                        sb.append("\n").append(key.replace("_", " ").capitalizeWords()).append(": ").append(value)
                    }
                }
                Log.d(TAG, sb.toString())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error logging event $eventName: ${e.message}")
        }
    }

    private fun sanitizeQuery(query: String): String {
        return query.take(100).replace(Regex("[\\r\\n\\t]"), " ").trim()
    }

    private fun sanitizeUrl(url: String): String {
        return try {
            val clean = url.split("?")[0] // Strip query parameters (tokens, auth params)
            if (clean.length > 80) clean.take(80) + "..." else clean
        } catch (_: Exception) {
            "sanitized_url"
        }
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }
}
