# Changelog

## [Unreleased]

### Added

- Show the live Codex IDE context in the project status bar with selection preview, active file, open tabs, and last-sent time.

### Fixed

- Stop Codex session monitoring and hide the context widget when the terminal process exits.

## [0.1.0] - 2026-07-08

### Added

- Initial Codex CLI Bridge plugin release.
- Launch Codex CLI from JetBrains IDEs using the toolbar icon or Tools menu.
- Provide project-scoped IDE context for Codex `/ide`, including active file, selections, selected text, and open tabs.
- Add Codex command settings and automatic `/ide on` enablement after the Codex terminal is ready.
