package net.lighttools.lightmux.ui.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

private sealed interface PreviewState {
    data object Loading : PreviewState
    data object HostMissing : PreviewState
    data object TooLarge : PreviewState
    data object Unsupported : PreviewState
    data class Failed(val message: String) : PreviewState
    data class Ready(val bitmap: Bitmap) : PreviewState
}

/** 位图只随页面存活，退回文件列表就释放引用，避免每台主机的常驻 VM 各攒一张大图。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePreviewScreen(
    repository: SftpRepository,
    hostStore: HostStore,
    hostId: String,
    path: String,
    onBack: () -> Unit,
) {
    var attempt by remember(path, hostId) { mutableIntStateOf(0) }
    val state by produceState<PreviewState>(PreviewState.Loading, hostId, path, attempt) {
        value = PreviewState.Loading
        value = try {
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
    }
    var scale by remember(path, hostId) { mutableFloatStateOf(1f) }
    var offset by remember(path, hostId) { mutableStateOf(Offset.Zero) }
    val reset = { scale = 1f; offset = Offset.Zero }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(SftpPath.name(path), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
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
        Box(Modifier.fillMaxSize().padding(padding).clipToBounds(), contentAlignment = Alignment.Center) {
            when (val loaded = state) {
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
                is PreviewState.Ready -> Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.weight(1f).fillMaxSize().clipToBounds()
                            .pointerInput(path) {
                                detectTapGestures(onDoubleTap = {
                                    scale = if (scale > 1f) 1f else 2f
                                    offset = Offset.Zero
                                })
                            }
                            .pointerInput(path) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 8f)
                                    val maxX = size.width * (scale - 1) / 2f
                                    val maxY = size.height * (scale - 1) / 2f
                                    offset = Offset(
                                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                                    )
                                }
                            },
                    ) {
                        Image(
                            bitmap = loaded.bitmap.asImageBitmap(),
                            contentDescription = SftpPath.name(path),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                        )
                    }
                    Text(
                        stringResource(R.string.files_image_hint),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
