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
package com.health.openscale.ui.screen.report

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes a report into cache and hands it to the system share sheet (WhatsApp, Drive, Files).
 */
object ReportShare {

    fun cacheFile(context: Context, fileName: String): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val safe = fileName.filterNot { it in "/\\:*?\"<>|" }.ifBlank { "report" }
        return File(dir, safe)
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.file_provider", file)

    fun writeBytes(context: Context, fileName: String, bytes: ByteArray): Uri {
        val file = cacheFile(context, fileName)
        file.writeBytes(bytes)
        return uriFor(context, file)
    }

    fun createEmpty(context: Context, fileName: String): Uri {
        val file = cacheFile(context, fileName)
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        return uriFor(context, file)
    }

    fun share(context: Context, uri: Uri, mimeType: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("No app available to share this file", e)
        }
    }
}
