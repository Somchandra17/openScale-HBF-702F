package com.health.openscale.core.report

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementTypeKey
import org.junit.Test

class ReferenceRangesTest {

    // --- BMI: Indian / Asian-Pacific cut-offs ---------------------------------

    @Test
    fun `bmi 22_9 is normal for asian pacific cutoffs`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BMI, 22.9f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NORMAL)
        assertThat(c.normalRange).isEqualTo("18.0 – 22.9")
    }

    @Test
    fun `bmi 23_0 is overweight not normal`() {
        // The whole point of the Asian-Pacific table: WHO would call this normal.
        val c = ReferenceRanges.classify(MeasurementTypeKey.BMI, 23.0f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.HIGH)
        assertThat(c.label).isEqualTo("Overweight")
    }

    @Test
    fun `bmi 25_0 is obese`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BMI, 25.0f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.VERY_HIGH)
        assertThat(c.label).isEqualTo("Obese")
    }

    @Test
    fun `bmi 17_9 is underweight`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BMI, 17.9f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.LOW)
        assertThat(c.label).isEqualTo("Underweight")
    }

    // --- Body fat: sex- and age-dependent -------------------------------------

    @Test
    fun `body fat 32_9 is normal for a 34 year old woman`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 32.9f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NORMAL)
        assertThat(c.normalRange).isEqualTo("21.0 – 32.9 %")
    }

    @Test
    fun `body fat 33_0 is high for a 34 year old woman`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 33.0f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `body fat 33_0 is very high for a 34 year old man`() {
        // Same reading, different sex, three bands apart.
        val c = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 33.0f, 34, GenderType.MALE)
        assertThat(c.band).isEqualTo(Band.VERY_HIGH)
    }

    @Test
    fun `body fat 33_5 is normal for a 45 year old woman but high at 34`() {
        val younger = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 33.5f, 34, GenderType.FEMALE)
        val older = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 33.5f, 45, GenderType.FEMALE)
        assertThat(younger.band).isEqualTo(Band.HIGH)
        assertThat(older.band).isEqualTo(Band.NORMAL)
    }

    // --- Skeletal muscle ------------------------------------------------------

    @Test
    fun `skeletal muscle 31_0 is high for a 34 year old woman`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, 31.0f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.HIGH)
        assertThat(c.normalRange).isEqualTo("24.3 – 30.3 %")
    }

    @Test
    fun `skeletal muscle 30_3 is the top of normal for a 34 year old woman`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, 30.3f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NORMAL)
    }

    // --- Visceral fat: half steps, sex/age independent ------------------------

    @Test
    fun `visceral fat 9_5 is normal`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.VISCERAL_FAT, 9.5f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NORMAL)
        assertThat(c.normalRange).isEqualTo("0.5 – 9.5")
    }

    @Test
    fun `visceral fat 10_0 is high`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.VISCERAL_FAT, 10.0f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `visceral fat 15_0 is very high`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.VISCERAL_FAT, 15.0f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.VERY_HIGH)
    }

    // --- Unbanded metrics -----------------------------------------------------

    @Test
    fun `weight has no band`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.WEIGHT, 68.4f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NONE)
        assertThat(c.label).isEqualTo("—")
        assertThat(c.normalRange).isEqualTo("—")
    }

    @Test
    fun `bmr has no band`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BMR, 1420f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NONE)
    }

    // --- Age guards -----------------------------------------------------------

    @Test
    fun `under 18 gets no band for age dependent metrics`() {
        // Adult thresholds are wrong for children; refuse rather than mislead.
        val c = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 25f, 16, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NONE)
    }

    @Test
    fun `age 19 clamps to the youngest adult bracket`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 32.9f, 19, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NORMAL)
    }

    @Test
    fun `age above 79 clamps to the oldest bracket`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 35.9f, 85, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NORMAL)
    }

    // --- Interior age-bracket seams ---------------------------------------------
    //
    // Only the outer clamps (19, 85) were previously tested; the interior seams where the
    // age bracket actually switches were unguarded, and a transposed boundary there would be
    // silent — these thresholds decide what a real client is told. See finding D1.
    //
    // Note: this implementation has exactly two interior seams per metric/sex — 39/40 and
    // 59/60 (the 60+ bracket is the oldest; there is no separate 80+ bracket to seam-test).
    // Each value below sits strictly between the two neighbouring brackets' cut-offs, so the
    // band genuinely differs on either side of the seam — not just the printed range string.

    @Test
    fun `body fat 39_40 seam for women- HIGH just under, NORMAL at 40`() {
        val under = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 33.4f, 39, GenderType.FEMALE)
        val at = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 33.4f, 40, GenderType.FEMALE)
        assertThat(under.band).isEqualTo(Band.HIGH)
        assertThat(at.band).isEqualTo(Band.NORMAL)
    }

    @Test
    fun `body fat 39_40 seam for men- HIGH just under, NORMAL at 40`() {
        val under = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 20.9f, 39, GenderType.MALE)
        val at = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 20.9f, 40, GenderType.MALE)
        assertThat(under.band).isEqualTo(Band.HIGH)
        assertThat(at.band).isEqualTo(Band.NORMAL)
    }

    @Test
    fun `body fat 59_60 seam for women- HIGH just under, NORMAL at 60`() {
        val under = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 34.9f, 59, GenderType.FEMALE)
        val at = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 34.9f, 60, GenderType.FEMALE)
        assertThat(under.band).isEqualTo(Band.HIGH)
        assertThat(at.band).isEqualTo(Band.NORMAL)
    }

    @Test
    fun `body fat 59_60 seam for men- HIGH just under, NORMAL at 60`() {
        val under = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 22.9f, 59, GenderType.MALE)
        val at = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 22.9f, 60, GenderType.MALE)
        assertThat(under.band).isEqualTo(Band.HIGH)
        assertThat(at.band).isEqualTo(Band.NORMAL)
    }

    @Test
    fun `skeletal muscle 39_40 seam for women- NORMAL just under, HIGH at 40`() {
        // Muscle cut-offs move DOWN with age (unlike body fat), so the seam flips direction.
        val under = ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, 30.2f, 39, GenderType.FEMALE)
        val at = ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, 30.2f, 40, GenderType.FEMALE)
        assertThat(under.band).isEqualTo(Band.NORMAL)
        assertThat(at.band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `skeletal muscle 39_40 seam for men- NORMAL just under, HIGH at 40`() {
        val under = ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, 39.2f, 39, GenderType.MALE)
        val at = ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, 39.2f, 40, GenderType.MALE)
        assertThat(under.band).isEqualTo(Band.NORMAL)
        assertThat(at.band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `skeletal muscle 59_60 seam for women- NORMAL just under, HIGH at 60`() {
        val under = ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, 30.0f, 59, GenderType.FEMALE)
        val at = ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, 30.0f, 60, GenderType.FEMALE)
        assertThat(under.band).isEqualTo(Band.NORMAL)
        assertThat(at.band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `skeletal muscle 59_60 seam for men- NORMAL just under, HIGH at 60`() {
        val under = ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, 39.0f, 59, GenderType.MALE)
        val at = ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, 39.0f, 60, GenderType.MALE)
        assertThat(under.band).isEqualTo(Band.NORMAL)
        assertThat(at.band).isEqualTo(Band.HIGH)
    }
}
