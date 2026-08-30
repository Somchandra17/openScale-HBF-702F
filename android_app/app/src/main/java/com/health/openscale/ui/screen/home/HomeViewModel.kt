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
package com.health.openscale.ui.screen.home

import com.health.openscale.core.data.ConnectionStatus
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.User
import com.health.openscale.core.model.MeasurementWithValues
import java.util.Locale

/**
 * Home does not get its own Hilt ViewModel: [com.health.openscale.ui.shared.SharedViewModel]
 * already exposes the reactive state it needs (selected user, users, measurements), and
 * [com.health.openscale.ui.screen.components.rememberBluetoothActionButton] already owns the
 * Bluetooth connect flow. What Home actually needs of its own is the *decision* logic — which
 * of the four fixed clients' measurements yields "the latest reading", and how the raw numbers
 * become the strings on screen. That decision logic lives here, as plain Kotlin with no Compose
 * or Android dependency, so it is JVM-testable without a Compose test harness (see the UI Testing
 * Policy in the task brief — this project has no Compose test infrastructure).
 *
 * [HomeScreen] (in HomeScreen.kt) is the thin wrapper: it collects the flows above and calls
 * into this file; [HomeContent] renders what it is handed and decides nothing itself.
 */
sealed interface HomeUiState {
    /**
     * The selected person's latest reading, already reduced to what Home displays.
     *
     * Note: the task brief's interface for this data class does not include a visceral-fat
     * field, but the brief's own screen description calls for "a compact grid of fat / muscle /
     * BMI / visceral fat" — four cells. [visceralFat] is added here to satisfy that; nothing
     * outside this screen consumes [HomeUiState], so widening it is safe.
     */
    data class Reading(
        val measurementId: Int,
        val measuredAt: Long,
        val weightKg: Float,
        val deltaKg: Float,
        val fatPercent: Float,
        val musclePercent: Float,
        val bmi: Float,
        val visceralFat: Float,
    ) : HomeUiState
}

/**
 * The Home "Edit person" form. Pure so [applyTo] / [from] can be unit-tested without Compose.
 * [birthDate] uses the same 0L "not set" sentinel as [User.birthDate].
 */
data class ClientEditUiState(
    val name: String,
    val phone: String,
    val email: String,
    val birthDate: Long,
    val gender: GenderType,
    val heightCm: String,
) {
    fun isValid(): Boolean {
        val height = heightCm.replace(',', '.').toFloatOrNull() ?: return false
        return name.isNotBlank() && height > 0f
    }

    fun applyTo(user: User): User? {
        if (!isValid()) return null
        val height = heightCm.replace(',', '.').toFloatOrNull() ?: return null
        return user.copy(
            name = name.trim(),
            phone = phone.trim(),
            email = email.trim(),
            birthDate = birthDate,
            gender = gender,
            heightCm = height,
        )
    }

    companion object {
        fun from(user: User) = ClientEditUiState(
            name = user.name,
            phone = user.phone,
            email = user.email,
            birthDate = user.birthDate,
            gender = user.gender,
            heightCm = if (user.heightCm > 0f) {
                String.format(java.util.Locale.US, "%.0f", user.heightCm)
            } else {
                ""
            },
        )
    }
}

/**
 * Derives [HomeUiState.Reading] from a user's measurements. Pure and Compose-free.
 */
object HomeStateMapper {

    /**
     * [measurements] must already be newest-first, as returned by
     * [com.health.openscale.core.facade.MeasurementFacade.getMeasurementsForUser] — this
     * function does not sort. Returns `null` when there are no measurements (the empty-state
     * case), or when the newest measurement carries no weight value at all.
     */
    fun toReading(measurements: List<MeasurementWithValues>): HomeUiState.Reading? {
        val latest = measurements.firstOrNull() ?: return null
        val latestValues = valuesByKey(latest)
        val weightKg = latestValues[MeasurementTypeKey.WEIGHT.name] ?: return null

        val previousWeight = measurements.getOrNull(1)
            ?.let { valuesByKey(it)[MeasurementTypeKey.WEIGHT.name] }

        return HomeUiState.Reading(
            measurementId = latest.measurement.id,
            measuredAt = latest.measurement.timestamp,
            weightKg = weightKg,
            deltaKg = previousWeight?.let { weightKg - it } ?: 0f,
            fatPercent = latestValues[MeasurementTypeKey.BODY_FAT.name] ?: 0f,
            musclePercent = latestValues[MeasurementTypeKey.MUSCLE.name] ?: 0f,
            bmi = latestValues[MeasurementTypeKey.BMI.name] ?: 0f,
            visceralFat = latestValues[MeasurementTypeKey.VISCERAL_FAT.name] ?: 0f,
        )
    }

    /** Same convention [com.health.openscale.core.report.ReportUseCases] uses to build its value map. */
    private fun valuesByKey(mwv: MeasurementWithValues): Map<String, Float> =
        mwv.values.mapNotNull { v -> v.value.floatValue?.let { f -> v.type.key.name to f } }.toMap()
}

/**
 * Display-string formatting for Home. Pure and Compose-free, pinned to [Locale.US] throughout —
 * a comma-decimal locale would print "68,4 kg" (same reasoning as
 * [com.health.openscale.core.report.ReportRowBuilder]).
 */
object HomeDisplay {
    private val L = Locale.US

    fun weightLabel(weightKg: Float): String = String.format(L, "%.1f kg", weightKg)

    /** Explicit "+" for a gain, "-" (from Java's formatting) for a loss, no sign for no change. */
    fun deltaLabel(deltaKg: Float): String =
        if (deltaKg > 0f) String.format(L, "+%.1f kg", deltaKg)
        else String.format(L, "%.1f kg", deltaKg)

    fun percentLabel(value: Float): String = String.format(L, "%.1f%%", value)

    fun bmiLabel(value: Float): String = String.format(L, "%.1f", value)

    fun visceralFatLabel(value: Float): String = String.format(L, "%.1f", value)

    /**
     * Spinner stays up from the first CONNECTING through CONNECTED (the scale is still
     * sending records) until a newer measurement lands, or the handshake ends with
     * nothing new. Tapping Sync before Bluetooth permission is granted must not latch
     * the spinner forever — [advanceSyncWait] only arms once CONNECTING is seen.
     */
    data class SyncWait(
        val awaiting: Boolean = false,
        val baselineTimestamp: Long? = null,
        val seenHandshake: Boolean = false,
    )

    fun isHandshake(status: ConnectionStatus): Boolean =
        status == ConnectionStatus.CONNECTING ||
            status == ConnectionStatus.CONNECTED ||
            status == ConnectionStatus.DISCONNECTING

    fun isBusy(status: ConnectionStatus, wait: SyncWait): Boolean =
        wait.awaiting || isHandshake(status)

    fun advanceSyncWait(
        wait: SyncWait,
        status: ConnectionStatus,
        newestTimestamp: Long?,
    ): SyncWait {
        val handshake = isHandshake(status)
        if (!wait.awaiting && handshake) {
            return SyncWait(awaiting = true, baselineTimestamp = newestTimestamp, seenHandshake = true)
        }
        if (!wait.awaiting) return wait
        if (newestTimestamp != null && newestTimestamp != wait.baselineTimestamp) return SyncWait()
        val seen = wait.seenHandshake || handshake
        if (seen && (status == ConnectionStatus.FAILED ||
                status == ConnectionStatus.DISCONNECTED ||
                status == ConnectionStatus.NONE)
        ) {
            return SyncWait()
        }
        return wait.copy(seenHandshake = seen)
    }

}
