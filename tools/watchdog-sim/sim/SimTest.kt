import android.app.Notification
import androidx.core.app.NotificationCompat
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.state.BackendStatus
import com.wgtunnel.parser.ActivePeer
import com.wgtunnel.parser.ActiveConfig
import com.wgtunnel.parser.InterfaceSection
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.core.orchestration.World
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider
import com.zaneschepke.wireguardautotunnel.data.DataStoreManager
import com.zaneschepke.wireguardautotunnel.domain.enums.NotificationAction
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelActionSource
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelMode
import com.zaneschepke.wireguardautotunnel.domain.model.*
import com.zaneschepke.wireguardautotunnel.domain.repository.*
import com.zaneschepke.wireguardautotunnel.notification.AndroidNotificationService.NotificationChannels
import com.zaneschepke.wireguardautotunnel.notification.NotificationService
import com.zaneschepke.wireguardautotunnel.xt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ------------------------------------------------------------------ fakes
class SimProvider : TunnelProvider {
    override val backendStatus: StateFlow<BackendStatus> = World.status
    override suspend fun stopTunnel(tunnelId: Int): Result<Unit> { World.bringDown(tunnelId); return Result.success(Unit) }
    override suspend fun setLockDown(settings: LockdownSettings): Result<Unit> { World.killSwitch = true; return Result.success(Unit) }
    override suspend fun disableLockDown(): Result<Unit> { World.killSwitch = false; World.log += "KS OFF"; return Result.success(Unit) }
    // Like the backend: fresh resolution with the phone's (system) DNS, peers re-applied in place
    override suspend fun bounceTunnel(tunnelId: Int, withFreshResolution: Boolean): Boolean {
        World.log += "BOUNCE $tunnelId"
        World.status.update { st ->
            val at = st.activeTunnels[tunnelId] ?: return false
            val cfg = at.mode!!.config
            val peers = cfg.peers.map { p -> ActivePeer(p.publicKey, endpoint = World.resolve(p.host!!)?.let { XtIp.formatEndpoint(it, p.port!!) }) }
            st.copy(activeTunnels = st.activeTunnels + (tunnelId to at.copy(activeConfig = ActiveConfig(InterfaceSection(), peers), transportState = Tunnel.State.Starting)))
        }
        return true
    }
}
class Repo : TunnelRepository {
    val map = linkedMapOf<Int, TunnelConfig>()
    override val userTunnelsFlow = MutableStateFlow<List<TunnelConfig>>(emptyList())
    override suspend fun getAll() = map.values.toList()
    override suspend fun save(tunnelConfig: TunnelConfig) { map[tunnelConfig.id] = tunnelConfig; userTunnelsFlow.value = map.values.toList() }
    override suspend fun getById(id: Int) = map[id]
}
class General : GeneralSettingRepository {
    override val flow = MutableStateFlow(GeneralSettings())
    override suspend fun getGeneralSettings() = GeneralSettings(tunnelMode = if (World.lockdownMode) TunnelMode.LOCK_DOWN else TunnelMode.VPN)
}
class Lockdown : LockdownSettingsRepository { override suspend fun getLockdownSettings() = LockdownSettings() }
class Notifs : NotificationService {
    override val context: android.content.Context get() = throw UnsupportedOperationException()
    val shown = mutableListOf<Int>()
    override fun createNotification(channel: NotificationChannels, title: CharSequence, subText: String?, actions: Collection<NotificationCompat.Action>, description: String, showTimestamp: Boolean, onGoing: Boolean, onlyAlertOnce: Boolean, groupKey: String?, isGroupSummary: Boolean, style: NotificationCompat.Style?, requestPromotedOngoing: Boolean, shortCriticalText: String?, chronometerBaseMillis: Long?, color: Int?): Notification = allocate()
    override fun createNotificationAction(notificationAction: NotificationAction, extraId: Int?, authenticationRequired: Boolean) = NotificationCompat.Action(0, "", null)
    override fun remove(notificationId: Int) {}
    override fun show(notificationId: Int, notification: Notification) { shown += notificationId }
    private fun allocate(): Notification = Notification()
}
class Net : XtNetworkApi {
    var lookups = 0; var dohLookups = 0
    override fun hasValidatedUnderlay() = World.internet
    override suspend fun lookup(source: String, useDoh: Boolean, current: Collection<String>, forceDoh: Boolean): XtLookupResult {
        lookups++
        val sys = World.systemDns[source].orEmpty().map { XtIp.normalize(it) }
        val confirms = sys.isNotEmpty() && sys.any { it in current }
        val doh = if (useDoh && (forceDoh || !confirms)) { dohLookups++; (World.publicDns[source] ?: World.systemDns[source]).orEmpty().map { XtIp.normalize(it) } } else emptyList()
        return XtLookupResult(sys, doh, if (sys.isEmpty() && doh.isEmpty()) "NXDOMAIN" else null)
    }
    override suspend fun internetReachable() = World.internet
    override suspend fun tcpReachable(ip: String, port: Int) = World.serverUp && World.pingable && XtIp.normalize(ip) == XtIp.normalize(World.serverIp)
    override suspend fun ping(ip: String) = tcpReachable(ip, 0)
}
class Platform : XtPlatform {
    var keepAlive = false
    override fun string(id: Int, vararg args: Any) = "#$id(${args.joinToString()})"
    override fun isDeviceIdle() = false
    override fun startKeepAlive() { keepAlive = true }
    override fun stopKeepAlive() { keepAlive = false }
    override fun registerSignals(onNetworkValidated: () -> Unit, onScreenOn: () -> Unit) {}
}

// ------------------------------------------------------------------ harness
class Env(settings: XtSettings, val tunnels: List<TunnelConfig>) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val coord = TunnelCoordinator()
    val provider = SimProvider()
    val repo = Repo()
    val ds = DataStoreManager()
    val xtRepo = XtSettingsRepository(ds)
    val net = Net()
    val platform = Platform()
    val notifs = Notifs()
    lateinit var wd: ServerWatchdogCoordinator
    suspend fun init(settings: XtSettings) {
        tunnels.forEach { repo.save(it) }
        xtRepo.update { settings }
        wd = ServerWatchdogCoordinator(coord, provider, repo, General(), Lockdown(), xtRepo, net, notifs, platform, scope,
            XtTiming(initialIpCheckDelayMs = 300, outageTickMs = 100, outageRecheckMs = 300, verifyTimeoutMs = 700, restoreFirstProbeDelayMs = 300, probeSignalDebounceMs = 500, secondMs = 50))
        scope.launch { while (isActive) { World.tick(); delay(50) } }
        wd.start()
        delay(100)
    }
    suspend fun events() = XtEvent.listFromJson(ds.store.value["XT_EVENTS"] as String?).reversed().map { "${it.type}: ${it.message}" }
    fun endpointOf(id: Int) = World.status.value.activeTunnels[id]?.activeConfig?.peers?.firstOrNull()?.endpoint
    fun healthy(id: Int) = World.status.value.activeTunnels[id]?.isTransportHealthy() == true
    fun close() { scope.cancel() }
}

var failures = 0
fun check(name: String, cond: Boolean) { if (cond) println("  ok   $name") else { failures++; println("  FAIL $name") } }
suspend fun waitFor(ms: Long, cond: () -> Boolean): Boolean { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (cond()) return true; delay(25) }; return cond() }

val A = "203.0.113.10"; val B = "198.51.100.77"
val base = XtSettings(pauseAfterSec = 30, probeIntervalSec = 30, trialTimeoutSec = 5, ipWatchIntervalSec = 300) // x50ms in tests

suspend fun scenario(title: String, settings: XtSettings = base, tunnels: List<TunnelConfig>, block: suspend Env.() -> Unit) {
    println("\n=== $title")
    World.reset(); XtPauseGate.paused = false; (1..5).forEach { XtEndpointOverrides.clear(it) }
    val env = Env(settings, tunnels)
    try { env.init(settings); env.block() } catch (e: Throwable) { failures++; println("  FAIL exception $e"); e.printStackTrace() }
    finally { println("  world log: ${World.log}"); println("  events: ${env.events()}"); env.close(); delay(100) }
}

fun main() = runBlocking {
    val ddns = TunnelConfig(1, "Nha", "PK1|home.ddns.net:51820")

    scenario("1. Server reboots with a new IP (DDNS updated)", tunnels = listOf(ddns)) {
        World.systemDns["home.ddns.net"] = listOf(A); World.serverIp = A
        coord.startTunnel(ddns); check("starts healthy on A", waitFor(1000) { healthy(1) })
        World.serverIp = B; World.systemDns["home.ddns.net"] = listOf(B)
        check("moved to B and healthy", waitFor(4000) { endpointOf(1) == "$B:51820" && healthy(1) })
        check("not paused", wd.runtime.value.pause == null)
        check("IP_CHANGED logged", events().any { it.startsWith("IP_CHANGED") })
        check("non-disruptive bounce used", World.log.any { it.startsWith("BOUNCE") } && World.log.count { it.startsWith("UP") } == 1)
    }

    scenario("2. New IP but phone DNS cache still stale (only public DNS knows)", tunnels = listOf(ddns)) {
        World.systemDns["home.ddns.net"] = listOf(A); World.serverIp = A
        coord.startTunnel(ddns); waitFor(1000) { healthy(1) }
        World.serverIp = B; World.publicDns["home.ddns.net"] = listOf(B)
        check("pinned to B via restart, healthy", waitFor(4000) { endpointOf(1) == "$B:51820" && healthy(1) })
        check("not paused", wd.runtime.value.pause == null)
    }

    val static = TunnelConfig(2, "VanPhong", "PK2|$A:51820")
    scenario("3. Static IP endpoint + DDNS source: config rewritten", base.copy(ipSources = mapOf(2 to "office.ddns.net")), listOf(static)) {
        World.systemDns["office.ddns.net"] = listOf(A); World.serverIp = A
        coord.startTunnel(static); waitFor(1000) { healthy(2) }
        World.serverIp = B; World.systemDns["office.ddns.net"] = listOf(B)
        check("tunnel on B and healthy", waitFor(4000) { endpointOf(2) == "$B:51820" && healthy(2) })
        check("stored config now has B", repo.map[2]!!.quickConfig == "PK2|$B:51820")
    }

    scenario("4. Power cut: VPN paused, internet kept, auto-resume when power is back", tunnels = listOf(ddns)) {
        World.systemDns["home.ddns.net"] = listOf(A); World.serverIp = A
        coord.startTunnel(ddns); waitFor(1000) { healthy(1) }
        World.serverUp = false
        check("paused", waitFor(6000) { wd.runtime.value.pause != null })
        check("tunnel down while paused", waitFor(1500) { World.status.value.activeTunnels.isEmpty() })
        check("keep-alive service started", platform.keepAlive)
        check("pause gate set", XtPauseGate.paused)
        delay(2500)
        check("still paused while server down", wd.runtime.value.pause != null)
        check("trials were attempted", World.log.count { it.startsWith("UP") } >= 2)
        World.serverUp = true
        check("resumed and healthy", waitFor(5000) { wd.runtime.value.pause == null && healthy(1) })
        check("keep-alive stopped", !platform.keepAlive)
        check("RESUMED logged", events().any { it.startsWith("RESUMED") })
    }

    scenario("5. Lockdown mode: kill switch lifted while paused, restored on resume", tunnels = listOf(ddns)) {
        World.lockdownMode = true; World.systemDns["home.ddns.net"] = listOf(A); World.serverIp = A
        coord.startTunnel(ddns); waitFor(1000) { healthy(1) }
        check("kill switch on", World.killSwitch)
        World.serverUp = false
        check("paused", waitFor(6000) { wd.runtime.value.pause != null })
        check("kill switch lifted", waitFor(1000) { !World.killSwitch })
        delay(2500)
        check("kill switch stays lifted between trials", !World.killSwitch || World.status.value.activeTunnels.isNotEmpty())
        World.serverUp = true
        check("resumed", waitFor(5000) { wd.runtime.value.pause == null && healthy(1) })
        check("kill switch restored", World.killSwitch)
    }

    scenario("6. Phone has no internet: never pause", tunnels = listOf(ddns)) {
        World.systemDns["home.ddns.net"] = listOf(A); World.serverIp = A
        coord.startTunnel(ddns); waitFor(1000) { healthy(1) }
        World.serverUp = false; World.internet = false
        delay(4000)
        check("not paused", wd.runtime.value.pause == null)
        check("tunnel left up", World.status.value.activeTunnels.isNotEmpty())
    }

    scenario("7. User starts a tunnel while paused: pause ends", tunnels = listOf(ddns)) {
        World.systemDns["home.ddns.net"] = listOf(A); World.serverIp = A
        coord.startTunnel(ddns); waitFor(1000) { healthy(1) }
        World.serverUp = false
        check("paused", waitFor(6000) { wd.runtime.value.pause != null })
        coord.startTunnel(ddns, TunnelActionSource.USER)
        check("pause cleared", waitFor(3000) { wd.runtime.value.pause == null })
        check("PAUSE_CANCELLED logged", events().any { it.startsWith("PAUSE_CANCELLED") })
    }

    scenario("8. Server comes back after outage with a NEW IP", tunnels = listOf(ddns)) {
        World.systemDns["home.ddns.net"] = listOf(A); World.serverIp = A
        coord.startTunnel(ddns); waitFor(1000) { healthy(1) }
        World.serverUp = false
        check("paused", waitFor(6000) { wd.runtime.value.pause != null })
        World.serverIp = B; World.systemDns["home.ddns.net"] = listOf(B); World.serverUp = true
        check("resumed on B", waitFor(5000) { wd.runtime.value.pause == null && endpointOf(1) == "$B:51820" && healthy(1) })
    }

    scenario("9. Ping method: no test connections until the server answers ping", base.copy(probeMethod = ProbeMethod.PING), listOf(ddns)) {
        World.systemDns["home.ddns.net"] = listOf(A); World.serverIp = A
        coord.startTunnel(ddns); waitFor(1000) { healthy(1) }
        World.serverUp = false
        check("paused", waitFor(6000) { wd.runtime.value.pause != null })
        val ups = World.log.count { it.startsWith("UP") }
        delay(2500)
        check("no trial while ping fails", World.log.count { it.startsWith("UP") } == ups)
        World.serverUp = true
        check("resumed once ping answers", waitFor(5000) { wd.runtime.value.pause == null && healthy(1) })
    }

    scenario("10. LAN split-horizon DNS: healthy tunnel never moved to the public IP", tunnels = listOf(ddns)) {
        val lan = "192.168.1.10"
        World.systemDns["home.ddns.net"] = listOf(lan); World.publicDns["home.ddns.net"] = listOf(B); World.serverIp = lan
        coord.startTunnel(ddns); waitFor(1000) { healthy(1) }
        wd.checkNow(); delay(1500)
        check("still on LAN IP", endpointOf(1) == "$lan:51820" && healthy(1))
        check("no IP change logged", events().none { it.startsWith("IP_CHANGED") })
    }

    scenario("11. Manual cancel of a pause", tunnels = listOf(ddns)) {
        World.systemDns["home.ddns.net"] = listOf(A); World.serverIp = A
        coord.startTunnel(ddns); waitFor(1000) { healthy(1) }
        World.serverUp = false
        check("paused", waitFor(6000) { wd.runtime.value.pause != null })
        wd.cancelPause()
        check("pause cancelled", waitFor(3000) { wd.runtime.value.pause == null && !platform.keepAlive })
        delay(2000)
        check("no more trials after cancel", World.status.value.activeTunnels.isEmpty())
    }


    scenario("12. WG Tunnel recovery keeps bouncing the tunnel during the outage", tunnels = listOf(ddns)) {
        World.systemDns["home.ddns.net"] = listOf(A); World.serverIp = A
        coord.startTunnel(ddns); waitFor(1000) { healthy(1) }
        World.serverUp = false
        val bouncer = scope.launch { while (isActive) { delay(300); World.status.update { st -> st.copy(activeTunnels = st.activeTunnels.mapValues { it.value.copy(transportState = Tunnel.State.Starting) }) } } }
        check("paused despite recovery bounces", waitFor(6000) { wd.runtime.value.pause != null })
        bouncer.cancel()
    }

    scenario("13. Home LAN with split DNS: server loses power, comes back on its LAN IP", tunnels = listOf(ddns)) {
        val lan = "192.168.1.10"
        World.systemDns["home.ddns.net"] = listOf(lan); World.publicDns["home.ddns.net"] = listOf(B); World.serverIp = lan
        coord.startTunnel(ddns); waitFor(1000) { healthy(1) }
        World.serverUp = false
        check("paused", waitFor(8000) { wd.runtime.value.pause != null })
        delay(1000)
        World.serverUp = true
        check("resumed on LAN IP", waitFor(8000) { wd.runtime.value.pause == null && endpointOf(1) == "$lan:51820" && healthy(1) })
    }

    println(if (failures == 0) "\nALL SCENARIOS PASSED" else "\n$failures CHECK(S) FAILED")
}
