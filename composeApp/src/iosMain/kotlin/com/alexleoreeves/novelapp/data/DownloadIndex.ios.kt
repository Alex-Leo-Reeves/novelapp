package com.alexleoreeves.novelapp.data

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

private fun documentsDirectory(): String =
    (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String)
        ?: ""

private fun downloadsDir(): String {
    val dir = "${documentsDirectory()}/downloads"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = dir,
        withIntermediateDirectories = true,
        attributes = null,
        error = null
    )
    return dir
}

actual fun readIndexJson(): String? {
    return try {
        NSString.stringWithContentsOfFile(
            path = "${downloadsDir()}/index.json",
            encoding = NSUTF8StringEncoding,
            error = null
        )?.toString()
    } catch (e: Exception) {
        null
    }
}

actual fun writeIndexJson(json: String) {
    runCatching {
        NSString.create(string = json).writeToFile(
            path = "${downloadsDir()}/index.json",
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null
        )
    }
}
