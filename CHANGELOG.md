# Changelog

## 1.3.20

- Change default `serverSelectDelayTicks` from 12 to 8.
- Change default `matchItemId` from true to false (display-name matching by default).
- Install the debug packet/build artifacts for this release.

## 1.3.19

- Add `debugPackets`: log outgoing select/click packets and incoming slot/content packets to `latest.log`.
- Add packet debug helpers.

## 1.3.18

- Fix a client crash (`IndexOutOfBoundsException`) when opening the inventory after using a container: stale bundle slot indexes were used while rendering the enlarged bundle icon.
- Validate hovered bundle slots against the current menu.
- Reset the panel selection/highlight and the automated click queue when `containerMenu` changes.

## 1.3.17

- Add `extractionMode`: `SELECT` (vanilla selection packet) or `ROTATE` (works on servers that ignore the selection packet).
- Make `serverSelectDelayTicks` and `serverStepDelayTicks` configurable.

## 1.3.16

- Remove the impossible selection-sync handshake; always perform the extraction click after the configured delay.
- Add a single seed/retry model for extraction (no more "nothing happens").
- Add configurable delays for server play.

## 1.3.14 – 1.3.15

- Add `autoDepositInInventory` and `autoDepositInMenus`.
- Esc menu / chat no longer block auto-deposit by default; inventory/containers still do.
- Add `serverSelectDelayTicks` / `serverStepDelayTicks`.
- Improve server extraction reliability.

## 1.3.12 – 1.3.13

- Rework server-side bundle extraction: avoid taking the first item when the selection packet is ignored.
- Add fallback handling after failed extractions.

## 1.3.7 – 1.3.11

- Rebuild the side panel: group contents by bundle instead of merging every bundle into one grid.
- Add a search box with Chinese/pinyin matching.
- Use vanilla-styled slot frames from `inventory.png`, so resource packs apply.
- Add chest-like controls: left all / right half, insert all / insert one.
- Fix rapid-click issues that could pick up the bundle itself.
- Enforce matching rules for manual insertion (including bundle-in-bundle).
- Add a hard `slotClicked` guard and throttling for bundle insertions.

## 1.3.2 – 1.3.6

- Add Mod Menu integration and an in-game config screen.
- Add Chinese localization for the config screen, keybinds and mod description.
- Remove the old category bar / unused GUI code.
- Add config hot-reload.
- Add `depositMode` and picked-up-only splitting.

## 1.3.0 / 1.2.0

- Initial public versions of the continuation: auto-deposit, matching rules, side panel, Mod Menu, config.
