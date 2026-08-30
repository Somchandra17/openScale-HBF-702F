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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.report.ReportUseCases
import com.health.openscale.testutil.MainDispatcherRule
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [ReportViewModel]'s own Empty/Loading/Loaded/Failed state machine — [onSelectionChanged]'s
 * sequencing, not just the pure mappers it calls into (those are covered in
 * `ReportViewModelTest`, plain JVM, no Robolectric).
 *
 * This is exactly where the "Export PDF is enabled while it's still a no-op" defect lived: a
 * test asserting `isPdfExportEnabled(selectedMeasurementId, previewLoaded)` in isolation can't
 * catch a caller that never threads `previewLoaded` through correctly, or a state machine that
 * gets stuck in `Loading` — only exercising the real sequence does. Runs against a real
 * [ReportUseCases] over an in-memory Room DB + real `SettingsFacadeImpl` (Robolectric, no
 * device), matching `CoachProfileViewModelTest` / `SettingsViewModelTest`'s established
 * "real components, no mocks" convention in this codebase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportViewModelStateTest {

    @get:Rule
    val mainRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private lateinit var db: AppDatabase
    private lateinit var vm: ReportViewModel
    private var userId: Int = 0
    private var measurementId: Int = 0

    @Before
    fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = RoomTestSupport.inMemory(app)
        val repo: DatabaseRepository = RoomTestSupport.repositoryFor(db)
        val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = RoomTestSupport.settingsFacadeFor(
            settingsScope, File(app.cacheDir, "report-vm-${System.nanoTime()}.preferences_pb"),
        )

        userId = db.userDao().insert(
            User(
                name = "Asha Verma",
                birthDate = 0L,
                gender = GenderType.FEMALE,
                heightCm = 162f,
                activityLevel = ActivityLevel.MODERATE,
                useAssistedWeighing = false,
            )
        ).toInt()
        val weightTypeId = db.measurementTypeDao().insert(
            MeasurementType(key = MeasurementTypeKey.WEIGHT, inputType = InputFieldType.FLOAT)
        ).toInt()
        measurementId = db.measurementDao().insert(Measurement(userId = userId, timestamp = 1_000L)).toInt()
        db.measurementValueDao().insert(
            MeasurementValue(measurementId = measurementId, typeId = weightTypeId, floatValue = 68.4f)
        )

        vm = ReportViewModel(ReportUseCases(repo, settings))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `starts Empty before any selection is made`() {
        assertThat(vm.previewState.value).isEqualTo(ReportViewModel.PreviewState.Empty)
    }

    @Test
    fun `a null measurementId resets to Empty, cancelling any in-flight load`() = runBlocking {
        vm.onSelectionChanged(userId, measurementId)

        vm.onSelectionChanged(userId, null)

        assertThat(vm.previewState.value).isEqualTo(ReportViewModel.PreviewState.Empty)
    }

    @Test
    fun `resolves to Loaded with the client's name and a suggested file name once buildModel succeeds`() = runBlocking {
        vm.onSelectionChanged(userId, measurementId)

        val loaded = withTimeout(5_000) {
            vm.previewState.first { it is ReportViewModel.PreviewState.Loaded }
                as ReportViewModel.PreviewState.Loaded
        }

        // This is exactly the state ReportContent's isPdfExportEnabled(..., previewLoaded) and
        // the PDF launcher's file name both depend on -- both must be non-null/true only once
        // this state is reached, never before.
        assertThat(loaded.preview.clientName).isEqualTo("Asha Verma")
        assertThat(loaded.suggestedFileName).contains("Asha Verma")
    }

    @Test
    fun `an unknown measurementId resolves to Failed rather than getting stuck in Loading`() = runBlocking {
        vm.onSelectionChanged(userId, measurementId = 999_999)

        val state = withTimeout(5_000) {
            vm.previewState.first { it !is ReportViewModel.PreviewState.Loading }
        }

        assertThat(state).isInstanceOf(ReportViewModel.PreviewState.Failed::class.java)
    }

    @Test
    fun `picking a different weigh-in cancels a still-in-flight load for the previous one`() = runBlocking {
        // Select the real weigh-in, then immediately re-select an unknown one before the first
        // load can resolve. Only the SECOND selection's outcome (Failed) should win -- a stale
        // Loaded from the cancelled first call must never overwrite it.
        vm.onSelectionChanged(userId, measurementId)
        vm.onSelectionChanged(userId, 999_999)

        val state = withTimeout(5_000) {
            vm.previewState.first { it !is ReportViewModel.PreviewState.Loading }
        }

        assertThat(state).isInstanceOf(ReportViewModel.PreviewState.Failed::class.java)
    }
}
