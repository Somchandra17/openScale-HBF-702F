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
    val band: Band = Band.NONE,
)

data class ReportModel(
    val coach: CoachBlock,
    val client: ClientBlock,
    val measuredAt: LocalDateTime,
    val deviceName: String,
    val rows: List<ReportRow>,
    val summary: String = "",
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
        Spec(MeasurementTypeKey.BODY_FAT, "Body fat %") { String.format(L, "%.1f %%", it) },
        Spec(MeasurementTypeKey.MUSCLE, "Skeletal muscle %") { String.format(L, "%.1f %%", it) },
        Spec(MeasurementTypeKey.BMI, "BMI") { String.format(L, "%.1f", it) },
        Spec(MeasurementTypeKey.VISCERAL_FAT, "Visceral fat") { String.format(L, "%.1f", it) },
        Spec(MeasurementTypeKey.BMR, "Resting metabolism") { String.format(L, "%.0f kcal", it) },
        Spec(MeasurementTypeKey.BODY_AGE, "Body age") { String.format(L, "%.0f years", it) },
    )

    /**
     * [values] is keyed by [MeasurementTypeKey.name]; absent keys become dashed rows.
     *
     * Fat mass and muscle mass are not sent by the HBF-702T; they are derived from the
     * machine's weight × percentage so the sheet can show kg alongside %. TSF is not a
     * scale output and is never invented.
     */
    fun build(values: Map<String, Float>, client: ClientBlock): List<ReportRow> {
        val weight = values[MeasurementTypeKey.WEIGHT.name]
        val fat = values[MeasurementTypeKey.BODY_FAT.name]
        val muscle = values[MeasurementTypeKey.MUSCLE.name]
        val bmr = values[MeasurementTypeKey.BMR.name]
        fun spec(key: MeasurementTypeKey) = SPECS.first { it.key == key }

        val bmiValue = values[MeasurementTypeKey.BMI.name] ?: bmiFrom(weight, client.heightCm)
        val bmiClass = bmiValue?.let {
            ReferenceRanges.classify(MeasurementTypeKey.BMI, it, client.ageYears, client.gender)
        } ?: UNBANDED
        val fatClass = fat?.let {
            ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, it, client.ageYears, client.gender)
        } ?: UNBANDED
        val muscleClass = muscle?.let {
            ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, it, client.ageYears, client.gender)
        } ?: UNBANDED
        val bmrClass = if (bmr != null && weight != null) {
            ReferenceRanges.classifyBmr(bmr, client.ageYears, client.gender, weight, client.heightCm)
        } else {
            UNBANDED
        }

        return listOf(
            readingRow(spec(MeasurementTypeKey.WEIGHT), weight, rangeInKgFromBmi(bmiClass, client.heightCm)),
            readingRow(spec(MeasurementTypeKey.BODY_FAT), fat, fatClass),
            derivedMassRow("Fat mass", weight, fat, rangeInKgFromPercent(fatClass, weight)),
            readingRow(spec(MeasurementTypeKey.MUSCLE), muscle, muscleClass),
            derivedMassRow("Muscle mass", weight, muscle, rangeInKgFromPercent(muscleClass, weight)),
            readingRow(spec(MeasurementTypeKey.BMI), bmiValue, bmiClass),
            machineRow(spec(MeasurementTypeKey.VISCERAL_FAT), values, client),
            readingRow(spec(MeasurementTypeKey.BMR), bmr, bmrClass),
            machineRow(spec(MeasurementTypeKey.BODY_AGE), values, client),
        )
    }

    private val UNBANDED = Classification(Band.NONE, DASH, DASH)

    private fun bmiFrom(weightKg: Float?, heightCm: Float): Float? {
        if (weightKg == null || heightCm <= 0f) return null
        val m = heightCm / 100f
        return weightKg / (m * m)
    }

    private fun readingRow(spec: Spec, value: Float?, classification: Classification): ReportRow {
        if (value == null) return ReportRow(spec.label, DASH, DASH, DASH)
        return ReportRow(spec.label, spec.format(value), classification.label, classification.normalRange, classification.band)
    }

    private fun machineRow(spec: Spec, values: Map<String, Float>, client: ClientBlock): ReportRow {
        val v = values[spec.key.name]
        return when {
            v == null -> ReportRow(spec.label, DASH, DASH, DASH)
            spec.key == MeasurementTypeKey.BODY_AGE -> bodyAgeRow(spec, v, client)
            else -> {
                val c = ReferenceRanges.classify(spec.key, v, client.ageYears, client.gender)
                ReportRow(spec.label, spec.format(v), c.label, c.normalRange, c.band)
            }
        }
    }

    private fun rangeInKgFromBmi(c: Classification, heightCm: Float): Classification {
        val lo = c.normalLow
        val hi = c.normalHigh
        if (lo == null || hi == null || heightCm <= 0f) {
            return c.copy(normalRange = DASH, normalLow = null, normalHigh = null)
        }
        val m2 = (heightCm / 100f) * (heightCm / 100f)
        return c.copy(normalRange = String.format(L, "%.1f – %.1f kg", lo * m2, hi * m2))
    }

    private fun rangeInKgFromPercent(c: Classification, weightKg: Float?): Classification {
        val lo = c.normalLow
        val hi = c.normalHigh
        if (lo == null || hi == null || weightKg == null || weightKg <= 0f) {
            return c.copy(normalRange = DASH, normalLow = null, normalHigh = null)
        }
        return c.copy(normalRange = String.format(L, "%.1f – %.1f kg", weightKg * lo / 100f, weightKg * hi / 100f))
    }

    private fun derivedMassRow(
        label: String,
        weightKg: Float?,
        percent: Float?,
        classification: Classification,
    ): ReportRow {
        if (weightKg == null || percent == null) return ReportRow(label, DASH, DASH, DASH)
        val kg = weightKg * percent / 100f
        return ReportRow(label, String.format(L, "%.1f kg", kg), classification.label, classification.normalRange, classification.band)
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

/**
 * One-paragraph English wrap-up printed above Remarks. Covers every mix of
 * High/Low/Normal/missing, plus body-age delta. Does not invent advice.
 */
object ReportSummary {

    fun build(rows: List<ReportRow>): String {
        val clauses = mutableListOf<String>()

        addOnce(clauses, rows, setOf("Weight", "BMI"), ::bmiClause)
        addOnce(clauses, rows, setOf("Body fat %", "Fat mass")) { r ->
            "body fat is ${r.status.lowercase()}"
        }
        addOnce(clauses, rows, setOf("Skeletal muscle %", "Muscle mass")) { r ->
            "skeletal muscle is ${r.status.lowercase()}"
        }
        addOnce(clauses, rows, setOf("Visceral fat")) { r ->
            "visceral fat is ${r.status.lowercase()}"
        }
        addOnce(clauses, rows, setOf("Resting metabolism")) { r ->
            "resting metabolism is ${r.status.lowercase()}"
        }

        val ageSentence = bodyAgeSentence(rows)
        val notable = clauses.isNotEmpty() || ageSentence != null
        if (!notable) {
            return if (rows.any { it.reading != "—" }) {
                "Readings are within the expected ranges."
            } else {
                "No measurements to summarise."
            }
        }

        val first = if (clauses.isEmpty()) {
            null
        } else {
            joinClauses(clauses).replaceFirstChar { it.uppercase() } + "."
        }
        return listOfNotNull(first, ageSentence).joinToString(" ")
    }

    private fun addOnce(
        out: MutableList<String>,
        rows: List<ReportRow>,
        labels: Set<String>,
        phrase: (ReportRow) -> String,
    ) {
        val row = rows.firstOrNull {
            it.label in labels && it.band != Band.NONE && it.band != Band.NORMAL
        } ?: return
        out += phrase(row)
    }

    private fun bmiClause(row: ReportRow): String = when (row.band) {
        Band.LOW -> "BMI is in the underweight range"
        Band.HIGH -> "BMI is in the overweight range"
        Band.VERY_HIGH -> "BMI is in the obese range"
        else -> "BMI is ${row.status.lowercase()}"
    }

    private fun bodyAgeSentence(rows: List<ReportRow>): String? {
        val row = rows.firstOrNull { it.label == "Body age" } ?: return null
        val match = Regex("""^([+-])(\d+) yrs$""").matchEntire(row.status) ?: return null
        val years = match.groupValues[2].toInt()
        if (years == 0) return null
        val direction = if (match.groupValues[1] == "+") "above" else "below"
        return "Body age is $years ${if (years == 1) "year" else "years"} $direction actual age."
    }

    private fun joinClauses(clauses: List<String>): String = when (clauses.size) {
        0 -> ""
        1 -> clauses[0]
        2 -> "${clauses[0]} and ${clauses[1]}"
        else -> clauses.dropLast(1).joinToString(", ") + ", and " + clauses.last()
    }
}
