package dev.dimension.flare.data.datasource.microblog.datasource

import kotlin.native.HiddenFromObjC

/** Native-only authentication; callers must validate the URL and keep these headers out of plugin JavaScript. */
@HiddenFromObjC
public interface PlatformExtensionDataSource {
    public val extensionAuthOrigins: Set<String>

    public suspend fun extensionAuthHeaders(url: String): Map<String, String>
}
