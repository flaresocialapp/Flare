package dev.dimension.flare.feature.plugin.marketplace

import dev.dimension.flare.data.io.AppFileStore
import dev.dimension.flare.feature.plugin.installer.pluginIconFixtures
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginMarketplaceTest {
    private val root = Files.createTempDirectory("plugin-marketplace").toOkioPath()
    private val files =
        object : AppFileStore {
            override val fileSystem = FileSystem.SYSTEM

            override fun directory(name: String): Path = root / name
        }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(root)
    }

    @Test
    fun searchMatchesOnlyNamesAndDescriptionsAndPreservesCatalogOrder() {
        val first = PluginMarketplaceEntryV1("hidden.id", "Zhihu", "知乎热榜与推荐", "https://github.com/hidden/repository", "")
        val second = first.copy(id = "other.id", name = "Reader", description = "A ZHIHU reader")
        val state = PluginMarketplaceStateV1(plugins = listOf(first, second), loaded = true)

        assertEquals(listOf(first, second), state.search("  zHiHu  "))
        assertEquals(listOf(first), state.search("推荐"))
        assertEquals(state.plugins, state.search(" \n\t "))
        assertTrue(state.search("hidden.id").isEmpty())
        assertTrue(state.search("hidden/repository").isEmpty())
        assertTrue(state.search("unmatched").isEmpty())
        assertTrue(state.copy(plugins = listOf(second)).search("推荐").isEmpty())
        assertEquals(2, state.plugins.size)
    }

    @Test
    fun callerCancellationStillPropagatesThroughTheRequestDeadline() =
        runTest {
            val client =
                PluginMarketplace(
                    files,
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler { awaitCancellation() }
                        },
                    ),
                )
            try {
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(1) { client.load() }
                }
                assertNull(client.cachedCatalog())
            } finally {
                client.close()
            }
        }

    @Test
    fun checkedInCatalogUsesTheSameValidationAsDownloadedCatalogs() =
        runBlocking {
            val client = PluginMarketplace(files, MockEngine { respond(File("../../docs/plugins/index.json").readText()) })
            try {
                assertTrue(client.load().plugins.isNotEmpty())
            } finally {
                client.close()
            }
        }

    @Test
    fun catalogCachesAcrossRestartsAndOnlyRefreshesAfterAnHourOrOnRequest() =
        runBlocking {
            var requests = 0
            var time = 10_000L
            val client =
                PluginMarketplace(
                    files,
                    MockEngine {
                        requests++
                        respond(CATALOG)
                    },
                    now = { time },
                )
            try {
                assertNull(client.cachedCatalog())
                val catalog = client.load()
                assertEquals("example.plugin", catalog.plugins.single().id)
                assertEquals(catalog, client.load())
                assertEquals(1, requests)
                client.load(forceRefresh = true)
                assertEquals(2, requests)
                time += 3_600_000
                client.load()
                assertEquals(3, requests)
            } finally {
                client.close()
            }
            val restarted = PluginMarketplace(files, MockEngine { error("A fresh cache must not access the network") }, now = { time })
            try {
                assertEquals(restarted.cachedCatalog(), restarted.load())
            } finally {
                restarted.close()
            }
        }

    @Test
    fun malformedOrUnavailableRefreshesKeepTheLastValidCatalogOnDisk() =
        runBlocking {
            var response = CATALOG
            val client = PluginMarketplace(files, MockEngine { respond(response) })
            try {
                val catalog = client.load()
                for (invalid in listOf(
                    CATALOG.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                    CATALOG.replace("\"plugins\":[", "\"plugins\":[$ENTRY,"),
                    CATALOG.replace("https://github.com/owner/repo", "https://github.com.evil/owner/repo"),
                    CATALOG.replace("raw.githubusercontent.com/", "localhost/"),
                    CATALOG.replace("raw.githubusercontent.com/", "flareapp.moe/"),
                    CATALOG.replace("https://raw.", "http://raw."),
                    CATALOG.replace("raw.githubusercontent.com/", "user:secret@raw.githubusercontent.com/"),
                    CATALOG.replace("\"name\":\"Example\"", "\"name\":\"\""),
                    "{broken",
                )) {
                    response = invalid
                    assertFails { client.load(forceRefresh = true) }
                    assertEquals(catalog, client.cachedCatalog())
                }
            } finally {
                client.close()
            }
            val unavailable = PluginMarketplace(files, MockEngine { respond("", HttpStatusCode.NotFound) })
            try {
                assertFails { unavailable.load(forceRefresh = true) }
                assertEquals("example.plugin", assertNotNull(unavailable.cachedCatalog()).plugins.single().id)
            } finally {
                unavailable.close()
            }
        }

    @Test
    fun responsesAreBoundedWithAndWithoutAContentLengthAndRedirectsAreRejected() =
        runBlocking {
            for (headers in listOf(headersOf(), headersOf(HttpHeaders.ContentLength, "1048577"))) {
                val client = PluginMarketplace(files, MockEngine { respond(" ".repeat(1_048_577), headers = headers) })
                try {
                    assertFails { client.load() }
                    assertNull(client.cachedCatalog())
                } finally {
                    client.close()
                }
            }
            var requests = 0
            val redirected =
                PluginMarketplace(
                    files,
                    MockEngine {
                        requests++
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://example.com/icon.png"))
                    },
                )
            try {
                assertFails { redirected.load() }
                assertEquals(1, requests)
            } finally {
                redirected.close()
            }
        }

    @Test
    fun iconsAreValidatedCachedAndFailWithoutAffectingTheCatalog() =
        runBlocking {
            var iconRequests = 0
            var iconBytes = pluginIconFixtures.getValue("png")
            val client =
                PluginMarketplace(
                    files,
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("index.json")) {
                            respond(CATALOG)
                        } else {
                            iconRequests++
                            respond(iconBytes)
                        }
                    },
                )
            try {
                val catalog = client.load()
                val entry = catalog.plugins.single()
                val path = assertNotNull(client.icon(entry))
                assertEquals(path, client.icon(entry))
                assertEquals(1, iconRequests)
                assertTrue(FileSystem.SYSTEM.exists(path.toPath()))
                iconBytes = "not an image".encodeToByteArray()
                assertNull(client.icon(entry.copy(icon = entry.icon.replace("icon.png", "invalid.png"))))
                assertNull(client.icon(entry.copy(icon = "https://example.com/icon.png")))
                assertNull(client.icon(entry.copy(icon = "https://flareapp.moe/plugins/index.json")))
                assertEquals(2, iconRequests)
                assertEquals(catalog, client.cachedCatalog())
            } finally {
                client.close()
            }
        }
}

private val ENTRY =
    """
    {"id":"example.plugin","name":"Example","description":"Example plugin",
    "repository":"https://github.com/owner/repo",
    "icon":"https://raw.githubusercontent.com/owner/repo/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/icon.png"}
    """.trimIndent()
private val CATALOG = """{"schemaVersion":1,"extra":"ignored","plugins":[$ENTRY]}"""
