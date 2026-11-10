package dev.dimension.flare.feature.plugin.github

import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.network.proxyHttpClientEngine
import dev.dimension.flare.feature.plugin.installer.FppLimits
import dev.dimension.flare.feature.plugin.installer.FppPaths
import dev.dimension.flare.feature.plugin.installer.compareSemVer
import dev.dimension.flare.feature.plugin.manifest.isPluginSettingsPath
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.blackholeSink
import okio.buffer
import okio.use
import kotlin.native.HiddenFromObjC
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Serializable
public data class GitHubPluginRepositoryV1(
    val id: Long,
    val owner: String,
    val name: String,
) {
    val url: String get() = "https://github.com/$owner/$name"

    internal fun requireValid() {
        require(id > 0 && OWNER.matches(owner) && REPOSITORY.matches(name) && name != "." && name != "..") {
            "Invalid GitHub repository"
        }
    }
}

public data class GitHubPluginVersionV1(
    val tag: String,
    val version: String,
    val commitSha: String,
)

public data class GitHubPluginCatalogV1(
    val repository: GitHubPluginRepositoryV1,
    val versions: List<GitHubPluginVersionV1>,
    val path: String = "",
)

internal class GitHubRepositorySizeConfirmationRequired : IllegalStateException("Repository download exceeds 20 MiB")

@HiddenFromObjC
public class GitHubPluginClient(
    engine: HttpClientEngine = proxyHttpClientEngine(ProxyRef.Global),
) {
    private val client =
        HttpClient(engine) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
            }
        }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val catalogs = mutableMapOf<String, Pair<Instant, GitHubPluginCatalogV1>>()
    private val trees = mutableMapOf<String, List<TreeEntry>>()
    private val retryAfter = MutableStateFlow<Instant?>(null)

    public suspend fun catalog(
        repositoryUrl: String,
        forceRefresh: Boolean = false,
        expectedRepositoryId: Long? = null,
    ): GitHubPluginCatalogV1 =
        mutex.withLock {
            val location = parseRepositoryUrl(repositoryUrl)
            val (owner, name) = location
            val key = "$owner/$name".lowercase()
            val cached = catalogs[key]
            if (!forceRefresh && cached != null && Clock.System.now() - cached.first < 5.minutes) {
                require(
                    expectedRepositoryId == null || cached.second.repository.id == expectedRepositoryId,
                ) { "GitHub repository identity changed" }
                return@withLock cached.second.copy(path = location.path)
            }
            catalogs.remove(key)
            val metadata = json.decodeFromString<RepositoryResponse>(request("https://api.github.com/repos/$owner/$name").decodeToString())
            require(!metadata.isPrivate) { "Only public GitHub repositories are supported" }
            val repository = GitHubPluginRepositoryV1(metadata.id, metadata.owner.login, metadata.name).also { it.requireValid() }
            require(expectedRepositoryId == null || repository.id == expectedRepositoryId) { "GitHub repository identity changed" }
            val versions = mutableListOf<GitHubPluginVersionV1>()
            var page = 1
            while (true) {
                val tags =
                    json.decodeFromString<List<TagResponse>>(
                        request(
                            "https://api.github.com/repos/${repository.owner}/${repository.name}/tags?per_page=100&page=$page",
                        ).decodeToString(),
                    )
                require(tags.size <= 100) { "GitHub returned too many tags in one page" }
                require(page <= 10 || tags.isEmpty()) { "Too many GitHub tags to determine the latest version" }
                tags.forEach { tag ->
                    val version = stableGitHubVersion(tag.name) ?: return@forEach
                    require(COMMIT_SHA.matches(tag.commit.sha)) { "Invalid GitHub tag commit" }
                    versions += GitHubPluginVersionV1(tag.name, version, tag.commit.sha)
                }
                if (tags.size < 100) break
                page++
            }
            require(versions.isNotEmpty()) { "This repository has no stable version tags" }
            require(versions.map { it.tag }.distinct().size == versions.size) { "Duplicate GitHub tags" }
            val catalog =
                GitHubPluginCatalogV1(
                    repository,
                    versions.sortedWith { first, second ->
                        compareSemVer(second.version, first.version).takeIf { it != 0 } ?: first.tag.compareTo(second.tag)
                    },
                )
            catalogs[key] = Clock.System.now() to catalog
            if (catalogs.size > 32) catalogs.remove(catalogs.keys.first())
            catalog.copy(path = location.path)
        }

    public suspend fun archive(
        repository: GitHubPluginRepositoryV1,
        version: GitHubPluginVersionV1,
        fileSystem: FileSystem,
        destination: Path,
        allowLargeRepository: Boolean = false,
    ) {
        repository.requireValid()
        require(stableGitHubVersion(version.tag) == version.version && COMMIT_SHA.matches(version.commitSha)) { "Invalid GitHub version" }
        try {
            fileSystem.sink(destination).buffer().use { sink ->
                request(
                    "https://api.github.com/repos/${repository.owner}/${repository.name}/zipball/${version.commitSha}",
                    limit = FppLimits.MAX_REPOSITORY_BYTES,
                    sink = sink,
                    confirmationThreshold = FppLimits.REPOSITORY_CONFIRMATION_BYTES.takeUnless { allowLargeRepository },
                )
            }
        } catch (error: Throwable) {
            fileSystem.delete(destination, mustExist = false)
            throw error
        }
    }

    public suspend fun packageFingerprint(
        repository: GitHubPluginRepositoryV1,
        version: GitHubPluginVersionV1,
        path: String,
    ): Map<String, String> =
        mutex.withLock {
            repository.requireValid()
            require(COMMIT_SHA.matches(version.commitSha)) { "Invalid GitHub tag commit" }
            require(normalizePluginRepositoryPath(path) == path) { "Invalid plugin repository path" }
            var entries = tree(repository, version.commitSha)
            path.split('/').filter(String::isNotEmpty).forEach { segment ->
                val directory =
                    requireNotNull(entries.singleOrNull { it.path == segment && it.type == "tree" }) {
                        "Plugin directory not found: $path"
                    }
                entries = tree(repository, directory.sha)
            }
            val files = linkedMapOf<String, String>()

            fun add(
                name: String,
                entry: TreeEntry,
                limit: Long,
            ) {
                require(entry.type == "blob" && entry.mode in setOf("100644", "100755")) { "Unsupported plugin file: $name" }
                require(entry.size != null && entry.size in 0..limit) { "Plugin file exceeds its size limit: $name" }
                require(files.size < FppLimits.MAX_ENTRY_COUNT) { "Invalid package entry count" }
                files[name] = entry.sha
            }
            add(
                FppPaths.MANIFEST,
                requireNotNull(entries.singleOrNull { it.path == FppPaths.MANIFEST }) { "Plugin is missing manifest.json" },
                FppLimits.MAX_MANIFEST_BYTES,
            )
            entries.singleOrNull { it.path == FppPaths.ENTRY }?.let {
                add(FppPaths.ENTRY, it, FppLimits.MAX_SCRIPT_BYTES)
            }
            entries.singleOrNull { it.path == "assets" }?.let { assets ->
                require(assets.type == "tree") { "Invalid plugin assets directory" }
                tree(repository, assets.sha).filter { "assets/${it.path}" in FppPaths.ICONS }.forEach {
                    add("assets/${it.path}", it, FppLimits.MAX_ICON_BYTES)
                }
            }
            require(files.keys.count { it in FppPaths.ICONS } == 1) { "Plugin must contain exactly one supported icon" }
            entries.singleOrNull { it.path == "locales" }?.let { locales ->
                require(locales.type == "tree") { "Invalid plugin locales directory" }
                val catalogs = tree(repository, locales.sha)
                require(catalogs.none { it.type == "tree" }) { "Nested plugin locale directories are not supported" }
                val translations = catalogs.filter { it.path.endsWith(".json") }
                require(translations.size <= FppLimits.MAX_CATALOG_COUNT) { "Too many locale catalogs" }
                translations.forEach { add("locales/${it.path}", it, FppLimits.MAX_CATALOG_BYTES) }
            }
            var settingsDirectories = 0

            suspend fun settings(
                directory: TreeEntry,
                prefix: String,
            ) {
                require(directory.type == "tree") { "Invalid plugin settings directory" }
                require(++settingsDirectories <= FppLimits.MAX_REPOSITORY_ENTRY_COUNT) { "Too many settings directories" }
                // Even the shortest supported filename must fit before walking a directory.
                if (!isPluginSettingsPath("$prefix/.js")) return
                tree(repository, directory.sha).forEach { entry ->
                    val name = "$prefix/${entry.path}"
                    if (entry.type == "tree") {
                        settings(entry, name)
                    } else if (isPluginSettingsPath(name)) {
                        add(name, entry, FppLimits.MAX_SCRIPT_BYTES)
                    }
                }
            }
            entries.singleOrNull { it.path == "settings" }?.let { settings(it, "settings") }
            files
        }

    private suspend fun tree(
        repository: GitHubPluginRepositoryV1,
        sha: String,
    ): List<TreeEntry> {
        require(COMMIT_SHA.matches(sha)) { "Invalid GitHub tree SHA" }
        val url = "https://api.github.com/repos/${repository.owner}/${repository.name}/git/trees/$sha"
        trees[url]?.let { return it }
        val response = json.decodeFromString<TreeResponse>(request(url, limit = 7L * 1024 * 1024).decodeToString())
        require(!response.truncated) { "GitHub returned an incomplete directory listing" }
        require(response.tree.size <= FppLimits.MAX_REPOSITORY_ENTRY_COUNT) { "Too many repository files" }
        require(
            response.tree
                .map { it.path.lowercase() }
                .distinct()
                .size == response.tree.size,
        ) { "Duplicate GitHub tree path" }
        response.tree.forEach {
            require(
                it.path.isNotEmpty() && '/' !in it.path && '\\' !in it.path && it.path != "." && it.path != ".." &&
                    it.path.none(Char::isISOControl) && COMMIT_SHA.matches(it.sha),
            ) { "Invalid GitHub tree entry" }
        }
        trees[url] = response.tree
        while (trees.size > 128 || trees.values.sumOf(List<TreeEntry>::size) > 8_192) trees.remove(trees.keys.first())
        return response.tree
    }

    public fun close() {
        client.close()
    }

    private suspend fun request(
        initialUrl: String,
        limit: Long = 2L * 1024 * 1024,
        sink: BufferedSink? = null,
        confirmationThreshold: Long? = null,
    ): ByteArray =
        withTimeout(120_000) {
            require(retryAfter.value?.let { Clock.System.now() >= it } != false) { "GitHub is rate limited; try again later" }
            var url = initialUrl
            repeat(6) {
                val target = Url(url)
                require(
                    target.protocol == URLProtocol.HTTPS && target.port == 443 && target.user == null && target.password == null &&
                        target.host in setOf("api.github.com", "codeload.github.com"),
                ) { "Unsupported GitHub download redirect" }
                var redirect: String? = null
                val bytes =
                    client
                        .prepareGet(url) {
                            header(HttpHeaders.UserAgent, "Flare-Plugin-Installer")
                            header(HttpHeaders.Accept, "application/vnd.github+json")
                            header("X-GitHub-Api-Version", "2022-11-28")
                        }.execute { response ->
                            if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                                redirect = requireNotNull(response.headers[HttpHeaders.Location]) { "Missing GitHub download redirect" }
                                return@execute null
                            }
                            if (response.status.value == 429 || response.status.value == 403) {
                                val now = Clock.System.now()
                                val seconds =
                                    response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
                                        ?: response.headers["X-RateLimit-Reset"]?.toLongOrNull()?.minus(now.epochSeconds)
                                        ?: 60L
                                retryAfter.value = now + seconds.coerceIn(1, 3_600).seconds
                                error("GitHub request was rate limited or denied; try again later")
                            }
                            require(response.status.value != 404) { "Public GitHub repository or version not found" }
                            require(response.status.value == 200) { "GitHub request failed (${response.status.value})" }
                            val channel = response.bodyAsChannel()
                            require(response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it <= limit } != false) {
                                channel.cancel()
                                "GitHub response exceeds its size limit"
                            }
                            val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                            if (confirmationThreshold != null && length != null && length > confirmationThreshold) {
                                channel.cancel()
                                throw GitHubRepositorySizeConfirmationRequired()
                            }
                            val memory = if (sink == null) Buffer() else null
                            val output = sink ?: requireNotNull(memory)
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            try {
                                while (true) {
                                    val count = channel.readAvailable(buffer, 0, buffer.size)
                                    if (count == -1) break
                                    total += count
                                    require(total <= limit) { "GitHub response exceeds its size limit" }
                                    if (confirmationThreshold != null && total > confirmationThreshold) {
                                        throw GitHubRepositorySizeConfirmationRequired()
                                    }
                                    output.write(buffer, 0, count)
                                }
                            } finally {
                                channel.cancel()
                            }
                            memory?.readByteArray() ?: byteArrayOf()
                        }
                if (bytes != null) return@withTimeout bytes
                url = requireNotNull(redirect)
            }
            error("Too many GitHub download redirects")
        }
}

internal data class GitHubPluginLocation(
    val owner: String,
    val name: String,
    val path: String,
)

internal fun parseRepositoryUrl(value: String): GitHubPluginLocation {
    require(value.length <= 2_048) { "Invalid GitHub repository URL" }
    val url = Url(value.trim())
    require(
        url.protocol == URLProtocol.HTTPS && url.host == "github.com" && url.port == 443 &&
            url.user == null && url.password == null && url.parameters.names().all { it == "path" } && url.fragment.isEmpty(),
    ) { "Enter a public https://github.com/owner/repository URL" }
    val segments = url.encodedPath.trim('/').split('/')
    require(segments.size == 2) { "Enter a GitHub repository URL with an optional ?path=/plugin-directory" }
    val owner = segments[0]
    val name = segments[1].removeSuffix(".git")
    GitHubPluginRepositoryV1(1, owner, name).requireValid()
    require(
        url.parameters
            .getAll("path")
            .orEmpty()
            .size <= 1,
    ) { "Duplicate plugin repository path" }
    return GitHubPluginLocation(owner, name, normalizePluginRepositoryPath(url.parameters["path"].orEmpty()))
}

internal fun normalizePluginRepositoryPath(value: String): String {
    val path = value.trim('/')
    require(
        value.length <= 1_024 && '\\' !in value && value.none(Char::isISOControl) &&
            !Regex("^[A-Za-z]:").containsMatchIn(path) &&
            (path.isEmpty() || path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }),
    ) {
        "Invalid plugin repository path"
    }
    return path
}

internal fun gitHubPluginUrl(
    repositoryUrl: String,
    path: String,
): String = if (path.isEmpty()) repositoryUrl else URLBuilder(repositoryUrl).apply { parameters.append("path", "/$path") }.buildString()

internal fun gitBlobHash(bytes: ByteArray): String {
    val hashing = HashingSink.sha1(blackholeSink())
    hashing.buffer().use { sink -> sink.writeUtf8("blob ${bytes.size}\u0000").write(bytes) }
    return hashing.hash.hex()
}

internal fun stableGitHubVersion(tag: String): String? {
    val version = tag.removePrefix("v")
    if (version.length > 128 || !STABLE_VERSION.matches(version)) return null
    if (version.substringBefore('+').split('.').any { it.toLongOrNull() == null }) return null
    return version
}

internal val COMMIT_SHA: Regex = Regex("[0-9a-f]{40}")
private val OWNER = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
private val REPOSITORY = Regex("[A-Za-z0-9_.-]{1,100}")
private val STABLE_VERSION = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")

@Serializable
private data class RepositoryResponse(
    val id: Long,
    val name: String,
    val owner: OwnerResponse,
    @SerialName("private") val isPrivate: Boolean,
)

@Serializable
private data class OwnerResponse(
    val login: String,
)

@Serializable
private data class TagResponse(
    val name: String,
    val commit: CommitResponse,
)

@Serializable
private data class TreeResponse(
    val tree: List<TreeEntry>,
    val truncated: Boolean,
)

@Serializable
private data class TreeEntry(
    val path: String,
    val type: String,
    val mode: String,
    val sha: String,
    val size: Long? = null,
)

@Serializable
private data class CommitResponse(
    val sha: String,
)
