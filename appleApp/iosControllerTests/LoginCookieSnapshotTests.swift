import Foundation
import XCTest
import KotlinSharedUI
import FlareAppleCore

@MainActor
final class LoginCookieSnapshotTests: XCTestCase {
    func testOverlappingPathsPreserveBothCookiesWithMostSpecificFirst() throws {
        let root = try cookie("root", path: "/")
        let api = try cookie("api", path: "/api")
        for cookies in [[root, api], [api, root]] {
            let snapshot = snapshot(cookies, urls: ["https://login.example/api/cookie", "https://login.example/"])
            XCTAssertEqual(snapshot.values.map(\.value), ["api", "root", "root"])
            XCTAssertEqual(snapshot.values.compactMap { $0.attributes?.path }, ["/api", "/", "/"])
            XCTAssertNil(snapshot.values.first?.attributes?.hostOnly)
            XCTAssertEqual(snapshot.values.map(\.sourceUrl), ["https://login.example/api/cookie", "https://login.example/api/cookie", "https://login.example/"])
        }
    }

    func testOverlappingDomainsPreserveAllCookiesWithMostSpecificFirst() throws {
        let parent = try cookie("parent", domain: ".example")
        let domain = try cookie("domain", domain: ".login.example")
        let host = try cookie("host")
        for cookies in [[parent, domain, host], [host, domain, parent]] {
            XCTAssertEqual(snapshot(cookies).values.map(\.value), ["host", "domain", "parent"])
        }
    }

    func testNonmatchingCookiesDoNotHideRequestedCookies() throws {
        let cookies = try [
            cookie("wrong-host", domain: "other.example", path: "/api/cookie"),
            cookie("wrong-path", path: "/api/cookies"),
            cookie("secure", path: "/api", secure: true),
            cookie("http", path: "/"),
            cookie("unrequested", name: "other")
        ]
        XCTAssertEqual(snapshot(cookies).values.map(\.value), ["secure", "http"])
        XCTAssertEqual(snapshot(cookies, urls: ["http://login.example/api/cookie"]).values.map(\.value), ["http"])
    }

    func testRepeatedProbesKeepOneValuePerURLAndName() throws {
        let url = "https://login.example/api/cookie"
        let snapshot = loginCookieSnapshot(
            cookies: try [cookie("session"), cookie("other", name: "other")],
            probes: [LoginCookieProbe(url: url, names: ["session"]), LoginCookieProbe(url: url, names: ["other"]), LoginCookieProbe(url: url, names: ["session"])],
            fallbackUrl: url
        )
        XCTAssertEqual(snapshot.values.map(\.name), ["session", "other"])
    }

    func testUserAgentDefaultsToNilAndIsForwardedWhenProvided() throws {
        let url = "https://login.example/api/cookie"
        let cookies = try [cookie("session")]
        let defaultSnapshot = loginCookieSnapshot(
            cookies: cookies,
            probes: [LoginCookieProbe(url: url, names: ["session"])],
            fallbackUrl: url
        )
        XCTAssertNil(defaultSnapshot.userAgent)

        let uaSnapshot = loginCookieSnapshot(
            cookies: cookies,
            probes: [LoginCookieProbe(url: url, names: ["session"])],
            fallbackUrl: url,
            userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15"
        )
        XCTAssertEqual(uaSnapshot.userAgent, "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15")
    }

    func testCaptureAllProbeCollectsEveryMatchingCookieWhileKeepingHostOnlyNil() throws {
        let url = "https://login.example/api/cookie"
        let cookies = try [
            cookie("sub-val", name: "SUB", domain: ".login.example", path: "/"),
            cookie("csrf==", name: "X-CSRF-TOKEN", domain: "login.example", path: "/api"),
            cookie("foreign", name: "ALC", domain: "other.example", path: "/")
        ]
        let snapshot = loginCookieSnapshot(
            cookies: cookies,
            probes: [LoginCookieProbe(url: url, names: [], captureAll: true)],
            fallbackUrl: url
        )
        XCTAssertEqual(snapshot.values.map(\.name), ["X-CSRF-TOKEN", "SUB"])
        XCTAssertEqual(snapshot.values.map(\.value), ["csrf==", "sub-val"])
        XCTAssertTrue(snapshot.values.allSatisfy { $0.attributes?.hostOnly == nil })
    }

    private func snapshot(_ cookies: [HTTPCookie], urls: [String] = ["https://login.example/api/cookie"]) -> LoginCookieSnapshot {
        loginCookieSnapshot(
            cookies: cookies,
            probes: urls.map { LoginCookieProbe(url: $0, names: ["session"]) },
            fallbackUrl: urls[0]
        )
    }

    private func cookie(_ value: String, name: String = "session", domain: String = "login.example", path: String = "/", secure: Bool = false) throws -> HTTPCookie {
        var properties: [HTTPCookiePropertyKey: Any] = [.name: name, .value: value, .domain: domain, .path: path]
        if secure { properties[.secure] = "TRUE" }
        return try XCTUnwrap(HTTPCookie(properties: properties))
    }
}
