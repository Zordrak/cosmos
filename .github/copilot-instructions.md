# Cosmos — Copilot instructions

Follow `/AGENTS.md`. The owner's direct instructions override this file and `AGENTS.md`; if they conflict, follow the owner and update these files. Do not invent rules, safeguards or blockers.

## Project shape

- Native Kotlin Android TV / Google TV app; the screensaver is an Android `DreamService`.
- One app module; use package boundaries.
- No backend or hosted service. The device fetches images directly from public sources and caches them locally.
- No accounts, analytics, telemetry, tracking or ads.

## Behaviour defaults (owner's choices)

- Any publicly available image is acceptable by default; the licence filter is an optional setting, off by default.
- Credits are optional; Subtle is the default.
- Picture fit defaults to Fill without distorting aspect ratio; Fit, Stretch, Original and Auto are options.
- Prefer 4K images, capped around 8K.

## Working rules

- Do not commit third-party image files or secrets. Use synthetic fixtures.
- Add tests, keep build, tests and lint passing, and document non-obvious decisions.
- Commits are authored as `Mike Peachey <Zordrak@users.noreply.github.com>`.
