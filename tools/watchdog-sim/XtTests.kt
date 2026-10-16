import com.zaneschepke.wireguardautotunnel.xt.*
import com.wgtunnel.parser.*

var failures = 0
fun check(name: String, cond: Boolean) { if (!cond) { failures++; println("FAIL: $name") } else println("ok:   $name") }

fun main() {
    // --- XtIp
    check("ipv4 literal", XtIp.isIpLiteral("113.161.5.20"))
    check("ipv4 invalid octet", !XtIp.isIpLiteral("300.1.1.1"))
    check("hostname not ip", !XtIp.isIpLiteral("myhome.duckdns.org"))
    check("ipv6 bracket", XtIp.isIpLiteral("[2001:db8::1]"))
    check("ipv6 plain", XtIp.isIpLiteral("2001:0db8:0000:0000:0000:0000:0000:0001"))
    check("hex word not ip", !XtIp.isIpLiteral("cafe"))
    check("normalize v6", XtIp.normalize("[2001:0DB8:0:0:0:0:0:1]") == "2001:db8:0:0:0:0:0:1" || XtIp.normalize("[2001:0DB8:0:0:0:0:0:1]") == "2001:db8::1")
    println("     normalize v6 -> " + XtIp.normalize("[2001:0DB8:0:0:0:0:0:1]"))
    check("normalize v6 equal forms", XtIp.normalize("2001:db8::1") == XtIp.normalize("[2001:0db8:0:0::1]"))
    check("format v4", XtIp.formatEndpoint("1.2.3.4", 51820) == "1.2.3.4:51820")
    check("format v6", XtIp.formatEndpoint("2001:db8::1", 51820) == "[2001:db8::1]:51820")
    check("extract", XtIp.extractIps("Your IP: 14.232.10.7\n").contains("14.232.10.7"))
    check("extract v6", XtIp.extractIps("ip=2402:800:1234::9 ok").isNotEmpty())
    check("extract none in text", XtIp.extractIps("hello world 12:30").isEmpty())
    check("extract json", XtIp.extractIps("{\"ip\":\"171.244.1.2\"}") == listOf("171.244.1.2"))
    check("pick same family v4", XtIp.pickPreferred(listOf("2402:800::1", "1.2.3.4"), listOf("5.6.7.8")) == "1.2.3.4")
    check("pick same family v6", XtIp.pickPreferred(listOf("1.2.3.4", "2402:800::1"), listOf("2402:800::2")) == "2402:800::1")
    check("pick no current", XtIp.pickPreferred(listOf("1.2.3.4"), emptyList()) == "1.2.3.4")

    // --- settings JSON
    val s = XtSettings(ipWatchEnabled = false, ipWatchIntervalSec = 300, probeMethod = ProbeMethod.PING, probeTcpPort = 8443, ipSources = mapOf(3 to "home.duckdns.org", 7 to "https://x.y/ip"))
    val back = XtSettings.fromJson(s.toJson())
    check("settings roundtrip", back == s)
    check("settings defaults on garbage", XtSettings.fromJson("{oops") == XtSettings())
    check("settings defaults on null", XtSettings.fromJson(null) == XtSettings())
    check("settings partial", XtSettings.fromJson("{\"pauseAfterSec\":120}") == XtSettings(pauseAfterSec = 120))
    check("settings clamp", XtSettings.fromJson("{\"pauseAfterSec\":1}").pauseAfterSec == 20)

    // --- pause JSON
    val p = PauseState(5, "Nhà", 123L, true, mapOf("pk=" to listOf("1.2.3.4", "2402::1")), 4, 999L)
    check("pause roundtrip", PauseState.fromJson(p.toJson()) == p)
    check("pause null", PauseState.fromJson(null) == null)

    // --- events JSON
    val ev = listOf(XtEvent(1L, XtEventType.PAUSED, "a: “x” \"q\""), XtEvent(2L, XtEventType.RESUMED, "b"))
    check("events roundtrip", XtEvent.listFromJson(XtEvent.listToJson(ev)) == ev)

    // --- change detection
    fun st(cur: List<String>, res: List<String>, src: String? = "h") = EndpointStatus(1, "t", "k", "h", src, cur, res, 0L)
    check("unchanged when current in resolved", !st(listOf("1.1.1.1"), listOf("1.1.1.1", "2.2.2.2")).changed)
    check("changed when current not in resolved", st(listOf("1.1.1.1"), listOf("2.2.2.2")).changed)
    check("not changed with no current", !st(emptyList(), listOf("2.2.2.2")).changed)
    check("not changed when lookup empty", !st(listOf("1.1.1.1"), emptyList()).changed)
    check("not changed without source", !st(listOf("1.1.1.1"), listOf("2.2.2.2"), null).changed)

    // --- endpoint pins
    val cfg = Config(InterfaceSection(), listOf(PeerSection("A", endpoint = "home.ddns.net:51820"), PeerSection("B", endpoint = "other:1")))
    check("no pin = same", XtEndpointOverrides.apply(9, cfg) === cfg)
    XtEndpointOverrides.put(9, mapOf("A" to "5.6.7.8:51820"))
    val pinned = XtEndpointOverrides.apply(9, cfg)
    check("pin applied", pinned.peers[0].endpoint == "5.6.7.8:51820" && pinned.peers[1].endpoint == "other:1")
    XtEndpointOverrides.put(9, mapOf("A" to "9.9.9.9:51820"), ttlMs = -1)
    check("expired pin ignored", XtEndpointOverrides.apply(9, cfg) === cfg)
    XtEndpointOverrides.put(9, mapOf("A" to "5.6.7.8:51820")); XtEndpointOverrides.clear(9)
    check("cleared pin", XtEndpointOverrides.apply(9, cfg) === cfg)

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
