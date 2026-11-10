package dev.dimension.flare.feature.plugin.storage

import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.host.PluginHostException
import dev.dimension.flare.feature.plugin.host.platformUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import kotlin.native.HiddenFromObjC

@Serializable
public data class PluginStorageSnapshotV1(
    val values: Map<String, JsonElement>,
    val revision: String,
)

@Serializable
public data class PluginStorageChangesV1(
    val set: Map<String, JsonElement> = emptyMap(),
    val remove: Set<String> = emptySet(),
)

@Serializable
private data class StoredPluginData(
    val schemaVersion: Int = 1,
    val epoch: String,
    val namespaces: Map<String, PluginStorageSnapshotV1> = emptyMap(),
)

/** Namespace and key names are opaque strings, never filesystem paths. */
@HiddenFromObjC
public class PluginStorageV1(
    private val fileSystem: FileSystem,
    private val root: Path,
) {
    private val mutex = Mutex()
    private val records = MutableStateFlow<Map<String, Result<StoredPluginData>>>(emptyMap())
    private val mutableChanges = MutableStateFlow(0L)
    public val changes: StateFlow<Long> = mutableChanges.asStateFlow()

    internal fun prepare(pluginId: String) {
        if (pluginId in records.value) return
        val loaded =
            runCatching {
                val path = path(pluginId)
                if (!fileSystem.exists(path)) {
                    StoredPluginData(epoch = platformUuid()).also { persist(pluginId, it) }
                } else {
                    val text =
                        fileSystem.read(path) {
                            val buffer = Buffer()
                            while (buffer.size <= MAX_PLUGIN_BYTES) {
                                if (read(buffer, MAX_PLUGIN_BYTES + 1L - buffer.size) == -1L) break
                            }
                            val bytes = buffer.readByteArray()
                            require(bytes.size <= MAX_PLUGIN_BYTES) { "Plugin storage exceeds its size limit" }
                            bytes.decodeToString(throwOnInvalidSequence = true)
                        }
                    PluginJsonV1.decodeFromString<StoredPluginData>(text).also {
                        require(it.schemaVersion == 1 && it.epoch.isNotBlank()) { "Invalid plugin storage" }
                        validate(it)
                    }
                }
            }
        records.update { if (pluginId in it) it else it + (pluginId to loaded) }
    }

    internal fun access(
        pluginId: String,
        ensureActive: () -> Unit,
    ): PluginStorageAccessV1 = PluginStorageAccessV1(this, pluginId, ensureActive)

    internal fun token(
        pluginId: String,
        namespaces: List<String>?,
    ): String {
        val record = record(pluginId)
        val names = namespaces ?: record.namespaces.keys.sorted()
        return buildString {
            append(record.epoch)
            names.sorted().forEach { name ->
                append('|').append(name.encodeUtf8().sha256().hex()).append(':')
                append(record.namespaces[name]?.revision ?: "empty")
            }
        }.encodeUtf8().sha256().hex()
    }

    internal suspend fun <T> ifCurrent(
        pluginId: String,
        namespaces: List<String>?,
        expected: String,
        block: suspend () -> T,
    ): T =
        mutex.withLock {
            if (token(pluginId, namespaces) != expected) throw CancellationException("Plugin storage changed")
            block()
        }

    internal suspend fun remove(pluginId: String) {
        mutex.withLock {
            withContext(NonCancellable) {
                fileSystem.delete(path(pluginId), mustExist = false)
                fileSystem.delete(root / (path(pluginId).name + ".tmp"), mustExist = false)
                records.update { it - pluginId }
                mutableChanges.update { it + 1 }
            }
        }
    }

    internal suspend fun read(
        pluginId: String,
        namespace: String,
        keys: Set<String>?,
        ensureActive: () -> Unit,
    ): PluginStorageSnapshotV1 =
        mutex.withLock {
            ensureActive()
            validateName(namespace)
            keys?.forEach(::validateName)
            val record = record(pluginId)
            val snapshot = record.namespaces[namespace] ?: PluginStorageSnapshotV1(emptyMap(), record.epoch + ":empty")
            snapshot.copy(values = snapshot.values.filterKeys { keys == null || it in keys })
        }

    internal suspend fun write(
        pluginId: String,
        namespace: String,
        changes: PluginStorageChangesV1,
        expectedRevision: String?,
        ensureActive: () -> Unit,
    ): PluginStorageSnapshotV1 =
        mutex.withLock {
            ensureActive()
            validateName(namespace)
            (changes.set.keys + changes.remove).forEach(::validateName)
            if (changes.set.keys.any { it in changes.remove }) fail("invalid", "A key cannot be set and removed in one write")
            changes.set.values.forEach(::validateValue)
            val before = record(pluginId)
            val previous = before.namespaces[namespace] ?: PluginStorageSnapshotV1(emptyMap(), before.epoch + ":empty")
            if (expectedRevision != null && expectedRevision != previous.revision) fail("conflict", "Plugin storage revision conflict")
            val values = (previous.values - changes.remove + changes.set).toMap()
            if (values == previous.values) return@withLock previous
            val snapshot = PluginStorageSnapshotV1(values, platformUuid())
            val updated = before.copy(namespaces = before.namespaces + (namespace to snapshot))
            validate(updated)
            ensureActive()
            withContext(NonCancellable) {
                try {
                    persist(pluginId, updated)
                } catch (error: Exception) {
                    throw PluginHostException("storage.io", "Could not persist plugin storage", error)
                }
                records.update { it + (pluginId to Result.success(updated)) }
                mutableChanges.update { it + 1 }
            }
            snapshot
        }

    private fun record(pluginId: String): StoredPluginData =
        records.value[pluginId]?.getOrElse { throw PluginHostException("storage.corrupt", "Plugin storage is unreadable", it) }
            ?: throw PluginHostException("storage.unavailable", "Plugin storage is unavailable")

    private fun path(pluginId: String): Path = root / (pluginId.encodeUtf8().sha256().hex() + ".json")

    private fun persist(
        pluginId: String,
        record: StoredPluginData,
    ) {
        fileSystem.createDirectories(root)
        val destination = path(pluginId)
        val temporary = root / (destination.name + ".tmp")
        try {
            fileSystem.write(temporary) { writeUtf8(PluginJsonV1.encodeToString(record)) }
            fileSystem.atomicMove(temporary, destination)
        } finally {
            runCatching { fileSystem.delete(temporary, mustExist = false) }
        }
    }

    private fun validate(record: StoredPluginData) {
        if (record.namespaces.size > 64 ||
            record.namespaces.values.sumOf { it.values.size } > 4_096
        ) {
            fail("quota", "Too many storage entries")
        }
        record.namespaces.forEach { (name, snapshot) ->
            validateName(name)
            require(snapshot.revision.isNotBlank() && snapshot.revision.length <= 128)
            snapshot.values.forEach { (key, value) ->
                validateName(key)
                validateValue(value)
            }
        }
        if (utf8Size(PluginJsonV1.encodeToString(record)) > MAX_PLUGIN_BYTES) fail("quota", "Plugin storage quota exceeded")
    }

    private fun validateValue(value: JsonElement) {
        val pending = ArrayDeque<Pair<JsonElement, Int>>()
        pending.add(value to 0)
        while (pending.isNotEmpty()) {
            val (item, depth) = pending.removeLast()
            if (depth > 64) fail("invalid", "Storage value is too deeply nested")
            when (item) {
                is JsonObject -> {
                    item.values.forEach { pending.add(it to depth + 1) }
                }

                is JsonArray -> {
                    item.forEach { pending.add(it to depth + 1) }
                }

                is JsonPrimitive -> {
                    if (item != JsonNull && !item.isString && item.booleanOrNull == null &&
                        item.doubleOrNull?.isFinite() != true
                    ) {
                        fail("invalid", "Storage numbers must be finite")
                    }
                }
            }
        }
        if (utf8Size(value.toString()) > 1_024 * 1_024) fail("quota", "Storage value exceeds 1 MiB")
    }

    private fun validateName(value: String) {
        if (value.isEmpty() || utf8Size(value) > 256 || value.any { it.code < 32 }) fail("invalid", "Invalid storage name")
    }

    private fun utf8Size(value: String): Int =
        try {
            value.encodeToByteArray(throwOnInvalidSequence = true).size
        } catch (_: CharacterCodingException) {
            fail("invalid", "Storage strings must be valid Unicode")
        }

    private fun fail(
        code: String,
        message: String,
    ): Nothing = throw PluginHostException("storage.$code", message)

    private companion object {
        const val MAX_PLUGIN_BYTES = 4 * 1_024 * 1_024
    }
}

@HiddenFromObjC
public class PluginStorageAccessV1 internal constructor(
    private val storage: PluginStorageV1,
    private val pluginId: String,
    private val ensureActive: () -> Unit,
) {
    public suspend fun read(
        namespace: String,
        keys: Set<String>? = null,
    ): PluginStorageSnapshotV1 = storage.read(pluginId, namespace, keys, ensureActive)

    public suspend fun write(
        namespace: String,
        changes: PluginStorageChangesV1,
        expectedRevision: String? = null,
    ): PluginStorageSnapshotV1 = storage.write(pluginId, namespace, changes, expectedRevision, ensureActive)
}
