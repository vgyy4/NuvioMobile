package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.memory.MemoryCache

// Decoded images are what fills memory. Devices with <= 2.5 GiB RAM (iPad Air 2, iPhone 6s/7/8) get
// an explicit cap; other devices keep the library default.
private const val LOW_END_IMAGE_MEMORY_CACHE_BYTES = 96L * 1024L * 1024L
private const val VERY_LOW_END_IMAGE_MEMORY_CACHE_BYTES = 40L * 1024L * 1024L

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    if (DevicePerformance.isLowEnd) {
        val bytes = if (DevicePerformance.isVeryLowEnd) {
            VERY_LOW_END_IMAGE_MEMORY_CACHE_BYTES
        } else {
            LOW_END_IMAGE_MEMORY_CACHE_BYTES
        }
        val builder = memoryCache { MemoryCache.Builder().maxSizeBytes(bytes).build() }
        if (DevicePerformance.isVeryLowEnd) {
            // One image is decoded and one fetched at a time: two A7 cores also run the UI and the player.
            builder
                .decoderCoroutineContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1))
                .fetcherCoroutineContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2))
        } else {
            builder
        }
    } else {
        this
    }

internal actual val platformProvidesImageLoader: Boolean = false