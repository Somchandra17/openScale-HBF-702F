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
package com.health.openscale.core.bluetooth

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.scales.OmronWlcHandler
import com.health.openscale.core.service.ScannedDeviceInfo
import org.junit.Test

class ScaleFactoryTest {

    @Test
    fun `registry holds exactly the omron handler`() {
        val handlers = ScaleFactory.createHandlers()
        assertThat(handlers).hasSize(1)
        assertThat(handlers.single()).isInstanceOf(OmronWlcHandler::class.java)
    }

    @Test
    fun `an hbf 702t advertisement still resolves after the cull`() {
        // BLEsmart_<group=0001><model=000C><mac> — the 702T's advertised local name.
        val device = ScannedDeviceInfo(
            name = "BLEsmart_0001000C1A2B3C4D5E6F",
            address = "1A:2B:3C:4D:5E:6F",
            rssi = 0,
            serviceUuids = emptyList(),
            manufacturerData = null,
        )
        val support = ScaleFactory.createHandlers().single().supportFor(device)
        assertThat(support).isNotNull()
        assertThat(support!!.displayName).isEqualTo("Omron HBF-702T")
    }

    @Test
    fun `a non omron scale is no longer claimed`() {
        val device = ScannedDeviceInfo(
            name = "MI SCALE2",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = 0,
            serviceUuids = emptyList(),
            manufacturerData = null,
        )
        assertThat(ScaleFactory.createHandlers().single().supportFor(device)).isNull()
    }
}
