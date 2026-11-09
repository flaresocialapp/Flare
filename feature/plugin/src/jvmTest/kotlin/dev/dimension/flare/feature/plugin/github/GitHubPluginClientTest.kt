package dev.dimension.flare.feature.plugin.github

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GitHubPluginClientTest {
    @Test
    fun pluginPathsAreNormalizedWithoutAllowingTraversalOrChangingRepositoryIdentity() {
        val location = parseRepositoryUrl("https://github.com/Owner/Repo.git?path=/plugins/%E6%B5%8B%E8%AF%95%20feed/")
        assertEquals(GitHubPluginLocation("Owner", "Repo", "plugins/测试 feed"), location)
        assertEquals(location, parseRepositoryUrl(gitHubPluginUrl("https://github.com/Owner/Repo", location.path)))
        assertEquals("", parseRepositoryUrl("https://github.com/owner/repo?path=/").path)
        listOf(
            "?path=/plugins/../escape",
            "?path=/%2e%2e/escape",
            "?path=/plugins//feed",
            "?path=plugins%5Cfeed",
            "?path=plugins%00feed",
            "?path=C:/feed",
            "?path=/first&path=/second",
            "?path=/first&ref=main",
        ).forEach { assertFailsWith<IllegalArgumentException>(it) { parseRepositoryUrl("https://github.com/owner/repo$it") } }
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", gitBlobHash(byteArrayOf()))
    }

    @Test
    fun cachedCatalogsKeepDifferentPluginPathsSeparate() =
        runBlocking {
            var calls = 0
            withClient(
                MockEngine { request ->
                    calls++
                    respond(if (request.url.encodedPath.endsWith("/tags")) "[${tag("v1.0.0")}]" else METADATA)
                },
            ) { client ->
                assertEquals("plugins/first", client.catalog("${REPOSITORY.url}?path=/plugins/first").path)
                assertEquals("plugins/second", client.catalog("${REPOSITORY.url}?path=/plugins/second").path)
                assertEquals("", client.catalog(REPOSITORY.url).path)
                assertEquals(2, calls)
            }
        }

    @Test
    fun largeDownloadsRequireConfirmationWithAndWithoutAContentLength() =
        runBlocking {
            val bytes = ByteArray((dev.dimension.flare.feature.plugin.installer.FppLimits.REPOSITORY_CONFIRMATION_BYTES + 1).toInt())
            listOf(false, true).forEach { knownLength ->
                val headers = if (knownLength) headersOf(HttpHeaders.ContentLength, bytes.size.toString()) else headersOf()
                withClient(MockEngine { respond(bytes, headers = headers) }) { client ->
                    assertFailsWith<GitHubRepositorySizeConfirmationRequired> { downloadArchive(client) }
                    assertEquals(bytes.size, downloadArchive(client, allowLargeRepository = true).size)
                }
            }
        }

    @Test
    fun repositoryUrlsCannotBecomeArbitraryNetworkRequests() {
        assertEquals(GitHubPluginLocation("Owner", "Repo", ""), parseRepositoryUrl(" https://github.com/Owner/Repo.git/ "))
        listOf(
            "http://github.com/owner/repo",
            "https://github.com.evil/owner/repo",
            "https://user:secret@github.com/owner/repo",
            "https://github.com:8443/owner/repo",
            "https://github.com/owner/repo/tree/main",
            "https://github.com/owner/repo?ref=main",
            "https://github.com/owner/repo#main",
            "https://github.com/owner/%2e%2e",
            "https://github.com/owner/..",
        ).forEach { assertFailsWith<IllegalArgumentException>(it) { parseRepositoryUrl(it) } }
        assertEquals("1.2.3", stableGitHubVersion("v1.2.3"))
        listOf("main", "nightly", "v1.0", "v01.0.0", "v1.0.0-beta.1", "v99999999999999999999.0.0").forEach {
            assertNull(stableGitHubVersion(it))
        }
    }

    @Test
    fun tagsArePaginatedSortedBySemverAndCachedUntilExplicitRefresh() =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine { request ->
                    requests++
                    val content =
                        when {
                            !request.url.encodedPath.endsWith("/tags") -> {
                                METADATA
                            }

                            request.url.parameters["page"] == "1" -> {
                                (listOf(tag("v1.9.0")) + (1..99).map { tag("development-$it") }).joinToString(",", "[", "]")
                            }

                            else -> {
                                "[${tag("v1.10.0")},${tag("v2.0.0-beta.1")},${tag("v1.2.0")} ]"
                            }
                        }
                    respond(content)
                }
            withClient(engine) { client ->
                val first = client.catalog(REPOSITORY.url)
                assertEquals(listOf("1.10.0", "1.9.0", "1.2.0"), first.versions.map { it.version })
                assertEquals(3, requests)
                assertEquals(first, client.catalog(REPOSITORY.url))
                assertEquals(3, requests)
                client.catalog(REPOSITORY.url, forceRefresh = true)
                assertEquals(6, requests)
                assertFails { client.catalog(REPOSITORY.url, expectedRepositoryId = 999) }
                assertEquals(6, requests)
            }
        }

    @Test
    fun privateRepositoriesAndReplacedRepositoryIdentitiesAreRejected() =
        runBlocking {
            withClient(MockEngine { respond(METADATA.replace("false", "true")) }) { client ->
                assertContains(assertFails { client.catalog(REPOSITORY.url) }.message.orEmpty(), "public")
            }
            withClient(MockEngine { respond(METADATA) }) { client ->
                assertContains(
                    assertFails { client.catalog(REPOSITORY.url, expectedRepositoryId = 999) }.message.orEmpty(),
                    "identity changed",
                )
            }
        }

    @Test
    fun noStableTagsAndIncompletePaginationCannotReportAnUpToDateVersion() =
        runBlocking {
            withClient(
                MockEngine { request ->
                    respond(if (request.url.encodedPath.endsWith("/tags")) "[${tag("nightly")}]" else METADATA)
                },
            ) { client ->
                assertContains(assertFails { client.catalog(REPOSITORY.url) }.message.orEmpty(), "no stable")
            }
            var pages = 0
            withClient(
                MockEngine { request ->
                    respond(
                        if (request.url.encodedPath.endsWith("/tags")) {
                            pages++
                            (1..100).joinToString(",", "[", "]") { tag("v$pages.$it.0") }
                        } else {
                            METADATA
                        },
                    )
                },
            ) { client ->
                assertContains(assertFails { client.catalog(REPOSITORY.url) }.message.orEmpty(), "Too many GitHub tags")
                assertEquals(11, pages)
            }
        }

    @Test
    fun archiveUsesPinnedCommitAndOnlyFollowsGitHubDownloadRedirects() =
        runBlocking {
            val paths = mutableListOf<String>()
            val archive = byteArrayOf(0x50, 0x4b, 1, 2, 3)
            withClient(
                MockEngine { request ->
                    paths += request.url.toString()
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertNull(request.headers[HttpHeaders.Cookie])
                    if (request.url.host == "api.github.com") {
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "https://codeload.github.com/owner/repo/legacy.zip/$SHA"),
                        )
                    } else {
                        respond(archive)
                    }
                },
            ) { client ->
                assertContentEquals(archive, downloadArchive(client))
                assertEquals("https://api.github.com/repos/owner/repo/zipball/$SHA", paths.first())
                assertEquals(2, paths.size)
            }
            listOf(
                "http://codeload.github.com/owner/repo",
                "https://evil.example/archive.zip",
                "https://user@api.github.com/repos/owner/repo",
            ).forEach { location ->
                var calls = 0
                withClient(
                    MockEngine {
                        calls++
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
                    },
                ) { client ->
                    assertFails { downloadArchive(client) }
                    assertEquals(1, calls)
                }
            }
        }

    @Test
    fun responseLimitsApplyWithAndWithoutContentLength() =
        runBlocking {
            withClient(MockEngine { respond("", headers = headersOf(HttpHeaders.ContentLength, "999999999")) }) { client ->
                assertContains(assertFails { downloadArchive(client) }.message.orEmpty(), "size limit")
                assertContains(assertFails { downloadArchive(client, allowLargeRepository = true) }.message.orEmpty(), "size limit")
            }
            withClient(MockEngine { respond(ByteArray(2 * 1024 * 1024 + 1)) }) { client ->
                assertContains(assertFails { client.catalog(REPOSITORY.url) }.message.orEmpty(), "size limit")
            }
        }

    @Test
    fun rateLimitRetryAfterPreventsRepeatedNetworkRequests() =
        runBlocking {
            var calls = 0
            withClient(
                MockEngine {
                    calls++
                    respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "60"))
                },
            ) { client ->
                assertFails { client.catalog(REPOSITORY.url) }
                assertFails { client.catalog(REPOSITORY.url, forceRefresh = true) }
                assertEquals(1, calls)
            }
        }

    @Test
    fun rateLimitsAndUnavailableRepositoriesRemainErrors() =
        runBlocking {
            listOf(HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests, HttpStatusCode.NotFound).forEach { status ->
                withClient(MockEngine { respond("{}", status) }) { client ->
                    assertFails { client.catalog(REPOSITORY.url) }
                }
            }
        }
}

private suspend fun withClient(
    engine: MockEngine,
    block: suspend (GitHubPluginClient) -> Unit,
) {
    val client = GitHubPluginClient(engine)
    try {
        block(client)
    } finally {
        client.close()
    }
}

private fun tag(name: String): String = """{"name":"$name","commit":{"sha":"$SHA"}}"""

private const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private val REPOSITORY = GitHubPluginRepositoryV1(42, "owner", "repo")
private val VERSION = GitHubPluginVersionV1("v1.0.0", "1.0.0", SHA)
private const val METADATA = """{"id":42,"name":"repo","owner":{"login":"owner"},"private":false}"""

private suspend fun downloadArchive(
    client: GitHubPluginClient,
    allowLargeRepository: Boolean = false,
): ByteArray {
    val fileSystem = FileSystem.SYSTEM
    val root = Files.createTempDirectory("github-archive-test").toOkioPath()
    val destination = root / "source.zip"
    try {
        client.archive(REPOSITORY, VERSION, fileSystem, destination, allowLargeRepository)
        return fileSystem.read(destination) { readByteArray() }
    } finally {
        fileSystem.deleteRecursively(root)
    }
}
