package dev.dimension.flare.feature.plugin.manifest

import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginExtensionManifestTest {
    private val manifest =
        PluginJsonV1.decodeFromString<PluginManifestV1>(
            """{
          "apiVersion":2,"id":"org.example.extension","version":"1.0.0","name":"Actions",
          "platformExtension":{
            "targets":[{"platformId":"VVo","authOrigins":["https://m.weibo.cn"]}],
            "actions":[{"id":"checkIn","title":"Check in","dailyTimeZone":"Asia/Shanghai"}]
          }
        }""",
        )

    @Test
    fun requiresV2AndMatchingActionRegistration() {
        assertTrue(manifest.validate(PluginMethodTableV1(2, setOf("platformExtension.actions.checkIn"))).isValid)
        assertFalse(manifest.copy(apiVersion = 1).validate().isValid)
        assertFalse(manifest.validate(PluginMethodTableV1(2, emptySet())).isValid)
    }

    @Test
    fun rejectsUnsafeMethodPathsInvalidTimezonesAndUnboundedDeclarations() {
        val extension = requireNotNull(manifest.platformExtension)
        val action = extension.actions.single()
        for (id in listOf("check.in", "check-in", "__proto__", "constructor")) {
            assertFalse(manifest.copy(platformExtension = extension.copy(actions = listOf(action.copy(id = id)))).validate().isValid)
        }
        assertFalse(
            manifest
                .copy(
                    platformExtension = extension.copy(actions = listOf(action.copy(dailyTimeZone = "invalid/timezone"))),
                ).validate()
                .isValid,
        )
        assertFalse(manifest.copy(platformExtension = extension.copy(actions = listOf(action, action))).validate().isValid)
        assertFalse(manifest.copy(platformExtension = extension.copy(targets = emptyList())).validate().isValid)
        assertFalse(
            manifest
                .copy(
                    platformExtension =
                        extension.copy(
                            targets = listOf(extension.targets.single().copy(authOrigins = setOf("https://*.weibo.cn"))),
                        ),
                ).validate()
                .isValid,
        )
    }
}
