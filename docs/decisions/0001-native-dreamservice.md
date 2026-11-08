# ADR 0001: Native Android DreamService

- Status: Accepted
- Date: 2026-11-08

## Context

Cosmos must behave as a selectable system screensaver on Android TV and Google TV, installable through the ordinary TV application workflow. A custom foreground activity or launcher-only experience would not meet this requirement.

A third-party screensaver application has been installed on the target Sony Google TV and appears in the system's selectable screensaver list. This establishes that the target platform supports third-party screensaver integration through normal installation.

## Decision

Implement the screensaver using Android's native `DreamService` API, with the required manifest declaration, permission, and service metadata. The app must be selectable and activatable through the TV's normal settings UI.

ADB may be used for developer diagnostics and deployment, but must not be a prerequisite for end-user installation or selection.

## Consequences

- The screensaver lifecycle is governed by Android's dream lifecycle and must be tested on physical TV hardware.
- The rendering path must tolerate start, stop, interruption, wake, and repeated activation.
- The first milestone is a synthetic-image proof of concept before any image-provider work.
- Emulator success is insufficient evidence of target-device compatibility.
