package com.alexleoreeves.novelapp.platform

import platform.Foundation.NSDate

actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
