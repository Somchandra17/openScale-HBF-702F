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
package com.health.openscale.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Modifier

class RoutesTest {

    @Test
    fun `exactly three tabs`() {
        assertThat(Routes.mainTabs()).containsExactly(Routes.HOME, Routes.HISTORY, Routes.REPORT).inOrder()
    }

    @Test
    fun `deleted routes are gone`() {
        val all = Routes::class.java.declaredFields.mapNotNull { it.name }
        assertThat(all).containsNoneOf(
            "OVERVIEW",
            "GRAPH",
            "TABLE",
            "STATISTICS",
            "INSIGHTS",
            "TABLE_DRILLDOWN",
            "OVERVIEW_DRILLDOWN",
            "MEASUREMENT_TYPES",
            "MEASUREMENT_TYPE_DETAIL",
            "CHART_SETTINGS",
        )
    }

    @Test
    fun `deleted builder functions are gone`() {
        val methodNames = Routes::class.java.declaredMethods.map { it.name }
        assertThat(methodNames).containsNoneOf("tableDrillDown", "overviewDrillDown", "measurementTypeDetail")
    }

    /** Every `const val String` on [Routes] — i.e. every route constant, surviving or not. */
    private fun allRouteConstants(): List<String> =
        Routes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .onEach { it.isAccessible = true }
            .map { it.get(null) as String }

    @Test
    fun `getTitleResourceId handles every surviving route without throwing`() {
        allRouteConstants().forEach { route ->
            // Must not throw; NO_TITLE_RESOURCE_ID is an acceptable answer for sub-pages that set
            // their own top bar title directly (this function only special-cases main routes).
            Routes.getTitleResourceId(route)
        }
    }

    @Test
    fun `getTitleResourceId resolves the three main tabs and settings`() {
        assertThat(Routes.getTitleResourceId(Routes.HOME)).isNotEqualTo(Routes.NO_TITLE_RESOURCE_ID)
        assertThat(Routes.getTitleResourceId(Routes.HISTORY)).isNotEqualTo(Routes.NO_TITLE_RESOURCE_ID)
        assertThat(Routes.getTitleResourceId(Routes.REPORT)).isNotEqualTo(Routes.NO_TITLE_RESOURCE_ID)
        assertThat(Routes.getTitleResourceId(Routes.SETTINGS)).isNotEqualTo(Routes.NO_TITLE_RESOURCE_ID)
    }

    @Test
    fun `getTitleResourceId returns NO_TITLE_RESOURCE_ID for null or unknown route`() {
        assertThat(Routes.getTitleResourceId(null)).isEqualTo(Routes.NO_TITLE_RESOURCE_ID)
        assertThat(Routes.getTitleResourceId("not_a_real_route")).isEqualTo(Routes.NO_TITLE_RESOURCE_ID)
    }

    @Test
    fun `getIconForRoute handles every surviving route and an unknown route without throwing`() {
        allRouteConstants().forEach { route ->
            assertThat(Routes.getIconForRoute(route)).isNotNull()
        }
        assertThat(Routes.getIconForRoute("not_a_real_route")).isNotNull()
    }
}
