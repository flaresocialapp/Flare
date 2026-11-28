import Foundation
@preconcurrency import KotlinSharedUI
import WebKit

public enum AppleSilentPollDecision: Equatable, Sendable {
    case pending(interactiveLoginEnteredAtMs: Int64?)
    case completed
    case interactiveLoginRequired
}

private struct SeedDedupKey: Hashable {
    let name: String
    let domain: String
    let hostOnly: Bool
    let path: String
}

public func silentSeedToHTTPCookie(_ seed: SilentSeedCookie) -> HTTPCookie? {
    let name = seed.name.trimmingCharacters(in: .whitespacesAndNewlines)
    let domain = seed.domain
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .trimmingCharacters(in: CharacterSet(charactersIn: "."))
        .lowercased()
    let trimmedPath = seed.path.trimmingCharacters(in: .whitespacesAndNewlines)
    let path = trimmedPath.isEmpty ? "/" : trimmedPath

    guard !name.isEmpty,
          !domain.isEmpty,
          path.hasPrefix("/"),
          !name.containsInvalidCookieNameScalar,
          !seed.value.containsInvalidCookieValueScalar,
          !domain.containsInvalidCookieDomainScalar,
          !path.containsInvalidCookieValueScalar,
          let originURL = URL(string: "https://\(domain)/"),
          originURL.host?.lowercased() == domain
    else {
        return nil
    }

    var properties: [HTTPCookiePropertyKey: Any] = [
        .name: name,
        .value: seed.value,
        .path: path,
    ]
    if seed.hostOnly {
        properties[.originURL] = originURL
    } else {
        properties[.domain] = ".\(domain)"
    }
    if seed.secure {
        properties[.secure] = "TRUE"
    }
    if seed.httpOnly {
        properties[HTTPCookiePropertyKey("HttpOnly")] = "TRUE"
    }
    if let expiresMs = seed.expiresAtEpochMillis?.int64Value {
        properties[.expires] = Date(timeIntervalSince1970: TimeInterval(expiresMs) / 1_000.0)
    }
    return HTTPCookie(properties: properties)
}

public func httpCookiesToSilentSeedCookies(
    _ cookies: [HTTPCookie],
    probeUrls: [String]
) -> [SilentSeedCookie] {
    let probeHosts: [String] = probeUrls.compactMap { raw in
        guard let url = URL(string: raw),
              url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(),
              !host.isEmpty
        else {
            return nil
        }
        return host
    }
    guard !probeHosts.isEmpty else { return [] }

    var seen = Set<SeedDedupKey>()
    return cookies.compactMap { cookie in
        let name = cookie.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let rawDomain = cookie.domain.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let hostOnly = !rawDomain.hasPrefix(".")
        let cleanDomain = rawDomain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard !name.isEmpty,
              cleanDomain.contains("."),
              !cleanDomain.containsInvalidCookieDomainScalar
        else {
            return nil
        }

        let matchesTrustedProbeHost = probeHosts.contains { probeHost in
            if hostOnly {
                return probeHost == cleanDomain
            }
            return probeHost == cleanDomain || probeHost.hasSuffix(".\(cleanDomain)")
        }
        guard matchesTrustedProbeHost else { return nil }

        let normalizedPath = cookie.path.hasPrefix("/") ? cookie.path : "/"
        let dedupKey = SeedDedupKey(name: name, domain: cleanDomain, hostOnly: hostOnly, path: normalizedPath)
        guard seen.insert(dedupKey).inserted else { return nil }

        let expiresMillis = cookie.expiresDate.map { date in
            KotlinLong(longLong: Int64((date.timeIntervalSince1970 * 1_000.0).rounded()))
        }
        return SilentSeedCookie(
            name: name,
            value: cookie.value,
            domain: cleanDomain,
            hostOnly: hostOnly,
            path: normalizedPath,
            expiresAtEpochMillis: expiresMillis,
            secure: cookie.isSecure,
            httpOnly: cookie.isHTTPOnly
        )
    }
}

public func evaluateAppleSilentRenewPollState(
    loadedUrl: String?,
    isLoading: Bool,
    mWeiboCookies: [String: String],
    previousSubCookie: String?,
    visitedPassportOrVisitor: Bool,
    interactiveLoginEnteredAtMs: Int64?,
    nowMs: Int64,
    settleWindowMs: Int64 = 2_500
) -> AppleSilentPollDecision {
    let decision = SilentWebCookieRenewerKt.evaluateSilentRenewPollState(
        loadedUrl: loadedUrl,
        isLoading: isLoading,
        mWeiboCookies: mWeiboCookies,
        previousSubCookie: previousSubCookie,
        visitedPassportOrVisitor: visitedPassportOrVisitor,
        interactiveLoginEnteredAtMs: interactiveLoginEnteredAtMs.map { KotlinLong(longLong: $0) },
        nowMs: nowMs,
        settleWindowMs: settleWindowMs
    )
    switch onEnum(of: decision) {
    case .completed:
        return .completed
    case .interactiveLoginRequired:
        return .interactiveLoginRequired
    case .pending(let pending):
        return .pending(interactiveLoginEnteredAtMs: pending.interactiveLoginEnteredAtMs?.int64Value)
    }
}

public final class AppleSilentWebCookieRenewer: SilentWebCookieRenewer, @unchecked Sendable {
    public static let shared = AppleSilentWebCookieRenewer()

    public let pollIntervalMs: Int64
    public let settleWindowMs: Int64

    private var isRunning = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    public init(
        pollIntervalMs: Int64 = 750,
        settleWindowMs: Int64 = 2_500
    ) {
        self.pollIntervalMs = pollIntervalMs
        self.settleWindowMs = settleWindowMs
    }

    public func __renew(request: SilentCookieRenewRequest) async throws -> any SilentCookieRenewResult {
        guard SilentWebCookieRenewerKt.isAllowedSilentRenewNavigationUrl(
            url: request.startUrl,
            allowedHosts: request.allowedHosts,
            allowedHostSuffixes: request.allowedHostSuffixes
        ),
        let startURL = URL(string: request.startUrl)
        else {
            return SilentCookieRenewResultSkipped(reason: "invalid-start-url")
        }

        await acquireSlot()
        defer { releaseSlot() }

        try Task.checkCancellation()

        let snapshot: ProxySnapshot
        do {
            snapshot = try await ProxyNetwork.shared.snapshot(ref: request.proxyRef)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            if Task.isCancelled { throw CancellationError() }
            return SilentCookieRenewResultSkipped(reason: "proxy-snapshot-failed")
        }

        try Task.checkCancellation()
        guard snapshot.isValid else {
            return SilentCookieRenewResultSkipped(reason: "proxy-invalid")
        }

        let route = WebViewProxyRoute(snapshot: snapshot)
        let seedHTTPCookies = request.seedCookies
            .filter { SilentWebCookieRenewerKt.shouldSeedSilentRenewCookie(cookie: $0, previousSubCookie: request.previousSubCookie) }
            .compactMap(silentSeedToHTTPCookie)
        do {
            try await route.prepare(cookies: seedHTTPCookies)
        } catch is CancellationError {
            route.close()
            throw CancellationError()
        } catch {
            route.close()
            if Task.isCancelled { throw CancellationError() }
            return SilentCookieRenewResultSkipped(reason: "proxy-prepare-failed")
        }

        let (stream, continuation) = AsyncStream<PollEvent>.makeStream()
        let delegate = SilentRenewNavigationDelegate(
            route: route,
            allowedHosts: request.allowedHosts,
            allowedHostSuffixes: request.allowedHostSuffixes,
            onWake: { continuation.yield(.check) }
        )

        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = route.dataStore
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: configuration)
        let configuredUserAgent = request.userAgent?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let configuredUserAgent, !configuredUserAgent.isEmpty {
            webView.customUserAgent = configuredUserAgent
        }
        webView.navigationDelegate = delegate
        route.bind(webView)
        route.onStop {
            continuation.yield(.check)
        }

        let intervalMs = pollIntervalMs
        let timeoutMs = max(request.timeoutMillis, 1_000)
        let tickerTask = Task {
            continuation.yield(.check)
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(intervalMs))
                guard !Task.isCancelled else { break }
                continuation.yield(.check)
            }
        }
        let timeoutTask = Task {
            try? await Task.sleep(for: .milliseconds(timeoutMs))
            guard !Task.isCancelled else { return }
            continuation.yield(.timeout)
            continuation.finish()
        }

        do {
            webView.load(URLRequest(url: startURL, cachePolicy: .reloadIgnoringLocalCacheData))

            var interactiveEnteredAtMs: Int64?
            let mWeiboURL = URL(string: "https://m.weibo.cn/")
            var outcome: (any SilentCookieRenewResult)?

            for await event in stream {
                try Task.checkCancellation()
                if !route.isValid || route.failed {
                    outcome = SilentCookieRenewResultSkipped(reason: "proxy-route-failed")
                    break
                }

                let allCookies = await route.dataStore.httpCookieStore.allCookies()
                try Task.checkCancellation()

                let mWeiboCookies = preferredCookieMap(cookies: allCookies, matching: mWeiboURL)
                let nowMs = Int64((Date().timeIntervalSince1970 * 1_000.0).rounded())
                let loadedURL = webView.url?.absoluteString ?? delegate.lastMainFrameURL

                let decision = evaluateAppleSilentRenewPollState(
                    loadedUrl: loadedURL,
                    isLoading: webView.isLoading,
                    mWeiboCookies: mWeiboCookies,
                    previousSubCookie: request.previousSubCookie,
                    visitedPassportOrVisitor: delegate.visitedPassportOrVisitor,
                    interactiveLoginEnteredAtMs: interactiveEnteredAtMs,
                    nowMs: nowMs,
                    settleWindowMs: settleWindowMs
                )

                switch decision {
                case .completed:
                    let evaluatedUA = (try? await webView.evaluateJavaScript("navigator.userAgent") as? String)?
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    let finalUA = (evaluatedUA?.isEmpty == false)
                        ? evaluatedUA
                        : ((configuredUserAgent?.isEmpty == false) ? configuredUserAgent : nil)
                    let renewed = httpCookiesToSilentSeedCookies(allCookies, probeUrls: request.probeUrls)
                    outcome = SilentCookieRenewResultRenewed(cookies: renewed, userAgent: finalUA)
                case .interactiveLoginRequired:
                    outcome = SilentCookieRenewResultInteractiveLoginRequired.shared
                case .pending(let enteredAt):
                    interactiveEnteredAtMs = enteredAt
                    if event == .timeout {
                        outcome = SilentCookieRenewResultSkipped(reason: "timeout")
                    }
                }

                if outcome != nil {
                    break
                }
            }

            await teardown(
                webView: webView,
                route: route,
                tickerTask: tickerTask,
                timeoutTask: timeoutTask,
                continuation: continuation
            )
            return outcome ?? SilentCookieRenewResultSkipped(reason: "timeout")
        } catch {
            await teardown(
                webView: webView,
                route: route,
                tickerTask: tickerTask,
                timeoutTask: timeoutTask,
                continuation: continuation
            )
            throw error
        }
    }

    private func acquireSlot() async {
        if !isRunning {
            isRunning = true
            return
        }
        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    private func releaseSlot() {
        if waiters.isEmpty {
            isRunning = false
        } else {
            let next = waiters.removeFirst()
            next.resume()
        }
    }

    private func teardown(
        webView: WKWebView,
        route: WebViewProxyRoute,
        tickerTask: Task<Void, Never>,
        timeoutTask: Task<Void, Never>,
        continuation: AsyncStream<PollEvent>.Continuation
    ) async {
        tickerTask.cancel()
        timeoutTask.cancel()
        continuation.finish()
        webView.stopLoading()
        webView.navigationDelegate = nil
        webView.loadHTMLString("", baseURL: nil)
        let dataStore = route.dataStore
        let types = WKWebsiteDataStore.allWebsiteDataTypes()
        let records = await dataStore.dataRecords(ofTypes: types)
        if !records.isEmpty {
            await dataStore.removeData(ofTypes: types, for: records)
        }
        route.close()
    }
}

private enum PollEvent: Sendable {
    case check
    case timeout
}

private final class SilentRenewNavigationDelegate: NSObject, WKNavigationDelegate {
    private let route: WebViewProxyRoute
    private let allowedHosts: Set<String>
    private let allowedHostSuffixes: Set<String>
    private let onWake: () -> Void

    private(set) var visitedPassportOrVisitor = false
    private(set) var lastMainFrameURL: String?

    init(
        route: WebViewProxyRoute,
        allowedHosts: Set<String>,
        allowedHostSuffixes: Set<String>,
        onWake: @escaping () -> Void
    ) {
        self.route = route
        self.allowedHosts = allowedHosts
        self.allowedHostSuffixes = allowedHostSuffixes
        self.onWake = onWake
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction
    ) async -> WKNavigationActionPolicy {
        guard route.isValid, let targetFrame = navigationAction.targetFrame, let url = navigationAction.request.url else {
            return .cancel
        }
        if targetFrame.isMainFrame {
            let urlString = url.absoluteString
            guard SilentWebCookieRenewerKt.isAllowedSilentRenewNavigationUrl(
                url: urlString,
                allowedHosts: allowedHosts,
                allowedHostSuffixes: allowedHostSuffixes
            ) else {
                return .cancel
            }
            recordSSOHostVisitIfNeeded(url)
            lastMainFrameURL = urlString
            return .allow
        }
        guard isStrictHTTPSURL(url) else {
            return .cancel
        }
        recordSSOHostVisitIfNeeded(url)
        return .allow
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationResponse: WKNavigationResponse
    ) async -> WKNavigationResponsePolicy {
        guard route.isValid, let url = navigationResponse.response.url else {
            return .cancel
        }
        if navigationResponse.isForMainFrame {
            guard SilentWebCookieRenewerKt.isAllowedSilentRenewNavigationUrl(
                url: url.absoluteString,
                allowedHosts: allowedHosts,
                allowedHostSuffixes: allowedHostSuffixes
            ) else {
                return .cancel
            }
            recordSSOHostVisitIfNeeded(url)
            lastMainFrameURL = url.absoluteString
            return .allow
        }
        return isStrictHTTPSURL(url) ? .allow : .cancel
    }

    func webView(_ webView: WKWebView, didReceiveServerRedirectForProvisionalNavigation navigation: WKNavigation!) {
        guard route.isValid else { return }
        if let url = webView.url {
            recordSSOHostVisitIfNeeded(url)
            lastMainFrameURL = url.absoluteString
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard route.isValid else { return }
        if let url = webView.url {
            recordSSOHostVisitIfNeeded(url)
            lastMainFrameURL = url.absoluteString
        }
        onWake()
    }

    func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation!,
        withError error: Error
    ) {
        reportIfRelevant(error)
        onWake()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        reportIfRelevant(error)
        onWake()
    }

    private func recordSSOHostVisitIfNeeded(_ url: URL) {
        if SilentWebCookieRenewerKt.isSilentRenewSsoHost(host: url.host) {
            visitedPassportOrVisitor = true
        }
    }

    private func reportIfRelevant(_ error: Error) {
        let nsError = error as NSError
        if nsError.domain == "WebKitErrorDomain" && nsError.code == 102 {
            return
        }
        route.report(error)
    }
}

private func isStrictHTTPSURL(_ url: URL) -> Bool {
    guard url.scheme?.lowercased() == "https",
          let host = url.host,
          !host.isEmpty,
          url.user == nil,
          url.password == nil
    else {
        return false
    }
    if let port = url.port, port != 443 {
        return false
    }
    return true
}

private func preferredCookieMap(cookies: [HTTPCookie], matching url: URL?) -> [String: String] {
    let matchingCookies = cookies
        .filter { $0.matchesRequest(url: url) }
        .sorted { lhs, rhs in
            if lhs.path.count != rhs.path.count { return lhs.path.count > rhs.path.count }
            let lhsDomain = lhs.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
            let rhsDomain = rhs.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))
            if lhsDomain.count != rhsDomain.count { return lhsDomain.count > rhsDomain.count }
            return !lhs.domain.hasPrefix(".") && rhs.domain.hasPrefix(".")
        }
    var result: [String: String] = [:]
    for cookie in matchingCookies where result[cookie.name] == nil {
        result[cookie.name] = cookie.value
    }
    return result
}

private extension String {
    var containsInvalidCookieNameScalar: Bool {
        unicodeScalars.contains { scalar in
            CharacterSet.controlCharacters.contains(scalar) ||
                CharacterSet.whitespacesAndNewlines.contains(scalar) ||
                scalar == ";" ||
                scalar == "="
        }
    }

    var containsInvalidCookieValueScalar: Bool {
        unicodeScalars.contains { scalar in
            CharacterSet.controlCharacters.contains(scalar) || scalar == ";"
        }
    }

    var containsInvalidCookieDomainScalar: Bool {
        unicodeScalars.contains { scalar in
            CharacterSet.controlCharacters.contains(scalar) ||
                CharacterSet.whitespacesAndNewlines.contains(scalar) ||
                scalar == ";" ||
                scalar == "/" ||
                scalar == ":"
        }
    }
}
