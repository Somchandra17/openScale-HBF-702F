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
package com.health.openscale.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.ui.graphics.vector.ImageVector
import com.health.openscale.R
import com.health.openscale.ui.navigation.Routes.NO_TITLE_RESOURCE_ID

object Routes {
    // Main screens (the three tabs, see [mainTabs])
    const val HOME = "home"
    const val HISTORY = "history"
    const val REPORT = "report"
    const val SETTINGS = "settings"

    const val MEASUREMENT_DETAIL = "measurementDetail" // Not a main navigation item, but a route

    // Sub-pages (Settings Subgraph)
    const val GENERAL_SETTINGS = "settings/general"
    const val USER_SETTINGS = "settings/users"
    const val USER_DETAIL = "settings/userDetail"
    const val BLUETOOTH_SETTINGS = "settings/bluetooth"
    const val BLUETOOTH_DETAIL = "settings/bluetoothDetail"
    const val DATA_MANAGEMENT_SETTINGS = "settings/dataManagement"
    const val ABOUT_SETTINGS = "settings/about"
    const val COACH_PROFILE = "settings/coach"

    // Special constant for no title
    const val NO_TITLE_RESOURCE_ID = 0

    /**
     * The app's three main sections, in the order they should be presented (drawer, tabs, etc).
     */
    fun mainTabs(): List<String> = listOf(HOME, HISTORY, REPORT)

    // Routes with parameters
    fun userDetail(userId: Int?) = "$USER_DETAIL?id=${userId ?: -1}"

    fun measurementDetail(measurementId: Int?, userId: Int?): String =
        "$MEASUREMENT_DETAIL?measurementId=${measurementId ?: -1}&userId=$userId"

    /**
     * Gets the string resource ID for the title of a given route.
     * Intended for main navigation items displayed in the TopAppBar or NavigationDrawer.
     *
     * @param route The route string.
     * @return The string resource ID for the title, or [NO_TITLE_RESOURCE_ID] if no title is defined.
     */
    @StringRes
    fun getTitleResourceId(route: String?): Int = when {
        route == null -> NO_TITLE_RESOURCE_ID
        route.startsWith(HOME) -> R.string.route_title_home
        route.startsWith(HISTORY) -> R.string.route_title_history
        route.startsWith(REPORT) -> R.string.route_title_report
        route.startsWith(SETTINGS) -> R.string.route_title_settings
        else -> NO_TITLE_RESOURCE_ID // No specific title for other routes via this function
    }

    fun getIconForRoute(route: String): ImageVector {
        return when (route) {
            HOME -> Icons.Filled.Home
            HISTORY -> Icons.Filled.TableRows
            REPORT -> Icons.Filled.Description
            SETTINGS -> Icons.Filled.Settings
            else -> Icons.Filled.QuestionMark // Default icon for routes not explicitly handled
        }
    }
}
