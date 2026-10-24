package com.solar.launcher.bookorbit;

import android.content.Context;

import com.solar.launcher.podcast.PodcastLibrary;
import com.solar.launcher.podcast.PodcastPlayedStore;
import com.solar.launcher.podcast.PodcastResumeStore;

import java.io.File;

/**
 * 2026-10-21 — Solar's own podcast resume/played state, as BookOrbitPodcastSync sees it.
 * Layman: the place Solar's podcast player remembers "stopped at 12:40" and "already heard".
 * Tech: same "podcast_resume" record (pos|dur|savedAt) and key (file:path) the player uses.
 */
final class BookOrbitPodcastLocal implements BookOrbitPodcastSync.Local {

    private static final String RESUME_PREFS = "podcast_resume";
    private final Context ctx;

    BookOrbitPodcastLocal(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private String key(File f) {
        return PodcastResumeStore.keyForFile(f);
    }

    private String[] raw(File f) {
        String v = ctx.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE).getString(key(f), null);
        return v != null ? v.split("\\|") : null;
    }

    @Override public int resumeMs(File f) {
        return PodcastResumeStore.restorePositionMs(ctx, key(f), 0);
    }

    @Override public long resumeSavedAt(File f) {
        String[] p = raw(f);
        try {
            return p != null && p.length >= 3 ? Long.parseLong(p[2]) : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override public void saveResume(File f, int positionMs) {
        // Keep the stored duration so Solar's end-of-episode checks still work.
        String[] p = raw(f);
        int dur = 0;
        try {
            if (p != null && p.length >= 2) dur = Integer.parseInt(p[1]);
        } catch (NumberFormatException ignored) {}
        PodcastResumeStore.save(ctx, key(f), positionMs, dur);
    }

    @Override public void clearResume(File f) {
        PodcastResumeStore.clear(ctx, key(f));
    }

    @Override public boolean played(File f) {
        return PodcastPlayedStore.isPlayed(ctx, key(f));
    }

    @Override public void markPlayed(File f) {
        PodcastPlayedStore.markPlayed(ctx, key(f));
    }

    @Override public void tag(File f, String show, String title) {
        PodcastLibrary.tryEmbedSaveTags(f, show, title);
    }
}
