package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPayload(): String?
    /** The saved downloads of another profile (every profile keeps its own list). */
    fun loadPayload(profileId: Int): String?
    fun savePayload(payload: String)
    /** Saves into another profile's list (a download of that profile finished while another one is active). */
    fun savePayload(profileId: Int, payload: String)
}
