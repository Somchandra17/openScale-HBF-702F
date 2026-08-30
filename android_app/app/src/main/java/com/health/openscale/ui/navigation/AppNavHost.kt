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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.health.openscale.ui.screen.history.HistoryScreen
import com.health.openscale.ui.screen.home.HomeScreen
import com.health.openscale.ui.screen.overview.MeasurementDetailScreen
import com.health.openscale.ui.screen.report.ReportScreen
import com.health.openscale.ui.screen.settings.AboutScreen
import com.health.openscale.ui.screen.settings.BluetoothDetailScreen
import com.health.openscale.ui.screen.settings.BluetoothScreen
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import com.health.openscale.ui.screen.settings.CoachProfileScreen
import com.health.openscale.ui.screen.settings.DataManagementSettingsScreen
import com.health.openscale.ui.screen.settings.GeneralSettingsScreen
import com.health.openscale.ui.screen.settings.SettingsScreen
import com.health.openscale.ui.screen.settings.SettingsViewModel
import com.health.openscale.ui.screen.settings.UserDetailScreen
import com.health.openscale.ui.screen.settings.UserSettingsScreen
import com.health.openscale.ui.shared.SharedViewModel

/**
 * Hosts the app's [NavHost] with all screen destinations. Extracted from
 * AppNavigation to keep that file focused on the drawer/scaffold shell.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    innerPadding: PaddingValues,
    sharedViewModel: SharedViewModel,
    settingsViewModel: SettingsViewModel,
    bluetoothViewModel: BluetoothViewModel
) {
    Column(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .padding(innerPadding) // Apply padding from Scaffold.
                .weight(1f)      // NavHost takes the remaining space in the Column.
        ) {
            // Define all composable screens for navigation routes.
            composable(Routes.HOME) {
                HomeScreen(
                    navController = navController,
                    sharedViewModel = sharedViewModel,
                    bluetoothViewModel = bluetoothViewModel
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    navController = navController,
                    sharedViewModel = sharedViewModel
                )
            }
            composable(Routes.REPORT) {
                ReportScreen(
                    navController = navController,
                    sharedViewModel = sharedViewModel
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    navController = navController,
                    sharedViewModel = sharedViewModel,
                    settingsViewModel = settingsViewModel
                )
            }
            composable(Routes.GENERAL_SETTINGS) {
                GeneralSettingsScreen(
                    sharedViewModel = sharedViewModel,
                    settingsViewModel = settingsViewModel
                )
            }
            composable(Routes.USER_SETTINGS) {
                UserSettingsScreen(
                    sharedViewModel = sharedViewModel,
                    onEditUser = { userId ->
                        navController.navigate(Routes.userDetail(userId))
                    }
                )
            }
            composable(
                route = "${Routes.USER_DETAIL}?id={id}", // Argument in route pattern
                arguments = listOf(navArgument("id") {
                    type = NavType.IntType
                    defaultValue = -1 // No user resolves for -1; UserDetailScreen edits existing users only.
                })
            ) { backStackEntry ->
                val userId = backStackEntry.arguments?.getInt("id") ?: -1
                UserDetailScreen(
                    navController = navController,
                    userId = userId,
                    sharedViewModel = sharedViewModel,
                    settingsViewModel = settingsViewModel
                )
            }
            composable(
                route = "${Routes.MEASUREMENT_DETAIL}?measurementId={measurementId}&userId={userId}",
                arguments = listOf(
                    navArgument("measurementId") {
                        type = NavType.IntType
                        defaultValue = -1 // Default if not provided
                    },
                    navArgument("userId") {
                        type = NavType.IntType
                        defaultValue = -1 // Default if not provided, might also fetch from selectedUser if appropriate
                    }
                )
            ) { backStackEntry ->
                val measurementId = backStackEntry.arguments?.getInt("measurementId") ?: -1
                val userId = backStackEntry.arguments?.getInt("userId") ?: -1
                MeasurementDetailScreen(
                    navController = navController,
                    measurementId = measurementId,
                    userId = userId,
                    sharedViewModel = sharedViewModel
                )
            }
            composable(Routes.BLUETOOTH_SETTINGS) {
                BluetoothScreen(
                    navController = navController,
                    sharedViewModel = sharedViewModel,
                    bluetoothViewModel = bluetoothViewModel
                )
            }
            composable(Routes.BLUETOOTH_DETAIL) {
                BluetoothDetailScreen(
                    navController = navController,
                    sharedViewModel = sharedViewModel,
                    bluetoothViewModel = bluetoothViewModel
                )
            }
            composable(Routes.DATA_MANAGEMENT_SETTINGS) {
                DataManagementSettingsScreen(
                    settingsViewModel = settingsViewModel
                )
            }
            composable(Routes.ABOUT_SETTINGS) {
                AboutScreen(
                    navController = navController,
                    sharedViewModel = sharedViewModel
                )
            }
            composable(Routes.COACH_PROFILE) {
                CoachProfileScreen(
                    navController = navController,
                    sharedViewModel = sharedViewModel
                )
            }
        }
        // Box to fill the space behind the system navigation bar, if visible.
        // This prevents UI elements from being drawn under a translucent navigation bar,
        // ensuring consistent background color.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    WindowInsets.navigationBars // Get insets for the system navigation bar.
                        .asPaddingValues()
                        .calculateBottomPadding() // Calculate its height.
                )
                .background(MaterialTheme.colorScheme.surfaceContainer) // Match TopAppBar color or general theme background.
        )
    }
}
