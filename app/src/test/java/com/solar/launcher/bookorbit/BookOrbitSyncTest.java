package com.solar.launcher.bookorbit;

import org.junit.Test;

/**
 * 2026-10-21 — BookOrbit position merge + naming rules.
 * Layman: whichever side you listened on since the last sync wins; if both, the newer one wins.
 */
public class BookOrbitSyncTest {

    @Test
    public void onlyLocalChangedPushes() {
        if (BookOrbitSync.decide(true, false, 1L, 999L) != BookOrbitSync.Decision.PUSH) throw new AssertionError("push");
    }

    @Test
    public void onlyServerChangedPulls() {
        if (BookOrbitSync.decide(false, true, 999L, 1L) != BookOrbitSync.Decision.PULL) throw new AssertionError("pull");
    }

    @Test
    public void bothChangedNewerWins() {
        if (BookOrbitSync.decide(true, true, 2000L, 1000L) != BookOrbitSync.Decision.PUSH) throw new AssertionError("local newer");
        if (BookOrbitSync.decide(true, true, 1000L, 2000L) != BookOrbitSync.Decision.PULL) throw new AssertionError("server newer");
    }

    @Test
    public void neitherChangedDoesNothing() {
        if (BookOrbitSync.decide(false, false, 5L, 9L) != BookOrbitSync.Decision.NONE) throw new AssertionError("none");
    }

    @Test
    public void positionWithinOneSecondIsUnchanged() {
        if (BookOrbitSync.positionDiffers("A/B/01.mp3", 60500L, "A/B/01.mp3", 60000L)) throw new AssertionError("jitter");
        if (!BookOrbitSync.positionDiffers("A/B/01.mp3", 62000L, "A/B/01.mp3", 60000L)) throw new AssertionError("moved");
        if (!BookOrbitSync.positionDiffers("A/B/02.mp3", 60000L, "A/B/01.mp3", 60000L)) throw new AssertionError("other part");
        if (!BookOrbitSync.positionDiffers("A/B/01.mp3", 10L, "", -1L)) throw new AssertionError("no snapshot");
    }

    @Test
    public void partFileNamesSortInPlaybackOrder() {
        if (!"Dune.m4b".equals(BookOrbitSync.partFileName(0, 1, "Dune", "M4B"))) throw new AssertionError("single");
        if (!"02 - Dune.mp3".equals(BookOrbitSync.partFileName(1, 12, "Dune", "mp3"))) throw new AssertionError("multi");
        if (!"007 - Dune.mp3".equals(BookOrbitSync.partFileName(6, 120, "Dune", "mp3"))) throw new AssertionError("3-digit");
        if (!"A_B.mp3".equals(BookOrbitSync.partFileName(0, 1, "A/B", null))) throw new AssertionError("safe name + default ext");
    }

    @Test
    public void serverUrlIsNormalized() {
        if (!"http://192.168.1.5:6060".equals(BookOrbitClient.normalizeServerUrl(" 192.168.1.5:6060/ ")))
            throw new AssertionError("bare host");
        if (!"https://books.example.com".equals(BookOrbitClient.normalizeServerUrl("https://books.example.com/api/v1/")))
            throw new AssertionError("pasted api path");
    }

    @Test
    public void isoTimestampsRoundTrip() {
        long t = BookOrbitClient.parseIso("2026-10-21T12:00:00.000Z");
        if (t != 1792584000000L) throw new AssertionError("parse " + t);
        if (BookOrbitClient.parseIso("2026-10-21T12:00:00Z") != t) throw new AssertionError("no millis");
        if (BookOrbitClient.parseIso("garbage") != 0L) throw new AssertionError("bad input → 0");
    }

    @Test
    public void sourcesParseAndToggle() {
        java.util.Set<String> s = BookOrbitSync.parseSources(" lib:3, col:7,junk,lib:x,col:7");
        if (!s.toString().equals("[lib:3, col:7]")) throw new AssertionError("parse " + s);
        if (!"lib:3".equals(BookOrbitSync.toggleSource("lib:3,col:7", "col:7"))) throw new AssertionError("toggle off");
        if (!"lib:3,lib:5".equals(BookOrbitSync.toggleSource("lib:3", "lib:5"))) throw new AssertionError("toggle on");
        if (!BookOrbitSync.parseSources(null).isEmpty()) throw new AssertionError("null → empty");
    }

    @Test
    public void audioDetectionMatchesBookOrbitFormats() throws Exception {
        org.json.JSONObject m4b = new org.json.JSONObject("{\"files\":[{\"role\":\"primary\",\"format\":\"M4B\"}]}");
        org.json.JSONObject epub = new org.json.JSONObject("{\"files\":[{\"role\":\"primary\",\"format\":\"epub\"}]}");
        org.json.JSONObject coverOnly = new org.json.JSONObject("{\"files\":[{\"role\":\"cover\",\"format\":\"mp3\"}]}");
        org.json.JSONObject unknown = new org.json.JSONObject("{\"id\":1}");
        if (!BookOrbitClient.hasAudio(m4b)) throw new AssertionError("m4b is audio");
        if (BookOrbitClient.hasAudio(epub)) throw new AssertionError("epub is not");
        if (BookOrbitClient.hasAudio(coverOnly)) throw new AssertionError("non-content file ignored");
        if (!BookOrbitClient.hasAudio(unknown)) throw new AssertionError("no file list → let manifest decide");
    }
}
