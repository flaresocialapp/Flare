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
import kotlin.test.assertNotEquals

class GitHubPluginFingerprintTest {
    @Test
    fun fingerprintsOnlyTheSelectedRuntimeFilesAndCachesImmutableTrees() =
        runBlocking {
            val fileSystem = FileSystem.SYSTEM
            val root = Files.createTempDirectory("github-fingerprint").toOkioPath()
            val archive = root / "source.zip"
            val files =
                TestFppFactory.validEntries() +
                    listOf(
                        "locales/en.json" to """{"name":"Feed"}""".encodeToByteArray(),
                        "settings/index.html" to "<!doctype html><title>Settings</title>".encodeToByteArray(),
                        "settings/chunks/options.mjs" to "export const version = 1;".encodeToByteArray(),
                        "settings/${"a".repeat(240)}/${"b".repeat(240)}/${"c".repeat(17)}/.js" to byteArrayOf(),
                    )
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
    fun settingsOnlyPackagesNeedNoBackgroundScriptAndPageEditsChangeTheirFingerprint() =
        runBlocking<Unit> {
            val fileSystem = FileSystem.SYSTEM
            val root = Files.createTempDirectory("github-settings-fingerprint").toOkioPath()
            val archive = root / "source.zip"
            val files =
                listOf(
                    "manifest.json" to
                        """
                        {"apiVersion":2,"id":"dev.test.settings","version":"1.0.0","name":"Settings",
                         "settings":{"entry":"settings/index.html"}}
                        """.trimIndent().encodeToByteArray(),
                    "assets/icon.png" to TestFppFactory.validPng,
                    "settings/index.html" to "<!doctype html><title>Settings</title>".encodeToByteArray(),
                    "settings/chunks/options.mjs" to "export const version = 1;".encodeToByteArray(),
                )
            val original = "a".repeat(40)
            val updated = "b".repeat(40)
            TestFppFactory.write(archive, files.map { (name, bytes) -> "root/$name" to bytes })
            val trees = testGitHubTrees(fileSystem.read(archive) { readByteArray() }, original).toMutableMap()
            val changed =
                files.map { (name, bytes) ->
                    name to if (name == "settings/chunks/options.mjs") "export const version = 2;".encodeToByteArray() else bytes
                }
            TestFppFactory.write(archive, changed.map { (name, bytes) -> "root/$name" to bytes })
            trees += testGitHubTrees(fileSystem.read(archive) { readByteArray() }, updated)
            val client =
                GitHubPluginClient(
                    MockEngine { request ->
                        respond(trees.getValue(request.url.encodedPath.substringAfterLast('/')))
                    },
                )
            try {
                val repository = GitHubPluginRepositoryV1(1, "owner", "repo")
                val first = client.packageFingerprint(repository, GitHubPluginVersionV1("v1.0.0", "1.0.0", original), "")
                val second = client.packageFingerprint(repository, GitHubPluginVersionV1("v1.0.1", "1.0.1", updated), "")
                assertEquals(files.associate { (name, bytes) -> name to gitBlobHash(bytes) }, first)
                assertEquals(changed.associate { (name, bytes) -> name to gitBlobHash(bytes) }, second)
                assertNotEquals(first, second)
                assertEquals(first - "settings/chunks/options.mjs", second - "settings/chunks/options.mjs")
            } finally {
                client.close()
                fileSystem.deleteRecursively(root)
            }
        }

    @Test
    fun settingsResourcesCannotExceedThePackageEntryLimit() =
        runBlocking<Unit> {
            val fileSystem = FileSystem.SYSTEM
            val root = Files.createTempDirectory("github-settings-limit").toOkioPath()
            val archive = root / "source.zip"
            TestFppFactory.write(
                archive,
                (TestFppFactory.validEntries() + List(256) { "settings/resource$it.js" to byteArrayOf() })
                    .map { (name, bytes) -> "root/$name" to bytes },
            )
            val commit = "a".repeat(40)
            val trees = testGitHubTrees(fileSystem.read(archive) { readByteArray() }, commit)
            val client =
                GitHubPluginClient(
                    MockEngine { request ->
                        respond(trees.getValue(request.url.encodedPath.substringAfterLast('/')))
                    },
                )
            try {
                assertFails {
                    client.packageFingerprint(
                        GitHubPluginRepositoryV1(1, "owner", "repo"),
                        GitHubPluginVersionV1("v1.0.0", "1.0.0", commit),
                        "",
                    )
                }
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
