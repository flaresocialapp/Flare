package dev.dimension.flare.feature.plugin.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

internal fun testGitHubTrees(
    archive: ByteArray,
    commitSha: String,
): Map<String, String> {
    val files = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) files[entry.name.substringAfter('/')] = zip.readBytes()
        }
    }
    val trees = mutableMapOf<String, String>()

    fun directory(prefix: String): String {
        val entries =
            files.keys
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }
                .distinct()
                .sorted()
                .map { name ->
                    val bytes = files[prefix + name]
                    buildJsonObject {
                        put("path", name)
                        put("type", if (bytes == null) "tree" else "blob")
                        put("mode", if (bytes == null) "040000" else "100644")
                        put("sha", if (bytes == null) directory("$prefix$name/") else gitBlobHash(bytes))
                        if (bytes != null) put("size", bytes.size)
                    }
                }
        val response =
            buildJsonObject {
                put("tree", JsonArray(entries))
                put("truncated", false)
            }.toString()
        val sha = response.encodeUtf8().sha1().hex()
        trees[sha] = response
        return sha
    }
    val root = directory("")
    trees[commitSha] = trees.getValue(root)
    return trees
}
