package dev.dimension.flare.ui.screen.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import dev.dimension.flare.common.PagingItems
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.toPagingState
import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.PostActionFamily
import dev.dimension.flare.data.model.ProxyMode
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.data.repository.RequireReLoginException
import dev.dimension.flare.data.repository.SessionChallengeCheckResult
import dev.dimension.flare.data.repository.SessionChallengeCookieProbe
import dev.dimension.flare.data.repository.SessionChallengeCookieRequirement
import dev.dimension.flare.data.repository.SessionChallengeCookieSnapshot
import dev.dimension.flare.data.repository.SessionChallengeException
import dev.dimension.flare.data.repository.SessionChallengeHandle
import dev.dimension.flare.data.repository.SessionChallengePromptBus
import dev.dimension.flare.data.repository.SessionChallengeSeedCookie
import dev.dimension.flare.data.translation.image.ImageTranslationError
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.ErrorContent
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalSessionChallengeLauncher
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiMangaChapter
import dev.dimension.flare.ui.model.UiMangaChapterGroup
import dev.dimension.flare.ui.model.UiMangaDetail
import dev.dimension.flare.ui.model.UiMangaStatus
import dev.dimension.flare.ui.model.UiMangaTag
import dev.dimension.flare.ui.model.UiMangaTagGroup
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.presenter.manga.ChapterBoundary
import dev.dimension.flare.ui.presenter.manga.MangaDetailPresenter
import dev.dimension.flare.ui.presenter.manga.MangaReaderPresenter
import dev.dimension.flare.ui.presenter.manga.MangaTranslatePresenter
import dev.dimension.flare.ui.presenter.manga.PageMediaStatus
import dev.dimension.flare.ui.presenter.manga.PrimaryReadTarget
import dev.dimension.flare.ui.presenter.manga.ReaderChapterItem
import dev.dimension.flare.ui.presenter.manga.ReaderPageState
import dev.dimension.flare.ui.presenter.manga.ReaderSlot
import dev.dimension.flare.ui.presenter.manga.UiMangaChapterItem
import dev.dimension.flare.ui.presenter.media.ImageTranslationItem
import dev.dimension.flare.ui.presenter.media.ImageTranslationPresenter
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.Route
import dev.dimension.flare.ui.route.TopLevelBackStack
import dev.dimension.flare.ui.route.dispatchDesktopRouterNavigation
import dev.dimension.flare.ui.screen.serviceselect.DesktopChallengePolicyDecision
import dev.dimension.flare.ui.screen.serviceselect.ProvideDesktopSessionChallengeHost
import dev.dimension.flare.ui.screen.serviceselect.SessionChallengeWebViewDialog
import dev.dimension.flare.ui.screen.serviceselect.collectDesktopProbeCookies
import dev.dimension.flare.ui.screen.serviceselect.evaluateDesktopChallengePolicy
import dev.dimension.flare.ui.screen.serviceselect.prepareDesktopChallengeWebView
import dev.dimension.flare.ui.screen.serviceselect.shouldAllowDesktopChallengeRequest
import dev.dimension.flare.ui.theme.FlareTheme
import io.github.kdroidfilter.webview.wry.CookieSameSite
import io.github.kdroidfilter.webview.wry.WebViewCookie
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class, coil3.annotation.DelicateCoilApi::class)
class MangaScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader
                .Builder(context)
                .components {
                    add(
                        Fetcher.Factory<Any> { data, _, _ ->
                            val text = data.toString()
                            if (!text.startsWith("local-")) return@Factory null
                            Fetcher {
                                ImageFetchResult(
                                    image = ColorImage(color = 0xFF445566.toInt(), width = 1000, height = 1400),
                                    isSampled = false,
                                    dataSource = DataSource.MEMORY,
                                )
                            }
                        },
                    )
                }.build()
        }
    }

    @After
    fun tearDown() {
        SingletonImageLoader.reset()
    }

    @Test
    fun rendersGroupedTagsAndChaptersAndHandlesLockedChapterClick() {
        val mangaKey = MicroBlogKey("manga-42", "manga.example")
        val accountType = AccountType.GuestPlugin(platformId = "manga-plat", host = "manga.example")
        val tankobonChapters =
            persistentListOf(
                UiMangaChapter(
                    id = "ch-1",
                    title = "Chapter 1: Dawn",
                    subtitle = "32p",
                    uploadedAt = null,
                    locked = false,
                    url = "https://manga.example/ch/1",
                ),
                UiMangaChapter(
                    id = "ch-locked",
                    title = "Chapter 2: Locked Bonus",
                    subtitle = "Paid",
                    uploadedAt = null,
                    locked = true,
                    url = "https://manga.example/ch/2-locked",
                ),
            )
        val serialChapters =
            persistentListOf(
                UiMangaChapter(
                    id = "ch-serial-1",
                    title = "Serial Episode 1",
                    subtitle = null,
                    uploadedAt = null,
                    locked = false,
                    url = "https://manga.example/serial/1",
                ),
            )
        val detail =
            UiMangaDetail(
                pluginId = "plugin.manga",
                platformId = "manga-plat",
                mangaKey = mangaKey,
                accountType = accountType,
                title = "Starlight Odyssey",
                altTitles = persistentListOf("星光奥德赛"),
                cover = null,
                author = null,
                authors = persistentListOf("Hana Sato"),
                artists = persistentListOf("Kenji Mori"),
                status = UiMangaStatus.Ongoing,
                content = "A journey across star seas.".toUiPlainText(),
                url = "https://manga.example/series/42",
                updatedAt = null,
                readingDirection = MangaReadingDirection.Rtl,
                tagGroups =
                    persistentListOf(
                        UiMangaTagGroup(
                            id = "genre",
                            title = UiText.Raw("Genre"),
                            tags =
                                persistentListOf(
                                    UiMangaTag("Action", ClickEvent.Noop),
                                    UiMangaTag("Sci-Fi", ClickEvent.Noop),
                                ),
                        ),
                        UiMangaTagGroup(
                            id = "theme",
                            title = UiText.Raw("Theme"),
                            tags =
                                persistentListOf(
                                    UiMangaTag("Space Opera", ClickEvent.Noop),
                                ),
                        ),
                    ),
                chapterGroups =
                    persistentListOf(
                        UiMangaChapterGroup(
                            id = "tankobon",
                            title = UiText.Raw("Tankobon"),
                            chapters = tankobonChapters,
                        ),
                        UiMangaChapterGroup(
                            id = "serial",
                            title = UiText.Raw("Serial"),
                            chapters = serialChapters,
                        ),
                    ),
                isBookmarked = false,
                bookmarkAction = ClickEvent.Noop,
                actions = persistentListOf(),
                sourceLanguages = persistentListOf("ja"),
            )

        val fakeState = FakeMangaDetailState(detail)
        val navigatedRoutes = mutableListOf<Route>()
        val openedUrls = mutableListOf<String>()
        val uriHandler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    openedUrls += uri
                }
            }

        compose.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides uriHandler,
                LocalTimelineAppearance provides TimelineAppearance(),
                LocalGlobalAppearance provides GlobalAppearance(),
            ) {
                FlareTheme(isDarkTheme = false) {
                    Box(Modifier.size(1100.dp, 850.dp)) {
                        MangaDetailContent(
                            state = fakeState,
                            navigate = { navigatedRoutes += it },
                        )
                    }
                }
            }
        }

        // Tag group headers and tags
        compose.onNodeWithText("A journey across star seas.").assertIsDisplayed()
        compose.onNodeWithTag("manga_synopsis_toggle").assertDoesNotExist()
        compose.onNodeWithTag("manga_cta_bookmark_fallback").assertDoesNotExist()
        compose.onNodeWithText("Genre").assertIsDisplayed()
        compose.onNodeWithText("Action").assertIsDisplayed()
        compose.onNodeWithText("Theme").assertIsDisplayed()
        compose.onNodeWithText("Space Opera").assertIsDisplayed()

        // Chapter group chips
        compose.onNodeWithText("Tankobon").assertIsDisplayed()
        compose.onNodeWithText("Serial").assertIsDisplayed()

        // Sort toggle
        compose.onNodeWithTag("manga_chapter_sort_toggle").performClick()
        compose.runOnIdle {
            assertEquals(true, fakeState.chapterSortDescending)
        }

        // Primary Read CTA
        compose.onNodeWithTag("manga_primary_read_cta").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    Route.Manga.Reader(
                        mangaKey = mangaKey,
                        chapterId = "ch-1",
                        initialPage = 0,
                        accountType = accountType,
                    ),
                ),
                navigatedRoutes,
            )
        }

        // Locked chapter click shows locked chapter prompt and opens URL on confirm
        compose.onNodeWithText("Chapter 2: Locked Bonus").performClick()
        compose.onNodeWithTag("manga_locked_chapter_confirm").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(
                "Expected locked chapter URL to be opened, got $openedUrls",
                openedUrls.any { it.contains("https://manga.example/ch/2-locked") || DeeplinkRoute.parse(it) != null },
            )
        }

        // Chapter group selection switches chapters
        compose.onNodeWithText("Serial").performClick()
        compose.runOnIdle {
            assertEquals("serial", fakeState.selectedGroupId)
        }
        compose.onNodeWithText("Serial Episode 1").assertIsDisplayed()

        // Chapter context menu marks chapter as read
        compose.onNodeWithTag("manga_chapter_menu_ch-serial-1").performClick()
        compose.onNodeWithText("Mark as read").performClick()
        compose.runOnIdle {
            assertTrue(fakeState.chapters.single().isRead)
        }

        // Conditional Comments / Recommendations tabs only appear when supported
        compose.onNodeWithText("Comments").assertDoesNotExist()
        compose.onNodeWithText("Recommendations").assertDoesNotExist()
        compose.runOnIdle {
            fakeState.supportsComments = true
            fakeState.supportsRecommendations = true
        }
        compose.onNodeWithText("Comments").assertIsDisplayed()
        compose.onNodeWithText("Recommendations").assertIsDisplayed()
    }

    @Test
    fun deeplinkRoutesMapToDesktopMangaDetailAndReader() {
        val mangaKey = MicroBlogKey("m-1", "manga.example")
        val specificAccount = AccountType.Specific(MicroBlogKey("u-1", "manga.example"))
        val guestPluginAccount = AccountType.GuestPlugin(platformId = "plat", host = "manga.example")

        for (account in listOf(specificAccount, guestPluginAccount)) {
            val detailRoute =
                Route.from(
                    DeeplinkRoute.Manga.Detail(
                        mangaKey = mangaKey,
                        accountType = account,
                    ),
                )
            assertEquals(Route.Manga.Detail(mangaKey = mangaKey, accountType = account), detailRoute)

            val readerRoute =
                Route.from(
                    DeeplinkRoute.Manga.Reader(
                        mangaKey = mangaKey,
                        chapterId = "ch-7",
                        initialPage = 3,
                        accountType = account,
                    ),
                )
            assertEquals(
                Route.Manga.Reader(
                    mangaKey = mangaKey,
                    chapterId = "ch-7",
                    initialPage = 3,
                    accountType = account,
                ),
                readerRoute,
            )
        }
    }

    @Test
    fun readerScreenFlipsScrubberInRtlAndShowsCollapsedBadge() {
        val fakeReaderState = FakeDesktopReaderState(pageCount = 5, initialDirection = MangaReadingDirection.Rtl)

        compose.setContent {
            FlareTheme(isDarkTheme = true) {
                Box(Modifier.size(900.dp, 700.dp)) {
                    MangaReaderContent(
                        state = fakeReaderState,
                        onBack = {},
                    )
                }
            }
        }

        // In RTL mode, tapping near the left edge of the page slider advances toward the end of the chapter
        val sliderBounds = compose.onNodeWithTag("manga_reader_page_slider").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("manga_reader_page_slider").performTouchInput {
            click(Offset(sliderBounds.width * 0.1f, sliderBounds.height * 0.5f))
        }
        compose.runOnIdle {
            assertTrue(
                "Expected RTL left-side slider tap to advance pageIndex > 2, got ${fakeReaderState.currentPageIndex}",
                fakeReaderState.currentPageIndex >= 3,
            )
        }

        // Toggle HUD off via center tap zone and verify collapsed bottom-right page badge appears
        val canvasBounds = compose.onNodeWithTag("manga_reader_canvas").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("manga_reader_canvas").performTouchInput {
            click(Offset(canvasBounds.width * 0.5f, canvasBounds.height * 0.5f))
        }
        compose.onNodeWithTag("manga_reader_collapsed_page_badge").assertIsDisplayed()
    }

    @Test
    fun shareUsesTheSelectedPageWithoutAChapterUrl() {
        val reader = FakeDesktopReaderState(pageCount = 3, initialDirection = MangaReadingDirection.Ltr, chapterUrl = null)
        reader.goToPage(1)
        var shared: UiMedia? = null
        compose.setContent {
            FlareTheme(isDarkTheme = true) {
                MangaReaderContent(state = reader, onBack = {}, onSharePage = { _, displayed, _ -> shared = displayed })
            }
        }
        compose.onNodeWithTag("manga_reader_share_page").performClick()
        assertEquals("local-1", shared?.url)
    }

    @Test
    fun readerSettingsHidesDoublePageSpreadOnNarrowViewport() {
        val fakeReaderState = FakeDesktopReaderState(pageCount = 4, initialDirection = MangaReadingDirection.Rtl)
        var viewportWidth by mutableStateOf(500.dp)

        compose.setContent {
            FlareTheme(isDarkTheme = true) {
                Box(Modifier.size(viewportWidth, 700.dp)) {
                    MangaReaderContent(
                        state = fakeReaderState,
                        onBack = {},
                    )
                }
            }
        }

        // Open reader settings flyout on narrow viewport (< 720.dp) -> Double-page spread must be hidden
        compose.onNodeWithTag("manga_reader_settings_button").performClick()
        compose.onNodeWithText("Reading direction").assertIsDisplayed()
        compose.onNodeWithText("Double-page spread").assertDoesNotExist()

        // Widen viewport (>= 720.dp) -> Double-page spread becomes available
        compose.runOnIdle {
            viewportWidth = 900.dp
        }
        compose.onNodeWithText("Double-page spread").assertIsDisplayed()
    }

    @Test
    fun guestModeRendersSingleDisabledBookmarkAndCollapsesLongSynopsis() {
        val mangaKey = MicroBlogKey("manga-guest-1", "manga.example")
        val guestAccount = AccountType.GuestPlugin(platformId = "manga-plat", host = "manga.example")
        val longSynopsis =
            (1..8).joinToString(separator = "\n") { idx ->
                "Synopsis paragraph line $idx describing the long saga across distant star systems."
            }
        val guestBookmarkItem =
            ActionMenu.Item(
                updateKey = "bookmark",
                icon = UiIcon.Bookmark,
                text = ActionMenu.Item.Text.Localized(ActionMenu.Item.Text.Localized.Type.Bookmark),
                clickEvent = ClickEvent.Noop,
                actionFamily = PostActionFamily.Bookmark,
                enabled = false,
            )
        val detail =
            UiMangaDetail(
                pluginId = "plugin.manga",
                platformId = "manga-plat",
                mangaKey = mangaKey,
                accountType = guestAccount,
                title = "Guest Starlight",
                altTitles = persistentListOf(),
                cover = null,
                author = null,
                authors = persistentListOf("Hana Sato"),
                artists = persistentListOf(),
                status = UiMangaStatus.Ongoing,
                content = longSynopsis.toUiPlainText(),
                url = "https://manga.example/series/guest-1",
                updatedAt = null,
                readingDirection = MangaReadingDirection.Rtl,
                tagGroups = persistentListOf(),
                chapterGroups =
                    persistentListOf(
                        UiMangaChapterGroup(
                            id = "main",
                            title = null,
                            chapters =
                                persistentListOf(
                                    UiMangaChapter("ch-1", "Chapter 1: Dawn", null, null, locked = false, url = null),
                                ),
                        ),
                    ),
                isBookmarked = false,
                bookmarkAction = ClickEvent.Noop,
                actions = persistentListOf(guestBookmarkItem),
                sourceLanguages = persistentListOf("en"),
            )

        val fakeState = FakeMangaDetailState(detail)

        compose.setContent {
            CompositionLocalProvider(
                LocalTimelineAppearance provides TimelineAppearance(),
                LocalGlobalAppearance provides GlobalAppearance(),
            ) {
                FlareTheme(isDarkTheme = false) {
                    Box(Modifier.size(1100.dp, 850.dp)) {
                        MangaDetailContent(
                            state = fakeState,
                            navigate = {},
                        )
                    }
                }
            }
        }

        // No duplicate standalone CTA heart button when detail.actions is non-empty,
        // and the single Bookmark action in MangaActionsMenu is disabled in guest mode.
        compose.onNodeWithTag("manga_cta_bookmark_fallback").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add bookmark").assertIsDisplayed().assertIsNotEnabled()

        // Multi-line synopsis (> 4 lines) shows expand/collapse toggle and expands/collapses height on click.
        compose.onNodeWithTag("manga_synopsis_toggle").assertIsDisplayed()
        val collapsedToggleTop =
            compose
                .onNodeWithTag("manga_synopsis_toggle")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        compose.onNodeWithTag("manga_synopsis_toggle").performClick()
        val expandedToggleTop =
            compose
                .onNodeWithTag("manga_synopsis_toggle")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertTrue(
            "Expected expanding > 4 line synopsis to push toggle downward ($collapsedToggleTop -> $expandedToggleTop)",
            expandedToggleTop > collapsedToggleTop + 20f,
        )
        compose.onNodeWithTag("manga_synopsis_toggle").performClick()
        val recollapsedToggleTop =
            compose
                .onNodeWithTag("manga_synopsis_toggle")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertEquals(collapsedToggleTop, recollapsedToggleTop, 2f)
    }

    @Test
    fun adaptiveChapterGridReflowsBetweenMultiColumnAndSingleColumn() {
        val chapterMatchingHeaderKey = "chapters_header"
        val mangaKey = MicroBlogKey("manga-grid-1", "manga.example")
        val accountType = AccountType.GuestPlugin(platformId = "manga-plat", host = "manga.example")
        val detail =
            UiMangaDetail(
                pluginId = "plugin.manga",
                platformId = "manga-plat",
                mangaKey = mangaKey,
                accountType = accountType,
                title = "Grid Reflow Saga",
                altTitles = persistentListOf(),
                cover = null,
                author = null,
                authors = persistentListOf("Hana Sato"),
                artists = persistentListOf(),
                status = UiMangaStatus.Ongoing,
                content = "Short synopsis.".toUiPlainText(),
                url = "https://manga.example/series/grid-1",
                updatedAt = null,
                readingDirection = MangaReadingDirection.Rtl,
                tagGroups = persistentListOf(),
                chapterGroups =
                    persistentListOf(
                        UiMangaChapterGroup(
                            id = "main",
                            title = null,
                            chapters =
                                persistentListOf(
                                    UiMangaChapter(
                                        id = chapterMatchingHeaderKey,
                                        title = "Chapter 1: Dawn",
                                        subtitle = "24p",
                                        uploadedAt = null,
                                        locked = false,
                                        url = "https://manga.example/ch/1",
                                    ),
                                    UiMangaChapter(
                                        id = "ch-2",
                                        title = "Chapter 2: Horizon",
                                        subtitle = "26p",
                                        uploadedAt = null,
                                        locked = false,
                                        url = "https://manga.example/ch/2",
                                    ),
                                ),
                        ),
                    ),
                isBookmarked = false,
                bookmarkAction = ClickEvent.Noop,
                actions = persistentListOf(),
                sourceLanguages = persistentListOf("en"),
            )

        val fakeState = FakeMangaDetailState(detail)
        var viewportWidth by mutableStateOf(1100.dp)

        compose.setContent {
            CompositionLocalProvider(
                LocalTimelineAppearance provides TimelineAppearance(),
                LocalGlobalAppearance provides GlobalAppearance(),
            ) {
                FlareTheme(isDarkTheme = false) {
                    Box(Modifier.size(viewportWidth, 850.dp)) {
                        MangaDetailContent(
                            state = fakeState,
                            navigate = {},
                        )
                    }
                }
            }
        }

        // 1. At viewportWidth = 1100.dp (chapters pane = 720.dp >= 520.dp): multi-column grid
        val row1 = compose.onNodeWithTag("manga_chapter_row_$chapterMatchingHeaderKey").fetchSemanticsNode().boundsInRoot
        val row2 = compose.onNodeWithTag("manga_chapter_row_ch-2").fetchSemanticsNode().boundsInRoot
        assertEquals(row1.top, row2.top, 2f)
        assertTrue(
            "Expected ch-1 left (${row1.left}) < ch-2 left (${row2.left})",
            row1.left < row2.left,
        )

        // 2. At viewportWidth = 820.dp (chapters pane = 440.dp < 520.dp): single-column stacked list
        compose.runOnIdle {
            viewportWidth = 820.dp
        }
        val stacked1 = compose.onNodeWithTag("manga_chapter_row_$chapterMatchingHeaderKey").fetchSemanticsNode().boundsInRoot
        val stacked2 = compose.onNodeWithTag("manga_chapter_row_ch-2").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected stacked ch-1 bottom (${stacked1.bottom}) <= ch-2 top (${stacked2.top}) + 2f",
            stacked1.bottom <= stacked2.top + 2f,
        )

        // 3. Clicking manga_detail_refresh invokes fakeState.refresh() and disables while refreshing
        compose.onNodeWithTag("manga_detail_refresh").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, fakeState.refreshCount)
            fakeState.isRefreshing = true
        }
        compose.onNodeWithTag("manga_detail_refresh").assertIsNotEnabled()
        compose.onNodeWithTag("manga_detail_refresh").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, fakeState.refreshCount)
        }
    }

    @Test
    fun narrowViewportShowsTabSwitcherOnlyWhenMultiTabAndFallsBackSafely() {
        val mangaKey = MicroBlogKey("manga-narrow-1", "manga.example")
        val accountType = AccountType.GuestPlugin(platformId = "manga-plat", host = "manga.example")
        val detail =
            UiMangaDetail(
                pluginId = "plugin.manga",
                platformId = "manga-plat",
                mangaKey = mangaKey,
                accountType = accountType,
                title = "Narrow Viewport Saga",
                altTitles = persistentListOf(),
                cover = null,
                author = null,
                authors = persistentListOf("Hana Sato"),
                artists = persistentListOf(),
                status = UiMangaStatus.Ongoing,
                content = "Compact synopsis.".toUiPlainText(),
                url = "https://manga.example/series/narrow-1",
                updatedAt = null,
                readingDirection = MangaReadingDirection.Rtl,
                tagGroups = persistentListOf(),
                chapterGroups =
                    persistentListOf(
                        UiMangaChapterGroup(
                            id = "main",
                            title = null,
                            chapters =
                                persistentListOf(
                                    UiMangaChapter("ch-1", "Chapter 1: Dawn", null, null, locked = false, url = null),
                                    UiMangaChapter("ch-2", "Chapter 2: Horizon", null, null, locked = false, url = null),
                                ),
                        ),
                    ),
                isBookmarked = false,
                bookmarkAction = ClickEvent.Noop,
                actions = persistentListOf(),
                sourceLanguages = persistentListOf("en"),
            )

        val fakeState = FakeMangaDetailState(detail)

        compose.setContent {
            CompositionLocalProvider(
                LocalTimelineAppearance provides TimelineAppearance(),
                LocalGlobalAppearance provides GlobalAppearance(),
            ) {
                FlareTheme(isDarkTheme = false) {
                    Box(Modifier.size(520.dp, 850.dp)) {
                        MangaDetailContent(
                            state = fakeState,
                            navigate = {},
                        )
                    }
                }
            }
        }

        // 1. Single-tab mode: "Chapters (2)" header shown, no PillButton tab bar, 2.dp gap between .listCard chapter rows
        compose.onNodeWithText("Chapters (2)").assertIsDisplayed()
        compose.onNodeWithText("Chapters").assertDoesNotExist()
        compose.onNodeWithText("Comments").assertDoesNotExist()
        compose.onNodeWithText("Recommendations").assertDoesNotExist()
        compose.onNodeWithTag("manga_chapter_row_ch-1").assertIsDisplayed()
        compose.onNodeWithTag("manga_chapter_row_ch-2").assertIsDisplayed()
        val expectedChapterGapPx = with(compose.density) { 2.dp.toPx() }
        val compactRow1 = compose.onNodeWithTag("manga_chapter_row_ch-1").fetchSemanticsNode().boundsInRoot
        val compactRow2 = compose.onNodeWithTag("manga_chapter_row_ch-2").fetchSemanticsNode().boundsInRoot
        assertEquals(expectedChapterGapPx, compactRow2.top - compactRow1.bottom, 0.5f)

        // 2. Multi-tab mode: tabs appear; header spacing stays consistent across tabs; losing Comments falls back to Chapters
        compose.runOnIdle {
            fakeState.supportsComments = true
            fakeState.supportsRecommendations = true
        }
        compose.onNodeWithText("Chapters").assertIsDisplayed()
        compose.onNodeWithText("Comments").assertIsDisplayed()
        compose.onNodeWithText("Recommendations").assertIsDisplayed()
        val chaptersSynopsisTop =
            compose
                .onNodeWithText("Compact synopsis.")
                .fetchSemanticsNode()
                .boundsInRoot
                .top

        compose.onNodeWithText("Recommendations").performClick()
        val recommendationsSynopsisTop =
            compose
                .onNodeWithText("Compact synopsis.")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertEquals(chaptersSynopsisTop, recommendationsSynopsisTop, 0.5f)

        compose.onNodeWithText("Comments").performClick()
        val commentsSynopsisTop =
            compose
                .onNodeWithText("Compact synopsis.")
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertEquals(chaptersSynopsisTop, commentsSynopsisTop, 0.5f)
        compose.onNodeWithTag("manga_chapter_row_ch-1").assertDoesNotExist()

        compose.runOnIdle {
            fakeState.supportsComments = false
        }
        compose.onNodeWithText("Comments").assertDoesNotExist()
        compose.onNodeWithText("Recommendations").assertIsDisplayed()
        compose.onNodeWithText("Chapters (2)").assertIsDisplayed()
        compose.onNodeWithTag("manga_chapter_row_ch-1").assertIsDisplayed()
    }

    @Test
    fun resizingAcrossBreakpointPreservesSelectedTabAndOmitsBlankSynopsisCard() {
        val mangaKey = MicroBlogKey("manga-resize-tab", "manga.example")
        val accountType = AccountType.GuestPlugin(platformId = "manga-plat", host = "manga.example")
        val blankDetail =
            UiMangaDetail(
                pluginId = "plugin.manga",
                platformId = "manga-plat",
                mangaKey = mangaKey,
                accountType = accountType,
                title = "Resize Tab Preservation",
                altTitles = persistentListOf(),
                cover = null,
                author = null,
                authors = persistentListOf("Hana Sato"),
                artists = persistentListOf(),
                status = UiMangaStatus.Ongoing,
                content = "   \n\t  ".toUiPlainText(),
                url = "https://manga.example/series/resize-tab",
                updatedAt = null,
                readingDirection = MangaReadingDirection.Rtl,
                tagGroups =
                    persistentListOf(
                        UiMangaTagGroup(
                            id = "empty-group",
                            title = UiText.Raw("Empty"),
                            tags = persistentListOf(),
                        ),
                    ),
                chapterGroups =
                    persistentListOf(
                        UiMangaChapterGroup(
                            id = "main",
                            title = null,
                            chapters =
                                persistentListOf(
                                    UiMangaChapter("ch-1", "Chapter 1: Dawn", null, null, locked = false, url = null),
                                    UiMangaChapter("ch-2", "Chapter 2: Horizon", null, null, locked = false, url = null),
                                ),
                        ),
                    ),
                isBookmarked = false,
                bookmarkAction = ClickEvent.Noop,
                actions = persistentListOf(),
                sourceLanguages = persistentListOf("en"),
            )

        assertFalse(blankDetail.hasRenderableSynopsis())
        assertFalse(blankDetail.copy(content = "".toUiPlainText()).hasRenderableSynopsis())
        assertTrue(blankDetail.copy(content = "Valid synopsis".toUiPlainText()).hasRenderableSynopsis())

        val fakeState =
            FakeMangaDetailState(blankDetail).apply {
                supportsComments = true
                supportsRecommendations = true
            }
        var viewportWidth by mutableStateOf(1100.dp)

        compose.setContent {
            CompositionLocalProvider(
                LocalTimelineAppearance provides TimelineAppearance(),
                LocalGlobalAppearance provides GlobalAppearance(),
            ) {
                FlareTheme(isDarkTheme = false) {
                    Box(Modifier.size(viewportWidth, 850.dp)) {
                        MangaDetailContent(
                            state = fakeState,
                            navigate = {},
                        )
                    }
                }
            }
        }

        // Wide layout (>= 720.dp): blank UiRichText with empty tagGroups must not mount a hollow synopsis card
        compose.onNodeWithTag("manga_synopsis_tags_card").assertDoesNotExist()
        compose.onNodeWithTag("manga_chapter_row_ch-1").assertIsDisplayed()

        // Select Comments on wide layout (1100.dp), then shrink across 720.dp breakpoint to 520.dp
        compose.onNodeWithText("Comments").performClick()
        compose.onNodeWithTag("manga_chapter_row_ch-1").assertDoesNotExist()

        compose.runOnIdle {
            viewportWidth = 520.dp
        }
        // Compact layout (< 720.dp) must preserve Comments selection and still omit hollow synopsis card
        compose.onNodeWithTag("manga_synopsis_tags_card").assertDoesNotExist()
        compose.onNodeWithText("Comments").assertIsDisplayed()
        compose.onNodeWithTag("manga_chapter_row_ch-1").assertDoesNotExist()
        compose.onNodeWithText("Chapters (2)").assertDoesNotExist()

        // Widen back to 1100.dp -> Comments remains selected
        compose.runOnIdle {
            viewportWidth = 1100.dp
        }
        compose.onNodeWithTag("manga_chapter_row_ch-1").assertDoesNotExist()
    }

    @Test
    fun desktopReaderCoordinatorReusesSameMangaSessionAndSurvivesBackStackChanges() {
        val coordinator = DesktopMangaReaderWindowCoordinator()
        val accountA = AccountType.GuestPlugin(platformId = "manga-plat", host = "manga.example")
        val manga1 = MicroBlogKey("manga-1", "manga.example")
        val manga2 = MicroBlogKey("manga-2", "manga.example")

        coordinator.open(
            Route.Manga.Reader(
                accountType = accountA,
                mangaKey = manga1,
                chapterId = "ch-1",
                initialPage = null,
            ),
        )
        assertEquals(1, coordinator.sessions.size)
        val firstSession = coordinator.sessions.single()
        val initialFocusToken = firstSession.focusRequestToken
        assertEquals("ch-1", firstSession.chapterId)
        assertEquals(null, firstSession.initialPage)

        coordinator.open(
            Route.Manga.Reader(
                accountType = accountA,
                mangaKey = manga1,
                chapterId = "ch-2",
                initialPage = 3,
            ),
        )
        assertEquals(1, coordinator.sessions.size)
        val updatedSession = coordinator.sessions.single()
        assertEquals("ch-2", updatedSession.chapterId)
        assertEquals(3, updatedSession.initialPage)
        assertTrue(
            "Expected focusRequestToken to increment ($initialFocusToken -> ${updatedSession.focusRequestToken})",
            updatedSession.focusRequestToken > initialFocusToken,
        )
        assertTrue("Expected requestNonce > 0 on reuse, got ${updatedSession.requestNonce}", updatedSession.requestNonce > 0)

        coordinator.open(
            Route.Manga.Reader(
                accountType = accountA,
                mangaKey = manga2,
                chapterId = "ch-1",
                initialPage = null,
            ),
        )
        assertEquals(2, coordinator.sessions.size)

        val homeRoute = Route.Home(AccountType.Guest)
        val backStack =
            TopLevelBackStack(
                startKey = homeRoute,
                topLevelRoutes = listOf(homeRoute, Route.Discover),
            )
        backStack.push(Route.Settings)
        backStack.push(Route.Discover)
        backStack.pop()
        assertEquals(2, coordinator.sessions.size)

        coordinator.close(SessionKey(accountType = accountA, mangaKey = manga1))
        assertEquals(1, coordinator.sessions.size)
        assertEquals(manga2, coordinator.sessions.single().mangaKey)

        coordinator.close(SessionKey(accountType = accountA, mangaKey = manga2))
        assertTrue(coordinator.sessions.isEmpty())
    }

    @Test
    fun escapeKeyResetsZoomWithoutTriggeringWindowCloseOnKeyUp() {
        val fakeReaderState = FakeDesktopReaderState(pageCount = 3, initialDirection = MangaReadingDirection.Rtl)
        var windowClosedCount = 0

        compose.setContent {
            FlareTheme(isDarkTheme = true) {
                Box(
                    modifier =
                        Modifier
                            .size(900.dp, 700.dp)
                            .onPreviewKeyEvent { false }
                            .onKeyEvent { event ->
                                if (shouldCloseDesktopWindowOnEscape(event)) {
                                    windowClosedCount++
                                    true
                                } else {
                                    false
                                }
                            },
                ) {
                    MangaReaderContent(
                        state = fakeReaderState,
                        onBack = { windowClosedCount++ },
                        isStandaloneWindow = true,
                    )
                }
            }
        }

        compose.onNodeWithTag("manga_reader_back").assertDoesNotExist()

        compose.onNodeWithTag("manga_reader_page_0").performTouchInput {
            doubleClick(center)
        }
        compose.runOnIdle {}
        val zoomedPosition = compose.onNodeWithTag("manga_reader_page_0").fetchSemanticsNode().positionInRoot
        assertTrue(
            "Expected double-click to zoom page 0 to 2.5x, got positionInRoot=$zoomedPosition",
            zoomedPosition.x < -100f && zoomedPosition.y < -100f,
        )

        compose.onNodeWithTag("manga_reader_canvas").performKeyInput {
            pressKey(Key.Escape)
        }
        compose.runOnIdle {
            assertEquals(0, windowClosedCount)
        }
        val resetPosition = compose.onNodeWithTag("manga_reader_page_0").fetchSemanticsNode().positionInRoot
        assertEquals(0f, resetPosition.x, 2f)
        assertEquals(0f, resetPosition.y, 2f)

        compose.onNodeWithTag("manga_reader_canvas").performKeyInput {
            pressKey(Key.Escape)
        }
        compose.runOnIdle {
            assertEquals(1, windowClosedCount)
        }
    }

    @Test
    fun desktopReaderChapterFlyoutAndWideWebtoonSettingSwitchChaptersAndWidthMode() {
        val chapterGroups =
            persistentListOf(
                UiMangaChapterGroup(
                    id = "tankobon",
                    title = UiText.Raw("Tankobon"),
                    chapters =
                        persistentListOf(
                            UiMangaChapter("ch-1", "Chapter 1: Dawn", null, null, locked = false, url = null),
                            UiMangaChapter("ch-2", "Chapter 2: Horizon", null, null, locked = false, url = null),
                        ),
                ),
                UiMangaChapterGroup(
                    id = "serial",
                    title = UiText.Raw("Serial"),
                    chapters =
                        persistentListOf(
                            UiMangaChapter("ch-s1", "Episode 1", null, null, locked = false, url = null),
                        ),
                ),
            )
        val fakeReaderState =
            FakeDesktopReaderState(
                pageCount = 4,
                initialDirection = MangaReadingDirection.Rtl,
                chapterGroups = chapterGroups,
            )
        var contextCopiedPageIndex: Int? = null

        compose.setContent {
            FlareTheme(isDarkTheme = true) {
                Box(Modifier.size(900.dp, 700.dp)) {
                    MangaReaderContent(
                        state = fakeReaderState,
                        onBack = {},
                        onSharePage = { _, _, idx -> contextCopiedPageIndex = idx },
                        onSavePage = { _, _, _ -> },
                    )
                }
            }
        }

        compose.onNodeWithTag("manga_reader_back").assertIsDisplayed()
        compose.onNodeWithTag("manga_reader_chapters_button").performClick()
        compose.onNodeWithText("Tankobon").assertIsDisplayed()
        compose.onNodeWithText("Serial").assertIsDisplayed()

        compose.onNodeWithText("Serial").performClick()
        compose.runOnIdle {
            assertEquals("serial", fakeReaderState.selectedChapterGroupId)
        }
        compose.onNodeWithText("Episode 1").assertIsDisplayed()

        compose.onNodeWithText("Tankobon").performClick()
        compose.runOnIdle {
            assertEquals("tankobon", fakeReaderState.selectedChapterGroupId)
        }

        val initialSortDescending = fakeReaderState.chapterSortDescending
        compose.onNodeWithTag("manga_reader_chapter_sort_toggle").performClick()
        compose.runOnIdle {
            assertEquals(!initialSortDescending, fakeReaderState.chapterSortDescending)
        }

        compose.onNodeWithTag("manga_reader_chapter_item_ch-2").performClick()
        compose.runOnIdle {
            assertEquals(listOf("ch-2"), fakeReaderState.selectedChapterIds)
        }

        compose.runOnIdle {
            fakeReaderState.setDirection(MangaReadingDirection.Webtoon)
        }
        compose.onNodeWithTag("manga_reader_settings_button").performClick()
        compose.onNodeWithText("Constrain webtoon width").assertIsDisplayed()
        compose.onNodeWithTag("manga_reader_constrain_webtoon_switcher").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertFalse(fakeReaderState.webtoonConstrainWidthOnWideScreen)
        }

        // Open flyout suppresses 2500ms auto-hide
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("manga_reader_chapters_button").assertIsDisplayed()

        // Closing flyout allows HUD to auto-hide after 2500ms of pointer inactivity, and mouse motion reveals it again
        compose.onNodeWithTag("manga_reader_canvas").performMouseInput {
            click(Offset(50f, 500f))
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("manga_reader_collapsed_page_badge").assertIsDisplayed()

        compose.onNodeWithTag("manga_reader_canvas").performMouseInput {
            moveTo(Offset(120f, 220f))
        }
        compose.onNodeWithTag("manga_reader_chapters_button").assertIsDisplayed()

        // Right-clicking ready page opens ContextMenuArea and copies that page
        compose.onNodeWithTag("manga_reader_page_0").performMouseInput {
            rightClick(center)
        }
        compose.onNodeWithText("Copy page image").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(0, contextCopiedPageIndex)
        }
    }

    @Test(timeout = 30_000)
    fun desktopRouterNavigationDispatchesMangaReaderToCoordinatorInsteadOfInlineDeckBackStack() {
        val coordinator = DesktopMangaReaderWindowCoordinator()
        val account = AccountType.GuestPlugin(platformId = "manga-plat", host = "manga.example")
        val mangaKey = MicroBlogKey("manga-deck-1", "manga.example")
        val deckBackStack = mutableListOf<Route>(Route.DeckTimeline("tab-1"))

        val detailRoute = Route.Manga.Detail(mangaKey = mangaKey, accountType = account)
        dispatchDesktopRouterNavigation(
            route = detailRoute,
            coordinator = coordinator,
            fallback = { deckBackStack.add(it) },
        )
        assertEquals(listOf(Route.DeckTimeline("tab-1"), detailRoute), deckBackStack)
        assertTrue(coordinator.sessions.isEmpty())

        val firstReaderRoute =
            Route.Manga.Reader(
                mangaKey = mangaKey,
                chapterId = "ch-1",
                initialPage = 0,
                accountType = account,
            )
        dispatchDesktopRouterNavigation(
            route = firstReaderRoute,
            coordinator = coordinator,
            fallback = { deckBackStack.add(it) },
        )
        assertEquals(1, coordinator.sessions.size)
        assertEquals("ch-1", coordinator.sessions.single().chapterId)
        assertEquals(0, coordinator.sessions.single().initialPage)
        assertEquals(listOf(Route.DeckTimeline("tab-1"), detailRoute), deckBackStack)

        val replaceReaderRoute =
            Route.Manga.Reader(
                mangaKey = mangaKey,
                chapterId = "ch-2",
                initialPage = 4,
                accountType = account,
            )
        dispatchDesktopRouterNavigation(
            route = replaceReaderRoute,
            coordinator = coordinator,
            fallback = { replacement ->
                deckBackStack[deckBackStack.lastIndex] = replacement
            },
        )
        assertEquals(1, coordinator.sessions.size)
        assertEquals("ch-2", coordinator.sessions.single().chapterId)
        assertEquals(4, coordinator.sessions.single().initialPage)
        assertEquals(1, coordinator.sessions.single().requestNonce)
        assertEquals(listOf(Route.DeckTimeline("tab-1"), detailRoute), deckBackStack)

        dispatchDesktopRouterNavigation(
            route = firstReaderRoute,
            coordinator = null,
            fallback = { deckBackStack.add(it) },
        )
        assertEquals(listOf(Route.DeckTimeline("tab-1"), detailRoute, firstReaderRoute), deckBackStack)
    }

    @Test(timeout = 30_000)
    fun escapeKeyDismissesLockedChapterDialogWithoutClosingStandaloneWindowAndClearsOnChapterOrNonceChange() {
        val chapterGroups =
            persistentListOf(
                UiMangaChapterGroup(
                    id = "main",
                    title = null,
                    chapters =
                        persistentListOf(
                            UiMangaChapter("ch-1", "Chapter 1: Dawn", null, null, locked = false, url = "https://manga.example/ch/1"),
                            UiMangaChapter(
                                "ch-locked",
                                "Chapter 2: Locked Bonus",
                                "Paid",
                                null,
                                locked = true,
                                url = "https://manga.example/ch/2-locked",
                            ),
                            UiMangaChapter("ch-3", "Chapter 3: Horizon", null, null, locked = false, url = "https://manga.example/ch/3"),
                        ),
                ),
            )
        val fakeReaderState =
            FakeDesktopReaderState(
                pageCount = 3,
                initialDirection = MangaReadingDirection.Rtl,
                chapterGroups = chapterGroups,
            )
        var windowClosedCount = 0
        var requestNonce by mutableIntStateOf(0)

        compose.setContent {
            FlareTheme(isDarkTheme = true) {
                Box(
                    modifier =
                        Modifier
                            .size(900.dp, 700.dp)
                            .onPreviewKeyEvent { false }
                            .onKeyEvent { event ->
                                if (shouldCloseDesktopWindowOnEscape(event)) {
                                    windowClosedCount++
                                    true
                                } else {
                                    false
                                }
                            },
                ) {
                    MangaReaderContent(
                        state = fakeReaderState,
                        onBack = { windowClosedCount++ },
                        isStandaloneWindow = true,
                        requestNonce = requestNonce,
                    )
                }
            }
        }

        // 1. Escape on focused locked-chapter FluentDialog dismisses dialog without closing standalone window
        compose.onNodeWithTag("manga_reader_chapters_button").performClick()
        compose.onNodeWithTag("manga_reader_chapter_item_ch-locked").performClick()
        compose.onNodeWithText("Locked chapter").assertIsDisplayed()
        compose.onNodeWithTag("manga_reader_locked_chapter_dialog").assertIsDisplayed().performKeyInput {
            pressKey(Key.Escape)
        }
        compose.onNodeWithText("Locked chapter").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, windowClosedCount)
        }

        // 2. Escape routed through root/canvas while zoomed also dismisses locked-chapter dialog without resetting zoom or closing window
        compose.onNodeWithTag("manga_reader_page_0").performTouchInput {
            doubleClick(center)
        }
        compose.runOnIdle {}
        val zoomedPosition = compose.onNodeWithTag("manga_reader_page_0").fetchSemanticsNode().positionInRoot
        assertTrue(
            "Expected double-click to zoom page 0 to 2.5x, got positionInRoot=$zoomedPosition",
            zoomedPosition.x < -100f && zoomedPosition.y < -100f,
        )

        compose.onNodeWithTag("manga_reader_chapters_button").performClick()
        compose.onNodeWithTag("manga_reader_chapter_item_ch-locked").performClick()
        compose.onNodeWithText("Locked chapter").assertIsDisplayed()
        compose.onNodeWithTag("manga_reader_canvas").performKeyInput {
            pressKey(Key.Escape)
        }
        compose.onNodeWithText("Locked chapter").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, windowClosedCount)
        }
        val stillZoomedPosition = compose.onNodeWithTag("manga_reader_page_0").fetchSemanticsNode().positionInRoot
        assertTrue(
            "Expected page 0 to remain zoomed after dismissing locked chapter dialog, got $stillZoomedPosition",
            stillZoomedPosition.x < -100f && stillZoomedPosition.y < -100f,
        )

        // Reset zoom with next Escape, still keeping window open
        compose.onNodeWithTag("manga_reader_canvas").performKeyInput {
            pressKey(Key.Escape)
        }
        compose.runOnIdle {
            assertEquals(0, windowClosedCount)
        }

        // 3. Changing state.activeChapterId clears lockedPromptChapter
        compose.onNodeWithTag("manga_reader_chapters_button").performClick()
        compose.onNodeWithTag("manga_reader_chapter_item_ch-locked").performClick()
        compose.onNodeWithText("Locked chapter").assertIsDisplayed()
        compose.runOnIdle {
            fakeReaderState.selectChapter("ch-3")
        }
        compose.onNodeWithText("Locked chapter").assertDoesNotExist()

        // 4. Incrementing requestNonce clears lockedPromptChapter even when activeChapterId is unchanged
        compose.onNodeWithTag("manga_reader_chapters_button").performClick()
        compose.onNodeWithTag("manga_reader_chapter_item_ch-locked").performClick()
        compose.onNodeWithText("Locked chapter").assertIsDisplayed()
        compose.runOnIdle {
            requestNonce++
        }
        compose.onNodeWithText("Locked chapter").assertDoesNotExist()

        // 5. Escape at 1.0x with no dialog open closes the standalone window
        compose.onNodeWithTag("manga_reader_canvas").performKeyInput {
            pressKey(Key.Escape)
        }
        compose.runOnIdle {
            assertEquals(1, windowClosedCount)
        }
    }

    @Test(timeout = 30_000)
    fun desktopReaderShowsPartialChapterPageProgressAndNonPrimaryTranslationErrorDialog() {
        val chapterGroups =
            persistentListOf(
                UiMangaChapterGroup(
                    id = "main",
                    title = null,
                    chapters =
                        persistentListOf(
                            UiMangaChapter("ch-1", "Chapter 1: Dawn", "20p", null, locked = false, url = "https://manga.example/ch/1"),
                            UiMangaChapter("ch-2", "Chapter 2: Midpoint", "24p", null, locked = false, url = "https://manga.example/ch/2"),
                            UiMangaChapter("ch-3", "Chapter 3: Finale", "28p", null, locked = false, url = "https://manga.example/ch/3"),
                        ),
                ),
            )
        var translationEntries by mutableStateOf<Map<String, ImageTranslationItem>>(emptyMap())
        val fakeTranslationState =
            object : ImageTranslationPresenter.State {
                override val configured: Boolean = true

                override fun item(media: UiMedia): ImageTranslationItem = translationEntries[media.url] ?: ImageTranslationItem()

                override fun displayed(media: UiMedia): UiMedia = media

                override fun toggle(media: UiMedia) {}

                override fun regenerate(media: UiMedia) {}

                override fun clearError(media: UiMedia) {
                    val existing = translationEntries[media.url] ?: return
                    translationEntries = translationEntries + (media.url to existing.copy(error = null))
                }

                override fun setErrorDetailsVisible(
                    media: UiMedia,
                    visible: Boolean,
                ) {
                    val existing = translationEntries[media.url] ?: ImageTranslationItem()
                    translationEntries = translationEntries + (media.url to existing.copy(error = null, errorDetailsVisible = visible))
                }

                override fun imageFailed(
                    media: UiMedia,
                    url: String,
                ) {}
            }
        val fakeReaderState =
            FakeDesktopReaderState(
                pageCount = 2,
                initialDirection = MangaReadingDirection.Rtl,
                chapterGroups = chapterGroups,
                chapterProgress =
                    mapOf(
                        "ch-1" to (false to 0),
                        "ch-2" to (false to 4),
                        "ch-3" to (true to 9),
                    ),
                customSlots =
                    persistentListOf(
                        ReaderSlot.Double(leftPageIndex = 1, rightPageIndex = 0, primaryPageIndex = 0),
                    ),
                customImageTranslation = fakeTranslationState,
            )
        var windowClosedCount = 0

        compose.setContent {
            FlareTheme(isDarkTheme = true) {
                Box(
                    modifier =
                        Modifier
                            .size(960.dp, 720.dp)
                            .onPreviewKeyEvent { false }
                            .onKeyEvent { event ->
                                if (shouldCloseDesktopWindowOnEscape(event)) {
                                    windowClosedCount++
                                    true
                                } else {
                                    false
                                }
                            },
                ) {
                    MangaReaderContent(
                        state = fakeReaderState,
                        onBack = { windowClosedCount++ },
                        isStandaloneWindow = true,
                    )
                }
            }
        }

        // 1. DesktopMangaReaderChaptersFlyout renders "Page 5" (lastPageIndex + 1) only for partially-read chapters (!isRead && lastPageIndex > 0)
        compose.onNodeWithTag("manga_reader_chapters_button").performClick()
        compose.onNodeWithText("Page 5").assertIsDisplayed()
        compose.onNodeWithText("Page 1").assertDoesNotExist()
        compose.onNodeWithText("Page 10").assertDoesNotExist()

        // 2. Close flyout by clicking canvas outside HUD, let HUD auto-hide after 2.5s, then fail translation on spread's secondary page (local-1 != currentReady local-0)
        compose.onNodeWithTag("manga_reader_canvas").performMouseInput {
            click(Offset(50f, 500f))
        }
        compose.mainClock.advanceTimeBy(3_000L)
        compose.onNodeWithTag("manga_reader_collapsed_page_badge").assertIsDisplayed()
        compose.onNodeWithTag("manga_reader_chapters_button").assertDoesNotExist()

        compose.runOnIdle {
            translationEntries =
                mapOf(
                    "local-1" to
                        ImageTranslationItem(
                            error = ImageTranslationError.Authentication,
                            errorDetails = "secondary-spread-error",
                        ),
                )
        }

        compose.onNodeWithText("Image translation").assertIsDisplayed()
        compose.onNodeWithText("Check the provider credentials and service access.").assertIsDisplayed()

        // Pressing Escape dismisses the translation error dialog cleanly without closing the standalone window
        compose.onNodeWithTag("image_translation_error_dialog").assertIsDisplayed().performKeyInput {
            pressKey(Key.Escape)
        }
        compose.onNodeWithText("Check the provider credentials and service access.").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, windowClosedCount)
            assertEquals(null, translationEntries["local-1"]?.error)
        }
    }

    @Test
    fun standaloneMangaReaderWindowMountsSessionChallengeLauncherAndTriggersBatchRetry() {
        val accountKey = MicroBlogKey("me", "manga.example")
        val session =
            DesktopMangaReaderWindowSession(
                key = SessionKey(accountType = AccountType.Specific(accountKey), mangaKey = MicroBlogKey("m-1", "manga.example")),
                chapterId = "ch-1",
                initialPage = 0,
            )
        val coordinator = DesktopMangaReaderWindowCoordinator()
        val challenge =
            SessionChallengeException(
                challengeId = "desktop-reader-challenge",
                accountKey = accountKey,
                platformId = "MangaPlat",
                challengeUrl = "https://manga.example/challenge",
                displayMessage = "Solve challenge to read",
            )
        val fakeReaderState =
            FakeDesktopReaderState(
                pageCount = 2,
                initialDirection = MangaReadingDirection.Ltr,
                initialLoadState = UiState.Error(challenge),
            )
        var capturedLauncher: ((SessionChallengeException, () -> Unit) -> Unit)? = null

        compose.setContent {
            FlareTheme(isDarkTheme = true) {
                DesktopMangaReaderWindowBody(
                    session = session,
                    coordinator = coordinator,
                ) {
                    capturedLauncher = LocalSessionChallengeLauncher.current
                    MangaReaderContent(
                        state = fakeReaderState,
                        onBack = {},
                        isStandaloneWindow = true,
                    )
                }
            }
        }

        assertTrue("Expected LocalSessionChallengeLauncher to be mounted in standalone reader window", capturedLauncher != null)
        compose.onNodeWithText("Solve challenge to read").assertIsDisplayed()
        compose.onNodeWithText("Complete Verification").assertIsDisplayed()

        // General ErrorContent invokes onRetry() when challenge completes
        var generalRetried = 0
        val mountedLauncher = capturedLauncher!!
        compose.runOnIdle {
            assertEquals(0, fakeReaderState.retryChallengeFailuresCount)
        }
        // Directly verify completion callback wired from MangaReaderContent calls retryChallengeFailures()
        var interceptedOnComplete: (() -> Unit)? = null
        compose.setContent {
            FlareTheme(isDarkTheme = true) {
                CompositionLocalProvider(
                    LocalSessionChallengeLauncher provides { _, onComplete ->
                        interceptedOnComplete = onComplete
                    },
                ) {
                    ErrorContent(
                        error = challenge,
                        onRetry = { generalRetried++ },
                    )
                }
            }
        }
        compose.onNodeWithText("Complete Verification").performClick()
        compose.runOnIdle {
            interceptedOnComplete?.invoke()
            assertEquals(1, generalRetried)
            mountedLauncher.toString()
        }
    }

    @Test
    fun desktopSessionChallengePolicyBannerOriginHighlightAndCompletionFlow() {
        // 1. Policy decision checks: ProxyMode != SYSTEM falls back to system proxy with warning; non-empty seedCookies fail-closes.
        assertEquals(
            DesktopChallengePolicyDecision(blockedBySeedCookies = false, usesSystemProxyFallback = false),
            evaluateDesktopChallengePolicy(
                accountProxyMode = ProxyMode.SYSTEM,
                seedCookies = emptyList(),
            ),
        )
        assertEquals(
            DesktopChallengePolicyDecision(blockedBySeedCookies = false, usesSystemProxyFallback = true),
            evaluateDesktopChallengePolicy(
                accountProxyMode = ProxyMode.PROXY,
                seedCookies = emptyList(),
            ),
        )
        assertEquals(
            DesktopChallengePolicyDecision(blockedBySeedCookies = true, usesSystemProxyFallback = false),
            evaluateDesktopChallengePolicy(
                accountProxyMode = ProxyMode.SYSTEM,
                seedCookies = listOf(SessionChallengeSeedCookie("ipb_member_id", "42")),
            ),
        )

        // 2. Request interceptor allows only https:// on main frame.
        assertTrue(shouldAllowDesktopChallengeRequest(isForMainFrame = true, url = "https://manga.example/challenge"))
        assertFalse(shouldAllowDesktopChallengeRequest(isForMainFrame = true, url = "http://manga.example/challenge"))
        assertFalse(shouldAllowDesktopChallengeRequest(isForMainFrame = true, url = "javascript:alert(1)"))
        assertTrue(shouldAllowDesktopChallengeRequest(isForMainFrame = false, url = "http://sub.example/asset"))

        // 3. Probe cookie collector gathers ONLY declared cookies from handle.probes.
        val probe =
            SessionChallengeCookieProbe(
                url = "https://manga.example/gallery",
                cookies = listOf(SessionChallengeCookieRequirement("cf_clearance", required = true)),
            )
        val collected =
            collectDesktopProbeCookies(
                probes = listOf(probe),
                getCookiesForUrl = { url ->
                    if (url == probe.url) {
                        listOf(
                            WebViewCookie(
                                "cf_clearance",
                                "cleared",
                                "manga.example",
                                "/",
                                null,
                                true,
                                null,
                                CookieSameSite.LAX,
                                true,
                                true,
                            ),
                            WebViewCookie("undeclared", "secret", "manga.example", "/", null, true, null, CookieSameSite.NONE, true, true),
                        )
                    } else {
                        emptyList()
                    }
                },
            )
        assertEquals(listOf("cf_clearance" to "cleared"), collected.map { it.name to it.value })
        assertEquals(listOf("https://manga.example/gallery"), collected.map { it.sourceUrl })

        // 4. Non-empty seedCookies fail-closes without ever mounting WebView.
        val accountKey = MicroBlogKey("me", "manga.example")
        val seededHandle =
            FakeDesktopChallengeHandle(
                challengeId = "seeded-1",
                accountKey = accountKey,
                challengeUrl = "https://manga.example/challenge",
                seedCookies = listOf(SessionChallengeSeedCookie("ipb_member_id", "1")),
                probes = listOf(probe),
            )
        var webViewMountedCount = 0
        var dismissedCount = 0
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                SessionChallengeWebViewDialog(
                    exception =
                        SessionChallengeException(
                            challengeId = "seeded-1",
                            accountKey = accountKey,
                            platformId = "MangaPlat",
                            challengeUrl = "https://manga.example/challenge",
                            displayMessage = "Verify",
                        ),
                    accountProxyMode = ProxyMode.SYSTEM,
                    beginSession = { seededHandle },
                    onCompleted = {},
                    onRequireRelogin = {},
                    onDismiss = { dismissedCount++ },
                    webViewContent = { _, _, _, _, _ ->
                        webViewMountedCount++
                    },
                )
            }
        }
        compose.onNodeWithTag("session_challenge_seed_cookies_unsupported").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, webViewMountedCount)
        }

        // 5. Empty seedCookies + ProxyMode.PROXY gates WebView mount behind system-network consent,
        //    only mounts WebView & shows top proxy warning banner after confirmation, highlights deviated origin, and completes.
        val emptySeedHandle =
            FakeDesktopChallengeHandle(
                challengeId = "cf-1",
                accountKey = accountKey,
                challengeUrl = "https://manga.example/challenge",
                seedCookies = emptyList(),
                probes = listOf(probe),
            )
        var originUpdateCallback: ((String) -> Unit)? = null
        var navFinishedCallback: (() -> Unit)? = null
        var uaCallback: ((String) -> Unit)? = null
        var snapshotCallback: ((() -> SessionChallengeCookieSnapshot?) -> Unit)? = null
        var completedCount = 0
        var reloginExceptions = mutableListOf<RequireReLoginException>()

        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                SessionChallengeWebViewDialog(
                    exception =
                        SessionChallengeException(
                            challengeId = "cf-1",
                            accountKey = accountKey,
                            platformId = "MangaPlat",
                            challengeUrl = "https://manga.example/challenge",
                            displayMessage = "Verify",
                        ),
                    accountProxyMode = ProxyMode.PROXY,
                    beginSession = { emptySeedHandle },
                    onCompleted = { completedCount++ },
                    onRequireRelogin = { reloginExceptions += it },
                    onDismiss = { dismissedCount++ },
                    webViewContent = { _, onOriginChanged, onNavigationFinished, onUserAgentAvailable, onSnapshotProvider ->
                        webViewMountedCount++
                        originUpdateCallback = onOriginChanged
                        navFinishedCallback = onNavigationFinished
                        uaCallback = onUserAgentAvailable
                        snapshotCallback = onSnapshotProvider
                    },
                )
            }
        }
        compose.onNodeWithTag("session_challenge_proxy_consent").assertIsDisplayed()
        compose.onNodeWithTag("session_challenge_continue_system_network").assertIsDisplayed()
        compose.onNodeWithTag("session_challenge_proxy_warning").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, webViewMountedCount)
            assertEquals(0, emptySeedHandle.cancelCount)
        }

        compose.onNodeWithTag("session_challenge_continue_system_network").performClick()
        compose.onNodeWithTag("session_challenge_proxy_consent").assertDoesNotExist()
        compose.onNodeWithTag("session_challenge_proxy_warning").assertIsDisplayed()
        compose.onNodeWithTag("session_challenge_origin").assertIsDisplayed()
        compose.onNodeWithText("https://manga.example").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, webViewMountedCount)
            originUpdateCallback?.invoke("https://challenges.cloudflare.com")
        }
        compose.onNodeWithText("https://challenges.cloudflare.com").assertIsDisplayed()
        compose.onNodeWithTag("session_challenge_origin_deviated").assertIsDisplayed()

        // Busy is ignored, then Completed closes and triggers onCompleted
        emptySeedHandle.nextResults.add(SessionChallengeCheckResult.Busy)
        emptySeedHandle.nextResults.add(SessionChallengeCheckResult.Completed)
        compose.runOnIdle {
            uaCallback?.invoke("FlareDesktopTestUA/1.0")
            snapshotCallback?.invoke {
                SessionChallengeCookieSnapshot(
                    cookies = emptyList(),
                    userAgent = "FlareDesktopTestUA/1.0",
                )
            }
            navFinishedCallback?.invoke()
        }
        compose.runOnIdle {
            navFinishedCallback?.invoke()
        }
        compose.runOnIdle {
            assertEquals(1, completedCount)
            assertEquals(1, emptySeedHandle.closedCount)
        }

        // 5b. Closing on consent screen cancels handle without ever mounting WebView.
        val declHandle =
            FakeDesktopChallengeHandle(
                challengeId = "cf-decline",
                accountKey = accountKey,
                challengeUrl = "https://manga.example/challenge",
                seedCookies = emptyList(),
                probes = listOf(probe),
            )
        var declinedDismissed = 0
        var declinedMounts = 0
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                SessionChallengeWebViewDialog(
                    exception =
                        SessionChallengeException(
                            challengeId = "cf-decline",
                            accountKey = accountKey,
                            platformId = "MangaPlat",
                            challengeUrl = "https://manga.example/challenge",
                            displayMessage = "Verify",
                        ),
                    accountProxyMode = ProxyMode.DIRECT,
                    beginSession = { declHandle },
                    onCompleted = {},
                    onRequireRelogin = {},
                    onDismiss = { declinedDismissed++ },
                    webViewContent = { _, _, _, _, _ -> declinedMounts++ },
                )
            }
        }
        compose.onNodeWithTag("session_challenge_proxy_consent").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle {
            assertEquals(0, declinedMounts)
            assertEquals(1, declinedDismissed)
            assertEquals(1, declHandle.cancelCount)
        }

        // 6. RequireReLoginException closes handle & WebView and notifies onRequireRelogin
        val reloginHandle =
            FakeDesktopChallengeHandle(
                challengeId = "relogin-1",
                accountKey = accountKey,
                challengeUrl = "https://manga.example/challenge",
                seedCookies = emptyList(),
                probes = listOf(probe),
                throwOnCheck = RequireReLoginException(accountKey, "MangaPlat"),
            )
        var reloginNavCallback: (() -> Unit)? = null
        var reloginSnapshotCallback: ((() -> SessionChallengeCookieSnapshot?) -> Unit)? = null
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                SessionChallengeWebViewDialog(
                    exception =
                        SessionChallengeException(
                            challengeId = "relogin-1",
                            accountKey = accountKey,
                            platformId = "MangaPlat",
                            challengeUrl = "https://manga.example/challenge",
                            displayMessage = "Verify",
                        ),
                    accountProxyMode = ProxyMode.SYSTEM,
                    beginSession = { reloginHandle },
                    onCompleted = {},
                    onRequireRelogin = { reloginExceptions += it },
                    onDismiss = {},
                    webViewContent = { _, _, onNavigationFinished, _, onSnapshotProvider ->
                        reloginNavCallback = onNavigationFinished
                        reloginSnapshotCallback = onSnapshotProvider
                    },
                )
            }
        }
        compose.runOnIdle {
            reloginSnapshotCallback?.invoke {
                SessionChallengeCookieSnapshot(cookies = emptyList(), userAgent = "FlareDesktopTestUA/1.0")
            }
            reloginNavCallback?.invoke()
        }
        compose.runOnIdle {
            assertEquals(1, reloginExceptions.size)
            assertEquals(accountKey, reloginExceptions.single().accountKey)
            assertEquals(1, reloginHandle.closedCount)
        }
    }

    @Test
    fun desktopSessionChallengeCheckFailureAllowsCancelAndCloseOnDismiss() {
        val accountKey = MicroBlogKey("me", "manga.example")
        val probe =
            SessionChallengeCookieProbe(
                url = "https://manga.example/gallery",
                cookies = listOf(SessionChallengeCookieRequirement("cf_clearance", required = true)),
            )
        val errorHandle =
            FakeDesktopChallengeHandle(
                challengeId = "err-1",
                accountKey = accountKey,
                challengeUrl = "https://manga.example/challenge",
                seedCookies = emptyList(),
                probes = listOf(probe),
                throwOnCheck = IllegalStateException("Transient challenge check failure"),
            )
        var showDialog by mutableStateOf(true)
        var completedCount = 0
        var dismissedCount = 0
        var navCallback: (() -> Unit)? = null
        var snapshotCallback: ((() -> SessionChallengeCookieSnapshot?) -> Unit)? = null

        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                if (showDialog) {
                    SessionChallengeWebViewDialog(
                        exception =
                            SessionChallengeException(
                                challengeId = "err-1",
                                accountKey = accountKey,
                                platformId = "MangaPlat",
                                challengeUrl = "https://manga.example/challenge",
                                displayMessage = "Verify",
                            ),
                        accountProxyMode = ProxyMode.SYSTEM,
                        beginSession = { errorHandle },
                        onCompleted = { completedCount++ },
                        onRequireRelogin = {},
                        onDismiss = {
                            dismissedCount++
                            showDialog = false
                        },
                        webViewContent = { _, _, onNavigationFinished, _, onSnapshotProvider ->
                            navCallback = onNavigationFinished
                            snapshotCallback = onSnapshotProvider
                        },
                    )
                }
            }
        }

        compose.runOnIdle {
            snapshotCallback?.invoke {
                SessionChallengeCookieSnapshot(cookies = emptyList(), userAgent = "FlareDesktopTestUA/1.0")
            }
            navCallback?.invoke()
        }

        compose.onNodeWithTag("session_challenge_error_view").assertIsDisplayed()
        compose.onNodeWithText("Transient challenge check failure").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, completedCount)
            assertEquals(0, errorHandle.cancelCount)
            assertEquals(0, errorHandle.closeCount)
        }

        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle {
            assertEquals(1, dismissedCount)
            assertEquals(0, completedCount)
            assertEquals(1, errorHandle.cancelCount)
            assertEquals(1, errorHandle.closeCount)
        }

        // Also verify active-coroutine CancellationException from check() stops polling without blocking close() on disposal.
        val expiredCheckHandle =
            FakeDesktopChallengeHandle(
                challengeId = "expired-check-1",
                accountKey = accountKey,
                challengeUrl = "https://manga.example/challenge",
                seedCookies = emptyList(),
                probes = listOf(probe),
                throwOnCheck = kotlinx.coroutines.CancellationException("ticket expired"),
            )
        var showExpiredDialog by mutableStateOf(true)
        var expiredNavCallback: (() -> Unit)? = null
        var expiredSnapshotCallback: ((() -> SessionChallengeCookieSnapshot?) -> Unit)? = null
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                if (showExpiredDialog) {
                    SessionChallengeWebViewDialog(
                        exception =
                            SessionChallengeException(
                                challengeId = "expired-check-1",
                                accountKey = accountKey,
                                platformId = "MangaPlat",
                                challengeUrl = "https://manga.example/challenge",
                                displayMessage = "Verify",
                            ),
                        accountProxyMode = ProxyMode.SYSTEM,
                        beginSession = { expiredCheckHandle },
                        onCompleted = { completedCount++ },
                        onRequireRelogin = {},
                        onDismiss = {},
                        webViewContent = { _, _, onNavigationFinished, _, onSnapshotProvider ->
                            expiredNavCallback = onNavigationFinished
                            expiredSnapshotCallback = onSnapshotProvider
                        },
                    )
                }
            }
        }
        compose.runOnIdle {
            expiredSnapshotCallback?.invoke {
                SessionChallengeCookieSnapshot(cookies = emptyList(), userAgent = "FlareDesktopTestUA/1.0")
            }
            expiredNavCallback?.invoke()
        }
        compose.onNodeWithTag("session_challenge_error_view").assertIsDisplayed()
        compose.runOnIdle {
            showExpiredDialog = false
        }
        compose.runOnIdle {
            assertEquals(0, completedCount)
            assertEquals(1, expiredCheckHandle.closeCount)
        }
    }

    @Test
    fun desktopSessionChallengeFailsClosedOnInvalidHandleUrlAndPreservesEarlyNavigationTrigger() {
        val accountKey = MicroBlogKey("me", "manga.example")
        val probe =
            SessionChallengeCookieProbe(
                url = "https://manga.example/gallery",
                cookies = listOf(SessionChallengeCookieRequirement("cf_clearance", required = true)),
            )

        // 1. Non-HTTPS / invalid handle.challengeUrl fails closed upfront without mounting WebView and cancels/closes handle.
        listOf(
            "http://manga.example/challenge",
            "https://user@manga.example/challenge",
            "javascript:alert(1)",
        ).forEach { invalidUrl ->
            val invalidHandle =
                FakeDesktopChallengeHandle(
                    challengeId = "invalid-url-${invalidUrl.hashCode()}",
                    accountKey = accountKey,
                    challengeUrl = invalidUrl,
                    seedCookies = emptyList(),
                    probes = listOf(probe),
                )
            var mountedCount = 0
            compose.setContent {
                FlareTheme(isDarkTheme = false) {
                    SessionChallengeWebViewDialog(
                        exception =
                            SessionChallengeException(
                                challengeId = invalidHandle.challengeId,
                                accountKey = accountKey,
                                platformId = "MangaPlat",
                                challengeUrl = "https://manga.example/challenge",
                                displayMessage = "Verify",
                            ),
                        accountProxyMode = ProxyMode.SYSTEM,
                        beginSession = { invalidHandle },
                        onCompleted = {},
                        onRequireRelogin = {},
                        onDismiss = {},
                        webViewContent = { _, _, _, _, _ -> mountedCount++ },
                    )
                }
            }
            compose.onNodeWithTag("session_challenge_error_view").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(0, mountedCount)
                assertEquals(1, invalidHandle.cancelCount)
                assertEquals(1, invalidHandle.closeCount)
            }
        }

        // 2. Early onNavigationFinished before onCookieSnapshotProviderReady is not dropped once provider registers.
        val earlyNavHandle =
            FakeDesktopChallengeHandle(
                challengeId = "early-nav-1",
                accountKey = accountKey,
                challengeUrl = "https://manga.example/challenge",
                seedCookies = emptyList(),
                probes = listOf(probe),
            ).apply {
                nextResults.add(SessionChallengeCheckResult.Completed)
            }
        var earlyNavCallback: (() -> Unit)? = null
        var earlySnapshotCallback: ((() -> SessionChallengeCookieSnapshot?) -> Unit)? = null
        var completedCount = 0
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                SessionChallengeWebViewDialog(
                    exception =
                        SessionChallengeException(
                            challengeId = "early-nav-1",
                            accountKey = accountKey,
                            platformId = "MangaPlat",
                            challengeUrl = "https://manga.example/challenge",
                            displayMessage = "Verify",
                        ),
                    accountProxyMode = ProxyMode.SYSTEM,
                    beginSession = { earlyNavHandle },
                    onCompleted = { completedCount++ },
                    onRequireRelogin = {},
                    onDismiss = {},
                    webViewContent = { _, _, onNavigationFinished, _, onSnapshotProvider ->
                        earlyNavCallback = onNavigationFinished
                        earlySnapshotCallback = onSnapshotProvider
                    },
                )
            }
        }
        compose.runOnIdle {
            earlyNavCallback?.invoke()
        }
        compose.runOnIdle {
            earlySnapshotCallback?.invoke {
                SessionChallengeCookieSnapshot(cookies = emptyList(), userAgent = "FlareDesktopTestUA/1.0")
            }
        }
        compose.runOnIdle {
            assertEquals(1, completedCount)
            assertEquals(1, earlyNavHandle.closeCount)
        }

        // 3. prepareDesktopChallengeWebView registers snapshotProvider BEFORE loadUrl and BEFORE slow UA read completes.
        val events = mutableListOf<String>()
        var providerRegisteredBeforeLoad = false
        var snapshotProviderRegistered = false
        runBlocking {
            prepareDesktopChallengeWebView(
                challengeUrl = "https://manga.example/challenge",
                isReady = { true },
                clearAllCookies = { events += "clear" },
                registerSnapshotProvider = {
                    snapshotProviderRegistered = true
                    events += "registerSnapshotProvider"
                },
                loadUrl = {
                    providerRegisteredBeforeLoad = snapshotProviderRegistered
                    events += "loadUrl"
                },
                readUserAgent = {
                    events += "readUserAgent"
                    "FlareDesktopTestUA/1.0"
                },
                onUserAgentAvailable = { ua -> events += "ua:$ua" },
            )
        }
        assertTrue("snapshotProvider must be registered before loadUrl", providerRegisteredBeforeLoad)
        assertEquals(
            listOf("clear", "registerSnapshotProvider", "loadUrl", "readUserAgent", "ua:FlareDesktopTestUA/1.0"),
            events,
        )
    }

    @Test
    fun desktopSessionChallengeHostCollectsGlobalPromptBusAndRunsCompletionCallback() {
        val accountKey = MicroBlogKey("me", "manga.example")
        val probe =
            SessionChallengeCookieProbe(
                url = "https://manga.example/gallery",
                cookies = listOf(SessionChallengeCookieRequirement("cf_clearance", required = true)),
            )
        val busHandle =
            FakeDesktopChallengeHandle(
                challengeId = "bus-1",
                accountKey = accountKey,
                challengeUrl = "https://manga.example/challenge",
                seedCookies = emptyList(),
                probes = listOf(probe),
            ).apply {
                nextResults.add(SessionChallengeCheckResult.Completed)
            }
        var navCallback: (() -> Unit)? = null
        var snapshotCallback: ((() -> SessionChallengeCookieSnapshot?) -> Unit)? = null
        var completedCount = 0

        try {
            compose.setContent {
                FlareTheme(isDarkTheme = false) {
                    ProvideDesktopSessionChallengeHost(
                        collectGlobalPrompts = true,
                        beginSession = { busHandle },
                        webViewContent = { _, _, onNavigationFinished, _, onSnapshotProvider ->
                            navCallback = onNavigationFinished
                            snapshotCallback = onSnapshotProvider
                        },
                    ) {
                        Box(Modifier.size(100.dp))
                    }
                }
            }

            SessionChallengePromptBus.emit(
                exception =
                    SessionChallengeException(
                        challengeId = "bus-1",
                        accountKey = accountKey,
                        platformId = "MangaPlat",
                        challengeUrl = "https://manga.example/challenge",
                        displayMessage = "Verify to bookmark",
                    ),
                onCompleted = { completedCount++ },
            )

            compose.onNodeWithTag("session_challenge_proxy_consent").assertIsDisplayed()
            compose.onNodeWithTag("session_challenge_continue_system_network").performClick()
            compose.onNodeWithTag("session_challenge_origin").assertIsDisplayed()

            compose.runOnIdle {
                snapshotCallback?.invoke {
                    SessionChallengeCookieSnapshot(cookies = emptyList(), userAgent = "FlareDesktopTestUA/1.0")
                }
                navCallback?.invoke()
            }
            compose.runOnIdle {
                assertEquals(1, completedCount)
                assertEquals(1, busHandle.closeCount)
            }
        } finally {
            while (SessionChallengePromptBus.tryReceive() != null) {
                // Drain buffered prompts between tests.
            }
        }
    }
}

private class FakeDesktopChallengeHandle(
    override val challengeId: String,
    override val accountKey: MicroBlogKey,
    override val challengeUrl: String,
    override val seedCookies: List<SessionChallengeSeedCookie>,
    override val probes: List<SessionChallengeCookieProbe>,
    private val throwOnCheck: Throwable? = null,
) : SessionChallengeHandle {
    override val platformId: String = "MangaPlat"
    override val proxyRef: ProxyRef = ProxyRef.forAccount(accountKey)
    val nextResults = ArrayDeque<SessionChallengeCheckResult>()
    var cancelCount = 0
    var closeCount = 0
    val closedCount: Int
        get() = cancelCount + closeCount

    override suspend fun check(snapshot: SessionChallengeCookieSnapshot): SessionChallengeCheckResult {
        throwOnCheck?.let { throw it }
        return nextResults.removeFirstOrNull() ?: SessionChallengeCheckResult.Pending
    }

    override fun cancel() {
        cancelCount++
    }

    override fun close() {
        closeCount++
    }
}

private class FakeMangaDetailState(
    private val initialDetail: UiMangaDetail,
) : MangaDetailPresenter.State {
    override val detail: UiState<UiMangaDetail> = UiState.Success(initialDetail)
    override val translation: MangaTranslatePresenter.State? = null
    override var selectedGroupId: String? by mutableStateOf(initialDetail.chapterGroups.firstOrNull()?.id)
    override var chapterSortDescending: Boolean by mutableStateOf(false)
    private var readChapterIds by mutableStateOf(emptySet<String>())

    override val chapters: ImmutableList<UiMangaChapterItem>
        get() {
            val group = initialDetail.chapterGroups.firstOrNull { it.id == selectedGroupId } ?: return persistentListOf()
            val list = if (chapterSortDescending) group.chapters.asReversed() else group.chapters
            return list
                .map { ch ->
                    UiMangaChapterItem(
                        chapter = ch,
                        isRead = ch.id in readChapterIds,
                        isCurrent = false,
                        lastPageIndex = 0,
                        pageCount = 0,
                    )
                }.toImmutableList()
        }

    override val primaryReadTarget: PrimaryReadTarget =
        PrimaryReadTarget(
            chapterId = "ch-1",
            chapterTitle = "Chapter 1: Dawn",
            initialPage = 0,
            isContinue = false,
            isSingleChapterWork = false,
        )
    override var supportsComments: Boolean by mutableStateOf(false)
    override var supportsRecommendations: Boolean by mutableStateOf(false)
    override val comments: PagingState<UiTimelineV2> = UiState.Loading<PagingItems<UiTimelineV2>>().toPagingState()
    override val recommendations: PagingState<UiTimelineV2> = UiState.Loading<PagingItems<UiTimelineV2>>().toPagingState()
    override var isRefreshing: Boolean by mutableStateOf(false)
    var refreshCount: Int by mutableIntStateOf(0)

    override fun refresh() {
        refreshCount++
    }

    override fun selectGroup(groupId: String) {
        selectedGroupId = groupId
    }

    override fun toggleChapterSort() {
        chapterSortDescending = !chapterSortDescending
    }

    override fun setChapterRead(
        chapterId: String,
        isRead: Boolean,
    ) {
        readChapterIds = if (isRead) readChapterIds + chapterId else readChapterIds - chapterId
    }
}

private class FakeDesktopReaderState(
    pageCount: Int,
    initialDirection: MangaReadingDirection,
    override val chapterUrl: String? = "https://manga.example/ch/1",
    override val chapterGroups: ImmutableList<UiMangaChapterGroup> = persistentListOf(),
    private val chapterProgress: Map<String, Pair<Boolean, Int>> = emptyMap(),
    customSlots: ImmutableList<ReaderSlot>? = null,
    customImageTranslation: ImageTranslationPresenter.State? = null,
    initialLoadState: UiState<Unit> = UiState.Success(Unit),
) : MangaReaderPresenter.State {
    override val mangaTitle: String = "Starlight Odyssey"
    override val chapterTitle: String = "Chapter 1"
    override var activeChapterId: String by mutableStateOf("ch-1")
    override val loadState: UiState<Unit> = initialLoadState
    var retryChallengeFailuresCount: Int = 0
    override val pages: ImmutableList<ReaderPageState> =
        (0 until pageCount)
            .map { idx ->
                val media = UiMedia.Image("local-$idx", "local-$idx", null, 1400f, 1000f, false)
                ReaderPageState(
                    id = "p-$idx",
                    index = idx,
                    status = PageMediaStatus.Ready(media, media),
                    aspectRatio = 1000f / 1400f,
                )
            }.toImmutableList()
    override val slots: ImmutableList<ReaderSlot> =
        customSlots ?: (0 until pageCount).map(ReaderSlot::Single).toImmutableList()
    override var currentPageIndex: Int by mutableIntStateOf(0)
    override val currentSlotIndex: Int
        get() = slots.indexOfFirst { it.contains(currentPageIndex) }.coerceAtLeast(0)
    private var _direction by mutableStateOf(initialDirection)
    private var _doublePageSpread by mutableStateOf(slots.any { it is ReaderSlot.Double })
    private var _spreadCoverSinglePage by mutableStateOf(true)
    private var _webtoonConstrainWidthOnWideScreen by mutableStateOf(true)
    override val direction: MangaReadingDirection get() = _direction
    override val doublePageSpread: Boolean get() = _doublePageSpread
    override val spreadCoverSinglePage: Boolean get() = _spreadCoverSinglePage
    override val webtoonConstrainWidthOnWideScreen: Boolean get() = _webtoonConstrainWidthOnWideScreen
    override var selectedChapterGroupId: String? by mutableStateOf(chapterGroups.firstOrNull()?.id)
    override var chapterSortDescending: Boolean by mutableStateOf(true)
    override val chapterItems: ImmutableList<ReaderChapterItem>
        get() {
            val group = chapterGroups.firstOrNull { it.id == selectedChapterGroupId } ?: return persistentListOf()
            val ordered = if (chapterSortDescending) group.chapters.asReversed() else group.chapters
            return ordered
                .map { chapter ->
                    val progress = chapterProgress[chapter.id]
                    ReaderChapterItem(
                        chapter = chapter,
                        isCurrent = chapter.id == activeChapterId,
                        isRead = progress?.first ?: false,
                        lastPageIndex = progress?.second ?: 0,
                    )
                }.toImmutableList()
        }
    val selectedChapterIds = mutableListOf<String>()
    override val previousBoundary: ChapterBoundary = ChapterBoundary.None
    override val nextBoundary: ChapterBoundary = ChapterBoundary.None
    override val prefetchMedia: ImmutableList<UiMedia.Image> = persistentListOf()
    override val imageTranslation: ImageTranslationPresenter.State =
        customImageTranslation
            ?: object : ImageTranslationPresenter.State {
                override val configured: Boolean = false

                override fun item(media: UiMedia): ImageTranslationItem = ImageTranslationItem()

                override fun displayed(media: UiMedia): UiMedia = media

                override fun toggle(media: UiMedia) {}

                override fun regenerate(media: UiMedia) {}

                override fun clearError(media: UiMedia) {}

                override fun setErrorDetailsVisible(
                    media: UiMedia,
                    visible: Boolean,
                ) {}

                override fun imageFailed(
                    media: UiMedia,
                    url: String,
                ) {}
            }

    override fun setWideViewport(isWide: Boolean) {}

    override fun onPageDimensionsLoaded(
        pageId: String,
        width: Float,
        height: Float,
    ) {}

    override fun goToPage(pageIndex: Int) {
        currentPageIndex = pageIndex.coerceIn(0, pages.lastIndex)
    }

    override fun goToSlot(slotIndex: Int) {
        currentPageIndex = slotIndex.coerceIn(0, pages.lastIndex)
    }

    override fun stepForward() {
        if (currentPageIndex < pages.lastIndex) currentPageIndex++
    }

    override fun stepBackward() {
        if (currentPageIndex > 0) currentPageIndex--
    }

    override fun openAdjacentChapter(boundary: ChapterBoundary.Unlocked) {}

    override fun retryChapter() {}

    override fun retryChallengeFailures() {
        retryChallengeFailuresCount++
    }

    override fun retryPage(pageId: String) {}

    override fun setDirection(direction: MangaReadingDirection) {
        _direction = direction
    }

    override fun setDoublePageSpread(enabled: Boolean) {
        _doublePageSpread = enabled
    }

    override fun setSpreadCoverSinglePage(enabled: Boolean) {
        _spreadCoverSinglePage = enabled
    }

    override fun selectChapterGroup(groupId: String) {
        selectedChapterGroupId = groupId
    }

    override fun toggleChapterSort() {
        chapterSortDescending = !chapterSortDescending
    }

    override fun selectChapter(chapterId: String) {
        selectedChapterIds += chapterId
        activeChapterId = chapterId
    }

    override fun resolveChapterOpenUrl(chapter: UiMangaChapter): String? = chapter.url

    override fun setWebtoonConstrainWidthOnWideScreen(enabled: Boolean) {
        _webtoonConstrainWidthOnWideScreen = enabled
    }
}
