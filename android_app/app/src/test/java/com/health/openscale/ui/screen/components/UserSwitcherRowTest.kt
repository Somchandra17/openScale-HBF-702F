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
package com.health.openscale.ui.screen.components

import com.google.common.truth.Truth.assertThat
import com.health.openscale.testutil.Fixtures
import org.junit.Test

/**
 * Tests for [userSwitcherEntries], the pure selection logic behind [UserSwitcherRow].
 *
 * The Composable itself has no JVM-testable Compose harness in this project (see the UI testing
 * policy in the task brief) so the decision it renders — which of the four people is selected,
 * in what order, under what label — is extracted into this plain-Kotlin function and tested here
 * directly. The Composable is left to draw already-decided state.
 */
class UserSwitcherRowTest {

    private val users = listOf(
        Fixtures.user(id = 1, name = "Asha"),
        Fixtures.user(id = 2, name = "Ravi"),
        Fixtures.user(id = 3, name = "Mira"),
        Fixtures.user(id = 4, name = "Dev"),
    )

    @Test
    fun `marks only the selected user, preserving list order and labels`() {
        val entries = userSwitcherEntries(users, selectedId = 3)

        assertThat(entries.map { it.id }).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(entries.map { it.label }).containsExactly("Asha", "Ravi", "Mira", "Dev").inOrder()
        assertThat(entries.map { it.isSelected }).containsExactly(false, false, true, false).inOrder()
    }

    @Test
    fun `no entry is selected when selectedId matches nobody`() {
        val entries = userSwitcherEntries(users, selectedId = 999)

        assertThat(entries.none { it.isSelected }).isTrue()
    }

    @Test
    fun `empty user list yields no entries`() {
        assertThat(userSwitcherEntries(emptyList(), selectedId = 1)).isEmpty()
    }
}
