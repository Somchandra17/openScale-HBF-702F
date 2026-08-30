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

import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.model.MeasurementWithValues
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * As with Home (see HomeViewModel.kt), History has no Hilt ViewModel of its own —
 * [com.health.openscale.ui.shared.SharedViewModel.measurementsOfSelectedUser] already streams
 * the newest-first measurement list this screen needs. What lives here is the pure,
 * JVM-testable derivation of that list into what the screen displays: row order, row text, and
 * the sparkline's points.
 */

/** One printed row: an already-decided label and a plain int id, nothing left for the row to compute. */
data class HistoryRow(
    val measurementId: Int,
    val dateLabel: String,
    val weightLabel: String,
    val deltaLabel: String,
)

/**
 * Derives [HistoryRow]s and sparkline points from a user's measurements. Pure and Compose-free.
 */
object HistoryStateMapper {

    // Locale.US throughout: a comma-decimal locale would print "68,4 kg" (see
    // com.health.openscale.core.report.ReportRowBuilder, which pins the same way).
    private val L = Locale.US
    private val DATE_FORMAT = SimpleDateFormat("d MMM yyyy", L)

    /**
     * [measurements] must already be newest-first, as returned by
     * [com.health.openscale.core.facade.MeasurementFacade.getMeasurementsForUser] — this
     * function does not sort, and preserves that order: History is a reverse-chronological list.
     * Each row's delta is against its older neighbour (the next entry in this same newest-first
     * list). Empty input yields an empty list (the empty-state case).
     */
    fun toRows(measurements: List<MeasurementWithValues>): List<HistoryRow> =
        measurements.mapIndexed { index, mwv ->
            val values = valuesByKey(mwv)
            val weightKg = values[MeasurementTypeKey.WEIGHT.name] ?: 0f
            val previousWeight = measurements.getOrNull(index + 1)
                ?.let { valuesByKey(it)[MeasurementTypeKey.WEIGHT.name] }

            HistoryRow(
                measurementId = mwv.measurement.id,
                dateLabel = DATE_FORMAT.format(Date(mwv.measurement.timestamp)),
                weightLabel = String.format(L, "%.1f kg", weightKg),
                deltaLabel = formatDelta(previousWeight?.let { weightKg - it } ?: 0f),
            )
        }

    /**
     * Weight values oldest → newest, for the sparkline: it reads left-to-right as time passing,
     * the opposite order from [toRows]'s newest-first list.
     */
    fun sparklinePoints(measurements: List<MeasurementWithValues>): List<Float> =
        measurements.asReversed().mapNotNull { valuesByKey(it)[MeasurementTypeKey.WEIGHT.name] }

    /** Explicit "+" for a gain, "-" (from Java's formatting) for a loss, no sign for no change. */
    private fun formatDelta(delta: Float): String =
        if (delta > 0f) String.format(L, "+%.1f", delta) else String.format(L, "%.1f", delta)

    /** Same convention [com.health.openscale.core.report.ReportUseCases] uses to build its value map. */
    private fun valuesByKey(mwv: MeasurementWithValues): Map<String, Float> =
        mwv.values.mapNotNull { v -> v.value.floatValue?.let { f -> v.type.key.name to f } }.toMap()
}
