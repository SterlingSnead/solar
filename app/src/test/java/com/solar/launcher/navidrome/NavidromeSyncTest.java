package com.solar.launcher.navidrome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * 2026-10-21 — Navidrome sync safety rules.
 * Layman: never wipe the player when the server returns nothing; only remove what left the list;
 * "#sync" tag matching is forgiving about case and where the tag sits.
 */
public class NavidromeSyncTest {

    @Test
    public void removesOnlyIdsThatLeftTheDesiredSet() {
        Set<String> gone = NavidromeSync.idsToRemove(Arrays.asList("a", "b", "c"), Arrays.asList("a", "c", "d"));
        if (!gone.equals(Collections.singleton("b"))) throw new AssertionError("expected [b], got " + gone);
    }

    @Test
    public void emptyServerListNeverDeletesAnything() {
        Set<String> gone = NavidromeSync.idsToRemove(Arrays.asList("a", "b"), Collections.<String>emptyList());
        if (!gone.isEmpty()) throw new AssertionError("empty desired set must not delete: " + gone);
    }

    @Test
    public void tagMatchesNameOrCommentCaseInsensitive() {
        if (!NavidromeSync.matchesTag("Road Trip #SYNC", "", "#sync")) throw new AssertionError("name tag");
        if (!NavidromeSync.matchesTag("Road Trip", "keep #sync on device", "#sync")) throw new AssertionError("comment tag");
        if (NavidromeSync.matchesTag("Road Trip", "", "#sync")) throw new AssertionError("untagged must not match");
        if (NavidromeSync.matchesTag("Road Trip #sync", "", "  ")) throw new AssertionError("blank tag disables");
    }

    @Test
    public void tagIsStrippedFromPlaylistFileName() {
        if (!"Road Trip".equals(NavidromeSync.stripTag("Road Trip #Sync", "#sync"))) throw new AssertionError("strip");
        if (!"#sync".equals(NavidromeSync.stripTag("#sync", "#sync"))) throw new AssertionError("tag-only name kept");
    }
}
