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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.openscale.core.data.User

/**
 * One row entry for [UserSwitcherRow]: an already-decided label and selection state.
 *
 * Kept as a plain data class (no Compose types) so the selection logic that produces it —
 * [userSwitcherEntries] — is JVM-testable without a Compose test harness.
 */
data class UserSwitcherEntry(
    val id: Int,
    val label: String,
    val isSelected: Boolean,
)

/**
 * Decides, for each of [users], whether it is the currently selected one.
 *
 * Pure and Compose-free: this is the one decision [UserSwitcherRow] would otherwise have to make
 * itself. List order and labels are passed through unchanged from [users]; if [selectedId]
 * matches no user's id, no entry is marked selected.
 */
fun userSwitcherEntries(users: List<User>, selectedId: Int): List<UserSwitcherEntry> =
    users.map { user ->
        UserSwitcherEntry(id = user.id, label = user.name, isSelected = user.id == selectedId)
    }

/**
 * The four people, always on screen, one tap apart.
 *
 * Deliberately not a dropdown or a drawer: switching client is the single most frequent action
 * in the practice, so it costs one tap and no discovery. This composable only renders the
 * already-decided [userSwitcherEntries] and forwards taps via [onSelect]; it makes no decisions
 * of its own.
 */
@Composable
fun UserSwitcherRow(
    users: List<User>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = userSwitcherEntries(users, selectedId)
    // Four buttons share this row on a 360 dp phone, so every dp of chrome the default
    // ButtonDefaults.ContentPadding (24 dp each side) reserves comes straight out of the label's
    // budget — enough to cut a name down to its first 4-5 glyphs, exactly where two of the four
    // seeded names ("Person 1".."Person 4") differ. A tight, explicit padding plus a smaller
    // type scale buys back that space; this is the control switching client, so its label must
    // stay legible above all else. See finding B1 in the pre-ship review.
    val tightPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEach { entry ->
            val label = @Composable {
                Text(
                    entry.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (entry.isSelected) {
                Button(
                    onClick = { onSelect(entry.id) },
                    modifier = Modifier.weight(1f),
                    contentPadding = tightPadding,
                ) { label() }
            } else {
                OutlinedButton(
                    onClick = { onSelect(entry.id) },
                    modifier = Modifier.weight(1f),
                    contentPadding = tightPadding,
                ) { label() }
            }
        }
    }
}
