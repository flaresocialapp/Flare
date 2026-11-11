package dev.dimension.flare.feature.plugin.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class RelationV1(
    val profileKey: EntityKeyV1,
    val following: Boolean = false,
    val followedBy: Boolean = false,
    val blocking: Boolean = false,
    val muting: Boolean = false,
    @Serializable(with = KnownActionTokensV1Serializer::class)
    val actionTokens: Map<SemanticActionV1, String> = emptyMap(),
    val blockedBy: Boolean = false,
    val hasPendingFollowRequestFromYou: Boolean = false,
    val hasPendingFollowRequestToYou: Boolean = false,
)

@Serializable
public data class SocialListV1(
    val id: String,
    val title: String,
    val memberCount: Int? = null,
    val entityToken: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val creator: ProfileV1? = null,
    val readOnly: Boolean? = null,
)

@Serializable
public data class DirectMessageRoomV1(
    val key: EntityKeyV1,
    val title: String,
    val participants: List<ProfileV1>,
    val lastMessage: DirectMessageV1? = null,
    val unreadCount: Int = 0,
    val entityToken: String? = null,
)

@Serializable
public data class DirectMessageV1(
    val key: EntityKeyV1,
    val roomKey: EntityKeyV1,
    val sender: ProfileV1,
    val createdAt: String,
    val content: RichTextV1,
    val fromCurrentAccount: Boolean = false,
    val entityToken: String? = null,
    val body: DirectMessageBodyV1? = null,
)

@Serializable
public sealed interface DirectMessageBodyV1 {
    @Serializable
    @SerialName("media")
    public data class Media(
        @Serializable(with = CompatibleMediaV1Serializer::class)
        val media: MediaV1,
    ) : DirectMessageBodyV1

    @Serializable
    @SerialName("post")
    public data class Post(
        val post: PostV1,
    ) : DirectMessageBodyV1

    @Serializable
    @SerialName("deleted")
    public data object Deleted : DirectMessageBodyV1
}

@Serializable
public data class ArticleV1(
    val key: EntityKeyV1,
    val title: String,
    val author: ProfileV1? = null,
    val createdAt: String? = null,
    val content: RichTextV1,
    val url: String? = null,
    val coverUrl: String? = null,
    val attribution: ArticleAttributionV1? = null,
    val blocks: List<ArticleBlockV1> = emptyList(),
)

@Serializable
public data class ArticleAttributionV1(
    val siteName: String? = null,
    val byline: String? = null,
    val iconUrl: String? = null,
)

@Serializable
public sealed interface ArticleBlockV1 {
    @Serializable
    @SerialName("text")
    public data class Text(
        val content: RichTextV1,
    ) : ArticleBlockV1

    @Serializable
    @SerialName("image")
    public data class Image(
        val media: MediaV1,
    ) : ArticleBlockV1

    @Serializable
    @SerialName("video")
    public data class Video(
        val media: MediaV1,
    ) : ArticleBlockV1

    @Serializable
    @SerialName("file")
    public data class File(
        val name: String,
        val url: String,
        val sizeBytes: Long? = null,
        val extension: String? = null,
        val customHeaders: Map<String, String> = emptyMap(),
    ) : ArticleBlockV1

    @Serializable
    @SerialName("embed")
    public data class Embed(
        val url: String,
        val title: String? = null,
        val description: String? = null,
        val imageUrl: String? = null,
    ) : ArticleBlockV1

    @Serializable
    @SerialName("contentGate")
    public data class ContentGate(
        val actionUrl: String? = null,
        val feeRequired: Int? = null,
    ) : ArticleBlockV1
}

@Serializable
public enum class GalleryOrientationV1 {
    @SerialName("horizontal")
    Horizontal,

    @SerialName("vertical")
    Vertical,
}

@Serializable
public data class GalleryV1(
    val key: EntityKeyV1,
    val title: String,
    val author: ProfileV1? = null,
    val createdAt: String,
    val content: RichTextV1? = null,
    val url: String,
    @Serializable(with = CompatibleMediaListV1Serializer::class)
    val images: List<MediaV1>,
    val orientation: GalleryOrientationV1 = GalleryOrientationV1.Vertical,
    val entityToken: String? = null,
    @Serializable(with = KnownActionsV1Serializer::class)
    val actions: List<ActionDescriptorV1> = emptyList(),
)

@Serializable
public data class TimelineDescriptorV1(
    val id: String,
    val title: WireTextV1,
    val icon: HostIconV1,
    val display: TimelineDisplayV1 = TimelineDisplayV1.List,
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable
public data class TimelineSectionV1(
    val id: String,
    val title: WireTextV1,
    val timelines: PageV1<TimelineDescriptorV1>,
)
