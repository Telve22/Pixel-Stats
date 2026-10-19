# Changelog

## 1.0.0 — Submission review fixes

- Moved statistics file loading and saving to a dedicated background executor.
- Returned load and save results to the client thread through LabyMod's public API.
- Protected new statistics from being marked as saved by an older write.
- Preserved unsaved data during account switches and queued final saves on shutdown.

Validation: 248 automated tests passed; functionality verified in-game by the addon author.
