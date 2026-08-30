package com.health.openscale.core.report

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.GenderType
import org.junit.Test
import java.time.LocalDateTime

/**
 * Drives [PdfReportRenderer.draw] directly against a recording [ReportCanvas] fake — a
 * plain JVM object, no Robolectric.
 *
 * [PdfReportRenderer.render] (the real, Skia-backed [android.graphics.pdf.PdfDocument]
 * adapter) is deliberately not exercised here: Robolectric has no shadow for
 * `PdfDocument`'s native methods in this project's Robolectric version — decompiling the
 * actual class it loads for `@Config(sdk = [34])` showed `nativeCreateDocument()` is
 * un-shadowed and returns `0`, so `mNativeDocument` stays `0` and every call reports
 * "document is closed!" before any drawing happens (see the Task 6 report for the full
 * trace). Splitting the layout out behind [ReportCanvas] is what lets these tests verify
 * real layout behaviour — row order, the dash fallback, the ellipsize fallback, the
 * greyscale-only palette, the no-branding guarantee — without a device or an emulator.
 * `render()`'s real rendering is verified once by running the app, tracked on the
 * sign-off list.
 */
class PdfReportRendererTest {

    // Mirrors PdfReportRenderer.draw's private layout geometry so a test can address a
    // specific cell. If the renderer's column geometry changes, these move with it.
    private val margin = 42f
    private val col2 = PdfReportRenderer.PAGE_W / 2f
    private val labelColX = margin + 4f
    private val readingColX = margin + 150f
    private val statusColX = margin + 250f
    private val rangeColX = margin + 360f
    private val pageUsableWidth = PdfReportRenderer.PAGE_W - 2 * margin
    private val tableRight = PdfReportRenderer.PAGE_W - margin
    private val rangeColWidth = tableRight - rangeColX

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

    private sealed class DrawCall {
        data class Text(val text: String, val x: Float, val y: Float, val style: TextStyle) : DrawCall()
        data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float, val colour: Int) : DrawCall()
        data class Line(val startX: Float, val startY: Float, val stopX: Float, val stopY: Float, val colour: Int) : DrawCall()
    }

    /**
     * Records every call instead of drawing. [measureText] is a fixed width per
     * character per point of font size — deterministic across machines, unlike real
     * glyph metrics, so ellipsize/overflow assertions do not depend on the font available
     * wherever the test runs.
     */
    private class RecordingCanvas : ReportCanvas {
        val calls = mutableListOf<DrawCall>()

        override fun drawText(text: String, x: Float, y: Float, style: TextStyle) {
            calls += DrawCall.Text(text, x, y, style)
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, colour: Int) {
            calls += DrawCall.Rect(left, top, right, bottom, colour)
        }

        override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, colour: Int) {
            calls += DrawCall.Line(startX, startY, stopX, stopY, colour)
        }

        override fun measureText(text: String, style: TextStyle): Float = text.length * style.size * 0.5f

        val texts get() = calls.filterIsInstance<DrawCall.Text>()
    }

    private fun recordDraw(model: ReportModel = model()): RecordingCanvas =
        RecordingCanvas().also { PdfReportRenderer.draw(model, it) }

    @Test
    fun `draws seven rows in order with expected labels`() {
        val canvas = recordDraw()
        val expected = model().rows.map { it.label }
        // Non-bold distinguishes the row cells from the (also non-bold at other columns,
        // but bold here) table header sharing the same x.
        val drawnLabels = canvas.texts.filter { it.x == labelColX && !it.style.bold }.map { it.text }
        assertThat(drawnLabels).isEqualTo(expected)
    }

    @Test
    fun `an absent metric draws an em dash rather than being skipped`() {
        val dashedModel = model().copy(rows = ReportRowBuilder.build(emptyMap(), model().client))
        val canvas = recordDraw(dashedModel)

        val drawnLabels = canvas.texts.filter { it.x == labelColX && !it.style.bold }.map { it.text }
        assertThat(drawnLabels).hasSize(7) // every row still drawn, none skipped

        val readings = canvas.texts.filter { it.x == readingColX && !it.style.bold }.map { it.text }
        val statuses = canvas.texts.filter { it.x == statusColX && !it.style.bold }.map { it.text }
        val ranges = canvas.texts.filter { it.x == rangeColX && !it.style.bold }.map { it.text }
        assertThat(readings).containsExactlyElementsIn(List(7) { "—" })
        assertThat(statuses).containsExactlyElementsIn(List(7) { "—" })
        assertThat(ranges).containsExactlyElementsIn(List(7) { "—" })
    }

    @Test
    fun `an overlong client name is ellipsized to fit its column`() {
        // The real overflow check: the client name is the one field whose length the
        // coach's data entry doesn't bound, so it is the one place ellipsize must hold.
        val m = model().copy(client = model().client.copy(name = "A".repeat(200)))
        val canvas = recordDraw(m)
        val maxWidth = col2 - margin - 60f

        val nameCall = canvas.texts.first { it.x == margin + 55f && it.text.startsWith("A") }
        assertThat(canvas.measureText(nameCall.text, nameCall.style)).isAtMost(maxWidth)
        assertThat(nameCall.text.length).isLessThan(200) // really trimmed, not coincidentally short
    }

    @Test
    fun `an overlong coach name does not run off the masthead`() {
        val m = model().copy(coach = model().coach.copy(name = "A".repeat(200)))
        val canvas = recordDraw(m)
        val nameStyle = TextStyle(20f, PdfReportRenderer.INK, bold = true)

        val nameCall = canvas.texts.first { it.x == margin && it.style == nameStyle }
        assertThat(canvas.measureText(nameCall.text, nameCall.style)).isAtMost(pageUsableWidth)
        assertThat(nameCall.text.length).isLessThan(200) // really trimmed, not coincidentally short
    }

    @Test
    fun `an overlong club name does not run the contact line off the page`() {
        val m = model().copy(coach = model().coach.copy(club = "A".repeat(200)))
        val canvas = recordDraw(m)
        val contactStyle = TextStyle(9f, PdfReportRenderer.INK_SOFT)
        val untrimmedLength = m.coach.phone.length + 3 + m.coach.email.length + 3 + m.coach.club.length

        // Distinguished from the client block's same-style labels ("Client"/"Phone"/…)
        // by content: only the joined contact line carries the coach's phone number.
        val contactCall = canvas.texts.first { it.style == contactStyle && it.text.contains(m.coach.phone) }
        assertThat(canvas.measureText(contactCall.text, contactCall.style)).isAtMost(pageUsableWidth)
        assertThat(contactCall.text.length).isLessThan(untrimmedLength) // really trimmed
    }

    @Test
    fun `an overlong table cell does not run off its column`() {
        // Table risk is structurally low (labels come from ReportRowBuilder.SPECS and
        // readings are format-bounded) but normalRange is a built string, not fixed-width,
        // so it is the cell most likely to be handed something long.
        val m = model().copy(rows = listOf(ReportRow("Weight", "68.4 kg", "Normal", "A".repeat(200))))
        val canvas = recordDraw(m)
        val rangeStyle = TextStyle(9f, PdfReportRenderer.INK_SOFT)

        val rangeCall = canvas.texts.first { it.x == rangeColX && it.style == rangeStyle && it.text.startsWith("A") }
        assertThat(canvas.measureText(rangeCall.text, rangeCall.style)).isAtMost(rangeColWidth)
        assertThat(rangeCall.text.length).isLessThan(200) // really trimmed, not coincidentally short
    }

    @Test
    fun `all content stays within the A4 page bounds`() {
        val canvas = recordDraw()
        val pageW = PdfReportRenderer.PAGE_W.toFloat()
        val pageH = PdfReportRenderer.PAGE_H.toFloat()

        canvas.calls.forEach { call ->
            when (call) {
                is DrawCall.Text -> {
                    assertThat(call.x).isAtLeast(0f)
                    assertThat(call.x + canvas.measureText(call.text, call.style)).isAtMost(pageW)
                    assertThat(call.y).isAtLeast(0f)
                    assertThat(call.y).isAtMost(pageH)
                }
                is DrawCall.Rect -> {
                    assertThat(call.left).isAtLeast(0f)
                    assertThat(call.right).isAtMost(pageW)
                    assertThat(call.top).isAtLeast(0f)
                    assertThat(call.bottom).isAtMost(pageH)
                }
                is DrawCall.Line -> {
                    assertThat(call.startX).isAtLeast(0f)
                    assertThat(call.stopX).isAtMost(pageW)
                    assertThat(call.startY).isAtLeast(0f)
                    assertThat(call.stopY).isAtMost(pageH)
                }
            }
        }
    }

    @Test
    fun `every colour drawn is greyscale`() {
        val canvas = recordDraw()
        canvas.calls.forEach { call ->
            val colour = when (call) {
                is DrawCall.Text -> call.style.colour
                is DrawCall.Rect -> call.colour
                is DrawCall.Line -> call.colour
            }
            assertThat(isGrey(colour)).isTrue()
        }
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
    fun `no drawn string carries app branding`() {
        // Global constraint: the sheet is the coach's, not the app's. PdfDocument itself
        // never writes the package id into the file (Skia only emits its own Producer
        // string), so the one place this could leak in is a drawn string.
        val canvas = recordDraw()
        val allText = canvas.texts.joinToString(" ") { it.text }
        assertThat(allText.lowercase()).doesNotContain("openscale")
        assertThat(allText).doesNotContain("com.health.openscale")
    }

    private fun isGrey(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r == g && g == b
    }

    private fun luminance(argb: Int): Float = (argb and 0xFF) / 255f
}
