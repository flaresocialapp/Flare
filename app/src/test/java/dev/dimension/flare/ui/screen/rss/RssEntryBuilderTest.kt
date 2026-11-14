package dev.dimension.flare.ui.screen.rss

import dev.dimension.flare.data.database.app.model.SubscriptionType
import dev.dimension.flare.data.repository.SubscriptionSourceInput
import dev.dimension.flare.data.repository.toUiRssSource
import dev.dimension.flare.ui.route.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class RssEntryBuilderTest {
    @Test
    fun opensMastodonSubscriptionsWithTheirSavedType() {
        val types =
            listOf(
                SubscriptionType.MASTODON_LOCAL,
                SubscriptionType.MASTODON_PUBLIC,
                SubscriptionType.MASTODON_TRENDS,
            )
        for (type in types) {
            val source =
                SubscriptionSourceInput(
                    id = 1,
                    url = "merveilles.town",
                    title = null,
                    icon = null,
                    type = type,
                ).toUiRssSource()

            val tab = Route.Rss.Timeline(source).toTimelineTabItem()

            assertEquals("rss.subscription:${type.name}:merveilles.town", tab.id)
        }
    }

    @Test
    fun opensRssSubscriptionsAsFeeds() {
        val source =
            SubscriptionSourceInput(
                id = 1,
                url = "https://example.com/feed.xml",
                title = null,
                icon = null,
            ).toUiRssSource()

        val tab = Route.Rss.Timeline(source).toTimelineTabItem()

        assertEquals("rss.feed:https://example.com/feed.xml", tab.id)
    }
}
