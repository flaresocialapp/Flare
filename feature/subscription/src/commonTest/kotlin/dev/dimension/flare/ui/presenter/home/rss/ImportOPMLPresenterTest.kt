package dev.dimension.flare.ui.presenter.home.rss

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.data.database.app.model.SubscriptionType
import dev.dimension.flare.data.repository.SubscriptionSourceInput
import dev.dimension.flare.di.startKoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ImportOPMLPresenterTest {
    private lateinit var subscriptionRepository: FakeSubscriptionRepository

    @BeforeTest
    fun setup() {
        subscriptionRepository = startKoin().koin.get()
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun testImportOPML() =
        runTest {
            val opmlContent =
                """
                <opml version="2.0">
                    <head>
                        <title>My Feeds</title>
                    </head>
                    <body>
                        <outline text="Tech" title="Tech">
                            <outline type="rss" text="The Verge" title="The Verge" xmlUrl="https://www.theverge.com/rss/index.xml" htmlUrl="https://www.theverge.com/"/>
                        </outline>
                        <outline type="rss" text="Daring Fireball" title="Daring Fireball" xmlUrl="https://daringfireball.net/feeds/main" htmlUrl="https://daringfireball.net/"/>
                    </body>
                </opml>
                """.trimIndent()

            val presenter = ImportOPMLPresenter(opmlContent) { null }

            val states = mutableListOf<ImportOPMLPresenter.State>()
            val job =
                launch {
                    moleculeFlow(mode = RecompositionMode.Immediate) {
                        presenter.body()
                    }.collect {
                        states.add(it)
                    }
                }

            advanceUntilIdle()
            job.cancel()

            val finalState = states.last()

            assertFalse(finalState.importing)
            assertNull(finalState.error)
            assertEquals(2, finalState.totalCount)
            assertEquals(2, finalState.importedCount)

            val sources = subscriptionRepository.currentSources
            assertEquals(2, sources.size)

            val verge = sources.find { it.url == "https://www.theverge.com/rss/index.xml" }
            assertEquals("The Verge", verge?.title)

            val df = sources.find { it.url == "https://daringfireball.net/feeds/main" }
            assertEquals("Daring Fireball", df?.title)
        }

    @Test
    fun testImportOPMLWithExistingUrl() =
        runTest {
            // Pre-populate DB with one source
            val existingUrl = "https://www.theverge.com/rss/index.xml"
            subscriptionRepository.upsert(
                SubscriptionSourceInput(
                    url = existingUrl,
                    title = "Old Title",
                    icon = null,
                    lastUpdateMillis = 123456789L,
                ),
            )

            val opmlContent =
                """
                <opml version="2.0">
                    <head>
                        <title>My Feeds</title>
                    </head>
                    <body>
                        <outline text="Tech" title="Tech">
                            <outline type="rss" text="The Verge" title="New Title" xmlUrl="$existingUrl" htmlUrl="https://www.theverge.com/"/>
                        </outline>
                        <outline type="rss" text="Daring Fireball" title="Daring Fireball" xmlUrl="https://daringfireball.net/feeds/main" htmlUrl="https://daringfireball.net/"/>
                    </body>
                </opml>
                """.trimIndent()

            val presenter = ImportOPMLPresenter(opmlContent) { null }

            val states = mutableListOf<ImportOPMLPresenter.State>()
            val job =
                launch {
                    moleculeFlow(mode = RecompositionMode.Immediate) {
                        presenter.body()
                    }.collect {
                        states.add(it)
                    }
                }

            advanceUntilIdle()
            job.cancel()

            val finalState = states.last()

            assertFalse(finalState.importing)
            assertNull(finalState.error)
            assertEquals(2, finalState.totalCount)
            assertEquals(2, finalState.importedCount)

            val sources = subscriptionRepository.currentSources
            assertEquals(2, sources.size)

            val verge = sources.find { it.url == existingUrl }
            // Should retain the OLD title because we skipped insertion
            assertEquals("Old Title", verge?.title)

            val df = sources.find { it.url == "https://daringfireball.net/feeds/main" }
            assertEquals("Daring Fireball", df?.title)
        }

    @Test
    fun importsFeedUrlsWithMissingSchemesAsHttps() =
        runTest {
            val opmlContent =
                """
                <opml version="2.0">
                    <body>
                        <outline type="rss" text="Feed" xmlUrl="example.com/feed.xml"/>
                        <outline type="rss" text="Same feed" xmlUrl="https://example.com/feed.xml"/>
                        <outline type="rss" text="Protocol-relative feed" xmlUrl="//example.org/feed.xml"/>
                        <outline type="rss" text="HTTP feed" xmlUrl="http://legacy.example/feed.xml"/>
                    </body>
                </opml>
                """.trimIndent()
            val presenter = ImportOPMLPresenter(opmlContent) { null }

            val state = moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.first { !it.importing }

            assertNull(state.error)
            assertEquals(
                setOf("https://example.com/feed.xml", "https://example.org/feed.xml", "http://legacy.example/feed.xml"),
                subscriptionRepository.currentSources.map { it.url }.toSet(),
            )
            assertEquals(3, subscriptionRepository.currentSources.size)
            assertEquals(3, state.totalCount)
            assertEquals(3, state.importedCount)
        }

    @Test
    fun importsRssWhenTheSameUrlExistsWithAnotherSubscriptionType() =
        runTest {
            val url = "https://example.com/"
            val mastodon =
                subscriptionRepository.upsert(
                    SubscriptionSourceInput(
                        url = url,
                        title = "Mastodon",
                        icon = null,
                        type = SubscriptionType.MASTODON_LOCAL,
                    ),
                )
            val opmlContent =
                """
                <opml version="2.0">
                    <body><outline type="rss" text="Feed" xmlUrl="$url"/></body>
                </opml>
                """.trimIndent()
            val presenter = ImportOPMLPresenter(opmlContent) { null }

            val state = moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.first { !it.importing }

            assertNull(state.error)
            val rss = state.importedSources.single()
            assertEquals(SubscriptionType.RSS, rss.type)
            assertEquals(url, rss.url)
            assertEquals(listOf(mastodon, rss), subscriptionRepository.currentSources)
        }

    @Test
    fun reusesAnExistingRssSourceWithTheSameNormalizedUrl() =
        runTest {
            val legacySource =
                subscriptionRepository.upsert(
                    SubscriptionSourceInput(
                        url = "example.com/feed.xml",
                        title = "Existing feed",
                        icon = null,
                    ),
                )
            val opmlContent =
                """
                <opml version="2.0">
                    <body><outline type="rss" text="Feed" xmlUrl="https://example.com/feed.xml"/></body>
                </opml>
                """.trimIndent()
            val presenter = ImportOPMLPresenter(opmlContent) { null }

            val state = moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.first { !it.importing }

            assertNull(state.error)
            assertEquals(listOf(legacySource), state.importedSources)
            assertEquals(listOf(legacySource), subscriptionRepository.currentSources)
        }

    @Test
    fun testConcurrencyStressTest() =
        runTest {
            val feedCount = 1000
            val sb = StringBuilder()
            sb.append("""<opml version="2.0"><head><title>Stress Test</title></head><body>""")

            repeat(feedCount) { i ->
                sb.append(
                    """
                    <outline type="rss" text="Feed $i" title="Title $i" xmlUrl="https://stress.test/feed/$i.xml" />
                    """.trimIndent(),
                )
            }
            sb.append("</body></opml>")

            val presenter =
                ImportOPMLPresenter(sb.toString()) { url ->
                    kotlinx.coroutines.delay((1..10).random().toLong())
                    "https://icon.url/icon.png"
                }

            val states = mutableListOf<ImportOPMLPresenter.State>()

            val job =
                launch {
                    moleculeFlow(mode = RecompositionMode.Immediate) {
                        presenter.body()
                    }.collect {
                        states.add(it)
                    }
                }

            advanceUntilIdle()
            job.cancel()

            val finalState = states.last()
            assertNull(finalState.error, "Should verify concurrency without errors")
            assertFalse(finalState.importing, "Should finish importing")
            assertEquals(feedCount, finalState.totalCount, "Total count should match input")
            assertEquals(feedCount, finalState.importedCount, "Imported count should match input")
            assertEquals(
                feedCount,
                finalState.importedSources.size,
                "UiState list size should match input",
            )
            assertEquals(feedCount, subscriptionRepository.currentSources.size, "Repository records should match input")
        }

    @Test
    fun testNoTypeOPML() =
        runTest {
            val sb = StringBuilder()
            sb.append(
                """
                <?xml version='1.0' encoding='UTF-8' ?>
                <opml version="2.0">
                  <head>
                    <title>Fake Data</title>
                    <dateCreated>Sun Dec 14 12:56:07 GMT+08:00 2025</dateCreated>
                  </head>
                  <body>
                """.trimIndent(),
            )

            val categories = 5
            val feedsPerCategory = 10
            val expectedTotal = categories * feedsPerCategory

            repeat(categories) { c ->
                sb.append("""<outline isDefault="true" text="Category $c" title="Category $c">""")
                repeat(feedsPerCategory) { f ->
                    sb.append(
                        """<outline isFullContent="false" htmlUrl="https://fake.com/$c/$f" text="Feed $c-$f" title="Feed $c-$f" isNotification="false" isBrowser="false" xmlUrl="https://fake.com/$c/$f/rss" />""",
                    )
                }
                sb.append("</outline>")
            }

            sb.append("</body></opml>")

            val presenter = ImportOPMLPresenter(sb.toString()) { null }

            val states = mutableListOf<ImportOPMLPresenter.State>()
            val job =
                launch {
                    moleculeFlow(mode = RecompositionMode.Immediate) {
                        presenter.body()
                    }.collect {
                        states.add(it)
                    }
                }

            advanceUntilIdle()
            job.cancel()

            val finalState = states.last()

            assertFalse(finalState.importing)
            assertNull(finalState.error)
            assertEquals(expectedTotal, finalState.totalCount)
            assertEquals(expectedTotal, finalState.importedCount)

            val sources = subscriptionRepository.currentSources
            assertEquals(expectedTotal, sources.size)
        }
}
