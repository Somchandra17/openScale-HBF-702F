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
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.  See the
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.User
import com.health.openscale.core.report.ReportArtwork
import com.health.openscale.ui.screen.components.UserSwitcherRow
import com.health.openscale.ui.screen.components.rememberBluetoothActionButton
import com.health.openscale.ui.screen.report.ReportShare
import com.health.openscale.ui.screen.report.ReportViewModel
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import com.health.openscale.ui.shared.SharedViewModel
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    bluetoothViewModel: BluetoothViewModel,
    reportViewModel: ReportViewModel = hiltViewModel(),
) {
    val users by sharedViewModel.allUsers.collectAsStateWithLifecycle()
    val selectedUserId by sharedViewModel.selectedUserId.collectAsStateWithLifecycle()
    val measurements by sharedViewModel.measurementsOfSelectedUser.collectAsStateWithLifecycle()
    val connectionStatus by bluetoothViewModel.connectionStatus.collectAsStateWithLifecycle()

    val bluetoothAction = rememberBluetoothActionButton(bluetoothViewModel, sharedViewModel, navController)
    val latest = remember(measurements) { HomeStateMapper.toReading(measurements) }

    var syncWait by remember { mutableStateOf(HomeDisplay.SyncWait()) }
    LaunchedEffect(connectionStatus, latest?.measuredAt) {
        syncWait = HomeDisplay.advanceSyncWait(syncWait, connectionStatus, latest?.measuredAt)
    }
    val isSyncing = HomeDisplay.isBusy(connectionStatus, syncWait)

    var editing by remember { mutableStateOf<ClientEditUiState?>(null) }
    val selectedUser = users.firstOrNull { it.id == selectedUserId }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(R.string.route_title_home)
        sharedViewModel.setTopBarActions(emptyList())
    }

    HomeContent(
        users = users,
        selectedId = selectedUserId ?: -1,
        onSelect = { sharedViewModel.selectUser(it) },
        latest = latest,
        onSync = bluetoothAction.onClick,
        isSyncing = isSyncing,
        onEditPerson = { selectedUser?.let { editing = ClientEditUiState.from(it) } },
        editEnabled = selectedUser != null,
        onPrint = {
            val uid = selectedUserId
            val mid = latest?.measurementId
            if (uid != null && mid != null && !sharing) {
                sharing = true
                coroutineScope.launch {
                    try {
                        val artwork = ReportArtwork.load(context.assets)
                        reportViewModel.renderPdf(uid, mid, artwork)
                            .onSuccess { (bytes, fileName) ->
                                val uri = ReportShare.writeBytes(context, fileName, bytes)
                                ReportShare.share(context, uri, "application/pdf")
                            }
                            .onFailure { e ->
                                sharedViewModel.showSnackbar(
                                    messageResId = R.string.export_error_generic,
                                    formatArgs = listOf(e.localizedMessage ?: "Unknown error"),
                                )
                            }
                    } finally {
                        sharing = false
                    }
                }
            }
        },
        printEnabled = latest != null && !sharing,
    )

    editing?.let { state ->
        ClientEditDialog(
            state = state,
            onChange = { editing = it },
            onDismiss = { editing = null },
            onSave = {
                selectedUser?.let { user ->
                    state.applyTo(user)?.let { sharedViewModel.updateUser(it) }
                }
                editing = null
            },
        )
    }
}

@Composable
fun HomeContent(
    users: List<User>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    latest: HomeUiState.Reading?,
    onSync: () -> Unit,
    isSyncing: Boolean,
    onEditPerson: () -> Unit = {},
    editEnabled: Boolean = true,
    onPrint: () -> Unit = {},
    printEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        UserSwitcherRow(users = users, selectedId = selectedId, onSelect = onSelect)

        TextButton(
            onClick = onEditPerson,
            enabled = editEnabled,
            modifier = Modifier.align(Alignment.End).padding(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.home_action_edit_person))
        }

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
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
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

        OutlinedButton(
            onClick = onPrint,
            enabled = printEnabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.home_action_print_report))
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

@Composable
private fun ClientEditDialog(
    state: ClientEditUiState,
    onChange: (ClientEditUiState) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_edit_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { onChange(state.copy(name = it)) },
                    label = { Text(stringResource(R.string.user_detail_label_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.phone,
                    onValueChange = { onChange(state.copy(phone = it)) },
                    label = { Text(stringResource(R.string.report_header_label_phone)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.email,
                    onValueChange = { onChange(state.copy(email = it)) },
                    label = { Text(stringResource(R.string.report_header_label_email)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
                    Button(onClick = onSave, enabled = state.isValid()) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
