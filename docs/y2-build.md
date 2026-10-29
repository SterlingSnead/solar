# Building the Y2 ROM (this fork)

<!-- 2026-10-29 — Fork-only: Solar + Navidrome/BookOrbit sync, no Rockbox. -->

`rom_y2.zip` is the normal Solar Y2 ROM (Rockbox removed, as upstream ships it) with this fork's
Navidrome and BookOrbit sync (see `server-sync.md`). Automatic updates are **off** in this build,
so the player can't replace it with upstream Solar, which lacks the sync features.

## Build

1. Push this branch to your fork's `main`, or open **Actions → Build Y2 ROM (fork) → Run workflow**.
   (GitHub turns Actions off on new forks; enable them once on the Actions tab.)
2. About 30–60 minutes later the files appear under the fork's **Releases** as *Solar Y2 (sync)*:
   `rom_y2.zip` (the full ROM) and `solar-sync.apk` (the same Solar build on its own).

No secrets are needed. The build signs with the public AOSP test platform key, which the Y2's stock
firmware uses. Upstream's own workflows (release, OTA, nightly Y2) only run on
`thesolarproject/solar`, so they stay quiet on the fork.

## Flash

Flash `rom_y2.zip` like any Solar Y2 release: Innioasis Updater's Y2 flow, or SP Flash Tool with the
included scatter (see `solar-rom/README.md`). **Flashing can erase the player, so back it up first.**

After that, set up the servers on the player under **Settings → Library → Navidrome server** and
**BookOrbit server**.
