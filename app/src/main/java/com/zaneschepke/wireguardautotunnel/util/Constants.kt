package com.zaneschepke.wireguardautotunnel.util

object Constants {
    const val BASE_PACKAGE = "com.zaneschepke.wireguardautotunnel"

    const val BASE_LOG_FILE_NAME = "wg_vpn_logs"

    // WG VPN updates are published as GitHub Releases of this repository
    const val UPDATE_REPO_OWNER = "xuanthanhtnt"
    const val UPDATE_REPO_NAME = "Wireguard"

    const val VPN_SETTINGS_PACKAGE = "android.net.vpn.SETTINGS"
    const val SPECIAL_USE_SERVICE_TYPE_ID = 1 shl 30

    const val FDROID_FLAVOR = "fdroid"
    const val GOOGLE_PLAY_FLAVOR = "google"
    const val STANDALONE_FLAVOR = "standalone"
}
