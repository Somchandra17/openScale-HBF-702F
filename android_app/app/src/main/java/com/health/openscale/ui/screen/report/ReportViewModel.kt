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

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.report.ReportModel
import com.health.openscale.core.report.ReportUseCases
import com.health.openscale.ui.screen.history.HistoryRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Placeholder shown for a header field the coach left unset. Printing a blank line would look
 * identical to a field that really is empty; the coach needs to *notice* the gap before handing
 * the sheet to a client, which a silent blank would not achieve.
 */
const val REPORT_HEADER_MISSING_PLACEHOLDER = "— not set —"

/**
 * Decides which of [rows] is selected before the coach has touched anything, and whether the
 * two export buttons should be enabled. Pure and Compose-free — see the UI Testing Policy in
 * the Task 11 brief for why these decisions are extracted rather than left inside ReportContent.
 */
object ReportSelection {

    /**
     * [rows] must already be newest-first, as produced by
     * [com.health.openscale.ui.screen.history.HistoryStateMapper.toRows] — this function does
     * not sort. The first entry is therefore the most recent weigh-in; `null` when the client
     * has no weigh-ins yet (the empty-state case).
     */
    fun defaultMeasurementId(rows: List<HistoryRow>): Int? = rows.firstOrNull()?.measurementId

    /**
     * CSV only needs a weigh-in selected to know *which client's* whole history to export — it
     * doesn't touch the built [ReportModel]/header preview at all.
     */
    fun isCsvExportEnabled(selectedMeasurementId: Int?): Boolean = selectedMeasurementId != null

    /**
     * PDF additionally needs the header preview to have finished loading (`previewLoaded`):
     * unlike CSV, it needs a real, built [ReportModel] — and the suggested filename that comes
     * with it — to act on. Gating this on selection alone (as CSV can) made the button enabled
     * the instant a weigh-in was picked, while [ReportViewModel.onSelectionChanged]'s async
     * `buildModel` call was still in flight — a real, steady-state dead click on first entry to
     * this screen and after every row pick, not a rare race. See the Task 11 fix report.
     */
    fun isPdfExportEnabled(selectedMeasurementId: Int?, previewLoaded: Boolean): Boolean =
        isCsvExportEnabled(selectedMeasurementId) && previewLoaded
}

/**
 * Read-only header fields shown before export, so the coach can spot a missing phone number or
 * an unset club name before printing. Every blank field is replaced with
 * [REPORT_HEADER_MISSING_PLACEHOLDER] rather than shown as an empty line, which would be easy to
 * miss at a glance.
 */
data class ReportHeaderPreview(
    val clientName: String,
    val clientPhone: String,
    val clientEmail: String,
    // Raw, unformatted — mirroring ReportModel.client — so the age-unknown sentinel and any
    // "does this look right" judgement stay the UI's call, not baked into a formatted string
    // here. These three drive every banded row on the sheet, so the coach must be able to spot
    // a missing or wrong one here, before printing. See finding B4.
    val ageYears: Int,
    val gender: GenderType,
    val heightCm: Float,
    val coachName: String,
    val coachTitle: String,
    val coachClub: String,
    val coachPhone: String,
    val coachEmail: String,
    val deviceName: String,
    val measuredAtLabel: String,
)

/**
 * Derives [ReportHeaderPreview] from a built [ReportModel]. Pure and Compose-free.
 */
object ReportPreviewMapper {

    // Locale.US throughout: a comma-decimal locale would print this differently from the printed
    // sheet itself (see com.health.openscale.core.report.ReportRowBuilder, which pins the same way).
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

    fun toPreview(model: ReportModel): ReportHeaderPreview = ReportHeaderPreview(
        clientName = model.client.name.ifBlank { REPORT_HEADER_MISSING_PLACEHOLDER },
        clientPhone = model.client.phone.ifBlank { REPORT_HEADER_MISSING_PLACEHOLDER },
        clientEmail = model.client.email.ifBlank { REPORT_HEADER_MISSING_PLACEHOLDER },
        ageYears = model.client.ageYears,
        gender = model.client.gender,
        heightCm = model.client.heightCm,
        coachName = model.coach.name.ifBlank { REPORT_HEADER_MISSING_PLACEHOLDER },
        coachTitle = model.coach.title.ifBlank { REPORT_HEADER_MISSING_PLACEHOLDER },
        coachClub = model.coach.club.ifBlank { REPORT_HEADER_MISSING_PLACEHOLDER },
        coachPhone = model.coach.phone.ifBlank { REPORT_HEADER_MISSING_PLACEHOLDER },
        coachEmail = model.coach.email.ifBlank { REPORT_HEADER_MISSING_PLACEHOLDER },
        deviceName = model.deviceName,
        measuredAtLabel = model.measuredAt.format(DATE_FORMAT),
    )
}

/**
 * Suggested CSV export filename for a client's *entire* history. Deliberately not scoped to a
 * single weigh-in (unlike the PDF's [ReportUseCases.suggestedFileName]) — the CSV export is a
 * full spreadsheet dump, not a client handout; see the Task 11 report for why the two exports
 * differ in scope. Same *shape* as the PDF's name, though — `"<Client Name> - <date>.csv"` — so
 * the coach's two exports for the same client read as a matched pair rather than one looking
 * like it came from a different app than the other. [exportDate] is the export's own date, not
 * any single measurement's (there is no one weigh-in a whole-history dump belongs to), and
 * defaults to today. The sanitising this had before (whitespace → underscore, a 20-char cap on
 * the name) is unchanged.
 */
object ReportCsvNaming {

    private val EXPORT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)

    fun suggestedFileName(userName: String, exportDate: LocalDate = LocalDate.now()): String {
        val safeName = userName.replace("\\s+".toRegex(), "_").take(20)
        return "$safeName - ${exportDate.format(EXPORT_DATE_FORMAT)}.csv"
    }
}

/**
 * Owns [ReportUseCases]: builds the read-only header preview for whatever weigh-in is currently
 * selected, and drives the PDF export. Everything else the screen needs (the list of users, the
 * selected user's weigh-in rows) already streams from
 * [com.health.openscale.ui.shared.SharedViewModel] — this ViewModel exists only for the parts
 * that need [ReportUseCases], which SharedViewModel does not depend on.
 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val reportUseCases: ReportUseCases,
) : ViewModel() {

    sealed interface PreviewState {
        /** Nothing selected — no user, or the selected user has no weigh-ins yet. */
        data object Empty : PreviewState
        data object Loading : PreviewState
        data class Loaded(
            val preview: ReportHeaderPreview,
            val suggestedFileName: String,
        ) : PreviewState
        data class Failed(val message: String?) : PreviewState
    }

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Empty)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Rebuilds the preview for [userId]/[measurementId]. Pass a `null` [measurementId] (nothing
     * selected, e.g. the client has no weigh-ins) to clear it back to [PreviewState.Empty]
     * instead of calling into [reportUseCases].
     */
    fun onSelectionChanged(userId: Int, measurementId: Int?) {
        loadJob?.cancel()
        if (measurementId == null) {
            _previewState.value = PreviewState.Empty
            return
        }
        _previewState.value = PreviewState.Loading
        loadJob = viewModelScope.launch {
            reportUseCases.buildModel(userId, measurementId)
                .onSuccess { model ->
                    _previewState.value = PreviewState.Loaded(
                        preview = ReportPreviewMapper.toPreview(model),
                        suggestedFileName = ReportUseCases.suggestedFileName(model),
                    )
                }
                .onFailure { _previewState.value = PreviewState.Failed(it.message) }
        }
    }

    /**
     * Renders and writes the PDF for [userId]/[measurementId] to [uri] via SAF. A thin
     * pass-through to [ReportUseCases.exportPdf] — the Android/Skia rendering it drives is not
     * JVM-testable, for the same reason [ReportUseCases.exportPdf] itself isn't (see its doc).
     */
    suspend fun exportPdf(
        userId: Int,
        measurementId: Int,
        uri: Uri,
        resolver: ContentResolver,
    ): Result<Unit> = reportUseCases.exportPdf(userId, measurementId, uri, resolver)
}
