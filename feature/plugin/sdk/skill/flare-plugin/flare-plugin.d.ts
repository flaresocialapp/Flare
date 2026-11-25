/** Flare Plugin API v1/v2. Ambient editor types; do not import at runtime.
 * See ABI.md and references/ for validation, defaults, and operation dependencies.
 * Optional service members do not relax a declared capability's required method set.
 * Compatible Hosts ignore additive fields, unsupported capabilities/operations,
 * timeline/media/action variants and navigation targets. Known payloads stay validated.
 * These editor types list this SDK's supported authoring contract; see ABI.md.
 */
type JsonPrimitive = string | number | boolean | null;
type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };

/** Deeply frozen per-call Host metadata. Globals are disposable; social origin is normalized HTTPS.
 * Extension origin is the selected account's origin when its provider supplies one; ordinary HTTP permissions stay separate.
 * Plugin scope has empty platformId/origin when absent.
 */
interface PluginInvocationContextV1 {
  readonly pluginId: string;
  readonly platformId: string;
  readonly packageHash: string;
  readonly scope: "detector" | "login" | "account" | "guest" | "plugin" | "extension";
  readonly origin: string;
  /** Present for account and extension calls; extension handles are opaque and do not expose credentials. */
  readonly accountId?: string;
  /** Extension scope: target built-in or plugin-provided platform; accountId is a plugin-scoped opaque grant handle. */
  readonly targetPlatformId?: string | null;
  readonly locale: string;
  /** HTTP allowlist: httpOrigins expands login/account/guest; authOrigins expands login only. Detector uses its candidate origin only. */
  readonly approvedOrigins: readonly string[];
  readonly credentialAvailable: boolean;
  readonly assetHandles: readonly string[];
}

interface TranslationTokenV1 { id: number; kind: "Translatable" | "Locked"; text: string }
interface TranslationBlockV1 { id: number; tokens: TranslationTokenV1[] }
interface TranslationDocumentV1 { version?: number | null; targetLanguage?: string | null; blocks: TranslationBlockV1[] }
interface TranslationBatchPayloadV1 {
  content?: TranslationDocumentV1 | null;
  contentWarning?: TranslationDocumentV1 | null;
  title?: TranslationDocumentV1 | null;
  description?: TranslationDocumentV1 | null;
}
interface TranslationBatchItemV1 {
  entityKey: string;
  status?: "Completed" | "Skipped";
  payload?: TranslationBatchPayloadV1 | null;
  reason?: string | null;
}
interface TranslationBatchDocumentV1 { version?: number; targetLanguage?: string; items: TranslationBatchItemV1[] }
interface TranslationRequestV1 { sourceTemplate: string; sourceJson: string; targetLanguage: string; prompt: string }
interface BatchTranslationRequestV1 { sourceJson: string; sourceDocument: TranslationBatchDocumentV1; targetLanguage: string; prompt: string }

/** Register each provided type once, synchronously. Run Host API calls only inside these methods.
 * Manifest operation names, registration paths, and login negotiation must agree.
 * Await or return all work whose failure must fail the invocation. The Host drains
 * started Host tasks, but detached Promise rejections do not fail the returned chain.
 * Targets use JavaScriptCore (native Apple), AndroidX JavaScriptEngine (Android),
 * or QuickJS (Compose Desktop). No uniform heap/stack cap or busy-loop interruption.
 * @see references/capabilities.md
 */
interface TranslatePluginDefinitionV1 {
  translateDocumentJson(request: TranslationRequestV1, context: PluginInvocationContextV1): MaybePromise<string | null>;
  translateBatchDocumentJson(request: BatchTranslationRequestV1, context: PluginInvocationContextV1): MaybePromise<string | null>;
}

interface PlatformExtensionRequestV1 {
  actionId: string;
  businessDate: string;
  trigger: "manual" | "automatic";
}
interface PlatformExtensionResultV1 {
  status: "completed" | "already-completed" | "partial" | "failed" | "authentication-required" | "user-action-required" | "skipped";
  succeeded?: number;
  skipped?: number;
  failed?: number;
}
/** API v2 provider callback. Returned headers stay inside the Host. */
interface AccountHttpAuthenticationRequestV1 {
  method: string;
  url: string;
  headers: Record<string, string>;
  /** Exact encoded request bytes, including multipart boundaries. At most 2 MiB before base64 encoding. */
  bodyBase64?: string | null;
}
interface PlatformExtensionPluginDefinitionV1 {
  actions: Record<string, (request: PlatformExtensionRequestV1, context: PluginInvocationContextV1) => MaybePromise<PlatformExtensionResultV1>>;
}

type TimelineProcessTriggerV1 = "auto" | "manualRefresh";
interface TimelineProcessInputItemV1 {
  itemId: string;
  platformId: string;
  postKey: EntityKeyV1;
  createdAt: string;
  author?: { handle: string; displayName: string } | null;
  contentPlain: string;
  contentWarningPlain?: string | null;
  links: string[];
  card?: { url: string; title?: string | null; description?: string | null } | null;
  sourceLanguages: string[];
}
interface TimelineProcessBatchRequestV1 {
  locale: string;
  trigger: TimelineProcessTriggerV1;
  items: TimelineProcessInputItemV1[];
}
type WidgetCachePolicyV1 = "disk" | "memoryOnly" | "none";
interface TimelineProcessCacheOptionsV1 {
  policy?: WidgetCachePolicyV1;
  /** Seconds before expiration (clamped to 15..604800). Omit or null to cache until post or plugin state changes. */
  ttlSeconds?: number | null;
}
type WidgetToneV1 = "neutral" | "accent" | "positive" | "negative" | "warning";
type WidgetGapV1 = "xs" | "sm" | "md";
type WidgetAlignV1 = "start" | "center" | "end" | "spaceBetween";
type WidgetTextStyleV1 = "title" | "subtitle" | "body" | "caption" | "code";
type WidgetButtonStyleV1 = "secondary" | "primary";
type WidgetCornerStyleV1 = "rounded" | "circle";
type WidgetSparklineHeightV1 = "sm" | "md";
type WidgetActionV1 =
  | { type: "openUrl"; url: string }
  | { type: "copy"; text: string; label?: string | null }
  | { type: "refresh" };
interface WidgetBadgeV1 {
  text: string;
  tone?: WidgetToneV1;
}
interface WidgetButtonV1 {
  label: string;
  tone?: WidgetToneV1;
  style?: WidgetButtonStyleV1;
  action: WidgetActionV1;
}
interface WidgetMatchupSideV1 {
  name: string;
  subtitle?: string | null;
  /** HTTPS URL without credentials; requires permissions.mediaNetwork. */
  iconUrl?: string | null;
  score?: string | null;
}
interface WidgetBarSegmentV1 {
  label: string;
  valueText?: string | null;
  weight: number;
  tone?: WidgetToneV1;
}
interface WidgetKeyValueItemV1 {
  label: string;
  value: string;
  tone?: WidgetToneV1;
  action?: WidgetActionV1 | null;
}
type WidgetNodeV1 =
  | { type: "text"; text: string; style?: WidgetTextStyleV1; tone?: WidgetToneV1; maxLines?: number | null; copyable?: boolean }
  | { type: "row"; children: WidgetNodeV1[]; gap?: WidgetGapV1; align?: WidgetAlignV1; weight?: number | null }
  | { type: "column"; children: WidgetNodeV1[]; gap?: WidgetGapV1; align?: WidgetAlignV1; weight?: number | null }
  | { type: "stat"; label: string; value: string; delta?: string | null; deltaTone?: WidgetToneV1; subtext?: string | null; weight?: number | null }
  | { type: "matchup"; left: WidgetMatchupSideV1; right: WidgetMatchupSideV1; status?: string | null; statusTone?: WidgetToneV1 }
  | { type: "sparkline"; points: number[]; tone?: WidgetToneV1; minLabel?: string | null; maxLabel?: string | null; height?: WidgetSparklineHeightV1; weight?: number | null }
  | { type: "segmentedBar"; segments: WidgetBarSegmentV1[] }
  | { type: "keyValue"; items: WidgetKeyValueItemV1[] }
  | { type: "badgeRow"; badges: WidgetBadgeV1[] }
  | { type: "image"; url: string; alt?: string | null; aspectRatio?: number | null; cornerStyle?: WidgetCornerStyleV1; weight?: number | null }
  | { type: "collapsible"; id: string; summary: string; defaultExpanded?: boolean; children: WidgetNodeV1[] }
  | { type: "divider" };
interface PluginWidgetCardV1 {
  id: string;
  title?: string | null;
  badge?: WidgetBadgeV1 | null;
  accentTone?: WidgetToneV1;
  refreshable?: boolean;
  cardAction?: WidgetActionV1 | null;
  body: WidgetNodeV1[];
  actions?: WidgetButtonV1[];
}
interface TimelineProcessItemResultV1 {
  itemId: string;
  cards: PluginWidgetCardV1[];
  cache?: TimelineProcessCacheOptionsV1 | null;
}
interface TimelineProcessBatchResponseV1 {
  results: TimelineProcessItemResultV1[];
}
interface TimelineProcessorPluginDefinitionV1 {
  processBatch(request: TimelineProcessBatchRequestV1, context: PluginInvocationContextV1): MaybePromise<TimelineProcessBatchResponseV1>;
}

interface SocialPluginDefinitionV1 {
  detector?: { detect(request: DetectorRequestV1, context: PluginInvocationContextV1): MaybePromise<DetectorResultV1> };
  login?: Record<string, LoginServiceV1>;
  capabilities?: {
    /** Optional read-only renegotiation using saved credentials, without invoking login. */
    accountCapabilities?: { negotiate(request: AccountCapabilitiesRequestV1, context: PluginInvocationContextV1): MaybePromise<AccountCapabilitiesV1> };
    accountHttp?: { authenticate(request: AccountHttpAuthenticationRequestV1, context: PluginInvocationContextV1): MaybePromise<Record<string, string>> };
    /** API v2: account-only interactive WebView challenge recovery.
     * Strongly recommended to pair with accountCapabilities so existing accounts can adopt it after upgrade without re-login.
     */
    sessionChallenge?: { complete(request: SessionChallengeCompleteRequestV1, context: PluginInvocationContextV1): MaybePromise<SessionChallengeResultV1> };
    /** API v2: transient native lists, independent of home timelines and tabCatalog. */
    timelinePage?: { load(request: TimelinePageLoadRequestV2, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>> };
    timeline?: { page(request: TimelinePageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>> };
    search?: Partial<SearchServiceV1>;
    profile?: Partial<ProfileServiceV1>;
    post?: Partial<PostServiceV1>;
    relation?: Partial<RelationServiceV1>;
    compose?: { publish(request: ComposeRequestV1 | ComposeRequestV2, context: PluginInvocationContextV1): MaybePromise<ComposeResultV1> };
    /** Opt-in compose/v3 capability for friends and selfOnly. */
    composeV3?: { publish(request: ComposeRequestV3, context: PluginInvocationContextV1): MaybePromise<ComposeResultV1> };
    notification?: Partial<NotificationServiceV1>;
    list?: Partial<ListServiceV1>;
    directMessage?: Partial<DirectMessageServiceV1>;
    article?: Partial<ArticleServiceV1>;
    gallery?: Partial<GalleryServiceV1>;
    /** API v2: manga detail and chapter page loading. */
    manga?: Partial<MangaServiceV1>;
    tabCatalog?: { page(request: PageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineSectionV1>> };
  };
}

/** Social registration shape retained for existing SDK consumers. */
type PluginDefinitionV1 = SocialPluginDefinitionV1;

/** Key this service by the manifest login method ID.
 * OAuth: begin -> externalBrowser/success, resume -> success.
 * Password/CredentialImport/Form: begin -> success.
 * WebCookie: begin -> webCookie, check -> pending/success.
 * API v2 QrCode: begin -> qrCode, poll -> qrPending/qrExpired/success.
 * @see references/authentication.md
 */
interface LoginServiceV1 {
  begin(request: LoginBeginRequestV1, context: PluginInvocationContextV1): MaybePromise<LoginTransitionV1>;
  resume?(request: LoginResumeRequestV1, context: PluginInvocationContextV1): MaybePromise<LoginTransitionV1>;
  check?(request: CookieCheckRequestV1, context: PluginInvocationContextV1): MaybePromise<LoginTransitionV1>;
  poll?(request: QrPollRequestV2, context: PluginInvocationContextV1): MaybePromise<LoginTransitionV1>;
}

interface SearchServiceV1 {
  posts(request: SearchRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>>;
  profiles(request: SearchRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<ProfileV1>>;
  discoverPosts(request: SearchRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>>;
  discoverProfiles(request: SearchRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<ProfileV1>>;
  discoverHashtags(request: SearchRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<HashtagV1>>;
}

interface ProfileServiceV1 {
  byId(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<ProfileV1>;
  byHandle(request: HandleRequestV1, context: PluginInvocationContextV1): MaybePromise<ProfileV1>;
  timeline(request: ProfileTimelineRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>>;
  following(request: EntityPageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<ProfileV1>>;
  followers(request: EntityPageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<ProfileV1>>;
}

interface PostServiceV1 {
  detail(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<TimelinePostV1>;
  context(request: EntityPageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>>;
  /** API v2. Declare and negotiate contextTabs and contextPage together. */
  contextTabs?(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<PostContextTabsV2>;
  /** API v2. Return the tab rows. The current post and its parents come from detail. */
  contextPage?(request: PostContextPageRequestV2, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>>;
  delete(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<DeleteResultV1>;
  mutate(request: MutationRequestV1, context: PluginInvocationContextV1): MaybePromise<PostMutationResultV1>;
}

interface RelationServiceV1 {
  state(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<RelationV1>;
  mutate(request: MutationRequestV1, context: PluginInvocationContextV1): MaybePromise<RelationMutationResultV1>;
}
interface NotificationServiceV1 {
  page(request: NotificationPageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>>;
  badge(request: EmptyRequestV1, context: PluginInvocationContextV1): MaybePromise<CountResultV1>;
}
interface ListServiceV1 {
  page(request: PageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<SocialListV1>>;
  detail(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<SocialListV1>;
  create(request: ListMutationRequestV1, context: PluginInvocationContextV1): MaybePromise<SocialListV1>;
  update(request: ListMutationRequestV1, context: PluginInvocationContextV1): MaybePromise<SocialListV1>;
  delete(request: ListMutationRequestV1, context: PluginInvocationContextV1): MaybePromise<DeleteResultV1>;
  timeline(request: EntityPageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>>;
  members(request: EntityPageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<ProfileV1>>;
  memberships(request: EntityPageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<SocialListV1>>;
  addMember(request: ListMemberRequestV1, context: PluginInvocationContextV1): MaybePromise<ProfileV1>;
  removeMember(request: ListMemberRequestV1, context: PluginInvocationContextV1): MaybePromise<DeleteResultV1>;
}
interface DirectMessageServiceV1 {
  rooms(request: PageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<DirectMessageRoomV1>>;
  room(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<DirectMessageRoomV1>;
  messages(request: DirectMessagePageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<DirectMessageV1>>;
  send(request: DirectMessageSendRequestV1, context: PluginInvocationContextV1): MaybePromise<DirectMessageV1>;
  delete(request: DirectMessageDeleteRequestV1, context: PluginInvocationContextV1): MaybePromise<DeleteResultV1>;
  leave(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<DeleteResultV1>;
  create(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<DirectMessageRoomV1>;
  badge(request: EmptyRequestV1, context: PluginInvocationContextV1): MaybePromise<CountResultV1>;
  canSend(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<BooleanResultV1>;
}
interface ArticleServiceV1 {
  detail(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<ArticleV1>;
  comments(request: EntityPageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>>;
}
interface GalleryServiceV1 {
  ugoiraMetadata(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<UgoiraMetadataV1>;
  detail(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<GalleryV1>;
  comments(request: EntityPageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>>;
  recommendations(request: EntityPageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>>;
}
/** API v2. Required: detail and pages. Optional: pageImage (image or gif only), comments, recommendations.
 * Up to 3 manga.pageImage calls execute in parallel across isolated worker VMs (workerLane);
 * store cross-page or cross-chunk state (such as ptoken tables) in flare.storage rather than JS module globals.
 */
interface MangaServiceV1 {
  detail(request: EntityRequestV1, context: PluginInvocationContextV1): MaybePromise<MangaV1>;
  pages(request: MangaPagesRequestV1, context: PluginInvocationContextV1): MaybePromise<MangaPagesV1>;
  /** Executed across up to 3 parallel isolated worker VMs; use flare.storage for cross-page shared state, not module globals. */
  pageImage?(request: MangaPageImageRequestV1, context: PluginInvocationContextV1): MaybePromise<MangaPageMediaV1>;
  comments?(request: EntityPageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>>;
  recommendations?(request: EntityPageRequestV1, context: PluginInvocationContextV1): MaybePromise<PageV1<TimelineItemV1>>;
}

interface DetectorRequestV1 { origin: string }
type DetectorMatchV1 = "none" | "compatible" | "exact";
/** Return none unless the website matches verified software/API evidence. */
interface DetectorResultV1 {
  match: DetectorMatchV1;
  canonicalOrigin: string;
  software?: string;
  instance?: InstanceMetadataV1;
}
interface InstanceMetadataV1 {
  domain: string;
  title?: string;
  description?: string;
  iconUrl?: string;
  bannerUrl?: string;
  usersCount?: number;
  registrationEnabled?: boolean;
}

interface LoginBeginRequestV1 {
  methodId: string;
  origin: string;
  flowId: string;
  state?: string;
  redirectUri?: string;
  values: Record<string, string>;
}
interface LoginResumeRequestV1 {
  methodId: string;
  origin: string;
  flowId: string;
  redirectUri?: string;
  callbackParameters: Record<string, string>;
  /** Host-persisted OAuth data from begin; globals do not survive the flow. At most 64 KiB JSON. */
  pendingPayload?: JsonValue;
}
interface CookieCheckRequestV1 {
  methodId: string;
  origin: string;
  flowId: string;
  cookies: { cookies: CookieValueV1[] };
  /** API v2: latest Pending payload for this flow; survives runtime eviction, not app restart. */
  pendingPayload?: JsonValue;
  /** API v2: normalized WebView User-Agent (1..1024 non-control characters), when available. Omitted in API v1. */
  userAgent?: string;
}
/** API v2 account-only interactive WebView challenge completion request. */
interface SessionChallengeCompleteRequestV1 {
  challengeUrl: string;
  cookies: { cookies: CookieValueV1[] };
  /** Normalized WebView User-Agent (1..1024 non-control characters), when available. */
  userAgent?: string;
  /** Latest pending payload returned by a previous "pending" result in this challenge attempt. */
  pendingPayload?: JsonValue;
}
type SessionChallengeResultV1 =
  | { type: "pending"; /** Whole replacement, omitted/null clears; at most 64 KiB JSON. */ pendingPayload?: JsonValue }
  | { type: "completed" };
interface QrPollRequestV2 {
  methodId: string;
  origin: string;
  flowId: string;
  pendingPayload: JsonValue;
}
/** sourceUrl is the declared probe URL; do not merge Cookies across probes/origins blindly. */
interface CookieValueV1 {
  sourceUrl: string;
  name: string;
  value: string;
  /** API v2: only fields reliably available on this platform; duplicate names are preserved. */
  attributes?: CookieAttributesV2;
}
interface CookieAttributesV2 {
  domain?: string;
  hostOnly?: boolean;
  path?: string;
  /** Unix epoch milliseconds; absent does not imply a session cookie. */
  expiresAt?: number;
  session?: boolean;
  secure?: boolean;
  httpOnly?: boolean;
  sameSite?: "strict" | "lax" | "none";
}
type LoginTransitionV1 =
  | { type: "pending"; /** API v2: whole replacement, omitted/null clears; at most 64 KiB JSON. */ pendingPayload?: JsonValue }
  | { type: "externalBrowser"; url: string; pendingPayload: JsonValue }
  | { type: "webCookie"; startUrl: string }
  | { type: "qrCode"; content: string; expiresInMillis: number; pendingPayload: JsonValue; pollIntervalMillis?: number }
  | { type: "qrPending"; scanned: boolean; /** Omission or null retains the current payload. */ pendingPayload?: JsonValue }
  | { type: "qrExpired" }
  | { type: "success"; value: LoginSuccessV1 };
/** Complete current account capability/config snapshot, bounded by the manifest. */
interface AccountCapabilitiesV1 {
  capabilities: Record<string, string[]>;
  composeConfig?: ComposeConfigV1 | ComposeConfigV2;
  composeConfigV3?: ComposeConfigV3;
}
interface AccountCapabilitiesRequestV1 { previous: AccountCapabilitiesV1 }
/** Identity/profile must match the selected instance. Credential JSON is limited to 64 KiB. */
interface LoginSuccessV1 extends AccountCapabilitiesV1 {
  accountId: string;
  origin: string;
  credential: JsonValue;
  profile: ProfileV1;
}

/** Stable ID in the selected API namespace; host is an HTTPS authority without scheme/path. */
interface EntityKeyV1 { id: string; host: string }
/** format defaults to plain. HTML is sanitized; remote HTML images also need mediaNetwork. */
interface RichTextV1 { format?: "plain" | "html"; value: string }
interface ProfileFieldV1 { name: string; value: RichTextV1 }
interface ProfileV1 {
  key: EntityKeyV1;
  handle: string;
  displayName: string;
  avatarUrl?: string;
  bannerUrl?: string;
  description?: RichTextV1;
  url?: string;
  followersCount?: number;
  followingCount?: number;
  postsCount?: number;
  locked?: boolean;
  bot?: boolean;
  fields?: ProfileFieldV1[];
  entityToken?: string;
  actions?: ActionDescriptorV1[];
  /** Platform-reported badges; false clears a cached badge, omission leaves it unspecified. */
  verified?: boolean;
  cat?: boolean;
  /** Inline formatted text and emoji. Keep displayName as the plain fallback. */
  displayNameRichText?: RichTextV1;
  sourceLanguages?: string[];
}
type MediaTypeV1 = "image" | "video" | "gif" | "audio" | "ugoira";
/** HTTPS URLs only; automatic loading requires permissions.mediaNetwork. Preserve alt text. */
interface MediaV1 {
  id: string;
  type: MediaTypeV1;
  url: string;
  previewUrl?: string;
  description?: string;
  width?: number;
  height?: number;
  durationMillis?: number;
  downloadUrl?: string;
  /** Ugoira requires an authenticated account and gallery.ugoiraMetadata. */
  ugoiraKey?: EntityKeyV1;
  originalFrameUrl?: string;
  /** Explicit media headers, shared by the main URL, preview and download. See wire-data.md. */
  customHeaders?: Record<string, string>;
}
type VisibilityV1 = "public" | "unlisted" | "followers" | "direct" | "channel";
type ExtendedVisibilityV1 = "friends" | "selfOnly";
type SemanticActionV1 =
  | "Favourite" | "Unfavourite" | "Repost" | "Unrepost" | "Bookmark" | "Unbookmark"
  | "Delete" | "Reply" | "Quote" | "Follow" | "Unfollow" | "Block" | "Unblock" | "Mute" | "Unmute"
  | "Vote" | "React" | "Unreact" | "AcceptFollowRequest" | "RejectFollowRequest";
/** Semantic Host control. enabled defaults true; active/count remain meaningful when disabled.
 * actionToken is bounded opaque mutation data, not a page cursor or credential store.
 */
interface ActionDescriptorV1 {
  action: SemanticActionV1;
  /** Required for user buttons; localize using context.locale. */
  label?: string;
  enabled?: boolean;
  active?: boolean;
  count?: number;
  actionToken?: string;
}
/** Known authoring values; compatible older Hosts make unfamiliar string values inert. */
type PostDetailTypeV1 = "post" | "article" | "gallery" | "manga" | "link";
interface PostV1 {
  key: EntityKeyV1;
  /** Defaults to null. When present, the complete ProfileV1 contract still applies. */
  author?: ProfileV1 | null;
  createdAt: string;
  content: RichTextV1;
  /** Required for detailType "link"; absolute HTTPS URL without URL credentials. */
  url?: string;
  media?: MediaV1[];
  replyTo?: EntityKeyV1;
  spoilerText?: string;
  sensitive?: boolean;
  /** Defaults to null; omitted visibility is not inferred as public. */
  visibility?: VisibilityV1 | null;
  favouritesCount?: number;
  repostsCount?: number;
  repliesCount?: number;
  entityToken?: string;
  actions?: ActionDescriptorV1[];
  /** Omission defaults to "post"; "link" opens url. Unknown strings disable post/cover/grid navigation without a fallback. */
  detailType?: PostDetailTypeV1;
  /** API v2: overrides navigation only; quote posts use this same field.
   * Keep the target stable for the same key, including detail/mutation results.
   */
  target?: NavigationTargetV1;
  sourceLanguages?: string[];
  replyToHandle?: string;
  references?: PostReferenceV1[];
  sourceChannel?: { id: string; name: string };
  card?: CardV1;
  poll?: PollV1;
  emojiReactions?: EmojiReactionV1[];
  /** Plain text in the author header. Omission uses the author's handle. */
  authorSubtitle?: string;
  /** Defaults to detailType behavior; openMedia requires negotiated post.detail. */
  mediaClickPolicy?: "openMedia" | "openTarget";
  /** Omit visibility when supplying this field. Older Hosts display no visibility indicator. */
  extendedVisibility?: ExtendedVisibilityV1;
}
/** All timeline-producing operations use this discriminated union. No bare-post compatibility.
 * Gap/loading/translation state, account identity and callbacks are owned by the Host.
 */
type TimelineItemV1 =
  | ({ type: "post" } & TimelinePostV1)
  | { type: "feed"; feed: FeedV1 }
  | { type: "user"; user: TimelineUserV1 }
  | { type: "userList"; userList: TimelineUserListV1 }
  | { type: "message"; message: MessageV1 };
/** post.detail returns this same body without a type discriminator; do not include type. */
interface TimelinePostV1 { post: PostV1; presentation?: PostPresentationV1 }
interface PostPresentationV1 {
  message?: MessageV1;
  /** Nested parents are displayed as one ancestor-first chain, keeping each post once. */
  inlineParents?: TimelinePostV1[];
  quotes?: PostV1[];
  repost?: PostV1;
  /** Stable event identity; distinct notifications about one post must use different keys. */
  notificationKey?: EntityKeyV1;
}
type NavigationTargetV1 =
  | { type: "timelinePage"; pageId: string; title: WireTextV1; parameters?: Record<string, string> }
  | { type: "post" | "profile" | "article" | "gallery" | "manga"; key: EntityKeyV1 }
  | { type: "mangaChapter"; key: EntityKeyV1; chapterId: string }
  | { type: "link"; url: string };
interface FeedV1 {
  url: string;
  createdAt: string;
  source: { name: string; iconUrl?: string };
  title?: string;
  description?: RichTextV1;
  /** Image only. */
  media?: MediaV1;
  sourceLanguages?: string[];
  /** Defaults to opening url in the browser, never the RSS detail route. */
  target?: NavigationTargetV1;
}
interface TimelineUserV1 {
  /** Row identity, distinct from profile.key. */
  key: EntityKeyV1;
  createdAt: string;
  profile: ProfileV1;
  message?: MessageV1;
  /** Follow/Unfollow/Block/Unblock/Mute/Unmute/AcceptFollowRequest/RejectFollowRequest; labels required. */
  buttons?: ActionDescriptorV1[];
}
interface TimelineUserListV1 {
  key: EntityKeyV1;
  createdAt: string;
  users: ProfileV1[];
  message?: MessageV1;
  post?: PostV1;
}
/** Supply exactly one of text, kind, unknownType. */
interface MessageV1 {
  key: EntityKeyV1;
  createdAt: string;
  icon?: HostIconV1;
  actor?: ProfileV1;
  text?: string;
  kind?: MessageKindV1;
  unknownType?: string;
  args?: string[];
  target?: NavigationTargetV1;
}
type MessageKindV1 = "Mention" | "NewPost" | "Repost" | "Follow" | "FollowRequest" | "Favourite"
  | "PollEnded" | "PostUpdated" | "Reply" | "Quote" | "Reaction" | "FollowRequestAccepted"
  | "ScheduledNotePosted" | "ScheduledNotePostFailed" | "RoleAssigned" | "ChatRoomInvitationReceived"
  | "AchievementEarned" | "ExportCompleted" | "Test" | "Login" | "CreateToken" | "App" | "StarterpackJoined" | "Pinned" | "Like";
interface PostReferenceV1 { key: EntityKeyV1; type: "Reply" | "Quote" | "Repost" }
interface CardV1 { title: string; url: string; description?: string; media?: MediaV1 }
interface PollV1 {
  id: string;
  options: { title: string; votesCount?: number }[];
  multiple?: boolean;
  ownVotes?: number[];
  expiresAt?: string;
  enabled?: boolean;
  actionToken?: string;
}
interface EmojiReactionV1 {
  name: string;
  count?: number;
  /** Required for image reactions; mediaNetwork must be granted. */
  url?: string;
  isUnicode?: boolean;
  me?: boolean;
  enabled?: boolean;
  actionToken?: string;
}
interface UgoiraMetadataV1 { zipUrl: string; frames: { file: string; delayMillis: number }[] }

interface HashtagV1 { name: string; url?: string }

type PageDirectionV1 = "refresh" | "older" | "newer";
/** limit is 1..100. refresh starts without a continuation; older/newer use their own cursors. */
interface PageRequestV1 {
  direction: PageDirectionV1;
  limit: number;
  cursor?: string;
  parameters: Record<string, string>;
}
/** items defaults []; endReached defaults false and suppresses olderCursor only.
 * Empty pages may still advance cursors. At most 200 items; cursors at most 4096 characters.
 * @see references/wire-data.md
 */
interface PageV1<T> {
  items?: T[];
  olderCursor?: string;
  newerCursor?: string;
  endReached?: boolean;
}
interface TimelinePageRequestV1 { timelineId: string; page: PageRequestV1; parameters: Record<string, string> }
/** API v2: page.parameters is copied from the navigation target on every request. */
interface TimelinePageLoadRequestV2 { pageId: string; page: PageRequestV1 }
interface SearchRequestV1 { query: string; page: PageRequestV1 }
/** entityToken is opaque entity context (at most 8192 characters), not a paging cursor. */
interface EntityRequestV1 { key: EntityKeyV1; entityToken?: string }
interface EntityPageRequestV1 { key: EntityKeyV1; page: PageRequestV1; entityToken?: string }
interface PostContextTabsV2 {
  /** Ordered tabs for this post. An empty list renders the post and its parents alone. */
  tabs: PostContextTabV2[];
  /** ID of the declared tab that supplies replies for sharing when legacy context is unavailable. */
  replyTabId?: string;
}
interface PostContextTabV2 { id: string; title: WireTextV1; filter?: PostContextFilterV2 }
interface PostContextFilterV2 { options: PostContextFilterOptionV2[]; defaultOptionId: string }
interface PostContextFilterOptionV2 { id: string; title: WireTextV1 }
interface PostContextPageRequestV2 {
  key: EntityKeyV1;
  tabId: string;
  /** Absent when the tab has no filter group. */
  filterId?: string;
  page: PageRequestV1;
}
interface HandleRequestV1 { handle: string; host: string }
interface ProfileTimelineRequestV1 {
  profile: EntityKeyV1;
  tabId?: string;
  page: PageRequestV1;
  parameters: Record<string, string>;
}
interface MutationRequestV1 {
  key: EntityKeyV1;
  action: SemanticActionV1;
  actionToken?: string;
  parameters: Record<string, string>;
}
/** Each operation accepts only its narrowed result union below. Remote failure should throw.
 * Updated entities must match the requested key; invalidation keys must be unique.
 */
type MutationResultV1 =
  | { type: "updatedPost"; post: PostV1 }
  | { type: "updatedProfile"; profile: ProfileV1; relation?: RelationV1 }
  | { type: "updatedRelation"; relation: RelationV1 }
  | { type: "deleted" }
  | { type: "invalidate"; keys: EntityKeyV1[] }
  | { type: "noChange" };
type DeleteResultV1 = Extract<MutationResultV1, { type: "deleted" | "noChange" }>;
type PostMutationResultV1 = Extract<MutationResultV1, { type: "updatedPost" | "deleted" | "invalidate" | "noChange" }>;
/** User-card actions may return updatedProfile (including replacement buttons), deleted, or updatedRelation.
 * Relation controls outside user cards accept only updatedRelation/noChange.
 */
type RelationMutationResultV1 = Extract<MutationResultV1, { type: "updatedRelation" | "updatedProfile" | "deleted" | "noChange" }>;

/** Return full authoritative relation state after mutation, including replacement actionTokens.
 * Omitted booleans default false; partial updatedRelation can clear existing state.
 */
interface RelationV1 {
  profileKey: EntityKeyV1;
  following?: boolean;
  followedBy?: boolean;
  blocking?: boolean;
  muting?: boolean;
  blockedBy?: boolean;
  hasPendingFollowRequestFromYou?: boolean;
  hasPendingFollowRequestToYou?: boolean;
  actionTokens?: Partial<Record<SemanticActionV1, string>>;
}
interface SocialListV1 {
  id: string;
  title: string;
  memberCount?: number;
  entityToken?: string;
  description?: string;
  avatarUrl?: string;
  creator?: ProfileV1;
  /** Also enforce write restrictions in the plugin's list mutation handlers. */
  readOnly?: boolean;
}
interface DirectMessageRoomV1 {
  key: EntityKeyV1;
  title: string;
  participants: ProfileV1[];
  lastMessage?: DirectMessageV1;
  unreadCount?: number;
  entityToken?: string;
}
interface DirectMessageV1 {
  key: EntityKeyV1;
  roomKey: EntityKeyV1;
  sender: ProfileV1;
  createdAt: string;
  content: RichTextV1;
  fromCurrentAccount?: boolean;
  entityToken?: string;
  /** Omission shows content. Keep content as the text fallback for older Hosts. */
  body?: DirectMessageBodyV1;
}
type DirectMessageBodyV1 =
  | { type: "media"; media: MediaV1 }
  | { type: "post"; post: PostV1 }
  | { type: "deleted" };
interface ArticleV1 {
  key: EntityKeyV1;
  title: string;
  author?: ProfileV1;
  createdAt?: string;
  content: RichTextV1;
  url?: string;
  coverUrl?: string;
  /** Site and byline for articles without a ProfileV1 author. */
  attribution?: ArticleAttributionV1;
  /** Nonempty blocks take precedence on compatible Hosts. Keep content as a readable fallback. */
  blocks?: ArticleBlockV1[];
}
interface ArticleAttributionV1 { siteName?: string; byline?: string; iconUrl?: string }
type ArticleBlockV1 =
  | { type: "text"; content: RichTextV1 }
  | { type: "image"; media: MediaV1 & { type: "image" } }
  | { type: "video"; media: MediaV1 & { type: "video" } }
  | { type: "file"; name: string; url: string; sizeBytes?: number; extension?: string; customHeaders?: Record<string, string> }
  | { type: "embed"; url: string; title?: string; description?: string; imageUrl?: string }
  | { type: "contentGate"; actionUrl?: string; /** Subscription price in Japanese yen. */ feeRequired?: number };
interface GalleryV1 {
  key: EntityKeyV1;
  title: string;
  author?: ProfileV1;
  createdAt: string;
  content?: RichTextV1;
  url: string;
  images: MediaV1[];
  orientation?: "horizontal" | "vertical";
  entityToken?: string;
  actions?: ActionDescriptorV1[];
}
type MangaStatusV1 = "ongoing" | "completed" | "hiatus" | "cancelled";
type MangaReadingDirectionV1 = "rtl" | "ltr" | "webtoon";
interface MangaTagV1 { name: string; target?: NavigationTargetV1 }
interface MangaTagGroupV1 { id: string; title: WireTextV1; tags: MangaTagV1[] }
interface MangaChapterV1 {
  /** Unique across the entire MangaV1 (1..512 characters). */
  id: string;
  title: string;
  subtitle?: string;
  uploadedAt?: string;
  locked?: boolean;
  url?: string;
  entityToken?: string;
}
interface MangaChapterGroupV1 {
  id: string;
  title: WireTextV1;
  /** Ascending reading order: index 0 is the first chapter, index i + 1 is the next chapter. */
  chapters: MangaChapterV1[];
}
interface MangaV1 {
  key: EntityKeyV1;
  title: string;
  altTitles?: string[];
  /** Image media only; requires permissions.mediaNetwork. */
  cover?: MediaV1 & { type: "image" };
  author?: ProfileV1;
  authors?: string[];
  artists?: string[];
  status?: MangaStatusV1;
  content?: RichTextV1;
  /** Canonical HTTPS URL; required when any locked chapter omits chapter.url. */
  url?: string;
  updatedAt?: string;
  readingDirection?: MangaReadingDirectionV1;
  /** Mutually exclusive: supply either flat tags or grouped tagGroups. */
  tags?: MangaTagV1[];
  tagGroups?: MangaTagGroupV1[];
  /** Mutually exclusive: supply either ascending chapters or grouped chapterGroups. */
  chapters?: MangaChapterV1[];
  chapterGroups?: MangaChapterGroupV1[];
  entityToken?: string;
  actions?: ActionDescriptorV1[];
  sourceLanguages?: string[];
}
interface MangaPagesRequestV1 {
  key: EntityKeyV1;
  chapterId: string;
  entityToken?: string;
  chapterToken?: string;
  /** Optional range paging start index (>= 0); mutually exclusive with cursor. */
  offset?: number;
  /** Optional requested chunk size (1..2000). */
  limit?: number;
  /** Optional cursor for sequential chunk paging; mutually exclusive with offset. */
  cursor?: string;
}
type MangaPageMediaV1 = MediaV1 & { type: "image" | "gif" };
interface MangaPageV1 {
  id: string;
  /** Image or GIF media only; mutually exclusive with pageToken. */
  media?: MangaPageMediaV1;
  /** Allowed only when manga.pageImage is declared and negotiated. */
  pageToken?: string;
  previewUrl?: string;
  width?: number;
  height?: number;
  customHeaders?: Record<string, string>;
}
interface MangaPagesV1 {
  /** 1..2000 pages per chunk. */
  pages: MangaPageV1[];
  /** Starting page index of this chunk (>= 0). Initial response must be 0 when present; range chunks at offset > 0 must cover requested offset. */
  offset?: number;
  /** Total chapter page count (1..50000) for range mode; must stay constant across chunks and cannot be combined with cursor requests. */
  totalCount?: number;
  /** Required when endReached is false (1..4096 non-blank characters). */
  nextCursor?: string;
  /** Defaults to true when omitted. */
  endReached?: boolean;
}
type MangaPageImageReasonV1 = "initial" | "resolveRetry" | "imageLoadFailed";
interface MangaPageImageRequestV1 {
  key: EntityKeyV1;
  chapterId: string;
  pageId: string;
  pageToken: string;
  entityToken?: string;
  chapterToken?: string;
  /** Retry attempt index (0..100). */
  attempt?: number;
  reason?: MangaPageImageReasonV1;
  /** Failing HTTPS image URL; provided only when reason is "imageLoadFailed". */
  failedUrl?: string;
}
type HostIconV1 =
  | "Home" | "Notification" | "Search" | "Profile" | "Local" | "World" | "Featured"
  | "Bookmark" | "Heart" | "List" | "Messages" | "Channel" | "Like" | "Repost"
  | "Reply" | "Delete" | "Follow" | "Block" | "Mute" | "Info";
interface TimelineDescriptorV1 {
  id: string;
  title: WireTextV1;
  icon: HostIconV1;
  display?: "List" | "Grid";
  parameters?: Record<string, string>;
}
interface TimelineSectionV1 { id: string; title: WireTextV1; timelines: PageV1<TimelineDescriptorV1> }
type EmptyRequestV1 = Record<string, never>;
interface NotificationPageRequestV1 { filterId?: string; page: PageRequestV1 }
interface ListMutationRequestV1 { id?: string; title?: string; entityToken?: string }
interface ListMemberRequestV1 { listId: string; profileKey: EntityKeyV1; entityToken?: string }
interface DirectMessageSendRequestV1 { roomKey: EntityKeyV1; text: string; roomEntityToken?: string }
/** Poll with page.newerCursor from the previous result, not roomEntityToken. */
interface DirectMessagePageRequestV1 { roomKey: EntityKeyV1; page: PageRequestV1; roomEntityToken?: string }
interface DirectMessageDeleteRequestV1 {
  roomKey: EntityKeyV1;
  messageKey: EntityKeyV1;
  roomEntityToken?: string;
  messageEntityToken?: string;
}
interface CountResultV1 { value: number }
interface BooleanResultV1 { value: boolean }

/** compose/v2 defaults require supportedTypes. Login intersects modes and shared limits;
 * an optional section present on only one side is retained. compose/v1 remains supported for new posts and replies.
 * @see references/manifest.md#compose-defaults
 */
type ComposeTypeV2 = "new" | "reply" | "quote";
interface ComposeConfigV2 {
  supportedTypes: ComposeTypeV2[];
  text?: { maxLength: number };
  media?: {
    minCountForNew?: number;
    maxCount: number;
    maxBytes: number;
    supportedMimeTypes?: string[];
    altTextMaxLength?: number;
    canSensitive?: boolean;
  };
  visibility?: { allowed: VisibilityV1[]; default?: VisibilityV1 };
  contentWarning?: boolean;
  poll?: { maxOptions: number };
  language?: { maxCount: number };
}
/** Legacy config has implicit new/reply modes; no quote support. */
interface ComposeConfigV1 extends Omit<ComposeConfigV2, "supportedTypes"> { supportedTypes?: never }
type ComposeVisibilityV3 = VisibilityV1 | ExtendedVisibilityV1;
interface ComposeConfigV3 extends Omit<ComposeConfigV2, "visibility"> {
  /** Required in platform.composeDefaultsV3. */
  visibility?: { allowed: ComposeVisibilityV3[]; default?: ComposeVisibilityV3 };
}
/** Host-owned per-invocation upload handle. Forward via multipart; bytes never enter JS. */
interface ComposeAssetV1 { handle: string; fileName?: string; mimeType?: string; description?: string }
interface ComposePollV1 { options: string[]; expiresInSeconds: number; multiple?: boolean }
interface ComposeRequestV2 {
  text: string;
  visibility: VisibilityV1;
  languages: string[];
  assets: ComposeAssetV1[];
  sensitive: boolean;
  spoilerText?: string;
  reference?: { type: "reply" | "quote"; key: EntityKeyV1 };
  replyTo?: never;
  poll?: ComposePollV1;
}
/** Selected by the manifest compose/v1 capability, independently of runtime apiVersion. */
interface ComposeRequestV1 extends Omit<ComposeRequestV2, "reference" | "replyTo"> { replyTo?: EntityKeyV1; reference?: never }
interface ComposeRequestV3 extends Omit<ComposeRequestV2, "visibility"> { visibility: ComposeVisibilityV3 }
interface ComposeResultV1 { post: PostV1 }

type HttpAuthorizationV1 =
  | { type: "bearer"; token: string }
  | { type: "basic"; username: string; password: string };
type HttpBodyV1 =
  | { type: "json"; value: JsonValue }
  | { type: "text"; value: string; contentType?: string }
  | { type: "form"; values: Record<string, string> }
  | { type: "multipart"; parts: HttpMultipartPartV1[] };
type HttpMultipartPartV1 =
  | { type: "text"; name: string; value: string; contentType?: string }
  | { type: "asset"; name: string; handle: string; fileName?: string; contentType?: string };
/** Absolute approved HTTPS URL. method defaults GET; GET/HEAD cannot have a body.
 * Use structured authorization/cookies; raw transport/auth headers are restricted.
 * timeoutMillis may not exceed the current invocation deadline.
 */
interface HttpRequestV1 {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE" | "HEAD";
  url: string;
  headers?: Record<string, string>;
  authorization?: HttpAuthorizationV1;
  cookies?: { name: string; value: string }[];
  body?: HttpBodyV1;
  timeoutMillis?: number;
  /** API v2 only; manual returns the current hop, follow (default) permits same-origin GET/HEAD redirects. */
  redirect?: "manual" | "follow";
}
/** body is text; parse JSON explicitly after checking status. Headers contain string arrays.
 * HTTP failure statuses are returned normally; this is not a browser Response.
 */
interface HttpResponseV1 {
  status: number;
  /** API v2 includes source application headers, including normalized Set-Cookie values. */
  headers: Record<string, string[]>;
  body: string;
  /** Always present for API v2: actual canonical response URL. */
  url?: string;
  /** API v2: one valid, authorized HTTPS Location resolved against url, with fragment removed. */
  redirectUrl?: string;
}
interface CredentialSnapshotV2 { value: JsonValue; revision: string }

type HtmlExtractModeV1 = "text" | "ownText" | "html" | "outerHtml" | "attr" | "exists";
/** Exactly one of extract or fields is required. Top-level nodes and all: true require selector.
 * name (1..128 non-blank chars) and absoluteUrl are allowed only when extract is "attr";
 * absoluteUrl: true requires HTTPS baseUrl (non-HTTP(S) or blank resolved values return null).
 * all: true forbids extract: "exists".
 */
interface HtmlNodeSpecV1 {
  /** CSS selector (1..512 non-blank chars). Required on top-level nodes and when all is true. */
  selector?: string;
  all?: boolean;
  /** Maximum matches when all is true (1..2000; defaults to 500). */
  limit?: number;
  extract?: HtmlExtractModeV1;
  /** Attribute name (1..128 non-blank chars); allowed and required only when extract is "attr". */
  name?: string;
  /** Resolve relative URL against baseUrl; allowed only when extract is "attr". */
  absoluteUrl?: boolean;
  /** 1..64 fields per level; max nesting depth 4, max 128 spec nodes total. */
  fields?: Record<string, HtmlNodeSpecV1>;
}
interface HtmlExtractRequestV1 {
  /** HTML input (max 4 MiB UTF-8; 8 MiB max bridge request envelope). */
  html: string;
  /** Optional HTTPS base URL; required when any spec node sets absoluteUrl: true. */
  baseUrl?: string;
  /** 1..64 top-level fields; each must provide selector. */
  spec: Record<string, HtmlNodeSpecV1>;
}
interface HtmlQueryRequestV1 {
  /** HTML input (max 4 MiB UTF-8; 8 MiB max bridge request envelope). */
  html: string;
  /** Optional HTTPS base URL; required when any field node sets absoluteUrl: true. */
  baseUrl?: string;
  /** CSS selector (1..512 non-blank chars) selecting items to extract. */
  selector: string;
  /** Maximum matched items (1..2000; defaults to 500). */
  limit?: number;
  /** 1..64 fields per level; max nesting depth 3 inside fields (depth 4 total), max 128 spec nodes total. */
  fields: Record<string, HtmlNodeSpecV1>;
}

type PluginErrorCodeV1 =
  | "AuthenticationRequired" | "ChallengeRequired" | "NotFound" | "Validation" | "RateLimited" | "Network"
  | "Remote" | "Unsupported" | "InvalidResponse" | "Cancelled";
interface PluginChallengeV1 {
  /** Approved HTTPS challenge URL on $accountOrigin or permissions.authOrigins (no whitespace, control chars, or fragment). */
  url: string;
  /** Optional seed cookies (max 32 entries, 64 KiB aggregate UTF-8 name+value bytes). */
  seedCookies?: { name: string; value: string }[];
}
/** Exactly one of value/key; localized key requires fallback.
 * args accepts finite numbers, booleans, or strings; omit null arguments.
 * Manifest text instead uses a string or { key, fallback }.
 */
interface WireTextV1 { value?: string; key?: string; fallback?: string; args?: Record<string, JsonPrimitive> }
/** Throw flare.error(value) for recoverable failures; retryAfterSeconds is 0..604800.
 * challenge is required when code is "ChallengeRequired" and forbidden for all other codes.
 */
interface PluginErrorV1 {
  code: PluginErrorCodeV1;
  message: WireTextV1;
  retryAfterSeconds?: number;
  remoteCode?: string;
  challenge?: PluginChallengeV1;
}

interface PluginStorageSnapshotV1 { values: Record<string, JsonValue>; revision: string }
interface PluginStorageV1 {
  read(keys?: string[]): Promise<PluginStorageSnapshotV1>;
  write(changes: { set?: Record<string, JsonValue>; remove?: string[] }, options?: { expectedRevision?: string }): Promise<{ revision: string }>;
}
interface FlareHostV1 {
  readonly storage: { open(namespace: string): PluginStorageV1 };
  readonly http: { request(request: HttpRequestV1): Promise<HttpResponseV1> };
  /** API v2, authorized extension scope only. Host injects authentication for the approved target origins. */
  readonly account: { readonly http: { request(request: Omit<HttpRequestV1, "authorization" | "cookies">): Promise<HttpResponseV1> } };
  /** Available only in account scope. replace atomically replaces the whole JSON payload. */
  readonly credential: {
    read(): Promise<JsonValue>;
    /** API v2: revision is opaque; it changes after every successful replacement. */
    read(options: { withRevision: true }): Promise<CredentialSnapshotV2>;
    /** API v2: false means a local revision conflict; reread, do not replay a remote refresh. */
    replace(value: JsonValue, options?: { expectedRevision: string }): Promise<boolean>;
  };
  readonly crypto: {
    /** size counts random bytes (1..1024), producing twice as many hex characters. */
    randomHex(size: number): Promise<string>;
    uuid(): Promise<string>;
    /** SHA-256 of UTF-8 input (at most 1 MiB); returns hex, not base64url/bytes. */
    sha256(value: string): Promise<string>;
    /** MD5 of UTF-8 input (at most 1 MiB). Returns lowercase hex for legacy protocol compatibility. */
    md5(value: string): Promise<string>;
  };
  /** API v2 across all scopes: native HTML extraction, list query, and entity decoding (4 MiB HTML / 4 MiB output / 20000 visited nodes).
   * Invalid requests, selectors, or exceeded limits reject with host.invalid-request (catchable with try/catch without retiring the runtime).
   */
  readonly html: {
    extract(request: HtmlExtractRequestV1): Promise<Record<string, JsonValue>>;
    query(request: HtmlQueryRequestV1): Promise<Record<string, JsonValue>[]>;
    /** Decodes HTML entities in a UTF-8 string up to 1 MiB. */
    decodeEntities(value: string): Promise<string>;
  };
  readonly locale: { current(): Promise<string> };
  error(value: PluginErrorV1): Error;
}

type MaybePromise<T> = T | Promise<T>;

/** Register this social plugin once when its classic script is evaluated. */
declare function defineFlareSocialPlugin(plugin: SocialPluginDefinitionV1): void;
declare const flare: FlareHostV1;

/** Register this text-translation provider once; may coexist with a social registration. */
declare function defineFlareTranslatePlugin(plugin: TranslatePluginDefinitionV1): void;

/** Register actions for existing built-in or plugin-provided accounts once; may coexist with other plugin definitions. */
declare function defineFlarePlatformExtensionPlugin(plugin: PlatformExtensionPluginDefinitionV1): void;

/** Register timeline post processing once; runs in plugin scope and may coexist with other plugin definitions. */
declare function defineFlareTimelineProcessorPlugin(plugin: TimelineProcessorPluginDefinitionV1): void;
