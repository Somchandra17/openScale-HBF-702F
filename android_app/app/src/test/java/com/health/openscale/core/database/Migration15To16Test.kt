/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
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
package com.health.openscale.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.getDefaultMeasurementTypes
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises MIGRATION_15_16 end-to-end on the JVM (Robolectric), following the same technique
 * as [MigrationTest]: a hand-built schema-v15 database (see [RoomTestSupport.writeV15Database])
 * is opened through the real migration chain and read back via the production repository —
 * no `MigrationTestHelper`/schema-asset machinery needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration15To16Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.getDatabasePath(AppDatabase.DATABASE_NAME).delete()
    }

    @Test
    fun existingUserSurvivesThePhoneAndEmailAddition() = runBlocking {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        RoomTestSupport.writeV15Database(dbFile)

        val opened = RoomTestSupport.onDisk(context).also { db = it }
        val repo = RoomTestSupport.repositoryFor(opened)

        val users = repo.getAllUsers().first()
        assertThat(users).hasSize(1)
        val user = users.single()
        assertThat(user.name).isEqualTo("Asha Verma")
        assertThat(user.phone).isEmpty()   // defaulted, not null
        assertThat(user.email).isEmpty()
    }

    @Test
    fun bodyAgeMeasurementTypeIsSeededAfterExistingTypes() = runBlocking {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        RoomTestSupport.writeV15Database(dbFile)

        val opened = RoomTestSupport.onDisk(context).also { db = it }
        val repo = RoomTestSupport.repositoryFor(opened)

        val types = repo.getAllMeasurementTypes().first()
        val bodyAgeTypes = types.filter { it.key == MeasurementTypeKey.BODY_AGE }
        assertThat(bodyAgeTypes).hasSize(1)

        // Proves the MAX(displayOrder) + 1 logic in MIGRATION_15_16: the seeded WEIGHT (1) and
        // BMI (12) rows in the hand-built v15 database must both sort before BODY_AGE.
        val bodyAge = bodyAgeTypes.single()
        val preExistingMaxOrder = types.filter { it.key != MeasurementTypeKey.BODY_AGE }
            .maxOf { it.displayOrder }
        assertThat(bodyAge.displayOrder).isGreaterThan(preExistingMaxOrder)

        // BODY_AGE must NOT be marked isDerived — the scale supplies this value directly,
        // it isn't computed from other measurements. isEnabled must be true so it shows up
        // by default. Asserted directly rather than trusting the migration's literals.
        assertThat(bodyAge.isDerived).isFalse()
        assertThat(bodyAge.isEnabled).isTrue()
    }

    @Test
    fun freshInstallSeedsBodyAgeMatchingTheMigration() {
        // The upgrade path (bodyAgeMeasurementTypeIsSeededAfterExistingTypes, above) proves
        // MIGRATION_15_16 seeds BODY_AGE with isDerived=false, isEnabled=true. A fresh install
        // never runs that migration — it seeds MeasurementType rows straight from
        // getDefaultMeasurementTypes(). The two paths must agree, or an upgraded install and a
        // fresh install would silently end up with different flags for the same type: the same
        // divergence class Task 3's migration was written to avoid in the first place.
        val bodyAgeTypes = getDefaultMeasurementTypes().filter { it.key == MeasurementTypeKey.BODY_AGE }
        assertThat(bodyAgeTypes).hasSize(1)

        val bodyAge = bodyAgeTypes.single()
        assertThat(bodyAge.isDerived).isFalse()
        assertThat(bodyAge.isEnabled).isTrue()
    }
}
