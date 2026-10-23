package com.kirin.bilitv.ui.home

import android.os.SystemClock

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kirin.bilitv.core.model.VideoSummary
import com.kirin.bilitv.ui.common.VideoThumbnailPrefetcher
import com.kirin.bilitv.ui.settings.LocalBiliPerformancePolicy
import com.kirin.bilitv.ui.theme.BiliFocus
import com.kirin.bilitv.ui.theme.BiliMotion
import com.kirin.bilitv.ui.theme.BiliSizing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TvGridRestoreFocusRetryCount = 8

internal class TvGridFocusRestoreTargetTracker {
  private var trackedRequestKey: Int? = null
  private var trackedIndex: Int = 0

  fun targetIndex(requestKey: Int, currentIndex: Int): Int {
    if (trackedRequestKey != requestKey) {
      trackedRequestKey = requestKey
      trackedIndex = currentIndex
    }
    return trackedIndex
  }
}

internal data class TvGridVisibleRow(
  val index: Int,
  val offset: Int,
  val size: Int,
)

internal fun resolveTvGridNavigationEntryRow(
  viewportStartOffset: Int,
  viewportEndOffset: Int,
  visibleRows: List<TvGridVisibleRow>,
): Int? {
  val rowsInViewport = visibleRows.filter { row ->
    row.size > 0 && row.offset < viewportEndOffset && row.offset + row.size > viewportStartOffset
  }
  val firstFullyVisibleRow = rowsInViewport.firstOrNull { row ->
    row.offset >= viewportStartOffset && row.offset + row.size <= viewportEndOffset
  }
  return firstFullyVisibleRow?.index ?: rowsInViewport.maxByOrNull { row ->
    minOf(row.offset + row.size, viewportEndOffset) - maxOf(row.offset, viewportStartOffset)
  }?.index
}

private val TvGridBringIntoViewSpec = object : BringIntoViewSpec {
  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    // D-pad row scrolling is handled below. Returning 0 prevents Compose's
    // default focus relocation from doing an instant pre-scroll first.
    return 0f
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvVideoGrid(
  videos: List<VideoSummary>,
  firstItemFocusRequester: FocusRequester,
  restoredFocusIndex: Int,
  restoreFocusRequestKey: Int,
  onRestoreFocusHandled: (Int) -> Unit,
  onFocusedIndexChange: (Int, VideoSummary) -> Unit,
  onLoadMore: () -> Unit,
  onMoveLeftToNav: () -> Boolean,
  onVideoSelected: (VideoSummary) -> Unit,
  modifier: Modifier = Modifier,
  cardMode: VideoCardMode = VideoCardMode.Standard,
  requestInitialFocus: Boolean = false,
  onInitialFocusRequested: () -> Unit = {},
  onMoveUpFromFirstRow: () -> Boolean = { true },
  onBackKey: (() -> Boolean)? = null,
  horizontalPadding: Dp = BiliSizing.VideoGridHorizontalPadding,
  topPadding: Dp = BiliFocus.ScrollInset,
  topBleed: Dp = 0.dp,
  keyFactory: (Int, VideoSummary) -> Any = { _, video -> video.bvid },
) {
  val columns = BiliSizing.VideoGridColumns
  val rowCount = (videos.size + columns - 1) / columns
  val restoreTargetTracker = remember { TvGridFocusRestoreTargetTracker() }
  val frozenRestoreTargetIndex = restoreTargetTracker.targetIndex(
    requestKey = restoreFocusRequestKey,
    currentIndex = restoredFocusIndex,
  )
  val restoreTargetIndex = frozenRestoreTargetIndex.coerceIn(0, (videos.size - 1).coerceAtLeast(0))
  val restoreTargetRow = if (videos.isEmpty()) {
    0
  } else {
    restoreTargetIndex / columns
  }
  // Rows about three ahead and one behind stay composed, so a new row is not composed in the first frame of
  // its own scroll: on a TV that renders a frame in ~50 ms that was a ~100 ms hitch on every row.
  val listState = rememberLazyListState(
    cacheWindow = remember { LazyLayoutCacheWindow(aheadFraction = 1.2f, behindFraction = 0.4f) },
    initialFirstVisibleItemIndex = restoreTargetRow,
  )
  val coroutineScope = rememberCoroutineScope()
  val performancePolicy = LocalBiliPerformancePolicy.current
  val density = LocalDensity.current
  val topBleedPx = with(density) { topBleed.roundToPx() }
  val focusScrollInsetPx = with(density) { topPadding.roundToPx() }
  val focusedRowTopPaddingPx = with(density) { BiliFocus.FocusedRowTopPadding.roundToPx() }
  val videoCardFallbackHeightPx = with(density) { BiliSizing.VideoCardMinHeight.roundToPx() }
  val itemFocusRequesters = remember(videos.size) {
    List(videos.size) { FocusRequester() }
  }
  var focusScrollJob by remember { mutableStateOf<Job?>(null) }
  var focusedIndex by remember { mutableIntStateOf(-1) }
  var navigationEntryTargetIndex by remember { mutableIntStateOf(0) }
  var rowScrollActive by remember { mutableStateOf(false) }
  // When the last vertical move was accepted: a held key repeats every ~50 ms and is paced by HeldRowMs.
  val lastVerticalMoveAt = remember { mutableLongStateOf(0L) }
  // A tap that lands while a row is still sliding: it runs as soon as the slide ends, so the focus never
  // leaves the row the grid is bringing into view and no press is lost.
  var pendingMove by remember { mutableStateOf<Key?>(null) }
  var rowScrollGeneration by remember { mutableIntStateOf(0) }
  val focusScale = when {
    !performancePolicy.motionEnabled -> 1f
    performancePolicy.cinematicVisualEffectsEnabled -> BiliFocus.CinematicCardScale
    else -> BiliFocus.CardScale
  }

  VideoThumbnailPrefetcher(
    videos = videos,
    focusedIndex = if (focusedIndex >= 0) focusedIndex else restoredFocusIndex,
    enabled = !rowScrollActive,
  )

  LaunchedEffect(listState, videos.size, topBleedPx) {
    snapshotFlow {
      listState.navigationEntryRow(topBleedPx)
    }
      .distinctUntilChanged()
      .collect { row ->
        if (row != null && videos.isNotEmpty()) {
          navigationEntryTargetIndex = (row * columns).coerceIn(0, videos.lastIndex)
        }
      }
  }

  suspend fun scrollRow(row: Int, smoothScroll: Boolean, linear: Boolean = false) {
    listState.scrollRowIntoStablePosition(
      row = row,
      totalRows = rowCount,
      fallbackItemHeightPx = videoCardFallbackHeightPx,
      scrollInsetPx = focusScrollInsetPx,
      focusedRowTopPaddingPx = focusedRowTopPaddingPx,
      focusScale = focusScale,
      smoothScroll = smoothScroll,
      linear = linear,
    )
  }

  LaunchedEffect(restoreFocusRequestKey, videos.size) {
    if (restoreFocusRequestKey <= 0 || videos.isEmpty()) {
      return@LaunchedEffect
    }
    val targetIndex = restoreTargetIndex
    scrollRow(targetIndex / columns, smoothScroll = false)
    repeat(TvGridRestoreFocusRetryCount) {
      withFrameNanos { }
      val focused = runCatching {
        itemFocusRequesters[targetIndex].requestFocus()
      }.getOrDefault(false)
      if (focused) {
        onRestoreFocusHandled(restoreFocusRequestKey)
        return@LaunchedEffect
      }
    }
    onRestoreFocusHandled(restoreFocusRequestKey)
  }

  LaunchedEffect(videos.size, requestInitialFocus) {
    if (requestInitialFocus && videos.isNotEmpty()) {
      withFrameNanos { }
      runCatching {
        firstItemFocusRequester.requestFocus()
      }
      onInitialFocusRequested()
    }
  }

  fun focusItem(index: Int): Boolean {
    return runCatching {
      itemFocusRequesters[index].requestFocus()
    }.isSuccess
  }

  fun commitFocusedItem(index: Int) {
    videos.getOrNull(index)?.let { video ->
      onFocusedIndexChange(index, video)
    }
  }

  fun scrollThenFocusItem(index: Int, row: Int, repeat: Boolean = false) {
    focusScrollJob?.cancel()
    val scrollGeneration = ++rowScrollGeneration
    rowScrollActive = true
    focusScrollJob = coroutineScope.launch {
      val smoothScroll = performancePolicy.smoothScrollingEnabled
      try {
        if (smoothScroll) {
          val scrollJob = launch {
            // Held key: rows at constant speed with no settle pause, so they join into one continuous scroll.
            scrollRow(row, smoothScroll = true, linear = repeat)
          }
          delay(BiliMotion.FocusScrollDelayMs)
          focusItem(index)
          scrollJob.join()
          if (!repeat) delay(BiliMotion.FocusScrollSettleMs)
        } else {
          val shift = launch {
            listState.scrollRowIntoView(
              row = row,
              scrollInsetPx = focusScrollInsetPx,
              focusedRowTopPaddingPx = focusedRowTopPaddingPx,
              focusScale = focusScale,
              durationMs = if (repeat) BiliMotion.HeldRowMs.toInt() else BiliMotion.EdgeShiftMs,
            )
          }
          withFrameNanos { }
          focusItem(index)
          shift.join()
        }
      } finally {
        if (rowScrollGeneration == scrollGeneration) {
          rowScrollActive = false
        }
      }
    }
  }

  fun moveFocus(fromIndex: Int, direction: Key, repeat: Boolean = false): Boolean {
    val currentRow = fromIndex / columns
    val currentColumn = fromIndex % columns
    val lastIndex = videos.lastIndex
    val lastRow = lastIndex / columns

    if (direction == Key.DirectionUp && currentRow == 0) {
      commitFocusedItem(fromIndex)
      return onMoveUpFromFirstRow()
    }
    if (direction == Key.DirectionLeft && currentColumn == 0) {
      commitFocusedItem(fromIndex)
      return onMoveLeftToNav()
    }

    val targetIndex = when (direction) {
      Key.DirectionUp -> ((currentRow - 1) * columns + currentColumn).coerceAtMost(lastIndex).takeIf { currentRow > 0 }
      Key.DirectionDown -> ((currentRow + 1) * columns + currentColumn).coerceAtMost(lastIndex).takeIf { currentRow < lastRow }
      Key.DirectionLeft -> (fromIndex - 1).takeIf { currentColumn > 0 }
      Key.DirectionRight -> (fromIndex + 1).takeIf { currentColumn < columns - 1 && it <= lastIndex && it / columns == currentRow }
      else -> null
    } ?: return direction == Key.DirectionDown || direction == Key.DirectionRight

    if (direction == Key.DirectionLeft || direction == Key.DirectionRight) {
      return focusItem(targetIndex)
    }

    scrollThenFocusItem(targetIndex, targetIndex / columns, repeat)
    return true
  }

  LaunchedEffect(rowScrollActive) {
    if (rowScrollActive) return@LaunchedEffect
    val key = pendingMove ?: return@LaunchedEffect
    pendingMove = null
    if (focusedIndex >= 0) {
      lastVerticalMoveAt.longValue = SystemClock.uptimeMillis()
      moveFocus(focusedIndex, key)
    }
  }

  CompositionLocalProvider(LocalBringIntoViewSpec provides TvGridBringIntoViewSpec) {
    LazyColumn(
      state = listState,
      modifier = modifier
        .fillMaxSize()
        .layout { measurable, constraints ->
          if (topBleedPx <= 0) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
              placeable.place(0, 0)
            }
          } else {
            val expandedMaxHeight = if (constraints.maxHeight == Constraints.Infinity) {
              Constraints.Infinity
            } else {
              constraints.maxHeight + topBleedPx
            }
            val placeable = measurable.measure(
              constraints.copy(maxHeight = expandedMaxHeight),
            )
            val layoutHeight = if (constraints.maxHeight == Constraints.Infinity) {
              placeable.height
            } else {
              constraints.maxHeight
            }
            layout(placeable.width, layoutHeight) {
              placeable.place(0, -topBleedPx)
            }
          }
        },
      contentPadding = PaddingValues(
        start = horizontalPadding,
        top = topPadding,
        end = horizontalPadding,
        bottom = BiliSizing.VideoGridBottomPadding,
      ),
      verticalArrangement = Arrangement.spacedBy(BiliSizing.VideoGridSpacing),
    ) {
      items(
        count = rowCount,
        key = { row ->
          val firstIndex = row * columns
          "row-$row-${keyFactory(firstIndex, videos[firstIndex])}"
        },
        contentType = { "video-row" },
      ) { row ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .zIndex(
              if (focusedIndex >= 0 && focusedIndex / columns == row) {
                BiliFocus.FocusedZIndex
              } else {
                0f
              },
            ),
          horizontalArrangement = Arrangement.spacedBy(BiliSizing.VideoGridSpacing),
        ) {
          repeat(columns) { column ->
            val index = row * columns + column
            if (index < videos.size) {
              val video = videos[index]
              VideoCard(
                video = video,
                mode = cardMode,
                // Only the marquee and the shine pause; with motion off neither exists, and flipping the
                // flag would recompose every card at the start and end of each row shift.
                interactionPaused = rowScrollActive && performancePolicy.motionEnabled,
                modifier = Modifier
                  .weight(1f)
                  .then(
                    if (index == navigationEntryTargetIndex.coerceIn(0, videos.lastIndex)) {
                      Modifier.focusRequester(firstItemFocusRequester)
                    } else {
                      Modifier
                    },
                  )
                  .focusRequester(itemFocusRequesters[index])
                  .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                      return@onPreviewKeyEvent false
                    }
                    val vertical = event.key == Key.DirectionUp || event.key == Key.DirectionDown
                    val repeat = vertical && event.nativeKeyEvent.repeatCount > 0
                    val now = SystemClock.uptimeMillis()
                    // Without smooth scrolling a held key's repeat is dropped while a row shift is still running or
                    // sooner than HeldRowMs after the last accepted move, so a scan runs at a steady pace; with it the
                    // repeats chain into one continuous scroll as before.
                    val paced = !performancePolicy.smoothScrollingEnabled
                    if (repeat && paced && (rowScrollActive || now - lastVerticalMoveAt.longValue < BiliMotion.HeldRowMs)) {
                      return@onPreviewKeyEvent true
                    }
                    if (vertical && paced && rowScrollActive) {
                      if (!repeat) pendingMove = event.key
                      return@onPreviewKeyEvent true
                    }
                    if (vertical) lastVerticalMoveAt.longValue = now
                    when (event.key) {
                      Key.Back -> onBackKey?.invoke() ?: false
                      Key.DirectionUp,
                      Key.DirectionDown,
                      Key.DirectionLeft,
                      Key.DirectionRight -> moveFocus(index, event.key, repeat)
                      else -> false
                    }
                },
                onFocused = {
                  focusedIndex = index
                  commitFocusedItem(index)
                  if (index.shouldLoadMore(
                      totalItems = videos.size,
                      threshold = performancePolicy.loadMoreFocusThreshold,
                    )
                  ) {
                    onLoadMore()
                  }
                },
                onClick = {
                  commitFocusedItem(index)
                  onVideoSelected(video)
                },
              )
            } else {
              Spacer(modifier = Modifier.weight(1f))
            }
          }
        }
      }
    }
  }
}

private fun LazyListState.navigationEntryRow(topBleedPx: Int): Int? {
  val layout = layoutInfo
  return resolveTvGridNavigationEntryRow(
    viewportStartOffset = layout.viewportStartOffset + topBleedPx,
    viewportEndOffset = layout.viewportEndOffset,
    visibleRows = layout.visibleItemsInfo.map { item ->
      TvGridVisibleRow(
        index = item.index,
        offset = item.offset,
        size = item.size,
      )
    },
  )
}

private suspend fun LazyListState.scrollRowIntoStablePosition(
  row: Int,
  totalRows: Int,
  fallbackItemHeightPx: Int,
  scrollInsetPx: Int,
  focusedRowTopPaddingPx: Int,
  focusScale: Float,
  smoothScroll: Boolean,
  linear: Boolean = false,
) {
  val safeRow = row.coerceIn(0, (totalRows - 1).coerceAtLeast(0))
  val layout = layoutInfo
  val viewportTop = layout.viewportStartOffset
  val viewportBottom = layout.viewportEndOffset
  val itemHeightPx = layout.visibleItemsInfo.firstOrNull { item -> item.index == safeRow }?.size
    ?: layout.visibleItemsInfo.firstOrNull()?.size
    ?: fallbackItemHeightPx
  val focusOverflowPx = ((itemHeightPx * (focusScale - 1f)) / 2f).roundToInt()
  val edgeInsetPx = scrollInsetPx + focusOverflowPx
  val focusedRow = layout.visibleItemsInfo.firstOrNull { item -> item.index == safeRow }

  if (focusedRow != null) {
    val targetTop = (viewportTop + focusedRowTopPaddingPx.coerceAtLeast(edgeInsetPx))
      .coerceAtMost(viewportBottom - edgeInsetPx - focusedRow.size)
      .coerceAtLeast(viewportTop + edgeInsetPx)
    val scrollDelta = focusedRow.offset - targetTop
    if (abs(scrollDelta) <= BiliMotion.FocusScrollMinDeltaPx) {
      return
    }
    if (smoothScroll) {
      animateScrollBy(
        value = scrollDelta.toFloat(),
        animationSpec = tween(
          durationMillis = BiliMotion.FocusScrollMs,
          easing = if (linear) LinearEasing else BiliMotion.FocusScrollEasing,
        ),
      )
    } else {
      scroll {
        scrollBy(scrollDelta.toFloat())
      }
    }
    return
  }

  if (smoothScroll) {
    animateScrollToItem(safeRow, scrollOffset = -focusedRowTopPaddingPx)
  } else {
    scrollToItem(safeRow, scrollOffset = -focusedRowTopPaddingPx)
  }
}

/**
 * Leanback's FOCUS_SCROLL_ITEM for this grid: the focus walks the rows already on screen, and the grid only
 * shifts by what brings a row at the edge fully into view, in one even slide of [durationMs]. On a TV that
 * redraws the grid in ~70 ms a per-key slide showed as a few visible steps; the rarer edge slide reads as a
 * crisp move, and a held key chains them at one pace.
 */
private suspend fun LazyListState.scrollRowIntoView(
  row: Int,
  scrollInsetPx: Int,
  focusedRowTopPaddingPx: Int,
  focusScale: Float,
  durationMs: Int,
) {
  val layout = layoutInfo
  val item = layout.visibleItemsInfo.firstOrNull { it.index == row }
  if (item == null) {
    scrollToItem(row, scrollOffset = -focusedRowTopPaddingPx)
    return
  }
  val focusOverflowPx = ((item.size * (focusScale - 1f)) / 2f).roundToInt()
  val edgeInsetPx = scrollInsetPx + focusOverflowPx
  val top = layout.viewportStartOffset + focusedRowTopPaddingPx.coerceAtLeast(edgeInsetPx)
  val bottom = layout.viewportEndOffset - edgeInsetPx
  val delta = when {
    item.offset < top -> item.offset - top
    item.offset + item.size > bottom -> item.offset + item.size - bottom
    else -> 0
  }
  if (delta == 0) return
  animateScrollBy(delta.toFloat(), tween(durationMs, easing = LinearEasing))
}

private fun Int.shouldLoadMore(totalItems: Int, threshold: Int): Boolean {
  return this >= totalItems - threshold
}
