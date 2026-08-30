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
package com.health.openscale.ui.screen.report

import com.health.openscale.core.report.ReportArtwork
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.data.User
import com.health.openscale.core.report.CLIENT_AGE_UNKNOWN
import com.health.openscale.ui.screen.components.UserSwitcherRow
import com.health.openscale.ui.screen.history.HistoryRow
import com.health.openscale.ui.screen.history.HistoryStateMapper
import com.health.openscale.ui.shared.SharedViewModel
import kotlinx.coroutines.launch

/**
 * Where the client handout gets made: pick a client (the always-visible [UserSwitcherRow]),
 * pick one of their weigh-ins (defaulting to the most recent), review the header fields that
 * will print on the sheet, then export. PDF exports exactly the selected weigh-in; CSV exports
 * that client's entire history — see [ReportContent]'s doc for why the two deliberately differ
 * in scope.
 *
 * Thin wrapper: collects [sharedViewModel] state, derives [rows] via the same
 * [HistoryStateMapper] History uses (so "the most recent weigh-in" means the same thing on both
 * screens), tracks which row is picked, and drives [ReportViewModel] for everything that needs
 * [com.health.openscale.core.report.ReportUseCases]. Hands already-decided state to
 * [ReportContent], which decides nothing itself.
 */
@Composable
fun ReportScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val users by sharedViewModel.allUsers.collectAsStateWithLifecycle()
    val selectedUserId by sharedViewModel.selectedUserId.collectAsStateWithLifecycle()
    val measurements by sharedViewModel.measurementsOfSelectedUser.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()

    val rows = remember(measurements) { HistoryStateMapper.toRows(measurements) }

    // Same top-bar contract as Home and History: this tab owns the title, and carries no
    // actions of its own.
    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(R.string.route_title_report)
        sharedViewModel.setTopBarActions(emptyList())
    }

    // Screen-local pick, not shared state: which weigh-in is being reported on is only relevant
    // here. Reset (to the newest again) whenever the client switches.
    var selectedMeasurementId by remember(selectedUserId) {
        mutableStateOf(ReportSelection.defaultMeasurementId(rows))
    }

    // If the row set changes under the current pick (e.g. that weigh-in was deleted elsewhere,
    // or this is the first composition after a sync) and the pick no longer exists in it, fall
    // back to the newest again.
    LaunchedEffect(rows) {
        if (rows.none { it.measurementId == selectedMeasurementId }) {
            selectedMeasurementId = ReportSelection.defaultMeasurementId(rows)
        }
    }

    LaunchedEffect(selectedUserId, selectedMeasurementId) {
        val uid = selectedUserId
        viewModel.onSelectionChanged(uid ?: -1, if (uid == null) null else selectedMeasurementId)
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    ReportContent(
        users = users,
        selectedId = selectedUserId ?: -1,
        onSelect = { sharedViewModel.selectUser(it) },
        rows = rows,
        selectedMeasurementId = selectedMeasurementId,
        onPick = { selectedMeasurementId = it },
        preview = (previewState as? ReportViewModel.PreviewState.Loaded)?.preview,
        previewFailed = previewState is ReportViewModel.PreviewState.Failed,
        previewLoading = previewState is ReportViewModel.PreviewState.Loading,
        onExportPdf = {
            val uid = selectedUserId
            val mid = selectedMeasurementId
            if (uid != null && mid != null) {
                coroutineScope.launch {
                    val artwork = ReportArtwork.load(context.assets)
                    viewModel.renderPdf(uid, mid, artwork)
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
                }
            }
        },
        onExportCsv = {
            val uid = selectedUserId
            val userName = users.firstOrNull { it.id == selectedUserId }?.name.orEmpty()
            if (uid != null) {
                coroutineScope.launch {
                    val fileName = ReportCsvNaming.suggestedFileName(userName)
                    val uri = ReportShare.createEmpty(context, fileName)
                    sharedViewModel.exportCsvToUri(uid, uri, context.contentResolver)
                        .onSuccess { rows ->
                            if (rows > 0) ReportShare.share(context, uri, "text/csv")
                            else sharedViewModel.showSnackbar(messageResId = R.string.export_error_no_exportable_values)
                        }
                        .onFailure { e ->
                            sharedViewModel.showSnackbar(
                                messageResId = R.string.export_error_generic,
                                formatArgs = listOf(e.localizedMessage ?: "Unknown error"),
                            )
                        }
                }
            }
        },
    )
}

/**
 * Stateless: renders exactly the state it is handed and forwards taps. No ordering, no
 * defaulting, no formatting decisions live here — those are all in ReportViewModel.kt
 * ([ReportSelection], [ReportPreviewMapper], [ReportCsvNaming]).
 *
 * The two export actions deliberately differ in scope: [onExportPdf] exports only
 * [selectedMeasurementId] (a client handout, one sheet per visit); [onExportCsv] exports that
 * client's *entire* history (a spreadsheet dump), independent of which row is picked. Their
 * enabled states differ too, on purpose: CSV only needs a weigh-in selected
 * ([ReportSelection.isCsvExportEnabled]); PDF additionally needs the header preview to have
 * finished loading ([ReportSelection.isPdfExportEnabled]), since it needs a real, built
 * `ReportModel` (and the filename that comes with it) to act on — see
 * [ReportSelection.isPdfExportEnabled]'s doc for why enabling it any earlier is a dead click,
 * not a rare race. While that load is in flight, the PDF button shows a progress spinner
 * (mirroring `HomeContent`'s "Sync scale" button) so the disabled state reads as "still working",
 * not as "broken".
 */
@Composable
fun ReportContent(
    users: List<User>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    rows: List<HistoryRow>,
    selectedMeasurementId: Int?,
    onPick: (Int) -> Unit,
    preview: ReportHeaderPreview?,
    onExportPdf: () -> Unit,
    onExportCsv: () -> Unit,
    modifier: Modifier = Modifier,
    previewFailed: Boolean = false,
    previewLoading: Boolean = false,
) {
    val csvExportEnabled = ReportSelection.isCsvExportEnabled(selectedMeasurementId)
    val pdfExportEnabled = ReportSelection.isPdfExportEnabled(selectedMeasurementId, previewLoaded = preview != null)

    Column(modifier = modifier.fillMaxSize()) {
        UserSwitcherRow(users = users, selectedId = selectedId, onSelect = onSelect)

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.report_label_no_weighins),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { it.measurementId }) { row ->
                    WeighInPickerRow(
                        row = row,
                        isSelected = row.measurementId == selectedMeasurementId,
                        onClick = { onPick(row.measurementId) },
                    )
                }
            }
        }

        if (preview != null) {
            ReportHeaderPreviewCard(preview)
        } else if (previewFailed) {
            Text(
                text = stringResource(R.string.report_header_load_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onExportPdf,
                enabled = pdfExportEnabled,
                modifier = Modifier.weight(1f),
            ) {
                if (previewLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text(stringResource(R.string.report_action_export_pdf))
            }
            OutlinedButton(
                onClick = onExportCsv,
                enabled = csvExportEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.report_action_export_csv))
            }
        }
    }
}

@Composable
private fun WeighInPickerRow(row: HistoryRow, isSelected: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isSelected, onClick = onClick)
                Text(text = row.dateLabel, style = MaterialTheme.typography.bodyMedium)
            }
            Text(text = row.weightLabel, style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider()
    }
}

/**
 * The read-only header fields that will print on the sheet — client and coach contact blocks,
 * plus device and measurement date — so a missing phone number or an unset club name is
 * something the coach notices here, not on the printed page.
 */
@Composable
private fun ReportHeaderPreviewCard(preview: ReportHeaderPreview, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.report_header_section_client),
                style = MaterialTheme.typography.titleSmall,
            )
            PreviewLine(stringResource(R.string.report_header_label_name), preview.clientName)
            PreviewLine(stringResource(R.string.report_header_label_phone), preview.clientPhone)
            PreviewLine(stringResource(R.string.report_header_label_email), preview.clientEmail)
            // Age, sex and height drive every banded row on the printed sheet, so the coach
            // must be able to spot a missing or wrong one here — before printing, not after.
            // An unset birth date shows as a placeholder rather than a fabricated age. See
            // finding B4.
            PreviewLine(
                stringResource(R.string.report_header_label_age),
                if (preview.ageYears == CLIENT_AGE_UNKNOWN) {
                    REPORT_HEADER_MISSING_PLACEHOLDER
                } else {
                    "${preview.ageYears}"
                },
            )
            PreviewLine(
                stringResource(R.string.report_header_label_gender),
                preview.gender.getDisplayName(LocalContext.current),
            )
            PreviewLine(
                stringResource(R.string.report_header_label_height),
                String.format(java.util.Locale.US, "%.0f cm", preview.heightCm),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Text(
                text = stringResource(R.string.report_header_section_coach),
                style = MaterialTheme.typography.titleSmall,
            )
            PreviewLine(stringResource(R.string.report_header_label_name), preview.coachName)
            PreviewLine(stringResource(R.string.report_header_label_title), preview.coachTitle)
            PreviewLine(stringResource(R.string.report_header_label_club), preview.coachClub)
            PreviewLine(stringResource(R.string.report_header_label_phone), preview.coachPhone)
            PreviewLine(stringResource(R.string.report_header_label_email), preview.coachEmail)

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            PreviewLine(stringResource(R.string.report_header_label_device), preview.deviceName)
            PreviewLine(stringResource(R.string.report_header_label_measured_at), preview.measuredAtLabel)
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
