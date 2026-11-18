package dev.dimension.flare.ui.screen.media

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.compose.rememberAsyncImagePainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import dev.dimension.flare.R
import dev.dimension.flare.ui.component.rememberMediaImageRequest
import dev.dimension.flare.ui.model.UiMedia
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.coil3.coil
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.abs

@Composable
internal fun ImageItem(
    media: UiMedia?,
    description: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    setLockPager: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onLoadError: () -> Unit = {},
    translating: Boolean = false,
    entryPreview: UiMedia.Image? = null,
    isCurrentPage: Boolean = true,
) {
    val loadingEffect by animateFloatAsState(
        targetValue = if (translating) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "Image translation loading",
    )
    val loadingDescription = stringResource(R.string.image_translation_loading)
    val currentOnLoadError by rememberUpdatedState(onLoadError)
    val currentSetLockPager by rememberUpdatedState(setLockPager)
    val scope = rememberCoroutineScope()
    val zoom = rememberZoomableState(ZoomSpec(maxZoomFactor = 10f), autoApplyTransformations = false)
    val isZoomed by remember(zoom) { derivedStateOf { zoom.isZoomedIn } }
    LaunchedEffect(isCurrentPage, isZoomed) { if (isCurrentPage) currentSetLockPager(isZoomed) }
    DisposableEffect(zoom) {
        onDispose { if (isZoomed) currentSetLockPager(false) }
    }
    BackHandler(enabled = isCurrentPage && isZoomed) { scope.launch { zoom.resetZoom() } }

    var loadedMediaKey by remember { mutableStateOf<String?>(null) }
    var previousCacheKey by remember { mutableStateOf<MemoryCache.Key?>(null) }
    val currentMediaKey by rememberUpdatedState(media?.cacheKey)
    val baseRequest = rememberMediaImageRequest(media, crossfade = false)
    val request =
        remember(baseRequest) {
            if (media == null || baseRequest.data != media.url) return@remember null
            val requestedKey = media.cacheKey
            baseRequest
                .newBuilder()
                .size(coil3.size.Size.ORIGINAL)
                .apply { previousCacheKey?.let { placeholderMemoryCacheKey(it) } }
                .listener(onError = { _, _ ->
                    if (requestedKey == currentMediaKey) {
                        loadedMediaKey = requestedKey
                        currentOnLoadError()
                    }
                }, onSuccess = { _, result ->
                    if (requestedKey == currentMediaKey) {
                        previousCacheKey = result.memoryCacheKey
                        loadedMediaKey = requestedKey
                    }
                })
                .build()
        }
    val startedWithPreview = remember { media == null }
    val preview =
        media?.preview?.takeIf { it.url != media.url }
            ?: entryPreview?.takeIf { startedWithPreview || it.url != media?.url }
    val originalSize =
        when (media) {
            is UiMedia.Image -> Size(media.width, media.height)
            is UiMedia.Gif -> Size(media.width, media.height)
            else -> null
        }?.takeIf { it.isUsable() }

    Box(modifier) {
        ProgressiveImage(
            request = request,
            preview = preview,
            originalSize = originalSize,
            zoom = zoom,
            description = description,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier =
                Modifier
                    .matchParentSize()
                    .blur((8 * loadingEffect).dp)
                    .alpha(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1f else 1f - 0.2f * loadingEffect),
        )
        AnimatedVisibility(
            visible = translating || (media != null && !media.isRemote && loadedMediaKey != media.cacheKey),
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        ) {
            LoadingIndicator(
                modifier = if (translating) Modifier.semantics { contentDescription = loadingDescription } else Modifier,
            )
        }
    }
}

@OptIn(ExperimentalTelephotoApi::class)
@Composable
private fun ProgressiveImage(
    request: ImageRequest?,
    preview: UiMedia.Image?,
    originalSize: Size?,
    zoom: ZoomableState,
    description: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    var viewport by remember { mutableStateOf(Size.Zero) }
    val viewportSizes = remember { snapshotFlow { viewport }.filter { it.isUsable() } }
    val resolved = request?.let { ZoomableImageSource.coil(it).resolve(viewportSizes) }
    val tiled = resolved?.delegate as? ZoomableImageSource.SubSamplingDelegate
    val tiles = tiled?.let { rememberSubSamplingImageState(it.source, zoom, it.imageOptions) }
    val fullPainter = (resolved?.delegate as? ZoomableImageSource.PainterDelegate)?.painter
    val fullSize =
        tiles?.imageSize?.toSize()
            ?: tiled?.source?.preview?.let { Size(it.width.toFloat(), it.height.toFloat()) }
            ?: fullPainter?.let { it.intrinsicSize.takeIf(Size::isUsable) ?: viewport.takeIf(Size::isUsable) }

    // Telephoto saves pan offsets in content pixels. Restore the size that gives those offsets their meaning.
    var contentSize by rememberSaveable(stateSaver = ImageSizeSaver) { mutableStateOf<Size?>(null) }
    var heldBounds by remember { mutableStateOf<Rect?>(null) }
    var fingersDown by remember { mutableStateOf(false) }
    val changingSize = (contentSize != null && contentSize != fullSize) || heldBounds != null
    val thumbnail =
        if (changingSize || (fullPainter == null && tiles?.isImageDisplayed != true)) {
            resolved?.placeholder?.let { remember(it) { it } }
                ?: preview?.let { rememberAsyncImagePainter(rememberMediaImageRequest(it, crossfade = false)) }
        } else {
            null
        }
    val previewSize = thumbnail?.intrinsicSize?.takeIf(Size::isUsable)
    val painter = if (previewSize != null) thumbnail else fullPainter?.let { remember(it) { it } }
    val desiredSize =
        fullSize ?: contentSize ?: previewSize?.let { thumbnail ->
            // Allow for rounding when a server resizes a thumbnail, but preserve the aspect of a cropped preview.
            val aspect = thumbnail.width / thumbnail.height
            originalSize?.takeIf { abs(it.width / it.height - aspect) < aspect * 0.01f } ?: thumbnail
        }
    val drawingSize = contentSize ?: desiredSize
    // The tile decoder reports its native bounds here. Keep using preview coordinates until the handoff.
    zoom.useImageSize(drawingSize)
    LaunchedEffect(desiredSize) {
        if (desiredSize == null || desiredSize == contentSize) return@LaunchedEffect
        if (contentSize == null) {
            contentSize = desiredSize
            return@LaunchedEffect
        }
        snapshotFlow { !fingersDown && !zoom.isAnimationRunning }.first { it }
        val before = zoom.imageBounds()
        val center = viewport.center
        val preserveFocus = !before.isEmpty && (zoom.isZoomedIn || before.top < -0.5f)
        contentSize = desiredSize
        zoom.useImageSize(desiredSize)
        if (preserveFocus) {
            val after = zoom.imageBounds()
            val focalPoint =
                Offset(
                    after.left + after.width * ((center.x - before.left) / before.width),
                    after.top + after.height * ((center.y - before.top) / before.height),
                )
            if ((center - focalPoint).getDistance() > 0.5f) {
                heldBounds = before
                try {
                    zoom.panBy(center - focalPoint, snap())
                } finally {
                    heldBounds = null
                }
            }
        }
    }

    Box(
        modifier
            .onSizeChanged { viewport = it.toSize() }
            .semantics {
                if (description != null) contentDescription = description
                role = Role.Image
            }.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) fingersDown = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                }
            }.zoomable(
                state = zoom,
                onClick = { onClick() },
                onLongClick = { onLongClick() },
                onDoubleClick = DoubleTapZoom,
            ),
    ) {
        if (painter != null) {
            Canvas(Modifier.fillMaxSize()) {
                val bounds = heldBounds ?: zoom.imageBounds()
                if (!bounds.isEmpty) translate(bounds.left, bounds.top) { with(painter) { draw(bounds.size) } }
            }
        }
        if (tiles != null && !changingSize) {
            SubSamplingImage(tiles, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

private val ZoomableState.isZoomedIn: Boolean
    get() = contentTransformation.scaleMetadata.userZoom > 1.01f

private val ImageSizeSaver =
    Saver<Size?, FloatArray>(
        save = { it?.let { size -> floatArrayOf(size.width, size.height) } },
        restore = { (width, height) -> Size(width, height) },
    )

private val DoubleTapZoom =
    DoubleClickToZoomListener { zoom, position ->
        if (zoom.isZoomedIn) zoom.resetZoom() else zoom.zoomBy(2f, position)
    }

private fun Size.isUsable() = isSpecified && width.isFinite() && height.isFinite() && !isEmpty()

private fun ZoomableState.useImageSize(size: Size?) {
    val isLong = size != null && size.height > size.width * (19.5f / 9f)
    contentScale = if (isLong) ContentScale.FillWidth else ContentScale.Fit
    contentAlignment = if (isLong) Alignment.TopCenter else Alignment.Center
    setContentLocation(ZoomableContentLocation.unscaledAndTopLeftAligned(size))
}

@OptIn(ExperimentalTelephotoApi::class)
private fun ZoomableState.imageBounds(): Rect =
    with(coordinateSystem) { contentBounds(clipToViewport = false).rectIn(CoordinateSpace.Viewport) }
