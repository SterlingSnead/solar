package com.solar.launcher.bookorbit;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.solar.launcher.R;
import com.solar.launcher.RowKeys;
import com.solar.launcher.SettingsScreens;
import com.solar.launcher.navidrome.NavidromeSettingsHost;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-21 — Settings → Library → BookOrbit server.
 * Layman: type the server address and login, pick which libraries/collections to sync, then sync.
 * Tech: reuses NavidromeSettingsHost.Actions (same row/keyboard plumbing in MainActivity).
 */
public final class BookOrbitSettingsHost {

    // 2026-10-21 — Unused keyboard purpose ids (see grep of KEYBOARD_* = n); 34-36 free.
    public static final int KEYBOARD_URL = 34;
    public static final int KEYBOARD_USER = 35;
    public static final int KEYBOARD_PASS = 36;
    /** 2026-10-21 — Audiobook search box. */
    public static final int KEYBOARD_SEARCH = 37;

    private final NavidromeSettingsHost.Actions actions;

    public BookOrbitSettingsHost(NavidromeSettingsHost.Actions actions) {
        this.actions = actions;
    }

    public static boolean ownsKeyboard(int purpose) {
        return purpose >= KEYBOARD_URL && purpose <= KEYBOARD_SEARCH;
    }

    /** 2026-10-21 — Main BookOrbit screen: server, Audiobooks, Podcasts, auto-sync, sync now. */
    public void build() {
        startScreen(SettingsScreens.BOOKORBIT);
        addBackRow(null);

        addEditableRow(RowKeys.BOOKORBIT_URL, R.string.navidrome_settings_url, KEYBOARD_URL);
        addEditableRow(RowKeys.BOOKORBIT_USER, R.string.navidrome_settings_user, KEYBOARD_USER);
        addEditableRow(RowKeys.BOOKORBIT_PASS, R.string.navidrome_settings_pass, KEYBOARD_PASS);

        LinearLayout test = actions.createSettingsRow(RowKeys.BOOKORBIT_TEST, R.string.navidrome_settings_test, false);
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                testConnection();
            }
        });
        actions.addSettingsRow(test);

        // 2026-10-21 — Pick individual audiobooks (browse/search) and podcast shows.
        addDrillRow(RowKeys.BOOKORBIT_BOOKS, R.string.bookorbit_audiobooks, new Runnable() {
            @Override public void run() { buildBooks(); }
        });
        addDrillRow(RowKeys.BOOKORBIT_PODCASTS, R.string.bookorbit_podcasts, new Runnable() {
            @Override public void run() { podcastRows = null; buildPodcasts(); }
        });

        LinearLayout auto = actions.createSettingsRow(RowKeys.BOOKORBIT_SYNC_AUTO, R.string.server_sync_auto, false);
        auto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                SharedPreferences p = actions.prefs();
                p.edit().putBoolean(BookOrbitSync.PREF_AUTO, !p.getBoolean(BookOrbitSync.PREF_AUTO, false)).commit();
                actions.refreshSettingsPreview(RowKeys.BOOKORBIT_SYNC_AUTO);
            }
        });
        actions.addSettingsRow(auto);

        LinearLayout sync = actions.createSettingsRow(RowKeys.BOOKORBIT_SYNC_NOW, R.string.server_sync_now, false);
        sync.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                syncNow();
            }
        });
        actions.addSettingsRow(sync);
    }

    // ---- Audiobooks: On this player / Search / Browse a library / Collections ----

    /**
     * 2026-10-21 — Settings → BookOrbit → Audiobooks.
     * Layman: see what's on the player, search, or browse a library and tick single books.
     * Collections can still be ticked whole (handy for "everything in my Y1 shelf").
     */
    public void buildBooks() {
        startScreen(SettingsScreens.BOOKORBIT_BOOKS);
        addBackRow(new Runnable() {
            @Override public void run() { build(); }
        });
        addDrillRow(RowKeys.BOOKORBIT_PICKED, R.string.bookorbit_on_player, new Runnable() {
            @Override public void run() { openBrowse(MODE_PICKED, 0, null, null); }
        });
        LinearLayout search = actions.createSettingsRow(RowKeys.BOOKORBIT_SEARCH, R.string.bookorbit_search, true);
        search.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                actions.openKeyboard(KEYBOARD_SEARCH, lastQuery != null ? lastQuery : "");
            }
        });
        actions.addSettingsRow(search);

        if (bookSources == null) {
            addInfoRow(R.string.bookorbit_sources_loading);
            final int gen = ++loadGen;
            runInBackground(new Job() {
                @Override public Object work(BookOrbitClient c) throws Exception {
                    return loadBookSources(actions.activity(), c);
                }
                @SuppressWarnings("unchecked")
                @Override public void done(Object result) {
                    bookSources = (List<String[]>) result;
                    if (gen == loadGen && SettingsScreens.BOOKORBIT_BOOKS.equals(actions.settingsSubScreen())) buildBooks();
                }
            });
            return;
        }
        for (final String[] src : bookSources) {
            if (src[0].startsWith(LIB_TOKEN)) {
                final int libId = Integer.parseInt(src[0].substring(LIB_TOKEN.length()));
                LinearLayout row = actions.createSettingsRow(RowKeys.BOOKORBIT_LIB_PREFIX + libId, src[1], true);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        actions.clickFeedback();
                        openBrowse(MODE_LIBRARY, libId, src[1], null);
                    }
                });
                actions.addSettingsRow(row);
            } else {
                addToggleRow(src[0], src[1], null);
            }
        }
    }

    static final int MODE_PICKED = 0;
    static final int MODE_LIBRARY = 1;
    static final int MODE_SEARCH = 2;
    private static final int PAGE = 50;
    private static final String LIB_TOKEN = "lib:";

    // Browse state (kept so returning from the keyboard / screen-off restores the same list).
    private int browseMode;
    private int browseLibrary;
    private String lastQuery;
    private List<String[]> browseRows = new ArrayList<String[]>();
    private int browseNextPage;
    private int browseTotal;
    private boolean browseLoading;
    private int loadGen;
    private List<String[]> bookSources;
    private List<String[]> podcastRows;

    private void openBrowse(int mode, int libraryId, String title, String query) {
        browseMode = mode;
        browseLibrary = libraryId;
        if (query != null) lastQuery = query;
        browseRows = new ArrayList<String[]>();
        browseNextPage = 0;
        browseTotal = -1;
        if (mode == MODE_PICKED) {
            browseRows = pickedBooks(actions.prefs());
            browseTotal = browseRows.size();
            buildBrowse(-1);
        } else {
            loadMore(-1);
        }
    }

    /** 2026-10-21 — The book list screen (picked / one library / search results). */
    public void buildBrowse(int focusIndex) {
        startScreen(SettingsScreens.BOOKORBIT_BROWSE);
        addBackRow(new Runnable() {
            @Override public void run() { buildBooks(); }
        });
        if (browseLoading && browseRows.isEmpty()) {
            addInfoRow(R.string.bookorbit_sources_loading);
            return;
        }
        if (browseRows.isEmpty()) {
            addInfoRow(browseMode == MODE_PICKED ? R.string.bookorbit_none_picked : R.string.bookorbit_no_results);
            return;
        }
        for (String[] b : browseRows) addToggleRow(b[0], b[1], b[2]);
        if (browseMode != MODE_PICKED && browseTotal >= 0 && browseNextPage * PAGE < browseTotal) {
            LinearLayout more = actions.createSettingsRow(RowKeys.BOOKORBIT_MORE,
                    actions.activity().getString(browseLoading ? R.string.bookorbit_sources_loading : R.string.bookorbit_more), false);
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    actions.clickFeedback();
                    if (!browseLoading) loadMore(browseRows.size() + 1);
                }
            });
            actions.addSettingsRow(more);
        }
        if (focusIndex > 0) actions.focusSettingsRow(focusIndex);
    }

    /** 2026-10-21 — Next page of the current library (or the search), audiobooks only. */
    private void loadMore(final int focusIndex) {
        browseLoading = true;
        if (browseRows.isEmpty()) buildBrowse(-1);
        final int gen = ++loadGen;
        final int mode = browseMode;
        final int lib = browseLibrary;
        final int page = browseNextPage;
        final String q = lastQuery;
        runInBackground(new Job() {
            @Override public Object work(BookOrbitClient c) throws Exception {
                List<String[]> rows = new ArrayList<String[]>();
                int total = 0;
                if (mode == MODE_SEARCH) {
                    // Search every book library, first PAGE hits each (no paging).
                    JSONArray libs = c.bookLibraries();
                    for (int i = 0; i < libs.length(); i++) {
                        JSONObject res = c.libraryBooksPage(libs.getJSONObject(i).getInt("id"), 0, PAGE, q);
                        addAudioRows(res.optJSONArray("items"), rows);
                    }
                    total = rows.size();
                } else {
                    JSONObject res = c.libraryBooksPage(lib, page, PAGE, null);
                    addAudioRows(res.optJSONArray("items"), rows);
                    total = res.optInt("total", 0);
                }
                return new Object[] { rows, total };
            }
            @SuppressWarnings("unchecked")
            @Override public void done(Object result) {
                browseLoading = false;
                if (gen != loadGen) return;
                Object[] o = (Object[]) result;
                browseRows.addAll((List<String[]>) o[0]);
                browseTotal = (Integer) o[1];
                browseNextPage = mode == MODE_SEARCH ? Integer.MAX_VALUE / PAGE : page + 1;
                if (SettingsScreens.BOOKORBIT_BROWSE.equals(actions.settingsSubScreen())) buildBrowse(focusIndex);
            }
            @Override public void failed() {
                browseLoading = false;
            }
        });
    }

    /** 2026-10-21 — Book cards → [token, "Title · Author", plain title] rows; ebooks skipped. */
    static void addAudioRows(JSONArray items, List<String[]> out) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject b = items.optJSONObject(i);
            if (b == null || !BookOrbitClient.hasAudio(b)) continue;
            String label = bookLabel(b);
            out.add(new String[] { BookOrbitSync.BOOK + b.optInt("id"), label, label });
        }
    }

    static String bookLabel(JSONObject b) {
        String title = b.optString("title", "Untitled");
        JSONArray a = b.optJSONArray("authors");
        String author = a != null && a.length() > 0 ? a.optString(0, "") : "";
        return author.isEmpty() ? title : title + " · " + author;
    }

    /** 2026-10-21 — Picked single books from the saved titles (works offline). */
    static List<String[]> pickedBooks(SharedPreferences p) {
        List<String[]> out = new ArrayList<String[]>();
        JSONObject titles = titles(p);
        for (String token : BookOrbitSync.parseSources(p.getString(BookOrbitSync.PREF_SOURCES, ""))) {
            if (!token.startsWith(BookOrbitSync.BOOK)) continue;
            String label = titles.optString(token.substring(BookOrbitSync.BOOK.length()), "Book " + token.substring(5));
            out.add(new String[] { token, label, label });
        }
        return out;
    }

    static JSONObject titles(SharedPreferences p) {
        try {
            return new JSONObject(p.getString(BookOrbitSync.PREF_TITLES, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 2026-10-21 — [token,label] for each book library (drill) and collection (whole toggle). */
    static List<String[]> loadBookSources(Context ctx, BookOrbitClient c) throws Exception {
        List<String[]> out = new ArrayList<String[]>();
        JSONArray libs = c.bookLibraries();
        for (int i = 0; i < libs.length(); i++) {
            JSONObject l = libs.getJSONObject(i);
            out.add(new String[] { LIB_TOKEN + l.getInt("id"), ctx.getString(R.string.bookorbit_browse_library, l.optString("name", "?")) });
        }
        JSONArray cols = c.collections();
        for (int i = 0; i < cols.length(); i++) {
            JSONObject o = cols.getJSONObject(i);
            out.add(new String[] { BookOrbitSync.COL + o.getInt("id"),
                    ctx.getString(R.string.bookorbit_source_collection, o.optString("name", "?")) });
        }
        return out;
    }

    // ---- Podcasts ----

    /** 2026-10-21 — Settings → BookOrbit → Podcasts: episodes per show + one On/Off row per show. */
    public void buildPodcasts() {
        startScreen(SettingsScreens.BOOKORBIT_PODCASTS);
        addBackRow(new Runnable() {
            @Override public void run() { build(); }
        });
        LinearLayout eps = actions.createSettingsRow(RowKeys.BOOKORBIT_EPISODES, R.string.bookorbit_episodes_per_show, false);
        eps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                SharedPreferences p = actions.prefs();
                p.edit().putInt(BookOrbitSync.PREF_EPISODES,
                        nextEpisodeCount(p.getInt(BookOrbitSync.PREF_EPISODES, BookOrbitSync.DEFAULT_EPISODES))).commit();
                actions.refreshSettingsPreview(RowKeys.BOOKORBIT_EPISODES);
            }
        });
        actions.addSettingsRow(eps);
        if (podcastRows == null) {
            addInfoRow(R.string.bookorbit_sources_loading);
            final int gen = ++loadGen;
            runInBackground(new Job() {
                @Override public Object work(BookOrbitClient c) throws Exception {
                    List<String[]> rows = new ArrayList<String[]>();
                    JSONArray libs = c.podcastLibraries();
                    for (int i = 0; i < libs.length(); i++) {
                        int lib = libs.getJSONObject(i).getInt("id");
                        for (JSONObject show : c.podcastShows(lib)) {
                            if (show == null || show.opt("archivedAt") instanceof String) continue;
                            rows.add(new String[] { BookOrbitSync.POD + lib + ":" + show.optInt("id"), show.optString("title", "?") });
                        }
                    }
                    return rows;
                }
                @SuppressWarnings("unchecked")
                @Override public void done(Object result) {
                    podcastRows = (List<String[]>) result;
                    if (gen == loadGen && SettingsScreens.BOOKORBIT_PODCASTS.equals(actions.settingsSubScreen())) buildPodcasts();
                }
            });
            return;
        }
        if (podcastRows.isEmpty()) {
            addInfoRow(R.string.bookorbit_no_podcasts);
            return;
        }
        for (String[] s : podcastRows) addToggleRow(s[0], s[1], null);
    }

    /** 2026-10-21 — 1 → 3 → 5 → 10 → 1 newest unfinished episodes per show. */
    static int nextEpisodeCount(int n) {
        if (n < 3) return 3;
        if (n < 5) return 5;
        if (n < 10) return 10;
        return 1;
    }

    // ---- Shared row helpers ----

    private void startScreen(String key) {
        actions.setSettingsSubScreen(key);
        actions.updateStatusBarTitle();
        actions.applyReachBrowseLayoutMode();
        actions.clearSettingsRows();
    }

    /** Back row: null → leave BookOrbit for Library settings; else rebuild the parent screen. */
    private void addBackRow(final Runnable parent) {
        android.widget.Button back = actions.createListButton(actions.activity().getString(R.string.common_cancel_back));
        actions.styleSecondaryLabel(back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                loadGen++; // drop any in-flight load for the screen we're leaving
                actions.drillSettingsBack(parent);
            }
        });
        actions.addSettingsRow(back);
    }

    private void addDrillRow(String rowKey, int labelRes, final Runnable open) {
        LinearLayout row = actions.createSettingsRow(rowKey, labelRes, true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                open.run();
            }
        });
        actions.addSettingsRow(row);
    }

    private void addInfoRow(int labelRes) {
        actions.addSettingsRow(actions.createSettingsRow(RowKeys.BOOKORBIT_INFO,
                actions.activity().getString(labelRes), false));
    }

    /**
     * 2026-10-21 — On/Off row for one pick (book, collection or show). bookLabel != null also
     * remembers the title so "On this player" can list it without the server.
     */
    private void addToggleRow(final String token, String label, final String bookLabel) {
        final String rowKey = RowKeys.BOOKORBIT_SOURCE_PREFIX + token;
        LinearLayout row = actions.createSettingsRow(rowKey, label, false);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                SharedPreferences p = actions.prefs();
                String csv = BookOrbitSync.toggleSource(p.getString(BookOrbitSync.PREF_SOURCES, ""), token);
                SharedPreferences.Editor e = p.edit().putString(BookOrbitSync.PREF_SOURCES, csv);
                if (bookLabel != null) {
                    e.putString(BookOrbitSync.PREF_TITLES, updateTitles(p.getString(BookOrbitSync.PREF_TITLES, "{}"),
                            token, bookLabel, BookOrbitSync.parseSources(csv).contains(token)));
                }
                e.commit();
                actions.refreshSettingsPreview(rowKey);
            }
        });
        actions.addSettingsRow(row);
    }

    /** 2026-10-21 — Add/remove one book's label in the saved titles JSON. */
    static String updateTitles(String json, String token, String label, boolean on) {
        JSONObject t;
        try {
            t = new JSONObject(json != null ? json : "{}");
        } catch (Exception ex) {
            t = new JSONObject();
        }
        String id = token.startsWith(BookOrbitSync.BOOK) ? token.substring(BookOrbitSync.BOOK.length()) : token;
        try {
            if (on) t.put(id, label);
            else t.remove(id);
        } catch (Exception ignored) {}
        return t.toString();
    }

    /** 2026-10-21 — Small background-load helper; results land on the main thread, errors toast. */
    private abstract static class Job {
        abstract Object work(BookOrbitClient c) throws Exception;
        abstract void done(Object result);
        void failed() {}
    }

    private void runInBackground(final Job job) {
        final Context ctx = actions.activity();
        final SharedPreferences p = actions.prefs();
        final Handler main = new Handler(Looper.getMainLooper());
        if (!BookOrbitSync.isConfigured(p)) {
            Toast.makeText(ctx, R.string.navidrome_test_fail, Toast.LENGTH_LONG).show();
            job.failed();
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                Object res = null;
                String err = null;
                try {
                    res = job.work(BookOrbitSync.client(p));
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : ctx.getString(R.string.navidrome_test_fail);
                }
                final Object result = res;
                final String error = err;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (error != null) {
                            job.failed();
                            Toast.makeText(ctx, error, Toast.LENGTH_LONG).show();
                        } else {
                            job.done(result);
                        }
                    }
                });
            }
        }, "BookOrbitLoad").start();
    }

    /** 2026-10-21 — Rebuild whichever BookOrbit screen is current (settings restore after screen-off). */
    public void rebuild(String screenKey) {
        if (SettingsScreens.BOOKORBIT_BOOKS.equals(screenKey)) buildBooks();
        else if (SettingsScreens.BOOKORBIT_BROWSE.equals(screenKey)) buildBrowse(-1);
        else if (SettingsScreens.BOOKORBIT_PODCASTS.equals(screenKey)) buildPodcasts();
        else build();
    }

    /** 2026-10-21 — After the keyboard closes: search → results; anything else → main screen. */
    public void afterKeyboard(int purpose, String text) {
        if (purpose == KEYBOARD_SEARCH) {
            String q = text != null ? text.trim() : "";
            if (q.isEmpty()) {
                buildBooks();
                return;
            }
            openBrowse(MODE_SEARCH, 0, null, q);
            return;
        }
        finishKeyboard(purpose, text);
        build();
    }

    private void addEditableRow(final String rowKey, int labelRes, final int purpose) {
        LinearLayout row = actions.createSettingsRow(rowKey, labelRes, false);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.clickFeedback();
                actions.openKeyboard(purpose, prefillFor(purpose));
            }
        });
        actions.addSettingsRow(row);
    }

    private String prefillFor(int purpose) {
        SharedPreferences p = actions.prefs();
        if (purpose == KEYBOARD_URL) return p.getString(BookOrbitSync.PREF_URL, "");
        if (purpose == KEYBOARD_USER) return p.getString(BookOrbitSync.PREF_USER, "");
        if (purpose == KEYBOARD_PASS) return p.getString(BookOrbitSync.PREF_PASS, "");
        return "";
    }

    /** 2026-10-21 — Save a typed value; URL is normalized so the preview shows what will be used. */
    public void finishKeyboard(int purpose, String text) {
        String v = text != null ? text.trim() : "";
        SharedPreferences.Editor e = actions.prefs().edit();
        if (purpose == KEYBOARD_URL) e.putString(BookOrbitSync.PREF_URL, BookOrbitClient.normalizeServerUrl(v));
        else if (purpose == KEYBOARD_USER) e.putString(BookOrbitSync.PREF_USER, v);
        else if (purpose == KEYBOARD_PASS) e.putString(BookOrbitSync.PREF_PASS, v);
        e.commit();
        bookSources = null; // different server/login → different libraries
        podcastRows = null;
    }

    /** 2026-10-21 — Keyboard title per purpose (MainActivity keyboard header). */
    public static int keyboardTitleRes(int purpose) {
        if (purpose == KEYBOARD_URL) return R.string.navidrome_settings_url;
        if (purpose == KEYBOARD_USER) return R.string.navidrome_settings_user;
        if (purpose == KEYBOARD_SEARCH) return R.string.bookorbit_search;
        return R.string.navidrome_settings_pass;
    }

    /** 2026-10-21 — Log in and count libraries/collections on a background thread; toast the result. */
    private void testConnection() {
        final SharedPreferences p = actions.prefs();
        final Context ctx = actions.activity();
        if (!BookOrbitSync.isConfigured(p)) {
            Toast.makeText(ctx, R.string.navidrome_test_fail, Toast.LENGTH_LONG).show();
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    BookOrbitClient c = BookOrbitSync.client(p);
                    msg = ctx.getString(R.string.bookorbit_test_ok, c.bookLibraries().length(), c.podcastLibraries().length());
                } catch (Exception e) {
                    msg = e.getMessage() != null ? e.getMessage() : ctx.getString(R.string.navidrome_test_fail);
                }
                final String text = msg;
                main.post(new Runnable() {
                    @Override public void run() { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show(); }
                });
            }
        }, "BookOrbitTest").start();
    }

    private void syncNow() {
        if (!BookOrbitSync.isConfigured(actions.prefs())) {
            Toast.makeText(actions.activity(), R.string.navidrome_test_fail, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(actions.activity(), R.string.server_sync_started, Toast.LENGTH_SHORT).show();
        BookOrbitSync.runAsync(actions.activity(), actions.prefs(), new BookOrbitSync.Listener() {
            @Override public void onStatus(String text) {
                actions.refreshSettingsPreview(RowKeys.BOOKORBIT_SYNC_NOW);
            }
            @Override public void onDone(BookOrbitSync.Result result) {
                actions.refreshSettingsPreview(RowKeys.BOOKORBIT_SYNC_NOW);
                Toast.makeText(actions.activity(), actions.activity().getString(
                        R.string.server_sync_done, result.summary()), Toast.LENGTH_LONG).show();
                actions.onServerSyncFinished();
            }
            @Override public void onError(String message) {
                actions.refreshSettingsPreview(RowKeys.BOOKORBIT_SYNC_NOW);
                Toast.makeText(actions.activity(), message, Toast.LENGTH_LONG).show();
            }
        });
        actions.refreshSettingsPreview(RowKeys.BOOKORBIT_SYNC_NOW);
    }

    /** 2026-10-21 — Right-hand preview text for each BookOrbit row. */
    public static String previewValue(Context ctx, SharedPreferences prefs, String rowKey) {
        if (prefs == null || rowKey == null) return "";
        if (RowKeys.BOOKORBIT_URL.equals(rowKey)) return orDash(prefs.getString(BookOrbitSync.PREF_URL, ""));
        if (RowKeys.BOOKORBIT_USER.equals(rowKey)) return orDash(prefs.getString(BookOrbitSync.PREF_USER, ""));
        if (RowKeys.BOOKORBIT_PASS.equals(rowKey)) {
            String p = prefs.getString(BookOrbitSync.PREF_PASS, "");
            return p != null && !p.isEmpty() ? "••••" : "-";
        }
        if (RowKeys.BOOKORBIT_BOOKS.equals(rowKey) || RowKeys.BOOKORBIT_PICKED.equals(rowKey)) {
            int n = BookOrbitSync.count(prefs, BookOrbitSync.BOOK) + BookOrbitSync.count(prefs, BookOrbitSync.COL);
            return n == 0 ? "-" : ctx != null ? ctx.getString(R.string.bookorbit_sources_selected, n) : String.valueOf(n);
        }
        if (RowKeys.BOOKORBIT_PODCASTS.equals(rowKey)) {
            int n = BookOrbitSync.count(prefs, BookOrbitSync.POD);
            return n == 0 ? "-" : ctx != null ? ctx.getString(R.string.bookorbit_sources_selected, n) : String.valueOf(n);
        }
        if (RowKeys.BOOKORBIT_EPISODES.equals(rowKey)) {
            return String.valueOf(prefs.getInt(BookOrbitSync.PREF_EPISODES, BookOrbitSync.DEFAULT_EPISODES));
        }
        if (rowKey.startsWith(RowKeys.BOOKORBIT_SOURCE_PREFIX)) {
            boolean on = BookOrbitSync.isSourceOn(prefs, rowKey.substring(RowKeys.BOOKORBIT_SOURCE_PREFIX.length()));
            return ctx != null ? ctx.getString(on ? R.string.common_on : R.string.common_off) : (on ? "On" : "Off");
        }
        if (RowKeys.BOOKORBIT_SYNC_AUTO.equals(rowKey)) {
            boolean on = prefs.getBoolean(BookOrbitSync.PREF_AUTO, false);
            return ctx != null ? ctx.getString(on ? R.string.common_on : R.string.common_off) : (on ? "On" : "Off");
        }
        if (RowKeys.BOOKORBIT_SYNC_NOW.equals(rowKey)) return orDash(BookOrbitSync.statusLine(prefs));
        return "";
    }

    private static String orDash(String s) {
        return s != null && !s.isEmpty() ? s : "-";
    }
}
