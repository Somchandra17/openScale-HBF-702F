package com.health.openscale.core.report

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.GenderType
import org.junit.Test
import java.time.LocalDateTime

class ReportModelTest {

    private val coach = CoachBlock(
        name = "Reena Chandra",
        title = "Weight Loss Coach",
        club = "",
        phone = "98xxxxxxxx",
        email = "reena@example.com",
    )

    private val client = ClientBlock(
        name = "Asha Verma",
        phone = "98xxxxxxxx",
        email = "asha@example.com",
        ageYears = 34,
        gender = GenderType.FEMALE,
        heightCm = 162f,
    )

    private fun rowsFor(vararg values: Pair<String, Float>) =
        ReportRowBuilder.build(values.toMap(), client)

    @Test
    fun `builds nine rows in a fixed order including derived masses`() {
        val model = ReportModel(
            coach = coach,
            client = client,
            measuredAt = LocalDateTime.of(2026, 8, 30, 9, 14),
            deviceName = "Omron HBF-702T",
            rows = rowsFor(
                "WEIGHT" to 68.4f, "BODY_FAT" to 28.1f, "MUSCLE" to 31.0f,
                "BMI" to 24.8f, "VISCERAL_FAT" to 8.5f, "BMR" to 1420f, "BODY_AGE" to 41f,
            ),
        )
        assertThat(model.rows).hasSize(9)
        assertThat(model.rows.map { it.label }).containsExactly(
            "Weight", "Body fat %", "Fat mass", "Skeletal muscle %", "Muscle mass",
            "BMI", "Visceral fat", "Resting metabolism", "Body age",
        ).inOrder()
    }

    @Test
    fun `fat mass is weight times body-fat percent, in kg`() {
        val row = rowsFor("WEIGHT" to 68.4f, "BODY_FAT" to 28.1f).single { it.label == "Fat mass" }
        assertThat(row.reading).isEqualTo("19.2 kg")
        assertThat(row.status).isEqualTo("Normal")
        assertThat(row.band).isEqualTo(Band.NORMAL)
    }

    @Test
    fun `muscle mass is weight times skeletal-muscle percent, in kg`() {
        val row = rowsFor("WEIGHT" to 68.4f, "MUSCLE" to 31.0f).single { it.label == "Muscle mass" }
        assertThat(row.reading).isEqualTo("21.2 kg")
        assertThat(row.status).isEqualTo("High")
        assertThat(row.band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `fat mass inherits the body-fat band`() {
        val row = rowsFor("WEIGHT" to 68.4f, "BODY_FAT" to 28.1f).single { it.label == "Fat mass" }
        assertThat(row.status).isEqualTo("Normal")
        assertThat(row.band).isEqualTo(Band.NORMAL)
    }

    @Test
    fun `derived mass rows dash when a factor is missing`() {
        val noWeight = rowsFor("BODY_FAT" to 28.1f).single { it.label == "Fat mass" }
        assertThat(noWeight.reading).isEqualTo("—")
        val noPercent = rowsFor("WEIGHT" to 68.4f).single { it.label == "Fat mass" }
        assertThat(noPercent.reading).isEqualTo("—")
    }

    @Test
    fun `TSF is never a row`() {
        assertThat(rowsFor("WEIGHT" to 68.4f).map { it.label }).doesNotContain("TSF")
    }

    @Test
    fun `a missing metric still produces a row with dashes`() {
        // The sheet's shape must be constant; never omit a row.
        val rows = rowsFor("WEIGHT" to 68.4f)
        val fatRow = rows.single { it.label == "Body fat %" }
        assertThat(fatRow.reading).isEqualTo("—")
        assertThat(fatRow.status).isEqualTo("—")
    }

    @Test
    fun `weight row inherits the BMI band`() {
        val row = rowsFor("WEIGHT" to 68.4f, "BMI" to 24.8f).single { it.label == "Weight" }
        assertThat(row.reading).isEqualTo("68.4 kg")
        assertThat(row.status).isEqualTo("Overweight")
        assertThat(row.band).isEqualTo(Band.HIGH)
        assertThat(row.normalRange).isEqualTo("18.0 – 22.9")
    }

    @Test
    fun `body fat row is classified against the client's age and sex`() {
        val row = rowsFor("BODY_FAT" to 28.1f).single { it.label == "Body fat %" }
        assertThat(row.reading).isEqualTo("28.1 %")
        assertThat(row.status).isEqualTo("Normal")
        assertThat(row.normalRange).isEqualTo("21.0 – 32.9 %")
        assertThat(row.band).isEqualTo(Band.NORMAL)
    }

    @Test
    fun `BMI row is classified against Asian-Pacific cutoffs, independent of age or sex`() {
        val row = rowsFor("BMI" to 24.8f).single { it.label == "BMI" }
        assertThat(row.reading).isEqualTo("24.8")
        assertThat(row.status).isEqualTo("Overweight")
        assertThat(row.normalRange).isEqualTo("18.0 – 22.9")
        assertThat(row.band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `skeletal muscle row is classified against the client's age and sex`() {
        val row = rowsFor("MUSCLE" to 31.0f).single { it.label == "Skeletal muscle %" }
        assertThat(row.reading).isEqualTo("31.0 %")
        assertThat(row.status).isEqualTo("High")
        assertThat(row.normalRange).isEqualTo("24.3 – 30.3 %")
        assertThat(row.band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `body age row shows the delta against actual age`() {
        val row = rowsFor("BODY_AGE" to 41f).single { it.label == "Body age" }
        assertThat(row.reading).isEqualTo("41 years")
        assertThat(row.status).isEqualTo("+7 yrs")
        assertThat(row.normalRange).isEqualTo("34 (actual age)")
    }

    @Test
    fun `body age below actual age shows a negative delta`() {
        val row = rowsFor("BODY_AGE" to 30f).single { it.label == "Body age" }
        assertThat(row.status).isEqualTo("-4 yrs")
    }

    @Test
    fun `bmr prints kcal and carries no status`() {
        val row = rowsFor("BMR" to 1420f).single { it.label == "Resting metabolism" }
        assertThat(row.reading).isEqualTo("1420 kcal")
        assertThat(row.status).isEqualTo("—")
    }

    @Test
    fun `water is never a row`() {
        // The HBF-702T does not measure it.
        assertThat(rowsFor("WEIGHT" to 68.4f).map { it.label }).doesNotContain("Water")
    }

    // --- Incomplete profile: age/sex-dependent rows must not guess -------------------
    //
    // A client whose birth date was never set carries CLIENT_AGE_UNKNOWN (see
    // ReportUseCases.buildModel and OpenScaleApp.getDefaultUsers). Spec §6: age/sex-dependent
    // rows must fall back to Band.NONE with a footnote rather than guessing a band. See
    // finding B4.

    private val clientWithUnsetProfile = client.copy(ageYears = CLIENT_AGE_UNKNOWN)

    @Test
    fun `an unset profile yields Band NONE (a dash) for body fat, not a guessed verdict`() {
        val row = ReportRowBuilder.build(mapOf("BODY_FAT" to 30.0f), clientWithUnsetProfile)
            .single { it.label == "Body fat %" }
        assertThat(row.status).isEqualTo("—")
        assertThat(row.normalRange).isEqualTo("—")
    }

    @Test
    fun `an unset profile yields Band NONE (a dash) for skeletal muscle, not a guessed verdict`() {
        val row = ReportRowBuilder.build(mapOf("MUSCLE" to 31.0f), clientWithUnsetProfile)
            .single { it.label == "Skeletal muscle %" }
        assertThat(row.status).isEqualTo("—")
        assertThat(row.normalRange).isEqualTo("—")
    }

    @Test
    fun `a completed profile still bands body fat and skeletal muscle correctly`() {
        // Same values as the unset-profile cases above, but with a real age/sex on file: both
        // rows must band, proving the fallback is scoped to the missing-profile case only.
        val rows = ReportRowBuilder.build(mapOf("BODY_FAT" to 30.0f, "MUSCLE" to 31.0f), client)

        val bodyFat = rows.single { it.label == "Body fat %" }
        assertThat(bodyFat.status).isEqualTo("Normal") // 34yo female normal range is 21.0-32.9
        val muscle = rows.single { it.label == "Skeletal muscle %" }
        assertThat(muscle.status).isEqualTo("High") // 34yo female normal range tops out at 30.3
    }

    @Test
    fun `an unset profile still shows the measured body age, but dashes status and normal range`() {
        // The HBF-702T reports Body age directly — it's a real machine value, so it must
        // still print, even though there's no actual age on file to compare it against.
        val row = ReportRowBuilder.build(mapOf("BODY_AGE" to 41f), clientWithUnsetProfile)
            .single { it.label == "Body age" }
        assertThat(row.reading).isEqualTo("41 years")
        assertThat(row.status).isEqualTo("—")
        assertThat(row.normalRange).isEqualTo("—")
    }

    @Test
    fun `a completed profile still shows the body age delta, proving the unset-profile fallback is scoped`() {
        val row = ReportRowBuilder.build(mapOf("BODY_AGE" to 41f), client).single { it.label == "Body age" }
        assertThat(row.reading).isEqualTo("41 years")
        assertThat(row.status).isEqualTo("+7 yrs")
        assertThat(row.normalRange).isEqualTo("34 (actual age)")
    }

    @Test
    fun `BMR within 10 percent of predicted is Normal`() {
        // 34yo female, 68.4 kg, 162 cm → Mifflin 1380 kcal. 1420 is within ±10%.
        val row = rowsFor("WEIGHT" to 68.4f, "BMR" to 1420f).single { it.label == "Resting metabolism" }
        assertThat(row.status).isEqualTo("Normal")
        assertThat(row.band).isEqualTo(Band.NORMAL)
    }

    @Test
    fun `BMR well above predicted is High`() {
        val row = rowsFor("WEIGHT" to 68.4f, "BMR" to 2000f).single { it.label == "Resting metabolism" }
        assertThat(row.status).isEqualTo("High")
        assertThat(row.band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `BMR is unbanded when age is unknown`() {
        val row = ReportRowBuilder.build(
            mapOf("WEIGHT" to 68.4f, "BMR" to 1420f),
            clientWithUnsetProfile,
        ).single { it.label == "Resting metabolism" }
        assertThat(row.status).isEqualTo("—")
        assertThat(row.band).isEqualTo(Band.NONE)
    }

    // --- Summary: every mix of findings ---------------------------------------

    @Test
    fun `summary says expected ranges when every banded row is Normal`() {
        val rows = rowsFor(
            "WEIGHT" to 55.0f, "BODY_FAT" to 28.1f, "MUSCLE" to 27.0f,
            "BMI" to 21.0f, "VISCERAL_FAT" to 8.0f, "BMR" to 1230f, "BODY_AGE" to 34f,
        )
        assertThat(ReportSummary.build(rows)).isEqualTo("Readings are within the expected ranges.")
    }

    @Test
    fun `summary says nothing to summarise when every reading is dashed`() {
        assertThat(ReportSummary.build(rowsFor())).isEqualTo("No measurements to summarise.")
    }

    @Test
    fun `summary covers the printed sheet with mixed highs and lows and an older body age`() {
        val som = client.copy(ageYears = 23, gender = GenderType.MALE, heightCm = 165f)
        val rows = ReportRowBuilder.build(
            mapOf(
                "WEIGHT" to 73.8f, "BODY_FAT" to 27.3f, "MUSCLE" to 30.8f,
                "BMI" to 25.5f, "VISCERAL_FAT" to 10.5f, "BMR" to 1644f, "BODY_AGE" to 40f,
            ),
            som,
        )
        val summary = ReportSummary.build(rows)
        assertThat(summary).contains("BMI is in the obese range")
        assertThat(summary).contains("body fat is very high")
        assertThat(summary).contains("skeletal muscle is low")
        assertThat(summary).contains("visceral fat is high")
        assertThat(summary).contains("Body age is 17 years above actual age.")
        assertThat(summary).doesNotContain("fat mass")
        assertThat(summary).doesNotContain("muscle mass")
    }

    @Test
    fun `summary of a single underweight BMI`() {
        val rows = rowsFor("BMI" to 17.0f)
        assertThat(ReportSummary.build(rows)).isEqualTo("BMI is in the underweight range.")
    }

    @Test
    fun `summary of a single overweight BMI`() {
        val rows = rowsFor("BMI" to 24.0f)
        assertThat(ReportSummary.build(rows)).isEqualTo("BMI is in the overweight range.")
    }

    @Test
    fun `summary of only a younger body age`() {
        val rows = rowsFor("BODY_AGE" to 30f)
        assertThat(ReportSummary.build(rows)).isEqualTo("Body age is 4 years below actual age.")
    }

    @Test
    fun `summary of a one-year body age gap uses the singular`() {
        val rows = rowsFor("BODY_AGE" to 35f)
        assertThat(ReportSummary.build(rows)).isEqualTo("Body age is 1 year above actual age.")
    }

    @Test
    fun `summary omits body age when actual age is unknown`() {
        val rows = ReportRowBuilder.build(mapOf("BODY_AGE" to 40f, "BMI" to 25.5f), clientWithUnsetProfile)
        val summary = ReportSummary.build(rows)
        assertThat(summary).contains("BMI is in the obese range")
        assertThat(summary).doesNotContain("Body age")
    }

    @Test
    fun `summary of two findings joins with and`() {
        val rows = rowsFor("BODY_FAT" to 40.0f, "VISCERAL_FAT" to 16.0f)
        val summary = ReportSummary.build(rows)
        assertThat(summary).isEqualTo("Body fat is very high and visceral fat is very high.")
    }

    @Test
    fun `summary of high BMR is included`() {
        val rows = rowsFor("WEIGHT" to 68.4f, "BMR" to 2000f)
        assertThat(ReportSummary.build(rows)).contains("resting metabolism is high")
    }

    @Test
    fun `summary does not mention Normal rows`() {
        val rows = rowsFor("BODY_FAT" to 28.1f, "VISCERAL_FAT" to 16.0f)
        val summary = ReportSummary.build(rows)
        assertThat(summary).doesNotContain("body fat")
        assertThat(summary.lowercase()).contains("visceral fat is very high")
    }
}
