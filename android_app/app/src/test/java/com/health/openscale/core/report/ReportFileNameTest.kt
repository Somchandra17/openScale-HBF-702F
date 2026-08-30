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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.GenderType
import org.junit.Test
import java.time.LocalDateTime

class ReportFileNameTest {

    private fun model(name: String) = ReportModel(
        coach = CoachBlock("Reena Chandra", "Weight Loss Coach", "", "", ""),
        client = ClientBlock(name, "", "", 34, GenderType.FEMALE, 162f),
        measuredAt = LocalDateTime.of(2026, 8, 30, 9, 14),
        deviceName = "Omron HBF-702T",
        rows = emptyList(),
    )

    @Test
    fun `file name is the client and date`() {
        assertThat(ReportUseCases.suggestedFileName(model("Asha Verma")))
            .isEqualTo("Asha Verma - 30 Aug 2026.pdf")
    }

    @Test
    fun `file name never leaks the app name`() {
        val n = ReportUseCases.suggestedFileName(model("Asha Verma")).lowercase()
        assertThat(n).doesNotContain("openscale")
    }

    @Test
    fun `path separators in a client name are stripped`() {
        assertThat(ReportUseCases.suggestedFileName(model("A/B\\C")))
            .isEqualTo("ABC - 30 Aug 2026.pdf")
    }
}
