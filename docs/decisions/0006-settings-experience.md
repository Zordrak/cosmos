# ADR 0006: Settings experience for a TV remote

- Status: Accepted (0.5.0)
- Date: 2026-11-10

## Context

Cosmos had grown many settings. A TV is used from an armchair with a D-pad, by people who may not be technical. A long flat list or an "Advanced" toggle in each section would have been hard to scan and hard to trust.

## Decision

1. **One Home, six topics:** Preview, What to show, Slideshow, Saved pictures, When it starts, More. Each opens a short page. Every setting stays reachable; detail is revealed by navigation, not by per-section "Advanced" switches.
2. **Plain, truthful wording** with current values on the row, recommended choices labelled, and a live preview for display settings. Unknown values stay unknown (no invented idle time).
3. **Safe by default.** Destructive or expensive actions (Tidy up, Clear, size reduction) describe exactly what will change, are Cancel-first, never run from arrow keys or Back, keep favourites, and offer Undo.
4. **Capabilities degrade gracefully.** Where the TV cannot do something (limited-data detection, changing the idle timeout without permission, an unselected screensaver), the row is greyed or the Home shows a single "Needs attention" row with one fix, and the app keeps working.
5. **D-pad first.** Rows have stable IDs that own focus, so polling or refreshing never moves it. Left/right step values, Select opens a picker, Back never commits. Pickers scroll, and text can grow without clipping.
6. **Defaults suit a novice;** nothing needs changing for a good result.

## Consequences

- New settings must fit an existing topic page and have a stable row ID; viewing settings must be added to the reset list and its confirmation text.
- Layouts are verified on the real TV, including long text and larger credit sizes, not only in unit tests.
