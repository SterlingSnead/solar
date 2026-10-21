package com.solar.launcher.navidrome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.solar.launcher.DeviceFeatures;
import com.solar.launcher.PlaylistManager;
import com.solar.launcher.net.SolarHttp;
import com.solar.launcher.sync.SyncFiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 2026-10-21 — Navidrome → device mirror.
 * Layman: keeps your starred music and "#sync"-tagged playlists copied onto the player, and
 * removes what you un-star — but only files this sync downloaded itself.
 * Tech: Subsonic getStarred2 + getPlaylists/getPlaylist → NavidromeDownloader.destinationFor paths,
 * a JSON manifest beside the files, relative .m3u8 playlists that both Solar and Rockbox read.
 */
public final class NavidromeSync {

    public interface Listener {
        void onStatus(String text);
        void onDone(Result result);
        void onError(String message);
    }

    /** 2026-10-21 — Counts shown in the Sync row after a run. */
    public static final class Result {
        public int downloaded;
        public int kept;
        public int removed;
        public int failed;
        public int playlists;
        public boolean storageFull;

        public String summary() {
            String s = String.format(Locale.US, "%d new · %d kept · %d removed", downloaded, kept, removed);
            if (failed > 0) s += " · " + failed + " failed";
            if (storageFull) s += " · storage full";
            return s;
        }
    }

    // 2026-10-21 — Pref keys; layman: what to sync and when; tech: Solar SharedPreferences.
    public static final String PREF_STARRED = "navidrome_sync_starred";
    public static final String PREF_TAG = "navidrome_sync_tag";
    public static final String PREF_AUTO = "navidrome_sync_auto";
    public static final String PREF_LAST_MS = "navidrome_sync_last_ms";
    public static final String PREF_STATUS = "navidrome_sync_status";
    public static final String DEFAULT_TAG = "#sync";

    static final String MANIFEST_NAME = ".solar-sync.json";
    /** ponytail: fixed headroom; upgrade path is per-song size from the Subsonic "size" field. */
    static final long MIN_FREE_BYTES = 256L * 1024 * 1024;
    /** ponytail: Wi-Fi flaps fire many connect events; one auto run per window is plenty. */
    static final long AUTO_INTERVAL_MS = 30L * 60 * 1000;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean running = new AtomicBoolean(false);
    /** 2026-10-21 — Live progress text for the Sync row; written by the sync thread only. */
    private static volatile String liveStatus = "";

    private NavidromeSync() {}

    public static boolean isRunning() {
        return running.get();
    }

    /** 2026-10-21 — Sync row preview: live progress while running, else the last run's summary. */
    public static String statusLine(SharedPreferences prefs) {
        if (running.get()) return liveStatus.length() > 0 ? liveStatus : "Syncing…";
        return prefs != null ? prefs.getString(PREF_STATUS, "") : "";
    }

    /**
     * 2026-10-21 — Wi-Fi came up: sync if the user turned auto-sync on and it's been a while.
     * Tech: called from MainActivity.runDeferredInternetAvailabilityHooks (input-idle, online).
     */
    public static void maybeAuto(Context ctx, SharedPreferences prefs, final Runnable onChanged) {
        if (ctx == null || prefs == null || !prefs.getBoolean(PREF_AUTO, false)) return;
        if (!NavidromePrefs.isConfigured(prefs) || !SyncFiles.onWifi(ctx)) return;
        long last = prefs.getLong(PREF_LAST_MS, 0L);
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return;
        // 2026-10-21 — Rescan the library only when files actually changed (keeps the UI calm).
        runAsync(ctx, prefs, new Listener() {
            @Override public void onStatus(String text) {}
            @Override public void onDone(Result result) {
                if (onChanged != null && result.downloaded + result.removed > 0) onChanged.run();
            }
            @Override public void onError(String message) {}
        });
    }

    /** 2026-10-21 — Start a sync in the background; listener callbacks arrive on the main thread. */
    public static void runAsync(final Context ctx, final SharedPreferences prefs, final Listener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (!running.compareAndSet(false, true)) {
            post(main, listener, null, null, "Sync already running");
            return;
        }
        final Context app = ctx.getApplicationContext();
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    NavidromePrefs.load(NavidromeClient.getInstance(), prefs);
                    Result r = runBlocking(app, prefs, new Listener() {
                        @Override public void onStatus(String text) {
                            liveStatus = text;
                            post(main, listener, text, null, null);
                        }
                        @Override public void onDone(Result result) {}
                        @Override public void onError(String message) {}
                    });
                    prefs.edit().putLong(PREF_LAST_MS, System.currentTimeMillis())
                            .putString(PREF_STATUS, r.summary()).commit();
                    post(main, listener, null, r, null);
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    prefs.edit().putString(PREF_STATUS, "Failed: " + msg).commit();
                    post(main, listener, null, null, msg);
                } finally {
                    liveStatus = "";
                    running.set(false);
                }
            }
        });
    }

    /**
     * 2026-10-21 — The whole sync, on the calling (background) thread.
     * Layman: 1) ask the server what should be on the player, 2) download what's missing,
     * 3) delete what this sync put there earlier but you no longer want, 4) write playlists.
     * Listing failures throw before anything is deleted, so a flaky server never wipes files.
     */
    static Result runBlocking(Context ctx, SharedPreferences prefs, Listener l) throws Exception {
        NavidromeClient client = NavidromeClient.getInstance();
        if (!client.isConfigured()) throw new Exception("Navidrome not configured");
        File mediaRoot = DeviceFeatures.getNewMediaRoot(ctx);
        if (mediaRoot == null) throw new Exception("No storage selected");
        File navRoot = new File(mediaRoot, NavidromeDownloader.ROOT_DIR);
        File legacyRoot = new File(mediaRoot, NavidromeDownloader.LEGACY_ROOT_DIR);
        if (!navRoot.exists()) navRoot.mkdirs();

        // 1) Desired set — songs by id (insertion order) + playlists by id.
        l.onStatus("Reading server…");
        Map<String, NavidromeSong> desired = new LinkedHashMap<String, NavidromeSong>();
        Map<String, String> playlistNames = new LinkedHashMap<String, String>();
        Map<String, List<String>> playlistSongIds = new LinkedHashMap<String, List<String>>();
        if (prefs.getBoolean(PREF_STARRED, true)) collectStarred(client, desired);
        String tag = prefs.getString(PREF_TAG, DEFAULT_TAG);
        collectPlaylists(client, tag, desired, playlistNames, playlistSongIds);

        JSONObject manifest = SyncFiles.readJson(new File(navRoot, MANIFEST_NAME));
        JSONObject oldSongs = manifest.optJSONObject("songs");
        if (oldSongs == null) oldSongs = new JSONObject();
        JSONObject newSongs = new JSONObject();
        Result r = new Result();

        // 2) Download what's missing. Per-song failures are counted, not fatal.
        Map<String, File> localById = new LinkedHashMap<String, File>();
        int i = 0;
        for (NavidromeSong s : desired.values()) {
            i++;
            File dest = destinationFor(ctx, s);
            String rel = SyncFiles.relative(mediaRoot, dest);
            JSONObject prev = oldSongs.optJSONObject(s.id);
            boolean owned = prev != null && prev.optBoolean("owned", false);
            // 2026-10-21 — Adopt a pre-existing manual save from the old top-level Navidrome/ folder
            // (same volume rename, no re-download). Stays not-owned: un-starring never deletes it.
            File legacy = new File(legacyRoot, SyncFiles.relative(navRoot, dest));
            if (!dest.exists() && legacy.isFile() && legacy.length() > 1024) {
                dest.getParentFile().mkdirs();
                if (legacy.renameTo(dest)) SyncFiles.pruneEmptyDirs(legacy.getParentFile(), legacyRoot);
            }
            if (dest.isFile() && dest.length() > 1024) {
                r.kept++;
            } else if (r.storageFull || mediaRoot.getUsableSpace() < MIN_FREE_BYTES) {
                r.storageFull = true;
                continue;
            } else {
                l.onStatus("Downloading " + i + "/" + desired.size());
                try {
                    downloadAtomic(client.getDownloadUrl(s.id), dest);
                    owned = true;
                    r.downloaded++;
                } catch (Exception e) {
                    r.failed++;
                    continue;
                }
            }
            localById.put(s.id, dest);
            JSONObject entry = new JSONObject();
            entry.put("path", rel);
            entry.put("owned", owned);
            newSongs.put(s.id, entry);
        }

        // 3) Remove files this sync owns that left the desired set.
        // 2026-10-21 — Same-name tracks can share one path; never delete a path a wanted song still uses.
        Set<String> keptPaths = new HashSet<String>();
        for (File f : localById.values()) keptPaths.add(f.getAbsolutePath());
        for (String id : idsToRemove(SyncFiles.keys(oldSongs), desired.keySet())) {
            JSONObject prev = oldSongs.optJSONObject(id);
            if (prev == null || !prev.optBoolean("owned", false)) continue;
            File f = new File(mediaRoot, prev.optString("path", ""));
            if (keptPaths.contains(f.getAbsolutePath())) continue;
            if (f.isFile() && f.delete()) r.removed++;
            SyncFiles.pruneEmptyDirs(f.getParentFile(), navRoot);
        }
        // ponytail: when desired is empty idsToRemove returns nothing, so owned entries are kept.
        if (desired.isEmpty()) newSongs = oldSongs;

        // 4) Playlists → Music/Playlists/<name>.m3u8 with relative paths (Solar + Rockbox both read these).
        JSONObject oldPls = manifest.optJSONObject("playlists");
        if (oldPls == null) oldPls = new JSONObject();
        JSONObject newPls = new JSONObject();
        File plDir = PlaylistManager.playlistsDir(new File(mediaRoot, "Music"));
        for (Map.Entry<String, String> e : playlistNames.entrySet()) {
            List<File> files = new ArrayList<File>();
            for (String sid : playlistSongIds.get(e.getKey())) {
                File f = localById.get(sid);
                if (f != null) files.add(f);
            }
            File out = playlistFile(mediaRoot, plDir, e.getValue(), oldPls.optString(e.getKey(), null));
            PlaylistManager.saveM3uRelative(PlaylistManager.fromTracks(e.getValue(), files), out);
            newPls.put(e.getKey(), SyncFiles.relative(mediaRoot, out));
            r.playlists++;
        }
        for (String pid : SyncFiles.keys(oldPls)) {
            if (!newPls.has(pid)) new File(mediaRoot, oldPls.optString(pid)).delete();
        }

        manifest.put("v", 1);
        manifest.put("songs", newSongs);
        manifest.put("playlists", newPls);
        SyncFiles.writeJsonAtomic(new File(navRoot, MANIFEST_NAME), manifest);
        return r;
    }

    /** 2026-10-21 — Starred songs + every song of starred albums (Subsonic getStarred2 / getAlbum). */
    private static void collectStarred(NavidromeClient client, Map<String, NavidromeSong> out) throws Exception {
        JSONObject starred = client.callBlocking("getStarred2", null).optJSONObject("starred2");
        if (starred == null) return;
        JSONArray songs = starred.optJSONArray("song");
        if (songs != null) {
            for (int i = 0; i < songs.length(); i++) addSong(out, NavidromeClient.parseSong(songs.getJSONObject(i)));
        }
        JSONArray albums = starred.optJSONArray("album");
        if (albums == null) return;
        for (int i = 0; i < albums.length(); i++) {
            for (NavidromeSong s : albumSongs(client, albums.getJSONObject(i).getString("id"))) addSong(out, s);
        }
    }

    /** 2026-10-21 — Playlists whose name or comment contains the tag (default "#sync"). */
    private static void collectPlaylists(NavidromeClient client, String tag, Map<String, NavidromeSong> out,
            Map<String, String> names, Map<String, List<String>> ids) throws Exception {
        if (tag == null || tag.trim().isEmpty()) return;
        JSONObject wrap = client.callBlocking("getPlaylists", null).optJSONObject("playlists");
        JSONArray arr = wrap != null ? wrap.optJSONArray("playlist") : null;
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            String name = p.optString("name", "");
            if (!matchesTag(name, p.optString("comment", ""), tag)) continue;
            String id = p.getString("id");
            JSONObject pl = client.callBlocking("getPlaylist", "id=" + java.net.URLEncoder.encode(id, "UTF-8"))
                    .optJSONObject("playlist");
            JSONArray entries = pl != null ? pl.optJSONArray("entry") : null;
            List<String> songIds = new ArrayList<String>();
            if (entries != null) {
                for (int j = 0; j < entries.length(); j++) {
                    NavidromeSong s = NavidromeClient.parseSong(entries.getJSONObject(j));
                    addSong(out, s);
                    songIds.add(s.id);
                }
            }
            names.put(id, stripTag(name, tag));
            ids.put(id, songIds);
        }
    }

    /** 2026-10-21 — Album tracks with the album's own artist so folders match browse downloads. */
    private static List<NavidromeSong> albumSongs(NavidromeClient client, String albumId) throws Exception {
        JSONObject album = client.callBlocking("getAlbum", "id=" + java.net.URLEncoder.encode(albumId, "UTF-8"))
                .getJSONObject("album");
        String albumArtist = album.optString("artist", "");
        List<NavidromeSong> out = new ArrayList<NavidromeSong>();
        JSONArray arr = album.optJSONArray("song");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            NavidromeSong s = NavidromeClient.parseSong(arr.getJSONObject(i));
            if (albumArtist.length() > 0) s.albumArtist = albumArtist;
            if (s.album.isEmpty()) s.album = album.optString("name", "");
            out.add(s);
        }
        return out;
    }

    private static void addSong(Map<String, NavidromeSong> out, NavidromeSong s) {
        if (s != null && s.id.length() > 0 && !out.containsKey(s.id)) out.put(s.id, s);
    }

    /** 2026-10-21 — Same Navidrome/Artist/Album/Title.ext path the browse "Save" uses (no duplicates). */
    private static File destinationFor(Context ctx, NavidromeSong s) {
        NavidromeAlbum al = new NavidromeAlbum();
        al.name = s.album;
        al.artist = s.albumArtist != null && s.albumArtist.length() > 0 ? s.albumArtist : s.artist;
        return NavidromeDownloader.destinationFor(ctx, null, al, s);
    }

    /**
     * 2026-10-21 — Download to "<name>.part" then rename.
     * Layman: a half-finished file never looks finished; tech: SolarHttp resumes the .part via Range.
     */
    static void downloadAtomic(String url, File dest) throws Exception {
        File dir = dest.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File part = new File(dest.getPath() + ".part");
        SolarHttp.downloadToFile(url, part);
        if (part.length() == 0) throw new Exception("Empty download");
        if (dest.exists()) dest.delete();
        if (!part.renameTo(dest)) throw new Exception("Rename failed: " + dest.getName());
    }

    /** 2026-10-21 — Reuse our old playlist file; never overwrite a user playlist with the same name. */
    private static File playlistFile(File mediaRoot, File plDir, String name, String previousRel) {
        if (previousRel != null && previousRel.length() > 0) return new File(mediaRoot, previousRel);
        String base = PlaylistManager.safeFileName(name);
        File f = new File(plDir, base + ".m3u8");
        if (f.exists()) f = new File(plDir, base + " (Navidrome).m3u8");
        return f;
    }

    // ---- Pure helpers (unit-tested in NavidromeSyncTest) ----

    /** 2026-10-21 — Ids we tracked before but no longer want; empty when nothing is wanted (safety). */
    static Set<String> idsToRemove(Collection<String> previous, Collection<String> desired) {
        Set<String> out = new HashSet<String>();
        if (desired == null || desired.isEmpty() || previous == null) return out;
        for (String id : previous) if (!desired.contains(id)) out.add(id);
        return out;
    }

    /** 2026-10-21 — Case-insensitive tag match on playlist name or comment. */
    static boolean matchesTag(String name, String comment, String tag) {
        if (tag == null || tag.trim().isEmpty()) return false;
        String t = tag.trim().toLowerCase(Locale.US);
        return (name != null && name.toLowerCase(Locale.US).contains(t))
                || (comment != null && comment.toLowerCase(Locale.US).contains(t));
    }

    /** 2026-10-21 — "Road trip #sync" → "Road trip" for the playlist file name. */
    static String stripTag(String name, String tag) {
        if (name == null) return "Playlist";
        String out = name.replaceAll("(?i)" + java.util.regex.Pattern.quote(tag.trim()), "").trim();
        return out.isEmpty() ? name.trim() : out;
    }

    private static void post(Handler main, final Listener l, final String status, final Result done,
            final String error) {
        if (l == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (status != null) l.onStatus(status);
                else if (done != null) l.onDone(done);
                else l.onError(error != null ? error : "error");
            }
        });
    }
}
