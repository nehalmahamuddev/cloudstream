package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.util.Log
import com.lagradost.cloudstream3.AppConfig
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.coroutines.delay

import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia

object OpenstreamRepoManager {

    /**
     * Checks if the default Openstream repository URL is registered in RepositoryManager
     */
    fun isDefaultRepositoryAdded(): Boolean {
        return RepositoryManager.getRepositories().any {
            it.url.equals(AppConfig.DEFAULT_REPOSITORY_URL, ignoreCase = true) ||
            it.url.equals(AppConfig.FALLBACK_REPOSITORY_URL, ignoreCase = true) ||
            it.url.contains("nehal-CloudStream", ignoreCase = true)
        }
    }

    /**
     * Automatically selects the first available extension source if currentHomePage is null or "None"
     */
    fun selectFirstAvailableSourceIfNeeded(context: android.content.Context): String? {
        val current = com.lagradost.cloudstream3.utils.DataStoreHelper.currentHomePage
        val noneName = com.lagradost.cloudstream3.ui.APIRepository.noneApi.name

        if (current.isNullOrBlank() || current == noneName) {
            val validAPIs = context.filterProviderByPreferredMedia()
                .filter { it.hasMainPage && it.name != noneName }

            if (validAPIs.isNotEmpty()) {
                val firstSource = validAPIs.first()
                com.lagradost.cloudstream3.utils.DataStoreHelper.currentHomePage = firstSource.name
                Log.d(AppConfig.LOG_TAG, "Automatically selected first available source: ${firstSource.name}")
                return firstSource.name
            }
        }
        return current
    }

    /**
     * Adds default repository, refreshes repo manifest, downloads and loads all extensions.
     * Retries with fallback repository URLs on failure.
     *
     * @param activity Current activity reference
     * @param onProgress Optional callback providing (statusText, percentageProgress 0..100)
     * @return Result containing added RepositoryData or Exception on failure after retries
     */
    suspend fun setupDefaultRepository(
        activity: Activity,
        onProgress: ((String, Int) -> Unit)? = null
    ): Result<RepositoryData> {
        onProgress?.invoke("Initializing Openstream Repository...", 10)

        var attempts = 0
        var repository: Repository? = null
        var lastException: Throwable? = null
        var chosenUrl = AppConfig.DEFAULT_REPOSITORY_URL

        val candidateUrls = listOf(
            AppConfig.DEFAULT_REPOSITORY_URL,
            AppConfig.FALLBACK_REPOSITORY_URL
        )

        val startTime = System.currentTimeMillis()
        com.lagradost.cloudstream3.analytics.AnalyticsManager.startTrace("openstream_repo_refresh")

        while (attempts < AppConfig.MAX_RETRIES && repository == null) {
            attempts++
            val urlToTry = candidateUrls[(attempts - 1) % candidateUrls.size]
            try {
                Log.d(
                    AppConfig.LOG_TAG,
                    "Fetching repository manifest (Attempt $attempts/${AppConfig.MAX_RETRIES}): $urlToTry"
                )
                onProgress?.invoke("Connecting to Openstream repository (Attempt $attempts)...", 20)
                repository = RepositoryManager.parseRepository(urlToTry)
                if (repository != null) {
                    chosenUrl = urlToTry
                }
            } catch (e: Throwable) {
                lastException = e
                Log.e(
                    AppConfig.LOG_TAG,
                    "Error fetching repository manifest (Attempt $attempts): ${e.message}",
                    e
                )
                if (attempts < AppConfig.MAX_RETRIES) {
                    delay(AppConfig.RETRY_DELAY_MS)
                }
            }
        }

        if (repository == null) {
            Log.e(
                AppConfig.LOG_TAG,
                "Failed to download Openstream repository after $attempts attempts.",
                lastException
            )
            return Result.failure(lastException ?: Exception("Failed to download repository after retries."))
        }

        val repoData = RepositoryData(
            name = repository.name.ifBlank { AppConfig.DEFAULT_REPOSITORY_NAME },
            url = chosenUrl,
            iconUrl = repository.iconUrl ?: ""
        )

        // Add repository to RepositoryManager
        RepositoryManager.addRepository(repoData)
        Log.d(AppConfig.LOG_TAG, "Repository added: ${repoData.url}")

        onProgress?.invoke("Fetching extensions manifest...", 40)
        Log.d(AppConfig.LOG_TAG, "Repository refreshed: ${repoData.url}")

        // Fetch repo plugins
        val repoPlugins = try {
            RepositoryManager.getRepoPlugins(repoData) ?: emptyList()
        } catch (e: Exception) {
            Log.e(AppConfig.LOG_TAG, "Error fetching repository extensions: ${e.message}", e)
            emptyList()
        }

        val extensionCount = repoPlugins.size
        Log.d(AppConfig.LOG_TAG, "Extension count: $extensionCount")
        onProgress?.invoke("Found $extensionCount extensions. Downloading and loading...", 50)

        // Download and load all available extensions
        var installedCount = 0
        repoPlugins.forEachIndexed { index, pluginWrapper ->
            val pluginName = pluginWrapper.plugin.name
            val progressPercent = 50 + (((index + 1).toDouble() / extensionCount.coerceAtLeast(1)) * 45).toInt()
            onProgress?.invoke("Installing extension (${index + 1}/$extensionCount): $pluginName", progressPercent)

            val success = try {
                PluginManager.downloadPlugin(
                    activity,
                    pluginWrapper.plugin.url,
                    pluginWrapper.plugin.fileHash,
                    pluginWrapper.plugin.internalName,
                    repoData.url,
                    pluginWrapper.plugin.status != PROVIDER_STATUS_DOWN
                )
            } catch (e: Exception) {
                Log.e(AppConfig.LOG_TAG, "Error downloading extension $pluginName: ${e.message}", e)
                false
            }

            if (success) {
                installedCount++
            }
        }

        val duration = System.currentTimeMillis() - startTime
        com.lagradost.cloudstream3.analytics.AnalyticsManager.logRepositoryRefresh(
            url = repoData.url,
            durationMs = duration,
            extensionCount = extensionCount,
            success = true
        )
        com.lagradost.cloudstream3.analytics.AnalyticsManager.stopTrace("openstream_repo_refresh")

        Log.d(AppConfig.LOG_TAG, "Extension updates: $installedCount/$extensionCount extensions downloaded/updated successfully")
        onProgress?.invoke("Setup Complete! $installedCount extensions loaded.", 100)

        return Result.success(repoData)
    }

    /**
     * Silent background refresh invoked every app launch when repository setup is already done.
     * Refreshes repositories, checks for extension updates, and updates them without deleting user plugins or settings.
     */
    fun syncRepositoriesOnLaunch(activity: Activity) {
        ioSafe {
            try {
                if (!isDefaultRepositoryAdded()) {
                    Log.d(AppConfig.LOG_TAG, "Default repository missing on launch, triggering setup...")
                    setupDefaultRepository(activity, null)
                    return@ioSafe
                }

                Log.d(AppConfig.LOG_TAG, "Silent background repository sync started.")

                // Refresh repository and update online plugins
                PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_updateAllOnlinePluginsAndLoadThem(activity)

                val onlinePlugins = PluginManager.getPluginsOnline()
                Log.d(AppConfig.LOG_TAG, "Repository refreshed. Extension count: ${onlinePlugins.size}")
                Log.d(AppConfig.LOG_TAG, "Extension updates: background update scan complete.")
            } catch (e: Exception) {
                Log.e(AppConfig.LOG_TAG, "Errors during background repository sync: ${e.message}", e)
            }
        }
    }
}
