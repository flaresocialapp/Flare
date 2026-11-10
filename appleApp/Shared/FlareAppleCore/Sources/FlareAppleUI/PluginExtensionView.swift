import FlareAppleCore
import Foundation
@preconcurrency import KotlinSharedUI
import SwiftUI

struct PluginExtensionView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var facade: PluginExtensionFacadeV1
    @State private var presenter: KotlinPresenter<PluginExtensionStateV1>

    init(pluginId: String) {
        let facade = PluginExtensionFacadeV1(pluginId: pluginId)
        _facade = State(initialValue: facade)
        _presenter = State(initialValue: KotlinPresenter(presenter: facade))
    }

    var body: some View {
        PluginExtensionContent(
            state: presenter.state,
            onLogin: {
                dismiss()
                if let url = URL(string: DeeplinkRoute.Login.shared.toUri()) { openURL(url) }
            },
            onAuthorize: { account in
                Task { try? await facade.authorize(accountKey: account.accountKey, authorizationToken: account.authorizationToken) }
            },
            onRevoke: { id in Task { try? await facade.revoke(accountId: id) } },
            onAutomatic: { id, action, enabled in
                Task { try? await facade.setAutomatic(accountId: id, actionId: action, enabled: enabled) }
            },
            onRun: { id, action in Task { try? await facade.run(accountId: id, actionId: action) } },
            onCancel: { facade.cancel(accountId: $0) }
        )
    }
}

struct PluginExtensionContent: View {
    @Environment(\.dismiss) private var dismiss
    let state: PluginExtensionStateV1
    let onLogin: () -> Void
    let onAuthorize: (PluginExtensionAccountItemV1) -> Void
    let onRevoke: (String) -> Void
    let onAutomatic: (String, String, Bool) -> Void
    let onRun: (String, String) -> Void
    let onCancel: (String) -> Void
    @State private var authorization: PluginExtensionAccountItemV1?
    @State private var revocation: PluginExtensionAccountItemV1?

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
        .alert("plugin_extension_authorize_title", isPresented: Binding(
            get: { authorization != nil },
            set: { if !$0 { authorization = nil } }
        ), presenting: authorization) { account in
            Button("Cancel", role: .cancel) { authorization = nil }
            Button("plugin_extension_authorize") { authorization = nil; onAuthorize(account) }
        } message: { account in
            Text(String(format: String(localized: "plugin_extension_authorize_message"), accountLabel(account), account.authOrigins.joined(separator: "\n")))
        }
        .alert("plugin_extension_revoke_title", isPresented: Binding(
            get: { revocation != nil },
            set: { if !$0 { revocation = nil } }
        ), presenting: revocation) { account in
            Button("Cancel", role: .cancel) { revocation = nil }
            Button("plugin_extension_revoke", role: .destructive) {
                revocation = nil
                if let id = account.accountId { onRevoke(id) }
            }
        } message: { account in
            Text(String(format: String(localized: "plugin_extension_revoke_message"), accountLabel(account)))
        }
    }

    private var closeButton: some View {
        Button(role: .cancel) { dismiss() } label: { Label("Close", systemImage: "xmark") }
            .labelStyle(.iconOnly)
            .help("Close")
            .keyboardShortcut(.cancelAction)
    }

    private var content: some View {
        Form {
            Section {
                Text(state.name.text).font(.headline)
                if state.error {
                    Label("plugin_operation_failed", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            } footer: {
                Text("plugin_extension_foreground_notice")
            }
            if state.loading {
                ProgressView().frame(maxWidth: .infinity)
            } else if state.accounts.isEmpty {
                Section {
                    ListEmptyView("plugin_extension_no_accounts", icon: .users)
                    Button(action: onLogin) { Label("login_title", systemImage: "plus") }
                }
            }
            ForEach(state.accounts, id: \.accountKey) { account in
                Section {
                    accountRow(account)
                    ForEach(state.actions.filter { $0.platformIds.isEmpty || $0.platformIds.contains(account.platformId) }, id: \.id) { action in
                        actionRow(action, account: account)
                        if let zone = action.dailyTimeZone {
                            Toggle(isOn: Binding(
                                get: { account.automaticActions.contains(action.id) },
                                set: { enabled in if let id = account.accountId { onAutomatic(id, action.id, enabled) } }
                            )) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("plugin_extension_automatic")
                                    Text(String(format: String(localized: "plugin_extension_automatic_description"), zone))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .toggleStyle(.switch)
                            .disabled(account.accountId == nil || isBusy(account))
                        }
                    }
                }
            }
            if !state.accounts.isEmpty {
                Section("plugin_extension_history") {
                    if state.runs.isEmpty { Text("plugin_extension_no_history").foregroundStyle(.secondary) }
                    ForEach(state.runs, id: \.id) { run in runRow(run) }
                }
            }
        }
        .formStyle(.grouped)
        .buttonStyle(.borderless)
    }

    private func accountLabel(_ account: PluginExtensionAccountItemV1) -> String {
        account.profile?.handle.display ?? account.accountKey
    }

    private func isBusy(_ account: PluginExtensionAccountItemV1) -> Bool {
        account.accountId.map { state.runningAccounts.contains($0) } ?? false
    }

    @ViewBuilder
    private func accountRow(_ account: PluginExtensionAccountItemV1) -> some View {
        let platform = account.account?.platformDisplayNameText.text ?? account.platformId
        if let profile = account.profile {
            UserCompatView(data: profile, subtitle: platform) {
                accountControls(account)
            }
        } else {
            HStack(spacing: 8) {
                if let metadata = account.account { TabIcon(icon: metadata.platformIcon, size: 44) }
                else { Image(systemName: "person.crop.circle").font(.largeTitle).foregroundStyle(.secondary) }
                VStack(alignment: .leading) {
                    Text(platform)
                    Text(account.accountKey).font(.caption).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading)
                accountControls(account)
            }
        }
    }

    @ViewBuilder
    private func accountControls(_ account: PluginExtensionAccountItemV1) -> some View {
        Toggle(isOn: Binding(
            get: { account.accountId != nil },
            set: { enabled in
                if enabled { authorization = account }
                else { revocation = account }
            }
        )) {
            Text(verbatim: "\(String(localized: "plugin_extension_authorize")): \(accountLabel(account))")
        }
        .labelsHidden()
        .toggleStyle(.switch)
    }

    private func actionRow(_ action: PluginExtensionActionItemV1, account: PluginExtensionAccountItemV1) -> some View {
        let running = isBusy(account) && state.runs.contains { $0.accountId == account.accountId && $0.actionId == action.id && $0.finishedAtMillis == nil }
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(action.title.text)
                if let description = action.description_ { Text(description.text).font(.caption).foregroundStyle(.secondary) }
            }.frame(maxWidth: .infinity, alignment: .leading)
            if running, let id = account.accountId {
                ProgressView().controlSize(.small)
                Text("plugin_extension_running").font(.caption)
                Button("Cancel") { onCancel(id) }
            } else {
                Button("plugin_extension_run") { if let id = account.accountId { onRun(id, action.id) } }
                    #if os(macOS)
                    .buttonStyle(.bordered)
                    #else
                    .buttonStyle(.borderless)
                    #endif
                    .disabled(account.accountId == nil || isBusy(account))
            }
        }
    }

    private func runRow(_ run: PluginExtensionRunV1) -> some View {
        let action = state.actions.first { $0.id == run.actionId }
        let account = state.accounts.first { $0.accountId == run.accountId }
        let running = state.runningAccounts.contains(run.accountId) && run.finishedAtMillis == nil
        return HStack(alignment: .top, spacing: 12) {
            if running { ProgressView().controlSize(.small) }
            else {
                Image(systemName: resultIcon(run.result.status))
                    .foregroundStyle(run.result.status == .failed ? .red : .secondary)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(action?.title.text ?? run.actionId)
                Text(running ? "plugin_extension_running" : resultKey(run.result.status))
                HStack(spacing: 6) {
                    Text(account?.profile?.name.innerText ?? account.map(accountLabel) ?? "")
                        .lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                    DateTimeText(data: run.startedAt).fixedSize()
                }.font(.caption).foregroundStyle(.secondary)
                Text(String(format: String(localized: "plugin_extension_counts"), run.result.succeeded, run.result.skipped, run.result.failed))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    private func resultIcon(_ status: PluginExtensionResultStatusV1) -> String {
        switch status {
        case .completed, .alreadyCompleted: "checkmark.circle"
        case .skipped: "arrow.right.circle"
        default: "exclamationmark.circle"
        }
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
