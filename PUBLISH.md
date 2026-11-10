# Publishing to JetBrains Marketplace

Plugin identity:

- **Name:** Pi Terminal Bridge
- **ID:** `com.piterminal.bridge` (new listing; not an update of the old pi-agent-launcher plugin)
- **Version:** `build.gradle.kts` → `version`
- **Repo:** https://github.com/JsonSong89/pi-jetbrains-terminal-bridge

Publishing is **manual**: CI only builds the zip and attaches it to the GitHub
Release. There is no `publish.yml` workflow and no Marketplace credential in
GitHub secrets — the maintainer verifies the build, then uploads it.

## Shipping a new version

Marketplace rejects a zip whose version was already published, so the version
number is bumped exactly once per release.

1. Bump `version` in `build.gradle.kts`.
2. Update `<change-notes>` in `src/main/resources/META-INF/plugin.xml`, and the
   bilingual `<description>` if the feature set changed.
3. Sync `README.md` / `README.zh-CN.md`.
4. Land the work on `main` (see `AGENTS.md`: `dev` keeps its own history; the
   release is squashed on a feature branch and merged).
5. Tag and push:

```bash
git tag v0.4.0
git push origin main v0.4.0
```

6. **Build and Release** runs on the tag, builds
   `build/distributions/pi-terminal-bridge-<version>.zip`, and attaches it to
   the GitHub Release. The `main` push run is cancelled by the shared
   per-sha concurrency group, so only one build happens.
7. Verify that zip locally (install into a sandbox IDE, exercise the panel).
8. Sign in at [plugins.jetbrains.com](https://plugins.jetbrains.com) and upload
   the verified zip to the existing listing: **your plugin → Upload update**.
   The HTML description and change notes come from `plugin.xml`.
9. Submit for review. After approval, installed users get the update.

## Signing (optional)

Signing is not required for an upload. If a signed build is wanted, run it
locally with the certificate in the environment:

```bash
./gradlew signPlugin        # CERTIFICATE_CHAIN / PRIVATE_KEY / PRIVATE_KEY_PASSWORD
./gradlew publishPlugin     # PUBLISH_TOKEN
```

`publishPlugin` uploads straight to Marketplace and is only a shortcut for the
browser upload — it is not part of CI.

## Checklist

- [ ] Version bumped; `plugin.xml` change-notes match
- [ ] README (both languages) reflects the release
- [ ] `vX.Y.Z` tag pushed; **Build and Release** green; zip downloaded
- [ ] Zip verified in a sandbox IDE
- [ ] Zip uploaded manually to plugins.jetbrains.com and submitted
