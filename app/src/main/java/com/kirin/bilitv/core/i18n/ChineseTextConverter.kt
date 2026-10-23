package com.kirin.bilitv.core.i18n

import android.icu.text.Transliterator
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.mutableStateOf
import com.github.houbb.opencc4j.util.ZhHkConverterUtil
import com.github.houbb.opencc4j.util.ZhTwConverterUtil
import kotlin.concurrent.thread

fun interface ChineseTextConverter {
  fun convert(text: String): String
}

object ChineseTextConverters {
  val Simplified = ChineseTextConverter { text -> text }

  /**
   * OpenCC reads its phrase dictionaries on the first conversion: several seconds on a TV CPU, and that used
   * to happen on the main thread while the home screen was being built. [warmUp] does it on a background
   * thread at process start. Until it is done, text goes through Android's own ICU transform (API 29 and
   * later; older devices show it as it came), which is ready at once but maps character by character, so
   * 软件 reads 軟件 for a few seconds and then 軟體. Snapshot state, so composables that converted early
   * recompose once the dictionaries are in.
   */
  private val ready = mutableStateOf(false)
  @Volatile private var warming = false

  fun warmUp() {
    if (warming) return
    warming = true
    thread(name = "opencc-warmup", isDaemon = true) {
      ZhTwConverterUtil.toTraditional("预热")
      ZhHkConverterUtil.toTraditional("预热")
      ready.value = true
    }
  }

  fun forVariant(variant: ChineseTextVariant): ChineseTextConverter {
    return when (variant) {
      ChineseTextVariant.Simplified -> Simplified
      ChineseTextVariant.HongKong -> staged { ZhHkConverterUtil.toTraditional(it) }
      ChineseTextVariant.Taiwan -> staged { ZhTwConverterUtil.toTraditional(it) }
    }
  }

  private val interim: ChineseTextConverter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    val transliterator by lazy { Transliterator.getInstance("Hans-Hant") }
    ChineseTextConverter { text -> transliterator.transliterate(text) }
  } else {
    Simplified
  }

  /**
   * [convert] once the dictionaries are warm, [interim] before. Each card converts its title, badge and owner
   * on every composition, so results are cached per string; the cache restarts when the stage changes.
   */
  private fun staged(convert: (String) -> String) = object : ChineseTextConverter {
    private var cache = LruCache<String, String>(1024)
    private var cachedReady = false
    override fun convert(text: String): String {
      if (text.isBlank()) return text
      val warm = ready.value
      if (!warm) warmUp()
      if (warm != cachedReady) {
        cache = LruCache(1024)
        cachedReady = warm
      }
      return cache.get(text) ?: (if (warm) convert(text) else interim.convert(text)).also { cache.put(text, it) }
    }
  }
}
