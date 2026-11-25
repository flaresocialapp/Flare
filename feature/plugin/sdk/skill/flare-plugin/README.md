# Flare Plugin SDK and skill

Use this directory as the `flare-plugin` skill to build a website adapter with an
AI coding agent, or read the same references directly. The SDK describes Plugin
API v1 and v2. The runtime targets Android, JVM Desktop, iOS and native macOS.
Offline settings pages and Plugin tasks have UI on Android, iOS and native macOS.

## Match Flare and the SDK

Choose the Flare build that will run the plugin, then select the SDK and skill
for that build. Check the feature's [Host API requirements](references/manifest.md#host-api-versions).

| Target build | SDK and skill |
| --- | --- |
| [Flare 1.8.0](https://github.com/flaresocialapp/Flare/releases/tag/1.8.0) | Host API v1. Pin `flare-plugin-sdk@0.1.0-beta.10` and use the [skill from that SDK tag](https://github.com/flaresocialapp/Flare/tree/plugin-sdk-v0.1.0-beta.10/feature/plugin/sdk/skill/flare-plugin). |
| Flare build with Host API v2 before QR login support | Pin [`flare-plugin-sdk@0.1.0-beta.11`](https://www.npmjs.com/package/flare-plugin-sdk/v/0.1.0-beta.11) and use the [skill from that SDK tag](https://github.com/flaresocialapp/Flare/tree/plugin-sdk-v0.1.0-beta.11/feature/plugin/sdk/skill/flare-plugin). |
| Flare build with QR login or configurable post detail tabs | Pin [`flare-plugin-sdk@0.1.0-beta.12`](https://www.npmjs.com/package/flare-plugin-sdk/v/0.1.0-beta.12) and use the [skill from that SDK tag](https://github.com/flaresocialapp/Flare/tree/plugin-sdk-v0.1.0-beta.12/feature/plugin/sdk/skill/flare-plugin). |
| Flare build with timeline processors, manga, or the other features below | Pin [`flare-plugin-sdk@0.1.0-beta.13`](https://www.npmjs.com/package/flare-plugin-sdk/v/0.1.0-beta.13) and use the [skill from that SDK tag](https://github.com/flaresocialapp/Flare/tree/plugin-sdk-v0.1.0-beta.13/feature/plugin/sdk/skill/flare-plugin). |

Host API v2 is included on `master` starting with the [V2 merge commit](https://github.com/flaresocialapp/Flare/commit/85c82a46d7738cb4010bda6a9bd09e4e50bb6e68).
SDK `0.1.0-beta.11` introduced the V2 schema and the WebView settings module at
`flare-plugin-sdk/settings`. The settings page runs in a V2 Host with
`apiVersion: 2` and `settings.bridgeVersion: 1` in the manifest. See the
[settings page contract](references/storage-and-settings.md#offline-settings-page).

SDK `0.1.0-beta.12` adds the QR login and configurable post detail tab contracts.
Each feature requires a Flare build that includes its host implementation.
[QR login](https://github.com/flaresocialapp/Flare/pull/2843) · [Post detail tabs](https://github.com/flaresocialapp/Flare/pull/2853)

SDK `0.1.0-beta.13` describes these features for Host `apiVersion: 2`. Choose
a Flare build that includes the implementation for each feature you use.

| Feature | Flare build must include |
| --- | --- |
| Timeline post processing and widgets | [Timeline processor merge](https://github.com/flaresocialapp/Flare/commit/dcbc5df17dea5213c8dbf0356282075f74ad4d37) |
| Manga detail and reader, native HTML extraction, recommended origins and fixed-site selection, standalone guest origin, session challenge recovery, and `post.mutate` without `post.detail` | [Manga and guest plugin merge](https://github.com/flaresocialapp/Flare/commit/39cc9b8ee622845c9354d58508cd68d0dca08137) |

`npm view flare-plugin-sdk dist-tags --json` shows the rolling `next` channel.
Pin the SDK version for reproducible builds. For features newer than the
published SDK, use the SDK and skill from the tag or commit used to build Flare.

### Use the SDK from a Flare checkout

With Node 22 or newer, run these commands from the Flare revision used for your
target build. They create a social starter in a sibling directory and install
the SDK from that checkout.

```sh
flare_sdk_dir="$PWD/feature/plugin/sdk"
node "$flare_sdk_dir/bin/cli.mjs" init ../my-plugin
cd ../my-plugin
mkdir -p .sdk
flare_sdk_archive="$(npm pack "$flare_sdk_dir" --pack-destination .sdk --silent)"
npm install --save-dev "./.sdk/$flare_sdk_archive"
```

For an existing plugin, set `flare_sdk_dir` to the matching SDK directory and
run the last three commands from the plugin root. In `manifest.json`, set
`$schema` to `./node_modules/flare-plugin-sdk/skill/flare-plugin/manifest.schema.json`.
Record the Flare commit and keep the tarball available at the same project path
for other developers running `npm ci`. Keep `.sdk/` outside the runtime package.
For an existing V1 plugin, follow the [V1 migration steps](ABI.md#migrate-an-api-v1-plugin).

## Install the skill

This skill installs into your AI coding agent. The website adapter it helps you
create is installed separately into Flare; see [Start a plugin repository](#start-a-plugin-repository).
The source package is `feature/plugin/sdk/skill/flare-plugin/`. Copy that **entire
directory**, including references, types, assets, and examples. Copying only
`SKILL.md` loses the SDK. The parent `skill/` directory is just a container.

The package follows the [Agent Skills format](https://agentskills.io/specification):
the directory and frontmatter name are both `flare-plugin`, the entrypoint is
`SKILL.md`, and supporting files use relative links. It has no agent-specific
runtime dependency. Optional UI metadata such as `agents/openai.yaml` is not
needed to use it. Your agent still needs file access and the tools required by
your plugin project; Node is needed for the documented syntax and package checks.

### Install from GitHub with Codex

The [development source](https://github.com/flaresocialapp/Flare/tree/master/feature/plugin/sdk/skill/flare-plugin)
is on `master` in `flaresocialapp/Flare`. For a specific Flare build, use its
commit SHA or the corresponding SDK tag in place of `master`.

Ask Codex's built-in installer:

```text
$skill-installer Install the flare-plugin skill from repository flaresocialapp/Flare,
ref master, path feature/plugin/sdk/skill/flare-plugin.
Copy the complete skill directory, including its supporting files.
```

The installer's Python CLI takes
`--repo flaresocialapp/Flare --ref master --path feature/plugin/sdk/skill/flare-plugin`.
Record the resolved commit SHA when you need to reproduce an installation. If a
skill already exists, follow [Update or remove](#update-or-remove) first.

### Install from a local checkout

Clone the repository and enter its root. For a specific target build, check
out its matching revision before copying the skill.

```sh
git clone https://github.com/flaresocialapp/Flare.git
cd Flare
```

For an existing checkout, use your normal Git workflow to select the intended
revision before copying. No Flare build is needed to install the skill.

From the Flare repository root, choose **one** of the following first-install
commands. The existence check prevents copying into an already-installed skill.

Codex, for all local projects:

```sh
mkdir -p "$HOME/.agents/skills"
if [ ! -e "$HOME/.agents/skills/flare-plugin" ] && [ ! -L "$HOME/.agents/skills/flare-plugin" ]; then
  cp -R feature/plugin/sdk/skill/flare-plugin "$HOME/.agents/skills/flare-plugin"
else
  echo "flare-plugin already exists; follow Update or remove before replacing it."
fi
```

Claude Code, for all local projects:

```sh
mkdir -p "$HOME/.claude/skills"
if [ ! -e "$HOME/.claude/skills/flare-plugin" ] && [ ! -L "$HOME/.claude/skills/flare-plugin" ]; then
  cp -R feature/plugin/sdk/skill/flare-plugin "$HOME/.claude/skills/flare-plugin"
else
  echo "flare-plugin already exists; follow Update or remove before replacing it."
fi
```

For a project-only installation, copy the same `flare-plugin/` folder into the
**plugin project's** `.agents/skills/` (Codex) or `.claude/skills/` (Claude Code).
Use the plugin project's path, even when your source checkout is elsewhere.
Choose one scope per agent to avoid loading duplicate copies. The expected
layout is `<skills-directory>/flare-plugin/SKILL.md`, with resources beside it.

### Verify discovery and invoke

1. Check the installed folder contains `SKILL.md`, `flare-plugin.d.ts`,
   `references/manifest.md`, `assets/github-template/plugin.js`, and
   `examples/pixelfed/plugin.js`.
2. In Codex CLI or its IDE extension, use `/skills` or type `$` and select
   `flare-plugin`. If it is missing, restart Codex and check the installation
   location. In Claude Code, type `/flare-plugin`; if a newly created skills
   directory is not discovered, run `/reload-skills` or start a new session.
3. Run a read-only check before creating a plugin:

   ```text
   Use the flare-plugin skill. Read its bundled references and template.
   Explain the required files for a GitHub plugin and the available login methods,
   and identify the template's illustrative endpoints. Do not create or publish files.
   ```

   Confirm the agent reads resources from the installed directory and identifies
   `/api/me` and `/api/posts` as illustrative. This checks discovery and access,
   not the correctness of a future website integration.

For actual work, prefix your request with `$flare-plugin` in Codex CLI/IDE or
`/flare-plugin` in Claude Code. Natural-language requests can also trigger the
skill when they match its description. See the current official
[Codex instructions](https://learn.chatgpt.com/docs/build-skills) and
[Claude Code instructions](https://code.claude.com/docs/en/skills) for discovery
locations, invocation, and client-specific behavior. These local installations
do not automatically install the skill in a remote or cloud agent environment.

### Update or remove

To update, select the desired source revision and review its changes. Back up the
existing installed `flare-plugin` folder **outside all skill discovery directories**,
preserving any local edits, then replace it with the complete new folder. With
Codex's installer, move the old folder out before reinstalling; it does not
overwrite an existing installation. Avoid merging copies: removed reference
files would otherwise remain. Repeat the discovery check after replacement.

To remove, delete or move out only the installed `flare-plugin` folder from the
location you chose. Restart the agent if its menu still shows it. Updating or
removing this skill does not change generated plugin repositories or plugins
already installed in Flare.

### Use without installing

An agent with access to this checkout can follow an explicit request to read
`feature/plugin/sdk/skill/flare-plugin/SKILL.md` and its referenced files. This
works for a single task; it does not register a discoverable skill. Keep the
entire folder accessible rather than pasting only the entrypoint into chat.

## Use with an AI coding agent

Start with a request such as:

> Use the flare-plugin skill to create a plugin for [website / HTTPS API origin].
> Implement [features] using [login method]. Here are the API documentation and
> redacted example responses: [sources]. First map these features to Flare
> capabilities and list any missing API information. Then create a GitHub-ready
> repository, validate the available paths, and explain installation and any
> remaining integration work.

For an existing plugin:

> Use the flare-plugin skill to add [feature] to this repository. Preserve its
> plugin and platform IDs and existing features. Inspect the current manifest,
> implementation, and website API before editing. Update the capability mapping,
> documentation, and relevant checks, and explain the version/schema implications.

Provide API docs, authenticated-profile and content responses, paging examples,
and authentication details relevant to the requested feature. Remove real tokens,
passwords, Cookies, and private user content. Tell the agent which errors or edge
cases matter. It should identify gaps rather than invent website endpoints.

## Start a plugin repository

1. With Node 22 or newer, create the social starter with the SDK for your
   target Flare build. This example uses `0.1.0-beta.13`.

   ```sh
   npx flare-plugin-sdk@0.1.0-beta.13 init my-plugin
   cd my-plugin
   npm install
   ```

   For earlier Flare builds, choose the SDK in the [version guide](#match-flare-and-the-sdk).
   For features newer than the published SDK, [install from the matching checkout](#use-the-sdk-from-a-flare-checkout).
   Both workflows generate a TS project with unique plugin/platform IDs and a
   prebuilt root `plugin.js`. Edit `src/plugin.ts` and use `npm run dev` while
   developing. `npm run build` checks types before rebuilding the root script.
   For manual copies of [assets/github-template](assets/github-template/README.md),
   replace the example IDs and names, rename `_gitignore` to `.gitignore`, and
   install the SDK for the target build. The example API needs adaptation.
2. Keep the generated IDs stable, replace the placeholder icon, and adapt
   authentication and data mapping to your website. See [manifest](references/manifest.md)
   and [authentication](references/authentication.md).
3. Implement the required capability methods and map the responses into
   [Wire data](references/wire-data.md). The starter uses token import and a
   read-only timeline whose posts open their website URLs.
4. Use [Desktop plugin development](references/testing.md#develop-locally-in-desktop)
   to start the local directory, test an account, and apply saved edits with
   **Reload plugin** after automatic validation. Follow the syntax, behavior and
   platform checks. Stop development before installing/updating the same plugin.
5. Prefer GitHub distribution: run `npm run build`, commit the TS source, build
   configuration, lockfile and built `plugin.js`, then push a stable repository
   snapshot tag. Each plugin's `manifest.version` is independent. In Flare's plugin
   management, enter `https://github.com/OWNER/REPO` and click **Install**. Review
   source and permissions, then confirm to use the plugin immediately. Follow the
   plugin's README to select the platform, origin, and login method.

The generated [project README](assets/github-template/README.md) contains the full
adaptation, local debugging, Git setup/tagging, GitHub installation and update path.
See [publishing](references/publishing.md) for exact tag rules, updates, and
[optional local `.fpp` installation](references/publishing.md#optional-local-installation).
Flare reads the selected directory in the latest stable repository snapshot. Add
`?path=/plugins/example` for a subdirectory; omit it for the repository root. A GitHub Release or `.fpp` upload is unnecessary.

## Reference and example index

| Need | Reference |
| --- | --- |
| Agent workflow | [SKILL.md](SKILL.md) |
| Host and SDK versions, existing V1 plugins | [Version pairing](#match-flare-and-the-sdk), [V1 migration](ABI.md#migrate-an-api-v1-plugin) |
| Boundary contract and registration | [ABI.md](ABI.md) |
| Exact JavaScript signatures and field comments | [flare-plugin.d.ts](flare-plugin.d.ts) |
| Manifest fields, defaults, enums, and validation | [Manifest](references/manifest.md) |
| JSON editor completion and static manifest checks | [JSON Schema](manifest.schema.json), [editor setup](references/manifest.md#json-schema-and-editor-support) |
| Capabilities, minimum methods, and UI requirements | [Capabilities](references/capabilities.md) |
| API v2 post/quote navigation and temporary reply lists | [Page contract](references/capabilities.md#api-v2-temporary-timeline-pages), [recipe](references/recipes.md#temporary-timeline-pages-api-v2), [offline showcase](examples/timeline-showcase/README.md) |
| API v2 timeline post processing and widgets | [Processor manifest](references/manifest.md#timeline-processor), [ABI](ABI.md#timeline-post-processing), [widget example](examples/timeline-processor/README.md) |
| API v2 manga detail and chapter reader | [Manga recipe](references/recipes.md#manga), [paging and image resolution](references/wire-data.md#manga-pagination-and-parallel-image-resolution), [reader example](examples/manga-reader/README.md) |
| API v2 native HTML parsing | [HTML extraction and entity decoding](references/runtime-http.md#html-extraction-and-entity-decoding-api-v2) |
| API v2 standalone guest origin and fixed-site selection | [Guest access](references/recipes.md#guest-access), [fixed-site selection](references/authentication.md#recommended-origins-and-single-site-plugins-api-v2) |
| API v2 interactive session recovery for saved accounts | [Session challenge recovery](references/authentication.md#session-challenge-recovery-api-v2) |
| API v2 actions without post detail | [Post and semantic actions](references/recipes.md#post-and-semantic-actions) |
| Platform engines, async completion, HTTP, permissions, credentials, and state | [Runtime and HTTP](references/runtime-http.md) |
| Text translation, plugin storage revisions and offline settings SDK | [Storage, translation and settings](references/storage-and-settings.md) |
| Detection and login methods, including QR | [Authentication](references/authentication.md) |
| DTO mapping, pagination, media, and opaque tokens | [Wire data](references/wire-data.md) |
| Capability implementations, guest, localization, and Deep Links | [Recipes](references/recipes.md) |
| Errors, resource limits, and recovery | [Troubleshooting](references/troubleshooting.md) |
| Author checks, Host tests, and acceptance evidence | [Testing](references/testing.md) |
| GitHub publication, upgrades, and optional local installation | [Publishing](references/publishing.md) |
| Complete website adapter | [Pixelfed](examples/pixelfed/README.md) |
| Complete offline translation/settings interface example | [Translation/settings demo](examples/translation-settings/README.md) |

Rules have one canonical reference; the skill links to it when needed. Recipe
fragments explain adaptation points and are not separate installable plugins.

For operations on existing built-in or plugin-provided accounts, including optional daily foreground
actions, read [platform extensions](references/platform-extensions.md) and the
[read-only Weibo example](examples/platform-extension/README.md).
