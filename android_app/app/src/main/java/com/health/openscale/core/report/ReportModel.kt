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
package com.health.openscale.core.report

import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementTypeKey
import java.time.LocalDateTime

/**
 * Sentinel [ClientBlock.ageYears] for "this client's birth date was never set" (an untouched,
 * freshly seeded profile — see [User.birthDate]'s own 0L "not set" sentinel in
 * `OpenScaleApp.getDefaultUsers`). Deliberately a value [ReferenceRanges] already refuses to
 * band on its own terms (below `MIN_ADULT_AGE`), and unmistakably out of range for a real age
 * (never negative) so it can't be confused with a rounding artefact — rather than reusing 0,
 * which a literal newborn could someday have. Consumers that print age directly (the PDF header,
 * the header preview card) must check for this value themselves rather than printing it as-is.
 * See finding B4.
 */
const val CLIENT_AGE_UNKNOWN = -1

data class CoachBlock(
    val name: String,
    val title: String,
    val club: String,
    val phone: String,
    val email: String,
)

data class ClientBlock(
    val name: String,
    val phone: String,
    val email: String,
    val ageYears: Int,
    val gender: GenderType,
    val heightCm: Float,
)

/** One printed table row. All fields are pre-formatted; the renderer does no maths. */
data class ReportRow(
    val label: String,
    val reading: String,
    val status: String,
    val normalRange: String,
)

data class ReportModel(
    val coach: CoachBlock,
    val client: ClientBlock,
    val measuredAt: LocalDateTime,
    val deviceName: String,
    val rows: List<ReportRow>,
)

/**
 * Turns raw measurement values into printable rows.
 *
 * The row set is fixed and ordered: a metric the scale did not report still yields a
 * row, dashed out, so every sheet has the same shape regardless of what landed.
 */
object ReportRowBuilder {

    private const val DASH = "—"

    private data class Spec(
        val key: MeasurementTypeKey,
        val label: String,
        val format: (Float) -> String,
    )

    // Locale.US throughout: a comma-decimal locale would print "68,4 kg" on the sheet.
    private val L = java.util.Locale.US

    private val SPECS = listOf(
        Spec(MeasurementTypeKey.WEIGHT, "Weight") { String.format(L, "%.1f kg", it) },
        Spec(MeasurementTypeKey.BODY_FAT, "Body fat") { String.format(L, "%.1f %%", it) },
        Spec(MeasurementTypeKey.MUSCLE, "Skeletal muscle") { String.format(L, "%.1f %%", it) },
        Spec(MeasurementTypeKey.BMI, "BMI") { String.format(L, "%.1f", it) },
        Spec(MeasurementTypeKey.VISCERAL_FAT, "Visceral fat") { String.format(L, "%.1f", it) },
        Spec(MeasurementTypeKey.BMR, "Resting metabolism") { String.format(L, "%.0f kcal", it) },
        Spec(MeasurementTypeKey.BODY_AGE, "Body age") { String.format(L, "%.0f years", it) },
    )

    /** [values] is keyed by [MeasurementTypeKey.name]; absent keys become dashed rows. */
    fun build(values: Map<String, Float>, client: ClientBlock): List<ReportRow> =
        SPECS.map { spec ->
            val v = values[spec.key.name]
            when {
                v == null -> ReportRow(spec.label, DASH, DASH, DASH)
                spec.key == MeasurementTypeKey.BODY_AGE -> bodyAgeRow(spec, v, client)
                else -> {
                    val c = ReferenceRanges.classify(spec.key, v, client.ageYears, client.gender)
                    ReportRow(spec.label, spec.format(v), c.label, c.normalRange)
                }
            }
        }

    private fun bodyAgeRow(spec: Spec, value: Float, client: ClientBlock): ReportRow {
        // Mirrors PdfReportRenderer's own CLIENT_AGE_UNKNOWN guard: the reading is a real,
        // machine-reported value and still prints, but with no actual age on file there is
        // nothing to compare it against, so status/normalRange dash out rather than guessing
        // (or printing the literal sentinel). See finding B4.
        if (client.ageYears == CLIENT_AGE_UNKNOWN) {
            return ReportRow(spec.label, spec.format(value), DASH, DASH)
        }
        val delta = value.toInt() - client.ageYears
        val status = if (delta >= 0) "+$delta yrs" else "$delta yrs"
        return ReportRow(spec.label, spec.format(value), status, "${client.ageYears} (actual age)")
    }
}
