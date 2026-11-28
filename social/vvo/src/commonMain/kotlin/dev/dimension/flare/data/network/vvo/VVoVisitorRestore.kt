package dev.dimension.flare.data.network.vvo

import dev.dimension.flare.common.JSON
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal data class VisitorRestorePayload(
    val retcode: Long,
    val alt: String?,
)

internal fun parseVisitorRestoreJsonp(body: String): VisitorRestorePayload? {
    val callbackIdx = body.indexOf("restore_back(")
    if (callbackIdx < 0) return null
    val openBrace = body.indexOf('{', startIndex = callbackIdx)
    if (openBrace < 0) return null
    val closeBrace = findMatchingJsonBrace(body, openBrace)
    if (closeBrace <= openBrace) return null

    val jsonObject =
        runCatching {
            JSON.parseToJsonElement(body.substring(openBrace, closeBrace + 1)) as? JsonObject
        }.getOrNull() ?: return null

    val retcode = (jsonObject["retcode"] as? JsonPrimitive)?.longOrNull ?: return null
    val dataObj = jsonObject["data"] as? JsonObject
    val alt =
        (dataObj?.get("alt") as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    return VisitorRestorePayload(
        retcode = retcode,
        alt = alt,
    )
}

private fun findMatchingJsonBrace(
    text: String,
    openBraceIndex: Int,
): Int {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in openBraceIndex until text.length) {
        val ch = text[i]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return -1
}

internal fun extractTrustedSsoLocations(
    body: String,
    baseUrl: String,
): List<String> {
    val root =
        runCatching {
            JSON.parseToJsonElement(body) as? JsonObject
        }.getOrNull() ?: return emptyList()

    val data = (root["data"] as? JsonObject) ?: root
    val rawCandidates = mutableListOf<String>()
    for (key in listOf("location", "url", "crossdomain", "arrURL")) {
        collectUrlStrings(data[key], rawCandidates)
    }

    return rawCandidates
        .mapNotNull { candidate ->
            resolveRefreshRedirectUrl(current = baseUrl, location = candidate)
        }.distinct()
}

private fun collectUrlStrings(
    element: JsonElement?,
    sink: MutableList<String>,
) {
    when (element) {
        is JsonPrimitive -> {
            element.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(sink::add)
        }

        is JsonArray -> {
            element.forEach { child -> collectUrlStrings(child, sink) }
        }

        else -> Unit
    }
}
