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

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
 * Status colour is an extra signal on top of the word: High/Low also print bold so a
 * monochrome laser still carries the verdict.
 */
object ReportArtwork {
    const val LOGO = "logo"
    const val FOOTER = "footer"

    fun load(assets: AssetManager): Map<String, ByteArray> = mapOf(
        LOGO to assets.open("report/logo.png").use { it.readBytes() },
        FOOTER to assets.open("report/footer.png").use { it.readBytes() },
    )
}

object PdfReportRenderer {

    // A4 at 72 dpi.
    const val PAGE_W = 595
    const val PAGE_H = 842
    const val MARGIN = 40f

    // Cropped pixel aspects of assets/report/{logo,footer}.png.
    const val LOGO_ASPECT_W_OVER_H = 1122f / 1156f
    const val FOOTER_ASPECT_W_OVER_H = 2055f / 747f
    const val LOGO_H = 56f
    val LOGO_W = LOGO_H * LOGO_ASPECT_W_OVER_H
    const val CLUB_SIZE = 22f
    const val COACH_NAME_SIZE = 12f

    // Not `const val`: 0xFF000000.toInt() is not a compile-time constant in Kotlin.
    val INK = 0xFF000000.toInt()
    val INK_SOFT = 0xFF666666.toInt()
    val HEADER_FILL = 0xFFE6E6E6.toInt()
    val RULE = 0xFFCCCCCC.toInt()
    val STATUS_HIGH = 0xFF8B1A1A.toInt()
    val STATUS_LOW = 0xFF0D47A1.toInt()

    /** Greyscale inks used for everything except abnormal status. */
    val GREY_PALETTE = listOf(INK, INK_SOFT, HEADER_FILL, RULE)
    val PALETTE = GREY_PALETTE + listOf(STATUS_HIGH, STATUS_LOW)

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
        val pageUsableWidth = PAGE_W - 2 * MARGIN
        val tableRight = PAGE_W - MARGIN
        val footerW = pageUsableWidth
        val footerH = footerW / FOOTER_ASPECT_W_OVER_H
        val footerTop = PAGE_H - MARGIN - footerH

        // -- Masthead: logo + club name as the H1, coach identity underneath ------
        val mastheadTop = MARGIN
        canvas.drawImage(ReportArtwork.LOGO, MARGIN, mastheadTop, LOGO_W, LOGO_H)
        val textX = MARGIN + LOGO_W + 10f
        val textMax = (PAGE_W - MARGIN) - textX
        var textY = mastheadTop + 22f
        val clubStyle = TextStyle(CLUB_SIZE, INK, bold = true)
        if (m.coach.club.isNotBlank()) {
            canvas.drawText(ellipsize(m.coach.club, clubStyle, textMax, canvas), textX, textY, clubStyle)
            textY += 16f
        }
        val nameStyle = TextStyle(COACH_NAME_SIZE, INK, bold = false)
        canvas.drawText(ellipsize(m.coach.name, nameStyle, textMax, canvas), textX, textY, nameStyle)
        textY += 14f
        val titleStyle = TextStyle(11f, INK_SOFT)
        canvas.drawText(ellipsize(m.coach.title, titleStyle, textMax, canvas), textX, textY, titleStyle)
        textY += 13f
        val contactStyle = TextStyle(9f, INK_SOFT)
        val contactLine = listOf(m.coach.phone, m.coach.email).filter { it.isNotBlank() }.joinToString(" · ")
        if (contactLine.isNotBlank()) {
            canvas.drawText(ellipsize(contactLine, contactStyle, textMax, canvas), textX, textY, contactStyle)
            textY += 12f
        }
        var y = maxOf(mastheadTop + LOGO_H + 14f, textY + 6f)
        rule(canvas, y)
        y += 20f

        // -- Client block ---------------------------------------------------------
        val col2 = PAGE_W / 2f
        val labelStyle = TextStyle(9f, INK_SOFT)
        val valueStyle = TextStyle(10f, INK)
        val sexLabel = m.client.gender.name.lowercase().replaceFirstChar { it.uppercase() }
        val ageKnown = m.client.ageYears != CLIENT_AGE_UNKNOWN
        val ageLabel = if (ageKnown) "${m.client.ageYears}" else "—"
        val infoRows = listOf(
            InfoRow("Client", m.client.name, "Date", m.measuredAt.format(DATE_FMT)),
            InfoRow("Phone", m.client.phone, "Time", m.measuredAt.format(TIME_FMT)),
            InfoRow("Email", m.client.email, "Age", "$ageLabel / $sexLabel"),
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
            y += 16f
        }
        y += 12f

        // -- Measurement table ----------------------------------------------------
        val cols = floatArrayOf(MARGIN, MARGIN + 150f, MARGIN + 250f, MARGIN + 360f)
        val headerH = 22f

        canvas.drawRect(MARGIN, y - 14f, tableRight, y - 14f + headerH, HEADER_FILL)
        val headStyle = TextStyle(9f, INK, bold = true)
        canvas.drawText("Measurement", cols[0] + 4f, y, headStyle)
        canvas.drawText("Reading", cols[1], y, headStyle)
        canvas.drawText("Status", cols[2], y, headStyle)
        canvas.drawText("Normal range", cols[3], y, headStyle)
        y += headerH

        val rangeStyle = TextStyle(9f, INK_SOFT)
        val colWidths = floatArrayOf(
            cols[1] - (cols[0] + 4f),
            cols[2] - cols[1],
            cols[3] - cols[2],
            tableRight - cols[3],
        )
        m.rows.forEach { r ->
            val statusStyle = statusStyle(r.band)
            canvas.drawText(ellipsize(r.label, valueStyle, colWidths[0], canvas), cols[0] + 4f, y, valueStyle)
            canvas.drawText(ellipsize(r.reading, valueStyle, colWidths[1], canvas), cols[1], y, valueStyle)
            canvas.drawText(ellipsize(r.status, statusStyle, colWidths[2], canvas), cols[2], y, statusStyle)
            canvas.drawText(ellipsize(r.normalRange, rangeStyle, colWidths[3], canvas), cols[3], y, rangeStyle)
            y += 10f
            rule(canvas, y)
            y += 16f
        }

        // -- Footnotes ------------------------------------------------------------
        y += 10f
        val noteStyle = TextStyle(8f, INK_SOFT)
        val ageSexNote = if (ageKnown) {
            val sex = m.client.gender.name.lowercase()
            "Ranges shown are for a ${m.client.ageYears}-year-old $sex."
        } else {
            "Client age not on file — age- and sex-based ranges are not shown."
        }
        canvas.drawText(ageSexNote, MARGIN, y, noteStyle)
        y += 12f
        canvas.drawText("Measured on ${m.deviceName}. Not a medical diagnosis.", MARGIN, y, noteStyle)

        // -- Remarks: kept above the footer so there is room to write --------------
        y += 24f
        canvas.drawText("Remarks", MARGIN, y, TextStyle(11f, INK, bold = true))
        y += 8f
        repeat(3) {
            y += 20f
            if (y < footerTop - 8f) {
                canvas.drawLine(MARGIN + 70f, y, tableRight, y, RULE)
            }
        }

        canvas.drawImage(ReportArtwork.FOOTER, MARGIN, footerTop, footerW, footerH)
    }

    private fun statusStyle(band: Band): TextStyle = when (band) {
        Band.HIGH, Band.VERY_HIGH -> TextStyle(10f, STATUS_HIGH, bold = true)
        Band.LOW -> TextStyle(10f, STATUS_LOW, bold = true)
        else -> TextStyle(10f, INK, bold = false)
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
    fun render(model: ReportModel, artwork: Map<String, ByteArray> = emptyMap()): ByteArray {
        val bitmaps = HashMap<String, Bitmap>()
        artwork.forEach { (key, bytes) ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmaps[key] = it }
        }
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        draw(model, AndroidReportCanvas(page.canvas, bitmaps))
        doc.finishPage(page)

        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    /** Maps [ReportCanvas] calls onto a real Android [Canvas]/[Paint]. See [render]. */
    private class AndroidReportCanvas(
        private val canvas: Canvas,
        private val bitmaps: Map<String, Bitmap> = emptyMap(),
    ) : ReportCanvas {

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

        override fun drawImage(key: String, left: Float, top: Float, width: Float, height: Float) {
            val bmp = bitmaps[key] ?: return
            canvas.drawBitmap(
                bmp,
                null,
                RectF(left, top, left + width, top + height),
                Paint().apply { isAntiAlias = true; isFilterBitmap = true },
            )
        }

        private fun paint(style: TextStyle) = Paint().apply {
            isAntiAlias = true
            textSize = style.size
            color = style.colour
            typeface = Typeface.create(Typeface.SANS_SERIF, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
