package dev.dimension.flare.ui.component.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Retweet
import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.model.IconType
import dev.dimension.flare.data.model.PostActionStyle
import dev.dimension.flare.data.model.TimelineDisplayMode
import dev.dimension.flare.data.model.TimelineMediaLayout
import dev.dimension.flare.data.model.VideoAutoplay
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.FlareDividerDefaults
import dev.dimension.flare.ui.component.LocalNetworkImageAllowHardware
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.platform.LocalWindowSizeClass
import dev.dimension.flare.ui.component.platform.PlatformTextStyle
import dev.dimension.flare.ui.component.platform.WindowSizeClass
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.TranslationDisplayState
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.createSampleStatus
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.model.toUiImage
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.theme.PlatformContentColor
import dev.dimension.flare.ui.theme.PlatformTheme
import io.github.composefluent.FluentTheme
import io.github.composefluent.darkColors
import io.github.composefluent.lightColors
import kotlinx.collections.immutable.persistentListOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class StatusHeaderSpacingTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        startKoin {
            modules(
                module {
                    single<PlatformFormatter> {
                        object : PlatformFormatter {
                            override fun formatNumber(number: Long) = number.toString()

                            override fun formatRelativeInstant(instant: Instant) = "5m"

                            override fun formatFullInstant(instant: Instant) = "5m"

                            override fun formatAbsoluteInstant(instant: Instant) = "5m"
                        }
                    }
                },
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun compactAuthorUsesTwoDpBeforeBodyAndFourBeforeReply() {
        assertBodyAndReplySpacing(isQuote = false)
    }

    @Test
    fun quotedAuthorUsesFourDpBeforeBodyAndReply() {
        assertBodyAndReplySpacing(isQuote = true)
    }

    @Test
    fun fullWidthAuthorUsesEightDpBeforeBodyAndFourBeforeReply() {
        assertBodyAndReplySpacing(isQuote = false, fullWidthPost = true)
    }

    @Test
    fun nonPostTranslationBadgesKeepCompactStateIconsAndThemeColor() {
        var state by mutableStateOf(TranslationDisplayState.Failed)
        var caption = Color.Unspecified
        compose.setContent {
            FluentTheme(colors = darkColors()) {
                caption = PlatformTheme.colorScheme.caption
                CompositionLocalProvider(
                    LocalStatusBackgroundColor provides Color.Black,
                    LocalDensity provides Density(1f),
                ) {
                    TranslationDisplayBadge(state, Modifier.background(Color.Black).testTag("non-post-badge"))
                }
            }
        }
        for (value in listOf(TranslationDisplayState.Failed, TranslationDisplayState.Translating)) {
            compose.runOnIdle { state = value }
            compose.mainClock.advanceTimeBy(1_000)
            val badge = compose.onNodeWithTag("non-post-badge")
            val bounds = badge.fetchSemanticsNode().boundsInRoot
            assertEquals(36f, bounds.width, 0.1f)
            assertEquals(20f, bounds.height, 0.1f)
            val pixels = badge.captureToImage().toPixelMap()
            val expected = caption.compositeOver(Color.Black).toArgb()
            check((0 until 20).any { x -> (0 until 20).any { y -> pixels[x, y].toArgb() == expected } })
        }
    }

    @Test
    fun attachmentsAndActionsUseTheIosSpacingGrid() {
        val file = Files.createTempFile("post-spacing-media-", ".png").toFile().apply { deleteOnExit() }
        val asset = BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB)
        asset.createGraphics().apply {
            color = java.awt.Color(42, 91, 105)
            fillRect(0, 0, 320, 180)
            dispose()
        }
        ImageIO.write(asset, "png", file)
        val url = file.toPath().toUri().toString()
        val body = "友達作るための行動したことないから当然の帰結だけど"
        val quoteBody = "文字と画像の間隔をそろえる。\n引用の内側にも余白を残す。"
        val sample =
            createSampleStatus(
                createSampleUser().copy(
                    nameInternal = "茶わらび".toUiPlainText(),
                    handle = UiHandle(raw = "Sawarabi_2000", host = "x.com", displayOverride = "@Sawarabi_2000"),
                    avatar = null,
                ),
            )
        val post =
            sample.copy(
                platformId = "X",
                platformIcon = IconType.Material(UiIcon.X),
                content = UiTranslatableText(body.toUiPlainText()),
                images = persistentListOf(UiMedia.Image(url, url, "Spacing reference media", 180f, 320f, false)),
            )
        val quote =
            sample.copy(
                user =
                    sample.user?.copy(
                        nameInternal = "引用ユーザー".toUiPlainText(),
                        handle = UiHandle(raw = "quoted", host = "x.com", displayOverride = "@quoted"),
                    ),
                content = UiTranslatableText(quoteBody.toUiPlainText()),
                actions = persistentListOf(),
            )
        var translation by mutableStateOf(TranslationDisplayState.Translated)
        lateinit var bodyStyle: TextStyle
        var mediaBorderColor = Color.Unspecified
        compose.setContent {
            FluentTheme(colors = darkColors()) {
                bodyStyle = PlatformTheme.typography.postBody
                mediaBorderColor = FlareDividerDefaults.color
                CompositionLocalProvider(
                    LocalTimelineAppearance provides TimelineAppearance(fullWidthPost = false, showPlatformLogo = true),
                    LocalNetworkImageAllowHardware provides false,
                    LocalStatusBackgroundColor provides Color.Black,
                    LocalDensity provides Density(1f),
                ) {
                    Box(Modifier.width(402.dp).background(Color.Black).testTag("component-spacing")) {
                        CommonStatusComponent(
                            post.copy(translationDisplayState = translation),
                            quotes = persistentListOf(quote),
                            isClickable = false,
                            modifier = Modifier.padding(start = 10.dp, top = 12.dp, end = 10.dp, bottom = 8.dp),
                            actionsModifier = Modifier.testTag("spacing-actions"),
                        )
                    }
                }
            }
        }
        val mediaNode = compose.onNodeWithContentDescription("Spacing reference media", useUnmergedTree = true)
        val fill = Color(0xFF2A5B69).toArgb()
        compose.waitUntil(timeoutMillis = 5_000) {
            val pixels = mediaNode.captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2].toArgb() == fill
        }
        val bodyBounds = compose.onNodeWithText(body, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val media = mediaNode.fetchSemanticsNode().boundsInRoot
        val quoteText = compose.onNodeWithText(quoteBody, useUnmergedTree = true).fetchSemanticsNode()
        val quoteFrame =
            generateSequence(quoteText.layoutInfo.coordinates.parentLayoutCoordinates) { it.parentLayoutCoordinates }
                .map { it.boundsInRoot() }
                .first { it.width >= media.width - 0.1f }
        val actions = compose.onNodeWithTag("spacing-actions").fetchSemanticsNode().boundsInRoot
        assertEquals(10f, media.top - bodyBounds.bottom, 0.1f)
        assertEquals(8f, quoteFrame.top - media.bottom, 0.1f)
        assertEquals(12f, quoteText.boundsInRoot.left - quoteFrame.left, 0.1f)
        assertEquals(12f, quoteFrame.bottom - quoteText.boundsInRoot.bottom, 0.1f)
        assertEquals(2f, actions.top - quoteFrame.bottom, 0.1f)
        assertEquals(bodyBounds.left - 4f, actions.left, 0.1f)
        val count = compose.onNodeWithText("10", useUnmergedTree = true)
        val countLayouts = mutableListOf<TextLayoutResult>()
        count.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(countLayouts) }
        val countStyle = countLayouts.single().layoutInput.style
        assertEquals(bodyStyle.fontSize.value - 4, countStyle.fontSize.value)
        assertEquals(bodyStyle.lineHeight, countStyle.lineHeight)
        assertEquals(bodyStyle.letterSpacing, countStyle.letterSpacing)
        assertEquals(26f, count.fetchSemanticsNode().boundsInRoot.left - actions.left, 0.1f)
        for ((state, label) in listOf(
            TranslationDisplayState.Translating to "Translating…",
            TranslationDisplayState.Failed to "Translation failed",
            TranslationDisplayState.Translated to "Translated",
        )) {
            compose.runOnIdle { translation = state }
            for (description in listOf("Platform: X", label)) {
                val icons = compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes()
                check(icons.isNotEmpty())
                icons.forEach { assertEquals(bodyStyle.fontSize.value, it.boundsInRoot.height, 0.1f) }
            }
        }
        val mediaPixels = mediaNode.captureToImage().toPixelMap()
        val expectedBorder = Color(mediaBorderColor.compositeOver(Color(fill)).toArgb())
        val borderPixel = mediaPixels[mediaPixels.width / 2, 0]
        assertEquals(expectedBorder.red, borderPixel.red, 0.004f, "Media border red")
        assertEquals(expectedBorder.green, borderPixel.green, 0.004f, "Media border green")
        assertEquals(expectedBorder.blue, borderPixel.blue, 0.004f, "Media border blue")
        assertEquals(fill, mediaPixels[mediaPixels.width / 2, 1].toArgb(), "First pixel inside the media border")
        System.getenv("FLARE_POST_SCREENSHOT")?.let { path ->
            val pixels = compose.onNodeWithTag("component-spacing").captureToImage().toPixelMap()
            val directory = File(path).parentFile.apply { mkdirs() }
            val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) screenshot.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(screenshot, "png", File(directory, "compose-components.png"))
            File(directory, "compose-component-bounds.txt").writeText("body=$bodyBounds\nmedia=$media\nquote=$quoteFrame\nactions=$actions")
        }
    }

    @Test
    fun postActionGlyphsShrinkInsideTheOriginalSlots() {
        val action = ActionMenu.Item(icon = UiIcon.Reply)
        val items = persistentListOf(action, ActionMenu.Group(displayItem = action, actions = persistentListOf(action)))
        compose.setContent {
            FluentTheme(colors = darkColors()) {
                CompositionLocalProvider(
                    LocalTimelineAppearance provides TimelineAppearance(postActionStyle = PostActionStyle.RightAligned),
                    LocalDensity provides Density(1f),
                ) {
                    Column {
                        StatusActions(items, modifier = Modifier.background(Color.Black).testTag("post-actions"))
                        StatusActions(items, modifier = Modifier.background(Color.Black).testTag("profile-actions"), isPost = false)
                    }
                }
            }
        }

        for ((tag, expectedGlyphWidth) in listOf("post-actions" to 14, "profile-actions" to 16)) {
            val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            assertEquals(24, pixels.height, tag)
            val middle = pixels.width / 2
            for (section in listOf(0 until middle, middle until pixels.width)) {
                val glyphColumns = section.filter { x -> (0 until pixels.height).any { y -> pixels[x, y] != Color.Black } }
                assertEquals(expectedGlyphWidth, glyphColumns.last() - glyphColumns.first() + 1, tag)
            }
        }
    }

    @Test
    fun compactPostFoldingKeepsTheEntireLastVisibleLine() {
        var body by mutableStateOf(List(15) { "漢字间距与行距" }.joinToString("\n"))
        showPost(isQuote = false) {
            samplePost().copy(content = UiTranslatableText(body.toUiPlainText()))
        }
        for (lineCount in listOf(15, 20)) {
            compose.runOnIdle { body = List(lineCount) { "漢字间距与行距" }.joinToString("\n") }
            val node = compose.onNodeWithText(body, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val expectedHeight = if (lineCount == 15) layout.size.height else ceil(layout.getLineBottom(4)).toInt()
            assertEquals(expectedHeight.toFloat(), node.fetchSemanticsNode().boundsInRoot.height, 0.1f, "$lineCount lines")
        }
    }

    @Test
    fun renderedUserNamesAreBoldWhileHandlesAndRepostLabelsStayRegular() {
        val user = checkNotNull(samplePost().user)
        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(
                    LocalTimelineAppearance provides TimelineAppearance(),
                    PlatformTextStyle provides PlatformTheme.typography.body,
                ) {
                    Column(Modifier.width(440.dp)) {
                        CommonStatusHeaderComponent(
                            data = user.copy(nameInternal = "Full width author".toUiPlainText()),
                            subtitle = "full-width-handle",
                            isPost = true,
                        )
                        UserCompat(
                            user = user.copy(nameInternal = "Compact author".toUiPlainText()),
                            subtitle = "compact-handle",
                            isPost = true,
                        )
                        StatusRetweetHeaderComponent(
                            icon = FontAwesomeIcons.Solid.Retweet,
                            user = user.copy(nameInternal = "Reposting author".toUiPlainText()),
                            text = "reposted",
                            iconColor = StatusComponentDefaults.secondaryColor,
                            textColor = StatusComponentDefaults.contentColor,
                            textStyle = PlatformTheme.typography.postBody,
                            isPost = true,
                        )
                        UserCompat(
                            user = user.copy(nameInternal = "Profile name".toUiPlainText()),
                            subtitle = "profile-handle",
                        )
                        CompositionLocalProvider(LocalEffectiveTimelineDisplayMode provides TimelineDisplayMode.Gallery) {
                            UserCompat(
                                user = user.copy(nameInternal = "Gallery author".toUiPlainText()),
                                subtitle = "gallery-handle",
                                isPost = true,
                            )
                        }
                    }
                }
            }
        }
        val expectedWeights =
            mapOf(
                "Full width author" to FontWeight.Bold,
                "Compact author" to FontWeight.Bold,
                "Reposting author" to FontWeight.Bold,
                "full-width-handle" to FontWeight.Normal,
                "compact-handle" to FontWeight.Normal,
                "reposted" to FontWeight.Normal,
                "Profile name" to FontWeight.Bold,
                "Gallery author" to FontWeight.ExtraBold,
            )
        for ((text, expectedWeight) in expectedWeights) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText(
                    text,
                    useUnmergedTree = true,
                ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val input = layouts.single().layoutInput
            val weights =
                input.text.indices
                    .map { offset ->
                        input.text.spanStyles
                            .lastOrNull { offset in it.start until it.end && it.item.fontWeight != null }
                            ?.item
                            ?.fontWeight ?: input.style.fontWeight ?: FontWeight.Normal
                    }.toSet()
            assertEquals(setOf(expectedWeight), weights, text)
        }
    }

    @Test
    fun timelineRowsShareThePostInsetsAndMessageStyle() {
        val avatar =
            previewAvatar
                .toPath()
                .toUri()
                .toString()
                .toUiImage()
        val author =
            createSampleUser().copy(
                nameInternal = "Alex Rivera".toUiPlainText(),
                handle = UiHandle("alex", "x.com", displayOverride = "@alex"),
                avatar = avatar,
            )
        val post =
            createSampleStatus(author).copy(
                content = UiTranslatableText("A quiet afternoon at the coast.".toUiPlainText()),
                images = persistentListOf(),
            )
        val mei = author.copy(nameInternal = "Mei Chen".toUiPlainText(), handle = UiHandle("mei", "x.com", displayOverride = "@mei"))
        val repost =
            UiTimelineV2.Message(
                user = author.copy(nameInternal = "Sam Lee".toUiPlainText()),
                statusKey = post.statusKey,
                icon = UiIcon.Retweet,
                type = UiTimelineV2.Message.Type.Raw("reposted"),
                createdAt = post.createdAt,
                clickEvent = ClickEvent.Noop,
                accountType = post.accountType,
            )
        val rows =
            listOf(
                UiTimelineV2.TimelinePostItem(post, UiTimelineV2.PostPresentation(message = repost)),
                UiTimelineV2.User(
                    repost.copy(
                        user = author.copy(nameInternal = "Jordan Kim".toUiPlainText()),
                        icon = UiIcon.Follow,
                        type = UiTimelineV2.Message.Type.Raw("followed you"),
                    ),
                    mei,
                    post.createdAt,
                    post.statusKey,
                    accountType = post.accountType,
                ),
                repost.copy(
                    user = author.copy(nameInternal = "Flare Team".toUiPlainText()),
                    icon = UiIcon.Pin,
                    type = UiTimelineV2.Message.Type.Raw("pinned a post"),
                ),
            )
        var mode by mutableStateOf(TimelineDisplayMode.Plain)
        compose.setContent {
            FluentTheme(colors = darkColors()) {
                CompositionLocalProvider(
                    LocalTimelineAppearance provides TimelineAppearance(timelineDisplayMode = mode, showPlatformLogo = false),
                    LocalNetworkImageAllowHardware provides false,
                    LocalDensity provides Density(1f),
                    PlatformTextStyle provides PlatformTheme.typography.body,
                ) {
                    LazyStatusVerticalStaggeredGrid(
                        modifier =
                            Modifier
                                .width(402.dp)
                                .height(310.dp)
                                .background(Color(0xFF101010))
                                .testTag("timeline"),
                        columns = StaggeredGridCells.Fixed(1),
                        allowGalleryMode = true,
                    ) {
                        items(rows.size) { index ->
                            val modifier = Modifier.testTag("row-$index")
                            if (mode == TimelineDisplayMode.Gallery) {
                                GalleryTimelineItem(rows[index], modifier)
                            } else {
                                AdaptiveCard(modifier, index, rows.size, respectTimelineMode = true) {
                                    StatusItem(rows[index])
                                }
                            }
                        }
                    }
                }
            }
        }
        val timeline = compose.onNodeWithTag("timeline")
        val bounds = timeline.fetchSemanticsNode().boundsInRoot

        fun userAvatar(handle: String) =
            compose.onNodeWithContentDescription("Open profile for $handle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        fun textBounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        fun textStyle(text: String): TextStyle {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText(
                    text,
                    useUnmergedTree = true,
                ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style
        }
        for ((displayMode, inset) in listOf(TimelineDisplayMode.Plain to 0f, TimelineDisplayMode.Card to 8f)) {
            compose.runOnIdle { mode = displayMode }
            for (index in rows.indices) {
                val row = compose.onNodeWithTag("row-$index", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals(bounds.left + inset, row.left, 0.1f)
                assertEquals(bounds.right - inset, row.right, 0.1f)
            }
            val avatarBounds = userAvatar("@mei")
            assertEquals(bounds.left + inset + 10, avatarBounds.left, 0.1f)
            assertEquals(bounds.left + inset + 10, userAvatar("@alex").left, 0.1f)
            assertEquals(avatarBounds.center.y, (textBounds("Mei Chen").top + textBounds("@mei").bottom) / 2, 1f)
            assertEquals(FontWeight.Bold, textStyle("Mei Chen").fontWeight)
            val postBodyStyle = textStyle("A quiet afternoon at the coast.")
            val headerColor = textStyle("@alex").color
            val messages =
                listOf(
                    Triple("Sam Lee", "reposted", headerColor),
                    Triple("Jordan Kim", "followed you", headerColor),
                    Triple("Flare Team", "pinned a post", postBodyStyle.color),
                )
            for ((name, message, color) in messages) {
                val nameStyle = textStyle(name)
                val messageStyle = textStyle(message)
                assertEquals(messageStyle.fontSize, nameStyle.fontSize, name)
                assertEquals(FontWeight.Bold, nameStyle.fontWeight)
                assertEquals(color, nameStyle.color, name)
                assertEquals(color, messageStyle.color, message)
                assertEquals(postBodyStyle.fontSize.value - if (message == "reposted") 2 else 0, messageStyle.fontSize.value, message)
            }
            System.getenv("FLARE_TIMELINE_PREVIEW_DIR")?.let { path ->
                val pixels = timeline.captureToImage().toPixelMap()
                val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
                ImageIO.write(image, "png", File(path, "desktop-${displayMode.name.lowercase()}.png"))
            }
        }
        compose.runOnIdle { mode = TimelineDisplayMode.Gallery }
        val galleryTile = compose.onNodeWithTag("row-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.left + 16, galleryTile.left, 0.1f)
    }

    @Test
    fun adaptivePlainTimelineUsesCardsOnlyWhenTwoLanesFit() {
        var width by mutableStateOf(650.dp)
        var windowSize by mutableStateOf(WindowSizeClass.Medium)
        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(
                    LocalTimelineAppearance provides TimelineAppearance(timelineDisplayMode = TimelineDisplayMode.Plain),
                    LocalWindowSizeClass provides windowSize,
                    LocalDensity provides Density(1f),
                ) {
                    LazyStatusVerticalStaggeredGrid(modifier = Modifier.width(width).height(100.dp)) {
                        items(2) { index ->
                            AdaptiveCard(index = index, totalCount = 2, respectTimelineMode = true) {
                                Box(Modifier.fillMaxWidth().height(24.dp).testTag("lane-$index"))
                            }
                        }
                    }
                }
            }
        }

        fun bounds(index: Int) = compose.onNodeWithTag("lane-$index").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, bounds(0).left, 0.1f)
        assertTrue(bounds(1).top >= bounds(0).bottom)

        compose.runOnIdle { width = 660.dp }
        assertEquals(8f, bounds(0).left, 0.1f)
        assertEquals(652f, bounds(1).right, 0.1f)
        assertEquals(bounds(0).top, bounds(1).top, 0.1f)

        compose.runOnIdle {
            width = 402.dp
            windowSize = WindowSizeClass.Expanded
        }
        assertEquals(0f, bounds(0).left, 0.1f)
        assertTrue(bounds(1).top >= bounds(0).bottom)
    }

    @Test
    fun standaloneTimelineMessageKeepsItsActorWhenTheBodyWraps() {
        val name = "Flare Team"
        val body = "Pinned a post about the next community meetup, with registration details and directions to the venue."
        val post = samplePost()
        val message =
            UiTimelineV2.Message(
                user = checkNotNull(post.user).copy(nameInternal = name.toUiPlainText()),
                statusKey = post.statusKey,
                icon = UiIcon.Pin,
                type = UiTimelineV2.Message.Type.Raw(body),
                createdAt = post.createdAt,
                clickEvent = ClickEvent.Noop,
                accountType = post.accountType,
            )
        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(
                    LocalTimelineAppearance provides TimelineAppearance(),
                    LocalDensity provides Density(1f),
                    PlatformTextStyle provides PlatformTheme.typography.body,
                ) {
                    StatusItem(message, modifier = Modifier.width(240.dp))
                }
            }
        }

        val actor = compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(actor.width > 40f, "The actor should keep room for a readable name, width was ${actor.width}")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(body, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1, "The body should wrap inside the 240dp row")
    }

    @Test
    fun darkPostCalibrationPreservesThemeColorsOutsidePlainBlackPosts() {
        val body =
            "《少女前线2》 × SSSS.GRIDMAN 联动\n" +
                "2027年予定\n" +
                "将2名女主角作为角色实装\n" +
                "漢字漢字漢字漢字漢字\n" +
                "文字间距与行距保持一致。\n" +
                "文字间距与行距保持一致。\n" +
                "English spacing 0123456789\n" +
                "English spacing 0123456789"
        val author = "K12s"
        val reposter = "凪風　27年のお仕事積極募集中"
        val avatarUrl = previewAvatar.toURI().toString()
        val post =
            createSampleStatus(
                createSampleUser().copy(
                    nameInternal = author.toUiPlainText(),
                    handle = UiHandle("kzwolf123", "x.com", displayOverride = "@kzwolf123"),
                    platformId = "X",
                    avatar = avatarUrl.toUiImage(),
                ),
            ).copy(
                content = UiTranslatableText(body.toUiPlainText()),
                images = persistentListOf(UiMedia.Image(avatarUrl, avatarUrl, "Sample media", 180f, 320f, false)),
                createdAt = (Clock.System.now() - 5.minutes).toUi(),
            )
        val timestamp = post.createdAt.relative
        val item =
            UiTimelineV2.TimelinePostItem(
                post = post,
                presentation =
                    UiTimelineV2.PostPresentation(
                        message =
                            UiTimelineV2.Message(
                                user = checkNotNull(post.user).copy(nameInternal = reposter.toUiPlainText()),
                                statusKey = post.statusKey,
                                icon = UiIcon.Retweet,
                                type = UiTimelineV2.Message.Type.Localized(UiTimelineV2.Message.Type.Localized.MessageId.Repost),
                                createdAt = post.createdAt,
                                clickEvent = ClickEvent.Noop,
                                accountType = post.accountType,
                            ),
                    ),
            )
        var dark by mutableStateOf(true)
        var fullWidth by mutableStateOf(false)
        var background by mutableStateOf<Color?>(Color.Black)
        var useCard by mutableStateOf(false)
        var multipleColumns by mutableStateOf(false)
        lateinit var density: Density
        lateinit var themeBody: TextStyle
        var themeText = Color.Unspecified
        var themeCaption = Color.Unspecified
        var emphasis = Color.Unspecified
        compose.setContent {
            FluentTheme(colors = if (dark) darkColors() else lightColors()) {
                density = LocalDensity.current
                themeBody = PlatformTheme.typography.body
                themeCaption = PlatformTheme.colorScheme.caption
                emphasis = PlatformTheme.colorScheme.primaryContainer
                themeText =
                    if (background == emphasis) PlatformTheme.colorScheme.onPrimaryContainer else PlatformTheme.colorScheme.text
                CompositionLocalProvider(
                    LocalTimelineAppearance provides TimelineAppearance(fullWidthPost = fullWidth, showPlatformLogo = false),
                    LocalMultipleColumns provides multipleColumns,
                    LocalStatusBackgroundColor provides background,
                    PlatformTextStyle provides PlatformTheme.typography.body,
                    PlatformContentColor provides themeText,
                ) {
                    Box(Modifier.width(402.dp).background(background ?: Color.Black).testTag("post-calibration")) {
                        if (useCard) {
                            AdaptiveCard(totalCount = 1) { UiTimelineComponent(item) }
                        } else {
                            UiTimelineComponent(item)
                        }
                    }
                }
            }
        }

        fun layout(text: String): TextLayoutResult {
            val result = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                it(result)
            }
            return result.single()
        }
        val texts =
            listOf(
                "body" to body,
                "author" to author,
                "handle" to "@kzwolf123",
                "repost" to reposter,
                "timestamp" to timestamp,
            ).map { (role, text) ->
                CalibrationText(role, compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot, layout(text))
            }
        val container = compose.onNodeWithTag("post-calibration")
        val bounds = container.fetchSemanticsNode().boundsInRoot
        assertEquals(402f * density.density, bounds.width, 1f)
        val postBodyStyle =
            texts
                .first { it.role == "body" }
                .layout.layoutInput.style
        assertEquals(Color(0xFFD9D9D9), postBodyStyle.color)
        assertEquals(themeBody.lineHeight, postBodyStyle.lineHeight)
        assertEquals(themeBody.letterSpacing, postBodyStyle.letterSpacing)
        assertEquals(Color(0xFF71767B), layout("@kzwolf123").layoutInput.style.color)
        val header = rowBounds(author, timestamp)
        assertEquals(2f * density.density, header.top - rowBounds(reposter, "reposted a post").bottom, 0.1f)
        assertEquals(2f * density.density, texts.first().bounds.top - header.bottom, 0.1f)
        System.getenv("FLARE_POST_SCREENSHOT")?.let { path ->
            val directory = File(path).parentFile.apply { mkdirs() }
            for ((wide, name) in listOf(false to "compose-post-calibration.png", true to "compose-post-full-width.png")) {
                compose.runOnIdle { fullWidth = wide }
                val avatar =
                    compose
                        .onAllNodesWithContentDescription("Open profile for @kzwolf123", useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .first { it.boundsInRoot.width >= 40 * density.density }
                        .boundsInRoot.center - bounds.topLeft
                compose.waitUntil(5_000) {
                    val pixels = container.captureToImage().toPixelMap()
                    pixels[avatar.x.toInt(), avatar.y.toInt()] == Color(0xFF5B80A5)
                }
                val pixels = container.captureToImage().toPixelMap()
                val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) screenshot.setRGB(x, y, pixels[x, y].toArgb())
                }
                ImageIO.write(screenshot, "png", File(directory, name))
            }
            compose.runOnIdle { fullWidth = false }
            File(directory, "compose-post-calibration.json").writeText(postCalibrationJson(density, themeBody, bounds, timestamp, texts))
        }
        compose.runOnIdle { background = Color(0xFF202020) }
        assertEquals(Color(0xFFD9D9D9), layout(body).layoutInput.style.color)
        assertEquals(themeCaption, layout("@kzwolf123").layoutInput.style.color)
        compose.runOnIdle { useCard = true }
        assertEquals(Color(0xFFD9D9D9), layout(body).layoutInput.style.color)
        assertEquals(themeCaption, layout("@kzwolf123").layoutInput.style.color)
        compose.runOnIdle { multipleColumns = true }
        assertEquals(Color(0xFFD9D9D9), layout(body).layoutInput.style.color)
        assertEquals(themeCaption, layout("@kzwolf123").layoutInput.style.color)
        compose.runOnIdle {
            useCard = false
            multipleColumns = false
        }
        for (fallback in listOf(null, Color(0x80202020), Color(0xFF999999), emphasis)) {
            compose.runOnIdle { background = fallback }
            val fallbackBodyColor = layout(body).layoutInput.style.color
            assertEquals(themeText, fallbackBodyColor)
            assertEquals(themeCaption, layout("@kzwolf123").layoutInput.style.color)
        }
        compose.runOnIdle {
            dark = false
            background = Color.White
        }
        val lightBodyColor = layout(body).layoutInput.style.color
        assertEquals(themeText, lightBodyColor)
        assertEquals(themeCaption, layout("@kzwolf123").layoutInput.style.color)
    }

    private fun assertBodyAndReplySpacing(
        isQuote: Boolean,
        fullWidthPost: Boolean = false,
    ) {
        val headerGap =
            when {
                isQuote -> 4f
                fullWidthPost -> 8f
                else -> 2f
            }
        val sample = samplePost()
        var post by mutableStateOf(sample)
        showPost(isQuote = isQuote, fullWidthPost = fullWidthPost) { post }
        for (warning in listOf(false, true)) {
            for (reply in listOf(false, true)) {
                compose.runOnIdle {
                    post =
                        sample.copy(
                            contentWarning = if (warning) UiTranslatableText("Warning".toUiPlainText()) else null,
                            replyToHandle = if (reply) "@someone@example.org" else null,
                        )
                }
                val header = headerBounds()
                val preceding =
                    if (reply) {
                        val prompt =
                            compose
                                .onNodeWithText("@someone@example.org", substring = true, useUnmergedTree = true)
                                .fetchSemanticsNode()
                                .boundsInRoot
                        assertEquals(4f, prompt.top - header.bottom, 0.1f)
                        prompt
                    } else {
                        header
                    }
                val body = compose.onNodeWithText("Body", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                if (warning) {
                    val warningBounds = compose.onNodeWithText("Warning", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    assertEquals(if (reply) 4f else headerGap, warningBounds.top - preceding.bottom, 0.1f)
                    assertEquals(8f, body.top - warningBounds.bottom, 0.1f)
                } else {
                    assertEquals(if (reply) 4f else headerGap, body.top - preceding.bottom, 0.1f)
                }
            }
        }
        compose.runOnIdle {
            post =
                sample.copy(
                    user = null,
                    replyToHandle = "@someone@example.org",
                    content = UiTranslatableText("".toUiPlainText(), "Body".toUiPlainText()),
                    translationDisplayState = TranslationDisplayState.Translated,
                )
        }
        val prompt =
            compose
                .onNodeWithText("@someone@example.org", substring = true, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val body = compose.onNodeWithText("Body", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(4f, body.top - prompt.bottom, 0.1f)
    }

    @Test
    fun inlineConversationHidesReplyPromptsWhileStandaloneAndQuotesKeepThem() {
        val user =
            createSampleUser().copy(
                nameInternal = "Morgan".toUiPlainText(),
                handle = UiHandle(raw = "morgan", host = "example.org"),
                avatar = previewAvatar.toURI().toString().toUiImage(),
            )
        val sample = createSampleStatus(user).copy(visibility = UiTimelineV2.Post.Visibility.Public)
        val parent =
            sample.copy(
                statusKey = MicroBlogKey("parent", "example.org"),
                content = UiTranslatableText("A quiet morning by the river.\nThe path is open again after the rain.".toUiPlainText()),
                replyToHandle = "@hidden-origin",
            )
        val quote =
            sample.copy(
                statusKey = MicroBlogKey("quote", "example.org"),
                content = UiTranslatableText("The small footbridge is a good place to start.".toUiPlainText()),
                replyToHandle = "@visible-quote",
            )
        val child =
            sample.copy(
                statusKey = MicroBlogKey("child", "example.org"),
                content = UiTranslatableText("Thanks for the update.\nWe will take a walk there this afternoon.".toUiPlainText()),
                replyToHandle = "@hidden-parent",
            )
        val standalone =
            sample.copy(
                statusKey = MicroBlogKey("standalone", "example.org"),
                content = UiTranslatableText("There is also a cafe near the entrance.".toUiPlainText()),
                replyToHandle = "@visible-standalone",
            )
        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(
                    LocalTimelineAppearance provides TimelineAppearance(fullWidthPost = false, showPlatformLogo = false),
                    LocalDensity provides Density(1f),
                ) {
                    Column(Modifier.width(440.dp).background(PlatformTheme.colorScheme.card)) {
                        UiTimelineComponent(
                            UiTimelineV2.TimelinePostItem(
                                post = child,
                                presentation =
                                    UiTimelineV2.PostPresentation(
                                        inlineParents = persistentListOf(UiTimelineV2.TimelinePostItem(post = parent)),
                                        quotes = persistentListOf(quote),
                                    ),
                            ),
                        )
                        UiTimelineComponent(standalone)
                    }
                }
            }
        }
        compose.onNodeWithText("@hidden-origin", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("@hidden-parent", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("@visible-quote", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("@visible-standalone", substring = true, useUnmergedTree = true).assertIsDisplayed()
        System.getenv("FLARE_POST_SCREENSHOT")?.let { path ->
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    screenshot.setRGB(x, y, pixels[x, y].toArgb())
                }
            }
            ImageIO.write(screenshot, "png", File(path))
        }
    }

    @Test
    fun mediaOnlyPostUsesTenDpGap() {
        assertMediaSpacing(isQuote = false)
    }

    @Test
    fun quotedMediaOnlyPostUsesTenDpGap() {
        assertMediaSpacing(isQuote = true)
    }

    @Test
    fun fullWidthMediaOnlyPostUsesEightDpGap() {
        assertMediaSpacing(isQuote = false, fullWidthPost = true)
    }

    @Test
    fun replyContextKeepsFourDpWhenActionsComeNext() {
        val sample = samplePost()
        val reply =
            sample.copy(
                content = UiTranslatableText("".toUiPlainText()),
                replyToHandle = "@context",
                actions = createSampleStatus(checkNotNull(sample.user)).actions,
            )
        showPost(isQuote = false) { reply }
        val prompt = compose.onNodeWithText("@context", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("post-actions").fetchSemanticsNode().boundsInRoot
        assertEquals(4f, prompt.top - headerBounds().bottom, 0.1f)
        assertEquals(4f, actions.top - prompt.bottom, 0.1f)
    }

    @Test
    fun replyCarouselClearsTheSideAvatar() {
        val url = previewAvatar.toPath().toUri().toString()
        val image = UiMedia.Image(url, url, "First carousel image", 48f, 48f, false)
        val reply =
            samplePost().copy(
                content = UiTranslatableText("".toUiPlainText()),
                replyToHandle = "@context",
                images = persistentListOf(image, image.copy(description = "Second carousel image")),
            )
        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(
                    LocalTimelineAppearance provides
                        TimelineAppearance(fullWidthPost = false, mediaLayout = TimelineMediaLayout.Carousel, showPlatformLogo = false),
                    LocalDensity provides Density(1f, fontScale = 0.75f),
                ) {
                    Box(Modifier.width(360.dp)) {
                        CommonStatusComponent(reply, isClickable = false, allowMediaCarousel = true)
                    }
                }
            }
        }
        val avatarLabel = "Open profile for ${checkNotNull(reply.user).handle.display}"
        val avatar = compose.onNodeWithContentDescription(avatarLabel, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val media = compose.onNodeWithContentDescription("First carousel image", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(media.top >= avatar.bottom, "Carousel starts at ${media.top}, above the avatar bottom at ${avatar.bottom}")
    }

    @Test
    fun galleryUserListKeepsExistingReplySpacing() {
        val reply = samplePost().copy(replyToHandle = "@context")
        val userList =
            UiTimelineV2.UserList(
                message = null,
                users = persistentListOf(checkNotNull(reply.user).copy(nameInternal = "Suggested user".toUiPlainText())),
                createdAt = reply.createdAt,
                statusKey = reply.statusKey,
                post = reply,
                accountType = reply.accountType,
            )
        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(
                    LocalTimelineAppearance provides TimelineAppearance.Default,
                    LocalDensity provides Density(1f),
                ) {
                    Box(Modifier.width(360.dp)) {
                        GalleryTimelineItem(userList)
                    }
                }
            }
        }
        val prompt = compose.onNodeWithText("@context", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithText("Body", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(8f, prompt.top - headerBounds().bottom, 0.1f)
        assertEquals(8f, body.top - prompt.bottom, 0.1f)
    }

    private fun assertMediaSpacing(
        isQuote: Boolean,
        fullWidthPost: Boolean = false,
    ) {
        val image = Files.createTempFile("header-spacing-", ".png").toFile().apply { deleteOnExit() }
        ImageIO.write(BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "png", image)
        val url = image.toPath().toUri().toString()
        var post by mutableStateOf(
            samplePost().copy(
                content = UiTranslatableText("".toUiPlainText()),
                images = persistentListOf(UiMedia.Video(url, url, "Preview", height = 200f, width = 200f)),
            ),
        )
        showPost(isQuote = isQuote, fullWidthPost = fullWidthPost) { post }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithContentDescription("Preview", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .any { it.boundsInRoot.height > 100 }
        }

        fun mediaBounds(): Rect =
            compose
                .onAllNodesWithContentDescription("Preview", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .maxBy { it.boundsInRoot.height }
                .boundsInRoot

        assertEquals(if (fullWidthPost) 8f else 10f, mediaBounds().top - headerBounds().bottom, 0.1f)
        compose.runOnIdle { post = post.copy(replyToHandle = "@context") }
        val prompt = compose.onNodeWithText("@context", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(4f, prompt.top - headerBounds().bottom, 0.1f)
        assertEquals(4f, mediaBounds().top - prompt.bottom, 0.1f)
    }

    private fun headerBounds(): Rect = rowBounds("Reader", "5m")

    private val previewAvatar by lazy {
        Files.createTempFile("post-layout-avatar-", ".png").toFile().apply {
            deleteOnExit()
            val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                color = java.awt.Color(91, 128, 165)
                fillRect(0, 0, 48, 48)
                dispose()
            }
            ImageIO.write(image, "png", this)
        }
    }

    private fun rowBounds(
        name: String,
        trailing: String,
    ): Rect {
        var coordinates =
            compose
                .onNodeWithText(name, useUnmergedTree = true)
                .fetchSemanticsNode()
                .layoutInfo.coordinates
        val timestamp =
            compose
                .onNodeWithText(trailing, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.center
        while (!coordinates.boundsInRoot().contains(timestamp)) {
            coordinates = checkNotNull(coordinates.parentLayoutCoordinates)
        }
        return coordinates.boundsInRoot()
    }

    private fun samplePost() =
        createSampleStatus(createSampleUser().copy(nameInternal = "Reader".toUiPlainText(), avatar = null)).copy(
            content = UiTranslatableText("Body".toUiPlainText()),
            actions = persistentListOf(),
        )

    private fun showPost(
        isQuote: Boolean,
        fullWidthPost: Boolean = false,
        post: () -> UiTimelineV2.Post,
    ) {
        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(
                    LocalTimelineAppearance provides
                        TimelineAppearance(
                            fullWidthPost = fullWidthPost,
                            expandContentWarning = true,
                            showPlatformLogo = false,
                            videoAutoplay = VideoAutoplay.NEVER,
                        ),
                    PlatformTextStyle provides PlatformTheme.typography.body,
                    LocalDensity provides Density(1f),
                ) {
                    Box(Modifier.width(360.dp)) {
                        CommonStatusComponent(
                            post(),
                            isQuote = isQuote,
                            isClickable = false,
                            actionsModifier = Modifier.testTag("post-actions"),
                        )
                    }
                }
            }
        }
    }
}
