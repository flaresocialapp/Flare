package dev.dimension.flare.ui.screen.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.imageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.data.network.ProxyNetwork
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.media
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.theme.FlareTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalComposeUiApi::class, DelicateCoilApi::class)
class ProgressiveImageZoomTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer get() = compose.onNodeWithTag("viewer")

    @Test
    fun cachedPreviewKeepsDoubleTapAndPanWhenOriginalArrives() = verifyDoubleTap(knownSize = true)

    @Test
    fun longEntryPreviewKeepsItsPageAndFocusWhenPostAndOriginalArrive() = verifyDoubleTap(knownSize = false, size = IntSize(800, 2400))

    @Test
    fun croppedThumbnailKeepsItsShapeAndSmallOriginalCanStillDoubleInSize() =
        withDelayedOriginal(knownSize = true, size = IntSize(320, 240), previewSize = IntSize(64, 64)) { _, release, _ ->
            val thumbnail = awaitMarker(Color.RED)
            assertTrue("The thumbnail marker must stay square", abs(thumbnail.width - thumbnail.height) <= 1f)
            release.complete(Unit)
            val initial = awaitMarker(Color.GREEN)
            doubleTap()
            assertTrue("Double tap is relative to the fitted image", awaitMarker(Color.GREEN).width > initial.width * 1.8f)
            doubleTap()
            assertSameFraming(initial, awaitMarker(Color.GREEN))
        }

    @Test
    fun animatedOriginalKeepsPlayingAtTheSelectedZoom() {
        val file = File.createTempFile("zoom-marker", ".gif", context.cacheDir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("zoom-marker.gif").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val uri = file.toURI().toString()
            val gif = UiMedia.Gif(uri, uri, "Animated marker", height = 48f, width = 64f)
            compose.setContent {
                MaterialExpressiveTheme {
                    ImageItem(gif, gif.description, {}, {}, {}, Modifier.size(240.dp, 320.dp).testTag("viewer"))
                }
            }
            val initial = awaitMarker(Color.GREEN)
            doubleTap()
            val zoomed = awaitMarker(Color.RED)
            assertTrue(zoomed.width > initial.width * 1.8f)
            assertSameFraming(zoomed, awaitMarker(Color.GREEN))
        } finally {
            file.delete()
        }
    }

    private fun verifyDoubleTap(
        knownSize: Boolean,
        size: IntSize = IntSize(1600, 1200),
    ) = withDelayedOriginal(knownSize, size) { loadPost, release, _ ->
        val initial = awaitMarker(Color.RED)
        doubleTap()
        val zoomed = awaitMarker(Color.RED)
        assertTrue("Double tap must magnify the preview: $initial -> $zoomed", zoomed.width > initial.width * 1.8f)
        viewer.performTouchInput {
            down(center)
            moveTo(center + Offset(70f, 40f), 100)
            moveTo(center + Offset(92f, 52f), 90)
            repeat(8) { move(32) }
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        val panned = awaitMarker(Color.RED)
        assertTrue("Dragging must move the preview", (panned.center - zoomed.center).getDistance() > 20)
        loadPost()
        assertSameFraming(panned, awaitMarker(Color.RED))
        release.complete(Unit)
        assertSameFraming(panned, awaitMarker(Color.GREEN))
        doubleTap()
        assertSameFraming(initial, awaitMarker(Color.GREEN))
    }

    @Test
    fun samePinchContinuesOnTheOriginalWithKnownDimensions() = verifyPinch(knownSize = true)

    @Test
    fun samePinchContinuesOnThePreviewUntilUnknownDimensionsCanBeRemapped() = verifyPinch(knownSize = false)

    private fun verifyPinch(knownSize: Boolean) =
        withDelayedOriginal(knownSize) { loadPost, release, decoded ->
            val initial = awaitMarker(Color.RED)
            viewer.performTouchInput {
                down(0, center - Offset(64f, 0f))
                down(1, center + Offset(64f, 0f))
            }
            spreadFingers(90f)
            spreadFingers(120f)
            val before = awaitMarker(Color.RED)
            assertTrue("Pinching must magnify the preview: $initial -> $before", before.width > initial.width * 1.3f)
            loadPost()
            release.complete(Unit)
            compose.waitUntil(10_000) { decoded.isCompleted }
            val displayed = if (knownSize) Color.GREEN else Color.RED
            assertSameFraming(before, awaitMarker(displayed))
            spreadFingers(153f)
            spreadFingers(182f)
            val continued = awaitMarker(displayed)
            assertTrue("Both pointers must stay active across the handoff", continued.width > before.width * 1.3f)
            viewer.performTouchInput {
                repeat(8) { move(32) }
                up(1)
                up(0)
            }
            assertSameFraming(continued, awaitMarker(Color.GREEN))
        }

    private fun withDelayedOriginal(
        knownSize: Boolean,
        size: IntSize = IntSize(1600, 1200),
        previewSize: IntSize = size / 5,
        test: (loadPost: () -> Unit, release: CompletableDeferred<Unit>, decoded: CompletableDeferred<Unit>) -> Unit,
    ) {
        val preview = markerImage(previewSize.width, previewSize.height, Color.RED)
        val original = markerImage(size.width, size.height, Color.GREEN)
        val previewUrl = "https://images.example.invalid/${preview.name}"
        val originalUrl = "https://images.example.invalid/${original.name}"
        val release = CompletableDeferred<Unit>()
        val decoded = CompletableDeferred<Unit>()
        val previousLoader = context.imageLoader
        val loader =
            previousLoader
                .newBuilder()
                .components {
                    add(
                        Interceptor { chain ->
                            if (chain.request.data == originalUrl) {
                                release.await()
                                val local =
                                    chain.request
                                        .newBuilder()
                                        .data(original)
                                        .build()
                                chain.withRequest(local).proceed().also { decoded.complete(Unit) }
                            } else if (chain.request.data == previewUrl) {
                                chain
                                    .withRequest(
                                        chain.request
                                            .newBuilder()
                                            .data(preview)
                                            .build(),
                                    ).proceed()
                            } else {
                                chain.proceed()
                            }
                        },
                    )
                }.build()
        val image =
            UiMedia.Image(
                url = originalUrl,
                previewUrl = previewUrl,
                description = "Zoom fixture",
                width = if (knownSize) size.width.toFloat() else 0f,
                height = if (knownSize) size.height.toFloat() else 0f,
                sensitive = false,
                proxy = ProxyRef.Global,
            )
        val index = if (knownSize) 0 else 1
        val pages = if (knownSize) persistentListOf<UiMedia>(image) else persistentListOf(checkNotNull(image.preview), image)
        var medias by mutableStateOf<UiState<ImmutableList<UiMedia>>>(if (knownSize) UiState.Success(pages) else UiState.Loading())
        try {
            ComposeUiFlags.isMediaQueryIntegrationEnabled = true
            SingletonImageLoader.setUnsafe(loader)
            if (knownSize) {
                runBlocking {
                    val request =
                        ImageRequest
                            .Builder(
                                context,
                            ).media(checkNotNull(image.preview), ProxyNetwork.snapshot(image.proxy))
                            .build()
                    assertTrue(loader.execute(request) is SuccessResult)
                }
            }
            compose.setContent {
                CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance(showPostInMediaViewer = false),
                    LocalTimelineAppearance provides TimelineAppearance(),
                ) {
                    FlareTheme {
                        with(LocalDensity.current) {
                            Box(Modifier.size(640.toDp(), 960.toDp()).testTag("viewer")) {
                                MediaViewerScreen(
                                    medias = medias,
                                    initialIndex = index,
                                    preview = image.previewUrl,
                                    previewProxy = ProxyRef.Global,
                                    onDismiss = {},
                                    toAltText = {},
                                    uriHandler = LocalUriHandler.current,
                                    fileName = { original.name },
                                    fileNames = { emptyMap() },
                                )
                            }
                        }
                    }
                }
            }
            test({ compose.runOnIdle { medias = UiState.Success(pages) } }, release, decoded)
        } finally {
            release.complete(Unit)
            SingletonImageLoader.setUnsafe(previousLoader)
            loader.shutdown()
            preview.delete()
            original.delete()
        }
    }

    private fun doubleTap() {
        viewer.performTouchInput { doubleClick(Offset(width * 0.61f, height * 0.44f), delayMillis = 150) }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun spreadFingers(radius: Float) {
        viewer.performTouchInput {
            updatePointerTo(0, center - Offset(radius, 0f))
            updatePointerTo(1, center + Offset(radius, 0f))
            move(32)
        }
    }

    private fun markerImage(
        width: Int,
        height: Int,
        color: Int,
    ): File {
        val file = File.createTempFile("zoom-fixture", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val top = if (height > width) 0.21875f else 0.375f
        val bottom = if (height > width) 0.25f else 0.4375f
        Canvas(bitmap).drawRect(width * 0.4375f, height * top, width * 0.5f, height * bottom, Paint().apply { this.color = color })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun awaitMarker(color: Int): Rect {
        var marker: Rect? = null
        compose.waitUntil(10_000) {
            val bitmap = viewer.captureToImage().asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val bounds = android.graphics.Rect()
            for ((i, pixel) in pixels.withIndex()) {
                if (abs(Color.red(pixel) - Color.red(color)) < 128 &&
                    abs(Color.green(pixel) - Color.green(color)) < 128 && Color.blue(pixel) < 128
                ) {
                    val x = i % bitmap.width
                    val y = i / bitmap.width
                    bounds.union(x, y, x + 1, y + 1)
                }
            }
            if (!bounds.isEmpty) marker = bounds.toComposeRect()
            marker != null
        }
        return checkNotNull(marker)
    }

    private fun assertSameFraming(
        expected: Rect,
        actual: Rect,
    ) {
        assertTrue("Marker moved from $expected to $actual", (expected.center - actual.center).getDistance() <= 3f)
        assertTrue(
            "Marker resized from $expected to $actual",
            abs(expected.width - actual.width) <= 3f && abs(expected.height - actual.height) <= 3f,
        )
    }
}
