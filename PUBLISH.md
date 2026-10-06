# Publishing to JetBrains Marketplace

Plugin identity:

- **Name:** Pi Terminal Bridge
- **ID:** `com.piterminal.bridge` (new listing; not an update of the old pi-agent-launcher plugin)
- **Version:** `build.gradle.kts` → `version`
- **Repo:** https://github.com/JsonSong89/pi-agent-launcher

Do not publish from a local `./gradlew` run. CI on GitHub Actions is the source of truth.

## First listing (manual, once)

JetBrains requires the first upload of a new plugin ID to be done in the browser.

1. Let **Build and Release** finish on `main` (or a `v*` tag) and download the zip from the workflow artifact / GitHub Release.
2. Sign in at [plugins.jetbrains.com](https://plugins.jetbrains.com) with a JetBrains Account.
3. **Profile → Add new plugin**, upload `build/distributions/pi-terminal-bridge-<version>.zip`.
4. Fill category, license (MIT), repository URL, screenshots (panel, terminal, Send to Pi, settings). The HTML description comes from `plugin.xml`.
5. Submit for review.

Until this listing exists, `publishPlugin` will fail.

## GitHub secrets (for later versions)

| Secret | Required | Purpose |
|---|---|---|
| `JETBRAINS_PUBLISH_TOKEN` | yes | Marketplace token from [My Tokens](https://plugins.jetbrains.com/author/me/tokens) |
| `CERTIFICATE_CHAIN` | no | Plugin signing |
| `PRIVATE_KEY` | no | Plugin signing |
| `PRIVATE_KEY_PASSWORD` | no | Plugin signing |

`signPlugin` is skipped when the certificate secrets are empty.

## Shipping a new version

Marketplace rejects a zip whose version was already published.

1. Bump `version` in `build.gradle.kts`.
2. Update `<change-notes>` in `src/main/resources/META-INF/plugin.xml`.
3. Commit and push `main`.
4. Tag the same version and push the tag:

```bash
git tag v0.3.2
git push origin v0.3.2
```

5. Wait for two workflows:
   - **Build and Release** (`v*` tag) — builds the zip and creates a GitHub Release
   - **Publish Plugin** (`release` created) — `verifyPlugin` then `publishPlugin`

After Marketplace review, installed users get the update.

## Checklist

- [ ] Version bumped; `plugin.xml` change-notes match
- [ ] First listing already exists on plugins.jetbrains.com (or this *is* the first, uploaded manually)
- [ ] `JETBRAINS_PUBLISH_TOKEN` is set
- [ ] `vX.Y.Z` tag pushed; both Actions runs are green
