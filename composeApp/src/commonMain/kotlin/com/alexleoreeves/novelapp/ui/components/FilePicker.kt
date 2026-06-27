package com.alexleoreeves.novelapp.ui.components

import androidx.compose.runtime.Composable

/**
 * Platform-independent file picker interface.
 * When a file is selected, it returns the name and the parsed text content.
 */
@Composable
expect fun FilePicker(
    show: Boolean,
    onFileSelected: (name: String, content: String) -> Unit,
    onDismiss: () -> Unit
)
