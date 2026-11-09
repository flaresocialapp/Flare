package dev.dimension.flare.data.datasource.microblog.datasource

import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.coroutines.flow.StateFlow
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public interface TimelinePageDataSource {
    public val pluginId: String
    public val packageHash: String
    public val revision: StateFlow<Long>

    public fun timelinePage(
        pageId: String,
        parameters: Map<String, String>,
    ): RemoteLoader<UiTimelineV2>
}
