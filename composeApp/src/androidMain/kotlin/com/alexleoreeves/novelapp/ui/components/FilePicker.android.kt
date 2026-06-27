package com.alexleoreeves.novelapp.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun FilePicker(
    show: Boolean,
    onFileSelected: (name: String, content: String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val text = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                val name = uri.path?.substringAfterLast('/') ?: "imported_document.txt"
                onFileSelected(name, text)
            } catch (e: Exception) {
                onFileSelected("Error", "Could not read file: ${e.message}")
            }
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        launcher.launch("*/*")
    }
}
