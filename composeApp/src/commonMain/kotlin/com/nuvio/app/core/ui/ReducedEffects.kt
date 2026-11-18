package com.nuvio.app.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * True on 1 GB / A7-class devices (iPad mini 2): blur, drop shadows and decorative animations are
 * dropped there, because the GPU and the two CPU cores cannot carry them smoothly.
 */
internal val ReduceEffects: Boolean get() = DevicePerformance.isVeryLowEnd

/** A drop shadow, except where effects are reduced. */
internal fun Modifier.nuvioShadow(elevation: Dp, shape: Shape = RoundedCornerShape(0)): Modifier =
    if (ReduceEffects) this else shadow(elevation, shape)
