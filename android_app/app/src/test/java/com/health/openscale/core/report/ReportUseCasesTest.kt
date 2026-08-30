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
import com.health.openscale.testutil.Fixtures
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [ReportUseCases.buildModel] over a real in-memory Room DB + real `SettingsFacadeImpl`
 * (Robolectric, no device, no mocks) — matching this codebase's established "real components"
 * convention (see [ReportViewModelStateTest], `CoachProfileViewModelTest`).
 *
 * Covers two review findings:
 *  - D4: the user and its measurement are fetched independently and were never checked to
 *    agree, so a client switch mid-flight could hand back a model with one client's identity
 *    next to another's readings — the worst failure a printed handout can have.
 *  - B4: an untouched profile's seeded birth date (0L, an explicit "not set" sentinel — see
 *    `OpenScaleApp.getDefaultUsers`) must make age/sex-dependent rows fall back to [Band.NONE]
 *    rather than banding against a fabricated age.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportUseCasesTest {

    /** The measurement timestamp every test uses, so a real birth date below is age'd against it. */
    private val measuredAt = Fixtures.ts(2026, 8, 30)

    /** ~34 years before [measuredAt] — a real, adult birth date, as opposed to the 0L sentinel. */
    private val realBirthDate = Fixtures.ts(1992, 1, 1)

    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var useCases: ReportUseCases
    private var bodyFatTypeId: Int = 0
    private var muscleTypeId: Int = 0

    @Before
    fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = RoomTestSupport.inMemory(app)
        repo = RoomTestSupport.repositoryFor(db)
        val settings = RoomTestSupport.settingsFacadeFor(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            File(app.cacheDir, "report-usecases-${System.nanoTime()}.preferences_pb"),
        )
        useCases = ReportUseCases(repo, settings)

        bodyFatTypeId = db.measurementTypeDao().insert(
            MeasurementType(key = MeasurementTypeKey.BODY_FAT, inputType = InputFieldType.FLOAT)
        ).toInt()
        muscleTypeId = db.measurementTypeDao().insert(
            MeasurementType(key = MeasurementTypeKey.MUSCLE, inputType = InputFieldType.FLOAT)
        ).toInt()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertUser(birthDate: Long, name: String = "Client"): Int = runBlocking {
        db.userDao().insert(
            User(
                name = name,
                birthDate = birthDate,
                gender = GenderType.FEMALE,
                heightCm = 162f,
                activityLevel = ActivityLevel.MODERATE,
                useAssistedWeighing = false,
            )
        ).toInt()
    }

    private fun insertMeasurement(userId: Int, bodyFat: Float, muscle: Float): Int = runBlocking {
        val measurementId = db.measurementDao().insert(Measurement(userId = userId, timestamp = measuredAt)).toInt()
        db.measurementValueDao().insert(MeasurementValue(measurementId = measurementId, typeId = bodyFatTypeId, floatValue = bodyFat))
        db.measurementValueDao().insert(MeasurementValue(measurementId = measurementId, typeId = muscleTypeId, floatValue = muscle))
        measurementId
    }

    // --- D4: identity must not be mixed across a client switch -----------------------

    @Test
    fun `buildModel fails when the measurement belongs to a different user than requested`() = runBlocking {
        val clientA = insertUser(birthDate = realBirthDate, name = "Client A")
        val clientB = insertUser(birthDate = realBirthDate, name = "Client B")
        val measurementForA = insertMeasurement(clientA, bodyFat = 30f, muscle = 31f)

        // Simulates the mid-switch composition: selectedUserId already moved to B, but the
        // measurement id passed in still belongs to A.
        val result = useCases.buildModel(userId = clientB, measurementId = measurementForA)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `buildModel succeeds when the measurement belongs to the requested user`() = runBlocking {
        val client = insertUser(birthDate = realBirthDate)
        val measurementId = insertMeasurement(client, bodyFat = 30f, muscle = 31f)

        val result = useCases.buildModel(userId = client, measurementId = measurementId)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().client.name).isEqualTo("Client")
    }

    // --- B4: an unset birth date must not produce a confidently wrong verdict ---------

    @Test
    fun `an unset birth date yields Band NONE for body fat and skeletal muscle, not a guessed verdict`() = runBlocking {
        val client = insertUser(birthDate = 0L) // the seeded "not set" sentinel
        val measurementId = insertMeasurement(client, bodyFat = 30f, muscle = 31f)

        val model = useCases.buildModel(client, measurementId).getOrThrow()

        assertThat(model.client.ageYears).isEqualTo(CLIENT_AGE_UNKNOWN)
        val bodyFatRow = model.rows.single { it.label == "Body fat" }
        val muscleRow = model.rows.single { it.label == "Skeletal muscle" }
        assertThat(bodyFatRow.status).isEqualTo("—")
        assertThat(muscleRow.status).isEqualTo("—")
    }

    @Test
    fun `a completed profile with a real birth date still bands correctly`() = runBlocking {
        // Same readings as the unset-profile case above, but with a real birth date on file:
        // both rows must band, proving the Band.NONE fallback is scoped to the missing-profile
        // case only, not a regression that always un-bands.
        val client = insertUser(birthDate = realBirthDate)
        val measurementId = insertMeasurement(client, bodyFat = 30f, muscle = 31f)

        val model = useCases.buildModel(client, measurementId).getOrThrow()

        assertThat(model.client.ageYears).isNotEqualTo(CLIENT_AGE_UNKNOWN)
        val bodyFatRow = model.rows.single { it.label == "Body fat" }
        val muscleRow = model.rows.single { it.label == "Skeletal muscle" }
        assertThat(bodyFatRow.status).isNotEqualTo("—")
        assertThat(muscleRow.status).isNotEqualTo("—")
    }
}
