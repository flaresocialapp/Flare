package dev.dimension.flare.ui.screen.rss

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.SquareRss
import dev.dimension.flare.data.model.IconType
import dev.dimension.flare.data.model.tab.UiTimelineTabItem
import dev.dimension.flare.data.model.tab.toUiTimelineTabItem
import dev.dimension.flare.data.platform.RssTimelineSpecs
import dev.dimension.flare.ui.component.BottomSheetSceneStrategy
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.FlareScaffold
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.route.Route
import dev.dimension.flare.ui.screen.home.TimelineScreen

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
internal fun EntryProviderScope<NavKey>.rssEntryBuilder(
    navigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    entry<Route.Rss.Sources>(
        metadata =
            ListDetailSceneStrategy.listPane(
                sceneKey = "Rss",
                detailPlaceholder = {
                    RssPlaceholder()
                },
            ),
    ) {
        RssSourcesScreen(
            onAdd = {
                navigate(Route.Rss.Create)
            },
            onEdit = {
                navigate(Route.Rss.Edit(it))
            },
            onClicked = {
                navigate(Route.Rss.Timeline(it))
            },
            onBack = onBack,
        )
    }
    entry<Route.Rss.Timeline>(
        metadata =
            ListDetailSceneStrategy.listPane(
                sceneKey = "Rss",
                detailPlaceholder = {
                    RssPlaceholder()
                },
            ),
    ) { args ->
        TimelineScreen(
            tabItem = remember(args) { args.toTimelineTabItem() },
            onBack = onBack,
        )
    }
    entry<Route.Rss.Detail>(
        metadata =
            ListDetailSceneStrategy.detailPane(
                sceneKey = "Rss",
            ),
    ) { args ->
        RssDetailScreen(
            url = args.url,
            descriptionHtml = args.descriptionHtml,
            fallbackHtml = args.fallbackHtml,
            descriptionTitle = args.title,
            onBack = onBack,
        )
    }
    entry<Route.Rss.Create>(
        metadata = BottomSheetSceneStrategy.bottomSheet(),
    ) {
        RssSourceEditSheet(
            onDismissRequest = onBack,
            id = null,
            onImportOPML = {
                navigate(Route.Rss.OPMLImport(it))
            },
        )
    }
    entry<Route.Rss.Edit>(
        metadata = BottomSheetSceneStrategy.bottomSheet(),
    ) { args ->
        RssSourceEditSheet(
            onDismissRequest = onBack,
            id = args.id,
            onImportOPML = {
                navigate(Route.Rss.OPMLImport(it))
            },
        )
    }
    entry<Route.Rss.OPMLImport>(
        metadata = BottomSheetSceneStrategy.bottomSheet(),
    ) { args ->
        OPMLImportSheet(
            uri = args.url.toUri(),
            onBack = onBack,
        )
    }
}

internal fun Route.Rss.Timeline.toTimelineTabItem(): UiTimelineTabItem =
    RssTimelineSpecs
        .candidate(
            url = url,
            type = type,
            title = UiText.Raw(title ?: url),
            icon = favIcon?.let { IconType.Url(it) } ?: IconType.Material(UiIcon.Rss),
        ).toUiTimelineTabItem()

@Composable
internal fun RssPlaceholder(modifier: Modifier = Modifier) {
    FlareScaffold(
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(it),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.CenterVertically),
        ) {
            FAIcon(
                FontAwesomeIcons.Solid.SquareRss,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}
