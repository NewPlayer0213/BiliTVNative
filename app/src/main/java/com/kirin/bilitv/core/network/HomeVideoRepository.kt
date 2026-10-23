package com.kirin.bilitv.core.network

import com.kirin.bilitv.core.auth.WbiKeyRepository
import com.kirin.bilitv.core.auth.WbiSigner
import com.kirin.bilitv.core.model.HomeSection
import com.kirin.bilitv.core.model.VideoSummary
import com.kirin.bilitv.core.storage.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

internal class HomeVideoRepository(
  private val apiClient: BiliApiClient,
  private val wbiKeyRepository: WbiKeyRepository,
  private val wbiSigner: WbiSigner,
  private val sessionStore: SessionStore,
  /** Where the recommend page for the next launch is kept; null disables the launch cache. */
  private val recommendCacheFile: File? = null,
) {
  suspend fun getHomeSectionVideos(
    section: HomeSection,
    page: Int = 1,
    idx: Int = 0,
  ): List<VideoSummary> {
    return when (section) {
      HomeSection.Recommend -> getRecommendVideos(idx)
      HomeSection.Popular -> getPopularVideos(page)
      else -> getRegionVideos(
        tid = section.regionTid ?: return emptyList(),
        page = page,
      )
    }
  }

  /** A recommend page; with [keepForNextLaunch] its JSON is also saved for [peekRecommendVideos]. */
  suspend fun getRecommendVideos(idx: Int = 0, keepForNextLaunch: Boolean = false): List<VideoSummary> {
    val root = fetchRecommend(idx)
    if (keepForNextLaunch) keep(root)
    return parseRecommend(root)
  }

  /** The recommend page saved at the previous launch, so the home grid has content in its first frame. */
  suspend fun peekRecommendVideos(): List<VideoSummary>? {
    val file = launchCacheFile() ?: return null
    return withContext(Dispatchers.IO) {
      if (!file.exists()) return@withContext null
      try {
        parseRecommend(Json.parseToJsonElement(file.readText()).rootObject())
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        null
      }
    }?.takeIf { it.isNotEmpty() }
  }

  /** Fetches a fresh recommend page and keeps it for the next launch. */
  suspend fun cacheRecommendVideos() {
    keep(fetchRecommend(idx = 0))
  }

  private suspend fun keep(root: JsonObject) {
    val file = launchCacheFile() ?: return
    withContext(Dispatchers.IO) {
      // Written whole, then renamed: a launch never reads a half-written page.
      try {
        val temp = File(file.path + ".tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) temp.delete()
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        // Next launch fetches as before.
      }
    }
  }

  /** One saved page for the signed-in account, another for signed out, so signing in or out never shows the other's feed. */
  private suspend fun launchCacheFile(): File? {
    val base = recommendCacheFile ?: return null
    val suffix = if (sessionStore.sessData.first().isNullOrBlank()) "-anon" else "-user"
    return File(base.parentFile, base.nameWithoutExtension + suffix + "." + base.extension)
  }

  private suspend fun fetchRecommend(idx: Int): JsonObject {
    val sessData = sessionStore.sessData.first()
    val keys = wbiKeyRepository.ensureKeys(sessData)

    val params = mutableMapOf(
      "fresh_idx" to idx.toString(),
      "fresh_type" to "4",
      "ps" to "20",
    )

    val signedParams = if (keys != null) {
      wbiSigner.sign(params, keys.imgKey, keys.subKey)
    } else {
      params
    }

    val root = apiClient.getJson(
      url = BiliApiEndpoints.Recommend,
      params = signedParams,
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("recommend")
    return root
  }

  private fun parseRecommend(root: JsonObject): List<VideoSummary> {
    val item = root.obj("data")?.get("item") as? JsonArray ?: return emptyList()
    return item
      .mapNotNull { it.asObjectOrNull() }
      .filter { it.string("bvid").isNotBlank() }
      .map(VideoSummaryMappers::fromArchive)
  }

  suspend fun getRelatedVideos(bvid: String): List<VideoSummary> {
    if (bvid.isBlank()) return emptyList()

    val sessData = sessionStore.sessData.first()
    val root = apiClient.getJson(
      url = BiliApiEndpoints.ArchiveRelated,
      params = mapOf("bvid" to bvid),
      sessData = sessData,
    ).rootObject()
    root.requireBiliCodeOk("archive related")

    val list = root["data"] as? JsonArray ?: return emptyList()
    return list
      .mapNotNull { it.asObjectOrNull() }
      .filter { it.string("bvid").isNotBlank() }
      .map(VideoSummaryMappers::fromArchive)
  }

  private suspend fun getPopularVideos(page: Int): List<VideoSummary> {
    val root = apiClient.getJson(
      url = BiliApiEndpoints.Popular,
      params = mapOf(
        "pn" to page.toString(),
        "ps" to "20",
      ),
    ).rootObject()
    root.requireBiliCodeOk("popular")

    val list = root.obj("data")?.get("list") as? JsonArray ?: return emptyList()
    return list
      .mapNotNull { it.asObjectOrNull() }
      .filter { it.string("bvid").isNotBlank() }
      .map(VideoSummaryMappers::fromArchive)
  }

  private suspend fun getRegionVideos(tid: Int, page: Int): List<VideoSummary> {
    val root = apiClient.getJson(
      url = BiliApiEndpoints.RegionLatest,
      params = mapOf(
        "rid" to tid.toString(),
        "pn" to page.toString(),
        "ps" to "20",
      ),
    ).rootObject()
    root.requireBiliCodeOk("region")

    val archives = root.obj("data")?.get("archives") as? JsonArray ?: return emptyList()
    return archives
      .mapNotNull { it.asObjectOrNull() }
      .filter { it.string("bvid").isNotBlank() }
      .map(VideoSummaryMappers::fromArchive)
  }
}
