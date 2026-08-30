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
import com.health.openscale.testutil.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verifies [CoachProfileViewModel]'s own decisions — what it loads on entry, that an
 * edit callback updates its exposed state, and that Save persists the CURRENT edited
 * state (not whatever was loaded at start). Runs against a real [SettingsFacadeImpl]
 * (isolated DataStore file, Robolectric, no device), matching SettingsViewModelTest /
 * BluetoothViewModelTest.
 *
 * Uses [UnconfinedTestDispatcher] + `runBlocking` (not `runTest`'s virtual-time
 * scheduler): the real [SettingsFacadeImpl] under test does its actual reads/writes on a
 * genuine `Dispatchers.IO` scope, so a virtual clock can't "advance" that work — the
 * `viewModelScope`-launched load/save has to be awaited for real, the same way
 * `SettingsViewModelTest.deleteUser_removesFromDatabase` awaits its async DB effect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoachProfileViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private fun newFacade(): SettingsFacadeImpl {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(context.cacheDir, "coach-profile-vm-${System.nanoTime()}.preferences_pb") },
        )
        return SettingsFacadeImpl(dataStore)
    }

    @Test
    fun `on entry loads the current coach profile from the facade`() = runBlocking {
        val facade = newFacade()
        CoachProfileUiState(
            name = "Reena Chandra", title = "Weight Loss Coach",
            club = "Fit Studio", phone = "98xxxxxxxx", email = "reena@example.com",
        ).saveTo(facade)

        val vm = CoachProfileViewModel(facade)

        withTimeout(5_000) { vm.uiState.first { it.club == "Fit Studio" } }
        assertThat(vm.uiState.value.phone).isEqualTo("98xxxxxxxx")
    }

    @Test
    fun `onChange replaces the exposed state with the edited one`() = runBlocking {
        val vm = CoachProfileViewModel(newFacade())
        withTimeout(5_000) { vm.uiState.first { it.name == "Reena Chandra" } } // wait past the initial load

        vm.onChange(vm.uiState.value.copy(club = "New Club"))

        assertThat(vm.uiState.value.club).isEqualTo("New Club")
    }

    @Test
    fun `save persists the currently edited state, not the originally loaded one`() = runBlocking {
        val facade = newFacade()
        val vm = CoachProfileViewModel(facade)
        withTimeout(5_000) { vm.uiState.first { it.name == "Reena Chandra" } } // wait past the initial load

        vm.onChange(vm.uiState.value.copy(club = "Fit Studio", email = "reena@example.com"))
        vm.save()

        val persisted = withTimeout(5_000) {
            var current = CoachProfileUiState.load(facade)
            while (current.club != "Fit Studio") {
                delay(10)
                current = CoachProfileUiState.load(facade)
            }
            current
        }
        assertThat(persisted.club).isEqualTo("Fit Studio")
        assertThat(persisted.email).isEqualTo("reena@example.com")
    }

    @Test
    fun `save invokes onSaved only after the edited state has actually been persisted`() = runBlocking {
        // Save was previously silent -- no snackbar, no navigation -- so the screen now drives
        // both from this callback. It must never fire before the write lands, or the coach
        // could be told "saved" (and popped back) before the profile printed on the client
        // sheet's masthead is actually on disk. See finding B2.
        val facade = newFacade()
        val vm = CoachProfileViewModel(facade)
        withTimeout(5_000) { vm.uiState.first { it.name == "Reena Chandra" } } // wait past the initial load
        vm.onChange(vm.uiState.value.copy(club = "Fit Studio"))

        val callbackFired = CompletableDeferred<String>()
        vm.save {
            // Read the facade directly (not vm.uiState, which is updated eagerly by onChange
            // regardless of save) to prove the WRITE, not just the in-memory edit, preceded us.
            callbackFired.complete(CoachProfileUiState.load(facade).club)
        }

        val clubSeenInCallback = withTimeout(5_000) { callbackFired.await() }
        assertThat(clubSeenInCallback).isEqualTo("Fit Studio")
    }
}
