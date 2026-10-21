package com.solar.launcher.navidrome;

/** Navidrome/Subsonic track row. */
public final class NavidromeSong {
    public String id = "";
    public String title = "";
    public String artist = "";
    public String album = "";
    public int durationSec;
    public String suffix = "mp3";
    public String coverArtId;
    /** 2026-10-21 — Album artist (folder owner) so synced compilations land in one folder; tech: Subsonic albumArtist/displayAlbumArtist. */
    public String albumArtist = "";
}
