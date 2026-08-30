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
package com.health.openscale.ui.screen.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.screen.components.UserSwitcherRow
import com.health.openscale.ui.shared.SharedViewModel
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill

/**
 * Reverse-chronological weigh-in list for the selected client, with a compact weight sparkline
 * above it. Tapping a row navigates to the existing [com.health.openscale.ui.screen.overview.MeasurementDetailScreen]
 * for view/edit/delete — this screen does not re-implement that editor.
 *
 * Thin wrapper: collects [sharedViewModel] state, reduces it via [HistoryStateMapper]
 * (HistoryViewModel.kt), and hands already-decided state to [HistoryContent].
 */
@Composable
fun HistoryScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
) {
    val users by sharedViewModel.allUsers.collectAsStateWithLifecycle()
    val selectedUserId by sharedViewModel.selectedUserId.collectAsStateWithLifecycle()
    val measurements by sharedViewModel.measurementsOfSelectedUser.collectAsStateWithLifecycle()

    val rows = remember(measurements) { HistoryStateMapper.toRows(measurements) }
    val sparklinePoints = remember(measurements) { HistoryStateMapper.sparklinePoints(measurements) }

    HistoryContent(
        users = users,
        selectedId = selectedUserId ?: -1,
        onSelect = { sharedViewModel.selectUser(it) },
        rows = rows,
        sparklinePoints = sparklinePoints,
        onRowClick = { measurementId ->
            navController.navigate(Routes.measurementDetail(measurementId, selectedUserId))
        },
    )
}

/**
 * Stateless: renders exactly the state it is handed and forwards taps. No ordering, no
 * defaulting, no formatting decisions live here — those are all in HistoryViewModel.kt.
 *
 * [sparklinePoints] is a separate parameter (not part of [HistoryRow]) since it is a single
 * period-level shape, not a per-row value — kept out of the `HistoryRow` shape Task 11 imports.
 */
@Composable
fun HistoryContent(
    users: List<User>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    rows: List<HistoryRow>,
    onRowClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    sparklinePoints: List<Float> = emptyList(),
) {
    Column(modifier = modifier.fillMaxSize()) {
        UserSwitcherRow(users = users, selectedId = selectedId, onSelect = onSelect)

        if (sparklinePoints.size >= 2) {
            WeightSparkline(
                points = sparklinePoints,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (rows.isEmpty()) {
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
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(rows, key = { it.measurementId }) { row ->
                    HistoryRowItem(row = row, onClick = { onRowClick(row.measurementId) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRowItem(row: HistoryRow, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = row.dateLabel, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = row.weightLabel, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = row.deltaLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
    }
}

/**
 * A compact weight-over-time line, no axes and no markers — just the shape of the trend.
 * [points] must have at least two entries; the caller (see [HistoryContent]) only renders this
 * when that holds.
 */
@Composable
private fun WeightSparkline(points: List<Float>, modifier: Modifier = Modifier) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val lineColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineModel { series(y = points) }
        }
    }

    val layer = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(
            LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(lineColor)),
                stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp),
                areaFill = null,
                pointProvider = null,
                interpolator = LineCartesianLayer.Interpolator.cubic(),
            )
        ),
    )
    val chart = rememberCartesianChart(layer, startAxis = null, bottomAxis = null)

    CartesianChartHost(chart = chart, modelProducer = modelProducer, modifier = modifier)
}
