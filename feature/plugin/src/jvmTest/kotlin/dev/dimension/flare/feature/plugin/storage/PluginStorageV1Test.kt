package dev.dimension.flare.feature.plugin.storage

import dev.dimension.flare.feature.plugin.host.PluginHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.SYSTEM
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PluginStorageV1Test {
    private val files = FileSystem.SYSTEM
    private val root = Files.createTempDirectory("plugin-storage").toOkioPath()
    private val storage =
        PluginStorageV1(files, root).also {
            it.prepare("dev.test.one")
            it.prepare("dev.test.two")
        }
    private val access = storage.access("dev.test.one") {}

    @AfterTest
    fun cleanUp() {
        files.deleteRecursively(root)
    }

    @Test
    fun persistsJsonAndDistinguishesMissingFromNull() =
        runTest {
            val empty = access.read("arbitrary/namespace")
            val saved =
                access.write(
                    "arbitrary/namespace",
                    PluginStorageChangesV1(mapOf("null" to JsonNull, "value" to JsonPrimitive("secret 🌟"))),
                    empty.revision,
                )
            val reopened = PluginStorageV1(files, root).also { it.prepare("dev.test.one") }.access("dev.test.one") {}
            assertEquals(saved, reopened.read("arbitrary/namespace"))
            assertEquals(mapOf("null" to JsonNull), reopened.read("arbitrary/namespace", setOf("null", "absent")).values)
            assertTrue(
                storage
                    .access("dev.test.two") {}
                    .read("arbitrary/namespace")
                    .values
                    .isEmpty(),
            )
        }

    @Test
    fun malformedUnicodeIsRejectedWithoutChangingStoredData() =
        runTest {
            val original = access.write("config", PluginStorageChangesV1(mapOf("value" to JsonPrimitive("original"))))
            listOf(
                "\uD800" to mapOf("value" to JsonPrimitive(1)),
                "config" to mapOf("\uDC00" to JsonPrimitive(1)),
                "config" to mapOf("value" to JsonPrimitive("\uD800")),
                "config" to mapOf("value" to JsonObject(mapOf("\uD800" to JsonNull))),
            ).forEach { (namespace, values) ->
                assertEquals(
                    "storage.invalid",
                    assertFailsWith<PluginHostException> { access.write(namespace, PluginStorageChangesV1(values)) }.code,
                )
            }
            assertEquals(original, access.read("config"))
            val reopened = PluginStorageV1(files, root).also { it.prepare("dev.test.one") }.access("dev.test.one") {}
            assertEquals(original, reopened.read("config"))
        }

    @Test
    fun failedAtomicMoveKeepsMemoryAndDiskAtThePreviousRevision() =
        runTest {
            val original = access.write("config", PluginStorageChangesV1(mapOf("value" to JsonPrimitive("original"))))
            val failingFiles =
                object : ForwardingFileSystem(files) {
                    override fun atomicMove(
                        source: Path,
                        target: Path,
                    ) = throw IOException("Simulated disk failure")
                }
            val failing = PluginStorageV1(failingFiles, root).also { it.prepare("dev.test.one") }.access("dev.test.one") {}
            assertEquals(
                "storage.io",
                assertFailsWith<PluginHostException> {
                    failing.write("config", PluginStorageChangesV1(mapOf("value" to JsonPrimitive("changed"))))
                }.code,
            )
            assertEquals(original, failing.read("config"))
            val reopened = PluginStorageV1(files, root).also { it.prepare("dev.test.one") }.access("dev.test.one") {}
            assertEquals(original, reopened.read("config"))
        }

    @Test
    fun temporaryFileCleanupCannotInvalidateACommittedWrite() =
        runTest {
            val cleanupFailure =
                object : ForwardingFileSystem(files) {
                    override fun delete(
                        path: Path,
                        mustExist: Boolean,
                    ) {
                        if (path.name.endsWith(".tmp")) throw IOException("Simulated cleanup failure")
                        super.delete(path, mustExist)
                    }
                }
            val writer = PluginStorageV1(cleanupFailure, root).also { it.prepare("dev.test.one") }.access("dev.test.one") {}
            val saved = writer.write("config", PluginStorageChangesV1(mapOf("value" to JsonPrimitive("saved"))))
            val reopened = PluginStorageV1(files, root).also { it.prepare("dev.test.one") }.access("dev.test.one") {}
            assertEquals(saved, writer.read("config"))
            assertEquals(saved, reopened.read("config"))
        }

    @Test
    fun writesAreAtomicConditionalAndNoOpsKeepTheirRevision() =
        runTest {
            val before = access.read("config")
            val saved =
                access.write(
                    "config",
                    PluginStorageChangesV1(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2))),
                    before.revision,
                )
            assertNotEquals(before.revision, saved.revision)
            assertEquals(
                saved,
                access.write("config", PluginStorageChangesV1(mapOf("a" to JsonPrimitive(1)), setOf("missing")), saved.revision),
            )
            assertEquals(
                "storage.conflict",
                assertFailsWith<PluginHostException> {
                    access.write("config", PluginStorageChangesV1(mapOf("a" to JsonPrimitive(3)), setOf("b")), before.revision)
                }.code,
            )
            assertEquals(saved, access.read("config"))
            assertEquals(
                "storage.invalid",
                assertFailsWith<PluginHostException> {
                    access.write("config", PluginStorageChangesV1(mapOf("a" to JsonPrimitive(3)), setOf("a")))
                }.code,
            )
            assertEquals(saved, access.read("config"))
        }

    @Test
    fun cacheTokensTrackOnlyDeclaredDependenciesAndRejectStaleCommits() =
        runTest {
            val config = storage.token("dev.test.one", listOf("config"))
            val all = storage.token("dev.test.one", null)
            val none = storage.token("dev.test.one", emptyList())
            access.write("state", PluginStorageChangesV1(mapOf("token" to JsonPrimitive("rotated"))))
            assertEquals(config, storage.token("dev.test.one", listOf("config")))
            assertNotEquals(all, storage.token("dev.test.one", null))
            assertEquals(none, storage.token("dev.test.one", emptyList()))
            access.write("config", PluginStorageChangesV1(mapOf("model" to JsonPrimitive("new"))))
            var wrote = false
            assertFailsWith<CancellationException> { storage.ifCurrent("dev.test.one", listOf("config"), config) { wrote = true } }
            assertTrue(!wrote)
        }

    @Test
    fun retiredAccessCannotReadOrWriteAndReinstallGetsFreshRevisions() =
        runTest {
            var active = true
            val guarded = storage.access("dev.test.one") { check(active) }
            val old = guarded.read("config").revision
            active = false
            assertFailsWith<IllegalStateException> { guarded.read("config") }
            assertFailsWith<IllegalStateException> { guarded.write("config", PluginStorageChangesV1(mapOf("a" to JsonPrimitive(1)))) }
            storage.remove("dev.test.one")
            storage.prepare("dev.test.one")
            assertNotEquals(old, access.read("config").revision)
        }

    @Test
    fun quotaFailureAndCorruptionPreserveTheOriginalFile() =
        runTest {
            val saved = access.write("config", PluginStorageChangesV1(mapOf("a" to JsonPrimitive(1))))
            assertEquals(
                "storage.quota",
                assertFailsWith<PluginHostException> {
                    access.write("config", PluginStorageChangesV1(mapOf("huge" to JsonPrimitive("x".repeat(1_024 * 1_024)))))
                }.code,
            )
            assertEquals(saved, access.read("config"))
            val path = root / ("dev.test.one".encodeUtf8().sha256().hex() + ".json")
            files.write(path) { writeUtf8("broken") }
            val broken = PluginStorageV1(files, root).also { it.prepare("dev.test.one") }.access("dev.test.one") {}
            assertEquals("storage.corrupt", assertFailsWith<PluginHostException> { broken.read("config") }.code)
            assertFailsWith<PluginHostException> { broken.write("config", PluginStorageChangesV1(mapOf("a" to JsonPrimitive(2)))) }
            assertEquals("broken", files.read(path) { readUtf8() })
        }
}
