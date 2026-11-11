import FlareAppleCore
@preconcurrency import KotlinSharedUI
import Foundation
import SwiftUI
import UniformTypeIdentifiers

#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

public struct PluginManagementView: View {
    @Environment(\.timelineAppearance.aiConfig.agent) private var agentEnabled
    #if os(macOS)
    @Environment(\.openSettings) private var openSettings
    #else
    @Environment(\.dismiss) private var dismiss
    #endif
    @State private var facade: PluginAppleFacadeV1
    @State private var presenter: KotlinPresenter<PluginManagementStateV1>
    @State private var importsPlugin = false
    @State private var checkingUpdates = false
    private var isRefreshing: Bool { presenter.state.busy || checkingUpdates }
    @State private var showsMarketplace = false
    @State private var marketplaceQuery = ""
    private let marketplace: Bool
    private let onOpenMarketplace: (() -> Void)?
    private let onSelectPluginSettings: (() -> Void)?
    private let onCreateWithAI: () -> Void
    private var showsGitHubInstall: Bool { presenter.state.gitHubInstall != nil }
    @State private var gitHubURL = ""
    @State private var settingsPluginId: String?
    @State private var extensionPluginId: String?
    @State private var pendingUninstallId: String?
    @State private var pendingUninstallName: String?
    #if os(macOS)
    @State private var showsDevelopment = false
    #endif

    public init(
        marketplace: Bool = false,
        onOpenMarketplace: (() -> Void)? = nil,
        onSelectPluginSettings: (() -> Void)? = nil,
        onCreateWithAI: @escaping () -> Void
    ) {
        self.marketplace = marketplace
        self.onOpenMarketplace = onOpenMarketplace
        self.onSelectPluginSettings = onSelectPluginSettings
        self.onCreateWithAI = onCreateWithAI
        let facade = PluginAppleFacadeV1()
        _facade = State(initialValue: facade)
        _presenter = State(initialValue: KotlinPresenter(presenter: facade))
    }

    public var body: some View {
        Group {
            if marketplace {
                marketplaceContent
                    .searchable(text: $marketplaceQuery, prompt: Text("plugin_marketplace_search"))
                    .refreshable { try? await facade.refreshMarketplace() }
                    .overlay {
                        if presenter.state.busy {
                            ProgressView()
                                .padding()
                                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                    #if os(macOS)
                    .toolbar {
                        ToolbarItem(placement: .primaryAction) {
                            marketplaceRefreshButton.disabled(presenter.state.busy)
                        }
                    }
                    #endif
            } else {
                List { installedSections }
                    .refreshable { await checkUpdates() }
                    #if os(macOS)
                    .safeAreaInset(edge: .top, spacing: 0) { installedActions }
                    #endif
            }
        }
        #if os(macOS)
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .padding(.horizontal, MacPluginLayout.pagePadding)
        .padding(.top, 6)
        #endif
        .navigationTitle(marketplace ? String(localized: "plugin_marketplace") : String(localized: "settings_plugins_title"))
        #if os(iOS)
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                if !marketplace {
                    addMenu
                    marketplaceButton.labelStyle(.iconOnly)
                    maintenanceMenu.labelStyle(.iconOnly)
                }
            }
        }
        #endif
        .fileImporter(
            isPresented: $importsPlugin,
            allowedContentTypes: [UTType(filenameExtension: "fpp") ?? .data],
            allowsMultipleSelection: false,
            onCompletion: importPlugin
        )
        .sheet(isPresented: Binding(get: { settingsPluginId != nil }, set: { if !$0 { settingsPluginId = nil } })) {
            if let settingsPluginId { PluginSettingsView(pluginId: settingsPluginId) }
        }
        .sheet(isPresented: Binding(get: { extensionPluginId != nil }, set: { if !$0 { extensionPluginId = nil } })) {
            if let extensionPluginId { PluginExtensionView(pluginId: extensionPluginId) }
        }
        .task {
            if marketplace { facade.loadMarketplace(forceRefresh: false) }
            else { await checkUpdates(forceRefresh: false) }
        }
        .navigationDestination(isPresented: $showsMarketplace) {
            PluginManagementView(marketplace: true, onCreateWithAI: onCreateWithAI)
        }
        .sheet(isPresented: Binding(
            get: { showsGitHubInstall },
            set: { visible in
                if !visible && showsGitHubInstall { Task { try? await facade.cancelInstall() } }
            }
        )) {
            gitHubInstallSheet
        }
        .alert(
            "plugin_large_repository_title",
            isPresented: Binding(
                get: { presenter.state.needsRepositoryDownloadConfirmation && !showsGitHubInstall },
                set: { _ in }
            )
        ) {
            Button("Cancel", role: .cancel) {
                Task { try? await facade.cancelRepositoryDownload() }
            }
            Button("plugin_continue_download") {
                Task { try? await facade.confirmRepositoryDownload() }
            }
        } message: {
            Text("plugin_large_repository_message")
        }
        .alert(
            presenter.state.pendingInstall.map { "\($0.name) \($0.version)" } ?? "",
            isPresented: Binding(
                get: { presenter.state.pendingInstall != nil && !showsGitHubInstall },
                set: { _ in }
            )
        ) {
            Button("Cancel", role: .cancel) {
                Task { try? await facade.cancelInstall() }
            }
            .disabled(presenter.state.busy)
            Button("plugin_confirm_install") {
                Task { try? await facade.confirmInstall() }
            }
            .keyboardShortcut(.defaultAction)
            .disabled(presenter.state.busy)
        } message: {
            if let review = presenter.state.pendingInstall {
                Text(installReviewText(review))
            }
        }
        .alert(
            "plugin_uninstall_confirm_title",
            isPresented: Binding(
                get: { pendingUninstallId != nil },
                set: { visible in
                    if !visible {
                        pendingUninstallId = nil
                        pendingUninstallName = nil
                    }
                }
            )
        ) {
            Button("Cancel", role: .cancel) {
                pendingUninstallId = nil
                pendingUninstallName = nil
            }
            .disabled(presenter.state.busy)
            Button("plugin_uninstall", role: .destructive) {
                guard let pluginId = pendingUninstallId else { return }
                pendingUninstallId = nil
                pendingUninstallName = nil
                Task { try? await facade.uninstall(pluginId: pluginId) }
            }
            .disabled(presenter.state.busy)
        } message: {
            Text(
                String(
                    format: presenter.state.authoredPlugins.contains { $0.id == pendingUninstallId } ? String(localized: "plugin_ai_delete_message") : String(localized: "plugin_uninstall_confirm_message"),
                    pendingUninstallName ?? ""
                )
            )
        }
    }

    #if os(macOS)
    // The Settings window reserves its toolbar for pane selection.
    private var installedActions: some View {
        HStack(spacing: 8) {
            Spacer()
            Button {
                Task { await checkUpdates() }
            } label: {
                Label {
                    Text("Refresh")
                } icon: {
                    Image(fontAwesome: .arrowsRotate)
                }
            }
            .labelStyle(.iconOnly)
            .help("Refresh")
            .disabled(isRefreshing || !presenter.state.plugins.contains { $0.sourceUrl != nil })
            addActions
            marketplaceButton
            maintenanceMenu.menuStyle(.borderedButton)
        }
        .buttonStyle(.bordered)
        .controlSize(.small)
        .labelStyle(.titleAndIcon)
        .disabled(presenter.state.busy)
        .padding(.horizontal, MacPluginLayout.nativeRowInset)
        .padding(.vertical, 8)
    }
    #endif

    @MainActor
    private func checkUpdates(forceRefresh: Bool = true) async {
        guard !isRefreshing else { return }
        checkingUpdates = true
        defer { checkingUpdates = false }
        try? await facade.checkUpdates(forceRefresh: forceRefresh)
    }

    @ViewBuilder
    private var installedSections: some View {
        let state = presenter.state
        #if os(macOS)
        let issues = state.issues.filter { !state.canRebuildIndex || $0.code != "index.corrupt" }
        if isRefreshing {
            ProgressView()
                .controlSize(.small)
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .accessibilityLabel(Text("Refresh"))
        }
        #else
        let issues = state.issues
        #endif
        Section {
            #if os(macOS)
            GroupBox {
                betaNotice.padding(4)
            }
            .listRowBackground(Color.clear)
            .listRowInsets(MacPluginLayout.rowInsets)
            .listRowSeparator(.hidden)
            #else
            betaNotice
            #endif
        }
        #if os(macOS)
        if showsDevelopment || presenter.state.development.directory != nil {
            developmentSection
        }
        #endif

        #if os(macOS)
        if let error = state.error, !state.items.isEmpty || state.canRebuildIndex {
            Section { macPluginOperationNotice(error) }
        }
        if state.canRebuildIndex && !state.items.isEmpty {
            Section {
                macPluginNotice("plugin_list_unavailable", detail: Text("plugin_issue_index_corrupt")) {
                    rebuildIndexButton
                }
            }
        }
        #else
        if let error = state.error {
            Section {
                Label {
                    Text("plugin_operation_failed")
                } icon: {
                    Image(fontAwesome: .circleExclamation)
                }
                .foregroundStyle(.red)
                Text(String(error.prefix(512))).font(.caption)
            }
        }
        #endif

        if !issues.isEmpty || !state.runtimeIssues.isEmpty {
            Section("plugin_issues") {
                ForEach(Array(issues.enumerated()), id: \.offset) { _, issue in
                    Text(issue.code == "update.failed" ? "\(issue.pluginId ?? ""): \(issue.message.prefix(512))" : issueText(issue.code))
                        .foregroundStyle(.red)
                }
                ForEach(state.runtimeIssues, id: \.pluginId) { issue in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(issue.pluginId)
                            Text(runtimeIssueText(issue.code))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("plugin_retry") {
                            Task { try? await facade.retryRuntime(pluginId: issue.pluginId) }
                        }
                        .disabled(presenter.state.busy)
                    }
                }
            }
        }

        if state.items.isEmpty {
            #if os(macOS)
            macInstalledState(state).macPluginStateRow()
            #else
            ContentUnavailableView {
                Label {
                    Text("plugin_empty")
                } icon: {
                    Image(fontAwesome: .puzzlePiece)
                }
            } description: {
                Text("settings_plugins_subtitle")
            }
            .listRowBackground(Color.clear)
            #endif
        } else {
            Section {
                ForEach(state.items, id: \.pluginId) { plugin in
                    pluginRow(plugin)
                }
            }
        }
    }

    #if os(macOS)
    @ViewBuilder
    private func macInstalledState(_ state: PluginManagementStateV1) -> some View {
        if state.canRebuildIndex {
            ContentUnavailableView {
                Label("plugin_list_unavailable", systemImage: "exclamationmark.triangle")
            } description: {
                Text("plugin_issue_index_corrupt")
            } actions: {
                rebuildIndexButton.buttonStyle(.borderedProminent)
            }
        } else if let error = state.error {
            ContentUnavailableView {
                Label("plugin_operation_failed_title", systemImage: "exclamationmark.triangle")
            } description: {
                Text(verbatim: String(error.prefix(512))).textSelection(.enabled)
            }
        } else {
            ContentUnavailableView {
                Label {
                    Text("plugin_empty")
                } icon: {
                    Image(fontAwesome: .puzzlePiece)
                }
            } description: {
                Text("plugin_empty_install_hint")
            }
        }
    }
    #endif

    @ViewBuilder
    private var marketplaceContent: some View {
        #if os(macOS)
        let state = presenter.state
        let catalog = state.marketplace
        if catalog.plugins.isEmpty {
            VStack(spacing: 0) {
                if let error = state.error {
                    macPluginOperationNotice(error)
                        .padding(.horizontal, MacPluginLayout.nativeRowInset)
                }
                Group {
                    if catalog.error != nil {
                        ContentUnavailableView {
                            Label("plugin_marketplace_failed", systemImage: "exclamationmark.triangle")
                        } actions: {
                            marketplaceRetryButton.buttonStyle(.borderedProminent)
                        }
                    } else if catalog.loading || !catalog.loaded {
                        ProgressView("plugin_marketplace_loading")
                    } else {
                        ListEmptyView("plugin_marketplace_empty", icon: .puzzlePiece)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            List { marketplaceSections }
        }
        #else
        List { marketplaceSections }
        #endif
    }

    @ViewBuilder
    private var marketplaceSections: some View {
        let state = presenter.state
        let marketplace = state.marketplace
        let plugins = marketplace.search(query: marketplaceQuery)
        #if os(macOS)
        if marketplace.error != nil {
            Section {
                macPluginNotice("plugin_marketplace_refresh_failed", detail: Text("plugin_marketplace_cached")) {
                    marketplaceRetryButton
                }
            }
        }
        if let error = state.error {
            Section { macPluginOperationNotice(error) }
        }
        if plugins.isEmpty {
            ContentUnavailableView.search(text: marketplaceQuery.trimmingCharacters(in: .whitespacesAndNewlines))
                .macPluginStateRow()
        }
        #else
        if marketplace.loading && marketplace.plugins.isEmpty {
            Section { UiListPlaceholder() }
        }
        if marketplace.error != nil || state.error != nil {
            Section {
                if marketplace.error != nil {
                    Text("plugin_marketplace_failed").foregroundStyle(.red)
                    if marketplace.loaded { Text("plugin_marketplace_cached").foregroundStyle(.secondary) }
                    Button("plugin_retry") { facade.loadMarketplace(forceRefresh: true) }
                        .disabled(marketplace.loading)
                }
                if let error = state.error {
                    Text("plugin_operation_failed").foregroundStyle(.red)
                    Text(verbatim: String(error.prefix(512))).font(.caption).foregroundStyle(.red)
                }
            }
        }
        if marketplace.loaded && plugins.isEmpty && !marketplace.loading {
            if marketplaceQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                ListEmptyView("plugin_marketplace_empty", icon: .puzzlePiece)
            } else {
                ListEmptyView("search_empty_title", icon: .magnifyingGlass)
            }
        }
        #endif
        Section {
            ForEach(plugins, id: \.id) { entry in
                let installed = state.isInstalled(pluginId: entry.id)
                let managed = state.isManaged(pluginId: entry.id)
                PluginMarketplaceRow(
                    entry: entry,
                    facade: facade,
                    installed: installed,
                    managed: managed,
                    canInstall: state.canInstallFromMarketplace,
                    onManage: {
                        #if os(macOS)
                        onSelectPluginSettings?()
                        openSettings()
                        #else
                        dismiss()
                        #endif
                    }
                )
            }
        }
    }

    #if os(macOS)
    private var marketplaceRetryButton: some View {
        Button("plugin_retry") { facade.loadMarketplace(forceRefresh: true) }
            .disabled(presenter.state.marketplace.loading || presenter.state.busy)
    }
    #endif

    private var marketplaceButton: some View {
        Button(action: onOpenMarketplace ?? { showsMarketplace = true }) {
            Label {
                Text("plugin_marketplace")
            } icon: {
                Image(fontAwesome: .store)
            }
        }
        .help("plugin_marketplace")
        .disabled(presenter.state.busy)
    }

    private var marketplaceRefreshButton: some View {
        Button {
            facade.loadMarketplace(forceRefresh: true)
        } label: {
            Label {
                Text("plugin_marketplace_refresh")
            } icon: {
                Image(fontAwesome: .arrowsRotate)
            }
        }
        .labelStyle(.iconOnly)
        .help("plugin_marketplace_refresh")
        .disabled(presenter.state.marketplace.loading)
    }

    private var betaNotice: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(fontAwesome: .circleInfo)
                .foregroundStyle(.tint)
                .accessibilityHidden(true)
            Text("plugin_beta_description")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                // Feedback must bypass the app's social content routing.
                .environment(\.openURL, OpenURLAction { .systemAction($0) })
        }
        .padding(.vertical, 4)
    }

    private func pluginMenu(_ plugin: PluginManagementItemV1, name: String, onUninstall: @escaping () -> Void) -> some View {
        Menu {
            if plugin.hasPlatformExtension {
                Button("plugin_extension_title") { extensionPluginId = plugin.pluginId }
            }
            if plugin.hasSettings {
                Button("settings_title") { settingsPluginId = plugin.pluginId }
            }
            Button(role: .destructive, action: onUninstall) {
                Label {
                    Text("plugin_uninstall")
                } icon: {
                    Image(fontAwesome: .trash)
                }
            }
        } label: {
            pluginRowMoreLabel()
        }
        .labelStyle(.iconOnly)
        .buttonStyle(.borderless)
        .accessibilityLabel(Text(verbatim: "\(String(localized: "more")): \(name)"))
        .help("more")
        #if os(macOS)
        .menuIndicator(.hidden)
        #endif
    }

    @ViewBuilder
    private func pluginRow(_ plugin: PluginManagementItemV1) -> some View {
        let name = plugin.nameText.text.isEmpty ? String(localized: "plugin_ai_untitled") : plugin.nameText.text
        let subtitle = plugin.authoring.map {
            String(format: String(localized: "plugin_ai_source_revision"), $0.revision)
        } ?? "\(plugin.platformId.isEmpty ? plugin.pluginId : plugin.platformId) · \(plugin.version)"
        let busy = presenter.state.busy || (plugin.authoring == nil && isDeveloping(plugin.pluginId))
        let title = VStack(alignment: .leading, spacing: 2) {
            Text(name).font(.headline)
            Text(subtitle).font(.caption).foregroundStyle(.secondary)
        }
        let menu = pluginMenu(plugin, name: name) {
            pendingUninstallId = plugin.pluginId
            pendingUninstallName = name
        }
        .disabled(busy)
        #if os(macOS)
        macPluginRow(iconPath: plugin.iconPath) {
            VStack(alignment: .leading, spacing: 4) {
                title
                pluginRowDetails(plugin, busy: busy)
            }
        } actions: {
            menu
        }
        #else
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                pluginIcon(path: plugin.iconPath)
                    .frame(width: 40, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                title
                Spacer()
                menu
            }
            pluginRowDetails(plugin, busy: busy)
        }
        .padding(.vertical, 4)
        #endif
    }

    @ViewBuilder
    private func pluginRowDetails(_ plugin: PluginManagementItemV1, busy: Bool) -> some View {
        if let source = plugin.sourceUrl {
            Text(source).font(.caption).foregroundStyle(.secondary)
        }
        if let update = plugin.availableUpdate {
            Button {
                Task { try? await facade.inspectGitHubUpdate(pluginId: plugin.pluginId) }
            } label: {
                Text("plugin_update") + Text(" " + update.tag)
            }
            .disabled(busy)
        }
        if let authoring = plugin.authoring {
            if authoring.phase != .idle && authoring.phase != .applied {
                Text(pluginAuthoringLabel(authoring.phase)).font(.caption).foregroundStyle(.secondary)
            }
            if let error = authoring.error {
                Text(verbatim: error).font(.caption).foregroundStyle(.red)
            }
            if authoring.canCancelUpdate {
                Button("plugin_ai_stop") {
                    Task { try? await facade.cancelPluginUpdate(pluginId: plugin.pluginId) }
                }
                .buttonStyle(.bordered)
                .disabled(busy)
            }
        }
    }

    private func isDeveloping(_ pluginId: String) -> Bool {
        presenter.state.development.active && presenter.state.development.pluginId == pluginId
    }

    private var addMenu: some View {
        Menu {
            addActions
        } label: {
            Label {
                Text("plugin_add")
            } icon: {
                Image(fontAwesome: .plus)
            }
        }
        .labelStyle(.iconOnly)
        .accessibilityLabel(Text("plugin_add"))
        .help("plugin_add")
        .disabled(presenter.state.busy)
    }

    private var addActions: some View {
        Group {
            Button {
                gitHubURL = ""
                facade.openGitHubInstall()
            } label: {
                Label {
                    Text("plugin_install_github")
                } icon: {
                    Image(fontAwesome: .github)
                }
            }
            if agentEnabled {
                Button(action: onCreateWithAI) {
                    Label {
                        Text("plugin_ai_create")
                    } icon: {
                        Image(fontAwesome: .robot)
                    }
                }
            }
            Button {
                importsPlugin = true
            } label: {
                Label {
                    Text("plugin_install")
                } icon: {
                    Image(fontAwesome: .fileImport)
                }
            }
        }
    }

    private var maintenanceMenu: some View {
        Menu {
            #if os(macOS)
            Button("plugin_development") {
                facade.prepareDevelopment()
                showsDevelopment = true
            }
            #endif
            Button("plugin_check_updates") {
                Task { await checkUpdates() }
            }
            .disabled(isRefreshing || !presenter.state.plugins.contains { $0.sourceUrl != nil })
            if presenter.state.canRebuildIndex {
                rebuildIndexButton
            }
            Button("plugin_cleanup") {
                Task { try? await facade.cleanup() }
            }
            .disabled(presenter.state.canRebuildIndex)
        } label: {
            Label {
                Text("more")
            } icon: {
                Image(fontAwesome: .ellipsisVertical)
            }
        }
        .accessibilityLabel(Text("more"))
        .help("more")
        .disabled(presenter.state.busy)
    }

    private var rebuildIndexButton: some View {
        Button("plugin_rebuild_index") {
            Task { try? await facade.rebuildIndex() }
        }
        .disabled(presenter.state.busy)
    }

    #if os(macOS)
    private var developmentSection: some View {
        let state = presenter.state.development
        return Section("plugin_development") {
            Text("plugin_development_description").foregroundStyle(.secondary)
            if let directory = state.directory ?? state.recentDirectory {
                Text(directory).font(.caption).textSelection(.enabled)
            }
            if state.active {
                Text("\(state.pluginId ?? "") · #\(state.generation)")
            } else if let pluginId = state.pluginId {
                Text("\(state.name ?? pluginId) (\(pluginId))")
            }
            if state.validating {
                ProgressView("plugin_development_validating")
            } else if state.candidateHash != nil {
                Text(state.active && state.candidateHash == state.runningHash
                     ? "plugin_development_current" : "plugin_development_ready")
            }
            if let error = state.error {
                Text(error).font(.caption).foregroundStyle(.red).textSelection(.enabled)
            }
            HStack {
                Button("plugin_development_open", action: openDevelopmentDirectory)
                    .disabled(state.busy || state.active)
                Button(state.active ? "plugin_development_reload" : "plugin_development_start") {
                    Task { try? await facade.reloadDevelopment(confirmed: false) }
                }
                .disabled(state.busy || state.validating || state.candidateHash == nil)
                if state.directory != nil {
                    Button("plugin_development_stop") {
                        Task {
                            do {
                                try await facade.stopDevelopment()
                                PluginDevelopmentDirectoryAccess.release()
                            } catch { /* The shared state displays the failure. */ }
                        }
                    }
                    .disabled(state.busy)
                }
                if state.busy { ProgressView().controlSize(.small) }
            }
            if !state.messages.isEmpty {
                DisclosureGroup("plugin_development_diagnostics") {
                    Text(state.messages.joined(separator: "\n"))
                        .font(.caption.monospaced()).textSelection(.enabled)
                }
            }
        }
        .alert("plugin_development", isPresented: Binding(
            get: { presenter.state.development.reviewHash != nil },
            set: { _ in }
        )) {
            Button("Cancel", role: .cancel) { facade.cancelDevelopmentReview() }
            Button(state.active ? "plugin_development_reload" : "plugin_development_start") {
                Task { try? await facade.reloadDevelopment(confirmed: true) }
            }
            .keyboardShortcut(.defaultAction)
            .disabled(state.busy)
        } message: {
            Text(([String(localized: "plugin_development_review")]
                  + state.permissions.map { "• " + pluginPermissionText($0) }
                  + (state.credentialSchemaChanged ? [String(localized: "plugin_development_schema")] : []))
                .joined(separator: "\n"))
        }
    }

    private func openDevelopmentDirectory() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        if let recent = presenter.state.development.recentDirectory {
            panel.directoryURL = URL(fileURLWithPath: recent, isDirectory: true)
        }
        guard panel.runModal() == .OK, let url = panel.url else { return }
        PluginDevelopmentDirectoryAccess.retain(url)
        Task { try? await facade.openDevelopmentDirectory(path: url.path(percentEncoded: false)) }
    }
    #endif

    private var gitHubInstallSheet: some View {
        let busy = presenter.state.gitHubInstall?.busy == true
        return NavigationStack {
            Form {
                Section {
                    if let review = presenter.state.pendingInstall {
                        Text(installReviewText(review))
                    } else {
                        TextField(
                            "plugin_github_repository",
                            text: $gitHubURL,
                            prompt: Text(verbatim: "https://github.com/owner/repository")
                        )
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        #endif
                        .onChange(of: gitHubURL) { _, _ in
                            facade.clearGitHubInstallError()
                        }
                    }
                } header: {
                    if let review = presenter.state.pendingInstall {
                        Text(verbatim: "\(review.name) \(review.version)")
                    }
                } footer: {
                    VStack(alignment: .leading, spacing: 8) {
                        if presenter.state.pendingInstall == nil {
                            Text("plugin_github_path_hint")
                        }
                        if let error = presenter.state.gitHubInstall?.error {
                            Text(String(error.prefix(512))).foregroundStyle(.red)
                        }
                    }
                }
            }
            #if os(macOS)
            .formStyle(.grouped)
            #endif
            .disabled(busy)
            .navigationTitle("plugin_install_github")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .cancel) {
                        Task { try? await facade.cancelInstall() }
                    } label: {
                        Label {
                            Text("Cancel")
                        } icon: {
                            Image(fontAwesome: .xmark)
                        }
                    }
                    #if os(iOS)
                    .labelStyle(.iconOnly)
                    #else
                    .labelStyle(.titleOnly)
                    #endif
                    .disabled(busy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        let isConfirming = presenter.state.pendingInstall != nil
                        Task {
                            do {
                                if isConfirming {
                                    try await facade.confirmInstall()
                                } else {
                                    try await facade.inspectGitHub(url: gitHubURL)
                                }
                            } catch { }
                        }
                    } label: {
                        Label {
                            Text("plugin_confirm_install")
                        } icon: {
                            Image(fontAwesome: .check)
                        }
                    }
                    #if os(iOS)
                    .labelStyle(.iconOnly)
                    #else
                    .labelStyle(.titleOnly)
                    #endif
                    .disabled(busy || (presenter.state.pendingInstall == nil && gitHubURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
                }
            }
            .overlay { if busy { ProgressView() } }
        }
        .interactiveDismissDisabled(busy || presenter.state.pendingInstall != nil)
        .alert(
            "plugin_large_repository_title",
            isPresented: Binding(
                get: { presenter.state.needsRepositoryDownloadConfirmation },
                set: { _ in }
            )
        ) {
            Button("Cancel", role: .cancel) {
                Task { try? await facade.cancelRepositoryDownload() }
            }
            Button("plugin_continue_download") {
                Task { try? await facade.confirmRepositoryDownload() }
            }
        } message: {
            Text("plugin_large_repository_message")
        }
        #if os(macOS)
        .frame(minWidth: 440, minHeight: 280)
        #endif
    }

    private func importPlugin(_ result: Result<[URL], Error>) {
        switch result {
        case .failure:
            facade.reportOperationFailure()
        case .success(let urls):
            guard let url = urls.first else { return }
            Task { @MainActor in
                let accessed = url.startAccessingSecurityScopedResource()
                defer {
                    if accessed {
                        url.stopAccessingSecurityScopedResource()
                    }
                }
                _ = try? await facade.inspect(path: url.path(percentEncoded: false))
            }
        }
    }

    private func issueText(_ code: String) -> String {
        let key: String
        switch code {
        case "index.corrupt":
            key = "plugin_issue_index_corrupt"
        case "package.missing-or-changed", "icon.missing-or-changed":
            key = "plugin_issue_files_missing"
        case "platform.conflict":
            key = "plugin_issue_platform_conflict"
        case "platform.invalid":
            key = "plugin_issue_platform_invalid"
        default:
            key = "plugin_operation_failed"
        }
        return String(localized: String.LocalizationValue(key))
    }

    private func runtimeIssueText(_ code: String) -> String {
        let key: String.LocalizationValue =
            code == "runtime.paused" ? "plugin_runtime_paused" : "plugin_runtime_fatal"
        return String(localized: key)
    }

    private func installReviewText(_ review: PluginInstallReviewV1) -> String {
        var lines = [review.sourceUrl == nil ? String(localized: "plugin_unverified_warning") : String(localized: "plugin_unverified_github_warning")]
        if let source = review.sourceUrl { lines.append(source) }
        if !review.capabilities.isEmpty {
            lines.append("")
            lines.append(String(localized: "plugin_capabilities"))
            lines.append(contentsOf: review.capabilities.map { "• \($0)" })
        }
        if !review.permissions.isEmpty {
            lines.append("")
            lines.append(String(localized: "plugin_permissions"))
            lines.append(contentsOf: review.permissions.map { "• " + pluginPermissionText($0) })
        }
        lines.append(contentsOf: review.warnings.compactMap(warningText))
        return lines.joined(separator: "\n")
    }

    private func warningText(_ warning: PluginInstallWarningV1) -> String? {
        let key: String
        switch warning.type {
        case .unverifiedLocal:
            return nil
        case .addedPermission:
            key = "plugin_warning_added_permission"
        case .downgrade:
            key = "plugin_warning_downgrade"
        case .sameVersionDifferentHash:
            key = "plugin_warning_changed_package"
        case .compatibility:
            key = "plugin_warning_compatibility"
        case .sourceChanged:
            key = "plugin_warning_source_changed"
        case .tagMoved:
            key = "plugin_warning_tag_moved"
        }
        let message = String(localized: String.LocalizationValue(key))
        let detail = warning.permission.map(pluginPermissionText) ?? warning.detail?.replacingOccurrences(
            of: "$accountOrigin",
            with: String(localized: "plugin_permission_account_origin_label")
        )
        return "• " + (detail.map { "\(message): \($0)" } ?? message)
    }
}

#if os(macOS)
// The directory watcher outlives this view; retain access until development stops.
@MainActor
private enum PluginDevelopmentDirectoryAccess {
    private static var url: URL?
    static func retain(_ selected: URL) {
        release()
        if selected.startAccessingSecurityScopedResource() { url = selected }
    }
    static func release() {
        url?.stopAccessingSecurityScopedResource()
        url = nil
    }
}
#endif

func pluginAuthoringLabel(_ phase: PluginAuthoringPhase) -> LocalizedStringKey {
    switch phase {
    case .validating: return "plugin_ai_validating"
    case .waitingForOperations: return "plugin_ai_waiting"
    case .applied: return "plugin_ai_applied"
    case .failed: return "plugin_ai_failed"
    default: return "plugin_ai_idle"
    }
}

func pluginPermissionText(_ permission: PluginPermission) -> String {
    if permission.type == .mediaNetwork {
        return String(localized: "plugin_permission_media_network")
    }
    if permission.type == .accountHttpOrigin {
        let origin = permission.origin == "$accountOrigin"
            ? String(localized: "plugin_permission_account_origin_label") : permission.origin
        return String(format: String(localized: "plugin_permission_account_http_origin"), permission.platformId ?? "", origin)
    }
    if permission.type == .httpOrigin {
        return String(format: String(localized: "plugin_permission_http_origin"), permission.origin)
    }
    let accountToken = "$accountOrigin"
    let accountRelativePath =
        permission.origin == accountToken || permission.origin.hasPrefix(accountToken + "/")
            ? String(permission.origin.dropFirst(accountToken.count))
            : nil
    let origin = accountRelativePath.map {
        String(localized: "plugin_permission_account_origin_label") + $0
    } ?? permission.origin
    guard let cookieName = permission.cookieName else {
        return accountRelativePath == nil
            ? String(format: String(localized: "plugin_permission_auth_origin"), origin)
            : String(localized: "plugin_permission_account_origin")
    }
    return String(
        format: String(localized: "plugin_permission_cookie"),
        origin,
        cookieName
    )
}

@ViewBuilder
private func pluginIcon(path: String) -> some View {
    #if os(iOS)
    if let image = UIImage(contentsOfFile: path) {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
    } else {
        Image(fontAwesome: .puzzlePiece)
    }
    #elseif os(macOS)
    if let image = NSImage(contentsOfFile: path) {
        Image(nsImage: image)
            .resizable()
            .scaledToFit()
    } else {
        Image(fontAwesome: .puzzlePiece)
    }
    #else
    Image(fontAwesome: .puzzlePiece)
    #endif
}

private func pluginRowMoreLabel() -> some View {
    Label {
        Text("more")
    } icon: {
        Image(fontAwesome: .ellipsisVertical)
            #if os(macOS)
            .frame(width: 28, height: 28)
            .contentShape(Rectangle())
            #else
            .frame(minWidth: 44, minHeight: 44)
            #endif
    }
}

#if os(macOS)
private enum MacPluginLayout {
    static let pagePadding: CGFloat = 16
    // Plain lists inset their row content inside the scroll view.
    static let nativeRowInset: CGFloat = 8
    static let iconSize: CGFloat = 40
    static let rowInsets = EdgeInsets(top: 10, leading: 0, bottom: 10, trailing: 0)
}

private extension View {
    func macPluginStateRow() -> some View {
        frame(maxWidth: .infinity)
            .padding(.vertical, 24)
            .listRowInsets(MacPluginLayout.rowInsets)
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}

private func macPluginOperationNotice(_ error: String) -> some View {
    macPluginNotice("plugin_operation_failed_title", detail: Text(verbatim: String(error.prefix(512)))) {}
}

private func macPluginNotice<Actions: View>(
    _ title: LocalizedStringKey,
    detail: Text,
    @ViewBuilder actions: () -> Actions
) -> some View {
    GroupBox {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
                .frame(width: 20)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline)
                detail
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            actions()
                .buttonStyle(.bordered)
                .controlSize(.small)
                .fixedSize(horizontal: true, vertical: false)
        }
        .padding(4)
    }
    .listRowInsets(MacPluginLayout.rowInsets)
    .listRowBackground(Color.clear)
    .listRowSeparator(.hidden)
}

private func macPluginRow<Details: View, Actions: View>(
    iconPath: String,
    @ViewBuilder details: () -> Details,
    @ViewBuilder actions: () -> Actions
) -> some View {
    HStack(alignment: .top, spacing: 12) {
        pluginIcon(path: iconPath)
            .font(.title3)
            .frame(width: MacPluginLayout.iconSize, height: MacPluginLayout.iconSize)
            .background(.quaternary)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .accessibilityHidden(true)
        details()
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 2)
            .frame(maxWidth: .infinity, minHeight: MacPluginLayout.iconSize, alignment: .topLeading)
        actions()
            .fixedSize(horizontal: true, vertical: false)
            .frame(height: MacPluginLayout.iconSize)
    }
    .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
    .listRowInsets(MacPluginLayout.rowInsets)
}
#endif

private struct PluginMarketplaceRow: View {
    let entry: PluginMarketplaceEntryV1
    let facade: PluginAppleFacadeV1
    let installed: Bool
    let managed: Bool
    let canInstall: Bool
    let onManage: () -> Void
    @State private var iconPath: String?

    var body: some View {
        Group {
            #if os(macOS)
            macPluginRow(iconPath: iconPath ?? "") {
                details
            } actions: {
                HStack(spacing: 8) {
                    installButton
                    repositoryMenu
                }
            }
            #else
            HStack(spacing: 12) {
                pluginIcon(path: iconPath ?? "")
                    .frame(width: 40, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .accessibilityHidden(true)
                details
                Spacer(minLength: 0)
                installButton
                repositoryMenu
            }
            .padding(.vertical, 4)
            #endif
        }
        .task(id: entry.icon) {
            iconPath = try? await facade.marketplaceIcon(pluginId: entry.id)
        }
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(entry.name)
                #if os(macOS)
                .font(.headline)
                #endif
            Text(entry.description_)
                #if os(macOS)
                .font(.subheadline)
                #else
                .font(.caption)
                #endif
                .foregroundStyle(.secondary)
            if managed {
                Text(installed ? "plugin_installed" : "plugin_marketplace_local")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var installButton: some View {
        Button {
            if managed {
                onManage()
            } else {
                Task { try? await facade.inspectMarketplace(pluginId: entry.id) }
            }
        } label: {
            Text(managed ? "plugin_marketplace_manage" : "plugin_marketplace_install")
                #if os(macOS)
                .frame(minWidth: 44)
                #endif
        }
        #if os(macOS)
        .buttonStyle(.bordered)
        .controlSize(.small)
        #else
        .buttonStyle(.borderless)
        #endif
        .disabled(!managed && !canInstall)
    }

    private var repositoryMenu: some View {
        Menu {
            if let url = URL(string: entry.repository) {
                Link("plugin_marketplace_repository", destination: url)
                    .environment(\.openURL, OpenURLAction { .systemAction($0) })
            }
        } label: {
            pluginRowMoreLabel()
        }
        .labelStyle(.iconOnly)
        .buttonStyle(.borderless)
        .accessibilityLabel(Text(verbatim: "\(String(localized: "more")): \(entry.name)"))
        #if os(macOS)
        .menuIndicator(.hidden)
        #endif
    }
}
