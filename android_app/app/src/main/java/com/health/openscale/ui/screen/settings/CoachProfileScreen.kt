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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.ui.shared.SharedViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the report masthead prints: name, title, club and the contact line. Five fields,
 * edited together on one form and saved together — there is no per-field autosave, so
 * an edit only reaches [SettingsFacade] via an explicit [saveTo] call.
 *
 * Deliberately a plain data class (no Compose types) so its transitions — loading from
 * [SettingsFacade], applying a field edit via `copy()`, and deciding what gets persisted
 * on save — are unit-testable without a Compose test rule. This repo has none
 * (`androidx.ui.test.junit4` is `androidTestImplementation`-only, no `androidTest`
 * source set); see the Task 12 report for the tradeoff this implies.
 */
data class CoachProfileUiState(
    val name: String,
    val title: String,
    val club: String,
    val phone: String,
    val email: String,
) {
    /** Persists all five fields to [settingsFacade], overwriting whatever was there before. */
    suspend fun saveTo(settingsFacade: SettingsFacade) {
        settingsFacade.setCoachName(name)
        settingsFacade.setCoachTitle(title)
        settingsFacade.setCoachClub(club)
        settingsFacade.setCoachPhone(phone)
        settingsFacade.setCoachEmail(email)
    }

    companion object {
        /** Loads the current coach profile from [settingsFacade] ([SettingsFacade]'s own defaults apply when unset). */
        suspend fun load(settingsFacade: SettingsFacade): CoachProfileUiState = CoachProfileUiState(
            name = settingsFacade.coachName(),
            title = settingsFacade.coachTitle(),
            club = settingsFacade.coachClub(),
            phone = settingsFacade.coachPhone(),
            email = settingsFacade.coachEmail(),
        )
    }
}

/**
 * Renders [state] and forwards edits/save; decides nothing itself. Every decision (what
 * the defaults are, what gets persisted) lives on [CoachProfileUiState] instead, where
 * it is JVM-testable (see `CoachProfileUiStateTest`).
 */
@Composable
fun CoachProfileContent(
    state: CoachProfileUiState,
    onChange: (CoachProfileUiState) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        CoachProfileField(
            label = "Name",
            value = state.name,
            onValueChange = { onChange(state.copy(name = it)) },
        )
        CoachProfileField(
            label = "Title",
            value = state.title,
            onValueChange = { onChange(state.copy(title = it)) },
        )
        CoachProfileField(
            label = "Club name",
            value = state.club,
            onValueChange = { onChange(state.copy(club = it)) },
        )
        CoachProfileField(
            label = "Phone",
            value = state.phone,
            onValueChange = { onChange(state.copy(phone = it)) },
        )
        CoachProfileField(
            label = "Email",
            value = state.email,
            onValueChange = { onChange(state.copy(email = it)) },
        )

        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Text("Save")
        }
    }
}

@Composable
private fun CoachProfileField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

/**
 * Loads the coach profile once on entry and persists it once on Save. Unlike the rest of
 * [SettingsFacade], the coach profile has no continuous [kotlinx.coroutines.flow.Flow]:
 * it is edited rarely and printed on demand, not observed live elsewhere in the app, so a
 * one-shot load/save round trip is all this screen needs.
 */
@HiltViewModel
class CoachProfileViewModel @Inject constructor(
    private val settingsFacade: SettingsFacade,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoachProfileUiState("", "", "", "", ""))
    val uiState: StateFlow<CoachProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = CoachProfileUiState.load(settingsFacade)
        }
    }

    /** Replaces the exposed state with [newState] — the edited state the user is looking at. */
    fun onChange(newState: CoachProfileUiState) {
        _uiState.value = newState
    }

    /**
     * Persists whatever is CURRENTLY in [uiState] (the live edit), not what was loaded at start.
     * [onSaved] runs only after the write completes, never before — the screen uses it to show a
     * confirmation snackbar and pop back, so it must not fire on a save that has not actually
     * landed. `suspend` (not a plain lambda) so a caller inside this same coroutine, such as a
     * test asserting what was actually persisted, can await it directly. See finding B2.
     */
    fun save(onSaved: suspend () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value.saveTo(settingsFacade)
            onSaved()
        }
    }
}

/**
 * Coach profile editor: name, title, club and contact line printed on the client report
 * masthead. Registered at [com.health.openscale.ui.navigation.Routes.COACH_PROFILE].
 */
@Composable
fun CoachProfileScreen(
    navController: NavController,
    sharedViewModel: SharedViewModel,
    viewModel: CoachProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val title = stringResource(R.string.settings_item_coach_profile)

    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(title)
        sharedViewModel.setTopBarActions(emptyList())
    }

    CoachProfileContent(
        state = state,
        onChange = viewModel::onChange,
        onSave = {
            // Save was previously silent: no snackbar, no navigation, no visible state change
            // at all, and the system back gesture would then discard the edit unconfirmed.
            // These five fields print on every client sheet's masthead, so the coach must be
            // able to tell the save actually landed. See finding B2.
            viewModel.save {
                sharedViewModel.showSnackbar(messageResId = R.string.coach_profile_saved_snackbar)
                navController.popBackStack()
            }
        },
    )
}
