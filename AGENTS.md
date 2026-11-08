# Agent instructions

## Authority

The owner's direct instructions override everything in this file and in `.github/copilot-instructions.md`. If an instruction from the owner conflicts with these files, the files are wrong: follow the owner and update the files in the same change. Do not invent rules, safeguards, gates or blockers that the owner has not asked for.

## What Cosmos is

A native Kotlin Android TV / Google TV screensaver (`DreamService`) for the owner's personal TV. It shows high-quality space images fetched directly from public sources and cached on the device. It is a personal project: favour delivering the requested behaviour over process.

## Constraints that remain

- Native Kotlin Android; the screensaver is an Android `DreamService`.
- No backend, hosted catalogue, CDN, proxy or other recurring infrastructure cost.
- No accounts, analytics, telemetry, tracking or ads.
- Images and metadata are cached locally on the device.
- Do not commit third-party image files or secrets (tokens, keys) to the repository. Test fixtures are synthetic.
- Keep it simple: one app module, package boundaries, no speculative abstraction.
- Add tests for behaviour that matters and keep the build, unit tests and lint passing.

## Things that are owner choices, not rules

Image sources, licence filtering, credit display, picture fit (Fill, Fit, Stretch, Original, Auto), quality limits and storage limits are settings and defaults chosen by the owner. By default any publicly available image is acceptable; the licence filter is an optional setting that is off by default. Credits are optional and subtle by default.

## Delivery

- Debug caches and stored data may be wiped on upgrade.
- Commits are authored and committed as `Mike Peachey <Zordrak@users.noreply.github.com>`.
- Keep `docs/` accurate when behaviour changes.
