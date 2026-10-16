package com.wgtunnel.backend.model.dns
data class DnsBootstrapResult(val ipv4: List<String> = emptyList(), val ipv6: List<String> = emptyList())
data class BootstrapResolution(val peerKeyResults: Map<String, DnsBootstrapResult>)
