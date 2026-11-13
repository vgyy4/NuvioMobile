package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.memory.MemoryCache

// Decoded images are what fills memory. Devices with <= 2.5 GiB RAM (iPad Air 2, iPhone 6s/7/8) get
// an explicit cap; other devices keep the library default.
private const val LOW_END_IMAGE_MEMORY_CACHE_BYTES = 96L * 1024L * 1024L

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    if (DevicePerformance.isLowEnd) {
        memoryCache { MemoryCache.Builder().maxSizeBytes(LOW_END_IMAGE_MEMORY_CACHE_BYTES).build() }
    } else {
        this
    }

internal actual val platformProvidesImageLoader: Boolean = false