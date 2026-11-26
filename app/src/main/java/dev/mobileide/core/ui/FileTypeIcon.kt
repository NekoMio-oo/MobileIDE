package dev.mobileide.core.ui

import androidx.annotation.DrawableRes
import dev.mobileide.R
import dev.mobileide.domain.files.FileKind
import dev.mobileide.domain.files.FileType

/** Shared icon mapping for files, used by the browser and the editor tabs. */
@DrawableRes
fun fileTypeIcon(fileName: String, isDirectory: Boolean = false): Int {
    if (isDirectory) return R.drawable.ic_folder
    return when (FileType.fromFileName(fileName).kind) {
        FileKind.CODE -> R.drawable.ic_file_code
        FileKind.MARKUP -> R.drawable.ic_file_code
        FileKind.DATA -> R.drawable.ic_file_data
        FileKind.TEXT -> R.drawable.ic_file_text
        else -> R.drawable.ic_file
    }
}
