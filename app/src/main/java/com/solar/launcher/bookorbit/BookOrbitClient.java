package com.solar.launcher.bookorbit;

import com.solar.launcher.net.SolarHttp;
import com.solar.launcher.net.TlsHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 2026-10-21 — BookOrbit REST client for background audiobook sync.
 * Layman: logs into your BookOrbit server, lists a collection, downloads audiobook files and
 * reads/writes where you are in each book.
 * Tech: /api/v1 bearer-token API (server 3.0+ audiobook manifest/assets/playback-state).
 * Blocking calls only — always run on a background thread. Re-logs in once on HTTP 401
 * instead of handling the refresh cookie (ponytail: one extra login per expired token).
 */
public final class BookOrbitClient {

    /** 2026-10-21 — HTTP failure with its status code so sync can react to 409/412. */
    public static final class HttpError extends IOException {
        public final int code;

        HttpError(int code, String msg) {
            super("HTTP " + code + (msg != null && msg.length() > 0 ? ": " + msg : ""));
            this.code = code;
        }
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String base;
    private final String user;
    private final String pass;
    private String token;

    public BookOrbitClient(String url, String user, String pass) {
        this.base = normalizeServerUrl(url);
        this.user = user != null ? user.trim() : "";
        this.pass = pass != null ? pass : "";
    }

    public boolean isConfigured() {
        return base.length() > 0 && user.length() > 0 && pass.length() > 0;
    }

    /**
     * 2026-10-21 — "192.168.1.5:6060/" → "http://192.168.1.5:6060"; also drops a pasted "/api/v1".
     * Layman: forgiving about how the address is typed on a click wheel.
     */
    public static String normalizeServerUrl(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.toLowerCase(Locale.US).endsWith("/api/v1")) u = u.substring(0, u.length() - 7);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.isEmpty()) return "";
        if (u.indexOf("://") < 0) u = "http://" + u;
        try {
            new URL(u);
        } catch (Exception e) {
            return "";
        }
        return u;
    }

    /** 2026-10-21 — POST /auth/login → access token kept in memory only. */
    public void login() throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("username", user);
            body.put("password", pass);
        } catch (Exception ignored) {}
        Response resp = TlsHelper.client().newCall(new Request.Builder()
                .url(base + "/api/v1/auth/login")
                .post(RequestBody.create(JSON, body.toString()))
                .build()).execute();
        String text = bodyText(resp);
        if (!resp.isSuccessful()) throw new HttpError(resp.code(), "login failed");
        try {
            token = new JSONObject(text).getString("accessToken");
        } catch (Exception e) {
            throw new IOException("Login response had no token");
        }
    }

    /** 2026-10-21 — Collections the user can see; [{id,name,bookCount,...}]. */
    public JSONArray collections() throws IOException {
        return parseArray(call("GET", "/collections", null));
    }

    /** 2026-10-21 — Case-insensitive collection lookup; -1 when missing. */
    public int findCollectionId(String name) throws IOException {
        JSONArray arr = collections();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c != null && c.optString("name", "").trim().equalsIgnoreCase(name.trim())) return c.optInt("id", -1);
        }
        return -1;
    }

    /** 2026-10-21 — Every book card in a collection (0-based pages of 50). */
    public List<JSONObject> collectionBooks(int collectionId) throws IOException {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (int page = 0; page < 200; page++) {
            JSONObject res = parseObject(call("GET", "/collections/" + collectionId + "/books?page=" + page + "&size=50", null));
            JSONArray items = res != null ? res.optJSONArray("items") : null;
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) out.add(items.optJSONObject(i));
            if (out.size() >= res.optInt("total", Integer.MAX_VALUE) || items.length() < 50) break;
        }
        return out;
    }

    /** 2026-10-21 — Audiobook manifest (assets + revision), or null for ebook-only books (404). */
    public JSONObject manifest(int bookId) throws IOException {
        try {
            return parseObject(call("GET", "/audiobooks/" + bookId + "/manifest", null));
        } catch (HttpError e) {
            if (e.code == 404 || e.code == 400) return null;
            throw e;
        }
    }

    /** 2026-10-21 — Saved listening position, or null when the user never started the book. */
    public JSONObject playbackState(int bookId) throws IOException {
        String text = call("GET", "/audiobooks/" + bookId + "/playback-state", null);
        if (text == null || text.trim().isEmpty() || "null".equals(text.trim())) return null;
        return parseObject(text);
    }

    /**
     * 2026-10-21 — Save position. Server checks baseRevision (409 = someone else wrote first)
     * and manifestRevision (412 = the book's files changed).
     */
    public JSONObject putPlaybackState(int bookId, String assetId, long positionMs, int baseRevision,
            String manifestRevision) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("assetId", assetId);
            body.put("positionMs", Math.max(0L, positionMs));
            body.put("capturedAt", isoNow());
            body.put("operationId", UUID.randomUUID().toString());
            body.put("baseRevision", Math.max(0, baseRevision));
            body.put("manifestRevision", manifestRevision);
        } catch (Exception ignored) {}
        return parseObject(call("PUT", "/audiobooks/" + bookId + "/playback-state", body.toString()));
    }

    /** 2026-10-21 — Stream one audio asset to a file, resuming a partial file with Range. */
    public void downloadAsset(int bookId, String assetId, File dest) throws IOException {
        download("/audiobooks/" + bookId + "/assets/" + assetId + "/content", dest, true);
    }

    /** 2026-10-21 — Cover art as cover.jpg (AudiobookLibrary.folderCover picks it up). */
    public void downloadCover(int bookId, File dest) throws IOException {
        download("/books/" + bookId + "/cover", dest, false);
    }

    // ---- HTTP plumbing ----

    private String call(String method, String path, String json) throws IOException {
        if (token == null) login();
        Response resp = execute(method, path, json);
        if (resp.code() == 401) {
            resp.close();
            login();
            resp = execute(method, path, json);
        }
        String text = bodyText(resp);
        if (!resp.isSuccessful()) throw new HttpError(resp.code(), shorten(text));
        return text;
    }

    private Response execute(String method, String path, String json) throws IOException {
        Request.Builder b = new Request.Builder()
                .url(base + "/api/v1" + path)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (json != null) b.method(method, RequestBody.create(JSON, json));
        else b.method(method, null);
        return TlsHelper.client().newCall(b.build()).execute();
    }

    private void download(String path, File dest, boolean resume) throws IOException {
        if (token == null) login();
        long existing = resume && dest.isFile() ? dest.length() : 0L;
        Response resp = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Request.Builder b = new Request.Builder()
                    .url(base + "/api/v1" + path)
                    .header("Authorization", "Bearer " + token);
            if (existing > 0) b.header("Range", "bytes=" + existing + "-");
            resp = SolarHttp.longReadClient().newCall(b.build()).execute();
            if (resp.code() != 401 || attempt == 1) break;
            resp.close();
            login();
        }
        if (!resp.isSuccessful() || resp.body() == null) {
            int code = resp.code();
            resp.close();
            throw new HttpError(code, path);
        }
        boolean append = resp.code() == 206 && existing > 0;
        InputStream in = resp.body().byteStream();
        FileOutputStream out = new FileOutputStream(dest, append);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.getFD().sync();
        } finally {
            try { out.close(); } catch (IOException ignored) {}
            resp.close();
        }
    }

    private static String bodyText(Response resp) throws IOException {
        try {
            return resp.body() != null ? resp.body().string() : "";
        } finally {
            resp.close();
        }
    }

    private static JSONObject parseObject(String text) throws IOException {
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw new IOException("Unexpected response: " + shorten(text));
        }
    }

    private static JSONArray parseArray(String text) throws IOException {
        try {
            return new JSONArray(text);
        } catch (Exception e) {
            throw new IOException("Unexpected response: " + shorten(text));
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    // ---- Time helpers (BookOrbit uses ISO-8601 UTC) ----

    static String isoNow() {
        return isoFormat().format(new Date());
    }

    /** 2026-10-21 — Parse server capturedAt; 0 when missing/unparseable (treated as "very old"). */
    static long parseIso(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try {
            String t = s.endsWith("Z") && s.indexOf('.') < 0 ? s.replace("Z", ".000Z") : s;
            return isoFormat().parse(t).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static SimpleDateFormat isoFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }
}
