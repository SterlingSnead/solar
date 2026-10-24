package com.solar.launcher.bookorbit;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.solar.launcher.MusicLibraryStore;
import com.solar.launcher.PlaylistManager;
import com.solar.launcher.audiobook.AudiobookLibrary;
import com.solar.launcher.sync.SyncFiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 2026-10-21 — BookOrbit → Audiobooks/ mirror with two-way listening position.
 * Layman: pick individual books (browse/search on the player) and/or whole collections; they
 * appear on the player, and books you un-pick are removed. Podcasts: see BookOrbitPodcastSync. Where you stopped listening is shared both ways, so you can
 * switch between the player and the BookOrbit app.
 * Tech: /audiobooks/{id}/manifest + assets → Audiobooks/Author/Title/NN - Title.ext; positions merge
 * between Solar's audiobook_bookmarks and /playback-state via a last-synced snapshot (3-way merge).
 */
public final class BookOrbitSync {

    public interface Listener {
        void onStatus(String text);
        void onDone(Result result);
        void onError(String message);
    }

    /** 2026-10-21 — Counts for the Sync row preview. */
    public static final class Result {
        public int books;
        public int downloaded;
        public int removed;
        public int pushed;
        public int pulled;
        public int failed;
        public boolean storageFull;
        // 2026-10-21 — Podcast episode counts (BookOrbitPodcastSync).
        public int podDownloaded;
        public int podRemoved;
        public int podPushed;
        public int podPulled;
        public boolean podcastsRan;

        public String summary() {
            String s = String.format(Locale.US, "%d books · %d files new · %d removed · %d↑ %d↓ positions",
                    books, downloaded, removed, pushed, pulled);
            if (podcastsRan) {
                s += String.format(Locale.US, " · podcasts %d new, %d removed", podDownloaded, podRemoved);
            }
            if (failed > 0) s += " · " + failed + " failed";
            if (storageFull) s += " · storage full";
            return s;
        }
    }

    /** 2026-10-21 — What to do with a book's position this run. */
    enum Decision { NONE, PUSH, PULL }

    public static final String PREF_URL = "bookorbit_url";
    public static final String PREF_USER = "bookorbit_user";
    public static final String PREF_PASS = "bookorbit_pass";
    /**
     * 2026-10-21 — Chosen items, comma-separated tokens: "book:12" (one book), "col:7" (a collection),
     * "pod:9:42" (podcast show 42 in podcast library 9).
     */
    public static final String PREF_SOURCES = "bookorbit_sources";
    /** 2026-10-21 — {"12":"Dune · Frank Herbert"} so the "On this player" list works offline. */
    public static final String PREF_TITLES = "bookorbit_titles";
    /** 2026-10-21 — Newest unfinished episodes kept per podcast show. */
    public static final String PREF_EPISODES = "bookorbit_episodes_per_show";
    public static final int DEFAULT_EPISODES = 3;
    public static final String PREF_AUTO = "bookorbit_sync_auto";
    public static final String PREF_LAST_MS = "bookorbit_sync_last_ms";
    public static final String PREF_STATUS = "bookorbit_sync_status";
    static final String BOOK = "book:";
    static final String COL = "col:";
    static final String POD = "pod:";

    static final String MANIFEST_NAME = ".bookorbit-sync.json";
    /** ponytail: fixed headroom; upgrade path is summing manifest sizeBytes before each book. */
    static final long MIN_FREE_BYTES = 256L * 1024 * 1024;
    static final long AUTO_INTERVAL_MS = 30L * 60 * 1000;
    /** Positions within a second are "the same" — avoids push/pull ping-pong on rounding. */
    static final long SAME_POSITION_MS = 1000L;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean running = new AtomicBoolean(false);
    /** 2026-10-21 — Live progress text for the Sync row; written by the sync thread only. */
    private static volatile String liveStatus = "";

    private BookOrbitSync() {}

    public static boolean isRunning() {
        return running.get();
    }

    /** 2026-10-21 — Sync row preview: live progress while running, else the last run's summary. */
    public static String statusLine(SharedPreferences prefs) {
        if (running.get()) return liveStatus.length() > 0 ? liveStatus : "Syncing…";
        return prefs != null ? prefs.getString(PREF_STATUS, "") : "";
    }

    public static boolean isConfigured(SharedPreferences prefs) {
        return prefs != null && client(prefs).isConfigured();
    }

    static BookOrbitClient client(SharedPreferences prefs) {
        return new BookOrbitClient(prefs.getString(PREF_URL, ""), prefs.getString(PREF_USER, ""),
                prefs.getString(PREF_PASS, ""));
    }

    /** 2026-10-21 — Wi-Fi came up: sync if auto-sync is on and the last run was a while ago. */
    public static void maybeAuto(Context ctx, SharedPreferences prefs, final Runnable onChanged) {
        if (ctx == null || prefs == null || !prefs.getBoolean(PREF_AUTO, false)) return;
        if (!isConfigured(prefs) || !SyncFiles.onWifi(ctx)) return;
        if (System.currentTimeMillis() - prefs.getLong(PREF_LAST_MS, 0L) < AUTO_INTERVAL_MS) return;
        // 2026-10-21 — Rescan the library only when files actually changed (keeps the UI calm).
        runAsync(ctx, prefs, new Listener() {
            @Override public void onStatus(String text) {}
            @Override public void onDone(Result result) {
                if (onChanged != null && result.downloaded + result.removed > 0) onChanged.run();
            }
            @Override public void onError(String message) {}
        });
    }

    /** 2026-10-21 — Start a sync in the background; listener runs on the main thread. */
    public static void runAsync(Context ctx, final SharedPreferences prefs, final Listener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (!running.compareAndSet(false, true)) {
            post(main, listener, null, null, "Sync already running");
            return;
        }
        final Context app = ctx.getApplicationContext();
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
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
     * 2026-10-21 — Whole sync on the calling thread.
     * Layman: 1) list the chosen libraries + collections, 2) download each book's audio, 3) share positions,
     * 4) remove books this sync added that left all of them.
     */
    static Result runBlocking(Context ctx, SharedPreferences prefs, Listener l) throws Exception {
        BookOrbitClient client = client(prefs);
        if (!client.isConfigured()) throw new Exception("BookOrbit not configured");
        File root = AudiobookLibrary.getRoot(ctx);
        // 2026-10-21 — Phone builds can have no storage picked yet → relative path; refuse instead.
        if (root == null || !root.isAbsolute()) throw new Exception("No storage selected");
        Set<String> sources = parseSources(prefs.getString(PREF_SOURCES, ""));
        if (sources.isEmpty()) throw new Exception("Choose audiobooks or podcasts in BookOrbit settings");

        l.onStatus("Signing in…");
        client.login(); // fail loudly on a bad password (single-book picks need no listing call)
        List<JSONObject> cards = collectSourceBooks(client, sources);
        // 2026-10-21 — Only an empty *collection* is suspicious; un-picking every book is a real request.
        boolean emptyIsSuspicious = cards.isEmpty() && hasCollection(sources);

        File manifestFile = new File(root, MANIFEST_NAME);
        JSONObject manifest = SyncFiles.readJson(manifestFile);
        JSONObject oldBooks = manifest.optJSONObject("books");
        if (oldBooks == null) oldBooks = new JSONObject();
        JSONObject newBooks = new JSONObject();
        MusicLibraryStore store = MusicLibraryStore.getInstance(ctx);
        Result r = new Result();

        int n = 0;
        for (JSONObject card : cards) {
            n++;
            // 2026-10-21 — Skip ebook-only cards up front (saves a manifest call per ebook in big libraries).
            if (card == null || !BookOrbitClient.hasAudio(card)) continue;
            int bookId = card.optInt("id", -1);
            if (bookId < 0) continue;
            String key = String.valueOf(bookId);
            JSONObject prev = oldBooks.optJSONObject(key);
            try {
                JSONObject entry = syncBook(ctx, client, store, root, bookId, prev, r, l, n, cards.size());
                if (entry != null) {
                    newBooks.put(key, entry);
                    r.books++;
                } else if (prev != null) {
                    newBooks.put(key, prev);
                }
            } catch (Exception e) {
                r.failed++;
                if (prev != null) newBooks.put(key, prev);
            }
        }

        // Books this sync added that are no longer picked. Skipped when a collection came back empty.
        if (!emptyIsSuspicious) {
            for (String key : SyncFiles.keys(oldBooks)) {
                if (newBooks.has(key)) continue;
                r.removed += deleteOwnedFiles(root, oldBooks.optJSONObject(key));
            }
        } else {
            newBooks = oldBooks;
        }

        manifest.put("v", 1);
        manifest.put("books", newBooks);
        SyncFiles.writeJsonAtomic(manifestFile, manifest);

        // 2026-10-21 — Podcasts after books; a podcast problem never undoes the book sync.
        List<String> shows = BookOrbitPodcastSync.showTokens(sources);
        File mediaRoot = com.solar.launcher.DeviceFeatures.getNewMediaRoot(ctx);
        File podManifest = new File(new File(mediaRoot, "Podcasts"), BookOrbitPodcastSync.MANIFEST_NAME);
        if (mediaRoot != null && (!shows.isEmpty() || podManifest.isFile())) {
            try {
                BookOrbitPodcastSync.run(client, mediaRoot, new java.util.LinkedHashSet<String>(shows),
                        Math.max(1, prefs.getInt(PREF_EPISODES, DEFAULT_EPISODES)), podcastLocal(ctx), l, r);
                r.podcastsRan = true;
            } catch (Exception e) {
                r.failed++;
            }
        }
        return r;
    }

    /** 2026-10-21 — Test hook; production uses Solar's podcast stores. */
    static BookOrbitPodcastSync.Local testPodcastLocal;

    private static BookOrbitPodcastSync.Local podcastLocal(Context ctx) {
        return testPodcastLocal != null ? testPodcastLocal : new BookOrbitPodcastLocal(ctx);
    }

    /**
     * 2026-10-21 — One book: download missing parts, then merge position. Returns its manifest entry,
     * or null when the book has no audio (ebook-only cards in a source are ignored).
     */
    private static JSONObject syncBook(Context ctx, BookOrbitClient client, MusicLibraryStore store, File root,
            int bookId, JSONObject prev, Result r, Listener l, int n, int total) throws Exception {
        JSONObject man = client.manifest(bookId);
        JSONArray assetsArr = man != null ? man.optJSONArray("assets") : null;
        if (assetsArr == null || assetsArr.length() == 0) return null;
        List<JSONObject> assets = sortedAssets(assetsArr);
        JSONObject book = man.optJSONObject("book");
        String title = book != null ? book.optString("title", "Untitled") : "Untitled";
        JSONArray authors = book != null ? book.optJSONArray("authors") : null;
        String author = authors != null && authors.length() > 0 ? authors.optString(0) : "Unknown Author";

        // Keep the folder we used before so renames on the server don't duplicate the book.
        String dirRel = prev != null && prev.optString("dir", "").length() > 0 ? prev.optString("dir")
                : PlaylistManager.safeFileName(author) + File.separator + PlaylistManager.safeFileName(title);
        File dir = new File(root, dirRel);
        if (!dir.exists()) dir.mkdirs();

        JSONObject oldFiles = prev != null ? prev.optJSONObject("files") : null;
        JSONObject files = new JSONObject();
        List<File> ordered = new ArrayList<File>();
        List<String> orderedIds = new ArrayList<String>();
        for (int i = 0; i < assets.size(); i++) {
            JSONObject a = assets.get(i);
            String assetId = a.optString("assetId", "");
            String name = oldFiles != null && oldFiles.has(assetId) ? oldFiles.optString(assetId)
                    : partFileName(i, assets.size(), title, a.optString("format", "mp3"));
            File f = new File(dir, name);
            long size = a.optLong("sizeBytes", 0L);
            boolean complete = f.isFile() && (size <= 0 ? f.length() > 0 : f.length() == size);
            if (!complete) {
                if (r.storageFull || root.getUsableSpace() < MIN_FREE_BYTES) {
                    r.storageFull = true;
                    continue;
                }
                l.onStatus("Book " + n + "/" + total + " · part " + (i + 1) + "/" + assets.size());
                File part = new File(f.getPath() + ".part");
                client.downloadAsset(bookId, assetId, part);
                if (f.exists()) f.delete();
                if (!part.renameTo(f)) throw new Exception("Rename failed: " + f.getName());
                r.downloaded++;
            }
            files.put(assetId, name);
            ordered.add(f);
            orderedIds.add(assetId);
        }
        // Parts dropped from the server's manifest (book re-encoded) — remove our old copies.
        if (oldFiles != null) {
            for (String oldId : SyncFiles.keys(oldFiles)) {
                if (!files.has(oldId)) new File(dir, oldFiles.optString(oldId)).delete();
            }
        }

        File cover = new File(dir, "cover.jpg");
        if (book != null && book.optBoolean("hasCover", false) && !cover.isFile()) {
            try {
                File part = new File(cover.getPath() + ".part");
                client.downloadCover(bookId, part);
                part.renameTo(cover);
            } catch (Exception ignored) {
                // Cover is cosmetic; never fail a book over it.
            }
        }

        JSONObject entry = new JSONObject();
        entry.put("dir", dirRel);
        entry.put("files", files);
        entry.put("rev", man.optString("revision", ""));
        copySnapshot(prev, entry);
        mergePosition(client, store, root, bookId, man.optString("revision", ""), ordered, orderedIds, entry, r);
        return entry;
    }

    /**
     * 2026-10-21 — Two-way position merge for one book.
     * Layman: if you listened on the player since last sync, upload; if you listened in the BookOrbit
     * app, download; if both, the more recent one wins.
     * Tech: local = newest audiobook_bookmarks row among this book's files; server = playback-state;
     * "changed" is relative to the snapshot (snapPath/snapPos/snapRev) stored at the previous sync.
     */
    private static void mergePosition(BookOrbitClient client, MusicLibraryStore store, File root, int bookId,
            String manifestRevision, List<File> files, List<String> assetIds, JSONObject entry, Result r)
            throws Exception {
        if (files.isEmpty()) return;
        MusicLibraryStore.AudiobookBookmark local = null;
        int localIdx = -1;
        for (int i = 0; i < files.size(); i++) {
            MusicLibraryStore.AudiobookBookmark b = store.loadAudiobookBookmark(files.get(i).getAbsolutePath());
            if (b == null || b.positionMs <= 0) continue;
            if (local == null || b.updatedAt > local.updatedAt) {
                local = b;
                localIdx = i;
            }
        }
        JSONObject server = client.playbackState(bookId);
        String snapPath = entry.optString("snapPath", "");
        long snapPos = entry.optLong("snapPos", -1L);
        int snapRev = entry.optInt("snapRev", -1);

        boolean localChanged = local != null && positionDiffers(
                SyncFiles.relative(root, files.get(localIdx)), local.positionMs, snapPath, snapPos);
        boolean serverChanged = server != null && server.optInt("revision", 0) != snapRev;
        long serverAt = server != null ? BookOrbitClient.parseIso(server.optString("capturedAt", "")) : 0L;
        Decision d = decide(localChanged, serverChanged, local != null ? local.updatedAt : 0L, serverAt);

        if (d == Decision.PUSH) {
            int base = server != null ? server.optInt("revision", 0) : 0;
            JSONObject saved;
            try {
                saved = client.putPlaybackState(bookId, assetIds.get(localIdx), local.positionMs, base, manifestRevision);
            } catch (BookOrbitClient.HttpError e) {
                if (e.code == 412) return; // manifest changed mid-sync; next run re-downloads first
                if (e.code != 409) throw e;
                // Someone saved in between — retry once on their revision (we're the newer write).
                JSONObject fresh = client.playbackState(bookId);
                saved = client.putPlaybackState(bookId, assetIds.get(localIdx), local.positionMs,
                        fresh != null ? fresh.optInt("revision", 0) : 0, manifestRevision);
            }
            r.pushed++;
            setSnapshot(entry, SyncFiles.relative(root, files.get(localIdx)), local.positionMs,
                    saved.optInt("revision", base + 1));
        } else if (d == Decision.PULL) {
            int idx = assetIds.indexOf(server.optString("assetId", ""));
            if (idx < 0) return; // that part isn't on the device (storage full) — try next sync
            int pos = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, server.optLong("positionMs", 0L)));
            store.saveAudiobookBookmark(files.get(idx).getAbsolutePath(), pos, 0);
            r.pulled++;
            setSnapshot(entry, SyncFiles.relative(root, files.get(idx)), pos, server.optInt("revision", 0));
        } else if (server != null && !localChanged) {
            // Nothing new either side; remember the server revision we've seen.
            entry.put("snapRev", server.optInt("revision", 0));
        }
    }

    /**
     * 2026-10-21 — Picked books + every book in picked collections, de-duplicated by id.
     * A picked collection that no longer exists on the server stops the sync before anything is deleted.
     * Picked books need no listing; a book deleted on the server just fails its manifest and is kept.
     */
    static List<JSONObject> collectSourceBooks(BookOrbitClient client, Set<String> sources) throws Exception {
        java.util.LinkedHashMap<Integer, JSONObject> byId = new java.util.LinkedHashMap<Integer, JSONObject>();
        for (String src : sources) {
            if (src.startsWith(BOOK)) {
                int id = Integer.parseInt(src.substring(BOOK.length()));
                if (!byId.containsKey(id)) byId.put(id, new JSONObject().put("id", id));
            }
        }
        if (hasCollection(sources)) {
            Set<String> available = new HashSet<String>();
            JSONArray cols = client.collections();
            for (int i = 0; i < cols.length(); i++) available.add(COL + cols.optJSONObject(i).optInt("id"));
            for (String src : sources) {
                if (!src.startsWith(COL)) continue;
                if (!available.contains(src)) throw new Exception("A chosen collection was removed on the server — update your picks");
                for (JSONObject b : client.collectionBooks(Integer.parseInt(src.substring(COL.length())))) {
                    if (b != null && b.optInt("id", -1) >= 0 && !byId.containsKey(b.optInt("id"))) byId.put(b.optInt("id"), b);
                }
            }
        }
        return new ArrayList<JSONObject>(byId.values());
    }

    static boolean hasCollection(Set<String> sources) {
        for (String s : sources) if (s.startsWith(COL)) return true;
        return false;
    }

    // ---- Pure helpers (unit-tested in BookOrbitSyncTest) ----

    /** 2026-10-21 — "book:3, col:7,pod:9:4,junk" → {book:3, col:7, pod:9:4}; keeps order, drops malformed. */
    static Set<String> parseSources(String csv) {
        Set<String> out = new java.util.LinkedHashSet<String>();
        if (csv == null) return out;
        for (String t : csv.split(",")) {
            String s = t.trim();
            if (s.matches("(book|col):\\d+|pod:\\d+:\\d+")) out.add(s);
        }
        return out;
    }

    /** 2026-10-21 — Turn one source on/off; returns the new pref string. */
    public static String toggleSource(String csv, String token) {
        Set<String> set = parseSources(csv);
        if (!set.remove(token)) set.add(token);
        StringBuilder sb = new StringBuilder();
        for (String t : set) {
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        return sb.toString();
    }

    public static boolean isSourceOn(SharedPreferences prefs, String token) {
        return prefs != null && parseSources(prefs.getString(PREF_SOURCES, "")).contains(token);
    }

    /** 2026-10-21 — How many picks start with prefix ("book:", "col:", "pod:"). */
    public static int count(SharedPreferences prefs, String prefix) {
        int n = 0;
        if (prefs == null) return 0;
        for (String s : parseSources(prefs.getString(PREF_SOURCES, ""))) if (s.startsWith(prefix)) n++;
        return n;
    }

    /** 2026-10-21 — 3-way merge rule. ponytail: "both changed" trusts device clocks (NTP-synced on Wi-Fi). */
    static Decision decide(boolean localChanged, boolean serverChanged, long localUpdatedAt, long serverCapturedAt) {
        if (localChanged && !serverChanged) return Decision.PUSH;
        if (serverChanged && !localChanged) return Decision.PULL;
        if (localChanged) return localUpdatedAt >= serverCapturedAt ? Decision.PUSH : Decision.PULL;
        return Decision.NONE;
    }

    static boolean positionDiffers(String path, long pos, String snapPath, long snapPos) {
        if (snapPos < 0 || snapPath == null || !snapPath.equals(path)) return true;
        return Math.abs(pos - snapPos) >= SAME_POSITION_MS;
    }

    /** 2026-10-21 — "01 - Title.m4b" for multi-part books, "Title.m4b" for single files. */
    static String partFileName(int index, int count, String title, String format) {
        String ext = format == null ? "" : format.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
        if (ext.isEmpty() || ext.length() > 5) ext = "mp3";
        String base = PlaylistManager.safeFileName(title);
        if (count <= 1) return base + "." + ext;
        String num = String.format(Locale.US, count > 99 ? "%03d" : "%02d", index + 1);
        return num + " - " + base + "." + ext;
    }

    // ---- Internals ----

    private static List<JSONObject> sortedAssets(JSONArray arr) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a != null && a.optString("assetId", "").length() > 0) out.add(a);
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return a.optInt("sequence", 0) - b.optInt("sequence", 0);
            }
        });
        return out;
    }

    private static void copySnapshot(JSONObject from, JSONObject to) throws Exception {
        if (from == null) return;
        if (from.has("snapPath")) to.put("snapPath", from.optString("snapPath"));
        if (from.has("snapPos")) to.put("snapPos", from.optLong("snapPos"));
        if (from.has("snapRev")) to.put("snapRev", from.optInt("snapRev"));
    }

    private static void setSnapshot(JSONObject entry, String path, long pos, int rev) throws Exception {
        entry.put("snapPath", path);
        entry.put("snapPos", pos);
        entry.put("snapRev", rev);
    }

    /** 2026-10-21 — Remove only the parts + cover this sync wrote; the folder goes if left empty. */
    private static int deleteOwnedFiles(File root, JSONObject entry) {
        if (entry == null) return 0;
        File dir = new File(root, entry.optString("dir", ""));
        if (dir.equals(root) || !dir.isDirectory()) return 0;
        int removed = 0;
        JSONObject files = entry.optJSONObject("files");
        Set<String> names = new HashSet<String>();
        for (String id : SyncFiles.keys(files)) names.add(files.optString(id));
        names.add("cover.jpg");
        for (String name : names) {
            if (new File(dir, name).delete()) removed++;
        }
        SyncFiles.pruneEmptyDirs(dir, root);
        return removed;
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
