package com.alexleoreeves.novelapp.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class SherpaModelManager(private val context: Context) {

    // Using VCTK model as requested, which supports multiple speakers
    private val modelDirName = "vits-piper-en_GB-vctk-medium"

    fun isModelReady(): Boolean {
        val targetDir = File(context.filesDir, modelDirName)
        val modelFile = File(targetDir, "en_GB-vctk-medium.onnx")
        val tokensFile = File(targetDir, "tokens.txt")
        val dataDir = File(targetDir, "espeak-ng-data")
        return modelFile.exists() && tokensFile.exists() && dataDir.exists()
    }

    suspend fun prepareModel(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            onProgress(100)
            return@withContext true
        }

        try {
            val targetDir = File(context.filesDir, modelDirName)
            if (!targetDir.exists()) targetDir.mkdirs()

            val archiveFile = File(context.filesDir, "$modelDirName.zip")

            // 1. Copy from assets
            context.assets.open("$modelDirName.zip").use { input ->
                FileOutputStream(archiveFile).use { output ->
                    val data = ByteArray(4096)
                    val fileLength = input.available().toLong()
                    var total: Long = 0
                    var count: Int

                    while (input.read(data).also { count = it } != -1) {
                        total += count
                        if (fileLength > 0) {
                            // allocate 0-80% for copy
                            onProgress(((total * 80) / fileLength).toInt())
                        }
                        output.write(data, 0, count)
                    }
                }
            }

            // 2. Extract
            onProgress(85)
            extractZip(archiveFile, context.filesDir)
            
            // 3. Cleanup archive
            archiveFile.delete()
            onProgress(100)
            
            return@withContext isModelReady()
        } catch (e: Throwable) {
            e.printStackTrace()
            return@withContext false
        }
    }

    private fun extractZip(archiveFile: File, destDir: File) {
        ZipInputStream(archiveFile.inputStream()).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                val destPath = File(destDir, entry.name)
                // Protect against Zip Slip vulnerabilities
                if (!destPath.canonicalPath.startsWith(destDir.canonicalPath)) {
                    throw SecurityException("Invalid zip entry path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    destPath.mkdirs()
                } else {
                    destPath.parentFile?.mkdirs()
                    FileOutputStream(destPath).use { out ->
                        val buffer = ByteArray(4096)
                        var len: Int
                        while (zipIn.read(buffer).also { len = it } != -1) {
                            out.write(buffer, 0, len)
                        }
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }

    fun getModelPath(): String {
        return File(context.filesDir, "$modelDirName/en_GB-vctk-medium.onnx").absolutePath
    }

    fun getTokensPath(): String {
        return File(context.filesDir, "$modelDirName/tokens.txt").absolutePath
    }

    fun getDataDir(): String {
        return File(context.filesDir, "$modelDirName/espeak-ng-data").absolutePath
    }
}
