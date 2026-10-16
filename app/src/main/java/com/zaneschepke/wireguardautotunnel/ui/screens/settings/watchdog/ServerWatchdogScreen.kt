package com.zaneschepke.wireguardautotunnel.ui.screens.settings.watchdog

import android.annotation.SuppressLint
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelMode
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.button.ThemedSwitch
import com.zaneschepke.wireguardautotunnel.ui.common.dialog.InfoDialog
import com.zaneschepke.wireguardautotunnel.ui.common.dropdown.LabeledDropdown
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.ui.state.ServerWatchdogUiState
import com.zaneschepke.wireguardautotunnel.viewmodel.ServerWatchdogViewModel
import com.zaneschepke.wireguardautotunnel.xt.EndpointStatus
import com.zaneschepke.wireguardautotunnel.xt.ProbeMethod
import com.zaneschepke.wireguardautotunnel.xt.XtEvent
import com.zaneschepke.wireguardautotunnel.xt.XtEventType
import com.zaneschepke.wireguardautotunnel.xt.XtIp
import com.zaneschepke.wireguardautotunnel.xt.XtSettings
import java.text.DateFormat
import java.util.Date
import org.koin.androidx.compose.koinViewModel
import org.orbitmvi.orbit.compose.collectAsState

@Composable
fun ServerWatchdogScreen(viewModel: ServerWatchdogViewModel = koinViewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.collectAsState()

    var editingTunnelId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showPortDialog by rememberSaveable { mutableStateOf(false) }

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var ignoringBatteryOptimizations by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }
    val batteryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            ignoringBatteryOptimizations =
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations() {
        try {
            batteryLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${context.packageName}".toUri()
                }
            )
        } catch (e: Exception) {
            batteryLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    if (uiState.isLoading) return
    val settings = uiState.settings

    val editingTunnel = editingTunnelId?.let { id -> uiState.tunnels.firstOrNull { it.id == id } }
    if (editingTunnel != null) {
        IpSourceDialog(
            tunnel = editingTunnel,
            initial = settings.ipSources[editingTunnel.id].orEmpty(),
            onDismiss = { editingTunnelId = null },
            onSave = { value ->
                viewModel.setIpSource(editingTunnel.id, value)
                editingTunnelId = null
            },
        )
    }

    if (showPortDialog) {
        TcpPortDialog(
            initial = settings.probeTcpPort,
            onDismiss = { showPortDialog = false },
            onSave = { port ->
                viewModel.setProbeTcpPort(port)
                showPortDialog = false
            },
        )
    }

    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
        modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxSize(),
    ) {
        StatusCard(
            uiState = uiState,
            onCheckNow = { viewModel.checkNow() },
            onResumeNow = { viewModel.resumeNow() },
            onCancelPause = { viewModel.cancelPause() },
        )

        // ---------------------------------------------------------------- server IP watch
        Column {
            GroupLabel(
                stringResource(R.string.xt_group_ip_watch),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.TravelExplore, contentDescription = null) },
                title = stringResource(R.string.xt_ip_watch),
                description = { DescriptionText(stringResource(R.string.xt_ip_watch_desc)) },
                trailing = { modifier ->
                    ThemedSwitch(
                        checked = settings.ipWatchEnabled,
                        onClick = { viewModel.setIpWatchEnabled(it) },
                        modifier = modifier,
                    )
                },
                onClick = { viewModel.setIpWatchEnabled(!settings.ipWatchEnabled) },
            )
            LabeledDropdown(
                title = stringResource(R.string.xt_ip_watch_interval),
                leading = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                currentValue = settings.ipWatchIntervalSec,
                options = XtSettings.IP_WATCH_INTERVAL_OPTIONS,
                onSelected = { selected -> selected?.let { viewModel.setIpWatchInterval(it) } },
                optionToString = { durationLabel(it ?: settings.ipWatchIntervalSec) },
                enabled = settings.ipWatchEnabled,
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                title = stringResource(R.string.xt_use_doh),
                description = { DescriptionText(stringResource(R.string.xt_use_doh_desc)) },
                trailing = { modifier ->
                    ThemedSwitch(
                        checked = settings.useDoh,
                        onClick = { viewModel.setUseDoh(it) },
                        modifier = modifier,
                    )
                },
                onClick = { viewModel.setUseDoh(!settings.useDoh) },
            )
        }

        // ---------------------------------------------------------- per tunnel IP sources
        Column {
            GroupLabel(
                stringResource(R.string.xt_group_sources),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (uiState.tunnels.isEmpty()) {
                DescriptionText(
                    stringResource(R.string.xt_no_tunnels),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            uiState.tunnels.forEach { tunnel ->
                TunnelSourceRow(
                    tunnel = tunnel,
                    source = settings.ipSources[tunnel.id],
                    statuses = uiState.runtime.endpoints.filter { it.tunnelId == tunnel.id },
                    onClick = { editingTunnelId = tunnel.id },
                )
            }
        }

        // ------------------------------------------------------------- outage auto pause
        Column {
            GroupLabel(
                stringResource(R.string.xt_group_outage),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null) },
                title = stringResource(R.string.xt_auto_pause),
                description = { DescriptionText(stringResource(R.string.xt_auto_pause_desc)) },
                trailing = { modifier ->
                    ThemedSwitch(
                        checked = settings.autoPauseEnabled,
                        onClick = { viewModel.setAutoPauseEnabled(it) },
                        modifier = modifier,
                    )
                },
                onClick = { viewModel.setAutoPauseEnabled(!settings.autoPauseEnabled) },
            )
            LabeledDropdown(
                title = stringResource(R.string.xt_pause_after),
                description = { DescriptionText(stringResource(R.string.xt_pause_after_desc)) },
                leading = { Icon(Icons.Outlined.HourglassTop, contentDescription = null) },
                currentValue = settings.pauseAfterSec,
                options = XtSettings.PAUSE_AFTER_OPTIONS,
                onSelected = { selected -> selected?.let { viewModel.setPauseAfter(it) } },
                optionToString = { durationLabel(it ?: settings.pauseAfterSec) },
                enabled = settings.autoPauseEnabled,
            )
            LabeledDropdown(
                title = stringResource(R.string.xt_probe_interval),
                description = { DescriptionText(stringResource(R.string.xt_probe_interval_desc)) },
                leading = { Icon(Icons.Outlined.Sync, contentDescription = null) },
                currentValue = settings.probeIntervalSec,
                options = XtSettings.PROBE_INTERVAL_OPTIONS,
                onSelected = { selected -> selected?.let { viewModel.setProbeInterval(it) } },
                optionToString = { durationLabel(it ?: settings.probeIntervalSec) },
                enabled = settings.autoPauseEnabled,
            )
            LabeledDropdown(
                title = stringResource(R.string.xt_probe_method),
                description = {
                    DescriptionText(
                        stringResource(
                            if (settings.probeMethod == ProbeMethod.PING)
                                R.string.xt_probe_method_ping_desc
                            else R.string.xt_probe_method_handshake_desc
                        )
                    )
                },
                leading = { Icon(Icons.Outlined.Router, contentDescription = null) },
                currentValue = settings.probeMethod,
                options = ProbeMethod.entries,
                onSelected = { selected -> selected?.let { viewModel.setProbeMethod(it) } },
                optionToString = { probeMethodLabel(it ?: settings.probeMethod) },
                enabled = settings.autoPauseEnabled,
            )
            if (settings.probeMethod == ProbeMethod.PING) {
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.Link, contentDescription = null) },
                    title = stringResource(R.string.xt_probe_tcp_port),
                    description = {
                        DescriptionText(
                            if (settings.probeTcpPort in 1..65_535)
                                stringResource(R.string.xt_probe_tcp_port_value, settings.probeTcpPort)
                            else stringResource(R.string.xt_probe_tcp_port_none)
                        )
                    },
                    enabled = settings.autoPauseEnabled,
                    onClick = { showPortDialog = true },
                )
            }
            LabeledDropdown(
                title = stringResource(R.string.xt_trial_timeout),
                description = { DescriptionText(stringResource(R.string.xt_trial_timeout_desc)) },
                leading = { Icon(Icons.Outlined.Speed, contentDescription = null) },
                currentValue = settings.trialTimeoutSec,
                options = XtSettings.TRIAL_TIMEOUT_OPTIONS,
                onSelected = { selected -> selected?.let { viewModel.setTrialTimeout(it) } },
                optionToString = { durationLabel(it ?: settings.trialTimeoutSec) },
                enabled = settings.autoPauseEnabled,
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                title = stringResource(R.string.xt_suspend_kill_switch),
                description = {
                    DescriptionText(stringResource(R.string.xt_suspend_kill_switch_desc))
                },
                trailing = { modifier ->
                    ThemedSwitch(
                        checked = settings.suspendKillSwitch,
                        onClick = { viewModel.setSuspendKillSwitch(it) },
                        modifier = modifier,
                    )
                },
                onClick = { viewModel.setSuspendKillSwitch(!settings.suspendKillSwitch) },
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                title = stringResource(R.string.xt_notify_events),
                description = { DescriptionText(stringResource(R.string.xt_notify_events_desc)) },
                trailing = { modifier ->
                    ThemedSwitch(
                        checked = settings.notifyEvents,
                        onClick = { viewModel.setNotifyEvents(it) },
                        modifier = modifier,
                    )
                },
                onClick = { viewModel.setNotifyEvents(!settings.notifyEvents) },
            )
            if (!ignoringBatteryOptimizations) {
                SurfaceRow(
                    leading = {
                        Icon(
                            Icons.Outlined.BatteryAlert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    title = stringResource(R.string.xt_battery_title),
                    description = { DescriptionText(stringResource(R.string.xt_battery_desc)) },
                    onClick = { requestIgnoreBatteryOptimizations() },
                )
            }
            if (uiState.tunnelMode == TunnelMode.PROXY) {
                DescriptionText(
                    stringResource(R.string.xt_proxy_mode_note),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            DescriptionText(
                stringResource(R.string.xt_always_on_note),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // ---------------------------------------------------------------- history
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            ) {
                GroupLabel(stringResource(R.string.xt_group_history))
                Spacer(modifier = Modifier.weight(1f))
                if (uiState.events.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearHistory() }) {
                        Text(stringResource(R.string.xt_clear_history))
                    }
                }
            }
            if (uiState.events.isEmpty()) {
                DescriptionText(
                    stringResource(R.string.xt_history_empty),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            uiState.events.forEach { event -> EventRow(event) }
        }
    }
}

@Composable
private fun StatusCard(
    uiState: ServerWatchdogUiState,
    onCheckNow: () -> Unit,
    onResumeNow: () -> Unit,
    onCancelPause: () -> Unit,
) {
    val runtime = uiState.runtime
    val settings = uiState.settings
    val pause = runtime.pause
    val failingSince = runtime.failingSinceMs

    val (icon, container, title) =
        when {
            pause != null ->
                Triple(
                    Icons.Outlined.CloudOff,
                    MaterialTheme.colorScheme.errorContainer,
                    stringResource(R.string.xt_status_paused, pause.tunnelName),
                )
            failingSince != null ->
                Triple(
                    Icons.Outlined.Warning,
                    MaterialTheme.colorScheme.tertiaryContainer,
                    stringResource(
                        R.string.xt_status_failing,
                        runtime.failingTunnelName.orEmpty(),
                        timeLabel(failingSince),
                    ),
                )
            !settings.ipWatchEnabled && !settings.autoPauseEnabled ->
                Triple(
                    Icons.Outlined.PowerSettingsNew,
                    MaterialTheme.colorScheme.surfaceVariant,
                    stringResource(R.string.xt_status_disabled),
                )
            else ->
                Triple(
                    Icons.Outlined.CheckCircle,
                    MaterialTheme.colorScheme.secondaryContainer,
                    stringResource(R.string.xt_status_ok),
                )
        }

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            if (pause != null) {
                Text(
                    stringResource(
                        R.string.xt_status_paused_detail,
                        dateTimeLabel(pause.pausedAtMs),
                        pause.probeCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                runtime.nextProbeAtMs?.let {
                    Text(
                        stringResource(R.string.xt_status_next_probe, timeLabel(it)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else if (failingSince != null && settings.autoPauseEnabled) {
                Text(
                    stringResource(
                        R.string.xt_status_failing_detail,
                        durationLabel(settings.pauseAfterSec),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            runtime.lastIpCheckAtMs?.let {
                Text(
                    stringResource(R.string.xt_status_last_ip_check, timeLabel(it)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pause != null) {
                    Button(onClick = onResumeNow, enabled = !runtime.checking) {
                        Text(stringResource(R.string.xt_action_reconnect_now))
                    }
                    TextButton(onClick = onCancelPause) {
                        Text(stringResource(R.string.xt_action_stop_waiting))
                    }
                } else {
                    OutlinedButton(onClick = onCheckNow, enabled = !runtime.checking) {
                        Text(stringResource(R.string.xt_action_check_now))
                    }
                }
                if (runtime.checking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun TunnelSourceRow(
    tunnel: TunnelConfig,
    source: String?,
    statuses: List<EndpointStatus>,
    onClick: () -> Unit,
) {
    val hosts =
        remember(tunnel.quickConfig) {
            try {
                tunnel.getConfig().peers.mapNotNull { it.host }
            } catch (e: Exception) {
                emptyList()
            }
        }
    val hasStaticEndpoint = hosts.any { XtIp.isIpLiteral(it) }
    val lines = mutableListOf<String>()
    when {
        hosts.isEmpty() -> lines += stringResource(R.string.xt_source_no_endpoint)
        hasStaticEndpoint && source.isNullOrBlank() ->
            lines += stringResource(R.string.xt_source_static_unset, hosts.joinToString())
        hasStaticEndpoint -> lines += stringResource(R.string.xt_source_static_set, source.orEmpty())
        else -> lines += stringResource(R.string.xt_source_hostname, hosts.joinToString())
    }
    statuses.forEach { status ->
        lines +=
            when {
                status.source == null -> return@forEach
                status.error != null && status.resolvedIps.isEmpty() ->
                    stringResource(R.string.xt_source_lookup_error, status.error ?: "")
                status.changed ->
                    stringResource(
                        R.string.xt_source_changed,
                        status.currentIps.joinToString(),
                        status.resolvedIps.joinToString(),
                    )
                else ->
                    stringResource(
                        R.string.xt_source_resolved,
                        status.resolvedIps.joinToString(),
                        status.currentIps.joinToString().ifEmpty { "-" },
                    )
            }
    }
    SurfaceRow(
        leading = { Icon(Icons.Outlined.Router, contentDescription = null) },
        title = tunnel.name,
        description = { DescriptionText(lines.joinToString("\n")) },
        onClick = onClick,
    )
}

@Composable
private fun EventRow(event: XtEvent) {
    val icon: ImageVector =
        when (event.type) {
            XtEventType.IP_CHANGED,
            XtEventType.IP_UPDATED -> Icons.Outlined.Sync
            XtEventType.IP_CHECK_FAILED -> Icons.Outlined.Warning
            XtEventType.PAUSED -> Icons.Outlined.CloudOff
            XtEventType.RESUMED -> Icons.Outlined.CheckCircle
            XtEventType.PAUSE_CANCELLED -> Icons.Outlined.PowerSettingsNew
            XtEventType.INFO -> Icons.Outlined.Notifications
        }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                dateTimeLabel(event.atMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(event.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun IpSourceDialog(
    tunnel: TunnelConfig,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(tunnel.id) { mutableStateOf(initial) }
    var isError by remember { mutableStateOf(false) }

    InfoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.xt_source_dialog_title, tunnel.name),
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.xt_source_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ConfigurationTextBox(
                    value = value,
                    label = stringResource(R.string.xt_source_dialog_label),
                    hint = stringResource(R.string.xt_source_dialog_hint),
                    onValueChange = {
                        value = it
                        isError = false
                    },
                    isError = isError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmText = stringResource(R.string.save),
        onAttest = {
            val clean = value.trim()
            val valid =
                clean.isEmpty() ||
                    clean.startsWith("https://", true) ||
                    clean.startsWith("http://", true) ||
                    XtIp.isIpLiteral(clean) ||
                    HOSTNAME.matches(clean)
            if (valid) onSave(clean) else isError = true
        },
    )
}

@Composable
private fun TcpPortDialog(initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by remember { mutableStateOf(if (initial > 0) initial.toString() else "") }
    var isError by remember { mutableStateOf(false) }

    InfoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.xt_probe_tcp_port),
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.xt_probe_tcp_port_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ConfigurationTextBox(
                    value = value,
                    label = stringResource(R.string.xt_probe_tcp_port),
                    hint = "443",
                    onValueChange = {
                        value = it.filter { c -> c.isDigit() }.take(5)
                        isError = false
                    },
                    isError = isError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmText = stringResource(R.string.save),
        onAttest = {
            val port = value.toIntOrNull() ?: 0
            if (value.isEmpty() || port in 1..65_535) onSave(port) else isError = true
        },
    )
}

@Composable
private fun durationLabel(seconds: Int): String =
    if (seconds >= 60 && seconds % 60 == 0) stringResource(R.string.xt_minutes_fmt, seconds / 60)
    else stringResource(R.string.xt_seconds_fmt, seconds)

@Composable
private fun probeMethodLabel(method: ProbeMethod): String =
    when (method) {
        ProbeMethod.HANDSHAKE -> stringResource(R.string.xt_probe_method_handshake)
        ProbeMethod.PING -> stringResource(R.string.xt_probe_method_ping)
    }

private fun timeLabel(ms: Long): String =
    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(ms))

private fun dateTimeLabel(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(ms))

private val HOSTNAME =
    Regex("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z0-9-]{2,63}$")
