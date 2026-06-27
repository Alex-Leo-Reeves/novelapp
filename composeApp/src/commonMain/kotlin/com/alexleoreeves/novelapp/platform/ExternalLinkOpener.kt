package com.alexleoreeves.novelapp.platform

interface ExternalLinkOpener {
    fun open(url: String)
}

object NoOpExternalLinkOpener : ExternalLinkOpener {
    override fun open(url: String) = Unit
}
