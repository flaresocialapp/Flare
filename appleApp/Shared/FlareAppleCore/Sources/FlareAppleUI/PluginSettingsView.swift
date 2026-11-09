import Foundation
@preconcurrency import KotlinSharedUI
import SwiftUI
@preconcurrency import WebKit

struct PluginSettingsView: View {
    let pluginId: String
    @Environment(\.dismiss) private var dismiss
    @State private var session: PluginSettingsFacadeV1?
    @State private var failed = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("settings_title").font(.headline)
                Spacer()
                Button("Close") { dismiss() }
            }.padding()
            if failed {
                Text("plugin_operation_failed").padding()
                Spacer()
            } else if let session {
                PluginSettingsWebView(session: session) { failed = true }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        #if os(macOS)
        .frame(minWidth: 480, minHeight: 480)
        #endif
        .task {
            do { session = try PluginSettingsFacadeV1.companion.open(pluginId: pluginId) }
            catch { failed = true }
        }
        .onDisappear { session?.close() }
    }
}

private struct PluginSettingsWebView {
    let session: PluginSettingsFacadeV1
    let onFailure: () -> Void

    func makeCoordinator() -> PluginSettingsCoordinator {
        PluginSettingsCoordinator(session: session, onFailure: onFailure)
    }
}

#if os(iOS)
extension PluginSettingsWebView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView { context.coordinator.makeWebView() }
    func updateUIView(_ view: WKWebView, context: Context) {}
    static func dismantleUIView(_ view: WKWebView, coordinator: PluginSettingsCoordinator) { coordinator.close() }
}
#elseif os(macOS)
extension PluginSettingsWebView: NSViewRepresentable {
    func makeNSView(context: Context) -> WKWebView { context.coordinator.makeWebView() }
    func updateNSView(_ view: WKWebView, context: Context) {}
    static func dismantleNSView(_ view: WKWebView, coordinator: PluginSettingsCoordinator) { coordinator.close() }
}
#endif

@MainActor
private final class PluginSettingsCoordinator: NSObject, WKScriptMessageHandler, WKURLSchemeHandler, WKNavigationDelegate {
    private let session: PluginSettingsFacadeV1
    private let onFailure: () -> Void
    private weak var webView: WKWebView?
    private var started = false
    private var closed = false
    private var entryURL: URL { URL(string: "flare-plugin://\(session.host)/\(session.entry)")! }

    init(session: PluginSettingsFacadeV1, onFailure: @escaping () -> Void) {
        self.session = session
        self.onFailure = onFailure
    }

    func makeWebView() -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.setURLSchemeHandler(self, forURLScheme: "flare-plugin")
        configuration.userContentController.add(self, name: "flareSettings")
        configuration.userContentController.addUserScript(WKUserScript(source: session.documentStartScript, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = self
        webView = view
        let rules = #"[{"trigger":{"url-filter":"^https?://"},"action":{"type":"block"}},{"trigger":{"url-filter":"^wss?://"},"action":{"type":"block"}},{"trigger":{"url-filter":"^ftp://"},"action":{"type":"block"}}]"#
        WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "flare-plugin-settings-v1", encodedContentRuleList: rules) { [weak self, weak view] rules, _ in
            guard let self, let view, !self.closed else { return }
            guard let rules else { self.fail(); return }
            configuration.userContentController.add(rules)
            view.load(URLRequest(url: self.entryURL))
        }
        return view
    }

    func close() {
        closed = true
        session.close()
        webView?.stopLoading()
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: "flareSettings")
    }

    private func fail() { close(); onFailure() }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard !closed, message.frameInfo.isMainFrame,
              let url = message.frameInfo.request.url, isLocal(url), url.path == entryURL.path,
              let body = message.body as? String, body.utf8.count <= 5 * 1024 * 1024,
              let data = body.data(using: .utf8), let request = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = request["id"] as? String, id.count <= 64 else { return }
        Task { [weak self] in
            guard let self else { return }
            let response: String
            do { response = try await session.call(message: body) }
            catch {
                let value: [String: Any] = ["id": id, "ok": false, "error": ["code": "bridge.failed", "message": "Host request failed"]]
                guard let encoded = try? JSONSerialization.data(withJSONObject: value), let text = String(data: encoded, encoding: .utf8) else { return }
                response = text
            }
            guard !closed else { return }
            _ = try? await webView?.evaluateJavaScript("globalThis.__flareSettingsReceive?.(\(response))")
        }
    }

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard !closed, let url = urlSchemeTask.request.url, isLocal(url), urlSchemeTask.request.httpMethod == "GET" else {
            urlSchemeTask.didFailWithError(URLError(.unsupportedURL)); return
        }
        do {
            let resource = try session.resource(path: String(url.path.dropFirst()))
            guard let data = Data(base64Encoded: resource.base64), let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: [
                "Content-Type": resource.mimeType,
                "Content-Security-Policy": session.contentSecurityPolicy,
                "Cache-Control": "no-store",
                "X-Content-Type-Options": "nosniff",
                "X-DNS-Prefetch-Control": "off",
                "Access-Control-Allow-Origin": "*",
            ]) else { throw URLError(.cannotDecodeContentData) }
            urlSchemeTask.didReceive(response)
            urlSchemeTask.didReceive(data)
            urlSchemeTask.didFinish()
        } catch { urlSchemeTask.didFailWithError(URLError(.resourceUnavailable)) }
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
        guard !closed, navigationAction.targetFrame?.isMainFrame == true, let url = navigationAction.request.url,
              isLocal(url), url.path == entryURL.path else { return .cancel }
        return .allow
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        if started { fail(); return }
        started = true
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { fail() }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { fail() }

    private func isLocal(_ url: URL) -> Bool {
        url.scheme == "flare-plugin" && url.host == session.host && url.port == nil && url.query == nil && url.user == nil && url.password == nil
    }
}
