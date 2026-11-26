package dev.mobileide.core.ui

import androidx.annotation.StringRes
import dev.mobileide.R
import dev.mobileide.domain.repository.EntryFailure
import dev.mobileide.domain.repository.ProjectFileError
import dev.mobileide.domain.validation.EntryNameError

@StringRes
fun projectFileErrorMessage(error: ProjectFileError): Int = when (error) {
    ProjectFileError.NOT_FOUND -> R.string.file_error_not_found
    ProjectFileError.NOT_A_FILE -> R.string.file_error_not_a_file
    ProjectFileError.PERMISSION_DENIED -> R.string.file_error_permission
    ProjectFileError.FILE_TOO_LARGE -> R.string.file_error_too_large
    ProjectFileError.BINARY -> R.string.file_error_binary
    ProjectFileError.INVALID_PATH -> R.string.file_error_invalid_path
    ProjectFileError.IO -> R.string.file_error_io
}

@StringRes
fun entryNameErrorMessage(error: EntryNameError): Int = when (error) {
    EntryNameError.EMPTY -> R.string.entry_name_empty
    EntryNameError.TOO_LONG -> R.string.entry_name_too_long
    EntryNameError.INVALID_CHARACTERS -> R.string.entry_name_invalid
    EntryNameError.RESERVED -> R.string.entry_name_reserved
}

@StringRes
fun entryFailureMessage(failure: EntryFailure): Int = when (failure) {
    EntryFailure.DUPLICATE -> R.string.explorer_error_duplicate
    EntryFailure.NOT_FOUND -> R.string.file_error_not_found
    EntryFailure.NOT_A_DIRECTORY -> R.string.explorer_error_not_a_folder
    EntryFailure.PERMISSION_DENIED -> R.string.explorer_error_permission
    EntryFailure.INVALID_PATH -> R.string.explorer_error_invalid_path
    EntryFailure.IO -> R.string.explorer_error_failed
}
