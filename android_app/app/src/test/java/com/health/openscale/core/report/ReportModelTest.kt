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
    fun `builds seven rows in a fixed order`() {
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
        assertThat(model.rows).hasSize(7)
        assertThat(model.rows.map { it.label }).containsExactly(
            "Weight", "Body fat", "Skeletal muscle", "BMI",
            "Visceral fat", "Resting metabolism", "Body age",
        ).inOrder()
    }

    @Test
    fun `a missing metric still produces a row with dashes`() {
        // The sheet's shape must be constant; never omit a row.
        val rows = rowsFor("WEIGHT" to 68.4f)
        val fatRow = rows.single { it.label == "Body fat" }
        assertThat(fatRow.reading).isEqualTo("—")
        assertThat(fatRow.status).isEqualTo("—")
    }

    @Test
    fun `weight row carries no status`() {
        val row = rowsFor("WEIGHT" to 68.4f).single { it.label == "Weight" }
        assertThat(row.reading).isEqualTo("68.4 kg")
        assertThat(row.status).isEqualTo("—")
        assertThat(row.normalRange).isEqualTo("—")
    }

    @Test
    fun `body fat row is classified against the client's age and sex`() {
        val row = rowsFor("BODY_FAT" to 28.1f).single { it.label == "Body fat" }
        assertThat(row.reading).isEqualTo("28.1 %")
        assertThat(row.status).isEqualTo("Normal")
        assertThat(row.normalRange).isEqualTo("21.0 – 32.9 %")
    }

    @Test
    fun `BMI row is classified against Asian-Pacific cutoffs, independent of age or sex`() {
        val row = rowsFor("BMI" to 24.8f).single { it.label == "BMI" }
        assertThat(row.reading).isEqualTo("24.8")
        assertThat(row.status).isEqualTo("Overweight")
        assertThat(row.normalRange).isEqualTo("18.0 – 22.9")
    }

    @Test
    fun `skeletal muscle row is classified against the client's age and sex`() {
        val row = rowsFor("MUSCLE" to 31.0f).single { it.label == "Skeletal muscle" }
        assertThat(row.reading).isEqualTo("31.0 %")
        assertThat(row.status).isEqualTo("High")
        assertThat(row.normalRange).isEqualTo("24.3 – 30.3 %")
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
            .single { it.label == "Body fat" }
        assertThat(row.status).isEqualTo("—")
        assertThat(row.normalRange).isEqualTo("—")
    }

    @Test
    fun `an unset profile yields Band NONE (a dash) for skeletal muscle, not a guessed verdict`() {
        val row = ReportRowBuilder.build(mapOf("MUSCLE" to 31.0f), clientWithUnsetProfile)
            .single { it.label == "Skeletal muscle" }
        assertThat(row.status).isEqualTo("—")
        assertThat(row.normalRange).isEqualTo("—")
    }

    @Test
    fun `a completed profile still bands body fat and skeletal muscle correctly`() {
        // Same values as the unset-profile cases above, but with a real age/sex on file: both
        // rows must band, proving the fallback is scoped to the missing-profile case only.
        val rows = ReportRowBuilder.build(mapOf("BODY_FAT" to 30.0f, "MUSCLE" to 31.0f), client)

        val bodyFat = rows.single { it.label == "Body fat" }
        assertThat(bodyFat.status).isEqualTo("Normal") // 34yo female normal range is 21.0-32.9
        val muscle = rows.single { it.label == "Skeletal muscle" }
        assertThat(muscle.status).isEqualTo("High") // 34yo female normal range tops out at 30.3
    }
}
