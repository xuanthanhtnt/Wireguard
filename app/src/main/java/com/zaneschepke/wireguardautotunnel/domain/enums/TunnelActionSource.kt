package com.zaneschepke.wireguardautotunnel.domain.enums

enum class TunnelActionSource {
    USER,
    AUTO_TUNNEL,
    // WireguardXT server watchdog (IP update / outage pause / auto resume)
    WATCHDOG,
}
