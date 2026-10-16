package com.zaneschepke.wireguardautotunnel.xt

import android.app.Notification
import androidx.core.app.NotificationCompat
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.backend.state.ActiveTunnel
import com.wgtunnel.parser.Config
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider
import com.zaneschepke.wireguardautotunnel.domain.enums.NotificationAction
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelActionSource
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelMode
import com.zaneschepke.wireguardautotunnel.domain.events.TunnelActionEvent
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.LockdownSettingsRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.notification.AndroidNotificationService
import com.zaneschepke.wireguardautotunnel.notification.NotificationService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * WireguardXT server watchdog.
 *
 * 1. **Server IP watch** – periodically looks up the server hostname (or the DDNS name / URL the
 *    user attached to a static-IP tunnel) over the physical network. When the server came back with
 *    a different public IP, the tunnel is moved to the new address: first with a non-disruptive
 *    peer re-resolution, otherwise by restarting it pinned to the new IP. For static-IP configs the
 *    stored config is rewritten with the new IP.
 * 2. **Outage auto-pause** – when the active tunnel cannot complete a handshake for a while although
 *    the phone itself has working internet (checked around the tunnel), the server is considered down
 *    (e.g. power cut). The tunnel is stopped, a kill switch is suspended if configured, and the
 *    phone keeps a normal internet connection. A foreground service then probes the server and
 *    brings the tunnel back automatically as soon as it answers again.
 */
class ServerWatchdogCoordinator(
    private val tunnelCoordinator: TunnelCoordinator,
    private val tunnelProvider: TunnelProvider,
    private val tunnelRepository: TunnelRepository,
    private val settingsRepository: GeneralSettingRepository,
    private val lockdownRepository: LockdownSettingsRepository,
    private val xtRepository: XtSettingsRepository,
    private val xtNetwork: XtNetworkApi,
    private val notificationService: NotificationService,
    private val platform: XtPlatform,
    private val scope: CoroutineScope,
    private val timing: XtTiming = XtTiming(),
) {

    private val _runtime = MutableStateFlow(WatchdogRuntime())
    val runtime: StateFlow<WatchdogRuntime> = _runtime.asStateFlow()

    val settings: StateFlow<XtSettings> =
        xtRepository.flow.stateIn(scope, SharingStarted.Eagerly, XtSettings())

    private val ipCheckRequests = Channel<Unit>(Channel.CONFLATED)
    private val probeRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var manualIpCheckPending = false
    @Volatile private var forcedTrialPending = false
    @Volatile private var lastProbeSignalMs = 0L
    @Volatile private var lastTrialPinnedFromPublicDns = false

    /** Bumped whenever something other than the watchdog starts a tunnel. */
    private val takeoverCounter = AtomicInteger(0)

    /** Serialises every watchdog action that touches tunnels or the pause state. */
    private val opMutex = Mutex()

    private val started = AtomicBoolean(false)
    private val reportedLookupErrors = ConcurrentHashMap.newKeySet<String>()

    private data class EndpointChange(
        val peerKey: String,
        val port: Int,
        val staticEndpoint: Boolean,
        val oldIps: List<String>,
        val newIp: String,
        val source: String,
    )

    private data class Inspection(
        val statuses: List<EndpointStatus>,
        val changes: List<EndpointChange>,
    )

    private enum class OutageOutcome {
        NONE,
        IP_UPDATED,
        PAUSED,
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            guarded("restore") {
                tunnelCoordinator.awaitReady()
                xtRepository.flow.first()
                opMutex.withLock { restorePersistedPause() }
            }
            launch { observeExternalActions() }
            launch { ipWatchLoop() }
            launch { outageLoop() }
            launch { probeLoop() }
            platform.registerSignals(
                onNetworkValidated = { signalProbe() },
                onScreenOn = { signalProbe(fromScreen = true) },
            )
        }
    }

    // ---------------------------------------------------------------- public actions (UI, actions)

    /** Runs an IP check now (or a server probe while paused). */
    fun checkNow() {
        if (_runtime.value.pause != null) {
            probeRequests.trySend(Unit)
        } else {
            manualIpCheckPending = true
            ipCheckRequests.trySend(Unit)
        }
    }

    /** While paused: try to bring the tunnel back right now, regardless of the probe method. */
    fun resumeNow() {
        if (_runtime.value.pause == null) return
        forcedTrialPending = true
        probeRequests.trySend(Unit)
    }

    /** While paused: stop waiting for the server and leave the tunnel off. */
    fun cancelPause() {
        scope.launch {
            guarded("cancel") {
                opMutex.withLock {
                    clearPause(
                        XtEventType.PAUSE_CANCELLED,
                        string(R.string.xt_event_pause_cancelled),
                    )
                }
            }
        }
    }

    suspend fun clearHistory() = xtRepository.clearEvents()

    // --------------------------------------------------------------------------------- loops

    private suspend fun observeExternalActions() {
        tunnelCoordinator.actions.collect { event ->
            if (event.source == TunnelActionSource.WATCHDOG) return@collect
            if (event !is TunnelActionEvent.Started) return@collect
            takeoverCounter.incrementAndGet()
            if (_runtime.value.pause == null) return@collect
            scope.launch {
                guarded("takeover") {
                    opMutex.withLock {
                        clearPause(
                            XtEventType.PAUSE_CANCELLED,
                            string(R.string.xt_event_pause_taken_over),
                        )
                    }
                }
            }
        }
    }

    private suspend fun ipWatchLoop() {
        delay(timing.initialIpCheckDelayMs.milliseconds)
        while (currentCoroutineContext().isActive) {
            val manual = manualIpCheckPending
            manualIpCheckPending = false
            if ((settings.value.ipWatchEnabled || manual) && _runtime.value.pause == null) {
                guarded("ip check") { opMutex.withLock { checkTunnels(manual) } }
            }
            val intervalMs = settings.value.ipWatchIntervalSec.coerceAtLeast(30) * timing.secondMs
            withTimeoutOrNull(intervalMs.milliseconds) { ipCheckRequests.receive() }
        }
    }

    private suspend fun outageLoop() {
        var failingId: Int? = null
        var failingSince = 0L
        var recheckAfter = 0L
        while (currentCoroutineContext().isActive) {
            delay(timing.outageTickMs.milliseconds)
            if (_runtime.value.pause != null) {
                if (failingId != null) {
                    failingId = null
                    publishFailing(null, null)
                }
                continue
            }
            val entry =
                tunnelProvider.backendStatus.value.activeTunnels.entries.firstOrNull { (_, at) ->
                    at.mode is BackendMode.Vpn || at.mode is BackendMode.Proxy.KillSwitchPrimary
                }
            if (entry == null || entry.value.isTransportHealthy()) {
                if (failingId != null) {
                    failingId = null
                    publishFailing(null, null)
                }
                continue
            }
            // Starting / Stopping: WG Tunnel's own recovery is bouncing the tunnel. Keep the
            // current failure timer running, but do not start one.
            if (!isFailing(entry.value)) continue
            val id = entry.key
            val now = now()
            if (failingId != id) {
                failingId = id
                failingSince = now
                recheckAfter = 0L
                publishFailing(now, entry.value.tunnel?.name)
                // A server that restarted often comes back with a new public IP: look right away
                ipCheckRequests.trySend(Unit)
                continue
            }
            val s = settings.value
            if (!s.autoPauseEnabled) continue
            if (now - failingSince < s.pauseAfterSec * timing.secondMs) continue
            if (now < recheckAfter) continue
            recheckAfter = now + timing.outageRecheckMs
            // Doze blocks our network access and makes handshakes fail on purpose: not an outage
            if (platform.isDeviceIdle()) continue
            if (!xtNetwork.hasValidatedUnderlay()) continue
            // Only blame the server when the internet works fine around the tunnel
            if (!xtNetwork.internetReachable()) {
                Timber.i("XT: handshake failing but no internet around the tunnel either; not pausing")
                continue
            }
            var outcome = OutageOutcome.NONE
            guarded("outage") { outcome = opMutex.withLock { handleSuspectedOutage(id) } }
            when (outcome) {
                OutageOutcome.IP_UPDATED -> failingSince = now()
                OutageOutcome.PAUSED -> {
                    failingId = null
                    publishFailing(null, null)
                }
                OutageOutcome.NONE -> Unit
            }
        }
    }

    private suspend fun probeLoop() {
        while (currentCoroutineContext().isActive) {
            if (_runtime.value.pause == null) {
                _runtime.first { it.pause != null }
                continue
            }
            val nextAt =
                _runtime.value.nextProbeAtMs ?: (now() + settings.value.probeIntervalSec * timing.secondMs)
            val waitMs = (nextAt - now()).coerceAtLeast(0L)
            withTimeoutOrNull(waitMs.milliseconds) { probeRequests.receive() }
            if (_runtime.value.pause == null) continue
            val forced = forcedTrialPending
            forcedTrialPending = false
            guarded("probe") { opMutex.withLock { probe(forced) } }
            _runtime.update {
                if (it.pause != null) {
                    it.copy(nextProbeAtMs = now() + settings.value.probeIntervalSec * timing.secondMs)
                } else it
            }
        }
    }

    // ------------------------------------------------------------------------ IP watch

    private suspend fun checkTunnels(manual: Boolean) {
        val s = settings.value
        val active =
            tunnelProvider.backendStatus.value.activeTunnels.filterValues { at ->
                at.mode != null &&
                    at.transportState !is Tunnel.State.Starting &&
                    at.transportState !is Tunnel.State.Stopping
            }
        val targets: List<Pair<TunnelConfig, ActiveTunnel?>> =
            when {
                active.isNotEmpty() ->
                    active.mapNotNull { (id, at) -> tunnelRepository.getById(id)?.let { it to at } }
                // Nothing connected: a manual check still shows (and fixes) every tunnel
                manual -> tunnelRepository.getAll().filterNot { it.isGlobalConfig }.map { it to null }
                else -> emptyList()
            }
        _runtime.update { it.copy(checking = true) }
        try {
            val statuses = mutableListOf<EndpointStatus>()
            var changed = false
            for ((cfg, at) in targets) {
                val inspection = inspectTunnel(cfg, at, s)
                statuses += inspection.statuses
                reportLookupErrors(cfg, inspection.statuses, manual)
                val changes =
                    if (at != null) inspection.changes
                    else inspection.changes.filter { it.staticEndpoint }
                if (changes.isNotEmpty()) {
                    applyChanges(cfg, changes, tunnelActive = at != null)
                    changed = true
                }
            }
            _runtime.update { it.copy(endpoints = statuses, lastIpCheckAtMs = now()) }
            if (manual && !changed && statuses.none { it.error != null }) {
                Timber.i("XT: manual IP check, no change for ${statuses.size} endpoint(s)")
            }
        } finally {
            _runtime.update { it.copy(checking = false) }
        }
    }

    private suspend fun inspectTunnel(
        cfg: TunnelConfig,
        at: ActiveTunnel?,
        s: XtSettings,
        knownIps: Map<String, List<String>> = emptyMap(),
        failing: Boolean = at?.let { isFailing(it) } ?: false,
    ): Inspection {
        val config =
            try {
                cfg.getConfig()
            } catch (e: Exception) {
                Timber.w(e, "XT: cannot parse config of ${cfg.name}")
                return Inspection(emptyList(), emptyList())
            }
        val statuses = mutableListOf<EndpointStatus>()
        val changes = mutableListOf<EndpointChange>()
        for (peer in config.peers) {
            val host = peer.host ?: continue
            val port = peer.port ?: continue
            val isStatic = XtIp.isIpLiteral(host)
            val source =
                if (isStatic) s.ipSources[cfg.id]?.trim()?.takeIf { it.isNotEmpty() }
                else XtIp.stripBrackets(host)
            val current =
                currentIps(cfg.id, peer.publicKey, if (isStatic) host else null, at).ifEmpty {
                    knownIps[peer.publicKey].orEmpty().map { XtIp.normalize(it) }
                }
            if (source == null) {
                statuses +=
                    EndpointStatus(
                        tunnelId = cfg.id,
                        tunnelName = cfg.name,
                        peerKey = peer.publicKey,
                        endpointHost = host,
                        source = null,
                        currentIps = current,
                        resolvedIps = emptyList(),
                        checkedAtMs = now(),
                    )
                continue
            }
            val lookup = xtNetwork.lookup(source, s.useDoh, current, forceDoh = failing)
            // The server is unreachable on the address the phone's DNS still returns, while
            // public DNS already knows another one: trust public DNS. (Never done while the tunnel
            // is healthy, so LAN / split-horizon DNS setups are not affected.)
            val staleLocalDns =
                failing &&
                    lookup.doh.isNotEmpty() &&
                    current.isNotEmpty() &&
                    current.none { it in lookup.doh }
            val status =
                EndpointStatus(
                    tunnelId = cfg.id,
                    tunnelName = cfg.name,
                    peerKey = peer.publicKey,
                    endpointHost = host,
                    source = source,
                    currentIps = current,
                    resolvedIps = lookup.all,
                    checkedAtMs = now(),
                    error = lookup.error,
                    staleLocalDns = staleLocalDns,
                )
            statuses += status
            if (status.changed) {
                val candidates =
                    if (staleLocalDns) lookup.doh else lookup.all.filterNot { it in current }
                val newIp = XtIp.pickPreferred(candidates, current) ?: continue
                changes +=
                    EndpointChange(
                        peerKey = peer.publicKey,
                        port = port,
                        staticEndpoint = isStatic,
                        oldIps = current,
                        newIp = newIp,
                        source = source,
                    )
            }
        }
        return Inspection(statuses, changes)
    }

    /** The server IPs a peer is using right now (or the configured one for static endpoints). */
    private fun currentIps(
        tunnelId: Int,
        peerKey: String,
        staticHost: String?,
        at: ActiveTunnel?,
        includePins: Boolean = true,
    ): List<String> {
        if (staticHost != null) return listOf(XtIp.normalize(staticHost))
        if (at == null) return emptyList()
        val live = at.activeConfig?.peers?.firstOrNull { it.publicKey == peerKey }?.host
        if (live != null && XtIp.isIpLiteral(live)) return listOf(XtIp.normalize(live))
        if (includePins) {
            XtEndpointOverrides.active(tunnelId)[peerKey]?.let { endpoint ->
                val pinnedHost = Config.parseEndpoint(endpoint).first
                if (pinnedHost != null && XtIp.isIpLiteral(pinnedHost)) {
                    return listOf(XtIp.normalize(pinnedHost))
                }
            }
        }
        val boot = at.lastBootstrapResolution?.peerKeyResults?.get(peerKey) ?: return emptyList()
        return (boot.ipv4 + boot.ipv6).map { XtIp.normalize(it) }.distinct()
    }

    private suspend fun applyChanges(
        cfg: TunnelConfig,
        changes: List<EndpointChange>,
        tunnelActive: Boolean,
    ) {
        changes.forEach { change ->
            addEvent(
                XtEventType.IP_CHANGED,
                string(
                    R.string.xt_event_ip_changed,
                    cfg.name,
                    change.source,
                    change.oldIps.joinToString().ifEmpty { "?" },
                    change.newIp,
                ),
            )
        }
        val staticChanges = changes.filter { it.staticEndpoint }
        val dynamicChanges = changes.filterNot { it.staticEndpoint }

        if (staticChanges.isNotEmpty()) {
            try {
                val config = cfg.getConfig()
                val updated =
                    config.copy(
                        peers =
                            config.peers.map { peer ->
                                staticChanges
                                    .firstOrNull { it.peerKey == peer.publicKey }
                                    ?.let { peer.copy(endpoint = XtIp.formatEndpoint(it.newIp, it.port)) }
                                    ?: peer
                            }
                    )
                tunnelRepository.save(cfg.copy(quickConfig = updated.asQuickString()))
                addEvent(XtEventType.IP_UPDATED, string(R.string.xt_event_config_saved, cfg.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "XT: failed to save new endpoint for ${cfg.name}")
            }
        }

        if (!tunnelActive) {
            // Make the next start use the new address even if DNS caches still hold the old one
            pin(cfg.id, dynamicChanges)
            return
        }

        if (staticChanges.isEmpty() && dynamicChanges.isNotEmpty()) {
            // Non-disruptive first: re-resolve and re-apply peers in place
            val bounced = tunnelProvider.bounceTunnel(cfg.id, withFreshResolution = true)
            if (bounced && awaitNewEndpoints(cfg.id, dynamicChanges)) {
                XtEndpointOverrides.clear(cfg.id)
                announceIpUpdate(cfg.name, dynamicChanges.first().newIp)
                return
            }
        }

        pin(cfg.id, dynamicChanges)
        restartTunnel(cfg.id)
        announceIpUpdate(cfg.name, changes.first().newIp)
    }

    private fun pin(tunnelId: Int, changes: List<EndpointChange>) {
        if (changes.isEmpty()) return
        XtEndpointOverrides.put(
            tunnelId,
            changes.associate { it.peerKey to XtIp.formatEndpoint(it.newIp, it.port) },
        )
    }

    private suspend fun awaitNewEndpoints(tunnelId: Int, changes: List<EndpointChange>): Boolean =
        withTimeoutOrNull(timing.verifyTimeoutMs.milliseconds) {
            tunnelProvider.backendStatus.first { status ->
                val at = status.activeTunnels[tunnelId] ?: return@first false
                changes.all { change ->
                    change.newIp in currentIps(tunnelId, change.peerKey, null, at, includePins = false)
                }
            }
        } != null

    private suspend fun restartTunnel(tunnelId: Int) {
        val cfg = tunnelRepository.getById(tunnelId) ?: return
        tunnelCoordinator.exclusively { locked ->
            locked.stop(tunnelId, TunnelActionSource.WATCHDOG)
            locked.start(cfg, TunnelActionSource.WATCHDOG)
        }
    }

    private suspend fun reportLookupErrors(
        cfg: TunnelConfig,
        statuses: List<EndpointStatus>,
        manual: Boolean,
    ) {
        statuses.forEach { status ->
            val key = "${cfg.id}/${status.peerKey}"
            if (status.source == null) return@forEach
            if (status.error == null && status.resolvedIps.isNotEmpty()) {
                reportedLookupErrors.remove(key)
                return@forEach
            }
            if (manual || reportedLookupErrors.add(key)) {
                addEvent(
                    XtEventType.IP_CHECK_FAILED,
                    string(
                        R.string.xt_event_lookup_failed,
                        cfg.name,
                        status.source,
                        status.error ?: "-",
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------- outage / pause

    private suspend fun handleSuspectedOutage(tunnelId: Int): OutageOutcome {
        val at = tunnelProvider.backendStatus.value.activeTunnels[tunnelId] ?: return OutageOutcome.NONE
        if (at.isTransportHealthy()) return OutageOutcome.NONE
        val cfg = tunnelRepository.getById(tunnelId) ?: return OutageOutcome.NONE
        val s = settings.value
        val inspection = inspectTunnel(cfg, at, s, failing = true)
        _runtime.update { it.copy(endpoints = inspection.statuses, lastIpCheckAtMs = now()) }
        if (inspection.changes.isNotEmpty()) {
            applyChanges(cfg, inspection.changes, tunnelActive = true)
            return OutageOutcome.IP_UPDATED
        }
        pause(cfg, inspection)
        return OutageOutcome.PAUSED
    }

    private suspend fun pause(cfg: TunnelConfig, inspection: Inspection) {
        val s = settings.value
        val general = settingsRepository.getGeneralSettings()
        val now = now()
        val known =
            inspection.statuses
                .associate { it.peerKey to it.currentIps.ifEmpty { it.resolvedIps } }
                .filterValues { it.isNotEmpty() }
        var state =
            PauseState(
                tunnelId = cfg.id,
                tunnelName = cfg.name,
                pausedAtMs = now,
                killSwitchSuspended = false,
                knownIps = known,
            )
        XtPauseGate.paused = true
        _runtime.update {
            it.copy(pause = state, nextProbeAtMs = now + s.probeIntervalSec * timing.secondMs)
        }
        // Start the keep-alive service while the VPN's own foreground service is still running
        platform.startKeepAlive()
        tunnelCoordinator.stopTunnel(cfg.id, TunnelActionSource.WATCHDOG)
        if (general.tunnelMode == TunnelMode.LOCK_DOWN && s.suspendKillSwitch) {
            tunnelProvider
                .disableLockDown()
                .onSuccess { state = state.copy(killSwitchSuspended = true) }
                .onFailure { Timber.e(it, "XT: could not suspend kill switch") }
        }
        xtRepository.setPause(state)
        _runtime.update { it.copy(pause = state) }
        addEvent(XtEventType.PAUSED, string(R.string.xt_event_paused, cfg.name))
        notificationService.show(ONGOING_NOTIFICATION_ID, buildOngoingNotification())
    }

    private suspend fun probe(forceTrial: Boolean) {
        val pause = _runtime.value.pause ?: return
        if (tunnelProvider.backendStatus.value.activeTunnels.isNotEmpty()) {
            clearPause(XtEventType.PAUSE_CANCELLED, string(R.string.xt_event_pause_taken_over))
            return
        }
        val cfg = tunnelRepository.getById(pause.tunnelId)
        if (cfg == null) {
            clearPause(XtEventType.PAUSE_CANCELLED, string(R.string.xt_event_pause_tunnel_missing))
            return
        }
        val s = settings.value
        var current = pause.copy(probeCount = pause.probeCount + 1, lastProbeAtMs = now())
        xtRepository.setPause(current)
        _runtime.update { it.copy(pause = current, checking = true) }
        try {
            // The phone itself is offline: nothing to learn about the server right now
            if (!xtNetwork.hasValidatedUnderlay()) return

            // Alternate: when the last test used a pinned address and still failed, test with the
            // phone's own DNS answer this time (LAN / split-horizon setups)
            val allowPublicDnsOverride = !lastTrialPinnedFromPublicDns
            val inspection =
                inspectTunnel(cfg, null, s, current.knownIps, failing = allowPublicDnsOverride)
            _runtime.update { it.copy(endpoints = inspection.statuses, lastIpCheckAtMs = now()) }

            var target = cfg
            val ipChanged = inspection.changes.isNotEmpty()
            if (ipChanged) {
                applyChanges(cfg, inspection.changes, tunnelActive = false)
                target = tunnelRepository.getById(cfg.id) ?: cfg
                current =
                    current.copy(
                        knownIps =
                            current.knownIps +
                                inspection.changes.associate { it.peerKey to listOf(it.newIp) }
                    )
                xtRepository.setPause(current)
                _runtime.update { it.copy(pause = current) }
            }

            val shouldTry =
                forceTrial ||
                    ipChanged ||
                    when (s.probeMethod) {
                        ProbeMethod.HANDSHAKE -> true
                        ProbeMethod.PING -> serverAnswers(inspection, current, s)
                    }
            if (!shouldTry) return

            if (trial(target, current, s)) {
                lastTrialPinnedFromPublicDns = false
                resume(target, current)
            } else {
                // The pinned address did not answer either: next time test with plain DNS
                val hadPin = XtEndpointOverrides.active(cfg.id).isNotEmpty()
                if (hadPin) XtEndpointOverrides.clear(cfg.id)
                lastTrialPinnedFromPublicDns = hadPin
            }
        } finally {
            _runtime.update { it.copy(checking = false) }
            if (_runtime.value.pause != null) {
                notificationService.show(ONGOING_NOTIFICATION_ID, buildOngoingNotification())
            }
        }
    }

    private suspend fun serverAnswers(
        inspection: Inspection,
        pause: PauseState,
        s: XtSettings,
    ): Boolean {
        val targets =
            (inspection.statuses.flatMap { it.resolvedIps.ifEmpty { it.currentIps } } +
                    pause.knownIps.values.flatten())
                .map { XtIp.normalize(it) }
                .filter { XtIp.isIpLiteral(it) }
                .distinct()
        for (ip in targets) {
            val reachable =
                if (s.probeTcpPort in 1..65_535) xtNetwork.tcpReachable(ip, s.probeTcpPort)
                else xtNetwork.ping(ip)
            if (reachable) return true
        }
        return false
    }

    /** Brings the tunnel up and waits for a healthy handshake; takes it down again on failure. */
    private suspend fun trial(cfg: TunnelConfig, pause: PauseState, s: XtSettings): Boolean {
        val takeoverMark = takeoverCounter.get()
        tunnelCoordinator.startTunnel(cfg, TunnelActionSource.WATCHDOG)
        val healthy =
            withTimeoutOrNull((s.trialTimeoutSec * timing.secondMs).milliseconds) {
                tunnelProvider.backendStatus.first {
                    it.activeTunnels[cfg.id]?.isTransportHealthy() == true
                }
            } != null
        if (healthy) return true
        // Somebody else started a tunnel meanwhile: leave it alone, the takeover clears the pause
        if (takeoverCounter.get() != takeoverMark) return false
        tunnelCoordinator.stopTunnel(cfg.id, TunnelActionSource.WATCHDOG)
        if (pause.killSwitchSuspended) {
            tunnelProvider.disableLockDown().onFailure { Timber.e(it, "XT: kill switch re-suspend") }
        }
        return false
    }

    private suspend fun resume(cfg: TunnelConfig, pause: PauseState) {
        XtPauseGate.paused = false
        xtRepository.setPause(null)
        _runtime.update { it.copy(pause = null, nextProbeAtMs = null) }
        restoreKillSwitchIfNeeded(pause)
        platform.stopKeepAlive()
        notificationService.remove(ONGOING_NOTIFICATION_ID)
        val minutes = ((now() - pause.pausedAtMs) / 60_000L).coerceAtLeast(0L)
        val message = string(R.string.xt_event_resumed, cfg.name, minutes)
        addEvent(XtEventType.RESUMED, message)
        if (settings.value.notifyEvents) showEvent(string(R.string.xt_notif_resumed_title), message)
    }

    private suspend fun clearPause(type: XtEventType, message: String) {
        val pause = _runtime.value.pause ?: return
        XtPauseGate.paused = false
        xtRepository.setPause(null)
        _runtime.update { it.copy(pause = null, nextProbeAtMs = null) }
        restoreKillSwitchIfNeeded(pause)
        platform.stopKeepAlive()
        notificationService.remove(ONGOING_NOTIFICATION_ID)
        addEvent(type, message)
    }

    private suspend fun restoreKillSwitchIfNeeded(pause: PauseState) {
        if (!pause.killSwitchSuspended) return
        if (settingsRepository.getGeneralSettings().tunnelMode != TunnelMode.LOCK_DOWN) return
        try {
            tunnelCoordinator.applyLockdownSettings(lockdownRepository.getLockdownSettings())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "XT: could not restore kill switch")
        }
    }

    private suspend fun restorePersistedPause() {
        val pause = xtRepository.getPause()
        if (pause == null) {
            // A sticky restart of the keep-alive service with nothing left to wait for
            platform.stopKeepAlive()
            return
        }
        val tunnelExists = tunnelRepository.getById(pause.tunnelId) != null
        if (!tunnelExists || tunnelProvider.backendStatus.value.activeTunnels.isNotEmpty()) {
            xtRepository.setPause(null)
            platform.stopKeepAlive()
            return
        }
        XtPauseGate.paused = true
        _runtime.update {
            it.copy(pause = pause, nextProbeAtMs = now() + timing.restoreFirstProbeDelayMs)
        }
        // App bootstrap re-arms lockdown on start; keep internet working while we wait
        if (
            pause.killSwitchSuspended &&
                settingsRepository.getGeneralSettings().tunnelMode == TunnelMode.LOCK_DOWN
        ) {
            tunnelProvider.disableLockDown().onFailure { Timber.e(it) }
        }
        platform.startKeepAlive()
        Timber.i("XT: restored paused state for tunnel ${pause.tunnelName}")
    }

    // ------------------------------------------------------------------ system signals

    /** A new network or the user picking up the phone: good moments to check a paused server. */
    private fun signalProbe(fromScreen: Boolean = false) {
        val pause = _runtime.value.pause ?: return
        val now = now()
        if (now - lastProbeSignalMs < timing.probeSignalDebounceMs) return
        if (now - pause.lastProbeAtMs < timing.probeSignalDebounceMs) return
        // A test connection interrupts internet for a few seconds: on every unlock that would be
        // annoying, so only bring the next check forward once half the interval has passed
        val s = settings.value
        if (
            fromScreen &&
                s.probeMethod == ProbeMethod.HANDSHAKE &&
                now - pause.lastProbeAtMs < s.probeIntervalSec * timing.secondMs / 2
        ) return
        lastProbeSignalMs = now
        probeRequests.trySend(Unit)
    }

    // ------------------------------------------------------------------ notifications

    fun buildOngoingNotification(): Notification {
        val pause = _runtime.value.pause
        val text =
            if (pause != null) string(R.string.xt_notif_paused_text, pause.tunnelName)
            else string(R.string.xt_notif_waiting_text)
        return notificationService.createNotification(
            channel = AndroidNotificationService.NotificationChannels.Errors,
            title = string(R.string.xt_notif_paused_title),
            description = text,
            actions =
                listOf(
                    notificationService.createNotificationAction(NotificationAction.WATCHDOG_RESUME),
                    notificationService.createNotificationAction(NotificationAction.WATCHDOG_CANCEL),
                ),
            onGoing = true,
            style = NotificationCompat.BigTextStyle().bigText(text),
        )
    }

    private fun announceIpUpdate(tunnelName: String, newIp: String) {
        if (!settings.value.notifyEvents) return
        showEvent(
            string(R.string.xt_notif_ip_updated_title),
            string(R.string.xt_notif_ip_updated_text, tunnelName, newIp),
        )
    }

    private fun showEvent(title: String, text: String) {
        try {
            notificationService.show(
                EVENT_NOTIFICATION_ID,
                notificationService.createNotification(
                    channel = AndroidNotificationService.NotificationChannels.Events,
                    title = title,
                    description = text,
                    style = NotificationCompat.BigTextStyle().bigText(text),
                ),
            )
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Handshakes failing, or the tunnel left Down by a failed recovery while still "on". */
    private fun isFailing(at: ActiveTunnel): Boolean =
        at.isHandshakeFailure() || at.transportState is Tunnel.State.Down

    private fun publishFailing(since: Long?, name: String?) {
        _runtime.update { it.copy(failingSinceMs = since, failingTunnelName = name) }
    }

    private suspend fun addEvent(type: XtEventType, message: String) {
        Timber.i("XT event $type: $message")
        try {
            xtRepository.addEvent(XtEvent(now(), type, message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private suspend fun guarded(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            Timber.w(e, "XT $label cancelled internally")
        } catch (e: Exception) {
            Timber.e(e, "XT $label failed")
        }
    }

    private fun string(id: Int, vararg args: Any): String = platform.string(id, *args)

    private fun now() = System.currentTimeMillis()

    companion object {
        const val ONGOING_NOTIFICATION_ID = 140
        const val EVENT_NOTIFICATION_ID = 141
    }
}
