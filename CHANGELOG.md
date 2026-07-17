# Changelog

## [0.1.2] - 2026-07-17

### Added

- Automatically refresh Codex Desktop session content after successful `/app` handoffs, with a default-enabled setting.

### Changed

- Improve terminal monitoring performance and reduce idle resource usage.

### Fixed

- Improve terminal monitor lifecycle cleanup and IntelliJ Platform API compatibility for better overall stability.

## [0.1.1] - 2026-07-13

### Added

- Support the reworked JetBrains terminal and reuse active Codex tabs.
- Show live Codex IDE context in the status bar.

### Fixed

- Enable IDE context faster and restore it after Codex `/clear`.
- Stop reusing Codex terminals after the process exits or is cancelled.

## [0.1.0] - 2026-07-08

### Added

- Initial Codex CLI Bridge plugin release.
- Launch Codex CLI from JetBrains IDEs using the toolbar icon or Tools menu.
- Provide project-scoped IDE context for Codex `/ide`, including active file, selections, selected text, and open tabs.
- Add Codex command settings and automatic `/ide on` enablement after the Codex terminal is ready.
