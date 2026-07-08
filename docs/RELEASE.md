# Release

Codex CLI Bridge publishes one JetBrains plugin ZIP from an Ubuntu GitHub Actions
runner. There is no OS or architecture matrix because the plugin artifact is
platform-independent.

## Release Steps

1. Update `version` in `build.gradle.kts`.
2. Add a matching top section to `CHANGELOG.md`:

   ```markdown
   ## [X.Y.Z] - YYYY-MM-DD
   ```

3. Commit the release with this exact subject:

   ```text
   chore: release codex-cli-bridge vX.Y.Z
   ```

4. Push the commit to `master`.

The `Release` workflow validates the commit subject, Gradle version, and
changelog section. It then builds the plugin with:

```bash
gradle --no-daemon clean check buildPlugin verifyPluginStructure
```

The plugin What's New content is generated from the top `CHANGELOG.md` version
section during the Gradle build and injected into the packaged plugin metadata.

The GitHub release receives:

- `codex-cli-bridge-vX.Y.Z.zip`
- `codex-cli-bridge-vX.Y.Z.zip.sha256`
