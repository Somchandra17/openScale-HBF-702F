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

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.User
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [UserDao.insertAll] must be all-or-nothing: fixed-user seeding
 * ([com.health.openscale.OpenScaleApp.seedFixedUsersIfEmpty]) checks the table is empty and, if
 * so, inserts the four fixed users in one call. If a partial failure left 1-3 rows behind, the
 * table would never again read as empty (there is deliberately no add-user path in Settings), so
 * the coach would be stuck permanently short of clients. A single Room transaction is what
 * prevents that: either all rows land, or none do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserDaoTest {

    private lateinit var db: AppDatabase

    private fun user(id: Int, name: String) = User(
        id = id, name = name, birthDate = 0L, gender = GenderType.MALE, heightCm = 170f,
        activityLevel = ActivityLevel.SEDENTARY, useAssistedWeighing = false,
    )

    @Before
    fun setUp() {
        db = RoomTestSupport.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertAll_allSucceed_insertsEveryUser() = runBlocking {
        db.userDao().insertAll(listOf(user(1, "Person 1"), user(2, "Person 2"), user(3, "Person 3"), user(4, "Person 4")))

        assertThat(db.userDao().getAllUsers().first().map { it.name })
            .containsExactly("Person 1", "Person 2", "Person 3", "Person 4")
            .inOrder()
    }

    @Test
    fun insertAll_oneInsertFailsPartway_rollsBackTheWholeBatchInsteadOfLeavingAPartialInsert() = runBlocking {
        // The 3rd and 4th entries collide on the same explicit id, forcing a primary-key
        // constraint violation partway through the batch. Without a transaction, entries 1-2
        // would already be committed by the time this throws.
        val batch = listOf(
            user(1, "Person 1"),
            user(2, "Person 2"),
            user(3, "Person 3"),
            user(3, "Duplicate id collides with Person 3"),
        )

        try {
            db.userDao().insertAll(batch)
            throw AssertionError("expected a primary-key constraint violation")
        } catch (e: SQLiteConstraintException) {
            // expected: the 4th insert conflicts with the 3rd's explicit id
        }

        assertThat(db.userDao().getAllUsers().first()).isEmpty()
    }
}
