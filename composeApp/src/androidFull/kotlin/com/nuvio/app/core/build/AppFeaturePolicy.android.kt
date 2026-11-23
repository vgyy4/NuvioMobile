package com.nuvio.app.core.build

actual object AppFeaturePolicy {
    actual val pluginsEnabled: Boolean = true
    actual val supportersContributorsPageEnabled: Boolean = true
    actual val donationActionsEnabled: Boolean = true
    actual val donationProgressEnabled: Boolean = false
    actual val accountDeletionEnabled: Boolean = false
    actual val personalMediaAddonCopyEnabled: Boolean = false
    actual val p2pEnabled: Boolean = true
    actual val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.IN_APP
    actual val heroTrailerPlaybackSupported: Boolean = true
    // The updater always checks NuvioMedia's own upstream releases
    // (AppUpdaterRepository hardcodes github.com/NuvioMedia/NuvioMobile), regardless of
    // which fork built this APK. On a modified fork that's actively wrong: it would
    // offer to replace a customized build with upstream's unmodified one. iosFull
    // already disables this for the same reason; do the same here.
    actual val inAppUpdaterEnabled: Boolean = false
    actual val imdbRatingLogoEnabled: Boolean = true
    actual val mediaPlaybackForegroundServiceEnabled: Boolean = true
    actual val downloadForegroundServiceEnabled: Boolean = true
    actual val customServerConnectionsEnabled: Boolean = true
}
