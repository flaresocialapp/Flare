import FlareAppleCore
import Foundation
@preconcurrency import KotlinSharedUI
import SwiftUI

struct PluginExtensionView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var facade: PluginExtensionFacadeV1
    @State private var presenter: KotlinPresenter<PluginExtensionStateV1>
    @State private var authorization: PluginExtensionAccountItemV1?

    init(pluginId: String) {
        let facade = PluginExtensionFacadeV1(pluginId: pluginId)
        _facade = State(initialValue: facade)
        _presenter = State(initialValue: KotlinPresenter(presenter: facade))
    }

    var body: some View {
        Group {
            #if os(macOS)
            VStack(spacing: 0) {
                HStack {
                    Text("plugin_extension_title").font(.headline)
                    Spacer()
                    closeButton.buttonStyle(.bordered)
                }.padding()
                Divider()
                content
            }
            .frame(minWidth: 480, minHeight: 480)
            #else
            NavigationStack {
                content
                    .navigationTitle("plugin_extension_title")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { closeButton } }
            }
            #endif
        }
        .alert("plugin_extension_authorize", isPresented: Binding(
            get: { authorization != nil },
            set: { if !$0 { authorization = nil } }
        ), presenting: authorization) { account in
            Button("Cancel", role: .cancel) { authorization = nil }
            Button("plugin_extension_authorize") {
                authorization = nil
                Task { try? await facade.authorize(accountKey: account.accountKey, authorizationToken: account.authorizationToken) }
            }
        } message: { account in
            Text(String(format: String(localized: "plugin_extension_authorize_message"), account.accountKey, account.authOrigins.joined(separator: "\n")))
        }
    }

    private var closeButton: some View {
        Button(role: .cancel) { dismiss() } label: {
            Label("Close", systemImage: "xmark")
        }
        .labelStyle(.iconOnly)
        .help("Close")
        .keyboardShortcut(.cancelAction)
    }

    private var content: some View {
        List {
            Section {
                Text(presenter.state.name.text).font(.headline)
                Text("plugin_extension_foreground_notice")
                if presenter.state.error { Text("plugin_operation_failed").foregroundStyle(.red) }
                if presenter.state.accounts.isEmpty { Text("plugin_extension_no_accounts") }
            }
            ForEach(presenter.state.accounts, id: \.accountKey) { account in
                Section(account.accountKey) {
                    Text(account.platformId).font(.caption).foregroundStyle(.secondary)
                    if let accountId = account.accountId {
                        let running = presenter.state.runningAccounts.contains(accountId)
                        Button("plugin_extension_revoke", role: .destructive) {
                            Task { try? await facade.revoke(accountId: accountId) }
                        }
                        ForEach(presenter.state.actions, id: \.id) { action in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(action.title.text).font(.headline)
                                if let description = action.description_ { Text(description.text) }
                                if let zone = action.dailyTimeZone {
                                    Toggle(isOn: Binding(
                                        get: { account.automaticActions.contains(action.id) },
                                        set: { enabled in Task { try? await facade.setAutomatic(accountId: accountId, actionId: action.id, enabled: enabled) } }
                                    )) {
                                        Text(String(format: String(localized: "plugin_extension_automatic"), zone))
                                    }
                                    .disabled(running)
                                }
                                Button("plugin_extension_run") {
                                    Task { try? await facade.run(accountId: accountId, actionId: action.id) }
                                }.disabled(running)
                            }
                        }
                        if running {
                            HStack {
                                ProgressView().controlSize(.small)
                                Text("plugin_extension_running")
                                Spacer()
                                Button("Cancel") { facade.cancel(accountId: accountId) }
                            }
                        }
                    } else {
                        Button("plugin_extension_authorize") { authorization = account }
                    }
                }
            }
            if !presenter.state.runs.isEmpty {
                Section("plugin_extension_history") {
                    ForEach(presenter.state.runs, id: \.id) { run in
                        VStack(alignment: .leading, spacing: 4) {
                            let action = presenter.state.actions.first { $0.id == run.actionId }
                            let account = presenter.state.accounts.first { $0.accountId == run.accountId }
                            Text("\(action?.title.text ?? run.actionId) · \(run.businessDate)")
                            Text(account?.accountKey ?? "").font(.caption).foregroundStyle(.secondary)
                            Text(presenter.state.runningAccounts.contains(run.accountId) && run.finishedAtMillis == nil
                                 ? "plugin_extension_running" : resultKey(run.result.status))
                            Text(String(format: String(localized: "plugin_extension_counts"), run.result.succeeded, run.result.skipped, run.result.failed))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .buttonStyle(.borderless)
    }

    private func resultKey(_ status: PluginExtensionResultStatusV1) -> LocalizedStringKey {
        switch status {
        case .completed: "plugin_extension_completed"
        case .alreadyCompleted: "plugin_extension_already_completed"
        case .partial: "plugin_extension_partial"
        case .authenticationRequired: "plugin_extension_login_required"
        case .userActionRequired: "plugin_extension_user_action_required"
        case .skipped: "plugin_extension_skipped"
        case .interrupted: "plugin_extension_interrupted"
        default: "plugin_extension_failed"
        }
    }
}
