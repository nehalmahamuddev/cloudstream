package com.lagradost.cloudstream3

import android.text.format.Formatter.formatFileSize
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lagradost.cloudstream3.mvvm.getStackTracePretty
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.settings.ApkUpdater
import com.lagradost.cloudstream3.ui.settings.GithubReleases
import com.lagradost.cloudstream3.ui.settings.GithubReleases.APK_CONTENT_TYPE
import com.lagradost.cloudstream3.ui.settings.GithubReleases.APK_PRERELEASE
import com.lagradost.cloudstream3.ui.settings.GithubReleases.APK_REPOSITORY
import com.lagradost.cloudstream3.ui.settings.GithubReleases.APK_USERNAME
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import com.lagradost.cloudstream3.utils.parseMarkdown
import com.lagradost.cloudstream4.compose.BlackButton
import com.lagradost.cloudstream4.compose.CloudStreamThemeAppSettings
import com.lagradost.cloudstream4.compose.PrimaryButton
import com.lagradost.cloudstream4.compose.Screen
import com.lagradost.cloudstream4.compose.WhiteButton
import com.lagradost.cloudstream4.rememberAppSettings
import com.lagradost.cloudstream4.viewmodels.GithubAction
import com.lagradost.cloudstream4.viewmodels.GithubAction.AutoSearchForUpdate
import com.lagradost.cloudstream4.viewmodels.GithubAction.Dismiss
import com.lagradost.cloudstream4.viewmodels.GithubAction.SearchForUpdate
import com.lagradost.cloudstream4.viewmodels.GithubAction.SkipUpdate
import com.lagradost.cloudstream4.viewmodels.GithubAction.Update
import com.lagradost.cloudstream4.viewmodels.GithubState
import com.lagradost.cloudstream4.viewmodels.GithubUpdateDialogState
import com.lagradost.cloudstream4.viewmodels.GithubViewModel

object MainActivityScreen : Screen {
    private var hasSearchedForUpdate: Boolean = false

    @Composable
    fun githubViewModel(): GithubViewModel? {
        // By having the ComponentActivity as the owner we can have a singleton viewmodel
        val activity = LocalActivity.current as? ComponentActivity ?: return null
        val settings = rememberAppSettings()
        return viewModel(viewModelStoreOwner = activity) {
            GithubViewModel(
                remoteUserName = APK_USERNAME,
                remoteRepository = APK_REPOSITORY,
                remotePrereleaseTag = APK_PRERELEASE,
                remoteContentType = APK_CONTENT_TYPE,
                versionName = BuildConfig.VERSION_NAME,
                isPrerelease = BuildConfig.FLAVOR == "prerelease",
                isDebug = BuildConfig.DEBUG,
                buildSha = activity.currentCommitHash(),
                settings = settings,
                updater = ApkUpdater,
                repository = GithubReleases,
            ).apply {
                /** Only search once per session when not in safe mode */
                if (!hasSearchedForUpdate && !PluginManager.isSafeMode()) {
                    hasSearchedForUpdate = true
                    onAction(AutoSearchForUpdate)
                }
            }
        }
    }

    @Composable
    override fun Content() {
        GithubView()
    }

    @Composable
    fun GithubView() {
        val updateViewModel = githubViewModel() ?: return
        val state by updateViewModel.state.collectAsState()
        CloudStreamThemeAppSettings {
            GithubState(state, updateViewModel::onAction)
        }
    }

    @Composable
    fun GithubState(updaterState: GithubState, onAction: (GithubAction) -> Unit) {
        val dialog = updaterState.dialog ?: return

        when (val state = dialog.state) {
            is GithubUpdateDialogState.UpdateFound -> {
                UpdateFoundDialog(
                    state = state,
                    onUpdate = { onAction(Update(state.file)) },
                    onSkip = {
                        onAction(SkipUpdate(state.file))
                        onAction(Dismiss)
                    },
                    onDismiss = { onAction(Dismiss) }
                )
            }

            is GithubUpdateDialogState.DownloadProgress -> {
                DownloadProgressDialog(
                    state = state,
                    onCancel = { onAction(Dismiss) }
                )
            }

            is GithubUpdateDialogState.Loading -> {
                if (!dialog.isFromUser) return
                LoadingDialog(onDismiss = { onAction(Dismiss) })
            }

            is GithubUpdateDialogState.NoUpdateFound -> {
                if (!dialog.isFromUser) return
                NoUpdateFoundDialog(onDismiss = { onAction(Dismiss) })
            }

            is GithubUpdateDialogState.Error -> {
                if (!dialog.isFromUser) return
                ErrorDialog(
                    error = state.error,
                    onRetry = { onAction(SearchForUpdate) },
                    onDismiss = { onAction(Dismiss) }
                )
            }
        }
    }

    private data class CatalogData(
        val title: String,
        val items: List<String>,
        val compareUrl: String?
    )

    private fun parseChangelogCatalog(rawChangeLog: String?): CatalogData {
        if (rawChangeLog.isNullOrBlank()) {
            return defaultCatalogData(null)
        }

        var compareUrl: String? = null
        val urlRegex = Regex("""https://github\.com/[^\s)]+/(compare|commits)/[^\s)]+""")
        val urlMatch = urlRegex.find(rawChangeLog)
        if (urlMatch != null) {
            compareUrl = urlMatch.value
        }

        val lines = rawChangeLog.lines()
        val parsedItems = mutableListOf<String>()
        var detectedTitle = "What's New"

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank()) continue

            // Filter out git comparison links or "Full Changelog" line
            if (line.contains("compare/", ignoreCase = true) ||
                line.startsWith("Full Changelog", ignoreCase = true) ||
                line.startsWith("**Full Changelog**", ignoreCase = true)
            ) {
                continue
            }

            // Detect heading
            if (line.startsWith("#")) {
                val h = line.trimStart('#').trim()
                if (h.isNotBlank() && !h.equals("Changelog", ignoreCase = true)) {
                    detectedTitle = h
                }
                continue
            }

            // Extract bullet points
            val cleanBullet = when {
                line.startsWith("- ") -> line.removePrefix("- ")
                line.startsWith("* ") -> line.removePrefix("* ")
                line.startsWith("• ") -> line.removePrefix("• ")
                line.startsWith("+ ") -> line.removePrefix("+ ")
                line.matches(Regex("""^\d+\.\s+.*""")) -> line.substringAfter(". ")
                else -> line
            }.trim()

            if (cleanBullet.isNotBlank()) {
                parsedItems.add(cleanBullet)
            }
        }

        return if (parsedItems.isEmpty()) {
            defaultCatalogData(compareUrl)
        } else {
            CatalogData(
                title = detectedTitle,
                items = parsedItems,
                compareUrl = compareUrl
            )
        }
    }

    private fun defaultCatalogData(compareUrl: String?): CatalogData {
        return CatalogData(
            title = "What's New in this Update",
            items = listOf(
                "✨ Performance & streaming playback optimizations",
                "🛠️ Enhanced plugin & provider compatibility",
                "🎧 Audio booster & player refinements",
                "⚡ Bug fixes and general stability improvements"
            ),
            compareUrl = compareUrl
        )
    }

    private fun formatVersionTag(version: String): String {
        val clean = version.trim()
        if (clean.length == 7 && clean.all { it in "0123456789abcdefABCDEF" }) {
            return clean
        }
        val withoutV = clean.removePrefix("v").removePrefix("V")
        return "v$withoutV"
    }

    @Composable
    private fun UpdateFoundDialog(
        state: GithubUpdateDialogState.UpdateFound,
        onUpdate: () -> Unit,
        onSkip: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val catalog = remember(state.file.changeLog) {
            parseChangelogCatalog(state.file.changeLog)
        }

        val oldVersion = state.oldSha ?: BuildConfig.VERSION_NAME
        val newVersion = state.newSha ?: state.file.displayName

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false
            )
        ) {
            CloudStreamThemeAppSettings {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .widthIn(max = 440.dp)
                        .padding(vertical = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp)
                    ) {
                        // Header: App icon + Title + Version Badges
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Image(
                                        painter = painterResource(R.mipmap.ic_launcher),
                                        contentDescription = null,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.update_available),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                    ) {
                                        Text(
                                            text = formatVersionTag(oldVersion),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }

                                    Text(
                                        text = "➜",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                                    ) {
                                        Text(
                                            text = formatVersionTag(newVersion),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Update Catalog Card
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = catalog.title,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 190.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    catalog.items.forEach { item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Text(
                                                text = "•",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                ),
                                                modifier = Modifier.padding(end = 8.dp)
                                            )
                                            Text(
                                                text = parseMarkdown(item),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }

                                if (catalog.compareUrl != null) {
                                    val uriHandler = LocalUriHandler.current
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.view_full_changelog),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Medium,
                                            textDecoration = TextDecoration.Underline
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clickable {
                                                try {
                                                    uriHandler.openUri(catalog.compareUrl)
                                                } catch (_: Throwable) {}
                                            }
                                            .padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Action buttons - Primary button flows with active App Theme color!
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            BlackButton(
                                text = stringResource(R.string.later),
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f)
                            )
                            PrimaryButton(
                                text = stringResource(R.string.update),
                                onClick = onUpdate,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.skip_update),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .clickable(onClick = onSkip)
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DownloadProgressDialog(
        state: GithubUpdateDialogState.DownloadProgress,
        onCancel: () -> Unit
    ) {
        Dialog(
            onDismissRequest = onCancel,
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            CloudStreamThemeAppSettings {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .widthIn(max = 400.dp)
                        .padding(vertical = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(R.mipmap.ic_launcher),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.update_notification_downloading),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        val total = state.total
                        val progress = if (total != null && total > 0) {
                            (state.progress.toFloat() / total.toFloat()).coerceIn(0.0f, 1.0f)
                        } else {
                            null
                        }

                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${formatFileSize(LocalContext.current, state.progress)} / ${formatFileSize(LocalContext.current, total ?: 0)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${(progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = formatFileSize(LocalContext.current, state.progress),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        BlackButton(
                            text = stringResource(R.string.cancel),
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun LoadingDialog(onDismiss: () -> Unit) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CloudStreamThemeAppSettings {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .widthIn(max = 340.dp)
                        .padding(vertical = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        BlackButton(
                            text = stringResource(R.string.cancel),
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun NoUpdateFoundDialog(onDismiss: () -> Unit) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CloudStreamThemeAppSettings {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .widthIn(max = 340.dp)
                        .padding(vertical = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.no_update_found),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        PrimaryButton(
                            text = stringResource(R.string.ok),
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ErrorDialog(
        error: Throwable,
        onRetry: () -> Unit,
        onDismiss: () -> Unit
    ) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CloudStreamThemeAppSettings {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .widthIn(max = 380.dp)
                        .padding(vertical = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.download_failed),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = error.getStackTracePretty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState())
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            BlackButton(
                                text = stringResource(R.string.ok),
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f)
                            )
                            PrimaryButton(
                                text = stringResource(R.string.check_for_update),
                                onClick = onRetry,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
