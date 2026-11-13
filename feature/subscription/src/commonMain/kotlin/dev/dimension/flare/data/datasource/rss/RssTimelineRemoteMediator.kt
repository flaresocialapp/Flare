package dev.dimension.flare.data.datasource.rss

import dev.dimension.flare.data.database.app.model.RssDisplayMode
import dev.dimension.flare.data.database.app.model.SubscriptionType
import dev.dimension.flare.data.datasource.microblog.paging.CacheableRemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.network.rss.RssService
import dev.dimension.flare.data.network.rss.model.Feed
import dev.dimension.flare.data.network.rss.toRssWebUrl
import dev.dimension.flare.data.repository.SubscriptionRepository
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.mapper.render
import dev.dimension.flare.ui.model.mapper.title

internal class RssTimelineRemoteMediator(
    private val url: String,
    private val subscriptionRepository: SubscriptionRepository,
    private val fetchFeed: suspend (String) -> Feed = RssService::fetch,
    private val fetchIcon: suspend (String) -> String? = RssService::fetchIcon,
) : CacheableRemoteLoader<UiTimelineV2> {
    override val pagingKey: String
        get() =
            buildString {
                append("rss_")
                append(url)
            }

    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> {
        val rssSource = subscriptionRepository.findByUrlAndType(url, SubscriptionType.RSS).firstOrNull()
        // Keep the stored URL for subscription lookup and paging, including legacy scheme-less URLs.
        val requestUrl = url.toRssWebUrl()
        val response = fetchFeed(requestUrl)
        val title = rssSource?.title ?: response.title
        val icon = rssSource?.favIcon ?: fetchIcon(requestUrl)
        val displayMode = rssSource?.displayMode ?: RssDisplayMode.FULL_CONTENT
        val content =
            when (response) {
                is Feed.Atom -> {
                    response.entries.map {
                        it.render(
                            sourceName = title,
                            sourceIcon = icon,
                            displayMode = displayMode,
                            sourceLanguage = null,
                        )
                    }
                }

                is Feed.RDF -> {
                    response.items.map {
                        it.render(
                            sourceName = title,
                            sourceIcon = icon,
                            displayMode = displayMode,
                            sourceLanguage = null,
                        )
                    }
                }

                is Feed.Rss20 -> {
                    response.channel.items.map {
                        it.render(
                            sourceName = title,
                            sourceIcon = icon,
                            displayMode = displayMode,
                            sourceLanguage = response.channel.language,
                        )
                    }
                }
            }

        return PagingResult(
            endOfPaginationReached = true,
            data = content,
        )
    }
}
