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

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

/**
 * Draws a [ReportModel] onto a single A4 portrait page.
 *
 * The layout ([draw]) is pure input → output over the [ReportCanvas] abstraction: no
 * Android type appears in it, no Room, no Compose, no Context. That is what keeps the
 * layout unit-testable on the JVM with a plain recording fake, with no Robolectric
 * involved — see the doc comment on [render] for why that split exists.
 *
 * ## Printed in black and white
 * The practice prints on a mono laser, so the palette is greyscale only and status is
 * carried by the word plus the adjacent range — never by colour. The sheet must stay
 * readable as a photocopy.
 */
object PdfReportRenderer {

    // A4 at 72 dpi.
    const val PAGE_W = 595
    const val PAGE_H = 842
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

    /**
     * Draws [model] onto [canvas]. All layout decisions — column positions, row order,
     * the ellipsize fallback, which colour goes where — live here and only here. Pure
     * Kotlin: no Android import, so this is exercised on the JVM against a recording fake
     * with no Robolectric involved.
     */
    fun draw(model: ReportModel, canvas: ReportCanvas) {
        val m = model
        var y = MARGIN + 24f

        // -- Masthead: the coach's identity, never the app's ----------------------
        // Free text the coach types into their profile — unlike the measurement table,
        // nothing here is format-bounded, so every line is ellipsized to the page's
        // usable width rather than trusting it to be short.
        val pageUsableWidth = PAGE_W - 2 * MARGIN
        val nameStyle = TextStyle(20f, INK, bold = true)
        canvas.drawText(ellipsize(m.coach.name.uppercase(), nameStyle, pageUsableWidth, canvas), MARGIN, y, nameStyle)
        y += 18f
        val titleStyle = TextStyle(11f, INK_SOFT)
        canvas.drawText(ellipsize(m.coach.title, titleStyle, pageUsableWidth, canvas), MARGIN, y, titleStyle)
        y += 14f
        val contactStyle = TextStyle(9f, INK_SOFT)
        val contactLine = listOf(m.coach.phone, m.coach.email, m.coach.club).filter { it.isNotBlank() }.joinToString(" · ")
        canvas.drawText(ellipsize(contactLine, contactStyle, pageUsableWidth, canvas), MARGIN, y, contactStyle)
        y += 20f
        rule(canvas, y); y += 22f

        // -- Client block ---------------------------------------------------------
        val col2 = PAGE_W / 2f
        val labelStyle = TextStyle(9f, INK_SOFT)
        val valueStyle = TextStyle(10f, INK)
        val sexLabel = m.client.gender.name.lowercase().replaceFirstChar { it.uppercase() }
        val infoRows = listOf(
            InfoRow("Client", m.client.name, "Date", m.measuredAt.format(DATE_FMT)),
            InfoRow("Phone", m.client.phone, "Time", m.measuredAt.format(TIME_FMT)),
            InfoRow("Email", m.client.email, "Age", "${m.client.ageYears} / $sexLabel"),
            InfoRow("", "", "Height", String.format(L, "%.0f cm", m.client.heightCm)),
        )
        val leftValueMaxWidth = col2 - MARGIN - 60f
        val rightValueMaxWidth = (PAGE_W - MARGIN) - (col2 + 55f)
        infoRows.forEach { r ->
            if (r.leftLabel.isNotBlank()) {
                canvas.drawText(r.leftLabel, MARGIN, y, labelStyle)
                canvas.drawText(ellipsize(r.leftValue, valueStyle, leftValueMaxWidth, canvas), MARGIN + 55f, y, valueStyle)
            }
            canvas.drawText(r.rightLabel, col2, y, labelStyle)
            canvas.drawText(ellipsize(r.rightValue, valueStyle, rightValueMaxWidth, canvas), col2 + 55f, y, valueStyle)
            y += 15f
        }
        y += 10f

        // -- Measurement table ----------------------------------------------------
        val cols = floatArrayOf(MARGIN, MARGIN + 150f, MARGIN + 250f, MARGIN + 360f)
        val tableRight = PAGE_W - MARGIN
        val headerH = 20f

        canvas.drawRect(MARGIN, y - 13f, tableRight, y - 13f + headerH, HEADER_FILL)
        val headStyle = TextStyle(9f, INK, bold = true)
        canvas.drawText("Measurement", cols[0] + 4f, y, headStyle)
        canvas.drawText("Reading", cols[1], y, headStyle)
        canvas.drawText("Status", cols[2], y, headStyle)
        canvas.drawText("Normal range", cols[3], y, headStyle)
        y += headerH

        // Column widths for ellipsize: label/reading/status risk is low (fixed labels,
        // format-bounded readings) but normalRange is a built string, not fixed-width —
        // "low risk" is not "guarded", so every cell gets the same width bound.
        val rangeStyle = TextStyle(9f, INK_SOFT)
        val colWidths = floatArrayOf(
            cols[1] - (cols[0] + 4f),
            cols[2] - cols[1],
            cols[3] - cols[2],
            tableRight - cols[3],
        )
        m.rows.forEach { r ->
            canvas.drawText(ellipsize(r.label, valueStyle, colWidths[0], canvas), cols[0] + 4f, y, valueStyle)
            canvas.drawText(ellipsize(r.reading, valueStyle, colWidths[1], canvas), cols[1], y, valueStyle)
            canvas.drawText(ellipsize(r.status, valueStyle, colWidths[2], canvas), cols[2], y, valueStyle)
            canvas.drawText(ellipsize(r.normalRange, rangeStyle, colWidths[3], canvas), cols[3], y, rangeStyle)
            y += 8f
            rule(canvas, y)
            y += 14f
        }

        // -- Footnotes ------------------------------------------------------------
        y += 8f
        val noteStyle = TextStyle(8f, INK_SOFT)
        val sex = m.client.gender.name.lowercase()
        canvas.drawText("Ranges shown are for a ${m.client.ageYears}-year-old $sex.", MARGIN, y, noteStyle)
        y += 11f
        canvas.drawText("Measured on ${m.deviceName}. Not a medical diagnosis.", MARGIN, y, noteStyle)

        // -- Remarks: the only blank on the sheet ---------------------------------
        y += 30f
        canvas.drawText("Remarks", MARGIN, y, TextStyle(9f, INK, bold = true))
        y += 6f
        repeat(2) {
            y += 18f
            canvas.drawLine(MARGIN + 55f, y, tableRight, y, RULE)
        }
    }

    private fun rule(canvas: ReportCanvas, y: Float) =
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, RULE)

    /**
     * Trims to fit rather than letting a long name run into the next column.
     *
     * Structurally safe even if [maxWidth] is narrower than the ellipsis glyph itself
     * (not reachable at current geometry, but a magic-number layout tweak should not be
     * able to reintroduce overflow silently): in that case there is no width budget for
     * any character at all, so the field is dropped rather than emitting an oversized "…".
     */
    private fun ellipsize(text: String, style: TextStyle, maxWidth: Float, canvas: ReportCanvas): String {
        if (canvas.measureText(text, style) <= maxWidth) return text
        if (canvas.measureText("…", style) > maxWidth) return ""
        var s = text
        while (s.isNotEmpty() && canvas.measureText("$s…", style) > maxWidth) s = s.dropLast(1)
        return "$s…"
    }

    /**
     * Renders [model] to PDF bytes via the real, Skia-backed [PdfDocument].
     *
     * This is a thin adapter and makes no layout decisions of its own: it opens a page,
     * wraps the page's [Canvas] in [AndroidReportCanvas], delegates every decision to
     * [draw], and writes the result out. By design it is **not** covered by a JVM test:
     * [PdfDocument]'s native methods (`nativeCreateDocument` et al.) have no Robolectric
     * shadow in this project's Robolectric version, so under Robolectric
     * `mNativeDocument` stays `0` and every call reports "document is closed!" before any
     * drawing happens. That was confirmed by decompiling the actual class Robolectric
     * loads for `@Config(sdk = [34])` — see the Task 6 report for the full trace. The
     * split with [draw] is what lets the *layout* be tested even though this adapter, and
     * the real rendering, can only be verified by running the app on a device (tracked on
     * the sign-off list).
     */
    fun render(model: ReportModel): ByteArray {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        draw(model, AndroidReportCanvas(page.canvas))
        doc.finishPage(page)

        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    /** Maps [ReportCanvas] calls onto a real Android [Canvas]/[Paint]. See [render]. */
    private class AndroidReportCanvas(private val canvas: Canvas) : ReportCanvas {

        override fun drawText(text: String, x: Float, y: Float, style: TextStyle) {
            canvas.drawText(text, x, y, paint(style))
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, colour: Int) {
            canvas.drawRect(left, top, right, bottom, Paint().apply { color = colour })
        }

        override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, colour: Int) {
            canvas.drawLine(startX, startY, stopX, stopY, Paint().apply { color = colour; strokeWidth = 0.7f })
        }

        override fun measureText(text: String, style: TextStyle): Float = paint(style).measureText(text)

        private fun paint(style: TextStyle) = Paint().apply {
            isAntiAlias = true
            textSize = style.size
            color = style.colour
            typeface = Typeface.create(Typeface.SANS_SERIF, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
