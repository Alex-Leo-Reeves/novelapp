package com.alexleoreeves.novelapp.platform

import android.content.Context
import android.content.Intent
import android.net.Uri

class AndroidExternalLinkOpener(context: Context) : ExternalLinkOpener {
    private val appContext = context.applicationContext

    override fun open(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }
}
