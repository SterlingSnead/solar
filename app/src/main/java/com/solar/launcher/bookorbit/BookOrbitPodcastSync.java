package com.solar.launcher.bookorbit;

import com.solar.launcher.PlaylistManager;
import com.solar.launcher.sync.SyncFiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-21 — BookOrbit podcasts → Podcasts/<Show>/ on the player.
 * Layman: for each show you pick, keep the newest few episodes you haven't finished. Finish one
 * (here or in BookOrbit) and it's marked played on both sides and removed from the player.
 * Where you stopped in an episode is shared both ways, like audiobooks.
 * Tech: GET episodes (filter=latest, newest first) → first N not finished; POST state-batch for
 * server state; PATCH /podcast-episodes/{id}/state for pushes; 3-way merge against a per-episode
 * snapshot in Podcasts/.bookorbit-podcasts.json. Local state = Solar's PodcastResumeStore /
 * PodcastPlayedStore (via BookOrbitPodcastLocal), so Solar's own podcast player resumes there.
 */
final class BookOrbitPodcastSync {

    /** 2026-10-21 — Solar's on-device podcast state for one file (see BookOrbitPodcastLocal). */
    interface Local {
        /** Saved resume position in ms, 0 when none. */
        int resumeMs(File f);
        /** When the resume position was saved (ms since epoch), 0 when unknown. */
        long resumeSavedAt(File f);
        void saveResume(File f, int positionMs);
        void clearResume(File f);
        boolean played(File f);
        void markPlayed(File f);
        /** Write show/episode tags so Solar groups the file under its show. */
        void tag(File f, String show, String title);
    }

    static final String MANIFEST_NAME = ".bookorbit-podcasts.json";
    static final long SAME_POSITION_MS = 1000L;

    private BookOrbitPodcastSync() {}

    /**
     * 2026-10-21 — One pass over all chosen shows. Show listing failures throw before anything is
     * removed; per-episode failures are counted and retried next sync.
     */
    static void run(BookOrbitClient client, File mediaRoot, Set<String> showTokens, int perShow, Local local,
            BookOrbitSync.Listener l, BookOrbitSync.Result r) throws Exception {
        File root = new File(mediaRoot, "Podcasts");
        if (!root.exists()) root.mkdirs();
        File manifestFile = new File(root, MANIFEST_NAME);
        JSONObject manifest = SyncFiles.readJson(manifestFile);
        JSONObject oldEps = manifest.optJSONObject("eps");
        if (oldEps == null) oldEps = new JSONObject();

        // 1) Candidates: unfinished episodes per chosen show, newest first (a few spare beyond N).
        Map<String, List<JSONObject>> candidates = new LinkedHashMap<String, List<JSONObject>>();
        for (String token : showTokens) {
            int[] ids = parseShowToken(token);
            if (ids == null) continue;
            l.onStatus("Podcasts: checking shows…");
            JSONArray eps = client.showEpisodes(ids[0], ids[1], Math.min(100, perShow * 3 + 5));
            List<JSONObject> list = new ArrayList<JSONObject>();
            for (int i = 0; i < eps.length(); i++) {
                JSONObject ep = eps.optJSONObject(i);
                if (ep == null || ep.optBoolean("finished", false)) continue;
                ep.put("_show", token);
                list.add(ep);
            }
            candidates.put(token, list);
        }

        // 2) Server state for everything we have or might want.
        Set<Integer> allIds = new HashSet<Integer>();
        for (List<JSONObject> list : candidates.values()) for (JSONObject ep : list) allIds.add(ep.optInt("id"));
        for (String k : SyncFiles.keys(oldEps)) allIds.add(Integer.parseInt(k));
        Map<Integer, JSONObject> states = client.episodeStates(allIds);

        // 3a) Episodes already on the player: share finished/position first, so an episode finished
        //     in this very sync frees its slot before the window is chosen.
        Set<Integer> finishedNow = new HashSet<Integer>();
        Map<String, File> onDevice = new LinkedHashMap<String, File>();
        for (String k : SyncFiles.keys(oldEps)) {
            int id = Integer.parseInt(k);
            JSONObject prev = oldEps.optJSONObject(k);
            File f = new File(mediaRoot, prev.optString("path", ""));
            if (!f.isFile()) continue; // user deleted it; forget it
            onDevice.put(k, f);
            JSONObject st = states.get(id);
            if (st == null) st = new JSONObject(); // never touched on the server: position 0, not finished
            boolean serverFinished = st.optBoolean("finished", false);
            boolean localPlayed = local.played(f);
            try {
                if (localPlayed && !serverFinished) {
                    client.putEpisodeState(id, null, Boolean.TRUE);
                    r.podPushed++;
                } else if (serverFinished && !localPlayed) {
                    local.markPlayed(f);
                    local.clearResume(f);
                    r.podPulled++;
                } else if (!serverFinished) {
                    mergePosition(client, local, id, f, prev, st, r);
                }
            } catch (Exception e) {
                r.failed++;
            }
            if (serverFinished || local.played(f)) finishedNow.add(id);
        }

        // 3b) The window: newest N not finished (including anything finished just now).
        Map<Integer, JSONObject> wanted = new LinkedHashMap<Integer, JSONObject>();
        for (List<JSONObject> list : candidates.values()) {
            int kept = 0;
            for (JSONObject ep : list) {
                if (kept >= perShow) break;
                if (finishedNow.contains(ep.optInt("id"))) continue;
                wanted.put(ep.optInt("id"), ep);
                kept++;
            }
        }

        // 3c) Keep or remove what's on the player.
        JSONObject newEps = new JSONObject();
        for (Map.Entry<String, File> e : onDevice.entrySet()) {
            int id = Integer.parseInt(e.getKey());
            JSONObject prev = oldEps.optJSONObject(e.getKey());
            File f = e.getValue();
            boolean stillChosen = candidates.containsKey(prev.optString("show", ""));
            boolean inProgress = local.resumeMs(f) > 0;
            boolean keep = !finishedNow.contains(id) && stillChosen && (wanted.containsKey(id) || inProgress);
            if (keep) {
                newEps.put(e.getKey(), prev);
                wanted.remove(id);
            } else if (prev.optBoolean("owned", true) && f.delete()) {
                r.podRemoved++;
                SyncFiles.pruneEmptyDirs(f.getParentFile(), root);
            }
        }

        // 4) New episodes: download, tag, carry over the server position.
        int n = 0;
        for (JSONObject ep : wanted.values()) {
            n++;
            int id = ep.optInt("id");
            if (r.storageFull || mediaRoot.getUsableSpace() < BookOrbitSync.MIN_FREE_BYTES) {
                r.storageFull = true;
                break;
            }
            String show = ep.optString("podcastTitle", "Podcast");
            String title = ep.optString("title", "Episode " + id);
            File dir = new File(root, PlaylistManager.safeFileName(show));
            File f = uniqueFile(dir, PlaylistManager.safeFileName(title), extFor(ep.optString("audioFormat", "")));
            l.onStatus("Podcasts: episode " + n + "/" + wanted.size());
            try {
                if (!dir.exists()) dir.mkdirs();
                File part = new File(f.getPath() + ".part");
                client.downloadEpisode(id, part);
                if (!part.renameTo(f)) throw new Exception("Rename failed: " + f.getName());
                local.tag(f, show, title);
                JSONObject st = states.get(id);
                long posMs = st != null ? (long) (st.optDouble("positionSeconds", 0) * 1000) : 0L;
                if (posMs > 0) local.saveResume(f, (int) Math.min(Integer.MAX_VALUE, posMs));
                JSONObject entry = new JSONObject();
                entry.put("path", SyncFiles.relative(mediaRoot, f));
                entry.put("show", ep.optString("_show"));
                entry.put("owned", true);
                entry.put("snapPos", local.resumeMs(f));
                entry.put("snapServerPos", posMs);
                newEps.put(String.valueOf(id), entry);
                r.podDownloaded++;
            } catch (Exception e) {
                r.failed++;
            }
        }

        manifest.put("v", 1);
        manifest.put("eps", newEps);
        SyncFiles.writeJsonAtomic(manifestFile, manifest);
    }

    /** 2026-10-21 — Position merge for an unfinished episode (same 3-way rule as audiobooks). */
    private static void mergePosition(BookOrbitClient client, Local local, int id, File f, JSONObject prev,
            JSONObject st, BookOrbitSync.Result r) throws Exception {
        long localPos = local.resumeMs(f);
        long serverPos = (long) (st.optDouble("positionSeconds", 0) * 1000);
        long snapLocal = prev.optLong("snapPos", 0L);
        long snapServer = prev.optLong("snapServerPos", 0L);
        boolean localChanged = Math.abs(localPos - snapLocal) >= SAME_POSITION_MS;
        boolean serverChanged = Math.abs(serverPos - snapServer) >= SAME_POSITION_MS;
        long serverAt = BookOrbitClient.parseIso(st.optString("updatedAt", ""));
        BookOrbitSync.Decision d = BookOrbitSync.decide(localChanged, serverChanged, local.resumeSavedAt(f), serverAt);
        if (d == BookOrbitSync.Decision.PUSH) {
            client.putEpisodeState(id, localPos / 1000L, null);
            r.podPushed++;
            prev.put("snapPos", localPos);
            prev.put("snapServerPos", (localPos / 1000L) * 1000L);
        } else if (d == BookOrbitSync.Decision.PULL) {
            if (serverPos > 0) local.saveResume(f, (int) Math.min(Integer.MAX_VALUE, serverPos));
            else local.clearResume(f);
            r.podPulled++;
            prev.put("snapPos", local.resumeMs(f));
            prev.put("snapServerPos", serverPos);
        }
    }

    // ---- Pure helpers (unit-tested) ----

    /** 2026-10-21 — "pod:9:42" → {9, 42}; null when malformed. */
    static int[] parseShowToken(String token) {
        if (token == null || !token.matches("pod:\\d+:\\d+")) return null;
        String[] p = token.split(":");
        try {
            return new int[] { Integer.parseInt(p[1]), Integer.parseInt(p[2]) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 2026-10-21 — Server audioFormat → file extension (Solar plays these). */
    static String extFor(String format) {
        String f = format == null ? "" : format.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
        if (f.equals("mpeg") || f.isEmpty() || f.length() > 5) return "mp3";
        if (f.equals("mp4") || f.equals("aac") || f.equals("xm4a")) return "m4a";
        return f;
    }

    /** 2026-10-21 — "Title.mp3", or "Title (2).mp3" when another episode already has that name. */
    static File uniqueFile(File dir, String base, String ext) {
        File f = new File(dir, base + "." + ext);
        for (int i = 2; f.exists() && i < 1000; i++) f = new File(dir, base + " (" + i + ")." + ext);
        return f;
    }

    static List<String> showTokens(Set<String> sources) {
        List<String> out = new ArrayList<String>();
        for (String s : sources) if (s.startsWith("pod:")) out.add(s);
        return out;
    }
}
