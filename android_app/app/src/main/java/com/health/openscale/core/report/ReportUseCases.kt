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

import android.content.ContentResolver
import android.net.Uri
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.CalculationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReportUseCases @Inject constructor(
    private val repository: DatabaseRepository,
    private val settingsFacade: SettingsFacade,
) {
    suspend fun buildModel(userId: Int, measurementId: Int): Result<ReportModel> = runCatching {
        val user = repository.getUserById(userId).first()
            ?: error("No user with id=$userId")
        val mwv = repository.getMeasurementWithValuesById(measurementId).first()
            ?: error("No measurement with id=$measurementId")
        // User and measurement are fetched independently and can legitimately disagree during a
        // client switch (selectedUserId already moved to B while `rows` still belongs to A) —
        // without this check the model would silently carry client B's identity fields next to
        // client A's readings, the worst failure a printed handout can have. See finding D4.
        check(mwv.measurement.userId == userId) {
            "Measurement $measurementId belongs to user ${mwv.measurement.userId}, not requested user $userId"
        }

        val values: Map<String, Float> = mwv.values
            .mapNotNull { v -> v.value.floatValue?.let { v.type.key.name to it } }
            .toMap()

        val client = ClientBlock(
            name = user.name,
            phone = user.phone,
            email = user.email,
            // birthDate == 0L is the "not set" sentinel a freshly seeded profile carries (see
            // OpenScaleApp.getDefaultUsers). Computing a real ageOn() against epoch would yield
            // a plausible-looking but entirely made-up age (decades old) that then confidently
            // (and wrongly) bands against ReferenceRanges' cut-offs. CLIENT_AGE_UNKNOWN is below
            // ReferenceRanges.MIN_ADULT_AGE, so every age/sex-dependent row falls back to
            // Band.NONE instead. See finding B4.
            ageYears = if (user.birthDate == 0L) {
                CLIENT_AGE_UNKNOWN
            } else {
                CalculationUtils.ageOn(mwv.measurement.timestamp, user.birthDate)
            },
            gender = user.gender,
            heightCm = user.heightCm,
        )

        val rows = ReportRowBuilder.build(values, client)
        ReportModel(
            coach = loadCoachBlock(),
            client = client,
            measuredAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(mwv.measurement.timestamp), ZoneId.systemDefault()
            ),
            deviceName = "Omron HBF-702T",
            rows = rows,
            summary = ReportSummary.build(rows),
        )
    }

    private suspend fun loadCoachBlock(): CoachBlock = CoachBlock(
        name = settingsFacade.coachName(),
        title = settingsFacade.coachTitle(),
        club = settingsFacade.coachClub(),
        phone = settingsFacade.coachPhone(),
        email = settingsFacade.coachEmail(),
    )

    /**
     * Renders the report and returns the PDF bytes plus the suggested file name.
     * Artwork (logo/footer) is loaded by the caller from [ReportArtwork.load].
     */
    suspend fun renderPdf(
        userId: Int,
        measurementId: Int,
        artwork: Map<String, ByteArray> = emptyMap(),
    ): Result<Pair<ByteArray, String>> = runCatching {
        val model = buildModel(userId, measurementId).getOrThrow()
        withContext(Dispatchers.IO) {
            PdfReportRenderer.render(model, artwork) to suggestedFileName(model)
        }
    }

    /**
     * Renders the report for [userId]/[measurementId] and writes it to [uri] via SAF.
     * The renderer itself is a thin Android/Skia adapter (see [PdfReportRenderer.render])
     * and is not covered by a JVM test for that reason.
     */
    suspend fun exportPdf(
        userId: Int,
        measurementId: Int,
        uri: Uri,
        resolver: ContentResolver,
        artwork: Map<String, ByteArray> = emptyMap(),
    ): Result<Unit> = runCatching {
        val (bytes, _) = renderPdf(userId, measurementId, artwork).getOrThrow()
        withContext(Dispatchers.IO) {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Cannot open OutputStream for uri=$uri")
        }
    }

    companion object {
        private val FILE_DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.US)

        /** Client name and date only — never the app name or package id. */
        fun suggestedFileName(model: ReportModel): String {
            val safeName = model.client.name.filterNot { it in "/\\:*?\"<>|" }.trim()
            return "$safeName - ${model.measuredAt.format(FILE_DATE_FMT)}.pdf"
        }
    }
}
