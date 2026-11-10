package dev.dimension.flare.feature.plugin.adapter

import dev.dimension.flare.data.io.AppFileStore
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.installer.TestFppFactory
import dev.dimension.flare.feature.plugin.lifecycle.RunningPluginV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingStoreV1
import dev.dimension.flare.feature.plugin.login.PluginOAuthPendingV1
import dev.dimension.flare.feature.plugin.management.PluginSettingsFacadeV1
import dev.dimension.flare.feature.plugin.runtime.PluginCallException
import dev.dimension.flare.feature.plugin.storage.PluginStorageChangesV1
import dev.dimension.flare.feature.plugin.wire.PluginErrorCodeV1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.SYSTEM
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PluginTranslationIntegrationTest {
    private val files = FileSystem.SYSTEM
    private val root = Files.createTempDirectory("translation-plugin").toOkioPath()
    private val subsystem =
        PluginSubsystemV1(
            object : AppFileStore {
                override val fileSystem = files

                override fun directory(name: String) = root / name
            },
            object : PluginOAuthPendingStoreV1 {
                override suspend fun save(pending: PluginOAuthPendingV1) = Unit

                override suspend fun load(flowId: String): PluginOAuthPendingV1? = null

                override suspend fun consume(pending: PluginOAuthPendingV1) = false
            },
        )

    @AfterTest
    fun cleanup() =
        runBlocking {
            subsystem.runtimePool.close()
            files.deleteRecursively(root)
        }

    @Test
    fun installsTranslationOnlyAndRunsSingleAndBatchThroughTheRealRuntime() =
        runBlocking {
            val plugin = installExample()
            assertEquals(null, plugin.installed.manifest.platform)
            val service = PluginTranslationServiceV1(subsystem)
            val storage = subsystem.storage.access(ID) {}
            val oldKey =
                service.providers.value
                    .single()
                    .cacheKey
            storage.write("state", PluginStorageChangesV1(mapOf("token" to JsonPrimitive("ignored"))))
            assertEquals(
                oldKey,
                service.providers.value
                    .single()
                    .cacheKey,
            )
            storage.write("options", PluginStorageChangesV1(mapOf("prefix" to JsonPrimitive("translated: "))))
            assertNotEquals(
                oldKey,
                service.providers.value
                    .single()
                    .cacheKey,
            )
            assertFailsWith<CancellationException> { service.withCurrent(ID, oldKey) { error("Must not commit") } }
            val result = PluginJsonV1.parseToJsonElement(checkNotNull(service.translate(ID, "Hello", DOCUMENT, "zh-Hans", "", false)))
            val tokens =
                result.jsonObject
                    .getValue("blocks")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("tokens")
                    .jsonArray
            assertEquals(
                "translated: Hello",
                tokens[0]
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "@locked",
                tokens[1]
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content,
            )
            val batch =
                """
                {
                  "version": 1,
                  "targetLanguage": "zh-Hans",
                  "items": [
                    {
                      "entityKey": "one",
                      "status": "Completed",
                      "payload": {
                        "content": $DOCUMENT
                      }
                    }
                  ]
                }
                """.trimIndent()
            val translatedBatch = checkNotNull(service.translate(ID, null, batch, "zh-Hans", "", true))
            assertTrue(translatedBatch.contains("translated: Hello"))
            assertTrue(translatedBatch.contains("\"entityKey\":\"one\""))
        }

    @Test
    fun pageStorageIsSharedAndTheBridgeRejectsOtherOperationsAndOrigins(): Unit =
        runBlocking {
            installExample()
            val page = PluginSettingsFacadeV1(subsystem, ID)
            try {
                assertEquals("text/html", page.resource("settings/index.html").mimeType)
                assertFailsWith<IllegalArgumentException> { page.resource("settings/../manifest.json") }
                assertFailsWith<IllegalStateException> {
                    page.call(
                        """
                        {
                          "id": "0",
                          "operation": "storage.read",
                          "arguments": {
                            "namespace": "options"
                          }
                        }
                        """.trimIndent(),
                    )
                }
                page.call(
                    """
                    {
                      "id": "1",
                      "operation": "bridge.hello",
                      "arguments": {
                        "version": 1
                      }
                    }
                    """.trimIndent(),
                )
                val write =
                    PluginJsonV1
                        .parseToJsonElement(
                            page.call(
                                """
                                {
                                  "id": "2",
                                  "operation": "storage.write",
                                  "arguments": {
                                    "namespace": "options",
                                    "changes": {
                                      "set": {
                                        "prefix": "page: "
                                      }
                                    }
                                  }
                                }
                                """.trimIndent(),
                            ),
                        ).jsonObject
                assertTrue(write.getValue("ok").jsonPrimitive.boolean)
                assertEquals(
                    JsonPrimitive("page: "),
                    subsystem.storage
                        .access(ID) {}
                        .read("options")
                        .values["prefix"],
                )
                assertFailsWith<IllegalArgumentException> {
                    page.call(
                        """
                        {
                          "id": "3",
                          "operation": "credential.read"
                        }
                        """.trimIndent(),
                    )
                }
                val denied =
                    PluginJsonV1
                        .parseToJsonElement(
                            page.call(
                                """
                                {
                                  "id": "4",
                                  "operation": "http.request",
                                  "arguments": {
                                    "url": "https://unapproved.example/"
                                  }
                                }
                                """.trimIndent(),
                            ),
                        ).jsonObject
                assertFalse(denied.getValue("ok").jsonPrimitive.boolean)
                subsystem.replaceRunning(ID, null)
                assertFailsWith<IllegalStateException> { page.resource("settings/index.html") }
                assertFailsWith<IllegalStateException> {
                    page.call(
                        """
                        {
                          "id": "5",
                          "operation": "storage.read",
                          "arguments": {
                            "namespace": "options"
                          }
                        }
                        """.trimIndent(),
                    )
                }
            } finally {
                page.close()
            }
        }

    @Test
    fun expectedStorageConflictsDoNotPauseThePlugin() =
        runBlocking<Unit> {
            val original = installExample()
            val script =
                """
                defineFlareTranslatePlugin({
                  async translateDocumentJson() {
                    await flare.storage.open("options").write({set:{value:1}}, {expectedRevision:"stale"});
                    return null;
                  },
                  async translateBatchDocumentJson() { return null; }
                });
                """.trimIndent()
            subsystem.replaceRunning(ID, original.copy(developmentSource = script))
            val service = PluginTranslationServiceV1(subsystem)
            repeat(3) {
                val error = assertFailsWith<PluginCallException> { service.translate(ID, "Hello", DOCUMENT, "zh-Hans", "", false) }
                assertEquals(PluginErrorCodeV1.Validation, error.error.code)
                assertEquals("storage.conflict", error.error.remoteCode)
            }
            assertTrue(
                subsystem.runtimePool.issues.value
                    .isEmpty(),
            )
        }

    @Test
    fun installsSettingsOnlyWithoutAJavaScriptEntrypoint() =
        runBlocking {
            val path = root / "settings-only.fpp"
            TestFppFactory.write(
                path,
                listOf(
                    "manifest.json" to
                        """
                        {
                          "apiVersion": 2,
                          "id": "dev.example.settings",
                          "version": "1.0.0",
                          "name": "Settings",
                          "settings": {
                            "entry": "settings/index.html"
                          }
                        }
                        """.trimIndent().encodeToByteArray(),
                    "assets/icon.png" to TestFppFactory.validPng,
                    "settings/index.html" to "<!doctype html><title>Settings</title>".encodeToByteArray(),
                ),
            )
            val preview = subsystem.installer.inspect(path)
            subsystem.installer.commit(preview, true, subsystem.stateStore::install)
            assertEquals(
                null,
                subsystem.stateStore.desired.value.plugins
                    .getValue("dev.example.settings")
                    .manifest.platform,
            )
        }

    private suspend fun installExample(): RunningPluginV1 {
        val example =
            sequenceOf(
                Paths.get("sdk/skill/flare-plugin/examples/translation-settings"),
                Paths.get("feature/plugin/sdk/skill/flare-plugin/examples/translation-settings"),
            ).first {
                Files.isDirectory(it)
            }
        val entries =
            Files.walk(example).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString() != "README.md" }
                    .map {
                        example.relativize(it).toString().replace('\\', '/') to
                            Files.readAllBytes(it)
                    }.toList()
            }
        val archive = root / "example.fpp"
        TestFppFactory.write(archive, entries)
        val installed = subsystem.installer.commit(subsystem.installer.inspect(archive), true, subsystem.stateStore::install)
        return RunningPluginV1(
            installed,
            subsystem.stateStore.paths
                .packagePath(installed.packageHash)
                .toString(),
            "",
        ).also {
            subsystem.replaceRunning(ID, it)
        }
    }

    private companion object {
        const val ID = "dev.example.translation"
        val DOCUMENT =
            """
            {
              "version": 1,
              "blocks": [
                {
                  "id": 0,
                  "tokens": [
                    {
                      "id": 0,
                      "kind": "Translatable",
                      "text": "Hello"
                    },
                    {
                      "id": 1,
                      "kind": "Locked",
                      "text": "@locked"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
    }
}
