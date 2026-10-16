package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.splittunnel.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.model.InstalledPackage
import com.zaneschepke.wireguardautotunnel.ui.LocalIsAndroidTV
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.button.ThemedSwitch
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.splittunnel.state.SplitBehavior

@Composable
fun SplitTunnelContent(
    isSplitTunnelingEnabled: Boolean,
    activeBehavior: SplitBehavior,
    selectedPackages: Set<String>,
    installedPackages: List<InstalledPackage>,
    onSplitTunnelingEnabledChange: (Boolean) -> Unit,
    onActiveBehaviorChange: (SplitBehavior) -> Unit,
    onAppSelectionToggle: (String, Boolean) -> Unit,
) {
    val isTv = LocalIsAndroidTV.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv) focusRequester.requestFocus() }

    var query by rememberSaveable { mutableStateOf("") }
    val locale = Locale.current
    val filteredAndSortedPackages =
        remember(installedPackages, query, selectedPackages) {
            filterAndSortPackages(installedPackages, query, selectedPackages, locale)
        }

    Column(modifier = Modifier.fillMaxSize()) {
        Column {
            SurfaceRow(
                leading = {
                    Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null)
                },
                title = stringResource(R.string.split_tunneling),
                description = { DescriptionText(stringResource(R.string.split_tunneling_desc)) },
                trailing = {
                    ThemedSwitch(
                        checked = isSplitTunnelingEnabled,
                        onClick = onSplitTunnelingEnabledChange,
                    )
                },
                onClick = { onSplitTunnelingEnabledChange(!isSplitTunnelingEnabled) },
                modifier = Modifier.focusRequester(focusRequester),
            )
            if (isSplitTunnelingEnabled) {
                SplitBehaviorOptions(
                    activeBehavior = activeBehavior,
                    onBehaviorChange = onActiveBehaviorChange,
                )
            }
        }
        if (isSplitTunnelingEnabled) {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                GroupLabel(
                    stringResource(
                        if (activeBehavior == SplitBehavior.INCLUDE) {
                            R.string.select_apps_to_include
                        } else {
                            R.string.select_apps_to_exclude
                        }
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                AppSearchField(query = query, onQueryChange = { query = it })
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredAndSortedPackages, key = { it.packageName }) { pkg ->
                    val selected = selectedPackages.contains(pkg.packageName)
                    AppListItem(
                        installedPackage = pkg,
                        isSelected = selected,
                        onToggle = { onAppSelectionToggle(pkg.packageName, it) },
                    )
                }
            }
        }
    }
}
