/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.User
import com.health.openscale.ui.screen.components.rememberBluetoothActionButton
import com.health.openscale.ui.screen.components.UserSwitcherRow
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import com.health.openscale.ui.shared.SharedViewModel

/**
 * The always-on landing screen: the selected client's latest reading, at a glance, plus the
 * one button that matters most in this app — sync the scale.
 *
 * Thin wrapper: collects [sharedViewModel] and [bluetoothViewModel] state, reduces it via
 * [HomeStateMapper]/[HomeDisplay] (HomeViewModel.kt), and hands already-decided state to
 * [HomeContent]. The Bluetooth connect flow is not reimplemented here — [bluetoothAction] is the
 * exact same [com.health.openscale.ui.shared.TopBarAction] built by
 * [rememberBluetoothActionButton] for the top bar icon elsewhere in the app, so tapping "Sync
 * scale" runs the identical permission/enable-Bluetooth/connect/assisted-weighing logic.
 */
@Composable
fun HomeScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel,
) {
    val users by sharedViewModel.allUsers.collectAsStateWithLifecycle()
    val selectedUserId by sharedViewModel.selectedUserId.collectAsStateWithLifecycle()
    val measurements by sharedViewModel.measurementsOfSelectedUser.collectAsStateWithLifecycle()
    val connectionStatus by bluetoothViewModel.connectionStatus.collectAsStateWithLifecycle()

    val bluetoothAction = rememberBluetoothActionButton(bluetoothViewModel, sharedViewModel, navController)
    val latest = remember(measurements) { HomeStateMapper.toReading(measurements) }
    val isSyncing = remember(connectionStatus) { HomeDisplay.isSyncing(connectionStatus) }

    HomeContent(
        users = users,
        selectedId = selectedUserId ?: -1,
        onSelect = { sharedViewModel.selectUser(it) },
        latest = latest,
        onSync = bluetoothAction.onClick,
        isSyncing = isSyncing,
    )
}

/**
 * Stateless: renders exactly the state it is handed and forwards taps. No ordering, no
 * defaulting, no formatting decisions live here — those are all in HomeViewModel.kt.
 */
@Composable
fun HomeContent(
    users: List<User>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    latest: HomeUiState.Reading?,
    onSync: () -> Unit,
    isSyncing: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        UserSwitcherRow(users = users, selectedId = selectedId, onSelect = onSelect)

        if (latest == null) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.label_no_readings_yet),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier.weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column {
                    Text(
                        text = HomeDisplay.weightLabel(latest.weightKg),
                        style = MaterialTheme.typography.displayLarge,
                    )
                    Text(
                        text = HomeDisplay.deltaLabel(latest.deltaKg),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCell(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.home_label_body_fat),
                        value = HomeDisplay.percentLabel(latest.fatPercent),
                    )
                    StatCell(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.home_label_muscle),
                        value = HomeDisplay.percentLabel(latest.musclePercent),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCell(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.home_label_bmi),
                        value = HomeDisplay.bmiLabel(latest.bmi),
                    )
                    StatCell(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.home_label_visceral_fat),
                        value = HomeDisplay.visceralFatLabel(latest.visceralFat),
                    )
                }
            }
        }

        Button(
            onClick = onSync,
            enabled = !isSyncing,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.size(8.dp))
            }
            Text(stringResource(R.string.action_sync_scale))
        }
    }
}

/** One cell of Home's 2x2 fat/muscle/BMI/visceral-fat grid. */
@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
