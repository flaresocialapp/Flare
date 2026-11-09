package dev.dimension.flare.data.datasource.misskey

import androidx.datastore.core.DataStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.model.ProxySettings
import dev.dimension.flare.data.network.misskey.MisskeyService
import dev.dimension.flare.data.repository.ProxyRepository
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StatusDetailRemoteMediatorTest {
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private lateinit var mediator: StatusDetailRemoteMediator
    private lateinit var showCalls: AtomicInteger
    private lateinit var conversationCalls: AtomicInteger
    private lateinit var childrenCalls: AtomicInteger

    @BeforeTest
    fun setup() {
        val proxyRepository =
            ProxyRepository(
                object : DataStore<ProxySettings> {
                    override val data = flowOf(ProxySettings())

                    override suspend fun updateData(transform: suspend (ProxySettings) -> ProxySettings): ProxySettings =
                        error("Read-only test proxy configuration")
                },
            )
        startKoin { modules(module { single { proxyRepository } }) }
        showCalls = AtomicInteger()
        conversationCalls = AtomicInteger()
        childrenCalls = AtomicInteger()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/api/notes/show") {
            showCalls.incrementAndGet()
            it.respond(DETAIL_JSON)
        }
        server.createContext("/api/notes/conversation") {
            conversationCalls.incrementAndGet()
            it.respond("[$PARENT_JSON,$GRANDPARENT_JSON]")
        }
        server.createContext("/api/notes/children") { exchange ->
            childrenCalls.incrementAndGet()
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            exchange.respond(
                when {
                    body.contains("\"noteId\":\"detail\"") -> "[$CHILD_JSON]"
                    body.contains("\"noteId\":\"child\"") -> "[$GRANDCHILD_JSON]"
                    else -> "[]"
                },
            )
        }
        server.start()
        mediator = createMediator()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
        stopKoin()
    }

    @Test
    fun refreshPublishesOnlyTheMainPostAndSchedulesBothSides() =
        runTest {
            val refresh = mediator.load(pageSize = 20, request = PagingRequest.Refresh)
            assertEquals(listOf("detail"), refresh.data.map { it.statusKey.id })
            assertNotNull(refresh.previousKey)
            assertNotNull(refresh.nextKey)
            val update = mediator.contextUpdate(PagingRequest.Refresh, refresh, initial = true)
            assertNull(update.before)
            assertNull(update.after)
            assertEquals(1, showCalls.get())
            assertEquals(0, conversationCalls.get())
            assertEquals(0, childrenCalls.get())
        }

    @Test
    fun failedParentsDoNotBlockRepliesAndCanBeRetriedSeparately() =
        runTest {
            server.removeContext("/api/notes/conversation")
            server.createContext("/api/notes/conversation") {
                if (conversationCalls.incrementAndGet() == 1) {
                    it.respond("""{"error":{"message":"Parents unavailable","code":"INTERNAL_ERROR"}}""", status = 500)
                } else {
                    it.respond("[$PARENT_JSON,$GRANDPARENT_JSON]")
                }
            }
            val refresh = mediator.load(20, PagingRequest.Refresh)
            val request = PagingRequest.Prepend(assertNotNull(refresh.previousKey))
            val parents = async { runCatching { mediator.load(20, request) } }
            val replies = mediator.load(20, PagingRequest.Append(assertNotNull(refresh.nextKey)))
            assertEquals(listOf("child", "grandchild"), replies.data.map { it.statusKey.id })
            assertTrue(parents.await().isFailure)
            val retried = mediator.load(20, request)
            assertEquals(listOf("grandparent", "parent"), retried.data.map { it.statusKey.id })
            assertNull(retried.previousKey)
            assertNull(retried.nextKey)
            val update = mediator.contextUpdate(request, retried, initial = true)
            assertEquals(listOf("grandparent", "parent"), update.before?.keys?.map { it.id })
            assertNull(update.after)
            assertEquals(1, showCalls.get())
            assertEquals(2, conversationCalls.get())
            assertEquals(2, childrenCalls.get())
        }

    @Test
    fun repliesFinishWhileParentsAreStillPending() =
        runTest(timeout = 10.seconds) {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.removeContext("/api/notes/conversation")
            server.createContext("/api/notes/conversation") {
                conversationCalls.incrementAndGet()
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                it.respond("[$PARENT_JSON,$GRANDPARENT_JSON]")
            }
            val refresh = mediator.load(20, PagingRequest.Refresh)
            val parents = async { mediator.load(20, PagingRequest.Prepend(assertNotNull(refresh.previousKey))) }
            try {
                val replies = mediator.load(20, PagingRequest.Append(assertNotNull(refresh.nextKey)))
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertEquals(listOf("child", "grandchild"), replies.data.map { it.statusKey.id })
                assertTrue(!parents.isCompleted)
            } finally {
                release.countDown()
            }
            assertEquals(listOf("grandparent", "parent"), parents.await().data.map { it.statusKey.id })
        }

    private fun createMediator(statusOnly: Boolean = false) =
        StatusDetailRemoteMediator(
            statusKey = MicroBlogKey("detail", "example.test"),
            accountKey = MicroBlogKey("me", "example.test"),
            service = MisskeyService("http://127.0.0.1:${server.address.port}/api/"),
            statusOnly = statusOnly,
        )

    @Test
    fun loadsAncestorsAndNestedDescendantsAroundDetailPost() =
        runTest {
            val refresh = mediator.load(pageSize = 20, request = PagingRequest.Refresh)
            val prepend = mediator.load(pageSize = 20, request = PagingRequest.Prepend(assertNotNull(refresh.previousKey)))
            val append = mediator.load(pageSize = 20, request = PagingRequest.Append(assertNotNull(refresh.nextKey)))
            assertEquals(
                listOf("grandparent", "parent", "detail", "child", "grandchild"),
                (prepend.data + refresh.data + append.data).map { it.statusKey.id },
            )
            assertEquals("child", append.nextKey)
        }

    @Test
    fun emptyConversationKeepsTheEmbeddedParentFallback() =
        runTest {
            server.removeContext("/api/notes/show")
            server.createContext("/api/notes/show") {
                if (showCalls.incrementAndGet() == 1) {
                    it.respond(DETAIL_JSON)
                } else {
                    it.respond("""{"error":{"message":"Rate limited","code":"RATE_LIMIT_EXCEEDED"}}""", status = 429)
                }
            }
            server.removeContext("/api/notes/conversation")
            server.createContext("/api/notes/conversation") { it.respond("[]") }
            val refresh = mediator.load(20, PagingRequest.Refresh)
            val parents = mediator.load(20, PagingRequest.Prepend(assertNotNull(refresh.previousKey)))
            assertEquals(listOf("parent"), parents.data.map { it.statusKey.id })
            assertEquals(1, showCalls.get())
            assertNull(parents.previousKey)
        }

    @Test
    fun rootPostWithEmptyConversationDoesNotRefetchTheMainPost() =
        runTest {
            server.removeContext("/api/notes/show")
            server.createContext("/api/notes/show") {
                showCalls.incrementAndGet()
                it.respond(GRANDPARENT_JSON.replace("\"id\":\"grandparent\"", "\"id\":\"detail\""))
            }
            server.removeContext("/api/notes/conversation")
            server.createContext("/api/notes/conversation") { it.respond("[]") }
            val refresh = mediator.load(20, PagingRequest.Refresh)
            assertEquals(listOf("detail"), refresh.data.map { it.statusKey.id })
            val parents = mediator.load(20, PagingRequest.Prepend(assertNotNull(refresh.previousKey)))
            assertTrue(parents.data.isEmpty())
            assertEquals(1, showCalls.get())
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun aLateRefreshCannotReplaceTheLatestEmbeddedParentFallback() =
        runTest(timeout = 10.seconds) {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            server.removeContext("/api/notes/show")
            server.createContext("/api/notes/show") {
                if (showCalls.incrementAndGet() == 1) {
                    firstStarted.countDown()
                    releaseFirst.await(10, TimeUnit.SECONDS)
                    it.respond(DETAIL_JSON.replace("\"parent\"", "\"old-parent\""))
                } else {
                    it.respond(DETAIL_JSON)
                }
            }
            server.removeContext("/api/notes/conversation")
            server.createContext("/api/notes/conversation") { it.respond("[]") }
            val first = async { mediator.load(20, PagingRequest.Refresh) }
            try {
                runCurrent()
                assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
                val latest = mediator.load(20, PagingRequest.Refresh)
                releaseFirst.countDown()
                first.await()
                val parents = mediator.load(20, PagingRequest.Prepend(assertNotNull(latest.previousKey)))
                assertEquals(listOf("parent"), parents.data.map { it.statusKey.id })
                assertEquals(2, showCalls.get())
            } finally {
                releaseFirst.countDown()
                first.cancelAndJoin()
            }
        }

    @Test
    fun failedRepliesDoNotBlockParentsAndCanBeRetriedSeparately() =
        runTest {
            server.removeContext("/api/notes/children")
            server.createContext("/api/notes/children") {
                if (childrenCalls.incrementAndGet() == 1) {
                    it.respond("""{"error":{"message":"Replies unavailable","code":"INTERNAL_ERROR"}}""", status = 500)
                } else {
                    it.respond("[]")
                }
            }
            val refresh = mediator.load(20, PagingRequest.Refresh)
            val request = PagingRequest.Append(assertNotNull(refresh.nextKey))
            val replies = async { runCatching { mediator.load(20, request) } }
            val parents = mediator.load(20, PagingRequest.Prepend(assertNotNull(refresh.previousKey)))
            assertEquals(listOf("grandparent", "parent"), parents.data.map { it.statusKey.id })
            assertTrue(replies.await().isFailure)
            val update = mediator.contextUpdate(request, mediator.load(20, request), initial = true)
            assertNull(update.before)
            assertTrue(assertNotNull(update.after).keys.isEmpty())
            assertEquals(1, showCalls.get())
            assertEquals(1, conversationCalls.get())
            assertEquals(2, childrenCalls.get())
        }

    @Test
    fun statusOnlyDoesNotRequestEitherContextSide() =
        runTest {
            val statusOnly = createMediator(statusOnly = true)
            val refresh = statusOnly.load(20, PagingRequest.Refresh)
            assertEquals(listOf("detail"), refresh.data.map { it.statusKey.id })
            assertNull(refresh.previousKey)
            assertNull(refresh.nextKey)
            assertTrue(statusOnly.load(20, PagingRequest.Prepend("")).data.isEmpty())
            assertTrue(statusOnly.load(20, PagingRequest.Append("")).data.isEmpty())
            assertEquals(1, showCalls.get())
            assertEquals(0, conversationCalls.get())
            assertEquals(0, childrenCalls.get())
        }

    private fun HttpExchange.respond(
        body: String,
        status: Int = 200,
    ) {
        val bytes = body.encodeToByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val GRANDPARENT_JSON =
            """{"id":"grandparent","createdAt":"2024-01-01T00:00:00Z","text":"grandparent","userId":"user","user":{"id":"user","username":"tester"},"visibility":"public","reactions":{},"renoteCount":0,"repliesCount":1}"""

        const val PARENT_JSON =
            """{"id":"parent","createdAt":"2024-01-01T00:01:00Z","text":"parent","userId":"user","user":{"id":"user","username":"tester"},"visibility":"public","reactions":{},"renoteCount":0,"repliesCount":1,"replyId":"grandparent","reply":$GRANDPARENT_JSON}"""

        const val DETAIL_JSON =
            """{"id":"detail","createdAt":"2024-01-01T00:02:00Z","text":"detail","userId":"user","user":{"id":"user","username":"tester"},"visibility":"public","reactions":{},"renoteCount":0,"repliesCount":1,"replyId":"parent","reply":$PARENT_JSON}"""

        const val CHILD_JSON =
            """{"id":"child","createdAt":"2024-01-01T00:03:00Z","text":"child","userId":"user","user":{"id":"user","username":"tester"},"visibility":"public","reactions":{},"renoteCount":0,"repliesCount":1,"replyId":"detail","reply":$DETAIL_JSON}"""

        const val GRANDCHILD_JSON =
            """{"id":"grandchild","createdAt":"2024-01-01T00:04:00Z","text":"grandchild","userId":"user","user":{"id":"user","username":"tester"},"visibility":"public","reactions":{},"renoteCount":0,"repliesCount":0,"replyId":"child","reply":$CHILD_JSON}"""
    }
}
