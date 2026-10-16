package com.zaneschepke.wireguardautotunnel.ui.screens.support

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import com.zaneschepke.wireguardautotunnel.BuildConfig
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.model.UpdateDownloadState
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.GlobalSideEffect
import com.zaneschepke.wireguardautotunnel.ui.LocalIsAndroidTV
import com.zaneschepke.wireguardautotunnel.ui.LocalNavController
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.functions.rememberClipboardHelper
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.navigation.Route
import com.zaneschepke.wireguardautotunnel.ui.screens.support.components.PermissionDialog
import com.zaneschepke.wireguardautotunnel.ui.screens.support.components.UpdateDialog
import com.zaneschepke.wireguardautotunnel.ui.screens.support.components.UpdateDownloadProgress
import com.zaneschepke.wireguardautotunnel.util.Constants
import com.zaneschepke.wireguardautotunnel.util.StringValue
import com.zaneschepke.wireguardautotunnel.util.extensions.canInstallPackages
import com.zaneschepke.wireguardautotunnel.util.extensions.openWebUrl
import com.zaneschepke.wireguardautotunnel.viewmodel.SharedAppViewModel
import com.zaneschepke.wireguardautotunnel.viewmodel.SupportViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.viewmodel.koinActivityViewModel
import org.orbitmvi.orbit.compose.collectAsState

/** WG VPN "About" tab: app info, updates from GitHub Releases, project links and licenses. */
@Composable
fun SupportScreen(
    viewModel: SupportViewModel = koinViewModel(),
    sharedViewModel: SharedAppViewModel = koinActivityViewModel(),
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val isTv = LocalIsAndroidTV.current
    val scope = rememberCoroutineScope()

    val supportState by viewModel.collectAsState()

    val clipboardManager = rememberClipboardHelper()

    var showPermissionDialog by rememberSaveable { mutableStateOf(false) }

    // Opened from an "update available" notification: check (and optionally download) right away
    LaunchedEffect(Unit) {
        sharedViewModel.supportAutoUpdateRequests.collect { startDownload ->
            if (BuildConfig.DEBUG) return@collect
            if (BuildConfig.FLAVOR != Constants.STANDALONE_FLAVOR) return@collect
            if (startDownload && !context.canInstallPackages()) {
                viewModel.checkForStandaloneUpdate(startDownloadIfAvailable = false)
                showPermissionDialog = true
            } else {
                viewModel.checkForStandaloneUpdate(startDownloadIfAvailable = startDownload)
            }
        }
    }

    // The update row sits at the top of this screen, so there is nothing to scroll to
    LaunchedEffect(Unit) {
        sharedViewModel.showUpdateStatusRequested.collect { requested ->
            if (requested) sharedViewModel.consumeShowUpdateStatus()
        }
    }

    val repoUrl = stringResource(R.string.xt_repo_url)
    val issuesUrl = stringResource(R.string.xt_issues_url)
    val releasesUrl = stringResource(R.string.xt_releases_url)

    val version = remember { BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) "-debug" else "" }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (isTv) {
            focusRequester.requestFocus()
        }
    }

    if (supportState.appUpdate != null) {
        UpdateDialog(
            viewModel = viewModel,
            context = context,
            onPermissionNeeded = { showPermissionDialog = true },
        )
    }

    if (showPermissionDialog) {
        PermissionDialog(context = context, onDismiss = { showPermissionDialog = false })
    }

    fun openWebUrl(url: String) {
        context.openWebUrl(url).onFailure {
            scope.launch {
                viewModel.postSideEffect(
                    GlobalSideEffect.Snackbar(
                        StringValue.StringResource(R.string.no_browser_detected),
                        ToastType.Error,
                    )
                )
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
    ) {
        AboutHeader(version = version)

        // ------------------------------------------------------------------ updates
        Column {
            GroupLabel(
                stringResource(R.string.xt_group_updates),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val download = supportState.download
            val downloading = download as? UpdateDownloadState.Downloading
            val downloadReady = download is UpdateDownloadState.Completed
            val downloadFailed = download is UpdateDownloadState.Failed
            val isStandalone = BuildConfig.FLAVOR == Constants.STANDALONE_FLAVOR
            SurfaceRow(
                leading = { Icon(Icons.Outlined.InstallMobile, contentDescription = null) },
                title =
                    stringResource(
                        when {
                            downloading != null -> R.string.downloading_update
                            downloadReady -> R.string.update_ready_to_install
                            downloadFailed -> R.string.update_download_failed_title
                            else -> R.string.check_for_update
                        }
                    ),
                description =
                    when {
                        downloading != null -> {
                            { UpdateDownloadProgress(downloading) }
                        }
                        downloadReady -> {
                            { DescriptionText(stringResource(R.string.update_tap_to_install)) }
                        }
                        downloadFailed -> {
                            { DescriptionText(stringResource(R.string.update_tap_to_retry)) }
                        }
                        else -> {
                            {
                                DescriptionText(
                                    stringResource(R.string.xt_update_auto_desc, version)
                                )
                            }
                        }
                    },
                onClick = {
                    when {
                        BuildConfig.DEBUG || !isStandalone -> openWebUrl(releasesUrl)
                        // A running or finished download reopens the dialog, no new check
                        downloading != null || downloadReady -> viewModel.showActiveDownload()
                        else -> viewModel.checkForStandaloneUpdate()
                    }
                },
                modifier = Modifier.focusRequester(focusRequester),
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                title = stringResource(R.string.xt_releases),
                description = { DescriptionText(stringResource(R.string.xt_releases_desc)) },
                trailing = { Icon(Icons.AutoMirrored.Outlined.Launch, null) },
                onClick = { openWebUrl(releasesUrl) },
            )
        }

        // ------------------------------------------------------------------ features
        Column {
            GroupLabel(
                stringResource(R.string.xt_group_features),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.HealthAndSafety, contentDescription = null) },
                title = stringResource(R.string.xt_watchdog_title),
                description = { DescriptionText(stringResource(R.string.xt_watchdog_entry_desc)) },
                onClick = { navController.push(Route.ServerWatchdog) },
            )
        }

        // ------------------------------------------------------------------ information
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            GroupLabel(
                stringResource(R.string.xt_group_info),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SurfaceRow(
                leading = {
                    Icon(
                        ImageVector.vectorResource(R.drawable.github),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                title = stringResource(R.string.xt_source_code),
                description = { DescriptionText(stringResource(R.string.xt_repo_name)) },
                trailing = { Icon(Icons.AutoMirrored.Outlined.Launch, null) },
                onClick = { openWebUrl(repoUrl) },
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) },
                title = stringResource(R.string.xt_report_issue),
                trailing = { Icon(Icons.AutoMirrored.Outlined.Launch, null) },
                onClick = { openWebUrl(issuesUrl) },
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Balance, contentDescription = null) },
                title = stringResource(R.string.licenses),
                onClick = { navController.push(Route.License) },
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Memory, contentDescription = null) },
                title = stringResource(R.string.xt_version_label),
                description = {
                    DescriptionText(
                        stringResource(R.string.xt_build_info, version, Build.VERSION.RELEASE)
                    )
                },
                onClick = { clipboardManager.copy(version) },
            )
        }
    }
}

@Composable
private fun AboutHeader(version: String) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F2036)),
            ) {
                Image(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_launcher_foreground_xt),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.xt_version_fmt, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.xt_about_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
