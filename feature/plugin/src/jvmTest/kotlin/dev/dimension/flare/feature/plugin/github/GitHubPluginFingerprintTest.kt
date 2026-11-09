package dev.dimension.flare.feature.plugin.github

import dev.dimension.flare.feature.plugin.installer.TestFppFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class GitHubPluginFingerprintTest {
    @Test
    fun fingerprintsOnlyTheSelectedRuntimeFilesAndCachesImmutableTrees() =
        runBlocking {
            val fileSystem = FileSystem.SYSTEM
            val root = Files.createTempDirectory("github-fingerprint").toOkioPath()
            val archive = root / "source.zip"
            val files = TestFppFactory.validEntries() + ("locales/en.json" to """{"name":"Feed"}""".encodeToByteArray())
            TestFppFactory.write(
                archive,
                files.map { (name, bytes) -> "root/plugins/feed/$name" to bytes } +
                    TestFppFactory.validEntries().map { (name, bytes) -> "root/plugins/feed-other/$name" to bytes } +
                    ("root/plugins/feed/README.md" to "development".encodeToByteArray()),
            )
            val commit = "a".repeat(40)
            val trees = testGitHubTrees(fileSystem.read(archive) { readByteArray() }, commit)
            var calls = 0
            val client =
                GitHubPluginClient(
                    MockEngine { request ->
                        calls++
                        val response = trees[request.url.encodedPath.substringAfterLast('/')]
                        if (response == null) respond("{}", HttpStatusCode.NotFound) else respond(response)
                    },
                )
            try {
                val repository = GitHubPluginRepositoryV1(1, "owner", "repo")
                val version = GitHubPluginVersionV1("v9.0.0", "9.0.0", commit)
                assertEquals(
                    files.associate { (name, bytes) ->
                        name to gitBlobHash(bytes)
                    },
                    client.packageFingerprint(repository, version, "plugins/feed"),
                )
                val before = calls
                client.packageFingerprint(repository, version, "plugins/feed")
                assertEquals(before, calls)
                assertFails { client.packageFingerprint(repository, version, "plugins/fee") }
                Unit
            } finally {
                client.close()
                fileSystem.deleteRecursively(root)
            }
        }

    @Test
    fun incompleteListingsAreErrorsInsteadOfUnchangedPackages() =
        runBlocking {
            val client = GitHubPluginClient(MockEngine { respond("""{"tree":[],"truncated":true}""") })
            try {
                assertFails {
                    client.packageFingerprint(
                        GitHubPluginRepositoryV1(1, "owner", "repo"),
                        GitHubPluginVersionV1("v1.0.0", "1.0.0", "a".repeat(40)),
                        "",
                    )
                }
                Unit
            } finally {
                client.close()
            }
        }
}
