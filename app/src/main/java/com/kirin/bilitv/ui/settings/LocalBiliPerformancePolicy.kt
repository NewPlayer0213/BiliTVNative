package com.kirin.bilitv.ui.settings

import com.kirin.bilitv.ui.theme.BiliRadius
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.staticCompositionLocalOf
import com.kirin.bilitv.core.settings.AppPerformancePolicy

val LocalBiliPerformancePolicy = staticCompositionLocalOf {
  AppPerformancePolicy.Standard
}

/**
 * With motion off the card bakes its top corners into the cover bitmap instead of clipping every frame; the
 * card and the thumbnail prefetcher must ask for the same radius, or their memory-cache keys differ.
 */
fun AppPerformancePolicy.bakedCoverCornerRadiusPx(density: Density): Float {
  return if (motionEnabled) 0f else with(density) { BiliRadius.Card.toPx() }
}
