# Omron HBF-702T Coach App — Design

**Date:** 2026-08-30
**Status:** Approved design, pending implementation plan
**Base:** fork of openScale 3.1.2 (`com.health.openscale`)

## 1. Context

openScale is a general-purpose Bluetooth scale tracker supporting ~70 devices. This
fork serves exactly one setting: a weight-loss coaching practice run by Reena Chandra,
with a single **Omron HBF-702T** body composition monitor and four regular clients.

The app must do three things well and nothing else:

1. Pull readings off the HBF-702T over Bluetooth.
2. Let the coach switch between four people in one tap.
3. Produce a printable, client-facing PDF report for a single weigh-in.

Everything openScale does beyond that is weight the coach has to carry, so it goes.

## 2. Non-goals

- Supporting any scale other than the HBF-702T (and its identical KRD-703T sibling).
- More than four people. The scale has four slots; the app mirrors them exactly.
- Progress / date-range reports. Explicitly dropped — one PDF is one weigh-in.
- Charts, statistics, insights screens, or the home-screen widget.
- Colour output. Reports are designed for a monochrome laser printer.
- Cloud sync, accounts, or any network capability.

## 3. Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Device support | HBF-702T only | The only scale in the practice. Confirmed as the Bluetooth "T" variant. |
| People | Exactly 4, fixed | Mirrors the scale's 4 EEPROM slots. No client list to manage. |
| Screens | Home / History / Report | Everything else deleted. |
| PDF scope | Single weigh-in only | Coach hands a client one sheet per session. |
| Classification | Status + normal range per metric | Compact; leaves room for Remarks. |
| BMI cut-offs | Indian / Asian-Pacific | Practice is in India; risk rises at lower BMI in South Asians. |
| Blank fields | Remarks box only | Everything else prints filled. |
| BMI provenance | App-derived, not the scale's | Pure `weight/height²`, not a BIA measurement; keeps BMI consistent with the weight printed beside it. See §4.2. |
| Colour | None — greyscale only | Printed black and white. |
| Graphics | SVG → vector drawable → PDF paths | Sharp at any size, small files, no bitmaps. |
| App name | Stays "openScale"; package unchanged | No rebrand wanted; avoids needless churn. |
| App name in PDF | Never appears | The sheet is the coach's, not the app's. |
| PDF engine | `android.graphics.pdf.PdfDocument` | No new dependency, no licence friction with GPL-3. |

## 4. Architecture

### 4.1 Device layer cull

`core/bluetooth/` currently holds 70 handlers and 21 protocol libraries.

**Keep:**
- `scales/ScaleDeviceHandler.kt` — the abstract base.
- `scales/OmronWlcHandler.kt` — the HBF-702T driver.
- `scales/GattScaleAdapter.kt` — the Omron declares `LinkMode.CONNECT_GATT`.
- `libs/OmronLib.kt` — record decoder.

**Delete:** the other ~67 handlers, `BroadcastScaleAdapter`, `SppScaleAdapter`,
`DebugGattHandler`, the other 20 libraries, and their tests (~90 files).

**Consequent edits:**
- `ScaleFactory.createHandlers()` collapses to `listOf(OmronWlcHandler())`.
- `ScaleFactory.createModernCommunicator()` loses its `BROADCAST_ONLY` and
  `CLASSIC_SPP` branches; `LinkMode` reduces to `CONNECT_GATT`.
- `BroadcastAction` and its plumbing go with `BroadcastScaleAdapter`.

`MODELS_BY_ADVERTISED_ID` in `OmronWlcHandler` narrows to the two 702T ids
(`0x0001_000C`, `0x0001_040C`) and `0x0001_0011` (KRD-703T, same profile). The other
Omron entries reference `PROFILE_HBF_32`, which becomes dead and is removed from
`OmronLib` along with `PROFILE_HBF_32_NO_BODY_AGE`.

The scan-and-pair flow is untouched; it simply only ever recognises one scale.

### 4.2 Data model

**`User`** (`core/data/User.kt`) gains two fields, both used only on the report header:

```kotlin
val phone: String = "",
val email: String = "",
```

Room migration required; schemas are versioned under `app/schemas/`.

**`MeasurementTypeKey`** (`core/data/Enums.kt`) gains:

```kotlin
BODY_AGE(35, R.string.measurement_type_body_age, listOf(UnitType.NONE), listOf(InputFieldType.FLOAT)),
```

`id = 35` — next free after `BCM(34)`, below the `CUSTOM(99)` sentinel.

**`ScaleMeasurement`** gains `bmi` and `bodyAge`.

**Bug fix — currently discarded data.** `OmronWlcHandler.toMeasurement()`
(`OmronWlcHandler.kt:492`) maps only weight, fat, muscle, visceral fat and BMR.
`OmronLib.Record` also decodes `bmi` and `bodyAgeYears`, and both are dropped on the
floor. **Body age is carried through; BMI is deliberately not.**

```kotlin
private fun OmronBodyCompositionLib.Record.toMeasurement(userId: Int) = ScaleMeasurement(
    userId = userId,
    dateTime = timestamp,
    weight = weightKg,
    fat = bodyFatPercent ?: 0f,
    muscle = skeletalMusclePercent ?: 0f,
    visceralFat = visceralFatLevel ?: 0f,
    bmr = bmrKcal?.toFloat() ?: 0f,
    bodyAge = bodyAgeYears?.toFloat() ?: 0f,  // was discarded; nothing else can produce it
)
```

**Why body age must come from the scale.** It is a proprietary Omron figure with no
app-side formula. If it is not carried through, the value simply does not exist —
nothing derives it, and nothing can overwrite it once stored. No guard is needed.

**Why BMI stays app-derived.** BMI is not a BIA measurement. It is `weight / height²`
— the same arithmetic on both sides — unlike body fat, skeletal muscle, visceral fat
and BMR, which come from the scale's proprietary impedance analysis and have no
honest app-side equivalent. Three reasons decide it:

1. **Internal consistency of the printed sheet.** Making the scale's BMI sticky means
   that correcting a mistyped weight leaves BMI stale. A sheet reading
   `Weight 68.4 kg … BMI 26.1` when 26.1 corresponds to 71 kg is visibly
   self-contradictory, and a client or a coach checking the arithmetic loses trust in
   the whole document. Deriving BMI keeps it locked to the weight printed beside it.
2. **No provenance exists to distinguish the two cases.** A stored BMI row is
   identical whether the device wrote it or the app computed it. A guard that keeps
   any existing BMI cannot tell "the scale measured this" from "the app computed this
   from a weight that has since been corrected". Adding a provenance column would mean
   another schema migration to solve a problem BMI does not actually have.
3. **The existing behaviour is correct and tested.**
   `MeasurementCrudUseCasesTest.saveMeasurement_editWeight_recalculatesDerivedWithoutChurningIds`
   encodes the rule that derived values track their inputs. That rule is right.

The residual risk is that the app's stored height differs from the height configured
in the scale's user slot, making the printed BMI disagree with the scale's display.
This is mitigated by the report printing the client's height in its header, so the
input is visible and auditable on the sheet itself.

`DerivedValuesCalculator` is therefore left unchanged, and `ScaleMeasurement` gains
`bodyAge` only — not `bmi`.

**`CoachProfile`** — new, stored in settings (DataStore), not Room. One instance:
name, title, club name, phone, email. Defaults to `Reena Chandra` /
`Weight Loss Coach`.

**Seeding.** Four users are created on first run. The add-user and delete-user paths
are removed; users can be renamed and edited but not added or removed.

### 4.3 Reference ranges

New `core/report/ReferenceRanges.kt` — a single pure-Kotlin file, no Android
dependencies, holding every threshold in one place so a wrong number is a two-minute
edit.

```kotlin
enum class Band { LOW, NORMAL, HIGH, VERY_HIGH, NONE }

data class Classification(
    val band: Band,
    val label: String,       // "Normal", "High", …
    val normalRange: String, // "21.0 – 32.9 %"
)

fun classify(key: MeasurementTypeKey, value: Float, ageYears: Int, gender: GenderType): Classification
```

Bands are **age- and sex-dependent** for body fat and skeletal muscle. The app already
stores `birthDate` and `gender` per user, so the correct band is selected
automatically and the sheet footnotes whose ranges are shown.

| Metric | Banding |
|---|---|
| Body fat % | Omron / Gallagher et al. — by sex, age brackets 20–39 / 40–59 / 60–79 |
| Skeletal muscle % | Omron — by sex, age brackets 18–39 / 40–59 / 60–80 |
| Visceral fat | Normal 0.5–9.5, High 10.0–14.5, Very High ≥15.0 (sex/age independent) |
| BMI | Indian / Asian-Pacific: Under <18.0, Normal 18.0–22.9, Over 23.0–24.9, Obese ≥25.0 |
| Body age | Delta against actual age; no band |
| Weight | `Band.NONE` — BMI carries the interpretation |
| BMR | `Band.NONE` — no meaningful "high" or "low" |

`Band.NONE` rows print an em dash in the Status and Normal range columns rather than
an invented verdict.

> **Verification required before use.** The threshold tables are transcribed from
> published Omron and Asian-Pacific BMI references. They are printed on sheets handed
> to clients and **must be checked against the manual supplied with the scale** before
> the app is used in the practice. All values live in this one file for that reason.
>
> This is a fitness classification, not a medical diagnosis. The report carries a
> one-line footnote saying so.

### 4.4 Report module

New `core/report/` package. The renderer is pure input → output: no Room, no Compose,
no Android context. That makes it unit-testable on the JVM.

**Where formatting lives.** Per-metric values are pre-formatted by `ReportRowBuilder`,
so the renderer never does arithmetic or unit formatting on a measurement — that is
what keeps the seven table rows consistent and locale-safe in one place.

The header blocks are deliberately different. `ClientBlock` keeps `ageYears` and
`gender` as semantic values because `ReferenceRanges.classify` consumes them to band
every row; `measuredAt` stays a `LocalDateTime` for the same reason a date should not
be stringly-typed in a model. Turning those into pre-formatted strings would mean
carrying each field twice — once to compute with, once to print — which is worse than
the split it would remove.

So the header's display formatting (date, time, height, gender casing, masthead
uppercasing) happens inside `draw()`. That is acceptable precisely because `draw()` is
the fully JVM-tested half: the `RecordingCanvas` captures every resulting string, so
the formatting is asserted, not assumed. The binding rule is therefore narrower than
"the renderer formats nothing":

> No measurement value is formatted in the renderer, and every formatter it does use
> pins `Locale.US`.

```
core/report/
  ReferenceRanges.kt    — thresholds and classification (§4.3)
  ReportModel.kt        — plain data, no framework types
  ReportCanvas.kt       — drawing seam; keeps layout testable off-device
  PdfReportRenderer.kt  — lays a ReportModel out onto a ReportCanvas
  ReportUseCases.kt     — assembles the model from the DB, writes via SAF
```

**Drawing is abstracted behind a `ReportCanvas` seam.** `PdfDocument` is Skia-backed
and native: Robolectric ships no `ShadowPdfDocument`, so `nativeCreateDocument()`
returns `0L` and every `startPage()` throws `IllegalStateException: document is
closed!`. The renderer therefore cannot be unit-tested by driving a real
`PdfDocument` on the JVM. The alternatives were an instrumented test (needing a device
this project deliberately does not require — the suite is JVM-runnable by design), a
third-party PDF dependency, or leaving the flagship deliverable untested. Instead the
drawing surface is abstracted:

```kotlin
interface ReportCanvas {
    fun drawText(text: String, x: Float, y: Float, style: TextStyle)
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, colour: Int)
    fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, colour: Int)
    fun measureText(text: String, style: TextStyle): Float
}
```

- `PdfReportRenderer.draw(model, canvas)` holds **all** layout logic and is fully
  JVM-testable against a `RecordingCanvas` fake that captures every draw call.
- `PdfReportRenderer.render(model): ByteArray` is a thin adapter: create the
  `PdfDocument`, wrap its `Canvas`, call `draw`, write the bytes. It makes no
  decisions, so there is little for a test to catch.

What the `RecordingCanvas` tests assert: the seven rows are drawn in order; an absent
metric draws an em dash rather than being skipped; no text overflows its column;
content fits one page; **every colour passed to the canvas is greyscale**; and **no
drawn string contains `openScale` or `com.health.openscale`**.

That last assertion replaces the byte-scan of the emitted PDF and covers the same
realistic risk. Android's `PdfDocument` exposes no document-info API and never injects
the package id; Skia writes only its own `Producer` string. The only way the app's
name could reach the sheet is by being drawn onto it — which is precisely what the
canvas-level assertion prevents, and it fails at the offending `drawText` call rather
than somewhere inside a byte blob.

What no JVM test can cover, and which therefore stays on the sign-off list: that the
emitted file is a structurally valid PDF which opens and is legible. That is verified
once by rendering on a real device.

`ReportModel` shape:

```kotlin
data class ReportModel(
    val coach: CoachBlock,      // name, title, club, phone, email
    val client: ClientBlock,    // name, phone, email, age, gender, heightCm
    val measuredAt: LocalDateTime,
    val deviceName: String,     // "Omron HBF-702T"
    val rows: List<ReportRow>,
)

data class ReportRow(
    val label: String,
    val reading: String,        // pre-formatted, unit included
    val status: String,         // "Normal", "—"
    val normalRange: String,    // "21.0 – 32.9 %", "—"
)
```

**Page layout — A4 portrait** (595 × 842 pt). One weigh-in always fits one page;
there is no pagination.

```
┌────────────────────────────────────────────────────────┐
│  REENA CHANDRA                                         │
│  Weight Loss Coach                                     │
│  <phone> · <email> · <club>                            │
├────────────────────────────────────────────────────────┤
│  Client   Asha Verma        Date   30 Aug 2026         │
│  Phone    98xxxxxxxx        Time   09:14 AM            │
│  Email    asha@example.com  Age    34 / Female         │
│                             Height 162 cm              │
├──────────────────┬───────────┬────────────┬────────────┤
│ Measurement      │ Reading   │ Status     │ Normal     │
├──────────────────┼───────────┼────────────┼────────────┤
│ Weight           │ 68.4 kg   │    —       │    —       │
│ Body fat         │ 28.1 %    │ Normal     │ 21.0–32.9 %│
│ Skeletal muscle  │ 31.0 %    │ High       │ 24.3–30.3 %│
│ BMI              │ 24.8      │ Overweight │ 18.0–22.9  │
│ Visceral fat     │ 8.5       │ Normal     │ 0.5–9.5    │
│ Resting metab.   │ 1420 kcal │    —       │    —       │
│ Body age         │ 41 years  │ +7 yrs     │ 34 actual  │
└──────────────────┴───────────┴────────────┴────────────┘
  Ranges shown are for a 34-year-old female.
  Measured on Omron HBF-702T. Not a medical diagnosis.

  Remarks ______________________________________________
  ______________________________________________________
```

Seven rows, fixed. Water % is **not** included: the HBF-702T does not measure it
(`OmronLib.Record` has no such field), so there is no honest value to print.

**Monochrome rendering.** The report is printed on a mono laser. Therefore:

- Palette is black and greys only: `#000000` text, `#666666` secondary text,
  `#E6E6E6` table header fill, `#CCCCCC` rules. No colour is emitted anywhere.
- Status is conveyed by the **word plus the adjacent range**, never by colour or
  fill. A reader must be able to interpret the sheet from a photocopy.
- Emphasis uses weight and rules, not hue.
- Grey fills stay ≥ 85% luminance so text over them survives toner variance.

**No app branding on the report.** The sheet belongs to the coaching practice, not to
the app. "openScale" — and the package id `com.health.openscale` — must appear
nowhere in the exported file. Three surfaces, all of which are easy to miss:

1. **Visible content.** The only attributions on the page are the coach masthead and
   the `Measured on Omron HBF-702T` line, which names the *scale*, not the software.
2. **PDF metadata.** `android.graphics.pdf.PdfDocument` exposes no API for the
   document info dictionary, so nothing is set deliberately — but the underlying Skia
   writer emits a `Producer` string of its own. The requirement is verified by
   assertion rather than by construction (see below).
3. **Suggested filename.** The SAF `CreateDocument` prompt is pre-filled with
   `<Client Name> - <dd MMM yyyy>.pdf`, e.g. `Asha Verma - 30 Aug 2026.pdf`. It must
   not be derived from the app name or package id.

Enforced by a test that scans the emitted PDF bytes and fails on any occurrence of
`openScale` (case-insensitive) or `com.health.openscale`. This is a guard against
regression as much as a check on the initial implementation: a future change to the
export path could reintroduce the name silently.

The app's own display name and package are unchanged — this constraint applies only
to the generated file.

**Vector graphics.** Any mark or logo is authored as **SVG**, converted to an Android
vector drawable (`res/drawable/*.xml`), and drawn onto the PDF canvas via
`VectorDrawable.draw(canvas)`. Canvas path operations are recorded as vector content
in the PDF, so output stays sharp at any print size and the file stays small. No
bitmaps, no rasterisation.

### 4.5 UI

`Routes.kt` drops `GRAPH`, `STATISTICS`, `INSIGHTS`, `TABLE_DRILLDOWN`,
`OVERVIEW_DRILLDOWN`, and most settings sub-routes.

**Home** (`ui/screen/home/`)
- A persistent four-button user switcher across the top. Selected button filled,
  others outlined. Always visible — not a dropdown, not a drawer.
- Selected person's latest reading in large type.
- One primary **Sync scale** button.

**History** (`ui/screen/history/`)
- Reverse-chronological list of that person's weigh-ins with a compact sparkline.
- Tap a row to view, edit or delete.

**Report** (`ui/screen/report/`)
- Pick which weigh-in (defaults to the most recent).
- Review the header fields.
- **Export PDF** — the selected single weigh-in, as specified above.
- **Export CSV** — unchanged from openScale: the selected person's *entire*
  measurement history, for spreadsheet use. The two exports deliberately differ in
  scope; the PDF is a client handout, the CSV is a data dump.

Both go through SAF.

**Settings** — one screen: the four people (rename, edit details, bind to scale slot),
coach profile, scale pairing, units, backup.

**Deleted:** `GraphScreen`, `StatisticsScreen`, `InsightsScreen`, the drill-down
screens, `MeasurementWidget`, `MeasurementWidgetConfigActivity`, and six of the eight
settings screens. Vico stays only for the History sparkline.

## 5. Data flow

```
HBF-702T EEPROM
   → OmronWlcHandler (GATT unlock → session → read slot ring buffer)
   → OmronLib.decodeRecord → Record
   → toMeasurement() → ScaleMeasurement          [now keeps bmi + bodyAge]
   → Room
   → ReportUseCases.build(userId, measurementId)
      ├→ ReferenceRanges.classify(...) per row   [age + sex aware]
      └→ ReportModel
   → PdfReportRenderer.render(model) → PdfDocument
   → SAF → file
```

The scale stores up to 30 records per slot and does not stream live weights; a sync
reads out whatever is new since the newest measurement already held for that user.

## 6. Error handling

| Case | Behaviour |
|---|---|
| Scale not found during scan | Snackbar naming the scale; sync button returns to idle. |
| User not yet bound to a slot | Existing `CHOOSE_USER` flow — previews each slot's newest record so the coach can identify the right one. Binding persists. |
| Scale walks away mid-transfer | Existing 8 s `RESPONSE_TIMEOUT_MS`; partial reads discarded, nothing written to the scale. |
| No new records | "No new measurements" — not an error. |
| Metric missing from a record | Row prints an em dash; the row is never omitted, so the sheet's shape is constant. |
| Client has no birth date or gender | Age/sex-dependent rows fall back to `Band.NONE` with a footnote, rather than guessing a band. |
| PDF write fails | Snackbar with the failure; no partial file left behind. |

## 7. Testing

TDD on the parts that carry risk. All JVM-runnable — Robolectric is already wired.

- `ReferenceRangesTest` — every band boundary for both sexes and every age bracket,
  including the exact cut-over values (a 33.0 % body fat reading for a 34-year-old
  woman is `HIGH`, not `NORMAL`). Highest-value tests in the suite: these numbers are
  printed on client-facing sheets.
- `PdfReportRendererTest` — column fitting, long-name truncation, em-dash rows,
  single-page guarantee, that **no non-greyscale colour is ever emitted**, and that
  the output bytes contain **no occurrence of `openScale` or `com.health.openscale`**
  (§4.4).
- `ReportModelTest` — assembly from a measurement, body-age delta, missing-metric
  handling, missing birth-date fallback.
- `OmronWlcHandlerTest` — extended to assert BMI and body age now survive
  `toMeasurement()`. This is a regression guard on the §4.2 fix.
- Room migration test — existing rows survive the `phone` / `email` addition.
- `ScaleFactoryTest` — an HBF-702T advertisement still resolves after the cull, and
  the registry holds exactly one handler.

Existing `OmronLibTest` must keep passing untouched; it is the proof the scale
protocol still works.

## 8. Risks

| Risk | Mitigation |
|---|---|
| Threshold numbers wrong | All in one file; flagged for verification against the scale's manual before practice use. |
| Deleting 90 files breaks a shared helper | Compile after each deletion batch; the test suite is the safety net. |
| Room migration data loss | Migration test with a populated pre-migration DB. |
| Grey fills print too dark | Constrain to ≥ 85% luminance; verify on an actual mono print before sign-off. |
| Scale "guest" mode | Guest readings are not stored in a slot and cannot be synced. Documented for the coach; not a code problem. |

## 9. Open items

- **Verify every threshold in `ReferenceRanges.kt` against the HBF-702T manual.**
- Club name for the coach profile — currently unset.
- Licence: the fork remains GPL-3. If it is ever distributed beyond the practice,
  source must be offered alongside it.
