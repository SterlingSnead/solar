# Server sync: Navidrome music + BookOrbit audiobooks

<!-- 2026-10-21 — Added with feature/server-sync. -->

Solar can keep a copy of your self-hosted music and audiobooks on the player and update it whenever it's on Wi‑Fi. Everything plays offline afterwards.

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
2. **Test connection** shows how many book and podcast libraries your account can see.
3. **Audiobooks** — pick exactly the books you want:
   - **Search** finds audiobooks across all your book libraries.
   - **Browse: ‹library›** lists that library's audiobooks A–Z, 50 at a time (**More…** loads the next 50). Ebooks are hidden.
   - Click a book to turn it **On** (on the player) or **Off**.
   - **On this player** lists everything you've picked (works without the server), so you can un-pick from one place.
   - **Whole collection: ‹name›** is optional — turn one on to keep everything in that BookOrbit collection on the player.
4. **Podcasts** — turn on the shows you want, and set **Episodes per show** (1, 3, 5 or 10; default 3).
5. **Sync now**, or turn on **Auto-sync on Wi‑Fi**.

Books go to `Audiobooks/Author/Title/` (multi-part books as `01 - Title.mp3`, `02 - …`), with `cover.jpg`. Un-picking a book removes it from the player at the next sync.

Podcast episodes go to `Podcasts/‹Show›/‹Episode›.ext` and appear in Solar's Podcasts section like any saved episode. For each show the player keeps the newest unfinished episodes. Finishing an episode (on the player or in BookOrbit) marks it played on both sides and removes it here. A half-listened episode is kept even after newer ones arrive, and un-picking a show removes its episodes.

**Listening position syncs both ways** for audiobooks (in Solar's audiobook player) and podcasts (in Solar's podcast player):

- Listened on the player since the last sync → uploaded to BookOrbit.
- Listened in the BookOrbit app/web since the last sync → the player resumes there.
- Both → the more recent one wins.

**Formats:** whatever BookOrbit serves, unconverted — m4b, m4a, mp3, flac, ogg, opus. Solar plays all of them; for m4b/m4a it reads the embedded chapters so you can jump between them.

## Safety rules

- Sync only deletes files it downloaded itself (tracked in `Music/Navidrome/.solar-sync.json`, `Audiobooks/.bookorbit-sync.json` and `Podcasts/.bookorbit-podcasts.json`). Your own files and manual saves are never removed.
- If the server returns an empty list (outage, wrong account), nothing is deleted.
- If a picked collection was deleted on the server, the sync stops with a message instead of removing its books. If a collection you picked comes back empty, nothing is deleted.
- A wrong password stops the sync with an error before anything changes.
- Downloads go to `*.part` and are renamed when complete; interrupted downloads resume.
- Sync stops downloading when less than 256 MB is free and says "storage full".

## Known limits

- **Plex/Jellyfin** still save to their old top-level folders (not part of this change).
- Conflict resolution when both sides changed compares the player's clock with the server's. Solar sets its clock automatically when online (SolarAutoTime), so this is normally fine.

## Developer notes

| File | Role |
|---|---|
| `navidrome/NavidromeSync.java` | Subsonic `getStarred2` / `getPlaylists` / `getPlaylist` / `getAlbum` → mirror + `.m3u8`. |
| `bookorbit/BookOrbitClient.java` | `/api/v1` bearer client: login, `GET /libraries` (books type), `POST /libraries/{id}/books`, collections, audiobook manifest/assets, playback-state (server 3.0+). Re-logs in on 401. |
| `bookorbit/BookOrbitSync.java` | Mirror of the picks (pref `bookorbit_sources` = `book:12,col:7,pod:9:42`; titles in `bookorbit_titles`) + 3-way position merge against a per-book snapshot (`snapPath/snapPos/snapRev`); runs podcasts after books. |
| `bookorbit/BookOrbitPodcastSync.java` | Chosen shows → newest N unfinished episodes; `state-batch` + `PATCH /podcast-episodes/{id}/state`; finished/played both ways; 3-way position merge. |
| `bookorbit/BookOrbitPodcastLocal.java` | Bridge to Solar's `PodcastResumeStore` / `PodcastPlayedStore` (key `file:<path>`) and tag embedding. |
| `bookorbit/BookOrbitSettingsHost.java` | BookOrbit screens: server rows, Audiobooks (On this player / Search / Browse library / collections), book list with paging, Podcasts; loads off the UI thread; reuses `NavidromeSettingsHost.Actions`. |
| `sync/SyncFiles.java` | Manifest read/atomic write, folder pruning, Wi‑Fi check. |
| `MainActivity` | `serverSettingsActions()` (shared row plumbing), `buildBookOrbitSettingsUI()`, keyboard ids 25 and 34–37, `maybeAuto(...)` in `runDeferredInternetAvailabilityHooks()`, `SettingsScreens.isBookOrbit(...)` restore. |

BookOrbit position writes use the server's optimistic concurrency: `baseRevision` (409 → re-read and retry once) and `manifestRevision` (412 → skip; next sync re-downloads first). Unit tests: `NavidromeSyncTest`, `BookOrbitSyncTest`.
