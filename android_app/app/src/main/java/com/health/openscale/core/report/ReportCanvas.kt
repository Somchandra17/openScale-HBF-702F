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

/** A run of text's size, colour and weight. No Android type — kept JVM-pure. */
data class TextStyle(val size: Float, val colour: Int, val bold: Boolean = false)

/**
 * The drawing surface [PdfReportRenderer]'s layout logic ([PdfReportRenderer.draw]) draws onto.
 *
 * Deliberately free of any Android type. That is what lets the layout — column widths,
 * row order, the ellipsize fallback, the palette — be driven and asserted
 * against a plain JVM fake in tests, with no Robolectric involved.
 *
 * [android.graphics.pdf.PdfDocument] is Skia-backed with no shadow in this project's
 * Robolectric version (its native methods are un-shadowed and default to zeroed state,
 * see `PdfReportRenderer.render`'s doc comment), so nothing that depends on it can be
 * exercised on the JVM. Splitting the surface out here is what keeps the *layout* testable
 * even though the real *rendering* is not.
 */
interface ReportCanvas {
    fun drawText(text: String, x: Float, y: Float, style: TextStyle)
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, colour: Int)
    fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, colour: Int)
    fun measureText(text: String, style: TextStyle): Float
    /** [key] is [ReportArtwork.LOGO] or [ReportArtwork.FOOTER]; size is already decided by the layout. */
    fun drawImage(key: String, left: Float, top: Float, width: Float, height: Float)
}
