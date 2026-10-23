package com.kirin.bilitv.core.settings

import com.kirin.bilitv.core.i18n.ChineseTextVariant
import com.kirin.bilitv.core.model.HomeSection
import com.kirin.bilitv.core.player.PlaybackCodecPreference
import com.kirin.bilitv.core.player.PlaybackQualityPreference

enum class AppVisualPerformanceMode(val key: String) {
  Smooth("smooth"),
  Balanced("balanced"),
  Refined("refined");

  companion object {
    fun fromKey(key: String?): AppVisualPerformanceMode {
      return entries.firstOrNull { mode -> mode.key == key } ?: Balanced
    }
  }
}

enum class HomeThemeVariant(val key: String) {
  Pink("pink"),
  Black("black"),
  Gray("gray"),
  BlueGray("blue_gray");

  companion object {
    fun fromKey(key: String?): HomeThemeVariant {
      return entries.firstOrNull { theme -> theme.key == key } ?: Pink
    }
  }
}

data class AppSettings(
  val visualPerformanceMode: AppVisualPerformanceMode = AppVisualPerformanceMode.Balanced,
  val homeThemeVariant: HomeThemeVariant = HomeThemeVariant.Pink,
  val chineseTextVariant: ChineseTextVariant = ChineseTextVariant.Simplified,
  val playbackQualityPreference: PlaybackQualityPreference = PlaybackQualityPreference.Highest,
  val playbackCodecPreference: PlaybackCodecPreference = PlaybackCodecPreference.Auto,
  val seekPreviewSpritesEnabled: Boolean = true,
  val airJumpAssistantEnabled: Boolean = true,
  val confirmPlaybackExit: Boolean = true,
  val autoPlayNextEpisode: Boolean = false,
  val autoPlayRelatedVideo: Boolean = false,
  val autoReturnHomeOnCompletion: Boolean = false,
  val showClock: Boolean = true,
  val showMiniProgressBar: Boolean = true,
  val autoConfirmOnFocus: Boolean = false,
  val autoRefreshOnSwitch: Boolean = false,
  val liquidGlassCardsEnabled: Boolean = false,
  val enabledHomeSections: Set<HomeSection> = HomeSection.DefaultOrder.toSet(),
) {
  val lowSpecMode: Boolean
    get() = visualPerformanceMode == AppVisualPerformanceMode.Smooth
}

data class AppPerformancePolicy(
  val lowSpecMode: Boolean,
  val visualPerformanceMode: AppVisualPerformanceMode,
  val motionEnabled: Boolean,
  val smoothScrollingEnabled: Boolean,
  val videoThumbnailWidthPx: Int,
  val videoThumbnailHeightPx: Int,
  val videoThumbnailRgb565Enabled: Boolean,
  val ownerAvatarSizePx: Int,
  val ownerAvatarRgb565Enabled: Boolean,
  val imageMemoryCacheEnabled: Boolean,
  val videoThumbnailPrefetchCount: Int,
  val focusShadowEnabled: Boolean,
  val loadMoreFocusThreshold: Int,
  val focusedCoverBlurEnabled: Boolean,
  val refinedVisualEffectsEnabled: Boolean,
  val cinematicVisualEffectsEnabled: Boolean,
  val liquidGlassCardsEnabled: Boolean,
) {
  companion object {
    val Balanced = AppPerformancePolicy(
      lowSpecMode = false,
      visualPerformanceMode = AppVisualPerformanceMode.Balanced,
      motionEnabled = true,
      smoothScrollingEnabled = true,
      videoThumbnailWidthPx = 640,
      videoThumbnailHeightPx = 360,
      videoThumbnailRgb565Enabled = false,
      ownerAvatarSizePx = 96,
      ownerAvatarRgb565Enabled = false,
      imageMemoryCacheEnabled = true,
      videoThumbnailPrefetchCount = 24,
      focusShadowEnabled = true,
      loadMoreFocusThreshold = 18,
      focusedCoverBlurEnabled = false,
      refinedVisualEffectsEnabled = true,
      cinematicVisualEffectsEnabled = false,
      liquidGlassCardsEnabled = false,
    )

    val Refined = AppPerformancePolicy(
      lowSpecMode = false,
      visualPerformanceMode = AppVisualPerformanceMode.Refined,
      motionEnabled = true,
      smoothScrollingEnabled = true,
      videoThumbnailWidthPx = 640,
      videoThumbnailHeightPx = 360,
      videoThumbnailRgb565Enabled = false,
      ownerAvatarSizePx = 96,
      ownerAvatarRgb565Enabled = false,
      imageMemoryCacheEnabled = true,
      videoThumbnailPrefetchCount = 24,
      focusShadowEnabled = true,
      loadMoreFocusThreshold = 18,
      focusedCoverBlurEnabled = true,
      refinedVisualEffectsEnabled = true,
      cinematicVisualEffectsEnabled = true,
      liquidGlassCardsEnabled = false,
    )

    val LowSpec = AppPerformancePolicy(
      lowSpecMode = true,
      visualPerformanceMode = AppVisualPerformanceMode.Smooth,
      motionEnabled = false,
      smoothScrollingEnabled = false,
      // At the size the card draws them (about 480 px wide at 1080p), so the baked corners keep their radius.
      // Baked corners and circles are transformations, which make Coil decode ARGB_8888 (RGB_565 cannot hold
      // the transparent corners), about 500 KB a cover: the RGB_565 switches would be dead, so they are off.
      videoThumbnailWidthPx = 480,
      videoThumbnailHeightPx = 270,
      videoThumbnailRgb565Enabled = false,
      ownerAvatarSizePx = 48,
      ownerAvatarRgb565Enabled = false,
      // Covers come from the CDN with a few hundred ms of latency: keep the decoded ones and fetch four rows
      // past the focus, or a scan keeps meeting grey tiles.
      imageMemoryCacheEnabled = true,
      videoThumbnailPrefetchCount = 16,
      focusShadowEnabled = false,
      // Pages hold 20; asking for the next one 6 items from the end left the grid waiting on the network.
      loadMoreFocusThreshold = 18,
      focusedCoverBlurEnabled = false,
      refinedVisualEffectsEnabled = false,
      cinematicVisualEffectsEnabled = false,
      liquidGlassCardsEnabled = false,
    )

    private val ConstrainedTv = Balanced.copy(
      videoThumbnailWidthPx = 480,
      videoThumbnailHeightPx = 270,
      videoThumbnailRgb565Enabled = true,
      ownerAvatarSizePx = 72,
      ownerAvatarRgb565Enabled = true,
      videoThumbnailPrefetchCount = 8,
      focusShadowEnabled = false,
      loadMoreFocusThreshold = 8,
      focusedCoverBlurEnabled = false,
      refinedVisualEffectsEnabled = false,
      cinematicVisualEffectsEnabled = false,
    )

    val Standard = Balanced

    fun fromSettings(
      settings: AppSettings,
      constrainedTvUi: Boolean = false,
    ): AppPerformancePolicy {
      if (settings.visualPerformanceMode == AppVisualPerformanceMode.Smooth) {
        return LowSpec
      }
      if (constrainedTvUi) {
        return when (settings.visualPerformanceMode) {
          AppVisualPerformanceMode.Smooth -> LowSpec
          AppVisualPerformanceMode.Balanced -> ConstrainedTv.copy(visualPerformanceMode = AppVisualPerformanceMode.Balanced)
          AppVisualPerformanceMode.Refined -> Refined.copy(liquidGlassCardsEnabled = settings.liquidGlassCardsEnabled)
        }
      }
      return when (settings.visualPerformanceMode) {
        AppVisualPerformanceMode.Smooth -> LowSpec
        AppVisualPerformanceMode.Balanced -> Balanced
        AppVisualPerformanceMode.Refined -> Refined.copy(liquidGlassCardsEnabled = settings.liquidGlassCardsEnabled)
      }
    }
  }
}
