# ADR 0005: Saved pictures are user data; ordering, protection and the saving policy

- Status: Accepted (0.5.0)
- Date: 2026-11-10

Revises the storage and refresh parts of [ADR 0004](0004-personal-use-defaults.md).

## Context

In 0.4.0 the picture library lived in the app's cache directory and was capped at 150 pictures. Android treats "Clear cache" as a safe action and may reclaim cache on its own when storage is low, which is exactly when losing a curated library hurts most. With favourites, blocked pictures and history, the library had become user data. The random shuffle also repeated pictures while others went unseen, and Wi-Fi can be metered, so "unmetered network" is not the same as "free to download".

## Decision

1. **Storage location.** The library (`images/`, `catalogue.json`), `userstate.json` (favourites, blocks, history, order state) and the Undo area (`trash/`) are in `filesDir`. Only streaming scratch is in `cacheDir/stream`. Cosmos never writes the library under `cacheDir`. 0.4.0 libraries are migrated by renaming files (images first, catalogue last, resumable); an existing `filesDir` catalogue is authoritative. All JSON uses synced atomic writes with a last-good backup; unreadable data is never treated as empty and never cleaned up.
2. **Size.** There is no picture-count cap. The limit is the user's chosen size (Automatic by default), clamped to what is safely available. The free-space reserve (at least the larger of 3 GB and 15% of storage) and a 90 MiB write headroom floor stay, because Android no longer reclaims the library for us.
3. **Identity.** A source key identifies provenance; SHA-256 identifies identical bytes. Identical bytes from two sources share one file and keep both provenance entries as aliases. Counting, ordering and favouriting use the hash once.
4. **Protection.** Favourites are never removed automatically (eviction, shrink, Tidy, Clear, reset, de-duplication, filter changes). Only an explicit, confirmed Remove or Block removes one. Favourites may exceed the chosen limit; Cosmos then stops saving and says so, rather than deleting them or pretending the limit holds. Pictures outside the current view are "kept for later" and are the first reclaimed when room is needed; changing the view never flushes them.
5. **Reversible removals.** Tidy up, Clear and size reductions are staged in `trash/` with an immutable journal and a phase file, confirmed with a Cancel-first dialog, and undoable for 15 seconds. Recovery after a crash at any step either rolls back or completes. Refreshes cannot re-add a stashed or just-removed picture.
6. **Block.** Blocking is only from the saved-pictures browser and is undone from "Blocked pictures". A block records known source keys and the hash. The Iwamoto multi-epoch comet composite is blocked by a seed record, and a content filter rejects charts and composites.
7. **Ordering.** *Fair shuffle* (default) is lowest-score-first weighted service: each showing adds a cost (Less 8, Normal 4, More 2, halved for favourites) to a persisted score, the lowest score is shown next, and new pictures are served after every four ordinary picks. *Pure random* ignores state. *In order* sorts by source, title and hash with a persisted cursor. History advances only when a frame was actually posted. Streaming uses the same engine, keyed by lead, with failure cooldowns.
8. **Saving policy.** One `download_mode` preference: Automatically, Not on limited data, Paused. Network type never blocks the app: if limited data cannot be detected the option is disabled with a reason and saving continues; if it cannot be determined right now, saving continues. Force is urgency, not permission: only the explicit "Look for new pictures now" overrides Paused or limited data. Local maintenance runs regardless; streaming is independent.
9. **Idle and scheduling.** The TV's own inactivity timer decides when the screensaver starts. Cosmos can set `SCREEN_OFF_TIMEOUT` after a user grant of `WRITE_SETTINGS`, verifying by read-back. No background scheduler is assumed to run while idle.
10. **Startup.** The dream and preview start black and fade the first real picture in. There is no placeholder or logo flash. A dim status line appears only after 2.5 seconds of genuine waiting.

## Consequences

- Android's "Clear cache" is safe. "Clear data" or uninstall removes pictures, favourites and blocks, which is standard behaviour.
- Cosmos is responsible for its own storage discipline; a stricter limit or reserve is a settings choice, not an OS side effect.
- The ordering state and Undo journal are small persistent formats; changing them requires versioned migration.
