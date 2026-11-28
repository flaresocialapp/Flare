@testable import FlareAppleCore
import Foundation
@preconcurrency import KotlinSharedUI
import XCTest

@MainActor
final class AppleSilentWebCookieRenewerTests: XCTestCase {
    func testSilentSeedToHTTPCookieDistinguishesDomainAndHostOnlyCookies() throws {
        let expiresMs = (Int64(Date().timeIntervalSince1970) + 30 * 86_400) * 1_000
        let domainSeed = SilentSeedCookie(
            name: "ALC",
            value: "alc-token==",
            domain: "weibo.com",
            hostOnly: false,
            path: "/",
            expiresAtEpochMillis: KotlinLong(longLong: expiresMs),
            secure: true,
            httpOnly: true
        )
        let domainCookie = try XCTUnwrap(silentSeedToHTTPCookie(domainSeed))
        XCTAssertEqual(domainCookie.name, "ALC")
        XCTAssertEqual(domainCookie.value, "alc-token==")
        XCTAssertTrue(domainCookie.domain.hasPrefix("."))
        XCTAssertTrue(domainCookie.isSecure)
        XCTAssertTrue(domainCookie.isHTTPOnly)
        XCTAssertEqual(Int64((domainCookie.expiresDate?.timeIntervalSince1970 ?? 0) * 1_000), expiresMs)
        XCTAssertTrue(domainCookie.matchesRequest(url: URL(string: "https://passport.weibo.com/sso/signin")))
        XCTAssertTrue(domainCookie.matchesRequest(url: URL(string: "https://weibo.com/")))
        XCTAssertFalse(domainCookie.matchesRequest(url: URL(string: "https://m.weibo.cn/")))

        let hostOnlySeed = SilentSeedCookie(
            name: "SRF",
            value: "srf-val",
            domain: "visitor.passport.weibo.cn",
            hostOnly: true,
            path: "/",
            expiresAtEpochMillis: nil,
            secure: true,
            httpOnly: false
        )
        let hostCookie = try XCTUnwrap(silentSeedToHTTPCookie(hostOnlySeed))
        XCTAssertFalse(hostCookie.domain.hasPrefix("."))
        XCTAssertTrue(hostCookie.matchesRequest(url: URL(string: "https://visitor.passport.weibo.cn/visitor/visitor")))
        XCTAssertFalse(hostCookie.matchesRequest(url: URL(string: "https://sub.visitor.passport.weibo.cn/")))
    }

    func testHttpCookiesToSilentSeedCookiesKeepsTrustedWeiboAndSinaScopesOnly() throws {
        let probeUrls = [
            "https://m.weibo.cn/",
            "https://passport.weibo.cn/",
            "https://visitor.passport.weibo.cn/",
            "https://weibo.com/",
            "https://passport.weibo.com/",
            "https://visitor.passport.weibo.com/",
            "https://login.sina.com.cn/",
        ]
        let sinaAlc = try XCTUnwrap(
            HTTPCookie(properties: [
                .name: "ALC",
                .value: "sina-alc==",
                .domain: ".sina.com.cn",
                .path: "/",
                .secure: "TRUE",
                HTTPCookiePropertyKey("HttpOnly"): "TRUE",
            ])
        )
        let passportSso = try XCTUnwrap(
            HTTPCookie(properties: [
                .name: "SSOLoginState",
                .value: "state-1",
                .originURL: try XCTUnwrap(URL(string: "https://passport.weibo.com/sso/")),
                .path: "/sso",
                .secure: "TRUE",
            ])
        )
        let foreign = try XCTUnwrap(
            HTTPCookie(properties: [
                .name: "TRACK",
                .value: "bad",
                .domain: ".evil.example",
                .path: "/",
                .secure: "TRUE",
            ])
        )

        let seeds = httpCookiesToSilentSeedCookies([sinaAlc, passportSso, foreign], probeUrls: probeUrls)
        XCTAssertEqual(seeds.map(\.name), ["ALC", "SSOLoginState"])
        XCTAssertEqual(seeds[0].domain, "sina.com.cn")
        XCTAssertFalse(seeds[0].hostOnly)
        XCTAssertTrue(seeds[0].httpOnly)
        XCTAssertEqual(seeds[1].domain, "passport.weibo.com")
        XCTAssertTrue(seeds[1].hostOnly)
        XCTAssertEqual(seeds[1].path, "/sso")
    }

    func testEvaluateAppleSilentRenewPollStateHandlesCompletionAndInteractiveLoginTimeout() {
        XCTAssertEqual(
            evaluateAppleSilentRenewPollState(
                loadedUrl: "https://m.weibo.cn/",
                isLoading: false,
                mWeiboCookies: ["MLOGIN": "1", "SUB": "new-sub"],
                previousSubCookie: "old-sub",
                visitedPassportOrVisitor: false,
                interactiveLoginEnteredAtMs: nil,
                nowMs: 1_000
            ),
            .completed
        )

        let firstSignInPoll = evaluateAppleSilentRenewPollState(
            loadedUrl: "https://passport.weibo.com/sso/signin?entry=wapsso",
            isLoading: false,
            mWeiboCookies: ["MLOGIN": "0"],
            previousSubCookie: "old-sub",
            visitedPassportOrVisitor: true,
            interactiveLoginEnteredAtMs: nil,
            nowMs: 1_000
        )
        XCTAssertEqual(firstSignInPoll, .pending(interactiveLoginEnteredAtMs: 1_000))

        let settledSignInPoll = evaluateAppleSilentRenewPollState(
            loadedUrl: "https://passport.weibo.com/sso/signin?entry=wapsso",
            isLoading: false,
            mWeiboCookies: ["MLOGIN": "0"],
            previousSubCookie: "old-sub",
            visitedPassportOrVisitor: true,
            interactiveLoginEnteredAtMs: 1_000,
            nowMs: 3_500
        )
        XCTAssertEqual(settledSignInPoll, .interactiveLoginRequired)

        let guestHomeFirstPoll = evaluateAppleSilentRenewPollState(
            loadedUrl: "https://m.weibo.cn/",
            isLoading: false,
            mWeiboCookies: ["MLOGIN": "0", "SUB": "visitor-guest-sub"],
            previousSubCookie: "old-sub",
            visitedPassportOrVisitor: true,
            interactiveLoginEnteredAtMs: nil,
            nowMs: 5_000
        )
        XCTAssertEqual(guestHomeFirstPoll, .pending(interactiveLoginEnteredAtMs: 5_000))

        let guestHomeSettledPoll = evaluateAppleSilentRenewPollState(
            loadedUrl: "https://m.weibo.cn/",
            isLoading: false,
            mWeiboCookies: ["MLOGIN": "0", "SUB": "visitor-guest-sub"],
            previousSubCookie: "old-sub",
            visitedPassportOrVisitor: true,
            interactiveLoginEnteredAtMs: 5_000,
            nowMs: 7_600
        )
        XCTAssertEqual(guestHomeSettledPoll, .interactiveLoginRequired)
    }

    func testAllowedNavigationAndInteractiveLoginUrlClassification() {
        let allowedHosts: Set<String> = ["weibo.cn", "weibo.com", "login.sina.com.cn"]
        let allowedSuffixes: Set<String> = ["weibo.cn", "weibo.com"]

        XCTAssertTrue(
            SilentWebCookieRenewerKt.isAllowedSilentRenewNavigationUrl(
                url: "https://passport.weibo.com/sso/signin?entry=wapsso",
                allowedHosts: allowedHosts,
                allowedHostSuffixes: allowedSuffixes
            )
        )
        XCTAssertFalse(
            SilentWebCookieRenewerKt.isAllowedSilentRenewNavigationUrl(
                url: "http://passport.weibo.com/sso/signin",
                allowedHosts: allowedHosts,
                allowedHostSuffixes: allowedSuffixes
            )
        )
        XCTAssertFalse(
            SilentWebCookieRenewerKt.isAllowedSilentRenewNavigationUrl(
                url: "https://evil.example/sso/signin",
                allowedHosts: allowedHosts,
                allowedHostSuffixes: allowedSuffixes
            )
        )

        XCTAssertTrue(SilentWebCookieRenewerKt.isWeiboInteractiveLoginUrl(url: "https://passport.weibo.com/sso/signin?entry=wapsso"))
        XCTAssertFalse(SilentWebCookieRenewerKt.isWeiboInteractiveLoginUrl(url: "https://m.weibo.cn/"))

        let customRenewer = AppleSilentWebCookieRenewer(pollIntervalMs: 500, settleWindowMs: 2_000)
        XCTAssertEqual(customRenewer.pollIntervalMs, 500)
        XCTAssertEqual(customRenewer.settleWindowMs, 2_000)

        let staleWeiboCnSub = SilentSeedCookie(
            name: "SUB",
            value: "expired-sub",
            domain: "weibo.cn",
            hostOnly: false,
            path: "/",
            expiresAtEpochMillis: nil,
            secure: true,
            httpOnly: true
        )
        let validWeiboComSub = SilentSeedCookie(
            name: "SUB",
            value: "com-sub",
            domain: "weibo.com",
            hostOnly: false,
            path: "/",
            expiresAtEpochMillis: nil,
            secure: true,
            httpOnly: true
        )
        XCTAssertFalse(SilentWebCookieRenewerKt.shouldSeedSilentRenewCookie(cookie: staleWeiboCnSub, previousSubCookie: "expired-sub"))
        XCTAssertTrue(SilentWebCookieRenewerKt.shouldSeedSilentRenewCookie(cookie: validWeiboComSub, previousSubCookie: "expired-sub"))
    }
}
