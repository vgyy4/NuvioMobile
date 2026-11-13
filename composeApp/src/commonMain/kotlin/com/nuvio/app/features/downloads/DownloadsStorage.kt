package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPayload(): String?
    /** The saved downloads of another profile (every profile keeps its own list). */
    fun loadPayload(profileId: Int): String?
    fun savePayload(payload: String)
}
