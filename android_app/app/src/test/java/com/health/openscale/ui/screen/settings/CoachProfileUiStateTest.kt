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
package com.health.openscale.ui.screen.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.facade.SettingsFacadeImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [CoachProfileUiState]'s transitions — the pure, JVM-testable decisions
 * behind the coach profile editor. This repo has no Compose test rule (see the Task 12
 * report), so [CoachProfileContent] itself is not exercised here; what's tested is
 * everything the Composable defers to this state: loading defaults, applying a field
 * edit, and what gets persisted on save.
 *
 * Runs against a REAL [SettingsFacadeImpl] backed by an isolated DataStore file
 * (Robolectric, no device) — the same pattern used by SettingsViewModelTest and
 * BluetoothViewModelTest — rather than a hand-rolled fake, since [SettingsFacade] is a
 * wide interface and this real implementation is already the shared test double for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoachProfileUiStateTest {

    private fun newFacade(): SettingsFacadeImpl {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(context.cacheDir, "coach-profile-${System.nanoTime()}.preferences_pb") },
        )
        return SettingsFacadeImpl(dataStore)
    }

    @Test
    fun `load reads the coach defaults when nothing has been saved yet`() = runBlocking {
        val facade = newFacade()

        val state = CoachProfileUiState.load(facade)

        assertThat(state).isEqualTo(
            CoachProfileUiState(
                name = "Reena Chandra",
                title = "Weight Loss Coach",
                club = "",
                phone = "",
                email = "",
            )
        )
    }

    @Test
    fun `saveTo persists all five fields and load reads them back`() = runBlocking {
        val facade = newFacade()
        val edited = CoachProfileUiState(
            name = "Reena Chandra",
            title = "Certified Nutrition Coach",
            club = "Fit Studio",
            phone = "98xxxxxxxx",
            email = "reena@example.com",
        )

        edited.saveTo(facade)

        assertThat(CoachProfileUiState.load(facade)).isEqualTo(edited)
    }

    @Test
    fun `saveTo overwrites a previously saved profile rather than merging it`() = runBlocking {
        val facade = newFacade()
        CoachProfileUiState(
            name = "Reena Chandra",
            title = "Weight Loss Coach",
            club = "Old Club",
            phone = "111",
            email = "old@example.com",
        ).saveTo(facade)

        val replacement = CoachProfileUiState(
            name = "Reena Chandra",
            title = "Weight Loss Coach",
            club = "New Club",
            phone = "222",
            email = "new@example.com",
        )
        replacement.saveTo(facade)

        assertThat(CoachProfileUiState.load(facade)).isEqualTo(replacement)
    }

    @Test
    fun `editing one field via copy leaves the others untouched`() {
        val original = CoachProfileUiState(
            name = "Reena Chandra",
            title = "Weight Loss Coach",
            club = "",
            phone = "",
            email = "",
        )

        val afterClubEdit = original.copy(club = "Fit Studio")

        assertThat(afterClubEdit.club).isEqualTo("Fit Studio")
        assertThat(afterClubEdit.name).isEqualTo(original.name)
        assertThat(afterClubEdit.title).isEqualTo(original.title)
        assertThat(afterClubEdit.phone).isEqualTo(original.phone)
        assertThat(afterClubEdit.email).isEqualTo(original.email)
    }
}
