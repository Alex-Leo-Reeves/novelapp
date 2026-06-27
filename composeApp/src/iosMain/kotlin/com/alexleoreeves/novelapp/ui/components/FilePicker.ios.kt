package com.alexleoreeves.novelapp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
actual fun FilePicker(
    show: Boolean,
    onFileSelected: (name: String, content: String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return
    LaunchedEffect(Unit) {
        // Stub implementation for iOS compiling
        onFileSelected("mock_ios_doc.txt", "This is simulated text imported from iOS Document Picker.")
    }
}
