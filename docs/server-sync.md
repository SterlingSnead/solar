# Server sync: Navidrome music + BookOrbit audiobooks

<!-- 2026-10-21 — Added with feature/server-sync. -->

Solar can keep a copy of your self-hosted music and audiobooks on the player and update it whenever it's on Wi‑Fi. Everything plays offline afterwards, in Solar and in Rockbox.

## Navidrome

**Settings → Library → Navidrome server**

| Row | What it does |
|---|---|
| Server URL / Username / Password | Your Navidrome login (unchanged from before). |
| Sync starred music | On by default. Starred songs, plus every song on starred albums. |
| Sync playlists tagged | Default `#sync`. Any playlist whose name *or* comment contains the tag is copied, and written as `Music/Playlists/<name>.m3u8` (tag removed from the name). Leave blank to skip playlists. |
| Auto-sync on Wi‑Fi | Off by default. Runs at most once every 30 minutes when Wi‑Fi connects. |
| Sync now | Runs immediately; the row shows progress, then the last result. |

Files land in `Music/Navidrome/Artist/Album/Title.ext`, the original file from the server (no transcoding). Un-starring a song or dropping it from a tagged playlist removes it from the player on the next sync.

> **Changed:** the "Save to library" action now saves to `Music/Navidrome/` instead of the top-level `Navidrome/` folder, because the Library scan only reads `Music/`. The first sync moves any matching old saves over instead of downloading them again.

## BookOrbit

**Settings → Library → BookOrbit server**

1. Enter the server URL (e.g. `192.168.1.20:6060`), username and password. Use the LAN address; plain `http://` on your home network is the most reliable on the Y1's old Android version.
2. **Test connection** shows how many libraries and collections your account can see.
3. **Sync sources** lists every book library (`Library: …`) and collection (`Collection: …`). Turn on as many as you like; a book in two of them is copied once. Podcast libraries aren't listed.
4. **Sync now**, or turn on **Auto-sync on Wi‑Fi**.

Books go to `Audiobooks/Author/Title/` (multi-part books as `01 - Title.mp3`, `02 - …`), with `cover.jpg`. Ebooks in a chosen library are skipped. A book is removed from the player once it's no longer in *any* chosen source (or you turn that source off).

**Tip:** a whole library can be large. A collection (e.g. "On the Y1") is the easy way to choose exactly what fits on the card.

**Formats:** whatever BookOrbit serves, unconverted — m4b, m4a, mp3, flac, ogg, opus. Solar plays all of them; for m4b/m4a it reads the embedded chapters so you can jump between them.

**Listening position syncs both ways** for books played in Solar's audiobook player:

- Listened on the player since the last sync → uploaded to BookOrbit.
- Listened in the BookOrbit app/web since the last sync → the player resumes there.
- Both → the more recent one wins.

## Safety rules

- Sync only deletes files it downloaded itself (tracked in `Music/Navidrome/.solar-sync.json` and `Audiobooks/.bookorbit-sync.json`). Your own files and manual saves are never removed.
- If the server returns an empty list (outage, wrong account), nothing is deleted.
- If a chosen library or collection was deleted on the server, the sync stops with a message instead of removing that source's books.
- Downloads go to `*.part` and are renamed when complete; interrupted downloads resume.
- Sync stops downloading when less than 256 MB is free and says "storage full".

## Known limits

- **Rockbox bookmarks don't sync yet.** Rockbox plays the synced files and playlists, but its `.bmark` positions aren't read or written. Use Solar's audiobook player for position sync.
- **Plex/Jellyfin** still save to their old top-level folders (not part of this change).
- Conflict resolution when both sides changed compares the player's clock with the server's. Solar sets its clock automatically when online (SolarAutoTime), so this is normally fine.

## Developer notes

| File | Role |
|---|---|
| `navidrome/NavidromeSync.java` | Subsonic `getStarred2` / `getPlaylists` / `getPlaylist` / `getAlbum` → mirror + `.m3u8`. |
| `bookorbit/BookOrbitClient.java` | `/api/v1` bearer client: login, `GET /libraries` (books type), `POST /libraries/{id}/books`, collections, audiobook manifest/assets, playback-state (server 3.0+). Re-logs in on 401. |
| `bookorbit/BookOrbitSync.java` | Mirror of the union of chosen sources (pref `bookorbit_sources` = `lib:3,col:7`) + 3-way position merge against a per-book snapshot (`snapPath/snapPos/snapRev`). |
| `bookorbit/BookOrbitSettingsHost.java` | Settings rows + the Sync sources picker (fetched off the UI thread); reuses `NavidromeSettingsHost.Actions`. |
| `sync/SyncFiles.java` | Manifest read/atomic write, folder pruning, Wi‑Fi check. |
| `MainActivity` | `serverSettingsActions()` (shared row plumbing), `buildBookOrbitSettingsUI()`, keyboard ids 25 and 34–36, `maybeAuto(...)` in `runDeferredInternetAvailabilityHooks()`, `SettingsScreens.BOOKORBIT_SOURCES` restore. |

BookOrbit position writes use the server's optimistic concurrency: `baseRevision` (409 → re-read and retry once) and `manifestRevision` (412 → skip; next sync re-downloads first). Unit tests: `NavidromeSyncTest`, `BookOrbitSyncTest`.
