# Omron HBF-702T Coach App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Strip the openScale fork to an Omron HBF-702T-only coaching app with four fixed users, a one-tap user switcher, and a printable black-and-white single-visit PDF report carrying reference ranges.

**Architecture:** Delete ~88 non-Omron device files, leaving one handler and its adapter chain. Add a pure-Kotlin `core/report/` package (thresholds → model → PDF renderer) with no Android or Room dependencies in the classifier and renderer, so both are JVM-unit-testable. Collapse the Compose UI from six screens plus a widget down to three tabs.

**Tech Stack:** Kotlin, Jetpack Compose, Room (v15 → v16), Hilt, `android.graphics.pdf.PdfDocument`, JUnit4 + Truth + Robolectric.

**Spec:** `docs/superpowers/specs/2026-08-30-omron-hbf702t-coach-app-design.md`

## Global Constraints

Every task's requirements implicitly include these. Values are copied verbatim from the spec.

- **Monochrome PDF.** Palette is `#000000` text, `#666666` secondary text, `#E6E6E6` table header fill, `#CCCCCC` rules. No other colour may be emitted. Grey fills stay ≥ 85% luminance.
- **No app branding in the exported PDF.** The bytes must contain no case-insensitive occurrence of `openScale` or `com.health.openscale`. Covers visible content, PDF metadata, and the SAF suggested filename.
- **App display name stays `openScale`.** Package id stays `com.health.openscale`. No rebrand.
- **Vector graphics only.** SVG → Android vector drawable → PDF canvas paths. No bitmaps.
- **BMI cut-offs are Indian / Asian-Pacific**, not WHO: Underweight `< 18.0`, Normal `18.0–22.9`, Overweight `23.0–24.9`, Obese `≥ 25.0`.
- **Machine-reported values only** on the report, for every BIA-derived figure: body
  fat, skeletal muscle, visceral fat, BMR and body age come from the scale and are
  never estimated by the app. **BMI is the one deliberate exception** — it stays
  app-derived (`weight/height²`, the same arithmetic the scale does) so it always
  agrees with the weight printed beside it. See spec §4.2 for the full reasoning.
- **Water % is never shown.** The HBF-702T does not measure it.
- **Only the Remarks box is blank.** Every other field prints filled.
- Working directory for all Gradle commands is `android_app/`.
- **`JAVA_HOME` must point at the JetBrains Runtime.** This machine has AMFI disabled
  system-wide, which breaks W^X/JIT memory, and every Eclipse/OpenJDK HotSpot build
  aborts with SIGBUS on startup. Android Studio's bundled arm64 JBR 21 tolerates it
  and is the only native JVM that works:

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.<Class>"
  ```

  It is exported from `~/.zshenv`, so it is normally already set even in
  non-interactive shells — verify with `echo $JAVA_HOME` before assuming, and set it
  explicitly if empty. A bare `./gradlew` with `JAVA_HOME` unset still resolves
  `/usr/bin/java` (arm64 Temurin 25) and still crashes.

  Fallback if an Android Studio update moves that path:
  `/Users/somchandra/jdks/jdk-21.0.12.1+1/Contents/Home` (x86_64 Temurin 21 under
  Rosetta — works, but roughly 3.5× slower).

  Prefer `--tests` filters over full-suite runs while iterating; run the full suite
  only where a task step explicitly calls for it.
- **Baseline before any change: 632 tests, 0 failures, 0 ignored** (`BUILD SUCCESSFUL`,
  commit `d6efff8e`). Any failure beyond that count was introduced by this work.
  Task 1 added 18, so from commit `50a10ea1` the expected total is 650.

## UI Testing Policy — supersedes the test code in Tasks 8–12

**The `createComposeRule()` tests written into Tasks 8, 9, 10, 11 and 12 are void.**
They cannot compile. This repo has no Compose tests at all: `androidx.ui.test.junit4`
is declared `androidTestImplementation` only (instrumented, device-required), there is
no `androidTest` source directory, and the JVM unit-test classpath has no Compose test
artifact. Running them as unit tests would need `testImplementation` of the Compose
test libraries plus `unitTests.isIncludeAndroidResources = true` — a build-wide change
rejected in Task 3 for good reasons, to buy assertions that mostly prove Compose
renders what it was told to.

Apply the same solution that worked for the PDF renderer in Task 6: **put the
decisions where a test can reach them, and keep the untestable layer thin.**

For every UI task:

1. **Extract each decision into a pure, JVM-testable unit** — a top-level function or
   a ViewModel-held state mapper, in plain Kotlin, with no Compose imports. These are
   the assertions worth having:
   - History orders weigh-ins newest-first
   - Report defaults to the most recent weigh-in
   - Export is disabled when nothing is selected
   - Home renders an empty state when a person has no readings
   - The switcher reports the id of the person tapped
   - Any display string built from model data (deltas, dates, units)
2. **Test those units directly**, in `src/test/`, with JUnit + Truth. No Compose rule,
   no Robolectric unless the unit genuinely needs Android types.
3. **Keep the `@Composable` thin**: it receives already-decided state and forwards
   callbacks. It contains no ordering, no defaulting, no enable/disable rules, no
   formatting. If a Composable has to decide something, that decision belongs in
   step 1 instead.
4. **Do not** add Compose test dependencies, create an `androidTest` source set, or
   modify `build.gradle.kts`. If a task seems to require it, stop and escalate.

What this deliberately gives up: no automated proof that a Composable draws the state
it was handed. That is verified on-device at sign-off, alongside the PDF's visual
check — the two things a JVM test could never have told us anyway.

---

## File Structure

**Created:**
| File | Responsibility |
|---|---|
| `core/report/ReferenceRanges.kt` | Age/sex-aware banding thresholds. Pure Kotlin, no Android imports. |
| `core/report/ReportModel.kt` | Plain data carried from DB to renderer. No framework types. |
| `core/report/PdfReportRenderer.kt` | Draws a `ReportModel` onto a `PdfDocument`. No Room, no Compose. |
| `core/report/ReportUseCases.kt` | Assembles `ReportModel` from the DB; writes via SAF. |
| `ui/screen/components/UserSwitcherRow.kt` | The always-visible four-button switcher. |
| `ui/screen/home/HomeScreen.kt` | Switcher + latest reading + Sync button. |
| `ui/screen/history/HistoryScreen.kt` | Past weigh-ins for the selected person. |
| `ui/screen/report/ReportScreen.kt` | Pick a weigh-in, export PDF or CSV. |
| `ui/screen/settings/CoachProfileScreen.kt` | Edits the five masthead fields. |

The coach profile is five strings, so it lives in `SettingsFacade`'s DataStore rather than earning its own Room entity.

**Modified:** `core/data/Enums.kt`, `core/data/User.kt`, `core/database/AppDatabase.kt`, `OpenScaleApp.kt`, `core/bluetooth/data/ScaleMeasurement.kt`, `core/bluetooth/scales/OmronWlcHandler.kt`, `core/bluetooth/ScaleFactory.kt`, `core/bluetooth/scales/ScaleDeviceHandler.kt`, `core/service/BleConnector.kt`, `core/service/DerivedValuesCalculator.kt`, `core/bluetooth/libs/OmronLib.kt`, `core/facade/SettingsFacade.kt`, `ui/navigation/Routes.kt`, `ui/navigation/AppNavHost.kt`, `ui/screen/settings/SettingsScreen.kt`, `ui/screen/settings/UserSettingsScreen.kt`, `testutil/RoomTestSupport.kt`.

**Deleted:** ~88 device files (Task 2), plus `GraphScreen`, `StatisticsScreen`, `InsightsScreen`, `OverviewScreen`, `TableScreen`, the drill-down screens, `MeasurementWidget*`, and four settings screens (Task 13).

---

### Task 1: Reference ranges

Pure Kotlin, zero dependencies, highest test value in the plan — these numbers are printed on sheets handed to clients. Do this first; nothing else depends on it compiling.

**Files:**
- Create: `android_app/app/src/main/java/com/health/openscale/core/report/ReferenceRanges.kt`
- Test: `android_app/app/src/test/java/com/health/openscale/core/report/ReferenceRangesTest.kt`

**Interfaces:**
- Consumes: `MeasurementTypeKey` and `GenderType` from `com.health.openscale.core.data`.
- Produces: `Band` enum; `Classification(band, label, normalRange)`; `ReferenceRanges.classify(key: MeasurementTypeKey, value: Float, ageYears: Int, gender: GenderType): Classification`. Task 5 calls exactly this signature.

- [ ] **Step 1: Write the failing test**

Create `ReferenceRangesTest.kt`:

```kotlin
package com.health.openscale.core.report

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementTypeKey
import org.junit.Test

class ReferenceRangesTest {

    // --- BMI: Indian / Asian-Pacific cut-offs ---------------------------------

    @Test
    fun `bmi 22_9 is normal for asian pacific cutoffs`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BMI, 22.9f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NORMAL)
        assertThat(c.normalRange).isEqualTo("18.0 – 22.9")
    }

    @Test
    fun `bmi 23_0 is overweight not normal`() {
        // The whole point of the Asian-Pacific table: WHO would call this normal.
        val c = ReferenceRanges.classify(MeasurementTypeKey.BMI, 23.0f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.HIGH)
        assertThat(c.label).isEqualTo("Overweight")
    }

    @Test
    fun `bmi 25_0 is obese`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BMI, 25.0f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.VERY_HIGH)
        assertThat(c.label).isEqualTo("Obese")
    }

    @Test
    fun `bmi 17_9 is underweight`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BMI, 17.9f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.LOW)
        assertThat(c.label).isEqualTo("Underweight")
    }

    // --- Body fat: sex- and age-dependent -------------------------------------

    @Test
    fun `body fat 32_9 is normal for a 34 year old woman`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 32.9f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NORMAL)
        assertThat(c.normalRange).isEqualTo("21.0 – 32.9 %")
    }

    @Test
    fun `body fat 33_0 is high for a 34 year old woman`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 33.0f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `body fat 33_0 is very high for a 34 year old man`() {
        // Same reading, different sex, three bands apart.
        val c = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 33.0f, 34, GenderType.MALE)
        assertThat(c.band).isEqualTo(Band.VERY_HIGH)
    }

    @Test
    fun `body fat 33_5 is normal for a 45 year old woman but high at 34`() {
        val younger = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 33.5f, 34, GenderType.FEMALE)
        val older = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 33.5f, 45, GenderType.FEMALE)
        assertThat(younger.band).isEqualTo(Band.HIGH)
        assertThat(older.band).isEqualTo(Band.NORMAL)
    }

    // --- Skeletal muscle ------------------------------------------------------

    @Test
    fun `skeletal muscle 31_0 is high for a 34 year old woman`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, 31.0f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.HIGH)
        assertThat(c.normalRange).isEqualTo("24.3 – 30.3 %")
    }

    @Test
    fun `skeletal muscle 30_3 is the top of normal for a 34 year old woman`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.MUSCLE, 30.3f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NORMAL)
    }

    // --- Visceral fat: half steps, sex/age independent ------------------------

    @Test
    fun `visceral fat 9_5 is normal`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.VISCERAL_FAT, 9.5f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NORMAL)
        assertThat(c.normalRange).isEqualTo("0.5 – 9.5")
    }

    @Test
    fun `visceral fat 10_0 is high`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.VISCERAL_FAT, 10.0f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.HIGH)
    }

    @Test
    fun `visceral fat 15_0 is very high`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.VISCERAL_FAT, 15.0f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.VERY_HIGH)
    }

    // --- Unbanded metrics -----------------------------------------------------

    @Test
    fun `weight has no band`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.WEIGHT, 68.4f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NONE)
        assertThat(c.label).isEqualTo("—")
        assertThat(c.normalRange).isEqualTo("—")
    }

    @Test
    fun `bmr has no band`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BMR, 1420f, 34, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NONE)
    }

    // --- Age guards -----------------------------------------------------------

    @Test
    fun `under 18 gets no band for age dependent metrics`() {
        // Adult thresholds are wrong for children; refuse rather than mislead.
        val c = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 25f, 16, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NONE)
    }

    @Test
    fun `age 19 clamps to the youngest adult bracket`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 32.9f, 19, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NORMAL)
    }

    @Test
    fun `age above 79 clamps to the oldest bracket`() {
        val c = ReferenceRanges.classify(MeasurementTypeKey.BODY_FAT, 35.9f, 85, GenderType.FEMALE)
        assertThat(c.band).isEqualTo(Band.NORMAL)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.core.report.ReferenceRangesTest"`
Expected: FAIL — `Unresolved reference: ReferenceRanges`.

- [ ] **Step 3: Write the implementation**

Create `ReferenceRanges.kt`:

```kotlin
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
package com.health.openscale.core.report

import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementTypeKey

/**
 * Classification bands printed in the report's Status column.
 *
 * [NONE] means the metric has no meaningful banding (weight, BMR) or cannot be banded
 * for this client (missing age or sex, or a minor). It prints an em dash rather than
 * an invented verdict.
 */
enum class Band { LOW, NORMAL, HIGH, VERY_HIGH, NONE }

/** One row's worth of interpretation. [normalRange] is pre-formatted for printing. */
data class Classification(
    val band: Band,
    val label: String,
    val normalRange: String,
)

/**
 * Age- and sex-aware reference ranges for the metrics an Omron HBF-702T reports.
 *
 * ## Every threshold in this app lives in this file, on purpose.
 *
 * These numbers decide whether a client is told they are "Normal" or "Obese" on a
 * printed sheet. They are transcribed from published Omron (Gallagher et al. 2000)
 * and Asian-Pacific BMI references and **must be verified against the manual supplied
 * with the scale** before use in the practice. Keeping them in one file makes a
 * correction a one-line edit rather than an archaeology exercise.
 *
 * This is a fitness classification, not a medical diagnosis.
 */
object ReferenceRanges {

    private const val DASH = "—"
    private val UNBANDED = Classification(Band.NONE, DASH, DASH)

    /** Adult thresholds do not apply to children; below this we refuse to band. */
    private const val MIN_ADULT_AGE = 18

    /** Upper and lower bounds of a band, plus the label printed for it. */
    private data class Cut(val normalLow: Float, val normalHigh: Float, val highHigh: Float)

    fun classify(
        key: MeasurementTypeKey,
        value: Float,
        ageYears: Int,
        gender: GenderType,
    ): Classification = when (key) {
        MeasurementTypeKey.BMI -> classifyBmi(value)
        MeasurementTypeKey.VISCERAL_FAT -> classifyVisceralFat(value)
        MeasurementTypeKey.BODY_FAT -> classifyAgeSex(value, ageYears, gender, bodyFatCut(ageYears, gender), "%")
        MeasurementTypeKey.MUSCLE -> classifyAgeSex(value, ageYears, gender, muscleCut(ageYears, gender), "%")
        else -> UNBANDED
    }

    // -- BMI: Indian / Asian-Pacific, sex- and age-independent ---------------------

    private fun classifyBmi(value: Float): Classification {
        val band = when {
            value < 18.0f -> Band.LOW
            value < 23.0f -> Band.NORMAL
            value < 25.0f -> Band.HIGH
            else -> Band.VERY_HIGH
        }
        val label = when (band) {
            Band.LOW -> "Underweight"
            Band.NORMAL -> "Normal"
            Band.HIGH -> "Overweight"
            else -> "Obese"
        }
        return Classification(band, label, "18.0 – 22.9")
    }

    // -- Visceral fat: half steps on the 702T, sex- and age-independent ------------

    private fun classifyVisceralFat(value: Float): Classification {
        val band = when {
            value < 10.0f -> Band.NORMAL
            value < 15.0f -> Band.HIGH
            else -> Band.VERY_HIGH
        }
        return Classification(band, bandLabel(band), "0.5 – 9.5")
    }

    // -- Body fat % (Omron / Gallagher et al. 2000) --------------------------------

    private fun bodyFatCut(ageYears: Int, gender: GenderType): Cut? {
        if (ageYears < MIN_ADULT_AGE) return null
        return if (gender == GenderType.FEMALE) {
            when {
                ageYears < 40 -> Cut(21.0f, 32.9f, 38.9f)
                ageYears < 60 -> Cut(23.0f, 33.9f, 39.9f)
                else -> Cut(24.0f, 35.9f, 41.9f)
            }
        } else {
            when {
                ageYears < 40 -> Cut(8.0f, 19.9f, 24.9f)
                ageYears < 60 -> Cut(11.0f, 21.9f, 27.9f)
                else -> Cut(13.0f, 24.9f, 29.9f)
            }
        }
    }

    // -- Skeletal muscle % (Omron) -------------------------------------------------

    private fun muscleCut(ageYears: Int, gender: GenderType): Cut? {
        if (ageYears < MIN_ADULT_AGE) return null
        return if (gender == GenderType.FEMALE) {
            when {
                ageYears < 40 -> Cut(24.3f, 30.3f, 35.3f)
                ageYears < 60 -> Cut(24.1f, 30.1f, 35.1f)
                else -> Cut(23.9f, 29.9f, 34.9f)
            }
        } else {
            when {
                ageYears < 40 -> Cut(33.3f, 39.3f, 44.0f)
                ageYears < 60 -> Cut(33.1f, 39.1f, 43.8f)
                else -> Cut(32.9f, 38.9f, 43.6f)
            }
        }
    }

    private fun classifyAgeSex(
        value: Float,
        ageYears: Int,
        gender: GenderType,
        cut: Cut?,
        unit: String,
    ): Classification {
        if (cut == null) return UNBANDED
        val band = when {
            value < cut.normalLow -> Band.LOW
            value <= cut.normalHigh -> Band.NORMAL
            value <= cut.highHigh -> Band.HIGH
            else -> Band.VERY_HIGH
        }
        val range = "${fmt(cut.normalLow)} – ${fmt(cut.normalHigh)} $unit"
        return Classification(band, bandLabel(band), range)
    }

    private fun bandLabel(band: Band): String = when (band) {
        Band.LOW -> "Low"
        Band.NORMAL -> "Normal"
        Band.HIGH -> "High"
        Band.VERY_HIGH -> "Very high"
        Band.NONE -> DASH
    }

    // Locale.US, not the default: a comma-decimal locale would print "21,0 – 32,9 %".
    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)
}
```

Note the age clamping falls out of the `when` chains: `ageYears < 40` catches 18 and 19, and the `else` branch catches everything above 79.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.core.report.ReferenceRangesTest"`
Expected: PASS, 18 tests.

- [ ] **Step 5: Commit**

```bash
git add android_app/app/src/main/java/com/health/openscale/core/report/ReferenceRanges.kt \
        android_app/app/src/test/java/com/health/openscale/core/report/ReferenceRangesTest.kt
git commit -m "feat(report): add age and sex aware reference ranges"
```

---

### Task 2: Cull the device layer

Mechanical but large. Doing it now shrinks the compile surface for every task that follows.

**Files:**
- Delete: ~67 handlers, 3 adapters, 20 libs, and their tests (enumerated below)
- Modify: `core/bluetooth/ScaleFactory.kt`, `core/bluetooth/scales/ScaleDeviceHandler.kt`, `core/bluetooth/libs/OmronLib.kt`, `core/bluetooth/scales/OmronWlcHandler.kt`
- Test: `android_app/app/src/test/java/com/health/openscale/core/bluetooth/ScaleFactoryTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `ScaleFactory.createHandlers(): List<ScaleDeviceHandler>` returning exactly one element. `LinkMode` reduced to a single constant `CONNECT_GATT`.

**Keep these four files** — `GattScaleAdapter` extends the abstract `ModernScaleAdapter`, so that base class survives:
- `scales/ScaleDeviceHandler.kt`
- `scales/ModernScaleAdapter.kt`
- `scales/GattScaleAdapter.kt`
- `scales/OmronWlcHandler.kt`
- `libs/OmronLib.kt`

- [ ] **Step 1: Write the failing test**

Replace the body of `ScaleFactoryTest.kt` with:

```kotlin
package com.health.openscale.core.bluetooth

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.scales.OmronWlcHandler
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
        )
        val support = ScaleFactory.createHandlers().single().supportFor(device)
        assertThat(support).isNotNull()
        assertThat(support!!.displayName).isEqualTo("Omron HBF-702T")
    }

    @Test
    fun `a non omron scale is no longer claimed`() {
        val device = ScannedDeviceInfo(name = "MI SCALE2", address = "AA:BB:CC:DD:EE:FF")
        assertThat(ScaleFactory.createHandlers().single().supportFor(device)).isNull()
    }
}
```

If `ScannedDeviceInfo` needs more constructor arguments, read its definition at `core/bluetooth/data/` and supply defaults — do not change the data class to suit the test.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.core.bluetooth.ScaleFactoryTest"`
Expected: FAIL — the registry still holds ~60 handlers, so `hasSize(1)` fails.

- [ ] **Step 3: Delete the driver files**

```bash
cd android_app/app/src/main/java/com/health/openscale/core/bluetooth

# Every handler except the four we keep.
find scales -name '*.kt' \
  ! -name 'ScaleDeviceHandler.kt' \
  ! -name 'ModernScaleAdapter.kt' \
  ! -name 'GattScaleAdapter.kt' \
  ! -name 'OmronWlcHandler.kt' \
  -delete

# Every protocol lib except Omron's.
find libs -name '*.kt' ! -name 'OmronLib.kt' -delete

cd ../../../../../../test/java/com/health/openscale/core/bluetooth
find . -name '*Test.kt' \
  ! -name 'OmronLibTest.kt' \
  ! -name 'OmronWlcHandlerTest.kt' \
  ! -name 'ScaleFactoryTest.kt' \
  -delete
```

- [ ] **Step 4: Collapse the factory**

In `core/bluetooth/ScaleFactory.kt`, replace the whole `createHandlers()` body:

```kotlin
        @VisibleForTesting
        internal fun createHandlers(): List<ScaleDeviceHandler> = listOf(
            OmronWlcHandler(),
        )
```

Delete every now-unused handler import. In the same file, reduce `createModernCommunicator`'s `when` to its single surviving branch:

```kotlin
        return when (support.linkMode) {
            LinkMode.CONNECT_GATT ->
                GattScaleAdapter(
                    applicationContext,
                    settingsFacade,
                    measurementFacade,
                    userFacade,
                    handler,
                    effectiveTuning
                )
        }
```

Remove the `DebugGattHandler` early-return near the top of `createCommunicator` along with its import.

- [ ] **Step 5: Reduce LinkMode and BroadcastAction**

In `core/bluetooth/scales/ScaleDeviceHandler.kt:85`, replace:

```kotlin
enum class LinkMode { CONNECT_GATT, BROADCAST_ONLY, CLASSIC_SPP }
```

with:

```kotlin
/** Only one link mode survives: the Omron HBF-702T is read over a GATT connection. */
enum class LinkMode { CONNECT_GATT }
```

Delete the `BroadcastAction` enum (lines ~87-93) and the `onBroadcast` handler hook that returns it, since `BroadcastScaleAdapter` is gone. Follow the compiler.

- [ ] **Step 6: Narrow the Omron model table**

In `core/bluetooth/scales/OmronWlcHandler.kt`, reduce `MODELS_BY_ADVERTISED_ID` to the three ids that use the 702T profile:

```kotlin
        private val MODELS_BY_ADVERTISED_ID: Map<Int, KnownModel> = mapOf(
            0x0001_000C to KnownModel("Omron HBF-702T", OmronBodyCompositionLib.PROFILE_HBF_702T),
            0x0001_040C to KnownModel("Omron HBF-702T", OmronBodyCompositionLib.PROFILE_HBF_702T),
            0x0001_0011 to KnownModel("Omron KRD-703T", OmronBodyCompositionLib.PROFILE_HBF_702T),
        )
```

In `core/bluetooth/libs/OmronLib.kt`, delete `PROFILE_HBF_32` and `PROFILE_HBF_32_NO_BODY_AGE`, now unreferenced. Delete the corresponding cases in `OmronLibTest.kt` if any assert on them.

**This narrowing also breaks `OmronWlcHandlerTest.kt`**, which asserts recognition of HBF-227T/228T/230T/BCM-500/VIVA — models this fork deliberately drops. Delete those specific assertions. They test a capability the spec removes, so keeping them would mean keeping dead model tables to satisfy a test for a scale the coach does not own.

What must survive in that file, untouched and passing: everything proving the **HBF-702T/KRD-703T** path — advertised-id recognition for `0x0001_000C`, `0x0001_040C`, `0x0001_0011`, the WLC unlock/session handshake, EEPROM record readout, slot binding, and record decoding. That is the real invariant. If a change would weaken any of those, stop and escalate instead.

- [ ] **Step 7: Compile and run the full suite**

Run: `cd android_app && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If unresolved references remain, they are references to deleted handlers — delete the reference, never resurrect the handler.

Run: `cd android_app && ./gradlew :app:testDebugUnitTest`
Expected: PASS. `OmronLibTest` and `OmronWlcHandlerTest` must pass **untouched** — they are the proof the scale protocol still works.

Expect fallout outside `core/bluetooth`: `test/.../ui/screen/settings/BluetoothViewModelTest.kt` and `test/.../ui/shared/SharedViewModelTest.kt` may reference culled handlers. Fix those references; do not resurrect a handler to satisfy a test.

- [ ] **Step 8: Commit**

```bash
git add -A android_app/app/src
git commit -m "refactor(bluetooth): strip device support to Omron HBF-702T only"
```

---

### Task 3: Body age type and user contact fields

One Room migration carries both schema changes, so there is one version bump rather than two.

**Files:**
- Modify: `core/data/Enums.kt`, `core/data/User.kt`, `core/database/AppDatabase.kt`, `OpenScaleApp.kt`, `res/values/strings.xml`, `testutil/RoomTestSupport.kt`
- Test: `android_app/app/src/test/java/com/health/openscale/core/database/Migration15To16Test.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `MeasurementTypeKey.BODY_AGE` (id 35); `User.phone: String`; `User.email: String`; `MIGRATION_15_16`. Tasks 4, 5 and 8 depend on all four.

- [ ] **Step 1: Write the failing test**

Create `Migration15To16Test.kt`:

```kotlin
package com.health.openscale.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration15To16Test {

    private val dbName = "migration-test-15-16"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `existing users survive the phone and email addition`() {
        helper.createDatabase(dbName, 15).apply {
            execSQL(
                "INSERT INTO User (id, name, icon, birthDate, gender, heightCm, " +
                "activityLevel, useAssistedWeighing, amputations) " +
                "VALUES (1, 'Asha Verma', 'IC_DEFAULT', 0, 'FEMALE', 162.0, 'MODERATE', 0, '{}')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 16, true, MIGRATION_15_16)

        db.query("SELECT name, phone, email FROM User WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Asha Verma")
            assertThat(c.getString(1)).isEmpty()   // defaulted, not null
            assertThat(c.getString(2)).isEmpty()
        }
    }

    @Test
    fun `body age measurement type is seeded`() {
        helper.createDatabase(dbName, 15).close()
        val db = helper.runMigrationsAndValidate(dbName, 16, true, MIGRATION_15_16)

        db.query("SELECT COUNT(*) FROM MeasurementType WHERE `key` = 'BODY_AGE'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
    }
}
```

If the v15 `User` column list differs from the INSERT above, read `app/schemas/com.health.openscale.core.database.AppDatabase/15.json` and match it exactly.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.core.database.Migration15To16Test"`
Expected: FAIL — `Unresolved reference: MIGRATION_15_16`.

- [ ] **Step 3: Add the enum constant and string**

In `core/data/Enums.kt`, after `BCM(34, ...)` and before `CUSTOM(99, ...)`:

```kotlin
    BODY_AGE(35, R.string.measurement_type_body_age, listOf(UnitType.NONE), listOf(InputFieldType.FLOAT)),
```

In `res/values/strings.xml`, beside the other `measurement_type_*` entries:

```xml
    <string name="measurement_type_body_age">Body age</string>
```

- [ ] **Step 4: Add the User fields**

In `core/data/User.kt`:

```kotlin
@Entity
data class User(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val icon: UserIcon = UserIcon.IC_DEFAULT,
    val birthDate: Long,
    val gender: GenderType,
    val heightCm: Float,
    val activityLevel: ActivityLevel,
    val useAssistedWeighing: Boolean,
    val amputations: Map<Limb, AmputationPart> = emptyMap(),
    // Printed on the report header; never used for anything else.
    val phone: String = "",
    val email: String = "",
)
```

- [ ] **Step 5: Add the default measurement type**

In `OpenScaleApp.kt`, in `getDefaultMeasurementTypes()`, after the `BCM` line. Not `isDerived` — the scale supplies this value:

```kotlin
        MeasurementType(key = MeasurementTypeKey.BODY_AGE, unit = UnitType.NONE, color = 0xFF795548.toInt(), icon = MeasurementTypeIcon.IC_DEFAULT, isEnabled = true),
```

- [ ] **Step 6: Write the migration**

In `core/database/AppDatabase.kt`, bump `version = 15` to `version = 16`, append `MIGRATION_15_16` to the `.addMigrations(...)` call, and add — following the `MIGRATION_14_15` pattern already in the file:

```kotlin
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Client contact details for the printed report header. Non-null with an
        // empty default so existing rows migrate without a backfill pass.
        db.execSQL("ALTER TABLE User ADD COLUMN phone TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE User ADD COLUMN email TEXT NOT NULL DEFAULT ''")

        // BODY_AGE is reported by the HBF-702T but had no type until now. Flags come
        // from getDefaultMeasurementTypes() so upgraded installs match fresh ones.
        val bodyAge = getDefaultMeasurementTypes()
            .first { it.key == MeasurementTypeKey.BODY_AGE }

        // displayOrder is NOT NULL with no default — append after the current maximum
        // so the new type lands at the end of the user's existing ordering.
        val nextOrder = db.query("SELECT IFNULL(MAX(displayOrder), -1) + 1 FROM MeasurementType")
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        // `name` is nullable and stays NULL: it is only consulted for CUSTOM types,
        // which resolve their label from the row instead of the key's string resource.
        db.execSQL(
            """
            INSERT INTO MeasurementType
                (`key`, `name`, color, icon, unit, inputType, displayOrder,
                 isDerived, isEnabled, isPinned, isOnRightYAxis, isInternal)
            VALUES ('${bodyAge.key.name}', NULL, ${bodyAge.color}, '${bodyAge.icon.name}',
                    '${bodyAge.unit.name}', '${bodyAge.inputType.name}', $nextOrder,
                    0, 1, 0, 0, 0)
            """.trimIndent()
        )
    }
}
```

The v15 `MeasurementType` schema is, verbatim from `15.json`:

```sql
CREATE TABLE IF NOT EXISTS `MeasurementType` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `key` TEXT NOT NULL, `name` TEXT,
  `color` INTEGER NOT NULL, `icon` TEXT NOT NULL, `unit` TEXT NOT NULL,
  `inputType` TEXT NOT NULL, `displayOrder` INTEGER NOT NULL, `isDerived` INTEGER NOT NULL,
  `isEnabled` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, `isOnRightYAxis` INTEGER NOT NULL,
  `isInternal` INTEGER NOT NULL)
```

The v15 `User` columns are: `id, name, icon, birthDate, gender, heightCm, activityLevel, useAssistedWeighing, amputations`. The test's INSERT must match that list exactly.

- [ ] **Step 7: Register the migration in test support**

In `testutil/RoomTestSupport.kt`, add the import and append `MIGRATION_15_16` to the migration list it passes to Room.

- [ ] **Step 8: Run the tests**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.core.database.Migration15To16Test"`
Expected: PASS, 2 tests. A new `app/schemas/.../16.json` is generated.

Run: `cd android_app && ./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A android_app/app
git commit -m "feat(data): add body age type and client contact fields (schema v16)"
```

---

### Task 4: Carry the scale's BMI and body age through to the database

The decoder already produces both; three layers currently drop them. `DerivedValuesCalculator` is the subtle one — it overwrites BMI unconditionally.

**Files:**
- Modify: `core/bluetooth/data/ScaleMeasurement.kt`, `core/bluetooth/scales/OmronWlcHandler.kt:492`, `core/service/BleConnector.kt:475-490`, `core/service/DerivedValuesCalculator.kt:215`
- Test: `android_app/app/src/test/java/com/health/openscale/core/bluetooth/scales/OmronWlcHandlerTest.kt` (extend)

**Interfaces:**
- Consumes: `MeasurementTypeKey.BODY_AGE` from Task 3.
- Produces: `ScaleMeasurement.bmi: Float` and `ScaleMeasurement.bodyAge: Float`, both defaulting to `0.0f`. Task 5 reads the persisted `BMI` and `BODY_AGE` rows, not these fields directly.

- [ ] **Step 1: Write the failing test**

Append to `OmronWlcHandlerTest.kt`:

```kotlin
    @Test
    fun `decoded bmi and body age survive the mapping to ScaleMeasurement`() {
        // Regression guard: both were decoded by OmronLib and then silently discarded
        // by toMeasurement(). The report prints machine values only, so they must land.
        val record = OmronBodyCompositionLib.Record(
            timestamp = Date(0),
            weightKg = 68.4f,
            bodyFatPercent = 28.1f,
            skeletalMusclePercent = 31.0f,
            bmi = 24.8f,
            bmrKcal = 1420,
            visceralFatLevel = 8.5f,
            bodyAgeYears = 41,
        )

        val measurement = record.toMeasurementForTest(userId = 1)

        assertThat(measurement.bmi).isWithin(0.001f).of(24.8f)
        assertThat(measurement.bodyAge).isWithin(0.001f).of(41f)
        assertThat(measurement.weight).isWithin(0.001f).of(68.4f)
    }
```

`toMeasurement` is currently `private`. Change it to `internal` and add `@VisibleForTesting`, then expose it to the test under the name used above — or, if the existing test file already reaches it another way, follow that convention instead of inventing a new one.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.core.bluetooth.scales.OmronWlcHandlerTest"`
Expected: FAIL — `Unresolved reference: bmi` on `ScaleMeasurement`.

- [ ] **Step 3: Add the fields to ScaleMeasurement**

In `core/bluetooth/data/ScaleMeasurement.kt`, inside the constructor after `bmr`:

```kotlin
    var bmi: Float = 0.0f,       // dimensionless; supplied by the scale, not computed here
    var bodyAge: Float = 0.0f,   // years, as reported by the scale
```

And in `mergeWith`, beside the other fields:

```kotlin
        if (other.bmi > 0f && this.bmi <= 0f) this.bmi = other.bmi
        if (other.bodyAge > 0f && this.bodyAge <= 0f) this.bodyAge = other.bodyAge
```

- [ ] **Step 4: Stop discarding them in the handler**

In `core/bluetooth/scales/OmronWlcHandler.kt`, replace `toMeasurement`:

```kotlin
    @VisibleForTesting
    internal fun OmronBodyCompositionLib.Record.toMeasurement(userId: Int) = ScaleMeasurement(
        userId = userId,
        dateTime = timestamp,
        weight = weightKg,
        fat = bodyFatPercent ?: 0f,
        muscle = skeletalMusclePercent ?: 0f,
        visceralFat = visceralFatLevel ?: 0f,
        bmr = bmrKcal?.toFloat() ?: 0f,
        bmi = bmi ?: 0f,
        bodyAge = bodyAgeYears?.toFloat() ?: 0f,
    )
```

- [ ] **Step 5: Persist them in BleConnector**

In `core/service/BleConnector.kt`, add to the `rawUnitByKey` map (around line 490):

```kotlin
                MeasurementTypeKey.BMI          to UnitType.NONE,
                MeasurementTypeKey.BODY_AGE     to UnitType.NONE,
```

Then, immediately after the existing `addConvertedIfValid(measurementData.bmr, ...)` line and **before** the `WEIGHT` line — the ordering comment above that BMR call explains why: a value written after WEIGHT lands gets clobbered by the derived-values recalculation:

```kotlin
            // BMI and body age go in beside BMR, before WEIGHT, for the same reason:
            // DerivedValuesCalculator recalculates on WEIGHT and would otherwise
            // overwrite the scale's own figures with computed ones.
            addConvertedIfValid(measurementData.bmi,     MeasurementTypeKey.BMI)
            addConvertedIfValid(measurementData.bodyAge, MeasurementTypeKey.BODY_AGE)
```

- [ ] **Step 6: Stop the calculator overwriting the scale's BMI**

`core/service/DerivedValuesCalculator.kt:215` currently reads:

```kotlin
        processBmiCalculation(weightKg, userHeightCm).also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.BMI) }
```

BMR a few lines below already has a "keep the device's value" guard. BMI needs the same one:

```kotlin
        // BMI: when the scale supplied one, keep it. The height-derived formula is a
        // fallback for manual entries only — the report prints machine values.
        val bmiTypeId = allGlobalTypes.find { it.key == MeasurementTypeKey.BMI }?.id
        val existingBmi = bmiTypeId?.let { id ->
            currentMeasurementValues.find { it.typeId == id }?.floatValue
        }
        if (existingBmi == null || existingBmi <= 0f) {
            processBmiCalculation(weightKg, userHeightCm)
                .also { saveOrUpdateDerivedValue(it, MeasurementTypeKey.BMI) }
        }
```

Copy the exact variable names for `allGlobalTypes` and `currentMeasurementValues` from the BMR block directly beneath; they are already in scope there.

- [ ] **Step 7: Run the tests**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest`
Expected: PASS, including the existing `OmronLibTest` untouched.

- [ ] **Step 8: Commit**

```bash
git add -A android_app/app/src
git commit -m "fix(omron): stop discarding the scale's BMI and body age"
```

---

### Task 5: Report model and assembly

**Files:**
- Create: `core/report/ReportModel.kt`, `core/report/ReportUseCases.kt`
- Test: `android_app/app/src/test/java/com/health/openscale/core/report/ReportModelTest.kt`

**Interfaces:**
- Consumes: `ReferenceRanges.classify(...)` (Task 1); `MeasurementTypeKey.BODY_AGE` and `User.phone`/`User.email` (Task 3).
- Produces: `ReportModel`, `CoachBlock`, `ClientBlock`, `ReportRow`; and `ReportUseCases.buildModel(userId: Int, measurementId: Int): Result<ReportModel>`. Tasks 6 and 7 consume `ReportModel`.

- [ ] **Step 1: Write the failing test**

Create `ReportModelTest.kt`:

```kotlin
package com.health.openscale.core.report

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.GenderType
import org.junit.Test
import java.time.LocalDateTime

class ReportModelTest {

    private val coach = CoachBlock(
        name = "Reena Chandra",
        title = "Weight Loss Coach",
        club = "",
        phone = "98xxxxxxxx",
        email = "reena@example.com",
    )

    private val client = ClientBlock(
        name = "Asha Verma",
        phone = "98xxxxxxxx",
        email = "asha@example.com",
        ageYears = 34,
        gender = GenderType.FEMALE,
        heightCm = 162f,
    )

    private fun rowsFor(vararg values: Pair<String, Float>) =
        ReportRowBuilder.build(values.toMap(), client)

    @Test
    fun `builds seven rows in a fixed order`() {
        val model = ReportModel(
            coach = coach,
            client = client,
            measuredAt = LocalDateTime.of(2026, 8, 30, 9, 14),
            deviceName = "Omron HBF-702T",
            rows = rowsFor(
                "WEIGHT" to 68.4f, "BODY_FAT" to 28.1f, "MUSCLE" to 31.0f,
                "BMI" to 24.8f, "VISCERAL_FAT" to 8.5f, "BMR" to 1420f, "BODY_AGE" to 41f,
            ),
        )
        assertThat(model.rows).hasSize(7)
        assertThat(model.rows.map { it.label }).containsExactly(
            "Weight", "Body fat", "Skeletal muscle", "BMI",
            "Visceral fat", "Resting metabolism", "Body age",
        ).inOrder()
    }

    @Test
    fun `a missing metric still produces a row with dashes`() {
        // The sheet's shape must be constant; never omit a row.
        val rows = rowsFor("WEIGHT" to 68.4f)
        val fatRow = rows.single { it.label == "Body fat" }
        assertThat(fatRow.reading).isEqualTo("—")
        assertThat(fatRow.status).isEqualTo("—")
    }

    @Test
    fun `weight row carries no status`() {
        val row = rowsFor("WEIGHT" to 68.4f).single { it.label == "Weight" }
        assertThat(row.reading).isEqualTo("68.4 kg")
        assertThat(row.status).isEqualTo("—")
        assertThat(row.normalRange).isEqualTo("—")
    }

    @Test
    fun `body fat row is classified against the client's age and sex`() {
        val row = rowsFor("BODY_FAT" to 28.1f).single { it.label == "Body fat" }
        assertThat(row.reading).isEqualTo("28.1 %")
        assertThat(row.status).isEqualTo("Normal")
        assertThat(row.normalRange).isEqualTo("21.0 – 32.9 %")
    }

    @Test
    fun `body age row shows the delta against actual age`() {
        val row = rowsFor("BODY_AGE" to 41f).single { it.label == "Body age" }
        assertThat(row.reading).isEqualTo("41 years")
        assertThat(row.status).isEqualTo("+7 yrs")
        assertThat(row.normalRange).isEqualTo("34 (actual age)")
    }

    @Test
    fun `body age below actual age shows a negative delta`() {
        val row = rowsFor("BODY_AGE" to 30f).single { it.label == "Body age" }
        assertThat(row.status).isEqualTo("-4 yrs")
    }

    @Test
    fun `bmr prints kcal and carries no status`() {
        val row = rowsFor("BMR" to 1420f).single { it.label == "Resting metabolism" }
        assertThat(row.reading).isEqualTo("1420 kcal")
        assertThat(row.status).isEqualTo("—")
    }

    @Test
    fun `water is never a row`() {
        // The HBF-702T does not measure it.
        assertThat(rowsFor("WEIGHT" to 68.4f).map { it.label }).doesNotContain("Water")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.core.report.ReportModelTest"`
Expected: FAIL — `Unresolved reference: ReportModel`.

- [ ] **Step 3: Write ReportModel.kt**

```kotlin
package com.health.openscale.core.report

import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementTypeKey
import java.time.LocalDateTime

data class CoachBlock(
    val name: String,
    val title: String,
    val club: String,
    val phone: String,
    val email: String,
)

data class ClientBlock(
    val name: String,
    val phone: String,
    val email: String,
    val ageYears: Int,
    val gender: GenderType,
    val heightCm: Float,
)

/** One printed table row. All fields are pre-formatted; the renderer does no maths. */
data class ReportRow(
    val label: String,
    val reading: String,
    val status: String,
    val normalRange: String,
)

data class ReportModel(
    val coach: CoachBlock,
    val client: ClientBlock,
    val measuredAt: LocalDateTime,
    val deviceName: String,
    val rows: List<ReportRow>,
)

/**
 * Turns raw measurement values into printable rows.
 *
 * The row set is fixed and ordered: a metric the scale did not report still yields a
 * row, dashed out, so every sheet has the same shape regardless of what landed.
 */
object ReportRowBuilder {

    private const val DASH = "—"

    private data class Spec(
        val key: MeasurementTypeKey,
        val label: String,
        val format: (Float) -> String,
    )

    // Locale.US throughout: a comma-decimal locale would print "68,4 kg" on the sheet.
    private val L = java.util.Locale.US

    private val SPECS = listOf(
        Spec(MeasurementTypeKey.WEIGHT, "Weight") { String.format(L, "%.1f kg", it) },
        Spec(MeasurementTypeKey.BODY_FAT, "Body fat") { String.format(L, "%.1f %%", it) },
        Spec(MeasurementTypeKey.MUSCLE, "Skeletal muscle") { String.format(L, "%.1f %%", it) },
        Spec(MeasurementTypeKey.BMI, "BMI") { String.format(L, "%.1f", it) },
        Spec(MeasurementTypeKey.VISCERAL_FAT, "Visceral fat") { String.format(L, "%.1f", it) },
        Spec(MeasurementTypeKey.BMR, "Resting metabolism") { String.format(L, "%.0f kcal", it) },
        Spec(MeasurementTypeKey.BODY_AGE, "Body age") { String.format(L, "%.0f years", it) },
    )

    /** [values] is keyed by [MeasurementTypeKey.name]; absent keys become dashed rows. */
    fun build(values: Map<String, Float>, client: ClientBlock): List<ReportRow> =
        SPECS.map { spec ->
            val v = values[spec.key.name]
            when {
                v == null -> ReportRow(spec.label, DASH, DASH, DASH)
                spec.key == MeasurementTypeKey.BODY_AGE -> bodyAgeRow(spec, v, client)
                else -> {
                    val c = ReferenceRanges.classify(spec.key, v, client.ageYears, client.gender)
                    ReportRow(spec.label, spec.format(v), c.label, c.normalRange)
                }
            }
        }

    private fun bodyAgeRow(spec: Spec, value: Float, client: ClientBlock): ReportRow {
        val delta = value.toInt() - client.ageYears
        val status = if (delta >= 0) "+$delta yrs" else "$delta yrs"
        return ReportRow(spec.label, spec.format(value), status, "${client.ageYears} (actual age)")
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.core.report.ReportModelTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Write ReportUseCases.kt**

This is the only piece in `core/report/` that touches Room. It reads the measurement, maps `MeasurementValue` rows to a `Map<String, Float>` keyed by type name, and delegates to `ReportRowBuilder`.

```kotlin
package com.health.openscale.core.report

import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.CalculationUtils
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReportUseCases @Inject constructor(
    private val repository: DatabaseRepository,
    private val settingsFacade: SettingsFacade,
) {
    suspend fun buildModel(userId: Int, measurementId: Int): Result<ReportModel> = runCatching {
        val user = repository.getUserById(userId).first()
            ?: error("No user with id=$userId")
        val mwv = repository.getMeasurementWithValuesById(measurementId).first()
            ?: error("No measurement with id=$measurementId")

        val values: Map<String, Float> = mwv.values
            .mapNotNull { v -> v.value.floatValue?.let { v.type.key.name to it } }
            .toMap()

        val client = ClientBlock(
            name = user.name,
            phone = user.phone,
            email = user.email,
            ageYears = CalculationUtils.ageOn(mwv.measurement.timestamp, user.birthDate),
            gender = user.gender,
            heightCm = user.heightCm,
        )

        ReportModel(
            coach = loadCoachBlock(),
            client = client,
            measuredAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(mwv.measurement.timestamp), ZoneId.systemDefault()
            ),
            deviceName = "Omron HBF-702T",
            rows = ReportRowBuilder.build(values, client),
        )
    }

    private suspend fun loadCoachBlock(): CoachBlock = CoachBlock(
        name = settingsFacade.coachName(),
        title = settingsFacade.coachTitle(),
        club = settingsFacade.coachClub(),
        phone = settingsFacade.coachPhone(),
        email = settingsFacade.coachEmail(),
    )
}
```

Add the five `coach*()` accessors to `SettingsFacade` following the DataStore pattern already used there, defaulting `coachName` to `"Reena Chandra"` and `coachTitle` to `"Weight Loss Coach"`, the rest to `""`. Verify the exact `DatabaseRepository` method names (`getUserById`, `getMeasurementWithValuesById`) against the interface and adjust if they differ.

- [ ] **Step 6: Compile**

Run: `cd android_app && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A android_app/app/src
git commit -m "feat(report): add report model and assembly from measurements"
```

---

### Task 6: PDF renderer

**Files:**
- Create: `core/report/PdfReportRenderer.kt`
- Test: `android_app/app/src/test/java/com/health/openscale/core/report/PdfReportRendererTest.kt`

**Interfaces:**
- Consumes: `ReportModel` (Task 5).
- Produces: `PdfReportRenderer.render(model: ReportModel): ByteArray`. Task 7 writes these bytes to a SAF URI.

- [ ] **Step 1: Write the failing test**

Create `PdfReportRendererTest.kt`:

```kotlin
package com.health.openscale.core.report

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.GenderType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class PdfReportRendererTest {

    private fun model() = ReportModel(
        coach = CoachBlock("Reena Chandra", "Weight Loss Coach", "Fit Studio", "98xxxxxxxx", "reena@example.com"),
        client = ClientBlock("Asha Verma", "98xxxxxxxx", "asha@example.com", 34, GenderType.FEMALE, 162f),
        measuredAt = LocalDateTime.of(2026, 8, 30, 9, 14),
        deviceName = "Omron HBF-702T",
        rows = ReportRowBuilder.build(
            mapOf(
                "WEIGHT" to 68.4f, "BODY_FAT" to 28.1f, "MUSCLE" to 31.0f, "BMI" to 24.8f,
                "VISCERAL_FAT" to 8.5f, "BMR" to 1420f, "BODY_AGE" to 41f,
            ),
            ClientBlock("Asha Verma", "98xxxxxxxx", "asha@example.com", 34, GenderType.FEMALE, 162f),
        ),
    )

    @Test
    fun `renders a non empty pdf`() {
        val bytes = PdfReportRenderer.render(model())
        assertThat(bytes).isNotEmpty()
        assertThat(String(bytes.copyOfRange(0, 5), Charsets.ISO_8859_1)).isEqualTo("%PDF-")
    }

    @Test
    fun `output carries no app branding`() {
        // Global constraint: the sheet is the coach's, not the app's. Guards visible
        // content, PDF metadata and anything Skia writes underneath.
        val text = String(PdfReportRenderer.render(model()), Charsets.ISO_8859_1)
        assertThat(text.lowercase()).doesNotContain("openscale")
        assertThat(text).doesNotContain("com.health.openscale")
    }

    @Test
    fun `emits a single page`() {
        val text = String(PdfReportRenderer.render(model()), Charsets.ISO_8859_1)
        assertThat(text).contains("/Count 1")
    }

    @Test
    fun `every colour emitted is greyscale`() {
        // A colour survives print only as a grey; verify none is ever set.
        assertThat(PdfReportRenderer.PALETTE.all { isGrey(it) }).isTrue()
    }

    @Test
    fun `grey fills stay light enough for text to survive toner variance`() {
        val fills = listOf(PdfReportRenderer.HEADER_FILL)
        fills.forEach { assertThat(luminance(it)).isAtLeast(0.85f) }
    }

    @Test
    fun `a dashed row renders without throwing`() {
        val m = model().copy(rows = ReportRowBuilder.build(emptyMap(), model().client))
        assertThat(PdfReportRenderer.render(m)).isNotEmpty()
    }

    @Test
    fun `an overlong client name does not overflow its column`() {
        val m = model().copy(client = model().client.copy(name = "A".repeat(200)))
        assertThat(PdfReportRenderer.render(m)).isNotEmpty()
    }

    private fun isGrey(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r == g && g == b
    }

    private fun luminance(argb: Int): Float = (argb and 0xFF) / 255f
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.core.report.PdfReportRendererTest"`
Expected: FAIL — `Unresolved reference: PdfReportRenderer`.

- [ ] **Step 3: Write the renderer**

```kotlin
package com.health.openscale.core.report

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

/**
 * Draws a [ReportModel] onto a single A4 portrait page.
 *
 * Pure input → output: no Room, no Compose, no Context. That keeps it unit-testable
 * on the JVM and keeps layout decisions in one readable place.
 *
 * ## Printed in black and white
 * The practice prints on a mono laser, so the palette is greyscale only and status is
 * carried by the word plus the adjacent range — never by colour. The sheet must stay
 * readable as a photocopy.
 */
object PdfReportRenderer {

    // A4 at 72 dpi.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 42f

    // Not `const val`: 0xFF000000.toInt() is not a compile-time constant in Kotlin.
    val INK = 0xFF000000.toInt()
    val INK_SOFT = 0xFF666666.toInt()
    val HEADER_FILL = 0xFFE6E6E6.toInt()
    val RULE = 0xFFCCCCCC.toInt()

    /** Every colour this renderer may use. Asserted greyscale by test. */
    val PALETTE = listOf(INK, INK_SOFT, HEADER_FILL, RULE)

    private val L = java.util.Locale.US
    private val DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", L)
    private val TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a", L)

    /** One line of the client block: a label/value pair on the left and on the right. */
    private data class InfoRow(
        val leftLabel: String,
        val leftValue: String,
        val rightLabel: String,
        val rightValue: String,
    )

    private fun paint(size: Float, colour: Int, bold: Boolean = false) = Paint().apply {
        isAntiAlias = true
        textSize = size
        color = colour
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    fun render(model: ReportModel): ByteArray {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        drawPage(page.canvas, model)
        doc.finishPage(page)

        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private fun drawPage(c: Canvas, m: ReportModel) {
        var y = MARGIN + 24f

        // -- Masthead: the coach's identity, never the app's ----------------------
        c.drawText(m.coach.name.uppercase(), MARGIN, y, paint(20f, INK, bold = true))
        y += 18f
        c.drawText(m.coach.title, MARGIN, y, paint(11f, INK_SOFT))
        y += 14f
        c.drawText(
            listOf(m.coach.phone, m.coach.email, m.coach.club).filter { it.isNotBlank() }.joinToString(" · "),
            MARGIN, y, paint(9f, INK_SOFT),
        )
        y += 20f
        rule(c, y); y += 22f

        // -- Client block ---------------------------------------------------------
        val col2 = PAGE_W / 2f
        val label = paint(9f, INK_SOFT)
        val value = paint(10f, INK)
        val sexLabel = m.client.gender.name.lowercase().replaceFirstChar { it.uppercase() }
        val infoRows = listOf(
            InfoRow("Client", m.client.name, "Date", m.measuredAt.format(DATE_FMT)),
            InfoRow("Phone", m.client.phone, "Time", m.measuredAt.format(TIME_FMT)),
            InfoRow("Email", m.client.email, "Age", "${m.client.ageYears} / $sexLabel"),
            InfoRow("", "", "Height", String.format(L, "%.0f cm", m.client.heightCm)),
        )
        infoRows.forEach { r ->
            if (r.leftLabel.isNotBlank()) {
                c.drawText(r.leftLabel, MARGIN, y, label)
                c.drawText(ellipsize(r.leftValue, value, col2 - MARGIN - 60f), MARGIN + 55f, y, value)
            }
            c.drawText(r.rightLabel, col2, y, label)
            c.drawText(r.rightValue, col2 + 55f, y, value)
            y += 15f
        }
        y += 10f

        // -- Measurement table ----------------------------------------------------
        val cols = floatArrayOf(MARGIN, MARGIN + 150f, MARGIN + 250f, MARGIN + 360f)
        val tableRight = PAGE_W - MARGIN
        val headerH = 20f

        c.drawRect(MARGIN, y - 13f, tableRight, y - 13f + headerH, Paint().apply { color = HEADER_FILL })
        val head = paint(9f, INK, bold = true)
        c.drawText("Measurement", cols[0] + 4f, y, head)
        c.drawText("Reading", cols[1], y, head)
        c.drawText("Status", cols[2], y, head)
        c.drawText("Normal range", cols[3], y, head)
        y += headerH

        m.rows.forEach { r ->
            c.drawText(r.label, cols[0] + 4f, y, value)
            c.drawText(r.reading, cols[1], y, value)
            c.drawText(r.status, cols[2], y, value)
            c.drawText(r.normalRange, cols[3], y, paint(9f, INK_SOFT))
            y += 8f
            rule(c, y)
            y += 14f
        }

        // -- Footnotes ------------------------------------------------------------
        y += 8f
        val note = paint(8f, INK_SOFT)
        val sex = m.client.gender.name.lowercase()
        c.drawText("Ranges shown are for a ${m.client.ageYears}-year-old $sex.", MARGIN, y, note)
        y += 11f
        c.drawText("Measured on ${m.deviceName}. Not a medical diagnosis.", MARGIN, y, note)

        // -- Remarks: the only blank on the sheet ---------------------------------
        y += 30f
        c.drawText("Remarks", MARGIN, y, paint(9f, INK, bold = true))
        y += 6f
        repeat(2) {
            y += 18f
            c.drawLine(MARGIN + 55f, y, tableRight, y, Paint().apply { color = RULE; strokeWidth = 0.7f })
        }
    }

    private fun rule(c: Canvas, y: Float) =
        c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, Paint().apply { color = RULE; strokeWidth = 0.7f })

    /** Trims to fit rather than letting a long name run into the next column. */
    private fun ellipsize(text: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(text) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && p.measureText("$s…") > maxWidth) s = s.dropLast(1)
        return "$s…"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.core.report.PdfReportRendererTest"`
Expected: PASS, 7 tests.

If `output carries no app branding` fails, inspect what the emitted bytes contain — Skia writes a `Producer` string. If it turns out to embed something identifying, post-process the byte array to strip the document info dictionary before returning, and add a comment saying why.

- [ ] **Step 5: Commit**

```bash
git add -A android_app/app/src
git commit -m "feat(report): render a monochrome single-visit PDF"
```

---

### Task 7: Export wiring

**Files:**
- Modify: `core/report/ReportUseCases.kt`
- Test: `android_app/app/src/test/java/com/health/openscale/core/report/ReportFileNameTest.kt`

**Interfaces:**
- Consumes: `PdfReportRenderer.render(...)` (Task 6), `buildModel(...)` (Task 5).
- Produces: `ReportUseCases.suggestedFileName(model: ReportModel): String` and `ReportUseCases.exportPdf(userId: Int, measurementId: Int, uri: Uri, resolver: ContentResolver): Result<Unit>`. Task 11 calls both.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.health.openscale.core.report

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.GenderType
import org.junit.Test
import java.time.LocalDateTime

class ReportFileNameTest {

    private fun model(name: String) = ReportModel(
        coach = CoachBlock("Reena Chandra", "Weight Loss Coach", "", "", ""),
        client = ClientBlock(name, "", "", 34, GenderType.FEMALE, 162f),
        measuredAt = LocalDateTime.of(2026, 8, 30, 9, 14),
        deviceName = "Omron HBF-702T",
        rows = emptyList(),
    )

    @Test
    fun `file name is the client and date`() {
        assertThat(ReportUseCases.suggestedFileName(model("Asha Verma")))
            .isEqualTo("Asha Verma - 30 Aug 2026.pdf")
    }

    @Test
    fun `file name never leaks the app name`() {
        val n = ReportUseCases.suggestedFileName(model("Asha Verma")).lowercase()
        assertThat(n).doesNotContain("openscale")
    }

    @Test
    fun `path separators in a client name are stripped`() {
        assertThat(ReportUseCases.suggestedFileName(model("A/B\\C")))
            .isEqualTo("ABC - 30 Aug 2026.pdf")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.core.report.ReportFileNameTest"`
Expected: FAIL — `Unresolved reference: suggestedFileName`.

- [ ] **Step 3: Implement**

Add to `ReportUseCases`, with `suggestedFileName` in a `companion object` so the test can call it without Hilt:

```kotlin
    suspend fun exportPdf(
        userId: Int,
        measurementId: Int,
        uri: Uri,
        resolver: ContentResolver,
    ): Result<Unit> = runCatching {
        val model = buildModel(userId, measurementId).getOrThrow()
        val bytes = PdfReportRenderer.render(model)
        withContext(Dispatchers.IO) {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Cannot open OutputStream for uri=$uri")
        }
    }

    companion object {
        private val FILE_DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.US)

        /** Client name and date only — never the app name or package id. */
        fun suggestedFileName(model: ReportModel): String {
            val safeName = model.client.name.filterNot { it in "/\\:*?\"<>|" }.trim()
            return "$safeName - ${model.measuredAt.format(FILE_DATE_FMT)}.pdf"
        }
    }
```

- [ ] **Step 4: Run the tests**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.core.report.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A android_app/app/src
git commit -m "feat(report): write the PDF through SAF with a client-named file"
```

---

### Task 8: Four fixed users and the switcher

**Files:**
- Create: `ui/screen/components/UserSwitcherRow.kt`
- Modify: `OpenScaleApp.kt` (seeding), `ui/screen/settings/UserSettingsScreen.kt` (remove add/delete)
- Test: `android_app/app/src/test/java/com/health/openscale/ui/screen/components/UserSwitcherRowTest.kt`

**Interfaces:**
- Consumes: `User` with `phone`/`email` (Task 3).
- Produces: `@Composable fun UserSwitcherRow(users: List<User>, selectedId: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier)`. Tasks 9, 10 and 11 place this at the top of each tab.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.health.openscale.ui.screen.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.health.openscale.testutil.Fixtures
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserSwitcherRowTest {

    @get:Rule val compose = createComposeRule()

    private val users = listOf(
        Fixtures.user(id = 1, name = "Asha"),
        Fixtures.user(id = 2, name = "Ravi"),
        Fixtures.user(id = 3, name = "Mira"),
        Fixtures.user(id = 4, name = "Dev"),
    )

    @Test
    fun `shows all four people at once`() {
        compose.setContent { UserSwitcherRow(users, selectedId = 1, onSelect = {}) }
        listOf("Asha", "Ravi", "Mira", "Dev").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun `tapping a person reports their id`() {
        var picked = -1
        compose.setContent { UserSwitcherRow(users, selectedId = 1, onSelect = { picked = it }) }
        compose.onNodeWithText("Mira").performClick()
        assertThat(picked).isEqualTo(3)
    }
}
```

Extend `testutil/Fixtures.kt` with a `user(id: Int, name: String)` helper if it lacks one, matching the `User` constructor from Task 3.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.ui.screen.components.UserSwitcherRowTest"`
Expected: FAIL — `Unresolved reference: UserSwitcherRow`.

- [ ] **Step 3: Implement the switcher**

```kotlin
package com.health.openscale.ui.screen.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.openscale.core.data.User

/**
 * The four people, always on screen, one tap apart.
 *
 * Deliberately not a dropdown or a drawer: switching client is the single most
 * frequent action in the practice, so it costs one tap and no discovery.
 */
@Composable
fun UserSwitcherRow(
    users: List<User>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        users.forEach { user ->
            val label = @Composable {
                Text(user.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (user.id == selectedId) {
                Button(onClick = { onSelect(user.id) }, modifier = Modifier.weight(1f)) { label() }
            } else {
                OutlinedButton(onClick = { onSelect(user.id) }, modifier = Modifier.weight(1f)) { label() }
            }
        }
    }
}
```

- [ ] **Step 4: Seed exactly four users**

In `OpenScaleApp.initializeDefaultData()`, after the measurement types are seeded, insert four placeholder users when the table is empty — named `Person 1`…`Person 4`, renameable in Settings. Then remove the add-user and delete-user affordances from `UserSettingsScreen.kt`, leaving edit intact.

- [ ] **Step 5: Run the tests**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A android_app/app/src
git commit -m "feat(ui): add the always-visible four-person switcher"
```

---

### Task 9: Home screen

**Files:**
- Create: `ui/screen/home/HomeScreen.kt`, `ui/screen/home/HomeViewModel.kt`
- Test: `android_app/app/src/test/java/com/health/openscale/ui/screen/home/HomeScreenTest.kt`

**Interfaces:**
- Consumes: `UserSwitcherRow(...)` (Task 8).
- Produces: `@Composable fun HomeScreen(navController: NavController)`, registered at `Routes.HOME` by Task 13, plus the stateless half the test drives:

```kotlin
sealed interface HomeUiState {
    data class Reading(
        val weightKg: Float,
        val deltaKg: Float,
        val fatPercent: Float,
        val musclePercent: Float,
        val bmi: Float,
    ) : HomeUiState
}

@Composable
fun HomeContent(
    users: List<User>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    latest: HomeUiState.Reading?,   // null renders the "No readings yet" empty state
    onSync: () -> Unit,
    isSyncing: Boolean,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.health.openscale.ui.screen.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `shows the latest weight and a sync button`() {
        compose.setContent {
            HomeContent(
                users = emptyList(),
                selectedId = 1,
                onSelect = {},
                latest = HomeUiState.Reading(
                    weightKg = 68.4f, deltaKg = -0.6f,
                    fatPercent = 28.1f, musclePercent = 31.0f, bmi = 24.8f,
                ),
                onSync = {},
                isSyncing = false,
            )
        }
        compose.onNodeWithText("68.4 kg").assertIsDisplayed()
        compose.onNodeWithText("Sync scale").assertIsDisplayed()
    }

    @Test
    fun `shows an empty state when the person has no readings`() {
        compose.setContent {
            HomeContent(emptyList(), 1, {}, latest = null, onSync = {}, isSyncing = false)
        }
        compose.onNodeWithText("No readings yet").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.ui.screen.home.HomeScreenTest"`
Expected: FAIL — `Unresolved reference: HomeContent`.

- [ ] **Step 3: Implement**

Split into a stateless `HomeContent(...)` composable (what the test drives) and a `HomeScreen(navController)` wrapper that collects from `HomeViewModel`. `HomeUiState.Reading` carries pre-formatted numbers. Layout, top to bottom: `UserSwitcherRow`, the weight in `displayLarge` with the delta beneath, a two-by-two grid of fat / muscle / BMI / visceral fat, then a full-width `Button` reading "Sync scale" that shows a `CircularProgressIndicator` while `isSyncing`.

Wire `onSync` to the existing Bluetooth connect path used by `BluetoothActionButton.kt` — read that file and reuse its view-model calls rather than inventing a second path.

- [ ] **Step 4: Run the tests**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.ui.screen.home.HomeScreenTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A android_app/app/src
git commit -m "feat(ui): add the home screen"
```

---

### Task 10: History screen

**Files:**
- Create: `ui/screen/history/HistoryScreen.kt`, `ui/screen/history/HistoryViewModel.kt`
- Test: `android_app/app/src/test/java/com/health/openscale/ui/screen/history/HistoryScreenTest.kt`

**Interfaces:**
- Consumes: `UserSwitcherRow(...)` (Task 8).
- Produces: `@Composable fun HistoryScreen(navController: NavController)`, plus:

```kotlin
data class HistoryRow(
    val measurementId: Int,
    val dateLabel: String,
    val weightLabel: String,
    val deltaLabel: String,
)

@Composable
fun HistoryContent(
    users: List<User>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    rows: List<HistoryRow>,          // newest first; empty renders "No readings yet"
    onRowClick: (Int) -> Unit,       // receives measurementId
    modifier: Modifier = Modifier,
)
```

Task 11 imports `HistoryRow` from this package for its weigh-in picker.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.health.openscale.ui.screen.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryScreenTest {

    @get:Rule val compose = createComposeRule()

    private val rows = listOf(
        HistoryRow(11, "23 Aug 2026", "69.5 kg", "-0.7"),
        HistoryRow(12, "30 Aug 2026", "68.4 kg", "-1.1"),
    )

    @Test
    fun `lists weigh-ins newest first`() {
        compose.setContent { HistoryContent(emptyList(), 1, {}, rows.reversed(), onRowClick = {}) }
        compose.onNodeWithText("30 Aug 2026").assertIsDisplayed()
        compose.onNodeWithText("68.4 kg").assertIsDisplayed()
    }

    @Test
    fun `tapping a row reports its measurement id`() {
        var picked = -1
        compose.setContent { HistoryContent(emptyList(), 1, {}, rows, onRowClick = { picked = it }) }
        compose.onNodeWithText("23 Aug 2026").performClick()
        assertThat(picked).isEqualTo(11)
    }

    @Test
    fun `shows an empty state with no readings`() {
        compose.setContent { HistoryContent(emptyList(), 1, {}, emptyList(), onRowClick = {}) }
        compose.onNodeWithText("No readings yet").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.ui.screen.history.HistoryScreenTest"`
Expected: FAIL — `Unresolved reference: HistoryContent`.

- [ ] **Step 3: Implement**

`HistoryContent` is stateless: `UserSwitcherRow` on top, then a compact Vico sparkline of weight over the visible period, then a `LazyColumn` of rows. Tapping a row navigates to the existing `MeasurementDetailScreen` for edit and delete — reuse it rather than writing a second editor.

- [ ] **Step 4: Run the tests**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.ui.screen.history.HistoryScreenTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A android_app/app/src
git commit -m "feat(ui): add the history screen"
```

---

### Task 11: Report screen

**Files:**
- Create: `ui/screen/report/ReportScreen.kt`, `ui/screen/report/ReportViewModel.kt`
- Test: `android_app/app/src/test/java/com/health/openscale/ui/screen/report/ReportScreenTest.kt`

**Interfaces:**
- Consumes: `UserSwitcherRow` (Task 8), `HistoryRow` (Task 10), `ReportUseCases.exportPdf(...)` and `suggestedFileName(...)` (Task 7), `ImportExportUseCases.exportUserToCsv(...)` (existing).
- Produces: `@Composable fun ReportScreen(navController: NavController)`, plus:

```kotlin
@Composable
fun ReportContent(
    users: List<User>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    rows: List<HistoryRow>,               // newest first
    selectedMeasurementId: Int?,          // null disables both export buttons
    onPick: (Int) -> Unit,
    onExportPdf: () -> Unit,
    onExportCsv: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.health.openscale.ui.screen.report

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.health.openscale.ui.screen.history.HistoryRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReportScreenTest {

    @get:Rule val compose = createComposeRule()

    private val rows = listOf(HistoryRow(12, "30 Aug 2026", "68.4 kg", "-1.1"))

    @Test
    fun `defaults to the most recent weigh-in`() {
        compose.setContent {
            ReportContent(emptyList(), 1, {}, rows, selectedMeasurementId = 12, onPick = {}, onExportPdf = {}, onExportCsv = {})
        }
        compose.onNodeWithText("30 Aug 2026").assertIsDisplayed()
        compose.onNodeWithText("Export PDF").assertIsEnabled()
    }

    @Test
    fun `export is disabled with nothing selected`() {
        compose.setContent {
            ReportContent(emptyList(), 1, {}, emptyList(), selectedMeasurementId = null, onPick = {}, onExportPdf = {}, onExportCsv = {})
        }
        compose.onNodeWithText("Export PDF").assertIsNotEnabled()
    }

    @Test
    fun `export pdf fires for the selected weigh-in`() {
        var exported = false
        compose.setContent {
            ReportContent(emptyList(), 1, {}, rows, 12, onPick = {}, onExportPdf = { exported = true }, onExportCsv = {})
        }
        compose.onNodeWithText("Export PDF").performClick()
        assertThat(exported).isTrue()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.ui.screen.report.ReportScreenTest"`
Expected: FAIL — `Unresolved reference: ReportContent`.

- [ ] **Step 3: Implement**

`ReportContent` is stateless: `UserSwitcherRow`, a weigh-in picker defaulting to the newest, a read-only preview of the header fields so the coach can spot a missing phone number before printing, then `Export PDF` and `Export CSV` buttons.

In `ReportScreen`, launch SAF with `ActivityResultContracts.CreateDocument("application/pdf")`, pre-filled from `ReportUseCases.suggestedFileName(model)`. Follow the launcher pattern already in `DataManagementSettingsScreen.kt:280`. CSV reuses the existing `exportCsvLauncher` path unchanged.

- [ ] **Step 4: Run the tests**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.ui.screen.report.ReportScreenTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A android_app/app/src
git commit -m "feat(ui): add the report screen with PDF and CSV export"
```

---

### Task 12: Coach profile settings

Spec §4.5 lists the coach profile as part of Settings; Task 5 added the `SettingsFacade` accessors but nothing to edit them. Without this, the masthead is stuck on its defaults and the contact line prints empty.

**Files:**
- Create: `ui/screen/settings/CoachProfileScreen.kt`
- Modify: `ui/screen/settings/SettingsScreen.kt`, `ui/navigation/Routes.kt`, `ui/navigation/AppNavHost.kt`
- Test: `android_app/app/src/test/java/com/health/openscale/ui/screen/settings/CoachProfileScreenTest.kt`

**Interfaces:**
- Consumes: the `coachName()` / `coachTitle()` / `coachClub()` / `coachPhone()` / `coachEmail()` accessors on `SettingsFacade` (Task 5).
- Produces: `@Composable fun CoachProfileContent(state: CoachProfileUiState, onChange: (CoachProfileUiState) -> Unit, onSave: () -> Unit)`; `Routes.COACH_PROFILE`. Task 13 registers the route.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.health.openscale.ui.screen.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoachProfileScreenTest {

    @get:Rule val compose = createComposeRule()

    private val defaults = CoachProfileUiState(
        name = "Reena Chandra",
        title = "Weight Loss Coach",
        club = "",
        phone = "",
        email = "",
    )

    @Test
    fun `defaults to the coach's name and title`() {
        compose.setContent { CoachProfileContent(defaults, onChange = {}, onSave = {}) }
        compose.onNodeWithText("Reena Chandra").assertIsDisplayed()
        compose.onNodeWithText("Weight Loss Coach").assertIsDisplayed()
    }

    @Test
    fun `editing the club reports the change`() {
        var latest = defaults
        compose.setContent { CoachProfileContent(latest, onChange = { latest = it }, onSave = {}) }
        compose.onNodeWithText("Club name").performTextReplacement("Fit Studio")
        assertThat(latest.club).isEqualTo("Fit Studio")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.ui.screen.settings.CoachProfileScreenTest"`
Expected: FAIL — `Unresolved reference: CoachProfileUiState`.

- [ ] **Step 3: Implement**

```kotlin
package com.health.openscale.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What the report masthead prints. Five fields, set once. */
data class CoachProfileUiState(
    val name: String,
    val title: String,
    val club: String,
    val phone: String,
    val email: String,
)

@Composable
fun CoachProfileContent(
    state: CoachProfileUiState,
    onChange: (CoachProfileUiState) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        val field = @Composable { label: String, value: String, update: (String) -> Unit ->
            OutlinedTextField(
                value = value,
                onValueChange = update,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        field("Name", state.name) { onChange(state.copy(name = it)) }
        field("Title", state.title) { onChange(state.copy(title = it)) }
        field("Club name", state.club) { onChange(state.copy(club = it)) }
        field("Phone", state.phone) { onChange(state.copy(phone = it)) }
        field("Email", state.email) { onChange(state.copy(email = it)) }

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Save")
        }
    }
}
```

Add a `CoachProfileScreen(navController)` wrapper that loads from and persists to `SettingsFacade`, add `const val COACH_PROFILE = "settings/coach"` to `Routes.kt`, and add a "Coach profile" entry to `SettingsScreen.kt`'s list.

- [ ] **Step 4: Run the tests**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.ui.screen.settings.CoachProfileScreenTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A android_app/app/src
git commit -m "feat(settings): add the coach profile editor"
```

---

### Task 13: Collapse navigation and delete dead screens

Last, so nothing is deleted while something still needs it.

**Files:**
- Modify: `ui/navigation/Routes.kt`, `ui/navigation/AppNavHost.kt`, `ui/navigation/AppNavigation.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`
- Delete: `ui/screen/graph/`, `ui/screen/statistics/`, `ui/screen/insights/`, `ui/screen/overview/OverviewScreen.kt`, `ui/screen/table/TableScreen.kt`, `ui/widget/`, and five settings screens

**Interfaces:**
- Consumes: `HomeScreen`, `HistoryScreen`, `ReportScreen` (Tasks 9–11), `CoachProfileScreen` and `Routes.COACH_PROFILE` (Task 12).
- Produces: `Routes.HOME`, `Routes.HISTORY`, `Routes.REPORT`, `Routes.mainTabs()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.health.openscale.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RoutesTest {

    @Test
    fun `exactly three tabs`() {
        assertThat(Routes.mainTabs()).containsExactly(Routes.HOME, Routes.HISTORY, Routes.REPORT).inOrder()
    }

    @Test
    fun `deleted routes are gone`() {
        val all = Routes::class.java.declaredFields.mapNotNull { it.name }
        assertThat(all).containsNoneOf("GRAPH", "STATISTICS", "INSIGHTS", "TABLE_DRILLDOWN", "OVERVIEW_DRILLDOWN")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android_app && ./gradlew :app:testDebugUnitTest --tests "com.health.openscale.ui.navigation.RoutesTest"`
Expected: FAIL — `Unresolved reference: mainTabs`.

- [ ] **Step 3: Rewrite Routes**

Replace `OVERVIEW`, `GRAPH`, `TABLE`, `STATISTICS`, `INSIGHTS` with `HOME`, `HISTORY`, `REPORT`; drop both drill-down routes and their builder functions; add `fun mainTabs() = listOf(HOME, HISTORY, REPORT)`. Update `getTitleResourceId` and `getIconForRoute` to cover only the surviving routes, with icons `Home`, `TableRows`, `Description`. Keep `MEASUREMENT_DETAIL`, `SETTINGS`, `USER_SETTINGS`, `USER_DETAIL`, `COACH_PROFILE`, `BLUETOOTH_SETTINGS`, `BLUETOOTH_DETAIL`, `DATA_MANAGEMENT_SETTINGS`, `ABOUT_SETTINGS`.

- [ ] **Step 4: Update the nav host and delete the screens**

Point `AppNavHost.kt` at the three new screens and remove every `composable(...)` for a deleted route. Then:

```bash
cd android_app/app/src/main/java/com/health/openscale/ui
rm -rf screen/graph screen/statistics screen/insights widget
rm screen/overview/OverviewScreen.kt screen/table/TableScreen.kt
rm screen/settings/ChartSettingsScreen.kt \
   screen/settings/MeasurementTypeSettingsScreen.kt \
   screen/settings/MeasurementTypeDetailScreen.kt
rm -f screen/components/MeasurementChartFilter.kt \
      screen/components/MeasurementChartSettings.kt \
      screen/components/PeriodChart.kt \
      screen/components/MeasurementTypeFilterRow.kt
```

Keep `screen/overview/MeasurementDetailScreen.kt` — History and Report both navigate to it.

Keep `screen/settings/GeneralSettingsScreen.kt` — spec §4.5 retains units in Settings, and this is the screen that hosts them. It is deliberately absent from the `rm` list above.

In `AndroidManifest.xml`, delete the `MeasurementWidgetReceiver` receiver, the `MeasurementWidgetConfigActivity` activity, and the `RECEIVE_BOOT_COMPLETED` permission if nothing else uses it. In `app/build.gradle.kts`, drop the three Glance dependencies. Delete `res/xml/measurement_widget.xml`.

- [ ] **Step 5: Compile and run the full suite**

Run: `cd android_app && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Follow the compiler for stragglers referencing deleted screens.

Run: `cd android_app && ./gradlew :app:testDebugUnitTest`
Expected: PASS, whole suite.

Run: `cd android_app && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A android_app/app
git commit -m "refactor(ui): collapse to three tabs and delete unused screens"
```

---

## Verification before sign-off

Beyond green tests, three things need a human:

1. **Check every threshold in `ReferenceRanges.kt` against the HBF-702T manual.** They are printed on client-facing sheets. This is the one item no test can settle.
2. **Print a real report on the actual mono laser.** Confirm the `#E6E6E6` header fill does not swallow its text and the Remarks lines are wide enough to write on.
3. **Sync a real weigh-in from the scale** and confirm the printed BMI matches the scale's own display, proving the Task 4 fix landed end to end.
