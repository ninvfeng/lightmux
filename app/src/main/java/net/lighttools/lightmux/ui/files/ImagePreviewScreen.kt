package net.lighttools.lightmux.ui.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lighttools.lightmux.R
import net.lighttools.lightmux.data.HostStore
import net.lighttools.lightmux.sftp.ImagePreview
import net.lighttools.lightmux.sftp.SftpPath
import net.lighttools.lightmux.sftp.SftpRepository
import net.lighttools.lightmux.ui.common.BackButton
import net.lighttools.lightmux.ui.common.ErrorBanner
import net.lighttools.lightmux.ui.common.Spinner
import kotlin.math.abs

private sealed interface PreviewState {
    data object Loading : PreviewState
    data object HostMissing : PreviewState
    data object TooLarge : PreviewState
    data object Unsupported : PreviewState
    data class Failed(val message: String) : PreviewState
    data class Ready(val bitmap: Bitmap) : PreviewState
}

/** 只解码停稳后的当前页，避免 Pager 预组合相邻页时同时读取、保留多张大图。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePreviewScreen(
    repository: SftpRepository,
    hostStore: HostStore,
    hostId: String,
    path: String,
    images: List<String>,
    onBack: () -> Unit,
) {
    val paths = remember(path, images) { images.takeIf { path in it } ?: listOf(path) }
    val pager = rememberPagerState(initialPage = paths.indexOf(path)) { paths.size }
    val currentPath = paths[pager.settledPage.coerceIn(paths.indices)]
    var attempt by remember(currentPath, hostId) { mutableIntStateOf(0) }
    // key 让翻页第一帧就是 Loading，不会把上一张位图短暂显示成新文件的内容。
    val state by key(hostId, currentPath, attempt) {
        produceState<PreviewState>(PreviewState.Loading) {
            value = loadPreview(repository, hostStore, hostId, currentPath)
        }
    }
    var scale by remember(currentPath, hostId) { mutableFloatStateOf(1f) }
    var offset by remember(currentPath, hostId) { mutableStateOf(Offset.Zero) }
    val reset = { scale = 1f; offset = Offset.Zero }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            SftpPath.name(currentPath), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (paths.size > 1) {
                            Text(
                                stringResource(R.string.files_image_position, pager.settledPage + 1, paths.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = reset, enabled = state is PreviewState.Ready) {
                        Icon(Icons.Default.ZoomOutMap, stringResource(R.string.files_image_reset))
                    }
                    IconButton(onClick = { reset(); attempt++ }, enabled = state != PreviewState.Loading) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = scale == 1f,
                modifier = Modifier.weight(1f).fillMaxSize(),
                key = { paths[it] },
            ) { page ->
                Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                    when (val loaded = if (page == pager.settledPage) state else PreviewState.Loading) {
                        PreviewState.Loading -> Spinner()
                        PreviewState.HostMissing -> ErrorBanner(stringResource(R.string.error_host_missing))
                        PreviewState.TooLarge -> Text(
                            stringResource(R.string.files_image_too_large, SftpPath.humanSize(ImagePreview.MAX_BYTES.toLong())),
                            modifier = Modifier.padding(24.dp),
                        )
                        PreviewState.Unsupported -> Text(
                            stringResource(R.string.files_image_unsupported), modifier = Modifier.padding(24.dp),
                        )
                        is PreviewState.Failed -> ErrorBanner(
                            message = stringResource(R.string.files_image_failed, loaded.message),
                            actionLabel = stringResource(R.string.retry),
                            onAction = { attempt++ },
                        )
                        is PreviewState.Ready -> Box(
                            Modifier.fillMaxSize()
                                .pointerInput(currentPath) {
                                    detectTapGestures(onDoubleTap = {
                                        scale = if (scale > 1f) 1f else 2f
                                        offset = Offset.Zero
                                    })
                                }
                                .pointerInput(currentPath) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        var transforming = scale > 1f
                                        var pastSlop = false
                                        var totalZoom = 1f
                                        var totalPan = Offset.Zero
                                        do {
                                            val event = awaitPointerEvent()
                                            if (event.changes.any { it.isConsumed }) break
                                            transforming = transforming || event.changes.count { it.pressed } > 1
                                            // 原尺寸的单指横划留给 Pager；多指或已放大时才认领变换手势。
                                            if (transforming) {
                                                val zoom = event.calculateZoom()
                                                val pan = event.calculatePan()
                                                totalZoom *= zoom
                                                totalPan += pan
                                                if (!pastSlop) {
                                                    val zoomMotion = abs(1 - totalZoom) *
                                                        event.calculateCentroidSize(useCurrent = false)
                                                    pastSlop = zoomMotion > viewConfiguration.touchSlop ||
                                                        totalPan.getDistance() > viewConfiguration.touchSlop
                                                }
                                                if (pastSlop) {
                                                    scale = (scale * zoom).coerceIn(1f, 8f)
                                                    val maxX = size.width * (scale - 1) / 2f
                                                    val maxY = size.height * (scale - 1) / 2f
                                                    offset = Offset(
                                                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                                                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                                                    )
                                                    // 缩回原尺寸后也认领到手指抬起，避免一次缩放中途变成翻页。
                                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                                }
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                },
                        ) {
                            Image(
                                bitmap = loaded.bitmap.asImageBitmap(),
                                contentDescription = SftpPath.name(currentPath),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(when {
                    scale > 1f -> R.string.files_image_zoomed_hint
                    paths.size > 1 -> R.string.files_image_gallery_hint
                    else -> R.string.files_image_hint
                }),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private suspend fun loadPreview(
    repository: SftpRepository,
    hostStore: HostStore,
    hostId: String,
    path: String,
): PreviewState = try {
    val host = hostStore.get(hostId)
    if (host == null) PreviewState.HostMissing
    else {
        val bytes = repository.readImage(host, path)
        if (bytes == null) PreviewState.TooLarge
        else withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) PreviewState.Unsupported
            else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = ImagePreview.sampleSize(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?.let { PreviewState.Ready(it) } ?: PreviewState.Unsupported
            }
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    PreviewState.Failed(e.describe())
}
