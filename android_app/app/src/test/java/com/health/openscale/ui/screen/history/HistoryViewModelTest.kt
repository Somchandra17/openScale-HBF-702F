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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.testutil.Fixtures
import org.junit.Test
import java.util.Locale

/**
 * Tests for [HistoryStateMapper] — the pure decisions behind History ([HistoryContent] in
 * HistoryScreen.kt has no JVM-testable Compose harness in this project; see the UI testing
 * policy in the task brief). [HistoryContent] itself is left to render already-decided state
 * and is verified on-device at sign-off.
 */
class HistoryViewModelTest {

    private val weightType = Fixtures.type(id = 1, key = MeasurementTypeKey.WEIGHT)

    private fun reading(measurementId: Int, timestamp: Long, weightKg: Float): MeasurementWithValues =
        Fixtures.mwv(measurementId, timestamp, listOf(Fixtures.valueWithType(weightType, weightKg, measurementId)))

    // -------------------------------------------------------------------------
    // toRows — order
    // -------------------------------------------------------------------------

    @Test
    fun `toRows is empty for an empty measurement list`() {
        assertThat(HistoryStateMapper.toRows(emptyList())).isEmpty()
    }

    @Test
    fun `toRows keeps measurements in the newest-first order they arrive in`() {
        // As MeasurementFacade#getMeasurementsForUser returns: newest first.
        val measurements = listOf(
            reading(12, Fixtures.ts(2026, 8, 30), 68.4f),
            reading(11, Fixtures.ts(2026, 8, 23), 69.5f),
            reading(10, Fixtures.ts(2026, 8, 16), 70.2f),
        )

        val rows = HistoryStateMapper.toRows(measurements)

        assertThat(rows.map { it.measurementId }).containsExactly(12, 11, 10).inOrder()
    }

    // -------------------------------------------------------------------------
    // toRows — per-row text
    // -------------------------------------------------------------------------

    @Test
    fun `toRows computes each row's delta against its older neighbour`() {
        val measurements = listOf(
            reading(12, Fixtures.ts(2026, 8, 30), 68.4f),
            reading(11, Fixtures.ts(2026, 8, 23), 69.5f),
        )

        val rows = HistoryStateMapper.toRows(measurements)

        assertThat(rows[0].deltaLabel).isEqualTo("-1.1") // 68.4 - 69.5
        assertThat(rows[1].deltaLabel).isEqualTo("0.0")  // oldest row: no older neighbour
    }

    @Test
    fun `toRows shows a dash instead of a fabricated 0_0 kg for a weigh-in with no weight value`() {
        // Home already returns null for the same condition; this is a wrong number on screen,
        // not a formatting nicety. The row itself must still be emitted. See finding D3.
        val bmiOnlyType = Fixtures.type(id = 2, key = MeasurementTypeKey.BMI)
        val noWeightMwv = Fixtures.mwv(
            measurementId = 1,
            timestamp = Fixtures.ts(2026, 8, 30),
            values = listOf(Fixtures.valueWithType(bmiOnlyType, 24.8f, measurementId = 1)),
        )

        val rows = HistoryStateMapper.toRows(listOf(noWeightMwv))

        assertThat(rows).hasSize(1)
        assertThat(rows.single().weightLabel).isEqualTo("—")
    }

    @Test
    fun `toRows formats weight with one decimal, a kg suffix, and a period decimal point regardless of default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // comma-decimal locale
            val rows = HistoryStateMapper.toRows(listOf(reading(1, Fixtures.ts(2026, 8, 30), 68.4f)))
            assertThat(rows.single().weightLabel).isEqualTo("68.4 kg")
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `toRows formats the date as day month-abbrev year regardless of default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val rows = HistoryStateMapper.toRows(listOf(reading(1, Fixtures.ts(2026, 8, 30), 68.4f)))
            assertThat(rows.single().dateLabel).isEqualTo("30 Aug 2026")
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `toRows adds an explicit plus sign for a gain`() {
        val measurements = listOf(
            reading(2, Fixtures.ts(2026, 8, 30), 69.0f),
            reading(1, Fixtures.ts(2026, 8, 23), 68.4f),
        )

        val rows = HistoryStateMapper.toRows(measurements)

        assertThat(rows[0].deltaLabel).isEqualTo("+0.6")
    }

    // -------------------------------------------------------------------------
    // sparklinePoints
    // -------------------------------------------------------------------------

    @Test
    fun `sparklinePoints is empty for an empty measurement list`() {
        assertThat(HistoryStateMapper.sparklinePoints(emptyList())).isEmpty()
    }

    @Test
    fun `sparklinePoints reverses the newest-first input to oldest-to-newest`() {
        val measurements = listOf(
            reading(12, Fixtures.ts(2026, 8, 30), 68.4f),
            reading(11, Fixtures.ts(2026, 8, 23), 69.5f),
            reading(10, Fixtures.ts(2026, 8, 16), 70.2f),
        )

        val points = HistoryStateMapper.sparklinePoints(measurements)

        assertThat(points).containsExactly(70.2f, 69.5f, 68.4f).inOrder()
    }
}
