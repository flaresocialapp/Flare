package dev.dimension.flare.feature.plugin.marketplace

import dev.dimension.flare.data.io.AppFileStore
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.network.proxyHttpClientEngine
import dev.dimension.flare.feature.plugin.github.parseRepositoryUrl
import dev.dimension.flare.feature.plugin.installer.FppLimits
import dev.dimension.flare.feature.plugin.installer.pluginIconExtension
import dev.dimension.flare.feature.plugin.installer.validatePluginIconImage
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.Path
import kotlin.native.HiddenFromObjC
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@Serializable
public data class PluginMarketplaceEntryV1(
    val id: String,
    val name: String,
    val description: String,
    val repository: String,
    val icon: String,
)

@Serializable
public data class PluginMarketplaceCatalogV1(
    val schemaVersion: Int,
    val plugins: List<PluginMarketplaceEntryV1>,
)

public data class PluginMarketplaceStateV1(
    val plugins: List<PluginMarketplaceEntryV1> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
) {
    public fun search(query: String): List<PluginMarketplaceEntryV1> {
        val term = query.trim()
        return if (term.isEmpty()) {
            plugins
        } else {
            plugins.filter {
                it.name.contains(term, ignoreCase = true) || it.description.contains(term, ignoreCase = true)
            }
        }
    }
}

@HiddenFromObjC
public class PluginMarketplace(
    private val appFiles: AppFileStore,
    engine: HttpClientEngine = proxyHttpClientEngine(ProxyRef.Global),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val client = HttpClient(engine) { followRedirects = false }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val iconMutex = Mutex()
    private val root = appFiles.directory("plugin-marketplace-v1")
    private val cachePath = root / "catalog.json"
    private var cached: CachedCatalog? = null
    private var cacheRead = false

    public suspend fun cachedCatalog(): PluginMarketplaceCatalogV1? = mutex.withLock { readCache()?.catalog }

    public suspend fun load(forceRefresh: Boolean = false): PluginMarketplaceCatalogV1 =
        mutex.withLock {
            val previous = readCache()
            if (!forceRefresh && previous != null && now() - previous.fetchedAt in 0 until 1.hours.inWholeMilliseconds) {
                return@withLock previous.catalog
            }
            val catalog = decode(request(CATALOG_URL, MAX_CATALOG_BYTES))
            val replacement = CachedCatalog(now(), catalog)
            write(cachePath, json.encodeToString(replacement).encodeToByteArray())
            cached = replacement
            catalog
        }

    public suspend fun icon(entry: PluginMarketplaceEntryV1): String? =
        try {
            requireValidIconUrl(entry.icon)
            iconMutex.withLock {
                val key =
                    entry.icon
                        .encodeUtf8()
                        .sha256()
                        .hex()
                val directory = root / "icons"
                val existing = ICON_EXTENSIONS.map { directory / "$key.$it" }.firstOrNull(appFiles.fileSystem::exists)
                if (existing != null) {
                    try {
                        val bytes = read(existing, FppLimits.MAX_ICON_BYTES)
                        pluginIconExtension(bytes)
                        validatePluginIconImage(bytes)
                        return@withLock existing.toString()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        appFiles.fileSystem.delete(existing, mustExist = false)
                    }
                }
                val bytes = request(entry.icon, FppLimits.MAX_ICON_BYTES)
                val extension = pluginIconExtension(bytes)
                validatePluginIconImage(bytes)
                val path = directory / "$key.$extension"
                write(path, bytes)
                path.toString()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    public fun close() {
        client.close()
    }

    private fun readCache(): CachedCatalog? {
        if (!cacheRead) {
            cacheRead = true
            cached =
                try {
                    json
                        .decodeFromString<CachedCatalog>(
                            read(cachePath, MAX_CATALOG_BYTES + 1024).decodeToString(throwOnInvalidSequence = true),
                        ).also { validate(it.catalog) }
                } catch (_: Exception) {
                    null
                }
        }
        return cached
    }

    private fun decode(bytes: ByteArray): PluginMarketplaceCatalogV1 =
        json.decodeFromString<PluginMarketplaceCatalogV1>(bytes.decodeToString(throwOnInvalidSequence = true)).also(::validate)

    private fun validate(catalog: PluginMarketplaceCatalogV1) {
        require(catalog.schemaVersion == 1) { "Unsupported plugin marketplace format" }
        require(catalog.plugins.size <= 500) { "Too many marketplace plugins" }
        require(
            catalog.plugins
                .map { it.id }
                .distinct()
                .size == catalog.plugins.size,
        ) { "Duplicate marketplace plugin ID" }
        catalog.plugins.forEach { entry ->
            require(entry.id.length <= 256 && PLUGIN_ID.matches(entry.id)) { "Invalid marketplace plugin ID" }
            require(entry.name.isNotBlank() && entry.name.length <= 128 && entry.name.none(Char::isISOControl)) { "Invalid plugin name" }
            require(entry.description.isNotBlank() && entry.description.length <= 4096) { "Invalid plugin description" }
            parseRepositoryUrl(entry.repository)
            requireValidIconUrl(entry.icon)
        }
    }

    private suspend fun request(
        url: String,
        maxBytes: Long,
    ): ByteArray =
        withTimeoutOrNull(20_000) {
            if (url != CATALOG_URL) requireValidIconUrl(url)
            client.prepareGet(url).execute { response ->
                val channel = response.bodyAsChannel()
                try {
                    require(response.status.value == 200) { "Plugin marketplace returned HTTP ${response.status.value}" }
                    require(
                        (response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) <= maxBytes,
                    ) { "Marketplace resource is too large" }
                    val buffer = channel.readBuffer(maxBytes + 1)
                    require(buffer.size <= maxBytes) { "Marketplace resource is too large" }
                    buffer.readByteArray()
                } finally {
                    channel.cancel()
                }
            }
        } ?: error("Plugin marketplace request timed out")

    private fun read(
        path: Path,
        maxBytes: Long,
    ): ByteArray {
        val size = requireNotNull(appFiles.fileSystem.metadata(path).size)
        require(size in 0..maxBytes) { "Marketplace cache is too large" }
        return appFiles.fileSystem.read(path) { readByteArray(size) }
    }

    private fun write(
        path: Path,
        bytes: ByteArray,
    ) {
        val fileSystem = appFiles.fileSystem
        fileSystem.createDirectories(requireNotNull(path.parent))
        val temporary = path.parent!! / "${path.name}.tmp"
        try {
            fileSystem.write(temporary) { write(bytes) }
            fileSystem.atomicMove(temporary, path)
        } finally {
            fileSystem.delete(temporary, mustExist = false)
        }
    }

    @Serializable
    private data class CachedCatalog(
        val fetchedAt: Long,
        val catalog: PluginMarketplaceCatalogV1,
    )

    private companion object {
        const val CATALOG_URL = "https://flareapp.moe/plugins/index.json"
        const val MAX_CATALOG_BYTES = 1024L * 1024
        val ICON_EXTENSIONS = listOf("png", "jpg", "gif", "webp", "ico")
        val PLUGIN_ID = Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+")

        fun requireValidIconUrl(value: String) {
            require(value.length <= 2048) { "Marketplace URL is too long" }
            val url = Url(value)
            require(
                url.protocol == URLProtocol.HTTPS && url.host == "raw.githubusercontent.com" && url.port == 443 &&
                    url.user == null && url.password == null && url.parameters.isEmpty() && url.fragment.isEmpty(),
            ) { "Use a raw.githubusercontent.com HTTPS URL without credentials" }
        }
    }
}
