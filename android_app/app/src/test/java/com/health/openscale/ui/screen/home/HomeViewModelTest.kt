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
package com.health.openscale.ui.screen.home

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ConnectionStatus
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.testutil.Fixtures
import org.junit.Test
import java.util.Locale

/**
 * Tests for [HomeStateMapper] and [HomeDisplay] — the pure decisions behind Home
 * ([HomeContent] in HomeScreen.kt has no JVM-testable Compose harness in this project; see the
 * UI testing policy in the task brief). [HomeContent] itself is left to render already-decided
 * state and is verified on-device at sign-off.
 */
class HomeViewModelTest {

    private val weightType = Fixtures.type(id = 1, key = MeasurementTypeKey.WEIGHT)
    private val fatType = Fixtures.type(id = 2, key = MeasurementTypeKey.BODY_FAT)
    private val muscleType = Fixtures.type(id = 3, key = MeasurementTypeKey.MUSCLE)
    private val bmiType = Fixtures.type(id = 4, key = MeasurementTypeKey.BMI)
    private val visceralType = Fixtures.type(id = 5, key = MeasurementTypeKey.VISCERAL_FAT)

    private fun reading(
        measurementId: Int,
        timestamp: Long,
        weightKg: Float,
        fatPercent: Float? = null,
        musclePercent: Float? = null,
        bmi: Float? = null,
        visceralFat: Float? = null,
    ): MeasurementWithValues {
        val values = buildList {
            add(Fixtures.valueWithType(weightType, weightKg, measurementId))
            fatPercent?.let { add(Fixtures.valueWithType(fatType, it, measurementId)) }
            musclePercent?.let { add(Fixtures.valueWithType(muscleType, it, measurementId)) }
            bmi?.let { add(Fixtures.valueWithType(bmiType, it, measurementId)) }
            visceralFat?.let { add(Fixtures.valueWithType(visceralType, it, measurementId)) }
        }
        return Fixtures.mwv(measurementId, timestamp, values)
    }

    // -------------------------------------------------------------------------
    // HomeStateMapper.toReading
    // -------------------------------------------------------------------------

    @Test
    fun `toReading returns null for an empty measurement list`() {
        assertThat(HomeStateMapper.toReading(emptyList())).isNull()
    }

    @Test
    fun `toReading returns the newest measurement's values with zero delta when there is no previous reading`() {
        val only = reading(1, Fixtures.ts(2026, 8, 30), 68.4f, fatPercent = 28.1f, musclePercent = 31.0f, bmi = 24.8f, visceralFat = 9f)

        val result = HomeStateMapper.toReading(listOf(only))

        assertThat(result).isEqualTo(
            HomeUiState.Reading(
                measurementId = 1,
                measuredAt = Fixtures.ts(2026, 8, 30),
                weightKg = 68.4f,
                deltaKg = 0f,
                fatPercent = 28.1f,
                musclePercent = 31.0f,
                bmi = 24.8f,
                visceralFat = 9f,
            )
        )
    }

    @Test
    fun `toReading computes delta against the previous (older) measurement's weight`() {
        // Newest-first, as MeasurementFacade#getMeasurementsForUser returns.
        val measurements = listOf(
            reading(2, Fixtures.ts(2026, 8, 30), 68.4f),
            reading(1, Fixtures.ts(2026, 8, 23), 69.0f),
        )

        val result = HomeStateMapper.toReading(measurements)

        assertThat(result?.weightKg).isEqualTo(68.4f)
        assertThat(result?.deltaKg).isWithin(1e-4f).of(-0.6f)
    }

    @Test
    fun `toReading defaults fat, muscle, bmi and visceral fat to zero when absent from the newest reading`() {
        val measurements = listOf(reading(1, Fixtures.ts(2026, 8, 30), 68.4f))

        val result = HomeStateMapper.toReading(measurements)

        assertThat(result?.fatPercent).isEqualTo(0f)
        assertThat(result?.musclePercent).isEqualTo(0f)
        assertThat(result?.bmi).isEqualTo(0f)
        assertThat(result?.visceralFat).isEqualTo(0f)
    }

    @Test
    fun `toReading returns null when the newest measurement carries no weight value`() {
        val noWeight = Fixtures.mwv(1, Fixtures.ts(2026, 8, 30), listOf(Fixtures.valueWithType(fatType, 28f, 1)))

        assertThat(HomeStateMapper.toReading(listOf(noWeight))).isNull()
    }

    // -------------------------------------------------------------------------
    // HomeDisplay — display-string formatting, pinned to Locale.US
    // -------------------------------------------------------------------------

    @Test
    fun `weightLabel formats with one decimal, a kg suffix, and a period decimal point regardless of default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // comma-decimal locale
            assertThat(HomeDisplay.weightLabel(68.4f)).isEqualTo("68.4 kg")
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `deltaLabel adds an explicit plus sign for a gain`() {
        assertThat(HomeDisplay.deltaLabel(0.6f)).isEqualTo("+0.6 kg")
    }

    @Test
    fun `deltaLabel keeps the sign Java already prints for a loss`() {
        assertThat(HomeDisplay.deltaLabel(-0.6f)).isEqualTo("-0.6 kg")
    }

    @Test
    fun `deltaLabel has no sign for no change`() {
        assertThat(HomeDisplay.deltaLabel(0f)).isEqualTo("0.0 kg")
    }

    @Test
    fun `percentLabel formats with one decimal and a percent sign, pinned to Locale US`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertThat(HomeDisplay.percentLabel(28.1f)).isEqualTo("28.1%")
        } finally {
            Locale.setDefault(original)
        }
    }

    // -------------------------------------------------------------------------
    // HomeDisplay sync-wait
    // -------------------------------------------------------------------------

    @Test
    fun `isBusy is true while connected — the scale is still sending records`() {
        assertThat(HomeDisplay.isBusy(ConnectionStatus.CONNECTING, HomeDisplay.SyncWait())).isTrue()
        assertThat(HomeDisplay.isBusy(ConnectionStatus.CONNECTED, HomeDisplay.SyncWait())).isTrue()
        assertThat(HomeDisplay.isBusy(ConnectionStatus.DISCONNECTING, HomeDisplay.SyncWait())).isTrue()
        assertThat(HomeDisplay.isBusy(ConnectionStatus.DISCONNECTED, HomeDisplay.SyncWait())).isFalse()
        assertThat(HomeDisplay.isBusy(ConnectionStatus.FAILED, HomeDisplay.SyncWait())).isFalse()
    }

    @Test
    fun `advanceSyncWait arms on CONNECTING and stays busy until a newer timestamp arrives`() {
        var wait = HomeDisplay.SyncWait()
        wait = HomeDisplay.advanceSyncWait(wait, ConnectionStatus.CONNECTING, newestTimestamp = 100L)
        assertThat(wait.awaiting).isTrue()
        assertThat(HomeDisplay.isBusy(ConnectionStatus.CONNECTED, wait)).isTrue()

        wait = HomeDisplay.advanceSyncWait(wait, ConnectionStatus.CONNECTED, newestTimestamp = 100L)
        assertThat(wait.awaiting).isTrue()

        wait = HomeDisplay.advanceSyncWait(wait, ConnectionStatus.CONNECTED, newestTimestamp = 200L)
        assertThat(wait.awaiting).isFalse()
        assertThat(HomeDisplay.isBusy(ConnectionStatus.DISCONNECTED, wait)).isFalse()
    }

    @Test
    fun `advanceSyncWait clears on disconnect after a handshake with no new reading`() {
        var wait = HomeDisplay.advanceSyncWait(HomeDisplay.SyncWait(), ConnectionStatus.CONNECTING, 100L)
        wait = HomeDisplay.advanceSyncWait(wait, ConnectionStatus.DISCONNECTED, 100L)
        assertThat(wait.awaiting).isFalse()
    }

    @Test
    fun `advanceSyncWait does not arm on DISCONNECTED so a denied-permission tap cannot latch`() {
        val wait = HomeDisplay.advanceSyncWait(HomeDisplay.SyncWait(), ConnectionStatus.DISCONNECTED, 100L)
        assertThat(wait.awaiting).isFalse()
        assertThat(HomeDisplay.isBusy(ConnectionStatus.DISCONNECTED, wait)).isFalse()
    }

    @Test
    fun `ClientEditUiState applyTo writes name phone email birth date sex and height`() {
        val user = Fixtures.user(id = 2, name = "Person 2", birthDate = 0L, heightCm = 170f)
        val edited = ClientEditUiState.from(user).copy(
            name = " Asha Verma ",
            phone = "9811111111",
            email = "asha@example.com",
            birthDate = Fixtures.ts(1992, 1, 1),
            gender = GenderType.FEMALE,
            heightCm = "162",
        ).applyTo(user)

        assertThat(edited).isNotNull()
        assertThat(edited!!.name).isEqualTo("Asha Verma")
        assertThat(edited.phone).isEqualTo("9811111111")
        assertThat(edited.email).isEqualTo("asha@example.com")
        assertThat(edited.birthDate).isEqualTo(Fixtures.ts(1992, 1, 1))
        assertThat(edited.heightCm).isEqualTo(162f)
        assertThat(edited.gender).isEqualTo(GenderType.FEMALE)
    }

    @Test
    fun `ClientEditUiState applyTo rejects a blank name or unparseable height`() {
        val user = Fixtures.user(id = 2, name = "Person 2")
        assertThat(ClientEditUiState.from(user).copy(name = "  ").applyTo(user)).isNull()
        assertThat(ClientEditUiState.from(user).copy(heightCm = "tall").applyTo(user)).isNull()
    }
}
