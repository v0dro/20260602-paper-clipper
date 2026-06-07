package com.example.paperclipper.data

import android.content.Context
import android.os.Environment
import java.io.File

/** Directory holding the saved clipping images. Shared by the UI (saving) and the repository. */
fun clippingsDir(context: Context): File =
    File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "clippings")
        .apply { mkdirs() }

/** Lists the clipping image files (jpg/png), newest first. */
fun listClippingFiles(context: Context): List<File> =
    clippingsDir(context)
        .listFiles { f ->
            f.isFile && (
                f.extension.equals("jpg", ignoreCase = true) ||
                    f.extension.equals("png", ignoreCase = true)
            )
        }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

fun mimeTypeFor(file: File): String =
    if (file.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"
