package com.kirin.bilitv.core.image

import android.content.Context
import com.kirin.bilitv.core.network.BiliHeaders
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation

object BiliImageSizing {
  const val StandardVideoThumbnailWidthPx = 480
  const val StandardVideoThumbnailHeightPx = 270
  const val StandardOwnerAvatarSizePx = 72
  const val AccountAvatarSizePx = 96
  const val AccountProfileAvatarSizePx = 192
}

fun buildVideoThumbnailRequest(
  context: Context,
  url: String,
  widthPx: Int = BiliImageSizing.StandardVideoThumbnailWidthPx,
  heightPx: Int = BiliImageSizing.StandardVideoThumbnailHeightPx,
  allowRgb565: Boolean = false,
  memoryCacheEnabled: Boolean = true,
  cornerRadiusPx: Float = 0f,
): ImageRequest {
  return ImageRequest.Builder(context)
    .data(url.biliCdnResizedImageUrl(widthPx, heightPx))
    // The card's top corners only: the cover meets the info strip flat, with no notch to paint under.
    .apply { if (cornerRadiusPx > 0f) transformations(RoundedCornersTransformation(topLeft = cornerRadiusPx, topRight = cornerRadiusPx)) }
    .addBiliImageHeaders()
    .size(widthPx, heightPx)
    .precision(Precision.INEXACT)
    .allowRgb565(allowRgb565)
    .memoryCachePolicy(if (memoryCacheEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED)
    .crossfade(false)
    .build()
}

fun buildOwnerAvatarRequest(
  context: Context,
  url: String,
  sizePx: Int = BiliImageSizing.StandardOwnerAvatarSizePx,
  allowRgb565: Boolean = false,
  memoryCacheEnabled: Boolean = true,
  circleCrop: Boolean = false,
): ImageRequest {
  return ImageRequest.Builder(context)
    .data(url.biliCdnResizedImageUrl(sizePx, sizePx))
    .apply { if (circleCrop) transformations(CircleCropTransformation()) }
    .addBiliImageHeaders()
    .size(sizePx, sizePx)
    .precision(Precision.INEXACT)
    .allowRgb565(allowRgb565)
    .memoryCachePolicy(if (memoryCacheEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED)
    .crossfade(false)
    .build()
}

fun String.biliCdnResizedImageUrl(
  widthPx: Int,
  heightPx: Int? = null,
): String {
  val normalized = normalizedBiliImageUrl()
  if (normalized.isBlank() || normalized.contains("@")) return normalized

  val suffix = if (heightPx != null) {
    "@${widthPx}w_${heightPx}h_1c.webp"
  } else {
    "@${widthPx}w.webp"
  }
  val queryStart = normalized.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: normalized.length
  return normalized.substring(0, queryStart) + suffix + normalized.substring(queryStart)
}

private fun String.normalizedBiliImageUrl(): String {
  val trimmed = trim()
  return when {
    trimmed.startsWith("//") -> "https:$trimmed"
    trimmed.startsWith("http://") -> "https://${trimmed.removePrefix("http://")}"
    else -> trimmed
  }
}

private fun ImageRequest.Builder.addBiliImageHeaders(): ImageRequest.Builder {
  return addHeader("User-Agent", BiliHeaders.UserAgent)
    .addHeader("Referer", BiliHeaders.Referer)
}
