package com.alexleoreeves.novelapp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import javax.swing.JFileChooser

@Composable
actual fun FilePicker(
    show: Boolean,
    onFileSelected: (name: String, content: String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return

    LaunchedEffect(show) {
        val chooser = JFileChooser()
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            val selectedFile = chooser.selectedFile
            runCatching {
                onFileSelected(selectedFile.name, selectedFile.readText())
            }.onFailure {
                onFileSelected("Error", "Could not read file: ${it.message}")
            }
        } else {
            onDismiss()
        }
    }
}
