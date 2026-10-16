package com.zaneschepke.wireguardautotunnel.di

import com.zaneschepke.wireguardautotunnel.core.orchestration.AppBoostrapCoordinator
import com.zaneschepke.wireguardautotunnel.core.orchestration.AutoTunnelCoordinator
import com.zaneschepke.wireguardautotunnel.core.orchestration.DnsSettingsCoordinator
import com.zaneschepke.wireguardautotunnel.core.orchestration.FileExportCoordinator
import com.zaneschepke.wireguardautotunnel.core.orchestration.ShortcutCoordinator
import com.zaneschepke.wireguardautotunnel.core.orchestration.StartupCoordinator
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelBackendCoordinator
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.xt.AndroidXtPlatform
import com.zaneschepke.wireguardautotunnel.xt.ServerWatchdogCoordinator
import com.zaneschepke.wireguardautotunnel.xt.XtNetwork
import com.zaneschepke.wireguardautotunnel.xt.XtNetworkApi
import com.zaneschepke.wireguardautotunnel.xt.XtPlatform
import com.zaneschepke.wireguardautotunnel.xt.XtSettingsRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

val coordinatorModule = module {
    singleOf(::FileExportCoordinator)
    singleOf(::ShortcutCoordinator)
    singleOf(::TunnelBackendCoordinator)
    singleOf(::StartupCoordinator)
    singleOf(::AutoTunnelCoordinator)
    singleOf(::DnsSettingsCoordinator)
    single {
        TunnelCoordinator(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(named(Scope.APPLICATION)),
        )
    }
    singleOf(::AppBoostrapCoordinator)

    // WG VPN server watchdog
    single { XtSettingsRepository(get()) }
    single<XtNetworkApi> { XtNetwork(androidContext(), get()) }
    single<XtPlatform> { AndroidXtPlatform(androidContext(), get()) }
    single {
        ServerWatchdogCoordinator(
            tunnelCoordinator = get(),
            tunnelProvider = get(),
            tunnelRepository = get(),
            settingsRepository = get(),
            lockdownRepository = get(),
            xtRepository = get(),
            xtNetwork = get(),
            notificationService = get(),
            platform = get(),
            scope = get(named(Scope.APPLICATION)),
        )
    }
}
