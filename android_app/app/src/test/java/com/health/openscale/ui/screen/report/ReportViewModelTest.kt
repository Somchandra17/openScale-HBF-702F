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
package com.health.openscale.ui.screen.report

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.report.ClientBlock
import com.health.openscale.core.report.CoachBlock
import com.health.openscale.core.report.ReportModel
import com.health.openscale.ui.screen.history.HistoryRow
import org.junit.Test
import java.time.LocalDateTime
import java.util.Locale

/**
 * Tests for the pure decisions behind the Report screen — [ReportSelection] (default weigh-in,
 * export-enabled), [ReportPreviewMapper] (header preview from a built [ReportModel]) and
 * [ReportCsvNaming]. [ReportContent] (ReportScreen.kt) has no JVM-testable Compose harness in
 * this project; see the UI Testing Policy in the Task 11 brief. [ReportContent] itself is left
 * to render already-decided state and is verified on-device at sign-off.
 */
class ReportViewModelTest {

    // -------------------------------------------------------------------------
    // ReportSelection.defaultMeasurementId
    // -------------------------------------------------------------------------

    private fun row(measurementId: Int, dateLabel: String = "1 Jan 2026") =
        HistoryRow(measurementId, dateLabel, "68.4 kg", "0.0")

    @Test
    fun `defaultMeasurementId is null when there are no weigh-ins`() {
        assertThat(ReportSelection.defaultMeasurementId(emptyList())).isNull()
    }

    @Test
    fun `defaultMeasurementId picks the first (newest) row, not the smallest or largest id`() {
        // rows is newest-first, per HistoryStateMapper.toRows: id order must not matter here.
        val rows = listOf(row(7, "30 Aug 2026"), row(12, "23 Aug 2026"), row(3, "16 Aug 2026"))

        assertThat(ReportSelection.defaultMeasurementId(rows)).isEqualTo(7)
    }

    // -------------------------------------------------------------------------
    // ReportSelection.isExportEnabled
    // -------------------------------------------------------------------------

    @Test
    fun `isExportEnabled is false with nothing selected`() {
        assertThat(ReportSelection.isExportEnabled(null)).isFalse()
    }

    @Test
    fun `isExportEnabled is true once a weigh-in is selected`() {
        assertThat(ReportSelection.isExportEnabled(12)).isTrue()
    }

    // -------------------------------------------------------------------------
    // ReportPreviewMapper.toPreview
    // -------------------------------------------------------------------------

    private fun model(
        coach: CoachBlock = CoachBlock(
            name = "Reena Chandra",
            title = "Weight Loss Coach",
            club = "Fit Club",
            phone = "9800000000",
            email = "reena@example.com",
        ),
        client: ClientBlock = ClientBlock(
            name = "Asha Verma",
            phone = "9811111111",
            email = "asha@example.com",
            ageYears = 34,
            gender = GenderType.FEMALE,
            heightCm = 162f,
        ),
    ) = ReportModel(
        coach = coach,
        client = client,
        measuredAt = LocalDateTime.of(2026, 8, 30, 9, 14),
        deviceName = "Omron HBF-702T",
        rows = emptyList(),
    )

    @Test
    fun `toPreview carries client and coach contact fields through unchanged when all are set`() {
        val preview = ReportPreviewMapper.toPreview(model())

        assertThat(preview.clientName).isEqualTo("Asha Verma")
        assertThat(preview.clientPhone).isEqualTo("9811111111")
        assertThat(preview.clientEmail).isEqualTo("asha@example.com")
        assertThat(preview.coachName).isEqualTo("Reena Chandra")
        assertThat(preview.coachTitle).isEqualTo("Weight Loss Coach")
        assertThat(preview.coachClub).isEqualTo("Fit Club")
        assertThat(preview.coachPhone).isEqualTo("9800000000")
        assertThat(preview.coachEmail).isEqualTo("reena@example.com")
        assertThat(preview.deviceName).isEqualTo("Omron HBF-702T")
    }

    @Test
    fun `toPreview flags a blank club name so the coach notices it before printing`() {
        val blankClub = model(
            coach = CoachBlock(
                name = "Reena Chandra",
                title = "Weight Loss Coach",
                club = "",
                phone = "9800000000",
                email = "reena@example.com",
            ),
        )

        val preview = ReportPreviewMapper.toPreview(blankClub)

        assertThat(preview.coachClub).isEqualTo(REPORT_HEADER_MISSING_PLACEHOLDER)
        assertThat(preview.coachClub).isNotEmpty() // a blank line would be easy to miss
    }

    @Test
    fun `toPreview flags a blank client phone number so the coach notices it before printing`() {
        val blankPhone = model(
            client = ClientBlock(
                name = "Asha Verma",
                phone = "",
                email = "asha@example.com",
                ageYears = 34,
                gender = GenderType.FEMALE,
                heightCm = 162f,
            ),
        )

        val preview = ReportPreviewMapper.toPreview(blankPhone)

        assertThat(preview.clientPhone).isEqualTo(REPORT_HEADER_MISSING_PLACEHOLDER)
    }

    @Test
    fun `toPreview formats the measured-at date as day month-abbrev year regardless of default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // comma-decimal locale
            val preview = ReportPreviewMapper.toPreview(model())
            assertThat(preview.measuredAtLabel).isEqualTo("30 Aug 2026")
        } finally {
            Locale.setDefault(original)
        }
    }

    // -------------------------------------------------------------------------
    // ReportCsvNaming.suggestedFileName
    // -------------------------------------------------------------------------

    @Test
    fun `csv file name replaces whitespace in the client name with underscores`() {
        assertThat(ReportCsvNaming.suggestedFileName("Asha Verma"))
            .isEqualTo("openScale_export_Asha_Verma.csv")
    }

    @Test
    fun `csv file name truncates a very long client name`() {
        val longName = "A".repeat(50)
        val fileName = ReportCsvNaming.suggestedFileName(longName)
        assertThat(fileName).isEqualTo("openScale_export_${"A".repeat(20)}.csv")
    }
}
