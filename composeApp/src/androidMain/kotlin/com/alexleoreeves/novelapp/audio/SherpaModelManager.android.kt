package com.alexleoreeves.novelapp.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class SherpaModelManager(private val context: Context) {

    // Using VCTK model as requested, which supports multiple speakers
    private val modelUrl = "https://github.com/k2fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-vctk-medium.tar.bz2"
    private val modelDirName = "vits-piper-en_GB-vctk-medium"

    fun isModelReady(): Boolean {
        val targetDir = File(context.filesDir, modelDirName)
        val modelFile = File(targetDir, "en_GB-vctk-medium.onnx")
        val tokensFile = File(targetDir, "tokens.txt")
        return modelFile.exists() && tokensFile.exists()
    }

    suspend fun prepareModel(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            onProgress(100)
            return@withContext true
        }

        try {
            val targetDir = File(context.filesDir, modelDirName)
            if (!targetDir.exists()) targetDir.mkdirs()

            val archiveFile = File(context.filesDir, "$modelDirName.tar.bz2")

            // 1. Download
            val connection = URL(modelUrl).openConnection() as HttpURLConnection
            connection.connect()

            val fileLength = connection.contentLength
            val input = connection.inputStream
            val output = FileOutputStream(archiveFile)

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int

            while (input.read(data).also { count = it } != -1) {
                total += count
                if (fileLength > 0) {
                    // allocate 0-80% for download
                    onProgress(((total * 80) / fileLength).toInt())
                }
                output.write(data, 0, count)
            }

            output.flush()
            output.close()
            input.close()

            // 2. Extract
            onProgress(85)
            extractTarBz2(archiveFile, context.filesDir)
            
            // 3. Cleanup archive
            archiveFile.delete()
            onProgress(100)
            
            return@withContext isModelReady()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    private fun extractTarBz2(archiveFile: File, destDir: File) {
        archiveFile.inputStream().use { fi ->
            BZip2CompressorInputStream(fi).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextTarEntry
                    while (entry != null) {
                        val destPath = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            destPath.mkdirs()
                        } else {
                            destPath.parentFile?.mkdirs()
                            FileOutputStream(destPath).use { out ->
                                val buffer = ByteArray(4096)
                                var len: Int
                                while (tarIn.read(buffer).also { len = it } != -1) {
                                    out.write(buffer, 0, len)
                                }
                            }
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
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
