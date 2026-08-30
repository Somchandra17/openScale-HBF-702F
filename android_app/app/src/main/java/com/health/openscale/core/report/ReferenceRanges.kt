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

/**
 * Classification bands printed in the report's Status column.
 *
 * [NONE] means the metric has no meaningful banding (weight, BMR) or cannot be banded
 * for this client (missing age or sex, or a minor). It prints an em dash rather than
 * an invented verdict.
 */
enum class Band { LOW, NORMAL, HIGH, VERY_HIGH, NONE }

/** One row's worth of interpretation. [normalRange] is pre-formatted for printing. */
data class Classification(
    val band: Band,
    val label: String,
    val normalRange: String,
    val normalLow: Float? = null,
    val normalHigh: Float? = null,
)

/**
 * Age- and sex-aware reference ranges for the metrics an Omron HBF-702T reports.
 *
 * ## Every threshold in this app lives in this file, on purpose.
 *
 * These numbers decide whether a client is told they are "Normal" or "Obese" on a
 * printed sheet. They are transcribed from published Omron (Gallagher et al. 2000)
 * and Asian-Pacific BMI references and **must be verified against the manual supplied
 * with the scale** before use in the practice. Keeping them in one file makes a
 * correction a one-line edit rather than an archaeology exercise.
 *
 * This is a fitness classification, not a medical diagnosis.
 */
object ReferenceRanges {

    private const val DASH = "—"
    private val UNBANDED = Classification(Band.NONE, DASH, DASH)

    /** Adult thresholds do not apply to children; below this we refuse to band. */
    private const val MIN_ADULT_AGE = 18

    /** Upper and lower bounds of a band, plus the label printed for it. */
    private data class Cut(val normalLow: Float, val normalHigh: Float, val highHigh: Float)

    fun classify(
        key: MeasurementTypeKey,
        value: Float,
        ageYears: Int,
        gender: GenderType,
    ): Classification = when (key) {
        MeasurementTypeKey.BMI -> classifyBmi(value)
        MeasurementTypeKey.VISCERAL_FAT -> classifyVisceralFat(value)
        MeasurementTypeKey.BODY_FAT -> classifyAgeSex(value, ageYears, gender, bodyFatCut(ageYears, gender), "%")
        MeasurementTypeKey.MUSCLE -> classifyAgeSex(value, ageYears, gender, muscleCut(ageYears, gender), "%")
        else -> UNBANDED
    }

    /**
     * Resting metabolism vs Mifflin–St Jeor predicted BMR for this client.
     * Normal if measured is within ±10% of predicted. Needs adult age, weight and height.
     */
    fun classifyBmr(
        measuredKcal: Float,
        ageYears: Int,
        gender: GenderType,
        weightKg: Float,
        heightCm: Float,
    ): Classification {
        if (ageYears < MIN_ADULT_AGE || ageYears == CLIENT_AGE_UNKNOWN) return UNBANDED
        if (weightKg <= 0f || heightCm <= 0f) return UNBANDED
        val predicted = mifflinStJeor(ageYears, gender, weightKg, heightCm)
        val lo = predicted * 0.90f
        val hi = predicted * 1.10f
        val band = when {
            measuredKcal < lo -> Band.LOW
            measuredKcal > hi -> Band.HIGH
            else -> Band.NORMAL
        }
        val predictedInt = kotlin.math.round(predicted).toInt()
        return Classification(band, bandLabel(band), "$predictedInt kcal (±10%)")
    }

    /** Mifflin–St Jeor resting kcal/day. */
    internal fun mifflinStJeor(
        ageYears: Int,
        gender: GenderType,
        weightKg: Float,
        heightCm: Float,
    ): Float {
        val base = 10f * weightKg + 6.25f * heightCm - 5f * ageYears
        return if (gender == GenderType.FEMALE) base - 161f else base + 5f
    }

    // -- BMI: Indian / Asian-Pacific, sex- and age-independent ---------------------

    private fun classifyBmi(value: Float): Classification {
        val band = when {
            value < 18.0f -> Band.LOW
            value < 23.0f -> Band.NORMAL
            value < 25.0f -> Band.HIGH
            else -> Band.VERY_HIGH
        }
        val label = when (band) {
            Band.LOW -> "Underweight"
            Band.NORMAL -> "Normal"
            Band.HIGH -> "Overweight"
            else -> "Obese"
        }
        return Classification(band, label, "18.0 – 22.9", 18.0f, 22.9f)
    }

    // -- Visceral fat: half steps on the 702T, sex- and age-independent ------------

    private fun classifyVisceralFat(value: Float): Classification {
        val band = when {
            value < 10.0f -> Band.NORMAL
            value < 15.0f -> Band.HIGH
            else -> Band.VERY_HIGH
        }
        return Classification(band, bandLabel(band), "0.5 – 9.5", 0.5f, 9.5f)
    }

    // -- Body fat % (Omron / Gallagher et al. 2000) --------------------------------

    private fun bodyFatCut(ageYears: Int, gender: GenderType): Cut? {
        if (ageYears < MIN_ADULT_AGE) return null
        return if (gender == GenderType.FEMALE) {
            when {
                ageYears < 40 -> Cut(21.0f, 32.9f, 38.9f)
                ageYears < 60 -> Cut(23.0f, 33.9f, 39.9f)
                else -> Cut(24.0f, 35.9f, 41.9f)
            }
        } else {
            when {
                ageYears < 40 -> Cut(8.0f, 19.9f, 24.9f)
                ageYears < 60 -> Cut(11.0f, 21.9f, 27.9f)
                else -> Cut(13.0f, 24.9f, 29.9f)
            }
        }
    }

    // -- Skeletal muscle % (Omron) -------------------------------------------------

    private fun muscleCut(ageYears: Int, gender: GenderType): Cut? {
        if (ageYears < MIN_ADULT_AGE) return null
        return if (gender == GenderType.FEMALE) {
            when {
                ageYears < 40 -> Cut(24.3f, 30.3f, 35.3f)
                ageYears < 60 -> Cut(24.1f, 30.1f, 35.1f)
                else -> Cut(23.9f, 29.9f, 34.9f)
            }
        } else {
            when {
                ageYears < 40 -> Cut(33.3f, 39.3f, 44.0f)
                ageYears < 60 -> Cut(33.1f, 39.1f, 43.8f)
                else -> Cut(32.9f, 38.9f, 43.6f)
            }
        }
    }

    private fun classifyAgeSex(
        value: Float,
        ageYears: Int,
        gender: GenderType,
        cut: Cut?,
        unit: String,
    ): Classification {
        if (cut == null) return UNBANDED
        val band = when {
            value < cut.normalLow -> Band.LOW
            value <= cut.normalHigh -> Band.NORMAL
            value <= cut.highHigh -> Band.HIGH
            else -> Band.VERY_HIGH
        }
        val range = "${fmt(cut.normalLow)} – ${fmt(cut.normalHigh)} $unit"
        return Classification(band, bandLabel(band), range, cut.normalLow, cut.normalHigh)
    }

    private fun bandLabel(band: Band): String = when (band) {
        Band.LOW -> "Low"
        Band.NORMAL -> "Normal"
        Band.HIGH -> "High"
        Band.VERY_HIGH -> "Very high"
        Band.NONE -> DASH
    }

    // Locale.US, not the default: a comma-decimal locale would print "21,0 – 32,9 %".
    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)
}
