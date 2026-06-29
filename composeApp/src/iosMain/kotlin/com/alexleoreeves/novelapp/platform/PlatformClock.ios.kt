@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.alexleoreeves.novelapp.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

actual fun currentTimeMillis(): Long = memScoped {
    val now = alloc<timeval>()
    gettimeofday(now.ptr, null)
    now.tv_sec * 1000L + now.tv_usec / 1000L
}
